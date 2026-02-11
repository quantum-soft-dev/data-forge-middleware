package com.bitbi.dfm.integration;

import com.bitbi.dfm.batch.application.BatchRetentionService;
import com.bitbi.dfm.batch.application.BatchRetentionService.BatchCleanupRequest;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.error.domain.ErrorLogRepository;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Batch Retention Integration Tests (Testcontainers)")
class BatchRetentionIntegrationTest extends AbstractIntegrationTest {

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

    @Test
    @Transactional
    @DisplayName("Should cleanup eligible batches and cascade error logs")
    void shouldCleanupEligibleBatches() {
        UUID accountId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        UUID eligibleBatchId = UUID.randomUUID();
        UUID inProgressBatchId = UUID.randomUUID();
        UUID recentBatchId = UUID.randomUUID();
        UUID errorId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();

        String compositeDomain = accountId + "_example.com";
        String secretHash = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhiN4Y7sJpX6dC";

        jdbcTemplate.update(
                "INSERT INTO accounts (id, email, name, is_active, created_at, updated_at) VALUES (?,?,?,?,NOW(),NOW())",
                accountId,
                "retention-test@example.com",
                "Retention Test",
                true
        );

        jdbcTemplate.update(
                "INSERT INTO sites (id, account_id, domain, client_secret_hash, display_name, is_active, retention_days, created_at, updated_at, site_name) VALUES (?,?,?,?,?,?,?,NOW(),NOW(),?)",
                siteId,
                accountId,
                compositeDomain,
                secretHash,
                "Retention Site",
                true,
                45,
                "example.com"
        );

        LocalDateTime oldStartedAt = LocalDateTime.now().minusDays(60);
        LocalDateTime recentStartedAt = LocalDateTime.now().minusDays(10);

        jdbcTemplate.update(
                "INSERT INTO batches (id, site_id, account_id, status, s3_path, uploaded_files_count, total_size, has_errors, started_at, completed_at, created_at, version) VALUES (?,?,?,?,?,?,?,?,?,?,NOW(),0)",
                eligibleBatchId,
                siteId,
                accountId,
                "COMPLETED",
                "path/eligible/",
                1,
                100,
                false,
                oldStartedAt,
                oldStartedAt.plusMinutes(5)
        );

        jdbcTemplate.update(
                "INSERT INTO batches (id, site_id, account_id, status, s3_path, uploaded_files_count, total_size, has_errors, started_at, completed_at, created_at, version) VALUES (?,?,?,?,?,?,?,?,?,?,NOW(),0)",
                inProgressBatchId,
                siteId,
                accountId,
                "IN_PROGRESS",
                "path/in-progress/",
                0,
                0,
                false,
                oldStartedAt,
                null
        );

        jdbcTemplate.update(
                "INSERT INTO batches (id, site_id, account_id, status, s3_path, uploaded_files_count, total_size, has_errors, started_at, completed_at, created_at, version) VALUES (?,?,?,?,?,?,?,?,?,?,NOW(),0)",
                recentBatchId,
                siteId,
                accountId,
                "COMPLETED",
                "path/recent/",
                0,
                0,
                false,
                recentStartedAt,
                recentStartedAt.plusMinutes(5)
        );

        jdbcTemplate.update(
                "INSERT INTO uploaded_files (id, batch_id, original_file_name, s3_key, file_size, content_type, checksum, uploaded_at) VALUES (?,?,?,?,?,?,?,NOW())",
                fileId,
                eligibleBatchId,
                "data.csv",
                "path/eligible/data.csv",
                100,
                "text/csv",
                "checksum"
        );

        jdbcTemplate.update(
                "INSERT INTO error_logs (id, site_id, batch_id, type, title, message, stack_trace, client_version, metadata, occurred_at, created_at) VALUES (?,?,?,?,?,?,?,?,?::jsonb,NOW(),NOW())",
                errorId,
                siteId,
                eligibleBatchId,
                "UPLOAD_ERROR",
                "Test Error",
                "Test error message",
                null,
                null,
                "{}"
        );

        BatchRetentionService.BatchCleanupSummary summary = batchRetentionService.runCleanup(
                new BatchCleanupRequest(siteId, null, null, null, 100, false)
        );

        assertThat(summary.candidates()).isEqualTo(1);
        assertThat(summary.deletedBatches()).isEqualTo(1);

        assertThat(batchRepository.existsById(eligibleBatchId)).isFalse();
        assertThat(batchRepository.existsById(inProgressBatchId)).isTrue();
        assertThat(batchRepository.existsById(recentBatchId)).isTrue();

        assertThat(uploadedFileRepository.countByBatchId(eligibleBatchId)).isZero();
        assertThat(errorLogRepository.countByBatchId(eligibleBatchId)).isZero();
    }

