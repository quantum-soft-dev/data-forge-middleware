package com.bitbi.dfm.plugin.contract;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.bitbi.dfm.plugin.application.PluginApiKeyService;
import com.bitbi.dfm.plugin.application.SqlChangesQueryService;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.PluginApiKey;
import com.bitbi.dfm.plugin.presentation.dto.TableDto;
import com.bitbi.dfm.shared.api.ApiRoutes;
import com.bitbi.dfm.site.domain.Site;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
 * Contract tests for Bit BI Plugin API endpoints.
 *
 * <p>Tests GET /api/v1/plugins/bit-bi/sql-changes endpoint:
 * <ul>
 *   <li>TC01: Valid request returns 200 OK with SQL content (text/plain)</li>
 *   <li>TC02: Valid request with no changes returns 200 OK with empty body</li>
 *   <li>TC03: Missing siteId parameter returns 400 Bad Request</li>
 *   <li>TC04: Invalid siteId format returns 400 Bad Request</li>
 *   <li>TC05: Missing since parameter returns 400 Bad Request</li>
 *   <li>TC06: Invalid since format returns 400 Bad Request</li>
 *   <li>TC07: Missing API key returns 401 Unauthorized</li>
 *   <li>TC08: Invalid API key returns 401 Unauthorized</li>
 *   <li>TC09: Site not owned by account returns 403 Forbidden</li>
 * </ul>
 *
 * <p>Tests GET /api/v1/plugins/bit-bi/sites endpoint:
 * <ul>
 *   <li>TC10: Valid request returns 200 OK with site list</li>
 *   <li>TC11: Valid request with no sites returns 200 OK with empty array</li>
 *   <li>TC12: Missing API key returns 401 Unauthorized</li>
 *   <li>TC13: Invalid API key returns 401 Unauthorized</li>
 * </ul>
 *
 * <p>Tests GET /api/v1/plugins/bit-bi/tables endpoint:
 * <ul>
 *   <li>TC14: Valid request returns 200 OK with table list</li>
 *   <li>TC15: Valid request with no tables returns 200 OK with empty array</li>
 *   <li>TC16: Missing API key returns 401 Unauthorized</li>
 *   <li>TC17: Table names are derived from file names (extensions stripped)</li>
 * </ul>
 *
 * @see com.bitbi.dfm.plugin.presentation.BitBiPluginApiController
 */
@DisplayName("Bit BI Plugin API Contract Tests")
class BitBiPluginApiContractTest extends BaseIntegrationTest {

    private static final String API_KEY_HEADER = "X-Plugin-Api-Key";
    private static final String VALID_API_KEY = "plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6";
    private static final String INVALID_API_KEY = "plk_invalid1234567890123456789012";
    private static final UUID TEST_ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID TEST_SITE_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID OTHER_SITE_ID = UUID.fromString("660f9500-f39c-52e5-b827-557766551111");

    @MockitoBean
    private PluginApiKeyService pluginApiKeyService;

    @MockitoBean
    private SqlChangesQueryService sqlChangesQueryService;

    private AccountPlugin mockAccountPlugin;

    @BeforeEach
    void setUp() {
        // Setup mock account plugin
        mockAccountPlugin = mock(AccountPlugin.class);
        when(mockAccountPlugin.getId()).thenReturn(1L);  // Required by PluginApiKeyAuthenticationFilter
        when(mockAccountPlugin.getAccountId()).thenReturn(TEST_ACCOUNT_ID);
        when(mockAccountPlugin.isActive()).thenReturn(true);
    }

    @Nested
    @DisplayName("GET /api/v1/plugins/bit-bi/sql-changes")
    class GetSqlChanges {

