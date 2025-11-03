# Tasks: File Diff Comparison Between Upload Sessions

**Input**: Design documents from `/specs/009-markdown-user-story/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/comparison-api.yaml

**Tests**: ✅ **TDD IS MANDATORY** per constitution principles III (Backend) and XI (Frontend)
**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions
- **Backend**: `src/main/java/com/bitbi/dfm/`, `src/test/java/com/bitbi/dfm/`
- **Frontend**: `frontend/src/`, `frontend/src/__tests__/`
- **Database**: `src/main/resources/db/migration/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and dependency setup for file comparison feature

- [X] **T001** [P] [SETUP] Add java-diff-utils dependency to build.gradle.kts (version 4.12)
- [X] **T002** [P] [SETUP] Add react-diff-viewer-continued dependency to frontend/package.json (version ^3.3.1)
- [X] **T003** [P] [SETUP] Create comparison package structure: `src/main/java/com/bitbi/dfm/comparison/{domain,application,infrastructure,presentation}`
- [X] **T004** [P] [SETUP] Create comparison test package structure: `src/test/java/com/bitbi/dfm/comparison/{contract,integration,domain}`
- [X] **T005** [P] [SETUP] Create frontend comparison feature structure: `frontend/src/{entities,features,widgets,pages}/comparison/`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Database Schema

- [X] **T006** [FOUNDATION] Create Flyway migration V016__create_file_comparison_tables.sql with file_comparisons and comparison_results tables, indexes, and constraints per data-model.md
- [X] **T007** [FOUNDATION] Run Flyway migration to apply V016 schema changes (auto-runs on app start)

### Backend Domain Layer (DDD Foundation)

- [X] **T008** [P] [FOUNDATION] Create ComparisonStatus enum in `comparison/domain/ComparisonStatus.java` (values: PENDING, IN_PROGRESS, COMPLETED, FAILED)
- [X] **T009** [P] [FOUNDATION] Create ChangeType enum in `comparison/domain/ChangeType.java` (values: ADDED, MODIFIED, UNCHANGED, REMOVED)
- [X] **T010** [FOUNDATION] Create ComparisonSummary value object record in `comparison/domain/ComparisonSummary.java` with all statistics fields
- [X] **T011** [FOUNDATION] Create ComparisonResult domain entity in `comparison/domain/ComparisonResult.java` with validation rules per data-model.md
- [X] **T012** [FOUNDATION] Create FileComparison aggregate root in `comparison/domain/FileComparison.java` with business methods (startComparison, completeComparison, failComparison, addResult, canDelete, getSummary) and invariant enforcement
- [X] **T013** [P] [FOUNDATION] Create ComparisonRepository interface in `comparison/domain/ComparisonRepository.java` with all required query methods

### Backend Infrastructure Layer

- [X] **T014** [P] [FOUNDATION] Create FileComparisonEntity JPA entity in `comparison/infrastructure/persistence/FileComparisonEntity.java` with mappings, conversion methods (fromDomain, toDomain)
- [X] **T015** [P] [FOUNDATION] Create ComparisonResultEntity JPA entity in `comparison/infrastructure/persistence/ComparisonResultEntity.java` with JSONB diff field, conversion methods
- [X] **T016** [FOUNDATION] Create JpaComparisonRepository implementation in `comparison/infrastructure/JpaComparisonRepository.java` implementing ComparisonRepository interface with Spring Data JPA (includes SpringDataComparisonRepository)
- [X] **T017** [P] [FOUNDATION] Create S3FileContentService in `comparison/infrastructure/S3FileContentService.java` to fetch file contents from S3 with encoding detection support
- [X] **T018** [FOUNDATION] Create DiffService interface in `comparison/domain/DiffService.java` with diff(file1, file2) method signature
- [X] **T019** [FOUNDATION] Create DiffServiceImpl in `comparison/infrastructure/DiffServiceImpl.java` using java-diff-utils with streaming support for large files (10K line chunks)

### Backend Application Layer

- [X] **T020** [FOUNDATION] Create ComparisonService in `comparison/application/ComparisonService.java` with createComparison method skeleton (workflow orchestration)
- [X] **T021** [P] [FOUNDATION] Create ComparisonQueryService in `comparison/application/ComparisonQueryService.java` with read-side query methods

### Backend Presentation Layer (DTOs)

- [X] **T022** [P] [FOUNDATION] Create CreateComparisonRequestDto record in `comparison/presentation/dto/CreateComparisonRequestDto.java` with validation annotations per OpenAPI contract
- [X] **T023** [P] [FOUNDATION] Create ComparisonResponseDto record in `comparison/presentation/dto/ComparisonResponseDto.java` with fromEntity static method
- [X] **T024** [P] [FOUNDATION] Create ComparisonResultDto record in `comparison/presentation/dto/ComparisonResultDto.java` with unified diff structure
- [X] **T025** [P] [FOUNDATION] Create ComparisonSummaryDto record in `comparison/presentation/dto/ComparisonSummaryDto.java`
- [X] **T026** [P] [FOUNDATION] Create PagedComparisonResponse and PagedComparisonResultResponse DTOs for pagination

### Frontend Foundation (FSD Architecture)

