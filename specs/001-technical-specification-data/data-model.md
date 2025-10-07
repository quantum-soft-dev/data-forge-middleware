# Phase 1: Data Model Design

**Date**: 2025-10-06
**Status**: Complete

## Domain Model Overview

The Data Forge Middleware domain model consists of 5 core entities organized into 3 aggregate roots following DDD principles. Each aggregate enforces invariants and encapsulates business logic.

### Aggregate Roots
1. **Account** - User identity and site ownership
2. **Site** - Data source with authentication credentials
3. **Batch** - Upload session lifecycle management

### Entities
- **UploadedFile** - Part of Batch aggregate
- **ErrorLog** - Independent entity with optional Batch association

---

## Entity Specifications

### 1. Account (Aggregate Root)

**Purpose**: Represents a system user who manages multiple data sources (sites). Enforces email uniqueness and soft-delete behavior.

**Attributes**:
| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | Primary Key, Non-null | Unique identifier (generated) |
| `email` | String (255) | Unique, Non-null, Email format | User's email address (login identifier) |
| `name` | String (255) | Non-null | User's display name |
| `isActive` | Boolean | Non-null, Default: true | Soft delete flag |
| `createdAt` | LocalDateTime | Non-null, Default: now() | Record creation timestamp |
| `updatedAt` | LocalDateTime | Non-null, Auto-update | Last modification timestamp |

**Relationships**:
- **One-to-Many** with Site: `sites` collection (lazy-loaded)

**Business Rules** (Invariants):
1. Email must be unique across all accounts (enforced by unique constraint + application validation)
2. When `isActive = false`, all associated sites must also be marked `isActive = false` (cascading soft delete via domain event)
3. Deactivated accounts (`isActive = false`) cannot create new sites or batches (checked in authorization layer)
4. Existing active batches continue processing when account is deactivated (FR-082, FR-083)

**Database Schema** (Flyway migration `V1__create_accounts_table.sql`):
```sql
CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_accounts_email ON accounts(email);
CREATE INDEX idx_accounts_is_active ON accounts(is_active);
```

**Domain Events**:
- `AccountDeactivatedEvent(accountId)` - Triggers cascade deactivation of all sites

---

### 2. Site (Aggregate Root)

**Purpose**: Represents a physical or logical data source (branch, store, warehouse) that uploads files. Owns authentication credentials (domain + clientSecret).

**Attributes**:
| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | Primary Key, Non-null | Unique identifier (generated) |
| `accountId` | UUID | Foreign Key (accounts.id), Non-null | Owner account reference |
| `domain` | String (255) | Unique, Non-null | Domain name (used in Basic Auth and S3 path) |
| `clientSecret` | String (255) | Non-null | Authentication secret (UUID generated on creation) |
| `displayName` | String (255) | Non-null | Human-readable site name |
| `isActive` | Boolean | Non-null, Default: true | Soft delete flag |
| `createdAt` | LocalDateTime | Non-null, Default: now() | Record creation timestamp |
| `updatedAt` | LocalDateTime | Non-null, Auto-update | Last modification timestamp |

**Relationships**:
- **Many-to-One** with Account: `account` (eager or lazy with EntityGraph)
- **One-to-Many** with Batch: `batches` collection (lazy-loaded)

**Business Rules** (Invariants):
1. Domain must be globally unique (enforced by unique constraint + application validation)
2. `clientSecret` is auto-generated as UUID on site creation (never user-provided)
3. A site belongs to exactly one account (immutable relationship after creation)
4. Deactivated sites (`isActive = false`) cannot authenticate or create new batches
5. When parent account is deactivated, all sites must be deactivated (handled by `AccountDeactivatedEvent`)

**Database Schema** (Flyway migration `V2__create_sites_table.sql`):
```sql
CREATE TABLE sites (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    domain VARCHAR(255) NOT NULL UNIQUE,
    client_secret VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sites_account_id ON sites(account_id);
CREATE INDEX idx_sites_domain ON sites(domain);
CREATE INDEX idx_sites_is_active ON sites(is_active);
```

