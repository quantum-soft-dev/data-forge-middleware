# Data Model

**Feature**: Site Management for Users and Admins
**Date**: 2025-10-30
**Status**: Complete

## Overview

This document defines the data entities, relationships, and validation rules for the site management feature. The model extends the existing Site entity and introduces a new AdminActionLog aggregate for audit trail capabilities.

## Entity Diagram

```
┌─────────────────────────────────────────────┐
│             Account (existing)              │
├─────────────────────────────────────────────┤
│ - id: UUID (PK)                            │
│ - email: String                             │
│ - name: String                              │
│ - isActive: Boolean                         │
│ - createdAt: Instant                        │
│ - keycloakUserId: String (nullable)         │
└─────────────────────────────────────────────┘
                   │ 1
                   │
                   │ owns
                   │
                   │ *
┌─────────────────────────────────────────────┐
│              Site (existing)                │
├─────────────────────────────────────────────┤
│ - id: UUID (PK)                            │
│ - accountId: UUID (FK → Account.id)        │
│ - domain: String                            │
│ - name: String                              │
│ - clientSecret: String (hashed)             │
│ - isActive: Boolean                         │
│ - createdAt: Instant                        │
│ - updatedAt: Instant                        │
│                                             │
│ Computed:                                   │
│ - siteIdentifier: String (accountId+"_"+domain) │
└─────────────────────────────────────────────┘
                   │ 1
                   │
                   │ referenced by
                   │
                   │ *
┌─────────────────────────────────────────────┐
│         AdminActionLog (NEW)                │
├─────────────────────────────────────────────┤
│ - id: UUID (PK)                            │
│ - actionType: String (enum)                 │
│ - targetAccountId: UUID (FK → Account.id)  │
│ - targetSiteId: UUID (FK → Site.id, nullable) │
│ - adminAccountId: UUID (FK → Account.id)   │
│ - ipAddress: String                         │
│ - userAgent: String                         │
│ - success: Boolean                          │
│ - errorMessage: String (nullable)           │
│ - createdAt: Instant                        │
└─────────────────────────────────────────────┘
```

---

## Entities

### 1. Site (Existing - No Schema Changes Required)

**Purpose**: Represents a monitored domain/website under a user's account. Existing entity from CLAUDE.md with all necessary columns already present.

**Attributes**:

| Attribute     | Type      | Constraints                   | Description |
|---------------|-----------|-------------------------------|-------------|
| id            | UUID      | PRIMARY KEY                   | Unique identifier for the site |
| accountId     | UUID      | FOREIGN KEY (accounts.id), NOT NULL, INDEX | Owner account reference |
| domain        | String    | NOT NULL, LENGTH(3-255)       | Domain name (e.g., "example.com") |
| name          | String    | NOT NULL, LENGTH(2-100)       | Display name for the site |
| clientSecret  | String    | NOT NULL                      | Hashed password for site authentication |
| isActive      | Boolean   | NOT NULL, DEFAULT TRUE        | Activation status (soft delete flag) |
| createdAt     | Instant   | NOT NULL, DEFAULT CURRENT_TIMESTAMP | Creation timestamp |
| updatedAt     | Instant   | NOT NULL, DEFAULT CURRENT_TIMESTAMP | Last modification timestamp |

**Computed Fields**:
- `siteIdentifier`: `accountId + "_" + domain` (not stored, generated in domain logic for uniqueness validation)

**Validation Rules**:
- **FR-015**: Domain must match regex `^[a-z0-9.-]{3,255}$` (alphanumeric, hyphens, dots)
- **FR-016**: Password (clientSecret) minimum 8 characters before hashing
- **FR-017**: Domain must be unique within account (composite unique constraint on account_id + domain)
- **FR-018**: Same domain allowed across different accounts
- **FR-020**: clientSecret hashed with bcrypt (existing Site entity logic)

**Relationships**:
- **Many-to-One** with Account: `Site.accountId → Account.id`
- **One-to-Many** with Batch: Existing relationship (not modified)
- **One-to-Many** with AdminActionLog: `AdminActionLog.targetSiteId → Site.id`

**State Transitions**:
```
┌─────────┐  create  ┌─────────────┐
│   ∅     │ ──────→  │ Active      │
│ (none)  │          │ (isActive=T)│
└─────────┘          └─────────────┘
                            │ ↓ ↑
                            │ deactivate / activate
                            │ ↓ ↑
                     ┌──────────────┐
                     │  Inactive    │
                     │ (isActive=F) │
                     └──────────────┘
                            │ ↓
                            │ delete (soft)
                            │ ↓
                     ┌──────────────┐
                     │  Deleted     │
                     │ (isActive=F) │
                     └──────────────┘
```

