# Data Model: File Diff Comparison Between Upload Sessions

**Feature**: File Diff Comparison Between Upload Sessions
**Date**: 2025-11-03
**Branch**: `009-markdown-user-story`

## Overview

This document defines the domain entities, value objects, and database schema for the file comparison feature. The design follows Domain-Driven Design (DDD) principles with clear aggregate boundaries and follows the existing patterns established in the codebase.

---

## Domain Model

### Aggregate: FileComparison

**Aggregate Root**: `FileComparison`

**Purpose**: Represents a comparison operation between two upload sessions (batches). This is the aggregate root that maintains consistency for all comparison results.

**Invariants**:
1. A comparison must reference two valid batches (current and target)
2. Both batches must belong to the same account
3. Current batch must be temporally after or equal to target batch (prevent comparing "future" with "past")
4. A comparison cannot be deleted while in IN_PROGRESS status
5. Comparison results can only be added while status is IN_PROGRESS or PENDING

**Lifecycle States**:
```
PENDING → IN_PROGRESS → COMPLETED
                      ↘ FAILED
```

**State Transitions**:
- `PENDING` → `IN_PROGRESS`: When comparison process starts
- `IN_PROGRESS` → `COMPLETED`: When all file comparisons succeed
- `IN_PROGRESS` → `FAILED`: When any unrecoverable error occurs (S3 access denied, file not found, etc.)

---

### Entities

#### 1. FileComparison (Aggregate Root)

**Domain Entity**: `com.bitbi.dfm.comparison.domain.FileComparison`

**Attributes**:
- `id: Long` - Unique identifier (generated)
- `currentBatchId: Long` - Reference to current batch (source)
- `targetBatchId: Long` - Reference to target batch (comparison baseline)
- `accountId: Long` - Account owner (for authorization and multi-tenancy)
- `status: ComparisonStatus` - Current lifecycle state (enum)
- `createdAt: Instant` - Timestamp when comparison was initiated
- `startedAt: Instant` - Timestamp when processing started (nullable, set when status → IN_PROGRESS)
- `completedAt: Instant` - Timestamp when processing completed (nullable, set when status → COMPLETED/FAILED)
- `totalFilesCompared: Integer` - Count of files processed (default 0)
- `filesChanged: Integer` - Count of files with changes (default 0)
- `filesAdded: Integer` - Count of new files (default 0)
- `filesUnchanged: Integer` - Count of identical files (default 0)
- `totalChangeSize: Long` - Total size of changes in bytes (default 0)
- `errorMessage: String` - Error details if status = FAILED (nullable, max 1000 chars)
- `results: List<ComparisonResult>` - Child entities (lazy-loaded collection)

**Business Methods**:
- `startComparison(): void` - Transition to IN_PROGRESS, set startedAt
- `completeComparison(): void` - Transition to COMPLETED, set completedAt
- `failComparison(String errorMessage): void` - Transition to FAILED, set completedAt and errorMessage
- `addResult(ComparisonResult result): void` - Add comparison result, update statistics
- `canDelete(): boolean` - Check if deletion is allowed (status != IN_PROGRESS)
- `getSummary(): ComparisonSummary` - Generate value object with summary statistics

**Validation Rules**:
- currentBatchId must exist and belong to accountId
- targetBatchId must exist and belong to accountId
- currentBatchId != targetBatchId (cannot compare batch with itself)
- Both batches must have files (uploadedFilesCount > 0) *(Updated 2025-11-03: Removed COMPLETED status requirement - batches in any status can be compared)*

---

#### 2. ComparisonResult (Child Entity)

**Domain Entity**: `com.bitbi.dfm.comparison.domain.ComparisonResult`

**Purpose**: Represents the diff result for a single file comparison within a FileComparison aggregate.

**Attributes**:
- `id: Long` - Unique identifier (generated)
- `comparisonId: Long` - Reference to parent FileComparison (foreign key)
- `fileId: Long` - Reference to file in current batch
- `targetFileId: Long` - Reference to file in target batch (nullable if file is new)
- `changeType: ChangeType` - Type of change detected (enum)
- `unifiedDiff: String` - Diff output in unified format (JSONB stored as structured JSON)
- `lineAdditions: Integer` - Count of added lines (default 0)
- `lineDeletions: Integer` - Count of deleted lines (default 0)
- `changeSize: Long` - Size of changes in bytes (default 0)
- `createdAt: Instant` - Timestamp when result was created

