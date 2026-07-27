package com.bitbi.dfm.device.presentation;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.bitbi.dfm.shared.api.ApiRoutes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Product semantics: <b>one site = one client installation = at most one live token session.</b>
 * <p>
 * A site is created by the Configurator wizard and {@code getOrCreateSite} keys on
 * {@code (accountId, siteName)}, so re-running the Device Authorization Flow with the same site
 * name is a <em>re-installation of the same logical device</em>, not a second parallel device.
 * Completing it therefore supersedes the previous session: the older refresh token dies at once.
 * </p>
 * <p>
 * This is a security requirement, not bookkeeping. Without the site-wide revocation on
 * re-authorization, the refresh token left on the previous installation would stay usable for its
 * full 90-day TTL - on a machine that may have been decommissioned, re-imaged or handed on.
 * </p>
 * <p>
 * If someone removes {@code revokeAllForSite(...)} from
 * {@code DeviceAuthorizationService.verify(...)}, this test must fail: the superseded token would
 * keep working. Note that {@code family_id} scopes <em>reuse detection</em> (see
 * {@link DeviceAuthRefreshContractTest}) and does not make sessions coexist.
 * </p>
 */
@DisplayName("Device Flow - Session Supersede Contract Tests")
class DeviceFlowSessionSupersedeContractTest extends BaseIntegrationTest {

    /**
     * Suffix matters: test-data.sql purges sites with this domain suffix before each test.
     */
    private static final String SITE_NAME = "device-flow-supersede.example.com";

    /**
     * Mock Auth0 user bound to test account 1 in test-data.sql - plays the approving owner.
     */
    private static final String OWNER_TOKEN = "mock.user.jwt.token";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /**
     * One completed Device Authorization Flow.
     */
    private record DeviceSession(UUID siteId, String refreshToken) {
    }

    /**
     * device_authorizations references sites(id) without a cascade, so the rows this test creates
     * would block test-data.sql from purging the site before the next test method.
     */
    @org.junit.jupiter.api.AfterEach
    void purgeDeviceAuthorizations() {
        jdbcTemplate.update(
                "DELETE FROM device_authorizations WHERE site_id IN (SELECT id FROM sites WHERE site_name = ?)",
                SITE_NAME);
    }

    @Test
    @DisplayName("Re-running the device flow for the same site supersedes the previous session")
    void shouldSupersedePreviousSessionOnReAuthorization() throws Exception {
        // Given: a client installation completed the device flow
        DeviceSession first = completeDeviceFlow();

        // When: the same site name is authorized again (client re-installed by the wizard)
        DeviceSession second = completeDeviceFlow();

        // Then: it is the same logical site, not a second one
        assertThat(second.siteId())
                .as("getOrCreateSite keys on (accountId, siteName): re-authorization must reattach "
                        + "to the same site rather than create a parallel one")
                .isEqualTo(first.siteId());

        // ...and the superseded installation's refresh token is dead immediately, so a token left
        // behind on a decommissioned machine cannot outlive the re-installation
        mockMvc.perform(refreshRequest(first.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("refresh_token_revoked"));

        // ...while the current installation works and keeps rotating normally
        String rotated = refreshFor(second.refreshToken());
        String rotatedAgain = refreshFor(rotated);

        assertThat(rotatedAgain).isNotBlank();
    }

    @Test
    @DisplayName("Superseding a session does not break the new session's own rotation family")
    void shouldLeaveNewSessionFamilyIntactAfterSupersede() throws Exception {
        completeDeviceFlow();
        DeviceSession current = completeDeviceFlow();

        // The surviving session rotates through several hops without tripping reuse detection
        String token = current.refreshToken();
        for (int hop = 0; hop < 3; hop++) {
            token = refreshFor(token);
        }

        mockMvc.perform(refreshRequest(token))
                .andExpect(status().isOk());
    }

    /**
     * Drive a full Device Authorization Flow: authorize -> owner approves -> device polls token.
     */
    private DeviceSession completeDeviceFlow() throws Exception {
        String authorizeResponse = mockMvc.perform(post(ApiRoutes.DEVICE_AUTHORIZATION_AUTHORIZE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "siteName", SITE_NAME,
                                "siteDescription", "Device flow supersede test",
                                "siteType", "DBF"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode authorization = objectMapper.readTree(authorizeResponse);
        String deviceCode = authorization.get("deviceCode").asText();
        String userCode = authorization.get("userCode").asText();

        mockMvc.perform(post(ApiRoutes.DEVICE_AUTHORIZATION_VERIFY)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + OWNER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userCode", userCode,
                                "action", "approve"))))
                .andExpect(status().isOk());

        String tokenResponse = mockMvc.perform(post(ApiRoutes.DEVICE_AUTHORIZATION_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("deviceCode", deviceCode))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode token = objectMapper.readTree(tokenResponse);
        return new DeviceSession(
                UUID.fromString(token.get("siteId").asText()),
                token.get("refreshToken").asText());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder refreshRequest(String token)
            throws Exception {
        return post(ApiRoutes.DEVICE_AUTH_REFRESH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", token)));
    }

    private String refreshFor(String token) throws Exception {
        String body = mockMvc.perform(refreshRequest(token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("refreshToken").asText();
    }
}
