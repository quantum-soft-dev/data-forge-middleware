# Quickstart: Upload History Feature

## Overview

This guide helps developers understand and implement the Upload History feature, which allows users to view their upload history, download files, and generate Excel exports from CSV files.

**Feature ID**: 008-upload-history-user
**Branch**: `008-upload-history-user`
**Status**: Planning Complete - Ready for Implementation

---

## Prerequisites

- Java 21 installed
- Spring Boot 3.5.6 knowledge
- React 19.2 + TypeScript familiarity
- AWS S3 access configured
- PostgreSQL 16 database running
- Keycloak authentication setup (for testing)

---

## Architecture Summary

### Backend (Spring Boot)

**Package Structure** (Package by Layered Feature - PbLF):
```
src/main/java/com/bitbi/dfm/batch/
├── domain/
│   ├── Batch.java                    # Existing aggregate root
│   └── BatchRepository.java           # Existing domain interface
├── application/
│   └── BatchHistoryService.java       # NEW - Upload history business logic
├── infrastructure/
│   ├── JpaBatchRepository.java        # Existing (extend with new queries)
│   └── S3PresignedUrlService.java     # NEW - S3 presigned URL generation
└── presentation/
    ├── BatchHistoryController.java    # NEW - REST endpoints
    └── dto/
        ├── BatchSummaryDto.java       # NEW - List view DTO
        ├── BatchDetailDto.java        # NEW - Detail view DTO
        ├── FileMetadataDto.java       # NEW - File list item DTO
        ├── FileDownloadResponseDto.java # NEW - Download URL response
        └── CursorPageResponseDto.java  # NEW - Pagination wrapper

src/main/java/com/bitbi/dfm/upload/
├── domain/
│   ├── UploadedFile.java              # Existing entity
│   └── UploadedFileRepository.java    # Existing (no changes needed)
└── application/
    ├── FileDownloadService.java       # NEW - File download orchestration
    └── ExcelExportService.java        # NEW - Excel generation
```

**Key Design Patterns**:
- **DDD**: Batch is aggregate root, UploadedFile is part of aggregate
- **DTO Projection**: Avoid N+1 queries with `BatchWithFileCountProjection`
- **Cursor Pagination**: O(1) performance for large datasets
- **Streaming**: ZIP and Excel generation stream to HTTP response

### Frontend (React + TypeScript)

**Package Structure** (Feature-Sliced Design - FSD):
```
frontend/src/
├── entities/
│   └── batch/
│       ├── model/
│       │   └── types.ts              # Batch, File type definitions
│       └── api/
│           ├── batchApi.ts           # API client functions
│           └── queries.ts            # TanStack Query hooks
├── features/
│   └── upload-history/
│       ├── ui/
│       │   ├── BatchListView.tsx     # List view component
│       │   ├── BatchDetailView.tsx   # Detail view component
│       │   ├── FileTable.tsx         # File selection table
│       │   └── DownloadButton.tsx    # Download/Excel action buttons
│       └── lib/
│           ├── useBatchList.ts       # Cursor pagination hook
│           ├── useFileDownload.ts    # Download mutation
│           └── useExcelExport.ts     # Excel export mutation
└── pages/
    └── UploadHistoryPage.tsx         # Route page component
```

---

## Implementation Steps

### Phase 1: Backend Setup (P1 - View Upload History List)

#### Step 1: Add Dependencies

**File**: `build.gradle.kts`

```kotlin
dependencies {
    // Excel generation
    implementation("org.apache.poi:poi-ooxml:5.3.0")

    // CSV parsing
    implementation("org.apache.commons:commons-csv:1.12.0")

    // Compression (ZIP + Gzip)
    implementation("org.apache.commons:commons-compress:1.28.0")

    // Encoding detection
    implementation("com.ibm.icu:icu4j:76.1")

    // Caching (if not already present)
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-cache")
}
```

#### Step 2: Create DTOs

**File**: `src/main/java/com/bitbi/dfm/batch/presentation/dto/BatchSummaryDto.java`