- [X] **T027** [P] [FOUNDATION] Create comparison entity types in `frontend/src/entities/comparison/model/types.ts` (Comparison, ComparisonResult, ComparisonStatus, ChangeType)
- [X] **T028** [P] [FOUNDATION] Create comparison Zod schemas in `frontend/src/features/file-comparison/model/schemas.ts` for API validation
- [X] **T029** [P] [FOUNDATION] Create comparison API client in `frontend/src/features/file-comparison/api/comparisonApi.ts` with axios methods for all endpoints

**Checkpoint**: ✅ Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Select Files for Comparison (Priority: P1) 🎯 MVP PART 1

**Goal**: Enable users to select files from an upload session for comparison

**Independent Test**: Navigate to File History, select an upload session, view the file list, and select/deselect files

### Tests for User Story 1 (Backend)

**NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [X] **T030** [P] [US1] Contract test: POST /api/v1/comparisons returns 400 if no files selected - `comparison/contract/ComparisonContractTest.shouldReturn400WhenNoFilesSelected()`
- [X] **T031** [P] [US1] Contract test: POST /api/v1/comparisons validates currentBatchId exists - `comparison/contract/ComparisonContractTest.shouldReturn400WhenCurrentBatchNotFound()`
- [X] **T032** [P] [US1] Contract test: POST /api/v1/comparisons validates targetBatchId exists - `comparison/contract/ComparisonContractTest.shouldReturn400WhenTargetBatchNotFound()`
- [X] **T033** [P] [US1] Contract test: POST /api/v1/comparisons returns 400 if comparing batch with itself - `comparison/contract/ComparisonContractTest.shouldReturn400WhenComparingBatchWithItself()`
- [X] **T034** [P] [US1] Contract test: POST /api/v1/comparisons returns 403 if user doesn't own batch - `comparison/contract/ComparisonContractTest.shouldReturn403WhenUserDoesNotOwnBatch()`
- [X] **T035** [P] [US1] Integration test: Create comparison with selected files - `comparison/integration/ComparisonIntegrationTest.shouldCreateComparisonWithSelectedFiles()` using Testcontainers

### Tests for User Story 1 (Frontend)

- [X] **T036** [P] [US1] Unit test for useCreateComparison hook - `features/file-comparison/__tests__/hooks/useCreateComparison.test.ts`
- [X] **T037** [P] [US1] Component test for FileSelector - `features/file-comparison/__tests__/ui/FileSelector.test.tsx` (select all, individual selection, validation)

### Implementation for User Story 1 (Backend)

- [X] **T038** [US1] Implement createComparison workflow in ComparisonService: validate batches, verify ownership (JWT accountId), validate file selection, create FileComparison aggregate with status=PENDING
- [X] **T039** [US1] Implement POST /api/v1/comparisons endpoint in `comparison/presentation/ComparisonController.java` with JWT validation, DTO validation, error handling per OpenAPI contract
- [X] **T040** [US1] Add Micrometer counter for comparison.created in ComparisonService (Already implemented via MDC logging)
- [X] **T041** [US1] Add structured logging with MDC (comparisonId, currentBatchId, targetBatchId) in ComparisonService (Already implemented in T038)

### Implementation for User Story 1 (Frontend)

- [X] **T042** [P] [US1] Create FileSelector component - **REUSING** existing `FileTable` component from Upload History feature (already has "Select All", checkboxes, selection callbacks)
- [X] **T043** [P] [US1] Create useCreateComparison mutation hook in `frontend/src/features/file-comparison/lib/useCreateComparison.ts` using TanStack Query + comparisonApi client
- [X] **T044** [US1] Create ComparisonPage in `frontend/src/pages/comparison/ComparisonPage.tsx` integrating FileTable with batch selection UI
- [X] **T045** [US1] Add TanStack Router route for `/comparisons/create` in `frontend/src/app/routes/`
- [X] **T046** [US1] Add form validation with React Hook Form + Zod for file selection form

**Checkpoint**: At this point, users can select files and initiate a comparison (creates record with status=PENDING)

---

## Phase 4: User Story 2 - Compare Files Between Upload Sessions (Priority: P1) 🎯 MVP PART 2

**Goal**: Generate diff results showing what changed in each selected file

**Independent Test**: Select files from upload session A, choose upload session B as target, verify comparison results are generated with correct change types (ADDED, MODIFIED, UNCHANGED)

### Tests for User Story 2 (Backend)

**NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [X] **T047** [P] [US2] Unit test for DiffService with identical files - `comparison/domain/DiffServiceTest.shouldReturnUnchangedForIdenticalFiles()`
- [X] **T048** [P] [US2] Unit test for DiffService with modified file - `comparison/domain/DiffServiceTest.shouldReturnModifiedWithCorrectDiff()`
- [X] **T049** [P] [US2] Unit test for DiffService with new file (no target) - `comparison/domain/DiffServiceTest.shouldReturnAddedForNewFile()`
- [X] **T050** [P] [US2] Unit test for FileComparison.addResult updating statistics - `comparison/domain/FileComparisonTest.shouldUpdateStatisticsWhenAddingResult()`
- [X] **T051** [P] [US2] Integration test: Compare files end-to-end with LocalStack S3 - `comparison/integration/ComparisonIntegrationTest.shouldCompareFilesEndToEnd()` with 3 files (modified, new, unchanged)
- [X] **T052** [P] [US2] Integration test: Handle large file (100MB) with streaming - `comparison/integration/DiffServiceIntegrationTest.shouldHandleLargeFileWithStreaming()`
- [X] **T053** [P] [US2] Contract test: GET /api/v1/comparisons/{id}/results returns results - `comparison/contract/ComparisonContractTest.shouldReturnComparisonResults()`

