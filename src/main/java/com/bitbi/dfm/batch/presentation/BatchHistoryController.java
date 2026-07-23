package com.bitbi.dfm.batch.presentation;

import com.bitbi.dfm.batch.application.BatchHistoryService;
import com.bitbi.dfm.batch.domain.exception.UnauthorizedBatchAccessException;
import com.bitbi.dfm.batch.presentation.dto.*;
import com.bitbi.dfm.error.application.ErrorLoggingService;
import com.bitbi.dfm.error.presentation.dto.ErrorLogSummaryDto;
import com.bitbi.dfm.shared.api.ApiRoutes;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import com.bitbi.dfm.shared.presentation.dto.PageResponseDto;
import com.bitbi.dfm.upload.application.ExcelExportService;
import com.bitbi.dfm.upload.application.FileDownloadService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller for upload history and file download operations.
 * <p>
 * The single controller for /api/v1/history/batches — serves every request shape regardless
 * of the Accept header (previously a second controller with produces=application/json shadowed
 * these routes and Accept-based routing caused diverging error handling).
 * </p>
 * <p>
 * Provides endpoints for:
 * - Viewing upload history (User Story 1)
 * - Viewing batch details and file list (User Story 2)
 * - Downloading files (User Story 3)
 * </p>
 * <p>
 * Account resolution uses {@link AuthorizationHelper} (accountId claim → sub UUID → email/
 * preferred_username lookup). Domain exceptions (BatchNotFoundException,
 * UnauthorizedBatchAccessException, AuthorizationHelper.UnauthorizedException) propagate to
 * GlobalExceptionHandler, which renders the standard ErrorResponseDto.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 * @since User Story 1 (Phase 3)
 */
@RestController
@RequestMapping(ApiRoutes.HISTORY_BATCHES)
@Tag(name = "UI/Admin API - Upload History", description = "View upload history and download files for authenticated users")
@SecurityRequirement(name = "oauth2")
public class BatchHistoryController {

    private static final Logger logger = LoggerFactory.getLogger(BatchHistoryController.class);

    private final BatchHistoryService batchHistoryService;
    private final FileDownloadService fileDownloadService;
    private final ExcelExportService excelExportService;
    private final ErrorLoggingService errorLoggingService;
    private final AuthorizationHelper authorizationHelper;
    private final MeterRegistry meterRegistry;

    public BatchHistoryController(
            BatchHistoryService batchHistoryService,
            FileDownloadService fileDownloadService,
            ExcelExportService excelExportService,
            ErrorLoggingService errorLoggingService,
            AuthorizationHelper authorizationHelper,
            MeterRegistry meterRegistry
    ) {
        this.batchHistoryService = batchHistoryService;
        this.fileDownloadService = fileDownloadService;
        this.excelExportService = excelExportService;
        this.errorLoggingService = errorLoggingService;
        this.authorizationHelper = authorizationHelper;
        this.meterRegistry = meterRegistry;
    }