**Value Objects**:
- `SiteCredentials(domain, clientSecret)` - Encapsulates authentication pair

---

### 3. Batch (Aggregate Root)

**Purpose**: Represents a single file upload session with lifecycle state management. Enforces "one active batch per site" constraint and timeout expiration logic.

**Attributes**:
| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | Primary Key, Non-null | Unique batch identifier (batchId) |
| `siteId` | UUID | Foreign Key (sites.id), Non-null | Owning site reference |
| `status` | BatchStatus (Enum) | Non-null, Default: IN_PROGRESS | Lifecycle state |
| `s3Path` | String (500) | Non-null | Full S3 directory path for uploaded files |
| `uploadedFilesCount` | Integer | Non-null, Default: 0, Min: 0 | Counter for uploaded files |
| `totalSize` | Long | Non-null, Default: 0, Min: 0 | Total bytes uploaded |
| `hasErrors` | Boolean | Non-null, Default: false | Flag set when errors are logged |
| `startedAt` | LocalDateTime | Non-null | Batch creation time (used for timeout calculation) |
| `completedAt` | LocalDateTime | Nullable | Completion timestamp (set when status changes from IN_PROGRESS) |
| `createdAt` | LocalDateTime | Non-null, Default: now() | Record creation timestamp |

**Enum: BatchStatus**
- `IN_PROGRESS` - Active upload session (only one per site allowed)
- `COMPLETED` - Successfully finished by client
- `NOT_COMPLETED` - Expired via timeout without explicit completion
- `FAILED` - Marked as failed by client due to critical error
- `CANCELLED` - Cancelled by client

**State Transitions** (enforced by domain logic):
```
IN_PROGRESS → COMPLETED   (client calls /complete)
IN_PROGRESS → FAILED      (client calls /fail)
IN_PROGRESS → CANCELLED   (client calls /cancel)
IN_PROGRESS → NOT_COMPLETED (timeout scheduler)
```
- No transitions allowed FROM terminal states (COMPLETED, FAILED, CANCELLED, NOT_COMPLETED)
- File uploads only allowed IN `IN_PROGRESS` status

**Relationships**:
- **Many-to-One** with Site: `site` (eager or lazy with EntityGraph)
- **One-to-Many** with UploadedFile: `uploadedFiles` collection (lazy-loaded)
- **One-to-Many** with ErrorLog: `errorLogs` collection (lazy-loaded)

**Business Rules** (Invariants):
1. Only one batch per site can be in `IN_PROGRESS` status (enforced by unique partial index + application check)
2. `s3Path` is immutable and formed as: `{accountId}/{domain}/{YYYY-MM-DD}/{HH-MM}/` on creation
3. `uploadedFilesCount` and `totalSize` are incremented atomically when files are uploaded (optimistic locking or database-level update)
4. When `status` transitions from `IN_PROGRESS`, `completedAt` must be set to current timestamp
5. `hasErrors` is set to `true` when any ErrorLog is associated with this batch (never reset to `false`)
6. Timeout: If `now() - startedAt > configuredTimeout` and `status == IN_PROGRESS`, automatically transition to `NOT_COMPLETED`

**Database Schema** (Flyway migration `V3__create_batches_table.sql`):
```sql
CREATE TABLE batches (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES sites(id),
    status VARCHAR(50) NOT NULL DEFAULT 'IN_PROGRESS',
    s3_path VARCHAR(500) NOT NULL,
    uploaded_files_count INTEGER NOT NULL DEFAULT 0,
    total_size BIGINT NOT NULL DEFAULT 0,
    has_errors BOOLEAN NOT NULL DEFAULT false,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_batch_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'NOT_COMPLETED', 'FAILED', 'CANCELLED'))
);

CREATE INDEX idx_batches_site_id ON batches(site_id);
CREATE INDEX idx_batches_status ON batches(status);
CREATE INDEX idx_batches_started_at ON batches(started_at);
CREATE UNIQUE INDEX idx_batches_active_per_site ON batches(site_id)
    WHERE status = 'IN_PROGRESS';  -- Partial unique index enforces one active batch per site
```

