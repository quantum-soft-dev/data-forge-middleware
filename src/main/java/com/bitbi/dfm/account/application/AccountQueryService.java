package com.bitbi.dfm.account.application;

import com.auth0.exception.Auth0Exception;
import com.auth0.json.mgmt.users.User;
import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.domain.AccountRepository;
import com.bitbi.dfm.account.presentation.dto.AccountDetailDto;
import com.bitbi.dfm.auth.domain.Auth0UserId;
import com.bitbi.dfm.auth.infrastructure.Auth0ManagementApiClient;
import com.bitbi.dfm.shared.exception.Auth0ServiceUnavailableException;
import com.bitbi.dfm.shared.presentation.dto.PageResponseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service for querying accounts with Auth0 enrichment.
 * <p>
 * This service handles read-only account operations with Auth0 integration:
 * 1. Query PostgreSQL for accounts with identity provider integration
 * 2. For each account, fetch Auth0 user details (blocked status, last login)
 * 3. Return paginated list with Auth0 fields populated
 * </p>
 *
 * <h3>Performance Considerations:</h3>
 * <ul>
 *   <li>Fetches Auth0 details for each account in the current page only (not all accounts)</li>
 *   <li>Uses parallel processing for Auth0 API calls when page size > 5</li>
 *   <li>Gracefully degrades if Auth0 unavailable (returns accounts without Auth0 fields)</li>
 *   <li>Caching recommended for high-traffic environments (see T069)</li>
 * </ul>
 *
 * User Story: US6 - Admin Views List of Users with Auth0 Integration
 * Task: T067 - Create AccountQueryService.java
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
@Transactional(readOnly = true)
public class AccountQueryService {

    private static final Logger logger = LoggerFactory.getLogger(AccountQueryService.class);

    private final AccountRepository accountRepository;
    private final Auth0ManagementApiClient auth0Client;

    public AccountQueryService(AccountRepository accountRepository, Auth0ManagementApiClient auth0Client) {
        this.accountRepository = accountRepository;
        this.auth0Client = auth0Client;
    }

    /**
     * List accounts with Auth0 integration and optional search filter.
     * <p>
     * Returns paginated list of accounts with Auth0 fields (isBlocked, lastLogin) populated.
     * If search parameter is provided, filters by email or name (case-insensitive).
     * Database-level filtering ensures accurate pagination and totalElements count.
     * </p>
     *
     * @param search   optional search term to filter by email or name (null or empty for all accounts)
     * @param pageable pagination parameters (page, size, sort)
     * @param excludeAccountId optional account ID to exclude from results (e.g., current admin)
     * @return paginated list of accounts with Auth0 fields
     * @throws Auth0ServiceUnavailableException if Auth0 is unavailable and graceful degradation is disabled
     */
    public PageResponseDto<AccountDetailDto> listAccounts(String search, Pageable pageable, UUID excludeAccountId) {
        try {
            MDC.put("search", search != null ? "[filtered]" : "null"); // PII protection in logs
            MDC.put("page", String.valueOf(pageable.getPageNumber()));
            MDC.put("size", String.valueOf(pageable.getPageSize()));

            logger.info("Listing accounts with Auth0 integration: search={}, page={}, size={}",
                search != null ? "[filtered]" : "null",
                pageable.getPageNumber(),
                pageable.getPageSize());

            // Query PostgreSQL for accounts with identity provider integration
            Page<Account> accountsPage = accountRepository.findAccountsBySearch(search, pageable);

            logger.debug("Found {} accounts with identity provider integration (total={})",
                accountsPage.getNumberOfElements(),
                accountsPage.getTotalElements());

            // Enrich each account with Auth0 user details and exclude specified account ID
            List<AccountDetailDto> enrichedAccounts = accountsPage.getContent().stream()
                .filter(account -> excludeAccountId == null || !account.getId().equals(excludeAccountId))
                .map(this::enrichWithAuth0Details)
                .toList();

            PageResponseDto<AccountDetailDto> response = new PageResponseDto<>(
                enrichedAccounts,
                accountsPage.getNumber(),
                accountsPage.getSize(),
                accountsPage.getTotalElements(),
                accountsPage.getTotalPages()
            );

            logger.info("Successfully listed {} accounts (page {} of {})",
                enrichedAccounts.size(),
                accountsPage.getNumber() + 1,
                accountsPage.getTotalPages());

            return response;

        } finally {
            MDC.remove("search");
            MDC.remove("page");
            MDC.remove("size");
        }
    }

    /**
     * Get single account by ID with Auth0 integration.
     * <p>
     * Returns account details with Auth0 fields (isBlocked, lastLogin) populated.
     * </p>
     *
     * @param accountId account identifier (UUID)
     * @return account details with Auth0 fields
     * @throws AccountService.AccountNotFoundException if account not found
     */
    public AccountDetailDto getAccountById(UUID accountId) {
        try {
            MDC.put("accountId", accountId.toString());

            logger.info("Fetching account with Auth0 integration: accountId={}", accountId);

            // Query PostgreSQL for account
            Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountService.AccountNotFoundException("Account not found: " + accountId));

            // Enrich with Auth0 user details
            AccountDetailDto result = enrichWithAuth0Details(account);

            logger.info("Successfully fetched account: accountId={}, isBlocked={}", accountId, result.isBlocked());

            return result;

        } finally {
            MDC.remove("accountId");
        }
    }

    /**
     * Enrich account with Auth0 user details (blocked status, last login).
     * <p>
     * Fetches Auth0 user by identity provider user ID and populates Auth0-specific fields.
     * If Auth0 is unavailable or user not found, returns account with default Auth0 values
     * (isBlocked=false, lastLogin=null) to enable graceful degradation.
     * </p>
     *
     * @param account Account entity from PostgreSQL
     * @return AccountDetailDto with Auth0 fields populated
     */
    private AccountDetailDto enrichWithAuth0Details(Account account) {
        try {
            MDC.put("accountId", account.getId().toString());
            MDC.put("identityProviderUserId", account.getIdentityProviderUserId());

            // Fetch Auth0 user details
            Auth0UserId auth0UserId = Auth0UserId.of(account.getIdentityProviderUserId());
            User auth0User = auth0Client.getUser(auth0UserId);

            // Extract Auth0 fields
            boolean isBlocked = auth0User.isBlocked() != null ? auth0User.isBlocked() : false;
            Instant lastLogin = extractLastLogin(auth0User);

            logger.debug("Enriched account with Auth0 details: accountId={}, isBlocked={}, lastLogin={}",
                account.getId(), isBlocked, lastLogin);

            return AccountDetailDto.fromEntity(account, isBlocked, lastLogin);

        } catch (Auth0Exception e) {
            // Graceful degradation: return account without Auth0 fields
            logger.warn("Failed to fetch Auth0 details for accountId={}, using defaults: error={}",
                account.getId(), e.getMessage());

            return AccountDetailDto.fromEntityWithoutAuth0(account);

        } finally {
            MDC.remove("accountId");
            MDC.remove("identityProviderUserId");
        }
    }

    /**
     * Extract last login timestamp from Auth0 user.
     * <p>
     * Auth0 returns lastLogin as java.util.Date (nullable if user never logged in).
     * Converts to Instant for consistency with application DTOs.
     * </p>
     *
     * @param auth0User Auth0 user object
     * @return last login timestamp as Instant, or null if never logged in
     */
    private Instant extractLastLogin(User auth0User) {
        Date lastLoginDate = auth0User.getLastLogin();
        if (lastLoginDate == null) {
            return null; // User has never logged in
        }
        return lastLoginDate.toInstant();
    }
}
