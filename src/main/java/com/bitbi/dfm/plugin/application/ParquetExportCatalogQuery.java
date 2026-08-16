package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.application.ParquetExportFileService.FileType;
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
 * Transactional catalog page for the Parquet Export listing (issue #164 / #176).
 *
 * <p>The database work — up to three source queries, the merge and the keyset cursor —
 * finishes here. {@link ParquetExportFileService#listFiles} probes S3 only after this
 * method returns, so a 100-row {@code type=delta} page cannot pin a HikariCP connection
 * across up to 200 sequential S3 calls.</p>
 */
@Service
public class ParquetExportCatalogQuery {

    record CatalogPage(List<CatalogRow> candidates, boolean hasMore, String nextCursor) {
    }

    private final ParquetExportCatalogDao catalogDao;

    public ParquetExportCatalogQuery(ParquetExportCatalogDao catalogDao) {
        this.catalogDao = catalogDao;
    }

    /**
     * Load one catalog page (size + 1 for hasMore) and encode the cursor from the last
     * candidate, including rows that the subsequent S3 probe may drop.
     */
    @Transactional(readOnly = true)
    public CatalogPage load(UUID accountId, LocalDateTime since, UUID siteId, String table,
                            FileType type, String cursor, int size) {
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
        return new CatalogPage(List.copyOf(pageCandidates), hasMore, nextCursor);
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
