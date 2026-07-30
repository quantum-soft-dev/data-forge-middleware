package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B8 (023) — contract tests for the re-baseline trigger:
 * POST /api/v1/account/sites/{siteId}/delta/rebaseline (owner) and
 * POST /api/v1/sites/{siteId}/delta/rebaseline (admin). Sets the persistent
 * rebaseline_requested flag (B2) so GetSyncState answers NEED_REBASELINE on next connect.
 * <p>
 * DELETE on the same paths takes the request back while the client has not started its
 * FULL_SNAPSHOT session yet (issue #84).
 */
@DisplayName("Delta Rebaseline REST Contract Tests")
class DeltaRebaselineRestContractTest extends BaseIntegrationTest {

    private static final String MOCK_USER_JWT = "mock.user.jwt.token";
    private static final String MOCK_ADMIN_JWT = "mock.admin.jwt.token";

    /** store-01.example.com — owned by test account 1. */
    private static final UUID OWNED_SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    /** store-03.example.com — owned by a different account. */
    private static final UUID FOREIGN_SITE = UUID.fromString("0199bab0-ca3b-e41c-5521-2f4b33fda8b6");

    private static final String USER_URL = "/api/v1/account/sites/%s/delta/rebaseline";
    private static final String ADMIN_URL = "/api/v1/sites/%s/delta/rebaseline";
    private static final String USER_SYNC_STATE_URL = "/api/v1/account/sites/%s/delta/sync-state";

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetSyncState() {
        jdbc.update("DELETE FROM site_sync_state WHERE site_id IN (?, ?)", OWNED_SITE, FOREIGN_SITE);
        jdbc.update("""
                INSERT INTO site_sync_state (site_id, last_applied_seq, last_checkpoint_seq, schema_version, updated_at)
                VALUES (?, 100, 50, 1, CURRENT_TIMESTAMP)
                """, OWNED_SITE);
    }

    /** Turn the seeded open batch into a session of the given Delta v2 mode (null = pre-V47 batch). */
    private void setOpenSessionMode(String sessionMode) {
        jdbc.update("UPDATE batches SET session_mode = ? WHERE site_id = ? AND status = 'IN_PROGRESS'",
                sessionMode, OWNED_SITE);
    }

    private void closeActiveSession() {
        jdbc.update("""
                UPDATE batches SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP
                WHERE site_id = ? AND status = 'IN_PROGRESS'
                """, OWNED_SITE);
    }

    /** Pretend GetSyncState has already handed NEED_REBASELINE to the client. */
    private void markClientNotified() {
        jdbc.update("UPDATE site_sync_state SET rebaseline_notified_at = CURRENT_TIMESTAMP WHERE site_id = ?",
                OWNED_SITE);
    }

    @Test
    @DisplayName("owner request returns 202 and raises the rebaselineRequested flag")
    void ownerRequestSetsFlag() throws Exception {
        mockMvc.perform(post(USER_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("requested"));

        mockMvc.perform(get(USER_SYNC_STATE_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rebaselineRequested").value(true));
    }

    @Test
    @DisplayName("admin request returns 202 for any site")
    void adminRequestSetsFlag() throws Exception {
        mockMvc.perform(post(ADMIN_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("requested"));
    }

    @Test
    @DisplayName("owner cannot re-baseline a foreign site (403)")
    void ownerForeignSiteForbidden() throws Exception {
        mockMvc.perform(post(USER_URL.formatted(FOREIGN_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("non-admin on the admin route gets 403")
    void userOnAdminRouteForbidden() throws Exception {
        mockMvc.perform(post(ADMIN_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("returns 404 for an unknown site")
    void unknownSite404() throws Exception {
        mockMvc.perform(post(USER_URL.formatted(UUID.randomUUID()))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("returns 401 without authentication")
    void unauthenticated401() throws Exception {
        mockMvc.perform(post(USER_URL.formatted(OWNED_SITE)))
                .andExpect(status().isUnauthorized());
    }

    // --- #84: cancelling a requested re-baseline ---

    @Test
    @DisplayName("owner cancel clears the flag and leaves the rest of the row as it was")
    void ownerCancelRestoresPreviousState() throws Exception {
        mockMvc.perform(post(USER_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isAccepted());

        mockMvc.perform(delete(USER_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));

        mockMvc.perform(get(USER_SYNC_STATE_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rebaselineRequested").value(false))
                .andExpect(jsonPath("$.lastAppliedSeq").value(100))
                .andExpect(jsonPath("$.lastCheckpointSeq").value(50))
                .andExpect(jsonPath("$.schemaVersion").value(1));
    }

    @Test
    @DisplayName("cancel reports snapshot-in-progress while a FULL_SNAPSHOT uploads, and the state shows it")
    void cancelDuringSnapshotReportsSnapshotInProgress() throws Exception {
        // The flag outlives the whole FULL_SNAPSHOT session (the wipe runs at commit), so the
        // running snapshot keeps its own intent — the answer must not claim success.
        mockMvc.perform(post(USER_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isAccepted());
        setOpenSessionMode("FULL_SNAPSHOT");

        mockMvc.perform(delete(USER_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("snapshot-in-progress"));

        // The flag is still cleared (no second snapshot is ordered), and the running one stays
        // visible in the projection instead of only in the one-shot response.
        mockMvc.perform(get(USER_SYNC_STATE_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rebaselineRequested").value(false))
                .andExpect(jsonPath("$.snapshotInProgress").value(true));
    }

    @Test
    @DisplayName("a second cancel during the same snapshot still reports snapshot-in-progress")
    void secondCancelDuringSnapshotDoesNotReportNotRequested() throws Exception {
        mockMvc.perform(post(USER_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isAccepted());
        setOpenSessionMode("FULL_SNAPSHOT");

        mockMvc.perform(delete(USER_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(jsonPath("$.status").value("snapshot-in-progress"));

        // Another operator, whose pill was up to one poll stale: "nothing to cancel" would be the
        // opposite conclusion about the very same running snapshot.
        mockMvc.perform(delete(USER_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("snapshot-in-progress"));
    }

    @Test
    @DisplayName("an ordinary delta session does not mask a successful cancellation")
    void cancelDuringContinuousSessionStillReportsCancelled() throws Exception {
        // 029: a CONTINUOUS session holds its batch IN_PROGRESS for hours, and the one-active-batch
        // rule means no FULL_SNAPSHOT can be running behind it.
        mockMvc.perform(post(USER_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isAccepted());
        setOpenSessionMode("CONTINUOUS");

        mockMvc.perform(delete(USER_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));

        mockMvc.perform(get(USER_SYNC_STATE_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(jsonPath("$.snapshotInProgress").value(false));
    }

    @Test
    @DisplayName("cancel reports client-notified once NEED_REBASELINE has gone out")
    void cancelAfterClientWasNotifiedIsNotReportedAsSuccess() throws Exception {
        // The client holds the order and may open its snapshot at any moment — no batch exists yet,
        // so nothing is observable and `cancelled` would promise a re-upload was averted.
        mockMvc.perform(post(USER_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isAccepted());
        closeActiveSession();
        markClientNotified();

        mockMvc.perform(delete(USER_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("client-notified"));
    }

    @Test
    @DisplayName("cancelling with no pending request is a no-op (200 not-requested)")
    void cancelWithoutPendingRequestIsNoOp() throws Exception {
        mockMvc.perform(delete(USER_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("not-requested"));
    }

    @Test
    @DisplayName("admin cancel returns 200 for any site")
    void adminCancelClearsFlag() throws Exception {
        mockMvc.perform(post(ADMIN_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT))
                .andExpect(status().isAccepted());

        mockMvc.perform(delete(ADMIN_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
    }

    @Test
    @DisplayName("owner cannot cancel a foreign site's re-baseline (403)")
    void ownerCancelForeignSiteForbidden() throws Exception {
        mockMvc.perform(delete(USER_URL.formatted(FOREIGN_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("non-admin on the admin cancel route gets 403")
    void userOnAdminCancelRouteForbidden() throws Exception {
        mockMvc.perform(delete(ADMIN_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("cancel returns 404 for an unknown site")
    void cancelUnknownSite404() throws Exception {
        mockMvc.perform(delete(USER_URL.formatted(UUID.randomUUID()))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("cancel returns 401 without authentication")
    void cancelUnauthenticated401() throws Exception {
        mockMvc.perform(delete(USER_URL.formatted(OWNED_SITE)))
                .andExpect(status().isUnauthorized());
    }
}
