package com.bitbi.dfm.comparison.presentation;

import com.bitbi.dfm.comparison.application.ComparisonQueryService;
import com.bitbi.dfm.comparison.application.ComparisonService;
import com.bitbi.dfm.comparison.domain.ChangeType;
import com.bitbi.dfm.comparison.domain.ComparisonResult;
import com.bitbi.dfm.comparison.domain.FileComparison;
import com.bitbi.dfm.comparison.presentation.dto.ComparisonResponseDto;
import com.bitbi.dfm.comparison.presentation.dto.ComparisonResultDto;
import com.bitbi.dfm.comparison.presentation.dto.ComparisonSummaryDto;
import com.bitbi.dfm.comparison.presentation.dto.CreateComparisonRequestDto;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

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

    public ComparisonController(
        ComparisonService comparisonService,
        ComparisonQueryService comparisonQueryService
    ) {
        this.comparisonService = comparisonService;
        this.comparisonQueryService = comparisonQueryService;
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
     * Extracts accountId from JWT token.
     *
     * <p>For Keycloak OAuth2 tokens, the accountId is expected to be in the "sub" claim.
     * This follows the pattern established in the Upload History feature (Spec 008).
     *
     * @param authentication the authentication object
     * @return the account ID from JWT
     * @throws IllegalStateException if JWT is missing or accountId cannot be extracted
     */
    private UUID extractAccountIdFromJwt(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("Authentication principal is not a valid JWT");
        }

        // Extract "sub" claim (subject) which contains the account/user ID
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new IllegalStateException("JWT subject (accountId) is missing");
        }

        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("JWT subject is not a valid UUID: " + subject, e);
        }
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
    public ResponseEntity<Page<ComparisonResultDto>> getComparisonResults(
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

        List<ComparisonResultDto> dtoList = filteredResults.subList(start, end).stream()
            .map(ComparisonResultDto::fromEntity)
            .collect(Collectors.toList());

        // Create Page object manually
        Page<ComparisonResultDto> resultPage = new org.springframework.data.domain.PageImpl<>(
            dtoList,
            pageable,
            filteredResults.size()
        );

        log.info("Returning {} results (total: {}) for comparison {}", dtoList.size(), filteredResults.size(), comparisonId);

        return ResponseEntity.ok(resultPage);
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
