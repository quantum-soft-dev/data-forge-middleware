package com.bitbi.dfm.batch.presentation;

import com.bitbi.dfm.batch.application.BatchHistoryService;
import com.bitbi.dfm.batch.domain.exception.BatchNotFoundException;
import com.bitbi.dfm.batch.domain.exception.UnauthorizedBatchAccessException;
import com.bitbi.dfm.batch.presentation.dto.*;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import com.bitbi.dfm.upload.application.FileDownloadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for upload history and file download operations.
 * <p>
 * Provides endpoints for:
 * - Viewing upload history (User Story 1)
 * - Viewing batch details and file list (User Story 2)
 * - Downloading files (User Story 3)
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 * @since User Story 1 (Phase 3)
 */
@RestController
@RequestMapping("/api/dfc/batches")
@Tag(name = "Upload History", description = "View upload history and download files")
@SecurityRequirement(name = "bearerAuth")
public class BatchHistoryController {

    private static final Logger logger = LoggerFactory.getLogger(BatchHistoryController.class);

    private final BatchHistoryService batchHistoryService;
    private final FileDownloadService fileDownloadService;
    private final AuthorizationHelper authorizationHelper;

    public BatchHistoryController(
            BatchHistoryService batchHistoryService,
            FileDownloadService fileDownloadService,
            AuthorizationHelper authorizationHelper
    ) {
        this.batchHistoryService = batchHistoryService;
        this.fileDownloadService = fileDownloadService;
        this.authorizationHelper = authorizationHelper;
    }

