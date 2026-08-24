package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B4 (023) — contract tests for the Delta sync-state REST endpoints:
 * GET /api/v1/account/sites/{siteId}/delta/sync-state (owner) and
 * GET /api/v1/sites/{siteId}/delta/sync-state (admin).
 */
@DisplayName("Delta Sync State REST Contract Tests")
class DeltaSyncStateRestContractTest extends BaseIntegrationTest {

    private static final String MOCK_USER_JWT = "mock.user.jwt.token";
    private static final String MOCK_ADMIN_JWT = "mock.admin.jwt.token";

    /** store-01.example.com — owned by test account 1 (the mock tokens' account). */
    private static final UUID OWNED_SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    /** store-03.example.com — owned by a different account. */
    private static final UUID FOREIGN_SITE = UUID.fromString("0199bab0-ca3b-e41c-5521-2f4b33fda8b6");

    private static final String USER_URL = "/api/v1/account/sites/%s/delta/sync-state";
    private static final String ADMIN_URL = "/api/v1/sites/%s/delta/sync-state";

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanSyncState() {
        jdbc.update("DELETE FROM site_sync_state WHERE site_id IN (?, ?)", OWNED_SITE, FOREIGN_SITE);
    }

    private void seedSyncState(UUID siteId) {
        jdbc.update("""
                INSERT INTO site_sync_state
                    (site_id, last_applied_seq, last_checkpoint_seq, last_checkpoint_at, schema_version,
                     updated_at, rebaseline_requested, rebuild_requested)
                VALUES (?, 4821, 3200, '2026-07-05 10:00:00', 12, '2026-07-05 12:30:00', false, true)
                """, siteId);
    }

    private void seedRebuildVerdict(UUID siteId, String outcome, String message) {
        jdbc.update("""
                UPDATE site_sync_state
                   SET last_rebuild_outcome = ?, last_rebuild_outcome_at = '2026-07-05 12:29:00',
                       last_rebuild_message = ?
                 WHERE site_id = ?
                """, outcome, message, siteId);
    }

    private void seedCheckpointBuildAbort(UUID siteId, String abort, String message) {
        jdbc.update("""
                UPDATE site_sync_state
                   SET last_checkpoint_seq = 0, last_checkpoint_at = NULL,
                       last_checkpoint_build_abort = ?, last_checkpoint_build_abort_at = '2026-07-05 02:00:00',
                       last_checkpoint_build_message = ?
                 WHERE site_id = ?
                """, abort, message, siteId);
    }

    @Nested
    @DisplayName("GET /api/v1/account/sites/{siteId}/delta/sync-state (owner)")
    class OwnerSyncState {

