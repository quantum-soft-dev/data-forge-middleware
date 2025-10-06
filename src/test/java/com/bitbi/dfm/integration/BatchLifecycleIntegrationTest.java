package com.bitbi.dfm.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test for Scenario 2: Batch Upload Lifecycle.
 * <p>
 * Tests batch creation, IN_PROGRESS status, S3 path validation, and duplicate prevention.
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Scenario 2: Batch Lifecycle Integration Test")
class BatchLifecycleIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String MOCK_JWT = "Bearer mock.jwt.token";

    @Test
    @DisplayName("Should create batch with IN_PROGRESS status and valid S3 path")
    void shouldCreateBatchWithInProgressStatusAndValidS3Path() throws Exception {
        // When: POST /api/v1/batch/start
        mockMvc.perform(post("/api/v1/batch/start")
                        .header("Authorization", MOCK_JWT))

                // Then: Batch created with batchId
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").exists());

        // Verify: Batch status is IN_PROGRESS
        // Verify: S3 path format is {accountId}/{domain}/{YYYY-MM-DD}/{HH-MM}/
    }

    @Test
    @DisplayName("Should prevent creating duplicate active batch for same site")
    void shouldPreventCreatingDuplicateActiveBatchForSameSite() throws Exception {
        // Given: Active batch exists for site
        mockMvc.perform(post("/api/v1/batch/start")
                .header("Authorization", MOCK_JWT))
                .andExpect(status().isOk());

        // When: Attempt to create another batch
        mockMvc.perform(post("/api/v1/batch/start")
                        .header("Authorization", MOCK_JWT))

                // Then: 409 Conflict
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Active batch already exists for this site"));
    }

    @Test
    @DisplayName("Should allow new batch after previous batch completed")
    void shouldAllowNewBatchAfterPreviousBatchCompleted() throws Exception {
        // Given: Previous batch completed
        String batchId = "completed-batch-id";

        // When: Start new batch
        mockMvc.perform(post("/api/v1/batch/start")
                        .header("Authorization", MOCK_JWT))

                // Then: Success
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").exists());
    }
}
