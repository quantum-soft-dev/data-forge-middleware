package com.bitbi.dfm.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test for Scenario 4: Batch Completion.
 * <p>
 * Tests batch completion, status transition from IN_PROGRESS to COMPLETED,
 * and prevention of uploads after completion.
 * </p>
 */
@DisplayName("Scenario 4: Batch Completion Integration Test")
class BatchCompletionIntegrationTest extends BaseIntegrationTest {

    private static final String MOCK_BATCH_ID = "test-batch-id";

    @Test
    @DisplayName("Should complete batch and transition to COMPLETED status")
    void shouldCompleteBatchAndTransitionToCompletedStatus() throws Exception {
        // Given: Batch with uploaded files
        // When: POST /api/v1/batch/{batchId}/complete
        mockMvc.perform(post("/api/v1/batch/{batchId}/complete", MOCK_BATCH_ID)
                        .header("Authorization", generateTestToken()))

                // Then: Batch completed
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(MOCK_BATCH_ID))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").exists())
                .andExpect(jsonPath("$.uploadedFilesCount").isNumber())
                .andExpect(jsonPath("$.totalSize").isNumber());

        // Verify: Status transitioned from IN_PROGRESS to COMPLETED
        // Verify: completedAt timestamp set
    }

    @Test
    @DisplayName("Should prevent file upload after batch completion")
    void shouldPreventFileUploadAfterBatchCompletion() throws Exception {
        // Given: Completed batch
        mockMvc.perform(post("/api/v1/batch/{batchId}/complete", MOCK_BATCH_ID)
                        .header("Authorization", generateTestToken()))
                .andExpect(status().isOk());

        // When: Attempt to upload file
        MockMultipartFile file = new MockMultipartFile(
                "files", "late-file.csv.gz", "application/gzip",
                "late content".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/batch/{batchId}/upload", MOCK_BATCH_ID)
                        .file(file)
                        .header("Authorization", generateTestToken()))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cannot upload files to completed batch"));
    }

    @Test
    @DisplayName("Should prevent double completion")
    void shouldPreventDoubleCompletion() throws Exception {
        // Given: Already completed batch
        mockMvc.perform(post("/api/v1/batch/{batchId}/complete", MOCK_BATCH_ID)
                        .header("Authorization", generateTestToken()))
                .andExpect(status().isOk());

        // When: Attempt to complete again
        mockMvc.perform(post("/api/v1/batch/{batchId}/complete", MOCK_BATCH_ID)
                        .header("Authorization", generateTestToken()))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Batch is already completed"));
    }

    @Test
    @DisplayName("Should allow new batch after completion")
    void shouldAllowNewBatchAfterCompletion() throws Exception {
        // Given: Previous batch completed
        mockMvc.perform(post("/api/v1/batch/{batchId}/complete", MOCK_BATCH_ID)
                        .header("Authorization", generateTestToken()))
                .andExpect(status().isOk());

        // When: Start new batch
        mockMvc.perform(post("/api/v1/batch/start")
                        .header("Authorization", generateTestToken()))

                // Then: Success (no more IN_PROGRESS conflict)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchId").exists());
    }
}
