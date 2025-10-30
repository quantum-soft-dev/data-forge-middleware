# Research: Admin User Management (Keycloak-First Architecture)

**Feature**: 006-task-the-admin
**Date**: 2025-10-28
**Status**: Complete
**Architecture Decision**: Keycloak as primary user store, extend existing accounts table

## Research Questions

This document resolves architectural decisions for implementing user management with Keycloak integration while reusing the existing `Account` entity.

---

## Q1: Should We Create a Separate Users Table or Extend Existing Accounts Table?

**Question**: The system already has an `accounts` table that represents users. Should we create a new `users` table for Keycloak integration or extend the existing `accounts` table?

### Decision

**Extend the existing `accounts` table** by adding a single `keycloak_user_id` column. Do NOT create a separate users table.

### Rationale

1. **No Data Duplication**: Account already represents a user with email, name, phone, company. Creating a separate users table would duplicate this data.
2. **Simpler Data Model**: Single source of truth for user business data (PostgreSQL accounts table).
3. **Existing Relationships**: Account entity already has relationships to sites and batches. Preserving these relationships avoids complex migration.
4. **DDD Compliance**: Account is already the aggregate root for user-related operations. Creating a separate User aggregate would violate single aggregate root principle.
5. **Minimal Schema Change**: Adding one column is less risky than creating an entirely new table with foreign keys.
6. **Backwards Compatibility**: Nullable `keycloak_user_id` allows existing accounts to continue working while new accounts get Keycloak integration.

### Implementation

```sql
-- Migration V006__extend_accounts_with_keycloak.sql
ALTER TABLE accounts
ADD COLUMN keycloak_user_id VARCHAR(36) UNIQUE;

CREATE INDEX idx_accounts_keycloak_user_id ON accounts(keycloak_user_id);
```

```java
// Extended Account.java
@Column(name = "keycloak_user_id", length = 36, unique = true)
private String keycloakUserId;

public static Account createWithKeycloak(String keycloakUserId, String email,
                                          String name, String phone, String company) {
    // Factory method for new Keycloak-integrated accounts
}
```

### Alternatives Considered

**Alternative 1: Create separate users table**
- **Rejected because**: Data duplication (email, name in both tables), complex joins, violates DRY principle, existing Account aggregate is sufficient

**Alternative 2: Replace accounts table with users table**
- **Rejected because**: Breaking change, requires massive data migration, existing code depends on Account entity, high risk

**Alternative 3: Use Keycloak as only user store (no PostgreSQL user data)**
- **Rejected because**: Loss of business-specific fields (phone, company, isActive for business logic), complex queries across Keycloak API, performance issues

---

## Q2: Keycloak Admin Client SDK Integration Pattern

**Question**: Should we use manual REST API calls or the official Keycloak Java Admin Client library for user management operations?

### Decision

Use the **official Keycloak Java Admin Client library** (`org.keycloak:keycloak-admin-client`).

### Rationale

1. **Type Safety**: Strongly-typed APIs with compile-time validation
2. **Built-in Error Handling**: Library handles Keycloak-specific error codes and retries
3. **Maintenance**: Official SDK maintained by Keycloak team, guaranteed compatibility
4. **Existing Dependency**: Project already uses Keycloak for OAuth2 authentication
5. **Developer Experience**: Fluent API is more intuitive than manual REST calls

### Implementation

```java
// Dependency (build.gradle.kts)
implementation("org.keycloak:keycloak-admin-client:23.0.1")

// Configuration Bean
@Bean
public Keycloak keycloakAdminClient(
        @Value("${keycloak.auth-server-url}") String serverUrl,
        @Value("${keycloak.realm}") String realm,
        @Value("${keycloak.admin.client-id}") String clientId,
        @Value("${keycloak.admin.client-secret}") String clientSecret) {

    return KeycloakBuilder.builder()
            .serverUrl(serverUrl)
            .realm(realm)
            .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
            .clientId(clientId)
            .clientSecret(clientSecret)
            .build();
}

// Usage in Service Layer
UsersResource usersResource = keycloak.realm("dfm").users();
UserRepresentation user = new UserRepresentation();
user.setEnabled(true);
user.setUsername(email);
Response response = usersResource.create(user);
```

### Alternatives Considered

**Alternative 1: Manual REST API calls with RestTemplate/WebClient**
- **Rejected because**: Higher maintenance burden, manual JSON serialization, no type safety, error handling complexity

---

## Q3: Account-to-Keycloak User Mapping Strategy

**Question**: How should we correlate PostgreSQL accounts with Keycloak users?

### Decision

