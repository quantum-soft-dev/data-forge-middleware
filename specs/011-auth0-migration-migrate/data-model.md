# Data Model: Auth0 Migration

**Branch**: `011-auth0-migration-migrate`
**Date**: 2025-11-06
**Status**: Complete

## Overview

This document outlines the database schema changes, domain entities, and value objects required for the Auth0 migration. The migration follows the decision to **rename** the existing `keycloak_user_id` column to `identity_provider_user_id` to maintain a clean schema without historical references.

---

## Database Schema Changes

### Migration V0##: Rename Keycloak Column to Identity Provider Column

**Purpose**: Repurpose existing `keycloak_user_id` column for Auth0 user IDs

**Flyway Migration** (`V0##__rename_keycloak_to_identity_provider.sql`):

```sql
-- Rename column from keycloak_user_id to identity_provider_user_id
ALTER TABLE accounts
RENAME COLUMN keycloak_user_id TO identity_provider_user_id;

-- Expand column size from VARCHAR(36) to VARCHAR(64)
-- Keycloak UUIDs: 36 characters (e.g., "60f7b8a8-b4a0-f100-74c5-d0e1")
-- Auth0 User IDs: up to 64 characters (e.g., "auth0|60f7b8a8b4a0f10074c5d0e1")
ALTER TABLE accounts
ALTER COLUMN identity_provider_user_id TYPE VARCHAR(64);

-- Update index name for clarity
DROP INDEX IF EXISTS idx_accounts_keycloak_user_id;
CREATE UNIQUE INDEX idx_accounts_identity_provider_user_id
ON accounts(identity_provider_user_id)
WHERE identity_provider_user_id IS NOT NULL;

-- Add comment for documentation
COMMENT ON COLUMN accounts.identity_provider_user_id IS
'External identity provider user ID. Keycloak format: UUID (36 chars). Auth0 format: auth0|{id} (up to 64 chars). NULL for accounts without external identity integration.';
```

**Rationale**:
- Simpler schema with single identity provider ID column
- Keycloak historical reference not needed post-migration (clean cutover)
- Reduces schema complexity and query overhead
- Follows principle of minimal schema changes
- No backward compatibility needed (one-time migration with maintenance window)

**Rollback Migration** (`V0##__rollback_rename_keycloak_to_identity_provider.sql`):

```sql
-- Rollback: Rename back to keycloak_user_id
ALTER TABLE accounts
RENAME COLUMN identity_provider_user_id TO keycloak_user_id;

-- Restore original size
ALTER TABLE accounts
ALTER COLUMN keycloak_user_id TYPE VARCHAR(36);

-- Restore original index
DROP INDEX IF EXISTS idx_accounts_identity_provider_user_id;
CREATE UNIQUE INDEX idx_accounts_keycloak_user_id
ON accounts(keycloak_user_id)
WHERE keycloak_user_id IS NOT NULL;

-- Remove comment
COMMENT ON COLUMN accounts.keycloak_user_id IS NULL;
```

---

## Domain Entities

### Account Entity (Updated)

**File**: `src/main/java/com/bitbi/dfm/account/domain/Account.java`

**Changes**:
- Rename field `keycloakUserId` → `identityProviderUserId`
- Update column annotation from `keycloak_user_id` → `identity_provider_user_id`
- Expand column length from 36 → 64

**Updated Entity**:

```java
package com.bitbi.dfm.account.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // CHANGED: Renamed from keycloakUserId to identityProviderUserId
    @Column(name = "identity_provider_user_id", length = 64, unique = true)
    private String identityProviderUserId;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "company", length = 200)
    private String company;

    @Column(name = "max_concurrent_batches", nullable = false)
    private Integer maxConcurrentBatches = 5;

    // Constructors
    public Account(String email, String name) {
        this.email = email;
        this.name = name;
        this.createdAt = Instant.now();
    }

    // Business Methods
    public void linkIdentityProvider(String providerUserId) {
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new IllegalArgumentException("Identity provider user ID cannot be null or blank");
        }
        if (providerUserId.length() > 64) {
            throw new IllegalArgumentException("Identity provider user ID cannot exceed 64 characters");
        }
        this.identityProviderUserId = providerUserId;
    }

    public void deactivate() {
        this.isActive = false;
    }

    public void activate() {
        this.isActive = true;
    }

    public boolean hasIdentityProvider() {
        return identityProviderUserId != null && !identityProviderUserId.isBlank();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
```

