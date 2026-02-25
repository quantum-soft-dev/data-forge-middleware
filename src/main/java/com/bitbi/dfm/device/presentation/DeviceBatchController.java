package com.bitbi.dfm.device.presentation;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.presentation.dto.BatchResponseDto;
import com.bitbi.dfm.batch.presentation.dto.BatchStartRequestDto;
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
 * Device API Batch Management Controller.
 * <p>
 * Provides batch lifecycle operations for device clients:
 * start, complete, fail, cancel, and get batch details.
 * </p>
 * <p>
 * <b>Authentication</b>: Custom JWT (obtained from Device Auth endpoint)<br>
 * <b>Authorization</b>: Site ownership verified for all operations
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 * @see com.bitbi.dfm.batch.application.BatchLifecycleService
 * @see <a href="specs/010-api-unification-goal/spec.md">API Unification Specification</a>
 */
@RestController
@RequestMapping(ApiRoutes.DEVICE_BATCHES)
@Tag(name = "Device API - Batch Management", description = "Batch lifecycle operations for device clients (start, complete, fail, cancel)")
@SecurityRequirement(name = "bearerAuth")
public class DeviceBatchController {

    private static final Logger logger = LoggerFactory.getLogger(DeviceBatchController.class);

    private final BatchLifecycleService batchLifecycleService;
    private final AuthorizationHelper authorizationHelper;

    /**
     * Constructor injection for dependencies.
     *
     * @param batchLifecycleService Service for batch lifecycle operations
     * @param authorizationHelper   Helper for JWT-based authorization
     */
    public DeviceBatchController(
            BatchLifecycleService batchLifecycleService,
            AuthorizationHelper authorizationHelper) {
        this.batchLifecycleService = batchLifecycleService;
        this.authorizationHelper = authorizationHelper;
    }