**Business Methods**:
- `hasChanges(): boolean` - Returns true if changeType = MODIFIED or ADDED
- `getDiffSummary(): String` - Returns human-readable summary (e.g., "+10 -5")
- `toDto(): ComparisonResultDto` - Convert to DTO for API response

**Validation Rules**:
- If changeType = ADDED, targetFileId must be null
- If changeType = MODIFIED or UNCHANGED, targetFileId must not be null
- If changeType = UNCHANGED, unifiedDiff should be empty or null
- lineAdditions >= 0, lineDeletions >= 0

---

### Value Objects

#### 1. ComparisonStatus (Enum)

**Purpose**: Represents the lifecycle state of a FileComparison

**Values**:
- `PENDING` - Comparison created but not yet started
- `IN_PROGRESS` - Comparison is actively processing files
- `COMPLETED` - Comparison finished successfully
- `FAILED` - Comparison failed due to error

**Transitions**:
```java
public enum ComparisonStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED;

    public boolean canTransitionTo(ComparisonStatus target) {
        return switch (this) {
            case PENDING -> target == IN_PROGRESS;
            case IN_PROGRESS -> target == COMPLETED || target == FAILED;
            case COMPLETED, FAILED -> false; // Terminal states
        };
    }
}
```

---

#### 2. ChangeType (Enum)

**Purpose**: Classifies the type of change detected for a file

**Values**:
- `ADDED` - File exists in current batch but not in target batch (new file)
- `MODIFIED` - File exists in both batches with different content
- `UNCHANGED` - File exists in both batches with identical content (no diff stored)
- `REMOVED` - File exists in target batch but not in current batch (not implemented in MVP per spec)

**Business Logic**:
```java
public enum ChangeType {
    ADDED,
    MODIFIED,
    UNCHANGED,
    REMOVED;  // For future enhancement

    public boolean requiresDiff() {
        return this == MODIFIED || this == ADDED;
    }

    public boolean countsAsChange() {
        return this == MODIFIED || this == ADDED;
    }
}
```

---

#### 3. ComparisonSummary (Value Object)

**Purpose**: Immutable value object representing summary statistics for a FileComparison

**Attributes**:
- `totalFilesCompared: Integer`
- `filesChanged: Integer`
- `filesAdded: Integer`
- `filesUnchanged: Integer`
- `totalChangeSize: Long`
- `comparisonTimestamp: Instant`
- `currentBatchId: Long`
- `targetBatchId: Long`

**Characteristics**:
- Immutable (Java record)
- No database mapping (generated on-demand from FileComparison)
- Used for API responses and report generation

```java
public record ComparisonSummary(
    int totalFilesCompared,
    int filesChanged,
    int filesAdded,
    int filesUnchanged,
    long totalChangeSize,
    Instant comparisonTimestamp,
    Long currentBatchId,
    Long targetBatchId
) {
    public static ComparisonSummary from(FileComparison comparison) {
        return new ComparisonSummary(
            comparison.getTotalFilesCompared(),
            comparison.getFilesChanged(),
            comparison.getFilesAdded(),
            comparison.getFilesUnchanged(),
            comparison.getTotalChangeSize(),
            comparison.getCompletedAt() != null ? comparison.getCompletedAt() : comparison.getCreatedAt(),
            comparison.getCurrentBatchId(),
            comparison.getTargetBatchId()
        );
    }
}
```

---

## Database Schema

### Table: file_comparisons

**Purpose**: Stores metadata for comparison operations between upload sessions

```sql
CREATE TABLE file_comparisons (
    id BIGSERIAL PRIMARY KEY,
    current_batch_id BIGINT NOT NULL REFERENCES batches(id) ON DELETE CASCADE,
    target_batch_id BIGINT NOT NULL REFERENCES batches(id) ON DELETE CASCADE,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    total_files_compared INTEGER NOT NULL DEFAULT 0,
    files_changed INTEGER NOT NULL DEFAULT 0,
    files_added INTEGER NOT NULL DEFAULT 0,
    files_unchanged INTEGER NOT NULL DEFAULT 0,
    total_change_size BIGINT NOT NULL DEFAULT 0,
    error_message VARCHAR(1000),
    CONSTRAINT chk_status CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_batch_ids CHECK (current_batch_id != target_batch_id),
    CONSTRAINT chk_statistics CHECK (
        total_files_compared >= 0 AND
        files_changed >= 0 AND
        files_added >= 0 AND
        files_unchanged >= 0 AND
        (total_files_compared = files_changed + files_added + files_unchanged)
    )
);

-- Indexes for query performance
CREATE INDEX idx_file_comparisons_account_id ON file_comparisons(account_id);
CREATE INDEX idx_file_comparisons_current_batch ON file_comparisons(current_batch_id);
CREATE INDEX idx_file_comparisons_target_batch ON file_comparisons(target_batch_id);
CREATE INDEX idx_file_comparisons_status ON file_comparisons(status);
CREATE INDEX idx_file_comparisons_created_at ON file_comparisons(created_at DESC);

-- Composite index for common queries (find comparisons for account, ordered by date)
CREATE INDEX idx_file_comparisons_account_created ON file_comparisons(account_id, created_at DESC);
```

