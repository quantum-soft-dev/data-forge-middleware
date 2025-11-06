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
 * Contract tests for Device API Error Management endpoints.
 * <p>
 * <b>Purpose</b>: Validate the Device API error logging contract.
 * </p>
 * <p>
 * <b>TDD Phase</b>: RED - These tests MUST FAIL initially since DeviceErrorController
 * is not yet implemented.
 * </p>
 *
 * @see com.bitbi.dfm.device.presentation.DeviceErrorController
 * @see <a href="specs/010-api-unification-goal/tasks.md">Task T013</a>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Sql("/test-data.sql")
@Transactional
@DisplayName("Device API - Error Management Contract Tests")
class DeviceErrorContractTest {

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
     * TC22: Log standalone error with valid JWT should return 201 Created.
     * <p>
     * <b>Given</b>: Authenticated device client with valid JWT<br>
     * <b>When</b>: POST /api/v1/device/errors with valid error payload<br>
     * <b>Then</b>: 201 Created with ErrorLogResponseDto
     * </p>
     */
    @Test
    @DisplayName("TC22: Should log standalone error with valid JWT")
    void shouldLogStandaloneErrorWithValidJwt() throws Exception {
        String errorPayload = """
                {
                    "type": "ValidationError",
                    "message": "Invalid data format in row 42",
                    "metadata": {
                        "filename": "data.csv",
                        "row": 42
                    }
                }
                """;

        mockMvc.perform(post(ApiRoutes.DEVICE_ERRORS_LOG)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(errorPayload))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.siteId").value("0199baac-f852-753f-6fc3-7c994fc38654"))
                .andExpect(jsonPath("$.type").value("ValidationError"))
                .andExpect(jsonPath("$.message").value("Invalid data format in row 42"))
                .andExpect(jsonPath("$.metadata").exists())
                .andExpect(jsonPath("$.occurredAt").exists());
    }

    /**
     * TC23: Log batch error with valid JWT should return 201 Created.
     * <p>
     * <b>Given</b>: Authenticated device client with valid JWT and existing batch<br>
     * <b>When</b>: POST /api/v1/device/errors/batches/{batchId} with valid error payload<br>
     * <b>Then</b>: 201 Created with ErrorLogResponseDto including batchId
     * </p>
     */
    @Test
    @DisplayName("TC23: Should log batch error with valid JWT")
    void shouldLogBatchErrorWithValidJwt() throws Exception {
        String batchId = "b1c2d3e4-f5a6-7890-bcde-f12345678903"; // IN_PROGRESS batch for store-01

        String errorPayload = """
                {
                    "type": "FileProcessingError",
                    "message": "Failed to parse CSV file",
                    "metadata": {
                        "filename": "sales.csv",
                        "error": "Invalid delimiter"
                    }
                }
                """;

        mockMvc.perform(post(ApiRoutes.DEVICE_ERRORS_LOG_BATCH, batchId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(errorPayload))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.batchId").value(batchId))
                .andExpect(jsonPath("$.siteId").value("0199baac-f852-753f-6fc3-7c994fc38654"))
                .andExpect(jsonPath("$.type").value("FileProcessingError"))
                .andExpect(jsonPath("$.message").value("Failed to parse CSV file"))
                .andExpect(jsonPath("$.metadata").exists())
                .andExpect(jsonPath("$.occurredAt").exists());
    }

    /**
     * TC24: Get error log with valid JWT should return 200 OK.
     * <p>
     * <b>Given</b>: Authenticated device client with valid JWT and existing error log<br>
     * <b>When</b>: GET /api/v1/device/errors/{errorId}<br>
     * <b>Then</b>: 200 OK with ErrorLogResponseDto
     * </p>
     */
    @Test
    @DisplayName("TC24: Should get error log with valid JWT")
    void shouldGetErrorLogWithValidJwt() throws Exception {
        String errorId = "0199bab3-d4d6-c1d1-226a-241c7b874314"; // Error from test-data.sql

        mockMvc.perform(get(ApiRoutes.DEVICE_ERRORS_GET, errorId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(errorId))
                .andExpect(jsonPath("$.siteId").value("0199baac-f852-753f-6fc3-7c994fc38654"))
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.occurredAt").exists());
    }

    /**
     * TC25: Log error with invalid payload should return 400 Bad Request.
     * <p>
     * <b>Given</b>: Authenticated device client with valid JWT<br>
     * <b>When</b>: POST /api/v1/device/errors with invalid payload (missing required fields)<br>
     * <b>Then</b>: 400 Bad Request
     * </p>
     */
    @Test
    @DisplayName("TC25: Should return 400 when error payload is invalid")
    void shouldReturn400WhenErrorPayloadInvalid() throws Exception {
        String invalidPayload = """
                {
                    "metadata": {
                        "some": "data"
                    }
                }
                """;

        mockMvc.perform(post(ApiRoutes.DEVICE_ERRORS_LOG)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest());
    }

    /**
     * TC26: Log error without JWT should return 401 Unauthorized.
     * <p>
     * <b>Given</b>: No authentication token<br>
     * <b>When</b>: POST /api/v1/device/errors<br>
     * <b>Then</b>: 401 Unauthorized
     * </p>
     */
    @Test
    @DisplayName("TC26: Should reject error log without JWT")
    void shouldRejectErrorLogWithoutJwt() throws Exception {
        String errorPayload = """
                {
                    "type": "ValidationError",
                    "message": "Test error"
                }
                """;

        mockMvc.perform(post(ApiRoutes.DEVICE_ERRORS_LOG)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(errorPayload))
                .andExpect(status().isUnauthorized());
    }

    /**
     * TC27: Get error log without JWT should return 401 Unauthorized.
     * <p>
     * <b>Given</b>: No authentication token<br>
     * <b>When</b>: GET /api/v1/device/errors/{errorId}<br>
     * <b>Then</b>: 401 Unauthorized
     * </p>
     */
    @Test
    @DisplayName("TC27: Should reject error get without JWT")
    void shouldRejectErrorGetWithoutJwt() throws Exception {
        String errorId = "0199bab3-d4d6-c1d1-226a-241c7b874314";

        mockMvc.perform(get(ApiRoutes.DEVICE_ERRORS_GET, errorId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    /**
     * TC28: Log batch error for batch owned by different site should return 403 Forbidden.
     * <p>
     * <b>Given</b>: A batch owned by a different site than the authenticated JWT<br>
     * <b>When</b>: POST /api/v1/device/errors/batches/{batchId}<br>
     * <b>Then</b>: 403 Forbidden
     * </p>
     */
    @Test
    @DisplayName("TC28: Should return 403 when logging error for batch from different site")
    void shouldReturn403WhenLoggingErrorForBatchFromDifferentSite() throws Exception {
        // Use batch from test-data.sql owned by site 0199baaf-ea7a-bd1f-6f6c-8610b9ddc4d7 (store-02)
        String otherSiteBatchId = "b1c2d3e4-f5a6-7890-bcde-f12345678905";

        String errorPayload = """
                {
                    "type": "TestError",
                    "message": "Test error message"
                }
                """;

        mockMvc.perform(post(ApiRoutes.DEVICE_ERRORS_LOG_BATCH, otherSiteBatchId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(errorPayload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }
}
