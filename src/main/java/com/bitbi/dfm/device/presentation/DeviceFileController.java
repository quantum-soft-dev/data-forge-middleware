package com.bitbi.dfm.device.presentation;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.shared.api.ApiRoutes;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import com.bitbi.dfm.shared.presentation.DeviceControllerHelper;
import com.bitbi.dfm.upload.application.FileUploadService;
import com.bitbi.dfm.upload.domain.UploadedFile;
import com.bitbi.dfm.upload.presentation.dto.FileUploadResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Device API File Management Controller.
 * <p>
 * Provides file metadata operations for device clients. Delta ingestion itself uses gRPC.
 * </p>
 * <p>
 * <b>Authentication</b>: Custom JWT (obtained from Device Auth endpoint)<br>
 * <b>Authorization</b>: Batch ownership verified for all operations
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 * @see com.bitbi.dfm.upload.application.FileUploadService
 * @see <a href="specs/010-api-unification-goal/spec.md">API Unification Specification</a>
 */
@RestController
@RequestMapping(ApiRoutes.DEVICE_FILES)
@Tag(name = "Device API - File Management", description = "File metadata operations for device clients")
@SecurityRequirement(name = "bearerAuth")
public class DeviceFileController {

    private static final Logger logger = LoggerFactory.getLogger(DeviceFileController.class);

    private final FileUploadService fileUploadService;
    private final BatchLifecycleService batchLifecycleService;
    private final AuthorizationHelper authorizationHelper;

    /**
     * Constructor injection for dependencies.
     *
     * @param fileUploadService     Service for file metadata operations
     * @param batchLifecycleService Service for batch operations
     * @param authorizationHelper   Helper for JWT-based authorization
     */
    public DeviceFileController(
            FileUploadService fileUploadService,
            BatchLifecycleService batchLifecycleService,
            AuthorizationHelper authorizationHelper) {
        this.fileUploadService = fileUploadService;
        this.batchLifecycleService = batchLifecycleService;
        this.authorizationHelper = authorizationHelper;
    }

    /**
     * Get file metadata.
     * <p>
     * Retrieves metadata for an uploaded file. The file must belong to a batch
     * owned by the authenticated device client.
     * </p>
     *
     * @param batchId Batch identifier (path variable)
     * @param fileId  File identifier (path variable)
     * @return 200 OK with FileMetadataDto, or error response
     */
    @GetMapping("/batches/{batchId}/files/{fileId}")
    @Operation(
            summary = "Get file metadata",
            description = "Retrieves metadata for an uploaded file owned by the authenticated site."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "File metadata retrieved successfully",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = FileUploadResponseDto.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Forbidden - File not owned by authenticated site",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Not Found - File does not exist",
                    content = @Content(mediaType = "application/json")
            )
    })
    public ResponseEntity<?> getFileMetadata(
            @PathVariable("batchId") UUID batchId,
            @PathVariable("fileId") UUID fileId) {
        try {
            logger.debug("Device API: Getting file metadata - batchId={}, fileId={}", batchId, fileId);

            // Get batch first to verify ownership
            Batch batch = batchLifecycleService.getBatch(batchId);

            // Verify site ownership via JWT claims
            authorizationHelper.verifySiteOwnership(batch.getSiteId());

            // Get file metadata
            UploadedFile file = fileUploadService.getFile(fileId);

            // Verify file belongs to this batch
            if (!file.getBatchId().equals(batchId)) {
                logger.warn("Device API: File does not belong to batch - fileId={}, batchId={}, fileBatchId={}",
                        fileId, batchId, file.getBatchId());
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(DeviceControllerHelper.createErrorResponse(HttpStatus.NOT_FOUND, "Not Found",
                                "File does not belong to this batch"));
            }

            FileUploadResponseDto response = FileUploadResponseDto.fromEntity(file);
            return ResponseEntity.ok(response);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Device API: Unauthorized file get - batchId={}, fileId={}, {}",
                    batchId, fileId, e.getMessage());
            return DeviceControllerHelper.handleUnauthorizedException(e);

        } catch (BatchLifecycleService.BatchNotFoundException e) {
            logger.warn("Device API: Batch not found - batchId={}", batchId);
            return DeviceControllerHelper.handleBatchNotFoundException(e);

        } catch (FileUploadService.FileNotFoundException e) {
            logger.warn("Device API: File not found - fileId={}", fileId);
            return DeviceControllerHelper.handleFileNotFoundException(e);

        } catch (Exception e) {
            logger.error("Device API: Error getting file metadata - batchId={}, fileId={}", batchId, fileId, e);
            return DeviceControllerHelper.handleInternalServerError("Failed to get file metadata");
        }
    }
}