**Constraints**:
- `current_batch_id` and `target_batch_id` must reference valid batches
- `account_id` must match the account owning both batches (enforced by application logic)
- `status` must be one of the valid enum values
- Statistics must be internally consistent (total = changed + added + unchanged)
- `current_batch_id` != `target_batch_id` (cannot compare batch with itself)

**Cascade Behavior**:
- If a batch is deleted, all comparisons referencing it are deleted (ON DELETE CASCADE)
- If an account is deleted, all comparisons are deleted (ON DELETE CASCADE)

---

### Table: comparison_results

**Purpose**: Stores individual file diff results for each comparison operation

```sql
CREATE TABLE comparison_results (
    id BIGSERIAL PRIMARY KEY,
    comparison_id BIGINT NOT NULL REFERENCES file_comparisons(id) ON DELETE CASCADE,
    file_id BIGINT NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    target_file_id BIGINT REFERENCES files(id) ON DELETE CASCADE,
    change_type VARCHAR(20) NOT NULL,
    unified_diff JSONB,
    line_additions INTEGER NOT NULL DEFAULT 0,
    line_deletions INTEGER NOT NULL DEFAULT 0,
    change_size BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_change_type CHECK (change_type IN ('ADDED', 'MODIFIED', 'UNCHANGED', 'REMOVED')),
    CONSTRAINT chk_target_file_required CHECK (
        (change_type = 'ADDED' AND target_file_id IS NULL) OR
        (change_type != 'ADDED' AND target_file_id IS NOT NULL)
    ),
    CONSTRAINT chk_line_counts CHECK (line_additions >= 0 AND line_deletions >= 0)
);

-- Indexes for query performance
CREATE INDEX idx_comparison_results_comparison_id ON comparison_results(comparison_id);
CREATE INDEX idx_comparison_results_file_id ON comparison_results(file_id);
CREATE INDEX idx_comparison_results_change_type ON comparison_results(change_type);

-- Composite index for filtering results by comparison and change type
CREATE INDEX idx_comparison_results_comparison_change ON comparison_results(comparison_id, change_type);

-- GIN index for JSONB diff content (if we need to query diff structure)
CREATE INDEX idx_comparison_results_diff ON comparison_results USING GIN(unified_diff);
```

**Constraints**:
- `comparison_id` must reference valid FileComparison
- `file_id` must reference valid file
- `target_file_id` is required for MODIFIED/UNCHANGED, null for ADDED
- `change_type` must be one of the valid enum values
- Line counts must be non-negative

**Cascade Behavior**:
- If FileComparison is deleted, all results are deleted (ON DELETE CASCADE)
- If a file is deleted, comparison results referencing it are deleted (ON DELETE CASCADE)

**JSONB Structure for unified_diff**:

```json
{
  "hunks": [
    {
      "oldStart": 1,
      "oldLines": 3,
      "newStart": 1,
      "newLines": 4,
      "changes": [
        {"type": "UNCHANGED", "lineNumber": 1, "content": "line 1"},
        {"type": "REMOVED", "lineNumber": 2, "content": "line 2"},
        {"type": "ADDED", "lineNumber": 2, "content": "line 2 modified"},
        {"type": "ADDED", "lineNumber": 3, "content": "line 3 added"},
        {"type": "UNCHANGED", "lineNumber": 4, "content": "line 4"}
      ]
    }
  ],
  "oldFileName": "file1.csv",
  "newFileName": "file1.csv"
}
```

---

## Relationships

### Entity Relationship Diagram

