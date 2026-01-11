package com.bitbi.dfm.error.presentation;

import com.bitbi.dfm.error.application.GlobalErrorService;
import com.bitbi.dfm.error.presentation.dto.*;
import com.bitbi.dfm.shared.presentation.dto.ErrorResponseDto;
import com.bitbi.dfm.shared.presentation.dto.PageResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for user-facing global error operations.
 * <p>
 * Provides endpoints for viewing and managing global errors on the Dashboard.
 * Uses Auth0 OAuth2 authentication with accountId claim.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@RestController
@RequestMapping("/api/v1/account/errors")
@Tag(name = "User - Global Errors", description = "User-facing global error management endpoints")
@SecurityRequirement(name = "auth0")
public class GlobalErrorUserController {

    private static final Logger logger = LoggerFactory.getLogger(GlobalErrorUserController.class);
    private static final String ACCOUNT_ID_CLAIM = "https://api.dataforge.com/accountId";

    private final GlobalErrorService globalErrorService;

    public GlobalErrorUserController(GlobalErrorService globalErrorService) {
        this.globalErrorService = globalErrorService;
    }

    /**
     * List global errors for the authenticated user's account.
     *
     * @param jwt        Auth0 JWT token
     * @param page       page number (0-indexed)
     * @param size       page size (1-100)
     * @param unreadOnly filter to show only unread errors
     * @return paginated list of global error summaries
     */
    @Operation(
            summary = "List global errors",
            description = "Returns paginated list of global errors for the authenticated user's account"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Paginated list of global errors"),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @GetMapping
    public ResponseEntity<PageResponseDto<GlobalErrorSummaryDto>> listGlobalErrors(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Page number (0-indexed)")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size (1-100)")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @Parameter(description = "Filter to show only unread errors")
            @RequestParam(defaultValue = "false") boolean unreadOnly) {

        UUID accountId = extractAccountId(jwt);
        logger.debug("Listing global errors: accountId={}, page={}, size={}, unreadOnly={}",
                accountId, page, size, unreadOnly);

        PageResponseDto<GlobalErrorSummaryDto> result = globalErrorService.listGlobalErrors(
                accountId, page, size, unreadOnly);

        return ResponseEntity.ok(result);
    }

    /**
     * Get unread global errors count for badge display.
     *
     * @param jwt Auth0 JWT token
     * @return unread count
     */
    @Operation(
            summary = "Get unread count",
            description = "Returns the count of unread global errors for badge display"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Unread count"),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @GetMapping("/unread-count")
    public ResponseEntity<UnreadCountResponseDto> getUnreadCount(@AuthenticationPrincipal Jwt jwt) {
        UUID accountId = extractAccountId(jwt);
        logger.debug("Getting unread count: accountId={}", accountId);

        long count = globalErrorService.getUnreadCount(accountId);

        return ResponseEntity.ok(new UnreadCountResponseDto(count));
    }

    /**
     * Get global error details.
     *
     * @param jwt     Auth0 JWT token
     * @param errorId error identifier
     * @return global error details
     */
    @Operation(
            summary = "Get global error details",
            description = "Returns full details of a specific global error"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Error details"),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "403", description = "Access denied",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Error not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @GetMapping("/{errorId}")
    public ResponseEntity<GlobalErrorResponseDto> getGlobalError(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Error identifier")
            @PathVariable UUID errorId) {

        UUID accountId = extractAccountId(jwt);
        logger.debug("Getting global error: errorId={}, accountId={}", errorId, accountId);

        GlobalErrorResponseDto error = globalErrorService.getGlobalError(errorId, accountId);

        return ResponseEntity.ok(error);
    }

    /**
     * Mark single error as read.
     *
     * @param jwt     Auth0 JWT token
     * @param errorId error identifier
     * @return 204 No Content
     */
    @Operation(
            summary = "Mark error as read",
            description = "Marks a specific error as read"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Error marked as read"),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "403", description = "Access denied",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Error not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @PatchMapping("/{errorId}/read")
    public ResponseEntity<Void> markAsRead(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Error identifier")
            @PathVariable UUID errorId) {

        UUID accountId = extractAccountId(jwt);
        logger.debug("Marking error as read: errorId={}, accountId={}", errorId, accountId);

        globalErrorService.markAsRead(errorId, accountId);

        return ResponseEntity.noContent().build();
    }

    /**
     * Mark multiple errors as read.
     *
     * @param jwt     Auth0 JWT token
     * @param request request containing error IDs
     * @return number of errors marked as read
     */
    @Operation(
            summary = "Mark multiple errors as read",
            description = "Marks specified errors as read (bulk operation)"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Errors marked as read"),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @PostMapping("/mark-as-read")
    public ResponseEntity<MarkAsReadResponseDto> markMultipleAsRead(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody MarkAsReadRequestDto request) {

        UUID accountId = extractAccountId(jwt);
        logger.debug("Marking multiple errors as read: count={}, accountId={}",
                request.errorIds().size(), accountId);

        int markedCount = globalErrorService.markMultipleAsRead(request.errorIds(), accountId);

        return ResponseEntity.ok(new MarkAsReadResponseDto(markedCount));
    }

    /**
     * Mark all global errors as read for account.
     *
     * @param jwt Auth0 JWT token
     * @return number of errors marked as read
     */
    @Operation(
            summary = "Mark all errors as read",
            description = "Marks all unread global errors for the account as read"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "All errors marked as read"),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @PostMapping("/mark-all-as-read")
    public ResponseEntity<MarkAsReadResponseDto> markAllAsRead(@AuthenticationPrincipal Jwt jwt) {
        UUID accountId = extractAccountId(jwt);
        logger.debug("Marking all errors as read: accountId={}", accountId);

        int markedCount = globalErrorService.markAllAsRead(accountId);

        return ResponseEntity.ok(new MarkAsReadResponseDto(markedCount));
    }

    /**
     * Extract account ID from JWT claims.
     *
     * @param jwt Auth0 JWT token
     * @return account ID
     * @throws IllegalArgumentException if accountId claim is missing
     */
    private UUID extractAccountId(Jwt jwt) {
        String accountIdClaim = jwt.getClaimAsString(ACCOUNT_ID_CLAIM);
        if (accountIdClaim == null || accountIdClaim.isEmpty()) {
            throw new IllegalArgumentException("Missing accountId claim in JWT token");
        }
        return UUID.fromString(accountIdClaim);
    }
}
