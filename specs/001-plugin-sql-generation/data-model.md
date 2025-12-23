# Data Model: Plugin SQL Generation Extension

**Feature Branch**: `001-plugin-sql-generation`
**Date**: 2025-12-22

## Entity Relationship Diagram

```
┌─────────────────┐       ┌──────────────────────┐       ┌─────────────────┐
│    accounts     │       │   account_plugins    │       │  plugin_configs │
├─────────────────┤       ├──────────────────────┤       ├─────────────────┤
│ id (PK)         │◄──────│ account_id (FK)      │       │ plugin_id (PK)  │
│ email           │       │ plugin_id (FK)       │───────│ client_id       │
│ name            │       │ plugin_data (JSONB)  │       │ display_name    │
│ is_active       │       │   └── tenantId       │       │ is_enabled      │
└─────────────────┘       │   └── apiKey (NEW)   │       └─────────────────┘
         │                │ is_active            │
         │                │ activated_at         │
         ▼                └──────────────────────┘
┌─────────────────┐                │
│     sites       │                │
├─────────────────┤                │
│ id (PK)         │                │
│ account_id (FK) │                │
│ domain          │                ▼
│ display_name    │       ┌──────────────────────────┐
└─────────────────┘       │  plugin_sql_generations  │ (NEW)
         │                ├──────────────────────────┤
         │                │ id (PK)                  │
         ▼                │ account_plugin_id (FK)   │
┌─────────────────┐       │ site_id (FK)             │
│    batches      │       │ source_batch_id (FK)     │
├─────────────────┤       │ comparison_batch_id (FK) │ NULL for first batch
│ id (PK)         │◄──────│ s3_key                   │
│ site_id (FK)    │       │ file_size_bytes          │
│ account_id      │       │ statement_count          │
│ status          │       │ insert_count             │
│ started_at      │       │ update_count             │
│ completed_at    │       │ delete_count             │
└─────────────────┘       │ files_processed          │
         │                │ generation_duration_ms   │
         │                │ created_at               │
         ▼                └──────────────────────────┘
┌─────────────────┐
│ uploaded_files  │
├─────────────────┤
│ id (PK)         │
│ batch_id (FK)   │
│ original_name   │
│ s3_key          │
│ file_size       │
└─────────────────┘
```

## New Entity: PluginSqlGeneration

### Purpose
Tracks each SQL file generation event for audit, retrieval, and statistics.

### Table Definition

```sql
-- V11__create_plugin_sql_generations_table.sql

CREATE TABLE plugin_sql_generations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Foreign keys
    account_plugin_id BIGINT NOT NULL,
    site_id UUID NOT NULL,
    source_batch_id UUID NOT NULL,
    comparison_batch_id UUID,  -- NULL for first batch

    -- S3 storage
    s3_key VARCHAR(1000) NOT NULL,
    file_size_bytes BIGINT NOT NULL,

    -- Statistics
    statement_count INTEGER NOT NULL DEFAULT 0,
    insert_count INTEGER NOT NULL DEFAULT 0,
    update_count INTEGER NOT NULL DEFAULT 0,
    delete_count INTEGER NOT NULL DEFAULT 0,
    files_processed INTEGER NOT NULL DEFAULT 0,

    -- Performance tracking
    generation_duration_ms BIGINT NOT NULL DEFAULT 0,

    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),

    -- Constraints
    CONSTRAINT fk_sql_gen_account_plugin
        FOREIGN KEY (account_plugin_id)
        REFERENCES account_plugins(id) ON DELETE CASCADE,
    CONSTRAINT fk_sql_gen_site
        FOREIGN KEY (site_id)
        REFERENCES sites(id) ON DELETE CASCADE,
    CONSTRAINT fk_sql_gen_source_batch
        FOREIGN KEY (source_batch_id)
        REFERENCES batches(id) ON DELETE CASCADE,
    CONSTRAINT fk_sql_gen_comparison_batch
        FOREIGN KEY (comparison_batch_id)
        REFERENCES batches(id) ON DELETE SET NULL,

    -- Ensure unique generation per source batch
    CONSTRAINT uk_sql_gen_source_batch UNIQUE (source_batch_id)
);

-- Indexes for common queries
CREATE INDEX idx_sql_gen_site_created ON plugin_sql_generations(site_id, created_at DESC);
CREATE INDEX idx_sql_gen_account_plugin ON plugin_sql_generations(account_plugin_id);

COMMENT ON TABLE plugin_sql_generations IS 'Tracks SQL file generation events for Bit BI plugin';
COMMENT ON COLUMN plugin_sql_generations.comparison_batch_id IS 'NULL for first batch (all INSERTs)';
COMMENT ON COLUMN plugin_sql_generations.s3_key IS 'Full S3 key path: plugins/bit-bi/{accountId}/{siteName}/{timestamp}.sql';
```

