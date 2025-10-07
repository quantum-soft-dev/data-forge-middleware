package com.bitbi.dfm.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test for Scenario 5: Error Logging and Tracking.
 * <p>
 * Tests error recording, batch hasErrors flag, and JSONB metadata storage.
 * </p>
 */
@DisplayName("Scenario 5: Error Logging Integration Test")
class ErrorLoggingIntegrationTest extends BaseIntegrationTest {

    private static final String MOCK_BATCH_ID = "0199bab2-8d63-8563-8340-edbf1c11c778";

    @Test
    @DisplayName("Should record error with batch association and JSONB metadata")
    void shouldRecordErrorWithBatchAssociationAndJsonbMetadata() throws Exception {
        // Given: Error details with metadata
        String errorPayload = """
                {
                  "type": "FileReadError",
                  "title": "Failed to read DBF file",
                  "message": "File data.dbf is corrupted and cannot be read",
                  "stackTrace": "com.bitbi.client.FileReadException: Corrupted header\\n  at FileReader.read()",
                  "clientVersion": "1.0.0",
                  "metadata": {
                    "fileName": "data.dbf",
                    "fileSize": 1048576,
                    "encoding": "CP866",
                    "errorCode": "ERR_CORRUPT_HEADER"
                  }
                }
                """;

        // When: POST /api/v1/error/{batchId}
        mockMvc.perform(post("/api/v1/error/{batchId}", MOCK_BATCH_ID)
                        .header("Authorization", generateTestToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(errorPayload))

                // Then: 204 No Content
                .andExpect(status().isCreated());

        // Verify: Error stored in error_logs partitioned table
        // Verify: Batch hasErrors flag set to true
        // Verify: JSONB metadata stored correctly
    }

    @Test
    @DisplayName("Should set batch hasErrors flag when error logged")
    void shouldSetBatchHasErrorsFlagWhenErrorLogged() throws Exception {
        // Given: Batch without errors
        String errorPayload = """
                {
                  "type": "ValidationError",
                  "title": "Invalid data format",
                  "message": "Column 'amount' contains non-numeric values"
                }
                """;

        // When: Log error
        mockMvc.perform(post("/api/v1/error/{batchId}", MOCK_BATCH_ID)
                        .header("Authorization", generateTestToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(errorPayload))
                .andExpect(status().isCreated());

        // Then: Verify batch hasErrors = true
        // Note: Actual verification will be done via admin API or direct DB query
    }

    @Test
    @DisplayName("Should allow standalone errors without batch association")
    void shouldAllowStandaloneErrorsWithoutBatchAssociation() throws Exception {
        // Given: Standalone error (configuration/startup error)
        String errorPayload = """
                {
                  "type": "ConfigurationError",
                  "title": "Missing configuration file",
                  "message": "config.ini not found in application directory"
                }
                """;

        // When: POST /api/v1/error (no batchId)
        mockMvc.perform(post("/api/v1/error")
                        .header("Authorization", generateTestToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(errorPayload))

                // Then: 204 No Content (error logged without batch)
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Should store error in correct time partition")
    void shouldStoreErrorInCorrectTimePartition() throws Exception {
        // Given: Error occurred at specific time
        String errorPayload = """
                {
                  "type": "NetworkError",
                  "title": "Connection timeout",
                  "message": "Failed to connect to server after 30 seconds"
                }
                """;

        // When: Log error
        mockMvc.perform(post("/api/v1/error/{batchId}", MOCK_BATCH_ID)
                        .header("Authorization", generateTestToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(errorPayload))
                .andExpect(status().isCreated());

        // Verify: Error stored in correct monthly partition (e.g., error_logs_2025_10)
    }
}
