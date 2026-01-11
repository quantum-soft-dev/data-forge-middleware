# Data Model: Global Error Handling

**Feature**: 016-global-error-handling
**Date**: 2026-01-11

## Entity Changes

### ErrorLog (Extended)

**Table**: `error_logs` (partitioned by `occurred_at`)

#### Existing Fields (unchanged)

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| `id` | UUID | NO | Primary key (part of composite PK) |
| `site_id` | UUID | NO | FK → sites.id |
| `batch_id` | UUID | YES | FK → batches.id (NULL for global errors) |
| `type` | VARCHAR(100) | NO | Error category |
| `title` | VARCHAR(500) | NO | Brief summary |
| `message` | TEXT | NO | Detailed description |
| `stack_trace` | TEXT | YES | Exception stack trace |
| `client_version` | VARCHAR(50) | YES | Client app version |
| `metadata` | JSONB | YES | Flexible key-value data |
| `occurred_at` | TIMESTAMP | NO | Error occurrence time (partition key) |
| `created_at` | TIMESTAMP | NO | Record insertion time |

#### New Fields

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `severity` | VARCHAR(20) | NO | 'ERROR' | Error severity level |
| `is_read` | BOOLEAN | NO | false | Read status for account |

#### Composite Primary Key

```
PRIMARY KEY (id, occurred_at)
```

#### Indexes (Existing + New)

| Index | Columns | Type | New? |
|-------|---------|------|------|
| `idx_error_logs_site_id` | site_id | B-tree | No |
| `idx_error_logs_batch_id` | batch_id | B-tree | No |
| `idx_error_logs_type` | type | B-tree | No |
| `idx_error_logs_occurred_at` | occurred_at | B-tree | No |
| `idx_error_logs_metadata_gin` | metadata | GIN | No |
| `idx_error_logs_global_unread` | (site_id) WHERE batch_id IS NULL AND is_read = false | Partial B-tree | **YES** |

### ErrorSeverity (New Enum)

**Java Enum**: `com.bitbi.dfm.error.domain.ErrorSeverity`

| Value | Order | Color (UI) | Use Case |
|-------|-------|------------|----------|
| CRITICAL | 1 | Red (#EF4444) | System failure, data loss |
| ERROR | 2 | Orange (#F97316) | Operation failed |
| WARNING | 3 | Yellow (#EAB308) | Degraded, recoverable |
| INFO | 4 | Blue (#3B82F6) | Informational |

**Storage**: `@Enumerated(EnumType.STRING)` → VARCHAR in DB

## Migration Script

**File**: `V17__add_severity_and_is_read_to_error_logs.sql`

```sql
-- Add severity column with default 'ERROR'
ALTER TABLE error_logs
ADD COLUMN severity VARCHAR(20) NOT NULL DEFAULT 'ERROR';

-- Add is_read column with default true (existing errors considered read)
ALTER TABLE error_logs
ADD COLUMN is_read BOOLEAN NOT NULL DEFAULT true;

-- Change default for new rows: is_read = false
ALTER TABLE error_logs
ALTER COLUMN is_read SET DEFAULT false;

-- Partial index for efficient unread global errors query
CREATE INDEX idx_error_logs_global_unread
ON error_logs (site_id)
WHERE batch_id IS NULL AND is_read = false;

-- Add check constraint for severity values
ALTER TABLE error_logs
ADD CONSTRAINT chk_error_logs_severity
CHECK (severity IN ('CRITICAL', 'ERROR', 'WARNING', 'INFO'));
```

## Relationships

```
Account (1) ──────< Site (N) ──────< ErrorLog (N)
                                         │
                                         │ batch_id (nullable)
                                         ▼
                                    Batch (0..1)
```

**Global Errors**: `batch_id IS NULL` — не связаны с batch
**Batch Errors**: `batch_id IS NOT NULL` — связаны с конкретным batch

## Query Patterns

### Get Global Errors for Account (Paginated)

```sql
SELECT e.* FROM error_logs e
JOIN sites s ON e.site_id = s.id
WHERE s.account_id = :accountId
  AND e.batch_id IS NULL
ORDER BY e.occurred_at DESC
LIMIT :size OFFSET :offset
```

### Count Unread Global Errors

```sql
SELECT COUNT(*) FROM error_logs e
JOIN sites s ON e.site_id = s.id
WHERE s.account_id = :accountId
  AND e.batch_id IS NULL
  AND e.is_read = false
```

### Mark Errors as Read (Bulk)

```sql
UPDATE error_logs
SET is_read = true
WHERE id = ANY(:ids)
```

### Mark All as Read for Account

```sql
UPDATE error_logs e
SET is_read = true
FROM sites s
WHERE e.site_id = s.id
  AND s.account_id = :accountId
  AND e.batch_id IS NULL
  AND e.is_read = false
```

## Validation Rules

| Field | Rule | Error Message |
|-------|------|---------------|
| severity | Must be valid enum value | "Invalid severity. Allowed: CRITICAL, ERROR, WARNING, INFO" |
| type | Max 100 chars, not blank | "Type is required and must be <= 100 characters" |
| message | Max 10,000 chars, not blank | "Message is required and must be <= 10,000 characters" |
| metadata | Max 20 entries, 10KB total | "Metadata exceeds limits" |

## State Transitions

```
New Error Created
       │
       ▼
   is_read = false
       │
       ├──── User clicks "Mark as Read"
       │              │
       │              ▼
       │         is_read = true
       │              │
       │              ├──── [END - No reverse transition]
       │
       ├──── User clicks "Mark All as Read"
       │              │
       │              ▼
       │         is_read = true (bulk)
       │
       └──── [Polling continues, error remains unread until action]
```

**Note**: Нет обратного перехода (mark as unread). Статус `is_read` может только переключиться с `false` на `true`.

## Data Retention

- **Policy**: 24 months (inherited from error_logs)
- **Enforcement**: PartitionScheduler drops old monthly partitions
- **Impact**: Global errors deleted together with batch errors by partition drop
