package com.bitbi.dfm.integration;

import com.bitbi.dfm.shared.api.ApiRoutes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Security tests for file upload authorization.
 * <p>
 * **CRITICAL P0**: Validates tenant isolation - sites cannot upload/access files in other sites' batches.
 * </p>
 * <p>
 * Addresses security vulnerability: Cross-tenant data access in file upload operations.
 * </p>
 */
@DisplayName("Security: File Upload Authorization Tests")
class FileUploadAuthorizationTest extends BaseIntegrationTest {

    // test-data.sql contains:
    // - store-01.example.com (siteId: 0199baac-f852-753f-6fc3-7c994fc38654, account: a1b2c3d4)
    //   - IN_PROGRESS batch: b1c2d3e4-f5a6-7890-bcde-f12345678903
    // - store-02.example.com (siteId: 0199baaf-ea7a-bd1f-6f6c-8610b9ddc4d7, account: a1b2c3d4, SAME ACCOUNT but different site)
    //   - IN_PROGRESS batch: b1c2d3e4-f5a6-7890-bcde-f12345678905
    // Security test: site-01 cannot access site-02's batch even though they share the same account

    private static final String STORE_01_BATCH_ID = "b1c2d3e4-f5a6-7890-bcde-f12345678903"; // Owned by store-01
    private static final String STORE_02_BATCH_ID = "b1c2d3e4-f5a6-7890-bcde-f12345678905"; // Owned by store-02 (same account, different site)

    /**
     * Test Case 1: Site should NOT be able to upload files to another site's batch.
     * <p>
     * **CRITICAL P0 Security Test**
     * </p>
     */
    @Test
    @DisplayName("Should reject file upload to batch owned by another site (403 Forbidden)")
    void shouldReject_uploadToOtherSiteBatch() throws Exception {
        // Given: Token for store-02.example.com (same account, different site)
        String token02 = generateToken("store-02.example.com", "inactive-secret-uuid");

        MockMultipartFile file = new MockMultipartFile(
                "files",
                "malicious-upload.csv.gz",
                "application/gzip",
                "malicious content".getBytes()
        );

        // When: Attempt to upload to store-01's batch (using store-02's token)
        mockMvc.perform(multipart(ApiRoutes.DEVICE_FILES_UPLOAD, STORE_01_BATCH_ID)
                        .file(file)
                        .header("Authorization", token02))

                // Then: 403 Forbidden (tenant isolation enforced - different site)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("Access denied: site ownership mismatch"));
    }

    /**
     * Test Case 2: Site SHOULD be able to upload files to its own batch.
     */
    @Test
    @DisplayName("Should allow file upload to own batch (200 OK)")
    void shouldAllow_uploadToOwnBatch() throws Exception {
        // Given: Token for store-01.example.com
        String token01 = generateTestToken(); // Uses store-01 by default

        MockMultipartFile file = new MockMultipartFile(
                "files",
                "legitimate-upload.csv.gz",
                "application/gzip",
                "legitimate content".getBytes()
        );

        // When: Upload to own batch
        mockMvc.perform(multipart(ApiRoutes.DEVICE_FILES_UPLOAD, STORE_01_BATCH_ID)
                        .file(file)
                        .header("Authorization", token01))

                // Then: 201 Created (file uploaded successfully)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.batchId").value(STORE_01_BATCH_ID))
                .andExpect(jsonPath("$.filename").value("legitimate-upload.csv.gz"));
    }

    /**
     * Test Case 3: Site should NOT be able to retrieve files from another site's batch.
     * <p>
     * **CRITICAL P0 Security Test**
     * </p>
     */
    @Test
    @DisplayName("Should reject file retrieval from batch owned by another site (403 Forbidden)")
    void shouldReject_getFileFromOtherSiteBatch() throws Exception {
        // Given: Token for store-02.example.com (same account, different site)
        String token02 = generateToken("store-02.example.com", "inactive-secret-uuid");

        // Note: test-data.sql contains uploaded file with known ID in store-01's batch
        String fileId = "0199bab3-a134-e3e5-e76e-7ba0a7c44fa5"; // File in store-01's batch

        // When: Attempt to retrieve file from store-01's batch (using store-02's token)
        mockMvc.perform(get(ApiRoutes.DEVICE_FILES_GET,
                        STORE_01_BATCH_ID, fileId)
                        .header("Authorization", token02))

                // Then: 403 Forbidden (tenant isolation enforced - different site)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("Access denied: site ownership mismatch"));
    }

    /**
     * Test Case 4: Site SHOULD be able to retrieve files from its own batch.
     */
    @Test
    @DisplayName("Should allow file retrieval from own batch (200 OK)")
    void shouldAllow_getFileFromOwnBatch() throws Exception {
        // Given: Token for store-01.example.com
        String token01 = generateTestToken(); // Uses store-01 by default

        // Use existing file from test-data.sql
        String fileId = "0199bab3-a134-e3e5-e76e-7ba0a7c44fa5"; // File in store-01's batch

        // When: Retrieve own file
        mockMvc.perform(get(ApiRoutes.DEVICE_FILES_GET,
                        STORE_01_BATCH_ID, fileId)
                        .header("Authorization", token01))

                // Then: 200 OK (authorized)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fileId))
                .andExpect(jsonPath("$.batchId").value(STORE_01_BATCH_ID));
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

        MockMultipartFile file = new MockMultipartFile(
                "files",
                "test.csv.gz",
                "application/gzip",
                "content".getBytes()
        );

        String nonExistentBatchId = "00000000-0000-0000-0000-000000000000";

        // When: Upload to non-existent batch
        mockMvc.perform(multipart(ApiRoutes.DEVICE_FILES_UPLOAD, nonExistentBatchId)
                        .file(file)
                        .header("Authorization", token))

                // Then: 404 Not Found (batch doesn't exist)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Batch not found"));
    }
}
