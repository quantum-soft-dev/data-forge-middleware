package com.bitbi.dfm.account.presentation;

import com.bitbi.dfm.account.application.AccountService;
import com.bitbi.dfm.account.application.AccountStatisticsService;
import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.presentation.dto.AccountResponseDto;
import com.bitbi.dfm.account.presentation.dto.AccountStatisticsDto;
import com.bitbi.dfm.account.presentation.dto.AccountWithStatsResponseDto;
import com.bitbi.dfm.account.presentation.dto.CreateAccountRequestDto;
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

    public AccountAdminController(AccountService accountService, AccountStatisticsService accountStatisticsService) {
        this.accountService = accountService;
        this.accountStatisticsService = accountStatisticsService;
    }

    /**
     * Create new account.
     * <p>
     * POST /admin/accounts
     * </p>
     *
     * @param request account details (email, name)
     * @return created account response
     */
    @Operation(
            summary = "Create new account",
            description = "Creates a new account with email and name. Returns account details."
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

        logger.info("Creating account: email={}, name={}", request.email(), request.name());

        Account account = accountService.createAccount(request.email(), request.name());

        AccountResponseDto response = AccountResponseDto.fromEntity(account);
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
        PageResponseDto<AccountResponseDto> response = PageResponseDto.of(accountPage, AccountResponseDto::fromEntity);

        return ResponseEntity.ok(response);
    }

    /**
     * Update account.
     * <p>
     * PUT /admin/accounts/{id}
     * </p>
     *
     * @param accountId account identifier
     * @param request   account update details (name)
     * @return updated account response
     */
    @Operation(
            summary = "Update account",
            description = "Updates account display name."
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

        logger.info("Updating account: accountId={}, name={}", accountId, request.name());

        Account account = accountService.updateAccount(accountId, request.name());

        AccountResponseDto response = AccountResponseDto.fromEntity(account);
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
     * Get account statistics (admin endpoint).
     * <p>
     * GET /admin/accounts/{id}/stats
     * </p>
     *
     * @param accountId account identifier
     * @return account statistics formatted for admin UI
     */
    @Operation(
            summary = "Get account statistics (short endpoint)",
            description = "Retrieves account statistics including sites count, batches, files, and storage size."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Statistics retrieved successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = AccountStatisticsDto.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    @GetMapping("/{id}/stats")
    public ResponseEntity<AccountStatisticsDto> getAccountStats(@PathVariable("id") UUID accountId) {
        Map<String, Object> statistics = accountStatisticsService.getAccountStatistics(accountId);
        AccountStatisticsDto response = AccountStatisticsDto.fromMap(statistics);
        return ResponseEntity.ok(response);
    }

    /**
     * Get account statistics.
     * <p>
     * GET /admin/accounts/{id}/statistics
     * </p>
     *
     * @param accountId account identifier
     * @return account statistics
     */
    @Operation(
            summary = "Get account statistics (long endpoint)",
            description = "Retrieves account statistics including sites count, batches, files, and storage size."
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
}
