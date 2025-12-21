package com.bitbi.dfm.plugin.contract;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.bitbi.dfm.plugin.application.PluginActivationService;
import com.bitbi.dfm.plugin.application.PluginActivationService.ActivationResult;
import com.bitbi.dfm.plugin.application.PluginDataValidator;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.exception.PluginDataValidationException;
import com.bitbi.dfm.plugin.domain.exception.PluginNotEnabledException;
import com.bitbi.dfm.plugin.domain.exception.PluginNotFoundException;
import com.bitbi.dfm.shared.api.ApiRoutes;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for Plugin Activation API endpoints.
 *
 * <p>Tests POST /api/v1/plugins/{pluginId}/activate endpoint:
 * <ul>
 *   <li>TC01: New activation returns 201</li>
 *   <li>TC02: Update existing returns 200</li>
 *   <li>TC03: Invalid schema returns 400</li>
 *   <li>TC04: Plugin not found returns 404</li>
 *   <li>TC05: Plugin disabled returns 404</li>
 *   <li>TC06: Missing pluginData returns 400</li>
 *   <li>TC07: Unauthenticated returns 401</li>
 * </ul>
 *
 * @see com.bitbi.dfm.plugin.presentation.PluginController
 */
@DisplayName("Plugin Activation Contract Tests")
class PluginActivationContractTest extends BaseIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

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
    @DisplayName("POST /api/v1/plugins/{pluginId}/activate")
    class ActivatePlugin {

        @Test
        @DisplayName("TC01: Should return 201 Created for new activation")
        void shouldReturn201ForNewActivation() throws Exception {
            // Given
            Map<String, Object> pluginData = Map.of("tenantId", "tenant-123");
            AccountPlugin accountPlugin = AccountPlugin.activate(TEST_ACCOUNT_ID, PLUGIN_ID, pluginData);
            ActivationResult result = new ActivationResult(accountPlugin, "Bit BI", true);

            when(pluginActivationService.activate(eq(TEST_ACCOUNT_ID), eq(PLUGIN_ID), any()))
                .thenReturn(result);

            String requestBody = objectMapper.writeValueAsString(Map.of("pluginData", pluginData));

            // When / Then
            mockMvc.perform(post(ApiRoutes.PLUGINS + "/{pluginId}/activate", PLUGIN_ID)
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pluginId").value(PLUGIN_ID))
                .andExpect(jsonPath("$.pluginName").value("Bit BI"))
                .andExpect(jsonPath("$.accountId").value(TEST_ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.activatedAt").exists());

            verify(pluginActivationService).activate(eq(TEST_ACCOUNT_ID), eq(PLUGIN_ID), any());
        }

        @Test
        @DisplayName("TC02: Should return 200 OK for existing activation update")
        void shouldReturn200ForExistingActivationUpdate() throws Exception {
            // Given
            Map<String, Object> pluginData = Map.of("tenantId", "updated-tenant");
            AccountPlugin accountPlugin = AccountPlugin.activate(TEST_ACCOUNT_ID, PLUGIN_ID, pluginData);
            ActivationResult result = new ActivationResult(accountPlugin, "Bit BI", false);

            when(pluginActivationService.activate(eq(TEST_ACCOUNT_ID), eq(PLUGIN_ID), any()))
                .thenReturn(result);

            String requestBody = objectMapper.writeValueAsString(Map.of("pluginData", pluginData));

            // When / Then
            mockMvc.perform(post(ApiRoutes.PLUGINS + "/{pluginId}/activate", PLUGIN_ID)
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pluginId").value(PLUGIN_ID))
                .andExpect(jsonPath("$.isActive").value(true));
        }

        @Test
        @DisplayName("TC03: Should return 400 Bad Request for invalid plugin data schema")
        void shouldReturn400ForInvalidPluginDataSchema() throws Exception {
            // Given
            Map<String, Object> invalidPluginData = Map.of("invalid", "data");

            when(pluginActivationService.activate(eq(TEST_ACCOUNT_ID), eq(PLUGIN_ID), any()))
                .thenThrow(new PluginDataValidationException(PLUGIN_ID, List.of("tenantId: required property missing")));

            String requestBody = objectMapper.writeValueAsString(Map.of("pluginData", invalidPluginData));

            // When / Then
            mockMvc.perform(post(ApiRoutes.PLUGINS + "/{pluginId}/activate", PLUGIN_ID)
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("validation failed")));
        }

        @Test
        @DisplayName("TC04: Should return 404 Not Found when plugin not in registry")
        void shouldReturn404WhenPluginNotFound() throws Exception {
            // Given
            String unknownPluginId = "unknown-plugin";
            Map<String, Object> pluginData = Map.of("tenantId", "tenant-123");

            when(pluginActivationService.activate(eq(TEST_ACCOUNT_ID), eq(unknownPluginId), any()))
                .thenThrow(new PluginNotFoundException(unknownPluginId));

            String requestBody = objectMapper.writeValueAsString(Map.of("pluginData", pluginData));

            // When / Then
            mockMvc.perform(post(ApiRoutes.PLUGINS + "/{pluginId}/activate", unknownPluginId)
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("not found")));
        }

        @Test
        @DisplayName("TC05: Should return 404 Not Found when plugin is disabled")
        void shouldReturn404WhenPluginDisabled() throws Exception {
            // Given
            Map<String, Object> pluginData = Map.of("tenantId", "tenant-123");

            when(pluginActivationService.activate(eq(TEST_ACCOUNT_ID), eq(PLUGIN_ID), any()))
                .thenThrow(new PluginNotEnabledException(PLUGIN_ID));

            String requestBody = objectMapper.writeValueAsString(Map.of("pluginData", pluginData));

            // When / Then
            mockMvc.perform(post(ApiRoutes.PLUGINS + "/{pluginId}/activate", PLUGIN_ID)
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("not enabled")));
        }

        @Test
        @DisplayName("TC06: Should return 400 Bad Request when pluginData is missing")
        void shouldReturn400WhenPluginDataMissing() throws Exception {
            // Given - request without pluginData field
            String requestBody = "{}";

            // When / Then
            mockMvc.perform(post(ApiRoutes.PLUGINS + "/{pluginId}/activate", PLUGIN_ID)
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC07: Should return 401 Unauthorized when not authenticated")
        void shouldReturn401WhenNotAuthenticated() throws Exception {
            // Given
            Map<String, Object> pluginData = Map.of("tenantId", "tenant-123");
            String requestBody = objectMapper.writeValueAsString(Map.of("pluginData", pluginData));

            // When / Then - no Authorization header
            mockMvc.perform(post(ApiRoutes.PLUGINS + "/{pluginId}/activate", PLUGIN_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("TC08: Should return 400 for invalid pluginId format")
        void shouldReturn400ForInvalidPluginIdFormat() throws Exception {
            // Given - pluginId with invalid characters
            String invalidPluginId = "INVALID_PLUGIN!";
            Map<String, Object> pluginData = Map.of("tenantId", "tenant-123");
            String requestBody = objectMapper.writeValueAsString(Map.of("pluginData", pluginData));

            // When / Then
            mockMvc.perform(post(ApiRoutes.PLUGINS + "/{pluginId}/activate", invalidPluginId)
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC09: Should accept pluginId with hyphens")
        void shouldAcceptPluginIdWithHyphens() throws Exception {
            // Given
            String pluginIdWithHyphens = "my-plugin-id";
            Map<String, Object> pluginData = Map.of("tenantId", "tenant-123");
            AccountPlugin accountPlugin = AccountPlugin.activate(TEST_ACCOUNT_ID, pluginIdWithHyphens, pluginData);
            ActivationResult result = new ActivationResult(accountPlugin, "My Plugin", true);

            when(pluginActivationService.activate(eq(TEST_ACCOUNT_ID), eq(pluginIdWithHyphens), any()))
                .thenReturn(result);

            String requestBody = objectMapper.writeValueAsString(Map.of("pluginData", pluginData));

            // When / Then
            mockMvc.perform(post(ApiRoutes.PLUGINS + "/{pluginId}/activate", pluginIdWithHyphens)
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pluginId").value(pluginIdWithHyphens));
        }

        @Test
        @DisplayName("TC10: Should handle reactivation of deactivated plugin")
        void shouldHandleReactivationOfDeactivatedPlugin() throws Exception {
            // Given
            Map<String, Object> pluginData = Map.of("tenantId", "new-tenant");
            AccountPlugin accountPlugin = AccountPlugin.activate(TEST_ACCOUNT_ID, PLUGIN_ID, pluginData);
            // Simulate reactivation (isNewActivation = false)
            ActivationResult result = new ActivationResult(accountPlugin, "Bit BI", false);

            when(pluginActivationService.activate(eq(TEST_ACCOUNT_ID), eq(PLUGIN_ID), any()))
                .thenReturn(result);

            String requestBody = objectMapper.writeValueAsString(Map.of("pluginData", pluginData));

            // When / Then
            mockMvc.perform(post(ApiRoutes.PLUGINS + "/{pluginId}/activate", PLUGIN_ID)
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(true));
        }
    }
}
