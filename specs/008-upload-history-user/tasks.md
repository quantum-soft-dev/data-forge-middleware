# Tasks: Upload History Feature

**Input**: Design documents from `/specs/008-upload-history-user/`
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/ ✅

**Tests**: Tests are INCLUDED in this feature - the existing codebase follows TDD with Testcontainers and MockMvc.

**Organization**: Tasks are grouped by user story (P1-P4) to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3, US4)
- Include exact file paths in descriptions

## Path Conventions
- **Backend**: `src/main/java/com/bitbi/dfm/`, `src/test/java/com/bitbi/dfm/`
- **Frontend**: `frontend/src/`
- **Migrations**: `src/main/resources/db/migration/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Add dependencies and create shared infrastructure for Upload History feature

- [X] T001 Add Apache POI dependency (5.3.0) to build.gradle.kts for Excel generation
- [X] T002 [P] Add Apache Commons CSV dependency (1.12.0) to build.gradle.kts for CSV parsing
- [X] T003 [P] Add Apache Commons Compress dependency (1.28.0) to build.gradle.kts for ZIP/Gzip handling
- [X] T004 [P] Add ICU4J dependency (76.1) to build.gradle.kts for encoding detection
- [X] T005 Verify Redis caching dependencies exist (spring-boot-starter-data-redis, spring-boot-starter-cache)
- [X] T006 Run `./gradlew build` to verify all dependencies resolve correctly

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Database Optimization

- [X] T007 Create Flyway migration V###__add_upload_history_indexes.sql with composite index `idx_batches_site_started_id ON batches(site_id, started_at DESC, id DESC)`

### DTOs and Shared Utilities

- [X] T008 [P] Create `BatchSummaryDto` record in src/main/java/com/bitbi/dfm/batch/presentation/dto/BatchSummaryDto.java with fields (id, siteId, status, hasErrors, uploadedFilesCount, totalSize, startedAt, completedAt) and `fromEntity()` method
- [X] T009 [P] Create `BatchDetailDto` record in src/main/java/com/bitbi/dfm/batch/presentation/dto/BatchDetailDto.java with fields including `List<FileMetadataDto> files`
- [X] T010 [P] Create `FileMetadataDto` record in src/main/java/com/bitbi/dfm/batch/presentation/dto/FileMetadataDto.java with fields (id, originalFileName, fileSize, uploadedAt)
- [X] T011 [P] Create `FileDownloadResponseDto` record in src/main/java/com/bitbi/dfm/batch/presentation/dto/FileDownloadResponseDto.java with fields (downloadUrl, fileName, fileSize, expiresAt)
- [X] T012 [P] Create `CursorPageResponseDto<T>` generic record in src/main/java/com/bitbi/dfm/batch/presentation/dto/CursorPageResponseDto.java with fields (items, nextCursor, hasNext)
- [X] T013 [P] Create `ErrorSummaryDto` record in src/main/java/com/bitbi/dfm/error/presentation/dto/ErrorSummaryDto.java with fields (id, severity, message, source, metadata, occurredAt)

### Repository Projections

- [X] T014 Create `BatchWithFileCountProjection` interface in src/main/java/com/bitbi/dfm/batch/infrastructure/BatchWithFileCountProjection.java with getters for (id, siteId, status, hasErrors, startedAt, completedAt, fileCount, totalSize)

### Frontend Shared Types

- [X] T015 [P] Create `BatchSummary` TypeScript interface in frontend/src/entities/batch/model/types.ts matching BatchSummaryDto
- [X] T016 [P] Create `BatchDetail` TypeScript interface in frontend/src/entities/batch/model/types.ts matching BatchDetailDto
- [X] T017 [P] Create `FileMetadata` TypeScript interface in frontend/src/entities/batch/model/types.ts matching FileMetadataDto
- [X] T018 [P] Create `CursorPageResponse<T>` TypeScript generic interface in frontend/src/entities/batch/model/types.ts
- [X] T019 [P] Extend `frontend/src/shared/lib/formatters.ts` with `formatBytes(bytes: number): string` and `formatDateTime(iso: string): string` utility functions

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - View Upload History List (Priority: P1) 🎯 MVP

**Goal**: Users can view paginated list of all their upload sessions with status indicators (green checkmark for success, red cross for errors)

**Independent Test**: Authenticate as user with upload history, navigate to upload history page, verify all uploads displayed with correct status indicators and pagination

### Backend Tests for User Story 1

**NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [X] T020 [P] [US1] Contract test TC01 in src/test/java/com/bitbi/dfm/contract/BatchHistoryContractTest.java: Verify GET /api/dfc/batches returns 200 with BatchSummaryDto list for authenticated user
- [X] T021 [P] [US1] Contract test TC02 in src/test/java/com/bitbi/dfm/contract/BatchHistoryContractTest.java: Verify empty state returns empty list when user has no uploads
- [X] T022 [P] [US1] Contract test TC03 in src/test/java/com/bitbi/dfm/contract/BatchHistoryContractTest.java: Verify cursor pagination with nextCursor and hasNext flags
- [X] T023 [P] [US1] Contract test TC04 in src/test/java/com/bitbi/dfm/contract/BatchHistoryContractTest.java: Verify results sorted by startedAt DESC, id DESC
- [X] T024 [P] [US1] Integration test in src/test/java/com/bitbi/dfm/integration/BatchHistoryIntegrationTest.java: End-to-end test for listing batches with Testcontainers PostgreSQL (placeholder created)

### Backend Implementation for User Story 1

- [X] T025 [US1] Add cursor-based query methods to src/main/java/com/bitbi/dfm/batch/infrastructure/JpaBatchRepository.java: `findBySiteIdsFirstPage()` and `findBySiteIdsWithCursor()` using BatchWithFileCountProjection
- [X] T026 [US1] Create `BatchHistoryService` in src/main/java/com/bitbi/dfm/batch/application/BatchHistoryService.java with `listBatchHistory(accountId, cursor, limit)` method implementing cursor pagination logic
- [X] T027 [US1] Add Redis caching configuration in src/main/java/com/bitbi/dfm/config/CacheConfiguration.java with 5-minute TTL for "batch-first-page" cache
- [X] T028 [US1] Apply @Cacheable to first page query in BatchHistoryService
- [X] T029 [US1] Create `BatchHistoryController` in src/main/java/com/bitbi/dfm/batch/presentation/BatchHistoryController.java with GET /api/dfc/batches endpoint
- [X] T030 [US1] Add authorization check in BatchHistoryController to extract accountId from JWT and filter batches by user's sites
- [X] T031 [US1] Add OpenAPI @Tag and @Operation annotations to BatchHistoryController matching contracts/upload-history-api.yaml
- [X] T032 [US1] Add Micrometer metric for batch history list timer ("batch.history.list")

### Frontend Tests for User Story 1

- [X] T033 [P] [US1] Unit test for `useBatchHistory` hook in frontend/src/entities/batch/api/queries.test.ts using Vitest
- [X] T034 [P] [US1] Component test for `BatchListView` in frontend/src/features/upload-history/ui/BatchListView.test.tsx using Testing Library

### Frontend Implementation for User Story 1

- [X] T035 [P] [US1] Create `listBatches(cursor?, limit)` API client function in frontend/src/entities/batch/api/batchApi.ts using Axios
- [X] T036 [US1] Create `useBatchHistory(limit)` TanStack Query infinite query hook in frontend/src/entities/batch/api/queries.ts
- [X] T037 [US1] Create `BatchListView` component in frontend/src/features/upload-history/ui/BatchListView.tsx with infinite scroll, empty state, and status indicators (CheckCircle/XCircle icons)
- [X] T038 [US1] Create `BatchListWidget` in frontend/src/widgets/upload-history/BatchListWidget.tsx as container component
- [X] T039 [US1] Create `UploadHistoryPage` in frontend/src/pages/upload-history/UploadHistoryPage.tsx as route page
- [X] T040 [US1] Register route in frontend/src/app/router.tsx using TanStack Router at /account/upload-history with authentication and role checks

**Checkpoint**: User Story 1 complete - users can view upload history list with pagination and status indicators

---

## Phase 4: User Story 2 - View Upload Details and File List (Priority: P2)

**Goal**: Users can drill into specific upload session to see all uploaded files with names, sizes, and selection checkboxes

**Independent Test**: Click on any upload from history list, verify all files displayed with correct metadata, "Select all" checkbox works

### Backend Tests for User Story 2

- [X] T041 [P] [US2] Contract test TC05 in src/test/java/com/bitbi/dfm/contract/BatchHistoryContractTest.java: Verify GET /api/dfc/batches/{batchId} returns BatchDetailDto with file list
- [X] T042 [P] [US2] Contract test TC06 in src/test/java/com/bitbi/dfm/contract/BatchHistoryContractTest.java: Verify 403 Forbidden when batch doesn't belong to user
- [X] T043 [P] [US2] Contract test TC07 in src/test/java/com/bitbi/dfm/contract/BatchHistoryContractTest.java: Verify 404 Not Found when batchId doesn't exist
- [X] T044 [P] [US2] Integration test in src/test/java/com/bitbi/dfm/integration/BatchHistoryIntegrationTest.java: Load batch details with JOIN FETCH to verify N+1 query prevention

### Backend Implementation for User Story 2

- [X] T045 [US2] Add `findByIdWithFiles(batchId)` query to src/main/java/com/bitbi/dfm/batch/infrastructure/JpaBatchRepository.java using LEFT JOIN FETCH
- [X] T046 [US2] Add `getBatchDetails(batchId, accountId)` method to src/main/java/com/bitbi/dfm/batch/application/BatchHistoryService.java with authorization check
- [X] T047 [US2] Add Redis caching for batch details (30-minute TTL, condition: status == COMPLETED) in BatchHistoryService
- [X] T048 [US2] Add GET /api/dfc/batches/{batchId} endpoint to src/main/java/com/bitbi/dfm/batch/presentation/BatchHistoryController.java
- [X] T049 [US2] Add Micrometer metric for batch details load timer ("batch.details.load")

### Frontend Tests for User Story 2

- [X] T050 [P] [US2] Component test for `BatchDetailView` in frontend/src/features/upload-history/ui/BatchDetailView.test.tsx
- [X] T051 [P] [US2] Component test for `FileTable` in frontend/src/features/upload-history/ui/FileTable.test.tsx verifying checkbox selection logic

### Frontend Implementation for User Story 2

- [X] T052 [P] [US2] Create `getBatchDetails(batchId)` API client function in frontend/src/entities/batch/api/batchApi.ts
- [X] T053 [US2] Create `useBatchDetails(batchId)` TanStack Query hook in frontend/src/entities/batch/api/queries.ts
- [X] T054 [US2] Create `FileTable` component in frontend/src/features/upload-history/ui/FileTable.tsx with checkbox selection (individual + "Select all")
- [X] T055 [US2] Create `BatchDetailView` component in frontend/src/features/upload-history/ui/BatchDetailView.tsx integrating FileTable and batch metadata
- [X] T056 [US2] Create `BatchDetailWidget` in frontend/src/widgets/upload-history/BatchDetailWidget.tsx as container
- [X] T057 [US2] Add navigation from BatchListView to detail page (onClick handler)

**Checkpoint**: User Story 2 complete - users can view detailed file list for any upload session

---

## Phase 5: User Story 3 - Download Selected Files (Priority: P3)

**Goal**: Users can download selected files in original .csv.gz format (single file direct download, multiple files as ZIP archive)

**Independent Test**: Select files in upload details, click download button, verify files download correctly (single = direct, multiple = ZIP)

### Backend Tests for User Story 3

- [X] T058 [P] [US3] Contract test TC08 in src/test/java/com/bitbi/dfm/contract/BatchHistoryContractTest.java: Verify GET /api/dfc/batches/{batchId}/files/{fileId}/download returns FileDownloadResponseDto with presigned URL
- [X] T059 [P] [US3] Contract test TC09 in src/test/java/com/bitbi/dfm/contract/BatchHistoryContractTest.java: Verify 403 Forbidden when batch status is IN_PROGRESS
- [X] T060 [P] [US3] Contract test TC10 in src/test/java/com/bitbi/dfm/contract/BatchHistoryContractTest.java: Verify POST /api/dfc/batches/{batchId}/download-zip returns application/zip binary
- [X] T061 [P] [US3] Integration test in src/test/java/com/bitbi/dfm/integration/FileDownloadIntegrationTest.java: Test presigned URL generation with LocalStack S3
- [X] T062 [P] [US3] Integration test in src/test/java/com/bitbi/dfm/integration/FileDownloadIntegrationTest.java: Test ZIP streaming with multiple files from S3

### Backend Implementation for User Story 3

- [X] T063 [P] [US3] Create `S3PresignedUrlService` in src/main/java/com/bitbi/dfm/batch/infrastructure/S3PresignedUrlService.java with `generatePresignedUrl(s3Key, fileName, expiryMinutes)` method using AWS SDK v2
- [X] T064 [US3] Create `FileDownloadService` in src/main/java/com/bitbi/dfm/upload/application/FileDownloadService.java with `getPresignedUrlForFile(batchId, fileId, accountId)` method
- [X] T065 [US3] Add batch status validation (COMPLETED only) to FileDownloadService
- [X] T066 [US3] Add `downloadFilesAsZip(batchId, fileIds, accountId, response)` method to FileDownloadService using Apache Commons Compress ZipArchiveOutputStream
- [X] T067 [US3] Implement streaming ZIP logic: detect .gz files and use STORED compression (no double compression), use DEFLATED for non-gz files
- [X] T068 [US3] Add GET /api/dfc/batches/{batchId}/files/{fileId}/download endpoint to src/main/java/com/bitbi/dfm/batch/presentation/BatchHistoryController.java
- [X] T069 [US3] Add POST /api/dfc/batches/{batchId}/download-zip endpoint to src/main/java/com/bitbi/dfm/batch/presentation/BatchHistoryController.java with StreamingResponseBody
- [X] T070 [US3] Add Micrometer metrics for presigned URL generation ("s3.presigned.url.generation"), ZIP download count ("downloads.zip.files"), and ZIP duration timer ("downloads.zip.duration")

### Frontend Tests for User Story 3

- [X] T071 [P] [US3] Unit test for `useFileDownload` mutation in frontend/src/features/upload-history/lib/useFileDownload.test.ts
- [X] T072 [P] [US3] Component test for `DownloadButton` in frontend/src/features/upload-history/ui/DownloadButton.test.tsx verifying disabled state when no files selected

### Frontend Implementation for User Story 3

- [X] T073 [P] [US3] Create `downloadFile(batchId, fileId, filename)` API client function in frontend/src/entities/batch/api/batchApi.ts using Axios responseType: 'blob'
- [X] T074 [P] [US3] Create `downloadFilesAsZip(batchId, fileIds)` API client function in frontend/src/entities/batch/api/batchApi.ts
- [X] T075 [US3] Create `useFileDownload()` TanStack Query mutation hook in frontend/src/features/upload-history/lib/useFileDownload.ts with progress tracking and blob download logic
- [X] T076 [US3] Create `DownloadButton` component in frontend/src/features/upload-history/ui/DownloadButton.tsx with disabled state when no files selected or batch not COMPLETED
- [X] T077 [US3] Integrate DownloadButton into BatchDetailView with selected files state
- [X] T078 [US3] Add URL.revokeObjectURL() cleanup after download in useFileDownload hook

**Checkpoint**: User Story 3 complete - users can download files in original format

---

## Phase 6: User Story 4 - Generate Excel from Selected Files (Priority: P4)

**Goal**: Users can generate Excel workbook (.xlsx) from selected CSV files, with each CSV becoming a separate sheet

**Independent Test**: Select multiple CSV files, click "Create Excel" button, verify downloaded Excel has correct sheets with data

### Backend Tests for User Story 4

- [ ] T079 [P] [US4] Contract test TC11 in src/test/java/com/bitbi/dfm/contract/BatchHistoryContractTest.java: Verify POST /api/dfc/batches/{batchId}/export-excel returns application/vnd.openxmlformats-officedocument.spreadsheetml.sheet binary
- [ ] T080 [P] [US4] Contract test TC12 in src/test/java/com/bitbi/dfm/contract/BatchHistoryContractTest.java: Verify 400 Bad Request when CSV parsing fails
- [ ] T081 [P] [US4] Integration test in src/test/java/com/bitbi/dfm/integration/ExcelExportIntegrationTest.java: Test Excel generation with UTF-8 encoded CSV files
- [ ] T082 [P] [US4] Integration test in src/test/java/com/bitbi/dfm/integration/ExcelExportIntegrationTest.java: Test Excel generation with Windows-1252 encoded CSV files
- [ ] T083 [P] [US4] Integration test in src/test/java/com/bitbi/dfm/integration/ExcelExportIntegrationTest.java: Test sheet name deduplication (e.g., "data", "data (2)", "data (3)")

### Backend Implementation for User Story 4

- [ ] T084 [P] [US4] Create `EncodingDetectionService` in src/main/java/com/bitbi/dfm/upload/application/EncodingDetectionService.java using ICU4J CharsetDetector
- [ ] T085 [US4] Create `CsvDecompressionService` in src/main/java/com/bitbi/dfm/upload/application/CsvDecompressionService.java using Apache Commons Compress GzipCompressorInputStream
- [ ] T086 [US4] Create `ExcelExportService` in src/main/java/com/bitbi/dfm/upload/application/ExcelExportService.java with `exportToExcel(batchId, fileIds, accountId, response)` method
- [ ] T087 [US4] Implement Excel generation logic using Apache POI SXSSF (100-row window) with Apache Commons CSV parser
- [ ] T088 [US4] Implement sheet naming with 31-character limit, invalid character replacement, and duplicate name handling (numeric suffix)
- [ ] T089 [US4] Add encoding detection before CSV parsing (try UTF-8 → detect with ICU4J → fallback to Windows-1252)
- [ ] T090 [US4] Add gzip decompression wrapper for .csv.gz files
- [ ] T091 [US4] Add workbook.dispose() cleanup in finally block to delete SXSSF temp files
- [ ] T092 [US4] Add POST /api/dfc/batches/{batchId}/export-excel endpoint to src/main/java/com/bitbi/dfm/batch/presentation/BatchHistoryController.java
- [ ] T093 [US4] Add Micrometer metrics for Excel export sheet count ("exports.excel.sheets") and duration timer ("exports.excel.duration")

### Frontend Tests for User Story 4

- [ ] T094 [P] [US4] Unit test for `useExcelExport` mutation in frontend/src/features/upload-history/lib/useExcelExport.test.ts
- [ ] T095 [P] [US4] Component test for `ExcelButton` in frontend/src/features/upload-history/ui/ExcelButton.test.tsx verifying disabled state

### Frontend Implementation for User Story 4

- [ ] T096 [P] [US4] Create `exportToExcel(batchId, fileIds)` API client function in frontend/src/entities/batch/api/batchApi.ts using Axios responseType: 'blob'
- [ ] T097 [US4] Create `useExcelExport()` TanStack Query mutation hook in frontend/src/features/upload-history/lib/useExcelExport.ts with progress tracking
- [ ] T098 [US4] Create `ExcelButton` component in frontend/src/features/upload-history/ui/ExcelButton.tsx with disabled state when no files selected or batch not COMPLETED
- [ ] T099 [US4] Integrate ExcelButton into BatchDetailView alongside DownloadButton
- [ ] T100 [US4] Add URL.revokeObjectURL() cleanup after download in useExcelExport hook

**Checkpoint**: User Story 4 complete - users can generate Excel workbooks from CSV files

---

## Phase 7: Error Details View (Supporting P1)

**Goal**: Users can view detailed error information for uploads marked with errors

**Independent Test**: Click "View errors" on upload with red cross indicator, verify error details displayed with severity, message, source, and metadata

### Backend Tests for Error Details

- [ ] T101 [P] [US1] Contract test TC13 in src/test/java/com/bitbi/dfm/contract/BatchHistoryContractTest.java: Verify GET /api/dfc/batches/{batchId}/errors returns PageResponseDto<ErrorSummaryDto>
- [ ] T102 [P] [US1] Integration test in src/test/java/com/bitbi/dfm/integration/BatchHistoryIntegrationTest.java: Test error pagination with 100+ errors

### Backend Implementation for Error Details

- [ ] T103 [US1] Add `getBatchErrors(batchId, accountId, page, size)` method to src/main/java/com/bitbi/dfm/error/application/ErrorLogService.java with authorization check
- [ ] T104 [US1] Add GET /api/dfc/batches/{batchId}/errors endpoint to src/main/java/com/bitbi/dfm/error/presentation/ErrorLogController.java with pagination

### Frontend Implementation for Error Details

- [ ] T105 [P] [US1] Create `getBatchErrors(batchId, page, size)` API client function in frontend/src/entities/batch/api/batchApi.ts
- [ ] T106 [US1] Create `useBatchErrors(batchId)` TanStack Query hook in frontend/src/entities/batch/api/queries.ts
- [ ] T107 [US1] Create `ErrorListView` component in frontend/src/features/upload-history/ui/ErrorListView.tsx with pagination
- [ ] T108 [US1] Add "View errors" button navigation from BatchListView to error details modal/page

**Checkpoint**: Error details view complete - users can troubleshoot failed uploads

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [ ] T109 [P] Add comprehensive JavaDoc comments to all public methods in BatchHistoryService, FileDownloadService, ExcelExportService
- [ ] T110 [P] Add JSDoc comments to frontend API client functions and hooks
- [ ] T111 Add global error handling for presigned URL expiry (redirect to generate new URL)
- [ ] T112 Add frontend error boundaries for upload history pages
- [ ] T113 [P] Add loading skeletons for batch list and detail views
- [ ] T114 [P] Add toast notifications for download/Excel export success and errors using Sonner
- [ ] T115 Validate quickstart.md instructions by following step-by-step
- [ ] T116 Run integration tests with Testcontainers to verify end-to-end flows
- [ ] T117 Run Micrometer metrics verification (check batch.history.list, s3.presigned.url.generation, downloads.zip.duration, exports.excel.duration)
- [ ] T118 Performance test: Verify batch list response time <200ms for 1000 uploads
- [ ] T119 Performance test: Verify Excel export completes in <30s for 20 CSV files (10K rows each)
- [ ] T120 Security review: Verify authorization checks prevent cross-account batch access
- [ ] T121 Update CLAUDE.md with Upload History feature summary and implementation decisions

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3-6)**: All depend on Foundational phase completion
  - User Story 1 (P1): Can start after Foundational - No dependencies on other stories
  - User Story 2 (P2): Can start after Foundational - Builds on US1 UI but independently testable
  - User Story 3 (P3): Can start after Foundational - Requires US2 file selection UI
  - User Story 4 (P4): Can start after Foundational - Shares file selection with US3
- **Error Details (Phase 7)**: Depends on User Story 1 (uses same list view)
- **Polish (Phase 8)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) - No dependencies on other stories ✅ MVP
- **User Story 2 (P2)**: Can start after Foundational (Phase 2) - Extends US1 by adding detail view (independently testable)
- **User Story 3 (P3)**: Depends on User Story 2 (needs file selection UI from detail view)
- **User Story 4 (P4)**: Depends on User Story 2 (shares file selection with US3, but can be implemented in parallel with US3)

### Within Each User Story

- Tests MUST be written and FAIL before implementation (TDD)
- DTOs before service methods
- Service methods before controllers
- Backend endpoints before frontend API clients
- API clients before React Query hooks
- Hooks before UI components
- Story complete before moving to next priority

### Parallel Opportunities

- **Phase 1 (Setup)**: All dependency additions (T001-T004) can run in parallel
- **Phase 2 (Foundational)**:
  - All DTOs (T008-T013) can run in parallel
  - All frontend types (T015-T019) can run in parallel
- **User Story 1**:
  - Contract tests (T020-T023) can run in parallel
  - Frontend API client + hook (T035-T036) can be developed in parallel with backend service (T026)
  - Frontend components (T037-T040) can run in parallel after hooks are ready
- **User Story 2**:
  - Contract tests (T041-T044) can run in parallel
  - Frontend components (T052-T056) can be developed in parallel with backend implementation
- **User Story 3**:
  - Contract tests (T058-T062) can run in parallel
  - S3PresignedUrlService and FileDownloadService (T063-T064) can be developed in parallel
  - Frontend tests (T071-T072) can run in parallel
- **User Story 4**:
  - Integration tests (T081-T083) can run in parallel
  - Encoding detection, decompression, Excel export services (T084-T086) can be scaffolded in parallel
  - Frontend tests (T094-T095) can run in parallel
- **Phase 8 (Polish)**: Most documentation and refactoring tasks (T109-T114) can run in parallel

---

## Parallel Example: User Story 1

```bash
# Launch all contract tests for User Story 1 together:
Task: "T020 [P] [US1] Contract test TC01 - GET /api/dfc/batches returns 200"
Task: "T021 [P] [US1] Contract test TC02 - Empty state returns empty list"
Task: "T022 [P] [US1] Contract test TC03 - Cursor pagination"
Task: "T023 [P] [US1] Contract test TC04 - Results sorted by startedAt DESC"