```
┌─────────────────┐
│    accounts     │
│                 │
│  id (PK)        │
└────────┬────────┘
         │ 1
         │
         │ *
┌────────▼────────┐       1       ┌─────────────────┐
│    batches      │◄──────────────│ file_comparisons│
│                 │                │                 │
│  id (PK)        │                │  id (PK)        │
│  account_id(FK) │◄───────┐      │  current_batch_id (FK)
└────────┬────────┘        │      │  target_batch_id (FK)
         │ 1               │      │  account_id (FK)
         │                 │      │  status         │
         │ *               │      │  statistics...  │
┌────────▼────────┐       │      └────────┬────────┘
│     files       │        │               │ 1
│                 │        │               │
│  id (PK)        │        │               │ *
│  batch_id (FK)  │        │      ┌────────▼────────┐
└────────┬────────┘        │      │comparison_results│
         │ 1               │      │                 │
         │                 │      │  id (PK)        │
         │ *               │      │  comparison_id(FK)
         └─────────────────┼─────►│  file_id (FK)   │
                           └─────►│  target_file_id(FK)
                                  │  change_type    │
                                  │  unified_diff   │
                                  │  statistics...  │
                                  └─────────────────┘
```

### Relationships Summary

1. **accounts → batches**: One-to-Many (existing relationship)
2. **batches → files**: One-to-Many (existing relationship)
3. **accounts → file_comparisons**: One-to-Many (new relationship)
4. **batches → file_comparisons (current_batch_id)**: One-to-Many (new relationship)
5. **batches → file_comparisons (target_batch_id)**: One-to-Many (new relationship)
6. **file_comparisons → comparison_results**: One-to-Many (new relationship, aggregate boundary)
7. **files → comparison_results (file_id)**: One-to-Many (new relationship)
8. **files → comparison_results (target_file_id)**: One-to-Many (new relationship, optional)

---

## Repository Interfaces

### ComparisonRepository

**Interface**: `com.bitbi.dfm.comparison.domain.ComparisonRepository`

**Purpose**: Define domain repository contract (implementation in infrastructure layer)

**Methods**:

```java
public interface ComparisonRepository {
    // Create
    FileComparison save(FileComparison comparison);

    // Read
    Optional<FileComparison> findById(Long id);
    List<FileComparison> findByAccountId(Long accountId);
    List<FileComparison> findByCurrentBatchId(Long batchId);
    List<FileComparison> findByAccountIdOrderByCreatedAtDesc(Long accountId, Pageable pageable);
    boolean existsByCurrentBatchIdAndTargetBatchIdAndStatus(Long currentBatchId, Long targetBatchId, ComparisonStatus status);

    // Update (through save)
    // No explicit update method - use save(comparison) for updates

    // Delete
    void delete(FileComparison comparison);
    void deleteById(Long id);

    // Query helpers
    long countByAccountId(Long accountId);
    List<FileComparison> findInProgressComparisons();  // For monitoring/timeout handling
}
```

---

## JPA Mappings

### FileComparisonEntity (Infrastructure Layer)

**Class**: `com.bitbi.dfm.comparison.infrastructure.persistence.FileComparisonEntity`

**Purpose**: JPA entity for persistence (separate from domain entity per DDD)

**Annotations**:
```java
@Entity
@Table(name = "file_comparisons")
@Getter
@NoArgsConstructor
public class FileComparisonEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "current_batch_id", nullable = false)
    private Long currentBatchId;

    @Column(name = "target_batch_id", nullable = false)
    private Long targetBatchId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ComparisonStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "total_files_compared", nullable = false)
    private Integer totalFilesCompared = 0;

    @Column(name = "files_changed", nullable = false)
    private Integer filesChanged = 0;

    @Column(name = "files_added", nullable = false)
    private Integer filesAdded = 0;

    @Column(name = "files_unchanged", nullable = false)
    private Integer filesUnchanged = 0;

    @Column(name = "total_change_size", nullable = false)
    private Long totalChangeSize = 0L;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @OneToMany(mappedBy = "comparisonId", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ComparisonResultEntity> results = new ArrayList<>();

    // Conversion methods
    public static FileComparisonEntity fromDomain(FileComparison domain) { ... }
    public FileComparison toDomain() { ... }
}
```

---

### ComparisonResultEntity (Infrastructure Layer)

**Class**: `com.bitbi.dfm.comparison.infrastructure.persistence.ComparisonResultEntity`

