# Data Model: Admin User Management (Keycloak-First Architecture)

**Feature**: 006-task-the-admin
**Date**: 2025-10-28
**Status**: Complete
**Architecture**: Keycloak as primary user store, existing `accounts` table extended for correlation

## Overview

This document defines the data model for user management functionality using a **Keycloak-first architecture**. Instead of creating a separate `users` table, we **extend the existing `Account` entity** with Keycloak integration fields. Keycloak is the source of truth for:
- Authentication credentials (passwords)
- User enabled/disabled status (replaces "locked" concept)
- Temporary password requirements
- Password expiration policies

The PostgreSQL `accounts` table stores:
- Business-specific user data (email, name, phone, company)
- Keycloak user ID for correlation
- Account active/inactive status (separate from Keycloak authentication status)

---

## Architecture Principles

### Keycloak as Source of Truth

**Authentication Layer (Keycloak)**:
- User enabled/disabled status → controls login ability
- Password management (temporary/permanent, expiration)
- Password reset workflows
- Multi-factor authentication (future)

**Business Layer (PostgreSQL accounts table)**:
- Account active/inactive status → controls resource creation (sites, batches)
- Business metadata (phone, company)
- Relationships to business entities (sites, batches)

### Key Distinction

- **Keycloak `enabled` field**: Can the user authenticate?
- **Account `isActive` field**: Can the account create business resources?

Example scenarios:
1. User locked by admin → Keycloak.enabled = false, Account.isActive = true (temporary security measure)
2. Account deactivated → Keycloak.enabled can be true/false, Account.isActive = false (business decision, cascades to sites)

---

## Database Schema Changes

### Extend Existing `accounts` Table

**Migration**: `V006__extend_accounts_with_keycloak.sql`

```sql
-- Add Keycloak integration column to existing accounts table
ALTER TABLE accounts
ADD COLUMN keycloak_user_id VARCHAR(36) UNIQUE;

-- Create index for Keycloak lookups
CREATE INDEX idx_accounts_keycloak_user_id ON accounts(keycloak_user_id);

-- Comment for documentation
COMMENT ON COLUMN accounts.keycloak_user_id IS 'Keycloak user UUID for authentication integration (nullable for backwards compatibility with existing accounts)';
```

**Rationale for Nullable**:
- Existing accounts may not have Keycloak users yet
- Allows gradual migration (create Keycloak users for existing accounts over time)
- New accounts MUST have Keycloak user ID (enforced in application layer)

### Admin Action Audit Log Table (NEW)

**Purpose**: Track all administrative actions on user accounts.

```sql
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
    CONSTRAINT fk_admin_account FOREIGN KEY (admin_account_id) REFERENCES accounts(id) ON DELETE RESTRICT,
    CONSTRAINT chk_action_type CHECK (action_type IN ('CREATE_ACCOUNT', 'LOCK_ACCOUNT', 'UNLOCK_ACCOUNT', 'RESET_PASSWORD')),
    CONSTRAINT chk_status CHECK (status IN ('SUCCESS', 'FAILED')),
    CONSTRAINT chk_error_message CHECK (
        (status = 'SUCCESS' AND error_message IS NULL) OR
        (status = 'FAILED' AND error_message IS NOT NULL)
    )
);

CREATE INDEX idx_audit_target_account ON admin_action_logs(target_account_id);
CREATE INDEX idx_audit_admin_account ON admin_action_logs(admin_account_id);
CREATE INDEX idx_audit_action_type ON admin_action_logs(action_type);
CREATE INDEX idx_audit_created_at ON admin_action_logs(created_at DESC);

COMMENT ON TABLE admin_action_logs IS 'Audit trail for administrative user management actions (uses accounts table, not separate users table)';
```

---

## Domain Model

### Extended Account Entity

**Package**: `com.bitbi.dfm.account.domain`

**Changes to Existing `Account.java`**:

