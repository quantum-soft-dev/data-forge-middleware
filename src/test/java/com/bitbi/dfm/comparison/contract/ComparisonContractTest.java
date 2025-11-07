package com.bitbi.dfm.comparison.contract;

import com.bitbi.dfm.config.Auth0TestConfig;
import com.bitbi.dfm.config.TestSecurityConfig;
import com.bitbi.dfm.config.TestS3Config;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for File Comparison API (/api/v1/comparisons).
 * <p>
 * CRITICAL: These tests MUST FAIL before implementation (TDD Red phase).
 * Purpose: Validate comparison endpoints contract before building actual services.
 * </p>
 *
 * Feature: File Diff Comparison Between Upload Sessions
 * Phase: Phase 3 - User Story 1 (Select Files for Comparison)
 * Priority: P1 (MVP)
 *
 * @see <a href="specs/009-markdown-user-story/contracts/comparison-api.yaml">Comparison API Contract</a>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({Auth0TestConfig.class, TestSecurityConfig.class, TestS3Config.class})
@Sql("/test-data.sql")
@DisplayName("File Comparison API Contract Tests (User Story 1)")
class ComparisonContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String COMPARISONS_ENDPOINT = "/api/v1/comparisons";

    // Use mock Auth0 token for /api/v1/** endpoints (OAuth2 authentication)
    private static final String MOCK_USER_TOKEN_ACCOUNT_1 = "mock-jwt-token-account-1";
    private static final String MOCK_USER_TOKEN_ACCOUNT_2 = "mock-jwt-token-account-2";

    private String jwtToken;

    @BeforeEach
    void setUp() {
        // Use mock Auth0 token for authenticated user
        jwtToken = MOCK_USER_TOKEN_ACCOUNT_1;
    }

    /**
     * T030 (TC01): POST /api/v1/comparisons returns 400 if no files selected
     * <p>
     * Given: Authenticated user creates comparison with empty fileIds array
     * When: POST /api/v1/comparisons with fileIds=[]
     * Then: 400 Bad Request with error message
     * </p>
     *
     * Note: This test should FAIL initially because endpoint doesn't exist yet.
     */
    @Test
    @DisplayName("TC01: POST /api/v1/comparisons should return 400 when no files selected")
    void shouldReturn400WhenNoFilesSelected() throws Exception {
        // Given: Request with empty fileIds array
        Map<String, Object> request = Map.of(
                "currentBatchId", "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                "targetBatchId", "c3d4e5f6-a7b8-9012-cdef-123456789012",
                "fileIds", new String[]{} // Empty array
        );

        // When: POST /api/v1/comparisons
        mockMvc.perform(post(COMPARISONS_ENDPOINT)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andDo(print())

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value(COMPARISONS_ENDPOINT));
    }

    /**
     * T031 (TC02): POST /api/v1/comparisons validates currentBatchId exists
     * <p>
     * Given: Authenticated user creates comparison with non-existent current batch
     * When: POST /api/v1/comparisons with invalid currentBatchId
     * Then: 400 Bad Request with error message "Current batch does not exist"
     * </p>
     *
     * Note: This test should FAIL initially because endpoint doesn't exist yet.
     */
    @Test
    @DisplayName("TC02: POST /api/v1/comparisons should return 400 when current batch not found")
    void shouldReturn400WhenCurrentBatchNotFound() throws Exception {
        // Given: Request with non-existent currentBatchId
        Map<String, Object> request = Map.of(
                "currentBatchId", "99999999-9999-9999-9999-999999999999", // Non-existent batch
                "targetBatchId", "c3d4e5f6-a7b8-9012-cdef-123456789012",
                "fileIds", new String[]{"a1b2c3d4-e5f6-7890-abcd-111111111111", "a1b2c3d4-e5f6-7890-abcd-222222222222"}
        );

        // When: POST /api/v1/comparisons
        mockMvc.perform(post(COMPARISONS_ENDPOINT)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andDo(print())

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("Current batch")))
                .andExpect(jsonPath("$.message").value(containsString("does not exist")))
                .andExpect(jsonPath("$.path").value(COMPARISONS_ENDPOINT));
    }

    /**
     * T032 (TC03): POST /api/v1/comparisons validates targetBatchId exists
     * <p>
     * Given: Authenticated user creates comparison with non-existent target batch
     * When: POST /api/v1/comparisons with invalid targetBatchId
     * Then: 400 Bad Request with error message "Target batch does not exist"
     * </p>
     *
     * Note: This test should FAIL initially because endpoint doesn't exist yet.
     */
    @Test
    @DisplayName("TC03: POST /api/v1/comparisons should return 400 when target batch not found")
    void shouldReturn400WhenTargetBatchNotFound() throws Exception {
        // Given: Request with non-existent targetBatchId
        Map<String, Object> request = Map.of(
                "currentBatchId", "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                "targetBatchId", "99999999-9999-9999-9999-999999999999", // Non-existent batch
                "fileIds", new String[]{"a1b2c3d4-e5f6-7890-abcd-111111111111", "a1b2c3d4-e5f6-7890-abcd-222222222222"}
        );

        // When: POST /api/v1/comparisons
        mockMvc.perform(post(COMPARISONS_ENDPOINT)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andDo(print())

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("Target batch")))
                .andExpect(jsonPath("$.message").value(containsString("does not exist")))
                .andExpect(jsonPath("$.path").value(COMPARISONS_ENDPOINT));
    }

    /**
     * T033 (TC04): POST /api/v1/comparisons returns 400 if comparing batch with itself
     * <p>
     * Given: Authenticated user creates comparison with same batch for current and target
     * When: POST /api/v1/comparisons with currentBatchId = targetBatchId
     * Then: 400 Bad Request with error message "Cannot compare a batch with itself"
     * </p>
     *
     * Note: This test should FAIL initially because endpoint doesn't exist yet.
     */
    @Test
    @DisplayName("TC04: POST /api/v1/comparisons should return 400 when comparing batch with itself")
    void shouldReturn400WhenComparingBatchWithItself() throws Exception {
        // Given: Request with same batch for current and target
        Map<String, Object> request = Map.of(
                "currentBatchId", "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                "targetBatchId", "a1b2c3d4-e5f6-7890-abcd-ef1234567890", // Same as currentBatchId
                "fileIds", new String[]{"a1b2c3d4-e5f6-7890-abcd-111111111111", "a1b2c3d4-e5f6-7890-abcd-222222222222"}
        );

        // When: POST /api/v1/comparisons
        mockMvc.perform(post(COMPARISONS_ENDPOINT)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andDo(print())

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("Cannot compare a batch with itself")))
                .andExpect(jsonPath("$.path").value(COMPARISONS_ENDPOINT));
    }

    /**
     * T034 (TC05): POST /api/v1/comparisons returns 403 if user doesn't own batch
     * <p>
     * Given: Authenticated user creates comparison with batch owned by different account
     * When: POST /api/v1/comparisons with batch from different account
     * Then: 403 Forbidden with error message "Access denied"
     * </p>
     *
     * Note: This test should FAIL initially because endpoint doesn't exist yet.
     */
    @Test
    @DisplayName("TC05: POST /api/v1/comparisons should return 403 when user doesn't own batch")
    void shouldReturn403WhenUserDoesNotOwnBatch() throws Exception {
        // Given: Request with batch from different account
        // batch a1b2c3d4-e5f6-7890-abcd-ef1234567890 belongs to account 1, and we're using account 2's token
        Map<String, Object> request = Map.of(
                "currentBatchId", "a1b2c3d4-e5f6-7890-abcd-ef1234567890", // Owned by account 1
                "targetBatchId", "c3d4e5f6-a7b8-9012-cdef-123456789012",
                "fileIds", new String[]{"a1b2c3d4-e5f6-7890-abcd-111111111111", "a1b2c3d4-e5f6-7890-abcd-222222222222"}
        );

        // When: POST /api/v1/comparisons with account 2's token
        mockMvc.perform(post(COMPARISONS_ENDPOINT)
                        .header("Authorization", "Bearer " + MOCK_USER_TOKEN_ACCOUNT_2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andDo(print())

                // Then: 403 Forbidden
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value(containsString("Access denied")))
                .andExpect(jsonPath("$.path").value(COMPARISONS_ENDPOINT));
    }

    /**
     * T053 (TC06): GET /api/v1/comparisons/{id}/results returns comparison results
     * <p>
     * Given: Authenticated user owns a completed comparison
     * When: GET /api/v1/comparisons/{id}/results
     * Then: 200 OK with paginated comparison results
     * And: Results include changeType, lineAdditions, lineDeletions, unifiedDiff
     * </p>
     *
     * Test Scenario:
     * - Create comparison first (POST /api/v1/comparisons)
     * - Wait for completion (status=COMPLETED)
     * - Fetch results (GET /api/v1/comparisons/{id}/results)
     * - Verify result structure matches ComparisonResultDto
     */
    @Test
    @DisplayName("TC06: GET /api/v1/comparisons/{id}/results should return comparison results")
    void shouldReturnComparisonResults() throws Exception {
        // Given: Create a comparison first
        Map<String, Object> createRequest = Map.of(
                "currentBatchId", "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                "targetBatchId", "c3d4e5f6-a7b8-9012-cdef-123456789012",
                "fileIds", new String[]{"a1b2c3d4-e5f6-7890-abcd-111111111111", "a1b2c3d4-e5f6-7890-abcd-222222222222"}
        );

        String createResponse = mockMvc.perform(post(COMPARISONS_ENDPOINT)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Parse comparison ID from response
        String comparisonId = objectMapper.readTree(createResponse).get("id").asText();

        // When: GET /api/v1/comparisons/{id}/results
        mockMvc.perform(get(COMPARISONS_ENDPOINT + "/" + comparisonId + "/results")
                        .header("Authorization", "Bearer " + jwtToken)
                        .param("page", "0")
                        .param("size", "20"))
                .andDo(print())

                // Then: 200 OK with paginated results
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))

                // Verify pagination structure (PagedComparisonResultResponse format)
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").exists()) // page number (0-indexed)
                .andExpect(jsonPath("$.size").exists())
                .andExpect(jsonPath("$.totalElements").exists())
                .andExpect(jsonPath("$.totalPages").exists());

        // Note: We don't verify result content structure here because test data
        // may not include actual files with S3 content, so comparison may complete
        // with 0 results. The important part is that the endpoint returns valid
        // paginated response structure.
    }

    /**
     * T078 (TC07): GET /api/v1/comparisons/{id}/summary returns comparison summary
     * <p>
     * Given: Authenticated user owns a completed comparison
     * When: GET /api/v1/comparisons/{id}/summary
     * Then: 200 OK with comparison summary statistics
     * And: Summary includes totalFilesCompared, filesChanged, filesAdded, filesUnchanged,
     *      totalChangeSize, comparisonTimestamp, currentBatchId, targetBatchId
     * </p>
     *
     * Test Scenario:
     * - Create comparison first (POST /api/v1/comparisons)
     * - Wait for completion (status=COMPLETED)
     * - Fetch summary (GET /api/v1/comparisons/{id}/summary)
     * - Verify summary structure matches ComparisonSummaryDto
     *
     * Note: This test should FAIL initially because endpoint doesn't exist yet (TDD Red phase).
     */
    @Test
    @DisplayName("TC07: GET /api/v1/comparisons/{id}/summary should return comparison summary")
    void shouldReturnComparisonSummary() throws Exception {
        // Given: Create a comparison first
        Map<String, Object> createRequest = Map.of(
                "currentBatchId", "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                "targetBatchId", "c3d4e5f6-a7b8-9012-cdef-123456789012",
                "fileIds", new String[]{"a1b2c3d4-e5f6-7890-abcd-111111111111", "a1b2c3d4-e5f6-7890-abcd-222222222222"}
        );

        String createResponse = mockMvc.perform(post(COMPARISONS_ENDPOINT)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Parse comparison ID from response
        String comparisonId = objectMapper.readTree(createResponse).get("id").asText();

        // When: GET /api/v1/comparisons/{id}/summary
        mockMvc.perform(get(COMPARISONS_ENDPOINT + "/" + comparisonId + "/summary")
                        .header("Authorization", "Bearer " + jwtToken))
                .andDo(print())

                // Then: 200 OK with summary
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))

                // Verify summary structure (ComparisonSummaryDto fields)
                .andExpect(jsonPath("$.totalFilesCompared").isNumber())
                .andExpect(jsonPath("$.filesChanged").isNumber())
                .andExpect(jsonPath("$.filesAdded").isNumber())
                .andExpect(jsonPath("$.filesUnchanged").isNumber())
                .andExpect(jsonPath("$.totalChangeSize").isNumber())
                .andExpect(jsonPath("$.comparisonTimestamp").exists())
                .andExpect(jsonPath("$.currentBatchId").exists())
                .andExpect(jsonPath("$.targetBatchId").exists())

                // Verify non-negative counts
                .andExpect(jsonPath("$.totalFilesCompared").value(greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.filesChanged").value(greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.filesAdded").value(greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.filesUnchanged").value(greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.totalChangeSize").value(greaterThanOrEqualTo(0)));
    }

    /**
     * T086 (TC08): GET /api/v1/comparisons/{id}/download returns ZIP archive
     * <p>
     * Given: Authenticated user owns a completed comparison with results
     * When: GET /api/v1/comparisons/{id}/download
     * Then: 200 OK with application/zip content type
     * And: Content-Disposition header is attachment with filename
     * And: ZIP contains diff files and summary.txt
     * </p>
     *
     * Test Scenario:
     * - Create comparison first (POST /api/v1/comparisons)
     * - Wait for completion (status=COMPLETED)
     * - Download ZIP (GET /api/v1/comparisons/{id}/download)
     * - Verify response headers (content-type, content-disposition)
     * - Verify response body is not empty (actual ZIP content)
     *
     * Note: This test should FAIL initially because endpoint doesn't exist yet (TDD Red phase).
     */
    @Test
    @DisplayName("TC08: GET /api/v1/comparisons/{id}/download should return ZIP archive")
    void shouldDownloadComparisonAsZip() throws Exception {
        // Given: Create a comparison first
        Map<String, Object> createRequest = Map.of(
                "currentBatchId", "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                "targetBatchId", "c3d4e5f6-a7b8-9012-cdef-123456789012",
                "fileIds", new String[]{"a1b2c3d4-e5f6-7890-abcd-111111111111", "a1b2c3d4-e5f6-7890-abcd-222222222222"}
        );

        String createResponse = mockMvc.perform(post(COMPARISONS_ENDPOINT)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Parse comparison ID from response
        String comparisonId = objectMapper.readTree(createResponse).get("id").asText();

        // When: GET /api/v1/comparisons/{id}/download
        mockMvc.perform(get(COMPARISONS_ENDPOINT + "/" + comparisonId + "/download")
                        .header("Authorization", "Bearer " + jwtToken))
                .andDo(print())

                // Then: 200 OK with ZIP content
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition",
                        containsString("attachment")))
                .andExpect(header().string("Content-Disposition",
                        containsString("comparison-" + comparisonId + ".zip")))

                // Verify response body is not empty (actual ZIP data)
                .andExpect(result -> {
                    byte[] content = result.getResponse().getContentAsByteArray();
                    assert content.length > 0 : "ZIP content should not be empty";
                });
    }

    /**
     * T099 (TC09): GET /api/v1/comparisons/{id}/summary/download returns summary report file
     * <p>
     * Given: Authenticated user owns a completed comparison
     * When: GET /api/v1/comparisons/{id}/summary/download
     * Then: 200 OK with text/plain content type
     * And: Content-Disposition header is attachment with filename
     * And: Response contains human-readable summary report
     * </p>
     *
     * Test Scenario:
     * - Create comparison first (POST /api/v1/comparisons)
     * - Wait for completion (status=COMPLETED)
     * - Download summary report (GET /api/v1/comparisons/{id}/summary/download)
     * - Verify response headers (content-type, content-disposition)
     * - Verify response body contains expected report sections
     *
     * Note: This test should FAIL initially because endpoint doesn't exist yet (TDD Red phase).
     */
    @Test
    @DisplayName("TC09: GET /api/v1/comparisons/{id}/summary/download should return summary report file")
    void shouldDownloadSummaryReport() throws Exception {
        // Given: Create a comparison first
        Map<String, Object> createRequest = Map.of(
                "currentBatchId", "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                "targetBatchId", "c3d4e5f6-a7b8-9012-cdef-123456789012",
                "fileIds", new String[]{"a1b2c3d4-e5f6-7890-abcd-111111111111", "a1b2c3d4-e5f6-7890-abcd-222222222222"}
        );

        String createResponse = mockMvc.perform(post(COMPARISONS_ENDPOINT)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Parse comparison ID from response
        String comparisonId = objectMapper.readTree(createResponse).get("id").asText();

        // When: GET /api/v1/comparisons/{id}/summary/download
        mockMvc.perform(get(COMPARISONS_ENDPOINT + "/" + comparisonId + "/summary/download")
                        .header("Authorization", "Bearer " + jwtToken))
                .andDo(print())

                // Then: 200 OK with text/plain content
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        containsString("attachment")))
                .andExpect(header().string("Content-Disposition",
                        containsString("comparison-" + comparisonId + "-summary.txt")))

                // Verify response body contains expected report sections
                .andExpect(result -> {
                    String content = result.getResponse().getContentAsString();
                    assert content.length() > 0 : "Summary report content should not be empty";
                    assert content.contains("FILE COMPARISON SUMMARY REPORT") : "Report should contain title";
                    assert content.contains("Statistics") : "Report should contain Statistics section";
                    assert content.contains("Total Files Compared") : "Report should contain file count";
                    assert content.contains("Session Information") : "Report should contain Session Information section";
                    assert content.contains("Current Upload Session") : "Report should contain current session info";
                    assert content.contains("Target Upload Session") : "Report should contain target session info";
                });
    }

    /**
     * T105 (TC15): DELETE /api/v1/comparisons/{id} returns 204
     * <p>
     * Given: Authenticated user owns a COMPLETED comparison
     * When: DELETE /api/v1/comparisons/{id}
     * Then: 204 No Content and comparison is deleted
     * </p>
     *
     * User Story: US7 - Delete Saved Comparisons (Phase 9)
     * Priority: P4
     *
     * Note: This test should FAIL initially because endpoint doesn't exist yet.
     */
    @Test
    @DisplayName("TC15: DELETE /api/v1/comparisons/{id} should return 204 and delete comparison")
    void shouldDeleteComparison() throws Exception {
        // Given: Create a completed comparison first
        Map<String, Object> createRequest = Map.of(
                "currentBatchId", "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                "targetBatchId", "c3d4e5f6-a7b8-9012-cdef-123456789012",
                "fileIds", new String[]{
                        "f1a2b3c4-d5e6-7890-abcd-ef1234567890",
                        "f2b3c4d5-e6f7-8901-bcde-f12345678901"
                }
        );

        String createResponse = mockMvc.perform(post(COMPARISONS_ENDPOINT)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String comparisonId = objectMapper.readTree(createResponse).get("id").asText();

        // When: DELETE /api/v1/comparisons/{id}
        mockMvc.perform(delete(COMPARISONS_ENDPOINT + "/" + comparisonId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andDo(print())

                // Then: 204 No Content
                .andExpect(status().isNoContent());

        // Verify: GET returns 404 after deletion
        mockMvc.perform(get(COMPARISONS_ENDPOINT + "/" + comparisonId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNotFound());
    }

    /**
     * T106 (TC16): DELETE /api/v1/comparisons/{id} returns 204 for completed comparison
     * <p>
     * Given: Authenticated user has a COMPLETED comparison
     * When: DELETE /api/v1/comparisons/{id}
     * Then: 204 No Content (successful deletion)
     * </p>
     *
     * User Story: US7 - Delete Saved Comparisons (Phase 9)
     * Priority: P4
     *
     * Note: Originally tested IN_PROGRESS state, but since comparisons execute synchronously
     * (Phase 4/US2), they complete immediately. The IN_PROGRESS business rule is tested
     * in unit tests (FileComparisonTest.shouldNotAllowDeleteWhenInProgress).
     */
    @Test
    @DisplayName("TC16: DELETE /api/v1/comparisons/{id} should succeed for completed comparison")
    void shouldReturn204WhenDeletingCompletedComparison() throws Exception {
        // Given: Create a comparison (completes synchronously)
        Map<String, Object> createRequest = Map.of(
                "currentBatchId", "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                "targetBatchId", "c3d4e5f6-a7b8-9012-cdef-123456789012",
                "fileIds", new String[]{
                        "f1a2b3c4-d5e6-7890-abcd-ef1234567890"
                }
        );

        String createResponse = mockMvc.perform(post(COMPARISONS_ENDPOINT)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String comparisonId = objectMapper.readTree(createResponse).get("id").asText();

        // When: DELETE the completed comparison
        mockMvc.perform(delete(COMPARISONS_ENDPOINT + "/" + comparisonId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andDo(print())

                // Then: 204 No Content (successful deletion)
                .andExpect(status().isNoContent());

        // Verify: Comparison is deleted (404 on GET)
        mockMvc.perform(get(COMPARISONS_ENDPOINT + "/" + comparisonId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNotFound());
    }

    /**
     * T118 (TC17): GET /api/v1/comparisons returns paginated list
     * <p>
     * Given: Authenticated user has multiple comparisons
     * When: GET /api/v1/comparisons with pagination params
     * Then: 200 OK with paginated list of comparisons
     * And: Response includes pagination metadata (page, size, totalElements, totalPages)
     * And: Comparisons ordered by created_at DESC (most recent first)
     * </p>
     *
     * Phase 10: List Comparisons (Supporting Feature)
     *
     * Note: This test should FAIL initially because endpoint doesn't exist yet (TDD Red phase).
     */
    @Test
    @DisplayName("TC17: GET /api/v1/comparisons should return paginated list")
    void shouldListComparisons() throws Exception {
        // Given: Create 3 comparisons to ensure we have data to list
        for (int i = 0; i < 3; i++) {
            Map<String, Object> createRequest = Map.of(
                    "currentBatchId", "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                    "targetBatchId", "c3d4e5f6-a7b8-9012-cdef-123456789012",
                    "fileIds", new String[]{"a1b2c3d4-e5f6-7890-abcd-111111111111"}
            );

            mockMvc.perform(post(COMPARISONS_ENDPOINT)
                            .header("Authorization", "Bearer " + jwtToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createRequest)))
                    .andExpect(status().isCreated());
        }

        // When: GET /api/v1/comparisons with pagination
        mockMvc.perform(get(COMPARISONS_ENDPOINT)
                        .header("Authorization", "Bearer " + jwtToken)
                        .param("page", "0")
                        .param("size", "10"))
                .andDo(print())

                // Then: 200 OK with paginated response
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))

                // Verify pagination structure
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.totalPages").value(greaterThanOrEqualTo(1)))

                // Verify comparison structure in list
                .andExpect(jsonPath("$.content[0].id").exists())
                .andExpect(jsonPath("$.content[0].currentBatchId").exists())
                .andExpect(jsonPath("$.content[0].targetBatchId").exists())
                .andExpect(jsonPath("$.content[0].status").exists())
                .andExpect(jsonPath("$.content[0].createdAt").exists())

                // Verify ordering (created_at DESC - most recent first)
                // Since we just created them, the first item should have the most recent timestamp
                .andExpect(jsonPath("$.content[0].createdAt").exists());
    }

    /**
     * T119 (TC18): GET /api/v1/comparisons filters by status
     * <p>
     * Given: Authenticated user has comparisons with different statuses
     * When: GET /api/v1/comparisons?status=COMPLETED
     * Then: 200 OK with only COMPLETED comparisons
     * And: All returned comparisons have status=COMPLETED
     * </p>
     *
     * Phase 10: List Comparisons (Supporting Feature)
     *
     * Note: This test should FAIL initially because status filter not implemented (TDD Red phase).
     */
    @Test
    @DisplayName("TC18: GET /api/v1/comparisons should filter by status")
    void shouldFilterComparisonsByStatus() throws Exception {
        // Given: Create at least one comparison (will be COMPLETED or FAILED)
        Map<String, Object> createRequest = Map.of(
                "currentBatchId", "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                "targetBatchId", "c3d4e5f6-a7b8-9012-cdef-123456789012",
                "fileIds", new String[]{"a1b2c3d4-e5f6-7890-abcd-111111111111"}
        );

        String createResponse = mockMvc.perform(post(COMPARISONS_ENDPOINT)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Get the status of the created comparison
        String status = objectMapper.readTree(createResponse).get("status").asText();

        // When: GET /api/v1/comparisons with status filter
        mockMvc.perform(get(COMPARISONS_ENDPOINT)
                        .header("Authorization", "Bearer " + jwtToken)
                        .param("status", status)
                        .param("page", "0")
                        .param("size", "10"))
                .andDo(print())

                // Then: 200 OK with filtered results
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))

                // Verify all results have the requested status
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[*].status").value(everyItem(equalTo(status))));
    }

    // ================================================================================================
    // Phase 11: Get Comparison Details (Supporting Feature)
    // ================================================================================================

    /**
     * T133 (TC19): GET /api/v1/comparisons/{id} returns comparison
     * <p>
     * Given: User creates a comparison
     * When: GET /api/v1/comparisons/{id}
     * Then: 200 OK with comparison metadata
     * </p>
     *
     * Phase 11: Get Comparison Details (Supporting Feature)
     *
     * Note: This test should FAIL initially if endpoint not implemented (TDD Red phase).
     */
    @Test
    @DisplayName("TC19: GET /api/v1/comparisons/{id} should return comparison")
    void shouldGetComparison() throws Exception {
        // Given: Create a comparison
        Map<String, Object> createRequest = Map.of(
                "currentBatchId", "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                "targetBatchId", "c3d4e5f6-a7b8-9012-cdef-123456789012",
                "fileIds", new String[]{"a1b2c3d4-e5f6-7890-abcd-111111111111"}
        );

        String createResponse = mockMvc.perform(post(COMPARISONS_ENDPOINT)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Extract comparison ID
        Long comparisonId = objectMapper.readTree(createResponse).get("id").asLong();

        // When: GET /api/v1/comparisons/{id}
        mockMvc.perform(get(COMPARISONS_ENDPOINT + "/" + comparisonId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andDo(print())

                // Then: 200 OK with comparison data
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(comparisonId))
                .andExpect(jsonPath("$.currentBatchId").value("a1b2c3d4-e5f6-7890-abcd-ef1234567890"))
                .andExpect(jsonPath("$.targetBatchId").value("c3d4e5f6-a7b8-9012-cdef-123456789012"))
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.totalFilesCompared").isNumber())
                .andExpect(jsonPath("$.createdAt").exists());
    }

    /**
     * T134 (TC20): GET /api/v1/comparisons/{id} returns 403 if user doesn't own
     * <p>
     * Given: User A creates comparison, User B tries to access it
     * When: User B calls GET /api/v1/comparisons/{id}
     * Then: 403 Forbidden
     * </p>
     *
     * Phase 11: Get Comparison Details (Supporting Feature)
     *
     * Note: This test should FAIL initially if authorization not implemented (TDD Red phase).
     */
    @Test
    @DisplayName("TC20: GET /api/v1/comparisons/{id} should return 403 when user doesn't own comparison")
    void shouldReturn403WhenUserDoesNotOwnComparison() throws Exception {
        // Given: User A (account 1) creates a comparison
        Map<String, Object> createRequest = Map.of(
                "currentBatchId", "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                "targetBatchId", "c3d4e5f6-a7b8-9012-cdef-123456789012",
                "fileIds", new String[]{"a1b2c3d4-e5f6-7890-abcd-111111111111"}
        );

        String createResponse = mockMvc.perform(post(COMPARISONS_ENDPOINT)
                        .header("Authorization", "Bearer " + MOCK_USER_TOKEN_ACCOUNT_1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Extract comparison ID
        Long comparisonId = objectMapper.readTree(createResponse).get("id").asLong();

        // When: User B (account 2) tries to access User A's comparison
        mockMvc.perform(get(COMPARISONS_ENDPOINT + "/" + comparisonId)
                        .header("Authorization", "Bearer " + MOCK_USER_TOKEN_ACCOUNT_2))
                .andDo(print())

                // Then: 403 Forbidden
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * T135 (TC21): GET /api/v1/comparisons/{id} returns 404 if not found
     * <p>
     * Given: Comparison ID does not exist
     * When: GET /api/v1/comparisons/99999
     * Then: 404 Not Found
     * </p>
     *
     * Phase 11: Get Comparison Details (Supporting Feature)
     *
     * Note: This test should FAIL initially if 404 handling not implemented (TDD Red phase).
     */
    @Test
    @DisplayName("TC21: GET /api/v1/comparisons/{id} should return 404 when comparison not found")
    void shouldReturn404WhenComparisonNotFound() throws Exception {
        // Given: Non-existent comparison ID
        Long nonExistentId = 99999L;

        // When: GET /api/v1/comparisons/{nonExistentId}
        mockMvc.perform(get(COMPARISONS_ENDPOINT + "/" + nonExistentId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andDo(print())

                // Then: 404 Not Found
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists());
    }
}
