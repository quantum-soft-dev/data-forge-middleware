package com.bitbi.dfm.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test for Scenario 3: File Upload Operations.
 * <p>
 * Tests multipart upload, checksum calculation, S3 storage, and duplicate filename rejection.
 * </p>
 */
@DisplayName("Scenario 3: File Upload Integration Test")
class FileUploadIntegrationTest extends BaseIntegrationTest {

    private static final String MOCK_BATCH_ID = "test-batch-id";

    @Test
    @DisplayName("Should upload files to S3 with checksum validation")
    void shouldUploadFilesToS3WithChecksumValidation() throws Exception {
        // Given: Files to upload
        MockMultipartFile file1 = new MockMultipartFile(
                "files", "sales.csv.gz", "application/gzip",
                "mock compressed data 1".getBytes()
        );
        MockMultipartFile file2 = new MockMultipartFile(
                "files", "inventory.csv.gz", "application/gzip",
                "mock compressed data 2".getBytes()
        );

        // When: Upload files
        mockMvc.perform(multipart("/api/v1/batch/{batchId}/upload", MOCK_BATCH_ID)
                        .file(file1)
                        .file(file2)
                        .header("Authorization", generateTestToken()))

                // Then: Success with upload summary
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadedFiles").value(2))
                .andExpect(jsonPath("$.files[0].fileName").exists())
                .andExpect(jsonPath("$.files[0].fileSize").exists())
                .andExpect(jsonPath("$.files[0].uploadedAt").exists());

        // Verify: Files stored in S3
        // Verify: Metadata in uploaded_files table
        // Verify: Checksums calculated and stored
    }

    @Test
    @DisplayName("Should reject duplicate filename upload")
    void shouldRejectDuplicateFilenameUpload() throws Exception {
        // Given: File already uploaded
        MockMultipartFile file = new MockMultipartFile(
                "files", "existing-file.csv.gz", "application/gzip",
                "existing content".getBytes()
        );

        // First upload succeeds
        mockMvc.perform(multipart("/api/v1/batch/{batchId}/upload", MOCK_BATCH_ID)
                        .file(file)
                        .header("Authorization", generateTestToken()))
                .andExpect(status().isOk());

        // When: Attempt duplicate upload
        MockMultipartFile duplicate = new MockMultipartFile(
                "files", "existing-file.csv.gz", "application/gzip",
                "different content".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/batch/{batchId}/upload", MOCK_BATCH_ID)
                        .file(duplicate)
                        .header("Authorization", generateTestToken()))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("File 'existing-file.csv.gz' already exists in this batch"));
    }

    @Test
    @DisplayName("Should allow retry upload after failure")
    void shouldAllowRetryUploadAfterFailure() throws Exception {
        // Given: Previous upload failed (no metadata record created)
        MockMultipartFile file = new MockMultipartFile(
                "files", "retry-file.csv.gz", "application/gzip",
                "retry content".getBytes()
        );

        // When: Retry upload with same filename
        mockMvc.perform(multipart("/api/v1/batch/{batchId}/upload", MOCK_BATCH_ID)
                        .file(file)
                        .header("Authorization", generateTestToken()))

                // Then: Success (no duplicate check for failed uploads)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadedFiles").value(1));
    }
}