    /**
     * Start new batch for the authenticated device client.
     * <p>
     * Creates a new IN_PROGRESS batch for uploading files. The site can only have
     * one active batch at a time.
     * </p>
     *
     * @return 201 Created with batch details, or error response
     */
    @PostMapping("/start")
    @Operation(
            summary = "Start new batch",
            description = "Creates a new IN_PROGRESS batch for file uploads. " +
                    "Requires batchType (BASELINE or DELTA) in the request body. " +
                    "The authenticated site can only have one active batch at a time. " +
                    "Account-level concurrent batch limit also applies."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Batch started successfully",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BatchResponseDto.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Bad Request - Missing batchType or schema required",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized - Invalid or missing JWT token",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Forbidden - Site not authorized",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Conflict - Site already has an active batch",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "428",
                    description = "Precondition Required - Heartbeat required before starting a batch",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "429",
                    description = "Too Many Requests - Concurrent batch limit exceeded",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal Server Error",
                    content = @Content(mediaType = "application/json")
            )
    })
    public ResponseEntity<?> startBatch(@Valid @RequestBody BatchStartRequestDto request) {
        try {
            // Extract authenticated site/account from JWT security context
            UUID siteId = authorizationHelper.getAuthenticatedSiteId();
            UUID accountId = authorizationHelper.getAuthenticatedAccountId();

            logger.info("Device API: Starting batch - siteId={}, batchType={}", siteId, request.batchType());

            // Delegate to service layer
            Batch batch = batchLifecycleService.startBatch(accountId, siteId, request);

            BatchResponseDto response = BatchResponseDto.fromEntity(batch);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Device API: Unauthorized batch start - {}", e.getMessage());
            return DeviceControllerHelper.handleUnauthorizedException(e);

        } catch (BatchLifecycleService.ActiveBatchExistsException e) {
            logger.warn("Device API: Active batch exists - {}", e.getMessage());
            return DeviceControllerHelper.handleActiveBatchExistsException(e);

        } catch (BatchLifecycleService.ConcurrentBatchLimitException e) {
            logger.warn("Device API: Concurrent batch limit exceeded - {}", e.getMessage());
            return DeviceControllerHelper.handleConcurrentBatchLimitException(e);

        } catch (Exception e) {
            logger.error("Device API: Error starting batch", e);
            return DeviceControllerHelper.handleInternalServerError("Failed to start batch");
        }
    }

    /**
     * Complete batch successfully.
     * <p>
     * Marks an IN_PROGRESS batch as COMPLETED. All files should be uploaded before
     * completing the batch.
     * </p>
     *
     * @param batchId Batch identifier (path variable)
     * @return 200 OK with updated batch details, or error response
     */
    @PostMapping("/{id}/complete")
    @Operation(
            summary = "Complete batch",
            description = "Marks an IN_PROGRESS batch as COMPLETED. " +
                    "This indicates all files have been successfully uploaded."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Batch completed successfully",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BatchResponseDto.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Bad Request - Invalid batch status transition",
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
    public ResponseEntity<?> completeBatch(@PathVariable("id") UUID batchId) {
        try {
            logger.info("Device API: Completing batch - batchId={}", batchId);

            // Get batch first to verify ownership
            Batch batch = batchLifecycleService.getBatch(batchId);

            // Verify site ownership via JWT claims
            authorizationHelper.verifySiteOwnership(batch.getSiteId());

            // Delegate to service layer
            batch = batchLifecycleService.completeBatch(batchId);

            BatchResponseDto response = BatchResponseDto.fromEntity(batch);
            return ResponseEntity.ok(response);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Device API: Unauthorized batch completion - batchId={}, {}", batchId, e.getMessage());
            return DeviceControllerHelper.handleUnauthorizedException(e);

        } catch (BatchLifecycleService.BatchNotFoundException e) {
            logger.warn("Device API: Batch not found - batchId={}", batchId);
            return DeviceControllerHelper.handleBatchNotFoundException(e);

        } catch (BatchLifecycleService.InvalidBatchStatusException e) {
            logger.warn("Device API: Invalid batch status - {}", e.getMessage());
            return DeviceControllerHelper.handleInvalidBatchStatusException(e);

        } catch (Exception e) {
            logger.error("Device API: Error completing batch - batchId={}", batchId, e);
            return DeviceControllerHelper.handleInternalServerError("Failed to complete batch");
        }
    }

    /**
     * Complete batch with warnings.
     * <p>
     * Marks an IN_PROGRESS batch as COMPLETED_WITH_WARNINGS. Use this when the batch
     * completes but with non-critical warnings (e.g., data quality issues that don't
     * prevent processing).
     * </p>
     * <p>
     * Key differences from regular completion:
     * <ul>
     *   <li>Status set to COMPLETED_WITH_WARNINGS instead of COMPLETED</li>
     *   <li>hasErrors remains false (warnings ≠ errors)</li>
     *   <li>Data is still considered valid for processing</li>
     * </ul>
     * </p>
     *
     * @param batchId Batch identifier (path variable)
     * @return 200 OK with updated batch details, or error response
     */
    @PostMapping("/{id}/complete-with-warnings")
    @Operation(
            summary = "Complete batch with warnings",
            description = "Marks an IN_PROGRESS batch as COMPLETED_WITH_WARNINGS. " +
                    "Use this when the batch completes successfully but with non-critical warnings. " +
                    "hasErrors remains false (warnings are not errors)."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Batch completed with warnings",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BatchResponseDto.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Bad Request - Invalid batch status transition",
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
    public ResponseEntity<?> completeBatchWithWarnings(@PathVariable("id") UUID batchId) {
        try {
            logger.info("Device API: Completing batch with warnings - batchId={}", batchId);

            // Get batch first to verify ownership
            Batch batch = batchLifecycleService.getBatch(batchId);

            // Verify site ownership via JWT claims
            authorizationHelper.verifySiteOwnership(batch.getSiteId());

            // Delegate to service layer
            batch = batchLifecycleService.completeBatchWithWarnings(batchId);

            BatchResponseDto response = BatchResponseDto.fromEntity(batch);
            return ResponseEntity.ok(response);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Device API: Unauthorized batch completion with warnings - batchId={}, {}", batchId, e.getMessage());
            return DeviceControllerHelper.handleUnauthorizedException(e);

        } catch (BatchLifecycleService.BatchNotFoundException e) {
            logger.warn("Device API: Batch not found - batchId={}", batchId);
            return DeviceControllerHelper.handleBatchNotFoundException(e);

        } catch (BatchLifecycleService.InvalidBatchStatusException e) {
            logger.warn("Device API: Invalid batch status - {}", e.getMessage());
            return DeviceControllerHelper.handleInvalidBatchStatusException(e);

        } catch (Exception e) {
            logger.error("Device API: Error completing batch with warnings - batchId={}", batchId, e);
            return DeviceControllerHelper.handleInternalServerError("Failed to complete batch with warnings");
        }
    }

    /**
     * Fail batch due to error.
     * <p>
     * Marks an IN_PROGRESS batch as FAILED. Use this when an unrecoverable error
     * occurs during file uploads.
     * </p>
     *
     * @param batchId Batch identifier (path variable)
     * @return 200 OK with updated batch details, or error response
     */
    @PostMapping("/{id}/fail")
    @Operation(
            summary = "Fail batch",
            description = "Marks an IN_PROGRESS batch as FAILED due to unrecoverable errors."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Batch failed successfully"),
            @ApiResponse(responseCode = "400", description = "Bad Request - Invalid status transition"),
            @ApiResponse(responseCode = "403", description = "Forbidden - Unauthorized"),
            @ApiResponse(responseCode = "404", description = "Not Found - Batch not found")
    })
    public ResponseEntity<?> failBatch(@PathVariable("id") UUID batchId) {
        try {
            logger.info("Device API: Failing batch - batchId={}", batchId);

            Batch batch = batchLifecycleService.getBatch(batchId);
            authorizationHelper.verifySiteOwnership(batch.getSiteId());

            batch = batchLifecycleService.failBatch(batchId);

            BatchResponseDto response = BatchResponseDto.fromEntity(batch);
            return ResponseEntity.ok(response);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Device API: Unauthorized batch fail - batchId={}, {}", batchId, e.getMessage());
            return DeviceControllerHelper.handleUnauthorizedException(e);

        } catch (BatchLifecycleService.BatchNotFoundException e) {
            logger.warn("Device API: Batch not found - batchId={}", batchId);
            return DeviceControllerHelper.handleBatchNotFoundException(e);

        } catch (BatchLifecycleService.InvalidBatchStatusException e) {
            logger.warn("Device API: Invalid batch status - {}", e.getMessage());
            return DeviceControllerHelper.handleInvalidBatchStatusException(e);

        } catch (Exception e) {
            logger.error("Device API: Error failing batch - batchId={}", batchId, e);
            return DeviceControllerHelper.handleInternalServerError("Failed to fail batch");
        }
    }

    /**
     * Cancel batch (user-initiated).
     * <p>
     * Marks an IN_PROGRESS batch as CANCELLED. Use this when the device client
     * decides to abort the upload process.
     * </p>
     *
     * @param batchId Batch identifier (path variable)
     * @return 200 OK with updated batch details, or error response
     */
    @PostMapping("/{id}/cancel")
    @Operation(
            summary = "Cancel batch",
            description = "Marks an IN_PROGRESS batch as CANCELLED. Used when device client aborts upload."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Batch cancelled successfully"),
            @ApiResponse(responseCode = "400", description = "Bad Request - Invalid status transition"),
            @ApiResponse(responseCode = "403", description = "Forbidden - Unauthorized"),
            @ApiResponse(responseCode = "404", description = "Not Found - Batch not found")
    })
    public ResponseEntity<?> cancelBatch(@PathVariable("id") UUID batchId) {
        try {
            logger.info("Device API: Cancelling batch - batchId={}", batchId);

            Batch batch = batchLifecycleService.getBatch(batchId);
            authorizationHelper.verifySiteOwnership(batch.getSiteId());

            batch = batchLifecycleService.cancelBatch(batchId);

            BatchResponseDto response = BatchResponseDto.fromEntity(batch);
            return ResponseEntity.ok(response);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Device API: Unauthorized batch cancel - batchId={}, {}", batchId, e.getMessage());
            return DeviceControllerHelper.handleUnauthorizedException(e);

        } catch (BatchLifecycleService.BatchNotFoundException e) {
            logger.warn("Device API: Batch not found - batchId={}", batchId);
            return DeviceControllerHelper.handleBatchNotFoundException(e);

        } catch (BatchLifecycleService.InvalidBatchStatusException e) {
            logger.warn("Device API: Invalid batch status - {}", e.getMessage());
            return DeviceControllerHelper.handleInvalidBatchStatusException(e);

        } catch (Exception e) {
            logger.error("Device API: Error cancelling batch - batchId={}", batchId, e);
            return DeviceControllerHelper.handleInternalServerError("Failed to cancel batch");
        }
    }

    /**
     * Get batch details.
     * <p>
     * Retrieves current status and metadata for a batch.
     * </p>
     *
     * @param batchId Batch identifier (path variable)
     * @return 200 OK with batch details, or error response
     */
    @GetMapping("/{id}")
    @Operation(
            summary = "Get batch details",
            description = "Retrieves current status and metadata for a batch owned by the authenticated site."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Batch details retrieved successfully",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BatchResponseDto.class)
                    )
            ),
            @ApiResponse(responseCode = "403", description = "Forbidden - Unauthorized"),
            @ApiResponse(responseCode = "404", description = "Not Found - Batch not found")
    })
    public ResponseEntity<?> getBatch(@PathVariable("id") UUID batchId) {
        try {
            logger.debug("Device API: Getting batch - batchId={}", batchId);

            Batch batch = batchLifecycleService.getBatch(batchId);
            authorizationHelper.verifySiteOwnership(batch.getSiteId());

            BatchResponseDto response = BatchResponseDto.fromEntity(batch);
            return ResponseEntity.ok(response);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Device API: Unauthorized batch get - batchId={}, {}", batchId, e.getMessage());
            return DeviceControllerHelper.handleUnauthorizedException(e);

        } catch (BatchLifecycleService.BatchNotFoundException e) {
            logger.warn("Device API: Batch not found - batchId={}", batchId);
            return DeviceControllerHelper.handleBatchNotFoundException(e);

        } catch (Exception e) {
            logger.error("Device API: Error getting batch - batchId={}", batchId, e);
            return DeviceControllerHelper.handleInternalServerError("Failed to get batch");
        }
    }
}