```java
@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor
public class Account {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "phone", length = 50)
    @Convert(converter = PhoneConverter.class)
    private Phone phone;

    @Column(name = "company", length = 255)
    @Convert(converter = CompanyConverter.class)
    private Company company;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    // NEW FIELD: Keycloak integration
    @Column(name = "keycloak_user_id", length = 36, unique = true)
    private String keycloakUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Existing constructor and methods...

    /**
     * NEW: Create account with Keycloak integration.
     * This is the preferred factory method for new accounts going forward.
     */
    public static Account createWithKeycloak(String keycloakUserId, String email,
                                              String name, String phone, String company) {
        Objects.requireNonNull(keycloakUserId, "Keycloak user ID cannot be null for new accounts");

        if (!keycloakUserId.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
            throw new IllegalArgumentException("Invalid Keycloak user ID format (must be UUID)");
        }

        Account account = Account.create(email, name, phone, company);
        account.keycloakUserId = keycloakUserId;
        return account;
    }

    /**
     * NEW: Associate existing account with Keycloak user (for gradual migration).
     */
    public void linkToKeycloak(String keycloakUserId) {
        Objects.requireNonNull(keycloakUserId, "Keycloak user ID cannot be null");

        if (this.keycloakUserId != null) {
            throw new IllegalStateException("Account is already linked to Keycloak user: " + this.keycloakUserId);
        }

        this.keycloakUserId = keycloakUserId;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * NEW: Check if account is integrated with Keycloak.
     */
    public boolean hasKeycloakIntegration() {
        return keycloakUserId != null;
    }

    // Existing methods: deactivate(), activate(), updateName(), etc.
}
```

### AdminActionLog Entity (NEW)

**Package**: `com.bitbi.dfm.account.domain`

```java
@Entity
@Table(name = "admin_action_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminActionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 50)
    private AdminActionType actionType;

    @Column(name = "target_account_id", nullable = false)
    private UUID targetAccountId;

    @Column(name = "admin_account_id", nullable = false)
    private UUID adminAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActionStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Factory Methods
    public static AdminActionLog success(AdminActionType actionType, UUID targetAccountId,
                                          UUID adminAccountId, String ipAddress, String userAgent) {
        AdminActionLog log = new AdminActionLog();
        log.actionType = actionType;
        log.targetAccountId = targetAccountId;
        log.adminAccountId = adminAccountId;
        log.status = ActionStatus.SUCCESS;
        log.ipAddress = ipAddress;
        log.userAgent = userAgent;
        log.createdAt = Instant.now();
        return log;
    }

    public static AdminActionLog failure(AdminActionType actionType, UUID targetAccountId,
                                          UUID adminAccountId, String errorMessage,
                                          String ipAddress, String userAgent) {
        AdminActionLog log = new AdminActionLog();
        log.actionType = actionType;
        log.targetAccountId = targetAccountId;
        log.adminAccountId = adminAccountId;
        log.status = ActionStatus.FAILED;
        log.errorMessage = errorMessage;
        log.ipAddress = ipAddress;
        log.userAgent = userAgent;
        log.createdAt = Instant.now();
        return log;
    }
}
```

---

## Value Objects (Enums)

### AdminActionType Enum (NEW)

```java
package com.bitbi.dfm.account.domain;

public enum AdminActionType {
    CREATE_ACCOUNT("Create Account"),
    LOCK_ACCOUNT("Lock Account"),
    UNLOCK_ACCOUNT("Unlock Account"),
    RESET_PASSWORD("Reset Password");

    private final String displayName;

    AdminActionType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
```

### ActionStatus Enum (NEW)

```java
package com.bitbi.dfm.account.domain;

public enum ActionStatus {
    SUCCESS("Success"),
    FAILED("Failed");

    private final String displayName;

    ActionStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
```

---

## Keycloak User Representation

Keycloak stores user data in its own database. We interact with it via the Admin Client API:

### Keycloak User Attributes