### JPA Entity

```java
@Entity
@Table(name = "plugin_sql_generations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PluginSqlGeneration {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "account_plugin_id", nullable = false)
    private Long accountPluginId;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "source_batch_id", nullable = false)
    private UUID sourceBatchId;

    @Column(name = "comparison_batch_id")
    private UUID comparisonBatchId;  // nullable for first batch

    @Column(name = "s3_key", nullable = false, length = 1000)
    private String s3Key;

    @Column(name = "file_size_bytes", nullable = false)
    private Long fileSizeBytes;

    @Column(name = "statement_count", nullable = false)
    private Integer statementCount;

    @Column(name = "insert_count", nullable = false)
    private Integer insertCount;

    @Column(name = "update_count", nullable = false)
    private Integer updateCount;

    @Column(name = "delete_count", nullable = false)
    private Integer deleteCount;

    @Column(name = "files_processed", nullable = false)
    private Integer filesProcessed;

    @Column(name = "generation_duration_ms", nullable = false)
    private Long generationDurationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Factory method
    public static PluginSqlGeneration create(
            Long accountPluginId,
            UUID siteId,
            UUID sourceBatchId,
            UUID comparisonBatchId,
            String s3Key,
            long fileSizeBytes,
            SqlGenerationStats stats,
            long durationMs
    ) {
        PluginSqlGeneration entity = new PluginSqlGeneration();
        entity.id = UUID.randomUUID();
        entity.accountPluginId = accountPluginId;
        entity.siteId = siteId;
        entity.sourceBatchId = sourceBatchId;
        entity.comparisonBatchId = comparisonBatchId;
        entity.s3Key = s3Key;
        entity.fileSizeBytes = fileSizeBytes;
        entity.statementCount = stats.total();
        entity.insertCount = stats.inserts();
        entity.updateCount = stats.updates();
        entity.deleteCount = stats.deletes();
        entity.filesProcessed = stats.filesProcessed();
        entity.generationDurationMs = durationMs;
        entity.createdAt = LocalDateTime.now();
        return entity;
    }

    public boolean isFirstBatch() {
        return comparisonBatchId == null;
    }
}
```

---

## Extended Schema: account_plugins.plugin_data

### Current Schema (existing)
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
      "pattern": "^[a-zA-Z0-9-_]+$"
    }
  },
  "additionalProperties": false
}
```

### Extended Schema (this feature)
```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["tenantId", "apiKey"],
  "properties": {
    "tenantId": {
      "type": "string",
      "minLength": 1,
      "maxLength": 64,
      "pattern": "^[a-zA-Z0-9-_]+$",
      "description": "Bit BI tenant identifier"
    },
    "apiKey": {
      "type": "string",
      "minLength": 36,
      "maxLength": 36,
      "pattern": "^plk_[a-zA-Z0-9]{32}$",
      "description": "Plugin API Key for authentication"
    }
  },
  "additionalProperties": false
}
```

### Sample Data
```json
{
  "tenantId": "bit-bi-tenant-acme",
  "apiKey": "plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6"
}
```

---

## Value Objects

### SqlGenerationStats (record)

```java
public record SqlGenerationStats(
    int inserts,
    int updates,
    int deletes,
    int filesProcessed
) {
    public int total() {
        return inserts + updates + deletes;
    }

    public static SqlGenerationStats empty() {
        return new SqlGenerationStats(0, 0, 0, 0);
    }

    public SqlGenerationStats merge(SqlGenerationStats other) {
        return new SqlGenerationStats(
            this.inserts + other.inserts,
            this.updates + other.updates,
            this.deletes + other.deletes,
            this.filesProcessed + other.filesProcessed
        );
    }
}
```

### CsvRowDiff (record)

```java
public record CsvRowDiff(
    DiffType type,
    int lineNumber,
    Map<String, String> values,
    Map<String, String> changedColumns  // only for MODIFIED: old values
) {
    public enum DiffType {
        ADDED,
        MODIFIED,
        DELETED
    }

    public static CsvRowDiff added(int lineNumber, Map<String, String> values) {
        return new CsvRowDiff(DiffType.ADDED, lineNumber, values, Map.of());
    }

    public static CsvRowDiff deleted(int lineNumber, Map<String, String> values) {
        return new CsvRowDiff(DiffType.DELETED, lineNumber, values, Map.of());
    }

    public static CsvRowDiff modified(int lineNumber, Map<String, String> newValues, Map<String, String> changedColumns) {
        return new CsvRowDiff(DiffType.MODIFIED, lineNumber, newValues, changedColumns);
    }
}
```

### DbfColumnType (enum)

```java
public enum DbfColumnType {
    CHARACTER("C", true),    // Empty = NULL
    NUMERIC("N", true),      // Empty = NULL
    LOGICAL("L", true),      // Empty = NULL
    DATE("D", true),         // Empty = NULL
    FLOAT("F", true),        // Empty = NULL
    DATETIME("T", true),     // Empty = NULL
    INTEGER("I", false),     // Empty = 0
    CURRENCY("Y", false);    // Empty = 0

