package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B10 (023) — contract tests for the bulk site-list sync health endpoints:
 * GET /api/v1/account/sites/delta/health (owner) and
 * GET /api/v1/accounts/{accountId}/sites/delta/health (admin).
 * One request covers all sites of the account; V2 is the sole client API version.
 */
@DisplayName("Delta Sync Health REST Contract Tests")
@Sql({"/test-data.sql", "/test-data-v2-site.sql"})
class DeltaSyncHealthRestContractTest extends BaseIntegrationTest {

    private static final String MOCK_USER_JWT = "mock.user.jwt.token";
    private static final String MOCK_ADMIN_JWT = "mock.admin.jwt.token";

    /** Test account 1 (the mock tokens' account). */
    private static final UUID ACCOUNT = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    /** store-v2.example.com — the site with sync-state data in this fixture. */
    private static final UUID V2_SITE = UUID.fromString("0199bab0-4444-4444-4444-444444444444");

    private static final String USER_URL = "/api/v1/account/sites/delta/health";
    private static final String ADMIN_URL = "/api/v1/accounts/%s/sites/delta/health";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("returns one entry per site and includes sync data")
    void shouldReturnHealthForV2SitesOnly() throws Exception {
        jdbc.update("""
                INSERT INTO site_sync_state (site_id, last_applied_seq, last_checkpoint_seq, schema_version, updated_at)
                VALUES (?, 4821, 3200, 3, '2026-07-05 12:00:00')
                """, V2_SITE);

        mockMvc.perform(get(USER_URL)
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[?(@.siteId == '" + V2_SITE + "')].hasSyncState", hasItem(true)))
                .andExpect(jsonPath("$[?(@.siteId == '" + V2_SITE + "')].lastAppliedSeq", hasItem(4821)))
                .andExpect(jsonPath("$[?(@.siteId == '" + V2_SITE + "')].lastCheckpointSeq", hasItem(3200)));
    }

    @Test
    @DisplayName("marks sites whose client never connected with hasSyncState=false")
    void shouldMarkNeverConnectedSites() throws Exception {
        mockMvc.perform(get(USER_URL)
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[?(@.siteId == '" + V2_SITE + "')].hasSyncState", hasItem(false)));
    }

    @Test
    @DisplayName("admin variant returns any account's health; user role gets 403")
    void adminVariantRoleGating() throws Exception {
        mockMvc.perform(get(ADMIN_URL.formatted(ACCOUNT))
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)));

        mockMvc.perform(get(ADMIN_URL.formatted(ACCOUNT))
                        .header("Authorization", "Bearer " + MOCK_USER_JWT))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("returns 401 without authentication")
    void unauthenticated401() throws Exception {
        mockMvc.perform(get(USER_URL))
                .andExpect(status().isUnauthorized());
    }
}
