package com.bitbi.dfm.plugin.contract;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.bitbi.dfm.plugin.application.PluginAdminQueryService;
import com.bitbi.dfm.plugin.domain.PluginActionType;
import com.bitbi.dfm.plugin.presentation.dto.PluginAuditLogEntryDto;
import com.bitbi.dfm.plugin.presentation.dto.PluginConfigResponseDto;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for Plugin Admin API endpoints.
 *
 * <p>Tests admin endpoints for plugin management and audit:</p>
 * <ul>
 *   <li>GET /api/v1/admin/plugins - List registered plugins</li>
 *   <li>GET /api/v1/admin/plugins/audit - Query audit logs</li>
 *   <li>GET /api/v1/admin/plugins/{pluginId}/audit - Get audit logs for specific plugin</li>
 * </ul>
 *
 * <p>Requires ROLE_ADMIN for all operations.</p>
 *
 * <p>User Story 6 (Phase 8) - Admin Views Plugin Audit Trail</p>
 *
 * @see com.bitbi.dfm.plugin.presentation.PluginAdminController
 */
@DisplayName("Plugin Admin Contract Tests")
class PluginAdminContractTest extends BaseIntegrationTest {

    @MockitoBean
    private PluginAdminQueryService pluginAdminQueryService;

    @MockitoBean
    private AuthorizationHelper authorizationHelper;

    private static final String MOCK_ADMIN_JWT_TOKEN = "mock.admin.jwt.token";
    private static final String MOCK_USER_JWT_TOKEN = "mock.user.jwt.token";
    private static final UUID TEST_ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final String PLUGIN_ID = "bit-bi";

    @BeforeEach
    void setUp() {
        when(authorizationHelper.getAuthenticatedAccountId()).thenReturn(TEST_ACCOUNT_ID);
    }

    @Nested
    @DisplayName("GET /api/v1/admin/plugins")
    class ListRegisteredPlugins {

        @Test
        @DisplayName("TC01: Should return 200 with list of registered plugins")
        void shouldReturn200WithPluginList() throws Exception {
            // Given
            List<PluginConfigResponseDto> plugins = List.of(
                    new PluginConfigResponseDto(
                            "bit-bi",
                            "Bit BI",
                            "1.0.0",
                            true,
                            Set.of("BATCH_COMPLETED"),
                            Instant.now().minus(30, ChronoUnit.DAYS),
                            Instant.now()
                    ),
                    new PluginConfigResponseDto(
                            "analytics-plugin",
                            "Analytics Plugin",
                            "2.0.0",
                            false,
                            Set.of("BATCH_COMPLETED", "FILE_UPLOADED"),
                            Instant.now().minus(60, ChronoUnit.DAYS),
                            Instant.now().minus(10, ChronoUnit.DAYS)
                    )
            );

            when(pluginAdminQueryService.listRegisteredPlugins()).thenReturn(plugins);

            // When / Then
            mockMvc.perform(get("/api/v1/admin/plugins")
                    .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].pluginId").value("bit-bi"))
                .andExpect(jsonPath("$[0].displayName").value("Bit BI"))
                .andExpect(jsonPath("$[0].version").value("1.0.0"))
                .andExpect(jsonPath("$[0].isEnabled").value(true))
                .andExpect(jsonPath("$[0].supportedEvents", hasItem("BATCH_COMPLETED")))
                .andExpect(jsonPath("$[1].pluginId").value("analytics-plugin"))
                .andExpect(jsonPath("$[1].isEnabled").value(false));

            verify(pluginAdminQueryService).listRegisteredPlugins();
        }