**Domain Services**:
- `BatchTimeoutPolicy` - Calculates timeout expiration and handles state transition to `NOT_COMPLETED`

**Domain Events**:
- `BatchStartedEvent(batchId, siteId, accountId)` - For metrics/logging
- `BatchCompletedEvent(batchId, uploadedFilesCount, totalSize)` - For metrics/logging
- `BatchExpiredEvent(batchId)` - Triggered by timeout scheduler

---

### 4. UploadedFile (Entity within Batch Aggregate)

**Purpose**: Stores metadata for each file uploaded to a batch. Enforces filename uniqueness within the batch and checksum calculation.

**Attributes**:
| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | Primary Key, Non-null | Unique file metadata identifier |
| `batchId` | UUID | Foreign Key (batches.id), Non-null | Parent batch reference |
| `originalFileName` | String (500) | Non-null | Client-provided filename (e.g., "sales.csv.gz") |
| `s3Key` | String (1000) | Non-null, Unique | Full S3 object key ({s3Path}{originalFileName}) |
| `fileSize` | Long | Non-null, Min: 1 | File size in bytes |
| `contentType` | String (100) | Non-null | MIME type (usually "application/gzip") |
| `checksum` | String (64) | Non-null | MD5 hash for integrity verification |
| `uploadedAt` | LocalDateTime | Non-null, Default: now() | Upload completion timestamp |

**Relationships**:
- **Many-to-One** with Batch: `batch` (parent aggregate root)

**Business Rules** (Invariants):
1. `originalFileName` must be unique within a batch (enforced by composite unique constraint: `batchId + originalFileName`)
2. Failed uploads (partial transfers) do not create UploadedFile records (FR-080, FR-081)
3. Retry uploads with same filename after failure are allowed (no duplicate check for failed uploads)
4. `s3Key` is derived from batch's `s3Path` + `originalFileName` (immutable)
5. `checksum` is calculated during upload and stored for later integrity checks (MD5 algorithm)
6. File size must not exceed configured maximum (default 128MB, validated before upload)

**Database Schema** (Flyway migration `V4__create_uploaded_files_table.sql`):
```sql
CREATE TABLE uploaded_files (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES batches(id) ON DELETE CASCADE,
    original_file_name VARCHAR(500) NOT NULL,
    s3_key VARCHAR(1000) NOT NULL UNIQUE,
    file_size BIGINT NOT NULL CHECK (file_size > 0),
    content_type VARCHAR(100) NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_uploaded_files_batch_id ON uploaded_files(batch_id);
CREATE UNIQUE INDEX idx_uploaded_files_batch_filename ON uploaded_files(batch_id, original_file_name);
```

**Value Objects**:
- `FileChecksum(algorithm, value)` - Encapsulates checksum type and hash value
- `S3Key(path)` - Encapsulates S3 object key with validation

---

### 5. ErrorLog (Entity with optional Batch association)

**Purpose**: Records errors reported by client applications. Supports batch-specific and standalone errors. Uses time-based partitioning for scalability.

