package com.bitbi.dfm.plugin.contract;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.bitbi.dfm.plugin.application.PluginHistoryService;
import com.bitbi.dfm.plugin.application.PluginQueryService;
import com.bitbi.dfm.plugin.application.PluginRateLimiterService;
import com.bitbi.dfm.plugin.presentation.dto.AccountPluginListResponseDto;
import com.bitbi.dfm.plugin.presentation.dto.AccountPluginSummaryDto;
import com.bitbi.dfm.plugin.presentation.dto.ReinitResultDto;
import com.bitbi.dfm.shared.api.ApiRoutes;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for Account Plugins API endpoints.
 *
 * <p>Tests GET /api/v1/account/plugins endpoint:
 * <ul>
 *   <li>TC01: List active plugins returns 200</li>
 *   <li>TC02: Empty list returns 200 with empty array</li>
 *   <li>TC03: includeInactive=true includes deactivated plugins</li>
 *   <li>TC04: Pagination parameters are respected</li>
 *   <li>TC05: Unauthenticated returns 401</li>
 *   <li>TC06: Invalid page parameter returns 400</li>
 *   <li>TC07: Invalid size parameter returns 400</li>
 *   <li>TC08: Plugin data is NOT exposed (FR-012)</li>
 * </ul>
 *
 * @see com.bitbi.dfm.plugin.presentation.AccountPluginsController
 */
@DisplayName("Account Plugins Contract Tests")
class AccountPluginsContractTest extends BaseIntegrationTest {

    @MockitoBean
    private PluginQueryService pluginQueryService;

    @MockitoBean
    private PluginHistoryService pluginHistoryService;

    @MockitoBean
    private PluginRateLimiterService rateLimiterService;

    @MockitoBean
    private AuthorizationHelper authorizationHelper;


    private static final String MOCK_USER_JWT_TOKEN = "mock.user.jwt.token";
    private static final UUID TEST_ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @BeforeEach
    void setUp() {
        // Mock both methods for backward compatibility
        when(authorizationHelper.getAuthenticatedAccountId()).thenReturn(TEST_ACCOUNT_ID);
        when(authorizationHelper.getOptionalAuthenticatedAccountId()).thenReturn(Optional.of(TEST_ACCOUNT_ID));

        // Allow rate limiting by default (so other tests pass)
        when(rateLimiterService.tryConsumeReinit(any(UUID.class))).thenReturn(true);
        when(rateLimiterService.getReinitRetryAfterSeconds()).thenReturn(30);
    }

    @Nested
    @DisplayName("GET /api/v1/account/plugins")
    class ListAccountPlugins {

        @Test
        @DisplayName("TC01: Should return 200 OK with list of active plugins")
        void shouldReturn200WithActivePlugins() throws Exception {
            // Given
            Instant now = Instant.now();
            List<AccountPluginSummaryDto> plugins = List.of(
                new AccountPluginSummaryDto(
                    "bit-bi", "Bit BI", true, now.minusSeconds(3600), null, now.minusSeconds(60)
                ),
                new AccountPluginSummaryDto(
                    "analytics-plugin", "Analytics Plugin", true, now.minusSeconds(7200), null, null
                )
            );
            AccountPluginListResponseDto response = AccountPluginListResponseDto.of(
                plugins, 0, 20, 2L, 1
            );

            when(pluginQueryService.listAccountPlugins(eq(TEST_ACCOUNT_ID), eq(false), any(Pageable.class)))
                .thenReturn(response);

            // When / Then
            mockMvc.perform(get(ApiRoutes.ACCOUNT_PLUGINS)
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].pluginId").value("bit-bi"))
                .andExpect(jsonPath("$.content[0].pluginName").value("Bit BI"))
                .andExpect(jsonPath("$.content[0].isActive").value(true))
                .andExpect(jsonPath("$.content[0].activatedAt").exists())
                .andExpect(jsonPath("$.content[0].lastUsedAt").exists())
                .andExpect(jsonPath("$.content[1].pluginId").value("analytics-plugin"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1));

            verify(pluginQueryService).listAccountPlugins(eq(TEST_ACCOUNT_ID), eq(false), any(Pageable.class));
        }

