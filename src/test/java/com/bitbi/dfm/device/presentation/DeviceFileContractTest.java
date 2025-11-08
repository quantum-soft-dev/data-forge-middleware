package com.bitbi.dfm.device.presentation;

import com.bitbi.dfm.auth.application.TokenService;
import com.bitbi.dfm.auth.domain.JwtToken;
import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.bitbi.dfm.shared.api.ApiRoutes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for Device API File Management endpoints.
 * <p>
 * <b>Purpose</b>: Validate the Device API file upload/metadata contract.
 * </p>
 * <p>
 * <b>TDD Phase</b>: RED - These tests MUST FAIL initially since DeviceFileController
 * is not yet implemented.
 * </p>
 *
 * @see com.bitbi.dfm.device.presentation.DeviceFileController
 * @see <a href="specs/010-api-unification-goal/tasks.md">Task T011</a>
 */
@Transactional
@DisplayName("Device API - File Management Contract Tests")
class DeviceFileContractTest extends BaseIntegrationTest {

    @Autowired
    private TokenService tokenService;

    private String jwtToken;

    @BeforeEach
    void setUp() {
        // Generate real JWT token for store-01.example.com site (from test-data.sql)
        JwtToken token = tokenService.generateToken("store-01.example.com", "valid-secret-uuid");
        jwtToken = token.token();
    }

    /**
     * TC16: Upload file to batch with valid JWT should return 201 Created.
     * <p>
     * <b>Given</b>: Authenticated device client with valid JWT and IN_PROGRESS batch<br>
     * <b>When</b>: POST /api/v1/device/files/batches/{batchId}/upload with multipart file<br>
     * <b>Then</b>: 201 Created with FileUploadResponseDto
     * </p>
     */
    @Test
    @DisplayName("TC16: Should upload file with valid JWT")
    void shouldUploadFileWithValidJwt() throws Exception {
        String batchId = "b1c2d3e4-f5a6-7890-bcde-f12345678903"; // IN_PROGRESS batch for store-01

        MockMultipartFile file = new MockMultipartFile(
                "files",
                "test-file.csv",
                "text/csv",
                "test,data,content".getBytes()
        );

        mockMvc.perform(multipart(ApiRoutes.DEVICE_FILES_UPLOAD, batchId)
                        .file(file)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.batchId").value(batchId))
                .andExpect(jsonPath("$.filename").value("test-file.csv"))
                .andExpect(jsonPath("$.s3Key").exists())
                .andExpect(jsonPath("$.fileSize").exists())
                .andExpect(jsonPath("$.uploadedAt").exists());
    }

    /**
     * TC17: Upload without file should return 400 Bad Request.
     * <p>
     * <b>Given</b>: Authenticated device client with valid JWT<br>
     * <b>When</b>: POST /api/v1/device/files/batches/{batchId}/upload without file parameter<br>
     * <b>Then</b>: 400 Bad Request
     * </p>
     */
    @Test
    @DisplayName("TC17: Should return 400 when file is missing")
    void shouldReturn400WhenFileMissing() throws Exception {
        String batchId = "b1c2d3e4-f5a6-7890-bcde-f12345678903";

        mockMvc.perform(multipart(ApiRoutes.DEVICE_FILES_UPLOAD, batchId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isBadRequest());
    }

    /**
     * TC18: Get file metadata with valid JWT should return 200 OK.
     * <p>
     * <b>Given</b>: Authenticated device client with valid JWT and existing uploaded file<br>
     * <b>When</b>: GET /api/v1/device/files/batches/{batchId}/files/{fileId}<br>
     * <b>Then</b>: 200 OK with FileMetadataDto
     * </p>
     */
    @Test
    @DisplayName("TC18: Should get file metadata with valid JWT")
    void shouldGetFileMetadataWithValidJwt() throws Exception {
        String batchId = "b1c2d3e4-f5a6-7890-bcde-f12345678903";
        String fileId = "0199bab3-a134-e3e5-e76e-7ba0a7c44fa5"; // File associated with this batch

        mockMvc.perform(get(ApiRoutes.DEVICE_FILES_GET, batchId, fileId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(fileId))
                .andExpect(jsonPath("$.batchId").value(batchId))
                .andExpect(jsonPath("$.filename").value("existing-file.csv"))
                .andExpect(jsonPath("$.s3Key").exists())
                .andExpect(jsonPath("$.fileSize").exists())
                .andExpect(jsonPath("$.contentType").exists())
                .andExpect(jsonPath("$.checksum").exists())
                .andExpect(jsonPath("$.uploadedAt").exists());
    }

    /**
     * TC19: Upload file without JWT should return 401 Unauthorized.
     * <p>
     * <b>Given</b>: No authentication token<br>
     * <b>When</b>: POST /api/v1/device/files/batches/{batchId}/upload<br>
     * <b>Then</b>: 401 Unauthorized
     * </p>
     */
    @Test
    @DisplayName("TC19: Should reject file upload without JWT")
    void shouldRejectFileUploadWithoutJwt() throws Exception {
        String batchId = "b1c2d3e4-f5a6-7890-bcde-f12345678903";

        MockMultipartFile file = new MockMultipartFile(
                "files",
                "test-file.csv",
                "text/csv",
                "test,data,content".getBytes()
        );

        mockMvc.perform(multipart(ApiRoutes.DEVICE_FILES_UPLOAD, batchId)
                        .file(file)
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isUnauthorized());
    }

    /**
     * TC20: Get file metadata without JWT should return 401 Unauthorized.
     * <p>
     * <b>Given</b>: No authentication token<br>
     * <b>When</b>: GET /api/v1/device/files/batches/{batchId}/files/{fileId}<br>
     * <b>Then</b>: 401 Unauthorized
     * </p>
     */
    @Test
    @DisplayName("TC20: Should reject file get without JWT")
    void shouldRejectFileGetWithoutJwt() throws Exception {
        String batchId = "b1c2d3e4-f5a6-7890-bcde-f12345678903";
        String fileId = "0199bab3-a134-e3e5-e76e-7ba0a7c44fa5";

        mockMvc.perform(get(ApiRoutes.DEVICE_FILES_GET, batchId, fileId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    /**
     * TC21: Upload file to batch owned by different site should return 403 Forbidden.
     * <p>
     * <b>Given</b>: A batch owned by a different site than the authenticated JWT<br>
     * <b>When</b>: POST /api/v1/device/files/batches/{batchId}/upload<br>
     * <b>Then</b>: 403 Forbidden
     * </p>
     */
    @Test
    @DisplayName("TC21: Should return 403 when uploading to batch from different site")
    void shouldReturn403WhenUploadingToBatchFromDifferentSite() throws Exception {
        // Use batch from test-data.sql owned by site 0199baaf-ea7a-bd1f-6f6c-8610b9ddc4d7 (store-02)
        String otherSiteBatchId = "b1c2d3e4-f5a6-7890-bcde-f12345678905";

        MockMultipartFile file = new MockMultipartFile(
                "files",
                "test-file.csv",
                "text/csv",
                "test,data,content".getBytes()
        );

        mockMvc.perform(multipart(ApiRoutes.DEVICE_FILES_UPLOAD, otherSiteBatchId)
                        .file(file)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }
}
