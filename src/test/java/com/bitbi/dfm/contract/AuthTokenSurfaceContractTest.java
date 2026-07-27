package com.bitbi.dfm.contract;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.bitbi.dfm.shared.api.ApiRoutes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract for how a client obtains a token.
 * <p>
 * Replaces {@code AuthContractTest}, which described Basic Auth token issuance at
 * {@code POST /api/v1/auth/token}. That endpoint ({@code AuthController}) was unreachable — the
 * Order 5 {@code /api/v1/**} OAuth2 chain matched the path before the default chain, so Spring
 * Security answered 401 and the controller never ran — and has been deleted. The old tests were
 * therefore disabled for a surface that could not work; asserting the surface is gone, and that the
 * live one still works, is the coverage worth keeping.
 * </p>
 * <p>
 * The Device Authorization Flow is now the only way a client gets a token.
 * </p>
 */
@DisplayName("Client token acquisition surface")
class AuthTokenSurfaceContractTest extends BaseIntegrationTest {

    private static final String REMOVED_AUTH_TOKEN_PATH = "/api/v1/auth/token";

    // Qualified by name: actuator contributes a second RequestMappingHandlerMapping.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    /**
     * No controller maps the legacy Basic Auth token path any more.
     * <p>
     * The behavioural assertions below would pass even with the controller present (Spring Security
     * shadows it), so the removal itself is asserted against the handler mappings.
     * </p>
     */
    @Test
    @DisplayName("no handler is mapped for the legacy token path")
    void shouldNotMapLegacyTokenPath() {
        List<String> patterns = handlerMapping.getHandlerMethods().keySet().stream()
                .map(RequestMappingInfo::toString)
                .filter(info -> info.contains(REMOVED_AUTH_TOKEN_PATH))
                .toList();

        assertTrue(patterns.isEmpty(),
                "no controller may map " + REMOVED_AUTH_TOKEN_PATH + ", found: " + patterns);
    }

    /**
     * The removed path stays denied — not 200, and not a 500 from an unmapped-but-permitted route.
     * <p>
     * It keeps answering 401 because the Order 5 chain still matches {@code /api/v1/**} and requires
     * OAuth2; that is the desired end state, not an accident to be "fixed" by re-opening the path.
     * </p>
     */
    @Test
    @DisplayName("legacy token path answers 401 for valid Basic Auth credentials")
    void shouldRejectBasicAuthOnRemovedPath() throws Exception {
        String credentials = Base64.getEncoder()
                .encodeToString("store-01.example.com:valid-secret-uuid".getBytes());

        mockMvc.perform(post(REMOVED_AUTH_TOKEN_PATH)
                        .header("Authorization", "Basic " + credentials)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    /**
     * Device Authorization Flow initiation stays publicly reachable.
     * <p>
     * A malformed body must be answered by the controller (4xx), never by security (401/403) —
     * that is what proves the route is still open to unauthenticated clients.
     * </p>
     */
    @Test
    @DisplayName("device flow: /authorize stays public")
    void deviceAuthorizeShouldStayPublic() throws Exception {
        mockMvc.perform(post(ApiRoutes.DEVICE_AUTHORIZATION_AUTHORIZE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertTrue(status != 401 && status != 403,
                            "device flow initiation must stay public, got HTTP " + status);
                });
    }

    /**
     * Device Authorization Flow polling stays publicly reachable.
     */
    @Test
    @DisplayName("device flow: /token stays public")
    void deviceTokenPollingShouldStayPublic() throws Exception {
        mockMvc.perform(post(ApiRoutes.DEVICE_AUTHORIZATION_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertTrue(status != 401 && status != 403,
                            "device flow polling must stay public, got HTTP " + status);
                });
    }

    /**
     * Token renewal stays publicly reachable — the refresh token is the credential.
     */
    @Test
    @DisplayName("device flow: /auth/refresh stays public")
    void deviceRefreshShouldStayPublic() throws Exception {
        mockMvc.perform(post(ApiRoutes.DEVICE_AUTH_REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"invalid-token\"}"))
                .andExpect(status().isBadRequest());
    }
}
