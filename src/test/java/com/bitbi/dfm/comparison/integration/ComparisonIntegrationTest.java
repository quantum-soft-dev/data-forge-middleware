package com.bitbi.dfm.comparison.integration;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for File Comparison feature using Testcontainers.
 * <p>
 * CRITICAL: These tests MUST FAIL before implementation (TDD Red phase).
 * Purpose: Validate end-to-end comparison workflow with real database and S3.
 * </p>
 *
 * Feature: File Diff Comparison Between Upload Sessions
 * Phase: Phase 3 - User Story 1 (Select Files for Comparison)
 * Priority: P1 (MVP)
 *
 * Test Environment:
 * - PostgreSQL: Testcontainers
 * - S3: LocalStack (from application-test.yml)
 * - Authentication: Real JWT token generation
 */
@DisplayName("File Comparison Integration Tests (User Story 1)")
class ComparisonIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    private static final String COMPARISONS_ENDPOINT = "/api/v1/comparisons";

    /**
     * T035 (ITC01): Create comparison with selected files
     * <p>
     * Given: Two completed batches with files in database
     * And: Authenticated user owns both batches
     * When: POST /api/v1/comparisons with valid fileIds
     * Then: 201 Created with ComparisonResponse
     * And: Comparison record created in database with status=PENDING
     * </p>
     *
     * Note: This test should FAIL initially because:
     * 1. ComparisonService doesn't exist yet
     * 2. ComparisonController doesn't exist yet
     * 3. Database tables don't exist yet (migration pending)
     */
    @Test
    @DisplayName("ITC01: Should create comparison with selected files")
    void shouldCreateComparisonWithSelectedFiles() throws Exception {
        // Given: Authenticated user token
        String token = "Bearer mock-jwt-token-account-1";

        // And: Request to compare specific files between two batches
        // Using UUIDs from test-data.sql
        Map<String, Object> request = Map.of(
                "currentBatchId", "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                "targetBatchId", "c3d4e5f6-a7b8-9012-cdef-123456789012",
                "fileIds", new String[]{"a1b2c3d4-e5f6-7890-abcd-111111111111", "a1b2c3d4-e5f6-7890-abcd-222222222222"}
        );

        // When: POST /api/v1/comparisons
        mockMvc.perform(post(COMPARISONS_ENDPOINT)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andDo(print())

                // Then: 201 Created
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))

                // Verify ComparisonResponse structure
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.currentBatchId").value("a1b2c3d4-e5f6-7890-abcd-ef1234567890"))
                .andExpect(jsonPath("$.targetBatchId").value("c3d4e5f6-a7b8-9012-cdef-123456789012"))
                .andExpect(jsonPath("$.accountId").exists())
                .andExpect(jsonPath("$.status").value("PENDING"))  // Should be PENDING initially
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.startedAt").doesNotExist())  // Not started yet
                .andExpect(jsonPath("$.completedAt").doesNotExist())  // Not completed yet
                .andExpect(jsonPath("$.totalFilesCompared").value(0))  // No files compared yet
                .andExpect(jsonPath("$.filesChanged").value(0))
                .andExpect(jsonPath("$.filesAdded").value(0))
                .andExpect(jsonPath("$.filesUnchanged").value(0))
                .andExpect(jsonPath("$.totalChangeSize").value(0))
                .andExpect(jsonPath("$.errorMessage").doesNotExist());

        // Note: Actual file comparison happens asynchronously in US2
        // For US1, we just verify that the comparison record is created
    }
}
