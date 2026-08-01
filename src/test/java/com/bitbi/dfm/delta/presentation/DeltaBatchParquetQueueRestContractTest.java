package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Batch Parquet Queue Admin REST Contract Tests")
@TestPropertySource(properties = "delta.batch-parquet.lease-seconds=1800")
class DeltaBatchParquetQueueRestContractTest extends BaseIntegrationTest {

    private static final String ADMIN_TOKEN = "Bearer mock.admin.jwt.token";
    private static final String USER_TOKEN = "Bearer mock.user.jwt.token";
    private static final UUID SITE_ID =
            UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    private static final UUID FOREIGN_SITE_ID =
            UUID.fromString("0199bab0-ca3b-e41c-5521-2f4b33fda8b6");
    private static final UUID BATCH_ID =
            UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID ABANDONED_ID =
            UUID.fromString("7d122441-9664-4e49-a95e-2d80bc98f9e1");
    private static final UUID PENDING_ID =
            UUID.fromString("86da4c19-8532-4baa-84ec-7fab9e6956af");
    private static final String LIST_URL =
            "/api/v1/sites/%s/delta/batches/%s/parquet-artifacts";
    private static final String REQUEUE_URL = LIST_URL + "/%s/requeue";

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedQueue() {
        jdbc.update("DELETE FROM admin_action_logs WHERE action_type = 'BATCH_PARQUET_REQUEUE'");
        jdbc.update("DELETE FROM batch_parquet_artifacts WHERE batch_id = ?", BATCH_ID);
        insert(ABANDONED_ID, "orders", "ABANDONED", 7, "schema mismatch", "2 hours");
        insert(PENDING_ID, "customers", "PENDING", 0, null, "1 minute");
    }

    @Test
    void adminListsAStableSafeProjection() throws Exception {
        mockMvc.perform(get(LIST_URL.formatted(SITE_ID, BATCH_ID))
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(PENDING_ID.toString()))
                .andExpect(jsonPath("$[0].tableName").value("customers"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].attemptCount").value(0))
                .andExpect(jsonPath("$[0].lastError").isEmpty())
                .andExpect(jsonPath("$[0].updatedAt").exists())
                .andExpect(jsonPath("$[0].claimToken").doesNotExist())
                .andExpect(jsonPath("$[0].s3Key").doesNotExist())
                .andExpect(jsonPath("$[1].id").value(ABANDONED_ID.toString()))
                .andExpect(jsonPath("$[1].lastError").value("schema mismatch"));
    }

    @Test
    void adminRequeuesAnAbandonedArtifactAndAuditsTheReset() throws Exception {
        mockMvc.perform(post(REQUEUE_URL.formatted(SITE_ID, BATCH_ID, ABANDONED_ID))
                        .header("Authorization", ADMIN_TOKEN)
                        .header("X-Forwarded-For", "192.0.2.44, 10.0.0.1")
                        .header("User-Agent", "queue-console"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attemptCount").value(0))
                .andExpect(jsonPath("$.lastError").isEmpty());

        MapRow artifact = jdbc.queryForObject("""
                SELECT status, attempt_count, claim_token, last_error
                FROM batch_parquet_artifacts WHERE id = ?
                """, (rs, row) -> new MapRow(rs.getString("status"), rs.getInt("attempt_count"),
                rs.getObject("claim_token"), rs.getString("last_error")), ABANDONED_ID);
        assertThat(artifact).isEqualTo(new MapRow("PENDING", 0, null, null));

        String audit = jdbc.queryForObject("""
                SELECT action_type || '|' || status || '|' || ip_address || '|' || user_agent
                       || '|' || (details->>'artifactId') || '|' || (details->>'previousStatus')
                FROM admin_action_logs
                WHERE target_site_id = ? AND action_type = 'BATCH_PARQUET_REQUEUE'
                ORDER BY id DESC LIMIT 1
                """, String.class, SITE_ID);
        assertThat(audit).isEqualTo("BATCH_PARQUET_REQUEUE|SUCCESS|192.0.2.44|queue-console|"
                + ABANDONED_ID + "|ABANDONED");
    }

    @Test
    void expiredBuildingCanBeRecoveredButALiveClaimCannot() throws Exception {
        // Keep this well beyond the lease and every local/JDBC timezone offset. The unit test
        // covers the exact 30-minute boundary; this contract test proves the persisted route.
        setBuilding("1 day");
        LocalDateTime expiredAt = jdbc.queryForObject(
                "SELECT updated_at FROM batch_parquet_artifacts WHERE id = ?",
                LocalDateTime.class, ABANDONED_ID);
        assertThat(expiredAt).isBefore(LocalDateTime.now(ZoneOffset.UTC).minusHours(12));
        mockMvc.perform(post(REQUEUE_URL.formatted(SITE_ID, BATCH_ID, ABANDONED_ID))
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        setBuilding("1 minute");
        mockMvc.perform(post(REQUEUE_URL.formatted(SITE_ID, BATCH_ID, ABANDONED_ID))
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isConflict());
    }

    @Test
    void pendingAndRouteMismatchedArtifactsAreRejected() throws Exception {
        mockMvc.perform(post(REQUEUE_URL.formatted(SITE_ID, BATCH_ID, PENDING_ID))
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isConflict());

        mockMvc.perform(post(REQUEUE_URL.formatted(FOREIGN_SITE_ID, BATCH_ID, ABANDONED_ID))
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void queueOperationsAreAdminOnly() throws Exception {
        mockMvc.perform(get(LIST_URL.formatted(SITE_ID, BATCH_ID))
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(REQUEUE_URL.formatted(SITE_ID, BATCH_ID, ABANDONED_ID))
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(LIST_URL.formatted(SITE_ID, BATCH_ID)))
                .andExpect(status().isUnauthorized());
    }

    private void insert(UUID id, String table, String status, int attempts, String error,
                        String age) {
        jdbc.update("""
                INSERT INTO batch_parquet_artifacts
                    (id, batch_id, site_id, table_name, status, attempt_count, last_error,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?,
                        CURRENT_TIMESTAMP AT TIME ZONE 'UTC' - CAST(? AS interval),
                        CURRENT_TIMESTAMP AT TIME ZONE 'UTC' - CAST(? AS interval), 0)
                """, id, BATCH_ID, SITE_ID, table, status, attempts, error, age, age);
    }

    private void setBuilding(String age) {
        jdbc.update("""
                UPDATE batch_parquet_artifacts
                SET status = 'BUILDING', attempt_count = 3, claim_token = ?, last_error = NULL,
                    updated_at = CURRENT_TIMESTAMP AT TIME ZONE 'UTC' - CAST(? AS interval),
                    version = version + 1
                WHERE id = ?
                """, UUID.randomUUID(), age, ABANDONED_ID);
    }

    private record MapRow(String status, int attempts, Object claimToken, String lastError) {
    }
}