**Indexes**:
- Existing: `idx_sites_account_id` on (account_id)
- **Recommended New**: `idx_sites_account_domain` on (account_id, domain) for duplicate domain check (UNIQUE constraint)
- **Recommended New**: `idx_sites_is_active` on (is_active) for filtering active sites

---

### 2. AdminActionLog (NEW Aggregate)

**Purpose**: Audit trail for all administrative actions performed on user sites. Supports compliance and security monitoring.

**Attributes**:

| Attribute        | Type      | Constraints                     | Description |
|------------------|-----------|---------------------------------|-------------|
| id               | UUID      | PRIMARY KEY                     | Unique identifier for the log entry |
| actionType       | String    | NOT NULL, LENGTH(50), CHECK constraint | Type of admin action (enum) |
| targetAccountId  | UUID      | FOREIGN KEY (accounts.id), NOT NULL, INDEX | Account affected by the action |
| targetSiteId     | UUID      | FOREIGN KEY (sites.id), NULLABLE | Site affected (null for account-level actions) |
| adminAccountId   | UUID      | FOREIGN KEY (accounts.id), NOT NULL, INDEX | Admin who performed the action |
| ipAddress        | String    | NULLABLE, LENGTH(45)            | IPv4 or IPv6 address of admin |
| userAgent        | String    | NULLABLE, LENGTH(500)           | Browser user agent string |
| success          | Boolean   | NOT NULL                        | Whether action succeeded |
| errorMessage     | String    | NULLABLE, LENGTH(1000)          | Error details if success=false |
| createdAt        | Instant   | NOT NULL, DEFAULT CURRENT_TIMESTAMP, INDEX | When action occurred |

**Action Types** (CHECK constraint):
- `CREATE_SITE`: Admin created a site for a user
- `DEACTIVATE_SITE`: Admin deactivated a user's site
- `ACTIVATE_SITE`: Admin reactivated a user's site
- `DELETE_SITE`: Admin soft-deleted a user's site

**Validation Rules**:
- **FR-014**: All admin site actions must be logged (CREATE, DEACTIVATE, ACTIVATE, DELETE)
- **Mandatory fields**: actionType, targetAccountId, adminAccountId, success, createdAt
- **Optional fields**: targetSiteId (null for future account-level actions), ipAddress, userAgent, errorMessage

**Relationships**:
- **Many-to-One** with Account (target): `AdminActionLog.targetAccountId → Account.id`
- **Many-to-One** with Account (admin): `AdminActionLog.adminAccountId → Account.id`
- **Many-to-One** with Site: `AdminActionLog.targetSiteId → Site.id` (nullable)

**Indexes**:
- **Primary**: `pk_admin_action_logs` on (id)
- **Query optimization**: `idx_admin_logs_target_account` on (target_account_id, created_at DESC) for filtering by user
- **Query optimization**: `idx_admin_logs_admin_account` on (admin_account_id, created_at DESC) for filtering by admin
- **Query optimization**: `idx_admin_logs_created_at` on (created_at DESC) for time-based queries

---

## Database Schema (SQL)

### Flyway Migration V008: Admin Action Logs

```sql
-- V008__add_admin_action_logs.sql
-- Migration for admin audit logging feature

CREATE TABLE admin_action_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action_type VARCHAR(50) NOT NULL,
    target_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    target_site_id UUID REFERENCES sites(id) ON DELETE SET NULL,
    admin_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    success BOOLEAN NOT NULL DEFAULT TRUE,
    error_message VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_action_type CHECK (action_type IN ('CREATE_SITE', 'DEACTIVATE_SITE', 'ACTIVATE_SITE', 'DELETE_SITE'))
);

-- Indexes for query performance
CREATE INDEX idx_admin_logs_target_account ON admin_action_logs(target_account_id, created_at DESC);
CREATE INDEX idx_admin_logs_admin_account ON admin_action_logs(admin_account_id, created_at DESC);
CREATE INDEX idx_admin_logs_created_at ON admin_action_logs(created_at DESC);

-- Add composite unique constraint to sites table for domain uniqueness per account
-- (This is recommended if not already present)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uk_sites_account_domain'
    ) THEN
        ALTER TABLE sites ADD CONSTRAINT uk_sites_account_domain UNIQUE (account_id, domain);
    END IF;
END $$;

-- Add index on is_active for filtering (if not already present)
CREATE INDEX IF NOT EXISTS idx_sites_is_active ON sites(is_active);

-- Comment documentation
COMMENT ON TABLE admin_action_logs IS 'Audit trail for administrative actions on user accounts and sites';
COMMENT ON COLUMN admin_action_logs.action_type IS 'Type of admin action: CREATE_SITE, DEACTIVATE_SITE, ACTIVATE_SITE, DELETE_SITE';
COMMENT ON COLUMN admin_action_logs.target_account_id IS 'Account affected by this admin action';
COMMENT ON COLUMN admin_action_logs.target_site_id IS 'Site affected by this action (nullable for account-level actions)';
COMMENT ON COLUMN admin_action_logs.admin_account_id IS 'Administrator who performed this action';
COMMENT ON COLUMN admin_action_logs.success IS 'Whether the action completed successfully';
COMMENT ON COLUMN admin_action_logs.error_message IS 'Error details if success=false';
```

