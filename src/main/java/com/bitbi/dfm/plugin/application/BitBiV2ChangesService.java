package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.delta.application.BatchParquetFinalizationService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.plugin.domain.BitBiChangesCursor;
import com.bitbi.dfm.plugin.domain.BitBiFileId;
import com.bitbi.dfm.plugin.infrastructure.BitBiChangesFeedDao;
import com.bitbi.dfm.plugin.infrastructure.BitBiChangesFeedDao.FeedBatch;
import com.bitbi.dfm.plugin.infrastructure.BitBiChangesFeedDao.FeedTable;
import com.bitbi.dfm.site.application.SiteService;
import com.bitbi.dfm.site.domain.SiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The changes feed of the Bit BI v2 surface (issue #388; {@code docs/cr-bitbi-parquet-plugin.md},
 * phase 2): {@code GET /v2/sites/{siteId}/changes}. A row is a <b>batch</b> — one Delta session —
 * with the manifest of every table it touched, never a file.
 *
 * <p><b>Only the current epoch.</b> Batches of a discarded history are never listed; a consumer
 * whose watermark belongs to one is answered from the start of the current epoch, whose first
 * batch is the {@code full_snapshot} that opened it.</p>
 *
 * <p><b>{@code afterSeq} is read within the consumer's epoch.</b> A wipe restarts seq at 1, and a
 * client may resend a snapshot from seq 1, so an {@code afterSeq} of a discarded epoch would skip
 * the new epoch's {@code full_snapshot}. The consumer therefore sends the epoch its watermark
 * belongs to: {@code afterSeq} filters only when that is the current epoch, and is refused without
 * it. A batch whose {@code lastSeq} is above {@code afterSeq} is listed whole — a {@code CONTINUOUS}
 * batch can straddle the checkpoint, and the consumer takes its rows with {@code _seq} above its
 * position.</p>
 *
 * <p>The epoch and the page are read in one {@code REPEATABLE READ} transaction, so the epoch the
 * page is compared against is the one its batches were selected by.</p>
 */
@Service
public class BitBiV2ChangesService {

    /** Batches per page unless the consumer asks otherwise. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /** The largest page; a batch carries up to one manifest entry per table of the site. */
    public static final int MAX_PAGE_SIZE = 200;

    private final SiteRepository siteRepository;
    private final DeltaSyncStateService syncStateService;
    private final BitBiChangesFeedDao feedDao;
    private final BatchParquetFinalizationService finalizationService;

    public BitBiV2ChangesService(SiteRepository siteRepository, DeltaSyncStateService syncStateService,
                                 BitBiChangesFeedDao feedDao, BatchParquetFinalizationService finalizationService) {
        this.siteRepository = siteRepository;
        this.syncStateService = syncStateService;
        this.feedDao = feedDao;
        this.finalizationService = finalizationService;
    }

    /**
     * One page of the feed.
     *
     * @param accountId the account the API key belongs to
     * @param siteId    the site
     * @param epoch     the epoch the consumer's watermark belongs to; required with {@code afterSeq > 0}
     * @param afterSeq  the consumer's seq watermark within {@code epoch}; 0 = from the epoch's start
     * @param cursor    the previous page's {@code nextCursor}; null for the first page
     * @param size      batches per page, 1 to {@link #MAX_PAGE_SIZE}
     * @return the page
     * @throws SiteService.SiteNotFoundException when the site does not exist or is another account's
     * @throws IllegalArgumentException          on a parameter out of range, {@code afterSeq} without
     *                                           {@code epoch}, or a cursor this server did not issue
     *                                           for this site
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ChangesPage changes(UUID accountId, UUID siteId, Long epoch, long afterSeq, String cursor, int size) {
        siteRepository.findByIdAndAccountId(siteId, accountId)
                .orElseThrow(() -> new SiteService.SiteNotFoundException("Site not found: " + siteId));
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid page size: 1 <= size <= " + MAX_PAGE_SIZE);
        }
        if (afterSeq < 0) {
            throw new IllegalArgumentException("afterSeq must not be negative");
        }
        if (epoch != null && epoch < 0) {
            throw new IllegalArgumentException("epoch must not be negative");
        }
        if (afterSeq > 0 && epoch == null) {
            throw new IllegalArgumentException("afterSeq is read within an epoch: pass the epoch your watermark "
                    + "belongs to");
        }
        BitBiChangesCursor position = null;
        if (cursor != null) {
            position = BitBiChangesCursor.decode(cursor)
                    .filter(c -> c.siteId().equals(siteId))
                    .orElseThrow(() -> new IllegalArgumentException("Invalid cursor"));
        }

        long current = syncStateService.getSyncState(siteId).baselineEpoch();
        long effectiveAfterSeq = epoch != null && epoch == current ? afterSeq : 0L;
        LocalDateTime horizon = LocalDateTime.now(ZoneOffset.UTC).minus(finalizationService.enqueueHorizon());
        List<FeedBatch> read = feedDao.findPage(siteId, current, effectiveAfterSeq,
                position == null ? null : position.startedAt(), position == null ? null : position.batchId(),
                horizon, size + 1);

        boolean more = read.size() > size;
        List<FeedBatch> kept = more ? read.subList(0, size) : read;
        List<ChangesBatch> batches = new ArrayList<>(kept.size());
        for (FeedBatch batch : kept) {
            batches.add(toBatch(siteId, current, batch));
        }
        String nextCursor = null;
        if (more) {
            FeedBatch last = kept.getLast();
            nextCursor = new BitBiChangesCursor(siteId, last.startedAt(), last.batchId()).encode();
        }
        return new ChangesPage(siteId, current, List.copyOf(batches), nextCursor);
    }

    /**
     * The object key behind a completed-batch {@code fileId}, when it may be handed out: the site
     * is the account's, the batch is the site's and in its current epoch, the table's artifact is
     * {@code READY}.
     *
     * @param accountId the account the API key belongs to
     * @param id        the decoded id
     * @return the key, or empty for every reason it may not be resolved
     */
    @Transactional(readOnly = true)
    public Optional<String> readyBatchFileKey(UUID accountId, BitBiFileId.BatchFile id) {
        return feedDao.findReadyKey(accountId, id.siteId(), id.batchId(), id.table());
    }

