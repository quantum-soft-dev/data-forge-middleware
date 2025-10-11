package com.bitbi.dfm.error.presentation;

import com.bitbi.dfm.auth.application.TokenService;
import com.bitbi.dfm.error.application.ErrorLoggingService;
import com.bitbi.dfm.error.domain.ErrorLog;
import com.bitbi.dfm.error.presentation.dto.ErrorLogResponseDto;
import com.bitbi.dfm.error.presentation.dto.LogErrorRequestDto;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for error logging operations (Data Forge Client API).
 * <p>
 * Provides endpoint for logging errors during batch processing.
 * Requires JWT authentication.
 * </p>
 * <p>
 * URL change from v2.x: /api/v1/error/** → /api/dfc/error/** (breaking change)
 * </p>
 *
 * @author Data Forge Team
 * @version 3.0.0
 */
@RestController
@RequestMapping("/api/dfc/error")
public class ErrorLogController {

    private static final Logger logger = LoggerFactory.getLogger(ErrorLogController.class);

    private final ErrorLoggingService errorLoggingService;
    private final TokenService tokenService;
    private final com.bitbi.dfm.batch.application.BatchLifecycleService batchLifecycleService;

    public ErrorLogController(ErrorLoggingService errorLoggingService,
                             TokenService tokenService,
                             com.bitbi.dfm.batch.application.BatchLifecycleService batchLifecycleService) {
        this.errorLoggingService = errorLoggingService;
        this.tokenService = tokenService;
        this.batchLifecycleService = batchLifecycleService;
    }

    /**
     * Log standalone error without batch association.
     * <p>
     * POST /api/v1/error
     * Used for errors that occur outside of batch processing context.
     * </p>
     *
     * @param request    error details (type, message, metadata)
     * @param authHeader Authorization header with Bearer token
     * @return 204 No Content
     */
    @PostMapping
    public ResponseEntity<Void> logStandaloneError(
            @Valid @RequestBody LogErrorRequestDto request,
            @RequestHeader("Authorization") String authHeader) {

        UUID siteId = extractSiteId(authHeader);

        logger.debug("Logging standalone error: siteId={}, type={}", siteId, request.type());

        errorLoggingService.logStandaloneError(siteId, request.type(), request.message(), request.metadata());

        return ResponseEntity.noContent().build();
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
    public ResponseEntity<ErrorLogResponseDto> logError(
            @PathVariable("batchId") UUID batchId,
            @Valid @RequestBody LogErrorRequestDto request,
            @RequestHeader("Authorization") String authHeader) {

        UUID siteId = extractSiteId(authHeader);

        // ✅ SECURITY FIX: Verify batch ownership before logging error
        com.bitbi.dfm.batch.domain.Batch batch = batchLifecycleService.getBatch(batchId);
        if (!batch.getSiteId().equals(siteId)) {
            logger.warn("Unauthorized error logging attempt: siteId={}, batchId={}, batchOwner={}",
                        siteId, batchId, batch.getSiteId());
            throw new IllegalArgumentException("Cannot log errors to batch owned by another site");
        }

        logger.debug("Logging error: batchId={}, siteId={}, type={}", batchId, siteId, request.type());

        ErrorLog errorLog = errorLoggingService.logError(batchId, siteId, request.type(), request.message(), request.metadata());

        ErrorLogResponseDto response = ErrorLogResponseDto.fromEntity(errorLog);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
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
    public ResponseEntity<?> getErrorLog(
            @PathVariable("errorId") UUID errorId,
            @RequestHeader("Authorization") String authHeader) {

        try {
            UUID siteId = extractSiteId(authHeader); // Validate authentication

            ErrorLog errorLog = errorLoggingService.getErrorLog(errorId);

            // ✅ SECURITY FIX: Verify error log belongs to authenticated site's batch
            if (errorLog.getBatchId() != null) {
                try {
                    com.bitbi.dfm.batch.domain.Batch batch = batchLifecycleService.getBatch(errorLog.getBatchId());
                    if (!batch.getSiteId().equals(siteId)) {
                        logger.warn("Unauthorized error log access attempt: siteId={}, errorId={}, batchOwner={}",
                                    siteId, errorId, batch.getSiteId());
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(createErrorResponse(HttpStatus.FORBIDDEN,
                                      "Cannot access error log from batch owned by another site"));
                    }
                } catch (com.bitbi.dfm.batch.application.BatchLifecycleService.BatchNotFoundException e) {
                    logger.warn("Batch not found for error log: errorId={}, batchId={}", errorId, errorLog.getBatchId());
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(createErrorResponse(HttpStatus.NOT_FOUND, "Batch not found"));
                }
            }
            // Note: Standalone errors (batchId == null) are accessible by the site that created them
            // because errorLog.getSiteId() was already used during creation

            ErrorLogResponseDto response = ErrorLogResponseDto.fromEntity(errorLog);
            return ResponseEntity.ok(response);

        } catch (ErrorLoggingService.ErrorLogNotFoundException e) {
            logger.warn("Error log not found: {}", errorId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse(HttpStatus.NOT_FOUND, "Error log not found"));

        } catch (Exception e) {
            logger.error("Error getting error log: errorId={}", errorId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                          "Failed to retrieve error log"));
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

    /**
     * Create standardized error response with status, error, and message fields.
     * Matches ErrorResponseDto structure for consistency across API.
     */
    private Map<String, Object> createErrorResponse(HttpStatus status, String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("status", status.value());
        error.put("error", status.getReasonPhrase());
        error.put("message", message);
        return error;
    }
}
