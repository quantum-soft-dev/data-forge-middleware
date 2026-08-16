package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage.ObjectPresence;
import com.bitbi.dfm.plugin.application.ParquetExportCatalogQuery.CatalogPage;
import com.bitbi.dfm.plugin.infrastructure.ParquetExportCatalogDao;
import com.bitbi.dfm.plugin.infrastructure.ParquetExportCatalogDao.CatalogRow;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
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

    private final ParquetExportCatalogQuery catalogQuery;
    private final S3CheckpointStorage checkpointStorage;

    public ParquetExportFileService(ParquetExportCatalogQuery catalogQuery,
                                    S3CheckpointStorage checkpointStorage) {
        this.catalogQuery = catalogQuery;
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
    public FileListing listFiles(UUID accountId, LocalDateTime since, UUID siteId, String table,
                                 FileType type, String cursor, int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid page size: 1 <= size <= " + MAX_PAGE_SIZE);
        }
        CatalogPage page = catalogQuery.load(accountId, since, siteId, table, type, cursor, size);

        // Existence is probed only for the served page (<= size S3 HEADs), and only after the
        // catalog transaction has closed (issue #164 / #176). Dropped candidates (egress
        // skipped the table) still advanced the cursor inside load — no dead links, no loss.
        //
        // Only a *known* absence drops a row (issue #157). S3 answering "you may not look" is not
        // evidence that egress skipped the table, and hiding the file would turn a transient read
        // denial into a listing that silently lost entries — the client's own download would have
        // reported the denial for what it is.
        List<ParquetFileItem> files = new ArrayList<>(page.candidates().size());
        for (CatalogRow row : page.candidates()) {
            if (row.type() == FileType.DELTA
                    && checkpointStorage.deltaPresence(row.siteId(), row.table(),
                            row.firstSeq(), row.lastSeq()) == ObjectPresence.ABSENT) {
                continue;
            }
            files.add(toItem(row));
        }
        return new FileListing(files, size, page.hasMore(), page.nextCursor());
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
}
