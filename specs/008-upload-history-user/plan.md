# Implementation Plan: Upload History

**Branch**: `008-upload-history-user` | **Date**: 2025-11-01 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/008-upload-history-user/spec.md`

## Summary

The Upload History feature enables users to view their upload history, download files from S3, and generate Excel exports from multiple CSV files. This feature leverages existing `batches` and `uploaded_files` tables without requiring schema changes, focusing on read-heavy operations with cursor-based pagination and S3 presigned URLs for efficient file delivery.

**Key Technical Approach**:
- **Cursor-based pagination** for O(1) performance with 10,000+ uploads
- **S3 presigned URLs** for direct client-to-S3 downloads (eliminates server bottleneck)
- **Apache POI SXSSF** for streaming Excel generation (90% memory reduction vs standard approach)
- **Streaming ZIP** generation with Apache Commons Compress (Zip64 support)
- **DTO projections** to avoid N+1 queries
- **Multi-layer caching** (Redis + browser HTTP caching)

---

## Technical Context

**Language/Version**: Java 21 (Backend), TypeScript 5.6 (Frontend)
**Primary Dependencies**:
- Backend: Spring Boot 3.5.6, Spring Data JPA, AWS SDK v2 (S3), Apache POI 5.3.0, Apache Commons CSV 1.12.0, Apache Commons Compress 1.28.0, ICU4J 76.1
- Frontend: React 19.2, TanStack Query v5, TanStack Router, Axios, Zod, shadcn/ui

**Storage**: PostgreSQL 16 (existing batches/uploaded_files tables), AWS S3 (file storage), Redis (caching)
**Testing**: JUnit 5 + Mockito + Testcontainers (Backend), Vitest + Testing Library (Frontend)
**Target Platform**: Linux server (Backend), Modern browsers (Frontend - Chrome, Firefox, Safari)
**Project Type**: Web application (Spring Boot + React SPA)

**Performance Goals**:
- **List batches (first page)**: <50ms (cached), <200ms (uncached)
- **Batch details**: <1s for 500 files
- **File download presigned URL**: <10ms generation time
- **ZIP generation**: <10s for 20 files (50MB total)
- **Excel export**: <30s for 20 CSV files (10K rows each)

**Constraints**:
- **No schema changes** (use existing tables only)
- **Memory efficient**: <200KB per request for ZIP/Excel (streaming APIs)
- **Security**: 15-minute presigned URL expiry
- **Browser compatibility**: Support Chrome 90+, Firefox 88+, Safari 14+

**Scale/Scope**:
- **Users**: Support 10,000+ concurrent users
- **Upload history**: Efficient pagination for accounts with 10,000+ uploads
- **File size**: Handle individual files up to 500MB
- **Batch size**: Support batches with up to 500 files

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Backend Principles (DDD + PbLF)

| Principle | Status | Notes |
|-----------|--------|-------|
| **I. Domain-Driven Design** | ✅ PASS | Batch is aggregate root (existing), no new domain logic required |
| **II. Package by Layered Feature** | ✅ PASS | Follows existing pattern: batch/{domain, application, infrastructure, presentation} |
| **III. Test-Driven Development** | ✅ PASS | Contract tests → Integration tests → Implementation → Unit tests |
| **IV. API-First Design** | ✅ PASS | OpenAPI 3.0 contract created (upload-history-api.yaml) |
| **V. Security by Default** | ✅ PASS | JWT authentication, authorization checks via accountId, presigned URLs with 15min expiry |
| **VI. Database Optimization** | ✅ PASS | Cursor pagination, DTO projections, composite index recommended (idx_batches_site_started_id) |
| **VII. Observability & Monitoring** | ✅ PASS | Micrometer metrics for presigned URL generation, ZIP/Excel export performance |

### Frontend Principles (FSD)

| Principle | Status | Notes |
|-----------|--------|-------|
| **VIII. Feature-Sliced Design** | ✅ PASS | entities/batch, features/upload-history, pages/UploadHistoryPage |
| **IX. Type Safety First** | ✅ PASS | TypeScript strict mode, Zod schemas for API responses |
| **X. React Query for Server State** | ✅ PASS | TanStack Query with cursor-based infinite scroll |
| **XI. TDD for Frontend** | ✅ PASS | Vitest unit tests, Testing Library integration tests planned |
| **XII. Keycloak SSO Integration** | ✅ PASS | JWT token from existing auth system |
| **XIII. Component Composition** | ✅ PASS | Avoids prop drilling with React Query hooks |
| **XIV. Form Validation with Zod** | ⚠️ N/A | No forms in this feature (read-only operations) |
| **XV. Performance & Bundle Optimization** | ✅ PASS | React.lazy for route splitting, virtualization if >100 uploads visible |
| **XVI. Accessibility & Security** | ✅ PASS | Semantic HTML, ARIA labels, XSS prevented by React escaping |

### Development Standards

| Standard | Status | Notes |
|----------|--------|-------|
| **Git Workflow** | ✅ PASS | Branch: feature/008-upload-history-user |
| **Backend Code Quality** | ✅ PASS | Java 21 records for DTOs, repository patterns, no circular dependencies |
| **Frontend Code Quality** | ✅ PASS | Named exports, functional components, TypeScript interfaces |
| **Backend Testing** | ✅ PASS | Testcontainers for PostgreSQL, MockMvc for contracts |
| **Frontend Testing** | ✅ PASS | Vitest + Testing Library, 80% coverage target |
| **Security Requirements** | ✅ PASS | No secrets in code, tokens in httpOnly cookies, HTTPS only |

### Final Gate Assessment

**Result**: ✅ **PASS** - All constitution principles satisfied

**Justifications**:
- No new domain entities (reuses existing Batch/UploadedFile)
- No schema changes required (performance index is optional optimization)
- Read-heavy operations align with caching strategy
- Streaming APIs prevent memory issues
- Security follows existing JWT patterns

---

## Project Structure

### Documentation (this feature)

```
specs/008-upload-history-user/
├── plan.md                      # This file (/speckit.plan command output)
├── spec.md                      # Feature specification
├── research.md                  # Phase 0 output (technical decisions)
├── data-model.md                # Phase 1 output (entities + DTOs)
├── quickstart.md                # Phase 1 output (developer guide)
├── contracts/
│   └── upload-history-api.yaml  # Phase 1 output (OpenAPI 3.0 spec)
├── checklists/
│   └── requirements.md          # Specification quality validation
└── tasks.md                     # Phase 2 output (/speckit.tasks command - NOT created yet)
```

### Source Code (repository root)

**Backend** (Spring Boot - Package by Layered Feature):
```
src/main/java/com/bitbi/dfm/
├── batch/
│   ├── domain/
│   │   ├── Batch.java                       # Existing aggregate root
│   │   └── BatchRepository.java             # Existing domain interface
│   ├── application/
│   │   └── BatchHistoryService.java         # NEW - Upload history business logic
│   ├── infrastructure/
│   │   ├── JpaBatchRepository.java          # EXTEND - Add cursor pagination queries
│   │   └── S3PresignedUrlService.java       # NEW - S3 presigned URL generation
│   └── presentation/
│       ├── BatchHistoryController.java      # NEW - REST endpoints
│       └── dto/
│           ├── BatchSummaryDto.java         # NEW - List view DTO
│           ├── BatchDetailDto.java          # NEW - Detail view DTO
│           ├── FileMetadataDto.java         # NEW - File list item DTO
│           ├── FileDownloadResponseDto.java # NEW - Download URL response
│           └── CursorPageResponseDto.java   # NEW - Pagination wrapper
├── upload/
│   ├── domain/
│   │   ├── UploadedFile.java                # Existing entity
│   │   └── UploadedFileRepository.java      # Existing (no changes)
│   └── application/
│       ├── FileDownloadService.java         # NEW - File download orchestration
│       └── ExcelExportService.java          # NEW - Excel generation
└── error/
    ├── domain/
    │   ├── ErrorLog.java                    # Existing entity
    │   └── ErrorLogRepository.java          # Existing (no changes)
    └── presentation/
        └── ErrorLogController.java          # EXTEND - Add batch errors endpoint

