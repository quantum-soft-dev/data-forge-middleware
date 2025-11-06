package com.bitbi.dfm.integration;

import com.bitbi.dfm.shared.api.ApiRoutes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end integration test for Device API workflow.
 * <p>
 * Tests complete device client workflow:
 * 1. Generate JWT token via authentication endpoint
 * 2. Start new batch
 * 3. Upload file to batch
 * 4. Log error for batch
 * 5. Complete batch
 * 6. Verify batch status = COMPLETED
 * </p>
 * <p>
 * Uses real services, database, and S3 (LocalStack) integration.
 * </p>
 *
 * @see com.bitbi.dfm.device.presentation.DeviceAuthController
 * @see com.bitbi.dfm.device.presentation.DeviceBatchController
 * @see com.bitbi.dfm.device.presentation.DeviceFileController
 * @see com.bitbi.dfm.device.presentation.DeviceErrorController
 * @see <a href="specs/010-api-unification-goal/tasks.md">Task T015</a>
 */
@DisplayName("Device API - End-to-End Integration Test")
class DeviceApiIntegrationTest extends BaseIntegrationTest {

    /**
     * Full Device API workflow test.
     * <p>
     * Simulates a complete device client session from authentication to batch completion.
     * </p>
     */
    @Test
    @DisplayName("Should complete full device workflow: auth → start → upload → log error → complete")
    void shouldCompleteFullDeviceWorkflow() throws Exception {
        // STEP 1: Generate JWT token via Device Auth endpoint
        String credentials = Base64.getEncoder()
                .encodeToString("store-03.example.com:batch-test-secret".getBytes());

        MvcResult authResult = mockMvc.perform(post(ApiRoutes.DEVICE_AUTH_TOKEN)
                        .header("Authorization", "Basic " + credentials)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.siteId").exists())
                .andExpect(jsonPath("$.domain").value("store-03.example.com"))
                .andReturn();

        // Extract JWT token from response
        String tokenJson = authResult.getResponse().getContentAsString();
        String jwtToken = com.jayway.jsonpath.JsonPath.read(tokenJson, "$.token");
        String bearerToken = "Bearer " + jwtToken;

        // STEP 2: Start new batch
        MvcResult batchResult = mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_START)
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.batchId").exists())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.siteId").exists())
                .andReturn();

        // Extract batch ID from response
        String batchJson = batchResult.getResponse().getContentAsString();
        String batchId = com.jayway.jsonpath.JsonPath.read(batchJson, "$.id");

        // STEP 3: Upload file to batch
        MockMultipartFile testFile = new MockMultipartFile(
                "files",
                "test-data.csv",
                "text/csv",
                "product,quantity,price\nWidget A,10,99.99\nWidget B,5,49.99".getBytes()
        );

        mockMvc.perform(multipart(ApiRoutes.DEVICE_FILES_UPLOAD, batchId)
                        .file(testFile)
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.batchId").value(batchId))
                .andExpect(jsonPath("$.filename").value("test-data.csv"))
                .andExpect(jsonPath("$.s3Key").exists())
                .andExpect(jsonPath("$.fileSize").exists())
                .andExpect(jsonPath("$.uploadedAt").exists());

        // STEP 4: Log error for batch
        String errorPayload = """
                {
                    "type": "DataValidationWarning",
                    "message": "Price value exceeds expected range",
                    "metadata": {
                        "filename": "test-data.csv",
                        "row": 1,
                        "column": "price",
                        "value": "99.99",
                        "expected_max": "50.00"
                    }
                }
                """;

        mockMvc.perform(post(ApiRoutes.DEVICE_ERRORS_LOG_BATCH, batchId)
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(errorPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.batchId").value(batchId))
                .andExpect(jsonPath("$.type").value("DataValidationWarning"))
                .andExpect(jsonPath("$.message").value("Price value exceeds expected range"))
                .andExpect(jsonPath("$.metadata").exists())
                .andExpect(jsonPath("$.occurredAt").exists());

        // STEP 5: Complete batch
        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_COMPLETE, batchId)
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(batchId))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").exists());

        // STEP 6: Verify batch status = COMPLETED
        mockMvc.perform(get(ApiRoutes.DEVICE_BATCHES_GET, batchId)
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(batchId))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.uploadedFilesCount").value(1))
                .andExpect(jsonPath("$.completedAt").exists());
    }

    /**
     * Verify that Device API rejects Keycloak tokens.
     * <p>
     * Tests that Device API endpoints properly reject OAuth2/Keycloak tokens
     * and only accept Custom JWT tokens.
     * </p>
     */
    @Test
    @DisplayName("Should reject Keycloak token on Device API endpoints")
    void shouldRejectKeycloakTokenOnDeviceApiEndpoints() throws Exception {
        // Given: Mock Keycloak token (invalid for Device API)
        String keycloakToken = "Bearer mock.keycloak.jwt.token";

        // When: Attempt to start batch with Keycloak token
        // Then: 401 Unauthorized (rejected by JwtAuthenticationFilter)
        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_START)
                        .header("Authorization", keycloakToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Verify that Device API enforces site ownership.
     * <p>
     * Tests that device clients cannot access resources belonging to other sites.
     * </p>
     */
    @Test
    @DisplayName("Should enforce site ownership for batch operations")
    void shouldEnforceSiteOwnershipForBatchOperations() throws Exception {
        // Given: JWT for store-01
        String store01Token = generateToken("store-01.example.com", "valid-secret-uuid");

        // And: Batch owned by store-02 (different site, same account)
        String store02BatchId = "b1c2d3e4-f5a6-7890-bcde-f12345678905";

        // When: Attempt to complete batch owned by different site
        // Then: 403 Forbidden
        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_COMPLETE, store02BatchId)
                        .header("Authorization", store01Token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    /**
     * Verify that Device Auth endpoint requires Basic Auth.
     * <p>
     * Tests that authentication endpoint properly validates credentials.
     * </p>
     */
    @Test
    @DisplayName("Should reject invalid credentials at Device Auth endpoint")
    void shouldRejectInvalidCredentialsAtDeviceAuthEndpoint() throws Exception {
        // Given: Invalid credentials (wrong secret)
        String invalidCredentials = Base64.getEncoder()
                .encodeToString("store-01.example.com:wrong-secret".getBytes());

        // When: Attempt authentication with invalid credentials
        // Then: 401 Unauthorized
        mockMvc.perform(post(ApiRoutes.DEVICE_AUTH_TOKEN)
                        .header("Authorization", "Basic " + invalidCredentials)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }
}
