package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.CheckpointService;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.infrastructure.S3ChangelogSegmentStorage;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.site.application.SiteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hard-deleting a site that has Delta v2 history (issue #367).
 *
 * <p>{@code SiteService.deleteSite} predated Delta v2: it deleted batches one by one while
 * {@code changelog_segments.batch_id} (V30) references {@code batches} with no {@code ON DELETE}
 * action, so any site with a committed segment could not be deleted at all, and it never touched
 * the site's objects under {@code delta/{siteId}/segments/}, {@code checkpoints/{siteId}/} and
 * {@code egress/{siteId}/} — which {@code DeltaS3OrphanSweeper} leaves alone for a site the
 * database does not know ({@code reclaim-unknown-sites: false}), i.e. for ever.</p>
 *
 * <p>The site is this class's own — its own account and a fresh id — so deleting it takes nothing
 * another class seeded, and its three prefixes start empty in the shared bucket. The account and
 * domain match the {@code test-data.sql} sweep, which is what removes a leftover of a failing
 * run.</p>
 */
@DisplayName("Deleting a site with Delta v2 history (issue #367)")
class SiteDeletionIntegrationTest extends BaseIntegrationTest {

    private static final String SECRET_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhiN4Y7sJpX6dC";

    @TempDir
    Path tempDir;

    @Autowired
    private SiteService siteService;

    @Autowired
    private ChangelogSegmentService changelogSegmentService;

    @Autowired
    private CheckpointService checkpointService;

    @Autowired
    private S3CheckpointStorage checkpointStorage;

    @Autowired
    private S3Client s3Client;

    @Value("${s3.bucket.name}")
    private String bucket;

    @Autowired
    private AccountPluginRepository accountPluginRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID accountId;
    private UUID siteId;
    private final List<UUID> seededAccounts = new ArrayList<>();
    /** Objects a test leaves in the shared bucket on purpose; removed after it. */
    private final List<String> objectsToRemove = new ArrayList<>();

    @BeforeEach
    void seedSite() {
        accountId = UUID.randomUUID();
        seededAccounts.add(accountId);
        siteId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO accounts (id, email, name, is_active, created_at, updated_at)
                VALUES (?, ?, 'Site Deletion', true, now() AT TIME ZONE 'UTC', now() AT TIME ZONE 'UTC')
                """, accountId, "site-deletion-" + accountId + "@example.com");
        jdbc.update("""
                INSERT INTO sites (id, account_id, domain, client_secret_hash, display_name, is_active,
                                   retention_days, created_at, updated_at, site_name, client_api_version)
                VALUES (?, ?, ?, ?, 'Site Deletion', true, 45, now() AT TIME ZONE 'UTC',
                        now() AT TIME ZONE 'UTC', ?, 'V2')
                """, siteId, accountId, siteId + ".example.com", SECRET_HASH, "delete-" + siteId);
        declareCustomersSchema();
    }

    @AfterEach
    void removeLeftovers() {
        for (String key : objectsToRemove) {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        }
        objectsToRemove.clear();
        for (UUID account : seededAccounts) {
            jdbc.update("DELETE FROM account_plugins WHERE account_id = ?", account);
            jdbc.update("DELETE FROM changelog_segments WHERE site_id IN (SELECT id FROM sites WHERE account_id = ?)", account);
            jdbc.update("DELETE FROM error_logs WHERE site_id IN (SELECT id FROM sites WHERE account_id = ?)", account);
            jdbc.update("DELETE FROM batches WHERE account_id = ?", account);
            jdbc.update("DELETE FROM sites WHERE account_id = ?", account);
            jdbc.update("DELETE FROM accounts WHERE id = ?", account);
        }
        seededAccounts.clear();
    }

    @Test
    @DisplayName("removes a site with a committed Delta session, its rows and its objects")
    void deletesASiteWithACommittedDeltaSession() throws IOException {
        UUID batchId = seedBatch();
        ChangelogSegment segment = changelogSegmentService.persist(siteId, batchId, "FULL_SNAPSHOT", 1L,
                List.of(insert(1L, "Ann"), insert(2L, "Bob")));
        // The global delta-SQL and egress queues must not claim this class's segment.
        markSegmentsProcessed(siteId);
        checkpointService.buildCheckpoint(siteId);
        Path artifactFile = tempDir.resolve("customers.parquet");
        Files.write(artifactFile, new byte[]{1, 2, 3, 4});
        String artifactKey = checkpointStorage.uploadBatchParquet(
                siteId, batchId, "customers", UUID.randomUUID(), artifactFile);
        seedReadyArtifact(batchId, artifactKey);
        seedErrorLog(batchId);
        String fileKey = seedUploadedFile(batchId);
        pointPluginBaselineAt(batchId);
        // Objects no row names: a delta Parquet (keyed by seq range) and a segment whose commit
        // never wrote its row. Only a walk of the site's prefixes can find them.
        checkpointStorage.uploadDelta(siteId, "customers", 1L, 2L, new byte[]{5, 6, 7});
        String strayKey = S3ChangelogSegmentStorage.segmentPrefix(siteId) + UUID.randomUUID() + ".pb.gz";
        s3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(strayKey).build(),
                RequestBody.fromBytes(new byte[]{9}));

        // Preconditions: the history the delete has to take is really there.
        assertThat(count("SELECT COUNT(*) FROM changelog_segments WHERE site_id = ?")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM checkpoints WHERE site_id = ?")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM site_sync_state WHERE site_id = ?")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM batch_parquet_artifacts WHERE site_id = ?")).isEqualTo(1);
        assertThat(checkpointStorage.exists(segment.getS3Key())).isTrue();
        assertThat(checkpointStorage.listKeys(S3CheckpointStorage.checkpointPrefix(siteId)))
                .as("a snapshot and the frame").hasSizeGreaterThanOrEqualTo(2);
        assertThat(checkpointStorage.listKeys(S3CheckpointStorage.egressPrefix(siteId))).hasSize(2);

        siteService.deleteSite(siteId);

        assertThat(count("SELECT COUNT(*) FROM sites WHERE id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM batches WHERE site_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM changelog_segments WHERE site_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM checkpoints WHERE site_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM site_sync_state WHERE site_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM batch_parquet_artifacts WHERE site_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM error_logs WHERE site_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM site_schemas WHERE site_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM plugin_delta_baselines WHERE site_id = ?")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_plugins WHERE account_id = ? AND baseline_batch_id IS NOT NULL",
                Long.class, accountId))
                .as("the activation survives, detached from the deleted batch").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_plugins WHERE account_id = ?",
                Long.class, accountId)).isEqualTo(1);

        assertThat(checkpointStorage.exists(fileKey)).isFalse();
        assertThat(checkpointStorage.exists(artifactKey)).isFalse();
        assertThat(checkpointStorage.listKeys(S3ChangelogSegmentStorage.segmentPrefix(siteId))).isEmpty();
        assertThat(checkpointStorage.listKeys(S3CheckpointStorage.checkpointPrefix(siteId))).isEmpty();
        assertThat(checkpointStorage.listKeys(S3CheckpointStorage.egressPrefix(siteId))).isEmpty();
    }

    @Test
    @DisplayName("a delete whose transaction fails leaves every row and every object in place")
    void failedDeleteKeepsTheObjects() {
        UUID batchId = seedBatch();
        String fileKey = seedUploadedFile(batchId);
        ChangelogSegment segment = changelogSegmentService.persist(siteId, batchId, "DELTA", 1L,
                List.of(insert(1L, "Ann")));
        markSegmentsProcessed(siteId);
        objectsToRemove.add(fileKey);
        objectsToRemove.add(segment.getS3Key());
        // Refuse the site row's delete — the transaction's last statement, after the whole purge —
        // for this site only, so nothing another class does can meet it.
        String trigger = "refuse_site_delete_" + siteId.toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + trigger + "() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'site delete refused by the test'; END $$");
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE DELETE ON sites FOR EACH ROW "
                + "WHEN (OLD.id = '" + siteId + "') EXECUTE FUNCTION " + trigger + "()");
        try {
            assertThatThrownBy(() -> siteService.deleteSite(siteId))
                    .hasMessageContaining("site delete refused by the test");
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger + " ON sites");
            jdbc.execute("DROP FUNCTION " + trigger + "()");
        }

        // Objects go only after the rows are committed as gone, so a failure finds every row still
        // pointing at an object that exists.
        assertThat(count("SELECT COUNT(*) FROM sites WHERE id = ?")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM changelog_segments WHERE site_id = ?")).isEqualTo(1);
        assertThat(checkpointStorage.exists(fileKey)).isTrue();
        assertThat(checkpointStorage.exists(segment.getS3Key())).isTrue();
    }

    @Test
    @DisplayName("refuses to run inside a caller's transaction, deleting nothing")
    void refusesACallersTransaction() {
        UUID batchId = seedBatch();
        String fileKey = seedUploadedFile(batchId);
        objectsToRemove.add(fileKey);

        // The object walk must run with no transaction open; joining the caller's would hold its
        // connection for the whole walk, so the delete refuses outright instead.
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                status -> siteService.deleteSite(siteId)))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(count("SELECT COUNT(*) FROM sites WHERE id = ?")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM batches WHERE site_id = ?")).isEqualTo(1);
        assertThat(checkpointStorage.exists(fileKey)).isTrue();
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class, siteId);
        return value == null ? 0L : value;
    }

    private UUID seedBatch() {
        UUID batchId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count,
                                     total_size, has_errors, started_at, created_at, completed_at)
                VALUES (?, ?, ?, 'COMPLETED', ?, 0, 0, false, now() AT TIME ZONE 'UTC',
                        now() AT TIME ZONE 'UTC', now() AT TIME ZONE 'UTC')
                """, batchId, accountId, siteId, "delta/" + batchId + "/");
        return batchId;
    }

    private String seedUploadedFile(UUID batchId) {
        String fileKey = "site-deletion/" + batchId + "/data.csv";
        s3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(fileKey).build(),
                RequestBody.fromBytes(new byte[]{1}));
        jdbc.update("""
                INSERT INTO uploaded_files (id, batch_id, original_file_name, s3_key, file_size,
                                            content_type, checksum, uploaded_at)
                VALUES (?, ?, 'data.csv', ?, 1, 'text/csv', 'abc', now() AT TIME ZONE 'UTC')
                """, UUID.randomUUID(), batchId, fileKey);
        return fileKey;
    }

    private void seedErrorLog(UUID batchId) {
        jdbc.update("""
                INSERT INTO error_logs (id, batch_id, site_id, type, title, message, occurred_at)
                VALUES (?, ?, ?, 'TEST', 'boom', 'boom', now() AT TIME ZONE 'UTC')
                """, UUID.randomUUID(), batchId, siteId);
    }

    private void seedReadyArtifact(UUID batchId, String s3Key) {
        jdbc.update("""
                INSERT INTO batch_parquet_artifacts
                    (id, site_id, batch_id, table_name, status, s3_key, row_count, file_size,
                     checksum, attempt_count, created_at, updated_at, ready_at, version)
                VALUES (?, ?, ?, 'customers', 'READY', ?, 2, 4, 'checksum', 1,
                        now() AT TIME ZONE 'UTC', now() AT TIME ZONE 'UTC', now() AT TIME ZONE 'UTC', 0)
                """, UUID.randomUUID(), siteId, batchId, s3Key);
    }

    /**
     * An activation whose Bit BI baseline is one of the site's batches — the
     * {@code ON DELETE RESTRICT} reference (V25) that blocks a batch delete — plus a per-site
     * delta baseline row.
     */
    private void pointPluginBaselineAt(UUID batchId) {
        AccountPlugin activation = accountPluginRepository.save(
                AccountPlugin.activate(accountId, "bit-bi", Map.of("tenantId", "t1")));
        Long activationId = activation.getId();
        jdbc.update("UPDATE account_plugins SET baseline_batch_id = ? WHERE id = ?", batchId, activationId);
        jdbc.update("""
                INSERT INTO plugin_delta_baselines (account_plugin_id, site_id, table_name, baseline_seq)
                VALUES (?, ?, 'customers', 1)
                """, activationId, siteId);
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
        jdbc.update("INSERT INTO site_schemas (id, site_id, schema_data, schema_version, created_at, updated_at) "
                        + "VALUES (?, ?, ?::jsonb, 1, now() AT TIME ZONE 'UTC', now() AT TIME ZONE 'UTC')",
                UUID.randomUUID(), siteId, schemaJson);
    }

    private static ChangeRecord insert(long id, String name) {
        Map<String, com.bitbi.dfm.delta.grpc.v2.Value> data = new LinkedHashMap<>();
        data.put("id", intValue(id));
        data.put("name", com.bitbi.dfm.delta.grpc.v2.Value.newBuilder().setStringValue(name).build());
        return ChangeRecord.newBuilder()
                .setTable("customers")
                .setOp(Op.INSERT)
                .setSeq(id)
                .putAllKey(Map.of("id", intValue(id)))
                .putAllData(data)
                .build();
    }

    private static com.bitbi.dfm.delta.grpc.v2.Value intValue(long v) {
        return com.bitbi.dfm.delta.grpc.v2.Value.newBuilder().setIntValue(v).build();
    }
}