---

## Domain Value Objects

### SiteIdentifier (Composite Key)

**Purpose**: Ensures global uniqueness of sites across the system

**Composition**: `accountId + "_" + domain`

**Example**: If accountId is `550e8400-e29b-41d4-a716-446655440000` and domain is `example.com`, siteIdentifier is:
```
550e8400-e29b-41d4-a716-446655440000_example.com
```

**Usage**:
- Not stored in database (computed on-the-fly)
- Used for duplicate detection: "Does a site with this identifier already exist?"
- Deterministic: Same account + domain always produces same identifier

**Java Implementation**:
```java
public class Site {
    public String getSiteIdentifier() {
        return this.accountId + "_" + this.domain;
    }
}
```

---

### SiteStatus (Enum)

**Purpose**: Represents the lifecycle state of a site

**Values**:
- `ACTIVE`: Site is operational, accepts uploads (isActive = true)
- `INACTIVE`: Site is suspended, rejects uploads (isActive = false)

**Note**: "DELETED" is not a separate status. Deleted sites are simply INACTIVE with no UI visibility.

**Mapping**:
- Database stores boolean `is_active`
- Domain logic interprets: `is_active = true → ACTIVE`, `is_active = false → INACTIVE`

---

### AdminActionType (Enum)

**Purpose**: Type-safe representation of admin actions for audit logging

**Values**:
- `CREATE_SITE`: Admin created a new site for a user
- `DEACTIVATE_SITE`: Admin deactivated a user's site
- `ACTIVATE_SITE`: Admin reactivated a user's site
- `DELETE_SITE`: Admin soft-deleted a user's site

**Database Constraint**: CHECK constraint enforces only these four values

**Java Implementation**:
```java
public enum AdminActionType {
    CREATE_SITE,
    DEACTIVATE_SITE,
    ACTIVATE_SITE,
    DELETE_SITE
}
```

---

## Data Integrity Rules

### Referential Integrity

1. **Site → Account**: Cascade behavior varies:
   - **ON DELETE CASCADE**: If account is deleted, all sites are deleted (soft delete both)
   - **ON UPDATE CASCADE**: If account.id changes (unlikely UUID), sites follow

2. **AdminActionLog → Account (target)**:
   - **ON DELETE CASCADE**: If target account is deleted, audit logs are deleted (debatable - could be SET NULL for compliance)

3. **AdminActionLog → Account (admin)**:
   - **ON DELETE CASCADE**: If admin account is deleted, audit logs are deleted (debatable - could be SET NULL to preserve trail)

4. **AdminActionLog → Site**:
   - **ON DELETE SET NULL**: If site is deleted, preserve audit log but nullify site reference

### Uniqueness Constraints

1. **Site**: Composite unique constraint on `(account_id, domain)`
   - Ensures FR-017: Same domain can exist across accounts but not within an account

2. **AdminActionLog**: No uniqueness constraints (multiple logs for same action over time are valid)

### Check Constraints

1. **Site.domain**: Regex validation enforced in application layer (not database CHECK constraint due to regex complexity)
2. **Site.clientSecret**: Length ≥ 8 before hashing (enforced in domain logic, not database)
3. **AdminActionLog.actionType**: CHECK constraint on enum values (CREATE_SITE, DEACTIVATE_SITE, ACTIVATE_SITE, DELETE_SITE)

---

## Query Patterns

### Backend Queries

#### User Site Management Queries

1. **List all active sites for a user** (FR-001, FR-009):
```sql
SELECT * FROM sites
WHERE account_id = :accountId
  AND is_active = TRUE
ORDER BY created_at DESC;
```

2. **Check if domain exists for account** (FR-017):
```sql
SELECT COUNT(*) FROM sites
WHERE account_id = :accountId
  AND domain = :domain
  AND is_active = TRUE;
```

