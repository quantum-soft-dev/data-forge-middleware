package com.bitbi.dfm.account.presentation;

import com.bitbi.dfm.account.application.AccountProperties;
import com.bitbi.dfm.account.application.AccountService;
import com.bitbi.dfm.account.application.AccountStatisticsService;
import com.bitbi.dfm.account.application.KeycloakAccountSyncService;
import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.domain.AdminActionLog;
import com.bitbi.dfm.account.infrastructure.AdminActionLogRepository;
import com.bitbi.dfm.account.presentation.dto.AccountResponseDto;
import com.bitbi.dfm.account.presentation.dto.AccountStatisticsDto;
import com.bitbi.dfm.account.presentation.dto.AccountWithStatsResponseDto;
import com.bitbi.dfm.account.presentation.dto.AdminActionLogResponseDto;
import com.bitbi.dfm.account.presentation.dto.CreateAccountRequestDto;
import com.bitbi.dfm.account.presentation.dto.CreateAccountResponse;
import com.bitbi.dfm.account.presentation.dto.ResetPasswordResponse;
import com.bitbi.dfm.account.presentation.dto.UpdateAccountRequestDto;
import com.bitbi.dfm.shared.presentation.dto.ErrorResponseDto;
import com.bitbi.dfm.shared.presentation.dto.PageResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST controller for account administration (Admin UI API).
 * <p>
 * Provides admin endpoints for account CRUD operations.
 * Requires Keycloak authentication with ROLE_ADMIN.
 * </p>
 * <p>
 * URL change from v2.x: /admin/accounts → /api/admin/accounts (breaking change)
 * </p>
 *
 * @author Data Forge Team
 * @version 3.0.0
 */
@RestController
@RequestMapping("/api/admin/accounts")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Accounts", description = "Account administration endpoints")
public class AccountAdminController {

    private static final Logger logger = LoggerFactory.getLogger(AccountAdminController.class);

    private final AccountService accountService;
    private final AccountStatisticsService accountStatisticsService;
    private final AccountProperties accountProperties;
    private final KeycloakAccountSyncService keycloakSyncService;
    private final AdminActionLogRepository auditLogRepository;
    private final com.bitbi.dfm.account.infrastructure.KeycloakAdminClient keycloakAdminClient;

    public AccountAdminController(
            AccountService accountService,
            AccountStatisticsService accountStatisticsService,
            AccountProperties accountProperties,
            KeycloakAccountSyncService keycloakSyncService,
            AdminActionLogRepository auditLogRepository,
            com.bitbi.dfm.account.infrastructure.KeycloakAdminClient keycloakAdminClient) {
        this.accountService = accountService;
        this.accountStatisticsService = accountStatisticsService;
        this.accountProperties = accountProperties;
        this.keycloakSyncService = keycloakSyncService;
        this.auditLogRepository = auditLogRepository;
        this.keycloakAdminClient = keycloakAdminClient;
    }

