# Data Model: Plugin System & Bit BI OAuth Integration

**Date**: 2025-12-16
**Feature**: 013-plugin-system
**Source**: [spec.md](./spec.md), [research.md](./research.md)

## Entity Relationship Diagram

```
┌─────────────────────┐          ┌─────────────────────┐
│      accounts       │          │    plugin_configs   │
├─────────────────────┤          ├─────────────────────┤
│ id (PK)             │          │ id (PK)             │
│ email               │◄─────────│ plugin_id (UNIQUE)  │
│ name                │    1:N   │ client_id           │
│ ...                 │          │ display_name        │
└─────────────────────┘          │ is_enabled          │
         │                       │ config (JSONB)      │
         │                       │ created_at          │
         │                       │ updated_at          │
         │                       └─────────────────────┘
         │                                 │
         │ 1:N                             │
         ▼                                 │ 1:N
┌─────────────────────┐                    ▼
│   account_plugins   │◄───────────────────┐
├─────────────────────┤                    │
│ id (PK)             │   FK (account_id)  │
│ account_id (FK)     │────────────────────┘ FK (plugin_id)
│ plugin_id (FK)      │
│ plugin_data (JSONB) │
│ is_active           │
│ activated_at        │
│ deactivated_at      │
│ last_used_at        │
│ created_at          │
│ updated_at          │
│ UNIQUE(account_id,  │
│        plugin_id)   │
└─────────────────────┘
         │
         │ 1:N (for audit)
         ▼
┌─────────────────────┐
│ plugin_audit_logs   │
├─────────────────────┤ Partitioned by month
│ id (PK)             │ (occurred_at)
│ plugin_id           │
│ account_id          │
│ client_id           │
│ action_type         │
│ request_body_hash   │
│ request_body_size   │
│ response_status     │
│ success             │
│ error_message       │
│ ip_address          │
│ user_agent          │
│ duration_ms         │
│ occurred_at         │
└─────────────────────┘
```

## Entities

### 1. PluginConfig (Aggregate Root)

**Purpose**: Database-stored configuration for registered plugins. Links compile-time plugins to their Auth0 client credentials and runtime settings.

**Table**: `plugin_configs`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | BIGSERIAL | PK | Auto-generated surrogate key |
| `plugin_id` | VARCHAR(64) | NOT NULL, UNIQUE | Matches Plugin.getId() from code |
| `client_id` | VARCHAR(64) | NOT NULL, UNIQUE | Auth0 M2M application client_id |
| `display_name` | VARCHAR(100) | NOT NULL | Human-readable name for UI |
| `is_enabled` | BOOLEAN | NOT NULL, DEFAULT true | Runtime enable/disable flag |
| `config` | JSONB | | Plugin-specific configuration |
| `created_at` | TIMESTAMP | NOT NULL, DEFAULT NOW() | Record creation time |
| `updated_at` | TIMESTAMP | NOT NULL, DEFAULT NOW() | Last modification time |

**Indexes**:
- `UNIQUE INDEX idx_plugin_configs_plugin_id ON plugin_configs(plugin_id)`
- `UNIQUE INDEX idx_plugin_configs_client_id ON plugin_configs(client_id)`

**Business Rules**:
- `plugin_id` must match a registered Plugin bean at startup (validated by PluginStartupValidator)
- `is_enabled = false` prevents new activations but doesn't deactivate existing ones
- `client_id` is used to verify that API calls come from authorized OAuth clients

**Domain Object**:
```java
@Entity
@Table(name = "plugin_configs")
public class PluginConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plugin_id", length = 64, nullable = false, unique = true)
    private String pluginId;

    @Column(name = "client_id", length = 64, nullable = false, unique = true)
    private String clientId;

    @Column(name = "display_name", length = 100, nullable = false)
    private String displayName;

    @Column(name = "is_enabled", nullable = false)
    private boolean isEnabled = true;

    @Type(JsonBinaryType.class)
    @Column(name = "config", columnDefinition = "jsonb")
    private Map<String, Object> config;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Factory methods
    public static PluginConfig create(String pluginId, String clientId, String displayName) { ... }

    // Business methods
    public void enable() { this.isEnabled = true; this.updatedAt = Instant.now(); }
    public void disable() { this.isEnabled = false; this.updatedAt = Instant.now(); }
    public void updateConfig(Map<String, Object> config) { ... }
}
```

---

### 2. AccountPlugin (Entity)