**Annotations**:
```java
@Entity
@Table(name = "comparison_results")
@Getter
@NoArgsConstructor
public class ComparisonResultEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "comparison_id", nullable = false)
    private Long comparisonId;

    @Column(name = "file_id", nullable = false)
    private Long fileId;

    @Column(name = "target_file_id")
    private Long targetFileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20)
    private ChangeType changeType;

    @Type(JsonBinaryType.class)
    @Column(name = "unified_diff", columnDefinition = "jsonb")
    private String unifiedDiff;  // Stored as JSON string

    @Column(name = "line_additions", nullable = false)
    private Integer lineAdditions = 0;

    @Column(name = "line_deletions", nullable = false)
    private Integer lineDeletions = 0;

    @Column(name = "change_size", nullable = false)
    private Long changeSize = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Conversion methods
    public static ComparisonResultEntity fromDomain(ComparisonResult domain) { ... }
    public ComparisonResult toDomain() { ... }
}
```

---

## Flyway Migration

**File**: `src/main/resources/db/migration/V009__create_file_comparison_tables.sql`

```sql
-- Migration V009: Create file comparison tables
-- Feature: File Diff Comparison Between Upload Sessions
-- Date: 2025-11-03

-- Table: file_comparisons
CREATE TABLE file_comparisons (
    id BIGSERIAL PRIMARY KEY,
    current_batch_id BIGINT NOT NULL REFERENCES batches(id) ON DELETE CASCADE,
    target_batch_id BIGINT NOT NULL REFERENCES batches(id) ON DELETE CASCADE,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    total_files_compared INTEGER NOT NULL DEFAULT 0,
    files_changed INTEGER NOT NULL DEFAULT 0,
    files_added INTEGER NOT NULL DEFAULT 0,
    files_unchanged INTEGER NOT NULL DEFAULT 0,
    total_change_size BIGINT NOT NULL DEFAULT 0,
    error_message VARCHAR(1000),
    CONSTRAINT chk_file_comparisons_status CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_file_comparisons_batch_ids CHECK (current_batch_id != target_batch_id),
    CONSTRAINT chk_file_comparisons_statistics CHECK (
        total_files_compared >= 0 AND
        files_changed >= 0 AND
        files_added >= 0 AND
        files_unchanged >= 0 AND
        (total_files_compared = files_changed + files_added + files_unchanged)
    )
);

-- Indexes for file_comparisons
CREATE INDEX idx_file_comparisons_account_id ON file_comparisons(account_id);
CREATE INDEX idx_file_comparisons_current_batch ON file_comparisons(current_batch_id);
CREATE INDEX idx_file_comparisons_target_batch ON file_comparisons(target_batch_id);
CREATE INDEX idx_file_comparisons_status ON file_comparisons(status);
CREATE INDEX idx_file_comparisons_created_at ON file_comparisons(created_at DESC);
CREATE INDEX idx_file_comparisons_account_created ON file_comparisons(account_id, created_at DESC);

-- Table: comparison_results
CREATE TABLE comparison_results (
    id BIGSERIAL PRIMARY KEY,
    comparison_id BIGINT NOT NULL REFERENCES file_comparisons(id) ON DELETE CASCADE,
    file_id BIGINT NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    target_file_id BIGINT REFERENCES files(id) ON DELETE CASCADE,
    change_type VARCHAR(20) NOT NULL,
    unified_diff JSONB,
    line_additions INTEGER NOT NULL DEFAULT 0,
    line_deletions INTEGER NOT NULL DEFAULT 0,
    change_size BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_comparison_results_change_type CHECK (change_type IN ('ADDED', 'MODIFIED', 'UNCHANGED', 'REMOVED')),
    CONSTRAINT chk_comparison_results_target_file CHECK (
        (change_type = 'ADDED' AND target_file_id IS NULL) OR
        (change_type != 'ADDED' AND target_file_id IS NOT NULL)
    ),
    CONSTRAINT chk_comparison_results_line_counts CHECK (line_additions >= 0 AND line_deletions >= 0)
);

-- Indexes for comparison_results
CREATE INDEX idx_comparison_results_comparison_id ON comparison_results(comparison_id);
CREATE INDEX idx_comparison_results_file_id ON comparison_results(file_id);
CREATE INDEX idx_comparison_results_change_type ON comparison_results(change_type);
CREATE INDEX idx_comparison_results_comparison_change ON comparison_results(comparison_id, change_type);
CREATE INDEX idx_comparison_results_diff ON comparison_results USING GIN(unified_diff);

-- Comments for documentation
COMMENT ON TABLE file_comparisons IS 'Stores metadata for file comparison operations between upload sessions (batches)';
COMMENT ON TABLE comparison_results IS 'Stores individual file diff results for each comparison operation';
COMMENT ON COLUMN file_comparisons.status IS 'Lifecycle state: PENDING, IN_PROGRESS, COMPLETED, FAILED';
COMMENT ON COLUMN comparison_results.change_type IS 'Type of change: ADDED, MODIFIED, UNCHANGED, REMOVED';
COMMENT ON COLUMN comparison_results.unified_diff IS 'Diff output stored as structured JSONB for queryability';
```

