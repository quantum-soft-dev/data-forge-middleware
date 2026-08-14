package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.TableChangeStats;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.infrastructure.S3ChangelogSegmentStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Persists a session's accepted change records as an immutable changelog segment
 * (Delta Client v2 — 022): serialize → object storage (bronze) → metadata row.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class ChangelogSegmentService {

    private final S3ChangelogSegmentStorage storage;
    private final ChangelogSegmentRepository repository;

    public ChangelogSegmentService(S3ChangelogSegmentStorage storage, ChangelogSegmentRepository repository) {
        this.storage = storage;
        this.repository = repository;
    }

    /**
     * Persist the accepted records of a session as a changelog segment.
     *
     * @param siteId   site identifier
     * @param batchId  batch (session) identifier
     * @param mode     session mode (DELTA | FULL_SNAPSHOT)
     * @param firstSeq first sequence of the session
     * @param records  accepted change records (in sequence order)
     * @return the persisted segment metadata
     */
    @Transactional
    public ChangelogSegment persist(UUID siteId, UUID batchId, String mode, long firstSeq, List<ChangeRecord> records) {
        return persist(siteId, batchId, mode, firstSeq, records, false);
    }

    /**
     * Persist a mid-snapshot seal of a re-baseline session (033). The segment is durable but
     * {@code provisional}: invisible to the checkpoint fold, the delta-Parquet egress queue and the
     * Bit BI SQL queue until {@code SessionEnd} publishes the whole batch. This is what lets a
     * snapshot larger than the session buffer stream at all, without exposing a half-replaced
     * dataset to readers.
     *
     * @return the persisted, still-invisible segment metadata
     */
    @Transactional
    public ChangelogSegment persistProvisional(UUID siteId, UUID batchId, String mode, long firstSeq,
                                               List<ChangeRecord> records) {
        return persist(siteId, batchId, mode, firstSeq, records, true);
    }

    private ChangelogSegment persist(UUID siteId, UUID batchId, String mode, long firstSeq,
                                     List<ChangeRecord> records, boolean provisional) {
        byte[] content = ChangelogCodec.serialize(records);
        String contentHash = sha256Hex(content);
        // Segment id is minted before the upload so the storage key carries the segment's own
        // identity — a session's batch owns many segments (029), a batch-derived key would collide.
        UUID segmentId = UUID.randomUUID();
        String s3Key = storage.uploadSegment(siteId, segmentId, content);

        long lastSeq = records.isEmpty() ? firstSeq - 1 : records.get(records.size() - 1).getSeq();
        Map<String, TableChangeStats> stats = ChangeRecordStats.computeByTable(records);

        ChangelogSegment segment = ChangelogSegment.create(
                segmentId, siteId, batchId, firstSeq, lastSeq, records.size(), contentHash, s3Key, mode, stats);
        if (provisional) {
            segment.markProvisional();
        }
        return repository.save(segment);
    }

    /**
     * Publish a completed re-baseline: clear {@code provisional} on every segment of the snapshot's
     * batch, so the fold and both work queues see the whole new baseline at once (033). Runs inside
     * the commit transaction, right after the previous baseline is discarded.
     *
     * @param batchId the snapshot session's batch
     * @return number of segments published
     */
    @Transactional
    public int publishProvisional(UUID batchId) {
        return repository.flipProvisionalByBatchId(batchId);
    }

    /**
     * Move a session's provisional segments onto a different batch (033 review) — used when a resume
     * runs under a replacement batch, so publication (which is batch-keyed) still covers them.
     *
     * @param fromBatchId batch the segments were sealed under
     * @param toBatchId   batch the resumed session now owns
     * @return number of segments moved
     */
    @Transactional
    public int reassignProvisionalBatch(UUID fromBatchId, UUID toBatchId) {
        return repository.reassignProvisionalBatch(fromBatchId, toBatchId);
    }

    /**
     * Read back the change records of a persisted segment.
     *
     * @param s3Key segment key
     * @return the segment's change records in order
     */
    public List<ChangeRecord> readRecords(String s3Key) {
        return ChangelogCodec.parse(storage.download(s3Key));
    }

    /**
     * Stream a raw segment in wire order without retaining its records. The stream is closed here
     * rather than inside the codec: a truncated or corrupt object makes {@code GZIPInputStream}
     * throw while reading the header, and the wrapper that would have closed it is never created.
     */
    public void forEachRecord(String s3Key, Consumer<ChangeRecord> consumer) {
        ReplayPhaseClock clock = ReplayPhaseClock.current();
        long openedAt = System.nanoTime();
        try (InputStream content = storage.open(s3Key)) {
            if (clock != null) {
                clock.addDownload(System.nanoTime() - openedAt);
            }
            replay(content, consumer, clock);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to close changelog segment " + s3Key, e);
        }
    }

    /**
     * Stream records from an already-open segment. When {@code clock} is present, stream
     * {@code read} time is download and parse-minus-consumer is decode.
     */
    static void replay(InputStream content, Consumer<ChangeRecord> consumer, ReplayPhaseClock clock) {
        if (clock == null) {
            ChangelogCodec.forEach(content, consumer);
            return;
        }
        TimingInputStream timed = content instanceof TimingInputStream already
                ? already
                : new TimingInputStream(content);
        long wallStarted = System.nanoTime();
        long[] consumerNanos = {0L};
        try {
            ChangelogCodec.forEach(timed, record -> {
                long consumeStarted = System.nanoTime();
                try {
                    consumer.accept(record);
                } finally {
                    consumerNanos[0] += System.nanoTime() - consumeStarted;
                }
            });
        } finally {
            clock.addDownload(timed.readNanos());
            clock.addDecode(System.nanoTime() - wallStarted - timed.readNanos() - consumerNanos[0]);
        }
    }

    /**
     * The most recent segments of a site, newest first (Delta Sync UI, B6 — admin surface).
     *
     * @param siteId site identifier
     * @param limit  maximum segments to return
     * @return up to {@code limit} segments ordered by createdAt desc
     */
    @Transactional(readOnly = true)
    public List<ChangelogSegment> listRecentSegments(UUID siteId, int limit) {
        return repository.findRecentBySiteId(siteId, limit);
    }

    /**
     * Delete all changelog segments (object storage + metadata) for a batch. Called before a batch is
     * removed by retention/admin so the {@code changelog_segments.batch_id} foreign key does not block
     * the delete and no S3 segment object is orphaned.
     *
     * @param batchId batch identifier
     */
    @Transactional
    public void deleteByBatchId(UUID batchId) {
        deleteMetadataByBatchId(batchId).forEach(storage::delete);
    }

    /**
     * Remove only segment metadata and return the object keys. A wider batch transaction uses this
     * variant so it can defer irreversible object deletion until the database commit succeeds.
     */
    @Transactional
    public List<String> deleteMetadataByBatchId(UUID batchId) {
        List<String> keys = new ArrayList<>();
        for (ChangelogSegment segment : repository.findByBatchId(batchId)) {
            keys.add(segment.getS3Key());
            repository.deleteById(segment.getId());
        }
        return keys;
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