```java
public record BatchSummaryDto(
    UUID id,
    UUID siteId,
    String status,
    boolean hasErrors,
    int uploadedFilesCount,
    long totalSize,
    LocalDateTime startedAt,
    LocalDateTime completedAt
) {
    public static BatchSummaryDto fromEntity(Batch batch) {
        return new BatchSummaryDto(
            batch.getId(),
            batch.getSiteId(),
            batch.getStatus().name(),
            batch.getHasErrors(),
            batch.getUploadedFilesCount(),
            batch.getTotalSize(),
            batch.getStartedAt(),
            batch.getCompletedAt()
        );
    }

    public static BatchSummaryDto fromProjection(BatchWithFileCountProjection projection) {
        return new BatchSummaryDto(
            projection.getId(),
            projection.getSiteId(),
            projection.getStatus(),
            projection.getHasErrors(),
            projection.getFileCount(),
            projection.getTotalSize(),
            projection.getStartedAt(),
            projection.getCompletedAt()
        );
    }
}
```

#### Step 3: Add Repository Queries

**File**: `src/main/java/com/bitbi/dfm/batch/infrastructure/JpaBatchRepository.java`

```java
public interface BatchWithFileCountProjection {
    UUID getId();
    UUID getSiteId();
    String getStatus();
    Boolean getHasErrors();
    LocalDateTime getStartedAt();
    LocalDateTime getCompletedAt();

    @Value("#{target.uploadedFilesCount}")
    Integer getFileCount();

    @Value("#{target.totalSize}")
    Long getTotalSize();
}

@Repository
public interface JpaBatchRepository extends JpaRepository<Batch, UUID> {

    // First page (no cursor)
    @Query("""
        SELECT b.id as id, b.siteId as siteId, b.status as status,
               b.hasErrors as hasErrors, b.startedAt as startedAt,
               b.completedAt as completedAt, b.uploadedFilesCount as fileCount,
               b.totalSize as totalSize
        FROM Batch b
        WHERE b.siteId IN :siteIds
        ORDER BY b.startedAt DESC, b.id DESC
        """)
    List<BatchWithFileCountProjection> findBySiteIdsFirstPage(
        @Param("siteIds") List<UUID> siteIds,
        Pageable pageable
    );

    // Subsequent pages (with cursor)
    @Query("""
        SELECT b.id as id, b.siteId as siteId, b.status as status,
               b.hasErrors as hasErrors, b.startedAt as startedAt,
               b.completedAt as completedAt, b.uploadedFilesCount as fileCount,
               b.totalSize as totalSize
        FROM Batch b
        WHERE b.siteId IN :siteIds
          AND (b.startedAt < :cursor OR (b.startedAt = :cursor AND b.id < :cursorId))
        ORDER BY b.startedAt DESC, b.id DESC
        """)
    List<BatchWithFileCountProjection> findBySiteIdsWithCursor(
        @Param("siteIds") List<UUID> siteIds,
        @Param("cursor") LocalDateTime cursor,
        @Param("cursorId") UUID cursorId,
        Pageable pageable
    );
}
```

#### Step 4: Create Service Layer

**File**: `src/main/java/com/bitbi/dfm/batch/application/BatchHistoryService.java`

```java
@Service
public class BatchHistoryService {

    private final BatchRepository batchRepository;
    private final SiteRepository siteRepository;

    public BatchHistoryService(BatchRepository batchRepository, SiteRepository siteRepository) {
        this.batchRepository = batchRepository;
        this.siteRepository = siteRepository;
    }

    public CursorPageResponseDto<BatchSummaryDto> listBatchHistory(
        UUID accountId,
        String cursor,
        int limit
    ) {
        // Get all site IDs for this account
        List<UUID> siteIds = siteRepository.findByAccountId(accountId).stream()
            .map(Site::getId)
            .toList();

        if (siteIds.isEmpty()) {
            return new CursorPageResponseDto<>(List.of(), null, false);
        }

        // Fetch batches with cursor pagination
        List<BatchWithFileCountProjection> batches;
        if (cursor == null) {
            batches = batchRepository.findBySiteIdsFirstPage(
                siteIds,
                PageRequest.of(0, limit + 1)
            );
        } else {
            String[] parts = cursor.split("_");
            LocalDateTime cursorTime = LocalDateTime.parse(parts[0]);
            UUID cursorId = UUID.fromString(parts[1]);

            batches = batchRepository.findBySiteIdsWithCursor(
                siteIds,
                cursorTime,
                cursorId,
                PageRequest.of(0, limit + 1)
            );
        }

        // Check if more results exist
        boolean hasNext = batches.size() > limit;
        List<BatchSummaryDto> items = batches.stream()
            .limit(limit)
            .map(BatchSummaryDto::fromProjection)
            .toList();

        // Generate next cursor
        String nextCursor = hasNext && !items.isEmpty()
            ? items.get(items.size() - 1).startedAt() + "_" + items.get(items.size() - 1).id()
            : null;

        return new CursorPageResponseDto<>(items, nextCursor, hasNext);
    }
}
```