Use **bidirectional mapping**:
1. `accounts.keycloak_user_id` → Keycloak user ID (UUID)
2. Keycloak user `attributes.accountId` → PostgreSQL `accounts.id` (UUID)

### Rationale

1. **Bidirectional Lookups**: Can find Keycloak user from Account OR find Account from Keycloak user
2. **Keycloak User ID is Immutable**: UUID never changes, making it a stable foreign key
3. **Custom Attributes Support**: Keycloak allows arbitrary attributes on users
4. **Integration Flexibility**: External systems can query Keycloak and resolve to Account

### Implementation

**PostgreSQL Side**:
```java
@Column(name = "keycloak_user_id", length = 36, unique = true)
private String keycloakUserId;  // Keycloak user UUID
```

**Keycloak Side**:
```java
UserRepresentation user = new UserRepresentation();
user.setAttributes(Map.of(
    "accountId", List.of(account.getId().toString())  // PostgreSQL UUID
));
```

**Lookup Examples**:
```java
// Given Account → find Keycloak user
UsersResource users = keycloak.realm("dfm").users();
UserRepresentation keycloakUser = users.get(account.getKeycloakUserId()).toRepresentation();

// Given Keycloak user → find Account
String accountIdStr = keycloakUser.getAttributes().get("accountId").get(0);
UUID accountId = UUID.fromString(accountIdStr);
Account account = accountRepository.findById(accountId).orElseThrow();
```

### Alternatives Considered

**Alternative 1: Use email as correlation key**
- **Rejected because**: Email can change (user profile updates), not a stable identifier

**Alternative 2: One-way mapping only (accounts → Keycloak)**
- **Rejected because**: Limits integration scenarios where Keycloak is the entry point (e.g., external SSO)

---

## Q4: Two-Phase Commit Pattern for Keycloak + PostgreSQL Sync

**Question**: How do we ensure atomic operations across Keycloak and PostgreSQL when they're separate systems?

### Decision

Use **Keycloak-first creation with compensating rollback** pattern.

### Rationale

1. **Keycloak is Authentication Source of Truth**: Create user in Keycloak first (authentication layer)
2. **PostgreSQL Stores Business Data**: Then create Account record (business layer)
3. **Compensating Transaction**: If Account creation fails, delete Keycloak user
4. **Idempotency**: Keycloak user ID stored in Account ensures we can detect partial failures

### Implementation

```java
@Service
@Transactional
public class KeycloakAccountSyncService {

    private final Keycloak keycloakClient;
    private final AccountRepository accountRepository;
    private final AdminActionLogRepository auditLogRepository;

    public CreateAccountResponse createAccount(CreateAccountRequest request) {
        String keycloakUserId = null;
        String temporaryPassword = passwordGenerator.generate();

        try {
            // Phase 1: Create in Keycloak (authentication layer)
            UserRepresentation keycloakUser = new UserRepresentation();
            keycloakUser.setEnabled(true);
            keycloakUser.setUsername(request.getEmail());
            keycloakUser.setEmail(request.getEmail());
            keycloakUser.setFirstName(extractFirstName(request.getName()));
            keycloakUser.setLastName(extractLastName(request.getName()));

            // Set temporary password
            CredentialRepresentation credential = new CredentialRepresentation();
            credential.setType(CredentialRepresentation.PASSWORD);
            credential.setValue(temporaryPassword);
            credential.setTemporary(true);  // Force change on first login
            keycloakUser.setCredentials(List.of(credential));

            Response response = keycloak.realm("dfm").users().create(keycloakUser);

            if (response.getStatus() != 201) {
                throw new KeycloakUserCreationException("Failed to create Keycloak user: " + response.getStatusInfo());
            }

            keycloakUserId = CreatedResponseUtil.getCreatedId(response);

            // Phase 2: Create in PostgreSQL (business layer)
            Account account = Account.createWithKeycloak(
                keycloakUserId,
                request.getEmail(),
                request.getName(),
                request.getPhone(),
                request.getCompany()
            );

            // Store bidirectional reference
            keycloak.realm("dfm").users().get(keycloakUserId)
                   .update(addAccountIdAttribute(account.getId()));

            Account savedAccount = accountRepository.save(account);

            // Audit log
            auditLogRepository.save(AdminActionLog.success(
                AdminActionType.CREATE_ACCOUNT,
                savedAccount.getId(),
                currentAdminId,
                requestIp,
                userAgent
            ));

            return new CreateAccountResponse(
                AccountWithKeycloakResponse.fromEntity(savedAccount, keycloakUser),
                temporaryPassword
            );

        } catch (Exception e) {
            // Compensating Transaction: Rollback Keycloak user creation
            if (keycloakUserId != null) {
                try {
                    keycloak.realm("dfm").users().get(keycloakUserId).remove();
                    log.warn("Rolled back Keycloak user creation: {}", keycloakUserId);
                } catch (Exception rollbackEx) {
                    log.error("CRITICAL: Failed to rollback Keycloak user {}, manual cleanup required",
                              keycloakUserId, rollbackEx);
                    // Alert monitoring system for manual intervention
                }
            }

            // Audit log failure
            auditLogRepository.save(AdminActionLog.failure(
                AdminActionType.CREATE_ACCOUNT,
                null,
                currentAdminId,
                e.getMessage(),
                requestIp,
                userAgent
            ));

            throw new AccountCreationException("Failed to create account", e);
        }
    }
}
```

