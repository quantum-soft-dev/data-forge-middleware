package com.bitbi.dfm.account.application;

import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.domain.AdminActionLog;
import com.bitbi.dfm.account.domain.AdminActionType;
import com.bitbi.dfm.account.infrastructure.AdminActionLogRepository;
import com.bitbi.dfm.account.domain.AccountRepository;
import com.bitbi.dfm.account.infrastructure.KeycloakAdminClient;
import com.bitbi.dfm.account.presentation.dto.AccountWithKeycloakResponse;
import com.bitbi.dfm.account.presentation.dto.CreateAccountRequestDto;
import com.bitbi.dfm.account.presentation.dto.CreateAccountResponse;
import com.bitbi.dfm.account.presentation.dto.ResetPasswordResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * KeycloakAccountSyncService handles account operations with Keycloak synchronization.
 * <p>
 * Implements two-phase commit pattern:
 * 1. Create/update in Keycloak (source of truth for authentication)
 * 2. Create/update in PostgreSQL (business data)
 * 3. If PostgreSQL fails, rollback Keycloak changes
 * </p>
 * <p>
 * All operations are logged to admin_action_logs for audit trail.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
@Transactional
public class KeycloakAccountSyncService {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAccountSyncService.class);

    private final KeycloakAdminClient keycloakClient;
    private final AccountRepository accountRepository;
    private final TemporaryPasswordGenerator passwordGenerator;
    private final AdminActionLogRepository auditLogRepository;
    private final Counter accountCreatedSuccessCounter;
    private final Counter accountCreatedFailureCounter;
    private final Timer accountCreationTimer;
    private final Counter accountLockedSuccessCounter;
    private final Counter accountLockedFailureCounter;
    private final Counter accountUnlockedSuccessCounter;
    private final Counter accountUnlockedFailureCounter;
    private final Counter passwordResetSuccessCounter;
    private final Counter passwordResetFailureCounter;

    public KeycloakAccountSyncService(KeycloakAdminClient keycloakClient,
                                       AccountRepository accountRepository,
                                       TemporaryPasswordGenerator passwordGenerator,
                                       AdminActionLogRepository auditLogRepository,
                                       MeterRegistry meterRegistry) {
        this.keycloakClient = keycloakClient;
        this.accountRepository = accountRepository;
        this.passwordGenerator = passwordGenerator;
        this.auditLogRepository = auditLogRepository;

        // Initialize metrics
        this.accountCreatedSuccessCounter = Counter.builder("account.created.success")
                .description("Number of accounts successfully created with Keycloak")
                .register(meterRegistry);
        this.accountCreatedFailureCounter = Counter.builder("account.created.failure")
                .description("Number of failed account creation attempts")
                .register(meterRegistry);
        this.accountCreationTimer = Timer.builder("account.creation.duration")
                .description("Time taken to create account with Keycloak")
                .register(meterRegistry);
        this.accountLockedSuccessCounter = Counter.builder("account.locked.success")
                .description("Number of accounts successfully locked")
                .register(meterRegistry);
        this.accountLockedFailureCounter = Counter.builder("account.locked.failure")
                .description("Number of failed account lock attempts")
                .register(meterRegistry);
        this.accountUnlockedSuccessCounter = Counter.builder("account.unlocked.success")
                .description("Number of accounts successfully unlocked")
                .register(meterRegistry);
        this.accountUnlockedFailureCounter = Counter.builder("account.unlocked.failure")
                .description("Number of failed account unlock attempts")
                .register(meterRegistry);
        this.passwordResetSuccessCounter = Counter.builder("password.reset.success")
                .description("Number of passwords successfully reset")
                .register(meterRegistry);
        this.passwordResetFailureCounter = Counter.builder("password.reset.failure")
                .description("Number of failed password reset attempts")
                .register(meterRegistry);
    }

    /**
     * Create new account with Keycloak integration.
     * <p>
     * Multi-phase commit:
     * 1. Create user in Keycloak (with temporary password)
     * 2. Assign role (USER or ADMIN) to Keycloak user
     * 3. Create account in PostgreSQL (with keycloakUserId)
     * 4. Update Keycloak user attributes (bidirectional mapping)
     * 5. Log success to audit trail
     * <p>
     * If any step fails, rollback Keycloak user (including role) and log failure.
     *
     * @param request         Account creation request
     * @param adminAccountId  UUID of admin performing the action
     * @param ipAddress       IP address of admin
     * @param userAgent       User agent of admin's browser
     * @return CreateAccountResponse with account and temporary password
     * @throws AccountCreationException if creation fails
     */
    public CreateAccountResponse createAccount(CreateAccountRequestDto request,
                                                UUID adminAccountId,
                                                String ipAddress,
                                                String userAgent) {
        return accountCreationTimer.record(() -> {
            String keycloakUserId = null;
            String temporaryPassword = passwordGenerator.generate();

            try {
            // Phase 1: Create in Keycloak
            log.info("Creating Keycloak user for email: {}", request.email());
            keycloakUserId = keycloakClient.createUser(
                    request.email(),
                    request.email(), // username = email
                    temporaryPassword,
                    true // enabled
            );

            log.info("Keycloak user created with ID: {}", keycloakUserId);

            // Phase 2: Assign role to Keycloak user
            String roleName = request.role();
            log.info("Assigning role '{}' to Keycloak user: {}", roleName, keycloakUserId);
            keycloakClient.assignRole(keycloakUserId, roleName);
            log.info("Role '{}' assigned successfully to user: {}", roleName, keycloakUserId);

            // Phase 3: Create in PostgreSQL
            Account account = Account.createWithKeycloak(
                    keycloakUserId,
                    request.email(),
                    request.name(),
                    request.phone(),
                    request.company()
            );

            Account savedAccount = accountRepository.save(account);
            log.info("Account created in database with ID: {}", savedAccount.getId());

            // Phase 4: Bidirectional reference - update Keycloak user attributes
            keycloakClient.updateUserAttributes(keycloakUserId, savedAccount.getId().toString());
            log.info("Bidirectional mapping established for account ID: {}", savedAccount.getId());

            // Phase 5: Log success
            AdminActionLog auditLog = AdminActionLog.success(
                    AdminActionType.CREATE_ACCOUNT,
                    savedAccount.getId(),
                    adminAccountId,
                    ipAddress,
                    userAgent
            );
            auditLogRepository.save(auditLog);

            // Fetch Keycloak user representation for response
            UserRepresentation keycloakUser = keycloakClient.getUser(keycloakUserId);
            AccountWithKeycloakResponse accountResponse = AccountWithKeycloakResponse.fromEntity(savedAccount, keycloakUser);

            // Increment success counter
            accountCreatedSuccessCounter.increment();

            return new CreateAccountResponse(accountResponse, temporaryPassword);

            } catch (Exception e) {
            log.error("Account creation failed for email: {}. Rolling back Keycloak user.", request.email(), e);

            // Rollback: Delete from Keycloak if created
            if (keycloakUserId != null) {
                try {
                    keycloakClient.deleteUser(keycloakUserId);
                    log.info("Successfully rolled back Keycloak user: {}", keycloakUserId);
                } catch (Exception rollbackEx) {
                    log.error("Failed to rollback Keycloak user: {}. Manual cleanup required.", keycloakUserId, rollbackEx);
                }
            }

            // Log failure (account wasn't created, so target ID is null)
            AdminActionLog auditLog = AdminActionLog.failure(
                    AdminActionType.CREATE_ACCOUNT,
                    null, // Account was never created
                    adminAccountId,
                    e.getMessage(),
                    ipAddress,
                    userAgent
            );
            auditLogRepository.save(auditLog);

            // Increment failure counter
            accountCreatedFailureCounter.increment();

            throw new AccountCreationException("Failed to create account: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Lock account (disable in Keycloak).
     * <p>
     * Steps:
     * 1. Load account and verify Keycloak integration
     * 2. Check if admin is trying to lock their own account (prevent self-lock)
     * 3. Get Keycloak user and check if already disabled
     * 4. Disable user in Keycloak
     * 5. Update account timestamp in database
     * 6. Log success to audit trail
     *
     * @param accountId      UUID of account to lock
     * @param adminAccountId UUID of admin performing the action
     * @param ipAddress      IP address of admin
     * @param userAgent      User agent of admin's browser
     * @return AccountWithKeycloakResponse with updated status
     * @throws AccountNotFoundException    if account not found
     * @throws NoKeycloakIntegrationException if account doesn't have Keycloak integration
     * @throws AccountAlreadyLockedException if account is already locked
     * @throws CannotLockOwnAccountException if admin tries to lock their own account
     * @throws AccountLockException        if locking fails
     */
    public AccountWithKeycloakResponse lockAccount(UUID accountId,
                                                    UUID adminAccountId,
                                                    String ipAddress,
                                                    String userAgent) {
        try {
            // Step 1: Load account
            Account account = accountRepository.findById(accountId)
                    .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

            // Step 2: Prevent admin from locking their own account
            if (adminAccountId != null && accountId.equals(adminAccountId)) {
                log.warn("Admin {} attempted to lock their own account", adminAccountId);
                throw new CannotLockOwnAccountException("You cannot lock your own account");
            }

            if (!account.hasKeycloakIntegration()) {
                throw new NoKeycloakIntegrationException("Account does not have Keycloak integration");
            }

            String keycloakUserId = account.getKeycloakUserId();
            log.info("Locking account ID: {} with Keycloak user ID: {}", accountId, keycloakUserId);

            // Step 2: Check current status
            UserRepresentation keycloakUser = keycloakClient.getUser(keycloakUserId);
            if (!keycloakUser.isEnabled()) {
                throw new AccountAlreadyLockedException("Account is already locked");
            }

            // Step 3: Disable in Keycloak
            keycloakClient.disableUser(keycloakUserId);
            log.info("Disabled Keycloak user: {}", keycloakUserId);

            // Step 4: Trigger database update (JPA @PreUpdate will update timestamp)
            accountRepository.save(account);

            // Step 5: Log success
            AdminActionLog auditLog = AdminActionLog.success(
                    AdminActionType.LOCK_ACCOUNT,
                    accountId,
                    adminAccountId,
                    ipAddress,
                    userAgent
            );
            auditLogRepository.save(auditLog);

            // Increment success counter
            accountLockedSuccessCounter.increment();

            // Fetch updated Keycloak user for response
            UserRepresentation updatedKeycloakUser = keycloakClient.getUser(keycloakUserId);
            return AccountWithKeycloakResponse.fromEntity(account, updatedKeycloakUser);

        } catch (AccountNotFoundException | NoKeycloakIntegrationException | AccountAlreadyLockedException | CannotLockOwnAccountException e) {
            // These are expected exceptions, re-throw without logging as errors
            accountLockedFailureCounter.increment();
            throw e;
        } catch (Exception e) {
            log.error("Failed to lock account ID: {}", accountId, e);

            // Log failure
            AdminActionLog auditLog = AdminActionLog.failure(
                    AdminActionType.LOCK_ACCOUNT,
                    accountId,
                    adminAccountId,
                    e.getMessage(),
                    ipAddress,
                    userAgent
            );
            auditLogRepository.save(auditLog);

            // Increment failure counter
            accountLockedFailureCounter.increment();

            throw new AccountLockException("Failed to lock account: " + e.getMessage(), e);
        }
    }

    /**
     * Unlock account (enable in Keycloak).
     * <p>
     * Steps:
     * 1. Load account and verify Keycloak integration
     * 2. Get Keycloak user and check if already enabled
     * 3. Enable user in Keycloak
     * 4. Update account timestamp in database
     * 5. Log success to audit trail
     *
     * @param accountId      UUID of account to unlock
     * @param adminAccountId UUID of admin performing the action
     * @param ipAddress      IP address of admin
     * @param userAgent      User agent of admin's browser
     * @return AccountWithKeycloakResponse with updated status
     * @throws AccountNotFoundException       if account not found
     * @throws NoKeycloakIntegrationException if account doesn't have Keycloak integration
     * @throws AccountAlreadyUnlockedException if account is already unlocked
     * @throws AccountUnlockException         if unlocking fails
     */
    public AccountWithKeycloakResponse unlockAccount(UUID accountId,
                                                      UUID adminAccountId,
                                                      String ipAddress,
                                                      String userAgent) {
        try {
            // Step 1: Load account
            Account account = accountRepository.findById(accountId)
                    .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

            if (!account.hasKeycloakIntegration()) {
                throw new NoKeycloakIntegrationException("Account does not have Keycloak integration");
            }

            String keycloakUserId = account.getKeycloakUserId();
            log.info("Unlocking account ID: {} with Keycloak user ID: {}", accountId, keycloakUserId);

            // Step 2: Check current status
            UserRepresentation keycloakUser = keycloakClient.getUser(keycloakUserId);
            if (keycloakUser.isEnabled()) {
                throw new AccountAlreadyUnlockedException("Account is already unlocked");
            }

            // Step 3: Enable in Keycloak
            keycloakClient.enableUser(keycloakUserId);
            log.info("Enabled Keycloak user: {}", keycloakUserId);

            // Step 4: Trigger database update (JPA @PreUpdate will update timestamp)
            accountRepository.save(account);

            // Step 5: Log success
            AdminActionLog auditLog = AdminActionLog.success(
                    AdminActionType.UNLOCK_ACCOUNT,
                    accountId,
                    adminAccountId,
                    ipAddress,
                    userAgent
            );
            auditLogRepository.save(auditLog);

            // Increment success counter
            accountUnlockedSuccessCounter.increment();

            // Fetch updated Keycloak user for response
            UserRepresentation updatedKeycloakUser = keycloakClient.getUser(keycloakUserId);
            return AccountWithKeycloakResponse.fromEntity(account, updatedKeycloakUser);

        } catch (AccountNotFoundException | NoKeycloakIntegrationException | AccountAlreadyUnlockedException e) {
            // These are expected exceptions, re-throw without logging as errors
            accountUnlockedFailureCounter.increment();
            throw e;
        } catch (Exception e) {
            log.error("Failed to unlock account ID: {}", accountId, e);

            // Log failure
            AdminActionLog auditLog = AdminActionLog.failure(
                    AdminActionType.UNLOCK_ACCOUNT,
                    accountId,
                    adminAccountId,
                    e.getMessage(),
                    ipAddress,
                    userAgent
            );
            auditLogRepository.save(auditLog);

            // Increment failure counter
            accountUnlockedFailureCounter.increment();

            throw new AccountUnlockException("Failed to unlock account: " + e.getMessage(), e);
        }
    }

    /**
     * Reset user password to new temporary password.
     * <p>
     * Steps:
     * 1. Load account and verify Keycloak integration
     * 2. Generate new temporary password
     * 3. Reset password in Keycloak (temporary=true, requires UPDATE_PASSWORD action)
     * 4. Update account timestamp in database
     * 5. Log success to audit trail
     * 6. Return response with temporary password and expiration (30 days)
     *
     * @param accountId      UUID of account to reset password
     * @param adminAccountId UUID of admin performing the action
     * @param ipAddress      IP address of admin
     * @param userAgent      User agent of admin's browser
     * @return ResetPasswordResponse with temporary password and expiration
     * @throws AccountNotFoundException       if account not found
     * @throws NoKeycloakIntegrationException if account doesn't have Keycloak integration
     * @throws PasswordResetException         if reset fails
     */
    public ResetPasswordResponse resetPassword(UUID accountId,
                                                UUID adminAccountId,
                                                String ipAddress,
                                                String userAgent) {
        try {
            // Step 1: Load account
            Account account = accountRepository.findById(accountId)
                    .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

            if (!account.hasKeycloakIntegration()) {
                throw new NoKeycloakIntegrationException("Account does not have Keycloak integration");
            }

            String keycloakUserId = account.getKeycloakUserId();
            log.info("Resetting password for account ID: {} with Keycloak user ID: {}", accountId, keycloakUserId);

            // Step 2: Generate new temporary password
            String temporaryPassword = passwordGenerator.generate();

            // Step 3: Reset password in Keycloak
            keycloakClient.resetPassword(keycloakUserId, temporaryPassword);
            log.info("Password reset in Keycloak for user: {}", keycloakUserId);

            // Step 4: Trigger database update (JPA @PreUpdate will update timestamp)
            accountRepository.save(account);

            // Step 5: Log success
            AdminActionLog auditLog = AdminActionLog.success(
                    AdminActionType.RESET_PASSWORD,
                    accountId,
                    adminAccountId,
                    ipAddress,
                    userAgent
            );
            auditLogRepository.save(auditLog);

            // Increment success counter
            passwordResetSuccessCounter.increment();

            // Step 6: Calculate expiration (30 days from now per spec requirement)
            java.time.Instant expiresAt = java.time.Instant.now().plus(30, java.time.temporal.ChronoUnit.DAYS);

            return new ResetPasswordResponse(accountId, temporaryPassword, expiresAt);

        } catch (AccountNotFoundException | NoKeycloakIntegrationException e) {
            // These are expected exceptions, re-throw without logging as errors
            passwordResetFailureCounter.increment();
            throw e;
        } catch (Exception e) {
            log.error("Failed to reset password for account ID: {}", accountId, e);

            // Log failure
            AdminActionLog auditLog = AdminActionLog.failure(
                    AdminActionType.RESET_PASSWORD,
                    accountId,
                    adminAccountId,
                    e.getMessage(),
                    ipAddress,
                    userAgent
            );
            auditLogRepository.save(auditLog);

            // Increment failure counter
            passwordResetFailureCounter.increment();

            throw new PasswordResetException("Failed to reset password: " + e.getMessage(), e);
        }
    }

    /**
     * Custom exception for account creation failures.
     */
    public static class AccountCreationException extends RuntimeException {
        public AccountCreationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Custom exception for account not found.
     */
    public static class AccountNotFoundException extends RuntimeException {
        public AccountNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * Custom exception for account without Keycloak integration.
     */
    public static class NoKeycloakIntegrationException extends RuntimeException {
        public NoKeycloakIntegrationException(String message) {
            super(message);
        }
    }

    /**
     * Custom exception for already locked account.
     */
    public static class AccountAlreadyLockedException extends RuntimeException {
        public AccountAlreadyLockedException(String message) {
            super(message);
        }
    }

    /**
     * Custom exception for already unlocked account.
     */
    public static class AccountAlreadyUnlockedException extends RuntimeException {
        public AccountAlreadyUnlockedException(String message) {
            super(message);
        }
    }

    /**
     * Custom exception for account lock failures.
     */
    public static class AccountLockException extends RuntimeException {
        public AccountLockException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Custom exception for account unlock failures.
     */
    public static class AccountUnlockException extends RuntimeException {
        public AccountUnlockException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Custom exception for password reset failures.
     */
    public static class PasswordResetException extends RuntimeException {
        public PasswordResetException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Custom exception when admin tries to lock their own account.
     */
    public static class CannotLockOwnAccountException extends RuntimeException {
        public CannotLockOwnAccountException(String message) {
            super(message);
        }
    }
}