**Purpose**: Activation record linking an Account to a Plugin. Contains plugin-specific data (e.g., Bit BI's tenantId) and activation status.

**Table**: `account_plugins`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | BIGSERIAL | PK | Auto-generated surrogate key |
| `account_id` | UUID | NOT NULL, FK accounts(id) | Owning account |
| `plugin_id` | VARCHAR(64) | NOT NULL, FK plugin_configs(plugin_id) | Target plugin |
| `plugin_data` | JSONB | NOT NULL | Plugin-specific data (validated against schema) |
| `is_active` | BOOLEAN | NOT NULL, DEFAULT true | Current activation status |
| `activated_at` | TIMESTAMP | NOT NULL | First activation timestamp |
| `deactivated_at` | TIMESTAMP | | Most recent deactivation timestamp |
| `last_used_at` | TIMESTAMP | | Last event received timestamp (FR-018) |
| `created_at` | TIMESTAMP | NOT NULL, DEFAULT NOW() | Record creation time |
| `updated_at` | TIMESTAMP | NOT NULL, DEFAULT NOW() | Last modification time |

**Constraints**:
- `UNIQUE (account_id, plugin_id)` - One activation per account-plugin pair
- `FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE`
- `FOREIGN KEY (plugin_id) REFERENCES plugin_configs(plugin_id) ON DELETE RESTRICT`

**Indexes**:
- `UNIQUE INDEX idx_account_plugins_account_plugin ON account_plugins(account_id, plugin_id)`
- `INDEX idx_account_plugins_plugin_id ON account_plugins(plugin_id)`
- `INDEX idx_account_plugins_is_active ON account_plugins(is_active) WHERE is_active = true`
- `GIN INDEX idx_account_plugins_data ON account_plugins USING GIN(plugin_data)`

**Business Rules**:
- `plugin_data` must validate against the plugin's JSON Schema before insert/update (FR-004)
- Upsert behavior: If `(account_id, plugin_id)` exists, update `plugin_data` and set `is_active = true` (FR-005)
- Deactivation sets `is_active = false` and `deactivated_at = NOW()` (soft delete)
- `last_used_at` updated when plugin receives an event (FR-018)

**Domain Object**:
```java
@Entity
@Table(name = "account_plugins")
public class AccountPlugin {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "plugin_id", length = 64, nullable = false)
    private String pluginId;

    @Type(JsonBinaryType.class)
    @Column(name = "plugin_data", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> pluginData;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "activated_at", nullable = false)
    private Instant activatedAt;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    // Factory methods
    public static AccountPlugin activate(UUID accountId, String pluginId, Map<String, Object> pluginData) {
        AccountPlugin ap = new AccountPlugin();
        ap.accountId = accountId;
        ap.pluginId = pluginId;
        ap.pluginData = pluginData;
        ap.isActive = true;
        ap.activatedAt = Instant.now();
        ap.createdAt = Instant.now();
        ap.updatedAt = Instant.now();
        return ap;
    }

    // Business methods
    public void deactivate() {
        this.isActive = false;
        this.deactivatedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void reactivate(Map<String, Object> newPluginData) {
        this.pluginData = newPluginData;
        this.isActive = true;
        this.deactivatedAt = null;
        this.updatedAt = Instant.now();
    }

    public void recordUsage() {
        this.lastUsedAt = Instant.now();
    }
}
```

---

### 3. PluginAuditLog (Entity)

**Purpose**: Audit trail for plugin API calls and event executions. Partitioned by month for efficient querying and potential retention management.

**Table**: `plugin_audit_logs` (partitioned)

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | BIGSERIAL | PK | Auto-generated within partition |
| `plugin_id` | VARCHAR(64) | NOT NULL | Plugin identifier |
| `account_id` | UUID | | Target account (null for non-account operations) |
| `client_id` | VARCHAR(64) | | OAuth client that made the request |
| `action_type` | VARCHAR(50) | NOT NULL | ACTIVATE, DEACTIVATE, EVENT_DISPATCHED, EVENT_FAILED |
| `request_body_hash` | VARCHAR(64) | | SHA-256 hash of request body (base64) |
| `request_body_size` | INTEGER | | Request body size in bytes |
| `response_status` | INTEGER | | HTTP response status code |
| `success` | BOOLEAN | NOT NULL | Operation succeeded |
| `error_message` | VARCHAR(500) | | Error message if failed |
| `ip_address` | VARCHAR(45) | | Client IP (supports IPv6) |
| `user_agent` | VARCHAR(512) | | Client user agent |
| `duration_ms` | BIGINT | | Operation duration in milliseconds |
| `occurred_at` | TIMESTAMP | NOT NULL | When the operation occurred |

**Partitioning**:
```sql
CREATE TABLE plugin_audit_logs (
    ...
) PARTITION BY RANGE (occurred_at);

-- Create partitions for current and next 3 months
CREATE TABLE plugin_audit_logs_2025_12 PARTITION OF plugin_audit_logs
    FOR VALUES FROM ('2025-12-01') TO ('2026-01-01');
```

**Indexes** (per partition):
- `INDEX idx_plugin_audit_logs_plugin_id ON plugin_audit_logs(plugin_id)`
- `INDEX idx_plugin_audit_logs_account_id ON plugin_audit_logs(account_id)`
- `INDEX idx_plugin_audit_logs_occurred_at ON plugin_audit_logs(occurred_at DESC)`
- `INDEX idx_plugin_audit_logs_action_type ON plugin_audit_logs(action_type)`

**Business Rules**:
- Request body is NEVER stored in plaintext - only SHA-256 hash (FR-014)
- `success = false` requires `error_message` to be populated
- Retention is indefinite per clarification (aligned with existing admin_action_logs)

**Domain Object**:
```java
@Entity
@Table(name = "plugin_audit_logs")
public class PluginAuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plugin_id", length = 64, nullable = false)
    private String pluginId;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "client_id", length = 64)
    private String clientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", length = 50, nullable = false)
    private PluginActionType actionType;

    @Column(name = "request_body_hash", length = 64)
    private String requestBodyHash;

    @Column(name = "request_body_size")
    private Integer requestBodySize;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    // Factory methods
    public static PluginAuditLog success(String pluginId, UUID accountId, PluginActionType actionType) { ... }
    public static PluginAuditLog failure(String pluginId, UUID accountId, PluginActionType actionType, String error) { ... }
}
```

---

## Value Objects & Enums

### PluginEventType (Enum)

```java
public enum PluginEventType {
    BATCH_COMPLETED("batch.completed"),
    BATCH_FAILED("batch.failed"),
    BATCH_EXPIRED("batch.expired"),
    FILE_UPLOADED("file.uploaded");

    private final String eventName;
}
```

### PluginActionType (Enum)

```java
public enum PluginActionType {
    ACTIVATE,
    DEACTIVATE,
    REACTIVATE,
    EVENT_DISPATCHED,
    EVENT_FAILED,
    EVENT_TIMEOUT
}
```

### PluginEvent (Value Object)

```java
public record PluginEvent(
    UUID eventId,
    PluginEventType type,
    UUID accountId,
    UUID resourceId,      // batchId, fileId, etc.
    Map<String, Object> metadata,
    Instant occurredAt
) {
    public static PluginEvent fromBatchCompleted(BatchCompletedEvent event, UUID accountId) {
        return new PluginEvent(
            event.eventId(),
            PluginEventType.BATCH_COMPLETED,
            accountId,
            event.batchId(),
            Map.of(
                "uploadedFilesCount", event.uploadedFilesCount(),
                "totalSize", event.totalSize()
            ),
            event.occurredAt()
        );
    }
}
```

---

## State Transitions

### AccountPlugin Lifecycle

```
                    activate()
    [NOT EXISTS] ──────────────► [ACTIVE]
                                    │
                        deactivate()│
                                    ▼
                              [INACTIVE]
                                    │
                       reactivate() │
                                    ▼
                               [ACTIVE]
```

**State Rules**:
1. **NOT EXISTS → ACTIVE**: First activation creates record with `is_active = true`
2. **ACTIVE → INACTIVE**: Deactivation sets `is_active = false`, `deactivated_at = NOW()`
3. **INACTIVE → ACTIVE**: Reactivation sets `is_active = true`, `deactivated_at = null`, updates `plugin_data`
4. **ACTIVE → ACTIVE**: Update sets `plugin_data`, `updated_at = NOW()` (upsert behavior)

---

## Validation Rules

### PluginConfig Validation

| Field | Rule | Error Message |
|-------|------|---------------|
| `plugin_id` | Not blank, 1-64 chars, alphanumeric + hyphen | "Plugin ID must be 1-64 alphanumeric characters" |
| `client_id` | Not blank, 1-64 chars | "Client ID is required" |
| `display_name` | Not blank, 2-100 chars | "Display name must be 2-100 characters" |

### AccountPlugin Validation

| Field | Rule | Error Message |
|-------|------|---------------|
| `account_id` | Not null, valid UUID | "Account ID is required" |
| `plugin_id` | Not blank, must exist in plugin_configs | "Plugin not found: {pluginId}" |
| `plugin_data` | Must validate against plugin's JSON Schema | "Plugin data validation failed: {details}" |

### Bit BI Plugin Data Schema

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["tenantId"],
  "properties": {
    "tenantId": {
      "type": "string",
      "minLength": 1,
      "maxLength": 64,
      "pattern": "^[a-zA-Z0-9-_]+$",
      "description": "Bit BI tenant identifier"
    }
  },
  "additionalProperties": false
}
```

---

## Database Migration Summary

### V8__create_plugin_tables.sql

Creates `plugin_configs` and `account_plugins` tables with:
- Primary keys and foreign keys
- Unique constraints
- GIN indexes for JSONB columns
- Default seed data for Bit BI plugin config

### V9__create_plugin_audit_logs_partitioned.sql

Creates partitioned `plugin_audit_logs` table with:
- Monthly partitions (created dynamically)
- Indexes per partition
- Check constraints for valid action types

---

## Integration Points

### Existing Entities Modified

1. **BatchCompletedEvent** (extend):
   - Add `accountId` field to enable routing events to plugins
   - Backward compatible - accountId nullable for existing events

### New Domain Events

1. **PluginActivatedEvent**: Published when plugin is activated for an account
2. **PluginDeactivatedEvent**: Published when plugin is deactivated
3. **PluginEventDispatchedEvent**: Published after successful event dispatch to plugin

---

## Performance Considerations

1. **GIN index on plugin_data**: Enables efficient queries on JSONB content
2. **Partial index on is_active**: Only indexes active plugins for event dispatch queries
3. **Partitioned audit logs**: Efficient time-range queries, easy retention management
4. **Schema caching**: JSON schemas cached in memory after first load (see research.md)
