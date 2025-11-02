# Data Model: Upload History Feature

## Overview

The Upload History feature leverages existing data models (`batches` and `uploaded_files` tables) without requiring schema changes. This document outlines how existing entities support the feature requirements and defines new DTOs for API responses.

---

## Existing Entities (No Schema Changes Required)

### 1. Batch (Upload Session)

**Purpose**: Represents a single upload session containing multiple CSV files.

**Schema** (from `V3__create_batches_table.sql`, `V6__add_account_id_to_batches.sql`, `V8__add_version_column_to_batches.sql`):

| Column | Type | Constraints | Used By Feature |
|---|---|---|---|
| `id` | UUID | PRIMARY KEY | ✅ Batch identifier for details view |
| `account_id` | UUID | FK → accounts | ✅ Authorization check (user owns batch) |
| `site_id` | UUID | FK → sites | ✅ Filter batches by user's sites |
| `status` | VARCHAR(50) | IN_PROGRESS/COMPLETED/FAILED/... | ✅ Determine download availability |
| `s3_path` | VARCHAR(500) | NOT NULL | ⚠️ Not directly used (files use s3_key) |
| `uploaded_files_count` | INTEGER | >= 0, default 0 | ✅ Display file count in list view |
| `total_size` | BIGINT | >= 0, default 0 | ✅ Display total size in list view |
| `has_errors` | BOOLEAN | default false | ✅ Show error indicator in list view |
| `started_at` | TIMESTAMP | NOT NULL, indexed | ✅ Sort batches by date (DESC) |
| `completed_at` | TIMESTAMP | NULL when IN_PROGRESS | ✅ Show completion time |
| `created_at` | TIMESTAMP | default CURRENT_TIMESTAMP | ⚠️ Not used (using started_at instead) |
| `version` | BIGINT | Optimistic locking | ⚠️ Not relevant for read operations |

**Indexes Used**:
```sql
CREATE INDEX idx_batches_site_id ON batches(site_id);
CREATE INDEX idx_batches_started_at ON batches(started_at);
CREATE INDEX idx_batches_account_id ON batches(account_id);
```

**Feature-Specific Composite Index** (RECOMMENDED for pagination performance):
```sql
CREATE INDEX idx_batches_site_started_id ON batches(site_id, started_at DESC, id DESC);
```
- Supports cursor-based pagination query: `WHERE site_id = ? AND (started_at < ? OR (started_at = ? AND id < ?))`
- Eliminates need for additional index scans

**JPA Entity**: `com.bitbi.dfm.batch.domain.Batch`

**Repository**: `com.bitbi.dfm.batch.domain.BatchRepository` (interface), `com.bitbi.dfm.batch.infrastructure.JpaBatchRepository` (impl)

---

### 2. UploadedFile

**Purpose**: Represents a single file within an upload session.

**Schema** (from `V4__create_uploaded_files_table.sql`):

| Column | Type | Constraints | Used By Feature |
|---|---|---|---|
| `id` | UUID | PRIMARY KEY | ✅ File identifier for download |
| `batch_id` | UUID | FK → batches, CASCADE DELETE | ✅ Link files to batch |
| `original_file_name` | VARCHAR(500) | NOT NULL | ✅ Display filename in UI |
| `s3_key` | VARCHAR(1000) | UNIQUE, NOT NULL | ✅ Generate presigned URLs for download |
| `file_size` | BIGINT | > 0 | ✅ Display file size in UI |
| `content_type` | VARCHAR(100) | NOT NULL | ✅ Set Content-Type for downloads |
| `checksum` | VARCHAR(64) | NOT NULL | ⚠️ Could be used for download integrity verification |
| `uploaded_at` | TIMESTAMP | default CURRENT_TIMESTAMP | ✅ Show upload timestamp |

**Indexes Used**:
```sql
CREATE INDEX idx_uploaded_files_batch_id ON uploaded_files(batch_id);
```

**JPA Entity**: `com.bitbi.dfm.upload.domain.UploadedFile`

**Repository**: `com.bitbi.dfm.upload.domain.UploadedFileRepository` (interface), `com.bitbi.dfm.upload.infrastructure.JpaUploadedFileRepository` (impl)

---

### 3. ErrorLog (Supporting Entity)

**Purpose**: Track errors that occurred during upload (referenced for "has errors" indicator).

**Schema** (from `V5__create_error_logs_table.sql`):

