package com.bitbi.dfm.batch.presentation;

import com.bitbi.dfm.account.domain.ActionStatus;
import com.bitbi.dfm.account.domain.AdminActionLog;
import com.bitbi.dfm.account.domain.AdminActionType;
import com.bitbi.dfm.account.infrastructure.AdminActionLogRepository;
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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
    private final AdminActionLogRepository adminActionLogRepository;

    public BatchCleanupAdminController(
            BatchRetentionService batchRetentionService,
            AdminActionLogRepository adminActionLogRepository) {
        this.batchRetentionService = batchRetentionService;
        this.adminActionLogRepository = adminActionLogRepository;
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
            @Valid @RequestBody(required = false) BatchCleanupRequestDto request,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        BatchCleanupRequestDto safeRequest = request != null
                ? request
                : new BatchCleanupRequestDto(null, null, null, null, null, null);

        logger.info("Running manual batch cleanup: siteId={}, accountId={}, retentionDays={}, olderThan={}, limit={}, dryRun={}",
                safeRequest.siteId(), safeRequest.accountId(), safeRequest.retentionDays(),
                safeRequest.olderThan(), safeRequest.limit(), safeRequest.dryRun());

        try {
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

            logAdminAction(safeRequest, authentication, httpRequest, null);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logAdminAction(safeRequest, authentication, httpRequest, e.getMessage());
            throw e;
        }
    }

    private void logAdminAction(BatchCleanupRequestDto request,
                                Authentication authentication,
                                HttpServletRequest httpRequest,
                                String errorMessage) {
        try {
            // Admins do not necessarily have an Account record in Postgres.
            // We log NULL adminAccountId and rely on Auth0/JWT for identity outside this table.
            java.util.UUID adminAccountId = null;

            String ipAddress = extractIpAddress(httpRequest);
            String userAgent = httpRequest.getHeader("User-Agent");

            AdminActionLog log;
            if (errorMessage == null) {
                log = AdminActionLog.successForSite(
                        AdminActionType.BATCH_CLEANUP,
                        request.accountId(),
                        request.siteId(),
                        adminAccountId,
                        ipAddress,
                        userAgent
                );
            } else {
                // If accountId/siteId is null, log as account-level failure is not supported by the current schema,
                // so we still use the site factory with nulls (columns are nullable).
                log = AdminActionLog.failureForSite(
                        AdminActionType.BATCH_CLEANUP,
                        request.accountId(),
                        request.siteId(),
                        adminAccountId,
                        errorMessage,
                        ipAddress,
                        userAgent
                );
            }

            adminActionLogRepository.save(log);
            logger.info("Admin action logged: actionType={}, targetAccountId={}, targetSiteId={}, status={}",
                    AdminActionType.BATCH_CLEANUP, request.accountId(), request.siteId(),
                    errorMessage == null ? ActionStatus.SUCCESS : ActionStatus.FAILED);
        } catch (Exception e) {
            logger.error("Failed to log admin action: actionType={}, error={}", AdminActionType.BATCH_CLEANUP, e.getMessage(), e);
        }
    }

    private String extractIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
