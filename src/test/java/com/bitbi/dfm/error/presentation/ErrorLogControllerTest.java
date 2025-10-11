package com.bitbi.dfm.error.presentation;

import com.bitbi.dfm.auth.application.TokenService;
import com.bitbi.dfm.error.application.ErrorLoggingService;
import com.bitbi.dfm.error.domain.ErrorLog;
import com.bitbi.dfm.error.presentation.dto.ErrorLogResponseDto;
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

    @Test
    @DisplayName("Should log standalone error successfully")
    void shouldLogStandaloneErrorSuccessfully() {
        // Given
        Map<String, Object> request = new HashMap<>();
        request.put("type", "ValidationError");
        request.put("message", "Test error message");
        request.put("metadata", new HashMap<>());

        // When
        ResponseEntity<?> response = controller.logStandaloneError(request, testAuthHeader);

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
    @DisplayName("Should return 400 when type is missing in standalone error")
    void shouldReturn400WhenTypeIsMissingInStandaloneError() {
        // Given
        Map<String, Object> request = new HashMap<>();
        request.put("message", "Test error message");

        when(tokenService.validateToken(testToken)).thenReturn(testSiteId);

        // When
        ResponseEntity<?> response = controller.logStandaloneError(request, testAuthHeader);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(errorLoggingService, never()).logStandaloneError(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should return 400 when type is blank in standalone error")
    void shouldReturn400WhenTypeIsBlankInStandaloneError() {
        // Given
        Map<String, Object> request = new HashMap<>();
        request.put("type", "");
        request.put("message", "Test error message");

        when(tokenService.validateToken(testToken)).thenReturn(testSiteId);

        // When
        ResponseEntity<?> response = controller.logStandaloneError(request, testAuthHeader);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(errorLoggingService, never()).logStandaloneError(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should return 400 when message is missing in standalone error")
    void shouldReturn400WhenMessageIsMissingInStandaloneError() {
        // Given
        Map<String, Object> request = new HashMap<>();
        request.put("type", "ValidationError");

        when(tokenService.validateToken(testToken)).thenReturn(testSiteId);

        // When
        ResponseEntity<?> response = controller.logStandaloneError(request, testAuthHeader);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(errorLoggingService, never()).logStandaloneError(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should return 400 when message is blank in standalone error")
    void shouldReturn400WhenMessageIsBlankInStandaloneError() {
        // Given
        Map<String, Object> request = new HashMap<>();
        request.put("type", "ValidationError");
        request.put("message", "");

        when(tokenService.validateToken(testToken)).thenReturn(testSiteId);

        // When
        ResponseEntity<?> response = controller.logStandaloneError(request, testAuthHeader);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(errorLoggingService, never()).logStandaloneError(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should return 500 when standalone error logging fails")
    void shouldReturn500WhenStandaloneErrorLoggingFails() {
        // Given
        Map<String, Object> request = new HashMap<>();
        request.put("type", "ValidationError");
        request.put("message", "Test error message");

        when(tokenService.validateToken(testToken)).thenReturn(testSiteId);
        doThrow(new RuntimeException("Database error"))
                .when(errorLoggingService).logStandaloneError(any(), any(), any(), any());

        // When
        ResponseEntity<?> response = controller.logStandaloneError(request, testAuthHeader);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    @DisplayName("Should log batch error successfully")
    void shouldLogBatchErrorSuccessfully() {
        // Given
        Map<String, Object> request = new HashMap<>();
        request.put("type", "ValidationError");
        request.put("message", "Test error message");

        ErrorLog errorLog = ErrorLog.create(testSiteId, testBatchId, "Error", "ValidationError", "Test error message", null, null, null);

        when(errorLoggingService.logError(eq(testBatchId), eq(testSiteId), any(), any(), any()))
                .thenReturn(errorLog);

        // When
        ResponseEntity<?> response = controller.logError(testBatchId, request, testAuthHeader);

        // Then
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        ErrorLogResponseDto body = (ErrorLogResponseDto) response.getBody();
        assertEquals(errorLog.getId(), body.id());
        assertEquals(errorLog.getBatchId(), body.batchId());
        assertEquals(errorLog.getSiteId(), body.siteId());

        verify(tokenService).validateToken(testToken);
        verify(errorLoggingService).logError(eq(testBatchId), eq(testSiteId), eq("ValidationError"), eq("Test error message"), any());
    }

    @Test
    @DisplayName("Should return 400 when type is missing in batch error")
    void shouldReturn400WhenTypeIsMissingInBatchError() {
        // Given
        Map<String, Object> request = new HashMap<>();
        request.put("message", "Test error message");

        when(tokenService.validateToken(testToken)).thenReturn(testSiteId);

        // When
        ResponseEntity<?> response = controller.logError(testBatchId, request, testAuthHeader);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertTrue(body.containsKey("error"));

        verify(errorLoggingService, never()).logError(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should return 400 when type is blank in batch error")
    void shouldReturn400WhenTypeIsBlankInBatchError() {
        // Given
        Map<String, Object> request = new HashMap<>();
        request.put("type", "");
        request.put("message", "Test error message");

        when(tokenService.validateToken(testToken)).thenReturn(testSiteId);

        // When
        ResponseEntity<?> response = controller.logError(testBatchId, request, testAuthHeader);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("Error type is required", body.get("message"));
    }

    @Test
    @DisplayName("Should return 400 when message is missing in batch error")
    void shouldReturn400WhenMessageIsMissingInBatchError() {
        // Given
        Map<String, Object> request = new HashMap<>();
        request.put("type", "ValidationError");

        when(tokenService.validateToken(testToken)).thenReturn(testSiteId);

        // When
        ResponseEntity<?> response = controller.logError(testBatchId, request, testAuthHeader);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertTrue(body.containsKey("error"));
    }

    @Test
    @DisplayName("Should return 400 when message is blank in batch error")
    void shouldReturn400WhenMessageIsBlankInBatchError() {
        // Given
        Map<String, Object> request = new HashMap<>();
        request.put("type", "ValidationError");
        request.put("message", "");

        when(tokenService.validateToken(testToken)).thenReturn(testSiteId);

        // When
        ResponseEntity<?> response = controller.logError(testBatchId, request, testAuthHeader);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("Error message is required", body.get("message"));
    }

    @Test
    @DisplayName("Should return 500 when batch error logging fails")
    void shouldReturn500WhenBatchErrorLoggingFails() {
        // Given
        Map<String, Object> request = new HashMap<>();
        request.put("type", "ValidationError");
        request.put("message", "Test error message");

        when(tokenService.validateToken(testToken)).thenReturn(testSiteId);
        when(errorLoggingService.logError(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Database error"));

        // When
        ResponseEntity<?> response = controller.logError(testBatchId, request, testAuthHeader);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("Failed to log error", body.get("message"));
    }

    @Test
    @DisplayName("Should get error log successfully")
    void shouldGetErrorLogSuccessfully() {
        // Given
        UUID errorId = UUID.randomUUID();
        ErrorLog errorLog = ErrorLog.create(testSiteId, testBatchId, "Error", "ValidationError", "Test error message", null, null, null);

        when(errorLoggingService.getErrorLog(errorId)).thenReturn(errorLog);

        // When
        ResponseEntity<?> response = controller.getErrorLog(errorId, testAuthHeader);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        ErrorLogResponseDto body = (ErrorLogResponseDto) response.getBody();
        assertEquals(errorLog.getId(), body.id());
        assertEquals(errorLog.getBatchId(), body.batchId());
        assertEquals(errorLog.getSiteId(), body.siteId());

        verify(tokenService).validateToken(testToken);
        verify(errorLoggingService).getErrorLog(errorId);
    }

    @Test
    @DisplayName("Should return 404 when error log not found")
    void shouldReturn404WhenErrorLogNotFound() {
        // Given
        UUID errorId = UUID.randomUUID();

        when(tokenService.validateToken(testToken)).thenReturn(testSiteId);
        when(errorLoggingService.getErrorLog(errorId))
                .thenThrow(new ErrorLoggingService.ErrorLogNotFoundException("Error log not found: " + errorId));

        // When
        ResponseEntity<?> response = controller.getErrorLog(errorId, testAuthHeader);

        // Then
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("Error log not found", body.get("message"));
    }

    @Test
    @DisplayName("Should return 500 when getting error log fails")
    void shouldReturn500WhenGettingErrorLogFails() {
        // Given
        UUID errorId = UUID.randomUUID();

        when(tokenService.validateToken(testToken)).thenReturn(testSiteId);
        when(errorLoggingService.getErrorLog(errorId))
                .thenThrow(new RuntimeException("Database error"));

        // When
        ResponseEntity<?> response = controller.getErrorLog(errorId, testAuthHeader);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("Failed to retrieve error log", body.get("message"));
    }

    @Test
    @DisplayName("Should handle missing Authorization header")
    void shouldHandleMissingAuthorizationHeader() {
        // Given
        Map<String, Object> request = new HashMap<>();
        request.put("type", "ValidationError");
        request.put("message", "Test error message");

        // When
        ResponseEntity<?> response = controller.logStandaloneError(request, null);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    @DisplayName("Should handle invalid Authorization header format")
    void shouldHandleInvalidAuthorizationHeaderFormat() {
        // Given
        Map<String, Object> request = new HashMap<>();
        request.put("type", "ValidationError");
        request.put("message", "Test error message");

        // When
        ResponseEntity<?> response = controller.logStandaloneError(request, "InvalidHeader");

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    @DisplayName("Should include metadata in batch error")
    void shouldIncludeMetadataInBatchError() {
        // Given
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sourceFile", "test.csv");
        metadata.put("lineNumber", 42);

        Map<String, Object> request = new HashMap<>();
        request.put("type", "ValidationError");
        request.put("message", "Test error message");
        request.put("metadata", metadata);

        ErrorLog errorLog = ErrorLog.create(testSiteId, testBatchId, "Error", "ValidationError", "Test error message", null, null, metadata);

        when(errorLoggingService.logError(eq(testBatchId), eq(testSiteId), any(), any(), any()))
                .thenReturn(errorLog);

        // When
        ResponseEntity<?> response = controller.logError(testBatchId, request, testAuthHeader);

        // Then
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        ErrorLogResponseDto body = (ErrorLogResponseDto) response.getBody();
        assertNotNull(body.metadata());

        verify(errorLoggingService).logError(eq(testBatchId), eq(testSiteId), eq("ValidationError"), eq("Test error message"), eq(metadata));
    }

    @Test
    @DisplayName("Should handle null metadata in batch error")
    void shouldHandleNullMetadataInBatchError() {
        // Given
        Map<String, Object> request = new HashMap<>();
        request.put("type", "ValidationError");
        request.put("message", "Test error message");
        request.put("metadata", null);

        ErrorLog errorLog = ErrorLog.create(testSiteId, testBatchId, "Error", "ValidationError", "Test error message", null, null, null);

        when(errorLoggingService.logError(eq(testBatchId), eq(testSiteId), any(), any(), any()))
                .thenReturn(errorLog);

        // When
        ResponseEntity<?> response = controller.logError(testBatchId, request, testAuthHeader);

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
        ResponseEntity<?> response = controller.getErrorLog(errorId, testAuthHeader);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        ErrorLogResponseDto body = (ErrorLogResponseDto) response.getBody();
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