**Attributes**:
| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | Primary Key (composite with occurredAt), Non-null | Unique error identifier |
| `siteId` | UUID | Foreign Key (sites.id), Non-null | Reporting site reference |
| `batchId` | UUID | Foreign Key (batches.id), Nullable | Associated batch (null for standalone errors) |
| `type` | String (100) | Non-null | Error category (e.g., "FileReadError", "ConfigurationError") |
| `title` | String (500) | Non-null | Brief error summary |
| `message` | Text | Non-null | Detailed error description |
| `stackTrace` | Text | Nullable | Exception stack trace (optional) |
| `clientVersion` | String (50) | Nullable | Client application version |
| `metadata` | JSONB | Nullable | Additional structured data (e.g., {"fileName": "data.dbf", "encoding": "CP866"}) |
| `occurredAt` | LocalDateTime | Primary Key (composite with id), Non-null | Error occurrence timestamp (partitioning key) |
| `createdAt` | LocalDateTime | Non-null, Default: now() | Record insertion timestamp |

**Relationships**:
- **Many-to-One** with Site: `site`
- **Many-to-One** with Batch: `batch` (nullable)

**Business Rules** (Invariants):
1. Errors can exist without batch association (`batchId = null` for configuration/startup errors)
2. When `batchId` is provided, the batch's `hasErrors` flag must be set to `true` (domain event or direct update)
3. Partitioning by `occurredAt` month: new partitions created automatically one month in advance (FR-075)
4. `metadata` field allows flexible JSON storage without schema changes (e.g., file-specific details)
5. Errors are immutable once created (no updates or deletes at domain level; only admin deletion of batch cascades)

**Database Schema** (Flyway migration `V5__create_error_logs_partitioned_table.sql`):
```sql
CREATE TABLE error_logs (
    id UUID NOT NULL,
    site_id UUID NOT NULL REFERENCES sites(id),
    batch_id UUID REFERENCES batches(id),
    type VARCHAR(100) NOT NULL,
    title VARCHAR(500) NOT NULL,
    message TEXT NOT NULL,
    stack_trace TEXT,
    client_version VARCHAR(50),
    metadata JSONB,
    occurred_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, occurred_at)  -- Composite PK for partitioning
) PARTITION BY RANGE (occurred_at);

CREATE INDEX idx_error_logs_site_id ON error_logs(site_id);
CREATE INDEX idx_error_logs_batch_id ON error_logs(batch_id);
CREATE INDEX idx_error_logs_type ON error_logs(type);
CREATE INDEX idx_error_logs_occurred_at ON error_logs(occurred_at);
CREATE INDEX idx_error_logs_metadata_gin ON error_logs USING GIN (metadata);  -- JSONB indexing

-- Initial partition (additional partitions created by scheduled task)
CREATE TABLE error_logs_2025_10 PARTITION OF error_logs
    FOR VALUES FROM ('2025-10-01') TO ('2025-11-01');
```

**Domain Services**:
- `PartitionManager` - Creates new partitions proactively, drops old partitions per retention policy (future phase)

**Domain Events**:
- `ErrorLoggedEvent(errorLogId, siteId, batchId, type)` - For metrics collection

---

## Validation Rules Summary

### Cross-Entity Validation
1. **Account → Site**: When account is deactivated, all sites must be deactivated (cascading via domain event)
2. **Site → Batch**: Only active sites can create batches; only one IN_PROGRESS batch per site allowed
3. **Batch → UploadedFile**: Files can only be uploaded to IN_PROGRESS batches; filename uniqueness per batch
4. **Batch → ErrorLog**: When error is associated with batch, batch's `hasErrors` flag is set

### Lifecycle Constraints
- Account: Can be soft-deleted (isActive=false); hard delete not allowed at domain level
- Site: Can be soft-deleted; hard delete not allowed
- Batch: Terminal states (COMPLETED, FAILED, CANCELLED, NOT_COMPLETED) are immutable; admin can delete metadata but files remain in S3
- UploadedFile: Cascade-deleted when batch is hard-deleted; individual deletion not supported
- ErrorLog: Immutable once created; cascade-deleted when batch is hard-deleted

---

## Domain Events

