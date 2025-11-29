package com.bitbi.dfm.account.presentation;

import com.bitbi.dfm.account.application.AccountQueryService;
import com.bitbi.dfm.account.application.AccountService;
import com.bitbi.dfm.account.application.AccountService.AccountNotFoundException;
import com.bitbi.dfm.account.application.AccountSyncService;
import com.bitbi.dfm.account.presentation.dto.AccountDetailDto;
import com.bitbi.dfm.account.presentation.dto.AccountResponseDto;
import com.bitbi.dfm.account.presentation.dto.AdminActionLogResponseDto;
import com.bitbi.dfm.account.presentation.dto.CreateAccountRequestDto;
import com.bitbi.dfm.account.presentation.dto.ResetPasswordResponseDto;
import com.bitbi.dfm.auth.config.Auth0Properties;
import com.bitbi.dfm.shared.presentation.dto.PageResponseDto;
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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.validation.annotation.Validated;
import java.util.UUID;
import java.util.List;
import com.bitbi.dfm.account.infrastructure.AdminActionLogRepository;
import com.bitbi.dfm.account.domain.AdminActionLog;
import com.bitbi.dfm.account.domain.AdminActionType;

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
@Validated
@Tag(name = "UI/Admin API - Accounts", description = "Account administration endpoints for web interface")
@SecurityRequirement(name = "oauth2")
public class AccountAdminController {

    private static final Logger logger = LoggerFactory.getLogger(AccountAdminController.class);

    private final AccountService accountService;
    private final AccountSyncService accountSyncService;
    private final AccountQueryService accountQueryService;
    private final AdminActionLogRepository adminActionLogRepository;
    private final Auth0Properties auth0Properties;

    public AccountAdminController(
            AccountService accountService,
            AccountSyncService accountSyncService,
            AccountQueryService accountQueryService,
            AdminActionLogRepository adminActionLogRepository,
            Auth0Properties auth0Properties) {
        this.accountService = accountService;
        this.accountSyncService = accountSyncService;
        this.accountQueryService = accountQueryService;
        this.adminActionLogRepository = adminActionLogRepository;
        this.auth0Properties = auth0Properties;
    }