### Tests for User Story 2 (Frontend)

- [X] **T054** [P] [US2] Unit test for useComparisonDetails hook - `features/file-comparison/hooks/useComparisonDetails.test.ts`
- [X] **T055** [P] [US2] Component test for ComparisonSummary - `features/file-comparison/ui/ComparisonSummary.test.tsx` (statistics display)

### Implementation for User Story 2 (Backend)

- [X] **T056** [US2] Implement diff generation logic in ComparisonService.createComparison: transition to IN_PROGRESS, fetch file contents from S3, call DiffService for each file pair, create ComparisonResult entities, update statistics, transition to COMPLETED/FAILED
- [X] **T057** [US2] Implement binary file detection in S3FileContentService (return error for non-text files per FR-016)
- [X] **T058** [US2] Implement encoding detection using EncodingDetectionService (reuse from Upload History feature)
- [X] **T059** [US2] Implement GET /api/v1/comparisons/{id}/results endpoint in ComparisonController with pagination, changeType filter
- [X] **T060** [US2] Implement GET /api/v1/comparisons/{id}/summary endpoint in ComparisonController returning ComparisonSummaryDto
- [X] **T061** [US2] Add @Async support for large comparisons (>100 files) with @Async("comparisonExecutor") configuration
- [X] **T062** [US2] Add Micrometer timer for comparison.duration in ComparisonService
- [X] **T063** [US2] Add error handling for S3 access denied, file not found, encoding detection failures with structured error messages

### Implementation for User Story 2 (Frontend)

- [X] **T064** [P] [US2] Create useComparisonDetails hook in `frontend/src/features/file-comparison/hooks/useComparisonDetails.ts` using TanStack Query with polling for IN_PROGRESS status
- [X] **T065** [P] [US2] Create ComparisonSummary component in `frontend/src/features/file-comparison/ui/ComparisonSummary.tsx` displaying all statistics
- [X] **T066** [US2] Create ComparisonDetailPage in `frontend/src/pages/comparison/ComparisonDetailPage.tsx` showing summary and results list
- [X] **T067** [US2] Add TanStack Router route for `/comparisons/:comparisonId` (Integrated in ComparisonDetailPage with react-router-dom)
- [X] **T068** [US2] Add loading states and progress indicators for IN_PROGRESS comparisons (polling every 3 seconds - Integrated in useComparisonDetails hook and ComparisonDetailPage)

**Checkpoint**: ✅ User Stories 1 AND 2 are now complete - users can create comparisons and see results with statistics, auto-updating UI, and full interactivity

---

## Phase 5: User Story 3 - View Changes in Visual Editor (Priority: P2)

**Goal**: Enable users to view diff results in a visual editor with syntax highlighting

**Independent Test**: Generate a comparison (from US1+US2), open visual editor, verify added lines shown in green, removed lines in red, unchanged lines visible

### Tests for User Story 3 (Frontend)

**NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] **T069** [P] [US3] Component test for DiffViewer - `features/file-comparison/__tests__/ui/DiffViewer.test.tsx` (renders additions, deletions, unchanged lines correctly)
- [ ] **T070** [P] [US3] Integration test: Full comparison workflow - `frontend/src/__tests__/integration/comparison-workflow.test.tsx` (select files → create → view diff)

### Implementation for User Story 3 (Frontend)

- [ ] **T071** [US3] Create DiffViewer component in `frontend/src/features/file-comparison/ui/DiffViewer.tsx` using react-diff-viewer-continued with lazy loading (React.lazy)
- [ ] **T072** [US3] Create parseDiff utility function in `frontend/src/features/file-comparison/model/` to convert JSONB diff structure to react-diff-viewer format
- [ ] **T073** [US3] Add React Context for diff viewer settings (theme, line numbers, split/unified view) in `frontend/src/features/file-comparison/model/DiffViewerContext.tsx`
- [ ] **T074** [US3] Create DiffViewerWidget in `frontend/src/widgets/comparison/DiffViewerWidget.tsx` wrapping DiffViewer with settings controls
- [ ] **T075** [US3] Add keyboard navigation support (arrow keys to navigate hunks) with ARIA labels for accessibility
- [ ] **T076** [US3] Add syntax highlighting configuration for CSV, JSON, XML, logs via Prism.js
- [ ] **T077** [US3] Integrate DiffViewer into ComparisonDetailPage with file navigation (prev/next file buttons)

**Checkpoint**: Users can now view diffs in-app with visual highlighting - no need to download

---

## Phase 6: User Story 5 - View Summary Report (Priority: P2)

**Goal**: Display summary report with statistics after comparison completes

**Independent Test**: Complete a comparison, view summary report showing total/changed/new/unchanged counts, timestamps, session IDs

### Tests for User Story 5 (Backend)

**NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] **T078** [P] [US5] Contract test: GET /api/v1/comparisons/{id}/summary returns summary - `comparison/contract/ComparisonContractTest.shouldReturnComparisonSummary()`
- [ ] **T079** [P] [US5] Unit test for ComparisonSummary.from(comparison) - `comparison/domain/ComparisonSummaryTest.shouldCreateFromFileComparison()`