```json
{
  "id": "a3bb189e-8bf9-3888-9912-ace4e6543002",
  "username": "user@example.com",
  "email": "user@example.com",
  "emailVerified": true,
  "enabled": true,  // Controls authentication ability (lock/unlock)
  "firstName": "John",
  "lastName": "Doe",
  "attributes": {
    "accountId": ["550e8400-e29b-41d4-a716-446655440000"]  // PostgreSQL UUID reference
  },
  "credentials": [
    {
      "type": "password",
      "temporary": true,  // Force password change on first login
      "value": "TempPass123!"
    }
  ],
  "requiredActions": ["UPDATE_PASSWORD"],  // When temporary = true
  "createdTimestamp": 1730116800000
}
```

### Keycloak ← → Account Mapping

**Bidirectional Correlation**:
1. `accounts.keycloak_user_id` → Keycloak user ID (UUID string)
2. Keycloak user attributes `accountId` → PostgreSQL accounts.id (UUID)

This allows lookups in both directions:
- Given Account → find Keycloak user by `keycloakUserId`
- Given Keycloak user → find Account by checking `attributes.accountId`

---

## State Transitions

### Account Lifecycle with Keycloak

```
┌──────────────────────────────────────────────────────────────┐
│ CREATE ACCOUNT                                                │
│ 1. Generate temp password                                     │
│ 2. Create Keycloak user (enabled=true, temporary=true)        │
│ 3. Create Account record with keycloak_user_id                │
└────────────────────────────┬─────────────────────────────────┘
                              │
                              ▼
                    ┌──────────────────┐
                    │ ACTIVE + ENABLED  │
                    │ (Temporary Pwd)   │
                    └─────────┬─────────┘
                              │
                              │ User logs in, changes password
                              ▼
                    ┌──────────────────┐
                    │ ACTIVE + ENABLED  │
                    │ (Permanent Pwd)   │
                    └─────────┬─────────┘
                              │
           ┌──────────────────┼──────────────────┐
           │ lock()           │                   │ deactivate()
           ▼                  │                   ▼
   ┌──────────────────┐      │          ┌──────────────────┐
   │ ACTIVE + DISABLED│      │          │ INACTIVE + *     │
   │ (Can't login)    │      │          │ (Business rules) │
   └─────────┬────────┘      │          └─────────┬────────┘
             │                │                    │
             │ unlock()       │                    │ activate()
             └────────────────┼────────────────────┘
                              │
                              │ resetPassword()
                              ▼
                    ┌──────────────────┐
                    │ ACTIVE + ENABLED  │
                    │ (Temporary Pwd)   │
                    └──────────────────┘
```

**State Fields**:
- `Account.isActive`: Business logic (can create sites/batches)
- `Keycloak.enabled`: Authentication (can login)

**Operations**:
- **lock()**: Set Keycloak.enabled = false (prevent login)
- **unlock()**: Set Keycloak.enabled = true (allow login)
- **deactivate()**: Set Account.isActive = false (business deactivation, may also disable Keycloak)
- **activate()**: Set Account.isActive = true (business reactivation)
- **resetPassword()**: Set Keycloak credential temporary = true

---

## Frontend Type Definitions

### TypeScript Types (entities/account/model/types.ts)

```typescript
export interface Account {
  id: string;  // UUID
  keycloakUserId: string | null;  // UUID (null for legacy accounts)
  email: string;
  name: string;
  phone: string | null;
  company: string | null;
  isActive: boolean;
  createdAt: string;  // ISO 8601
  updatedAt: string;  // ISO 8601
}

export interface AccountWithKeycloakStatus extends Account {
  keycloakEnabled: boolean;  // From Keycloak user.enabled
  passwordTemporary: boolean;  // From Keycloak credentials
  passwordExpiresAt: string | null;  // From Keycloak (if temporary)
}

export interface CreateAccountRequest {
  email: string;
  name: string;
  phone?: string;
  company?: string;
  role: string;  // Keycloak role assignment
}

export interface CreateAccountResponse {
  account: AccountWithKeycloakStatus;
  temporaryPassword: string;
}

export interface ResetPasswordResponse {
  accountId: string;
  temporaryPassword: string;
  expiresAt: string;  // 30 days from now
}

export interface AdminActionLog {
  id: number;
  actionType: 'CREATE_ACCOUNT' | 'LOCK_ACCOUNT' | 'UNLOCK_ACCOUNT' | 'RESET_PASSWORD';
  targetAccountId: string;
  adminAccountId: string;
  status: 'SUCCESS' | 'FAILED';
  errorMessage?: string;
  ipAddress?: string;
  createdAt: string;
}
```

