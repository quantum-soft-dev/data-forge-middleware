package com.bitbi.dfm.plugin.contract;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.bitbi.dfm.plugin.application.PluginHistoryService;
import com.bitbi.dfm.plugin.presentation.dto.*;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for Plugin History Admin API endpoints.
 *
 * <p>Feature 014: Plugin History Management</p>
 *
 * <p>Tests admin endpoints for:</p>
 * <ul>
 *   <li>GET /api/v1/admin/plugins/{pluginId}/accounts/{accountId}/generations - List generations</li>
 *   <li>GET /api/v1/admin/plugins/{pluginId}/accounts/{accountId}/generations/{id} - Get generation</li>
 *   <li>GET /api/v1/admin/plugins/{pluginId}/accounts/{accountId}/generations/{id}/content - Get SQL content</li>
 *   <li>GET /api/v1/admin/plugins/{pluginId}/accounts/{accountId}/generations/{id}/download - Download SQL file</li>
 *   <li>GET /api/v1/admin/plugins/{pluginId}/accounts/{accountId}/history/summary - Get clear summary</li>
 *   <li>DELETE /api/v1/admin/plugins/{pluginId}/accounts/{accountId}/history - Clear history</li>
 *   <li>POST /api/v1/admin/plugins/{pluginId}/accounts/{accountId}/generations/{id}/regenerate - Regenerate SQL</li>
 * </ul>
 *
 * <p>Requires ROLE_ADMIN for all operations.</p>
 */
@DisplayName("Plugin History Admin Contract Tests")
class PluginHistoryAdminControllerTest extends BaseIntegrationTest {

    @MockitoBean
    private PluginHistoryService pluginHistoryService;

    @MockitoBean
    private AuthorizationHelper authorizationHelper;

    private static final String MOCK_ADMIN_JWT_TOKEN = "mock.admin.jwt.token";
    private static final UUID TEST_ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID TEST_GENERATION_ID = UUID.fromString("b2c3d4e5-f6a7-8901-bcde-f12345678901");
    private static final UUID TEST_SITE_ID = UUID.fromString("c3d4e5f6-a7b8-9012-cdef-123456789012");
    private static final UUID TEST_BATCH_ID = UUID.fromString("d4e5f6a7-b8c9-0123-def0-234567890123");
    private static final String PLUGIN_ID = "bit-bi";
    private static final String BASE_URL = "/api/v1/admin/plugins/{pluginId}/accounts/{accountId}";

    @BeforeEach
    void setUp() {
        when(authorizationHelper.getAuthenticatedAccountId()).thenReturn(TEST_ACCOUNT_ID);
    }

    // ==================== User Story 1: View History ====================

    @Nested
    @DisplayName("GET /generations - List SQL Generations")
    class ListGenerations {

