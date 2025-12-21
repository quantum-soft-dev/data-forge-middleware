package com.bitbi.dfm.plugin.contract;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.bitbi.dfm.plugin.application.PluginActivationService;
import com.bitbi.dfm.plugin.domain.exception.PluginNotActivatedException;
import com.bitbi.dfm.plugin.domain.exception.PluginNotFoundException;
import com.bitbi.dfm.shared.api.ApiRoutes;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for Plugin Deactivation API endpoints.
 *
 * <p>Tests DELETE /api/v1/plugins/{pluginId}/deactivate endpoint:
 * <ul>
 *   <li>TC01: Successful deactivation returns 204</li>
 *   <li>TC02: Plugin not activated returns 403</li>
 *   <li>TC03: Plugin not found returns 404</li>
 *   <li>TC04: Already deactivated returns 403</li>
 *   <li>TC05: Unauthenticated returns 401</li>
 * </ul>
 *
 * @see com.bitbi.dfm.plugin.presentation.PluginController
 */
@DisplayName("Plugin Deactivation Contract Tests")
class PluginDeactivationContractTest extends BaseIntegrationTest {

    @MockitoBean
    private PluginActivationService pluginActivationService;

    @MockitoBean
    private AuthorizationHelper authorizationHelper;

    private static final String MOCK_USER_JWT_TOKEN = "mock.user.jwt.token";
    private static final UUID TEST_ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final String PLUGIN_ID = "bit-bi";

    @BeforeEach
    void setUp() {
        when(authorizationHelper.getAuthenticatedAccountId()).thenReturn(TEST_ACCOUNT_ID);
    }

    @Nested
    @DisplayName("DELETE /api/v1/plugins/{pluginId}/deactivate")
    class DeactivatePlugin {

        @Test
        @DisplayName("TC01: Should return 204 No Content for successful deactivation")
        void shouldReturn204ForSuccessfulDeactivation() throws Exception {
            // Given
            doNothing().when(pluginActivationService).deactivate(TEST_ACCOUNT_ID, PLUGIN_ID);

            // When / Then
            mockMvc.perform(delete(ApiRoutes.PLUGINS + "/{pluginId}/deactivate", PLUGIN_ID)
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN))
                .andExpect(status().isNoContent())
                .andExpect(content().string(emptyOrNullString()));

            verify(pluginActivationService).deactivate(TEST_ACCOUNT_ID, PLUGIN_ID);
        }

        @Test
        @DisplayName("TC02: Should return 403 Forbidden when plugin not activated for account")
        void shouldReturn403WhenPluginNotActivated() throws Exception {
            // Given
            doThrow(new PluginNotActivatedException(PLUGIN_ID, TEST_ACCOUNT_ID))
                .when(pluginActivationService).deactivate(TEST_ACCOUNT_ID, PLUGIN_ID);

            // When / Then
            mockMvc.perform(delete(ApiRoutes.PLUGINS + "/{pluginId}/deactivate", PLUGIN_ID)
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("not activated")));
        }

        @Test
        @DisplayName("TC03: Should return 404 Not Found when plugin not in registry")
        void shouldReturn404WhenPluginNotFound() throws Exception {
            // Given
            String unknownPluginId = "unknown-plugin";
            doThrow(new PluginNotFoundException(unknownPluginId))
                .when(pluginActivationService).deactivate(TEST_ACCOUNT_ID, unknownPluginId);

            // When / Then
            mockMvc.perform(delete(ApiRoutes.PLUGINS + "/{pluginId}/deactivate", unknownPluginId)
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("not found")));
        }

        @Test
        @DisplayName("TC04: Should return 403 Forbidden when plugin already deactivated")
        void shouldReturn403WhenPluginAlreadyDeactivated() throws Exception {
            // Given
            doThrow(new PluginNotActivatedException(PLUGIN_ID, TEST_ACCOUNT_ID,
                    "Plugin '" + PLUGIN_ID + "' is already deactivated for account " + TEST_ACCOUNT_ID))
                .when(pluginActivationService).deactivate(TEST_ACCOUNT_ID, PLUGIN_ID);

            // When / Then
            mockMvc.perform(delete(ApiRoutes.PLUGINS + "/{pluginId}/deactivate", PLUGIN_ID)
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("already deactivated")));
        }

        @Test
        @DisplayName("TC05: Should return 401 Unauthorized when not authenticated")
        void shouldReturn401WhenNotAuthenticated() throws Exception {
            // When / Then - no Authorization header
            mockMvc.perform(delete(ApiRoutes.PLUGINS + "/{pluginId}/deactivate", PLUGIN_ID))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("TC06: Should return 400 for invalid pluginId format")
        void shouldReturn400ForInvalidPluginIdFormat() throws Exception {
            // Given - pluginId with invalid characters
            String invalidPluginId = "INVALID_PLUGIN!";

            // When / Then
            mockMvc.perform(delete(ApiRoutes.PLUGINS + "/{pluginId}/deactivate", invalidPluginId)
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC07: Should accept pluginId with hyphens")
        void shouldAcceptPluginIdWithHyphens() throws Exception {
            // Given
            String pluginIdWithHyphens = "my-plugin-id";
            doNothing().when(pluginActivationService).deactivate(TEST_ACCOUNT_ID, pluginIdWithHyphens);

            // When / Then
            mockMvc.perform(delete(ApiRoutes.PLUGINS + "/{pluginId}/deactivate", pluginIdWithHyphens)
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN))
                .andExpect(status().isNoContent());

            verify(pluginActivationService).deactivate(TEST_ACCOUNT_ID, pluginIdWithHyphens);
        }

        @Test
        @DisplayName("TC08: Should allow deactivation for different account")
        void shouldAllowDeactivationForDifferentAccount() throws Exception {
            // Given - different account ID
            UUID differentAccountId = UUID.randomUUID();
            when(authorizationHelper.getAuthenticatedAccountId()).thenReturn(differentAccountId);
            doNothing().when(pluginActivationService).deactivate(differentAccountId, PLUGIN_ID);

            // When / Then
            mockMvc.perform(delete(ApiRoutes.PLUGINS + "/{pluginId}/deactivate", PLUGIN_ID)
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN))
                .andExpect(status().isNoContent());

            verify(pluginActivationService).deactivate(differentAccountId, PLUGIN_ID);
        }
    }
}
