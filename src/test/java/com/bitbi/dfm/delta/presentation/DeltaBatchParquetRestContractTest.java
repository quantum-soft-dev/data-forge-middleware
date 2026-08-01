package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feature 025 — contract tests for the per-batch delta Parquet download endpoint:
 * GET /api/v1/account/sites/{siteId}/delta/batches/{batchId}/tables/{tableName}/parquet (owner).
 *
 * <p>The endpoint presigns the segment's egressed delta Parquet file for one table
 * ({@code egress/{siteId}/{table}/delta/seq={first}-{last}.parquet}); 404 when the batch has no
 * segment or the file was never egressed (e.g. the table has no declared schema).</p>
 *
 * <p>The admin twin was descoped (2026-07-08): batch detail has no admin surface, so the
 * endpoint was unreachable dead code. Re-add it together with an admin batch-detail page.</p>
 */
@DisplayName("Delta Batch Parquet REST Contract Tests")
class DeltaBatchParquetRestContractTest extends BaseIntegrationTest {

    private static final String MOCK_USER_JWT = "mock.user.jwt.token";

    /** store-01.example.com — owned by test account 1 (the mock tokens' account). */
    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    /** store-03.example.com — owned by a different account. */
    private static final UUID FOREIGN_SITE = UUID.fromString("0199bab0-ca3b-e41c-5521-2f4b33fda8b6");
    /** COMPLETED batch of store-01 from test-data.sql (changelog_segments.batch_id FK). */
    private static final UUID BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    private static final String USER_URL = "/api/v1/account/sites/%s/delta/batches/%s/tables/%s/parquet";

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanSegments() {
        jdbc.update("DELETE FROM batch_parquet_artifacts WHERE site_id = ?", SITE);
        jdbc.update("DELETE FROM changelog_segments WHERE site_id = ?", SITE);
        // Defensive: assertions must not be satisfied by other tests' leftovers either.
        purgeEgressPrefix(SITE);
    }

    @AfterEach
    void cleanSeededArtifacts() {
        // The manifest rows are committed and the database is shared: leaving a PENDING/FAILED row
        // behind would make another class's claim-query assertions see work that is not theirs.
        jdbc.update("DELETE FROM batch_parquet_artifacts WHERE site_id = ?", SITE);
    }

    @AfterEach
    void cleanUploadedDeltaFiles() {
        // The LocalStack bucket is shared across the suite — leaving the uploaded delta
        // file behind breaks DeltaEgressIntegrationTest's "schema-less table skipped"
        // assertion on the same site/prefix.
        purgeEgressPrefix(SITE);
    }

    private void seedArtifact(String tableName, String artifactStatus) {
        boolean ready = "READY".equals(artifactStatus);
        jdbc.update("""
                INSERT INTO batch_parquet_artifacts
                    (id, site_id, batch_id, table_name, status, s3_key, row_count, file_size,
                     checksum, attempt_count, created_at, updated_at, ready_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, 0)
                """, UUID.randomUUID(), SITE, BATCH, tableName, artifactStatus,
                ready ? "egress/%s/batches/%s/%s.parquet".formatted(SITE, BATCH, tableName) : null,
                ready ? 42L : null, ready ? 1234L : null, ready ? "abc123" : null,
                ready ? LocalDateTime.now() : null);
    }

    @Test
    @DisplayName("owner: returns a presigned URL when the delta Parquet file exists")
    void shouldPresignOwnedBatchParquet() throws Exception {
        seedArtifact("orders", "READY");

        mockMvc.perform(get(USER_URL.formatted(SITE, BATCH, "orders"))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadUrl").isNotEmpty())
                .andExpect(jsonPath("$.fileName").value("orders_batch-" + BATCH + ".parquet"))
                .andExpect(jsonPath("$.downloadUrl", containsString("egress")))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    @DisplayName("returns 404 when the table's file was never egressed (no declared schema)")
    void shouldReturn404WhenFileMissing() throws Exception {
        mockMvc.perform(get(USER_URL.formatted(SITE, BATCH, "no_schema_table"))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("returns 409 while the unified artifact is still finalizing")
    void shouldReturn409WhenArtifactNotReady() throws Exception {
        seedArtifact("orders", "PENDING");

        mockMvc.perform(get(USER_URL.formatted(SITE, BATCH, "orders"))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("finalization")));
    }

    @Test
    @DisplayName("returns 409 while a failed table still has attempts left")
    void shouldReturn409WhenArtifactFailedButRetryable() throws Exception {
        seedArtifact("orders", "FAILED");

        mockMvc.perform(get(USER_URL.formatted(SITE, BATCH, "orders"))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("returns 404 once finalization gave up on that table")
    void shouldReturn404WhenArtifactAbandoned() throws Exception {
        seedArtifact("orders", "ABANDONED");

        mockMvc.perform(get(USER_URL.formatted(SITE, BATCH, "orders"))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("returns 404 when the batch has no changelog segment")
    void shouldReturn404WhenNoSegment() throws Exception {
        mockMvc.perform(get(USER_URL.formatted(SITE, BATCH, "orders"))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("owner: returns 403 for a site of another account")
    void shouldReturn403ForForeignSite() throws Exception {
        mockMvc.perform(get(USER_URL.formatted(FOREIGN_SITE, BATCH, "orders"))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isForbidden());
    }
}
