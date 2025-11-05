package com.bitbi.dfm.comparison.presentation;

import com.bitbi.dfm.comparison.application.ComparisonDownloadService;
import com.bitbi.dfm.comparison.application.ComparisonQueryService;
import com.bitbi.dfm.comparison.application.ComparisonService;
import com.bitbi.dfm.comparison.domain.ChangeType;
import com.bitbi.dfm.comparison.domain.ComparisonResult;
import com.bitbi.dfm.comparison.domain.ComparisonStatus;
import com.bitbi.dfm.comparison.domain.FileComparison;
import com.bitbi.dfm.comparison.presentation.dto.ComparisonResponseDto;
import com.bitbi.dfm.comparison.presentation.dto.ComparisonResultDto;
import com.bitbi.dfm.comparison.presentation.dto.ComparisonSummaryDto;
import com.bitbi.dfm.comparison.presentation.dto.CreateComparisonRequestDto;
import com.bitbi.dfm.comparison.presentation.dto.PagedComparisonResponse;
import com.bitbi.dfm.comparison.presentation.dto.PagedComparisonResultResponse;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import com.bitbi.dfm.upload.domain.UploadedFile;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST controller for file comparison operations.
 *
 * <p>This controller handles comparison requests between upload sessions,
 * enabling users to track changes across different file uploads.
 *
 * <p>Phase 3 (User Story 1) implementation: POST /api/v1/comparisons endpoint.
 *
 * @see ComparisonService
 * @see CreateComparisonRequestDto
 * @see ComparisonResponseDto
 */
@RestController
@RequestMapping("/api/v1/comparisons")
@Tag(name = "Comparisons", description = "File comparison operations")
@SecurityRequirement(name = "bearerAuth")
public class ComparisonController {

    private static final Logger log = LoggerFactory.getLogger(ComparisonController.class);

    private final ComparisonService comparisonService;
    private final ComparisonQueryService comparisonQueryService;
    private final ComparisonDownloadService comparisonDownloadService;
    private final AuthorizationHelper authorizationHelper;
    private final UploadedFileRepository uploadedFileRepository;

    public ComparisonController(
        ComparisonService comparisonService,
        ComparisonQueryService comparisonQueryService,
        ComparisonDownloadService comparisonDownloadService,
        AuthorizationHelper authorizationHelper,
        UploadedFileRepository uploadedFileRepository
    ) {
        this.comparisonService = comparisonService;
        this.comparisonQueryService = comparisonQueryService;
        this.comparisonDownloadService = comparisonDownloadService;
        this.authorizationHelper = authorizationHelper;
        this.uploadedFileRepository = uploadedFileRepository;
    }

