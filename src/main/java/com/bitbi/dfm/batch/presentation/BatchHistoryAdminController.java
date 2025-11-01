package com.bitbi.dfm.batch.presentation;

import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.infrastructure.JpaAccountRepository;
import com.bitbi.dfm.batch.application.BatchHistoryService;
import com.bitbi.dfm.batch.presentation.dto.BatchSummaryDto;
import com.bitbi.dfm.batch.presentation.dto.CursorPageResponseDto;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.UUID;

/**
 * Admin API controller for Upload History feature.
 * <p>
 * Provides admin access to view all batch upload history for any account.
 * Requires ROLE_ADMIN from Keycloak OAuth2 token.
 * </p>
 *
 * <p><strong>Endpoints:</strong></p>
 * <ul>
 *   <li>GET /api/admin/accounts/{accountId}/batches - List batch history for specific account</li>
 * </ul>
 *
 * <p><strong>Security:</strong></p>
 * <ul>
 *   <li>Requires Keycloak OAuth2 Bearer token with ROLE_ADMIN</li>
 *   <li>Admins can view batches for any account</li>
 * </ul>
 *
 * @author Data Forge Team (Feature: 008-upload-history-user)
 * @version 1.0.0
 */
@RestController
@RequestMapping("/api/user/batches")
@Tag(name = "User - Upload History", description = "User endpoints for viewing own upload history (Keycloak auth)")
public class BatchHistoryAdminController {

    private static final Logger logger = LoggerFactory.getLogger(BatchHistoryAdminController.class);

    private final BatchHistoryService batchHistoryService;
    private final JpaAccountRepository accountRepository;
    private final MeterRegistry meterRegistry;

    public BatchHistoryAdminController(
            BatchHistoryService batchHistoryService,
            JpaAccountRepository accountRepository,
            MeterRegistry meterRegistry
    ) {
        this.batchHistoryService = batchHistoryService;
        this.accountRepository = accountRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Get accountId for currently authenticated user.
     * <p>
     * Uses three-step strategy (same as SiteController):
     * 1. Try accountId claim (custom Keycloak attribute)
     * 2. Try sub claim as UUID
     * 3. Lookup by email/preferred_username in database
     *
     * @return Account ID if user has account
     * @throws ResponseStatusException if user not found
     */
    private UUID getCurrentUserAccountId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }

        Jwt jwt = (Jwt) authentication.getPrincipal();

        // Strategy 1: Try accountId from custom claim
        String accountIdClaim = jwt.getClaimAsString("accountId");
        if (accountIdClaim != null) {
            try {
                UUID accountId = UUID.fromString(accountIdClaim);
                logger.debug("Extracted account ID from 'accountId' claim: {}", accountId);
                return accountId;
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid accountId claim format: {}", accountIdClaim);
            }
        }

        // Strategy 2: Try subject (sub) claim if it's a valid UUID
        String subject = jwt.getSubject();
        if (subject != null) {
            try {
                UUID accountId = UUID.fromString(subject);
                logger.debug("Extracted account ID from 'sub' claim: {}", accountId);
                return accountId;
            } catch (IllegalArgumentException e) {
                logger.debug("'sub' claim is not a valid UUID: {}", subject);
            }
        }

        // Strategy 3: Use email or preferred_username to look up account
        final String email = jwt.getClaimAsString("email");
        final String username = jwt.getClaimAsString("preferred_username");
        final String identifier = (email != null) ? email : username;