    /**
     * Lock account (block identity provider user).
     * <p>
     * POST /api/v1/accounts/{id}/lock
     * </p>
     * <p>
     * Prevents user from logging in via identity provider. Existing sessions remain valid until token expiry.
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
     * @throws IllegalStateException                                                    if account does not have identity provider integration (400)
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
        String adminAccountIdStr = jwt.getClaimAsString(auth0Properties.api().accountIdClaim());

        // Handle case where accountId claim is not present (e.g., old token, missing Auth0 Action)
        if (adminAccountIdStr == null || adminAccountIdStr.isBlank()) {
            logger.warn("Admin lock account request without accountId claim in JWT. Using null for audit log.");
        }

        UUID adminAccountId = (adminAccountIdStr != null && !adminAccountIdStr.isBlank())
            ? UUID.fromString(adminAccountIdStr)
            : null;

        logger.info("Admin lock account request: accountId={}, adminAccountId={}", id, adminAccountId);

        accountService.lockAccount(id, adminAccountId);

        return ResponseEntity.noContent().build();
    }

    /**
     * Unlock account (unblock identity provider user).
     * <p>
     * POST /api/v1/accounts/{id}/unlock
     * </p>
     * <p>
     * Allows previously blocked user to log in again via identity provider.
     * </p>
     *
     * User Story: US2 - Admin Locks/Unlocks User Accounts
     * Functional Requirement: FR-008 - Unlock account endpoint
     *
     * @param id account identifier (UUID path parameter)
     * @return 204 No Content if successful
     * @throws com.bitbi.dfm.account.application.AccountService.AccountNotFoundException if account not found (404)
     * @throws IllegalStateException                                                    if account does not have identity provider integration (400)
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
     * Reset user password by generating identity provider password reset link.
     * <p>
     * POST /api/v1/admin/accounts/{id}/reset-password (mapped from ApiRoutes.ACCOUNTS_RESET_PASSWORD)
     * </p>
     * <p>
     * Generates a password change ticket (24-hour expiry) that directs the user to
     * the identity provider login page to set a new password. The ticket URL must be sent to
     * the user via email or other communication channel.
     * </p>
     *
     * User Story: US3 - Admin Resets User Password
     * Functional Requirement: FR-010 - Generate password reset link
     * Task: T049 - Update AccountAdminController with reset-password endpoint
     *
     * @param id Account identifier to reset password for
     * @return 200 OK with ResetPasswordResponseDto containing password reset link and metadata
     * @throws AccountNotFoundException         if account not found (404)
     * @throws MissingAuth0IntegrationException if account does not have identity provider integration (400)
     */
    @PostMapping(ApiRoutes.ACCOUNTS_RESET_PASSWORD)
    @Operation(
        summary = "Reset user password",
        description = "Generate Auth0 password reset link for user. Link expires in 24 hours."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Password reset link generated successfully",
            content = @Content(schema = @Schema(implementation = ResetPasswordResponseDto.class))
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
    public ResponseEntity<ResetPasswordResponseDto> resetPassword(
            @PathVariable("id") UUID id,
            Authentication authentication) {

        // Extract admin's account ID from JWT token
        Jwt jwt = (Jwt) authentication.getPrincipal();
        String adminAccountIdStr = jwt.getClaimAsString(auth0Properties.api().accountIdClaim());

        // Handle case where accountId claim is not present (e.g., old token, missing Auth0 Action)
        if (adminAccountIdStr == null || adminAccountIdStr.isBlank()) {
            logger.warn("Admin reset password request without accountId claim in JWT. Using null for audit log.");
        }

        UUID adminAccountId = (adminAccountIdStr != null && !adminAccountIdStr.isBlank())
            ? UUID.fromString(adminAccountIdStr)
            : null;

        logger.info("Admin reset password request: accountId={}, adminAccountId={}", id, adminAccountId);

        try {
            ResetPasswordResponseDto response = accountService.resetPassword(id);

            // Log successful password reset action
            AdminActionLog log = AdminActionLog.success(
                    AdminActionType.RESET_PASSWORD,
                    id,
                    adminAccountId,
                    null, // IP address - TODO: extract from request
                    null  // User agent - TODO: extract from request
            );
            adminActionLogRepository.save(log);

            logger.info("Password reset link generated: accountId={}, email={}", id, response.email());

            return ResponseEntity.ok(response);
        } catch (AccountNotFoundException e) {
            // Don't log AccountNotFoundException to admin_action_logs because:
            // 1. target_account_id foreign key constraint requires account to exist
            // 2. Failed lookups for non-existent accounts are not significant security events
            logger.warn("Reset password failed: Account not found accountId={}", id);
            throw e;
        } catch (Exception e) {
            // Log failed password reset action (only for existing accounts)
            AdminActionLog log = AdminActionLog.failure(
                    AdminActionType.RESET_PASSWORD,
                    id,
                    adminAccountId,
                    e.getMessage(),
                    null, // IP address - TODO: extract from request
                    null  // User agent - TODO: extract from request
            );
            adminActionLogRepository.save(log);
            throw e;
        }
    }

    /**
     * Get single account by ID with Auth0 integration.
     * <p>
     * GET /api/v1/accounts/{id} (mapped from ApiRoutes.ACCOUNTS_ID)
     * </p>
     * <p>
     * Returns account details with Auth0-specific fields (isBlocked, lastLogin).
     * </p>
     *
     * @param id account identifier (UUID path parameter)
     * @return 200 OK with account details
     * @throws com.bitbi.dfm.account.application.AccountService.AccountNotFoundException if account not found (404)
     */
    @GetMapping(ApiRoutes.ACCOUNTS_ID)
    @Operation(
        summary = "Get account by ID with Auth0 integration",
        description = "Returns account details with Auth0 fields (isBlocked, lastLogin)."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Account details retrieved successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = AccountDetailDto.class)
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Account not found",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponseDto.class)
            )
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized - missing or invalid authentication token",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponseDto.class)
            )
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden - user does not have ROLE_ADMIN",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponseDto.class)
            )
        )
    })
    public ResponseEntity<AccountDetailDto> getAccountById(@PathVariable("id") UUID id) {

        logger.info("Admin get account request: accountId={}", id);

        AccountDetailDto response = accountQueryService.getAccountById(id);

        logger.info("Account retrieved successfully: accountId={}", id);

        return ResponseEntity.ok(response);
    }

    /**
     * Get audit logs for an account.
     * <p>
     * GET /api/v1/accounts/{id}/audit-logs (mapped from ApiRoutes.ACCOUNTS_AUDIT_LOGS)
     * </p>
     * <p>
     * Returns paginated list of administrative actions performed on this account
     * (create, lock, unlock, reset password). Used for compliance and troubleshooting.
     * </p>
     *
     * @param id account identifier (UUID path parameter)
     * @param page page number (0-based, default=0)
     * @param size page size (default=20)
     * @param sort sort parameter (default=createdAt,desc)
     * @return 200 OK with paginated list of audit log entries
     */
    @GetMapping(ApiRoutes.ACCOUNTS_AUDIT_LOGS)
    @Operation(
        summary = "Get audit logs for account",
        description = "Returns paginated list of administrative actions performed on this account."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Audit logs retrieved successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = PageResponseDto.class)
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Account not found",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponseDto.class)
            )
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized - missing or invalid authentication token",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponseDto.class)
            )
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden - user does not have ROLE_ADMIN",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponseDto.class)
            )
        )
    })
    public ResponseEntity<PageResponseDto<AdminActionLogResponseDto>> getAuditLogs(
            @PathVariable("id") UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {

        logger.info("Admin get audit logs request: accountId={}, page={}, size={}", id, page, size);

        // Parse sort parameter
        String[] sortParts = sort.split(",");
        String sortField = sortParts[0];
        Sort.Direction sortDirection = sortParts.length > 1 && sortParts[1].equalsIgnoreCase("asc")
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(sortDirection, sortField));

        // Fetch audit logs for this account
        Page<AdminActionLog> logsPage = adminActionLogRepository.findByTargetAccountId(id, pageRequest);

        // Convert to DTOs
        List<AdminActionLogResponseDto> logDtos = logsPage.getContent().stream()
                .map(AdminActionLogResponseDto::fromEntity)
                .toList();

        PageResponseDto<AdminActionLogResponseDto> response = new PageResponseDto<>(
                logDtos,
                logsPage.getNumber(),
                logsPage.getSize(),
                logsPage.getTotalElements(),
                logsPage.getTotalPages()
        );

        logger.info("Audit logs retrieved successfully: accountId={}, count={}", id, logDtos.size());

        return ResponseEntity.ok(response);
    }

    /**
     * List accounts with Auth0 integration.
     * <p>
     * GET /api/v1/accounts (mapped from ApiRoutes.ACCOUNTS_LIST)
     * </p>
     * <p>
     * Returns a paginated list of accounts with Auth0-specific fields (isBlocked, lastLogin).
     * Supports optional search parameter to filter by email or name (case-insensitive).
     * Only returns accounts with Auth0 integration (identity_provider_user_id NOT NULL).
     * </p>
     *
     * User Story: US6 - Admin Views List of Users with Auth0 Integration
     * Functional Requirement: FR-019 - List accounts with Auth0 fields
     * Task: T068 - Update AccountAdminController with GET endpoint
     *
     * @param search   optional search term to filter by email or name (nullable)
     * @param page     page number (0-based, default=0)
     * @param size     page size (default=20)
     * @return 200 OK with paginated list of accounts with Auth0 fields
     */
    @GetMapping(ApiRoutes.ACCOUNTS_LIST)
    @Operation(
        summary = "List accounts with Auth0 integration",
        description = "Returns paginated list of accounts with Auth0 fields (isBlocked, lastLogin). Supports search by email/name."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Accounts listed successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = PageResponseDto.class)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid search pattern or pagination parameters",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponseDto.class)
            )
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized - missing or invalid authentication token",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponseDto.class)
            )
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden - user does not have ROLE_ADMIN",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponseDto.class)
            )
        )
    })
    public ResponseEntity<PageResponseDto<AccountDetailDto>> listAccounts(
            @RequestParam(required = false)
            @Size(max = 100, message = "search term must not exceed 100 characters")
            @Pattern(
                regexp = "^[a-zA-Z0-9@.\\s\\-]*$",
                message = "search term contains invalid characters"
            )
            String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {

        // Extract admin's account ID from JWT token to exclude from results
        UUID currentAdminAccountId = null;
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            String adminAccountIdStr = jwt.getClaimAsString(auth0Properties.api().accountIdClaim());
            if (adminAccountIdStr != null && !adminAccountIdStr.isEmpty()) {
                currentAdminAccountId = UUID.fromString(adminAccountIdStr);
            }
        }

        logger.info("Admin list accounts request: search={}, page={}, size={}, excludingAdminId={}",
            search != null ? "[filtered]" : "null",
            page,
            size,
            currentAdminAccountId);

        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);
        PageResponseDto<AccountDetailDto> response = accountQueryService.listAccounts(search, pageable, currentAdminAccountId);

        logger.info("Accounts listed successfully: count={}, totalElements={}",
            response.content().size(),
            response.totalElements());

        return ResponseEntity.ok(response);
    }

    /**
     * Create account with identity provider integration.
     * <p>
     * POST /api/v1/accounts (mapped from ApiRoutes.ACCOUNTS_CREATE)
     * </p>
     * <p>
     * Creates a new user account in PostgreSQL and identity provider in a two-phase commit transaction.
     * Returns a temporary password for the user's first login.
     * </p>
     *
     * User Story: US1 - Admin Creates User Account
     * Functional Requirement: FR-001 - Create account with identity provider
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

        logger.info("Creating account with identity provider integration for email: {}", request.email());

        // Create account with identity provider integration (two-phase commit)
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

        logger.info("Account created successfully: accountId={}, identityProviderUserId={}",
                result.account().getId(), result.account().getIdentityProviderUserId());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Delete account (remove from PostgreSQL and Auth0).
     * <p>
     * DELETE /api/v1/accounts/{id}
     * </p>
     * <p>
     * Permanently deletes the account from both PostgreSQL and Auth0.
     * This action cannot be undone. Admin cannot delete their own account.
     * </p>
     *
     * @param id             account identifier (UUID path parameter)
     * @param authentication JWT authentication (contains admin's accountId)
     * @return 204 No Content if successful
     * @throws com.bitbi.dfm.account.application.AccountService.AccountNotFoundException if account not found (404)
     * @throws com.bitbi.dfm.account.application.AccountService.CannotDeleteOwnAccountException if admin tries to delete their own account (403)
     */
    @DeleteMapping(ApiRoutes.ACCOUNTS_ID)
    @Operation(
        summary = "Delete account",
        description = "Permanently delete account from PostgreSQL and Auth0. Admin cannot delete their own account."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "204",
            description = "Account deleted successfully"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Account not found",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponseDto.class)
            )
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Cannot delete own account",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponseDto.class)
            )
        )
    })
    public ResponseEntity<Void> deleteAccount(
            @PathVariable("id") UUID id,
            Authentication authentication) {

        logger.info("Admin delete account request: accountId={}", id);

        // Prevent admin from deleting their own account
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            String adminAccountIdStr = jwt.getClaimAsString(auth0Properties.api().accountIdClaim());
            if (adminAccountIdStr != null && !adminAccountIdStr.isEmpty()) {
                UUID adminAccountId = UUID.fromString(adminAccountIdStr);
                if (adminAccountId.equals(id)) {
                    throw new AccountService.CannotDeleteOwnAccountException("Admin cannot delete their own account");
                }
            }
        }

        accountSyncService.deleteAccount(id);

        logger.info("Account deleted successfully: accountId={}", id);

        return ResponseEntity.noContent().build();
    }
}