    /**
     * Creates a new file comparison between two upload sessions.
     *
     * <p>T039: POST /api/v1/comparisons endpoint implementation.
     *
     * <p>This endpoint initiates a comparison between files in the current batch
     * and the target batch. The user must own both batches (verified via JWT accountId).
     *
     * @param request the comparison request DTO
     * @param authentication the authentication object (Spring Security OAuth2 JWT)
     * @return 201 Created with ComparisonResponseDto
     * @throws IllegalArgumentException if validation fails (400 Bad Request)
     * @throws ComparisonService.BatchNotFoundException if batch not found (400 Bad Request)
     * @throws ComparisonService.BatchNotCompletedException if batch not completed (400 Bad Request)
     * @throws ComparisonService.UnauthorizedAccessException if user doesn't own batch (403 Forbidden)
     */
    @PostMapping
    @Operation(
        summary = "Create a new file comparison",
        description = "Initiates a comparison between files in two upload sessions (batches). " +
            "The user must own both batches. Both batches must be in COMPLETED status."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "201",
            description = "Comparison created successfully",
            content = @Content(schema = @Schema(implementation = ComparisonResponseDto.class))
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Bad request (invalid input, batches incomplete, etc.)",
            content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden (user does not own one or both batches)",
            content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized (invalid or missing JWT token)",
            content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))
        )
    })
    public ResponseEntity<ComparisonResponseDto> createComparison(
        @Valid @RequestBody CreateComparisonRequestDto request,
        Authentication authentication
    ) {
        // T039: Extract accountId from JWT
        UUID accountId = extractAccountIdFromJwt(authentication);

        log.info("POST /api/v1/comparisons - Creating comparison for account={}, currentBatch={}, targetBatch={}",
            accountId, request.currentBatchId(), request.targetBatchId());

        // T039: Validate business rules in DTO
        request.validate();

        // T039: Delegate to service layer
        FileComparison comparison = comparisonService.createComparison(
            request.currentBatchId(),
            request.targetBatchId(),
            accountId,
            request.fileIds()
        );

        // T039: Convert to DTO and return 201 Created
        ComparisonResponseDto response = ComparisonResponseDto.fromDomain(comparison);

        log.info("Comparison created successfully: id={}, status={}", comparison.getId(), comparison.getStatus());

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(response);
    }

    /**
     * Extracts accountId from JWT token using AuthorizationHelper.
     *
     * <p>Delegates to shared helper that handles multiple JWT claim strategies
     * (accountId claim, account_id attribute, subject claim).
     *
     * @param authentication the authentication object (not used - helper extracts from SecurityContext)
     * @return the account ID from JWT
     * @throws UnauthorizedException if JWT is missing or accountId cannot be extracted
     */
    private UUID extractAccountIdFromJwt(Authentication authentication) {
        return authorizationHelper.getAuthenticatedAccountId();
    }

    /**
     * GET /api/v1/comparisons/{comparisonId}
     *
     * <p>Retrieves comparison metadata by ID.
     *
     * @param comparisonId the comparison ID
     * @param authentication the authentication object
     * @return 200 OK with comparison metadata
     */
    @GetMapping("/{comparisonId}")
    @Operation(
        summary = "Get comparison by ID",
        description = "Retrieves comparison metadata including status, batch IDs, and basic statistics."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Comparison retrieved successfully",
            content = @Content(schema = @Schema(implementation = ComparisonResponseDto.class))
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden (user does not own this comparison)"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Comparison not found"
        )
    })
    public ResponseEntity<ComparisonResponseDto> getComparison(
        @Parameter(description = "Comparison ID") @PathVariable Long comparisonId,
        Authentication authentication
    ) {
        UUID accountId = extractAccountIdFromJwt(authentication);

        log.info("GET /api/v1/comparisons/{} - account={}", comparisonId, accountId);

        // Get comparison (without results for better performance)
        FileComparison comparison = comparisonQueryService.findById(comparisonId, accountId);

        // Convert to DTO
        ComparisonResponseDto response = ComparisonResponseDto.fromDomain(comparison);

        log.info("Returning comparison {}: status={}, batches=({} vs {})",
            comparisonId, comparison.getStatus(),
            comparison.getCurrentBatchId(), comparison.getTargetBatchId());

        return ResponseEntity.ok(response);
    }

    /**
     * T059: GET /api/v1/comparisons/{comparisonId}/results
     *
     * <p>Retrieves detailed results for a specific comparison with filtering and pagination.
     *
     * @param comparisonId the comparison ID
     * @param changeType optional filter by change type (ADDED, MODIFIED, UNCHANGED)
     * @param page page number (zero-indexed)
     * @param size page size
     * @param authentication the authentication object
     * @return 200 OK with paginated comparison results
     */
    @GetMapping("/{comparisonId}/results")
    @Operation(
        summary = "Get comparison results",
        description = "Retrieves detailed results for a specific comparison, including diffs for each file. " +
            "Results can be filtered by change type and are paginated."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Comparison results retrieved successfully"
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden (user does not own this comparison)"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Comparison not found"
        )
    })
    public ResponseEntity<PagedComparisonResultResponse> getComparisonResults(
        @Parameter(description = "Comparison ID") @PathVariable Long comparisonId,
        @Parameter(description = "Filter by change type") @RequestParam(required = false) ChangeType changeType,
        @Parameter(description = "Page number (zero-indexed)") @RequestParam(defaultValue = "0") int page,
        @Parameter(description = "Page size") @RequestParam(defaultValue = "50") int size,
        Authentication authentication
    ) {
        UUID accountId = extractAccountIdFromJwt(authentication);

        log.info("GET /api/v1/comparisons/{}/results - account={}, changeType={}, page={}, size={}",
            comparisonId, accountId, changeType, page, size);

        // Get comparison with results eagerly loaded
        FileComparison comparison = comparisonQueryService.findByIdWithResults(comparisonId, accountId);

        // Filter results by change type if specified
        List<ComparisonResult> filteredResults = comparison.getResults().stream()
            .filter(result -> changeType == null || result.getChangeType() == changeType)
            .toList();

        // Manual pagination (since results are already in memory)
        Pageable pageable = PageRequest.of(page, size);
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), filteredResults.size());

        // Collect all file IDs from the current page
        List<UUID> fileIds = filteredResults.subList(start, end).stream()
            .flatMap(result -> {
                var ids = new java.util.ArrayList<UUID>();
                ids.add(result.getFileId());
                if (result.getTargetFileId() != null) {
                    ids.add(result.getTargetFileId());
                }
                return ids.stream();
            })
            .distinct()
            .collect(Collectors.toList());

        // Batch fetch all files for the current page
        java.util.Map<UUID, String> fileNameMap = uploadedFileRepository.findAllById(fileIds).stream()
            .collect(Collectors.toMap(
                UploadedFile::getId,
                UploadedFile::getOriginalFileName
            ));

        // Convert to DTOs with file names
        List<ComparisonResultDto> dtoList = filteredResults.subList(start, end).stream()
            .map(result -> ComparisonResultDto.fromEntityWithFileNames(
                result,
                fileNameMap.get(result.getFileId()),
                result.getTargetFileId() != null ? fileNameMap.get(result.getTargetFileId()) : null
            ))
            .collect(Collectors.toList());

        // Create Page object manually
        Page<ComparisonResultDto> resultPage = new org.springframework.data.domain.PageImpl<>(
            dtoList,
            pageable,
            filteredResults.size()
        );

        // Convert to custom DTO for consistent response structure
        PagedComparisonResultResponse response = PagedComparisonResultResponse.fromPage(resultPage);

        log.info("Returning {} results (total: {}) for comparison {}", dtoList.size(), filteredResults.size(), comparisonId);

        return ResponseEntity.ok(response);
    }

    /**
     * T060: GET /api/v1/comparisons/{comparisonId}/summary
     *
     * <p>Retrieves a summary report for a specific comparison.
     *
     * @param comparisonId the comparison ID
     * @param authentication the authentication object
     * @return 200 OK with comparison summary
     */
    @GetMapping("/{comparisonId}/summary")
    @Operation(
        summary = "Get comparison summary report",
        description = "Retrieves a summary report including statistics and metadata for a specific comparison. " +
            "This is a lightweight alternative to fetching full comparison details."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Comparison summary retrieved successfully",
            content = @Content(schema = @Schema(implementation = ComparisonSummaryDto.class))
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden (user does not own this comparison)"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Comparison not found"
        )
    })
    public ResponseEntity<ComparisonSummaryDto> getComparisonSummary(
        @Parameter(description = "Comparison ID") @PathVariable Long comparisonId,
        Authentication authentication
    ) {
        UUID accountId = extractAccountIdFromJwt(authentication);

        log.info("GET /api/v1/comparisons/{}/summary - account={}", comparisonId, accountId);

        // Get comparison (without results for better performance)
        FileComparison comparison = comparisonQueryService.findById(comparisonId, accountId);

        // T082: Validate that summary is only available for COMPLETED comparisons
        if (comparison.getStatus() != ComparisonStatus.COMPLETED) {
            log.warn("Attempt to get summary for non-completed comparison {}: status={}",
                comparisonId, comparison.getStatus());
            throw new IllegalStateException(
                String.format("Cannot retrieve summary for comparison in status %s. Summary is only available for COMPLETED comparisons.",
                    comparison.getStatus()));
        }

        // Generate summary from comparison aggregate
        ComparisonSummaryDto summary = ComparisonSummaryDto.fromValueObject(comparison.getSummary());

        log.info("Returning summary for comparison {}: {} files, {} changed, {} added, {} unchanged",
            comparisonId,
            summary.totalFilesCompared(),
            summary.filesChanged(),
            summary.filesAdded(),
            summary.filesUnchanged());

        return ResponseEntity.ok(summary);
    }

    /**
     * GET /api/v1/comparisons/by-batch/{batchId}
     *
     * Lists all comparisons for a specific batch (both as current and target).
     *
     * <p>Added 2025-11-03: Allows users to see comparison history on batch detail page.
     * <p>Path changed from /comparisons/batch/{batchId} to /comparisons/by-batch/{batchId}
     * to avoid routing conflicts with /comparisons/{comparisonId}.
     *
     * @param batchId the batch ID to get comparisons for
     * @param authentication the authentication object (Spring Security OAuth2 JWT)
     * @return 200 OK with list of comparisons
     */
    @GetMapping("/by-batch/{batchId}")
    @Operation(
        summary = "Get comparisons for a specific batch",
        description = "Returns all comparisons where the batch is either current or target batch. " +
            "Useful for displaying comparison history on batch detail page."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Comparisons retrieved successfully",
            content = @Content(schema = @Schema(implementation = ComparisonResponseDto.class))
        ),
        @ApiResponse(
            responseCode = "403",
            description = "User does not own the batch"
        )
    })
    public ResponseEntity<List<ComparisonResponseDto>> getComparisonsForBatch(
        @Parameter(description = "Batch ID", required = true)
        @PathVariable UUID batchId,
        Authentication authentication
    ) {
        UUID accountId = extractAccountIdFromJwt(authentication);

        log.info("GET /api/v1/comparisons/by-batch/{} - account={}", batchId, accountId);

        // Get all comparisons for this batch
        List<FileComparison> comparisons = comparisonQueryService.findByBatchId(batchId, accountId);

        // Convert to DTOs
        List<ComparisonResponseDto> response = comparisons.stream()
            .map(ComparisonResponseDto::fromDomain)
            .collect(Collectors.toList());

        log.info("Found {} comparisons for batch {}", response.size(), batchId);

        return ResponseEntity.ok(response);
    }

    /**
     * DELETE /api/v1/comparisons/{comparisonId}
     *
     * Deletes a comparison and all its results.
     *
     * @param comparisonId the comparison ID
     * @param authentication the authentication object
     * @return 204 No Content
     */
    @DeleteMapping("/{comparisonId}")
    @Operation(
        summary = "Delete comparison",
        description = "Deletes a comparison and all its results. The user must own the comparison."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "204",
            description = "Comparison deleted successfully"
        ),
        @ApiResponse(
            responseCode = "403",
            description = "User does not own this comparison"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Comparison not found"
        )
    })
    public ResponseEntity<Void> deleteComparison(
        @Parameter(description = "Comparison ID") @PathVariable Long comparisonId,
        Authentication authentication
    ) {
        UUID accountId = extractAccountIdFromJwt(authentication);

        log.info("DELETE /api/v1/comparisons/{} - account={}", comparisonId, accountId);

        // Get comparison to verify ownership
        FileComparison comparison = comparisonQueryService.findById(comparisonId, accountId);

        // Delete comparison (cascade will delete results)
        comparisonService.deleteComparison(comparisonId, accountId);

        log.info("Deleted comparison {}", comparisonId);

        return ResponseEntity.noContent().build();
    }


    /**
     * Download comparison results as ZIP archive.
     * <p>
     * Generates a ZIP file containing:
     * - summary.txt: Comparison statistics and metadata
     * - Individual .diff files for each comparison result
     * <p>
     * Filename format: comparison-{id}.zip
     *
     * @param id Comparison ID
     * @param authentication JWT authentication
     * @return ZIP file as byte array with appropriate headers
     * @throws IOException if ZIP generation fails
     *
     * Phase 7 (User Story 4) - Task T091
     */
    @GetMapping("/{id}/download")
    @Operation(
        summary = "Download comparison results as ZIP",
        description = "Downloads all comparison results as a ZIP archive containing diff files and summary report"
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "ZIP archive generated successfully",
            content = @Content(mediaType = "application/zip")
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Access denied - user does not own this comparison",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Comparison not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))
        ),
        @ApiResponse(
            responseCode = "500",
            description = "Internal server error during ZIP generation",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))
        )
    })
    public ResponseEntity<byte[]> downloadComparison(
        @Parameter(description = "Comparison ID", required = true, example = "123")
        @PathVariable Long id,
        Authentication authentication
    ) throws IOException {
        log.info("Download request for comparison {}", id);

        // Extract account ID from JWT using helper method
        UUID accountId = extractAccountIdFromJwt(authentication);

        // Generate ZIP archive (includes authorization check)
        byte[] zipBytes = comparisonDownloadService.generateZipArchive(id, accountId);

        // Set response headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentDispositionFormData("attachment", "comparison-" + id + ".zip");
        headers.setContentLength(zipBytes.length);

        log.info("Successfully generated ZIP for comparison {} ({} bytes)", id, zipBytes.length);

        return new ResponseEntity<>(zipBytes, headers, HttpStatus.OK);
    }

    /**
     * T123: GET /api/v1/comparisons - List comparisons with pagination and filtering
     * <p>
     * Phase 10: List Comparisons (Supporting Feature)
     * Priority: P3
     * <p>
     * Returns a paginated list of comparisons for the authenticated user.
     * Results are ordered by created_at DESC (most recent first).
     * Supports optional status filtering.
     *
     * @param page Page number (0-indexed), default 0
     * @param size Page size, default 10
     * @param status Optional status filter (COMPLETED, FAILED, IN_PROGRESS)
     * @param authentication JWT authentication
     * @return Paginated list of comparisons
     */
    @GetMapping
    @Operation(
        summary = "List comparisons",
        description = "Lists all comparisons for the authenticated user with pagination and optional status filtering. Results ordered by created_at DESC."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Comparisons retrieved successfully",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PagedComparisonResponse.class))
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized - invalid or missing JWT token",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))
        ),
        @ApiResponse(
            responseCode = "500",
            description = "Internal server error",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))
        )
    })
    public ResponseEntity<PagedComparisonResponse> listComparisons(
        @Parameter(description = "Page number (0-indexed)", example = "0")
        @RequestParam(defaultValue = "0") int page,

        @Parameter(description = "Page size", example = "10")
        @RequestParam(defaultValue = "10") int size,

        @Parameter(description = "Status filter (optional)", example = "COMPLETED")
        @RequestParam(required = false) ComparisonStatus status,

        Authentication authentication
    ) {
        log.info("List comparisons request: page={}, size={}, status={}", page, size, status);

        // Extract account ID from JWT
        UUID accountId = extractAccountIdFromJwt(authentication);

        // Create pageable with sorting by created_at DESC
        Pageable pageable = PageRequest.of(page, size);

        // Query comparisons with optional status filter
        Page<FileComparison> comparisonsPage;
        if (status != null) {
            comparisonsPage = comparisonQueryService.findByAccountIdAndStatus(accountId, status, pageable);
        } else {
            comparisonsPage = comparisonQueryService.findByAccountId(accountId, pageable);
        }

        // Convert to DTOs
        Page<ComparisonResponseDto> dtoPage = comparisonsPage.map(ComparisonResponseDto::fromEntity);

        // Wrap in paginated response
        PagedComparisonResponse response = PagedComparisonResponse.fromPage(dtoPage);

        log.info("Successfully listed {} comparisons for account {} (page {}/{}, status={})",
                response.content().size(), accountId, page, response.totalPages(), status);

        return ResponseEntity.ok(response);
    }

    /**
     * Download comparison summary report as text file.
     * <p>
     * T101: GET /api/v1/comparisons/{id}/summary/download endpoint implementation.
     * <p>
     * Generates a human-readable summary report containing:
     * - Comparison metadata (ID, status, timestamps)
     * - Session information (current and target batch IDs)
     * - Statistics (file counts, change size)
     * - Error message (if applicable)
     * <p>
     * Filename format: comparison-{id}-summary.txt
     *
     * @param id Comparison ID
     * @param authentication JWT authentication
     * @return Summary report as plain text file with appropriate headers
     *
     * Phase 8 (User Story 6) - Task T101
     */
    @GetMapping("/{id}/summary/download")
    @Operation(
        summary = "Download comparison summary report",
        description = "Downloads the comparison summary report as a plain text file containing statistics and metadata"
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Summary report generated successfully",
            content = @Content(mediaType = "text/plain;charset=UTF-8")
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Access denied - user does not own this comparison",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Comparison not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))
        ),
        @ApiResponse(
            responseCode = "500",
            description = "Internal server error during report generation",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))
        )
    })
    public ResponseEntity<String> downloadSummaryReport(
        @Parameter(description = "Comparison ID", required = true, example = "123")
        @PathVariable Long id,
        Authentication authentication
    ) {
        log.info("Summary report download request for comparison {}", id);

        // Extract account ID from JWT using helper method
        UUID accountId = extractAccountIdFromJwt(authentication);

        // Generate summary report (includes authorization check)
        String summaryText = comparisonDownloadService.generateSummaryReport(id, accountId);

        // Set response headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/plain;charset=UTF-8"));
        headers.setContentDispositionFormData("attachment", "comparison-" + id + "-summary.txt");
        headers.setContentLength(summaryText.length());

        log.info("Successfully generated summary report for comparison {} ({} bytes)", id, summaryText.length());

        return new ResponseEntity<>(summaryText, headers, HttpStatus.OK);
    }

    /**
     * Error response DTO for OpenAPI documentation.
     * Note: Actual error handling is done by GlobalExceptionHandler.
     */
    @Schema(description = "Error response structure")
    private record ErrorResponseDto(
        @Schema(description = "Timestamp when error occurred", example = "2025-11-03T10:30:00Z")
        String timestamp,

        @Schema(description = "HTTP status code", example = "400")
        int status,

        @Schema(description = "HTTP status text", example = "Bad Request")
        String error,

        @Schema(description = "Detailed error message", example = "Current batch does not exist")
        String message,

        @Schema(description = "Request path", example = "/api/v1/comparisons")
        String path
    ) {}
}
