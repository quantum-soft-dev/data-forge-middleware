package com.bitbi.dfm.plugin.contract;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.bitbi.dfm.plugin.application.DownloadLinkService;
import com.bitbi.dfm.plugin.application.ParquetExportCredentialsService;
import com.bitbi.dfm.plugin.application.ParquetExportFileService;
import com.bitbi.dfm.plugin.application.PluginRateLimiterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for GET /api/v1/plugins/parquet-export/download/{token}
 * (028-parquet-export-plugin, T012).
 */
@DisplayName("Parquet Export Download Contract Tests")
class ParquetExportDownloadContractTest extends BaseIntegrationTest {

    private static final String DOWNLOAD_PATH = "/api/v1/plugins/parquet-export/download/";
    private static final String TOKEN = "abcDEF123-_abcDEF123-_abcDEF123-_abcDEF123x";

    @MockitoBean
    private ParquetExportCredentialsService credentialsService;

    @MockitoBean
    private ParquetExportFileService fileService;

    @MockitoBean
    private DownloadLinkService downloadLinkService;

    @MockitoBean
    private PluginRateLimiterService rateLimiterService;

    @Test
    @DisplayName("Should redirect to presigned URL on first use without any authentication")
    void shouldRedirectOnFirstUse() throws Exception {
        when(downloadLinkService.consume(TOKEN)).thenReturn("https://s3.example.com/presigned?sig=x");

        mockMvc.perform(get(DOWNLOAD_PATH + TOKEN))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://s3.example.com/presigned?sig=x"))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    @DisplayName("Should return 410 Gone for consumed or expired link")
    void shouldReturn410ForGoneLink() throws Exception {
        when(downloadLinkService.consume(TOKEN))
                .thenThrow(new DownloadLinkService.LinkGoneException("consumed"));

        mockMvc.perform(get(DOWNLOAD_PATH + TOKEN))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value(410));
    }

    @Test
    @DisplayName("Should return 404 for unknown token")
    void shouldReturn404ForUnknownToken() throws Exception {
        when(downloadLinkService.consume(TOKEN))
                .thenThrow(new DownloadLinkService.LinkNotFoundException());

        mockMvc.perform(get(DOWNLOAD_PATH + TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