### Edge Case Handling

| Scenario | Behavior | Recovery |
|----------|----------|----------|
| Keycloak creation succeeds, PostgreSQL fails | Delete Keycloak user (rollback) | Automatic in try-catch |
| PostgreSQL creation succeeds, Keycloak fails | Transaction rolls back automatically | Spring @Transactional |
| Rollback fails (Keycloak user orphaned) | Log CRITICAL error, create monitoring alert | Manual cleanup |
| Keycloak unavailable | Fail fast, return 500 to admin | Retry after Keycloak recovers |
| Duplicate email in Keycloak | Return 409 Conflict | Admin retries with different email |

### Alternatives Considered

**Alternative 1: Database-first creation**
- **Rejected because**: Keycloak is authentication source of truth, should be created first

**Alternative 2: Saga pattern with distributed transaction coordinator**
- **Rejected because**: Overkill for two-system sync, adds complexity without proportional benefit

**Alternative 3: Eventually consistent (async queue)**
- **Rejected because**: User needs immediate access after creation, eventual consistency not acceptable for authentication

---

## Q5: Keycloak Enabled vs Account isActive Distinction

**Question**: Keycloak has an `enabled` field and Account has an `isActive` field. How should these interact?

### Decision

**Separate concerns**: Keycloak.enabled controls authentication, Account.isActive controls business operations.

### Rationale

1. **Authentication Layer (Keycloak.enabled)**: Can user log in?
   - Lock/unlock operations modify this field
   - Temporary security measure (suspicious activity, password reset)

2. **Business Layer (Account.isActive)**: Can account create resources (sites, batches)?
   - Deactivate/activate operations modify this field
   - Business decision (contract ended, trial expired)
   - Cascades to dependent entities (sites)

3. **Independent State**: Both fields can be true/false independently
   - Locked account (enabled=false, isActive=true): Can't login but business relationship intact
   - Deactivated account (enabled=true, isActive=false): Could login but can't create resources
   - Fully disabled (enabled=false, isActive=false): Neither authentication nor business operations

### State Matrix

