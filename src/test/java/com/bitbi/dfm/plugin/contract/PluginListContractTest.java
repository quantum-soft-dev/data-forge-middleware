package com.bitbi.dfm.plugin.contract;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.bitbi.dfm.plugin.application.PluginQueryService;
import com.bitbi.dfm.plugin.presentation.dto.AvailablePluginResponseDto;
import com.bitbi.dfm.shared.api.ApiRoutes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for Plugin List API endpoint.
 *
 * <p>Tests GET /api/v1/plugins endpoint:
 * <ul>
 *   <li>TC01: Should return 200 with list of enabled plugins</li>
 *   <li>TC02: Should return empty list when no plugins enabled</li>
 *   <li>TC03: Should return 401 without auth token</li>
 *   <li>TC04: Should be accessible by ROLE_USER</li>
 *   <li>TC05: Should be accessible by ROLE_ADMIN</li>
 *   <li>TC06: Should not expose sensitive data like client_id</li>
 * </ul>
 *
 * @see com.bitbi.dfm.plugin.presentation.PluginController
 */
@DisplayName("Plugin List Contract Tests")
class PluginListContractTest extends BaseIntegrationTest {

    @MockitoBean
    private PluginQueryService pluginQueryService;

    private static final String MOCK_USER_JWT_TOKEN = "mock.user.jwt.token";
    private static final String MOCK_ADMIN_JWT_TOKEN = "mock.admin.jwt.token";

    @Nested
    @DisplayName("GET /api/v1/plugins")
    class ListAvailablePlugins {

        @Test
        @DisplayName("TC01: Should return 200 with list of enabled plugins")
        void shouldReturnEnabledPlugins() throws Exception {
            // Given
            List<AvailablePluginResponseDto> plugins = List.of(
                new AvailablePluginResponseDto("bit-bi", "Bit BI", "1.0.0", true),
                new AvailablePluginResponseDto("analytics", "Analytics Plugin", "2.1.0", true)
            );

            when(pluginQueryService.listAvailablePlugins()).thenReturn(plugins);

            // When / Then
            mockMvc.perform(get(ApiRoutes.PLUGINS)
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].pluginId").value("bit-bi"))
                .andExpect(jsonPath("$[0].displayName").value("Bit BI"))
                .andExpect(jsonPath("$[0].version").value("1.0.0"))
                .andExpect(jsonPath("$[0].isEnabled").value(true))
                .andExpect(jsonPath("$[1].pluginId").value("analytics"))
                .andExpect(jsonPath("$[1].displayName").value("Analytics Plugin"))
                .andExpect(jsonPath("$[1].version").value("2.1.0"));

            verify(pluginQueryService).listAvailablePlugins();
        }

        @Test
        @DisplayName("TC02: Should return empty list when no plugins enabled")
        void shouldReturnEmptyListWhenNoPlugins() throws Exception {
            // Given
            when(pluginQueryService.listAvailablePlugins()).thenReturn(Collections.emptyList());

            // When / Then
            mockMvc.perform(get(ApiRoutes.PLUGINS)
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("TC03: Should return 401 without auth token")
        void shouldReturn401WithoutToken() throws Exception {
            // When / Then - no Authorization header
            mockMvc.perform(get(ApiRoutes.PLUGINS)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("TC04: Should be accessible by ROLE_USER")
        void shouldBeAccessibleByRoleUser() throws Exception {
            // Given
            when(pluginQueryService.listAvailablePlugins()).thenReturn(List.of(
                new AvailablePluginResponseDto("bit-bi", "Bit BI", "1.0.0", true)
            ));

            // When / Then
            mockMvc.perform(get(ApiRoutes.PLUGINS)
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        }

        @Test
        @DisplayName("TC05: Should be accessible by ROLE_ADMIN")
        void shouldBeAccessibleByRoleAdmin() throws Exception {
            // Given
            when(pluginQueryService.listAvailablePlugins()).thenReturn(List.of(
                new AvailablePluginResponseDto("bit-bi", "Bit BI", "1.0.0", true)
            ));

            // When / Then
            mockMvc.perform(get(ApiRoutes.PLUGINS)
                    .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        }

        @Test
        @DisplayName("TC06: Should not expose sensitive data like client_id")
        void shouldNotExposeSensitiveData() throws Exception {
            // Given
            when(pluginQueryService.listAvailablePlugins()).thenReturn(List.of(
                new AvailablePluginResponseDto("bit-bi", "Bit BI", "1.0.0", true)
            ));

            // When / Then
            mockMvc.perform(get(ApiRoutes.PLUGINS)
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].clientId").doesNotExist())
                .andExpect(jsonPath("$[0].clientSecret").doesNotExist())
                .andExpect(jsonPath("$[0].apiKey").doesNotExist())
                .andExpect(jsonPath("$[0].pluginData").doesNotExist());
        }
    }
}
