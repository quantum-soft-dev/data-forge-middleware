package com.bitbi.dfm.account.presentation;

import com.bitbi.dfm.account.application.AccountService;
import com.bitbi.dfm.account.application.AccountStatisticsService;
import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.presentation.dto.AccountResponseDto;
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

import java.util.*;
import java.util.stream.Collectors;

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
    @PostMapping
    public ResponseEntity<?> createAccount(@RequestBody Map<String, String> request) {
        try {
            String email = request.get("email");
            String name = request.get("name");

            if (email == null || email.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(createErrorResponse("Email is required"));
            }

            if (name == null || name.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(createErrorResponse("Name is required"));
            }

            logger.info("Creating account: email={}, name={}", email, name);

            Account account = accountService.createAccount(email, name);

            AccountResponseDto response = AccountResponseDto.fromEntity(account);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (AccountService.AccountAlreadyExistsException e) {
            logger.warn("Account already exists: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(createErrorResponse(e.getMessage()));

        } catch (IllegalArgumentException e) {
            logger.warn("Invalid account data: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(createErrorResponse(e.getMessage()));

        } catch (Exception e) {
            logger.error("Error creating account", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to create account"));
        }
    }

    /**
     * Get account by ID.
     * <p>
     * GET /admin/accounts/{id}
     * </p>
     *
     * @param accountId account identifier
     * @return account response
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getAccount(@PathVariable("id") UUID accountId) {
        try {
            Account account = accountService.getAccount(accountId);
            AccountResponseDto dto = AccountResponseDto.fromEntity(account);

            // Add statistics to the response
            Map<String, Object> statistics = accountStatisticsService.getAccountStatistics(accountId);

            // Build extended response with DTO fields + statistics
            Map<String, Object> response = new HashMap<>();
            response.put("id", dto.id());
            response.put("email", dto.email());
            response.put("name", dto.name());
            response.put("isActive", dto.isActive());
            response.put("createdAt", dto.createdAt());
            response.put("sitesCount", statistics.get("totalSites"));
            response.put("totalBatches", statistics.get("totalBatches"));
            response.put("totalUploadedFiles", statistics.get("totalFiles"));

            return ResponseEntity.ok(response);

        } catch (AccountService.AccountNotFoundException e) {
            logger.warn("Account not found: {}", accountId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse("Account not found"));

        } catch (Exception e) {
            logger.error("Error getting account: accountId={}", accountId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to retrieve account"));
        }
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
    @GetMapping
    public ResponseEntity<Map<String, Object>> listAccounts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        try {
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

            // Convert to response
            List<AccountResponseDto> accountList = accountPage.getContent().stream()
                    .map(AccountResponseDto::fromEntity)
                    .collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("content", accountList);
            response.put("page", accountPage.getNumber());
            response.put("size", accountPage.getSize());
            response.put("totalElements", accountPage.getTotalElements());
            response.put("totalPages", accountPage.getTotalPages());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error listing accounts", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to list accounts"));
        }
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
    @PutMapping("/{id}")
    public ResponseEntity<?> updateAccount(
            @PathVariable("id") UUID accountId,
            @RequestBody Map<String, String> request) {

        try {
            String name = request.get("name");

            if (name == null || name.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(createErrorResponse("Name is required"));
            }

            logger.info("Updating account: accountId={}, name={}", accountId, name);

            Account account = accountService.updateAccount(accountId, name);

            AccountResponseDto response = AccountResponseDto.fromEntity(account);
            return ResponseEntity.ok(response);

        } catch (AccountService.AccountNotFoundException e) {
            logger.warn("Account not found: {}", accountId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse("Account not found"));

        } catch (IllegalArgumentException e) {
            logger.warn("Invalid account data: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(createErrorResponse(e.getMessage()));

        } catch (Exception e) {
            logger.error("Error updating account: accountId={}", accountId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to update account"));
        }
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
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deactivateAccount(@PathVariable("id") UUID accountId) {
        try {
            logger.info("Deactivating account: accountId={}", accountId);

            accountService.deactivateAccount(accountId);

            return ResponseEntity.noContent().build();

        } catch (AccountService.AccountNotFoundException e) {
            logger.warn("Account not found: {}", accountId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse("Account not found"));

        } catch (Exception e) {
            logger.error("Error deactivating account: accountId={}", accountId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to deactivate account"));
        }
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
    @GetMapping("/{id}/stats")
    public ResponseEntity<Map<String, Object>> getAccountStats(@PathVariable("id") UUID accountId) {
        try {
            Map<String, Object> statistics = accountStatisticsService.getAccountStatistics(accountId);

            // Map to expected admin response format
            Map<String, Object> response = new HashMap<>();
            response.put("accountId", statistics.get("accountId"));
            response.put("sitesCount", statistics.get("totalSites"));
            response.put("activeSites", statistics.get("activeSites"));
            response.put("totalBatches", statistics.get("totalBatches"));
            response.put("completedBatches", 0); // TODO: Add to service
            response.put("failedBatches", 0); // TODO: Add to service
            response.put("totalFiles", statistics.get("totalFiles"));
            response.put("totalStorageSize", 0L); // TODO: Add to service

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error getting account statistics: accountId={}", accountId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to retrieve statistics"));
        }
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
    @GetMapping("/{id}/statistics")
    public ResponseEntity<Map<String, Object>> getAccountStatistics(@PathVariable("id") UUID accountId) {
        try {
            Map<String, Object> statistics = accountStatisticsService.getAccountStatistics(accountId);
            return ResponseEntity.ok(statistics);

        } catch (Exception e) {
            logger.error("Error getting account statistics: accountId={}", accountId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to retrieve statistics"));
        }
    }

    /**
     * Get global statistics.
     * <p>
     * GET /admin/statistics
     * </p>
     *
     * @return global statistics
     */
    @GetMapping("../statistics")
    public ResponseEntity<Map<String, Object>> getGlobalStatistics() {
        try {
            Map<String, Object> statistics = accountStatisticsService.getGlobalStatistics();
            return ResponseEntity.ok(statistics);

        } catch (Exception e) {
            logger.error("Error getting global statistics", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to retrieve statistics"));
        }
    }

    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        return error;
    }
}
