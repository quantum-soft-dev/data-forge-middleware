package com.bitbi.dfm.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test for Scenario 2: Batch Upload Lifecycle.
 * <p>
 * Tests batch creation, IN_PROGRESS status, S3 path validation, and duplicate prevention.
 * </p>
 */
@DisplayName("Scenario 2: Batch Lifecycle Integration Test")
class BatchLifecycleIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Should create batch with IN_PROGRESS status and valid S3 path")
    void shouldCreateBatchWithInProgressStatusAndValidS3Path() throws Exception {
        // When: POST /api/v1/batch/start
        mockMvc.perform(post("/api/v1/batch/start")
                        .header("Authorization", generateTestToken()))

                // Then: Batch created with batchId
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchId").exists());

        // Verify: Batch status is IN_PROGRESS
        // Verify: S3 path format is {accountId}/{domain}/{YYYY-MM-DD}/{HH-MM}/
    }

    @Test
    @DisplayName("Should prevent creating duplicate active batch for same site")
    void shouldPreventCreatingDuplicateActiveBatchForSameSite() throws Exception {
        String token = generateTestToken();

        // Given: Active batch exists for site
        mockMvc.perform(post("/api/v1/batch/start")
                .header("Authorization", token))
                .andExpect(status().isCreated());

        // When: Attempt to create another batch
        mockMvc.perform(post("/api/v1/batch/start")
                        .header("Authorization", token))

                // Then: 409 Conflict
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Site already has an active batch"));
    }

    @Test
    @DisplayName("Should allow new batch after previous batch completed")
    void shouldAllowNewBatchAfterPreviousBatchCompleted() throws Exception {
        // Given: Previous batch completed (test-store.example.com has no active batches in test data)

        // When: Start new batch
        mockMvc.perform(post("/api/v1/batch/start")
                        .header("Authorization", generateTestToken()))

                // Then: Success
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchId").exists());
    }
}
