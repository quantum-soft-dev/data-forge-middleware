package com.bitbi.dfm.integration;

import com.bitbi.dfm.batch.application.BatchRetentionService;
import com.bitbi.dfm.batch.application.BatchRetentionService.BatchCleanupRequest;
import com.bitbi.dfm.batch.application.BatchRetentionService.BatchCleanupSummary;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.error.domain.ErrorLogRepository;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Batch retention driven the way {@code BatchRetentionScheduler} drives it — with <b>no</b>
 * transaction around {@code runCleanup} (issue #344).
 * <p>
 * Deliberately no {@code @Transactional} on any test method: until #344 both methods of this class
 * carried one, so the service's inert self-invoked {@code @Transactional} was never exercised — the
 * test's own transaction stood in for it, while production threw
 * {@code TransactionRequiredException} on the first bulk delete every night and deleted nothing.
 * The rows this class commits are removed in {@link #removeSeededRows()}.
 * </p>
 */
@DisplayName("Batch Retention Integration Tests (Testcontainers)")
class BatchRetentionIntegrationTest extends AbstractIntegrationTest {

    private static final String SECRET_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhiN4Y7sJpX6dC";

    @TempDir
    Path tempDir;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BatchRetentionService batchRetentionService;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private ErrorLogRepository errorLogRepository;

    @Autowired
    private UploadedFileRepository uploadedFileRepository;

    @Autowired
    private S3CheckpointStorage checkpointStorage;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private S3Client s3Client;

    @Value("${s3.bucket.name}")
    private String bucketName;

    private final List<UUID> seededAccounts = new ArrayList<>();

    @AfterEach
    void removeSeededRows() {
        for (UUID accountId : seededAccounts) {
            jdbcTemplate.update("DELETE FROM account_plugins WHERE account_id = ?", accountId);
            jdbcTemplate.update("DELETE FROM changelog_segments WHERE site_id IN "
                    + "(SELECT id FROM sites WHERE account_id = ?)", accountId);
            // uploaded_files, error_logs and batch_parquet_artifacts cascade from batches.
            jdbcTemplate.update("DELETE FROM batches WHERE account_id = ?", accountId);
            jdbcTemplate.update("DELETE FROM sites WHERE account_id = ?", accountId);
            jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", accountId);
        }
        seededAccounts.clear();
    }

    @Test
    @DisplayName("Deletes an expired batch with no transaction around the call, as the scheduler runs it")
    void deletesAnExpiredBatchOutsideATransaction() throws Exception {
        UUID accountId = seedAccount();
        UUID siteId = seedSite(accountId);
        seedCheckpoint(siteId);
        LocalDateTime old = LocalDateTime.now(ZoneOffset.UTC).minusDays(60);
        LocalDateTime recent = LocalDateTime.now(ZoneOffset.UTC).minusDays(10);
        UUID eligibleBatchId = seedBatch(accountId, siteId, "COMPLETED", old);
        UUID inProgressBatchId = seedBatch(accountId, siteId, "IN_PROGRESS", old);
        UUID recentBatchId = seedBatch(accountId, siteId, "COMPLETED", recent);
        seedUploadedFile(eligibleBatchId);

        jdbcTemplate.update(
                "INSERT INTO error_logs (id, site_id, batch_id, type, title, message, stack_trace, client_version, metadata, occurred_at, created_at) VALUES (?,?,?,?,?,?,?,?,?::jsonb,NOW() AT TIME ZONE 'UTC',NOW() AT TIME ZONE 'UTC')",
                UUID.randomUUID(), siteId, eligibleBatchId, "UPLOAD_ERROR", "Test Error",
                "Test error message", null, null, "{}");

        Path artifactFile = tempDir.resolve("orders.parquet");
        Files.write(artifactFile, new byte[]{1, 2, 3, 4});
        String artifactKey = checkpointStorage.uploadBatchParquet(
                siteId, eligibleBatchId, "orders", UUID.randomUUID(), artifactFile);
        String orphanAttemptKey = checkpointStorage.uploadBatchParquet(
                siteId, eligibleBatchId, "orders", UUID.randomUUID(), artifactFile);
        jdbcTemplate.update("""
                INSERT INTO batch_parquet_artifacts
                    (id, site_id, batch_id, table_name, status, s3_key, row_count, file_size,
                     checksum, attempt_count, created_at, updated_at, ready_at, version)
                VALUES (?, ?, ?, 'orders', 'READY', ?, 1, 4, 'checksum', 1,
                        CURRENT_TIMESTAMP AT TIME ZONE 'UTC', CURRENT_TIMESTAMP AT TIME ZONE 'UTC', CURRENT_TIMESTAMP AT TIME ZONE 'UTC', 0)
                """, UUID.randomUUID(), siteId, eligibleBatchId, artifactKey);

        // A changelog segment: its row must go in the batch's transaction (the batch_id FK does not
        // cascade) and its object after the commit, with the rest of the batch's objects.
        String segmentKey = "delta/" + siteId + "/segments/" + UUID.randomUUID() + ".pb.gz";
        s3Client.putObject(PutObjectRequest.builder().bucket(bucketName).key(segmentKey).build(),
                RequestBody.fromBytes(new byte[]{9}));
        jdbcTemplate.update("""
                INSERT INTO changelog_segments (id, site_id, batch_id, first_seq, last_seq,
                    record_count, content_hash, s3_key, mode, plugin_sql_at, egress_at)
                VALUES (?, ?, ?, 1, 1, 1, 'hash', ?, 'DELTA',
                        CURRENT_TIMESTAMP AT TIME ZONE 'UTC', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
                """, UUID.randomUUID(), siteId, eligibleBatchId, segmentKey);
        assertThat(checkpointStorage.exists(artifactKey)).isTrue();
        assertThat(checkpointStorage.exists(orphanAttemptKey)).isTrue();
        assertThat(checkpointStorage.exists(segmentKey)).isTrue();

        BatchCleanupSummary summary = batchRetentionService.runCleanup(
                new BatchCleanupRequest(siteId, null, null, null, 100, false));

        assertThat(summary.errors()).isEmpty();
        assertThat(summary.candidates()).isEqualTo(1);
        assertThat(summary.deletedBatches()).isEqualTo(1);

        assertThat(batchRepository.existsById(eligibleBatchId)).isFalse();
        assertThat(batchRepository.existsById(inProgressBatchId)).isTrue();
        assertThat(batchRepository.existsById(recentBatchId)).isTrue();

        assertThat(uploadedFileRepository.countByBatchId(eligibleBatchId)).isZero();
        assertThat(errorLogRepository.countByBatchId(eligibleBatchId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM batch_parquet_artifacts WHERE batch_id = ?",
                Long.class, eligibleBatchId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM changelog_segments WHERE batch_id = ?",
                Long.class, eligibleBatchId)).isZero();
        assertThat(checkpointStorage.exists(artifactKey)).isFalse();
        assertThat(checkpointStorage.exists(orphanAttemptKey)).isFalse();
        assertThat(checkpointStorage.exists(segmentKey)).isFalse();
    }

    @Test
    @DisplayName("Does not delete a batch referenced as plugin baseline_batch_id")
    void keepsAPluginBaselineBatch() {
        UUID accountId = seedAccount();
        UUID siteId = seedSite(accountId);
        LocalDateTime old = LocalDateTime.now(ZoneOffset.UTC).minusDays(60);
        UUID baselineBatchId = seedBatch(accountId, siteId, "COMPLETED", old);
        UUID eligibleBatchId = seedBatch(accountId, siteId, "COMPLETED", old);
        jdbcTemplate.update(
                "INSERT INTO account_plugins (account_id, plugin_id, plugin_data, is_active, activated_at, created_at, updated_at, baseline_batch_id) VALUES (?,?,?::jsonb,?,?,NOW() AT TIME ZONE 'UTC',NOW() AT TIME ZONE 'UTC',?)",
                accountId, "bit-bi", "{}", true, LocalDateTime.now(ZoneOffset.UTC), baselineBatchId);

        BatchCleanupSummary summary = batchRetentionService.runCleanup(
                new BatchCleanupRequest(siteId, null, null, null, 100, false));

        assertThat(summary.errors()).isEmpty();
        assertThat(summary.candidates()).isEqualTo(1);
        assertThat(summary.deletedBatches()).isEqualTo(1);
        assertThat(batchRepository.existsById(baselineBatchId)).isTrue();
        assertThat(batchRepository.existsById(eligibleBatchId)).isFalse();
    }

    @Test
    @DisplayName("Keeps a v1 upload batch of a site with no checkpoint — its only Bit BI baseline (owner decision 1)")
    void keepsAV1BatchOfASiteWithoutCheckpoints() {
        UUID accountId = seedAccount();
        UUID siteId = seedSite(accountId);
        LocalDateTime old = LocalDateTime.now(ZoneOffset.UTC).minusDays(60);
        UUID v1BatchId = seedBatch(accountId, siteId, "COMPLETED", old);
        seedUploadedFile(v1BatchId);
        UUID noFilesBatchId = seedBatch(accountId, siteId, "COMPLETED", old);

        BatchCleanupSummary summary = batchRetentionService.runCleanup(
                new BatchCleanupRequest(siteId, null, null, null, 100, false));

        assertThat(summary.errors()).isEmpty();
        assertThat(summary.candidates()).isEqualTo(1);
        assertThat(summary.deletedBatches()).isEqualTo(1);
        assertThat(batchRepository.existsById(v1BatchId)).isTrue();
        assertThat(uploadedFileRepository.countByBatchId(v1BatchId)).isEqualTo(1);
        assertThat(batchRepository.existsById(noFilesBatchId)).isFalse();
    }

    @Test
    @DisplayName("Deletes a v1 upload batch once its site has a checkpoint (the historical fallback is closed)")
    void deletesAV1BatchOnceItsSiteHasACheckpoint() {
        UUID accountId = seedAccount();
        UUID siteId = seedSite(accountId);
        seedCheckpoint(siteId);
        UUID v1BatchId = seedBatch(accountId, siteId, "COMPLETED",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(60));
        seedUploadedFile(v1BatchId);

        BatchCleanupSummary summary = batchRetentionService.runCleanup(
                new BatchCleanupRequest(siteId, null, null, null, 100, false));

        assertThat(summary.errors()).isEmpty();
        assertThat(summary.deletedBatches()).isEqualTo(1);
        assertThat(batchRepository.existsById(v1BatchId)).isFalse();
    }

    @Test
    @DisplayName("A dry run reports the same candidates and deletes nothing")
    void dryRunAppliesTheSameCandidateRule() {
        UUID accountId = seedAccount();
        UUID siteId = seedSite(accountId);
        LocalDateTime old = LocalDateTime.now(ZoneOffset.UTC).minusDays(60);
        UUID v1BatchId = seedBatch(accountId, siteId, "COMPLETED", old);
        seedUploadedFile(v1BatchId);
        UUID noFilesBatchId = seedBatch(accountId, siteId, "COMPLETED", old);

        BatchCleanupSummary summary = batchRetentionService.runCleanup(
                new BatchCleanupRequest(siteId, null, null, null, 100, true));

        assertThat(summary.errors()).isEmpty();
        assertThat(summary.candidates()).isEqualTo(1);
        assertThat(summary.deletedBatches()).isZero();
        assertThat(batchRepository.existsById(v1BatchId)).isTrue();
        assertThat(batchRepository.existsById(noFilesBatchId)).isTrue();
    }

    @Test
    @DisplayName("Skips a batch another transaction holds locked, and deletes it once released")
    void skipsABatchLockedByAnotherPass() throws Exception {
        UUID accountId = seedAccount();
        UUID siteId = seedSite(accountId);
        UUID batchId = seedBatch(accountId, siteId, "COMPLETED",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(60));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            // Another retention pass (a sibling replica, the admin endpoint) mid-delete of this batch.
            Future<?> holding = holder.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbcTemplate.queryForList("SELECT id FROM batches WHERE id = ? FOR UPDATE", batchId);
                        locked.countDown();
                        try {
                            release.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

            BatchCleanupSummary whileLocked = batchRetentionService.runCleanup(
                    new BatchCleanupRequest(siteId, null, null, null, 100, false));

            assertThat(whileLocked.errors()).isEmpty();
            assertThat(whileLocked.candidates()).isEqualTo(1);
            assertThat(whileLocked.deletedBatches()).isZero();
            assertThat(batchRepository.existsById(batchId)).isTrue();

            release.countDown();
            holding.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            holder.shutdownNow();
        }

        BatchCleanupSummary afterRelease = batchRetentionService.runCleanup(
                new BatchCleanupRequest(siteId, null, null, null, 100, false));

        assertThat(afterRelease.deletedBatches()).isEqualTo(1);
        assertThat(batchRepository.existsById(batchId)).isFalse();
    }

    @Test
    @DisplayName("Refuses to run inside a caller's transaction")
    void refusesToRunInsideATransaction() {
        UUID accountId = seedAccount();
        UUID siteId = seedSite(accountId);
        UUID batchId = seedBatch(accountId, siteId, "COMPLETED",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(60));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> batchRetentionService.runCleanup(
                        new BatchCleanupRequest(siteId, null, null, null, 100, false))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");
        assertThat(batchRepository.existsById(batchId)).isTrue();
    }

    private UUID seedAccount() {
        UUID accountId = UUID.randomUUID();
        seededAccounts.add(accountId);
        jdbcTemplate.update(
                "INSERT INTO accounts (id, email, name, is_active, created_at, updated_at) VALUES (?,?,?,?,NOW() AT TIME ZONE 'UTC',NOW() AT TIME ZONE 'UTC')",
                accountId, "retention-" + accountId + "@example.com", "Retention Test", true);
        return accountId;
    }

    private UUID seedSite(UUID accountId) {
        UUID siteId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO sites (id, account_id, domain, client_secret_hash, display_name, is_active, retention_days, created_at, updated_at, site_name) VALUES (?,?,?,?,?,?,?,NOW() AT TIME ZONE 'UTC',NOW() AT TIME ZONE 'UTC',?)",
                siteId, accountId, siteId + ".example.com", SECRET_HASH, "Retention Site", true, 45,
                "example.com");
        return siteId;
    }

    private UUID seedBatch(UUID accountId, UUID siteId, String status, LocalDateTime startedAt) {
        UUID batchId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO batches (id, site_id, account_id, status, s3_path, uploaded_files_count, total_size, has_errors, started_at, completed_at, created_at, version) VALUES (?,?,?,?,?,?,?,?,?,?,NOW() AT TIME ZONE 'UTC',0)",
                batchId, siteId, accountId, status, "path/" + batchId + "/", 0, 0, false, startedAt,
                "IN_PROGRESS".equals(status) ? null : startedAt.plusMinutes(5));
        return batchId;
    }

    private void seedUploadedFile(UUID batchId) {
        jdbcTemplate.update(
                "INSERT INTO uploaded_files (id, batch_id, original_file_name, s3_key, file_size, content_type, checksum, uploaded_at) VALUES (?,?,?,?,?,?,?,NOW() AT TIME ZONE 'UTC')",
                UUID.randomUUID(), batchId, "data.csv", "path/" + batchId + "/data.csv", 100,
                "text/csv", "checksum");
    }

    private void seedCheckpoint(UUID siteId) {
        jdbcTemplate.update("""
                INSERT INTO checkpoints (id, site_id, table_name, seq, row_count, created_at, updated_at)
                VALUES (?, ?, 'orders', 1, 1, CURRENT_TIMESTAMP AT TIME ZONE 'UTC',
                        CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
                """, UUID.randomUUID(), siteId);
    }
}
