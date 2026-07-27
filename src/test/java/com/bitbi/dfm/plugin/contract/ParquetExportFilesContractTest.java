package com.bitbi.dfm.plugin.contract;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.bitbi.dfm.plugin.application.DownloadLinkService;
import com.bitbi.dfm.plugin.application.ParquetExportCredentialsService;
import com.bitbi.dfm.plugin.application.ParquetExportFileService;
import com.bitbi.dfm.plugin.application.ParquetExportFileService.FileListing;
import com.bitbi.dfm.plugin.application.ParquetExportFileService.FileType;
import com.bitbi.dfm.plugin.application.ParquetExportFileService.ParquetFileItem;
import com.bitbi.dfm.plugin.application.PluginAuditService;
import com.bitbi.dfm.plugin.application.PluginRateLimiterService;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.DownloadLink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for GET /api/v1/plugins/parquet-export/files (028-parquet-export-plugin, T011).
 */
@DisplayName("Parquet Export Files Contract Tests")
class ParquetExportFilesContractTest extends BaseIntegrationTest {

    private static final String FILES_PATH = "/api/v1/plugins/parquet-export/files";
    private static final UUID ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final Long ACCOUNT_PLUGIN_ID = 42L;
    private static final UUID SITE_ID = UUID.randomUUID();
    private static final LocalDateTime PRODUCED_AT = LocalDateTime.of(2026, 7, 20, 10, 0);

    @MockitoBean
    private ParquetExportCredentialsService credentialsService;

    @MockitoBean
    private ParquetExportFileService fileService;

    @MockitoBean
    private DownloadLinkService downloadLinkService;

    @MockitoBean
    private PluginRateLimiterService rateLimiterService;

    @MockitoBean
    private PluginAuditService pluginAuditService;

    private AccountPlugin accountPlugin;

    @BeforeEach
    void setUp() {
        accountPlugin = mock(AccountPlugin.class);
        lenient().when(accountPlugin.getAccountId()).thenReturn(ACCOUNT_ID);
        lenient().when(accountPlugin.getId()).thenReturn(ACCOUNT_PLUGIN_ID);
        lenient().when(credentialsService.validate("pex_testLogin001", "goodPassword"))
                .thenReturn(Optional.of(accountPlugin));
        lenient().when(rateLimiterService.tryConsume(ACCOUNT_ID)).thenReturn(true);
    }

    private static String basicAuth(String userinfo) {
        return "Basic " + Base64.getEncoder().encodeToString(userinfo.getBytes(StandardCharsets.UTF_8));
    }

    private ParquetFileItem deltaItem() {
        return new ParquetFileItem(SITE_ID, "shop.example.com", "orders", FileType.DELTA,
                100L, 250L, null, PRODUCED_AT, "orders_seq100-250.parquet",
                "egress/" + SITE_ID + "/orders/delta/seq=100-250.parquet");
    }

    @Test
    @DisplayName("Should return files with one-time download URLs")
    void shouldReturnFilesWithOneTimeUrls() throws Exception {
        ParquetFileItem item = deltaItem();
        DownloadLink link = DownloadLink.register(ACCOUNT_PLUGIN_ID, item.s3Key(), item.fileName(),
                Duration.ofHours(1));
        when(fileService.listFiles(eq(ACCOUNT_ID), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new FileListing(List.of(item), 0, 50, false));
        when(downloadLinkService.registerLinks(ACCOUNT_PLUGIN_ID, List.of(item)))
                .thenReturn(List.of(link));

        mockMvc.perform(get(FILES_PATH)
                        .header("Authorization", basicAuth("pex_testLogin001:goodPassword")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files", hasSize(1)))
                .andExpect(jsonPath("$.files[0].siteId").value(SITE_ID.toString()))
                .andExpect(jsonPath("$.files[0].siteDomain").value("shop.example.com"))
                .andExpect(jsonPath("$.files[0].table").value("orders"))
                .andExpect(jsonPath("$.files[0].type").value("delta"))
                .andExpect(jsonPath("$.files[0].firstSeq").value(100))
                .andExpect(jsonPath("$.files[0].lastSeq").value(250))
                .andExpect(jsonPath("$.files[0].seq").value(nullValue()))
                .andExpect(jsonPath("$.files[0].fileName").value("orders_seq100-250.parquet"))
                .andExpect(jsonPath("$.files[0].downloadUrl",
                        containsString("/api/v1/plugins/parquet-export/download/" + link.getToken())))
                .andExpect(jsonPath("$.files[0].linkExpiresAt").exists())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.hasMore").value(false));

        verify(pluginAuditService).logFilesListed(eq("parquet-export"), eq(ACCOUNT_ID), any(), eq(1));
    }

    @Test
    @DisplayName("Should pass filters through to the file service")
    void shouldPassFilters() throws Exception {
        when(fileService.listFiles(eq(ACCOUNT_ID), eq(LocalDateTime.of(2026, 7, 1, 0, 0)),
                eq(SITE_ID), eq("orders"), eq(FileType.CHECKPOINT), eq(2), eq(10)))
                .thenReturn(new FileListing(List.of(), 2, 10, false));
        when(downloadLinkService.registerLinks(eq(ACCOUNT_PLUGIN_ID), eq(List.of())))
                .thenReturn(List.of());

        mockMvc.perform(get(FILES_PATH)
                        .header("Authorization", basicAuth("pex_testLogin001:goodPassword"))
                        .param("since", "2026-07-01T00:00:00")
                        .param("siteId", SITE_ID.toString())
                        .param("table", "orders")
                        .param("type", "checkpoint")
                        .param("page", "2")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files", hasSize(0)));
    }

    @Test
    @DisplayName("Should return 400 for malformed since")
    void shouldReturn400ForMalformedSince() throws Exception {
        mockMvc.perform(get(FILES_PATH)
                        .header("Authorization", basicAuth("pex_testLogin001:goodPassword"))
                        .param("since", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Should return 400 for unknown type")
    void shouldReturn400ForUnknownType() throws Exception {
        mockMvc.perform(get(FILES_PATH)
                        .header("Authorization", basicAuth("pex_testLogin001:goodPassword"))
                        .param("type", "zip"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Should return 400 for size over 100")
    void shouldReturn400ForOversizedPage() throws Exception {
        mockMvc.perform(get(FILES_PATH)
                        .header("Authorization", basicAuth("pex_testLogin001:goodPassword"))
                        .param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Should return 401 without credentials")
    void shouldReturn401WithoutCredentials() throws Exception {
        mockMvc.perform(get(FILES_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Basic realm=\"parquet-export\""));
    }

    @Test
    @DisplayName("Should return 401 for wrong credentials")
    void shouldReturn401ForWrongCredentials() throws Exception {
        when(credentialsService.validate("pex_testLogin001", "wrong")).thenReturn(Optional.empty());

        mockMvc.perform(get(FILES_PATH)
                        .header("Authorization", basicAuth("pex_testLogin001:wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should return 429 with Retry-After when rate limited")
    void shouldReturn429WhenRateLimited() throws Exception {
        when(rateLimiterService.tryConsume(ACCOUNT_ID)).thenReturn(false);
        when(rateLimiterService.getRetryAfterSeconds(ACCOUNT_ID)).thenReturn(42L);

        mockMvc.perform(get(FILES_PATH)
                        .header("Authorization", basicAuth("pex_testLogin001:goodPassword")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"));
    }
}
