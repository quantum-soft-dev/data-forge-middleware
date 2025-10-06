package com.bitbi.dfm.error.presentation;

import com.bitbi.dfm.auth.application.TokenService;
import com.bitbi.dfm.error.application.ErrorLoggingService;
import com.bitbi.dfm.error.domain.ErrorLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for error logging operations.
 * <p>
 * Provides endpoint for logging errors during batch processing.
 * Requires JWT authentication.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@RestController
@RequestMapping("/api/v1/error")
public class ErrorLogController {

    private static final Logger logger = LoggerFactory.getLogger(ErrorLogController.class);

    private final ErrorLoggingService errorLoggingService;
    private final TokenService tokenService;

    public ErrorLogController(ErrorLoggingService errorLoggingService, TokenService tokenService) {
        this.errorLoggingService = errorLoggingService;
        this.tokenService = tokenService;
    }

    /**
     * Log error for batch.
     * <p>
     * POST /api/v1/error/{batchId}
     * </p>
     *
     * @param batchId    batch identifier
     * @param request    error details (type, message, metadata)
     * @param authHeader Authorization header with Bearer token
     * @return created error log response
     */
    @PostMapping("/{batchId}")
    public ResponseEntity<Map<String, Object>> logError(
            @PathVariable("batchId") UUID batchId,
            @RequestBody Map<String, Object> request,
            @RequestHeader("Authorization") String authHeader) {

        try {
            UUID siteId = extractSiteId(authHeader);

            String type = (String) request.get("type");
            String message = (String) request.get("message");
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) request.get("metadata");

            if (type == null || type.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(createErrorResponse("Error type is required"));
            }

            if (message == null || message.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(createErrorResponse("Error message is required"));
            }

            logger.debug("Logging error: batchId={}, siteId={}, type={}", batchId, siteId, type);

            ErrorLog errorLog = errorLoggingService.logError(batchId, siteId, type, message, metadata);

            Map<String, Object> response = createErrorLogResponse(errorLog);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (Exception e) {
            logger.error("Error logging error: batchId={}", batchId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to log error"));
        }
    }

    /**
     * Get error log by ID.
     * <p>
     * GET /api/v1/error/log/{errorId}
     * </p>
     *
     * @param errorId    error log identifier
     * @param authHeader Authorization header
     * @return error log response
     */
    @GetMapping("/log/{errorId}")
    public ResponseEntity<Map<String, Object>> getErrorLog(
            @PathVariable("errorId") UUID errorId,
            @RequestHeader("Authorization") String authHeader) {

        try {
            extractSiteId(authHeader); // Validate authentication

            ErrorLog errorLog = errorLoggingService.getErrorLog(errorId);

            Map<String, Object> response = createErrorLogResponse(errorLog);
            return ResponseEntity.ok(response);

        } catch (ErrorLoggingService.ErrorLogNotFoundException e) {
            logger.warn("Error log not found: {}", errorId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse("Error log not found"));

        } catch (Exception e) {
            logger.error("Error getting error log: errorId={}", errorId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to retrieve error log"));
        }
    }

    private UUID extractSiteId(String authHeader) {
        String token = extractToken(authHeader);
        return tokenService.validateToken(token);
    }

    private String extractToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Invalid Authorization header");
        }
        return authHeader.substring("Bearer ".length());
    }

    private Map<String, Object> createErrorLogResponse(ErrorLog errorLog) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", errorLog.getId());
        response.put("batchId", errorLog.getBatchId());
        response.put("siteId", errorLog.getSiteId());
        response.put("type", errorLog.getType());
        response.put("title", errorLog.getTitle());
        response.put("message", errorLog.getMessage());
        response.put("stackTrace", errorLog.getStackTrace());
        response.put("clientVersion", errorLog.getClientVersion());
        response.put("metadata", errorLog.getMetadata());
        response.put("occurredAt", errorLog.getOccurredAt().toString());
        return response;
    }

    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        return error;
    }
}
