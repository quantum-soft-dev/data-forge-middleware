package com.bitbi.dfm.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V60 rebuilds the totals of a finished {@code FULL_SNAPSHOT} batch from its
 * {@code batch_parquet_artifacts} where V58 could only record the segments retention had left
 * (issue #349).
 * <p>
 * The migration runs against a database of its own, created in the shared PostgreSQL server and
 * dropped afterwards, because V60 is a statement over <em>every</em> batch: run against the shared
 * test database it would rewrite rows other classes own, and its row locks would hold up their
 * workers. The history is replayed the way production lived it — rows written at V57, segments
 * pruned by deleting their rows, then V58's backfill, then V60 — so the partial totals V60 repairs
 * are the ones V58 really writes rather than values this test invents.
 * </p>
 * <p>
 * "Untouched" is asserted on the row's {@code xmin}, not on its values: an UPDATE that wrote the
 * same values back still counts as touching it, and equal values could not tell the two apart.
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("V60 batch totals from Parquet artifacts (#349)")
class BatchTotalsFromArtifactsMigrationIntegrationTest {

    private static final String LOCATION = "classpath:db/migration";

    private final String database = "v60_migration_" + UUID.randomUUID().toString().replace("-", "");
    private final Map<UUID, String> xminAfterV58 = new HashMap<>();

    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String url;
    private String user;
    private String password;

    // Scenario batches, one site each so their segment sequences never collide.
    private final UUID partiallyPrunedSnapshot = UUID.randomUUID();
    private final UUID fullyPrunedSnapshot = UUID.randomUUID();
    private final UUID intactSnapshot = UUID.randomUUID();
    private final UUID partiallyPrunedDelta = UUID.randomUUID();
    private final UUID modelessBatch = UUID.randomUUID();
    private final UUID snapshotWithAbandoned = UUID.randomUUID();
    private final UUID runningSnapshot = UUID.randomUUID();
    private final UUID artifactsBelowSegments = UUID.randomUUID();

    private final UUID account = UUID.randomUUID();

    @BeforeAll
    void migrateSeedPruneAndMigrate() {
        connect();
        admin.execute("CREATE DATABASE " + database);
        jdbc = new JdbcTemplate(dataSource(jdbcUrlOfOwnDatabase()));

        migrateTo("57");
        seed();
        prune();
        migrateTo("58");
        for (UUID batch : List.of(partiallyPrunedSnapshot, fullyPrunedSnapshot, intactSnapshot,
                partiallyPrunedDelta, modelessBatch, snapshotWithAbandoned, runningSnapshot,
                artifactsBelowSegments)) {
            xminAfterV58.put(batch, xmin(batch));
        }
        migrateTo("60");
    }

    @AfterAll
    void dropDatabase() {
        if (admin != null) {
            admin.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    @Test
    @DisplayName("a snapshot whose segments were partly pruned gets its exact totals from the artifacts")
    void partiallyPrunedSnapshotIsRebuilt() {
        assertThat(totals(partiallyPrunedSnapshot)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "total_records", 160L,
                "table_count", 3L,
                "first_seq", 1L,
                "last_seq", 160L,
                "table_stats", stats(Map.of("t1", 60L, "t2", 60L, "t3", 40L))));
    }

    @Test
    @DisplayName("a snapshot with no segment left — which V58 left untracked — becomes tracked")
    void fullyPrunedSnapshotBecomesTracked() {
        assertThat(totals(fullyPrunedSnapshot)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "total_records", 12L,
                "table_count", 2L,
                "first_seq", 1L,
                "last_seq", 12L,
                "table_stats", stats(Map.of("t1", 5L, "t2", 7L))));
    }

    @Test
    @DisplayName("a snapshot whose totals are already complete is not written at all")
    void intactSnapshotIsUntouched() {
        assertThat(xmin(intactSnapshot)).isEqualTo(xminAfterV58.get(intactSnapshot));
        assertThat(totals(intactSnapshot)).containsEntry("total_records", 30L);
    }

    @Test
    @DisplayName("a DELTA batch keeps what V58 recorded — its artifacts cannot be split by operation")
    void deltaBatchIsUntouched() {
        assertThat(xmin(partiallyPrunedDelta)).isEqualTo(xminAfterV58.get(partiallyPrunedDelta));
        assertThat(totals(partiallyPrunedDelta)).containsEntry("total_records", 10L);
    }

    @Test
    @DisplayName("a batch with no recorded session mode is skipped — it is not proven a snapshot")
    void modelessBatchIsUntouched() {
        assertThat(xmin(modelessBatch)).isEqualTo(xminAfterV58.get(modelessBatch));
        assertThat(totals(modelessBatch)).containsEntry("total_records", 10L);
    }

    @Test
    @DisplayName("a table whose artifact did not finish keeps what its segments recorded; with none, it is absent")
    void abandonedArtifactsMergeWithSegmentsTableByTable() {
        assertThat(totals(snapshotWithAbandoned)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "total_records", 58L,
                "table_count", 2L,
                "first_seq", 1L,
                "last_seq", 78L,
                "table_stats", stats(Map.of("t1", 50L, "t2", 8L))));
    }

    @Test
    @DisplayName("a running snapshot is left to its segments")
    void runningSnapshotIsUntouched() {
        assertThat(xmin(runningSnapshot)).isEqualTo(xminAfterV58.get(runningSnapshot));
        assertThat(totals(runningSnapshot)).containsEntry("total_records", null);
    }

    @Test
    @DisplayName("artifacts that describe less than the segments never lower the totals")
    void totalsNeverDecrease() {
        assertThat(xmin(artifactsBelowSegments)).isEqualTo(xminAfterV58.get(artifactsBelowSegments));
        assertThat(totals(artifactsBelowSegments)).containsEntry("total_records", 20L);
    }

    // --- history --------------------------------------------------------------------------------

    private void seed() {
        jdbc.update("""
                INSERT INTO accounts (id, email, name, is_active, created_at, updated_at)
                VALUES (?, ?, 'V60 migration', true, CURRENT_TIMESTAMP AT TIME ZONE 'UTC',
                        CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
                """, account, "v60-" + account + "@example.com");

        // Segments t1:60 t2:40 | t2:20 t3:30 | t3:10 — the first two are pruned below.
        batch(partiallyPrunedSnapshot, "FULL_SNAPSHOT", "COMPLETED");
        segment(partiallyPrunedSnapshot, 1, 100, Map.of("t1", 60L, "t2", 40L));
        segment(partiallyPrunedSnapshot, 101, 150, Map.of("t2", 20L, "t3", 30L));
        segment(partiallyPrunedSnapshot, 151, 160, Map.of("t3", 10L));
        artifact(partiallyPrunedSnapshot, "t1", "READY", 60L, 1L, 60L);
        artifact(partiallyPrunedSnapshot, "t2", "READY", 60L, 1L, 120L);
        artifact(partiallyPrunedSnapshot, "t3", "READY", 40L, 101L, 160L);

        batch(fullyPrunedSnapshot, "FULL_SNAPSHOT", "COMPLETED");
        segment(fullyPrunedSnapshot, 1, 12, Map.of("t1", 5L, "t2", 7L));
        artifact(fullyPrunedSnapshot, "t1", "READY", 5L, 1L, 5L);
        artifact(fullyPrunedSnapshot, "t2", "READY", 7L, 6L, 12L);

        batch(intactSnapshot, "FULL_SNAPSHOT", "COMPLETED");
        segment(intactSnapshot, 1, 30, Map.of("t1", 30L));
        artifact(intactSnapshot, "t1", "READY", 30L, 1L, 30L);

        batch(partiallyPrunedDelta, "DELTA", "COMPLETED");
        segment(partiallyPrunedDelta, 1, 10, Map.of("t1", 10L));
        segment(partiallyPrunedDelta, 11, 20, Map.of("t1", 10L));
        artifact(partiallyPrunedDelta, "t1", "READY", 20L, 1L, 20L);

        batch(modelessBatch, null, "COMPLETED");
        segment(modelessBatch, 1, 10, Map.of("t1", 10L));
        segment(modelessBatch, 11, 20, Map.of("t1", 10L));
        artifact(modelessBatch, "t1", "READY", 20L, 1L, 20L);

        // t1 READY; t2 ABANDONED with its segment kept; t3 ABANDONED with its segment pruned.
        batch(snapshotWithAbandoned, "FULL_SNAPSHOT", "COMPLETED");
        segment(snapshotWithAbandoned, 1, 50, Map.of("t1", 50L));
        segment(snapshotWithAbandoned, 51, 70, Map.of("t3", 20L));
        segment(snapshotWithAbandoned, 71, 78, Map.of("t2", 8L));
        artifact(snapshotWithAbandoned, "t1", "READY", 50L, 1L, 50L);
        artifact(snapshotWithAbandoned, "t2", "ABANDONED", null, null, null);
        artifact(snapshotWithAbandoned, "t3", "ABANDONED", null, null, null);

        batch(runningSnapshot, "FULL_SNAPSHOT", "IN_PROGRESS");
        artifact(runningSnapshot, "t1", "READY", 5L, 1L, 5L);

        batch(artifactsBelowSegments, "FULL_SNAPSHOT", "COMPLETED");
        segment(artifactsBelowSegments, 1, 20, Map.of("t1", 20L));
        artifact(artifactsBelowSegments, "t1", "READY", 15L, 1L, 15L);
    }

    /** What changelog retention did before V58 ran: the rows of the oldest segments are gone. */
    private void prune() {
        deleteSegments(partiallyPrunedSnapshot, 1, 101);
        deleteSegments(fullyPrunedSnapshot, 1);
        deleteSegments(partiallyPrunedDelta, 1);
        deleteSegments(modelessBatch, 1);
        deleteSegments(snapshotWithAbandoned, 1, 51);
    }

    private void batch(UUID batch, String mode, String status) {
        UUID site = siteOf(batch);
        jdbc.update("""
                INSERT INTO sites (id, account_id, domain, display_name, is_active, created_at, updated_at,
                                   site_name, client_api_version)
                VALUES (?, ?, ?, 'V60 migration', true, CURRENT_TIMESTAMP AT TIME ZONE 'UTC',
                        CURRENT_TIMESTAMP AT TIME ZONE 'UTC', ?, 'V2')
                """, site, account, site + ".example.com", site + ".example.com");
        jdbc.update("""
                INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count,
                                     total_size, has_errors, started_at, created_at, completed_at, session_mode)
                VALUES (?, ?, ?, ?, 'v60/', 0, 0, false, CURRENT_TIMESTAMP AT TIME ZONE 'UTC',
                        CURRENT_TIMESTAMP AT TIME ZONE 'UTC', CURRENT_TIMESTAMP AT TIME ZONE 'UTC', ?)
                """, batch, account, site, status, mode);
    }

    private void segment(UUID batch, long firstSeq, long lastSeq, Map<String, Long> insertsByTable) {
        long records = insertsByTable.values().stream().mapToLong(Long::longValue).sum();
        jdbc.update("""
                INSERT INTO changelog_segments (id, site_id, batch_id, first_seq, last_seq, record_count,
                                                content_hash, s3_key, mode, provisional, stats)
                VALUES (?, ?, ?, ?, ?, ?, 'hash', ?, 'FULL_SNAPSHOT', FALSE, CAST(? AS jsonb))
                """, UUID.randomUUID(), siteOf(batch), batch, firstSeq, lastSeq, records,
                "delta/" + siteOf(batch) + "/segments/" + firstSeq + ".pb.gz", stats(insertsByTable));
    }

    private void artifact(UUID batch, String table, String status, Long rowCount, Long firstSeq, Long lastSeq) {
        boolean ready = "READY".equals(status);
        jdbc.update("""
                INSERT INTO batch_parquet_artifacts
                    (id, site_id, batch_id, table_name, status, s3_key, row_count, file_size, checksum,
                     attempt_count, created_at, updated_at, ready_at, version, first_seq, last_seq)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, CURRENT_TIMESTAMP AT TIME ZONE 'UTC',
                        CURRENT_TIMESTAMP AT TIME ZONE 'UTC',
                        CASE WHEN ? THEN CURRENT_TIMESTAMP AT TIME ZONE 'UTC' END, 0, ?, ?)
                """, UUID.randomUUID(), siteOf(batch), batch, table, status,
                ready ? "egress/%s/batches/%s/%s.parquet".formatted(siteOf(batch), batch, table) : null,
                rowCount, ready ? 1L : null, ready ? "sum" : null, ready, firstSeq, lastSeq);
    }

    private void deleteSegments(UUID batch, long... firstSeqs) {
        for (long firstSeq : firstSeqs) {
            int deleted = jdbc.update("DELETE FROM changelog_segments WHERE batch_id = ? AND first_seq = ?",
                    batch, firstSeq);
            assertThat(deleted).as("segment %d of %s", firstSeq, batch).isEqualTo(1);
        }
    }

    // --- reading ---------------------------------------------------------------------------------

    private Map<String, Object> totals(UUID batch) {
        return jdbc.queryForObject("""
                SELECT total_records, table_count, first_seq, last_seq, table_stats::text AS table_stats
                FROM batches WHERE id = ?
                """, (rs, row) -> {
                    Map<String, Object> values = new HashMap<>();
                    values.put("total_records", rs.getObject("total_records", Long.class));
                    Integer tables = rs.getObject("table_count", Integer.class);
                    values.put("table_count", tables == null ? null : tables.longValue());
                    values.put("first_seq", rs.getObject("first_seq", Long.class));
                    values.put("last_seq", rs.getObject("last_seq", Long.class));
                    String json = rs.getString("table_stats");
                    values.put("table_stats", json == null ? null : normalize(json));
                    return values;
                }, batch);
    }

    private String xmin(UUID batch) {
        return jdbc.queryForObject("SELECT xmin::text FROM batches WHERE id = ?", String.class, batch);
    }

    /** A FULL_SNAPSHOT's stats: every record an insert. Rendered by PostgreSQL so both sides compare as jsonb text. */
    private String stats(Map<String, Long> insertsByTable) {
        StringBuilder json = new StringBuilder("{");
        insertsByTable.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append("\"%s\":{\"inserts\":%d,\"updates\":0,\"deletes\":0}"
                    .formatted(entry.getKey(), entry.getValue()));
        });
        return normalize(json.append('}').toString());
    }

    private String normalize(String json) {
        return jdbc.queryForObject("SELECT CAST(? AS jsonb)::text", String.class, json);
    }

    // --- infrastructure --------------------------------------------------------------------------

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(jdbcUrlOfOwnDatabase(), user, password)
                .locations(LOCATION)
                .target(version)
                .load()
                .migrate();
    }

    private String jdbcUrlOfOwnDatabase() {
        return url.replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
    }

    private void connect() {
        TestContainersManager containers = TestContainersManager.getInstance();
        if (containers.isUsingExternalServices()) {
            // The CI service container, the same credentials AbstractIntegrationTest registers.
            url = "jdbc:postgresql://localhost:5432/dataforge_test";
            user = "dataforge";
            password = "dataforge_test_password";
        } else {
            PostgreSQLContainer postgres = containers.getPostgresContainer();
            url = postgres.getJdbcUrl();
            user = postgres.getUsername();
            password = postgres.getPassword();
        }
        admin = new JdbcTemplate(dataSource(url));
    }

    private DriverManagerDataSource dataSource(String jdbcUrl) {
        return new DriverManagerDataSource(jdbcUrl, user, password);
    }

    private final Map<UUID, UUID> sites = new HashMap<>();

    private UUID siteOf(UUID batch) {
        return sites.computeIfAbsent(batch, ignored -> UUID.randomUUID());
    }
}