    @Test
    @Transactional
    @DisplayName("Should NOT delete batches referenced as plugin baseline_batch_id")
    void shouldNotDeleteBaselineBatch() {
        UUID accountId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        UUID baselineBatchId = UUID.randomUUID();
        UUID eligibleBatchId = UUID.randomUUID();

        String compositeDomain = accountId + "_example.com";
        String secretHash = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhiN4Y7sJpX6dC";

        jdbcTemplate.update(
                "INSERT INTO accounts (id, email, name, is_active, created_at, updated_at) VALUES (?,?,?,?,NOW(),NOW())",
                accountId,
                "baseline-test@example.com",
                "Baseline Test",
                true
        );

        jdbcTemplate.update(
                "INSERT INTO sites (id, account_id, domain, client_secret_hash, display_name, is_active, retention_days, created_at, updated_at, site_name) VALUES (?,?,?,?,?,?,?,NOW(),NOW(),?)",
                siteId,
                accountId,
                compositeDomain,
                secretHash,
                "Baseline Site",
                true,
                45,
                "baseline.example.com"
        );

        LocalDateTime oldStartedAt = LocalDateTime.now().minusDays(60);

        // Baseline batch (old but must not be deleted)
        jdbcTemplate.update(
                "INSERT INTO batches (id, site_id, account_id, status, s3_path, uploaded_files_count, total_size, has_errors, started_at, completed_at, created_at, version) VALUES (?,?,?,?,?,?,?,?,?,?,NOW(),0)",
                baselineBatchId,
                siteId,
                accountId,
                "COMPLETED",
                "path/baseline/",
                0,
                0,
                false,
                oldStartedAt,
                oldStartedAt.plusMinutes(5)
        );

        // Another eligible old batch (should be deleted)
        jdbcTemplate.update(
                "INSERT INTO batches (id, site_id, account_id, status, s3_path, uploaded_files_count, total_size, has_errors, started_at, completed_at, created_at, version) VALUES (?,?,?,?,?,?,?,?,?,?,NOW(),0)",
                eligibleBatchId,
                siteId,
                accountId,
                "COMPLETED",
                "path/eligible2/",
                0,
                0,
                false,
                oldStartedAt,
                oldStartedAt.plusMinutes(5)
        );

        // account_plugins row referencing baseline batch
        jdbcTemplate.update(
                "INSERT INTO account_plugins (account_id, plugin_id, plugin_data, is_active, activated_at, created_at, updated_at, baseline_batch_id) VALUES (?,?,?::jsonb,?,?,NOW(),NOW(),?)",
                accountId,
                "bit-bi",
                "{}",
                true,
                LocalDateTime.now(),
                baselineBatchId
        );

        BatchRetentionService.BatchCleanupSummary summary = batchRetentionService.runCleanup(
                new BatchCleanupRequest(siteId, null, null, null, 100, false)
        );

        assertThat(summary.candidates()).isEqualTo(1);
        assertThat(summary.deletedBatches()).isEqualTo(1);

        assertThat(batchRepository.existsById(baselineBatchId)).isTrue();
        assertThat(batchRepository.existsById(eligibleBatchId)).isFalse();
    }
}
