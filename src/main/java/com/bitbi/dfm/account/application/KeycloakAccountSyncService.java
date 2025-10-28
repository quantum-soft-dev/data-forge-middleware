package com.bitbi.dfm.account.application;

import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.domain.AdminActionLog;
import com.bitbi.dfm.account.domain.AdminActionType;
import com.bitbi.dfm.account.infrastructure.AdminActionLogRepository;
import com.bitbi.dfm.account.infrastructure.JpaAccountRepository;
import com.bitbi.dfm.account.infrastructure.KeycloakAdminClient;
import com.bitbi.dfm.account.presentation.dto.AccountWithKeycloakResponse;
import com.bitbi.dfm.account.presentation.dto.CreateAccountRequestDto;
import com.bitbi.dfm.account.presentation.dto.CreateAccountResponse;
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
    private final JpaAccountRepository accountRepository;
    private final TemporaryPasswordGenerator passwordGenerator;
    private final AdminActionLogRepository auditLogRepository;
    private final Counter accountCreatedSuccessCounter;
    private final Counter accountCreatedFailureCounter;
    private final Timer accountCreationTimer;

    public KeycloakAccountSyncService(KeycloakAdminClient keycloakClient,
                                       JpaAccountRepository accountRepository,
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
    }

    /**
     * Create new account with Keycloak integration.
     * <p>
     * Two-phase commit:
     * 1. Create user in Keycloak (with temporary password)
     * 2. Create account in PostgreSQL (with keycloakUserId)
     * 3. Update Keycloak user attributes (bidirectional mapping)
     * 4. Log success to audit trail
     * <p>
     * If any step fails, rollback Keycloak user and log failure.
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

            // Phase 2: Create in PostgreSQL
            Account account = Account.createWithKeycloak(
                    keycloakUserId,
                    request.email(),
                    request.name(),
                    request.phone(),
                    request.company()
            );

            Account savedAccount = accountRepository.save(account);
            log.info("Account created in database with ID: {}", savedAccount.getId());

            // Phase 3: Bidirectional reference - update Keycloak user attributes
            keycloakClient.updateUserAttributes(keycloakUserId, savedAccount.getId().toString());
            log.info("Bidirectional mapping established for account ID: {}", savedAccount.getId());

            // Phase 4: Log success
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

            // Log failure (use temporary UUID if account wasn't created)
            UUID targetAccountId = UUID.randomUUID(); // Placeholder since account wasn't created
            AdminActionLog auditLog = AdminActionLog.failure(
                    AdminActionType.CREATE_ACCOUNT,
                    targetAccountId,
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
     * Custom exception for account creation failures.
     */
    public static class AccountCreationException extends RuntimeException {
        public AccountCreationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