# Launch all DTOs together (Phase 2):
Task: "T008 [P] Create BatchSummaryDto record"
Task: "T009 [P] Create BatchDetailDto record"
Task: "T010 [P] Create FileMetadataDto record"
```

---

## Parallel Example: User Story 3

```bash
# Launch backend services in parallel:
Task: "T063 [P] [US3] Create S3PresignedUrlService"
Task: "T064 [US3] Create FileDownloadService" (depends on T063)

# Launch frontend tests in parallel:
Task: "T071 [P] [US3] Unit test for useFileDownload mutation"
Task: "T072 [P] [US3] Component test for DownloadButton"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T006) → Dependencies installed
2. Complete Phase 2: Foundational (T007-T019) → DTOs, types, index ready
3. Complete Phase 3: User Story 1 (T020-T040) → Upload history list view
4. **STOP and VALIDATE**: Test User Story 1 independently
   - Verify pagination works with 100+ uploads
   - Verify status indicators (green/red) display correctly
   - Verify empty state when no uploads
   - Verify authorization (users only see own uploads)
5. Deploy/demo if ready ✅ MVP delivered

### Incremental Delivery

1. **Foundation** (Phase 1 + 2): ~6 tasks → Dependencies + DTOs ready
2. **MVP** (Phase 3): User Story 1 → Upload history list ✅ Deploy
3. **Enhancement 1** (Phase 4): User Story 2 → File details view ✅ Deploy
4. **Enhancement 2** (Phase 5): User Story 3 → File downloads ✅ Deploy
5. **Enhancement 3** (Phase 6): User Story 4 → Excel export ✅ Deploy
6. **Error Support** (Phase 7): Error details view → Troubleshooting
7. **Polish** (Phase 8): Cross-cutting improvements