| Keycloak.enabled | Account.isActive | Login? | Create Sites/Batches? | Use Case |
|------------------|------------------|--------|----------------------|----------|
| true             | true             | ✅ Yes  | ✅ Yes                | Normal active user |
| false            | true             | ❌ No   | ❌ No (can't authenticate) | Temporarily locked for security |
| true             | false            | ✅ Yes  | ❌ No (business rule)  | Deactivated account, view-only access |
| false            | false            | ❌ No   | ❌ No                 | Fully disabled |

### Implementation

```java
// Lock operation (admin security action)
public void lockAccount(UUID accountId) {
    Account account = accountRepository.findById(accountId).orElseThrow();

    if (!account.hasKeycloakIntegration()) {
        throw new AccountNotIntegratedException("Account not linked to Keycloak");
    }

    // Disable authentication in Keycloak
    UserResource keycloakUser = keycloak.realm("dfm").users().get(account.getKeycloakUserId());
    UserRepresentation user = keycloakUser.toRepresentation();

    if (!user.isEnabled()) {
        throw new AccountAlreadyLockedException("Account is already locked");
    }

    user.setEnabled(false);  // Prevent login
    keycloakUser.update(user);

    // Note: Account.isActive remains unchanged (business operations state)
    account.updateTimestamp();  // Just update timestamp for audit trail
    accountRepository.save(account);
}

// Deactivate operation (business decision)
public void deactivateAccount(UUID accountId) {
    Account account = accountRepository.findById(accountId).orElseThrow();

    account.deactivate();  // Sets isActive = false, triggers domain event
    accountRepository.save(account);

    // Optionally also disable Keycloak user (business policy decision)
    if (account.hasKeycloakIntegration()) {
        UserResource keycloakUser = keycloak.realm("dfm").users().get(account.getKeycloakUserId());
        UserRepresentation user = keycloakUser.toRepresentation();
        user.setEnabled(false);
        keycloakUser.update(user);
    }
}
```

### Alternatives Considered

**Alternative 1: Single state field (merge enabled + isActive)**
- **Rejected because**: Conflates authentication concerns with business logic, loses granularity

**Alternative 2: Keycloak.enabled mirrors Account.isActive always**
- **Rejected because**: Can't have temporary security locks without affecting business status

---

## Q6: Temporary Password Generation Best Practices

**Question**: How should temporary passwords be generated to meet security requirements?

### Decision

Use **SecureRandom with guaranteed character class inclusion** (12 characters: uppercase + lowercase + digits + special chars).

### Implementation

```java
@Component
public class TemporaryPasswordGenerator {

    private static final String UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWERCASE = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String SPECIAL = "!@#$%^&*-_=+";
    private static final String ALL_CHARS = UPPERCASE + LOWERCASE + DIGITS + SPECIAL;
    private static final int PASSWORD_LENGTH = 12;

    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        StringBuilder password = new StringBuilder(PASSWORD_LENGTH);

        // Ensure at least one character from each class
        password.append(UPPERCASE.charAt(secureRandom.nextInt(UPPERCASE.length())));
        password.append(LOWERCASE.charAt(secureRandom.nextInt(LOWERCASE.length())));
        password.append(DIGITS.charAt(secureRandom.nextInt(DIGITS.length())));
        password.append(SPECIAL.charAt(secureRandom.nextInt(SPECIAL.length())));

        // Fill remaining positions
        for (int i = 4; i < PASSWORD_LENGTH; i++) {
            password.append(ALL_CHARS.charAt(secureRandom.nextInt(ALL_CHARS.length())));
        }

        // Shuffle to avoid predictable pattern (uppercase always first, etc.)
        return shuffleString(password.toString());
    }

    private String shuffleString(String input) {
        List<Character> chars = input.chars()
                .mapToObj(c -> (char) c)
                .collect(Collectors.toList());
        Collections.shuffle(chars, secureRandom);
        return chars.stream()
                .map(String::valueOf)
                .collect(Collectors.joining());
    }
}
```

### Requirements Met

- ✅ 12 characters minimum (spec: FR-002)
- ✅ At least one uppercase letter
- ✅ At least one lowercase letter
- ✅ At least one digit
- ✅ At least one special character
- ✅ Cryptographically secure (SecureRandom)
- ✅ No predictable patterns (shuffled)

---

## Q7: Testing Strategy for Keycloak Integration

**Question**: How to test Keycloak integration without requiring a live Keycloak instance for every test?

### Decision

- **Unit Tests**: Mock Keycloak admin client with Mockito
- **Integration Tests**: Testcontainers with real Keycloak Docker image
- **Contract Tests**: MockMvc with mocked service layer

### Integration Test Setup

```java
@SpringBootTest
@Testcontainers
class KeycloakAccountSyncIntegrationTest {

    @Container
    static KeycloakContainer keycloak = new KeycloakContainer("quay.io/keycloak/keycloak:23.0.1")
            .withRealmImportFile("test-realm.json");

    @DynamicPropertySource
    static void registerKeycloakProperties(DynamicPropertyRegistry registry) {
        registry.add("keycloak.auth-server-url", keycloak::getAuthServerUrl);
        registry.add("keycloak.admin.client-id", () -> "admin-cli");
        registry.add("keycloak.admin.client-secret", () -> "test-secret");
    }

    @Test
    void shouldCreateAccountInBothDatabaseAndKeycloak() {
        // Test full sync workflow with real Keycloak
    }
}
```

---

## Summary of Decisions

| Question | Decision | Impact |
|----------|----------|--------|
| Data Model | Extend existing accounts table, no separate users table | Add `keycloak_user_id` column only |
| Keycloak Integration | Official Java Admin Client SDK | Add `org.keycloak:keycloak-admin-client` dependency |
| User Mapping | Bidirectional (accounts.keycloak_user_id ↔ Keycloak attributes.accountId) | Store UUID in both systems |
| Sync Strategy | Keycloak-first with compensating rollback | Create Keycloak user first, rollback if PostgreSQL fails |
| State Management | Separate concerns (Keycloak.enabled for auth, Account.isActive for business) | Two independent boolean fields |
| Password Generation | SecureRandom with guaranteed character classes (12 chars) | Create TemporaryPasswordGenerator utility |
| Testing | Testcontainers + Mockito | Add Testcontainers Keycloak module |

All architectural decisions resolved. Ready to proceed to implementation.
