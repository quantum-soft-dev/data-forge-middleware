package com.bitbi.dfm.integration;

import com.bitbi.dfm.error.presentation.dto.LogErrorRequestDto;
import com.bitbi.dfm.shared.api.ApiRoutes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Security tests for error logging authorization.
 * <p>
 * **CRITICAL P0**: Validates tenant isolation - sites cannot log/access errors in other sites' batches.
 * </p>
 * <p>
 * Addresses security vulnerability: Cross-tenant data access in error logging operations.
 * </p>
 */
@DisplayName("Security: Error Log Authorization Tests")
class ErrorLogAuthorizationTest extends BaseIntegrationTest {

    // test-data.sql contains:
    // - store-01.example.com (siteId: 0199baac-f852-753f-6fc3-7c994fc38654, account: a1b2c3d4)
    //   - IN_PROGRESS batch: b1c2d3e4-f5a6-7890-bcde-f12345678903
    // - store-02.example.com (siteId: 0199baaf-ea7a-bd1f-6f6c-8610b9ddc4d7, account: a1b2c3d4, SAME ACCOUNT but different site)
    //   - IN_PROGRESS batch: b1c2d3e4-f5a6-7890-bcde-f12345678905
    // Security test: site-01 cannot access site-02's batch even though they share the same account

    private static final String STORE_01_BATCH_ID = "b1c2d3e4-f5a6-7890-bcde-f12345678903"; // Owned by store-01
    private static final String STORE_02_BATCH_ID = "b1c2d3e4-f5a6-7890-bcde-f12345678905"; // Owned by store-02 (same account, different site)

    /**
     * Test Case 1: Site should NOT be able to log errors to another site's batch.
     * <p>
     * **CRITICAL P0 Security Test**
     * </p>
     */
    @Test
    @DisplayName("Should reject error logging to batch owned by another site (403 Forbidden)")
    void shouldReject_logErrorToOtherSiteBatch() throws Exception {
        // Given: Token for store-02.example.com (same account, different site)
        String token02 = generateToken("store-02.example.com");

        LogErrorRequestDto errorRequest = new LogErrorRequestDto(
                "MaliciousError",
                "Attempting cross-tenant error logging",
                null, // severity - use default
                Map.of("malicious", "true")
        );

        String requestBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(errorRequest);

        // When: Attempt to log error to store-01's batch (using store-02's token)
        mockMvc.perform(post(ApiRoutes.DEVICE_ERRORS_LOG_BATCH, STORE_01_BATCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .header("Authorization", token02))

                // Then: 403 Forbidden (tenant isolation enforced - different site)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied: site ownership mismatch"));
    }

    /**
     * Test Case 2: Site SHOULD be able to log errors to its own batch.
     */
    @Test
    @DisplayName("Should allow error logging to own batch (201 Created)")
    void shouldAllow_logErrorToOwnBatch() throws Exception {
        // Given: Token for store-01.example.com
        String token01 = generateTestToken(); // Uses store-01 by default

        LogErrorRequestDto errorRequest = new LogErrorRequestDto(
                "ValidationError",
                "Legitimate error message",
                null, // severity - use default
                Map.of("field", "amount", "value", "invalid")
        );

        String requestBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(errorRequest);

        // When: Log error to own batch
        mockMvc.perform(post(ApiRoutes.DEVICE_ERRORS_LOG_BATCH, STORE_01_BATCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .header("Authorization", token01))

                // Then: 201 Created (authorized)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchId").value(STORE_01_BATCH_ID))
                .andExpect(jsonPath("$.type").value("ValidationError"));
    }

    /**
     * Test Case 3: Non-existent batch should return 404 (not 403).
     * <p>
     * Security consideration: Don't leak information about batch existence.
     * </p>
     */
    @Test
    @DisplayName("Should return 404 for non-existent batch (not 403)")
    void shouldReturn404_forNonExistentBatch() throws Exception {
        // Given: Valid token
        String token = generateTestToken(); // Uses store-01 by default

        LogErrorRequestDto errorRequest = new LogErrorRequestDto(
                "TestError",
                "Test message",
                null, // severity - use default
                null  // metadata
        );

        String requestBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(errorRequest);

        String nonExistentBatchId = "00000000-0000-0000-0000-000000000000";

        // When: Log error to non-existent batch
        mockMvc.perform(post(ApiRoutes.DEVICE_ERRORS_LOG_BATCH, nonExistentBatchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .header("Authorization", token))

                // Then: 404 Not Found (batch doesn't exist)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Batch not found"));
    }
}