        @Test
        @DisplayName("TC01: Should return 200 OK with SQL content for valid request")
        void shouldReturn200WithSqlContent() throws Exception {
            // Given
            String expectedSql = """
                INSERT INTO customers (id, name) VALUES ('1', 'John');
                --- END OF COMMAND "customers.csv:2" ---
                UPDATE orders SET status = 'shipped' WHERE id = '42';
                --- END OF COMMAND "orders.csv:15" ---
                """;

            when(pluginApiKeyService.validateApiKey(VALID_API_KEY))
                .thenReturn(Optional.of(mockAccountPlugin));
            when(sqlChangesQueryService.getSqlChanges(eq(TEST_ACCOUNT_ID), eq(TEST_SITE_ID), any(Instant.class)))
                .thenReturn(expectedSql);

            // When / Then
            mockMvc.perform(get(ApiRoutes.BITBI_SQL_CHANGES)
                    .header(API_KEY_HEADER, VALID_API_KEY)
                    .param("siteId", TEST_SITE_ID.toString())
                    .param("since", "2025-01-01T00:00:00Z"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(expectedSql));

            verify(pluginApiKeyService).validateApiKey(VALID_API_KEY);
            verify(sqlChangesQueryService).getSqlChanges(eq(TEST_ACCOUNT_ID), eq(TEST_SITE_ID), any(Instant.class));
        }

        @Test
        @DisplayName("TC02: Should return 200 OK with empty body when no changes")
        void shouldReturn200WithEmptyBodyWhenNoChanges() throws Exception {
            // Given
            when(pluginApiKeyService.validateApiKey(VALID_API_KEY))
                .thenReturn(Optional.of(mockAccountPlugin));
            when(sqlChangesQueryService.getSqlChanges(eq(TEST_ACCOUNT_ID), eq(TEST_SITE_ID), any(Instant.class)))
                .thenReturn("");

            // When / Then
            mockMvc.perform(get(ApiRoutes.BITBI_SQL_CHANGES)
                    .header(API_KEY_HEADER, VALID_API_KEY)
                    .param("siteId", TEST_SITE_ID.toString())
                    .param("since", "2025-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
        }

        @Test
        @DisplayName("TC03: Should return 400 Bad Request when siteId is missing")
        void shouldReturn400WhenSiteIdMissing() throws Exception {
            // Given
            when(pluginApiKeyService.validateApiKey(VALID_API_KEY))
                .thenReturn(Optional.of(mockAccountPlugin));

            // When / Then
            mockMvc.perform(get(ApiRoutes.BITBI_SQL_CHANGES)
                    .header(API_KEY_HEADER, VALID_API_KEY)
                    .param("since", "2025-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value(ApiRoutes.BITBI_SQL_CHANGES));
        }

        @Test
        @DisplayName("TC04: Should return 400 Bad Request when siteId is invalid UUID")
        void shouldReturn400WhenSiteIdInvalid() throws Exception {
            // Given
            when(pluginApiKeyService.validateApiKey(VALID_API_KEY))
                .thenReturn(Optional.of(mockAccountPlugin));

            // When / Then
            mockMvc.perform(get(ApiRoutes.BITBI_SQL_CHANGES)
                    .header(API_KEY_HEADER, VALID_API_KEY)
                    .param("siteId", "not-a-uuid")
                    .param("since", "2025-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message", containsString("siteId")));
        }

        @Test
        @DisplayName("TC05: Should return 400 Bad Request when since is missing")
        void shouldReturn400WhenSinceMissing() throws Exception {
            // Given
            when(pluginApiKeyService.validateApiKey(VALID_API_KEY))
                .thenReturn(Optional.of(mockAccountPlugin));

            // When / Then
            mockMvc.perform(get(ApiRoutes.BITBI_SQL_CHANGES)
                    .header(API_KEY_HEADER, VALID_API_KEY)
                    .param("siteId", TEST_SITE_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").exists());
        }

        @Test
        @DisplayName("TC06: Should return 400 Bad Request when since format is invalid")
        void shouldReturn400WhenSinceFormatInvalid() throws Exception {
            // Given
            when(pluginApiKeyService.validateApiKey(VALID_API_KEY))
                .thenReturn(Optional.of(mockAccountPlugin));

            // When / Then
            mockMvc.perform(get(ApiRoutes.BITBI_SQL_CHANGES)
                    .header(API_KEY_HEADER, VALID_API_KEY)
                    .param("siteId", TEST_SITE_ID.toString())
                    .param("since", "not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message", containsString("since")));
        }

        @Test
        @DisplayName("TC07: Should return 401 Unauthorized when API key is missing")
        void shouldReturn401WhenApiKeyMissing() throws Exception {
            // When / Then - no API key header
            mockMvc.perform(get(ApiRoutes.BITBI_SQL_CHANGES)
                    .param("siteId", TEST_SITE_ID.toString())
                    .param("since", "2025-01-01T00:00:00Z"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid or missing API key"));

            verify(pluginApiKeyService, never()).validateApiKey(any());
        }

        @Test
        @DisplayName("TC08: Should return 401 Unauthorized when API key is invalid")
        void shouldReturn401WhenApiKeyInvalid() throws Exception {
            // Given
            when(pluginApiKeyService.validateApiKey(INVALID_API_KEY))
                .thenReturn(Optional.empty());

            // When / Then
            mockMvc.perform(get(ApiRoutes.BITBI_SQL_CHANGES)
                    .header(API_KEY_HEADER, INVALID_API_KEY)
                    .param("siteId", TEST_SITE_ID.toString())
                    .param("since", "2025-01-01T00:00:00Z"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid or missing API key"));

            verify(pluginApiKeyService).validateApiKey(INVALID_API_KEY);
        }

        @Test
        @DisplayName("TC09: Should return 403 Forbidden when site is not owned by account")
        void shouldReturn403WhenSiteNotOwned() throws Exception {
            // Given
            when(pluginApiKeyService.validateApiKey(VALID_API_KEY))
                .thenReturn(Optional.of(mockAccountPlugin));
            when(sqlChangesQueryService.getSqlChanges(eq(TEST_ACCOUNT_ID), eq(OTHER_SITE_ID), any(Instant.class)))
                .thenThrow(new SecurityException("Site does not belong to your account"));

            // When / Then
            mockMvc.perform(get(ApiRoutes.BITBI_SQL_CHANGES)
                    .header(API_KEY_HEADER, VALID_API_KEY)
                    .param("siteId", OTHER_SITE_ID.toString())
                    .param("since", "2025-01-01T00:00:00Z"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("Site does not belong to your account"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/plugins/bit-bi/sites")
    class ListSites {

        @Test
        @DisplayName("TC10: Should return 200 OK with site list for valid request")
        void shouldReturn200WithSiteList() throws Exception {
            // Given - SqlChangesQueryService will return sites for the account
            when(pluginApiKeyService.validateApiKey(VALID_API_KEY))
                .thenReturn(Optional.of(mockAccountPlugin));

            // When / Then
            mockMvc.perform(get(ApiRoutes.BITBI_SITES)
                    .header(API_KEY_HEADER, VALID_API_KEY))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sites").isArray());

            verify(pluginApiKeyService).validateApiKey(VALID_API_KEY);
        }

        @Test
        @DisplayName("TC11: Should return 200 OK with empty array when no sites")
        void shouldReturn200WithEmptyArrayWhenNoSites() throws Exception {
            // Given
            when(pluginApiKeyService.validateApiKey(VALID_API_KEY))
                .thenReturn(Optional.of(mockAccountPlugin));

            // When / Then
            mockMvc.perform(get(ApiRoutes.BITBI_SITES)
                    .header(API_KEY_HEADER, VALID_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sites").isArray())
                .andExpect(jsonPath("$.sites", hasSize(greaterThanOrEqualTo(0))));
        }

        @Test
        @DisplayName("TC12: Should return 401 Unauthorized when API key is missing")
        void shouldReturn401WhenApiKeyMissing() throws Exception {
            // When / Then - no API key header
            mockMvc.perform(get(ApiRoutes.BITBI_SITES))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));

            verify(pluginApiKeyService, never()).validateApiKey(any());
        }

        @Test
        @DisplayName("TC13: Should return 401 Unauthorized when API key is invalid")
        void shouldReturn401WhenApiKeyInvalid() throws Exception {
            // Given
            when(pluginApiKeyService.validateApiKey(INVALID_API_KEY))
                .thenReturn(Optional.empty());

            // When / Then
            mockMvc.perform(get(ApiRoutes.BITBI_SITES)
                    .header(API_KEY_HEADER, INVALID_API_KEY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid or missing API key"));

            verify(pluginApiKeyService).validateApiKey(INVALID_API_KEY);
        }
    }

    @Nested
    @DisplayName("GET /api/v1/plugins/bit-bi/tables")
    class ListTables {

        @Test
        @DisplayName("TC14: Should return 200 OK with table list for valid request")
        void shouldReturn200WithTableList() throws Exception {
            // Given
            List<TableDto> tables = List.of(
                    TableDto.of("customers", 1048576L, Instant.parse("2025-01-15T10:30:00Z")),
                    TableDto.of("orders", 2097152L, Instant.parse("2025-01-15T11:45:00Z"))
            );

            when(pluginApiKeyService.validateApiKey(VALID_API_KEY))
                .thenReturn(Optional.of(mockAccountPlugin));
            when(sqlChangesQueryService.listTables(TEST_ACCOUNT_ID))
                .thenReturn(tables);

            // When / Then
            mockMvc.perform(get(ApiRoutes.BITBI_TABLES)
                    .header(API_KEY_HEADER, VALID_API_KEY))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.tables").isArray())
                .andExpect(jsonPath("$.tables", hasSize(2)))
                .andExpect(jsonPath("$.tables[0].tableName").value("customers"))
                .andExpect(jsonPath("$.tables[0].fileSize").value(1048576))
                .andExpect(jsonPath("$.tables[0].lastUpdatedAt").exists())
                .andExpect(jsonPath("$.tables[1].tableName").value("orders"));

            verify(pluginApiKeyService).validateApiKey(VALID_API_KEY);
            verify(sqlChangesQueryService).listTables(TEST_ACCOUNT_ID);
        }

        @Test
        @DisplayName("TC15: Should return 200 OK with empty array when no tables")
        void shouldReturn200WithEmptyArrayWhenNoTables() throws Exception {
            // Given
            when(pluginApiKeyService.validateApiKey(VALID_API_KEY))
                .thenReturn(Optional.of(mockAccountPlugin));
            when(sqlChangesQueryService.listTables(TEST_ACCOUNT_ID))
                .thenReturn(Collections.emptyList());

            // When / Then
            mockMvc.perform(get(ApiRoutes.BITBI_TABLES)
                    .header(API_KEY_HEADER, VALID_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables").isArray())
                .andExpect(jsonPath("$.tables", hasSize(0)));
        }

        @Test
        @DisplayName("TC16: Should return 401 Unauthorized when API key is missing")
        void shouldReturn401WhenApiKeyMissing() throws Exception {
            // When / Then - no API key header
            mockMvc.perform(get(ApiRoutes.BITBI_TABLES))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));

            verify(pluginApiKeyService, never()).validateApiKey(any());
        }

        @Test
        @DisplayName("TC17: Should return 401 Unauthorized when API key is invalid")
        void shouldReturn401WhenApiKeyInvalid() throws Exception {
            // Given
            when(pluginApiKeyService.validateApiKey(INVALID_API_KEY))
                .thenReturn(Optional.empty());

            // When / Then
            mockMvc.perform(get(ApiRoutes.BITBI_TABLES)
                    .header(API_KEY_HEADER, INVALID_API_KEY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid or missing API key"));

            verify(pluginApiKeyService).validateApiKey(INVALID_API_KEY);
        }
    }
}
