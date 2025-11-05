# Quick Start: File Diff Comparison Between Upload Sessions

**Feature**: File Diff Comparison Between Upload Sessions
**Date**: 2025-11-03
**Branch**: `009-markdown-user-story`

## Overview

This guide provides a quick start for understanding and implementing the file diff comparison feature. It covers the essential concepts, architecture, and development workflow.

---

## Architecture Overview

### High-Level Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                         User Workflow                            │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌──────────────────┐    1. Select Files     ┌──────────────────┐
│  Upload Session  │◄─────────────────────►│  Frontend (React)│
│  (Current Batch) │                        │   - File List    │
└──────────────────┘                        │   - Selection UI │
         │                                  └─────────┬────────┘
         │                                            │
         │ 2. Create Comparison                       │
         │    POST /api/v1/comparisons                │
         │                                            │
         ▼                                            ▼
┌──────────────────────────────────────────────────────────────────┐
│                    Backend (Spring Boot)                         │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  ComparisonController (Presentation Layer)                 │ │
│  │  - Validate JWT (accountId)                                │ │
│  │  - Validate request DTO                                    │ │
│  └────────────┬───────────────────────────────────────────────┘ │
│               │                                                  │
│               ▼                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  ComparisonService (Application Layer)                     │ │
│  │  - Verify batch ownership                                  │ │
│  │  - Create FileComparison aggregate                         │ │
│  │  - Orchestrate diff generation                             │ │
│  └────────────┬───────────────────────────────────────────────┘ │
│               │                                                  │
│               ▼                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  DiffService (Domain Service)                              │ │
│  │  - Fetch file contents from S3                             │ │
│  │  - Run Myers diff algorithm (java-diff-utils)              │ │
│  │  - Generate unified diff output                            │ │
│  │  - Create ComparisonResult value objects                   │ │
│  └────────────┬───────────────────────────────────────────────┘ │
│               │                                                  │
│               ▼                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  JpaComparisonRepository (Infrastructure Layer)            │ │
│  │  - Persist FileComparison aggregate                        │ │
│  │  - Persist ComparisonResult entities                       │ │
│  └────────────┬───────────────────────────────────────────────┘ │
│               │                                                  │
└───────────────┼──────────────────────────────────────────────────┘
                │
                ▼
┌────────────────────────────────────────────┐
│         PostgreSQL Database                │
│  - file_comparisons table                  │
│  - comparison_results table (JSONB diffs)  │
└────────────────────────────────────────────┘
                │
                ▼
┌────────────────────────────────────────────┐
│  Frontend (React)                          │
│  - Poll for completion or use SSE          │
│  - Display results in diff viewer          │
│  - Show summary statistics                 │
└────────────────────────────────────────────┘
```

### Key Components

**Backend**:
- `FileComparison` - Aggregate root managing comparison lifecycle
- `ComparisonResult` - Value object representing individual file diff
- `DiffService` - Domain service implementing Myers diff algorithm
- `ComparisonService` - Application service orchestrating workflows
- `ComparisonController` - REST API endpoints

**Frontend**:
- `FileSelector` - Component for selecting files to compare
- `DiffViewer` - Component for visualizing diff results (react-diff-viewer-continued)
- `ComparisonSummary` - Component displaying summary statistics
- `useComparisons` - TanStack Query hook for API calls
- `useCreateComparison` - TanStack Query mutation for creating comparisons

---

## Domain Model Quick Reference

### Aggregate: FileComparison

```java
public class FileComparison {
    private Long id;
    private Long currentBatchId;
    private Long targetBatchId;
    private Long accountId;
    private ComparisonStatus status;  // PENDING, IN_PROGRESS, COMPLETED, FAILED
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
    private Integer totalFilesCompared;
    private Integer filesChanged;
    private Integer filesAdded;
    private Integer filesUnchanged;
    private Long totalChangeSize;
    private String errorMessage;
    private List<ComparisonResult> results;

