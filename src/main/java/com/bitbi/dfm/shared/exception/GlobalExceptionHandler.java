package com.bitbi.dfm.shared.exception;

import com.bitbi.dfm.account.application.AccountService;
import com.bitbi.dfm.plugin.application.CheckpointFileQueryService;
import com.bitbi.dfm.plugin.domain.exception.PluginDataValidationException;
import com.bitbi.dfm.plugin.domain.exception.PluginNotActivatedException;
import com.bitbi.dfm.plugin.domain.exception.PluginNotEnabledException;
import com.bitbi.dfm.plugin.domain.exception.PluginNotFoundException;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage.CheckpointStorageException;
import com.bitbi.dfm.delta.application.BatchParquetDownloadService.BatchParquetNotReadyException;
import com.bitbi.dfm.delta.application.DeltaCheckpointQueryService;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import com.bitbi.dfm.shared.presentation.dto.ErrorResponseDto;
import com.bitbi.dfm.site.application.SiteService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.core.JacksonException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /** {@code application/*+json}: a structured-syntax suffix the JSON converter also writes. */
    private static final MediaType JSON_SUFFIX = new MediaType("application", "*+json");

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
     * It extracts all field validation errors and returns them in a user-friendly format.
     * </p>
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDto> handleValidationErrors(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        // Extract all validation error messages
        String errorMessage = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(java.util.stream.Collectors.joining(", "));

        if (errorMessage.isEmpty()) {
            errorMessage = "Validation failed";
        }

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
     * Handle ConstraintViolationException (400 Bad Request).
     * <p>
     * This handler is triggered when @RequestParam or @PathVariable validation fails
     * (when @Validated is used on the controller class).
     * It extracts all constraint violation messages and returns them in a user-friendly format.
     * </p>
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponseDto> handleConstraintViolation(
            ConstraintViolationException ex,
            HttpServletRequest request) {

        // Extract all constraint violation messages
        String errorMessage = ex.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(java.util.stream.Collectors.joining(", "));

        if (errorMessage.isEmpty()) {
            errorMessage = "Validation failed";
        }

        logger.warn("Constraint violation: {}", errorMessage);

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
     * Handle HttpMessageNotReadableException (400 Bad Request).
     * <p>
     * A request body that is missing, is not JSON, or holds a value its field cannot bind (an unknown
     * enum constant, a string where an object belongs) is the client's error. Without this handler it
     * reached the catch-all and answered 500 with an ERROR stack trace (issue #320), which a client
     * that retries 5xx retries for ever and an ERROR-rate alert reports as a server fault.
     * </p>
     * <p>
     * The message names the JSON path of the value that did not bind — field names the client itself
     * sent — and nothing else: the parser's own text carries Java type names, the source excerpt and
     * the rejected value. That text goes to the WARN line instead, without a stack trace.
     * </p>
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponseDto> handleUnreadableBody(
            HttpMessageNotReadableException ex,
            HttpServletRequest request) {

        String path = jsonPath(ex);
        String errorMessage = path.isEmpty() ? "Malformed request body" : "Malformed request body at '" + path + "'";
        logger.warn("Unreadable request body on {} {}: {}", request.getMethod(), request.getRequestURI(),
                ex.getMostSpecificCause().getMessage());

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
     * The JSON path of the value Jackson could not bind, as {@code items[1].grade}, or empty when the
     * failure has no path (a syntax error at the root, a missing body). Built from the path references
     * rather than {@link JacksonException#getPathReference()}, which prefixes each step with the Java
     * type it was reading.
     */
    private static String jsonPath(HttpMessageNotReadableException ex) {
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof JacksonException jackson) {
                StringBuilder path = new StringBuilder();
                for (JacksonException.Reference reference : jackson.getPath()) {
                    if (reference.getPropertyName() != null) {
                        if (!path.isEmpty()) {
                            path.append('.');
                        }
                        path.append(reference.getPropertyName());
                    } else if (reference.getIndex() >= 0) {
                        path.append('[').append(reference.getIndex()).append(']');
                    }
                }
                return path.toString();
            }
        }
        return "";
    }

    /**
     * Handle MissingServletRequestParameterException (400 Bad Request).
     * <p>
     * This handler is triggered when a required @RequestParam is missing.
     * </p>
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponseDto> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex,
            HttpServletRequest request) {

        String errorMessage = String.format("Required parameter '%s' is missing", ex.getParameterName());
        logger.warn("Missing request parameter: {}", errorMessage);

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
     * Handle MethodArgumentTypeMismatchException (400 Bad Request).
     * <p>
     * This handler is triggered when a @RequestParam or @PathVariable value
     * cannot be converted to the expected type (e.g., invalid UUID format).
     * </p>
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponseDto> handleMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {

        String errorMessage = String.format("Invalid value for parameter '%s': %s",
                ex.getName(), ex.getValue());
        logger.warn("Type mismatch for parameter: {}", errorMessage);

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
     * Handle AuthorizationHelper.UnauthorizedException (401 Unauthorized).
     * <p>
     * Thrown when the security context holds no usable authentication for the endpoint
     * (e.g. missing/invalid token type, or no resolvable accountId on Auth0-protected
     * endpoints such as /api/v1/device/verify). Without this handler such failures
     * fall through to the generic 500 handler.
     * </p>
     */
    @ExceptionHandler(AuthorizationHelper.UnauthorizedException.class)
    public ResponseEntity<ErrorResponseDto> handleUnauthorized(
            AuthorizationHelper.UnauthorizedException ex,
            HttpServletRequest request) {

        logger.warn("Unauthorized: {}", ex.getMessage());

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.UNAUTHORIZED.value(),
                "Unauthorized",
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    /**
     * Handle DataIntegrityViolationException (409 Conflict).
     * <p>
     * Thrown when a database constraint (unique key, foreign key, not-null) rejects the
     * request. The raw message can leak schema details, so the response uses a generic
     * conflict message while the full cause is logged server-side.
     * </p>
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponseDto> handleDataIntegrityViolation(
            DataIntegrityViolationException ex,
            HttpServletRequest request) {

        logger.warn("Data integrity violation: {}", ex.getMessage());

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.CONFLICT.value(),
                "Conflict",
                "The request conflicts with existing data (duplicate or referenced records).",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
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
     * Handle SecurityException (403 Forbidden).
     * <p>
     * Thrown when a resource access is denied due to ownership/authorization issues.
     * Used by Plugin API when a site doesn't belong to the authenticated account.
     * </p>
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ErrorResponseDto> handleSecurityException(
            SecurityException ex,
            HttpServletRequest request) {

        logger.warn("Security exception: {}", ex.getMessage());

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
     * Handle CannotLockOwnAccountException (403 Forbidden).
     * <p>
     * This exception is thrown when an admin attempts to lock their own account,
     * which is prevented as a security measure.
     * </p>
     *
     * User Story: US2 - Admin Locks/Unlocks User Accounts
     * Functional Requirement: FR-007 - Prevent self-lock
     */
    @ExceptionHandler(CannotLockOwnAccountException.class)
    public ResponseEntity<ErrorResponseDto> handleCannotLockOwnAccount(
            CannotLockOwnAccountException ex,
            HttpServletRequest request) {

        logger.warn("Cannot lock own account: {}", ex.getMessage());

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
     * Handle CannotDeleteOwnAccountException (403 Forbidden).
     * <p>
     * This exception is thrown when an admin attempts to delete their own account,
     * which is prevented as a security measure.
     * </p>
     */
    @ExceptionHandler(AccountService.CannotDeleteOwnAccountException.class)
    public ResponseEntity<ErrorResponseDto> handleCannotDeleteOwnAccount(
            AccountService.CannotDeleteOwnAccountException ex,
            HttpServletRequest request) {

        logger.warn("Cannot delete own account: {}", ex.getMessage());

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
     * Handle MissingAuth0IntegrationException (400 Bad Request).
     * <p>
     * This exception is thrown when an Auth0-specific operation (password reset, lock/unlock)
     * is attempted on an account that has no Auth0 user ID (identity_provider_user_id is NULL).
     * </p>
     *
     * User Story: US3 - Admin Resets User Password
     * Functional Requirement: FR-011 - Validate Auth0 integration before password reset
     */
    @ExceptionHandler(MissingAuth0IntegrationException.class)
    public ResponseEntity<ErrorResponseDto> handleMissingAuth0Integration(
            MissingAuth0IntegrationException ex,
            HttpServletRequest request) {

        logger.warn("Missing Auth0 integration: {}", ex.getMessage());

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
     * Handle object-storage failures (503 Service Unavailable) — a presign/HEAD round-trip to S3
     * failed; the request is retryable and must not surface as a generic 500 (feature 025).
     */
    @ExceptionHandler(CheckpointStorageException.class)
    public ResponseEntity<ErrorResponseDto> handleCheckpointStorage(
            CheckpointStorageException ex,
            HttpServletRequest request) {

        logger.warn("Object storage failure: {}", ex.getMessage());

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                "Service Unavailable",
                "Object storage is temporarily unavailable. Please try again.",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
    }

    /** A durable unified artifact exists but has not completed publication yet (036, issue #93). */
    @ExceptionHandler(BatchParquetNotReadyException.class)
    public ResponseEntity<ErrorResponseDto> handleBatchParquetNotReady(
            BatchParquetNotReadyException ex,
            HttpServletRequest request) {
        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(), HttpStatus.CONFLICT.value(), "Conflict", ex.getMessage(),
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    /**
     * Handle a retired checkpoint download format (410 Gone) — {@code format=csv} since issue #113.
     * <p>
     * Deliberately not 404: the file is not merely absent for this table, the format is no longer
     * produced for any of them, and a client asking for it needs to switch to Parquet.
     * </p>
     */
    @ExceptionHandler(DeltaCheckpointQueryService.RetiredFormatException.class)
    public ResponseEntity<ErrorResponseDto> handleRetiredCheckpointFormat(
            DeltaCheckpointQueryService.RetiredFormatException ex,
            HttpServletRequest request) {
        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(), HttpStatus.GONE.value(), "Gone", ex.getMessage(),
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.GONE).body(error);
    }

    /**
     * Handle a full async work queue (503 Service Unavailable) — e.g. the forced checkpoint
     * rebuild executor rejecting a task (review r3). Retryable, not a generic 500.
     */
    @ExceptionHandler(java.util.concurrent.RejectedExecutionException.class)
    public ResponseEntity<ErrorResponseDto> handleRejectedExecution(
            java.util.concurrent.RejectedExecutionException ex,
            HttpServletRequest request) {

        logger.warn("Async work queue full: {}", ex.getMessage());

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                "Service Unavailable",
                "The work queue is full. Please try again shortly.",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
    }

    /**
     * Handle NoHandlerFoundException (404 Not Found).
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorResponseDto> handleNotFound(
            Exception ex,
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
     * A removed method under a controller's surviving base path is a 405, not a generic 500.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponseDto> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request) {
        logger.warn("Method not allowed: {} {}", ex.getMethod(), request.getRequestURI());
        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.METHOD_NOT_ALLOWED.value(),
                "Method Not Allowed",
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(error);
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
     * Handle SiteInactiveException (403 Forbidden).
     * <p>
     * Thrown when attempting to perform operations on an inactive site.
     * </p>
     */
    @ExceptionHandler(com.bitbi.dfm.batch.application.BatchLifecycleService.SiteInactiveException.class)
    public ResponseEntity<ErrorResponseDto> handleSiteInactive(
            com.bitbi.dfm.batch.application.BatchLifecycleService.SiteInactiveException ex,
            HttpServletRequest request) {

        logger.warn("Site inactive: {}", ex.getMessage());

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
     * Handle SchemaRequiredException (400 Bad Request).
     * <p>
     * Thrown when a POSTGRES_CDC site attempts to start a batch without a registered schema.
     * </p>
     */
    @ExceptionHandler(com.bitbi.dfm.batch.application.BatchLifecycleService.SchemaRequiredException.class)
    public ResponseEntity<ErrorResponseDto> handleSchemaRequired(
            com.bitbi.dfm.batch.application.BatchLifecycleService.SchemaRequiredException ex,
            HttpServletRequest request) {

        logger.warn("Schema required: {}", ex.getMessage());

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
     * Handle BatchNotFoundException from domain package (404 Not Found).
     * <p>
     * Thrown when batch is not found in batch history operations.
     * </p>
     */
    @ExceptionHandler(com.bitbi.dfm.batch.domain.exception.BatchNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handleDomainBatchNotFound(
            com.bitbi.dfm.batch.domain.exception.BatchNotFoundException ex,
            HttpServletRequest request) {

        logger.warn("Batch not found (domain exception): {}", ex.getMessage());

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
     * Handle UnauthorizedBatchAccessException (403 Forbidden).
     * <p>
     * Thrown when user attempts to access a batch they don't own.
     * </p>
     */
    @ExceptionHandler(com.bitbi.dfm.batch.domain.exception.UnauthorizedBatchAccessException.class)
    public ResponseEntity<ErrorResponseDto> handleUnauthorizedBatchAccess(
            com.bitbi.dfm.batch.domain.exception.UnauthorizedBatchAccessException ex,
            HttpServletRequest request) {

        logger.warn("Unauthorized batch access: {}", ex.getMessage());

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
     * Handle GlobalErrorNotFoundException (404 Not Found).
     * <p>
     * Thrown when a global error is not found or access is denied.
     * Returns generic 404 to prevent information disclosure about error existence.
     * </p>
     * User Story: US016 - Global Error Handling
     */
    @ExceptionHandler(com.bitbi.dfm.error.application.GlobalErrorService.GlobalErrorNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handleGlobalErrorNotFound(
            com.bitbi.dfm.error.application.GlobalErrorService.GlobalErrorNotFoundException ex,
            HttpServletRequest request) {

        logger.warn("Global error not found: {}", ex.getMessage());

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
     * Handle AccountNotFoundException from domain package (404 Not Found).
     * <p>
     * This handler catches the new domain-level AccountNotFoundException.
     * </p>
     */
    @ExceptionHandler(com.bitbi.dfm.account.domain.exception.AccountNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handleDomainAccountNotFound(
            com.bitbi.dfm.account.domain.exception.AccountNotFoundException ex,
            HttpServletRequest request) {

        logger.warn("Account not found (domain exception): {}", ex.getMessage());

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
     * Handle AccountAlreadyLockedException from domain package (400 Bad Request).
     * <p>
     * Thrown when attempting to lock an account that is already locked.
     * </p>
     */
    @ExceptionHandler(com.bitbi.dfm.account.domain.exception.AccountAlreadyLockedException.class)
    public ResponseEntity<ErrorResponseDto> handleDomainAccountAlreadyLocked(
            com.bitbi.dfm.account.domain.exception.AccountAlreadyLockedException ex,
            HttpServletRequest request) {

        logger.warn("Account already locked (domain): {}", ex.getMessage());

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
     * Handle Auth0SyncException (503 Service Unavailable).
     * <p>
     * Thrown when synchronization with Auth0 fails.
     * Returns 503 to indicate temporary service unavailability.
     * </p>
     */
    @ExceptionHandler(com.bitbi.dfm.account.domain.exception.Auth0SyncException.class)
    public ResponseEntity<ErrorResponseDto> handleAuth0SyncException(
            com.bitbi.dfm.account.domain.exception.Auth0SyncException ex,
            HttpServletRequest request) {

        logger.error("Auth0 sync failed: {}", ex.getMessage(), ex);

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                "Service Unavailable",
                "Failed to synchronize with Auth0: " + ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
    }

    /**
     * Handle IllegalStateException (400 Bad Request OR 500 Internal Server Error).
     * <p>
     * For Auth0 integration issues (account validation errors), return 400 Bad Request.
     * For other illegal states, return 500 Internal Server Error with generic message.
     * </p>
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponseDto> handleIllegalState(
            IllegalStateException ex,
            HttpServletRequest request) {

        // Check if it's an Auth0 integration validation error
        String message = ex.getMessage();
        if (message != null && message.contains("Auth0 integration")) {
            logger.warn("Auth0 integration validation failed: {}", message);

            ErrorResponseDto error = new ErrorResponseDto(
                    Instant.now(),
                    HttpStatus.BAD_REQUEST.value(),
                    "Bad Request",
                    message,
                    request.getRequestURI()
            );

            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }

        // For other illegal states, return 500 with generic message
        logger.error("Illegal state: {}", message, ex);

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                "An unexpected error occurred", // Generic message - details logged server-side
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    /**
     * Handle BatchNotFoundException (400 Bad Request).
     * Thrown when a batch does not exist or is not in the expected state.
     */
    @ExceptionHandler(com.bitbi.dfm.comparison.application.ComparisonService.BatchNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handleBatchNotFound(
            com.bitbi.dfm.comparison.application.ComparisonService.BatchNotFoundException ex,
            HttpServletRequest request) {

        logger.warn("Batch not found: {}", ex.getMessage());

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
     * Handle BatchNotCompletedException (400 Bad Request).
     *
     * <p><strong>DEPRECATED (2025-11-03):</strong> This exception is no longer thrown after business rule change.
     * Batches no longer need to be COMPLETED to be compared. This handler is kept for backward compatibility
     * in case the exception class is still referenced elsewhere.
     *
     * <p>Previous behavior: Thrown when a batch exists but is not in COMPLETED status.
     * <p>Current behavior: Batches with any status can be compared if they have files.
     *
     * @deprecated Since 2025-11-03. Will be removed in next major version.
     */
    @Deprecated
    @ExceptionHandler(com.bitbi.dfm.comparison.application.ComparisonService.BatchNotCompletedException.class)
    public ResponseEntity<ErrorResponseDto> handleBatchNotCompleted(
            com.bitbi.dfm.comparison.application.ComparisonService.BatchNotCompletedException ex,
            HttpServletRequest request) {

        logger.warn("[DEPRECATED] Batch not completed exception thrown (this should not happen after business rule change): {}",
            ex.getMessage());

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
     * Handle ComparisonService.UnauthorizedAccessException (403 Forbidden).
     * Thrown when user tries to access a resource they don't own.
     */
    @ExceptionHandler(com.bitbi.dfm.comparison.application.ComparisonService.UnauthorizedAccessException.class)
    public ResponseEntity<ErrorResponseDto> handleComparisonUnauthorized(
            com.bitbi.dfm.comparison.application.ComparisonService.UnauthorizedAccessException ex,
            HttpServletRequest request) {

        logger.warn("Unauthorized access attempt: {}", ex.getMessage());

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
     * Handle ComparisonNotFoundException (404 Not Found).
     * Thrown when a comparison does not exist.
     */
    @ExceptionHandler(com.bitbi.dfm.comparison.application.ComparisonService.ComparisonNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handleComparisonNotFound(
            com.bitbi.dfm.comparison.application.ComparisonService.ComparisonNotFoundException ex,
            HttpServletRequest request) {

        logger.warn("Comparison not found: {}", ex.getMessage());

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
     * Handle ComparisonInProgressException (400 Bad Request).
     * Thrown when attempting to delete a comparison that is currently IN_PROGRESS.
     * User Story: US7 - Delete Saved Comparisons (Phase 9)
     */
    @ExceptionHandler(com.bitbi.dfm.comparison.application.ComparisonService.ComparisonInProgressException.class)
    public ResponseEntity<ErrorResponseDto> handleComparisonInProgress(
            com.bitbi.dfm.comparison.application.ComparisonService.ComparisonInProgressException ex,
            HttpServletRequest request) {

        logger.warn("Cannot delete comparison in progress: {}", ex.getMessage());

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
     * Handle Auth0ServiceUnavailableException (503 Service Unavailable).
     * <p>
     * Thrown when Auth0 Management API is unavailable or returns 5xx errors.
     * This indicates temporary Auth0 service issues or network problems.
     * </p>
     * User Story: Auth0 Migration (Phase 2 - Foundational)
     */
    @ExceptionHandler(Auth0ServiceUnavailableException.class)
    public ResponseEntity<ErrorResponseDto> handleAuth0ServiceUnavailable(
            Auth0ServiceUnavailableException ex,
            HttpServletRequest request) {

        logger.error("Auth0 service unavailable: {}", ex.getMessage(), ex);

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                "Service Unavailable",
                "Auth0 authentication service is temporarily unavailable. Please try again later.",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
    }

    /**
     * Handle Auth0RateLimitException (503 Service Unavailable).
     * <p>
     * Thrown when Auth0 Management API rate limit is exceeded (HTTP 429).
     * This indicates the application is making too many requests to Auth0.
     * Returns Retry-After header if available from Auth0.
     * </p>
     * User Story: Auth0 Migration (Phase 2 - Foundational)
     */
    @ExceptionHandler(Auth0RateLimitException.class)
    public ResponseEntity<ErrorResponseDto> handleAuth0RateLimit(
            Auth0RateLimitException ex,
            HttpServletRequest request) {

        logger.warn("Auth0 rate limit exceeded: {}", ex.getMessage());

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                "Service Unavailable",
                "Too many authentication requests. Please try again in a few moments.",
                request.getRequestURI()
        );

        // Add Retry-After header if available
        var response = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
        if (ex.getRetryAfterSeconds() != null) {
            response = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", ex.getRetryAfterSeconds().toString())
                    .body(error);
        }

        return response;
    }

    /**
     * Handle Auth0 APIException with duplicate email (409 Conflict).
     * <p>
     * Thrown when attempting to create a user with an email that already exists in Auth0.
     * This is a specific case of Auth0 APIException that should return 409 instead of 500.
     * </p>
     * User Story: US1 - Admin Creates User Account via Auth0
     */
    @ExceptionHandler(com.auth0.exception.APIException.class)
    public ResponseEntity<ErrorResponseDto> handleAuth0APIException(
            com.auth0.exception.APIException ex,
            HttpServletRequest request) {

        // Check if it's a duplicate email error
        if (ex.getMessage() != null && ex.getMessage().toLowerCase().contains("user already exists")) {
            logger.warn("Auth0 duplicate user: {}", ex.getMessage());

            ErrorResponseDto error = new ErrorResponseDto(
                    Instant.now(),
                    HttpStatus.CONFLICT.value(),
                    "Conflict",
                    "A user with this email already exists",
                    request.getRequestURI()
            );

            return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
        }

        // For other API exceptions, log and return 500
        logger.error("Auth0 API error: {}", ex.getMessage(), ex);

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                "An error occurred while communicating with the authentication service",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    // ==================== Plugin Exceptions ====================

    /**
     * Handle PluginNotFoundException (404 Not Found).
     * <p>
     * Thrown when a plugin is not found in the registry or database.
     * </p>
     * User Story: US2 - Activate a Plugin for an Account
     */
    @ExceptionHandler(PluginNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handlePluginNotFound(
            PluginNotFoundException ex,
            HttpServletRequest request) {

        logger.warn("Plugin not found: {}", ex.getPluginId());

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
     * Handle PluginDataValidationException (400 Bad Request).
     * <p>
     * Thrown when plugin data fails JSON Schema validation.
     * </p>
     * User Story: US2 - Activate a Plugin for an Account
     * Functional Requirement: FR-004 - Validate pluginData against JSON Schema
     */
    @ExceptionHandler(PluginDataValidationException.class)
    public ResponseEntity<ErrorResponseDto> handlePluginDataValidation(
            PluginDataValidationException ex,
            HttpServletRequest request) {

        logger.warn("Plugin data validation failed for {}: {}", ex.getPluginId(), ex.getValidationErrors());

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Validation Error",
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Handle PluginNotEnabledException (404 Not Found).
     * <p>
     * Thrown when a plugin exists but is not enabled.
     * Returns 404 because the plugin is effectively unavailable for activation.
     * </p>
     * User Story: US2 - Activate a Plugin for an Account
     */
    @ExceptionHandler(PluginNotEnabledException.class)
    public ResponseEntity<ErrorResponseDto> handlePluginNotEnabled(
            PluginNotEnabledException ex,
            HttpServletRequest request) {

        logger.warn("Plugin not enabled: {}", ex.getPluginId());

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
     * Handle PluginNotActivatedException (403 Forbidden).
     * <p>
     * Thrown when attempting to deactivate a plugin that is not active for the account.
     * Returns 403 Forbidden as the operation is not permitted.
     * </p>
     * User Story: US3 - Deactivate a Plugin Integration
     */
    @ExceptionHandler(PluginNotActivatedException.class)
    public ResponseEntity<ErrorResponseDto> handlePluginNotActivated(
            PluginNotActivatedException ex,
            HttpServletRequest request) {

        logger.warn("Plugin not activated: {} for account {}", ex.getPluginId(), ex.getAccountId());

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
     * Handle DownloadLinkService.LinkGoneException (410 Gone, 028-parquet-export-plugin).
     * <p>
     * The one-time link exists but is consumed, expired, or its activation is inactive.
     * </p>
     */
    @ExceptionHandler(com.bitbi.dfm.plugin.application.DownloadLinkService.LinkGoneException.class)
    public ResponseEntity<ErrorResponseDto> handleDownloadLinkGone(
            com.bitbi.dfm.plugin.application.DownloadLinkService.LinkGoneException ex,
            HttpServletRequest request) {

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.GONE.value(),
                "Gone",
                "Download link is no longer valid",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.GONE).body(error);
    }

    /**
     * Handle DownloadLinkService.LinkNotFoundException (404 Not Found, 028-parquet-export-plugin).
     */
    @ExceptionHandler(com.bitbi.dfm.plugin.application.DownloadLinkService.LinkNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handleDownloadLinkNotFound(
            com.bitbi.dfm.plugin.application.DownloadLinkService.LinkNotFoundException ex,
            HttpServletRequest request) {

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
     * Handle CheckpointFileQueryService.FileNotFoundException (404 Not Found).
     * <p>
     * Thrown when a baseline file is not found for a site.
     * </p>
     */
    @ExceptionHandler(CheckpointFileQueryService.FileNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handleBaselineFileNotFound(
            CheckpointFileQueryService.FileNotFoundException ex,
            HttpServletRequest request) {

        logger.warn("Baseline file not found: {}", ex.getMessage());

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
     * Handle ResponseStatusException — pass the declared status and reason through unchanged.
     * <p>
     * Controllers throw ResponseStatusException to signal intentional 403/404/401 responses
     * (e.g. BatchHistoryAdminController on cross-account batch access). Without this handler
     * the generic Exception handler turns such routine denials into 500 responses with an
     * ERROR-level stack trace (seen live on GKE test 2026-07-23).
     * </p>
     */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ErrorResponseDto> handleResponseStatusException(
            org.springframework.web.server.ResponseStatusException ex,
            HttpServletRequest request) {

        logger.warn("Response status exception: {} {}", ex.getStatusCode(), ex.getReason());

        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        String error = (status != null) ? status.getReasonPhrase() : ex.getStatusCode().toString();
        String message = (ex.getReason() != null) ? ex.getReason() : error;

        ErrorResponseDto body = new ErrorResponseDto(
                Instant.now(),
                ex.getStatusCode().value(),
                error,
                message,
                request.getRequestURI()
        );

        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }

    /**
     * Handle generic exceptions (500 Internal Server Error) — and every Spring MVC exception that
     * carries its own status.
     * <p>
     * This advice runs before {@code DefaultHandlerExceptionResolver}, so without the
     * {@link ErrorResponse} branch every Spring MVC exception with no handler of its own answered 500
     * with an ERROR stack trace (issue #336): an unsupported {@code Content-Type} (415) and an
     * {@code Accept} the route cannot produce (406) today, a missing header, a multipart or an async
     * timeout as soon as a route uses one. {@code @ExceptionHandler} takes only {@code Throwable}
     * types and {@code ErrorResponse} is an interface, so the branch lives here; every more specific
     * handler above still wins, because Spring picks the closest declared type and this is the
     * farthest.
     * </p>
     * <p>
     * Such an exception answers its own status and headers (the {@code Accept} list of a 415). A 4xx
     * is the client's: its {@link ProblemDetail} detail — the text Spring writes for clients, naming
     * the media type or the header at fault, never a Java type — and one WARN without a stack trace.
     * A 5xx is still ours: the reason phrase only, and an ERROR with the stack trace.
     * </p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDto> handleGenericException(
            Exception ex,
            HttpServletRequest request) {

        if (ex instanceof ErrorResponse errorResponse) {
            return handleErrorResponse(ex, errorResponse, request);
        }

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

    private ResponseEntity<ErrorResponseDto> handleErrorResponse(
            Exception ex,
            ErrorResponse errorResponse,
            HttpServletRequest request) {

        HttpStatusCode statusCode = errorResponse.getStatusCode();
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        String reason = (status != null) ? status.getReasonPhrase() : statusCode.toString();
        String detail = errorResponse.getBody().getDetail();
        String message;

        if (statusCode.is5xxServerError()) {
            message = reason;
            logger.error("{} on {} {}: {}", statusCode.value(), request.getMethod(), request.getRequestURI(),
                    ex.getMessage(), ex);
        } else {
            message = (detail != null && !detail.isBlank()) ? detail : reason;
            logger.warn("Client error {} on {} {}: {}", statusCode.value(), request.getMethod(),
                    request.getRequestURI(), message);
        }

        ErrorResponseDto body = new ErrorResponseDto(
                Instant.now(),
                statusCode.value(),
                reason,
                message,
                request.getRequestURI()
        );

        ResponseEntity.BodyBuilder response = ResponseEntity.status(statusCode).headers(errorResponse.getHeaders());
        return acceptsJson(request) ? response.body(body) : response.build();
    }

    /**
     * Whether the error body can be written in a type the client accepts. A JSON route asked for
     * {@code text/plain} only fails with 406 — and writing a JSON body into that response fails again,
     * which Spring's resolver logs as "Failure in @ExceptionHandler" with a stack trace before it
     * answers the same 406 bare. So such a client gets the status alone. An {@code Accept} that does
     * not parse is left to Spring's own negotiation, which copes with it.
     */
    private static boolean acceptsJson(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept == null || accept.isBlank()) {
            return true;
        }
        try {
            return MediaType.parseMediaTypes(accept).stream()
                    .anyMatch(type -> type.isCompatibleWith(MediaType.APPLICATION_JSON)
                            || type.isCompatibleWith(JSON_SUFFIX));
        } catch (InvalidMediaTypeException ex) {
            return true;
        }
    }
}