        @Test
        @DisplayName("T011: Should return 200 with paginated generations list")
        void shouldReturn200WithPaginatedGenerations() throws Exception {
            // Given
            SqlGenerationSummaryDto dto = new SqlGenerationSummaryDto(
                    TEST_GENERATION_ID,
                    TEST_BATCH_ID,
                    null, // first batch
                    TEST_SITE_ID,
                    "test-site.example.com",
                    LocalDateTime.now(),
                    150,
                    100,
                    30,
                    20,
                    45678L,
                    234L,
                    true,  // isInitialLoad
                    false, // superseded
                    null   // supersededBy
            );

            Page<SqlGenerationSummaryDto> page = new PageImpl<>(List.of(dto));
            when(pluginHistoryService.listGenerations(eq(PLUGIN_ID), eq(TEST_ACCOUNT_ID), eq(false), any(Pageable.class)))
                    .thenReturn(page);

            // When / Then
            mockMvc.perform(get(BASE_URL + "/generations", PLUGIN_ID, TEST_ACCOUNT_ID)
                            .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.content[0].id").value(TEST_GENERATION_ID.toString()))
                    .andExpect(jsonPath("$.content[0].siteDomain").value("test-site.example.com"))
                    .andExpect(jsonPath("$.content[0].statementCount").value(150))
                    .andExpect(jsonPath("$.content[0].isInitialLoad").value(true))
                    .andExpect(jsonPath("$.content[0].superseded").value(false))
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.totalElements").value(1));

            verify(pluginHistoryService).listGenerations(eq(PLUGIN_ID), eq(TEST_ACCOUNT_ID), eq(false), any(Pageable.class));
        }

        @Test
        @DisplayName("T011b: Should include superseded when requested")
        void shouldIncludeSupersededWhenRequested() throws Exception {
            // Given
            Page<SqlGenerationSummaryDto> page = new PageImpl<>(List.of());
            when(pluginHistoryService.listGenerations(eq(PLUGIN_ID), eq(TEST_ACCOUNT_ID), eq(true), any(Pageable.class)))
                    .thenReturn(page);

            // When / Then
            mockMvc.perform(get(BASE_URL + "/generations", PLUGIN_ID, TEST_ACCOUNT_ID)
                            .param("includeSuperseded", "true")
                            .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());

            verify(pluginHistoryService).listGenerations(eq(PLUGIN_ID), eq(TEST_ACCOUNT_ID), eq(true), any(Pageable.class));
        }

        @Test
        @DisplayName("Should return 401 when not authenticated")
        void shouldReturn401WhenNotAuthenticated() throws Exception {
            mockMvc.perform(get(BASE_URL + "/generations", PLUGIN_ID, TEST_ACCOUNT_ID)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /generations/{id}/content - Get SQL Content")
    class GetSqlContent {

        @Test
        @DisplayName("T012: Should return 200 with paginated SQL content")
        void shouldReturn200WithPaginatedSqlContent() throws Exception {
            // Given
            SqlContentPageDto dto = new SqlContentPageDto(
                    TEST_GENERATION_ID,
                    0,
                    100,
                    2,
                    150,
                    List.of(
                            "INSERT INTO customers (id, name) VALUES ('1', 'Alice');",
                            "UPDATE users SET status = 'active' WHERE id = '2';"
                    ),
                    true,  // hasNext
                    false  // hasPrevious
            );

            when(pluginHistoryService.getSqlContent(eq(PLUGIN_ID), eq(TEST_ACCOUNT_ID), eq(TEST_GENERATION_ID), eq(0), eq(100)))
                    .thenReturn(dto);

            // When / Then
            mockMvc.perform(get(BASE_URL + "/generations/{generationId}/content", PLUGIN_ID, TEST_ACCOUNT_ID, TEST_GENERATION_ID)
                            .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.generationId").value(TEST_GENERATION_ID.toString()))
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.totalStatements").value(150))
                    .andExpect(jsonPath("$.statements").isArray())
                    .andExpect(jsonPath("$.statements", hasSize(2)))
                    .andExpect(jsonPath("$.hasNext").value(true))
                    .andExpect(jsonPath("$.hasPrevious").value(false));
        }

        @Test
        @DisplayName("Should return 404 when generation not found")
        void shouldReturn404WhenGenerationNotFound() throws Exception {
            // Given
            when(pluginHistoryService.getSqlContent(any(), any(), any(), anyInt(), anyInt()))
                    .thenThrow(new IllegalArgumentException("Generation not found"));

            // When / Then
            mockMvc.perform(get(BASE_URL + "/generations/{generationId}/content", PLUGIN_ID, TEST_ACCOUNT_ID, TEST_GENERATION_ID)
                            .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /generations/{id}/download - Download SQL File")
    class DownloadSqlFile {

        @Test
        @DisplayName("T013: Should return 200 with SQL file content")
        void shouldReturn200WithSqlFileContent() throws Exception {
            // Given
            String sqlContent = "INSERT INTO customers (id, name) VALUES ('1', 'Alice');\n--- END OF COMMAND ---\n";
            when(pluginHistoryService.downloadSqlFile(eq(PLUGIN_ID), eq(TEST_ACCOUNT_ID), eq(TEST_GENERATION_ID)))
                    .thenReturn(sqlContent);

            // When / Then
            mockMvc.perform(get(BASE_URL + "/generations/{generationId}/download", PLUGIN_ID, TEST_ACCOUNT_ID, TEST_GENERATION_ID)
                            .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(content().contentType("text/plain;charset=UTF-8"))
                    .andExpect(header().string("Content-Disposition", containsString("attachment")))
                    .andExpect(content().string(sqlContent));
        }
    }

    // ==================== User Story 2: Clear History ====================

    @Nested
    @DisplayName("GET /history/summary - Get Clear Summary")
    class GetHistorySummary {

        @Test
        @DisplayName("T030: Should return 200 with clear summary")
        void shouldReturn200WithClearSummary() throws Exception {
            // Given
            HistoryClearSummaryDto dto = new HistoryClearSummaryDto(
                    TEST_ACCOUNT_ID,
                    PLUGIN_ID,
                    42L,
                    1234567L,
                    true,
                    false
            );

            when(pluginHistoryService.getHistorySummary(eq(PLUGIN_ID), eq(TEST_ACCOUNT_ID)))
                    .thenReturn(dto);

            // When / Then
            mockMvc.perform(get(BASE_URL + "/history/summary", PLUGIN_ID, TEST_ACCOUNT_ID)
                            .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountId").value(TEST_ACCOUNT_ID.toString()))
                    .andExpect(jsonPath("$.pluginId").value(PLUGIN_ID))
                    .andExpect(jsonPath("$.generationCount").value(42))
                    .andExpect(jsonPath("$.totalFileSizeBytes").value(1234567))
                    .andExpect(jsonPath("$.pluginWillBeDeactivated").value(true))
                    .andExpect(jsonPath("$.hasActiveBatches").value(false));
        }
    }

    @Nested
    @DisplayName("DELETE /history - Clear History")
    class ClearHistory {

        @Test
        @DisplayName("T031: Should return 200 with clear result")
        void shouldReturn200WithClearResult() throws Exception {
            // Given
            HistoryClearResultDto dto = HistoryClearResultDto.create(42L, 42L, 1234567L, List.of());

            when(pluginHistoryService.clearHistory(eq(PLUGIN_ID), eq(TEST_ACCOUNT_ID)))
                    .thenReturn(dto);

            // When / Then
            mockMvc.perform(delete(BASE_URL + "/history", PLUGIN_ID, TEST_ACCOUNT_ID)
                            .param("confirm", "true")
                            .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.deletedGenerations").value(42))
                    .andExpect(jsonPath("$.deletedFilesCount").value(42))
                    .andExpect(jsonPath("$.pluginDeactivated").value(true))
                    .andExpect(jsonPath("$.failedS3Keys").isArray())
                    .andExpect(jsonPath("$.failedS3Keys", hasSize(0)));
        }

        @Test
        @DisplayName("Should return 400 when confirm not provided")
        void shouldReturn400WhenConfirmNotProvided() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/history", PLUGIN_ID, TEST_ACCOUNT_ID)
                            .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 400 when confirm is false")
        void shouldReturn400WhenConfirmIsFalse() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/history", PLUGIN_ID, TEST_ACCOUNT_ID)
                            .param("confirm", "false")
                            .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
        }
    }

    // ==================== User Story 3: Regenerate ====================

    @Nested
    @DisplayName("POST /generations/{id}/regenerate - Regenerate SQL")
    class RegenerateSql {

        @Test
        @DisplayName("T044: Should return 200 with regenerate result")
        void shouldReturn200WithRegenerateResult() throws Exception {
            // Given
            UUID newGenerationId = UUID.randomUUID();
            RegenerateResultDto dto = new RegenerateResultDto(
                    TEST_GENERATION_ID,
                    newGenerationId,
                    155,
                    105,
                    30,
                    20,
                    234L,
                    LocalDateTime.now()
            );

            when(pluginHistoryService.regenerateSql(eq(PLUGIN_ID), eq(TEST_ACCOUNT_ID), eq(TEST_GENERATION_ID)))
                    .thenReturn(dto);

            // When / Then
            mockMvc.perform(post(BASE_URL + "/generations/{generationId}/regenerate", PLUGIN_ID, TEST_ACCOUNT_ID, TEST_GENERATION_ID)
                            .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.originalGenerationId").value(TEST_GENERATION_ID.toString()))
                    .andExpect(jsonPath("$.newGenerationId").value(newGenerationId.toString()))
                    .andExpect(jsonPath("$.statementCount").value(155));
        }

        @Test
        @DisplayName("Should return 409 when already superseded")
        void shouldReturn409WhenAlreadySuperseded() throws Exception {
            // Given
            when(pluginHistoryService.regenerateSql(any(), any(), any()))
                    .thenThrow(new IllegalStateException("Generation is already superseded"));

            // When / Then
            mockMvc.perform(post(BASE_URL + "/generations/{generationId}/regenerate", PLUGIN_ID, TEST_ACCOUNT_ID, TEST_GENERATION_ID)
                            .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isConflict());
        }
    }
}