#### Step 5: Create Controller

**File**: `src/main/java/com/bitbi/dfm/batch/presentation/BatchHistoryController.java`

```java
@RestController
@RequestMapping("/api/dfc/batches")
@Tag(name = "Upload History")
@SecurityRequirement(name = "bearerAuth")
public class BatchHistoryController {

    private final BatchHistoryService batchHistoryService;

    public BatchHistoryController(BatchHistoryService batchHistoryService) {
        this.batchHistoryService = batchHistoryService;
    }

    @GetMapping
    @Operation(summary = "List upload history (cursor-based pagination)")
    public ResponseEntity<CursorPageResponseDto<BatchSummaryDto>> listBatches(
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "20") int limit,
        Authentication authentication
    ) {
        UUID accountId = extractAccountId(authentication);

        CursorPageResponseDto<BatchSummaryDto> response =
            batchHistoryService.listBatchHistory(accountId, cursor, limit);

        return ResponseEntity.ok(response);
    }

    private UUID extractAccountId(Authentication authentication) {
        org.springframework.security.oauth2.jwt.Jwt jwt =
            (org.springframework.security.oauth2.jwt.Jwt) authentication.getPrincipal();
        return UUID.fromString(jwt.getSubject());
    }
}
```

---

### Phase 2: Frontend Setup (P1 - View Upload History List)

#### Step 1: Define Types

**File**: `frontend/src/entities/batch/model/types.ts`

```typescript
export interface BatchSummary {
  id: string;
  siteId: string;
  status: 'IN_PROGRESS' | 'COMPLETED' | 'NOT_COMPLETED' | 'FAILED' | 'CANCELLED';
  hasErrors: boolean;
  uploadedFilesCount: number;
  totalSize: number;
  startedAt: string;
  completedAt: string | null;
}

export interface CursorPageResponse<T> {
  items: T[];
  nextCursor: string | null;
  hasNext: boolean;
}
```

#### Step 2: Create API Client

**File**: `frontend/src/entities/batch/api/batchApi.ts`

```typescript
import { apiClient } from '@/shared/api/client';
import type { BatchSummary, CursorPageResponse } from '../model/types';

export async function listBatches(
  cursor?: string,
  limit: number = 20
): Promise<CursorPageResponse<BatchSummary>> {
  const params = new URLSearchParams();
  if (cursor) params.set('cursor', cursor);
  params.set('limit', limit.toString());

  const response = await apiClient.get<CursorPageResponse<BatchSummary>>(
    `/dfc/batches?${params.toString()}`
  );

  return response.data;
}
```

#### Step 3: Create TanStack Query Hook

**File**: `frontend/src/entities/batch/api/queries.ts`

```typescript
import { useInfiniteQuery } from '@tanstack/react-query';
import { listBatches } from './batchApi';

export function useBatchHistory(limit: number = 20) {
  return useInfiniteQuery({
    queryKey: ['batches', 'history', limit],
    queryFn: ({ pageParam }) => listBatches(pageParam, limit),
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    initialPageParam: undefined as string | undefined,
  });
}
```

#### Step 4: Create UI Component

**File**: `frontend/src/features/upload-history/ui/BatchListView.tsx`

