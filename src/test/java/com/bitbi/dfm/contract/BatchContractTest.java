package com.bitbi.dfm.contract;

import com.bitbi.dfm.auth.application.TokenService;
import com.bitbi.dfm.auth.domain.JwtToken;
import com.bitbi.dfm.config.TestSecurityConfig;
import com.bitbi.dfm.config.TestS3Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for Batch & Upload API (/api/v1/batch/*).
 * <p>
 * CRITICAL: These tests MUST FAIL before implementation.
 * Purpose: Validate batch lifecycle endpoints before building actual services.
 * </p>
 *
 * @see <a href="specs/001-technical-specification-data/contracts/batch-api.md">Batch API Contract</a>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestSecurityConfig.class, TestS3Config.class})
@Sql("/test-data.sql")
@DisplayName("Batch API Contract Tests")
class BatchContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenService tokenService;

    private static final String BATCH_START_ENDPOINT = "/api/v1/batch/start";
    private static final String BATCH_UPLOAD_ENDPOINT = "/api/v1/batch/{batchId}/upload";
    private static final String BATCH_COMPLETE_ENDPOINT = "/api/v1/batch/{batchId}/complete";
    private static final String BATCH_FAIL_ENDPOINT = "/api/v1/batch/{batchId}/fail";
    private static final String BATCH_CANCEL_ENDPOINT = "/api/v1/batch/{batchId}/cancel";

    private static final String MOCK_BATCH_ID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
    private static final String IN_PROGRESS_BATCH_ID = "0199bab2-8d63-8563-8340-edbf1c11c778";

    private String jwtToken;


    @BeforeEach
    void setUp() {
        // Generate real JWT token for store-01.example.com site
        JwtToken token = tokenService.generateToken("store-01.example.com", "valid-secret-uuid");
        jwtToken = token.token();
    }

    /**
     * Test Case 1: Start new batch should return batchId.
     * <p>
     * Given: Authenticated client with valid JWT
     * When: POST /api/v1/batch/start
     * Then: 200 OK with batchId in response
     * </p>
     */
    @Test
    @DisplayName("Should create new batch when authenticated client requests")
    void shouldCreateNewBatchWhenAuthenticatedClientRequests() throws Exception {

        JwtToken token = tokenService.generateToken("store-03.example.com", "batch-test-secret");
        final var jwtToken = token.token();


        // When: POST /api/v1/batch/start with Bearer token
        mockMvc.perform(post(BATCH_START_ENDPOINT)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON))

                // Then: 201 CREATED with batchId
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.batchId").exists())
                .andExpect(jsonPath("$.batchId").isString());
    }

    /**
     * Test Case 2: Start batch without authentication should return 401.
     */
    @Test
    @DisplayName("Should reject batch start when JWT token is missing")
    void shouldRejectBatchStartWhenJwtTokenMissing() throws Exception {
        // When: POST /api/v1/batch/start without Authorization header
        mockMvc.perform(post(BATCH_START_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON))

                // Then: 401 Unauthorized
                .andExpect(status().isUnauthorized());
    }

    /**
     * Test Case 3: Start batch when active batch exists should return 409.
     * <p>
     * Given: Site already has an IN_PROGRESS batch
     * When: POST /api/v1/batch/start
     * Then: 409 Conflict with appropriate message
     * </p>
     */
    @Test
    @DisplayName("Should reject batch start when active batch already exists")
    void shouldRejectBatchStartWhenActiveBatchExists() throws Exception {
        // When: POST /api/v1/batch/start (assuming active batch exists)
        mockMvc.perform(post(BATCH_START_ENDPOINT)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON))

                // Then: 409 Conflict
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 4: Upload files to batch should return upload summary.
     * <p>
     * Given: Active batch with IN_PROGRESS status
     * When: POST /api/v1/batch/{batchId}/upload with files
     * Then: 200 OK with upload summary
     * </p>
     */
    @Test
    @DisplayName("Should upload files when batch is in progress")
    void shouldUploadFilesWhenBatchInProgress() throws Exception {
        // Given: Mock multipart files
        MockMultipartFile file1 = new MockMultipartFile(
                "files",
                "data1.csv.gz",
                "application/gzip",
                "mock file content 1".getBytes()
        );

        MockMultipartFile file2 = new MockMultipartFile(
                "files",
                "data2.csv.gz",
                "application/gzip",
                "mock file content 2".getBytes()
        );

        // When: POST /api/v1/batch/{batchId}/upload
        mockMvc.perform(multipart(BATCH_UPLOAD_ENDPOINT, IN_PROGRESS_BATCH_ID)
                        .file(file1)
                        .file(file2)
                        .header("Authorization", "Bearer " + jwtToken))

                // Then: 200 OK with upload summary
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.uploadedFiles").isNumber())
                .andExpect(jsonPath("$.files").isArray())
                .andExpect(jsonPath("$.files[0].fileName").exists())
                .andExpect(jsonPath("$.files[0].fileSize").exists())
                .andExpect(jsonPath("$.files[0].uploadedAt").exists());
    }

    /**
     * Test Case 5: Upload duplicate filename should return 400.
     */
    @Test
    @DisplayName("Should reject file upload when duplicate filename exists")
    void shouldRejectFileUploadWhenDuplicateFilenameExists() throws Exception {
        // Given: File with duplicate name
        MockMultipartFile duplicateFile = new MockMultipartFile(
                "files",
                "existing-file.csv.gz",
                "application/gzip",
                "duplicate content".getBytes()
        );

        // When: POST /api/v1/batch/{batchId}/upload
        mockMvc.perform(multipart(BATCH_UPLOAD_ENDPOINT, MOCK_BATCH_ID)
                        .file(duplicateFile)
                        .header("Authorization", "Bearer " + jwtToken))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 6: Upload to non-existent batch should return 404.
     */
    @Test
    @DisplayName("Should reject file upload when batch not found")
    void shouldRejectFileUploadWhenBatchNotFound() throws Exception {
        String nonExistentBatchId = "00000000-0000-0000-0000-000000000000";

        MockMultipartFile file = new MockMultipartFile(
                "files",
                "test.csv.gz",
                "application/gzip",
                "test content".getBytes()
        );

        // When: POST /api/v1/batch/{batchId}/upload with non-existent batchId
        mockMvc.perform(multipart(BATCH_UPLOAD_ENDPOINT, nonExistentBatchId)
                        .file(file)
                        .header("Authorization", "Bearer " + jwtToken))

                // Then: 404 Not Found
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    /**
     * Test Case 7: Complete batch should return completion summary.
     * <p>
     * Given: Batch with uploaded files
     * When: POST /api/v1/batch/{batchId}/complete
     * Then: 200 OK with completion details
     * </p>
     */
    @Test
    @DisplayName("Should complete batch when files uploaded")
    void shouldCompleteBatchWhenFilesUploaded() throws Exception {
        // When: POST /api/v1/batch/{batchId}/complete
        mockMvc.perform(post(BATCH_COMPLETE_ENDPOINT, IN_PROGRESS_BATCH_ID)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON))

                // Then: 200 OK with completion summary
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.batchId").value(IN_PROGRESS_BATCH_ID))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").exists())
                .andExpect(jsonPath("$.uploadedFilesCount").isNumber())
                .andExpect(jsonPath("$.totalSize").isNumber());
    }

    /**
     * Test Case 8: Complete already completed batch should return 400.
     */
    @Test
    @DisplayName("Should reject completion when batch already completed")
    void shouldRejectCompletionWhenBatchAlreadyCompleted() throws Exception {
        // When: POST /api/v1/batch/{batchId}/complete on completed batch
        mockMvc.perform(post(BATCH_COMPLETE_ENDPOINT, MOCK_BATCH_ID)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 9: Fail batch should return status.
     * <p>
     * Given: Batch in IN_PROGRESS status
     * When: POST /api/v1/batch/{batchId}/fail
     * Then: 200 OK with FAILED status
     * </p>
     */
    @Test
    @DisplayName("Should mark batch as failed when client reports error")
    void shouldMarkBatchAsFailedWhenClientReportsError() throws Exception {
        // When: POST /api/v1/batch/{batchId}/fail
        String requestBody = "{\"reason\":\"Critical error during processing\"}";


        mockMvc.perform(post(BATCH_FAIL_ENDPOINT, IN_PROGRESS_BATCH_ID)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 200 OK with FAILED status
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.batchId").value(IN_PROGRESS_BATCH_ID))
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    /**
     * Test Case 10: Cancel batch should return status.
     * <p>
     * Given: Batch in IN_PROGRESS status
     * When: POST /api/v1/batch/{batchId}/cancel
     * Then: 200 OK with CANCELLED status
     * </p>
     */
    @Test
    @DisplayName("Should cancel batch when client requests")
    void shouldCancelBatchWhenClientRequests() throws Exception {
        // When: POST /api/v1/batch/{batchId}/cancel
        mockMvc.perform(post(BATCH_CANCEL_ENDPOINT, IN_PROGRESS_BATCH_ID)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON))

                // Then: 200 OK with CANCELLED status
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.batchId").value(IN_PROGRESS_BATCH_ID))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