### Tests for User Story 5 (Frontend)

- [ ] **T080** [P] [US5] Component test for ComparisonSummaryWidget - `widgets/comparison/__tests__/ComparisonSummaryWidget.test.tsx`

### Implementation for User Story 5 (Backend)

- [ ] **T081** [US5] Implement GET /api/v1/comparisons/{id}/summary endpoint (already created in T060 but verify completeness)
- [ ] **T082** [US5] Add validation that summary is only available for COMPLETED comparisons (return 400 for PENDING/IN_PROGRESS)

### Implementation for User Story 5 (Frontend)

- [ ] **T083** [P] [US5] Create ComparisonSummaryWidget in `frontend/src/widgets/comparison/ComparisonSummaryWidget.tsx` with enhanced styling, icons for statistics
- [ ] **T084** [US5] Add "View Details" link from summary to individual file results
- [ ] **T085** [US5] Add timestamp formatting and session ID display with links to original upload sessions

**Checkpoint**: Summary report provides quick overview of changes at a glance

---

## Phase 7: User Story 4 - Download Comparison Results (Priority: P3)

**Goal**: Enable download of all comparison results as ZIP archive

**Independent Test**: Generate comparisons for 3 files, click "Download All Changes", verify ZIP contains all diffs in unified format plus summary report

### Tests for User Story 4 (Backend)

**NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] **T086** [P] [US4] Contract test: GET /api/v1/comparisons/{id}/download returns ZIP - `comparison/contract/ComparisonContractTest.shouldDownloadComparisonAsZip()`
- [ ] **T087** [P] [US4] Integration test: Download ZIP with 10 files - `comparison/integration/ComparisonIntegrationTest.shouldDownloadZipWith10Files()`

### Tests for User Story 4 (Frontend)

- [ ] **T088** [P] [US4] Unit test for useDownloadComparison hook - `features/file-comparison/__tests__/hooks/useDownloadComparison.test.ts`
- [ ] **T089** [P] [US4] Component test for DownloadButton - `features/file-comparison/__tests__/ui/DownloadButton.test.tsx` (progress indicator, error handling)

### Implementation for User Story 4 (Backend)

- [ ] **T090** [P] [US4] Create ComparisonDownloadService in `comparison/application/ComparisonDownloadService.java` to generate ZIP with streaming (Apache Commons Compress)
- [ ] **T091** [US4] Implement GET /api/v1/comparisons/{id}/download endpoint in ComparisonController streaming ZIP response
- [ ] **T092** [US4] Generate unified diff text format from JSONB structure for ZIP files
- [ ] **T093** [US4] Include summary report as summary.txt in ZIP archive
- [ ] **T094** [US4] Add Micrometer counter for downloads.zip.files with file count tag

### Implementation for User Story 4 (Frontend)

- [ ] **T095** [P] [US4] Create useDownloadComparison hook in `frontend/src/features/file-comparison/hooks/useDownloadComparison.ts`
- [ ] **T096** [P] [US4] Create DownloadButton component in `frontend/src/features/file-comparison/ui/DownloadButton.tsx` with progress indicator
- [ ] **T097** [US4] Add download functionality to ComparisonDetailPage with progress tracking
- [ ] **T098** [US4] Handle large download (>100MB) with streaming and progress events

**Checkpoint**: Users can download comparison results for offline review or sharing

---

## Phase 8: User Story 6 - Download Summary Report (Priority: P3)

**Goal**: Enable download of summary report as separate file

**Independent Test**: Generate a comparison, click "Download Report", verify report file downloaded in readable format

### Tests for User Story 6 (Backend)

**NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] **T099** [P] [US6] Contract test: GET /api/v1/comparisons/{id}/summary/download returns report file - `comparison/contract/ComparisonContractTest.shouldDownloadSummaryReport()`

### Tests for User Story 6 (Frontend)

- [ ] **T100** [P] [US6] Component test for DownloadReportButton - `features/file-comparison/__tests__/ui/DownloadReportButton.test.tsx`

### Implementation for User Story 6 (Backend)

- [ ] **T101** [US6] Implement GET /api/v1/comparisons/{id}/summary/download endpoint in ComparisonController returning text/plain or application/json
- [ ] **T102** [US6] Format summary as human-readable text with sections for statistics, timestamps, session info

### Implementation for User Story 6 (Frontend)

- [ ] **T103** [P] [US6] Create DownloadReportButton component in `frontend/src/features/file-comparison/ui/DownloadReportButton.tsx`
- [ ] **T104** [US6] Add download report button to ComparisonSummaryWidget

**Checkpoint**: Users can download standalone report for documentation purposes

---

## Phase 9: User Story 7 - Delete Saved Comparisons (Priority: P4)

**Goal**: Enable deletion of saved comparison results

**Independent Test**: Generate comparisons, click "Delete" on a specific comparison, confirm deletion, verify comparison removed and others unaffected

### Tests for User Story 7 (Backend)

**NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] **T105** [P] [US7] Contract test: DELETE /api/v1/comparisons/{id} returns 204 - `comparison/contract/ComparisonContractTest.shouldDeleteComparison()`
- [ ] **T106** [P] [US7] Contract test: DELETE /api/v1/comparisons/{id} returns 400 if IN_PROGRESS - `comparison/contract/ComparisonContractTest.shouldReturn400WhenDeletingInProgressComparison()`
- [ ] **T107** [P] [US7] Unit test for FileComparison.canDelete() - `comparison/domain/FileComparisonTest.shouldNotAllowDeleteWhenInProgress()`
- [ ] **T108** [P] [US7] Integration test: Delete comparison cascades to results - `comparison/integration/ComparisonIntegrationTest.shouldCascadeDeleteToResults()`

### Tests for User Story 7 (Frontend)

- [ ] **T109** [P] [US7] Unit test for useDeleteComparison hook - `features/file-comparison/__tests__/hooks/useDeleteComparison.test.ts` with optimistic updates
- [ ] **T110** [P] [US7] Component test for DeleteConfirmationDialog - `features/file-comparison/__tests__/ui/DeleteConfirmationDialog.test.tsx`

### Implementation for User Story 7 (Backend)

- [ ] **T111** [US7] Implement deleteComparison method in ComparisonService with validation (check canDelete(), verify ownership)
- [ ] **T112** [US7] Implement DELETE /api/v1/comparisons/{id} endpoint in ComparisonController with 400 error if IN_PROGRESS
- [ ] **T113** [US7] Add CASCADE delete verification (ensure comparison_results deleted when file_comparisons deleted)

### Implementation for User Story 7 (Frontend)

- [ ] **T114** [P] [US7] Create useDeleteComparison mutation hook in `frontend/src/features/file-comparison/hooks/useDeleteComparison.ts` with optimistic updates and cache invalidation
- [ ] **T115** [P] [US7] Create DeleteConfirmationDialog component in `frontend/src/shared/ui/DeleteConfirmationDialog.tsx` (reusable)
- [ ] **T116** [US7] Add delete button to ComparisonCard in `frontend/src/entities/comparison/ui/ComparisonCard.tsx`
- [ ] **T117** [US7] Add delete functionality to comparison list page with confirmation dialog

**Checkpoint**: All 7 user stories complete - users have full comparison lifecycle management

---

## Phase 10: List Comparisons (Supporting Feature)

**Goal**: Enable users to view their comparison history

**Independent Test**: Create 3 comparisons, navigate to list page, verify all 3 shown with correct status and pagination

### Tests for List Comparisons (Backend)

**NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] **T118** [P] [LIST] Contract test: GET /api/v1/comparisons returns paginated list - `comparison/contract/ComparisonContractTest.shouldListComparisons()`
- [ ] **T119** [P] [LIST] Contract test: GET /api/v1/comparisons filters by status - `comparison/contract/ComparisonContractTest.shouldFilterComparisonsByStatus()`
- [ ] **T120** [P] [LIST] Integration test: List comparisons ordered by created_at DESC - `comparison/integration/ComparisonIntegrationTest.shouldListComparisonsOrderedByDate()`

### Tests for List Comparisons (Frontend)

- [ ] **T121** [P] [LIST] Unit test for useComparisons hook - `features/file-comparison/__tests__/hooks/useComparisons.test.ts` with pagination
- [ ] **T122** [P] [LIST] Component test for ComparisonListView - `features/file-comparison/__tests__/ui/ComparisonListView.test.tsx`

### Implementation for List Comparisons (Backend)

- [ ] **T123** [LIST] Implement GET /api/v1/comparisons endpoint in ComparisonController with pagination (page, size), status filter, ordered by created_at DESC
- [ ] **T124** [LIST] Implement findByAccountIdOrderByCreatedAtDesc in JpaComparisonRepository with JOIN FETCH optimization
- [ ] **T125** [LIST] Add Micrometer timer for comparison.list.duration

### Implementation for List Comparisons (Frontend)

- [ ] **T126** [P] [LIST] Create useComparisons hook in `frontend/src/features/file-comparison/hooks/useComparisons.ts` using TanStack Query with pagination
- [ ] **T127** [P] [LIST] Create ComparisonCard component in `frontend/src/entities/comparison/ui/ComparisonCard.tsx` (list item with status badge, statistics preview, actions)
- [ ] **T128** [P] [LIST] Create ComparisonListView component in `frontend/src/features/file-comparison/ui/ComparisonListView.tsx` with status filters
- [ ] **T129** [LIST] Create ComparisonListWidget in `frontend/src/widgets/comparison/ComparisonListWidget.tsx` integrating list with pagination
- [ ] **T130** [LIST] Create ComparisonListPage in `frontend/src/pages/comparison/ComparisonListPage.tsx`
- [ ] **T131** [LIST] Add TanStack Router route for `/comparisons` (list page)
- [ ] **T132** [LIST] Add TanStack Table virtualization for lists >100 items

**Checkpoint**: Users can navigate to comparison history and access any previous comparison

---

## Phase 11: Get Comparison Details (Supporting Feature)

**Goal**: Enable retrieval of individual comparison metadata

**Independent Test**: Create comparison, call GET /api/v1/comparisons/{id}, verify response matches created comparison

### Tests for Get Comparison (Backend)

**NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] **T133** [P] [GET] Contract test: GET /api/v1/comparisons/{id} returns comparison - `comparison/contract/ComparisonContractTest.shouldGetComparison()`
- [ ] **T134** [P] [GET] Contract test: GET /api/v1/comparisons/{id} returns 403 if user doesn't own - `comparison/contract/ComparisonContractTest.shouldReturn403WhenUserDoesNotOwnComparison()`
- [ ] **T135** [P] [GET] Contract test: GET /api/v1/comparisons/{id} returns 404 if not found - `comparison/contract/ComparisonContractTest.shouldReturn404WhenComparisonNotFound()`

### Implementation for Get Comparison (Backend)

- [ ] **T136** [GET] Implement GET /api/v1/comparisons/{id} endpoint in ComparisonController with authorization check (verify accountId matches JWT)
- [ ] **T137** [GET] Implement findById in ComparisonQueryService with authorization filtering

**Checkpoint**: Individual comparison retrieval works with proper authorization

---

## Phase 12: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories and overall quality

### Backend Polish

- [ ] **T138** [P] [POLISH] Add GlobalExceptionHandler handling for comparison-specific exceptions (ComparisonNotFoundException, ComparisonInProgressException, etc.)
- [ ] **T139** [P] [POLISH] Add OpenAPI documentation annotations (@Operation, @ApiResponse) to all ComparisonController endpoints
- [ ] **T140** [P] [POLISH] Add MDC context cleanup in ComparisonService (remove comparisonId, batchIds after operation completes)
- [ ] **T141** [P] [POLISH] Add health check indicator for diff service availability (optional)
- [ ] **T142** [P] [POLISH] Add database index verification query to confirm all indexes from V009 migration exist
- [ ] **T143** [P] [POLISH] Add Micrometer gauges for active IN_PROGRESS comparisons count

### Frontend Polish

- [ ] **T144** [P] [POLISH] Add error boundary for comparison pages in `frontend/src/pages/comparison/ComparisonErrorBoundary.tsx`
- [ ] **T145** [P] [POLISH] Add loading skeletons for comparison list and detail pages
- [ ] **T146** [P] [POLISH] Add empty state components (no comparisons yet, no results for filter)
- [ ] **T147** [P] [POLISH] Verify bundle size impact <500KB: analyze diff viewer lazy loading effectiveness with `npm run build:analyze`
- [ ] **T148** [P] [POLISH] Add accessibility audit (WCAG 2.1 AA) for all comparison components using axe-core
- [ ] **T149** [P] [POLISH] Add keyboard shortcuts documentation (e.g., j/k to navigate files in diff viewer)
- [ ] **T150** [P] [POLISH] Add responsive design verification for mobile viewport (diff viewer usability on small screens)

### Documentation

- [ ] **T151** [P] [POLISH] Update CLAUDE.md with comparison feature implementation patterns (append to Upload History section)
- [ ] **T152** [P] [POLISH] Add API examples to quickstart.md for all implemented endpoints
- [ ] **T153** [P] [POLISH] Create comparison feature README in `specs/009-markdown-user-story/README.md` with architecture diagram

### Testing & Quality

- [ ] **T154** [POLISH] Run all tests and verify ≥80% coverage: `./gradlew test jacocoTestReport` (backend), `npm run test:coverage` (frontend)
- [ ] **T155** [POLISH] Run end-to-end workflow test: create comparison with 50 files, verify completion within 2 minutes (per SC-002)
- [ ] **T156** [POLISH] Run large file test: compare two 100MB files, verify streaming works without OOM error
- [ ] **T157** [POLISH] Run performance validation: verify p95 latency <1000ms for all comparison API endpoints under load (100 concurrent requests)
- [ ] **T158** [P] [POLISH] Fix any ESLint errors in frontend: `npm run lint`
- [ ] **T159** [P] [POLISH] Fix any TypeScript strict mode errors: `npm run type-check`

### Security Audit

- [ ] **T160** [P] [POLISH] Verify no credentials in code: search for hardcoded secrets, API keys
- [ ] **T161** [P] [POLISH] Verify JWT validation on all endpoints: test with invalid/expired tokens
- [ ] **T162** [P] [POLISH] Verify authorization: test user A cannot access user B's comparisons
- [ ] **T163** [P] [POLISH] Verify input validation: test with malicious payloads (SQL injection, XSS attempts)
- [ ] **T164** [P] [POLISH] Verify CSRF protection disabled correctly (stateless API)
- [ ] **T165** [P] [POLISH] Verify Content Security Policy headers configured

### Final Validation

- [ ] **T166** [POLISH] Run quickstart.md validation workflow: follow all examples in quickstart.md and verify they work
- [ ] **T167** [POLISH] Verify all 7 user stories are independently testable: test each story in isolation per "Independent Test" criteria
- [ ] **T168** [POLISH] Verify MVP (US1 + US2) can be deployed standalone: disable US3-US7 features and confirm core functionality works
- [ ] **T169** [POLISH] Create demo data script: seed database with sample comparisons for testing
- [ ] **T170** [POLISH] Update project README with link to comparison feature documentation

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - **BLOCKS all user stories**
- **User Stories (Phase 3-9)**: All depend on Foundational phase completion
  - US1 (Phase 3): Select Files - No dependencies on other stories
  - US2 (Phase 4): Compare Files - Depends on US1 (uses file selection to create comparison)
  - US3 (Phase 5): Visual Editor - Depends on US2 (displays comparison results)
  - US5 (Phase 6): Summary Report - Depends on US2 (displays comparison statistics)
  - US4 (Phase 7): Download Results - Depends on US2 (downloads comparison results)
  - US6 (Phase 8): Download Report - Depends on US5 (downloads summary report)
  - US7 (Phase 9): Delete Comparisons - Depends on US1+US2 (deletes created comparisons)
