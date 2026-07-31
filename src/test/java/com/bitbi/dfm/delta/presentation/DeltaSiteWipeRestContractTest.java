package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests for the site history wipe endpoints (035 — issue #89):
 * {@code POST /api/v1/account/sites/{siteId}/delta/wipe} (owner) and
 * {@code POST /api/v1/sites/{siteId}/delta/wipe} (admin).
 *
 * <p>The wipe is irreversible, so the confirmation gate is part of the contract, not decoration:
 * the caller must echo the site's domain.</p>
 */
@DisplayName("Delta Site Wipe REST Contract Tests")
class DeltaSiteWipeRestContractTest extends BaseIntegrationTest {

    private static final String MOCK_USER_JWT = "mock.user.jwt.token";
    private static final String MOCK_ADMIN_JWT = "mock.admin.jwt.token";

    /** store-01.example.com — owned by test account 1. */
    private static final UUID OWNED_SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    private static final String OWNED_NAME = "store-01.example.com";
    /** store-03.example.com — owned by a different account. */
    private static final UUID FOREIGN_SITE = UUID.fromString("0199bab0-ca3b-e41c-5521-2f4b33fda8b6");

    private static final String USER_URL = "/api/v1/account/sites/%s/delta/wipe";
    private static final String ADMIN_URL = "/api/v1/sites/%s/delta/wipe";

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void closeSeededSession() {
        // The seed leaves an IN_PROGRESS batch on the site; a live session is a 409 by design, and
        // the tests that care about that re-open one explicitly.
        jdbc.update("""
                UPDATE batches SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP
                WHERE site_id = ? AND status = 'IN_PROGRESS'
                """, OWNED_SITE);
        jdbc.update("DELETE FROM site_sync_state WHERE site_id = ?", OWNED_SITE);
    }

    private static String confirm(String value) {
        return "{\"confirm\": \"" + value + "\"}";
    }

    @Test
    @DisplayName("owner wipe returns 200 with the full summary")
    void ownerWipeReturnsSummary() throws Exception {
        mockMvc.perform(post(USER_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirm(OWNED_NAME)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(1))
                .andExpect(jsonPath("$.deletedBatches").isNumber())
                .andExpect(jsonPath("$.deletedSegments").isNumber())
                .andExpect(jsonPath("$.deletedCheckpoints").isNumber())
                .andExpect(jsonPath("$.deletedFiles").isNumber())
                .andExpect(jsonPath("$.deletedSqlGenerations").isNumber())
                .andExpect(jsonPath("$.deletedErrorLogs").isNumber())
                .andExpect(jsonPath("$.deletedBytes").isNumber())
                .andExpect(jsonPath("$.s3DeleteErrors").isNumber())
                .andExpect(jsonPath("$.baselineBatchDetached").isBoolean());
    }

    @Test
    @DisplayName("the wipe removes the site's history and bumps the epoch")
    void wipeEmptiesTheSite() throws Exception {
        mockMvc.perform(post(USER_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirm(OWNED_NAME)))
                .andExpect(status().isOk());

        Long batches = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batches WHERE site_id = ?", Long.class, OWNED_SITE);
        Long generation = jdbc.queryForObject(
                "SELECT generation FROM site_sync_state WHERE site_id = ?", Long.class, OWNED_SITE);
        Boolean rebaselineRequested = jdbc.queryForObject(
                "SELECT rebaseline_requested FROM site_sync_state WHERE site_id = ?", Boolean.class, OWNED_SITE);

        org.assertj.core.api.Assertions.assertThat(batches).isZero();
        org.assertj.core.api.Assertions.assertThat(generation).isEqualTo(1L);
        // The client is told to re-baseline as well: an old client that ignores the epoch still
        // recovers, and a wipe retries until the snapshot actually commits.
        org.assertj.core.api.Assertions.assertThat(rebaselineRequested).isTrue();
    }

    @Test
    @DisplayName("a second wipe bumps the epoch again")
    void secondWipeBumpsGenerationAgain() throws Exception {
        mockMvc.perform(post(USER_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirm(OWNED_NAME)))
                .andExpect(status().isOk());

        mockMvc.perform(post(USER_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirm(OWNED_NAME)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.deletedBatches").value(0));
    }

    @Test
    @DisplayName("admin wipe returns 200 for any site")
    void adminWipeReturnsSummary() throws Exception {
        mockMvc.perform(post(ADMIN_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirm(OWNED_NAME)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(1));
    }

    @Test
    @DisplayName("a confirmation that does not match the site name is rejected (400)")
    void wrongConfirmationRejected() throws Exception {
        mockMvc.perform(post(USER_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirm("store-02.example.com")))
                .andExpect(status().isBadRequest());

        Long batches = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batches WHERE site_id = ?", Long.class, OWNED_SITE);
        org.assertj.core.api.Assertions.assertThat(batches).isPositive();
    }

    @Test
    @DisplayName("a missing confirmation is rejected (400)")
    void missingConfirmationRejected() throws Exception {
        mockMvc.perform(post(USER_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a missing body is rejected (400)")
    void missingBodyRejected() throws Exception {
        mockMvc.perform(post(USER_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a live ingestion session blocks the wipe (409 session-in-progress)")
    void liveSessionBlocksWipe() throws Exception {
        jdbc.update("""
                INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count,
                                     total_size, has_errors, started_at, last_activity_at, session_mode)
                VALUES (?, 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', ?, 'IN_PROGRESS', 'x/', 0, 0, false,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'CONTINUOUS')
                """, UUID.randomUUID(), OWNED_SITE);

        mockMvc.perform(post(USER_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirm(OWNED_NAME)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("session-in-progress"));
    }

    @Test
    @DisplayName("a session abandoned past the liveness window does not block the wipe")
    void staleSessionDoesNotBlockWipe() throws Exception {
        jdbc.update("""
                INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count,
                                     total_size, has_errors, started_at, last_activity_at, session_mode)
                VALUES (?, 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', ?, 'IN_PROGRESS', 'x/', 0, 0, false,
                        CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '2 days',
                        'CONTINUOUS')
                """, UUID.randomUUID(), OWNED_SITE);

        mockMvc.perform(post(USER_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirm(OWNED_NAME)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("owner cannot wipe a foreign site (403)")
    void ownerForeignSiteForbidden() throws Exception {
        mockMvc.perform(post(USER_URL.formatted(FOREIGN_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirm("store-03.example.com")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("non-admin on the admin route gets 403")
    void userOnAdminRouteForbidden() throws Exception {
        mockMvc.perform(post(ADMIN_URL.formatted(OWNED_SITE))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirm(OWNED_NAME)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("returns 404 for an unknown site")
    void unknownSite404() throws Exception {
        mockMvc.perform(post(USER_URL.formatted(UUID.randomUUID()))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirm(OWNED_NAME)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("returns 401 without authentication")
    void unauthenticated401() throws Exception {
        mockMvc.perform(post(USER_URL.formatted(OWNED_SITE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirm(OWNED_NAME)))
                .andExpect(status().isUnauthorized());
    }
}