    /**
     * Create new account.
     * <p>
     * POST /admin/accounts
     * </p>
     *
     * @param request account details (email, name, phone, company)
     * @return created account response
     */
    @Operation(
            summary = "Create new account",
            description = "Creates a new account with email, name, and optional phone/company. Returns account details."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Account created successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = AccountResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid input (validation error)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "409", description = "Account already exists",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @PostMapping
    public ResponseEntity<AccountResponseDto> createAccount(
            @Valid @RequestBody CreateAccountRequestDto request) {

        logger.info("Creating account: email={}, name={}, phone={}, company={}",
                   request.email(), request.name(),
                   request.phone() != null ? "[set]" : "[not set]",
                   request.company() != null ? "[set]" : "[not set]");

        Account account = accountService.createAccount(
            request.email(),
            request.name(),
            request.phone(),
            request.company()
        );

        AccountResponseDto response = AccountResponseDto.fromEntity(
                account,
                accountProperties.getMaxConcurrentBatches()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get account by ID.
     * <p>
     * GET /admin/accounts/{id}
     * </p>
     *
     * @param accountId account identifier
     * @return account response with statistics
     */
    @Operation(
            summary = "Get account by ID",
            description = "Retrieves account details with embedded statistics."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Account found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = AccountWithStatsResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @GetMapping("/{id}")
    public ResponseEntity<AccountWithStatsResponseDto> getAccount(@PathVariable("id") UUID accountId) {
        Account account = accountService.getAccount(accountId);
        Map<String, Object> statistics = accountStatisticsService.getAccountStatistics(accountId);

        AccountWithStatsResponseDto response = AccountWithStatsResponseDto.fromEntityAndStats(account, statistics);
        return ResponseEntity.ok(response);
    }

    /**
     * Get account by ID with Keycloak integration data.
     * <p>
     * GET /admin/accounts/{id}/with-keycloak
     * </p>
     * <p>
     * Returns account details enriched with Keycloak status (enabled, temporary password, last login).
     * If account doesn't have Keycloak integration, returns 400 Bad Request.
     * </p>
     *
     * @param accountId account identifier
     * @return account response with Keycloak data
     */
    @Operation(
            summary = "Get account with Keycloak integration",
            description = "Retrieves account details with Keycloak authentication data (status, last login, etc.)."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Account retrieved successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = com.bitbi.dfm.account.presentation.dto.AccountWithKeycloakResponse.class))),
            @ApiResponse(responseCode = "400", description = "Account has no Keycloak integration",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @GetMapping("/{id}/with-keycloak")
    public ResponseEntity<com.bitbi.dfm.account.presentation.dto.AccountWithKeycloakResponse> getAccountWithKeycloak(
            @PathVariable("id") UUID accountId) {

        logger.info("Fetching account with Keycloak integration: accountId={}", accountId);

        Account account = accountService.getAccount(accountId);

        if (!account.hasKeycloakIntegration()) {
            throw new IllegalArgumentException("Account does not have Keycloak integration");
        }

        try {
            String keycloakUserId = account.getKeycloakUserId();
            org.keycloak.representations.idm.UserRepresentation keycloakUser =
                    keycloakAdminClient.getUser(keycloakUserId);
            Long lastLogin = keycloakAdminClient.getLastLogin(keycloakUserId);

            com.bitbi.dfm.account.presentation.dto.AccountWithKeycloakResponse response =
                    com.bitbi.dfm.account.presentation.dto.AccountWithKeycloakResponse.fromEntity(
                            account, keycloakUser, lastLogin);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to fetch Keycloak data for account {}", accountId, e);
            throw new RuntimeException("Failed to fetch Keycloak data: " + e.getMessage(), e);
        }
    }

    /**
     * List all accounts with Keycloak integration data.
     * <p>
     * GET /admin/accounts/with-keycloak?page=0&size=20&sort=createdAt,desc&search=john
     * </p>
     * <p>
     * Returns accounts enriched with Keycloak status (enabled, temporary password, last login).
     * Only includes accounts with Keycloak integration. Legacy accounts are excluded.
     * </p>
     *
     * @param page page number (default: 0)
     * @param size page size (default: 20)
     * @param sort sort field and direction (default: createdAt,desc)
     * @param search search query to filter by email or name (optional, case-insensitive)
     * @return paginated list of accounts with Keycloak data
     */
    @Operation(
            summary = "List accounts with Keycloak integration",
            description = "Retrieves paginated list of accounts with Keycloak authentication data (status, last login, etc.). Supports search by email or name."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Accounts retrieved successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = PageResponseDto.class)))
    })
    @GetMapping("/with-keycloak")
    public ResponseEntity<PageResponseDto<com.bitbi.dfm.account.presentation.dto.AccountWithKeycloakResponse>> listAccountsWithKeycloak(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort,
            @RequestParam(required = false) String search) {

        logger.info("Fetching accounts with Keycloak integration: page={}, size={}, search={}", page, size, search);

        // Parse sort parameter
        String[] sortParams = sort.split(",");
        String sortField = sortParams[0];
        Sort.Direction sortDirection = sortParams.length > 1 && "asc".equalsIgnoreCase(sortParams[1])
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        // Create pageable
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortDirection, sortField));

        // Get paginated accounts (all accounts, will filter in-app)
        Page<Account> accountPage = accountService.listAccounts(pageable);

        // Filter accounts with Keycloak and enrich with Keycloak data
        java.util.List<com.bitbi.dfm.account.presentation.dto.AccountWithKeycloakResponse> enrichedAccounts = accountPage.getContent().stream()
                .filter(account -> {
                    boolean hasKeycloak = account.hasKeycloakIntegration();
                    if (!hasKeycloak) {
                        logger.debug("Skipping account {} - no Keycloak integration", account.getId());
                    }
                    return hasKeycloak;
                })
                .filter(account -> {
                    // Apply search filter if provided
                    if (search == null || search.isBlank()) {
                        return true; // No search filter, include all
                    }
                    String searchLower = search.toLowerCase();
                    boolean matchesEmail = account.getEmail().toLowerCase().contains(searchLower);
                    boolean matchesName = account.getName() != null && account.getName().toLowerCase().contains(searchLower);
                    return matchesEmail || matchesName;
                })
                .map(account -> {
                    try {
                        String keycloakUserId = account.getKeycloakUserId();
                        logger.debug("Fetching Keycloak data for account {} with Keycloak user {}", account.getId(), keycloakUserId);

                        org.keycloak.representations.idm.UserRepresentation keycloakUser =
                                keycloakAdminClient.getUser(keycloakUserId);
                        Long lastLogin = keycloakAdminClient.getLastLogin(keycloakUserId);

                        return com.bitbi.dfm.account.presentation.dto.AccountWithKeycloakResponse.fromEntity(account, keycloakUser, lastLogin);
                    } catch (Exception e) {
                        logger.error("Failed to fetch Keycloak data for account {} (Keycloak user: {}): {}",
                                account.getId(), account.getKeycloakUserId(), e.getMessage(), e);
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();

        logger.info("Successfully enriched {} accounts out of {} total on page {}",
                enrichedAccounts.size(), accountPage.getContent().size(), page);

        // For pagination: use total from page (this is approximate, but works for MVP)
        // TODO: In production, use a separate query to count only Keycloak accounts
        PageResponseDto<com.bitbi.dfm.account.presentation.dto.AccountWithKeycloakResponse> response =
                new PageResponseDto<>(
                        enrichedAccounts,
                        accountPage.getNumber(),
                        accountPage.getSize(),
                        accountPage.getTotalElements(), // Use total from DB (includes legacy)
                        accountPage.getTotalPages()
                );

        return ResponseEntity.ok(response);
    }

    /**
     * List all active accounts with pagination.
     * <p>
     * GET /admin/accounts?page=0&size=20&sort=createdAt,desc
     * </p>
     *
     * @param page page number (default: 0)
     * @param size page size (default: 20)
     * @param sort sort field and direction (default: createdAt,desc)
     * @return paginated list of accounts
     */
    @Operation(
            summary = "List all accounts",
            description = "Retrieves a paginated list of all active accounts with sorting support."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Accounts retrieved successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = PageResponseDto.class)))
    })
    @GetMapping
    public ResponseEntity<PageResponseDto<AccountResponseDto>> listAccounts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {

        // Parse sort parameter
        String[] sortParams = sort.split(",");
        String sortField = sortParams[0];
        Sort.Direction sortDirection = sortParams.length > 1 && "asc".equalsIgnoreCase(sortParams[1])
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        // Create pageable
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortDirection, sortField));

        // Get paginated accounts
        Page<Account> accountPage = accountService.listAccounts(pageable);

        // Convert to response DTO
        int maxConcurrentBatches = accountProperties.getMaxConcurrentBatches();
        PageResponseDto<AccountResponseDto> response = PageResponseDto.of(
                accountPage,
                account -> AccountResponseDto.fromEntity(account, maxConcurrentBatches)
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Update account.
     * <p>
     * PUT /admin/accounts/{id}
     * </p>
     *
     * @param accountId account identifier
     * @param request   account update details (name, phone, company - all optional)
     * @return updated account response
     */
    @Operation(
            summary = "Update account",
            description = "Updates account name, phone, and/or company. All fields are optional (partial update)."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Account updated successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = AccountResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid input (validation error)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @PutMapping("/{id}")
    public ResponseEntity<AccountResponseDto> updateAccount(
            @PathVariable("id") UUID accountId,
            @Valid @RequestBody UpdateAccountRequestDto request) {

        logger.info("Updating account: accountId={}, name={}, phone={}, company={}",
                   accountId,
                   request.name() != null ? "[updating]" : "[unchanged]",
                   request.phone() != null ? "[updating]" : "[unchanged]",
                   request.company() != null ? "[updating]" : "[unchanged]");

        Account account = accountService.updateAccount(
            accountId,
            request.name(),
            request.phone(),
            request.company()
        );

        AccountResponseDto response = AccountResponseDto.fromEntity(
                account,
                accountProperties.getMaxConcurrentBatches()
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Deactivate account.
     * <p>
     * DELETE /admin/accounts/{id}
     * </p>
     *
     * @param accountId account identifier
     * @return no content response
     */
    @Operation(
            summary = "Deactivate account",
            description = "Soft-deletes an account by setting isActive=false. Cascades to all associated sites."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Account deactivated successfully"),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivateAccount(@PathVariable("id") UUID accountId) {
        logger.info("Deactivating account: accountId={}", accountId);

        accountService.deactivateAccount(accountId);

        return ResponseEntity.noContent().build();
    }

    /**
     * Get account statistics (deprecated short endpoint).
     * <p>
     * GET /admin/accounts/{id}/stats
     * </p>
     * <p>
     * <b>DEPRECATED:</b> Use {@link #getAccountStatistics(UUID)} instead.
     * This endpoint will be removed in v4.0.0. Use /api/admin/accounts/{id}/statistics
     * for the canonical statistics endpoint.
     * </p>
     *
     * @param accountId account identifier
     * @return account statistics formatted for admin UI
     * @deprecated Use {@link #getAccountStatistics(UUID)} instead (GET /api/admin/accounts/{id}/statistics)
     */
    @Deprecated(since = "3.1.0", forRemoval = true)
    @Operation(
            summary = "Get account statistics (deprecated - use /statistics)",
            description = "⚠️ DEPRECATED: Use /api/admin/accounts/{id}/statistics instead. This endpoint will be removed in v4.0.0.",
            deprecated = true
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Statistics retrieved successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = AccountStatisticsDto.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @GetMapping("/{id}/stats")
    public ResponseEntity<AccountStatisticsDto> getAccountStats(@PathVariable("id") UUID accountId) {
        logger.warn("DEPRECATED: /stats endpoint called. Use /statistics instead. Endpoint: /api/admin/accounts/{}/stats", accountId);
        // Delegate to canonical endpoint
        return getAccountStatistics(accountId);
    }

    /**
     * Get account statistics (canonical endpoint).
     * <p>
     * GET /admin/accounts/{id}/statistics
     * </p>
     * <p>
     * This is the canonical endpoint for retrieving account statistics.
     * The shorter /stats endpoint is deprecated and will be removed in v4.0.0.
     * </p>
     *
     * @param accountId account identifier
     * @return account statistics
     */
    @Operation(
            summary = "Get account statistics",
            description = "Retrieves account statistics including sites count, batches, files, and storage size. This is the canonical endpoint."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Statistics retrieved successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = AccountStatisticsDto.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @GetMapping("/{id}/statistics")
    public ResponseEntity<AccountStatisticsDto> getAccountStatistics(@PathVariable("id") UUID accountId) {
        Map<String, Object> statistics = accountStatisticsService.getAccountStatistics(accountId);
        AccountStatisticsDto response = AccountStatisticsDto.fromMap(statistics);
        return ResponseEntity.ok(response);
    }

    /**
     * Get global statistics.
     * <p>
     * GET /admin/statistics
     * </p>
     *
     * @return global statistics
     */
    @Operation(
            summary = "Get global statistics",
            description = "Retrieves platform-wide statistics across all accounts."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Global statistics retrieved successfully",
                    content = @Content(mediaType = "application/json"))
    })
    @GetMapping("../statistics")
    public ResponseEntity<Map<String, Object>> getGlobalStatistics() {
        Map<String, Object> statistics = accountStatisticsService.getGlobalStatistics();
        return ResponseEntity.ok(statistics);
    }

    /**
     * Create new account with Keycloak integration.
     * <p>
     * POST /admin/accounts/with-keycloak
     * </p>
     * <p>
     * Creates account in both Keycloak (for authentication) and PostgreSQL (for business data).
     * Returns temporary password that user must change on first login.
     * </p>
     *
     * @param request account creation request (email, name, phone, company, role)
     * @return created account with temporary password
     */
    @Operation(
            summary = "Create account with Keycloak integration",
            description = "Creates a new account with Keycloak authentication. Returns account details and temporary password."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Account created successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = CreateAccountResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid input (validation error)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "409", description = "Account already exists",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @PostMapping("/with-keycloak")
    public ResponseEntity<CreateAccountResponse> createAccountWithKeycloak(
            @Valid @RequestBody CreateAccountRequestDto request,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ipAddress,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {

        logger.info("Creating account with Keycloak: email={}, name={}, phone={}, company={}",
                request.email(), request.name(),
                request.phone() != null ? "[set]" : "[not set]",
                request.company() != null ? "[set]" : "[not set]");

        // TODO: Extract admin account ID from Spring Security context (Keycloak JWT sub claim)
        // For now, passing null - audit logs will have null admin_account_id until we implement
        // proper Keycloak user ID to local account ID mapping
        UUID adminAccountId = null;

        CreateAccountResponse response = keycloakSyncService.createAccount(
                request,
                adminAccountId,
                ipAddress != null ? ipAddress : "unknown",
                userAgent != null ? userAgent : "unknown"
        );

        logger.info("Account created successfully with Keycloak user ID: {}",
                response.account().keycloakUserId());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Lock account (disable in Keycloak).
     * <p>
     * POST /admin/accounts/{id}/lock
     * </p>
     * <p>
     * Disables the user in Keycloak, preventing login while preserving data.
     * Requires account to have Keycloak integration.
     * </p>
     *
     * @param accountId account identifier
     * @param ipAddress IP address from X-Forwarded-For header
     * @param userAgent User-Agent from header
     * @return updated account with keycloakEnabled=false
     */
    @Operation(
            summary = "Lock account (disable Keycloak user)",
            description = "Locks an account by disabling the associated Keycloak user. User cannot login until unlocked."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Account locked successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = com.bitbi.dfm.account.presentation.dto.AccountWithKeycloakResponse.class))),
            @ApiResponse(responseCode = "400", description = "Account already locked or no Keycloak integration",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @PostMapping("/{id}/lock")
    public ResponseEntity<com.bitbi.dfm.account.presentation.dto.AccountWithKeycloakResponse> lockAccount(
            @PathVariable("id") UUID accountId,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ipAddress,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {

        logger.info("Locking account: accountId={}", accountId);

        // TODO: Extract admin account ID from Spring Security context
        // For now, passing null - audit logs will have null admin_account_id until we implement
        // proper Keycloak user ID to Account mapping (V13 migration makes this column nullable)
        UUID adminAccountId = null;

        com.bitbi.dfm.account.presentation.dto.AccountWithKeycloakResponse response = keycloakSyncService.lockAccount(
                accountId,
                adminAccountId,
                ipAddress != null ? ipAddress : "unknown",
                userAgent != null ? userAgent : "unknown"
        );

        logger.info("Account locked successfully: accountId={}, keycloakUserId={}",
                accountId, response.keycloakUserId());

        return ResponseEntity.ok(response);
    }

    /**
     * Unlock account (enable in Keycloak).
     * <p>
     * POST /admin/accounts/{id}/unlock
     * </p>
     * <p>
     * Enables the user in Keycloak, allowing login.
     * Requires account to have Keycloak integration.
     * </p>
     *
     * @param accountId account identifier
     * @param ipAddress IP address from X-Forwarded-For header
     * @param userAgent User-Agent from header
     * @return updated account with keycloakEnabled=true
     */
    @Operation(
            summary = "Unlock account (enable Keycloak user)",
            description = "Unlocks an account by enabling the associated Keycloak user. User can login again."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Account unlocked successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = com.bitbi.dfm.account.presentation.dto.AccountWithKeycloakResponse.class))),
            @ApiResponse(responseCode = "400", description = "Account already unlocked or no Keycloak integration",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @PostMapping("/{id}/unlock")
    public ResponseEntity<com.bitbi.dfm.account.presentation.dto.AccountWithKeycloakResponse> unlockAccount(
            @PathVariable("id") UUID accountId,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ipAddress,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {

        logger.info("Unlocking account: accountId={}", accountId);

        // TODO: Extract admin account ID from Spring Security context
        // For now, passing null - audit logs will have null admin_account_id until we implement
        // proper Keycloak user ID to Account mapping (V13 migration makes this column nullable)
        UUID adminAccountId = null;

        com.bitbi.dfm.account.presentation.dto.AccountWithKeycloakResponse response = keycloakSyncService.unlockAccount(
                accountId,
                adminAccountId,
                ipAddress != null ? ipAddress : "unknown",
                userAgent != null ? userAgent : "unknown"
        );

        logger.info("Account unlocked successfully: accountId={}, keycloakUserId={}",
                accountId, response.keycloakUserId());

        return ResponseEntity.ok(response);
    }

    /**
     * Reset account password to new temporary password.
     * <p>
     * POST /admin/accounts/{id}/reset-password
     * </p>
     * <p>
     * Generates a new temporary password and resets it in Keycloak.
     * User will be forced to change password on next login.
     * Password expires in 30 days.
     * </p>
     *
     * @param accountId account identifier
     * @param ipAddress IP address from X-Forwarded-For header
     * @param userAgent User-Agent from header
     * @return reset response with temporary password and expiration
     */
    @Operation(
            summary = "Reset account password",
            description = "Resets password to new temporary password. User must change it on next login. Password expires in 30 days."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Password reset successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResetPasswordResponse.class))),
            @ApiResponse(responseCode = "400", description = "Account has no Keycloak integration",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @PostMapping("/{id}/reset-password")
    public ResponseEntity<ResetPasswordResponse> resetPassword(
            @PathVariable("id") UUID accountId,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ipAddress,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {

        logger.info("Resetting password for account: accountId={}", accountId);

        // TODO: Extract admin account ID from Spring Security context
        // For now, passing null - audit logs will have null admin_account_id until we implement
        // proper Keycloak user ID to Account mapping (V13 migration makes this column nullable)
        UUID adminAccountId = null;

        ResetPasswordResponse response = keycloakSyncService.resetPassword(
                accountId,
                adminAccountId,
                ipAddress != null ? ipAddress : "unknown",
                userAgent != null ? userAgent : "unknown"
        );

        logger.info("Password reset successfully: accountId={}, expiresAt={}",
                accountId, response.expiresAt());

        return ResponseEntity.ok(response);
    }

    /**
     * Get audit logs for an account.
     * <p>
     * GET /admin/accounts/{id}/audit-logs?page=0&size=20&sort=createdAt,desc
     * </p>
     * <p>
     * Returns paginated history of administrative actions performed on the account
     * (create, lock, unlock, reset password).
     * </p>
     *
     * @param accountId account identifier
     * @param page      page number (default: 0)
     * @param size      page size (default: 20)
     * @param sort      sort field and direction (default: createdAt,desc)
     * @return paginated list of audit log entries
     */
    @Operation(
            summary = "Get account audit logs",
            description = "Retrieves paginated audit trail of administrative actions performed on this account."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Audit logs retrieved successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = PageResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @GetMapping("/{id}/audit-logs")
    public ResponseEntity<PageResponseDto<AdminActionLogResponseDto>> getAccountAuditLogs(
            @PathVariable("id") UUID accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {

        logger.info("Fetching audit logs for account: accountId={}, page={}, size={}", accountId, page, size);

        // Verify account exists
        accountService.getAccount(accountId);

        // Parse sort parameter
        String[] sortParams = sort.split(",");
        String sortField = sortParams[0];
        Sort.Direction sortDirection = sortParams.length > 1 && "asc".equalsIgnoreCase(sortParams[1])
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        // Create pageable
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortDirection, sortField));

        // Get paginated audit logs
        Page<AdminActionLog> logPage = auditLogRepository.findByTargetAccountId(accountId, pageable);

        // Convert to response DTO
        PageResponseDto<AdminActionLogResponseDto> response = PageResponseDto.of(
                logPage,
                AdminActionLogResponseDto::fromEntity
        );

        return ResponseEntity.ok(response);
    }
}
