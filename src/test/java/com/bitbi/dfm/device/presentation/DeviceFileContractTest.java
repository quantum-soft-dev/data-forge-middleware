package com.bitbi.dfm.device.presentation;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.bitbi.dfm.shared.api.ApiRoutes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for Device API File Management endpoints.
 * <p>
 * <b>Purpose</b>: Validate the Device API file metadata contract.
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

    private String jwtToken;

    @BeforeEach
    void setUp() {
        // Generate real JWT token for store-01.example.com site (from test-data.sql)
        jwtToken = generateToken("store-01.example.com");
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
                        .header("Authorization", jwtToken)
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

}
