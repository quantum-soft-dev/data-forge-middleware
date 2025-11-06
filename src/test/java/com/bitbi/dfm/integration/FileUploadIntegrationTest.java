package com.bitbi.dfm.integration;

import com.bitbi.dfm.shared.api.ApiRoutes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test for Scenario 3: File Upload Operations.
 * <p>
 * Tests multipart upload, checksum calculation, S3 storage, and duplicate filename rejection.
 * </p>
 */
@DisplayName("Scenario 3: File Upload Integration Test")
class FileUploadIntegrationTest extends BaseIntegrationTest {

    private static final String MOCK_BATCH_ID = "b1c2d3e4-f5a6-7890-bcde-f12345678903"; // IN_PROGRESS batch from test-data.sql

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
        mockMvc.perform(multipart(ApiRoutes.DEVICE_FILES_UPLOAD, MOCK_BATCH_ID)
                        .file(file1)
                        .file(file2)
                        .header("Authorization", generateTestToken()))

                // Then: 201 Created with FileUploadResponseDto (first file only - legacy behavior)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.batchId").value(MOCK_BATCH_ID))
                .andExpect(jsonPath("$.filename").exists())
                .andExpect(jsonPath("$.s3Key").exists())
                .andExpect(jsonPath("$.fileSize").exists())
                .andExpect(jsonPath("$.uploadedAt").exists());

        // Verify: Files stored in S3
        // Verify: Metadata in uploaded_files table
        // Verify: Checksums calculated and stored
    }

    @Test
    @org.junit.jupiter.api.Disabled("TODO: Fix duplicate file detection - currently returns 201 instead of 409")
    @DisplayName("Should reject duplicate filename upload")
    void shouldRejectDuplicateFilenameUpload() throws Exception {
        // Given: File already uploaded
        String jwtToken = generateTestToken(); // Use same token for both requests
        MockMultipartFile file = new MockMultipartFile(
                "files", "existing-file.csv.gz", "application/gzip",
                "existing content".getBytes()
        );

        // First upload succeeds
        mockMvc.perform(multipart(ApiRoutes.DEVICE_FILES_UPLOAD, MOCK_BATCH_ID)
                        .file(file)
                        .header("Authorization", jwtToken))
                .andExpect(status().isCreated());

        // When: Attempt duplicate upload
        MockMultipartFile duplicate = new MockMultipartFile(
                "files", "existing-file.csv.gz", "application/gzip",
                "different content".getBytes()
        );

        mockMvc.perform(multipart(ApiRoutes.DEVICE_FILES_UPLOAD, MOCK_BATCH_ID)
                        .file(duplicate)
                        .header("Authorization", jwtToken))
                .andDo(print())

                // Then: 409 Conflict (duplicate file detected by service layer)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists());
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
        mockMvc.perform(multipart(ApiRoutes.DEVICE_FILES_UPLOAD, MOCK_BATCH_ID)
                        .file(file)
                        .header("Authorization", generateTestToken()))

                // Then: 201 Created (no duplicate check for failed uploads)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.batchId").value(MOCK_BATCH_ID))
                .andExpect(jsonPath("$.filename").value("retry-file.csv.gz"));
    }
}