        logger.debug("Attempting to find account by identifier: {}", identifier);
        if (identifier != null) {
            Account account = accountRepository.findByEmail(identifier)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "No account found for user: " + identifier
                    ));
            logger.debug("Found account by email/username: accountId={}", account.getId());
            return account.getId();
        }

        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unable to determine account ID from token");
    }

    /**
     * List batch history for current user (Keycloak authenticated).
     * <p>
     * Returns paginated list of upload batches for currently authenticated user.
     * User must have a linked Account via Keycloak userId.
     * </p>
     *
     * @param cursor Cursor for next page (null for first page)
     * @param limit  Maximum items per page (default 20, max 100)
     * @return Paginated batch list or empty list if no account linked
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "List batch history for current user",
            description = "Retrieve paginated list of upload batches for currently authenticated Keycloak user. " +
                    "Requires user to have a linked Account.",
            security = @SecurityRequirement(name = "oauth2"),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Batch list retrieved successfully",
                            content = @Content(schema = @Schema(implementation = CursorPageResponseDto.class))
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "Unauthorized - Not authenticated"
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "No account linked to current user"
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
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // Get accountId for current Keycloak user
            UUID accountId = getCurrentUserAccountId();

            logger.info("Listing batch history for current user accountId={}, cursor={}, limit={}",
                    accountId, cursor, limit);

            CursorPageResponseDto<BatchSummaryDto> response = batchHistoryService.listBatchHistory(
                    accountId,
                    cursor,
                    limit
            );

            logger.info("Returning {} batches (hasNext={}) for accountId={}",
                    response.items().size(), response.hasNext(), accountId);

            return ResponseEntity.ok(response);

        } catch (ResponseStatusException e) {
            // User not found or not linked - return empty list instead of error
            logger.info("No account linked for current user, returning empty list");
            return ResponseEntity.ok(new CursorPageResponseDto<>(
                    Collections.emptyList(),
                    null,
                    false
            ));

        } catch (IllegalArgumentException e) {
            logger.warn("Invalid cursor format: cursor={}, error={}", cursor, e.getMessage());
            throw e;

        } finally {
            sample.stop(meterRegistry.timer("batch.history.user.list"));
        }
    }

    /**
     * T048: Get batch details with file list for current user.
     * <p>
     * Returns detailed batch information including all uploaded files.
     * Only allows access to batches owned by the current user's account.
     * </p>
     *
     * @param batchId Batch unique identifier
     * @return Batch details with file list
     */
    @GetMapping(value = "/{batchId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Get batch details with file list",
            description = "Returns detailed batch information including all uploaded files for current user",
            security = @SecurityRequirement(name = "Keycloak OAuth2")
    )
    @ApiResponse(responseCode = "200", description = "Batch details retrieved successfully",
            content = @Content(schema = @Schema(implementation = com.bitbi.dfm.batch.presentation.dto.BatchDetailDto.class)))
    @ApiResponse(responseCode = "403", description = "Batch doesn't belong to user")
    @ApiResponse(responseCode = "404", description = "Batch not found")
    public ResponseEntity<com.bitbi.dfm.batch.presentation.dto.BatchDetailDto> getBatchDetails(
            @Parameter(description = "Batch ID (UUID)", required = true)
            @PathVariable UUID batchId) {

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            UUID accountId = getCurrentUserAccountId();
            logger.info("Fetching batch details: batchId={}, accountId={}", batchId, accountId);

            com.bitbi.dfm.batch.presentation.dto.BatchDetailDto response = batchHistoryService.getBatchDetails(batchId, accountId);

            logger.debug("Batch details retrieved: batchId={}, fileCount={}", batchId, response.uploadedFilesCount());
            return ResponseEntity.ok(response);

        } catch (com.bitbi.dfm.batch.domain.exception.BatchNotFoundException e) {
            logger.warn("Batch not found: batchId={}", batchId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Batch not found");

        } catch (com.bitbi.dfm.batch.domain.exception.UnauthorizedBatchAccessException e) {
            logger.warn("Unauthorized batch access: batchId={}, accountId={}", batchId, e.getAccountId());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");

        } catch (ResponseStatusException e) {
            logger.warn("User not linked to account: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not linked to account");

        } finally {
            sample.stop(meterRegistry.timer("batch.details.user.load"));
        }
    }
}
