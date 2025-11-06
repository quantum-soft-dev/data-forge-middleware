package com.bitbi.dfm.device.presentation;

import com.bitbi.dfm.auth.application.TokenService;
import com.bitbi.dfm.auth.domain.JwtToken;
import com.bitbi.dfm.config.TestSecurityConfig;
import com.bitbi.dfm.shared.api.ApiRoutes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for Device API Batch Management endpoints.
 * <p>
 * <b>Purpose</b>: Validate the Device API batch lifecycle contract:
 * </p>
 * <ul>
 *   <li>POST /api/v1/device/batches/start - Start new batch</li>
 *   <li>POST /api/v1/device/batches/{id}/complete - Complete batch</li>
 *   <li>POST /api/v1/device/batches/{id}/fail - Fail batch</li>
 *   <li>POST /api/v1/device/batches/{id}/cancel - Cancel batch</li>
 *   <li>GET /api/v1/device/batches/{id} - Get batch details</li>
 * </ul>
 * <p>
 * <b>TDD Phase</b>: RED - These tests MUST FAIL initially since DeviceBatchController
 * is not yet implemented.
 * </p>
 *
 * @see com.bitbi.dfm.device.presentation.DeviceBatchController
 * @see <a href="specs/010-api-unification-goal/tasks.md">Task T009</a>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Sql("/test-data.sql")
@Transactional
@DisplayName("Device API - Batch Management Contract Tests")
class DeviceBatchContractTest {