---

## Data Validation Rules

### Application Layer Validation

**Before Creating Comparison**:
1. Verify both batches exist and belong to the same account
2. Verify both batches have files (uploadedFilesCount > 0) *(Updated 2025-11-03: Removed COMPLETED status requirement)*
3. Verify currentBatchId != targetBatchId
4. Verify selected files exist in current batch
5. Verify user has permission to access both batches (JWT accountId matches batch.accountId)

**Before Adding Comparison Result**:
1. Verify comparison status = IN_PROGRESS or PENDING
2. Verify file belongs to current batch
3. Verify target file (if provided) belongs to target batch
4. Verify changeType matches file presence (ADDED requires no target file)

**Before Deleting Comparison**:
1. Verify comparison.canDelete() returns true (status != IN_PROGRESS)
2. Verify user has permission (JWT accountId matches comparison.accountId)

### Database Integrity

**Referential Integrity**:
- All foreign keys have CASCADE delete to maintain consistency
- Deleting a batch deletes all comparisons referencing it
- Deleting a file deletes all comparison results referencing it
- Deleting an account deletes all comparisons owned by it

**Check Constraints**:
- Status values validated at database level
- Statistics consistency enforced (total = changed + added + unchanged)
- Line counts must be non-negative
- Target file ID rules enforced based on change type

---

## Performance Considerations

### Query Optimization

**Common Queries**:
1. List comparisons for account (paginated, ordered by date):
   - Index: `idx_file_comparisons_account_created`
   - Expected performance: <50ms for 1000+ comparisons

2. Get comparison with results:
   - Use JOIN FETCH to prevent N+1 queries
   - Lazy load results collection by default

3. Filter comparison results by change type:
   - Index: `idx_comparison_results_comparison_change`
   - Expected performance: <10ms for 1000+ results

**Pagination**:
- Use cursor-based pagination for large result sets (per Upload History pattern)
- Use Spring Data JPA Pageable for simpler queries

### Data Volume Estimation

**Assumptions**:
- Average account: 100 upload sessions per year
- Average comparison: 50 files
- Average diff size: 10KB per file
- Retention: 5 years

**Storage Estimates**:
- file_comparisons row: ~200 bytes
- comparison_results row: ~10KB (including JSONB diff)
- Per account per year: 100 comparisons × 50 files × 10KB = 50MB
- 1000 accounts over 5 years: 50MB × 1000 × 5 = 250GB

**Index Overhead**: ~20% additional storage for indexes = 50GB

**Total Storage**: ~300GB for 1000 accounts over 5 years (acceptable for PostgreSQL)

---

## Migration Strategy

### Phase 1: Schema Creation (V009 migration)
1. Create file_comparisons table
2. Create comparison_results table
3. Create indexes
4. Add comments

### Phase 2: Data Migration (if needed in future)
- No migration needed (new feature, no existing data)
- If extending existing feature, use SQL script to populate from old schema

### Rollback Strategy
1. Drop tables in reverse order (comparison_results, then file_comparisons)
2. CASCADE will handle foreign key cleanup
3. No impact on existing features (new isolated domain)

---

## Summary

This data model provides:

✅ **DDD Compliance**: Clear aggregate boundaries with FileComparison as root
✅ **Database Integrity**: Strong constraints and referential integrity
✅ **Performance**: Strategic indexes for common query patterns
✅ **Scalability**: Efficient storage with JSONB for structured diff data
✅ **Maintainability**: Clear relationships and separation of concerns (domain vs infrastructure)
✅ **Security**: Account-level isolation enforced at database and application layers

Ready to proceed to Phase 1: API Contracts generation.