        @Test
        @DisplayName("returns 200 with full sync state for own site")
        void shouldReturnSyncStateForOwnSite() throws Exception {
            seedSyncState(OWNED_SITE);

            mockMvc.perform(get(USER_URL.formatted(OWNED_SITE))
                            .header("Authorization", "Bearer " + MOCK_USER_JWT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lastAppliedSeq").value(4821))
                    .andExpect(jsonPath("$.lastCheckpointSeq").value(3200))
                    .andExpect(jsonPath("$.lastCheckpointAt").isNotEmpty())
                    .andExpect(jsonPath("$.schemaVersion").value(12))
                    .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                    .andExpect(jsonPath("$.rebaselineRequested").value(false))
                    .andExpect(jsonPath("$.rebuildRequested").value(true));
        }

        @Test
        @DisplayName("carries no rebuild verdict for a site that was never rebuilt")
        void shouldReturnNullRebuildVerdictWhenNoneWasRecorded() throws Exception {
            // The three fields must be present-and-null rather than absent: the UI decides between
            // "no verdict" and "a verdict" on their value, and an absent key would read as neither.
            seedSyncState(OWNED_SITE);

            mockMvc.perform(get(USER_URL.formatted(OWNED_SITE))
                            .header("Authorization", "Bearer " + MOCK_USER_JWT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lastRebuildOutcome").value(nullValue()))
                    .andExpect(jsonPath("$.lastRebuildOutcomeAt").value(nullValue()))
                    .andExpect(jsonPath("$.lastRebuildMessage").value(nullValue()))
                    .andExpect(jsonPath("$.lastCheckpointBuildAbort").value(nullValue()))
                    .andExpect(jsonPath("$.lastCheckpointBuildAbortAt").value(nullValue()))
                    .andExpect(jsonPath("$.lastCheckpointBuildMessage").value(nullValue()));
        }

        @Test
        @DisplayName("returns the last scheduled-build abort and its time, but withholds the diagnosis")
        void shouldReturnTheCheckpointBuildAbortWithoutItsMessage() throws Exception {
            // Issue #224: the owner is the user staring at a site whose first checkpoint never
            // arrived, so the reason and its time are what the projection owes them. The message
            // is the exception's own text, withheld the same way as lastRebuildMessage.
            seedSyncState(OWNED_SITE);
            seedCheckpointBuildAbort(OWNED_SITE, "FOLD_TOO_LARGE",
                    "The checkpoint fold reached an estimated 9000000 bytes");

            mockMvc.perform(get(USER_URL.formatted(OWNED_SITE))
                            .header("Authorization", "Bearer " + MOCK_USER_JWT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lastCheckpointBuildAbort").value("FOLD_TOO_LARGE"))
                    .andExpect(jsonPath("$.lastCheckpointBuildAbortAt").isNotEmpty())
                    .andExpect(jsonPath("$.lastCheckpointBuildMessage").value(nullValue()));
        }

        @Test
        @DisplayName("returns the last rebuild verdict and its time, but withholds the diagnosis")
        void shouldReturnTheRebuildVerdictWithoutItsMessage() throws Exception {
            // Raised in review. For a FAILED verdict the message is the exception's own text — a
            // PSQLException naming a constraint, an S3 error naming the bucket — and this endpoint
            // is the one place a tenant user could read it. The owner cannot request a rebuild
            // anyway (that route is ROLE_ADMIN), so the outcome and its time are the whole of what
            // this projection owes them.
            seedSyncState(OWNED_SITE);
            seedRebuildVerdict(OWNED_SITE, "FAILED", "PSQLException: duplicate key value violates ...");

            mockMvc.perform(get(USER_URL.formatted(OWNED_SITE))
                            .header("Authorization", "Bearer " + MOCK_USER_JWT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lastRebuildOutcome").value("FAILED"))
                    .andExpect(jsonPath("$.lastRebuildOutcomeAt").isNotEmpty())
                    .andExpect(jsonPath("$.lastRebuildMessage").value(nullValue()));
        }

        @Test
        @DisplayName("names when the scheduled checkpoint build next runs")
        void shouldNameTheNextScheduledBuild() throws Exception {
            // Issue #213: a site whose first checkpoint is not due yet used to read as a backlog,
            // because nothing on the projection could say when the wait ends. The value is the next
            // occurrence of delta.checkpoint.cron, so it is always in the future.
            seedSyncState(OWNED_SITE);

            String body = mockMvc.perform(get(USER_URL.formatted(OWNED_SITE))
                            .header("Authorization", "Bearer " + MOCK_USER_JWT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextCheckpointBuildAt").isNotEmpty())
                    .andReturn().getResponse().getContentAsString();

            assertThat(Instant.parse(JsonPath.read(body, "$.nextCheckpointBuildAt").toString()))
                    .isAfter(Instant.now());
        }

        @Test
        @DisplayName("returns 404 when the site has no sync state row yet")
        void shouldReturn404WhenNoSyncState() throws Exception {
            mockMvc.perform(get(USER_URL.formatted(OWNED_SITE))
                            .header("Authorization", "Bearer " + MOCK_USER_JWT))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("returns 403 for a site owned by another account")
        void shouldReturn403ForForeignSite() throws Exception {
            seedSyncState(FOREIGN_SITE);

            mockMvc.perform(get(USER_URL.formatted(FOREIGN_SITE))
                            .header("Authorization", "Bearer " + MOCK_USER_JWT))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("returns 404 for an unknown site")
        void shouldReturn404ForUnknownSite() throws Exception {
            mockMvc.perform(get(USER_URL.formatted(UUID.randomUUID()))
                            .header("Authorization", "Bearer " + MOCK_USER_JWT))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("returns 401 without authentication")
        void shouldReturn401WithoutAuth() throws Exception {
            mockMvc.perform(get(USER_URL.formatted(OWNED_SITE)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/sites/{siteId}/delta/sync-state (admin)")
    class AdminSyncState {

        @Test
        @DisplayName("returns 200 for any site with admin role")
        void shouldReturnSyncStateForAdmin() throws Exception {
            seedSyncState(FOREIGN_SITE);

            mockMvc.perform(get(ADMIN_URL.formatted(FOREIGN_SITE))
                            .header("Authorization", "Bearer " + MOCK_ADMIN_JWT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lastAppliedSeq").value(4821))
                    .andExpect(jsonPath("$.rebuildRequested").value(true));
        }

        @Test
        @DisplayName("returns the last rebuild verdict with its diagnosis")
        void shouldReturnTheRebuildVerdictWithItsMessage() throws Exception {
            seedSyncState(FOREIGN_SITE);
            seedRebuildVerdict(FOREIGN_SITE, "DEFERRED", "another checkpoint build held the fold budget");

            mockMvc.perform(get(ADMIN_URL.formatted(FOREIGN_SITE))
                            .header("Authorization", "Bearer " + MOCK_ADMIN_JWT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lastRebuildOutcome").value("DEFERRED"))
                    .andExpect(jsonPath("$.lastRebuildOutcomeAt").isNotEmpty())
                    .andExpect(jsonPath("$.lastRebuildMessage")
                            .value("another checkpoint build held the fold budget"));
        }

        @Test
        @DisplayName("returns the last scheduled-build abort with its diagnosis")
        void shouldReturnTheCheckpointBuildAbortWithItsMessage() throws Exception {
            seedSyncState(FOREIGN_SITE);
            seedCheckpointBuildAbort(FOREIGN_SITE, "FRAME_TOO_LARGE",
                    "the reload frame crossed delta.checkpoint.max-frame-temp-bytes");

            mockMvc.perform(get(ADMIN_URL.formatted(FOREIGN_SITE))
                            .header("Authorization", "Bearer " + MOCK_ADMIN_JWT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lastCheckpointBuildAbort").value("FRAME_TOO_LARGE"))
                    .andExpect(jsonPath("$.lastCheckpointBuildAbortAt").isNotEmpty())
                    .andExpect(jsonPath("$.lastCheckpointBuildMessage")
                            .value("the reload frame crossed delta.checkpoint.max-frame-temp-bytes"));
        }

        @Test
        @DisplayName("names when the scheduled checkpoint build next runs")
        void shouldNameTheNextScheduledBuild() throws Exception {
            seedSyncState(FOREIGN_SITE);

            mockMvc.perform(get(ADMIN_URL.formatted(FOREIGN_SITE))
                            .header("Authorization", "Bearer " + MOCK_ADMIN_JWT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextCheckpointBuildAt").isNotEmpty());
        }

        @Test
        @DisplayName("returns 404 when the site has no sync state row yet")
        void shouldReturn404WhenNoSyncState() throws Exception {
            mockMvc.perform(get(ADMIN_URL.formatted(OWNED_SITE))
                            .header("Authorization", "Bearer " + MOCK_ADMIN_JWT))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("returns 403 for a non-admin user")
        void shouldReturn403ForUserRole() throws Exception {
            mockMvc.perform(get(ADMIN_URL.formatted(OWNED_SITE))
                            .header("Authorization", "Bearer " + MOCK_USER_JWT))
                    .andExpect(status().isForbidden());
        }
    }
}
