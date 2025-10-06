package com.bitbi.dfm.account.presentation;

import com.bitbi.dfm.account.application.AccountService;
import com.bitbi.dfm.account.application.AccountStatisticsService;
import com.bitbi.dfm.account.domain.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for account administration.
 * <p>
 * Provides admin endpoints for account CRUD operations.
 * Requires Keycloak authentication with ROLE_ADMIN.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@RestController
@RequestMapping("/admin/accounts")
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
    public ResponseEntity<Map<String, Object>> createAccount(@RequestBody Map<String, String> request) {
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

            Map<String, Object> response = createAccountResponse(account);
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
    public ResponseEntity<Map<String, Object>> getAccount(@PathVariable("id") UUID accountId) {
        try {
            Account account = accountService.getAccount(accountId);
            Map<String, Object> response = createAccountResponse(account);
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
     * List all active accounts.
     * <p>
     * GET /admin/accounts
     * </p>
     *
     * @return list of accounts
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listAccounts() {
        try {
            List<Account> accounts = accountService.listActiveAccounts();

            List<Map<String, Object>> accountList = accounts.stream()
                    .map(this::createAccountResponse)
                    .collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("accounts", accountList);
            response.put("total", accountList.size());

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
    public ResponseEntity<Map<String, Object>> updateAccount(
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

            Map<String, Object> response = createAccountResponse(account);
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

    private Map<String, Object> createAccountResponse(Account account) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", account.getId());
        response.put("email", account.getEmail());
        response.put("name", account.getName());
        response.put("isActive", account.getIsActive());
        response.put("createdAt", account.getCreatedAt().toString());
        response.put("updatedAt", account.getUpdatedAt().toString());
        return response;
    }

    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        return error;
    }
}
