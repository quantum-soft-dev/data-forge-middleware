# Quickstart Guide: Admin User Management (Keycloak-First Architecture)

**Feature**: 006-task-the-admin
**Date**: 2025-10-28
**Architecture**: Extend existing Account entity with Keycloak integration

## Overview

This guide provides implementation steps for adding Keycloak user management to existing Account entity. **Key architectural decision**: We extend the existing `accounts` table with Keycloak integration rather than creating a separate users table.

---

## Prerequisites

### Backend Dependencies

Add to `build.gradle.kts`:

```kotlin
dependencies {
    // Keycloak Admin Client
    implementation("org.keycloak:keycloak-admin-client:23.0.1")
}
```

### Environment Variables

Add to `application-dev.yml`:

```yaml
keycloak:
  auth-server-url: ${KEYCLOAK_SERVER_URL:http://localhost:8081}
  realm: ${KEYCLOAK_REALM:dfm}
  admin:
    client-id: ${KEYCLOAK_ADMIN_CLIENT_ID:admin-cli}
    client-secret: ${KEYCLOAK_ADMIN_CLIENT_SECRET}  # Required
```

### Keycloak Configuration

1. Create admin service account client in Keycloak "dfm" realm
2. Enable "Service Accounts" on client
3. Assign "manage-users" role from "realm-management"
4. Set client secret in environment

---

## Implementation Checklist

### Phase 1: Database Migration (Day 1)

- [ ] Create migration `V006__extend_accounts_with_keycloak.sql`
- [ ] Add `keycloak_user_id` column to accounts table
- [ ] Create `admin_action_logs` table
- [ ] Run migration: `./gradlew flywayMigrate`
- [ ] Verify schema: `\d accounts` in psql

### Phase 2: Extend Account Domain (Day 1-2)

- [ ] Add `keycloakUserId` field to `Account.java`
- [ ] Implement `createWithKeycloak()` factory method
- [ ] Implement `linkToKeycloak()` method
- [ ] Implement `hasKeycloakIntegration()` method
- [ ] Create `AdminActionLog` entity
- [ ] Create `AdminActionType` enum
- [ ] Create `ActionStatus` enum
- [ ] Write unit tests for new domain methods

### Phase 3: Infrastructure Layer (Day 2-3)

- [ ] Create `KeycloakAdminConfig` in shared/config
- [ ] Implement `KeycloakAdminClient` wrapper in account/infrastructure
- [ ] Implement `TemporaryPasswordGenerator` utility
- [ ] Extend `AccountRepository` with Keycloak queries
- [ ] Create `AdminActionLogRepository`
- [ ] Write integration tests with Testcontainers (PostgreSQL + Keycloak)

### Phase 4: Application Layer (Day 3-4)

- [ ] Create `KeycloakAccountSyncService` in account/application
- [ ] Implement two-phase commit pattern (Keycloak first, then PostgreSQL)
- [ ] Add rollback logic for failed sync
- [ ] Implement lock/unlock/reset operations
- [ ] Add audit logging for all operations
- [ ] Add Micrometer metrics
- [ ] Write unit tests with Mockito

### Phase 5: Presentation Layer (Day 4-5)

- [ ] Extend `AccountAdminController` with new endpoints
- [ ] Create `AccountWithKeycloakResponse` DTO
- [ ] Create `ResetPasswordResponse` DTO
- [ ] Add OpenAPI annotations
- [ ] Write contract tests with MockMvc
- [ ] Test with Swagger UI

### Phase 6: Frontend (Day 5-7)

- [ ] Extend `entities/account/model/types.ts` with Keycloak fields
- [ ] Create Zod schemas in `features/user-management/`
- [ ] Implement TanStack Query hooks for lock/unlock/reset
- [ ] Create UI components for account actions
- [ ] Add pages for account management
- [ ] Write unit/integration/E2E tests

---

## Key Code Examples

### Migration Script

```sql
-- V006__extend_accounts_with_keycloak.sql
ALTER TABLE accounts
ADD COLUMN keycloak_user_id VARCHAR(36);

ALTER TABLE accounts
ADD CONSTRAINT uq_keycloak_user_id UNIQUE (keycloak_user_id);

CREATE INDEX idx_accounts_keycloak_user_id ON accounts(keycloak_user_id);

CREATE TABLE admin_action_logs (
    id BIGSERIAL PRIMARY KEY,
    action_type VARCHAR(50) NOT NULL,
    target_account_id UUID NOT NULL,
    admin_account_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_message TEXT,
    ip_address VARCHAR(45),
    user_agent TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_target_account FOREIGN KEY (target_account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT fk_admin_account FOREIGN KEY (admin_account_id) REFERENCES accounts(id) ON DELETE RESTRICT
);
```

### Extended Account Entity

```java
// Add to existing Account.java
@Column(name = "keycloak_user_id", length = 36, unique = true)
private String keycloakUserId;

public static Account createWithKeycloak(String keycloakUserId, String email,
                                          String name, String phone, String company) {
    Objects.requireNonNull(keycloakUserId, "Keycloak user ID required");

    if (!keycloakUserId.matches("^[0-9a-f-]{36}$")) {
        throw new IllegalArgumentException("Invalid Keycloak UUID format");
    }

    Account account = Account.create(email, name, phone, company);
    account.keycloakUserId = keycloakUserId;
    return account;
}

public void linkToKeycloak(String keycloakUserId) {
    if (this.keycloakUserId != null) {
        throw new IllegalStateException("Already linked to Keycloak");
    }
    this.keycloakUserId = keycloakUserId;
    this.updatedAt = LocalDateTime.now();
}

public boolean hasKeycloakIntegration() {
    return keycloakUserId != null;
}
```

