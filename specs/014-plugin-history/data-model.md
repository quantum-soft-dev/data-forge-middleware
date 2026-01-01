# Data Model: Plugin History Management

**Date**: 2026-01-01
**Feature**: 014-plugin-history

## Entity Changes

### PluginSqlGeneration (Modified)

Existing entity with new fields for regeneration tracking.

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| id | UUID | No | Primary key |
| accountPluginId | Long | No | FK to account_plugins |
| siteId | UUID | No | FK to sites |
| sourceBatchId | UUID | No | FK to batches (unique constraint) |
| comparisonBatchId | UUID | Yes | FK to batches (null for first batch) |
| s3Key | String | No | S3 path to SQL file |
| fileSizeBytes | Long | No | File size in bytes |
| statementCount | Integer | No | Total SQL statements |
| insertCount | Integer | No | INSERT count |
| updateCount | Integer | No | UPDATE count |
| deleteCount | Integer | No | DELETE count |
| filesProcessed | Integer | No | CSV files processed |
| generationDurationMs | Long | No | Generation time in ms |
| createdAt | LocalDateTime | No | Creation timestamp |
| **superseded** | Boolean | No | **NEW**: True if replaced by regeneration (default: false) |
| **supersededBy** | UUID | Yes | **NEW**: FK to id of replacement generation |

**Validation Rules**:
- `superseded` defaults to `false`
- `supersededBy` must reference valid PluginSqlGeneration if set
- Cannot set `supersededBy` if `superseded` is `false`

**State Transitions**:
```
ACTIVE (superseded=false)
    → SUPERSEDED (superseded=true, supersededBy=newId)
       [via regeneration]
```

---

### PluginActionType (Extended Enum)

Add new action types for history management operations.

| Value | Description | Metadata Fields |
|-------|-------------|-----------------|
| PLUGIN_HISTORY_CLEARED | Admin cleared history | deletedCount, deletedFilesCount, totalBytes |
| SQL_REGENERATION_STARTED | Regeneration initiated | batchId, originalGenerationId |
| SQL_REGENERATION_COMPLETED | Regeneration succeeded | batchId, originalGenerationId, newGenerationId, stats |
| SQL_REGENERATION_FAILED | Regeneration failed | batchId, originalGenerationId, errorMessage |

---

## Database Migration (V14)

```sql
-- V14__add_plugin_history_fields.sql

-- Add superseded tracking fields to plugin_sql_generations
ALTER TABLE plugin_sql_generations
ADD COLUMN superseded BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE plugin_sql_generations
ADD COLUMN superseded_by UUID;

-- Self-referential foreign key
ALTER TABLE plugin_sql_generations
ADD CONSTRAINT fk_superseded_by
FOREIGN KEY (superseded_by)
REFERENCES plugin_sql_generations(id)
ON DELETE SET NULL;

-- Index for finding active (non-superseded) generations
CREATE INDEX idx_plugin_sql_generations_active
ON plugin_sql_generations(account_plugin_id, superseded)
WHERE superseded = FALSE;

-- Add new action types to enum (if using PostgreSQL enum)
-- Note: PluginActionType is Java enum, stored as VARCHAR in DB
-- No migration needed for enum values
```

---

## Repository Queries

### PluginSqlGenerationRepository (New Methods)

