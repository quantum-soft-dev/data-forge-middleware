package com.bitbi.dfm.account.presentation;

import com.bitbi.dfm.account.application.AccountService;
import com.bitbi.dfm.account.application.AccountSyncService;
import com.bitbi.dfm.account.presentation.dto.AccountResponseDto;
import com.bitbi.dfm.account.presentation.dto.CreateAccountRequestDto;
import com.bitbi.dfm.shared.api.ApiRoutes;
import com.bitbi.dfm.shared.presentation.dto.ErrorResponseDto;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.UUID;

/**
 * REST controller for account administration (UI/Admin API).
 * <p>
 * Provides admin endpoints for account lock/unlock operations.
 * Requires Auth0 OAuth2 authentication with ROLE_ADMIN.
 * </p>
 * <p>
 * API Path: /api/v1/accounts/{id}/lock and /api/v1/accounts/{id}/unlock (per API Unification Spec 010)
 * </p>
 *
 * User Story: US2 - Admin Locks/Unlocks User Accounts
 * Task: T043 - Add lock/unlock endpoints to AccountAdminController
 *
 * @author Data Forge Team
 * @version 1.0.0
 * @see <a href="specs/011-auth0-migration-migrate/spec.md">Auth0 Migration Specification</a>
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "UI/Admin API - Accounts", description = "Account administration endpoints for web interface")
@SecurityRequirement(name = "oauth2")
public class AccountAdminController {

    private static final Logger logger = LoggerFactory.getLogger(AccountAdminController.class);

    private final AccountService accountService;
    private final AccountSyncService accountSyncService;

    public AccountAdminController(AccountService accountService, AccountSyncService accountSyncService) {
        this.accountService = accountService;
        this.accountSyncService = accountSyncService;
    }

    /**
     * Lock account (block Auth0 user).
     * <p>
     * POST /api/v1/accounts/{id}/lock
     * </p>
     * <p>
     * Prevents user from logging in via Auth0. Existing sessions remain valid until token expiry.
     * Admin cannot lock their own account (403 Forbidden).
     * </p>
     *
     * User Story: US2 - Admin Locks/Unlocks User Accounts
     * Functional Requirement: FR-006 - Lock account endpoint
     *
     * @param id             account identifier (UUID path parameter)
     * @param authentication JWT authentication (contains admin's accountId)
     * @return 204 No Content if successful
     * @throws com.bitbi.dfm.account.application.AccountService.AccountNotFoundException if account not found (404)
     * @throws com.bitbi.dfm.shared.exception.CannotLockOwnAccountException             if admin attempts to lock their own account (403)
     * @throws IllegalStateException                                                    if account does not have Auth0 integration (400)
     */
    @PostMapping(ApiRoutes.ACCOUNTS_LOCK)
    @Operation(
        summary = "Lock account",
        description = "Block user account in Auth0. Prevents user from logging in. Admin cannot lock their own account."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "204",
            description = "Account locked successfully"
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Admin attempted to lock their own account",
            content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Account not found",
            content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Account does not have Auth0 integration",
            content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))
        )
    })
    public ResponseEntity<Void> lockAccount(
            @PathVariable("id") UUID id,
            Authentication authentication) {

        // Extract admin's account ID from JWT token
        Jwt jwt = (Jwt) authentication.getPrincipal();
        String adminAccountIdStr = jwt.getClaimAsString("https://api.dataforge.com/accountId");
        UUID adminAccountId = UUID.fromString(adminAccountIdStr);

        logger.info("Admin lock account request: accountId={}, adminAccountId={}", id, adminAccountId);

        accountService.lockAccount(id, adminAccountId);

        return ResponseEntity.noContent().build();
    }

    /**
     * Unlock account (unblock Auth0 user).
     * <p>
     * POST /api/v1/accounts/{id}/unlock
     * </p>
     * <p>
     * Allows previously blocked user to log in again via Auth0.
     * </p>
     *
     * User Story: US2 - Admin Locks/Unlocks User Accounts
     * Functional Requirement: FR-008 - Unlock account endpoint
     *
     * @param id account identifier (UUID path parameter)
     * @return 204 No Content if successful
     * @throws com.bitbi.dfm.account.application.AccountService.AccountNotFoundException if account not found (404)
     * @throws IllegalStateException                                                    if account does not have Auth0 integration (400)
     */
    @PostMapping(ApiRoutes.ACCOUNTS_UNLOCK)
    @Operation(
        summary = "Unlock account",
        description = "Unblock user account in Auth0. Allows user to log in again."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "204",
            description = "Account unlocked successfully"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Account not found",
            content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Account does not have Auth0 integration",
            content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))
        )
    })
    public ResponseEntity<Void> unlockAccount(@PathVariable("id") UUID id) {

        logger.info("Admin unlock account request: accountId={}", id);

        accountService.unlockAccount(id);

        return ResponseEntity.noContent().build();
    }

    /**
     * Create account with Auth0 integration.
     * <p>
     * POST /api/v1/accounts (mapped from ApiRoutes.ACCOUNTS_CREATE)
     * </p>
     * <p>
     * Creates a new user account in PostgreSQL and Auth0 in a two-phase commit transaction.
     * Returns a temporary password for the user's first login.
     * </p>
     *
     * User Story: US1 - Admin Creates User Account via Auth0
     * Functional Requirement: FR-001 - Create account with Auth0
     *
     * @param request Account creation request (email, name, phone, company)
     * @return 201 Created with account details and temporary password
     * @throws IllegalArgumentException if account with email already exists (409)
     */
    @PostMapping(ApiRoutes.ACCOUNTS_CREATE)
    @Operation(
        summary = "Create account with Auth0 integration",
        description = "Creates a new account and links it to Auth0 for authentication. Returns temporary password for first login."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "201",
            description = "Account created successfully with Auth0 integration",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = AccountResponseDto.class)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid request data",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponseDto.class)
            )
        ),
        @ApiResponse(
            responseCode = "409",
            description = "Account with this email already exists",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponseDto.class)
            )
        ),
        @ApiResponse(
            responseCode = "503",
            description = "Auth0 service unavailable",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponseDto.class)
            )
        )
    })
    public ResponseEntity<AccountResponseDto> createAccount(
            @Valid @RequestBody CreateAccountRequestDto request) {

        logger.info("Creating account with Auth0 integration for email: {}", request.email());

        // Create account with Auth0 integration (two-phase commit)
        var result = accountSyncService.createAccount(
                request.email(),
                request.name(),
                request.phone(),
                request.company()
        );

        // Convert to response DTO with temporary password
        AccountResponseDto response = AccountResponseDto.fromEntityWithPassword(
                result.account(),
                result.temporaryPassword()
        );

        logger.info("Account created successfully with Auth0: accountId={}, auth0UserId={}",
                result.account().getId(), result.account().getIdentityProviderUserId());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
