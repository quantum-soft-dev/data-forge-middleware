package com.bitbi.dfm.comparison.contract;

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
@Import({TestSecurityConfig.class, TestS3Config.class, com.bitbi.dfm.config.TestKeycloakConfig.class})
@Sql("/test-data.sql")
@DisplayName("File Comparison API Contract Tests (User Story 1)")
class ComparisonContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String COMPARISONS_ENDPOINT = "/api/v1/comparisons";

    // Use mock Keycloak token for /api/v1/** endpoints (OAuth2 authentication)
    private static final String MOCK_USER_TOKEN_ACCOUNT_1 = "mock-jwt-token-account-1";
    private static final String MOCK_USER_TOKEN_ACCOUNT_2 = "mock-jwt-token-account-2";

    private String jwtToken;

    @BeforeEach
    void setUp() {
        // Use mock Keycloak token for authenticated user
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
}