    private static ChangesBatch toBatch(UUID siteId, long epoch, FeedBatch batch) {
        List<ChangesTable> tables = new ArrayList<>(batch.tables().size());
        LocalDateTime settled = null;
        for (FeedTable table : batch.tables()) {
            ManifestStatus status = ManifestStatus.of(table.artifactStatus());
            LocalDateTime at = switch (status) {
                case READY -> table.readyAt();
                case ABANDONED -> table.updatedAt();
                case MISSING -> null;
            };
            if (at != null && (settled == null || at.isAfter(settled))) {
                settled = at;
            }
            tables.add(new ChangesTable(table.table(), status,
                    status == ManifestStatus.READY
                            ? new BitBiFileId.BatchFile(siteId, batch.batchId(), table.table()).encode()
                            : null,
                    status == ManifestStatus.READY && table.rowCount() != null ? table.rowCount() : table.statRows()));
        }
        LocalDateTime readyAt = settled != null ? settled : batch.completedAt();
        return new ChangesBatch(epoch, batch.batchId(),
                batch.sessionMode() == null ? null : batch.sessionMode().toLowerCase(Locale.ROOT),
                batch.firstSeq(), batch.lastSeq(),
                readyAt == null ? null : readyAt.toInstant(ZoneOffset.UTC), List.copyOf(tables));
    }

    /**
     * A page of the feed.
     *
     * @param siteId     the site
     * @param epoch      the site's current epoch — the one every listed batch belongs to
     * @param batches    in batch order
     * @param nextCursor pass it as {@code cursor} for the next page; null when the feed ends here for now
     */
    public record ChangesPage(UUID siteId, long epoch, List<ChangesBatch> batches, String nextCursor) {
    }

    /**
     * One batch.
     *
     * @param epoch       the epoch it belongs to
     * @param batchId     the batch (= one Delta session)
     * @param sessionMode {@code full_snapshot}, {@code delta} or {@code continuous}; null when unrecorded
     * @param firstSeq    its lowest seq
     * @param lastSeq     its highest seq
     * @param readyAt     when its last table settled (published or abandoned); with no artifact, when
     *                    the session ended; null when neither is known
     * @param tables      every table it touched, sorted by name
     */
    public record ChangesBatch(long epoch, UUID batchId, String sessionMode, long firstSeq, long lastSeq,
                               Instant readyAt, List<ChangesTable> tables) {
    }

    /**
     * One table of a batch.
     *
     * @param table  the table
     * @param status whether its file exists
     * @param fileId the id for {@code /files/{fileId}} when {@link ManifestStatus#READY}; null otherwise
     * @param rows   the file's row count when ready, otherwise the batch's records of the table; null
     *               when neither is known
     */
    public record ChangesTable(String table, ManifestStatus status, String fileId, Long rows) {
    }

    /**
     * What a table of a listed batch has become.
     */
    public enum ManifestStatus {
        /** The file exists. */
        READY("ready"),
        /** The build gave up; an operator can requeue it. */
        ABANDONED("abandoned"),
        /** No file and no artifact row: no declared schema, or the batch was never queued. */
        MISSING("missing");

        private final String wireValue;

        ManifestStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        /**
         * @return the value written to the wire
         */
        public String wireValue() {
            return wireValue;
        }

        static ManifestStatus of(String artifactStatus) {
            if (artifactStatus == null) {
                return MISSING;
            }
            return switch (artifactStatus) {
                case "READY" -> READY;
                case "ABANDONED" -> ABANDONED;
                // A listed batch has no unfinished table: the feed ends before the first that has one,
                // and the page is one snapshot.
                default -> throw new IllegalStateException("A listed batch has an unfinished table: " + artifactStatus);
            };
        }
    }
}
