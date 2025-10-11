package com.bitbi.dfm.error.presentation;

import com.bitbi.dfm.auth.application.TokenService;
import com.bitbi.dfm.error.application.ErrorLoggingService;
import com.bitbi.dfm.error.domain.ErrorLog;
import com.bitbi.dfm.error.presentation.dto.ErrorLogResponseDto;
import com.bitbi.dfm.error.presentation.dto.LogErrorRequestDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ErrorLogController.
 */
@DisplayName("ErrorLogController Unit Tests")
class ErrorLogControllerTest {

    private ErrorLogController controller;
    private ErrorLoggingService errorLoggingService;
    private TokenService tokenService;
    private com.bitbi.dfm.batch.application.BatchLifecycleService batchLifecycleService;

    private UUID testSiteId;
    private UUID testBatchId;
    private String testToken;
    private String testAuthHeader;

    @BeforeEach
    void setUp() {
        errorLoggingService = mock(ErrorLoggingService.class);
        tokenService = mock(TokenService.class);
        batchLifecycleService = mock(com.bitbi.dfm.batch.application.BatchLifecycleService.class);
        controller = new ErrorLogController(errorLoggingService, tokenService, batchLifecycleService);

        testSiteId = UUID.randomUUID();
        testBatchId = UUID.randomUUID();
        testToken = "test-jwt-token";
        testAuthHeader = "Bearer " + testToken;

        when(tokenService.validateToken(testToken)).thenReturn(testSiteId);

        // Mock batch ownership check - by default, return a batch owned by testSiteId
        com.bitbi.dfm.batch.domain.Batch mockBatch = mock(com.bitbi.dfm.batch.domain.Batch.class);
        when(mockBatch.getSiteId()).thenReturn(testSiteId);
        when(batchLifecycleService.getBatch(testBatchId)).thenReturn(mockBatch);
    }

    // NOTE: Validation tests removed - now handled by @Valid annotation and GlobalExceptionHandler
    // For validation testing, see integration/contract tests instead

    @Test
    @DisplayName("Should log standalone error successfully")
    void shouldLogStandaloneErrorSuccessfully() {
        // Given
        LogErrorRequestDto request = new LogErrorRequestDto("ValidationError", "Test error message", Map.of());

        // When
        ResponseEntity<Void> response = controller.logStandaloneError(request, testAuthHeader);

        // Then
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());

        verify(tokenService).validateToken(testToken);
        verify(errorLoggingService).logStandaloneError(
                eq(testSiteId),
                eq("ValidationError"),
                eq("Test error message"),
                any()
        );
    }

    @Test
    @DisplayName("Should log batch error successfully")
    void shouldLogBatchErrorSuccessfully() {
        // Given
        LogErrorRequestDto request = new LogErrorRequestDto("ValidationError", "Test error message", null);

        ErrorLog errorLog = ErrorLog.create(testSiteId, testBatchId, "Error", "ValidationError", "Test error message", null, null, null);

        when(errorLoggingService.logError(eq(testBatchId), eq(testSiteId), any(), any(), any()))
                .thenReturn(errorLog);

        // When
        ResponseEntity<ErrorLogResponseDto> response = controller.logError(testBatchId, request, testAuthHeader);

        // Then
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        ErrorLogResponseDto body = response.getBody();
        assertEquals(errorLog.getId(), body.id());
        assertEquals(errorLog.getBatchId(), body.batchId());
        assertEquals(errorLog.getSiteId(), body.siteId());

        verify(tokenService).validateToken(testToken);
        verify(errorLoggingService).logError(eq(testBatchId), eq(testSiteId), eq("ValidationError"), eq("Test error message"), any());
    }

    @Test
    @DisplayName("Should get error log successfully")
    void shouldGetErrorLogSuccessfully() {
        // Given
        UUID errorId = UUID.randomUUID();
        ErrorLog errorLog = ErrorLog.create(testSiteId, testBatchId, "Error", "ValidationError", "Test error message", null, null, null);

        when(errorLoggingService.getErrorLog(errorId)).thenReturn(errorLog);

        // When
        ResponseEntity<ErrorLogResponseDto> response = controller.getErrorLog(errorId, testAuthHeader);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        ErrorLogResponseDto body = response.getBody();
        assertEquals(errorLog.getId(), body.id());
        assertEquals(errorLog.getBatchId(), body.batchId());
        assertEquals(errorLog.getSiteId(), body.siteId());

        verify(tokenService).validateToken(testToken);
        verify(errorLoggingService).getErrorLog(errorId);
    }

    @Test
    @DisplayName("Should include metadata in batch error")
    void shouldIncludeMetadataInBatchError() {
        // Given
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sourceFile", "test.csv");
        metadata.put("lineNumber", 42);

        LogErrorRequestDto request = new LogErrorRequestDto("ValidationError", "Test error message", metadata);

        ErrorLog errorLog = ErrorLog.create(testSiteId, testBatchId, "Error", "ValidationError", "Test error message", null, null, metadata);

        when(errorLoggingService.logError(eq(testBatchId), eq(testSiteId), any(), any(), any()))
                .thenReturn(errorLog);

        // When
        ResponseEntity<ErrorLogResponseDto> response = controller.logError(testBatchId, request, testAuthHeader);

        // Then
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        ErrorLogResponseDto body = response.getBody();
        assertNotNull(body.metadata());

        verify(errorLoggingService).logError(eq(testBatchId), eq(testSiteId), eq("ValidationError"), eq("Test error message"), eq(metadata));
    }

    @Test
    @DisplayName("Should handle null metadata in batch error")
    void shouldHandleNullMetadataInBatchError() {
        // Given
        LogErrorRequestDto request = new LogErrorRequestDto("ValidationError", "Test error message", null);

        ErrorLog errorLog = ErrorLog.create(testSiteId, testBatchId, "Error", "ValidationError", "Test error message", null, null, null);

        when(errorLoggingService.logError(eq(testBatchId), eq(testSiteId), any(), any(), any()))
                .thenReturn(errorLog);

        // When
        ResponseEntity<ErrorLogResponseDto> response = controller.logError(testBatchId, request, testAuthHeader);

        // Then
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());

        verify(errorLoggingService).logError(eq(testBatchId), eq(testSiteId), eq("ValidationError"), eq("Test error message"), eq(null));
    }

    @Test
    @DisplayName("Should include all fields in error log response")
    void shouldIncludeAllFieldsInErrorLogResponse() {
        // Given
        UUID errorId = UUID.randomUUID();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key", "value");

        ErrorLog errorLog = ErrorLog.create(testSiteId, testBatchId, "Error Title", "ValidationError", "Test error message", "stack trace", "v1.0", metadata);

        when(errorLoggingService.getErrorLog(errorId)).thenReturn(errorLog);

        // When
        ResponseEntity<ErrorLogResponseDto> response = controller.getErrorLog(errorId, testAuthHeader);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        ErrorLogResponseDto body = response.getBody();
        assertNotNull(body.id());
        assertNotNull(body.batchId());
        assertNotNull(body.siteId());
        assertNotNull(body.type());
        assertNotNull(body.title());
        assertNotNull(body.message());
        assertNotNull(body.stackTrace());
        assertNotNull(body.clientVersion());
        assertNotNull(body.metadata());
        assertNotNull(body.occurredAt());
    }
}
