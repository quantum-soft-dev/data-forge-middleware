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
 * Contract tests for the per-site strangler rule (022, Task 7): a site flagged
 * {@code client_api_version = V2} is rejected on the HTTP file-path write endpoints with
 * {@code 409 Conflict} and {@code code = CLIENT_API_V2_REQUIRED}, while drain endpoints
 * (batch complete/fail/cancel) keep working for batches opened before the flip.
 *
 * <p>V1 sites remain covered by the pre-existing contract tests (fixtures pinned to V1).</p>
 */
@DisplayName("HTTP file API — client_api_version enforcement")
@Sql({"/test-data.sql", "/test-data-v2-site.sql"})
class FileApiClientVersionContractTest extends BaseIntegrationTest {

    private static final String V2_DOMAIN = "store-v2.example.com";
    private static final String V2_SECRET = "valid-secret-uuid";
    private static final String V2_BATCH_ID = "b1c2d3e4-f5a6-7890-bcde-f12345678907";

    private static final String EXPECTED_CODE = "CLIENT_API_V2_REQUIRED";

    private String v2Token() {
        return generateToken(V2_DOMAIN, V2_SECRET);
    }

    // --- Legacy v1 client API (/api/dfc/**) ---

    @Test
    @DisplayName("Should reject /api/dfc/batch/start with 409 CLIENT_API_V2_REQUIRED for V2 site")
    void shouldRejectDfcBatchStartWhenSiteIsV2() throws Exception {
        mockMvc.perform(post("/api/dfc/batch/start")
                        .header("Authorization", v2Token())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(EXPECTED_CODE))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("Should reject /api/dfc/batch/{id}/upload with 409 CLIENT_API_V2_REQUIRED for V2 site")
    void shouldRejectDfcFileUploadWhenSiteIsV2() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "data.csv", "text/csv", "id,name\n1,test\n".getBytes());

        mockMvc.perform(multipart("/api/dfc/batch/" + V2_BATCH_ID + "/upload")
                        .file(file)
                        .header("Authorization", v2Token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(EXPECTED_CODE));
    }

    @Test
    @DisplayName("Should reject /api/dfc/schema with 409 CLIENT_API_V2_REQUIRED for V2 site")
    void shouldRejectDfcSchemaUploadWhenSiteIsV2() throws Exception {
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
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(EXPECTED_CODE));
    }

    @Test
    @DisplayName("Should still allow /api/dfc/batch/{id}/complete for V2 site (drain)")
    void shouldAllowDfcBatchCompleteWhenSiteIsV2() throws Exception {
        mockMvc.perform(post("/api/dfc/batch/" + V2_BATCH_ID + "/complete")
                        .header("Authorization", v2Token())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // --- Device HTTP client API (/api/v1/device/**) ---

    @Test
    @DisplayName("Should reject /api/v1/device/batches/start with 409 CLIENT_API_V2_REQUIRED for V2 site")
    void shouldRejectDeviceBatchStartWhenSiteIsV2() throws Exception {
        mockMvc.perform(post("/api/v1/device/batches/start")
                        .header("Authorization", v2Token())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(EXPECTED_CODE))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("Should reject /api/v1/device/files/batches/{id}/upload with 409 CLIENT_API_V2_REQUIRED for V2 site")
    void shouldRejectDeviceFileUploadWhenSiteIsV2() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "data.csv", "text/csv", "id,name\n1,test\n".getBytes());

        mockMvc.perform(multipart("/api/v1/device/files/batches/" + V2_BATCH_ID + "/upload")
                        .file(file)
                        .header("Authorization", v2Token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(EXPECTED_CODE));
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