    // Business methods
    public void startComparison() { ... }
    public void completeComparison() { ... }
    public void failComparison(String errorMessage) { ... }
    public void addResult(ComparisonResult result) { ... }
    public boolean canDelete() { ... }
    public ComparisonSummary getSummary() { ... }
}
```

### Value Object: ComparisonResult

```java
public class ComparisonResult {
    private Long id;
    private Long comparisonId;
    private Long fileId;
    private Long targetFileId;  // Nullable for new files
    private ChangeType changeType;  // ADDED, MODIFIED, UNCHANGED
    private String unifiedDiff;  // JSONB structure
    private Integer lineAdditions;
    private Integer lineDeletions;
    private Long changeSize;
    private Instant createdAt;

    public boolean hasChanges() { return changeType == MODIFIED || changeType == ADDED; }
    public String getDiffSummary() { return "+" + lineAdditions + " -" + lineDeletions; }
}
```

---

## API Quick Reference

### Create Comparison

```bash
POST /api/v1/comparisons
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json

{
  "currentBatchId": 123,
  "targetBatchId": 120,
  "fileIds": [501, 502, 503]  // Optional: null = compare all files
}

# Response (201 Created)
{
  "id": 45,
  "currentBatchId": 123,
  "targetBatchId": 120,
  "accountId": 10,
  "status": "IN_PROGRESS",
  "createdAt": "2025-11-03T10:30:00Z",
  "startedAt": "2025-11-03T10:30:01Z",
  "totalFilesCompared": 0,
  "filesChanged": 0,
  "filesAdded": 0,
  "filesUnchanged": 0,
  "totalChangeSize": 0
}
```

### List Comparisons

```bash
GET /api/v1/comparisons?page=0&size=20&status=COMPLETED
Authorization: Bearer <JWT_TOKEN>

