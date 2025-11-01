package com.bitbi.dfm.batch.presentation;

import com.bitbi.dfm.batch.application.BatchHistoryService;
import com.bitbi.dfm.batch.presentation.dto.BatchSummaryDto;
import com.bitbi.dfm.batch.presentation.dto.CursorPageResponseDto;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * T029: REST controller for Upload History feature (Data Forge Client API).
 * <p>
 * Provides cursor-based pagination for viewing batch upload history.
 * Authorized users can only view their own batches (filtered by accountId).
 * </p>
 *
 * <p><strong>Endpoints:</strong></p>
 * <ul>
 *   <li>GET /api/dfc/batches - List batch history (cursor pagination)</li>
 * </ul>
 *
 * <p><strong>Security:</strong></p>
 * <ul>
 *   <li>Requires JWT Bearer token</li>
 *   <li>Authorization via accountId extraction from JWT</li>
 *   <li>Users only see batches from their own sites</li>
 * </ul>
 *
 * @author Data Forge Team (Feature: 008-upload-history-user)
 * @version 1.0.0
 * @see com.bitbi.dfm.batch.application.BatchHistoryService
 */
@RestController
@RequestMapping("/api/dfc/batches")
@Tag(name = "Upload History", description = "Endpoints for viewing upload history")
public class BatchHistoryController {

    private static final Logger logger = LoggerFactory.getLogger(BatchHistoryController.class);

    private final BatchHistoryService batchHistoryService;
    private final AuthorizationHelper authorizationHelper;
    private final MeterRegistry meterRegistry;

    public BatchHistoryController(
            BatchHistoryService batchHistoryService,
            AuthorizationHelper authorizationHelper,
            MeterRegistry meterRegistry
    ) {
        this.batchHistoryService = batchHistoryService;
        this.authorizationHelper = authorizationHelper;
        this.meterRegistry = meterRegistry;
    }

    /**
     * T029: List batch history with cursor-based pagination.
     * <p>
     * Returns paginated list of user's upload batches sorted by startedAt DESC, id DESC.
     * First page: GET /api/dfc/batches?limit=20
     * Next page: GET /api/dfc/batches?cursor=2025-11-01T10:30:00_uuid&limit=20
     * </p>
     *
     * @param cursor Cursor for next page (null for first page)
     * @param limit  Maximum items per page (default 20, max 100)
     * @return Paginated batch list
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "List batch history",
            description = "Retrieve paginated list of user's upload batches using cursor-based pagination. " +
                    "First page omits cursor parameter. Subsequent pages use cursor from previous response.",
            security = @SecurityRequirement(name = "bearerAuth"),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Batch list retrieved successfully",
                            content = @Content(schema = @Schema(implementation = CursorPageResponseDto.class))
                    ),
                    @ApiResponse(
                            responseCode = "403",
                            description = "Forbidden - Invalid or missing JWT token"
                    )
            }
    )
    public ResponseEntity<CursorPageResponseDto<BatchSummaryDto>> listBatchHistory(
            @Parameter(
                    description = "Cursor for next page (format: startedAt_id)",
                    example = "2025-11-01T10:30:00_550e8400-e29b-41d4-a716-446655440000"
            )
            @RequestParam(required = false) String cursor,

            @Parameter(
                    description = "Maximum items per page (1-100)",
                    example = "20"
            )
            @RequestParam(required = false) Integer limit
    ) {
        // T032: Start metrics timer
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // T030: Extract accountId from JWT for authorization
            UUID accountId = authorizationHelper.getAuthenticatedAccountId();

            logger.info("Listing batch history for accountId={}, cursor={}, limit={}", accountId, cursor, limit);

            // T026: Call service to fetch paginated batches
            CursorPageResponseDto<BatchSummaryDto> response = batchHistoryService.listBatchHistory(
                    accountId,
                    cursor,
                    limit
            );

            logger.info("Returning {} batches (hasNext={}) for accountId={}",
                    response.items().size(), response.hasNext(), accountId);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            // Invalid cursor format
            logger.warn("Invalid cursor format: cursor={}, error={}", cursor, e.getMessage());
            throw e;  // GlobalExceptionHandler will handle

        } catch (AuthorizationHelper.UnauthorizedException e) {
            logger.warn("Unauthorized batch history access: {}", e.getMessage());
            throw e;  // GlobalExceptionHandler will handle

        } finally {
            // T032: Record metrics
            sample.stop(meterRegistry.timer("batch.history.list"));
        }
    }
}