Each story adds value without breaking previous stories!

### Parallel Team Strategy

With 3 developers after Foundational phase completes:

1. **Foundation** (Phase 1-2): Entire team collaborates (essential for all stories)
2. **After Foundation**:
   - Developer A: User Story 1 (P1) - MVP priority
   - Developer B: User Story 2 (P2) - Can start immediately
   - Developer C: Start User Story 3 (P3) skeleton - wait for US2 file selection UI
3. **After US2 completes**:
   - Developer B switches to User Story 4 (P4)
   - Developer C completes User Story 3 (P3) using US2's FileTable
4. **Final Integration**: All developers collaborate on Phase 7-8

---

## Summary

- **Total Tasks**: 121 tasks
- **Task Breakdown by User Story**:
  - Phase 1 (Setup): 6 tasks
  - Phase 2 (Foundational): 13 tasks (BLOCKING)
  - Phase 3 (US1 - Upload History List): 21 tasks ✅ MVP
  - Phase 4 (US2 - Upload Details): 17 tasks
  - Phase 5 (US3 - Download Files): 21 tasks
  - Phase 6 (US4 - Excel Export): 22 tasks
  - Phase 7 (Error Details): 8 tasks
  - Phase 8 (Polish): 13 tasks

- **Parallel Opportunities**: 45+ tasks marked [P] can run in parallel within their phase
- **Independent Test Criteria**:
  - US1: View upload history list with pagination
  - US2: View upload details and file list
  - US3: Download files (single/ZIP)
  - US4: Generate Excel from CSVs

- **Suggested MVP Scope**: Phase 1 + Phase 2 + Phase 3 (User Story 1 only) = 40 tasks
- **Full Feature**: All 8 phases = 121 tasks

---

## Notes

- [P] tasks = different files, no dependencies within phase
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Verify tests fail before implementing (TDD workflow)
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Follow existing patterns: Package by Layered Feature (backend), Feature-Sliced Design (frontend)
- Use Testcontainers for integration tests, MockMvc for contract tests
- All endpoints must match contracts/upload-history-api.yaml specification