        @Test
        @DisplayName("TC02: Should return 200 with empty list when no plugins registered")
        void shouldReturn200WithEmptyList() throws Exception {
            // Given
            when(pluginAdminQueryService.listRegisteredPlugins()).thenReturn(List.of());

            // When / Then
            mockMvc.perform(get("/api/v1/admin/plugins")
                    .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("TC03: Should return 401 when not authenticated")
        void shouldReturn401WhenNotAuthenticated() throws Exception {
            // When / Then - no Authorization header
            mockMvc.perform(get("/api/v1/admin/plugins")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/plugins/audit")
    class QueryAuditLogs {

        @Test
        @DisplayName("TC04: Should return 200 with paginated audit logs")
        void shouldReturn200WithPaginatedAuditLogs() throws Exception {
            // Given
            List<PluginAuditLogEntryDto> entries = List.of(
                    new PluginAuditLogEntryDto(
                            1L, "bit-bi", TEST_ACCOUNT_ID, "client-123",
                            PluginActionType.ACTIVATE, "hash123", 256,
                            201, true, null, "192.168.1.1", 45L,
                            Instant.now()
                    ),
                    new PluginAuditLogEntryDto(
                            2L, "bit-bi", TEST_ACCOUNT_ID, "client-123",
                            PluginActionType.EVENT_DISPATCHED, null, null,
                            null, true, null, null, 150L,
                            Instant.now().minus(1, ChronoUnit.HOURS)
                    )
            );

            Page<PluginAuditLogEntryDto> page = new PageImpl<>(entries);
            when(pluginAdminQueryService.queryAuditLogs(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(page);

            // When / Then
            mockMvc.perform(get("/api/v1/admin/plugins/audit")
                    .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].pluginId").value("bit-bi"))
                .andExpect(jsonPath("$.content[0].actionType").value("ACTIVATE"))
                .andExpect(jsonPath("$.content[0].success").value(true))
                .andExpect(jsonPath("$.content[0].requestBodyHash").value("hash123"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(2));

            verify(pluginAdminQueryService).queryAuditLogs(
                    eq(null), eq(null), eq(null), eq(null), eq(null), eq(null), any(Pageable.class));
        }

        @Test
        @DisplayName("TC05: Should filter by pluginId")
        void shouldFilterByPluginId() throws Exception {
            // Given
            Page<PluginAuditLogEntryDto> page = new PageImpl<>(List.of());
            when(pluginAdminQueryService.queryAuditLogs(eq("bit-bi"), any(), any(), any(), any(), any(), any()))
                    .thenReturn(page);

            // When / Then
            mockMvc.perform(get("/api/v1/admin/plugins/audit")
                    .param("pluginId", "bit-bi")
                    .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

            verify(pluginAdminQueryService).queryAuditLogs(
                    eq("bit-bi"), eq(null), eq(null), eq(null), eq(null), eq(null), any(Pageable.class));
        }

        @Test
        @DisplayName("TC06: Should filter by accountId")
        void shouldFilterByAccountId() throws Exception {
            // Given
            Page<PluginAuditLogEntryDto> page = new PageImpl<>(List.of());
            when(pluginAdminQueryService.queryAuditLogs(any(), eq(TEST_ACCOUNT_ID), any(), any(), any(), any(), any()))
                    .thenReturn(page);

            // When / Then
            mockMvc.perform(get("/api/v1/admin/plugins/audit")
                    .param("accountId", TEST_ACCOUNT_ID.toString())
                    .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

            verify(pluginAdminQueryService).queryAuditLogs(
                    eq(null), eq(TEST_ACCOUNT_ID), eq(null), eq(null), eq(null), eq(null), any(Pageable.class));
        }

        @Test
        @DisplayName("TC07: Should filter by actionType")
        void shouldFilterByActionType() throws Exception {
            // Given
            Page<PluginAuditLogEntryDto> page = new PageImpl<>(List.of());
            when(pluginAdminQueryService.queryAuditLogs(any(), any(), eq(PluginActionType.ACTIVATE), any(), any(), any(), any()))
                    .thenReturn(page);

            // When / Then
            mockMvc.perform(get("/api/v1/admin/plugins/audit")
                    .param("actionType", "ACTIVATE")
                    .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

            verify(pluginAdminQueryService).queryAuditLogs(
                    eq(null), eq(null), eq(PluginActionType.ACTIVATE), eq(null), eq(null), eq(null), any(Pageable.class));
        }

        @Test
        @DisplayName("TC08: Should filter by success status")
        void shouldFilterBySuccessStatus() throws Exception {
            // Given
            Page<PluginAuditLogEntryDto> page = new PageImpl<>(List.of());
            when(pluginAdminQueryService.queryAuditLogs(any(), any(), any(), eq(false), any(), any(), any()))
                    .thenReturn(page);

            // When / Then
            mockMvc.perform(get("/api/v1/admin/plugins/audit")
                    .param("success", "false")
                    .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

            verify(pluginAdminQueryService).queryAuditLogs(
                    eq(null), eq(null), eq(null), eq(false), eq(null), eq(null), any(Pageable.class));
        }

        @Test
        @DisplayName("TC09: Should apply pagination parameters")
        void shouldApplyPaginationParameters() throws Exception {
            // Given
            Page<PluginAuditLogEntryDto> page = new PageImpl<>(List.of());
            when(pluginAdminQueryService.queryAuditLogs(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(page);

            // When / Then
            mockMvc.perform(get("/api/v1/admin/plugins/audit")
                    .param("page", "2")
                    .param("size", "25")
                    .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

            // Verify pagination was applied
            verify(pluginAdminQueryService).queryAuditLogs(
                    eq(null), eq(null), eq(null), eq(null), eq(null), eq(null),
                    argThat(pageable -> pageable.getPageNumber() == 2 && pageable.getPageSize() == 25));
        }

        @Test
        @DisplayName("TC10: Should enforce maximum page size of 100")
        void shouldEnforceMaxPageSize() throws Exception {
            // Given
            Page<PluginAuditLogEntryDto> page = new PageImpl<>(List.of());
            when(pluginAdminQueryService.queryAuditLogs(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(page);

            // When / Then - request size 200, should be capped at 100
            mockMvc.perform(get("/api/v1/admin/plugins/audit")
                    .param("size", "200")
                    .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

            // Verify page size was capped at 100
            verify(pluginAdminQueryService).queryAuditLogs(
                    eq(null), eq(null), eq(null), eq(null), eq(null), eq(null),
                    argThat(pageable -> pageable.getPageSize() == 100));
        }

        @Test
        @DisplayName("TC11: Should return 401 when not authenticated")
        void shouldReturn401WhenNotAuthenticated() throws Exception {
            // When / Then
            mockMvc.perform(get("/api/v1/admin/plugins/audit")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/plugins/{pluginId}/audit")
    class GetPluginAuditLogs {

        @Test
        @DisplayName("TC12: Should return 200 with audit logs for specific plugin")
        void shouldReturn200WithPluginAuditLogs() throws Exception {
            // Given
            List<PluginAuditLogEntryDto> entries = List.of(
                    new PluginAuditLogEntryDto(
                            1L, PLUGIN_ID, TEST_ACCOUNT_ID, "client-123",
                            PluginActionType.ACTIVATE, "hash123", 256,
                            201, true, null, "192.168.1.1", 45L,
                            Instant.now()
                    )
            );

            Page<PluginAuditLogEntryDto> page = new PageImpl<>(entries);
            when(pluginAdminQueryService.getAuditLogsByPlugin(eq(PLUGIN_ID), any(Pageable.class)))
                    .thenReturn(page);

            // When / Then
            mockMvc.perform(get("/api/v1/admin/plugins/{pluginId}/audit", PLUGIN_ID)
                    .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].pluginId").value(PLUGIN_ID));

            verify(pluginAdminQueryService).getAuditLogsByPlugin(eq(PLUGIN_ID), any(Pageable.class));
        }

        @Test
        @DisplayName("TC13: Should return 200 with empty list for unknown plugin")
        void shouldReturn200WithEmptyListForUnknownPlugin() throws Exception {
            // Given
            Page<PluginAuditLogEntryDto> page = new PageImpl<>(List.of());
            when(pluginAdminQueryService.getAuditLogsByPlugin(eq("unknown-plugin"), any(Pageable.class)))
                    .thenReturn(page);

            // When / Then
            mockMvc.perform(get("/api/v1/admin/plugins/{pluginId}/audit", "unknown-plugin")
                    .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content", hasSize(0)));
        }

        @Test
        @DisplayName("TC14: Should return 401 when not authenticated")
        void shouldReturn401WhenNotAuthenticated() throws Exception {
            // When / Then
            mockMvc.perform(get("/api/v1/admin/plugins/{pluginId}/audit", PLUGIN_ID)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        }
    }
}