    /**
     * List upload history with cursor-based pagination.
     * <p>
     * GET /api/v1/history/batches?cursor={cursor}&limit={limit}
     * </p>
     * <p>
     * Users without a linked Account (pure Auth0 users, e.g. admins) get an empty page
     * instead of an error. Invalid limits fall back to the service default (20).
     * </p>
     *
     * @param cursor Pagination cursor (optional, null for first page)
     * @param limit  Page size (default: 20, max: 100)
     * @return Paginated list of batch summaries
     */
    @GetMapping
    @Operation(
            summary = "List upload history",
            description = "Get paginated list of user's upload sessions with cursor-based pagination. " +
                    "Users without a linked account receive an empty page."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Upload history retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content)
    })
    public ResponseEntity<CursorPageResponseDto<BatchSummaryDto>> listBatches(
            @Parameter(description = "Pagination cursor (null for first page)")
            @RequestParam(required = false) String cursor,

            @Parameter(description = "Page size (default: 20, max: 100)")
            @RequestParam(required = false) Integer limit
    ) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Optional<UUID> accountId = authorizationHelper.getOptionalAuthenticatedAccountId();

            if (accountId.isEmpty()) {
                // Pure Auth0 users (e.g. admins) have no Account record — degrade to an empty page
                logger.debug("No account linked to authenticated user, returning empty batch history");
                return ResponseEntity.ok(CursorPageResponseDto.empty());
            }

            logger.debug("Listing batches for accountId={}, cursor={}, limit={}", accountId.get(), cursor, limit);

            return ResponseEntity.ok(batchHistoryService.listBatchHistory(accountId.get(), cursor, limit));
        } finally {
            sample.stop(meterRegistry.timer("batch.history.user.list"));
        }
    }

    /**
     * Get batch details with file list.
     * <p>
     * GET /api/v1/history/batches/{batchId}
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
            @ApiResponse(responseCode = "401", description = "Not authenticated or no linked account"),
            @ApiResponse(responseCode = "403", description = "Batch does not belong to user"),
            @ApiResponse(responseCode = "404", description = "Batch not found")
    })
    public ResponseEntity<BatchDetailDto> getBatchDetails(
            @Parameter(description = "Batch ID", required = true)
            @PathVariable UUID batchId
    ) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            UUID accountId = authorizationHelper.getAuthenticatedAccountId();

            logger.debug("Getting batch details: batchId={}, accountId={}", batchId, accountId);

            return ResponseEntity.ok(batchHistoryService.getBatchDetails(batchId, accountId));
        } finally {
            sample.stop(meterRegistry.timer("batch.details.user.load"));
        }
    }

    /**
     * Get presigned URL for single file download.
     * <p>
     * GET /api/v1/history/batches/{batchId}/files/{fileId}/download
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
     * POST /api/v1/history/batches/{batchId}/download-zip
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
     * Export selected CSV files to Excel workbook (.xlsx).
     * <p>
     * POST /api/v1/history/batches/{batchId}/export-excel
     * </p>
     * <p>
     * T092: Generates Excel workbook where each CSV becomes a separate sheet.
     * Business rules:
     * - Batch must belong to user's account
     * - Batch must be COMPLETED (403 Forbidden for IN_PROGRESS batches)
     * - All file IDs must belong to the specified batch
     * - Automatically decompresses .csv.gz files
     * - Auto-detects CSV encoding (UTF-8, Windows-1252, ISO-8859-1)
     * - Sheet names limited to 31 characters with invalid char replacement
     * - Duplicate sheet names get numeric suffix (e.g., "data (2)")
     * </p>
     *
     * @param batchId  Batch ID
     * @param request  List of file IDs to export
     * @param response HTTP response for streaming Excel file
     */
    @PostMapping("/{batchId}/export-excel")
    @Operation(
            summary = "Generate Excel file from selected CSV files",
            description = "Generates .xlsx Excel workbook where each CSV becomes a separate sheet. " +
                    "Streams directly with SXSSF (memory efficient). Only available for COMPLETED batches. " +
                    "Automatically handles .csv.gz decompression and encoding detection."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Excel workbook generated successfully",
                    content = @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            ),
            @ApiResponse(responseCode = "400", description = "Invalid fileIds or CSV parsing error"),
            @ApiResponse(responseCode = "403", description = "Batch not completed or unauthorized access"),
            @ApiResponse(responseCode = "404", description = "Batch or files not found")
    })
    public void exportToExcel(
            @Parameter(description = "Batch ID", required = true)
            @PathVariable UUID batchId,

            @RequestBody ExportExcelRequestDto request,
            HttpServletResponse response
    ) {
        try {
            // Get authenticated account ID
            UUID accountId = authorizationHelper.getAuthenticatedAccountId();

            logger.info("Starting Excel export: batchId={}, fileCount={}, accountId={}",
                    batchId, request.fileIds().size(), accountId);

            excelExportService.exportToExcel(batchId, request.fileIds(), accountId, response);

            logger.info("Excel export completed successfully: batchId={}", batchId);

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Unauthorized Excel export: {}", e.getMessage());
            sendErrorResponse(response, HttpStatus.FORBIDDEN, "Unauthorized access");

        } catch (UnauthorizedBatchAccessException e) {
            logger.warn("Batch access denied for Excel export: batchId={}, {}", batchId, e.getMessage());
            sendErrorResponse(response, HttpStatus.FORBIDDEN, "Batch does not belong to user");

        } catch (IllegalStateException e) {
            logger.warn("Excel export on non-completed batch: batchId={}, {}", batchId, e.getMessage());
            sendErrorResponse(response, HttpStatus.FORBIDDEN, e.getMessage());

        } catch (IllegalArgumentException e) {
            logger.warn("Invalid Excel export request: batchId={}, {}", batchId, e.getMessage());
            sendErrorResponse(response, HttpStatus.BAD_REQUEST, e.getMessage());

        } catch (ExcelExportService.ExcelExportException e) {
            logger.error("Excel export failed: batchId={}, {}", batchId, e.getMessage(), e);
            sendErrorResponse(response, HttpStatus.BAD_REQUEST,
                    "Failed to generate Excel: " + e.getCause().getMessage());

        } catch (Exception e) {
            logger.error("Unexpected error during Excel export: batchId={}", batchId, e);
            sendErrorResponse(response, HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to generate Excel workbook");
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
     * DTO for Excel export request.
     */
    @Schema(description = "Request body for Excel export")
    public record ExportExcelRequestDto(
            @Schema(description = "List of file IDs to export as Excel sheets (must belong to batch)", required = true)
            List<UUID> fileIds
    ) {
    }

    // ============================================================================
    // Phase 7: Error Details View (Supporting P1)
    // ============================================================================

    /**
     * Get errors for batch with pagination (Phase 7 - T104).
     * <p>
     * GET /api/v1/history/batches/{batchId}/errors?page={page}&size={size}
     * </p>
     *
     * @param batchId Batch identifier
     * @param page    Page number (0-indexed, default: 0)
     * @param size    Page size (default: 20, max: 100)
     * @return Paginated list of error summaries
     */
    @GetMapping("/{batchId}/errors")
    @Operation(
            summary = "Get errors for batch",
            description = "Get paginated list of errors for a specific batch"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Errors retrieved successfully"),
            @ApiResponse(responseCode = "403", description = "Batch doesn't belong to user", content = @Content),
            @ApiResponse(responseCode = "404", description = "Batch not found", content = @Content)
    })
    public ResponseEntity<?> getBatchErrors(
            @Parameter(description = "Batch identifier")
            @PathVariable UUID batchId,

            @Parameter(description = "Page number (0-indexed)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Page size (max: 100)")
            @RequestParam(defaultValue = "20") int size
    ) {
        try {
            // Get authenticated account ID
            UUID accountId = authorizationHelper.getAuthenticatedAccountId();

            logger.debug("Getting errors for batch: batchId={}, accountId={}, page={}, size={}",
                        batchId, accountId, page, size);

            // Validate page size
            if (size > 100) {
                size = 100;
                logger.warn("Page size limited to 100 (requested: {})", size);
            }

            // Get paginated errors with authorization check
            PageResponseDto<ErrorLogSummaryDto> errors = errorLoggingService.getBatchErrors(
                    batchId,
                    accountId,
                    page,
                    size
            );

            logger.info("Retrieved {} errors for batch: batchId={}, page={}/{}",
                       errors.content().size(), batchId, page, errors.totalPages());

            return ResponseEntity.ok(errors);

        } catch (ErrorLoggingService.UnauthorizedBatchAccessException e) {
            logger.warn("Unauthorized access to batch errors: batchId={}", batchId, e);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(createErrorResponse(HttpStatus.FORBIDDEN, "Batch does not belong to user"));

        } catch (ErrorLoggingService.BatchNotFoundException e) {
            logger.warn("Batch not found: batchId={}", batchId, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse(HttpStatus.NOT_FOUND, "Batch not found"));

        } catch (Exception e) {
            logger.error("Failed to get batch errors: batchId={}", batchId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                            "Failed to retrieve errors: " + e.getMessage()));
        }
    }

    // ============================================================================
    // Helper Methods
    // ============================================================================

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
