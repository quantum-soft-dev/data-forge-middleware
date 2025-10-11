package com.bitbi.dfm.error.presentation;

import com.bitbi.dfm.auth.application.TokenService;
import com.bitbi.dfm.error.application.ErrorLoggingService;
import com.bitbi.dfm.error.domain.ErrorLog;
import com.bitbi.dfm.error.presentation.dto.ErrorLogResponseDto;
import com.bitbi.dfm.error.presentation.dto.LogErrorRequestDto;
import com.bitbi.dfm.shared.presentation.dto.ErrorResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.AccessDeniedException;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
@Tag(name = "Client - Error Logging", description = "Error logging endpoints for Data Forge Client")
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
    @Operation(
            summary = "Log standalone error",
            description = "Logs an error that occurs outside of batch processing context. Requires JWT authentication."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Error logged successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid input (validation error)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "403", description = "Invalid or expired JWT token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
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
    @Operation(
            summary = "Log batch error",
            description = "Logs an error associated with a specific batch. Verifies batch ownership before logging. Requires JWT authentication."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Error logged successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorLogResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid input or unauthorized batch access",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "403", description = "Invalid or expired JWT token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Batch not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
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
            throw new AccessDeniedException("Cannot log errors to batch owned by another site");
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
    @Operation(
            summary = "Get error log by ID",
            description = "Retrieves error log details. Verifies ownership for batch-associated errors. Requires JWT authentication."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Error log found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorLogResponseDto.class))),
            @ApiResponse(responseCode = "403", description = "Unauthorized access to error log",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "404", description = "Error log not found",
                    content = @Content(mediaType = "application/json"))
    })
    @GetMapping("/log/{errorId}")
    public ResponseEntity<ErrorLogResponseDto> getErrorLog(
            @PathVariable("errorId") UUID errorId,
            @RequestHeader("Authorization") String authHeader) {

        UUID siteId = extractSiteId(authHeader); // Validate authentication

        ErrorLog errorLog = errorLoggingService.getErrorLog(errorId);

        // ✅ SECURITY FIX: Verify error log belongs to authenticated site's batch
        if (errorLog.getBatchId() != null) {
            com.bitbi.dfm.batch.domain.Batch batch = batchLifecycleService.getBatch(errorLog.getBatchId());
            if (!batch.getSiteId().equals(siteId)) {
                logger.warn("Unauthorized error log access attempt: siteId={}, errorId={}, batchOwner={}",
                            siteId, errorId, batch.getSiteId());
                throw new AccessDeniedException("Cannot access error log from batch owned by another site");
            }
        }
        // Note: Standalone errors (batchId == null) are accessible by the site that created them
        // because errorLog.getSiteId() was already used during creation

        ErrorLogResponseDto response = ErrorLogResponseDto.fromEntity(errorLog);
        return ResponseEntity.ok(response);
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
}
