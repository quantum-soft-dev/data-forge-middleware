package com.bitbi.dfm.integration;

import com.bitbi.dfm.shared.api.ApiRoutes;
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

    private static final String IN_PROGRESS_MOCK_BATCH_ID = "b1c2d3e4-f5a6-7890-bcde-f12345678903"; // IN_PROGRESS batch from test-data.sql

    @Test
    @DisplayName("Should complete batch and transition to COMPLETED status")
    void shouldCompleteBatchAndTransitionToCompletedStatus() throws Exception {
        // Given: Batch with uploaded files
        // When: POST /api/v1/device/batches/{id}/complete
        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_COMPLETE, IN_PROGRESS_MOCK_BATCH_ID)
                        .header("Authorization", generateTestToken()))

                // Then: Batch completed (200 OK with BatchResponseDto)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.batchId").value(IN_PROGRESS_MOCK_BATCH_ID))
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
        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_COMPLETE, IN_PROGRESS_MOCK_BATCH_ID)
                        .header("Authorization", generateTestToken()))
                .andExpect(status().isOk());

        // When: Attempt to upload file
        MockMultipartFile file = new MockMultipartFile(
                "files", "late-file.csv.gz", "application/gzip",
                "late content".getBytes()
        );

        mockMvc.perform(multipart(ApiRoutes.DEVICE_FILES_UPLOAD, IN_PROGRESS_MOCK_BATCH_ID)
                        .file(file)
                        .header("Authorization", generateTestToken()))

                // Then: 409 Conflict (batch is already completed)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Cannot upload files to batch with status: COMPLETED"));
    }

    @Test
    @DisplayName("Should prevent double completion")
    void shouldPreventDoubleCompletion() throws Exception {
        // Given: Already completed batch
        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_COMPLETE, IN_PROGRESS_MOCK_BATCH_ID)
                        .header("Authorization", generateTestToken()))
                .andExpect(status().isOk());

        // When: Attempt to complete again
        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_COMPLETE, IN_PROGRESS_MOCK_BATCH_ID)
                        .header("Authorization", generateTestToken()))

                // Then: 400 Bad Request with ErrorResponseDto
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").value("Cannot complete batch in status COMPLETED (expected IN_PROGRESS): batchId=" + IN_PROGRESS_MOCK_BATCH_ID));
    }

    @Test
    @DisplayName("Should allow new batch after completion")
    void shouldAllowNewBatchAfterCompletion() throws Exception {
        // Given: Previous batch completed
        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_COMPLETE, IN_PROGRESS_MOCK_BATCH_ID)
                        .header("Authorization", generateTestToken()))
                .andExpect(status().isOk());

        // When: Start new batch
        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_START)
                        .header("Authorization", generateTestToken()))

                // Then: Success (201 Created with BatchResponseDto)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.batchId").exists());
    }

    @Test
    @DisplayName("Should complete batch with warnings and transition to COMPLETED_WITH_WARNINGS status")
    void shouldCompleteBatchWithWarningsAndTransitionToCorrectStatus() throws Exception {
        // Given: Batch with uploaded files
        // When: POST /api/v1/device/batches/{id}/complete-with-warnings
        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_COMPLETE_WITH_WARNINGS, IN_PROGRESS_MOCK_BATCH_ID)
                        .header("Authorization", generateTestToken()))

                // Then: Batch completed with warnings (200 OK with BatchResponseDto)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.batchId").value(IN_PROGRESS_MOCK_BATCH_ID))
                .andExpect(jsonPath("$.status").value("COMPLETED_WITH_WARNINGS"))
                .andExpect(jsonPath("$.hasErrors").value(false))
                .andExpect(jsonPath("$.completedAt").exists())
                .andExpect(jsonPath("$.uploadedFilesCount").isNumber())
                .andExpect(jsonPath("$.totalSize").isNumber());
    }

    @Test
    @DisplayName("Should prevent file upload after batch completed with warnings")
    void shouldPreventFileUploadAfterBatchCompletedWithWarnings() throws Exception {
        // Given: Batch completed with warnings
        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_COMPLETE_WITH_WARNINGS, IN_PROGRESS_MOCK_BATCH_ID)
                        .header("Authorization", generateTestToken()))
                .andExpect(status().isOk());

        // When: Attempt to upload file
        MockMultipartFile file = new MockMultipartFile(
                "files", "late-file.csv.gz", "application/gzip",
                "late content".getBytes()
        );

        mockMvc.perform(multipart(ApiRoutes.DEVICE_FILES_UPLOAD, IN_PROGRESS_MOCK_BATCH_ID)
                        .file(file)
                        .header("Authorization", generateTestToken()))

                // Then: 409 Conflict (batch is already completed with warnings)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Cannot upload files to batch with status: COMPLETED_WITH_WARNINGS"));
    }

    @Test
    @DisplayName("Should allow new batch after completion with warnings")
    void shouldAllowNewBatchAfterCompletionWithWarnings() throws Exception {
        // Given: Previous batch completed with warnings
        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_COMPLETE_WITH_WARNINGS, IN_PROGRESS_MOCK_BATCH_ID)
                        .header("Authorization", generateTestToken()))
                .andExpect(status().isOk());

        // When: Start new batch
        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_START)
                        .header("Authorization", generateTestToken()))

                // Then: Success (201 Created with BatchResponseDto)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.batchId").exists());
    }
}
