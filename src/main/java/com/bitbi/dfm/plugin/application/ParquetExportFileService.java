package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage.ObjectPresence;
import com.bitbi.dfm.plugin.infrastructure.ParquetExportCatalogDao;
import com.bitbi.dfm.plugin.infrastructure.ParquetExportCatalogDao.CatalogRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Serves the Parquet file catalog of the Parquet Export plugin (028).
 * <p>
 * Filtering and pagination are pushed into SQL ({@link ParquetExportCatalogDao}): a request
 * reads at most {@code size + 1} rows per source no matter how large the account history is.
 * Pagination is a <b>keyset cursor</b> over {@code (producedAt, s3Key)} — offset paging plus a
 * {@code since = max(producedAt)} client pattern silently skips files that share one
 * {@code producedAt} across a page boundary (one segment fans out to many tables with the same
 * {@code egress_at}), so the cursor is the only lossless way to walk the catalog.
 * </p>
 * <p>
 * The cursor also advances over candidates whose delta Parquet turned out not to exist
 * (schema-skipped/poison tables): {@code nextCursor} is taken from the last <i>candidate</i> of
 * the page window, not the last surviving file — a page may therefore contain fewer than
 * {@code size} entries (even zero) while {@code hasMore} is still {@code true}.
 * </p>
 */
@Service
public class ParquetExportFileService {

    public static final int MAX_PAGE_SIZE = 100;

    public enum FileType { DELTA, CHECKPOINT, BATCH }

    /** One catalogued Parquet file. Batch files carry batchId/status/artifactId. */
    public record ParquetFileItem(UUID siteId, String siteDomain, String table, FileType type,
                                  Long firstSeq, Long lastSeq, Long seq,
                                  LocalDateTime producedAt, String fileName, String s3Key,
                                  UUID batchId, String status, UUID artifactId) {
    }

    public record FileListing(List<ParquetFileItem> files, int size, boolean hasMore, String nextCursor) {
    }

    private final ParquetExportCatalogDao catalogDao;
    private final S3CheckpointStorage checkpointStorage;

    public ParquetExportFileService(ParquetExportCatalogDao catalogDao,
                                    S3CheckpointStorage checkpointStorage) {
        this.catalogDao = catalogDao;
        this.checkpointStorage = checkpointStorage;
    }

    /**
     * List Parquet files produced after {@code since} for the account's sites.
     *
     * @param accountId authenticated account (scoping happens in SQL via the sites join)
     * @param since     strictly-greater producedAt bound (delta: egress_at, checkpoint: updated_at)
     * @param siteId    optional site filter; a site not owned by the account yields an empty result
     * @param table     optional table-name filter
     * @param type      file-type filter; {@code null} defaults to {@link FileType#BATCH}
     * @param cursor    opaque keyset cursor from a previous response's {@code nextCursor}; null = start
     * @param size      page size, capped at {@value #MAX_PAGE_SIZE}
     */
    @Transactional(readOnly = true)
    public FileListing listFiles(UUID accountId, LocalDateTime since, UUID siteId, String table,
                                 FileType type, String cursor, int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid page size: 1 <= size <= " + MAX_PAGE_SIZE);
        }
        FileType effectiveType = type == null ? FileType.BATCH : type;
        Cursor position = Cursor.decode(cursor);
        LocalDateTime cursorAt = position == null ? null : position.producedAt();
        String cursorKey = position == null ? null : position.s3Key();
        int fetch = size + 1;

        List<CatalogRow> batch = effectiveType == FileType.BATCH
                ? catalogDao.findBatchFiles(accountId, since, siteId, table, cursorAt, cursorKey, fetch)
                : List.of();
        List<CatalogRow> delta = effectiveType == FileType.DELTA
                ? catalogDao.findDeltaFiles(accountId, since, siteId, table, cursorAt, cursorKey, fetch)
                : List.of();
        List<CatalogRow> checkpoints = effectiveType == FileType.CHECKPOINT
                ? catalogDao.findCheckpointFiles(accountId, since, siteId, table, cursorAt, cursorKey, fetch)
                : List.of();

        List<CatalogRow> merged = mergeSorted(mergeSorted(batch, delta, fetch), checkpoints, fetch);
        boolean hasMore = merged.size() > size;
        List<CatalogRow> pageCandidates = merged.subList(0, Math.min(size, merged.size()));
        String nextCursor = hasMore
                ? Cursor.encode(pageCandidates.get(pageCandidates.size() - 1))
                : null;

        // Existence is probed only for the served page (<= size S3 HEADs). Dropped candidates
        // (egress skipped the table) still advanced the cursor above — no dead links, no loss.
        //
        // Only a *known* absence drops a row (issue #157). S3 answering "you may not look" is not
        // evidence that egress skipped the table, and hiding the file would turn a transient read
        // denial into a listing that silently lost entries — the client's own download would have
        // reported the denial for what it is.
        List<ParquetFileItem> files = new ArrayList<>(pageCandidates.size());
        for (CatalogRow row : pageCandidates) {
            if (row.type() == FileType.DELTA
                    && checkpointStorage.deltaPresence(row.siteId(), row.table(),
                            row.firstSeq(), row.lastSeq()) == ObjectPresence.ABSENT) {
                continue;
            }
            files.add(toItem(row));
        }
        return new FileListing(files, size, hasMore, nextCursor);
    }

    private static List<CatalogRow> mergeSorted(List<CatalogRow> left, List<CatalogRow> right, int limit) {
        Comparator<CatalogRow> order = Comparator.comparing(CatalogRow::producedAt)
                .thenComparing(CatalogRow::s3Key);
        List<CatalogRow> merged = new ArrayList<>(Math.min(limit, left.size() + right.size()));
        int i = 0;
        int j = 0;
        while (merged.size() < limit && (i < left.size() || j < right.size())) {
            if (j >= right.size() || (i < left.size() && order.compare(left.get(i), right.get(j)) <= 0)) {
                merged.add(left.get(i++));
            } else {
                merged.add(right.get(j++));
            }
        }
        return merged;
    }

    private static ParquetFileItem toItem(CatalogRow row) {
        String fileName = switch (row.type()) {
            case DELTA -> "%s_seq%d-%d.parquet".formatted(row.table(), row.firstSeq(), row.lastSeq());
            case CHECKPOINT -> "%s_seq%d.parquet".formatted(row.table(), row.seq());
            case BATCH -> "%s_batch%s.parquet".formatted(row.table(), row.batchId());
        };
        String s3Key = "abandoned".equals(row.status()) ? null : row.s3Key();
        return new ParquetFileItem(row.siteId(), row.siteDomain(), row.table(), row.type(),
                row.firstSeq(), row.lastSeq(), row.seq(), row.producedAt(), fileName, s3Key,
                row.batchId(), row.status(), row.artifactId());
    }

    /** Opaque keyset position: base64url of {@code producedAt|s3Key}. */
    private record Cursor(LocalDateTime producedAt, String s3Key) {

        static String encode(CatalogRow row) {
            String raw = row.producedAt() + "|" + row.s3Key();
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                int separator = raw.indexOf('|');
                if (separator < 1) {
                    throw new IllegalArgumentException("missing separator");
                }
                return new Cursor(LocalDateTime.parse(raw.substring(0, separator)),
                        raw.substring(separator + 1));
            } catch (IllegalArgumentException | DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid 'cursor' value — use nextCursor from a previous response");
            }
        }
    }
}