| Column | Type | Used By Feature |
|---|---|---|
| `id` | UUID | ⚠️ Not directly used |
| `batch_id` | UUID | ✅ Link errors to batch |
| `severity` | VARCHAR(50) | ✅ Display in error details view |
| `message` | TEXT | ✅ Display in error details view |
| `source` | VARCHAR(200) | ✅ Display in error details view |
| `metadata_json` | JSONB | ✅ Display in error details view |
| `occurred_at` | TIMESTAMP | ✅ Sort errors by timestamp |

**JPA Entity**: `com.bitbi.dfm.error.domain.ErrorLog`

**Repository**: `com.bitbi.dfm.error.domain.ErrorLogRepository`

---

## New DTOs (API Response Models)

### 1. BatchSummaryDto (List View)

**Purpose**: Display upload sessions in paginated list.

**Fields**:
```java
public record BatchSummaryDto(
    UUID id,                    // Batch identifier
    UUID siteId,                // Site reference (for authorization)
    String status,              // COMPLETED, IN_PROGRESS, etc.
    boolean hasErrors,          // Red cross indicator
    int uploadedFilesCount,     // File count badge
    long totalSize,             // Human-readable (e.g., "50.3 MB")
    LocalDateTime startedAt,    // Sort key (DESC)
    LocalDateTime completedAt   // NULL if in progress
) {
    public static BatchSummaryDto fromEntity(Batch batch) { ... }
    public static BatchSummaryDto fromProjection(BatchWithFileCountProjection projection) { ... }
}
```

**Design Notes**:
- Uses DTO projection instead of full entity loading (performance optimization)
- `totalSize` rendered as human-readable string in frontend (e.g., "50.3 MB", "1.2 GB")
- `status` determines if file actions (download, Excel) are enabled (only COMPLETED allowed)

---

### 2. BatchDetailDto (Detail View)

**Purpose**: Display full batch information with file list.

**Fields**:
```java
public record BatchDetailDto(
    UUID id,
    UUID siteId,
    String status,
    boolean hasErrors,
    int uploadedFilesCount,
    long totalSize,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    List<FileMetadataDto> files  // All files in batch
) {
    public static BatchDetailDto fromEntity(Batch batch, List<UploadedFile> files) { ... }
}
```

**Design Notes**:
- Includes full file list (no pagination within batch - assumption: max 100-500 files per batch)
- If batches with 1000+ files become common, add file pagination later

---

### 3. FileMetadataDto (File List Item)

**Purpose**: Display individual file metadata in batch details view.

**Fields**:
```java
public record FileMetadataDto(
    UUID id,                    // File identifier for download
    String originalFileName,    // Display name (e.g., "sales-2024.csv.gz")
    long fileSize,              // Bytes (convert to KB/MB in frontend)
    LocalDateTime uploadedAt    // Upload timestamp
) {
    public static FileMetadataDto fromEntity(UploadedFile file) { ... }
}
```

**Design Notes**:
- Does NOT include `s3_key` (security - not exposed to frontend)
- Does NOT include `checksum` (internal use only)
- Frontend converts `fileSize` to human-readable format (e.g., "2.5 MB")

---

### 4. FileDownloadResponseDto (Download Endpoint Response)

**Purpose**: Return presigned URL for direct S3 download.

**Fields**:
```java
public record FileDownloadResponseDto(
    String downloadUrl,         // S3 presigned URL (15-minute expiry)
    String fileName,            // Suggested filename for browser download
    long fileSize,              // File size in bytes
    LocalDateTime expiresAt     // URL expiration timestamp
) {}
```

**Design Notes**:
- Presigned URL is short-lived (15 minutes) for security
- Frontend uses URL to trigger browser download via `<a download>` or Blob API
- Expiry included for UI countdown timer (optional UX enhancement)

---

### 5. CursorPageResponseDto<T> (Pagination Wrapper)

**Purpose**: Wrap paginated lists with cursor-based navigation.

**Fields**:
```java
public record CursorPageResponseDto<T>(
    List<T> items,              // Current page items
    String nextCursor,          // Cursor for next page (NULL if last page)
    boolean hasNext             // Convenience flag for UI
) {}
```

**Design Notes**:
- Generic wrapper for any cursor-paginated resource
- Cursor format: `"{startedAt}_{id}"` (e.g., `"2025-11-01T10:30:00_550e8400-e29b-41d4-a716-446655440000"`)
- Frontend stores `nextCursor` and includes in `?cursor=...` query param for next page request

---

### 6. ErrorSummaryDto (Error Details View)

