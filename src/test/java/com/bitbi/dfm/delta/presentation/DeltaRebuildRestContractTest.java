package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.delta.application.CheckpointService;
import com.bitbi.dfm.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B7 (023) — contract tests for the forced checkpoint rebuild trigger (admin only):
 * POST /api/v1/sites/{siteId}/delta/checkpoints/rebuild.
 */
@DisplayName("Delta Checkpoint Rebuild REST Contract Tests")
class DeltaRebuildRestContractTest extends BaseIntegrationTest {

    private static final String MOCK_USER_JWT = "mock.user.jwt.token";
    private static final String MOCK_ADMIN_JWT = "mock.admin.jwt.token";

    /** store-01.example.com. */
    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");

    private static final String ADMIN_URL = "/api/v1/sites/%s/delta/checkpoints/rebuild";

    @MockitoBean
    private CheckpointService checkpointService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("returns 202, sets the rebuild flag and runs the build asynchronously")
    void shouldScheduleRebuild() throws Exception {
        when(checkpointService.buildCheckpoint(SITE, true)).thenReturn(Map.of());
        jdbc.update("DELETE FROM site_sync_state WHERE site_id = ?", SITE);

        mockMvc.perform(post(ADMIN_URL.formatted(SITE))
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("scheduled"));

        // The async build runs (and afterwards clears rebuild_requested).
        verify(checkpointService, timeout(5000)).buildCheckpoint(SITE, true);
    }

    @Test
    @DisplayName("returns 202 already-queued and skips the duplicate build while one is pending")
    void shouldShortCircuitDuplicateRebuild() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(checkpointService.buildCheckpoint(SITE, true)).thenAnswer(inv -> {
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return Map.of();
        });
        jdbc.update("DELETE FROM site_sync_state WHERE site_id = ?", SITE);

        mockMvc.perform(post(ADMIN_URL.formatted(SITE))
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("scheduled"));

        // First rebuild still running (blocked on the latch) — the duplicate must not queue.
        mockMvc.perform(post(ADMIN_URL.formatted(SITE))
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("already-queued"));

        release.countDown();
        verify(checkpointService, timeout(5000)).buildCheckpoint(SITE, true);
    }

    @Test
    @DisplayName("returns 404 for an unknown site")
    void shouldReturn404ForUnknownSite() throws Exception {
        mockMvc.perform(post(ADMIN_URL.formatted(UUID.randomUUID()))
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("returns 403 for a non-admin user")
    void shouldReturn403ForUserRole() throws Exception {
        mockMvc.perform(post(ADMIN_URL.formatted(SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("returns 401 without authentication")
    void shouldReturn401WithoutAuth() throws Exception {
        mockMvc.perform(post(ADMIN_URL.formatted(SITE)))
                .andExpect(status().isUnauthorized());
    }
}
