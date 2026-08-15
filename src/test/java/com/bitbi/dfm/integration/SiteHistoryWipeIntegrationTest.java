package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.CheckpointService;
import com.bitbi.dfm.delta.application.DeltaSiteWipeService;
import com.bitbi.dfm.delta.application.SiteHistoryWipeSummary;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaseline;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaselineRepository;
import com.bitbi.dfm.site.application.SiteService;
import com.bitbi.dfm.site.domain.Site;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end site history wipe (035 — issue #89): a fully populated site is emptied, its epoch
 * bumped, and the Bit BI baselines re-captured automatically once the first post-wipe checkpoint
 * exists.
 */
@DisplayName("Site history wipe (035)")
class SiteHistoryWipeIntegrationTest extends BaseIntegrationTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    /** store-01 — seeded site under ACCOUNT_ID. */
    private static final UUID SITE_ID = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");

    @TempDir
    Path tempDir;

    @Autowired
    private DeltaSiteWipeService wipeService;

    @Autowired
    private ChangelogSegmentService changelogSegmentService;

    @Autowired
    private CheckpointService checkpointService;

    @Autowired
    private AccountPluginRepository accountPluginRepository;

    @Autowired
    private PluginDeltaBaselineRepository baselineRepository;

    @Autowired
    private SiteService siteService;

    @Autowired
    private S3CheckpointStorage checkpointStorage;

    @Autowired
    private JdbcTemplate jdbc;

    private Site site;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE sites SET client_api_version = 'V2' WHERE id = ?", SITE_ID);
        // A live session is a 409 by design; the seed leaves one open.
        jdbc.update("""
                UPDATE batches SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP
                WHERE site_id = ? AND status = 'IN_PROGRESS'
                """, SITE_ID);
        jdbc.update("DELETE FROM site_sync_state WHERE site_id = ?", SITE_ID);
        jdbc.update("DELETE FROM batch_parquet_artifacts WHERE site_id = ?", SITE_ID);
        jdbc.update("DELETE FROM changelog_segments WHERE site_id = ?", SITE_ID);
        jdbc.update("DELETE FROM checkpoints WHERE site_id = ?", SITE_ID);
        jdbc.update("DELETE FROM plugin_delta_baselines WHERE site_id = ?", SITE_ID);
        // plugin_audit_logs is partitioned and deliberately not cleaned by test-data.sql, so the
        // auto-reinit entries of the previous test in this class would otherwise be counted too.
        jdbc.update("DELETE FROM plugin_audit_logs WHERE metadata->>'siteId' = ?", SITE_ID.toString());
        // The LocalStack bucket is shared across the suite and the egress prefix is keyed on seq
        // alone, so leftovers from another class would decide these assertions.
        purgeEgressPrefix(SITE_ID);
        purgeCheckpointPrefix(SITE_ID);
        declareCustomersSchema();
        site = siteService.getSite(SITE_ID);
    }

    // --- the wipe itself ---

    @Test
    @DisplayName("empties every site-scoped table and bumps the epoch")
    void wipeEmptiesTheSite() throws Exception {
        UUID batchId = seedBatch();
        changelogSegmentService.persist(SITE_ID, batchId, "DELTA", 1L, List.of(
                insert(1L, "Ann"), insert(2L, "Bob")));
        // A snapshot that never reached SessionEnd: invisible to every reader, but history all the same.
        changelogSegmentService.persistProvisional(SITE_ID, batchId, "FULL_SNAPSHOT", 3L, List.of(insert(3L, "Cy")));
        checkpointService.buildCheckpoint(SITE_ID);
        seedUploadedFile(batchId);
        seedErrorLog(batchId);
        pointPluginBaselineAt(batchId);
        Path artifactFile = tempDir.resolve("customers.parquet");
        Files.write(artifactFile, new byte[]{1, 2, 3, 4});
        String artifactKey = checkpointStorage.uploadBatchParquet(
                SITE_ID, batchId, "customers", UUID.randomUUID(), artifactFile);
        seedReadyArtifact(batchId, artifactKey);

        SiteHistoryWipeSummary summary = wipeService.wipe(site, DeltaSiteWipeService.Initiator.ADMIN);

        assertThat(summary.generation()).isEqualTo(1L);
        assertThat(summary.deletedBatches()).isPositive();
        assertThat(summary.deletedSegments()).isEqualTo(2);
        assertThat(summary.deletedCheckpoints()).isEqualTo(1);
        // The seed gives store-01 batches of its own; the wipe takes theirs too.
        assertThat(summary.deletedFiles()).isGreaterThanOrEqualTo(1);
        assertThat(summary.deletedErrorLogs()).isGreaterThanOrEqualTo(1);
        assertThat(summary.baselineBatchDetached()).isTrue();
        assertThat(summary.s3DeleteErrors()).isZero();
        assertThat(summary.prefixesNotSwept()).isZero();

        assertThat(count("SELECT COUNT(*) FROM batches WHERE site_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM changelog_segments WHERE site_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM checkpoints WHERE site_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM batch_parquet_artifacts WHERE site_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM error_logs WHERE site_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM site_schemas WHERE site_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM plugin_delta_baselines WHERE site_id = ?")).isZero();
        assertThat(checkpointStorage.exists(artifactKey)).isFalse();

        // The site itself survives — that is the whole point of a wipe rather than a delete.
        assertThat(count("SELECT COUNT(*) FROM sites WHERE id = ?")).isEqualTo(1);

        Long detachedBaselines = jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_plugins WHERE baseline_batch_id IS NOT NULL AND account_id = ?",
                Long.class, ACCOUNT_ID);
        assertThat(detachedBaselines).isZero();
    }

    @Test
    @DisplayName("takes the site's egress Parquet objects with it")
    void wipeDeletesEgressObjects() {
        // No row names these: the key is derived from the sequence range alone. Since the wipe
        // sends seqs back to zero, a survivor is what a post-wipe segment covering the same range
        // resolves to — pre-wipe rows served as the new batch's delta.
        checkpointStorage.uploadDelta(SITE_ID, "customers", 1L, 2L, new byte[]{1, 2, 3});
        checkpointStorage.uploadDelta(SITE_ID, "orders", 3L, 4L, new byte[]{4, 5, 6});
        try {
            Path orphan = tempDir.resolve("orphan-attempt.parquet");
            Files.write(orphan, new byte[]{7, 8, 9});
            checkpointStorage.uploadBatchParquet(
                    SITE_ID, UUID.randomUUID(), "customers", UUID.randomUUID(), orphan);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        assertThat(checkpointStorage.listKeys(S3CheckpointStorage.egressPrefix(SITE_ID))).hasSize(3);
        letS3LastModifiedFallBehindTheWipe();

        SiteHistoryWipeSummary summary = wipeService.wipe(site, DeltaSiteWipeService.Initiator.ADMIN);

        assertThat(summary.s3DeleteErrors()).isZero();
        assertThat(summary.prefixesNotSwept()).isZero();
        assertThat(checkpointStorage.listKeys(S3CheckpointStorage.egressPrefix(SITE_ID))).isEmpty();
    }

    @Test
    @DisplayName("takes the checkpoint objects of every earlier build with it")
    void wipeDeletesSupersededCheckpointObjects() {
        // The checkpoints row is one per (site, table) and reused: the second build replaces the key
        // on it, so the first build's snapshot is unreferenced from that moment on. The _frame/
        // reload frames are named by no row at any point.
        changelogSegmentService.persist(SITE_ID, seedBatch(), "FULL_SNAPSHOT", 1L,
                List.of(insert(1L, "Ann"), insert(2L, "Bob")));
        checkpointService.buildCheckpoint(SITE_ID);
        changelogSegmentService.persist(SITE_ID, seedBatch(), "DELTA", 3L, List.of(insert(3L, "Cy")));
        checkpointService.buildCheckpoint(SITE_ID);

        List<String> beforeWipe = checkpointStorage.listAllKeys(S3CheckpointStorage.checkpointPrefix(SITE_ID)).keys();
        assertThat(beforeWipe.stream().filter(key -> key.endsWith("snapshot.parquet")).toList())
                .as("one table, two builds, two snapshot objects")
                .hasSize(2);
        String recordedKey = jdbc.queryForObject(
                "SELECT s3_key_parquet FROM checkpoints WHERE site_id = ? AND table_name = 'customers'",
                String.class, SITE_ID);
        assertThat(beforeWipe).contains(recordedKey);
        letS3LastModifiedFallBehindTheWipe();

        SiteHistoryWipeSummary summary = wipeService.wipe(site, DeltaSiteWipeService.Initiator.ADMIN);

        assertThat(summary.s3DeleteErrors()).isZero();
        assertThat(summary.prefixesNotSwept()).isZero();
        assertThat(checkpointStorage.listAllKeys(S3CheckpointStorage.checkpointPrefix(SITE_ID)).keys())
                .as("a clean slate means the whole prefix, not just the newest key on the row")
                .isEmpty();
    }

    @Test
    @DisplayName("leaves the site asking for a full snapshot at the new epoch")
    void wipeArmsRebaseline() {
        seedBatch();

        wipeService.wipe(site, DeltaSiteWipeService.Initiator.OWNER);

        Map<String, Object> state = jdbc.queryForMap(
                "SELECT * FROM site_sync_state WHERE site_id = ?", SITE_ID);
        assertThat(state.get("generation")).isEqualTo(1L);
        assertThat(state.get("last_applied_seq")).isEqualTo(0L);
        assertThat(state.get("schema_version")).isEqualTo(0);
        assertThat(state.get("rebaseline_requested")).isEqualTo(true);
        assertThat(state.get("wipe_pending")).isEqualTo(true);
    }

    @Test
    @DisplayName("records an admin audit entry with the outcome")
    void wipeIsAudited() {
        seedBatch();

        wipeService.wipe(site, DeltaSiteWipeService.Initiator.ADMIN);

        Map<String, Object> entry = jdbc.queryForMap("""
                SELECT action_type, status, details::text AS details FROM admin_action_logs
                WHERE target_site_id = ? AND action_type = 'SITE_HISTORY_WIPE'
                ORDER BY created_at DESC LIMIT 1
                """, SITE_ID);
        assertThat(entry.get("status")).isEqualTo("SUCCESS");
        assertThat((String) entry.get("details"))
                .contains("\"initiator\": \"ADMIN\"")
                .contains("\"generation\": 1");
    }

    @Test
    @DisplayName("a second wipe is harmless and bumps the epoch again")
    void secondWipeIsHarmless() {
        wipeService.wipe(site, DeltaSiteWipeService.Initiator.ADMIN);

        SiteHistoryWipeSummary second = wipeService.wipe(site, DeltaSiteWipeService.Initiator.ADMIN);

        assertThat(second.generation()).isEqualTo(2L);
        assertThat(second.deletedBatches()).isZero();
    }

    @Test
    @DisplayName("a live session blocks the wipe")
    void liveSessionBlocksWipe() {
        UUID batchId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count,
                                     total_size, has_errors, started_at, last_activity_at, session_mode)
                VALUES (?, ?, ?, 'IN_PROGRESS', 'x/', 0, 0, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        'CONTINUOUS')
                """, batchId, ACCOUNT_ID, SITE_ID);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> wipeService.wipe(site, DeltaSiteWipeService.Initiator.ADMIN))
                .isInstanceOf(DeltaSiteWipeService.SessionInProgressException.class);

        assertThat(count("SELECT COUNT(*) FROM batches WHERE site_id = ?")).isPositive();
    }

    // --- Bit BI auto-reinit on the first post-wipe checkpoint ---

    @Test
    @DisplayName("the first post-wipe checkpoint re-captures the plugin baselines at its seq")
    void firstPostWipeCheckpointRecapturesBaselines() {
        AccountPlugin activation = accountPluginRepository.save(
                AccountPlugin.activate(ACCOUNT_ID, "bit-bi", Map.of("tenantId", "t1")));

        wipeService.wipe(site, DeltaSiteWipeService.Initiator.ADMIN);

        // The client's post-wipe cycle: re-submit the schema, then a full snapshot from seq 1.
        declareCustomersSchema();
        UUID snapshotBatch = seedBatch();
        changelogSegmentService.persist(SITE_ID, snapshotBatch, "FULL_SNAPSHOT", 1L,
                List.of(insert(1L, "Ann"), insert(2L, "Bob")));

        checkpointService.buildCheckpoint(SITE_ID);

        List<PluginDeltaBaseline> baselines = baselineRepository
                .findByAccountPluginIdAndSiteId(activation.getId(), SITE_ID);
        assertThat(baselines).hasSize(1);
        // Not Long.MAX_VALUE: a baseline of "suspended" is what the FULL_SNAPSHOT sweep would leave,
        // and it is exactly what the automatic recapture exists to replace.
        assertThat(baselines.get(0).getBaselineSeq()).isEqualTo(2L);
        assertThat(baselines.get(0).getTableName()).isEqualTo("customers");

        Boolean wipePending = jdbc.queryForObject(
                "SELECT wipe_pending FROM site_sync_state WHERE site_id = ?", Boolean.class, SITE_ID);
        assertThat(wipePending).isFalse();

        awaitAutoReinitEntries(1);
    }

    @Test
    @DisplayName("a later checkpoint does not recapture again")
    void laterCheckpointsDoNotRecapture() {
        AccountPlugin activation = accountPluginRepository.save(
                AccountPlugin.activate(ACCOUNT_ID, "bit-bi", Map.of("tenantId", "t1")));

        wipeService.wipe(site, DeltaSiteWipeService.Initiator.ADMIN);
        declareCustomersSchema();
        changelogSegmentService.persist(SITE_ID, seedBatch(), "FULL_SNAPSHOT", 1L, List.of(insert(1L, "Ann")));
        checkpointService.buildCheckpoint(SITE_ID);

        changelogSegmentService.persist(SITE_ID, seedBatch(), "DELTA", 2L, List.of(insert(2L, "Bob")));
        checkpointService.buildCheckpoint(SITE_ID);

        // The flag is taken once; a second recapture would move the baselines forward and silently
        // skip the SQL for everything between the two checkpoints.
        List<PluginDeltaBaseline> baselines = baselineRepository
                .findByAccountPluginIdAndSiteId(activation.getId(), SITE_ID);
        assertThat(baselines.get(0).getBaselineSeq()).isEqualTo(1L);

        awaitAutoReinitEntries(1);
    }

    @Test
    @DisplayName("a site with no bit-bi activation still clears the pending-wipe flag")
    void noActivationStillClearsTheFlag() {
        wipeService.wipe(site, DeltaSiteWipeService.Initiator.ADMIN);
        declareCustomersSchema();
        changelogSegmentService.persist(SITE_ID, seedBatch(), "FULL_SNAPSHOT", 1L, List.of(insert(1L, "Ann")));

        checkpointService.buildCheckpoint(SITE_ID);

        Boolean wipePending = jdbc.queryForObject(
                "SELECT wipe_pending FROM site_sync_state WHERE site_id = ?", Boolean.class, SITE_ID);
        assertThat(wipePending).isFalse();
    }

    // --- fixtures ---

    /**
     * S3 {@code LastModified} is second-resolution; the wipe skips anything in its own second so a
     * concurrent PutObject is not deleted. Wait until the next second so objects written in this
     * test are unambiguously older than the wipe.
     */
    private static void letS3LastModifiedFallBehindTheWipe() {
        long remainderMs = 1000L - (Instant.now().toEpochMilli() % 1000L);
        try {
            Thread.sleep(remainderMs + 50L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * The audit write is {@code @Async} and {@code plugin_audit_logs} is partitioned with no test
     * cleanup, so the count is scoped to this site rather than the account.
     */
    private void awaitAutoReinitEntries(long expected) {
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM plugin_audit_logs
                        WHERE action_type = 'DELTA_AUTO_REINIT' AND metadata->>'siteId' = ?
                        """, Long.class, SITE_ID.toString())).isEqualTo(expected));
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class, SITE_ID);
        return value == null ? 0L : value;
    }

    private UUID seedBatch() {
        UUID batchId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count,
                                     total_size, has_errors, started_at, created_at, completed_at)
                VALUES (?, ?, ?, 'COMPLETED', ?, 0, 0, false, now(), now(), now())
                """, batchId, ACCOUNT_ID, SITE_ID, "delta/" + batchId + "/");
        return batchId;
    }

    private void seedUploadedFile(UUID batchId) {
        jdbc.update("""
                INSERT INTO uploaded_files (id, batch_id, original_file_name, s3_key, file_size,
                                            content_type, checksum, uploaded_at)
                VALUES (?, ?, 'data.csv', ?, 1024, 'text/csv', 'abc', now())
                """, UUID.randomUUID(), batchId, "wipe-test/" + batchId + "/data.csv");
    }

    private void seedErrorLog(UUID batchId) {
        jdbc.update("""
                INSERT INTO error_logs (id, batch_id, site_id, type, title, message, occurred_at)
                VALUES (?, ?, ?, 'TEST', 'boom', 'boom', now())
                """, UUID.randomUUID(), batchId, SITE_ID);
    }

    private void seedReadyArtifact(UUID batchId, String s3Key) {
        jdbc.update("""
                INSERT INTO batch_parquet_artifacts
                    (id, site_id, batch_id, table_name, status, s3_key, row_count, file_size,
                     checksum, attempt_count, created_at, updated_at, ready_at, version)
                VALUES (?, ?, ?, 'customers', 'READY', ?, 2, 4, 'checksum', 1,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """, UUID.randomUUID(), SITE_ID, batchId, s3Key);
    }

    private void pointPluginBaselineAt(UUID batchId) {
        AccountPlugin activation = accountPluginRepository.save(
                AccountPlugin.activate(ACCOUNT_ID, "bit-bi", Map.of("tenantId", "t1")));
        jdbc.update("UPDATE account_plugins SET baseline_batch_id = ? WHERE id = ?",
                batchId, activation.getId());
        jdbc.update("""
                INSERT INTO plugin_delta_baselines (account_plugin_id, site_id, table_name, baseline_seq)
                VALUES (?, ?, 'customers', 1)
                """, activation.getId(), SITE_ID);
    }

    private void declareCustomersSchema() {
        String schemaJson = """
                {
                  "tables": {
                    "customers": {
                      "columns": [
                        {"name": "id", "type": "bigint", "nullable": false},
                        {"name": "name", "type": "varchar(255)", "nullable": true}
                      ],
                      "primaryKey": ["id"],
                      "uniqueKeys": []
                    }
                  }
                }
                """;
        jdbc.update("DELETE FROM site_schemas WHERE site_id = ?", SITE_ID);
        jdbc.update("INSERT INTO site_schemas (id, site_id, schema_data, schema_version, created_at, updated_at) "
                        + "VALUES (?, ?, ?::jsonb, 1, now(), now())",
                UUID.randomUUID(), SITE_ID, schemaJson);
    }

    private static ChangeRecord insert(long id, String name) {
        Map<String, Value> data = new LinkedHashMap<>();
        data.put("id", intValue(id));
        data.put("name", stringValue(name));
        return ChangeRecord.newBuilder()
                .setTable("customers")
                .setOp(Op.INSERT)
                .setSeq(id)
                .putAllKey(Map.of("id", intValue(id)))
                .putAllData(data)
                .build();
    }

    private static Value intValue(long v) {
        return Value.newBuilder().setIntValue(v).build();
    }

    private static Value stringValue(String v) {
        return Value.newBuilder().setStringValue(v).build();
    }
}