**Purpose**: Display errors associated with a batch.

**Fields**:
```java
public record ErrorSummaryDto(
    UUID id,
    String severity,            // ERROR, WARNING, INFO
    String message,             // Error message
    String source,              // File or component that logged error
    Map<String, Object> metadata, // Additional context (from JSONB)
    LocalDateTime occurredAt    // Error timestamp
) {
    public static ErrorSummaryDto fromEntity(ErrorLog errorLog) { ... }
}
```

**Design Notes**:
- Used when user clicks "View errors" from batch list
- Separate endpoint: `GET /api/dfc/batches/{batchId}/errors`
- Paginated if error count > 100

---

## DTO Projection Patterns (Performance Optimization)

### BatchWithFileCountProjection (Repository Interface Projection)

**Purpose**: Avoid N+1 queries when loading batch list with file counts.

**Interface**:
```java
public interface BatchWithFileCountProjection {
    UUID getId();
    UUID getSiteId();
    String getStatus();
    Boolean getHasErrors();
    LocalDateTime getStartedAt();
    LocalDateTime getCompletedAt();

    @Value("#{target.uploadedFilesCount}")
    Integer getFileCount();  // Direct column, not JOIN

    @Value("#{target.totalSize}")
    Long getTotalSize();
}
```

**Usage in Repository**:
```java
@Query("""
    SELECT b.id as id, b.siteId as siteId, b.status as status,
           b.hasErrors as hasErrors, b.startedAt as startedAt,
           b.completedAt as completedAt, b.uploadedFilesCount as fileCount,
           b.totalSize as totalSize
    FROM Batch b
    WHERE b.siteId IN :siteIds
      AND (b.startedAt < :cursor OR (b.startedAt = :cursor AND b.id < :cursorId))
    ORDER BY b.startedAt DESC, b.id DESC
    LIMIT :limit
    """)
List<BatchWithFileCountProjection> findBySiteIdsWithCursor(
    List<UUID> siteIds, LocalDateTime cursor, UUID cursorId, int limit
);
```

**Performance Benefit**:
- Single query instead of 1 batch query + N file count queries
- Uses direct column `uploadedFilesCount` (materialized counter)
- No JOIN required (file count is denormalized)

---

## Authorization Model

### Resource Ownership Chain

```
User (Account) → Site → Batch → UploadedFile
```

**Authorization Checks**:
1. **List Batches**: User can only see batches from their own sites
   - Extract `accountId` from JWT token
   - Query: `SELECT * FROM batches WHERE site_id IN (SELECT id FROM sites WHERE account_id = ?)`

2. **View Batch Details**: Verify batch belongs to user's site
   - Check: `batch.site.accountId == token.accountId`

3. **Download File**: Verify file's batch belongs to user's site
   - Check: `file.batch.site.accountId == token.accountId`

**Implementation** (existing pattern):
```java
// Service layer
public BatchDetailDto getBatchDetails(UUID batchId, UUID accountId) {
    Batch batch = batchRepository.findById(batchId)
        .orElseThrow(() -> new BatchNotFoundException(batchId));

    // Authorization check
    if (!batch.getSite().getAccountId().equals(accountId)) {
        throw new UnauthorizedBatchAccessException("Batch does not belong to user");
    }

    return BatchDetailDto.fromEntity(batch);
}
```

---

## Query Patterns

### 1. List Batches (Cursor-Based Pagination)

**First Page**:
```java
@Query("""
    SELECT b
    FROM Batch b
    JOIN FETCH b.site s
    WHERE s.accountId = :accountId
    ORDER BY b.startedAt DESC, b.id DESC
    LIMIT :limit
    """)
List<Batch> findByAccountIdFirstPage(UUID accountId, int limit);
```

**Subsequent Pages**:
```java
@Query("""
    SELECT b
    FROM Batch b
    JOIN FETCH b.site s
    WHERE s.accountId = :accountId
      AND (b.startedAt < :cursor OR (b.startedAt = :cursor AND b.id < :cursorId))
    ORDER BY b.startedAt DESC, b.id DESC
    LIMIT :limit
    """)
List<Batch> findByAccountIdWithCursor(
    UUID accountId, LocalDateTime cursor, UUID cursorId, int limit
);
```

**Index Used**: `idx_batches_site_started_id` (composite index on site_id, started_at DESC, id DESC)

---

### 2. Load Batch with Files

