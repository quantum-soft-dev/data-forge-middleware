package com.bitbi.dfm.plugin.infrastructure;

import com.bitbi.dfm.plugin.application.ParquetExportFileService.FileType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * SQL-side catalog queries for the Parquet Export plugin listing (028, review P1).
 * <p>
 * Filtering (account scope, since, site, table) and keyset pagination all happen in PostgreSQL,
 * so a listing request reads at most {@code limit} rows regardless of account history size.
 * The per-table fan-out of delta segments uses {@code jsonb_object_keys(stats)}; the delta S3
 * key is computed in SQL with the exact {@code S3CheckpointStorage.deltaKey} format so the
 * keyset cursor {@code (produced_at, s3_key)} is stable and totally ordered across both sources.
 * </p>
 */
@Repository
public class ParquetExportCatalogDao {

    /** One catalog row; {@code seq} is set for checkpoints, {@code firstSeq/lastSeq} for deltas. */
    public record CatalogRow(UUID siteId, String siteDomain, String table, FileType type,
                             Long firstSeq, Long lastSeq, Long seq,
                             LocalDateTime producedAt, String s3Key) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public ParquetExportCatalogDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Delta Parquet candidates ordered by {@code (produced_at, s3_key)}, starting strictly after
     * the cursor position (when given), at most {@code limit} rows.
     */
    public List<CatalogRow> findDeltaFiles(UUID accountId, LocalDateTime since, UUID siteId,
                                           String table, LocalDateTime cursorAt, String cursorKey,
                                           int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT f.site_id, f.domain, f.table_name, f.first_seq, f.last_seq, f.produced_at, f.s3_key
                FROM (
                    SELECT s.site_id, st.domain, tbl.table_name, s.first_seq, s.last_seq,
                           s.egress_at AS produced_at,
                           'egress/' || s.site_id || '/' || tbl.table_name || '/delta/seq='
                               || lpad(s.first_seq::text, 19, '0') || '-'
                               || lpad(s.last_seq::text, 19, '0') || '.parquet' AS s3_key
                    FROM changelog_segments s
                    JOIN sites st ON st.id = s.site_id
                    CROSS JOIN LATERAL jsonb_object_keys(s.stats) AS tbl(table_name)
                    WHERE st.account_id = :accountId
                      AND s.egress_at IS NOT NULL
                      AND s.stats IS NOT NULL
                      AND s.egress_at > :since
                """);
        MapSqlParameterSource params = baseParams(accountId, since, limit);
        appendInnerFilters(sql, params, siteId, table, "s.site_id", "tbl.table_name");
        sql.append("                ) f\n");
        appendCursorAndOrder(sql, params, cursorAt, cursorKey, "f.produced_at", "f.s3_key");
        return jdbc.query(sql.toString(), params, (rs, i) -> new CatalogRow(
                rs.getObject("site_id", UUID.class), rs.getString("domain"), rs.getString("table_name"),
                FileType.DELTA, rs.getLong("first_seq"), rs.getLong("last_seq"), null,
                rs.getTimestamp("produced_at").toLocalDateTime(), rs.getString("s3_key")));
    }

    /**
     * Checkpoint Parquet candidates ordered by {@code (produced_at, s3_key)}, starting strictly
     * after the cursor position (when given), at most {@code limit} rows.
     */
    public List<CatalogRow> findCheckpointFiles(UUID accountId, LocalDateTime since, UUID siteId,
                                                String table, LocalDateTime cursorAt, String cursorKey,
                                                int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT f.site_id, f.domain, f.table_name, f.seq, f.produced_at, f.s3_key
                FROM (
                    SELECT c.site_id, st.domain, c.table_name, c.seq,
                           c.updated_at AS produced_at, c.s3_key_parquet AS s3_key
                    FROM checkpoints c
                    JOIN sites st ON st.id = c.site_id
                    WHERE st.account_id = :accountId
                      AND c.s3_key_parquet IS NOT NULL
                      AND c.updated_at > :since
                """);
        MapSqlParameterSource params = baseParams(accountId, since, limit);
        appendInnerFilters(sql, params, siteId, table, "c.site_id", "c.table_name");
        sql.append("                ) f\n");
        appendCursorAndOrder(sql, params, cursorAt, cursorKey, "f.produced_at", "f.s3_key");
        return jdbc.query(sql.toString(), params, (rs, i) -> new CatalogRow(
                rs.getObject("site_id", UUID.class), rs.getString("domain"), rs.getString("table_name"),
                FileType.CHECKPOINT, null, null, rs.getLong("seq"),
                rs.getTimestamp("produced_at").toLocalDateTime(), rs.getString("s3_key")));
    }

    private static MapSqlParameterSource baseParams(UUID accountId, LocalDateTime since, int limit) {
        // LocalDateTime binds directly via JDBC 4.2 — no Timestamp.valueOf, which would
        // interpret the wall-clock value in the JVM default zone.
        return new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("since", since)
                .addValue("limit", limit);
    }

    private static void appendInnerFilters(StringBuilder sql, MapSqlParameterSource params,
                                           UUID siteId, String table,
                                           String siteColumn, String tableColumn) {
        if (siteId != null) {
            sql.append("                      AND ").append(siteColumn).append(" = :siteId\n");
            params.addValue("siteId", siteId);
        }
        if (table != null) {
            sql.append("                      AND ").append(tableColumn).append(" = :tableName\n");
            params.addValue("tableName", table);
        }
    }

    private static void appendCursorAndOrder(StringBuilder sql, MapSqlParameterSource params,
                                             LocalDateTime cursorAt, String cursorKey,
                                             String atColumn, String keyColumn) {
        if (cursorAt != null) {
            sql.append("                WHERE (").append(atColumn).append(" > :cursorAt OR (")
                    .append(atColumn).append(" = :cursorAt AND ").append(keyColumn)
                    .append(" > :cursorKey))\n");
            params.addValue("cursorAt", cursorAt);
            params.addValue("cursorKey", cursorKey == null ? "" : cursorKey);
        }
        sql.append("                ORDER BY ").append(atColumn).append(", ").append(keyColumn)
                .append("\n                LIMIT :limit");
    }
}
