# Data Model: BitBi Plugin Reinit Option

**Feature**: 015-plugin-reinit
**Date**: 2026-01-04

## Entity Changes

### No New Entities Required

This feature extends existing entities and adds new repository methods. No database migrations needed.

---

## Modified Entities

### 1. PluginActionType (Enum Extension)

**Location**: `src/main/java/com/bitbi/dfm/plugin/domain/PluginActionType.java`

**Change**: Add new enum value for reinit operations

| Value | Description |
|-------|-------------|
| `REINIT` | Plugin SQL state reinitialized (history cleared + regenerated from latest batch) |

**Usage**: Recorded in `plugin_audit_logs.action_type` column

---

## Repository Changes

### 1. BatchRepository

**Location**: `src/main/java/com/bitbi/dfm/batch/domain/BatchRepository.java`

**New Method**:

```java
/**
 * Finds the most recent completed batch for an account across all sites.
 * Used by plugin initialization to find the batch to generate SQL from.
 *
 * @param accountId The account ID
 * @return Optional containing the most recent completed batch, or empty if none
 */
Optional<Batch> findLatestCompletedByAccountId(UUID accountId);
```

**JPA Implementation** (`JpaBatchRepository.java`):

```java
@Query("""
    SELECT b FROM Batch b
    WHERE b.accountId = :accountId
    AND b.status = com.bitbi.dfm.batch.domain.BatchStatus.COMPLETED
    ORDER BY b.completedAt DESC
    LIMIT 1
    """)
Optional<Batch> findLatestCompletedByAccountId(@Param("accountId") UUID accountId);
```

**Index Consideration**: Existing index on `(account_id, status)` should be sufficient. If slow, add index on `(account_id, status, completed_at DESC)`.

---

## Existing Entities (No Changes)

### AccountPlugin

Used to:
- Check if plugin is active (`isActive()`) before allowing reinit
- Get `accountId` for batch lookup
- Get `id` (accountPluginId) for SQL generation

### PluginSqlGeneration

Affected by reinit operation:
- All records for the account-plugin are deleted during reinit
- New records created by SQL generation after reinit

### PluginAuditLog

Receives new entries during reinit:
- `REINIT` action type with metadata about operation

### Batch

Queried to find latest completed batch:
- Status must be `COMPLETED`
- Ordered by `completedAt DESC`

---

## Data Flow

### Activation Flow (Modified)

```
User activates plugin
    │
    ▼
PluginActivationService.activate()
    │
    ├── Creates/updates AccountPlugin
    │
    ├── Calls plugin.onActivate()
    │       │
    │       ▼
    │   BitBiPlugin.onActivate()
    │       │
    │       └── Returns API key (existing)
    │
    ▼
If NEW activation or REACTIVATION:
    │
    ├── Find latest completed batch (NEW)
    │       │
    │       ▼
    │   batchRepository.findLatestCompletedByAccountId()
    │
    └── Trigger async SQL generation (NEW)
            │
            ▼
        sqlGenerationService.generateSqlForBatch()
```

### Reinit Flow (New)

```
User calls POST /api/v1/account/plugins/{pluginId}/reinit
    │
    ▼
AccountPluginsController.reinitPlugin()
    │
    ▼
pluginHistoryService.reinit()
    │
    ├── Validate plugin is active
    │
    ├── Log REINIT audit entry
    │
    ├── Delete S3 files (best-effort)
    │
    ├── Delete PluginSqlGeneration records
    │
    ├── Find latest completed batch
    │
    └── Trigger async SQL generation
            │
            ▼
        sqlGenerationService.generateSqlForBatch()
```

---

## Audit Log Metadata

### REINIT Action Metadata

```json
{
  "deletedGenerations": 10,
  "deletedS3Files": 10,
  "s3DeleteFailures": 0,
  "sqlGenerationTriggered": true,
  "batchId": "uuid-of-latest-batch",
  "success": true
}
```

### REINIT Failure Metadata

```json
{
  "deletedGenerations": 0,
  "errorMessage": "Plugin is not active",
  "success": false
}
```
