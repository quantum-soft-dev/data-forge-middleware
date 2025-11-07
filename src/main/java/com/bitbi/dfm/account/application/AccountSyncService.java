package com.bitbi.dfm.account.application;

import com.auth0.client.mgmt.ManagementAPI;
import com.auth0.exception.Auth0Exception;
import com.auth0.json.mgmt.users.User;
import com.auth0.net.Request;
import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.domain.AccountAuth0LinkedEvent;
import com.bitbi.dfm.account.domain.AccountRepository;
import com.bitbi.dfm.shared.exception.Auth0RateLimitException;
import com.bitbi.dfm.shared.exception.Auth0ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Service for synchronizing accounts between PostgreSQL and identity provider.
 * <p>
 * Implements Two-Phase Commit Pattern:
 * 1. Create user in identity provider (authentication layer)
 * 2. Create account in PostgreSQL (business layer) with identity provider user ID
 * 3. Update identity provider user with PostgreSQL accountId (bidirectional mapping)
 * 4. If PostgreSQL fails, delete identity provider user (rollback compensation)
 * </p>
 * <p>
 * Features:
 * - Automatic retry with exponential backoff for transient failures
 * - Rate limit detection and handling
 * - Compensating transactions for failure scenarios
 * - Domain event publishing for audit logging
 * - MDC logging context for traceability
 * </p>
 *
 * User Story: US1 - Admin Creates User Account
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
@Profile("!test")
public class AccountSyncService {

    private static final Logger logger = LoggerFactory.getLogger(AccountSyncService.class);

    private final ManagementAPI managementAPI;
    private final AccountRepository accountRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${auth0.database-connection}")
    private String databaseConnection;