    @Autowired
    private MockMvc mockMvc;

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
     * TC13: Start new batch when active batch exists should return 409 Conflict.
     * <p>
     * <b>Given</b>: Authenticated device client with valid JWT, site already has IN_PROGRESS batch<br>
     * <b>When</b>: POST /api/v1/device/batches/start<br>
     * <b>Then</b>: 409 Conflict (business rule: one active batch per site)
     * </p>
     * <p>
     * <b>Note</b>: This test verifies the business rule that prevents multiple concurrent batches
     * for the same site. Test data includes batch b1c2d3e4-f5a6-7890-bcde-f12345678903 (IN_PROGRESS)
     * for site 0199baac-f852-753f-6fc3-7c994fc38654.
     * </p>
     */
    @Test
    @DisplayName("TC13: Should return 409 when site already has active batch")
    void shouldReturn409WhenSiteHasActiveBatch() throws Exception {
        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_START)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"));
    }

    /**
     * TC14: Start batch without JWT should return 401 Unauthorized.
     * <p>
     * <b>Given</b>: No authentication token<br>
     * <b>When</b>: POST /api/v1/device/batches/start<br>
     * <b>Then</b>: 401 Unauthorized
     * </p>
     */
    @Test
    @DisplayName("TC14: Should reject batch start without JWT")
    void shouldRejectBatchStartWithoutJwt() throws Exception {
        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_START)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    /**
     * TC15: Complete batch with valid JWT should return 200 OK.
     * <p>
     * <b>Given</b>: An IN_PROGRESS batch owned by the authenticated site<br>
     * <b>When</b>: POST /api/v1/device/batches/{id}/complete<br>
     * <b>Then</b>: 200 OK with completed batch (status=COMPLETED)
     * </p>
     */
    @Test
    @DisplayName("TC15: Should complete batch with valid JWT")
    void shouldCompleteBatchWithValidJwt() throws Exception {
        // Use batch from test-data.sql: IN_PROGRESS batch for site 0199baac-f852-753f-6fc3-7c994fc38654
        String batchId = "b1c2d3e4-f5a6-7890-bcde-f12345678903"; // IN_PROGRESS batch from test data

        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_COMPLETE, batchId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(batchId))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").exists());
    }

    /**
     * TC16: Complete non-existent batch should return 404 Not Found.
     * <p>
     * <b>Given</b>: A batch ID that doesn't exist<br>
     * <b>When</b>: POST /api/v1/device/batches/{id}/complete<br>
     * <b>Then</b>: 404 Not Found
     * </p>
     */
    @Test
    @DisplayName("TC16: Should return 404 when completing non-existent batch")
    void shouldReturn404WhenCompletingNonExistentBatch() throws Exception {
        String nonExistentBatchId = "99999999-9999-9999-9999-999999999999";

        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_COMPLETE, nonExistentBatchId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    /**
     * TC17: Complete batch owned by different site should return 403 Forbidden.
     * <p>
     * <b>Given</b>: A batch owned by a different site than the authenticated JWT<br>
     * <b>When</b>: POST /api/v1/device/batches/{id}/complete<br>
     * <b>Then</b>: 403 Forbidden
     * </p>
     */
    @Test
    @DisplayName("TC17: Should return 403 when completing batch from different site")
    void shouldReturn403WhenCompletingBatchFromDifferentSite() throws Exception {
        // Use batch from test-data.sql owned by site 0199baaf-ea7a-bd1f-6f6c-8610b9ddc4d7 (store-02)
        String otherSiteBatchId = "b1c2d3e4-f5a6-7890-bcde-f12345678905"; // Batch for different site

        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_COMPLETE, otherSiteBatchId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    /**
     * TC18: Fail batch with valid JWT should return 200 OK.
     * <p>
     * <b>Given</b>: An IN_PROGRESS batch owned by the authenticated site<br>
     * <b>When</b>: POST /api/v1/device/batches/{id}/fail<br>
     * <b>Then</b>: 200 OK with failed batch (status=FAILED)
     * </p>
     */
    @Test
    @DisplayName("TC18: Should fail batch with valid JWT")
    void shouldFailBatchWithValidJwt() throws Exception {
        String batchId = "b1c2d3e4-f5a6-7890-bcde-f12345678903"; // IN_PROGRESS batch

        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_FAIL, batchId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(batchId))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.completedAt").exists());
    }

    /**
     * TC19: Cancel batch with valid JWT should return 200 OK.
     * <p>
     * <b>Given</b>: An IN_PROGRESS batch owned by the authenticated site<br>
     * <b>When</b>: POST /api/v1/device/batches/{id}/cancel<br>
     * <b>Then</b>: 200 OK with cancelled batch (status=CANCELLED)
     * </p>
     */
    @Test
    @DisplayName("TC19: Should cancel batch with valid JWT")
    void shouldCancelBatchWithValidJwt() throws Exception {
        String batchId = "b1c2d3e4-f5a6-7890-bcde-f12345678903"; // IN_PROGRESS batch

        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_CANCEL, batchId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(batchId))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.completedAt").exists());
    }

    /**
     * TC20: Get batch details with valid JWT should return 200 OK.
     * <p>
     * <b>Given</b>: A batch owned by the authenticated site<br>
     * <b>When</b>: GET /api/v1/device/batches/{id}<br>
     * <b>Then</b>: 200 OK with batch details
     * </p>
     */
    @Test
    @DisplayName("TC20: Should get batch details with valid JWT")
    void shouldGetBatchDetailsWithValidJwt() throws Exception {
        String batchId = "b1c2d3e4-f5a6-7890-bcde-f12345678903"; // IN_PROGRESS batch

        mockMvc.perform(get(ApiRoutes.DEVICE_BATCHES_GET, batchId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(batchId))
                .andExpect(jsonPath("$.batchId").exists())
                .andExpect(jsonPath("$.siteId").value("0199baac-f852-753f-6fc3-7c994fc38654"))
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.s3Path").exists());
    }

    /**
     * TC21: Get batch without JWT should return 401 Unauthorized.
     * <p>
     * <b>Given</b>: No authentication token<br>
     * <b>When</b>: GET /api/v1/device/batches/{id}<br>
     * <b>Then</b>: 401 Unauthorized
     * </p>
     */
    @Test
    @DisplayName("TC21: Should reject batch get without JWT")
    void shouldRejectBatchGetWithoutJwt() throws Exception {
        String batchId = "b1c2d3e4-f5a6-7890-bcde-f12345678903";

        mockMvc.perform(get(ApiRoutes.DEVICE_BATCHES_GET, batchId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    /**
     * TC22: Complete already completed batch should return 400 Bad Request.
     * <p>
     * <b>Given</b>: A batch that is already COMPLETED<br>
     * <b>When</b>: POST /api/v1/device/batches/{id}/complete<br>
     * <b>Then</b>: 400 Bad Request (invalid status transition)
     * </p>
     */
    @Test
    @DisplayName("TC22: Should return 400 when completing already completed batch")
    void shouldReturn400WhenCompletingAlreadyCompletedBatch() throws Exception {
        // Use completed batch from test-data.sql
        String completedBatchId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"; // COMPLETED batch

        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_COMPLETE, completedBatchId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }
}
