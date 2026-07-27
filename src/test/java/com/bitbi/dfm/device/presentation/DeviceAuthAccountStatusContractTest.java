package com.bitbi.dfm.device.presentation;

import com.bitbi.dfm.auth.infrastructure.JwtTokenProvider;
import com.bitbi.dfm.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests for account/site status enforcement on the Device API validation path.
 * <p>
 * A JWT is only a claim about the past. Every request re-checks that the site and its
 * parent account are still active, so deactivating either takes effect immediately
 * instead of waiting for the access token to expire.
 * </p>
 */
@DisplayName("Device API - Account/Site Status Contract Tests")
class DeviceAuthAccountStatusContractTest extends BaseIntegrationTest {

    /** store-03 - active site under active "Test Account 2". */
    private static final UUID STORE_03_SITE_ID =
            UUID.fromString("0199bab0-ca3b-e41c-5521-2f4b33fda8b6");
    private static final UUID ACCOUNT_2_ID =
            UUID.fromString("0199bab1-fad2-bf76-c478-eae1f61e1c17");

    /** orphaned-store - active site whose parent account is already inactive in test-data.sql. */
    private static final UUID ORPHANED_SITE_ID =
            UUID.fromString("0199bab0-3333-3333-3333-333333333333");
    private static final UUID INACTIVE_ACCOUNT_ID =
            UUID.fromString("0199bab2-3cbd-cc95-a989-57ba51d258c8");

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Should reject a live access token as soon as the parent account is deactivated")
    void shouldRejectLiveTokenAfterAccountDeactivation() throws Exception {
        // Given: a token minted while both site and account are active
        String token = mintToken(STORE_03_SITE_ID, ACCOUNT_2_ID);

        // Sanity: the token authenticates (404 = authenticated, batch simply does not exist)
        mockMvc.perform(getSomeBatch(token))
                .andExpect(status().isNotFound());

        // When: the parent account is deactivated
        jdbcTemplate.update("UPDATE accounts SET is_active = false WHERE id = ?", ACCOUNT_2_ID);

        // Then: the very same, still-unexpired token is rejected immediately
        mockMvc.perform(getSomeBatch(token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should reject a token issued for a site whose parent account is inactive")
    void shouldRejectTokenForSiteWithInactiveParentAccount() throws Exception {
        String token = mintToken(ORPHANED_SITE_ID, INACTIVE_ACCOUNT_ID);

        mockMvc.perform(getSomeBatch(token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should still reject a token when only the site is deactivated (unchanged behaviour)")
    void shouldRejectTokenAfterSiteDeactivation() throws Exception {
        String token = mintToken(STORE_03_SITE_ID, ACCOUNT_2_ID);

        jdbcTemplate.update("UPDATE sites SET is_active = false WHERE id = ?", STORE_03_SITE_ID);

        mockMvc.perform(getSomeBatch(token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should accept a token when both site and account are active")
    void shouldAcceptTokenWhenSiteAndAccountActive() throws Exception {
        String token = mintToken(STORE_03_SITE_ID, ACCOUNT_2_ID);

        // 404, not 401: authentication succeeded, the random batch just does not exist
        mockMvc.perform(getSomeBatch(token))
                .andExpect(status().isNotFound());
    }

    private String mintToken(UUID siteId, UUID accountId) {
        return jwtTokenProvider.generateToken(siteId, accountId).token();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder getSomeBatch(String token) {
        return get("/api/v1/device/batches/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