    public AccountSyncService(
        ManagementAPI managementAPI,
        AccountRepository accountRepository,
        ApplicationEventPublisher eventPublisher
    ) {
        this.managementAPI = managementAPI;
        this.accountRepository = accountRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Create account with identity provider integration (two-phase commit).
     * <p>
     * Phase 1: Create user in identity provider
     * Phase 2: Create account in PostgreSQL
     * Phase 3: Update identity provider with accountId (bidirectional mapping)
     * If Phase 2 or 3 fails: Delete identity provider user (compensating transaction)
     * </p>
     *
     * @param email User's email address
     * @param name User's full name
     * @param phone User's phone number (optional)
     * @param company User's company name (optional)
     * @return Created account with identity provider user ID
     * @throws Auth0ServiceUnavailableException if identity provider is unavailable
     * @throws Auth0RateLimitException if rate limit exceeded
     * @throws AccountService.AccountAlreadyExistsException if account exists
     */
    @Transactional
    @Retryable(
        retryFor = {Auth0ServiceUnavailableException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2.0)
    )
    public AccountCreationResult createAccount(
        String email,
        String name,
        String phone,
        String company
    ) {
        // Check if account already exists
        if (accountRepository.findByEmail(email).isPresent()) {
            throw new AccountService.AccountAlreadyExistsException(
                "Account with email " + email + " already exists"
            );
        }

        String auth0UserId = null;

        try {
            // PHASE 1: Create user in Auth0
            logger.info("Creating Auth0 user for email: {}", email);
            String temporaryPassword = generateSecurePassword();

            User auth0User = createAuth0User(email, name, temporaryPassword);
            auth0UserId = auth0User.getId();

            MDC.put("auth0UserId", auth0UserId);
            logger.info("Auth0 user created successfully: {}", auth0UserId);

            // PHASE 2: Create account in PostgreSQL
            logger.info("Creating PostgreSQL account for Auth0 user: {}", auth0UserId);
            Account account = Account.createWithIdentityProvider(auth0UserId, email, name, phone, company);
            account = accountRepository.save(account);

            UUID accountId = account.getId();
            MDC.put("accountId", accountId.toString());
            logger.info("PostgreSQL account created successfully: {}", accountId);

            // PHASE 3: Update Auth0 user with PostgreSQL accountId (bidirectional mapping)
            try {
                updateAuth0UserMetadata(auth0UserId, accountId);
                logger.info("Auth0 user metadata updated with accountId: {}", accountId);
            } catch (Exception ex) {
                // Non-critical error - account creation succeeded, metadata update failed
                logger.warn("Failed to update Auth0 user metadata (non-critical): {}", ex.getMessage());
                // Continue - account is usable without metadata
            }

            // Publish domain event for audit logging
            eventPublisher.publishEvent(AccountAuth0LinkedEvent.of(account));

            logger.info("Account creation completed successfully: {} -> {}", email, accountId);
            return new AccountCreationResult(account, temporaryPassword);

        } catch (Auth0Exception ex) {
            logger.error("Auth0 error during account creation for email: {}", email, ex);

            // Compensating transaction: Delete Auth0 user if PostgreSQL creation failed
            if (auth0UserId != null) {
                try {
                    deleteAuth0User(auth0UserId);
                    logger.warn("Compensating transaction: Deleted orphaned Auth0 user: {}", auth0UserId);
                } catch (Exception deleteEx) {
                    logger.error("CRITICAL: Failed to delete orphaned Auth0 user: {} - Manual cleanup required!",
                        auth0UserId, deleteEx);
                }
            }

            throw convertAuth0Exception(ex, "create user");
        } catch (RuntimeException ex) {
            logger.error("PostgreSQL error during account creation for email: {}", email, ex);

            // Compensating transaction: Delete Auth0 user if PostgreSQL creation failed
            if (auth0UserId != null) {
                try {
                    deleteAuth0User(auth0UserId);
                    logger.warn("Compensating transaction: Deleted orphaned Auth0 user: {}", auth0UserId);
                } catch (Exception deleteEx) {
                    logger.error("CRITICAL: Failed to delete orphaned Auth0 user: {} - Manual cleanup required!",
                        auth0UserId, deleteEx);
                }
            }

            throw ex;
        } finally {
            MDC.remove("auth0UserId");
            MDC.remove("accountId");
        }
    }

    /**
     * Create user in Auth0 with temporary password.
     *
     * @param email User's email
     * @param name User's name
     * @param temporaryPassword Generated temporary password
     * @return Created Auth0 user
     * @throws Auth0Exception if Auth0 API call fails
     */
    private User createAuth0User(String email, String name, String temporaryPassword) throws Auth0Exception {
        User user = new User();
        user.setEmail(email);
        user.setName(name);
        user.setConnection(databaseConnection);
        user.setPassword(temporaryPassword);
        user.setVerifyEmail(false); // Don't send verification email for temporary password

        Request<User> request = managementAPI.users().create(user);
        return request.execute().getBody();
    }

    /**
     * Update Auth0 user app_metadata with PostgreSQL accountId.
     *
     * @param auth0UserId Auth0 user ID
     * @param accountId PostgreSQL account UUID
     * @throws Auth0Exception if Auth0 API call fails
     */
    private void updateAuth0UserMetadata(String auth0UserId, UUID accountId) throws Auth0Exception {
        User updateRequest = new User();
        Map<String, Object> appMetadata = Map.of(
            "accountId", accountId.toString(),
            "created_by", "admin_api",
            "sync_timestamp", Instant.now().toString()
        );
        updateRequest.setAppMetadata(appMetadata);

        Request<User> request = managementAPI.users().update(auth0UserId, updateRequest);
        request.execute();
    }

    /**
     * Delete Auth0 user (compensating transaction).
     *
     * @param auth0UserId Auth0 user ID to delete
     * @throws Auth0Exception if Auth0 API call fails
     */
    private void deleteAuth0User(String auth0UserId) throws Auth0Exception {
        Request<Void> request = managementAPI.users().delete(auth0UserId);
        request.execute();
    }

    /**
     * Generate secure random password for Auth0 user.
     * <p>
     * Password requirements:
     * - 16 characters long
     * - Contains uppercase, lowercase, digits, and special characters
     * - Shuffled to avoid predictable patterns
     * </p>
     *
     * @return Generated password
     */
    private String generateSecurePassword() {
        String upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String lower = "abcdefghijklmnopqrstuvwxyz";
        String digits = "0123456789";
        String special = "!@#$%^&*";
        String all = upper + lower + digits + special;

        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder(16);

        // Ensure at least one character from each category
        password.append(upper.charAt(random.nextInt(upper.length())));
        password.append(lower.charAt(random.nextInt(lower.length())));
        password.append(digits.charAt(random.nextInt(digits.length())));
        password.append(special.charAt(random.nextInt(special.length())));

        // Fill remaining with random characters
        for (int i = 4; i < 16; i++) {
            password.append(all.charAt(random.nextInt(all.length())));
        }

        // Shuffle to avoid predictable pattern
        char[] chars = password.toString().toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char temp = chars[i];
            chars[i] = chars[j];
            chars[j] = temp;
        }

        return new String(chars);
    }

    /**
     * Convert Auth0Exception to domain exception.
     *
     * @param ex Auth0 exception
     * @param operation Operation that failed
     * @return Domain exception
     */
    private RuntimeException convertAuth0Exception(Auth0Exception ex, String operation) {
        String message = ex.getMessage();

        // Check if it's an APIException with status code
        if (ex instanceof com.auth0.exception.APIException apiException) {
            int statusCode = apiException.getStatusCode();

            // Rate limit (429)
            if (statusCode == 429) {
                return Auth0RateLimitException.forOperation(operation, 60);
            }

            // Service unavailable (5xx)
            if (statusCode >= 500) {
                return Auth0ServiceUnavailableException.forOperation(operation, ex);
            }
        }

        // Client errors or generic Auth0Exception - wrap in RuntimeException
        return new RuntimeException("Auth0 API error during " + operation + ": " + message, ex);
    }

    /**
     * Result of account creation with Auth0 integration.
     *
     * @param account Created account entity
     * @param temporaryPassword Generated temporary password for Auth0 user
     */
    public record AccountCreationResult(
        Account account,
        String temporaryPassword
    ) {
    }
}