| Event | Trigger | Consumers | Purpose |
|-------|---------|-----------|---------|
| `AccountDeactivatedEvent(accountId)` | Account.deactivate() | SiteService | Cascade deactivation to all sites |
| `BatchStartedEvent(batchId, siteId)` | Batch creation | MetricsCollector | Increment batch_started_total counter |
| `BatchCompletedEvent(batchId, filesCount, totalSize)` | Batch.complete() | MetricsCollector | Increment batch_completed_total, record size metrics |
| `BatchExpiredEvent(batchId)` | BatchTimeoutScheduler | MetricsCollector | Increment batch_not_completed_total |
| `ErrorLoggedEvent(errorLogId, type)` | ErrorLog creation | BatchService, MetricsCollector | Set Batch.hasErrors, increment error_logs_total |

---

## Repository Interfaces

### AccountRepository
```java
Optional<Account> findById(UUID id);
Optional<Account> findByEmail(String email);
List<Account> findAll(Pageable pageable);
Account save(Account account);
boolean existsByEmail(String email);
```

### SiteRepository
```java
Optional<Site> findById(UUID id);
Optional<Site> findByDomain(String domain);
List<Site> findByAccountId(UUID accountId);
Site save(Site site);
boolean existsByDomain(String domain);
```

### BatchRepository
```java
Optional<Batch> findById(UUID id);
Optional<Batch> findActiveBySiteId(UUID siteId);  // WHERE site_id = ? AND status = 'IN_PROGRESS'
List<Batch> findExpiredBatches(LocalDateTime cutoffTime);  // WHERE status = 'IN_PROGRESS' AND started_at < cutoffTime
List<Batch> findBySiteIdAndStatus(UUID siteId, BatchStatus status, Pageable pageable);
Batch save(Batch batch);
int countActiveBatchesByAccountId(UUID accountId);
```

### UploadedFileRepository
```java
Optional<UploadedFile> findById(UUID id);
List<UploadedFile> findByBatchId(UUID batchId);
boolean existsByBatchIdAndOriginalFileName(UUID batchId, String fileName);
UploadedFile save(UploadedFile file);
```

### ErrorLogRepository
```java
ErrorLog save(ErrorLog errorLog);
Page<ErrorLog> findBySiteId(UUID siteId, Pageable pageable);
Page<ErrorLog> findByTypeAndOccurredAtBetween(String type, LocalDateTime start, LocalDateTime end, Pageable pageable);
List<ErrorLog> exportByFilters(UUID siteId, String type, LocalDateTime start, LocalDateTime end);  // For CSV export
```

---

## Database Indexes Strategy

### Primary Indexes (created with table definitions)
- All primary keys are UUIDs with B-tree indexes
- Composite PK on `error_logs(id, occurred_at)` for partitioning

### Foreign Key Indexes (mandatory per constitution)
- `idx_sites_account_id` on `sites(account_id)`
- `idx_batches_site_id` on `batches(site_id)`
- `idx_uploaded_files_batch_id` on `uploaded_files(batch_id)`
- `idx_error_logs_site_id` on `error_logs(site_id)`
- `idx_error_logs_batch_id` on `error_logs(batch_id)`

### Unique Constraints
- `accounts(email)` - Global email uniqueness
- `sites(domain)` - Global domain uniqueness
- `uploaded_files(batch_id, original_file_name)` - Filename uniqueness per batch
- `batches(site_id) WHERE status = 'IN_PROGRESS'` - One active batch per site (partial index)

### Query Optimization Indexes
- `idx_accounts_is_active` on `accounts(is_active)` - Filter deactivated accounts
- `idx_batches_status` on `batches(status)` - Query by lifecycle state
- `idx_batches_started_at` on `batches(started_at)` - Timeout calculation
- `idx_error_logs_type` on `error_logs(type)` - Filter by error category
- `idx_error_logs_occurred_at` on `error_logs(occurred_at)` - Date range queries
- `idx_error_logs_metadata_gin` on `error_logs(metadata)` - JSONB queries (GIN index)

---

## Status

✅ **Data model design complete** - Ready for contract generation (Phase 1 continuation)