src/test/java/com/bitbi/dfm/
├── contract/
│   └── BatchHistoryContractTest.java        # NEW - API contract tests
├── integration/
│   └── BatchHistoryIntegrationTest.java     # NEW - End-to-end tests
└── batch/
    └── application/
        └── BatchHistoryServiceTest.java     # NEW - Unit tests

src/main/resources/
└── db/migration/
    └── V###__add_upload_history_indexes.sql # OPTIONAL - Performance index
```

**Frontend** (React - Feature-Sliced Design):
```
frontend/src/
├── app/
│   └── routes/
│       └── upload-history.tsx               # NEW - Route registration
├── pages/
│   └── upload-history/
│       └── UploadHistoryPage.tsx            # NEW - Page component
├── widgets/
│   └── upload-history/
│       ├── BatchListWidget.tsx              # NEW - Infinite scroll list
│       └── BatchDetailWidget.tsx            # NEW - Detail view widget
├── features/
│   └── upload-history/
│       ├── ui/
│       │   ├── BatchListView.tsx            # NEW - List view component
│       │   ├── BatchDetailView.tsx          # NEW - Detail view component
│       │   ├── FileTable.tsx                # NEW - File selection table
│       │   ├── DownloadButton.tsx           # NEW - Download action button
│       │   └── ExcelButton.tsx              # NEW - Excel export button
│       └── lib/
│           ├── useBatchList.ts              # NEW - Cursor pagination hook
│           ├── useFileDownload.ts           # NEW - Download mutation
│           └── useExcelExport.ts            # NEW - Excel export mutation
├── entities/
│   └── batch/
│       ├── model/
│       │   └── types.ts                     # NEW - Batch/File type definitions
│       └── api/
│           ├── batchApi.ts                  # NEW - API client functions
│           └── queries.ts                   # NEW - TanStack Query hooks
└── shared/
    └── lib/
        └── formatters.ts                    # EXTEND - Add formatBytes, formatDateTime

