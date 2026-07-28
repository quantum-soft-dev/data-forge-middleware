package com.bitbi.dfm.contract;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.jdbc.Sql;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests for feature 032: retired client HTTP writes are absent while the
 * Device API's drain/read and error-reporting operations remain available.
 */
@DisplayName("Retired client HTTP API contract")
@Sql({"/test-data.sql", "/test-data-v2-site.sql"})
class FileApiClientVersionContractTest extends BaseIntegrationTest {

    private static final String V2_DOMAIN = "store-v2.example.com";
    private static final String V2_BATCH_ID = "b1c2d3e4-f5a6-7890-bcde-f12345678907";

    private String v2Token() {
        return generateToken(V2_DOMAIN);
    }

    // --- Legacy v1 client API (/api/dfc/**) ---

    @Test
    @DisplayName("Should deny removed /api/dfc/batch/start")
    void shouldDenyRemovedDfcBatchStart() throws Exception {
        mockMvc.perform(post("/api/dfc/batch/start")
                        .header("Authorization", v2Token())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Should deny removed /api/dfc/batch/{id}/upload")
    void shouldDenyRemovedDfcFileUpload() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "data.csv", "text/csv", "id,name\n1,test\n".getBytes());

        mockMvc.perform(multipart("/api/dfc/batch/" + V2_BATCH_ID + "/upload")
                        .file(file)
                        .header("Authorization", v2Token()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Should deny removed /api/dfc/schema")
    void shouldDenyRemovedDfcSchemaUpload() throws Exception {
        String schemaBody = """
                {
                  "tables": {
                    "customers": {
                      "columns": [{"name": "id", "type": "integer", "nullable": false}],
                      "primaryKey": ["id"],
                      "uniqueKeys": []
                    }
                  }
                }
                """;

        mockMvc.perform(post("/api/dfc/schema")
                        .header("Authorization", v2Token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(schemaBody))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Should deny removed /api/dfc/batch/{id}/complete")
    void shouldDenyRemovedDfcBatchComplete() throws Exception {
        mockMvc.perform(post("/api/dfc/batch/" + V2_BATCH_ID + "/complete")
                        .header("Authorization", v2Token())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // --- Device HTTP client API (/api/v1/device/**) ---

    @Test
    @DisplayName("Should not expose the obsolete Device HTTP batch-start mapping")
    void shouldNotExposeDeviceBatchStart() throws Exception {
        mockMvc.perform(post("/api/v1/device/batches/start")
                        .header("Authorization", v2Token())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("Should not expose the obsolete Device HTTP file-upload mapping")
    void shouldNotExposeDeviceFileUpload() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "data.csv", "text/csv", "id,name\n1,test\n".getBytes());

        mockMvc.perform(multipart("/api/v1/device/files/batches/" + V2_BATCH_ID + "/upload")
                        .file(file)
                        .header("Authorization", v2Token()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Should still allow /api/v1/device/batches/{id}/fail for V2 site (drain)")
    void shouldAllowDeviceBatchFailWhenSiteIsV2() throws Exception {
        mockMvc.perform(post("/api/v1/device/batches/" + V2_BATCH_ID + "/fail")
                        .header("Authorization", v2Token())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Should still allow /api/v1/device/errors for V2 site (no gRPC replacement yet)")
    void shouldAllowDeviceErrorLoggingWhenSiteIsV2() throws Exception {
        String errorBody = """
                {"type": "UPLOAD_FAILED", "message": "connectivity check failed"}
                """;

        mockMvc.perform(post("/api/v1/device/errors")
                        .header("Authorization", v2Token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(errorBody))
                .andExpect(status().is2xxSuccessful());
    }
}