**Key Changes**:
1. **Field Rename**: `keycloakUserId` → `identityProviderUserId`
2. **Column Annotation**: `keycloak_user_id` → `identity_provider_user_id`
3. **Column Length**: 36 → 64
4. **New Method**: `linkIdentityProvider(String)` for setting Auth0 user ID
5. **New Method**: `hasIdentityProvider()` for checking if identity provider linked

---

## Value Objects

### Auth0UserId (New Value Object)

**Purpose**: Type-safe wrapper for Auth0 user IDs with validation

**File**: `src/main/java/com/bitbi/dfm/auth/domain/Auth0UserId.java`

```java
package com.bitbi.dfm.auth.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Value object representing an Auth0 user ID.
 * Format: auth0|{alphanumeric string}
 * Example: auth0|60f7b8a8b4a0f10074c5d0e1
 */
public record Auth0UserId(String value) {

    private static final Pattern AUTH0_USER_ID_PATTERN =
        Pattern.compile("^auth0\\|[a-zA-Z0-9]+$");

    public Auth0UserId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Auth0 user ID cannot be null or blank");
        }
        if (value.length() > 64) {
            throw new IllegalArgumentException("Auth0 user ID cannot exceed 64 characters");
        }
        if (!AUTH0_USER_ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "Auth0 user ID must match format: auth0|{alphanumeric}. Got: " + value
            );
        }
    }

    public static Auth0UserId of(String value) {
        return new Auth0UserId(value);
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Auth0UserId that = (Auth0UserId) obj;
        return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}
```

**Usage Example**:

```java
// Valid Auth0 user ID
Auth0UserId userId = Auth0UserId.of("auth0|60f7b8a8b4a0f10074c5d0e1");

// Invalid - throws IllegalArgumentException
Auth0UserId invalid = Auth0UserId.of("invalid-format");
```

---

## DTOs (Request/Response)

### CreateAccountRequestDto (Updated)

**Purpose**: Admin creates account with Auth0 integration

**File**: `src/main/java/com/bitbi/dfm/account/presentation/dto/CreateAccountRequestDto.java`

**Changes**: Add optional `role` field for Auth0 role assignment

```java
package com.bitbi.dfm.account.presentation.dto;

import jakarta.validation.constraints.*;

public record CreateAccountRequestDto(
    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    String email,

    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
    String name,

    @Size(max = 20, message = "Phone cannot exceed 20 characters")
    String phone,

    @Size(max = 200, message = "Company cannot exceed 200 characters")
    String company,

    @NotNull(message = "Role is required")
    @Pattern(regexp = "^(USER|ADMIN)$", message = "Role must be either USER or ADMIN")
    String role // NEW: Auth0 role assignment
) {}
```

### CreateAccountResponseDto (Updated)

**Purpose**: Return Auth0 password reset link instead of temporary password

**File**: `src/main/java/com/bitbi/dfm/account/presentation/dto/CreateAccountResponseDto.java`

**Changes**: Replace `temporaryPassword` with `passwordResetLink`

**Before (Keycloak)**:

```java
public record CreateAccountResponseDto(
    UUID id,
    String email,
    String name,
    String temporaryPassword, // <-- REMOVE
    boolean isActive,
    Instant createdAt
) {}
```

**After (Auth0)**:

```java
package com.bitbi.dfm.account.presentation.dto;

import java.time.Instant;
import java.util.UUID;

public record CreateAccountResponseDto(
    UUID id,
    String email,
    String name,
    String passwordResetLink, // <-- ADD (Auth0 password change ticket URL)
    boolean isActive,
    Instant createdAt
) {
    public static CreateAccountResponseDto fromEntity(Account account, String passwordResetLink) {
        return new CreateAccountResponseDto(
            account.getId(),
            account.getEmail(),
            account.getName(),
            passwordResetLink,
            account.isActive(),
            account.getCreatedAt()
        );
    }
}
```

### ResetPasswordResponseDto (Updated)

**Purpose**: Return Auth0 password reset link

**File**: `src/main/java/com/bitbi/dfm/account/presentation/dto/ResetPasswordResponseDto.java`

**Changes**: Replace `temporaryPassword` with `passwordResetLink`

**Before (Keycloak)**:

```java
public record ResetPasswordResponseDto(
    UUID accountId,
    String email,
    String temporaryPassword // <-- REMOVE
) {}
```

**After (Auth0)**:

