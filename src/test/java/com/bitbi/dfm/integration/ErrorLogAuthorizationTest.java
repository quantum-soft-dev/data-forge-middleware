package com.bitbi.dfm.integration;

import com.bitbi.dfm.error.presentation.dto.LogErrorRequestDto;
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
    //   - IN_PROGRESS batch: 0199bab2-8d63-8563-8340-edbf1c11c778
    //   - Error log: 0199bab3-d4d6-c1d1-226a-241c7b874314 (in FAILED batch 0199bab2-ca1c)
    // - store-03.example.com (siteId: 0199bab0-ca3b-e41c-5521-2f4b33fda8b6, account: 0199bab1, DIFFERENT ACCOUNT)
    //   - COMPLETED batch: 0199bab2-dddd-dddd-dddd-dddddddddddd

    private static final String STORE_01_BATCH_ID = "0199bab2-8d63-8563-8340-edbf1c11c778"; // Owned by store-01
    private static final String STORE_03_BATCH_ID = "0199bab2-dddd-dddd-dddd-dddddddddddd"; // Owned by store-03 (different account)
    private static final String STORE_01_ERROR_ID = "0199bab3-d4d6-c1d1-226a-241c7b874314"; // Error in store-01's batch

    /**
     * Test Case 1: Site should NOT be able to log errors to another site's batch.
     * <p>
     * **CRITICAL P0 Security Test**
     * </p>
     */
    @Test
    @DisplayName("Should reject error logging to batch owned by another site (403 Forbidden)")
    void shouldReject_logErrorToOtherSiteBatch() throws Exception {
        // Given: Token for store-03.example.com (different account)
        String token03 = generateToken("store-03.example.com", "batch-test-secret");

        LogErrorRequestDto errorRequest = new LogErrorRequestDto(
                "MaliciousError",
                "Attempting cross-tenant error logging",
                Map.of("malicious", "true")
        );

        String requestBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(errorRequest);

        // When: Attempt to log error to store-01's batch
        mockMvc.perform(post("/api/dfc/error/{batchId}", STORE_01_BATCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .header("Authorization", token03))

                // Then: 403 Forbidden (tenant isolation enforced)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Cannot log errors to batch owned by another site"));
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
                Map.of("field", "amount", "value", "invalid")
        );

        String requestBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(errorRequest);

        // When: Log error to own batch
        mockMvc.perform(post("/api/dfc/error/{batchId}", STORE_01_BATCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .header("Authorization", token01))

                // Then: 201 Created (authorized)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchId").value(STORE_01_BATCH_ID))
                .andExpect(jsonPath("$.type").value("ValidationError"));
    }

    /**
     * Test Case 3: Site should NOT be able to retrieve error logs from another site's batch.
     * <p>
     * **CRITICAL P0 Security Test**
     * </p>
     */
    @Test
    @DisplayName("Should reject error log retrieval from batch owned by another site (403 Forbidden)")
    void shouldReject_getErrorLogFromOtherSiteBatch() throws Exception {
        // Given: Token for store-03.example.com (different account)
        String token03 = generateToken("store-03.example.com", "batch-test-secret");

        // When: Attempt to retrieve error log from store-01's batch
        mockMvc.perform(get("/api/dfc/error/log/{errorId}", STORE_01_ERROR_ID)
                        .header("Authorization", token03))

                // Then: 403 Forbidden (tenant isolation enforced)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Cannot access error log from batch owned by another site"));
    }

    /**
     * Test Case 4: Site SHOULD be able to retrieve error logs from its own batch.
     */
    @Test
    @DisplayName("Should allow error log retrieval from own batch (200 OK)")
    void shouldAllow_getErrorLogFromOwnBatch() throws Exception {
        // Given: Token for store-01.example.com
        String token01 = generateTestToken(); // Uses store-01 by default

        // When: Retrieve own error log
        mockMvc.perform(get("/api/dfc/error/log/{errorId}", STORE_01_ERROR_ID)
                        .header("Authorization", token01))

                // Then: 200 OK (authorized)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(STORE_01_ERROR_ID));
    }

    /**
     * Test Case 5: Non-existent batch should return 404 (not 403).
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
                null
        );

        String requestBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(errorRequest);

        String nonExistentBatchId = "00000000-0000-0000-0000-000000000000";

        // When: Log error to non-existent batch
        mockMvc.perform(post("/api/dfc/error/{batchId}", nonExistentBatchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .header("Authorization", token))

                // Then: 404 Not Found (batch doesn't exist)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Batch not found"));
    }
}
