package com.bitbi.dfm.batch.presentation;

import com.bitbi.dfm.batch.application.BatchRetentionService;
import com.bitbi.dfm.batch.application.BatchRetentionService.BatchCleanupRequest;
import com.bitbi.dfm.batch.application.BatchRetentionService.BatchCleanupSummary;
import com.bitbi.dfm.batch.presentation.dto.BatchCleanupRequestDto;
import com.bitbi.dfm.batch.presentation.dto.BatchCleanupResultDto;
import com.bitbi.dfm.shared.api.ApiRoutes;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin controller for batch retention cleanup.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "UI/Admin API - Batch Cleanup", description = "Manual batch cleanup operations for admins")
@SecurityRequirement(name = "oauth2")
public class BatchCleanupAdminController {

    private static final Logger logger = LoggerFactory.getLogger(BatchCleanupAdminController.class);

    private final BatchRetentionService batchRetentionService;

    public BatchCleanupAdminController(BatchRetentionService batchRetentionService) {
        this.batchRetentionService = batchRetentionService;
    }

    /**
     * Manually run batch retention cleanup with filters.
     *
     * POST /api/v1/batches/cleanup
     */
    @Operation(
            summary = "Run batch retention cleanup",
            description = "Runs batch cleanup using retention policy or overrides. Supports dry run."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Cleanup completed",
                    content = @Content(schema = @Schema(implementation = BatchCleanupResultDto.class)))
    })
    @PostMapping(ApiRoutes.BATCHES_CLEANUP)
    public ResponseEntity<BatchCleanupResultDto> cleanupBatches(
            @Valid @RequestBody(required = false) BatchCleanupRequestDto request) {

        BatchCleanupRequestDto safeRequest = request != null
                ? request
                : new BatchCleanupRequestDto(null, null, null, null, null, null);

        logger.info("Running manual batch cleanup: siteId={}, accountId={}, retentionDays={}, olderThan={}, limit={}, dryRun={}",
                safeRequest.siteId(), safeRequest.accountId(), safeRequest.retentionDays(),
                safeRequest.olderThan(), safeRequest.limit(), safeRequest.dryRun());

        BatchCleanupSummary summary = batchRetentionService.runCleanup(new BatchCleanupRequest(
                safeRequest.siteId(),
                safeRequest.accountId(),
                safeRequest.retentionDays(),
                safeRequest.olderThan(),
                safeRequest.limit(),
                safeRequest.dryRun()
        ));

        BatchCleanupResultDto response = new BatchCleanupResultDto(
                summary.candidates(),
                summary.deletedBatches(),
                summary.deletedFiles(),
                summary.deletedBytes(),
                summary.errors()
        );

        return ResponseEntity.ok(response);
    }
}