# Response (200 OK)
{
  "content": [
    {
      "id": 45,
      "currentBatchId": 123,
      "targetBatchId": 120,
      "status": "COMPLETED",
      "totalFilesCompared": 50,
      "filesChanged": 12,
      "filesAdded": 3,
      "filesUnchanged": 35,
      "totalChangeSize": 125000,
      "completedAt": "2025-11-03T10:31:25Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 25,
  "totalPages": 2
}
```

### Get Comparison Results

```bash
GET /api/v1/comparisons/45/results?changeType=MODIFIED&page=0&size=50
Authorization: Bearer <JWT_TOKEN>

# Response (200 OK)
{
  "content": [
    {
      "id": 1001,
      "comparisonId": 45,
      "fileId": 501,
      "targetFileId": 450,
      "fileName": "data.csv",
      "changeType": "MODIFIED",
      "lineAdditions": 25,
      "lineDeletions": 10,
      "changeSize": 1500,
      "unifiedDiff": {
        "hunks": [
          {
            "oldStart": 1,
            "oldLines": 10,
            "newStart": 1,
            "newLines": 12,
            "changes": [
              {"type": "UNCHANGED", "lineNumber": 1, "content": "header1,header2"},
              {"type": "REMOVED", "lineNumber": 2, "content": "old_value1,old_value2"},
              {"type": "ADDED", "lineNumber": 2, "content": "new_value1,new_value2"}
            ]
          }
        ]
      }
    }
  ],
  "page": 0,
  "size": 50,
  "totalElements": 15,
  "totalPages": 1
}
```

### Get Comparison by ID

```bash
GET /api/v1/comparisons/45
Authorization: Bearer <JWT_TOKEN>

# Response (200 OK)
{
  "id": 45,
  "currentBatchId": 123,
  "targetBatchId": 120,
  "accountId": 10,
  "status": "COMPLETED",
  "createdAt": "2025-11-03T10:30:00Z",
  "startedAt": "2025-11-03T10:30:01Z",
  "completedAt": "2025-11-03T10:31:25Z",
  "totalFilesCompared": 50,
  "filesChanged": 12,
  "filesAdded": 3,
  "filesUnchanged": 35,
  "totalChangeSize": 125000
}
```

### Get Summary Report

```bash
GET /api/v1/comparisons/45/summary
Authorization: Bearer <JWT_TOKEN>

# Response (200 OK)
{
  "totalFilesCompared": 50,
  "filesChanged": 12,
  "filesAdded": 3,
  "filesUnchanged": 35,
  "totalChangeSize": 125000,
  "comparisonTimestamp": "2025-11-03T10:31:25Z",
  "currentBatchId": 123,
  "targetBatchId": 120
}
```

### Download Comparison as ZIP

```bash
GET /api/v1/comparisons/45/download
Authorization: Bearer <JWT_TOKEN>

# Response (200 OK)
Content-Type: application/zip
Content-Disposition: attachment; filename="comparison-45.zip"

# ZIP archive contains:
# - data.csv.diff (unified diff format)
# - config.json.diff
# - summary.txt (human-readable report)
```

### Download Summary Report

```bash
GET /api/v1/comparisons/45/summary/download
Authorization: Bearer <JWT_TOKEN>

# Response (200 OK)
Content-Type: text/plain
Content-Disposition: attachment; filename="comparison-45-summary.txt"

File Comparison Summary Report
==============================
Comparison ID: 45
Current Batch: 123
Target Batch: 120
Timestamp: 2025-11-03T10:31:25Z

Statistics:
-----------
Total Files Compared: 50
Files Changed: 12
Files Added: 3
Files Unchanged: 35
Total Change Size: 125000 bytes
```

### Delete Comparison

```bash
DELETE /api/v1/comparisons/45
Authorization: Bearer <JWT_TOKEN>

# Response (204 No Content)
```

---

## Development Workflow

### 1. Prerequisites

Before starting implementation, ensure:
- [ ] PostgreSQL 16 is running
- [ ] LocalStack (S3) is running for development
- [ ] JWT authentication is configured
- [ ] Existing Upload History feature (Spec 008) is working

### 2. Phase 1: Database Setup

**Create Flyway Migration** (`V009__create_file_comparison_tables.sql`):
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
    CONSTRAINT chk_batch_ids CHECK (current_batch_id != target_batch_id)
);

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
    CONSTRAINT chk_change_type CHECK (change_type IN ('ADDED', 'MODIFIED', 'UNCHANGED'))
);

-- Add indexes (see data-model.md for full list)
```

**Run Migration**:
```bash
./gradlew flywayMigrate
```

### 3. Phase 2: Backend Implementation (TDD)

**Step 1: Write Contract Tests** (`ComparisonContractTest.java`):
```java
@WebMvcTest(ComparisonController.class)
class ComparisonContractTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ComparisonService comparisonService;

    @Test
    void shouldCreateComparisonSuccessfully() throws Exception {
        // Arrange
        CreateComparisonRequestDto request = new CreateComparisonRequestDto(123L, 120L, null);
        ComparisonResponseDto response = new ComparisonResponseDto(/*...*/);
        when(comparisonService.createComparison(any(), anyLong())).thenReturn(response);

        // Act & Assert
        mockMvc.perform(post("/api/v1/comparisons")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("Authorization", "Bearer " + validJwtToken))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(45))
            .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    // ... more tests
}
```

**Step 2: Implement Domain Layer**:
```bash
src/main/java/com/bitbi/dfm/comparison/
├── domain/
│   ├── FileComparison.java          # Aggregate root
│   ├── ComparisonResult.java        # Value object
│   ├── ComparisonStatus.java        # Enum
│   ├── ChangeType.java              # Enum
│   ├── ComparisonSummary.java       # Value object
│   ├── DiffService.java             # Domain service interface
│   └── ComparisonRepository.java    # Repository interface
```

**Step 3: Implement Infrastructure Layer**:
```bash
src/main/java/com/bitbi/dfm/comparison/infrastructure/
├── JpaComparisonRepository.java     # Repository implementation
├── DiffServiceImpl.java             # Diff algorithm implementation
├── S3FileContentService.java        # Fetch file contents from S3
└── persistence/
    ├── FileComparisonEntity.java    # JPA entity
    └── ComparisonResultEntity.java  # JPA entity
```

**Step 4: Implement Application Layer**:
```bash
src/main/java/com/bitbi/dfm/comparison/application/
├── ComparisonService.java           # Workflow orchestration
├── ComparisonQueryService.java      # Read-side queries
└── events/
    ├── ComparisonStartedEvent.java
    └── ComparisonCompletedEvent.java
```

**Step 5: Implement Presentation Layer**:
```bash
src/main/java/com/bitbi/dfm/comparison/presentation/
├── ComparisonController.java        # REST endpoints
└── dto/
    ├── CreateComparisonRequestDto.java
    ├── ComparisonResponseDto.java
    ├── ComparisonResultDto.java
    └── ComparisonSummaryDto.java
```

**Step 6: Write Integration Tests** (`ComparisonIntegrationTest.java`):
```java
@SpringBootTest
@Testcontainers
class ComparisonIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(...)
        .withServices(S3);

    @Test
    void shouldCreateAndCompleteComparisonEndToEnd() {
        // Arrange: Create batches, upload files to LocalStack S3
        // Act: POST /api/v1/comparisons
        // Assert: Verify comparison completed, results persisted, diff accurate
    }
}
```

### 4. Phase 3: Frontend Implementation (TDD)

**Step 1: Add Dependencies** (`package.json`):
```json
{
  "dependencies": {
    "react-diff-viewer-continued": "^3.3.1"
  }
}
```

**Step 2: Create API Client** (`features/file-comparison/api/comparisonApi.ts`):
```typescript
import { apiClient } from '@/shared/api/client';
import { CreateComparisonRequest, ComparisonResponse } from '../model/types';

export const comparisonApi = {
  createComparison: async (request: CreateComparisonRequest): Promise<ComparisonResponse> => {
    const response = await apiClient.post('/comparisons', request);
    return response.data;
  },

  listComparisons: async (page = 0, size = 20) => {
    const response = await apiClient.get('/comparisons', { params: { page, size } });
    return response.data;
  },

  getComparisonResults: async (comparisonId: number, changeType?: string) => {
    const response = await apiClient.get(`/comparisons/${comparisonId}/results`, {
      params: { changeType }
    });
    return response.data;
  },

  deleteComparison: async (comparisonId: number) => {
    await apiClient.delete(`/comparisons/${comparisonId}`);
  }
};
```

**Step 3: Create TanStack Query Hooks** (`features/file-comparison/hooks/`):
```typescript
// useComparisons.ts
export const useComparisons = (page = 0, size = 20) => {
  return useQuery({
    queryKey: ['comparisons', page, size],
    queryFn: () => comparisonApi.listComparisons(page, size),
    staleTime: 30000  // 30 seconds
  });
};

// useCreateComparison.ts
export const useCreateComparison = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: comparisonApi.createComparison,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['comparisons'] });
    }
  });
};
```

**Step 4: Create Components** (`features/file-comparison/ui/`):
```typescript
// FileSelector.tsx
export const FileSelector = ({ batchId, onSelectionChange }: FileSelectorProps) => {
  const [selectedFiles, setSelectedFiles] = useState<number[]>([]);

  // ... file selection logic

  return (
    <div>
      <Button onClick={() => selectAll()}>Select All</Button>
      <FileList files={files} selectedIds={selectedFiles} onToggle={toggleFile} />
    </div>
  );
};

// DiffViewer.tsx
import ReactDiffViewer from 'react-diff-viewer-continued';

export const DiffViewer = ({ result }: DiffViewerProps) => {
  const { oldValue, newValue } = parseDiff(result.unifiedDiff);

  return (
    <ReactDiffViewer
      oldValue={oldValue}
      newValue={newValue}
      splitView={true}
      useDarkTheme={false}
      leftTitle={result.targetFileName}
      rightTitle={result.fileName}
    />
  );
};
```

**Step 5: Write Tests** (`features/file-comparison/__tests__/`):
```typescript
// useComparisons.test.ts
import { renderHook, waitFor } from '@testing-library/react';
import { useComparisons } from '../hooks/useComparisons';
import { createWrapper } from '@/test-utils';

describe('useComparisons', () => {
  it('should fetch comparisons successfully', async () => {
    const { result } = renderHook(() => useComparisons(), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data.content).toHaveLength(2);
  });
});
```

### 5. Testing Checklist

**Backend**:
- [ ] Contract tests for all endpoints (7 tests)
- [ ] Integration tests with Testcontainers (5 tests)
- [ ] Unit tests for DiffService (10 tests)
- [ ] Unit tests for FileComparison aggregate (8 tests)
- [ ] Coverage ≥80%

**Frontend**:
- [ ] Unit tests for hooks (4 tests)
- [ ] Component tests (6 tests)
- [ ] Integration test for full workflow (1 test)
- [ ] Coverage ≥80%

---

## Common Development Tasks

### Add New Diff Format Support

**Scenario**: Support CSV-specific diff with column-aware comparison

**Steps**:
1. Create new `CsvDiffService` implementing `DiffService` interface
2. Add strategy pattern to select diff service based on file type
3. Update `ComparisonService` to use strategy
4. Add tests for CSV-specific logic
5. Update OpenAPI contract if response format changes

### Add Async Processing for Large Comparisons

**Scenario**: Comparisons >100 files take too long (>2 minutes)

**Steps**:
1. Add `@Async` annotation to `ComparisonService.createComparison()`
2. Return `CompletableFuture<ComparisonResponseDto>`
3. Add polling endpoint: `GET /api/v1/comparisons/{id}/status`
4. Update frontend to poll for completion instead of waiting
5. Add timeout handling (e.g., 10 minutes max)

### Add Comparison Caching

**Scenario**: Users frequently compare same batch pairs

**Steps**:
1. Add `@Cacheable` annotation to `ComparisonQueryService.findById()`
2. Configure Spring Cache with Redis
3. Add cache eviction on comparison deletion
4. Add cache TTL (e.g., 1 hour)
5. Monitor cache hit rate with Micrometer

---

## Troubleshooting

### Comparison Fails with "S3 Access Denied"

**Cause**: Missing IAM permissions for S3 bucket access

**Solution**:
```bash
# Check LocalStack S3 bucket exists
aws --endpoint-url=http://localhost:4566 s3 ls s3://dataforge-uploads

# Verify file exists
aws --endpoint-url=http://localhost:4566 s3 ls s3://dataforge-uploads/{accountId}/{domain}/{date}/
```

### Diff Output Shows Incorrect Line Numbers

**Cause**: File encoding mismatch (e.g., UTF-8 vs Windows-1252)

**Solution**:
```java
// Use EncodingDetectionService from Upload History feature
String encoding = encodingDetectionService.detect(fileBytes);
List<String> lines = Files.readAllLines(path, Charset.forName(encoding));
```

### Frontend Diff Viewer Hangs on Large Files

**Cause**: react-diff-viewer-continued not optimized for 10K+ lines

**Solution**:
```typescript
// Add virtualization with react-window
import { FixedSizeList } from 'react-window';

// Render only visible portion of diff
<FixedSizeList
  height={600}
  itemCount={diffLines.length}
  itemSize={20}
  width="100%"
>
  {DiffRow}
</FixedSizeList>
```

### Database Query Timeout on Large Result Sets

**Cause**: Missing index on `comparison_id` in `comparison_results`

**Solution**:
```sql
-- Verify index exists
\d comparison_results

-- Create index if missing
CREATE INDEX idx_comparison_results_comparison_id ON comparison_results(comparison_id);
```

---

## Performance Tuning

### Backend Optimization

**Query Optimization**:
```java
// Use JOIN FETCH to prevent N+1 queries
@Query("SELECT c FROM FileComparisonEntity c LEFT JOIN FETCH c.results WHERE c.id = :id")
Optional<FileComparisonEntity> findByIdWithResults(@Param("id") Long id);
```

**Async Processing**:
```java
@Async("comparisonExecutor")
public CompletableFuture<ComparisonResponseDto> createComparison(...) {
    // Process asynchronously
}
```

**Connection Pooling**:
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
```

### Frontend Optimization

**Code Splitting**:
```typescript
// Lazy load diff viewer
const DiffViewer = lazy(() => import('./DiffViewer'));

// Use Suspense
<Suspense fallback={<Spinner />}>
  <DiffViewer result={result} />
</Suspense>
```

**Memoization**:
```typescript
// Memoize expensive diff parsing
const parsedDiff = useMemo(() => parseDiff(result.unifiedDiff), [result.unifiedDiff]);
```

**Debouncing**:
```typescript
// Debounce file search
const debouncedSearch = useDebouncedValue(searchTerm, 300);
```

---

## Monitoring & Observability

### Metrics

**Backend** (Micrometer):
```java
@Timed(value = "comparison.duration", description = "Time to complete comparison")
public ComparisonResponseDto createComparison(...) { ... }

@Counted(value = "comparison.created", description = "Number of comparisons created")
public void completeComparison() { ... }
```

**Frontend** (Custom Hook):
```typescript
// Track comparison creation timing
const { mutate, data, error, isLoading } = useCreateComparison({
  onSuccess: (data) => {
    analytics.track('comparison_created', {
      duration: Date.now() - startTime,
      fileCount: data.totalFilesCompared
    });
  }
});
```

### Logging

**Backend** (SLF4J + MDC):
```java
MDC.put("comparisonId", comparison.getId().toString());
log.info("Starting comparison: currentBatch={}, targetBatch={}", currentBatchId, targetBatchId);
// ... processing
log.info("Comparison completed: filesCompared={}, filesChanged={}", total, changed);
MDC.remove("comparisonId");
```

**Frontend** (Console in development only):
```typescript
if (process.env.NODE_ENV === 'development') {
  console.log('[Comparison] Creating comparison:', request);
}
```

---

## Next Steps

After completing this quick start:

1. **Read Full Specifications**:
   - [spec.md](./spec.md) - Feature requirements
   - [data-model.md](./data-model.md) - Database schema
   - [research.md](./research.md) - Technology decisions

2. **Review API Contracts**:
   - [comparison-api.yaml](./contracts/comparison-api.yaml) - OpenAPI specification

3. **Generate Tasks**:
   ```bash
   /speckit.tasks
   ```
   This will generate `tasks.md` with step-by-step implementation tasks.

4. **Start Implementation**:
   - Follow TDD workflow (tests first)
   - Implement backend (Phase 1-3 in tasks.md)
   - Implement frontend (Phase 4-6 in tasks.md)
   - Verify coverage ≥80%

---

## Additional Resources

### Java Diff Libraries
- [java-diff-utils Documentation](https://github.com/java-diff-utils/java-diff-utils)
- [Myers Diff Algorithm Paper](http://www.xmailserver.org/diff2.pdf)

### React Diff Viewer
- [react-diff-viewer-continued GitHub](https://github.com/otakustay/react-diff-viewer)
- [Prism.js Syntax Highlighting](https://prismjs.com/)

### Testing
- [Testcontainers Documentation](https://www.testcontainers.org/)
- [Testing Library Best Practices](https://testing-library.com/docs/react-testing-library/intro/)

### Architecture
- [Domain-Driven Design Reference](https://www.domainlanguage.com/ddd/reference/)
- [Feature-Sliced Design](https://feature-sliced.design/)