```typescript
import { useBatchHistory } from '@/entities/batch/api/queries';
import { formatBytes, formatDateTime } from '@/shared/lib/formatters';
import { CheckCircle, XCircle, Loader2 } from 'lucide-react';
import { Button } from '@/shared/ui/ui/button';

export function BatchListView() {
  const {
    data,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
    isLoading,
    error,
  } = useBatchHistory();

  if (isLoading) {
    return <div>Loading upload history...</div>;
  }

  if (error) {
    return <div>Error loading upload history: {error.message}</div>;
  }

  const batches = data?.pages.flatMap((page) => page.items) ?? [];

  if (batches.length === 0) {
    return (
      <div className="text-center py-12 text-muted-foreground">
        <p>No uploads yet</p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {batches.map((batch) => (
        <div
          key={batch.id}
          className="border rounded-lg p-4 hover:bg-accent cursor-pointer"
          onClick={() => {/* Navigate to details */}}
        >
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              {batch.hasErrors ? (
                <XCircle className="h-6 w-6 text-destructive" />
              ) : (
                <CheckCircle className="h-6 w-6 text-green-600" />
              )}
              <div>
                <p className="font-medium">
                  {formatDateTime(batch.startedAt)}
                </p>
                <p className="text-sm text-muted-foreground">
                  {batch.uploadedFilesCount} files • {formatBytes(batch.totalSize)}
                </p>
              </div>
            </div>
            <div className="text-right">
              <span className="text-sm font-medium">{batch.status}</span>
              {batch.hasErrors && (
                <Button variant="link" size="sm">
                  View errors
                </Button>
              )}
            </div>
          </div>
        </div>
      ))}

      {hasNextPage && (
        <Button
          variant="outline"
          onClick={() => fetchNextPage()}
          disabled={isFetchingNextPage}
          className="w-full"
        >
          {isFetchingNextPage ? (
            <>
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              Loading more...
            </>
          ) : (
            'Load more'
          )}
        </Button>
      )}
    </div>
  );
}
```

---

## Testing Strategy

### Backend Tests (TDD)

#### 1. Contract Tests

**File**: `src/test/java/com/bitbi/dfm/contract/BatchHistoryContractTest.java`

```java
@WebMvcTest(BatchHistoryController.class)
class BatchHistoryContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BatchHistoryService batchHistoryService;

    @Test
    @DisplayName("TC01: Should return 200 and batch list for authenticated user")
    void shouldReturnBatchListForAuthenticatedUser() throws Exception {
        // Given
        UUID accountId = UUID.randomUUID();
        List<BatchSummaryDto> batches = List.of(
            new BatchSummaryDto(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "COMPLETED",
                false,
                10,
                1048576L,
                LocalDateTime.now(),
                LocalDateTime.now()
            )
        );
        CursorPageResponseDto<BatchSummaryDto> response =
            new CursorPageResponseDto<>(batches, null, false);

        when(batchHistoryService.listBatchHistory(eq(accountId), isNull(), eq(20)))
            .thenReturn(response);

        // When/Then
        mockMvc.perform(get("/api/dfc/batches")
                .header("Authorization", "Bearer " + generateMockJwt(accountId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.items[0].id").exists())
            .andExpect(jsonPath("$.nextCursor").value(nullValue()))
            .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("TC02: Should return empty list when no uploads exist")
    void shouldReturnEmptyListWhenNoUploads() throws Exception {
        // Given
        UUID accountId = UUID.randomUUID();
        CursorPageResponseDto<BatchSummaryDto> response =
            new CursorPageResponseDto<>(List.of(), null, false);

        when(batchHistoryService.listBatchHistory(eq(accountId), isNull(), eq(20)))
            .thenReturn(response);

        // When/Then
        mockMvc.perform(get("/api/dfc/batches")
                .header("Authorization", "Bearer " + generateMockJwt(accountId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty());
    }
}
```

#### 2. Integration Tests

**File**: `src/test/java/com/bitbi/dfm/integration/BatchHistoryIntegrationTest.java`