```java
package com.bitbi.dfm.account.presentation.dto;

import java.util.UUID;

public record ResetPasswordResponseDto(
    UUID accountId,
    String email,
    String passwordResetLink // <-- ADD
) {
    public static ResetPasswordResponseDto of(UUID accountId, String email, String resetLink) {
        return new ResetPasswordResponseDto(accountId, email, resetLink);
    }
}
```

### AccountWithAuth0Dto (New)

**Purpose**: Account details with Auth0-specific fields for admin listing

**File**: `src/main/java/com/bitbi/dfm/account/presentation/dto/AccountWithAuth0Dto.java`

```java
package com.bitbi.dfm.account.presentation.dto;

import com.bitbi.dfm.account.domain.Account;

import java.time.Instant;
import java.util.UUID;

public record AccountWithAuth0Dto(
    UUID id,
    String email,
    String name,
    String phone,
    String company,
    boolean isActive,
    String auth0UserId, // Auth0 user ID (e.g., auth0|xxx)
    boolean isBlocked,  // Auth0 blocked status
    Instant lastLogin,  // Auth0 last_login timestamp
    Instant createdAt
) {
    public static AccountWithAuth0Dto fromEntity(
        Account account,
        boolean isBlocked,
        Instant lastLogin
    ) {
        return new AccountWithAuth0Dto(
            account.getId(),
            account.getEmail(),
            account.getName(),
            account.getPhone(),
            account.getCompany(),
            account.isActive(),
            account.getIdentityProviderUserId(),
            isBlocked,
            lastLogin,
            account.getCreatedAt()
        );
    }
}
```

---

## Repository Changes

### AccountRepository (Updated)

**File**: `src/main/java/com/bitbi/dfm/account/infrastructure/JpaAccountRepository.java`

**Changes**:
- Update queries referencing `keycloakUserId` → `identityProviderUserId`
- Add query to find accounts by Auth0 user ID

```java
package com.bitbi.dfm.account.infrastructure;

import com.bitbi.dfm.account.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface JpaAccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByEmail(String email);

    // UPDATED: Renamed from findByKeycloakUserId
    @Query("SELECT a FROM Account a WHERE a.identityProviderUserId = :identityProviderUserId")
    Optional<Account> findByIdentityProviderUserId(@Param("identityProviderUserId") String identityProviderUserId);

    // NEW: Query for accounts with Auth0 integration
    @Query("""
        SELECT a FROM Account a
        WHERE a.identityProviderUserId IS NOT NULL
        AND a.identityProviderUserId LIKE 'auth0|%'
        AND a.isActive = true
        """)
    List<Account> findAccountsWithAuth0Integration();

    // NEW: Search accounts with Auth0 by email or name
    @Query("""
        SELECT a FROM Account a
        WHERE a.identityProviderUserId IS NOT NULL
        AND a.identityProviderUserId LIKE 'auth0|%'
        AND (LOWER(a.email) LIKE LOWER(CONCAT('%', :search, '%'))
             OR LOWER(a.name) LIKE LOWER(CONCAT('%', :search, '%')))
        """)
    Page<Account> findAccountsWithAuth0BySearch(
        @Param("search") String search,
        Pageable pageable
    );
}
```

---

## Domain Events

### AccountAuth0LinkedEvent (New)

**Purpose**: Domain event triggered when account is linked to Auth0

**File**: `src/main/java/com/bitbi/dfm/account/domain/AccountAuth0LinkedEvent.java`

```java
package com.bitbi.dfm.account.domain;

import java.time.Instant;
import java.util.UUID;

public record AccountAuth0LinkedEvent(
    UUID accountId,
    String email,
    String auth0UserId,
    Instant linkedAt
) {
    public static AccountAuth0LinkedEvent of(Account account) {
        return new AccountAuth0LinkedEvent(
            account.getId(),
            account.getEmail(),
            account.getIdentityProviderUserId(),
            Instant.now()
        );
    }
}
```

---

## Configuration Properties

### Auth0Properties (New)

**Purpose**: Externalize Auth0 configuration

**File**: `src/main/java/com/bitbi/dfm/auth/config/Auth0Properties.java`

