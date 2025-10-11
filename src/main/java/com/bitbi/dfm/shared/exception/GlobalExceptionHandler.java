package com.bitbi.dfm.shared.exception;

import com.bitbi.dfm.account.application.AccountService;
import com.bitbi.dfm.shared.presentation.dto.ErrorResponseDto;
import com.bitbi.dfm.site.application.SiteService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.time.Instant;

/**
 * Global exception handler for standardized error responses.
 * <p>
 * Intercepts exceptions across all REST controllers and returns
 * consistent ErrorResponse DTOs with appropriate HTTP status codes.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handle IllegalArgumentException (400 Bad Request).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponseDto> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request) {

        logger.warn("Bad request: {}", ex.getMessage());

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Handle MethodArgumentNotValidException (400 Bad Request).
     * <p>
     * This handler is triggered when @Valid validation fails on request body DTOs.
     * It extracts field validation errors and returns them in a user-friendly format.
     * </p>
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDto> handleValidationErrors(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        // Extract first validation error message
        String errorMessage = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");

        logger.warn("Validation failed: {}", errorMessage);

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                errorMessage,
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Handle AccessDeniedException (403 Forbidden).
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponseDto> handleAccessDenied(
            AccessDeniedException ex,
            HttpServletRequest request) {

        logger.warn("Access denied: {}", ex.getMessage());

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.FORBIDDEN.value(),
                "Forbidden",
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    /**
     * Handle NoHandlerFoundException (404 Not Found).
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponseDto> handleNotFound(
            NoHandlerFoundException ex,
            HttpServletRequest request) {

        logger.warn("Endpoint not found: {}", request.getRequestURI());

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                "Endpoint not found: " + request.getRequestURI(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * Handle MaxUploadSizeExceededException (413 Payload Too Large).
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponseDto> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException ex,
            HttpServletRequest request) {

        logger.warn("Upload size exceeded: {}", ex.getMessage());

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.PAYLOAD_TOO_LARGE.value(),
                "Payload Too Large",
                "File upload size exceeds maximum allowed size",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(error);
    }

    /**
     * Handle AccountNotFoundException (404 Not Found).
     */
    @ExceptionHandler(AccountService.AccountNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handleAccountNotFound(
            AccountService.AccountNotFoundException ex,
            HttpServletRequest request) {

        logger.warn("Account not found: {}", ex.getMessage());

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * Handle AccountAlreadyExistsException (409 Conflict).
     */
    @ExceptionHandler(AccountService.AccountAlreadyExistsException.class)
    public ResponseEntity<ErrorResponseDto> handleAccountAlreadyExists(
            AccountService.AccountAlreadyExistsException ex,
            HttpServletRequest request) {

        logger.warn("Account already exists: {}", ex.getMessage());

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.CONFLICT.value(),
                "Conflict",
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    /**
     * Handle SiteNotFoundException (404 Not Found).
     */
    @ExceptionHandler(SiteService.SiteNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handleSiteNotFound(
            SiteService.SiteNotFoundException ex,
            HttpServletRequest request) {

        logger.warn("Site not found: {}", ex.getMessage());

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * Handle SiteAlreadyExistsException (409 Conflict).
     */
    @ExceptionHandler(SiteService.SiteAlreadyExistsException.class)
    public ResponseEntity<ErrorResponseDto> handleSiteAlreadyExists(
            SiteService.SiteAlreadyExistsException ex,
            HttpServletRequest request) {

        logger.warn("Site already exists: {}", ex.getMessage());

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.CONFLICT.value(),
                "Conflict",
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    /**
     * Handle BatchNotFoundException (404 Not Found).
     */
    @ExceptionHandler(com.bitbi.dfm.batch.application.BatchLifecycleService.BatchNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handleBatchNotFound(
            com.bitbi.dfm.batch.application.BatchLifecycleService.BatchNotFoundException ex,
            HttpServletRequest request) {

        logger.warn("Batch not found: {}", ex.getMessage());

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * Handle ErrorLogNotFoundException (404 Not Found).
     */
    @ExceptionHandler(com.bitbi.dfm.error.application.ErrorLoggingService.ErrorLogNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handleErrorLogNotFound(
            com.bitbi.dfm.error.application.ErrorLoggingService.ErrorLogNotFoundException ex,
            HttpServletRequest request) {

        logger.warn("Error log not found: {}", ex.getMessage());

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * Handle IllegalStateException (500 Internal Server Error).
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponseDto> handleIllegalState(
            IllegalStateException ex,
            HttpServletRequest request) {

        logger.error("Illegal state: {}", ex.getMessage(), ex);

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    /**
     * Handle generic exceptions (500 Internal Server Error).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDto> handleGenericException(
            Exception ex,
            HttpServletRequest request) {

        logger.error("Unexpected error: {}", ex.getMessage(), ex);

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                "An unexpected error occurred",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}