3. **Create site** (FR-002):
```sql
INSERT INTO sites (id, account_id, domain, name, client_secret, is_active, created_at, updated_at)
VALUES (:id, :accountId, :domain, :name, :hashedPassword, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
```

4. **Deactivate site** (FR-004):
```sql
UPDATE sites
SET is_active = FALSE, updated_at = CURRENT_TIMESTAMP
WHERE id = :siteId AND account_id = :accountId;
```

5. **Activate site** (FR-005):
```sql
UPDATE sites
SET is_active = TRUE, updated_at = CURRENT_TIMESTAMP
WHERE id = :siteId AND account_id = :accountId;
```

6. **Soft delete site** (FR-006, FR-021):
```sql
UPDATE sites
SET is_active = FALSE, updated_at = CURRENT_TIMESTAMP
WHERE id = :siteId AND account_id = :accountId;
```

#### Admin Audit Logging Queries

7. **Log admin action** (FR-014):
```sql
INSERT INTO admin_action_logs (id, action_type, target_account_id, target_site_id, admin_account_id, ip_address, user_agent, success, created_at)
VALUES (:id, :actionType, :targetAccountId, :targetSiteId, :adminAccountId, :ipAddress, :userAgent, :success, CURRENT_TIMESTAMP);
```

8. **Get audit logs for account** (future admin UI):
```sql
SELECT * FROM admin_action_logs
WHERE target_account_id = :accountId
ORDER BY created_at DESC
LIMIT 50;
```

9. **Get audit logs by admin** (future compliance reporting):
```sql
SELECT * FROM admin_action_logs
WHERE admin_account_id = :adminAccountId
ORDER BY created_at DESC
LIMIT 50;
```

### Frontend Query Keys

TanStack Query keys for cache management:

1. **User sites list**: `['sites', accountId]`
2. **Admin viewing user sites**: `['admin', 'sites', accountId]`
3. **Single site details**: `['sites', accountId, siteId]` (future use)

**Cache Invalidation**:
- After `createSite`: Invalidate `['sites', accountId]`
- After `updateSiteStatus`: Invalidate `['sites', accountId]` or optimistic update
- After `deleteSite`: Invalidate `['sites', accountId]`

---

## Data Validation Summary

### Backend Validation (Jakarta Bean Validation)

**CreateSiteRequestDto**:
```java
public record CreateSiteRequestDto(
    @NotBlank(message = "Domain is required")
    @Size(min = 3, max = 255, message = "Domain must be 3-255 characters")
    @Pattern(regexp = "^[a-z0-9.-]+$", message = "Domain can only contain lowercase letters, numbers, dots, and hyphens")
    String domain,

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    String password
) {}
```

### Frontend Validation (Zod)

**CreateSiteFormSchema**:
```typescript
export const CreateSiteFormSchema = z.object({
  domain: z.string()
    .min(3, "Domain must be at least 3 characters")
    .max(255, "Domain too long")
    .regex(/^[a-z0-9.-]+$/, "Domain can only contain lowercase letters, numbers, dots, and hyphens"),
  password: z.string()
    .min(8, "Password must be at least 8 characters")
});

export type CreateSiteFormData = z.infer<typeof CreateSiteFormSchema>;
```

---

## Performance Considerations

### Indexing Strategy

1. **sites.account_id**: Existing index, used for "list user sites" query
2. **sites (account_id, domain)**: Composite unique index, used for duplicate detection
3. **sites.is_active**: New index, used for filtering active sites (small overhead, large query benefit)
4. **admin_action_logs.target_account_id**: Index for audit log queries by user
5. **admin_action_logs.created_at**: Index for time-based audit queries

### Query Optimization

- **Avoid N+1**: Use JOIN FETCH in JPA when loading sites with related entities (if needed in future)
- **Pagination**: Not implemented in MVP (per clarification), but index on created_at supports future pagination
- **Soft delete filtering**: Always include `is_active = TRUE` in WHERE clause to exclude deleted sites

### Scaling Considerations

- **Site count per account**: Target 50, support unlimited. Indexes handle thousands efficiently.
- **Audit log growth**: Partition admin_action_logs by month if volume exceeds 1M rows (future consideration)
- **Archive strategy**: Consider archiving audit logs older than 1 year to separate table (compliance-driven)

---

## References

- Feature Spec: [spec.md](./spec.md)
- Research: [research.md](./research.md)
- CLAUDE.md: Existing Site entity schema
- PostgreSQL Docs: https://www.postgresql.org/docs/16/
- Spring Data JPA Docs: https://spring.io/projects/spring-data-jpa