        @Test
        @DisplayName("TC02: Should return 200 OK with empty list when no plugins")
        void shouldReturn200WithEmptyListWhenNoPlugins() throws Exception {
            // Given
            AccountPluginListResponseDto response = AccountPluginListResponseDto.of(
                Collections.emptyList(), 0, 20, 0L, 0
            );

            when(pluginQueryService.listAccountPlugins(eq(TEST_ACCOUNT_ID), eq(false), any(Pageable.class)))
                .thenReturn(response);

            // When / Then
            mockMvc.perform(get(ApiRoutes.ACCOUNT_PLUGINS)
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0));
        }

        @Test
        @DisplayName("TC03: Should include inactive plugins when includeInactive=true")
        void shouldIncludeInactivePluginsWhenFlagSet() throws Exception {
            // Given
            Instant now = Instant.now();
            List<AccountPluginSummaryDto> plugins = List.of(
                new AccountPluginSummaryDto(
                    "bit-bi", "Bit BI", true, now.minusSeconds(3600), null, now.minusSeconds(60)
                ),
                new AccountPluginSummaryDto(
                    "deactivated-plugin", "Deactivated Plugin", false,
                    now.minusSeconds(86400), now.minusSeconds(3600), now.minusSeconds(7200)
                )
            );
            AccountPluginListResponseDto response = AccountPluginListResponseDto.of(
                plugins, 0, 20, 2L, 1
            );

            when(pluginQueryService.listAccountPlugins(eq(TEST_ACCOUNT_ID), eq(true), any(Pageable.class)))
                .thenReturn(response);

            // When / Then
            mockMvc.perform(get(ApiRoutes.ACCOUNT_PLUGINS)
                    .param("includeInactive", "true")
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[1].isActive").value(false))
                .andExpect(jsonPath("$.content[1].deactivatedAt").exists());

            verify(pluginQueryService).listAccountPlugins(eq(TEST_ACCOUNT_ID), eq(true), any(Pageable.class));
        }

        @Test
        @DisplayName("TC04: Should respect pagination parameters")
        void shouldRespectPaginationParameters() throws Exception {
            // Given
            Instant now = Instant.now();
            List<AccountPluginSummaryDto> plugins = List.of(
                new AccountPluginSummaryDto(
                    "plugin-on-page-2", "Plugin on Page 2", true, now.minusSeconds(3600), null, null
                )
            );
            AccountPluginListResponseDto response = AccountPluginListResponseDto.of(
                plugins, 1, 5, 8L, 2
            );

            when(pluginQueryService.listAccountPlugins(eq(TEST_ACCOUNT_ID), eq(false), any(Pageable.class)))
                .thenReturn(response);

            // When / Then
            mockMvc.perform(get(ApiRoutes.ACCOUNT_PLUGINS)
                    .param("page", "1")
                    .param("size", "5")
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(8))
                .andExpect(jsonPath("$.totalPages").value(2));
        }

        @Test
        @DisplayName("TC05: Should return 401 Unauthorized when not authenticated")
        void shouldReturn401WhenNotAuthenticated() throws Exception {
            // When / Then - no Authorization header
            mockMvc.perform(get(ApiRoutes.ACCOUNT_PLUGINS)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("TC06: Should return 400 for negative page number")
        void shouldReturn400ForNegativePage() throws Exception {
            // When / Then
            mockMvc.perform(get(ApiRoutes.ACCOUNT_PLUGINS)
                    .param("page", "-1")
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC07: Should return 400 for size exceeding maximum")
        void shouldReturn400ForSizeExceedingMax() throws Exception {
            // When / Then
            mockMvc.perform(get(ApiRoutes.ACCOUNT_PLUGINS)
                    .param("size", "101")
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC08: Should NOT expose pluginData in response (FR-012)")
        void shouldNotExposePluginDataInResponse() throws Exception {
            // Given
            Instant now = Instant.now();
            List<AccountPluginSummaryDto> plugins = List.of(
                new AccountPluginSummaryDto(
                    "bit-bi", "Bit BI", true, now.minusSeconds(3600), null, now.minusSeconds(60)
                )
            );
            AccountPluginListResponseDto response = AccountPluginListResponseDto.of(
                plugins, 0, 20, 1L, 1
            );

            when(pluginQueryService.listAccountPlugins(eq(TEST_ACCOUNT_ID), eq(false), any(Pageable.class)))
                .thenReturn(response);

            // When / Then
            mockMvc.perform(get(ApiRoutes.ACCOUNT_PLUGINS)
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // Verify pluginData fields are NOT in the response
                .andExpect(jsonPath("$.content[0].pluginData").doesNotExist())
                .andExpect(jsonPath("$.content[0].tenantId").doesNotExist())
                // Verify only metadata is present
                .andExpect(jsonPath("$.content[0].pluginId").exists())
                .andExpect(jsonPath("$.content[0].pluginName").exists())
                .andExpect(jsonPath("$.content[0].isActive").exists())
                .andExpect(jsonPath("$.content[0].activatedAt").exists());
        }

        @Test
        @DisplayName("TC09: Should return 400 for size less than 1")
        void shouldReturn400ForSizeLessThanOne() throws Exception {
            // When / Then
            mockMvc.perform(get(ApiRoutes.ACCOUNT_PLUGINS)
                    .param("size", "0")
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC10: Should use default values when pagination parameters not provided")
        void shouldUseDefaultValuesWhenNotProvided() throws Exception {
            // Given
            AccountPluginListResponseDto response = AccountPluginListResponseDto.of(
                Collections.emptyList(), 0, 20, 0L, 0
            );

            when(pluginQueryService.listAccountPlugins(eq(TEST_ACCOUNT_ID), eq(false), any(Pageable.class)))
                .thenReturn(response);

            // When / Then
            mockMvc.perform(get(ApiRoutes.ACCOUNT_PLUGINS)
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
        }
    }

    // ==================== Feature 015: Reinit Endpoint ====================

    @Nested
    @DisplayName("POST /api/v1/account/plugins/{pluginId}/reinit - Feature 015")
    class ReinitPlugin {

        private static final UUID BATCH_ID = UUID.fromString("b1b2c3d4-e5f6-7890-abcd-ef1234567890");

        @Test
        @DisplayName("T020: Should return 202 Accepted on successful reinit (baseline batch set)")
        void shouldReturn202OnSuccessfulReinit() throws Exception {
            // Given
            ReinitResultDto result = ReinitResultDto.success(
                    10L,  // deletedGenerations
                    10L,  // deletedS3Files
                    50000L,  // totalBytesFreed
                    false,  // sqlGenerationTriggered - now false (baseline batch logic, no SQL generation)
                    BATCH_ID,  // batchId - used as baseline batch
                    Collections.emptyList()  // s3DeleteWarnings
            );

            when(pluginHistoryService.reinit("bit-bi", TEST_ACCOUNT_ID)).thenReturn(result);

            // When / Then
            mockMvc.perform(post(ApiRoutes.ACCOUNT_PLUGINS + "/bit-bi/reinit")
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.deletedGenerations").value(10))
                .andExpect(jsonPath("$.deletedS3Files").value(10))
                .andExpect(jsonPath("$.totalBytesFreed").value(50000))
                .andExpect(jsonPath("$.sqlGenerationTriggered").value(false))
                .andExpect(jsonPath("$.batchId").value(BATCH_ID.toString()))
                .andExpect(jsonPath("$.message").value(containsString("baseline batch")))
                .andExpect(jsonPath("$.s3DeleteWarnings").isArray())
                .andExpect(jsonPath("$.s3DeleteWarnings", hasSize(0)));

            verify(pluginHistoryService).reinit("bit-bi", TEST_ACCOUNT_ID);
        }

        @Test
        @DisplayName("Should return 202 Accepted with no SQL generation when no batches exist")
        void shouldReturn202WithNoSqlGenerationWhenNoBatches() throws Exception {
            // Given
            ReinitResultDto result = ReinitResultDto.success(
                    5L,  // deletedGenerations
                    5L,  // deletedS3Files
                    25000L,  // totalBytesFreed
                    false,  // sqlGenerationTriggered - no batches
                    null,  // batchId - null when no batch
                    Collections.emptyList()
            );

            when(pluginHistoryService.reinit("bit-bi", TEST_ACCOUNT_ID)).thenReturn(result);

            // When / Then
            mockMvc.perform(post(ApiRoutes.ACCOUNT_PLUGINS + "/bit-bi/reinit")
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.sqlGenerationTriggered").value(false))
                .andExpect(jsonPath("$.batchId").isEmpty())
                .andExpect(jsonPath("$.message").value(containsString("No completed batches")));
        }

        @Test
        @DisplayName("Should return 400 when plugin is not active")
        void shouldReturn400WhenPluginNotActive() throws Exception {
            // Given
            when(pluginHistoryService.reinit("bit-bi", TEST_ACCOUNT_ID))
                    .thenThrow(new IllegalArgumentException("Plugin 'bit-bi' is not active for this account"));

            // When / Then
            mockMvc.perform(post(ApiRoutes.ACCOUNT_PLUGINS + "/bit-bi/reinit")
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 401 when not authenticated")
        void shouldReturn401WhenNotAuthenticated() throws Exception {
            // When / Then - no Authorization header
            mockMvc.perform(post(ApiRoutes.ACCOUNT_PLUGINS + "/bit-bi/reinit")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Should return 400 for invalid plugin ID format")
        void shouldReturn400ForInvalidPluginIdFormat() throws Exception {
            // When / Then - invalid plugin ID (contains uppercase)
            mockMvc.perform(post(ApiRoutes.ACCOUNT_PLUGINS + "/BitBI/reinit")
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should include S3 delete warnings in response when present (202 Accepted)")
        void shouldIncludeS3DeleteWarningsInResponse() throws Exception {
            // Given
            ReinitResultDto result = ReinitResultDto.success(
                    10L,
                    8L,  // Only 8 succeeded
                    50000L,
                    true,
                    BATCH_ID,
                    List.of("key1.sql", "key2.sql")  // 2 failures
            );

            when(pluginHistoryService.reinit("bit-bi", TEST_ACCOUNT_ID)).thenReturn(result);

            // When / Then
            mockMvc.perform(post(ApiRoutes.ACCOUNT_PLUGINS + "/bit-bi/reinit")
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.deletedS3Files").value(8))
                .andExpect(jsonPath("$.s3DeleteWarnings", hasSize(2)))
                .andExpect(jsonPath("$.s3DeleteWarnings[0]").value("key1.sql"))
                .andExpect(jsonPath("$.s3DeleteWarnings[1]").value("key2.sql"));
        }

        @Test
        @DisplayName("Should return 429 when rate limited")
        void shouldReturn429WhenRateLimited() throws Exception {
            // Given - rate limiter returns false (rate limited)
            when(rateLimiterService.tryConsumeReinit(TEST_ACCOUNT_ID)).thenReturn(false);

            // When / Then
            mockMvc.perform(post(ApiRoutes.ACCOUNT_PLUGINS + "/bit-bi/reinit")
                    .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"));

            // Verify service was never called due to rate limiting
            verify(pluginHistoryService, never()).reinit(any(), any());
        }
    }
}