```java
// Find all generations for account-plugin (active only by default)
Page<PluginSqlGeneration> findByAccountPluginIdAndSuperseded(
    Long accountPluginId,
    boolean superseded,
    Pageable pageable
);

// Find all generations for account-plugin (including superseded)
@Query("""
    SELECT g FROM PluginSqlGeneration g
    WHERE g.accountPluginId = :accountPluginId
    ORDER BY g.createdAt DESC
    """)
Page<PluginSqlGeneration> findByAccountPluginId(
    @Param("accountPluginId") Long accountPluginId,
    Pageable pageable
);

// Count generations for summary before clear
@Query("""
    SELECT COUNT(g), COALESCE(SUM(g.fileSizeBytes), 0)
    FROM PluginSqlGeneration g
    WHERE g.accountPluginId = :accountPluginId
    """)
Object[] countAndSumByAccountPluginId(@Param("accountPluginId") Long accountPluginId);

// Get all S3 keys for bulk deletion
@Query("""
    SELECT g.s3Key FROM PluginSqlGeneration g
    WHERE g.accountPluginId = :accountPluginId
    """)
List<String> findS3KeysByAccountPluginId(@Param("accountPluginId") Long accountPluginId);

// Delete all for account-plugin (cascaded from account_plugins if using DB cascade)
void deleteByAccountPluginId(Long accountPluginId);

// Find by source batch for regeneration
Optional<PluginSqlGeneration> findBySourceBatchIdAndSuperseded(
    UUID sourceBatchId,
    boolean superseded
);
```

---

## DTOs

### SqlGenerationSummaryDto (List Item)

```java
public record SqlGenerationSummaryDto(
    UUID id,
    UUID sourceBatchId,
    UUID comparisonBatchId,      // nullable
    UUID siteId,
    String siteDomain,
    LocalDateTime createdAt,
    int statementCount,
    int insertCount,
    int updateCount,
    int deleteCount,
    long fileSizeBytes,
    long generationDurationMs,
    boolean isInitialLoad,       // computed: comparisonBatchId == null
    boolean superseded,
    UUID supersededBy            // nullable
) {}
```

### SqlContentPageDto (Paginated SQL Content)

```java
public record SqlContentPageDto(
    UUID generationId,
    int page,
    int pageSize,
    int totalPages,
    int totalStatements,
    List<String> statements,     // SQL statements for current page
    boolean hasNext,
    boolean hasPrevious
) {}
```

### HistoryClearSummaryDto (Pre-deletion Summary)

```java
public record HistoryClearSummaryDto(
    UUID accountId,
    String pluginId,
    long generationCount,
    long totalFileSizeBytes,
    boolean pluginWillBeDeactivated,
    boolean hasActiveBatches       // warning if true
) {}
```

### HistoryClearResultDto (Post-deletion Result)

```java
public record HistoryClearResultDto(
    long deletedGenerations,
    long deletedFilesCount,
    long deletedTotalBytes,
    List<String> failedS3Keys,    // empty if all succeeded
    boolean pluginDeactivated,
    LocalDateTime clearedAt
) {}
```

### RegenerateResultDto (Regeneration Result)

```java
public record RegenerateResultDto(
    UUID originalGenerationId,
    UUID newGenerationId,
    int statementCount,
    int insertCount,
    int updateCount,
    int deleteCount,
    long generationDurationMs,
    LocalDateTime regeneratedAt
) {}
```

---

## Relationships Diagram

```
┌─────────────────────┐
│   account_plugins   │
├─────────────────────┤
│ id (PK)             │◄───────────────┐
│ account_id          │                │
│ plugin_id           │                │
│ is_active           │                │
└─────────────────────┘                │
                                       │ FK: account_plugin_id
                                       │
┌─────────────────────────────────────────────────────┐
│              plugin_sql_generations                 │
├─────────────────────────────────────────────────────┤
│ id (PK, UUID)                                       │
│ account_plugin_id (FK) ─────────────────────────────┘
│ site_id (FK → sites)                                │
│ source_batch_id (FK → batches, UNIQUE)              │
│ comparison_batch_id (FK → batches, nullable)        │
│ s3_key                                              │
│ file_size_bytes, statement_count, ...               │
│ superseded (BOOLEAN, default FALSE)          [NEW]  │
│ superseded_by (FK → self, nullable)          [NEW]  │◄─┐
│ created_at                                          │  │
└─────────────────────────────────────────────────────┘  │
         │                                               │
         └───────────────────────────────────────────────┘
                    (self-referential for regeneration chain)
```