    /**
     * List upload history with cursor-based pagination.
     * <p>
     * GET /api/dfc/batches?cursor={cursor}&limit={limit}
     * </p>
     *
     * @param cursor Pagination cursor (optional, null for first page)
     * @param limit  Page size (default: 20, max: 100)
     * @return Paginated list of batch summaries
     */
    @GetMapping
    @Operation(
            summary = "List upload history",
            description = "Get paginated list of user's upload sessions with cursor-based pagination"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Upload history retrieved successfully"),
            @ApiResponse(responseCode = "403", description = "Unauthorized access", content = @Content)
    })
    public ResponseEntity<CursorPageResponseDto<BatchSummaryDto>> listBatches(
            @Parameter(description = "Pagination cursor (null for first page)")
            @RequestParam(required = false) String cursor,

            @Parameter(description = "Page size (default: 20, max: 100)")
            @RequestParam(defaultValue = "20") int limit
    ) {
        try {
            // Get authenticated account ID
            UUID accountId = authorizationHelper.getAuthenticatedAccountId();

            logger.debug("Listing batches for accountId={}, cursor={}, limit={}", accountId, cursor, limit);

            // Validate limit
            if (limit < 1 || limit > 100) {
                limit = 20; // Default
            }

            CursorPageResponseDto<BatchSummaryDto> response =
                    batchHistoryService.listBatchHistory(accountId, cursor, limit);

            return ResponseEntity.ok(response);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Unauthorized upload history access: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (Exception e) {
            logger.error("Failed to list batches", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get batch details with file list.
     * <p>
     * GET /api/dfc/batches/{batchId}
     * </p>
     *
     * @param batchId Batch ID
     * @return Batch details with file list
     */
    @GetMapping("/{batchId}")
    @Operation(
            summary = "Get batch details",
            description = "Get detailed information about a specific upload session including file list"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Batch details retrieved successfully"),
            @ApiResponse(responseCode = "403", description = "Batch does not belong to user"),
            @ApiResponse(responseCode = "404", description = "Batch not found")
    })
    public ResponseEntity<?> getBatchDetails(
            @Parameter(description = "Batch ID", required = true)
            @PathVariable UUID batchId
    ) {
        try {
            // Get authenticated account ID
            UUID accountId = authorizationHelper.getAuthenticatedAccountId();

            logger.debug("Getting batch details: batchId={}, accountId={}", batchId, accountId);

            BatchDetailDto response = batchHistoryService.getBatchDetails(batchId, accountId);

            return ResponseEntity.ok(response);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Unauthorized batch details access: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        } catch (UnauthorizedBatchAccessException e) {
            logger.warn("Batch access denied: batchId={}, {}", batchId, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(createErrorResponse(HttpStatus.FORBIDDEN, e.getMessage()));

        } catch (BatchNotFoundException e) {
            logger.warn("Batch not found: {}", batchId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse(HttpStatus.NOT_FOUND, "Batch not found"));

        } catch (Exception e) {
            logger.error("Failed to get batch details: batchId={}", batchId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to retrieve batch details"));
        }
    }

    /**
     * Get presigned URL for single file download.
     * <p>
     * GET /api/dfc/batches/{batchId}/files/{fileId}/download
     * </p>
     * <p>
     * T068: Returns FileDownloadResponseDto with S3 presigned URL (15-minute expiry).
     * Business rules:
     * - Batch must belong to user's account
     * - Batch must be COMPLETED (403 Forbidden for IN_PROGRESS batches)
     * </p>
     *
     * @param batchId Batch ID
     * @param fileId  File ID
     * @return Presigned download URL and metadata
     */
    @GetMapping("/{batchId}/files/{fileId}/download")
    @Operation(
            summary = "Get file download URL",
            description = "Get presigned S3 URL for direct file download (15-minute expiry). Only available for COMPLETED batches."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Presigned URL generated successfully",
                    content = @Content(schema = @Schema(implementation = FileDownloadResponseDto.class))
            ),
            @ApiResponse(responseCode = "403", description = "Batch not completed or unauthorized access"),
            @ApiResponse(responseCode = "404", description = "Batch or file not found")
    })
    public ResponseEntity<?> downloadFile(
            @Parameter(description = "Batch ID", required = true)
            @PathVariable UUID batchId,

            @Parameter(description = "File ID", required = true)
            @PathVariable UUID fileId
    ) {
        try {
            // Get authenticated account ID
            UUID accountId = authorizationHelper.getAuthenticatedAccountId();

            logger.info("Generating download URL: batchId={}, fileId={}, accountId={}", batchId, fileId, accountId);

            FileDownloadResponseDto response =
                    fileDownloadService.getPresignedUrlForFile(batchId, fileId, accountId);

            return ResponseEntity.ok(response);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Unauthorized file download: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(createErrorResponse(HttpStatus.FORBIDDEN, "Unauthorized access"));

        } catch (UnauthorizedBatchAccessException e) {
            logger.warn("Batch access denied for download: batchId={}, {}", batchId, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(createErrorResponse(HttpStatus.FORBIDDEN, "Batch does not belong to user"));

        } catch (FileDownloadService.BatchNotCompletedException e) {
            logger.warn("Download attempted on non-completed batch: batchId={}", batchId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(createErrorResponse(HttpStatus.FORBIDDEN, e.getMessage()));

        } catch (FileDownloadService.BatchNotFoundException e) {
            logger.warn("Batch not found for download: {}", batchId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse(HttpStatus.NOT_FOUND, "Batch not found"));

        } catch (FileDownloadService.FileNotFoundException e) {
            logger.warn("File not found for download: {}", fileId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse(HttpStatus.NOT_FOUND, "File not found"));

        } catch (Exception e) {
            logger.error("Failed to generate download URL: batchId={}, fileId={}", batchId, fileId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to generate download URL"));
        }
    }

    /**
     * Download multiple files as ZIP archive.
     * <p>
     * POST /api/dfc/batches/{batchId}/download-zip
     * </p>
     * <p>
     * T069: Streams files directly from S3 as ZIP archive.
     * Business rules:
     * - Batch must belong to user's account
     * - Batch must be COMPLETED (403 Forbidden for IN_PROGRESS batches)
     * - All file IDs must belong to the specified batch
     * </p>
     *
     * @param batchId  Batch ID
     * @param request  List of file IDs to include in ZIP
     * @param response HTTP response for streaming ZIP
     */
    @PostMapping("/{batchId}/download-zip")
    @Operation(
            summary = "Download files as ZIP",
            description = "Download multiple files as ZIP archive. Streams directly from S3 (memory efficient). Only available for COMPLETED batches."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "ZIP archive streamed successfully",
                    content = @Content(mediaType = "application/zip")
            ),
            @ApiResponse(responseCode = "403", description = "Batch not completed or unauthorized access"),
            @ApiResponse(responseCode = "404", description = "Batch or files not found")
    })
    public void downloadFilesAsZip(
            @Parameter(description = "Batch ID", required = true)
            @PathVariable UUID batchId,

            @RequestBody DownloadZipRequestDto request,
            HttpServletResponse response
    ) {
        try {
            // Get authenticated account ID
            UUID accountId = authorizationHelper.getAuthenticatedAccountId();

            logger.info("Starting ZIP download: batchId={}, fileCount={}, accountId={}",
                    batchId, request.fileIds().size(), accountId);

            fileDownloadService.downloadFilesAsZip(batchId, request.fileIds(), accountId, response);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Unauthorized ZIP download: {}", e.getMessage());
            sendErrorResponse(response, HttpStatus.FORBIDDEN, "Unauthorized access");

        } catch (UnauthorizedBatchAccessException e) {
            logger.warn("Batch access denied for ZIP download: batchId={}, {}", batchId, e.getMessage());
            sendErrorResponse(response, HttpStatus.FORBIDDEN, "Batch does not belong to user");

        } catch (FileDownloadService.BatchNotCompletedException e) {
            logger.warn("ZIP download attempted on non-completed batch: batchId={}", batchId);
            sendErrorResponse(response, HttpStatus.FORBIDDEN, e.getMessage());

        } catch (FileDownloadService.BatchNotFoundException e) {
            logger.warn("Batch not found for ZIP download: {}", batchId);
            sendErrorResponse(response, HttpStatus.NOT_FOUND, "Batch not found");

        } catch (FileDownloadService.FileNotFoundException e) {
            logger.warn("Files not found for ZIP download: batchId={}", batchId);
            sendErrorResponse(response, HttpStatus.NOT_FOUND, "Files not found");

        } catch (IOException e) {
            logger.error("Failed to stream ZIP: batchId={}", batchId, e);
            sendErrorResponse(response, HttpStatus.INTERNAL_SERVER_ERROR, "Failed to generate ZIP archive");

        } catch (Exception e) {
            logger.error("Unexpected error during ZIP download: batchId={}", batchId, e);
            sendErrorResponse(response, HttpStatus.INTERNAL_SERVER_ERROR, "Failed to download files");
        }
    }

    /**
     * DTO for ZIP download request.
     */
    @Schema(description = "Request body for ZIP download")
    public record DownloadZipRequestDto(
            @Schema(description = "List of file IDs to include in ZIP", required = true)
            List<UUID> fileIds
    ) {
    }

    /**
     * Create error response map.
     */
    private Map<String, Object> createErrorResponse(HttpStatus status, String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("status", status.value());
        error.put("error", status.getReasonPhrase());
        error.put("message", message);
        return error;
    }

    /**
     * Send error response for streaming endpoints.
     */
    private void sendErrorResponse(HttpServletResponse response, HttpStatus status, String message) {
        try {
            response.setStatus(status.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"status\":" + status.value() +
                    ",\"error\":\"" + status.getReasonPhrase() + "\"," +
                    "\"message\":\"" + message + "\"}");
            response.getWriter().flush();
        } catch (IOException e) {
            logger.error("Failed to send error response", e);
        }
    }
}