    private final String code;
    private final boolean emptyIsNull;

    DbfColumnType(String code, boolean emptyIsNull) {
        this.code = code;
        this.emptyIsNull = emptyIsNull;
    }

    public boolean isEmptyNull() {
        return emptyIsNull;
    }

    public static DbfColumnType fromCode(String code) {
        for (DbfColumnType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return CHARACTER;  // Default to CHARACTER (empty = NULL)
    }
}
```

### PluginApiKey (value object)

```java
public record PluginApiKey(String value) {
    private static final String PREFIX = "plk_";
    private static final int KEY_LENGTH = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    public PluginApiKey {
        Objects.requireNonNull(value, "API key value cannot be null");
        if (!value.matches("^plk_[a-zA-Z0-9]{32}$")) {
            throw new IllegalArgumentException("Invalid API key format");
        }
    }

    public static PluginApiKey generate() {
        StringBuilder key = new StringBuilder(PREFIX);
        for (int i = 0; i < KEY_LENGTH; i++) {
            key.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return new PluginApiKey(key.toString());
    }

    @Override
    public String toString() {
        // Mask for security in logs
        return PREFIX + "****" + value.substring(value.length() - 4);
    }
}
```

---

## Repository Interface

```java
public interface PluginSqlGenerationRepository {

    PluginSqlGeneration save(PluginSqlGeneration generation);

    Optional<PluginSqlGeneration> findById(UUID id);

    /**
     * Find all SQL generations for a site after a given date.
     * Used by the Plugin API to retrieve SQL changes.
     */
    List<PluginSqlGeneration> findBySiteIdAndCreatedAtAfter(
        UUID siteId,
        LocalDateTime since
    );

    /**
     * Find generation by source batch (unique constraint).
     */
    Optional<PluginSqlGeneration> findBySourceBatchId(UUID sourceBatchId);

    /**
     * Check if generation already exists for a batch.
     */
    boolean existsBySourceBatchId(UUID sourceBatchId);
}
```

---

## Database Queries

### Find SQL generations by site and date (Plugin API)

```sql
SELECT * FROM plugin_sql_generations
WHERE site_id = :siteId
  AND created_at > :since
ORDER BY created_at ASC;
```

### Validate API Key and get account

```sql
SELECT ap.account_id
FROM account_plugins ap
WHERE ap.plugin_id = 'bit-bi'
  AND ap.is_active = true
  AND ap.plugin_data @> :apiKeyJson::jsonb;

-- Example: :apiKeyJson = '{"apiKey": "plk_xxx..."}'
```

### Find previous batch for site

```sql
SELECT * FROM batches
WHERE site_id = :siteId
  AND status IN ('COMPLETED', 'COMPLETED_WITH_WARNINGS')
  AND id != :currentBatchId
ORDER BY completed_at DESC
LIMIT 1;
```

---

## Validation Rules

| Field | Rule | Error Message |
|-------|------|---------------|
| apiKey | Required, format `plk_` + 32 alphanumeric | Invalid API key format |
| siteId | Must belong to API Key's account | Site not found or access denied |
| since | Valid ISO8601 timestamp | Invalid date format |
| tenantId | Required for plugin activation | Tenant ID is required |

---

## State Transitions

### SQL Generation Lifecycle

```
BatchCompletedEvent
       │
       ▼
[Check Plugin Active] ──No──► (no action)
       │ Yes
       ▼
[Find Previous Batch]
       │
       ├── None found ──► [Generate INSERTs for all rows]
       │
       └── Found ──► [Compare files between batches]
                          │
                          ▼
                    [Generate SQL]
                          │
                          ▼
                    [Upload to S3]
                          │
                          ▼
               [Save PluginSqlGeneration]
                          │
                          ▼
                       COMPLETE
```

### No SQL Generation Cases

- Plugin not activated for account → Skip silently
- Empty diff (identical files) → Do NOT create PluginSqlGeneration record
- Binary files in batch → Skip (log warning)
- Batch with no CSV files → Do NOT create record