### Keycloak Account Sync Service

```java
@Service
@Transactional
public class KeycloakAccountSyncService {

    private final Keycloak keycloakClient;
    private final AccountRepository accountRepository;
    private final TemporaryPasswordGenerator passwordGenerator;

    public CreateAccountResponse createAccount(CreateAccountRequest request) {
        String keycloakUserId = null;
        String tempPassword = passwordGenerator.generate();

        try {
            // Phase 1: Create in Keycloak
            UserRepresentation user = new UserRepresentation();
            user.setEnabled(true);
            user.setUsername(request.getEmail());
            user.setEmail(request.getEmail());

            CredentialRepresentation credential = new CredentialRepresentation();
            credential.setType(CredentialRepresentation.PASSWORD);
            credential.setValue(tempPassword);
            credential.setTemporary(true);
            user.setCredentials(List.of(credential));

            Response response = keycloakClient.realm("dfm").users().create(user);
            keycloakUserId = CreatedResponseUtil.getCreatedId(response);

            // Phase 2: Create in PostgreSQL
            Account account = Account.createWithKeycloak(
                keycloakUserId,
                request.getEmail(),
                request.getName(),
                request.getPhone(),
                request.getCompany()
            );

            // Bidirectional reference
            user.setAttributes(Map.of(
                "accountId", List.of(account.getId().toString())
            ));
            keycloakClient.realm("dfm").users().get(keycloakUserId).update(user);

            Account saved = accountRepository.save(account);

            return new CreateAccountResponse(
                AccountWithKeycloakResponse.fromEntity(saved, user),
                tempPassword
            );

        } catch (Exception e) {
            // Rollback: Delete from Keycloak
            if (keycloakUserId != null) {
                keycloakClient.realm("dfm").users().get(keycloakUserId).remove();
            }
            throw new AccountCreationException("Failed to create account", e);
        }
    }

    public void lockAccount(UUID accountId) {
        Account account = accountRepository.findById(accountId).orElseThrow();

        if (!account.hasKeycloakIntegration()) {
            throw new IllegalStateException("Account not integrated with Keycloak");
        }

        UserResource keycloakUser = keycloakClient.realm("dfm")
                                                   .users()
                                                   .get(account.getKeycloakUserId());
        UserRepresentation user = keycloakUser.toRepresentation();
        user.setEnabled(false);  // Disable authentication
        keycloakUser.update(user);

        account.updateTimestamp();
        accountRepository.save(account);
    }
}
```

---

## Testing Commands

```bash
# Backend
./gradlew test
./gradlew integrationTest
./gradlew jacocoTestReport

# Frontend
cd frontend
npm test
npm run test:e2e
npm run test:coverage
```

---

## API Testing

### Create Account with Keycloak

```bash
curl -X POST http://localhost:8080/api/admin/accounts \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "name": "John Doe",
    "role": "USER"
  }'
```

### Lock Account (Disable Keycloak User)

```bash
curl -X POST http://localhost:8080/api/admin/accounts/{accountId}/lock \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

### Unlock Account (Enable Keycloak User)

```bash
curl -X POST http://localhost:8080/api/admin/accounts/{accountId}/unlock \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

### Reset Password

```bash
curl -X POST http://localhost:8080/api/admin/accounts/{accountId}/reset-password \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

---

## Architecture Decisions

### Why Extend Accounts Table Instead of Creating Users Table?

1. **No Data Duplication**: Account already has email, name, phone, company
2. **Existing Relationships**: Account → Sites, Batches (preserve these)
3. **Single Aggregate Root**: Account is the DDD aggregate root
4. **Minimal Migration**: Add one column vs creating entire new table
5. **Backwards Compatible**: Nullable `keycloak_user_id` allows existing accounts

### Keycloak.enabled vs Account.isActive

| Keycloak.enabled | Account.isActive | Behavior |
|------------------|------------------|----------|
| true             | true             | Normal user (can login, create resources) |
| false            | true             | **Locked** (can't login, security measure) |
| true             | false            | Deactivated (can login, can't create resources) |
| false            | false            | Fully disabled |

**Key Insight**: Lock/unlock modifies `Keycloak.enabled` (authentication layer), not `Account.isActive` (business layer).

---

## Common Issues

### Issue: Keycloak Admin Client 403 Forbidden

**Solution**: Verify admin client has "manage-users" role:
```bash
# In Keycloak Admin Console:
# Clients → admin-cli → Service Account Roles
# Add "realm-management" → "manage-users"
```

### Issue: Duplicate Key Error on keycloak_user_id

**Solution**: Ensure rollback logic deletes Keycloak user if PostgreSQL fails:
```java
try {
    // ... create in Keycloak and PostgreSQL
} catch (Exception e) {
    if (keycloakUserId != null) {
        keycloakClient.realm("dfm").users().get(keycloakUserId).remove();
    }
    throw e;
}
```

---

## Next Steps

1. Run `/speckit.tasks` to generate detailed task breakdown
2. Follow TDD workflow: tests first, then implementation
3. Ensure 80% code coverage
4. Update CLAUDE.md with new patterns
5. Create pull request with constitutional compliance

For detailed specifications:
- **Data Model**: [data-model.md](./data-model.md)
- **API Contracts**: [contracts/account-management-api.yaml](./contracts/account-management-api.yaml)
- **Research Decisions**: [research.md](./research.md)
- **Implementation Plan**: [plan.md](./plan.md)