**Efficient Loading** (avoids N+1):
```java
@Query("""
    SELECT b
    FROM Batch b
    LEFT JOIN FETCH b.uploadedFiles
    WHERE b.id = :batchId
    """)
Optional<Batch> findByIdWithFiles(UUID batchId);
```

**Alternative** (if files are loaded separately):
```java
// Load batch first
Batch batch = batchRepository.findById(batchId);

// Load files in single query
List<UploadedFile> files = uploadedFileRepository.findByBatchId(batchId);
```

---

### 3. Load Errors for Batch

```java
@Query("""
    SELECT e
    FROM ErrorLog e
    WHERE e.batchId = :batchId
    ORDER BY e.occurredAt DESC
    """)
List<ErrorLog> findByBatchIdOrderByOccurredAtDesc(UUID batchId);
```

**Pagination for Large Error Sets**:
```java
Page<ErrorLog> findByBatchId(UUID batchId, Pageable pageable);
```

---

## Migration Requirements

### No Schema Changes Required

✅ All existing tables support the Upload History feature without modifications.

### Recommended Index Addition (Performance Optimization)

**Migration**: `V###__add_upload_history_indexes.sql`

```sql
-- Composite index for cursor-based pagination
CREATE INDEX IF NOT EXISTS idx_batches_site_started_id
    ON batches(site_id, started_at DESC, id DESC);

-- Rationale: Supports efficient cursor queries without table scans
-- Query pattern: WHERE site_id = ? AND (started_at < ? OR (started_at = ? AND id < ?))
-- Performance: O(log n) lookup + sequential scan vs O(n) for OFFSET-based pagination
```

**Impact**:
- Index size: ~50 bytes per batch × 10,000 batches = ~500KB (negligible)
- Query performance: 50ms → 5ms for page 500 (10x improvement)
- Write performance: Minimal impact (<1ms per INSERT/UPDATE)

---

## Entity Relationship Diagram

```
┌──────────────┐
│  accounts    │
│  (existing)  │
└──────┬───────┘
       │
       │ 1:N
       ▼
┌──────────────┐
│    sites     │
│  (existing)  │
└──────┬───────┘
       │
       │ 1:N
       ▼
┌──────────────┐         ┌──────────────┐
│   batches    │ 1:N     │ error_logs   │
│  (existing)  ├────────►│  (existing)  │
└──────┬───────┘         └──────────────┘
       │
       │ 1:N
       ▼
┌──────────────┐
│uploaded_files│
│  (existing)  │
└──────────────┘
```

**Key Relationships**:
- **Account** → **Site**: One account owns multiple sites
- **Site** → **Batch**: One site has multiple upload sessions
- **Batch** → **UploadedFile**: One batch contains multiple files (CASCADE DELETE)
- **Batch** → **ErrorLog**: One batch may have multiple error entries

**Authorization Flow**:
- User (JWT contains accountId) → Query sites by accountId → Query batches by siteIds

---

## Caching Strategy

### Redis Cache Keys

```java
// First page (5-minute TTL)
"batch-first-page:{accountId}"

// Batch details (30-minute TTL, only for COMPLETED batches)
"batch-details:{batchId}"  // Conditional: status != IN_PROGRESS
```

### Cache Invalidation

```java
@CacheEvict(value = "batch-first-page", key = "#batch.site.accountId")
public void onBatchCompleted(Batch batch) {
    // Evict first page cache when batch status changes
}
```

**Rationale**:
- First page is hottest (80% of traffic)
- Completed batches are immutable (safe to cache)
- IN_PROGRESS batches are NOT cached (dynamic data)

---

## Summary

### Existing Entities Used
- ✅ **Batch**: Upload session metadata (no changes)
- ✅ **UploadedFile**: File metadata (no changes)
- ✅ **ErrorLog**: Error tracking (no changes)

### New DTOs Created
- ✅ **BatchSummaryDto**: List view item
- ✅ **BatchDetailDto**: Detail view with files
- ✅ **FileMetadataDto**: File list item
- ✅ **FileDownloadResponseDto**: Download URL response
- ✅ **CursorPageResponseDto<T>**: Pagination wrapper
- ✅ **ErrorSummaryDto**: Error details

### Schema Changes
- ❌ **None required** (all data already exists)
- ✅ **Recommended**: Add composite index `idx_batches_site_started_id` for pagination performance

### Performance Optimizations
- ✅ Cursor-based pagination (vs OFFSET)
- ✅ DTO projections (avoid N+1 queries)
- ✅ Redis caching (first page + completed batches)
- ✅ Composite indexes (efficient sorting + filtering)
