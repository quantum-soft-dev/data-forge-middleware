package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Derives the Parquet file catalog served by the Parquet Export plugin (028).
 * <p>
 * No file bookkeeping table exists: delta files are derived from egressed changelog segments
 * (key = {@link S3CheckpointStorage#deltaKey}), checkpoint files from {@link Checkpoint} rows
 * with a Parquet key — exactly the same derivation the owner UI download endpoints use (025),
 * so there is a single source of truth. Only sites of the given account are ever visible.
 * </p>
 */
@Service
public class ParquetExportFileService {

    public static final int MAX_PAGE_SIZE = 100;

    public enum FileType { DELTA, CHECKPOINT }

    /** One downloadable Parquet file. Delta files carry a seq range, checkpoints a single seq. */
    public record ParquetFileItem(UUID siteId, String siteDomain, String table, FileType type,
                                  Long firstSeq, Long lastSeq, Long seq,
                                  LocalDateTime producedAt, String fileName, String s3Key) {
    }

    public record FileListing(List<ParquetFileItem> files, int page, int size, boolean hasMore) {
    }

    private final SiteRepository siteRepository;
    private final ChangelogSegmentRepository segmentRepository;
    private final CheckpointRepository checkpointRepository;
    private final S3CheckpointStorage checkpointStorage;

    public ParquetExportFileService(SiteRepository siteRepository,
                                    ChangelogSegmentRepository segmentRepository,
                                    CheckpointRepository checkpointRepository,
                                    S3CheckpointStorage checkpointStorage) {
        this.siteRepository = siteRepository;
        this.segmentRepository = segmentRepository;
        this.checkpointRepository = checkpointRepository;
        this.checkpointStorage = checkpointStorage;
    }

    /**
     * List Parquet files produced after {@code since} for the account's sites.
     *
     * @param accountId  authenticated account (scoping boundary)
     * @param since      strictly-greater producedAt bound (delta: egress_at, checkpoint: updated_at)
     * @param siteId     optional site filter; a site not owned by the account yields an empty result
     * @param table      optional table-name filter
     * @param type       optional file-type filter; null = both
     * @param page       0-based page index
     * @param size       page size, capped at {@value #MAX_PAGE_SIZE}
     */
    @Transactional(readOnly = true)
    public FileListing listFiles(UUID accountId, LocalDateTime since, UUID siteId, String table,
                                 FileType type, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Invalid pagination: page >= 0, 1 <= size <= " + MAX_PAGE_SIZE);
        }

        List<Site> sites = siteRepository.findByAccountId(accountId).stream()
                .filter(site -> siteId == null || site.getId().equals(siteId))
                .toList();
        if (sites.isEmpty()) {
            return new FileListing(List.of(), page, size, false);
        }

        List<ParquetFileItem> candidates = new ArrayList<>();
        for (Site site : sites) {
            if (type != FileType.CHECKPOINT) {
                collectDeltaCandidates(site, since, table, candidates);
            }
            if (type != FileType.DELTA) {
                collectCheckpointCandidates(site, since, table, candidates);
            }
        }

        candidates.sort(Comparator.comparing(ParquetFileItem::producedAt)
                .thenComparing(ParquetFileItem::s3Key));

        int from = Math.min(page * size, candidates.size());
        int to = Math.min(from + size, candidates.size());
        boolean hasMore = candidates.size() > to;

        // Existence is probed only for the served page (<= size S3 HEADs per call): tables the
        // egress skipped (no declared schema / poison) have no Parquet and must not yield links.
        List<ParquetFileItem> files = candidates.subList(from, to).stream()
                .filter(item -> item.type() != FileType.DELTA
                        || checkpointStorage.deltaExists(item.siteId(), item.table(),
                                item.firstSeq(), item.lastSeq()))
                .toList();

        return new FileListing(files, page, size, hasMore);
    }

    private void collectDeltaCandidates(Site site, LocalDateTime since, String table,
                                        List<ParquetFileItem> candidates) {
        for (ChangelogSegment segment : segmentRepository.findEgressedSince(site.getId(), since)) {
            if (segment.getStats() == null) {
                continue; // pre-stats segments predate Parquet egress — no per-table info
            }
            for (String segmentTable : segment.getStats().keySet()) {
                if (table != null && !table.equals(segmentTable)) {
                    continue;
                }
                candidates.add(new ParquetFileItem(
                        site.getId(), site.getDomain(), segmentTable, FileType.DELTA,
                        segment.getFirstSeq(), segment.getLastSeq(), null,
                        segment.getEgressAt(),
                        "%s_seq%d-%d.parquet".formatted(segmentTable, segment.getFirstSeq(), segment.getLastSeq()),
                        S3CheckpointStorage.deltaKey(site.getId(), segmentTable,
                                segment.getFirstSeq(), segment.getLastSeq())));
            }
        }
    }

    private void collectCheckpointCandidates(Site site, LocalDateTime since, String table,
                                             List<ParquetFileItem> candidates) {
        for (Checkpoint checkpoint : checkpointRepository.findBySiteId(site.getId())) {
            if (checkpoint.getS3KeyParquet() == null
                    || !checkpoint.getUpdatedAt().isAfter(since)
                    || (table != null && !table.equals(checkpoint.getTableName()))) {
                continue;
            }
            candidates.add(new ParquetFileItem(
                    site.getId(), site.getDomain(), checkpoint.getTableName(), FileType.CHECKPOINT,
                    null, null, checkpoint.getSeq(),
                    checkpoint.getUpdatedAt(),
                    "%s_seq%d.parquet".formatted(checkpoint.getTableName(), checkpoint.getSeq()),
                    checkpoint.getS3KeyParquet()));
        }
    }
}