frontend/src/test/
└── features/
    └── upload-history/
        ├── BatchListView.test.tsx           # NEW - Component tests
        └── useBatchList.test.ts             # NEW - Hook tests
```

**Structure Decision**: Web application structure selected based on existing Spring Boot backend + React frontend. Backend follows Package by Layered Feature (PbLF) with batch/, upload/, error/ modules. Frontend uses Feature-Sliced Design (FSD) with clear layer separation (app → pages → widgets → features → entities → shared).

---

## Complexity Tracking

*No constitution violations - this section is intentionally empty.*

The Upload History feature aligns with all constitution principles:
- ✅ Reuses existing domain entities (Batch, UploadedFile, ErrorLog)
- ✅ No new architectural patterns introduced
- ✅ Follows established TDD workflow
- ✅ Leverages existing JWT authentication
- ✅ Uses cursor pagination (simpler than OFFSET with better performance)
- ✅ Streaming APIs prevent memory issues

---

## Implementation Phases

### Phase 0: Research & Decisions (COMPLETED ✅)

**Output**: `research.md`

**Decisions Made**:
1. **S3 Download Strategy**: Presigned URLs (15min expiry) for direct downloads
2. **ZIP Generation**: Apache Commons Compress with streaming API
3. **Excel Generation**: Apache POI SXSSF (100-row window)
4. **CSV Encoding**: ICU4J for detection (UTF-8/Windows-1252/ISO-8859-1)
5. **Gzip Decompression**: Apache Commons Compress GzipCompressorInputStream
6. **React Downloads**: Axios Blob + TanStack Query
7. **Pagination**: Cursor-based (startedAt + id)
8. **Caching**: Redis (5min first page, 30min details)

### Phase 1: Design & Contracts (COMPLETED ✅)

**Output**: `data-model.md`, `contracts/upload-history-api.yaml`, `quickstart.md`

**Artifacts Created**:
- ✅ Data model documented (no schema changes needed)
- ✅ 6 OpenAPI endpoints specified
- ✅ 7 DTOs defined (BatchSummaryDto, BatchDetailDto, etc.)
- ✅ Quickstart guide for developers
- ✅ Performance optimization recommendations

### Phase 2: Task Breakdown (PENDING - Use `/speckit.tasks`)

**Output**: `tasks.md`

**Expected Tasks**:
1. Backend tasks (by user story priority P1-P4)
2. Frontend tasks (by user story priority P1-P4)
3. Testing tasks (contract → integration → unit)
4. Performance optimization tasks (indexing, caching)

---

## Dependencies to Add

### Backend (`build.gradle.kts`)

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

**Total Size**: ~12MB additional JARs (POI is largest at ~8MB)

### Frontend (`package.json`)

No new dependencies required - feature uses existing stack:
- ✅ `@tanstack/react-query` (already installed)
- ✅ `axios` (already installed)
- ✅ `zod` (already installed)
- ✅ `shadcn/ui` components (already installed)

---

## Performance Optimizations

### Database Index (RECOMMENDED)

**Migration**: `V###__add_upload_history_indexes.sql`

```sql
-- Composite index for cursor-based pagination
CREATE INDEX IF NOT EXISTS idx_batches_site_started_id
    ON batches(site_id, started_at DESC, id DESC);
```

**Impact**:
- Query time for page 500: 50ms → 5ms (10x improvement)
- Index size: ~500KB for 10,000 batches (negligible)
- Write overhead: <1ms per INSERT/UPDATE

### Redis Caching Configuration

```java
@Bean
public RedisCacheConfiguration batchFirstPageCacheConfig() {
    return RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(Duration.ofMinutes(5))  // Hot data cache
        .serializeValuesWith(
            RedisSerializationContext.SerializationPair
                .fromSerializer(new GenericJackson2JsonRedisSerializer())
        );
}
```