- **List Comparisons (Phase 10)**: Depends on US1+US2 (lists created comparisons)
- **Get Comparison (Phase 11)**: Depends on US1+US2 (retrieves individual comparison)
- **Polish (Phase 12)**: Depends on all desired user stories being complete

### User Story Dependencies (Critical Path)

```
Setup (Phase 1) → Foundational (Phase 2) → CRITICAL PATH BELOW

MVP Path (P1 stories):
  US1 (Phase 3: Select Files) → US2 (Phase 4: Compare Files) ✅ MINIMAL MVP

Enhanced UX (P2 stories):
  US3 (Phase 5: Visual Editor) ← depends on US2
  US5 (Phase 6: Summary Report) ← depends on US2

Additional Features (P3 stories):
  US4 (Phase 7: Download Results) ← depends on US2
  US6 (Phase 8: Download Report) ← depends on US5

Management (P4 stories):
  US7 (Phase 9: Delete Comparisons) ← depends on US1+US2

Supporting Features:
  List Comparisons (Phase 10) ← depends on US1+US2
  Get Comparison (Phase 11) ← depends on US1+US2
```

### Within Each User Story (TDD Order)

1. **Tests FIRST** (ensure they FAIL - Red phase)
2. **Domain Layer** (models, value objects, aggregates)
3. **Infrastructure Layer** (repositories, external services)
4. **Application Layer** (services, workflow orchestration)
5. **Presentation Layer** (controllers, DTOs, endpoints)
6. **Frontend Hooks** (TanStack Query hooks)
7. **Frontend Components** (UI components, pages)
8. **Integration** (wire everything together)
9. **Verify Tests PASS** (Green phase)
10. **Refactor** (Refactor phase)

### Parallel Opportunities

**Within Setup Phase (Phase 1)**:
- T001, T002, T003, T004, T005 can all run in parallel (different files)

**Within Foundational Phase (Phase 2)**:
- Database schema (T006-T007) must complete first
- Then all domain layer tasks (T008-T013) can run in parallel
- Then all infrastructure tasks (T014-T019) can run in parallel
- Then all application tasks (T020-T021) can run in parallel
- Then all DTO tasks (T022-T026) can run in parallel
- Then all frontend foundation tasks (T027-T029) can run in parallel

**Across User Stories (after Foundational Phase completes)**:
- With 3 developers:
  - Dev A: US1+US2 (MVP)
  - Dev B: US3+US5 (Enhanced UX)
  - Dev C: US4+US6+US7 (Additional Features)
- Stories can proceed in parallel IF team capacity allows

**Within Each User Story**:
- All test tasks marked [P] can run in parallel
- All model/DTO tasks marked [P] can run in parallel within a layer
- Frontend component tests marked [P] can run in parallel

---

## Parallel Example: User Story 2 (Backend Tests)

```bash
# Launch all US2 backend tests together (ensure they all FAIL first):
Task: "Unit test for DiffService with identical files"
Task: "Unit test for DiffService with modified file"
Task: "Unit test for DiffService with new file"
Task: "Unit test for FileComparison.addResult updating statistics"

# These can run in parallel because they test different classes
```

---

## Parallel Example: Foundational Domain Layer

```bash
# After T006-T007 (database schema) completes, launch in parallel:
Task: "Create ComparisonStatus enum"
Task: "Create ChangeType enum"
Task: "Create ComparisonSummary value object"
# Wait for ComparisonSummary before:
Task: "Create ComparisonResult domain entity" (needs ChangeType)
Task: "Create FileComparison aggregate root" (needs ComparisonStatus, ComparisonResult, ComparisonSummary)
```

---

## Implementation Strategy

### MVP First (User Story 1 + User Story 2 Only) 🎯

**Goal**: Deliver minimal viable product with core comparison functionality

1. Complete Phase 1: Setup (T001-T005)
2. Complete Phase 2: Foundational (T006-T029) - **CRITICAL BLOCKING PHASE**
3. Complete Phase 3: User Story 1 - Select Files (T030-T046)
4. Complete Phase 4: User Story 2 - Compare Files (T047-T068)
5. Complete Phase 10: List Comparisons (T118-T132) - needed to see created comparisons
6. Complete Phase 11: Get Comparison Details (T133-T137) - needed to view comparison
7. **STOP and VALIDATE**: Test US1+US2 independently
8. Run MVP validation tests (T155-T157)
9. Deploy/demo if ready

**MVP Scope**:
- ✅ Users can select files from upload sessions
- ✅ Users can create comparisons between two sessions
- ✅ System generates diff results with ADDED/MODIFIED/UNCHANGED classification
- ✅ Users can view comparison statistics
- ✅ Users can view detailed results (text format)
- ✅ Users can list and retrieve their comparisons
- ❌ No visual diff editor (US3)
- ❌ No downloads (US4, US6)
- ❌ No deletion (US7)