### Zod Validation Schemas (features/user-management/model/userSchemas.ts)

```typescript
import { z } from 'zod';

export const createAccountSchema = z.object({
  email: z.string()
    .email('Invalid email format')
    .min(3)
    .max(255),
  name: z.string()
    .min(1, 'Name is required')
    .max(255),
  phone: z.string()
    .regex(/^\+?[0-9]{7,15}$/, 'Invalid phone format')
    .optional(),
  company: z.string()
    .min(2)
    .max(255)
    .optional(),
  role: z.string()
    .min(1, 'Role is required'),
});

export type CreateAccountFormData = z.infer<typeof createAccountSchema>;
```

---

## Validation Rules Summary

| Field | Backend Validation | Frontend Validation | Database Constraint |
|-------|-------------------|---------------------|---------------------|
| email | Email regex, unique | Zod email validator | UNIQUE, NOT NULL |
| name | Length 1-255 | Zod min/max | NOT NULL |
| keycloakUserId | UUID format, unique | N/A | UNIQUE, NULL allowed |
| isActive | Boolean (existing) | N/A | NOT NULL |
| phone | Phone.of() validation | Zod regex | length ≤50 |
| company | Company.of() validation | Zod min/max | length ≤255 |

---

## Migration Script

**File**: `src/main/resources/db/migration/V006__extend_accounts_with_keycloak.sql`

```sql
-- Add Keycloak integration to existing accounts table
ALTER TABLE accounts
ADD COLUMN keycloak_user_id VARCHAR(36);

-- Add unique constraint
ALTER TABLE accounts
ADD CONSTRAINT uq_keycloak_user_id UNIQUE (keycloak_user_id);

-- Create index for lookups
CREATE INDEX idx_accounts_keycloak_user_id ON accounts(keycloak_user_id);

-- Comment
COMMENT ON COLUMN accounts.keycloak_user_id IS 'Keycloak user UUID for authentication (nullable for backwards compatibility)';

-- Create admin_action_logs table
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
    CONSTRAINT fk_admin_account FOREIGN KEY (admin_account_id) REFERENCES accounts(id) ON DELETE RESTRICT,
    CONSTRAINT chk_action_type CHECK (action_type IN ('CREATE_ACCOUNT', 'LOCK_ACCOUNT', 'UNLOCK_ACCOUNT', 'RESET_PASSWORD')),
    CONSTRAINT chk_status CHECK (status IN ('SUCCESS', 'FAILED')),
    CONSTRAINT chk_error_message CHECK (
        (status = 'SUCCESS' AND error_message IS NULL) OR
        (status = 'FAILED' AND error_message IS NOT NULL)
    )
);

-- Indexes for audit logs
CREATE INDEX idx_audit_target_account ON admin_action_logs(target_account_id);
CREATE INDEX idx_audit_admin_account ON admin_action_logs(admin_account_id);
CREATE INDEX idx_audit_action_type ON admin_action_logs(action_type);
CREATE INDEX idx_audit_created_at ON admin_action_logs(created_at DESC);

-- Comments
COMMENT ON TABLE admin_action_logs IS 'Audit trail for administrative account management actions';
```

---

## Summary

- **Architecture**: Keycloak-first (authentication source of truth)
- **Database**: Extend existing `accounts` table with `keycloak_user_id` column
- **No Duplication**: Reuse Account aggregate, no separate users table
- **Bidirectional Mapping**: accounts.keycloak_user_id ↔ Keycloak user.attributes.accountId
- **State Management**: Keycloak.enabled (authentication) + Account.isActive (business logic)
- **Audit Trail**: New admin_action_logs table references accounts table
- **Backwards Compatibility**: keycloak_user_id nullable for existing accounts

Ready to proceed to API contract generation.