```java
@SpringBootTest
@Testcontainers
class BatchHistoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private BatchHistoryService batchHistoryService;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private SiteRepository siteRepository;

    @Test
    @DisplayName("Should return batches sorted by startedAt DESC")
    void shouldReturnBatchesSortedByDate() {
        // Given
        UUID accountId = UUID.randomUUID();
        Site site = createSite(accountId);

        Batch batch1 = createBatch(site.getId(), LocalDateTime.now().minusDays(2));
        Batch batch2 = createBatch(site.getId(), LocalDateTime.now().minusDays(1));
        Batch batch3 = createBatch(site.getId(), LocalDateTime.now());

        batchRepository.saveAll(List.of(batch1, batch2, batch3));

        // When
        CursorPageResponseDto<BatchSummaryDto> result =
            batchHistoryService.listBatchHistory(accountId, null, 10);

        // Then
        assertThat(result.items()).hasSize(3);
        assertThat(result.items().get(0).startedAt()).isAfter(result.items().get(1).startedAt());
        assertThat(result.items().get(1).startedAt()).isAfter(result.items().get(2).startedAt());
    }
}
```

### Frontend Tests (Vitest + Testing Library)

**File**: `frontend/src/features/upload-history/ui/BatchListView.test.tsx`

```typescript
import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BatchListView } from './BatchListView';
import * as batchApi from '@/entities/batch/api/batchApi';

describe('BatchListView', () => {
  it('should display batch list', async () => {
    // Given
    const mockBatches = {
      items: [
        {
          id: '123',
          siteId: '456',
          status: 'COMPLETED',
          hasErrors: false,
          uploadedFilesCount: 10,
          totalSize: 1048576,
          startedAt: '2025-11-01T10:30:00',
          completedAt: '2025-11-01T10:35:00',
        },
      ],
      nextCursor: null,
      hasNext: false,
    };

    vi.spyOn(batchApi, 'listBatches').mockResolvedValue(mockBatches);

    const queryClient = new QueryClient();

    // When
    render(
      <QueryClientProvider client={queryClient}>
        <BatchListView />
      </QueryClientProvider>
    );

    // Then
    await waitFor(() => {
      expect(screen.getByText(/10 files/)).toBeInTheDocument();
    });
  });

  it('should display empty state when no uploads', async () => {
    // Given
    vi.spyOn(batchApi, 'listBatches').mockResolvedValue({
      items: [],
      nextCursor: null,
      hasNext: false,
    });

    const queryClient = new QueryClient();

    // When
    render(
      <QueryClientProvider client={queryClient}>
        <BatchListView />
      </QueryClientProvider>
    );

    // Then
    await waitFor(() => {
      expect(screen.getByText(/No uploads yet/)).toBeInTheDocument();
    });
  });
});
```

---

## Performance Optimization Checklist

- [ ] Add composite index: `idx_batches_site_started_id`
- [ ] Enable Redis caching for first page
- [ ] Use DTO projections (avoid full entity loading)
- [ ] Implement cursor-based pagination (not OFFSET)
- [ ] Stream ZIP and Excel generation (no intermediate files)
- [ ] Use S3 presigned URLs (avoid server-side proxying)
- [ ] Monitor query performance with Micrometer metrics

---

## Common Pitfalls

1. **N+1 Query Problem**: Always use JOIN FETCH or DTO projections
2. **Pagination with OFFSET**: Use cursor-based for large datasets
3. **Memory Issues in Excel Export**: Always use SXSSF (streaming) not XSSF
4. **Double Compression in ZIP**: Detect .gz extension and use STORED method
5. **Presigned URL Expiry**: Use short TTL (15min) and handle expiration in frontend
6. **Frontend Blob Memory**: Large files (>100MB) may cause browser OOM - warn users

---

## Next Steps

1. **Implement P1** (View Upload History List) - Priority
2. **Implement P2** (View Upload Details) - After P1
3. **Implement P3** (Download Files) - After P2
4. **Implement P4** (Excel Export) - After P3

Each phase is independently testable and deliverable!

---

## Support & Resources

- **Spec Document**: `specs/008-upload-history-user/spec.md`
- **Data Model**: `specs/008-upload-history-user/data-model.md`
- **Research**: `specs/008-upload-history-user/research.md`
- **API Contract**: `specs/008-upload-history-user/contracts/upload-history-api.yaml`
- **Constitution**: `.specify/memory/constitution.md`
- **CLAUDE.md**: Project-specific development guidelines
