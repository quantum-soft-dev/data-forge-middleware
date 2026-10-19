package com.bitbi.dfm.plugin.infrastructure;

import com.bitbi.dfm.delta.infrastructure.JpaBatchParquetArtifactRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL of the Bit BI v2 changes feed (issue #388): a site's batches of its current epoch, in batch
 * order, each with what every table it touched has become.
 *
 * <p><b>Which batches.</b> A batch of the site that is no longer {@code IN_PROGRESS} and recorded
 * at least one segment ({@code first_seq} set). That includes a session that ended
 * {@code NOT_COMPLETED}, {@code FAILED} or {@code CANCELLED} after committing seals: its records
 * are applied and checkpointed, so leaving it out would put a hole in the epoch's seq — it is
 * listed, and since nothing builds its Parquet, its tables read {@code missing}.</p>
 *
 * <p><b>Which epoch</b> — {@link #inEpoch}: the batch's stamped {@code baseline_epoch} (V61); for
 * an unstamped one, the rule V61 backfilled by — no completed {@code FULL_SNAPSHOT} of its site
 * started after it. Every committed FULL_SNAPSHOT runs the re-baseline reset, and a history wipe
 * deletes every batch, so that rule is exact for any batch whose epoch's snapshot still exists; it
 * reads an unstamped batch a pre-V61 pod committed during the rolling deploy correctly.</p>
 *
 * <p><b>Order and end.</b> {@code (started_at, id)} — the order #378 builds a site's batches in,
 * <em>not</em> {@code ready_at}. The feed ends before the first batch with a table still
 * {@code PENDING}/{@code BUILDING}/{@code FAILED}, or still owed its work rows
 * ({@link JpaBatchParquetArtifactRepository#AWAITING_ENQUEUE}, the #380 gate's own text and
 * horizon), so a consumer never sees batch N+1 before N is settled — and never reads a batch whose
 * {@code AFTER_COMMIT} enqueue has simply not run yet as all {@code missing}.</p>
 *
 * <p>One statement, so the batches, the end of the feed and every manifest come from one snapshot;
 * the caller runs it under {@code REPEATABLE READ} together with the epoch it compares against.</p>
 */
@Repository
public class BitBiChangesFeedDao {

    /**
     * Whether the batch aliased {@code b} belongs to the epoch the SQL expression {@code epoch}
     * names — see the class comment. Shared by the feed and by {@code /files} resolution, so a
     * fileId resolves exactly while the feed would list its batch.
     */
    static String inEpoch(String epoch) {
        return "(b.baseline_epoch = " + epoch + IN_EPOCH_REST;
    }

    private static final String IN_EPOCH_REST = """

                 OR (b.baseline_epoch IS NULL AND NOT EXISTS (
                         SELECT 1 FROM batches later
                         WHERE later.site_id = b.site_id
                           AND later.session_mode = 'FULL_SNAPSHOT'
                           AND later.status IN ('COMPLETED', 'COMPLETED_WITH_WARNINGS')
                           AND (later.started_at, later.id) > (b.started_at, b.id))))
            """;

    private static final String LISTED = """
            SELECT b.id, b.site_id, b.status, b.started_at, b.completed_at, b.session_mode,
                   b.first_seq, b.last_seq, b.table_stats
            FROM batches b
            WHERE b.site_id = :siteId
              AND b.status <> 'IN_PROGRESS'
              AND b.first_seq IS NOT NULL
              AND """ + inEpoch(":epoch");

    private final NamedParameterJdbcTemplate jdbc;

    public BitBiChangesFeedDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One page of the feed.
     *
     * @param siteId         the site
     * @param epoch          the site's current baseline epoch
     * @param afterSeq       only batches whose {@code last_seq} is above it; 0 = from the epoch's start
     * @param cursorAt       {@code started_at} of the last batch of the previous page; null = first page
     * @param cursorId       its id; null with {@code cursorAt}
     * @param enqueueHorizon the #380 horizon: a batch started earlier is not waited for
     * @param limit          batches to read at most
     * @return the batches, in feed order, each with its tables sorted by name
     */
    public List<FeedBatch> findPage(UUID siteId, long epoch, long afterSeq, LocalDateTime cursorAt,
                                    UUID cursorId, LocalDateTime enqueueHorizon, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("siteId", siteId)
                .addValue("epoch", epoch)
                .addValue("afterSeq", afterSeq)
                .addValue("enqueueHorizon", enqueueHorizon)
                .addValue("limit", limit);
        String cursorPredicate = "";
        if (cursorAt != null) {
            cursorPredicate = "  AND (l.started_at, l.id) > (CAST(:cursorAt AS timestamp), CAST(:cursorId AS uuid))\n";
            params.addValue("cursorAt", cursorAt).addValue("cursorId", cursorId);
        }
        String sql = "WITH listed AS (\n" + LISTED + "),\n"
                + """
                held AS (
                    SELECT owing.started_at, owing.id FROM listed owing
                    WHERE EXISTS (SELECT 1 FROM batch_parquet_artifacts unfinished
                                  WHERE unfinished.batch_id = owing.id
                                    AND unfinished.status IN ('PENDING', 'BUILDING', 'FAILED'))
                       OR (""" + JpaBatchParquetArtifactRepository.AWAITING_ENQUEUE + """
                          )
                    ORDER BY owing.started_at, owing.id
                    LIMIT 1
                ),
                page AS (
                    SELECT l.* FROM listed l
                    WHERE l.last_seq > :afterSeq
                """ + cursorPredicate + """
                      AND NOT EXISTS (SELECT 1 FROM held h WHERE (h.started_at, h.id) <= (l.started_at, l.id))
                    ORDER BY l.started_at, l.id
                    LIMIT :limit
                )
                SELECT p.id, p.status AS batch_status, p.started_at, p.completed_at, p.session_mode,
                       p.first_seq, p.last_seq, t.table_name,
                       CASE WHEN p.table_stats -> t.table_name IS NULL THEN NULL
                            ELSE COALESCE((p.table_stats -> t.table_name ->> 'inserts')::bigint, 0)
                               + COALESCE((p.table_stats -> t.table_name ->> 'updates')::bigint, 0)
                               + COALESCE((p.table_stats -> t.table_name ->> 'deletes')::bigint, 0)
                       END AS stat_rows,
                       a.status AS artifact_status, a.row_count, a.ready_at, a.updated_at AS artifact_updated_at
                FROM page p
                LEFT JOIN LATERAL (
                    SELECT k.table_name FROM jsonb_object_keys(COALESCE(p.table_stats, '{}'::jsonb)) AS k(table_name)
                    UNION
                    SELECT x.table_name FROM batch_parquet_artifacts x WHERE x.batch_id = p.id
                ) t ON TRUE
                LEFT JOIN batch_parquet_artifacts a ON a.batch_id = p.id AND a.table_name = t.table_name
                ORDER BY p.started_at, p.id, t.table_name
                """;
        Map<UUID, FeedBatch> batches = new LinkedHashMap<>();
        jdbc.query(sql, params, rs -> {
            UUID id = rs.getObject("id", UUID.class);
            String status = rs.getString("batch_status");
            LocalDateTime startedAt = rs.getObject("started_at", LocalDateTime.class);
            LocalDateTime completedAt = rs.getObject("completed_at", LocalDateTime.class);
            String sessionMode = rs.getString("session_mode");
            long firstSeq = rs.getLong("first_seq");
            long lastSeq = rs.getLong("last_seq");
            FeedBatch batch = batches.computeIfAbsent(id, ignored -> new FeedBatch(id, status, startedAt,
                    completedAt, sessionMode, firstSeq, lastSeq, new ArrayList<>()));
            String table = rs.getString("table_name");
            if (table != null) {
                batch.tables().add(new FeedTable(table, rs.getString("artifact_status"),
                        rs.getObject("row_count", Long.class), rs.getObject("stat_rows", Long.class),
                        rs.getObject("ready_at", LocalDateTime.class),
                        rs.getObject("artifact_updated_at", LocalDateTime.class)));
            }
        });
        return List.copyOf(batches.values());
    }

    /**
     * The object key of a {@code READY} completed-batch Parquet that {@code /files} may hand out:
     * the site is the account's, the batch is the site's and belongs to the site's current epoch.
     *
     * @return the key, or empty for every reason it may not be resolved
     */
    public Optional<String> findReadyKey(UUID accountId, UUID siteId, UUID batchId, String table) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("siteId", siteId)
                .addValue("batchId", batchId)
                .addValue("tableName", table);
        String sql = """
                SELECT a.s3_key
                FROM batch_parquet_artifacts a
                JOIN batches b ON b.id = a.batch_id
                JOIN sites st ON st.id = b.site_id
                JOIN site_sync_state s ON s.site_id = b.site_id
                WHERE st.account_id = :accountId
                  AND b.site_id = :siteId
                  AND a.site_id = :siteId
                  AND b.id = :batchId
                  AND a.table_name = :tableName
                  AND a.status = 'READY'
                  AND """ + inEpoch("s.baseline_epoch");
        return jdbc.query(sql, params, (rs, i) -> rs.getString("s3_key")).stream().findFirst();
    }

    /**
     * One listed batch.
     *
     * @param batchId     the batch
     * @param status      its {@code batches.status}
     * @param startedAt   its position in the feed, with {@code batchId}
     * @param completedAt when the session ended; may be null for a session the server ended
     * @param sessionMode {@code FULL_SNAPSHOT}/{@code DELTA}/{@code CONTINUOUS}; null before V47
     * @param firstSeq    lowest recorded seq
     * @param lastSeq     highest recorded seq
     * @param tables      one entry per table in its stats or its artifact rows, sorted by name
     */
    public record FeedBatch(UUID batchId, String status, LocalDateTime startedAt, LocalDateTime completedAt,
                            String sessionMode, long firstSeq, long lastSeq, List<FeedTable> tables) {
    }

    /**
     * One table of a listed batch.
     *
     * @param table          the table
     * @param artifactStatus the artifact row's status; null when there is no row
     * @param rowCount       the artifact's row count; set when {@code READY}
     * @param statRows       inserts + updates + deletes from {@code batches.table_stats}; null when absent
     * @param readyAt        when the artifact was published; set when {@code READY}
     * @param updatedAt      the artifact row's last change; when it was abandoned, for {@code ABANDONED}
     */
    public record FeedTable(String table, String artifactStatus, Long rowCount, Long statRows,
                            LocalDateTime readyAt, LocalDateTime updatedAt) {
    }
}