**Cache Keys**:
- `batch-first-page:{accountId}` - 5min TTL
- `batch-details:{batchId}` - 30min TTL (only COMPLETED batches)

### Frontend Optimization

- **Code Splitting**: `React.lazy(() => import('./UploadHistoryPage'))`
- **Virtualization**: If >100 uploads visible, use `@tanstack/react-virtual`
- **Blob URL Cleanup**: Always call `URL.revokeObjectURL()` after download

---

## Testing Strategy

### Backend Testing

**Coverage Targets**:
- Overall: ≥80%
- Critical paths (download, Excel export): ≥95%
- Utilities (formatters, validators): 100%

**Test Layers**:
1. **Contract Tests** (MockMvc): Verify API contracts match OpenAPI spec
2. **Integration Tests** (Testcontainers): Full request→database→response flows
3. **Unit Tests** (Mockito): Service layer business logic

**Example Test Cases**:
- TC01: List batches with cursor pagination
- TC02: Empty state (no uploads)
- TC03: Batch details with file list
- TC04: Download presigned URL generation
- TC05: ZIP multi-file download
- TC06: Excel export with encoding detection
- TC07: Error list for batch with errors
- TC08: Authorization checks (user can only see own batches)

### Frontend Testing

**Coverage Targets**:
- Overall: ≥80%
- Critical user flows: ≥95%

**Test Layers**:
1. **Unit Tests** (Vitest): Hooks, utilities, API client
2. **Integration Tests** (Testing Library): Component interactions, mocked API
3. **E2E Tests** (Playwright): Critical paths (view history, download file)

**Example Test Cases**:
- TC01: Display batch list
- TC02: Empty state display
- TC03: Infinite scroll pagination
- TC04: File selection (select all)
- TC05: Download button disabled when batch not COMPLETED
- TC06: Progress indicator during download

---

## Monitoring & Alerts

### Metrics to Track (Micrometer)

```java
// S3 presigned URL generation
registry.timer("s3.presigned.url.generation").record(durationMs, TimeUnit.MILLISECONDS);

// ZIP download
registry.counter("downloads.zip.files", "count", String.valueOf(fileCount)).increment();
registry.timer("downloads.zip.duration").record(durationMs, TimeUnit.MILLISECONDS);

// Excel export
registry.counter("exports.excel.sheets", "count", String.valueOf(sheetCount)).increment();
registry.timer("exports.excel.duration").record(durationMs, TimeUnit.MILLISECONDS);

// Cache hit rate
registry.counter("cache.hit", "cache", "batch-first-page").increment();
registry.counter("cache.miss", "cache", "batch-first-page").increment();
```

### Alerts

- ⚠️ **Excel export >30s (p95)** → Investigate row count or file size limits
- ⚠️ **ZIP download >1min (p95)** → Check S3 latency or network bandwidth
- ⚠️ **Batch history >200ms (p95)** → Review query plan and index usage
- ⚠️ **Cache hit rate <60%** → Adjust TTL or add cache warming

---

## Success Criteria Verification

| Criterion | Target | How to Verify |
|-----------|--------|---------------|
| SC-001: View upload history | <2s for 1000 uploads | Micrometer `batch.history.list` timer |
| SC-002: Batch details | <1s for 500 files | Micrometer `batch.details.load` timer |
| SC-003: Download files | <5s for 10 files | Micrometer `downloads.zip.duration` timer |
| SC-004: Excel export | <30s for 20 CSVs (50MB) | Micrometer `exports.excel.duration` timer |
| SC-005: Download success rate | 95% first attempt | Micrometer `downloads.success.rate` counter |
| SC-006: Support 10,000+ uploads | No degradation | Load test with Gatling/JMeter |
| SC-007: Visual status indicators | At a glance | Manual UX review |
| SC-008: Encoding support | UTF-8/Windows-1252/ISO-8859-1 | Integration test with sample files |

---

## Next Steps

1. **Run `/speckit.tasks`** to generate implementation tasks
2. **Review tasks.md** with team for estimation
3. **Start TDD workflow**: Write contract tests first
4. **Implement P1** (View Upload History List) as MVP
5. **Iterate through P2-P4** based on user feedback

---

## Resources

- **Feature Spec**: [spec.md](spec.md)
- **Research Decisions**: [research.md](research.md)
- **Data Model**: [data-model.md](data-model.md)
- **API Contract**: [contracts/upload-history-api.yaml](contracts/upload-history-api.yaml)
- **Developer Guide**: [quickstart.md](quickstart.md)
- **Constitution**: [.specify/memory/constitution.md](../../.specify/memory/constitution.md)
- **CLAUDE.md**: [CLAUDE.md](../../CLAUDE.md)
