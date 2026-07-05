package com.bitbi.dfm.shared.presentation;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.error.application.ErrorLoggingService;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import com.bitbi.dfm.site.application.ClientApiVersionGuard;
import com.bitbi.dfm.upload.application.FileUploadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * Helper utilities for Device API controllers.
 * <p>
 * Provides common error response creation and exception handling patterns
 * to reduce code duplication across Device API controllers.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 * @see com.bitbi.dfm.device.presentation.DeviceAuthController
 * @see com.bitbi.dfm.device.presentation.DeviceBatchController
 * @see com.bitbi.dfm.device.presentation.DeviceFileController
 * @see com.bitbi.dfm.device.presentation.DeviceErrorController
 */
public final class DeviceControllerHelper {

    private DeviceControllerHelper() {
        // Utility class - prevent instantiation
    }

    /**
     * Create error response in consistent format.
     *
     * @param status  HTTP status code
     * @param error   Error type (e.g., "Bad Request", "Not Found")
     * @param message Detailed error message
     * @return Error response map
     */
    public static Map<String, Object> createErrorResponse(HttpStatus status, String error, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", status.value());
        response.put("error", error);
        response.put("message", message);
        return response;
    }

    /**
     * Create error response with request path included.
     *
     * @param status  HTTP status code
     * @param error   Error type (e.g., "Bad Request", "Not Found")
     * @param message Detailed error message
     * @param path    Request URI path
     * @return Error response map
     */
    public static Map<String, Object> createErrorResponse(HttpStatus status, String error, String message, String path) {
        Map<String, Object> response = createErrorResponse(status, error, message);
        response.put("path", path);
        return response;
    }

    /**
     * Handle AuthorizationHelper.UnauthorizedException and return 403 Forbidden.
     *
     * @param e Exception with unauthorized access details
     * @return 403 Forbidden response
     */
    public static ResponseEntity<?> handleUnauthorizedException(AuthorizationHelper.UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(createErrorResponse(HttpStatus.FORBIDDEN, "Forbidden", e.getMessage()));
    }

    /**
     * Handle BatchLifecycleService.BatchNotFoundException and return 404 Not Found.
     *
     * @param e Exception with batch not found details
     * @return 404 Not Found response
     */
    public static ResponseEntity<?> handleBatchNotFoundException(BatchLifecycleService.BatchNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(createErrorResponse(HttpStatus.NOT_FOUND, "Not Found", "Batch not found"));
    }

    /**
     * Handle BatchLifecycleService.InvalidBatchStatusException and return 400 Bad Request.
     *
     * @param e Exception with invalid status details
     * @return 400 Bad Request response
     */
    public static ResponseEntity<?> handleInvalidBatchStatusException(BatchLifecycleService.InvalidBatchStatusException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(createErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", e.getMessage()));
    }

    /**
     * Handle FileUploadService.FileNotFoundException and return 404 Not Found.
     *
     * @param e Exception with file not found details
     * @return 404 Not Found response
     */
    public static ResponseEntity<?> handleFileNotFoundException(FileUploadService.FileNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(createErrorResponse(HttpStatus.NOT_FOUND, "Not Found", "File not found"));
    }

    /**
     * Handle ErrorLoggingService.ErrorLogNotFoundException and return 404 Not Found.
     *
     * @param e Exception with error log not found details
     * @return 404 Not Found response
     */
    public static ResponseEntity<?> handleErrorLogNotFoundException(ErrorLoggingService.ErrorLogNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(createErrorResponse(HttpStatus.NOT_FOUND, "Not Found", "Error log not found"));
    }

    /**
     * Handle generic exceptions and return 500 Internal Server Error.
     *
     * @param message Custom error message
     * @return 500 Internal Server Error response
     */
    public static ResponseEntity<?> handleInternalServerError(String message) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", message));
    }

    /**
     * Handle BatchLifecycleService.ActiveBatchExistsException and return 409 Conflict.
     *
     * @param e Exception with active batch conflict details
     * @return 409 Conflict response
     */
    public static ResponseEntity<?> handleActiveBatchExistsException(BatchLifecycleService.ActiveBatchExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(createErrorResponse(HttpStatus.CONFLICT, "Conflict", "Site already has an active batch"));
    }

    /**
     * Handle BatchLifecycleService.ConcurrentBatchLimitException and return 429 Too Many Requests.
     *
     * @param e Exception with concurrent limit details
     * @return 429 Too Many Requests response
     */
    public static ResponseEntity<?> handleConcurrentBatchLimitException(BatchLifecycleService.ConcurrentBatchLimitException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(createErrorResponse(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", e.getMessage()));
    }

    /**
     * Handle ClientApiVersionGuard.HttpFileApiDisabledException and return 409 Conflict
     * with machine-readable {@code code = CLIENT_API_V2_REQUIRED} (022, Task 7).
     *
     * @param e Exception raised for a V2 (Delta gRPC) site on an HTTP file-path write endpoint
     * @return 409 Conflict response with {@code code} field
     */
    public static ResponseEntity<?> handleHttpFileApiDisabledException(ClientApiVersionGuard.HttpFileApiDisabledException e) {
        Map<String, Object> error = createErrorResponse(HttpStatus.CONFLICT, "Conflict", e.getMessage());
        error.put("code", ClientApiVersionGuard.ERROR_CODE);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }
}