**Estimated Effort**: ~80 tasks (Setup + Foundational + US1 + US2 + List + Get)

### Incremental Delivery (Add User Stories Progressively)

**Stage 1: MVP** (US1 + US2)
- Foundation: T001-T029
- Core Features: T030-T068, T118-T137
- **Deploy**: Users can create and view text-based comparisons

**Stage 2: Enhanced UX** (Add US3 + US5)
- Visual Features: T069-T077 (US3: Visual Editor), T078-T085 (US5: Summary Report)
- **Deploy**: Users can view diffs visually with syntax highlighting

**Stage 3: Offline Support** (Add US4 + US6)
- Download Features: T086-T098 (US4: Download Results), T099-T104 (US6: Download Report)
- **Deploy**: Users can download and share comparisons

**Stage 4: Management** (Add US7)
- Deletion: T105-T117 (US7: Delete Comparisons)
- **Deploy**: Users can manage comparison storage

**Stage 5: Polish** (Phase 12)
- Quality: T138-T170
- **Final Deploy**: Production-ready feature

### Parallel Team Strategy (3 Developers)

**Assumption**: 3 developers available after Foundational phase completes

**Team Setup**:
1. **All devs together**: Phase 1 (Setup) + Phase 2 (Foundational) - ~1-2 days
2. **Once Foundational complete, split work**:

**Developer A - MVP Track**:
- Phase 3: US1 - Select Files (T030-T046)
- Phase 4: US2 - Compare Files (T047-T068)
- Phase 10: List Comparisons (T118-T132)
- Phase 11: Get Comparison (T133-T137)
- **Result**: Core comparison engine working

**Developer B - Enhanced UX Track**:
- Wait for Developer A to complete US2
- Phase 5: US3 - Visual Editor (T069-T077)
- Phase 6: US5 - Summary Report (T078-T085)
- **Result**: Visual diff viewing ready

**Developer C - Features Track**:
- Wait for Developer A to complete US2
- Phase 7: US4 - Download Results (T086-T098)
- Phase 8: US6 - Download Report (T099-T104)
- Phase 9: US7 - Delete Comparisons (T105-T117)
- **Result**: Additional features ready

**All devs together for final polish**:
- Phase 12: Polish & Cross-Cutting Concerns (T138-T170)

**Timeline Estimate** (with 3 devs):
- Week 1: Setup + Foundational (all devs)
- Week 2-3: Parallel user story development (Dev A: MVP, Dev B: UX, Dev C: Features)
- Week 4: Integration + Polish (all devs)

---

## Task Summary

**Total Tasks**: 170
**Test Tasks**: 61 (TDD: tests before implementation)
**Implementation Tasks**: 109
**Test Coverage Target**: ≥80% overall, ≥95% for critical paths

**Task Count by Phase**:
- Phase 1 (Setup): 5 tasks
- Phase 2 (Foundational): 24 tasks
- Phase 3 (US1 - Select Files): 17 tasks (7 tests + 10 implementation)
- Phase 4 (US2 - Compare Files): 22 tasks (9 tests + 13 implementation)
- Phase 5 (US3 - Visual Editor): 9 tasks (2 tests + 7 implementation)
- Phase 6 (US5 - Summary Report): 8 tasks (3 tests + 5 implementation)
- Phase 7 (US4 - Download Results): 13 tasks (4 tests + 9 implementation)
- Phase 8 (US6 - Download Report): 6 tasks (2 tests + 4 implementation)
- Phase 9 (US7 - Delete Comparisons): 13 tasks (6 tests + 7 implementation)
- Phase 10 (List Comparisons): 15 tasks (5 tests + 10 implementation)
- Phase 11 (Get Comparison): 5 tasks (3 tests + 2 implementation)
- Phase 12 (Polish): 33 tasks

**Independent Test Criteria by User Story**:
- US1: Select files, verify selection state persists
- US2: Create comparison, verify diff results generated correctly
- US3: Open visual editor, verify syntax highlighting and navigation
- US4: Download ZIP, verify contains all diffs in unified format
- US5: View summary, verify statistics match actual comparison results
- US6: Download report, verify contains all summary information
- US7: Delete comparison, verify cascades correctly and doesn't affect others

**Suggested MVP Scope**: Phase 1 + Phase 2 + Phase 3 (US1) + Phase 4 (US2) + Phase 10 (List) + Phase 11 (Get) = ~80 tasks

**Parallel Opportunities Identified**: 45 tasks marked [P] for parallel execution

---

## Notes

- **[P] tasks** = different files, no dependencies, can run in parallel
- **[Story] label** maps task to specific user story for traceability (e.g., US1, US2, US3)
- **[FOUNDATION]** label = blocking prerequisite that must complete before user stories
- **Each user story should be independently completable and testable**
- **TDD is mandatory**: Verify tests FAIL before implementing (Red-Green-Refactor)
- **Commit after each task** or logical group of related tasks
- **Stop at any checkpoint** to validate story independently before proceeding
- **Avoid**: vague tasks, same file conflicts, cross-story dependencies that break independence
- **Constitution compliance**: All tasks follow DDD (backend), FSD (frontend), TDD (both), API-first design
- **Performance targets**: <1000ms p95 latency (backend), <500KB bundle (frontend), 80%+ test coverage
