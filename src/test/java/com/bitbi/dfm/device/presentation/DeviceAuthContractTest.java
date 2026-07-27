package com.bitbi.dfm.device.presentation;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.bitbi.dfm.shared.api.ApiRoutes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for the Device API authentication surface ({@code /api/v1/device/auth/**}).
 * <p>
 * {@link DeviceAuthController} serves exactly one operation: {@code POST /refresh} (Auth V2 token
 * rotation, public because the refresh token is the credential). Basic Auth token generation is
 * <b>not</b> part of this surface — nor of any other: the legacy {@code /api/v1/auth/token} endpoint
 * has been deleted, and {@code AuthTokenSurfaceContractTest} pins that clients now obtain tokens
 * solely through the Device Authorization Flow.
 * </p>
 * <p>
 * These tests used to assert Basic Auth token issuance at {@code /api/v1/device/auth/token}, a path
 * that never had a controller yet was permitAll in two filter chains; they were disabled and
 * therefore never caught the discrepancy. They now pin the real surface.
 * </p>
 *
 * @see DeviceAuthController
 */
@DisplayName("Device API - Authentication Surface Contract Tests")
class DeviceAuthContractTest extends BaseIntegrationTest {

    private static final String REMOVED_DEVICE_TOKEN_PATH = ApiRoutes.DEVICE_AUTH + "/token";

    /**
     * The refresh endpoint is the one public operation on this surface.
     * <p>
     * <b>Given</b>: No Authorization header<br>
     * <b>When</b>: POST /api/v1/device/auth/refresh with an unusable refresh token<br>
     * <b>Then</b>: 400 Bad Request — the endpoint is reachable without authentication (not 401)
     * </p>
     */
    @Test
    @DisplayName("Refresh endpoint should be reachable without authentication")
    void refreshEndpointShouldBePublic() throws Exception {
        mockMvc.perform(post(ApiRoutes.DEVICE_AUTH_REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"invalid-token\"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * The device token path is not served and must not be publicly reachable.
     * <p>
     * <b>Given</b>: Valid site credentials as Basic Auth<br>
     * <b>When</b>: POST /api/v1/device/auth/token<br>
     * <b>Then</b>: 401 Unauthorized and no token in the body
     * </p>
     */
    @Test
    @DisplayName("Device token path should not issue a token for Basic Auth credentials")
    void deviceTokenPathShouldNotIssueToken() throws Exception {
        String credentials = Base64.getEncoder()
                .encodeToString("store-01.example.com:valid-secret-uuid".getBytes());

        mockMvc.perform(post(REMOVED_DEVICE_TOKEN_PATH)
                        .header("Authorization", "Basic " + credentials)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    /**
     * The removed path must not be anonymously reachable.
     * <p>
     * <b>Given</b>: No credentials<br>
     * <b>When</b>: POST /api/v1/device/auth/token<br>
     * <b>Then</b>: 401 Unauthorized (the Device API chain requires authentication)
     * </p>
     */
    @Test
    @DisplayName("Device token path should not be public")
    void deviceTokenPathShouldNotBePublic() throws Exception {
        mockMvc.perform(post(REMOVED_DEVICE_TOKEN_PATH)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }
}
