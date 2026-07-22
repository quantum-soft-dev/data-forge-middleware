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
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Device API File Management Controller.
 * <p>
 * Provides file upload and metadata operations for device clients.
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
@Tag(name = "Device API - File Management", description = "File upload and metadata operations for device clients")
@SecurityRequirement(name = "bearerAuth")
public class DeviceFileController {

    private static final Logger logger = LoggerFactory.getLogger(DeviceFileController.class);

    private final FileUploadService fileUploadService;
    private final BatchLifecycleService batchLifecycleService;
    private final AuthorizationHelper authorizationHelper;
    private final com.bitbi.dfm.site.application.ClientApiVersionGuard clientApiVersionGuard;

    /**
     * Constructor injection for dependencies.
     *
     * @param fileUploadService     Service for file upload operations
     * @param batchLifecycleService Service for batch operations
     * @param authorizationHelper   Helper for JWT-based authorization
     * @param clientApiVersionGuard Per-site strangler guard for the HTTP file API
     */
    public DeviceFileController(
            FileUploadService fileUploadService,
            BatchLifecycleService batchLifecycleService,
            AuthorizationHelper authorizationHelper,
            com.bitbi.dfm.site.application.ClientApiVersionGuard clientApiVersionGuard) {
        this.fileUploadService = fileUploadService;
        this.batchLifecycleService = batchLifecycleService;
        this.authorizationHelper = authorizationHelper;
        this.clientApiVersionGuard = clientApiVersionGuard;
    }

    /**
     * Upload file to batch.
     * <p>
     * Uploads one or more files to an IN_PROGRESS batch owned by the authenticated device client.
     * Files are stored in S3 and metadata is saved to PostgreSQL.
     * </p>
     *
     * @param batchId Batch identifier (path variable)
     * @param files   Multipart files to upload (form parameter "files")
     * @return 201 Created with FileUploadResponseDto, or error response
     */
    @PostMapping("/batches/{batchId}/upload")
    @Operation(
            summary = "Upload file to batch",
            description = "Uploads files to an IN_PROGRESS batch. The authenticated site must own the batch."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "File uploaded successfully",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = FileUploadResponseDto.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Bad Request - Missing file or invalid file format",
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
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Conflict - Batch not in IN_PROGRESS status",
                    content = @Content(mediaType = "application/json")
            )
    })
    public ResponseEntity<?> uploadFile(
            @PathVariable("batchId") UUID batchId,
            @RequestParam(value = "files", required = false) MultipartFile[] files) {
        try {
            // Validate file parameter
            if (files == null || files.length == 0) {
                logger.warn("Device API: File upload attempt with no files - batchId={}", batchId);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(DeviceControllerHelper.createErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request",
                                "No file provided. Please include 'files' parameter."));
            }

            logger.info("Device API: Uploading {} files - batchId={}", files.length, batchId);

            // Get batch first to verify ownership and status
            Batch batch = batchLifecycleService.getBatch(batchId);

            // Verify site ownership via JWT claims
            authorizationHelper.verifySiteOwnership(batch.getSiteId());

            clientApiVersionGuard.assertHttpFileApiAllowed(batch.getSiteId());

            // Verify batch is IN_PROGRESS
            if (!batch.getStatus().equals(com.bitbi.dfm.batch.domain.BatchStatus.IN_PROGRESS)) {
                logger.warn("Device API: Cannot upload to non-IN_PROGRESS batch - batchId={}, status={}",
                        batchId, batch.getStatus());
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(DeviceControllerHelper.createErrorResponse(HttpStatus.CONFLICT, "Conflict",
                                "Cannot upload files to batch with status: " + batch.getStatus()));
            }

            // Upload first file (matching legacy behavior)
            UploadedFile uploadedFile = fileUploadService.uploadFile(batchId, files[0]);

            FileUploadResponseDto response = FileUploadResponseDto.fromEntity(uploadedFile);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Device API: Unauthorized file upload - batchId={}, {}", batchId, e.getMessage());
            return DeviceControllerHelper.handleUnauthorizedException(e);

        } catch (com.bitbi.dfm.site.application.ClientApiVersionGuard.HttpFileApiDisabledException e) {
            logger.warn("Device API: HTTP file API disabled for V2 site - {}", e.getMessage());
            return DeviceControllerHelper.handleHttpFileApiDisabledException(e);

        } catch (BatchLifecycleService.BatchNotFoundException e) {
            logger.warn("Device API: Batch not found - batchId={}", batchId);
            return DeviceControllerHelper.handleBatchNotFoundException(e);

        } catch (Exception e) {
            logger.error("Device API: Error uploading file - batchId={}", batchId, e);
            return DeviceControllerHelper.handleInternalServerError("Failed to upload file");
        }
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
