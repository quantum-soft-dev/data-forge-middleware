package com.bitbi.dfm.shared.exception;

import com.bitbi.dfm.shared.presentation.dto.ErrorResponseDto;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for GlobalExceptionHandler.
 */
@DisplayName("GlobalExceptionHandler Unit Tests")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/test");
    }

    @Test
    @DisplayName("Should handle IllegalArgumentException with 400 Bad Request")
    void shouldHandleIllegalArgumentException() {
        // Given
        IllegalArgumentException ex = new IllegalArgumentException("Invalid argument");

        // When
        ResponseEntity<ErrorResponseDto> response = handler.handleIllegalArgument(ex, request);

        // Then
        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(400, response.getBody().status());
        assertEquals("Bad Request", response.getBody().error());
        assertEquals("Invalid argument", response.getBody().message());
        assertEquals("/api/v1/test", response.getBody().path());
    }

    @Test
    @DisplayName("Should handle AccessDeniedException with 403 Forbidden")
    void shouldHandleAccessDeniedException() {
        // Given
        AccessDeniedException ex = new AccessDeniedException("Access denied");

        // When
        ResponseEntity<ErrorResponseDto> response = handler.handleAccessDenied(ex, request);

        // Then
        assertNotNull(response);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(403, response.getBody().status());
        assertEquals("Forbidden", response.getBody().error());
        assertEquals("Access denied", response.getBody().message()); // Uses actual exception message
        assertEquals("/api/v1/test", response.getBody().path());
    }

    @Test
    @DisplayName("Should handle NoHandlerFoundException with 404 Not Found")
    void shouldHandleNoHandlerFoundException() {
        // Given
        NoHandlerFoundException ex = new NoHandlerFoundException("GET", "/api/v1/test", null);

        // When
        ResponseEntity<ErrorResponseDto> response = handler.handleNotFound(ex, request);

        // Then
        assertNotNull(response);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(404, response.getBody().status());
        assertEquals("Not Found", response.getBody().error());
        assertTrue(response.getBody().message().contains("Endpoint not found"));
        assertEquals("/api/v1/test", response.getBody().path());
    }

    @Test
    @DisplayName("Should handle MaxUploadSizeExceededException with 413 Payload Too Large")
    void shouldHandleMaxUploadSizeExceededException() {
        // Given
        MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(10_000_000);

        // When
        ResponseEntity<ErrorResponseDto> response = handler.handleMaxUploadSizeExceeded(ex, request);

        // Then
        assertNotNull(response);
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(413, response.getBody().status());
        assertEquals("Payload Too Large", response.getBody().error());
        assertEquals("File upload size exceeds maximum allowed size", response.getBody().message());
        assertEquals("/api/v1/test", response.getBody().path());
    }

    @Test
    @DisplayName("Should handle IllegalStateException with 500 Internal Server Error")
    void shouldHandleIllegalStateException() {
        // Given
        IllegalStateException ex = new IllegalStateException("Invalid state");

        // When
        ResponseEntity<ErrorResponseDto> response = handler.handleIllegalState(ex, request);

        // Then
        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(500, response.getBody().status());
        assertEquals("Internal Server Error", response.getBody().error());
        assertEquals("An unexpected error occurred", response.getBody().message());
        assertEquals("/api/v1/test", response.getBody().path());
    }

    @Test
    @DisplayName("Should handle generic Exception with 500 Internal Server Error")
    void shouldHandleGenericException() {
        // Given
        Exception ex = new RuntimeException("Unexpected error");

        // When
        ResponseEntity<ErrorResponseDto> response = handler.handleGenericException(ex, request);

        // Then
        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(500, response.getBody().status());
        assertEquals("Internal Server Error", response.getBody().error());
        assertEquals("An unexpected error occurred", response.getBody().message());
        assertEquals("/api/v1/test", response.getBody().path());
    }

    @Test
    @DisplayName("Should include timestamp in error response")
    void shouldIncludeTimestampInErrorResponse() {
        // Given
        IllegalArgumentException ex = new IllegalArgumentException("Test");

        // When
        ResponseEntity<ErrorResponseDto> response = handler.handleIllegalArgument(ex, request);

        // Then
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().timestamp());
    }

    // ===================================================================
    // Edge Case Tests - Information Disclosure Prevention
    // ===================================================================

    @Test
    @DisplayName("Should sanitize IllegalStateException with sensitive data")
    void shouldSanitizeIllegalStateExceptionWithSensitiveData() {
        // Given: Exception with sensitive technical details
        IllegalStateException ex = new IllegalStateException(
                "Database connection failed: username=admin, password=secret123, host=internal-db.company.com:5432"
        );

        // When
        ResponseEntity<ErrorResponseDto> response = handler.handleIllegalState(ex, request);

        // Then: Sensitive data should NOT be in response
        assertNotNull(response.getBody());
        assertEquals("An unexpected error occurred", response.getBody().message());
        assertFalse(response.getBody().message().contains("password"));
        assertFalse(response.getBody().message().contains("secret123"));
        assertFalse(response.getBody().message().contains("admin"));
        assertFalse(response.getBody().message().contains("internal-db"));
    }

    @Test
    @DisplayName("Should sanitize generic Exception with stack trace info")
    void shouldSanitizeGenericExceptionWithStackTrace() {
        // Given: Exception with internal class/package names
        Exception ex = new RuntimeException(
                "NullPointerException at com.bitbi.internal.secret.SecretProcessor.processSecret(line:42)"
        );

        // When
        ResponseEntity<ErrorResponseDto> response = handler.handleGenericException(ex, request);

        // Then: Internal class names should NOT be in response
        assertNotNull(response.getBody());
        assertEquals("An unexpected error occurred", response.getBody().message());
        assertFalse(response.getBody().message().contains("SecretProcessor"));
        assertFalse(response.getBody().message().contains("com.bitbi.internal"));
    }

    @Test
    @DisplayName("Should return all validation errors, not just first one")
    void shouldReturnAllValidationErrors() {
        // Given: MethodArgumentNotValidException with multiple field errors
        org.springframework.validation.BindingResult bindingResult =
                mock(org.springframework.validation.BindingResult.class);

        org.springframework.validation.FieldError error1 =
                new org.springframework.validation.FieldError("dto", "email", "must be a valid email");
        org.springframework.validation.FieldError error2 =
                new org.springframework.validation.FieldError("dto", "name", "must not be blank");
        org.springframework.validation.FieldError error3 =
                new org.springframework.validation.FieldError("dto", "age", "must be positive");

        when(bindingResult.getFieldErrors()).thenReturn(java.util.List.of(error1, error2, error3));

        org.springframework.web.bind.MethodArgumentNotValidException ex =
                new org.springframework.web.bind.MethodArgumentNotValidException(
                        mock(org.springframework.core.MethodParameter.class),
                        bindingResult
                );

        // When
        ResponseEntity<ErrorResponseDto> response = handler.handleValidationErrors(ex, request);

        // Then: All errors should be present
        assertNotNull(response.getBody());
        String message = response.getBody().message();
        assertTrue(message.contains("email: must be a valid email"));
        assertTrue(message.contains("name: must not be blank"));
        assertTrue(message.contains("age: must be positive"));
    }

    @Test
    @DisplayName("Should handle empty validation errors gracefully")
    void shouldHandleEmptyValidationErrorsGracefully() {
        // Given: MethodArgumentNotValidException with no field errors
        org.springframework.validation.BindingResult bindingResult =
                mock(org.springframework.validation.BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(java.util.List.of());

        org.springframework.web.bind.MethodArgumentNotValidException ex =
                new org.springframework.web.bind.MethodArgumentNotValidException(
                        mock(org.springframework.core.MethodParameter.class),
                        bindingResult
                );

        // When
        ResponseEntity<ErrorResponseDto> response = handler.handleValidationErrors(ex, request);

        // Then
        assertNotNull(response.getBody());
        assertEquals("Validation failed", response.getBody().message());
    }
}
