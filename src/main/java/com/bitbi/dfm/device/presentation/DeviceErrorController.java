package com.bitbi.dfm.device.presentation;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.error.application.ErrorLoggingService;
import com.bitbi.dfm.error.domain.ErrorLog;
import com.bitbi.dfm.error.presentation.dto.ErrorLogResponseDto;
import com.bitbi.dfm.error.presentation.dto.LogErrorRequestDto;
import com.bitbi.dfm.shared.api.ApiRoutes;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import com.bitbi.dfm.shared.presentation.DeviceControllerHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Device API Error Management Controller.
 * <p>
 * Provides error logging and retrieval operations for device clients.
 * </p>
 * <p>
 * <b>Authentication</b>: Custom JWT (obtained from Device Auth endpoint)<br>
 * <b>Authorization</b>: Site ownership verified for all operations
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 * @see com.bitbi.dfm.error.application.ErrorLoggingService
 * @see <a href="specs/010-api-unification-goal/spec.md">API Unification Specification</a>
 */
@RestController
@RequestMapping(ApiRoutes.DEVICE_ERRORS)
@Tag(name = "Device API - Error Management", description = "Error logging and retrieval for device clients")
@SecurityRequirement(name = "bearerAuth")
public class DeviceErrorController {

    private static final Logger logger = LoggerFactory.getLogger(DeviceErrorController.class);

    private final ErrorLoggingService errorLoggingService;
    private final BatchLifecycleService batchLifecycleService;
    private final AuthorizationHelper authorizationHelper;

    /**
     * Constructor injection for dependencies.
     *
     * @param errorLoggingService   Service for error logging operations
     * @param batchLifecycleService Service for batch operations
     * @param authorizationHelper   Helper for JWT-based authorization
     */
    public DeviceErrorController(
            ErrorLoggingService errorLoggingService,
            BatchLifecycleService batchLifecycleService,
            AuthorizationHelper authorizationHelper) {
        this.errorLoggingService = errorLoggingService;
        this.batchLifecycleService = batchLifecycleService;
        this.authorizationHelper = authorizationHelper;
    }

    /**
     * Log standalone error (without batch association).
     * <p>
     * Logs an error that occurs outside of batch processing context.
     * </p>
     *
     * @param request Error details (type, message, metadata)
     * @return 201 Created with ErrorLogResponseDto
     */
    @PostMapping
    @Operation(
            summary = "Log standalone error",
            description = "Logs an error that occurs outside of batch processing context."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Error logged successfully",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ErrorLogResponseDto.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Bad Request - Invalid error payload",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized - Invalid or missing JWT token",
                    content = @Content(mediaType = "application/json")
            )
    })
    public ResponseEntity<?> logStandaloneError(@Valid @RequestBody LogErrorRequestDto request) {
        try {
            // Extract authenticated site ID from JWT
            UUID siteId = authorizationHelper.getAuthenticatedSiteId();

            logger.info("Device API: Logging standalone error - siteId={}, type={}", siteId, request.type());

            // Delegate to service layer
            ErrorLog errorLog = errorLoggingService.logStandaloneError(
                    siteId,
                    request.type(),
                    request.message(),
                    request.metadata()
            );

            ErrorLogResponseDto response = ErrorLogResponseDto.fromEntity(errorLog);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Device API: Unauthorized standalone error log - {}", e.getMessage());
            return DeviceControllerHelper.handleUnauthorizedException(e);

        } catch (Exception e) {
            logger.error("Device API: Error logging standalone error", e);
            return DeviceControllerHelper.handleInternalServerError("Failed to log error");
        }
    }

    /**
     * Log batch-related error.
     * <p>
     * Logs an error that occurred during batch processing.
     * </p>
     *
     * @param batchId Batch identifier (path variable)
     * @param request Error details (type, message, metadata)
     * @return 201 Created with ErrorLogResponseDto
     */
    @PostMapping("/batches/{batchId}")
    @Operation(
            summary = "Log batch error",
            description = "Logs an error that occurred during batch processing."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Error logged successfully",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ErrorLogResponseDto.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Bad Request - Invalid error payload",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Forbidden - Batch not owned by authenticated site",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Not Found - Batch does not exist",
                    content = @Content(mediaType = "application/json")
            )
    })
    public ResponseEntity<?> logBatchError(
            @PathVariable("batchId") UUID batchId,
            @Valid @RequestBody LogErrorRequestDto request) {
        try {
            logger.info("Device API: Logging batch error - batchId={}, type={}", batchId, request.type());

            // Get batch first to verify ownership
            Batch batch = batchLifecycleService.getBatch(batchId);

            // Verify site ownership via JWT claims
            authorizationHelper.verifySiteOwnership(batch.getSiteId());

            // Delegate to service layer
            ErrorLog errorLog = errorLoggingService.logError(
                    batchId,
                    batch.getSiteId(),
                    request.type(),
                    request.message(),
                    request.metadata()
            );

            ErrorLogResponseDto response = ErrorLogResponseDto.fromEntity(errorLog);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Device API: Unauthorized batch error log - batchId={}, {}", batchId, e.getMessage());
            return DeviceControllerHelper.handleUnauthorizedException(e);

        } catch (BatchLifecycleService.BatchNotFoundException e) {
            logger.warn("Device API: Batch not found - batchId={}", batchId);
            return DeviceControllerHelper.handleBatchNotFoundException(e);

        } catch (Exception e) {
            logger.error("Device API: Error logging batch error - batchId={}", batchId, e);
            return DeviceControllerHelper.handleInternalServerError("Failed to log error");
        }
    }

    /**
     * Get error log by ID.
     * <p>
     * Retrieves a specific error log. The error must belong to a batch/site
     * owned by the authenticated device client.
     * </p>
     *
     * @param errorId Error log identifier (path variable)
     * @return 200 OK with ErrorLogResponseDto, or error response
     */
    @GetMapping("/{errorId}")
    @Operation(
            summary = "Get error log",
            description = "Retrieves a specific error log owned by the authenticated site."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Error log retrieved successfully",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ErrorLogResponseDto.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Forbidden - Error log not owned by authenticated site",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Not Found - Error log does not exist",
                    content = @Content(mediaType = "application/json")
            )
    })
    public ResponseEntity<?> getErrorLog(@PathVariable("errorId") UUID errorId) {
        try {
            logger.debug("Device API: Getting error log - errorId={}", errorId);

            // Get error log
            ErrorLog errorLog = errorLoggingService.getErrorLog(errorId);

            // Verify site ownership via JWT claims
            authorizationHelper.verifySiteOwnership(errorLog.getSiteId());

            ErrorLogResponseDto response = ErrorLogResponseDto.fromEntity(errorLog);
            return ResponseEntity.ok(response);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Device API: Unauthorized error log get - errorId={}, {}", errorId, e.getMessage());
            return DeviceControllerHelper.handleUnauthorizedException(e);

        } catch (ErrorLoggingService.ErrorLogNotFoundException e) {
            logger.warn("Device API: Error log not found - errorId={}", errorId);
            return DeviceControllerHelper.handleErrorLogNotFoundException(e);

        } catch (Exception e) {
            logger.error("Device API: Error getting error log - errorId={}", errorId, e);
            return DeviceControllerHelper.handleInternalServerError("Failed to get error log");
        }
    }
}