```java
package com.bitbi.dfm.auth.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "auth0")
@Validated
public record Auth0Properties(
    @NotBlank
    String domain,

    @NotBlank
    String managementClientId,

    @NotBlank
    String managementClientSecret,

    @NotBlank
    String apiAudience,

    String databaseConnection, // Default: "Username-Password-Authentication"

    Integer passwordResetTtlSeconds, // Default: 86400 (24 hours)

    String rolesNamespace // Default: "https://api.dataforge.com/roles"
) {
    public Auth0Properties {
        if (databaseConnection == null || databaseConnection.isBlank()) {
            databaseConnection = "Username-Password-Authentication";
        }
        if (passwordResetTtlSeconds == null || passwordResetTtlSeconds <= 0) {
            passwordResetTtlSeconds = 86400; // 24 hours
        }
        if (rolesNamespace == null || rolesNamespace.isBlank()) {
            rolesNamespace = "https://api.dataforge.com/roles";
        }
    }
}
```

**Application Configuration** (`application.yml`):

```yaml
auth0:
  domain: ${AUTH0_DOMAIN:dev-example.us.auth0.com}
  management-client-id: ${AUTH0_MGMT_CLIENT_ID}
  management-client-secret: ${AUTH0_MGMT_CLIENT_SECRET}
  api-audience: ${AUTH0_AUDIENCE:https://api.dataforge.com}
  database-connection: Username-Password-Authentication
  password-reset-ttl-seconds: 86400 # 24 hours
  roles-namespace: https://api.dataforge.com/roles

spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://${auth0.domain}/
          audiences: ${auth0.api-audience}
```

---

## Summary of Changes

### Database Schema
- ✅ Rename `keycloak_user_id` → `identity_provider_user_id`
- ✅ Expand column from VARCHAR(36) → VARCHAR(64)
- ✅ Update index name for clarity
- ✅ Add column comment for documentation

### Domain Entities
- ✅ Account: Rename field `keycloakUserId` → `identityProviderUserId`
- ✅ Account: Add `linkIdentityProvider()` method
- ✅ Account: Add `hasIdentityProvider()` method

### Value Objects
- ✅ Auth0UserId: New value object with validation

### DTOs
- ✅ CreateAccountRequestDto: Add `role` field
- ✅ CreateAccountResponseDto: Replace `temporaryPassword` with `passwordResetLink`
- ✅ ResetPasswordResponseDto: Replace `temporaryPassword` with `passwordResetLink`
- ✅ AccountWithAuth0Dto: New DTO for admin listing with Auth0 fields

### Repository
- ✅ Update query methods to use `identityProviderUserId`
- ✅ Add `findByIdentityProviderUserId()` query
- ✅ Add `findAccountsWithAuth0Integration()` query
- ✅ Add `findAccountsWithAuth0BySearch()` query

### Domain Events
- ✅ AccountAuth0LinkedEvent: New event for Auth0 linking

### Configuration
- ✅ Auth0Properties: New configuration properties class
- ✅ application.yml: Add Auth0 configuration section

---

## Validation Rules

### Identity Provider User ID
- **Format (Auth0)**: `auth0|{alphanumeric}` (e.g., `auth0|60f7b8a8b4a0f10074c5d0e1`)
- **Max Length**: 64 characters
- **Nullable**: Yes (for accounts without identity provider integration)
- **Unique**: Yes (enforced by database constraint)

### Role Assignment
- **Valid Values**: `USER`, `ADMIN`
- **Required**: Yes (for Auth0 account creation)
- **Default**: None (must be explicitly specified)

### Password Reset Link
- **Format**: Full Auth0 ticket URL (e.g., `https://{domain}/lo/reset?ticket=xxx`)
- **Expiry**: 24 hours (configurable via `auth0.password-reset-ttl-seconds`)
- **One-time Use**: Yes (Auth0 enforces)

---

## Migration Checklist

- [ ] Apply Flyway migration V0##
- [ ] Update Account entity field names
- [ ] Update repository query methods
- [ ] Create Auth0UserId value object
- [ ] Update CreateAccountRequestDto (add `role`)
- [ ] Update CreateAccountResponseDto (replace `temporaryPassword`)
- [ ] Update ResetPasswordResponseDto (replace `temporaryPassword`)
- [ ] Create AccountWithAuth0Dto
- [ ] Create AccountAuth0LinkedEvent
- [ ] Create Auth0Properties
- [ ] Update application.yml configuration
- [ ] Update unit tests for renamed fields
- [ ] Update integration tests for Auth0 flow
- [ ] Update API documentation (OpenAPI)

---

**Status**: Ready for implementation
**Next Step**: Generate API contracts in `/contracts` directory
