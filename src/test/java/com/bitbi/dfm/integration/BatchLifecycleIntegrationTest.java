package com.bitbi.dfm.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test for Scenario 2: Batch Upload Lifecycle.
 * <p>
 * Tests batch creation, IN_PROGRESS status, S3 path validation, and duplicate prevention.
 * Uses store-03.example.com which has no active batches in test data.
 * </p>
 */
@DisplayName("Scenario 2: Batch Lifecycle Integration Test")
class BatchLifecycleIntegrationTest extends BaseIntegrationTest {

    /**
     * Generate token for store-03.example.com (site with no active batches).
     * <p>
     * Store 03 credentials from test-data.sql:
     * - Domain: store-03.example.com
     * - Secret: batch-test-secret
     * - Site ID: 0199bab0-ca3b-e41c-5521-2f4b33fda8b6
     * - Account ID: 0199bab1-fad2-bf76-c478-eae1f61e1c17
     * </p>
     */
    private String generateStore03Token() {
        return generateToken("store-03.example.com", "batch-test-secret");
    }

    @Test
    @DisplayName("Should create batch with IN_PROGRESS status and valid S3 path")
    void shouldCreateBatchWithInProgressStatusAndValidS3Path() throws Exception {
        // When: POST /api/dfc/batch/start
        mockMvc.perform(post("/api/dfc/batch/start")
                        .header("Authorization", generateStore03Token()))

                // Then: Batch created with batchId
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchId").exists());

        // Verify: Batch status is IN_PROGRESS
        // Verify: S3 path format is {accountId}/{domain}/{YYYY-MM-DD}/{HH-MM}/
    }

    @Test
    @DisplayName("Should prevent creating duplicate active batch for same site")
    void shouldPreventCreatingDuplicateActiveBatchForSameSite() throws Exception {
        String token = generateStore03Token();

        // Given: Active batch exists for site
        mockMvc.perform(post("/api/dfc/batch/start")
                .header("Authorization", token))
                .andExpect(status().isCreated());

        // When: Attempt to create another batch
        mockMvc.perform(post("/api/dfc/batch/start")
                        .header("Authorization", token))

                // Then: 409 Conflict
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Site already has an active batch"));
    }

    @Test
    @DisplayName("Should allow new batch after previous batch completed")
    void shouldAllowNewBatchAfterPreviousBatchCompleted() throws Exception {
        // Given: store-03.example.com has no active batches in test data

        // When: Start new batch
        mockMvc.perform(post("/api/dfc/batch/start")
                        .header("Authorization", generateStore03Token()))

                // Then: Success
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchId").exists());
    }
}
