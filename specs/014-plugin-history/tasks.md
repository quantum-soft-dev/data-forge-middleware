# Tasks: Plugin History Management

**Input**: Design documents from `/specs/014-plugin-history/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Included (test-first approach specified in constitution check)

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Backend**: `src/main/java/com/bitbi/dfm/plugin/`
- **Frontend**: `frontend/src/features/plugin-history/`
- **Tests**: `src/test/java/com/bitbi/dfm/plugin/`
- **Migrations**: `src/main/resources/db/migration/`

---

## Phase 1: Setup

**Purpose**: Database migration and shared infrastructure for all user stories

- [x] T001 Create database migration V14__add_plugin_history_fields.sql in src/main/resources/db/migration/
- [x] T002 [P] Add new action types (PLUGIN_HISTORY_CLEARED, SQL_REGENERATION_*) to PluginActionType enum in src/main/java/com/bitbi/dfm/plugin/domain/PluginActionType.java
- [x] T003 [P] Add superseded and supersededBy fields to PluginSqlGeneration entity in src/main/java/com/bitbi/dfm/plugin/domain/PluginSqlGeneration.java
- [x] T004 [P] Create DTO records (SqlGenerationSummaryDto, SqlContentPageDto, HistoryClearSummaryDto, HistoryClearResultDto, RegenerateResultDto) in src/main/java/com/bitbi/dfm/plugin/presentation/dto/

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core repository queries and service scaffolding that ALL user stories depend on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T005 Add new repository query methods to PluginSqlGenerationRepository interface in src/main/java/com/bitbi/dfm/plugin/domain/PluginSqlGenerationRepository.java
- [x] T006 Implement new repository queries in JpaPluginSqlGenerationRepository in src/main/java/com/bitbi/dfm/plugin/infrastructure/persistence/JpaPluginSqlGenerationRepository.java
- [x] T007 Create PluginHistoryService class scaffold in src/main/java/com/bitbi/dfm/plugin/application/PluginHistoryService.java
- [x] T008 [P] Create frontend feature directory structure and types in frontend/src/features/plugin-history/model/types.ts
- [x] T009 [P] Create API client in frontend/src/features/plugin-history/api/plugin-history.api.ts
- [x] T010 [P] Create TanStack Query hooks in frontend/src/features/plugin-history/api/plugin-history.queries.ts

**Checkpoint**: Foundation ready - user story implementation can now begin

---

## Phase 3: User Story 1 - View SQL Generation History (Priority: P1) 🎯 MVP

**Goal**: Administrators can view complete SQL generation history with paginated SQL preview and download

**Independent Test**: Navigate to account's plugin history, verify list loads with metadata, click entry to see SQL preview, download file

### Tests for User Story 1

- [x] T011 [P] [US1] Contract test for GET /generations endpoint in src/test/java/com/bitbi/dfm/plugin/contract/PluginHistoryAdminControllerTest.java
- [x] T012 [P] [US1] Contract test for GET /generations/{id}/content endpoint in src/test/java/com/bitbi/dfm/plugin/contract/PluginHistoryAdminControllerTest.java
- [x] T013 [P] [US1] Contract test for GET /generations/{id}/download endpoint in src/test/java/com/bitbi/dfm/plugin/contract/PluginHistoryAdminControllerTest.java
- [x] T014 [P] [US1] Unit test for PluginHistoryService.listGenerations() in src/test/java/com/bitbi/dfm/plugin/unit/PluginHistoryServiceTest.java
- [x] T015 [P] [US1] Unit test for PluginHistoryService.getSqlContent() with pagination in src/test/java/com/bitbi/dfm/plugin/unit/PluginHistoryServiceTest.java
- [x] T016 [US1] Integration test for view history flow in src/test/java/com/bitbi/dfm/plugin/integration/PluginHistoryIntegrationTest.java

### Implementation for User Story 1

- [x] T017 [US1] Implement PluginHistoryService.listGenerations() with pagination in src/main/java/com/bitbi/dfm/plugin/application/PluginHistoryService.java
- [x] T018 [US1] Implement PluginHistoryService.getGeneration() for single record in src/main/java/com/bitbi/dfm/plugin/application/PluginHistoryService.java
- [x] T019 [US1] Implement PluginHistoryService.getSqlContent() with statement parsing and pagination in src/main/java/com/bitbi/dfm/plugin/application/PluginHistoryService.java
- [x] T020 [US1] Implement PluginHistoryService.downloadSqlFile() returning file content in src/main/java/com/bitbi/dfm/plugin/application/PluginHistoryService.java
- [x] T021 [US1] Add GET /plugins/{pluginId}/accounts/{accountId}/generations endpoint to PluginAdminController in src/main/java/com/bitbi/dfm/plugin/presentation/PluginAdminController.java
- [x] T022 [US1] Add GET /plugins/{pluginId}/accounts/{accountId}/generations/{id} endpoint to PluginAdminController in src/main/java/com/bitbi/dfm/plugin/presentation/PluginAdminController.java
- [x] T023 [US1] Add GET /plugins/{pluginId}/accounts/{accountId}/generations/{id}/content endpoint to PluginAdminController in src/main/java/com/bitbi/dfm/plugin/presentation/PluginAdminController.java
- [x] T024 [US1] Add GET /plugins/{pluginId}/accounts/{accountId}/generations/{id}/download endpoint to PluginAdminController in src/main/java/com/bitbi/dfm/plugin/presentation/PluginAdminController.java
- [x] T025 [P] [US1] Create SqlGenerationList.tsx component in frontend/src/features/plugin-history/ui/GenerationListTable.tsx
- [x] T026 [P] [US1] Create SqlPreview.tsx component with syntax highlighting in frontend/src/features/plugin-history/ui/SqlContentViewer.tsx
- [x] T027 [US1] Create PluginHistoryWidget.tsx container in frontend/src/widgets/plugin-history/PluginHistoryWidget.tsx
- [x] T028 [US1] Create PluginHistoryPage.tsx admin page in frontend/src/pages/admin/plugins/PluginHistoryPage.tsx
- [x] T029 [US1] Add route for plugin history page in frontend/src/app/router.tsx

**Checkpoint**: User Story 1 complete - admin can view generation history, see SQL preview, and download files

---

## Phase 4: User Story 2 - Clear Plugin History (Priority: P2)

**Goal**: Administrators can clear all plugin history (DB records, S3 files) and deactivate plugin connection

**Independent Test**: Get clear summary, confirm deletion, verify records and S3 files removed, verify plugin deactivated

### Tests for User Story 2

- [x] T030 [P] [US2] Contract test for GET /history/summary endpoint in src/test/java/com/bitbi/dfm/plugin/contract/PluginHistoryAdminControllerTest.java
- [x] T031 [P] [US2] Contract test for DELETE /history endpoint in src/test/java/com/bitbi/dfm/plugin/contract/PluginHistoryAdminControllerTest.java
- [x] T032 [P] [US2] Unit test for PluginHistoryService.getHistorySummary() in src/test/java/com/bitbi/dfm/plugin/unit/PluginHistoryServiceTest.java
- [x] T033 [P] [US2] Unit test for PluginHistoryService.clearHistory() with S3 deletion in src/test/java/com/bitbi/dfm/plugin/unit/PluginHistoryServiceTest.java
- [x] T034 [US2] Integration test for clear history flow with S3 in src/test/java/com/bitbi/dfm/plugin/integration/PluginHistoryIntegrationTest.java

### Implementation for User Story 2

- [ ] T035 [US2] Add bulk delete method to S3SqlFileStorageService in src/main/java/com/bitbi/dfm/plugin/infrastructure/storage/S3SqlFileStorageService.java (using existing deleteFile in loop instead)
- [x] T036 [US2] Implement PluginHistoryService.getHistorySummary() with batch check in src/main/java/com/bitbi/dfm/plugin/application/PluginHistoryService.java
- [x] T037 [US2] Implement PluginHistoryService.clearHistory() with S3 deletion and plugin deactivation in src/main/java/com/bitbi/dfm/plugin/application/PluginHistoryService.java
- [x] T038 [US2] Add audit logging for PLUGIN_HISTORY_CLEARED action in PluginAuditService in src/main/java/com/bitbi/dfm/plugin/application/PluginAuditService.java
- [x] T039 [US2] Add GET /plugins/{pluginId}/accounts/{accountId}/history/summary endpoint to PluginAdminController in src/main/java/com/bitbi/dfm/plugin/presentation/PluginAdminController.java
- [x] T040 [US2] Add DELETE /plugins/{pluginId}/accounts/{accountId}/history endpoint to PluginAdminController in src/main/java/com/bitbi/dfm/plugin/presentation/PluginAdminController.java
- [x] T041 [P] [US2] Create ClearHistoryDialog.tsx component with confirmation in frontend/src/features/plugin-history/ui/ClearHistoryDialog.tsx
- [x] T042 [US2] Integrate ClearHistoryDialog into PluginHistoryWidget in frontend/src/widgets/plugin-history/PluginHistoryWidget.tsx
- [x] T043 [US2] Add clear history mutation to TanStack Query hooks in frontend/src/features/plugin-history/api/plugin-history.queries.ts

**Checkpoint**: User Story 2 complete - admin can clear history with confirmation, S3 files deleted, plugin deactivated

---

## Phase 5: User Story 3 - Regenerate SQL for a Batch (Priority: P3)

**Goal**: Administrators can regenerate SQL for a specific batch, preserving original as superseded

**Independent Test**: Click regenerate on a generation, verify new generation created, original marked superseded, audit log created

### Tests for User Story 3

- [x] T044 [P] [US3] Contract test for POST /generations/{id}/regenerate endpoint in src/test/java/com/bitbi/dfm/plugin/contract/PluginHistoryAdminControllerTest.java
- [x] T045 [P] [US3] Unit test for PluginHistoryService.regenerateSql() in src/test/java/com/bitbi/dfm/plugin/unit/PluginHistoryServiceTest.java
- [x] T046 [P] [US3] Unit test for superseded flag handling in src/test/java/com/bitbi/dfm/plugin/unit/PluginHistoryServiceTest.java
- [x] T047 [US3] Integration test for regeneration flow in src/test/java/com/bitbi/dfm/plugin/integration/PluginHistoryIntegrationTest.java

### Implementation for User Story 3

- [x] T048 [US3] Implement PluginHistoryService.regenerateSql() calling SqlGenerationService in src/main/java/com/bitbi/dfm/plugin/application/PluginHistoryService.java
- [x] T049 [US3] Add regenerateForBatch() method to SqlGenerationService in src/main/java/com/bitbi/dfm/plugin/application/SqlGenerationService.java
- [x] T050 [US3] Add markAsSuperseded() method to PluginSqlGeneration entity in src/main/java/com/bitbi/dfm/plugin/domain/PluginSqlGeneration.java
- [x] T051 [US3] Add audit logging for SQL_REGENERATION_* actions in PluginAuditService in src/main/java/com/bitbi/dfm/plugin/application/PluginAuditService.java
- [x] T052 [US3] Add POST /plugins/{pluginId}/accounts/{accountId}/generations/{id}/regenerate endpoint to PluginAdminController in src/main/java/com/bitbi/dfm/plugin/presentation/PluginAdminController.java
- [x] T053 [P] [US3] Create RegenerateDialog.tsx component in frontend/src/features/plugin-history/ui/RegenerateDialog.tsx
- [x] T054 [US3] Integrate RegenerateButton into GenerationListTable in frontend/src/features/plugin-history/ui/GenerationListTable.tsx
- [x] T055 [US3] Add regenerate mutation to TanStack Query hooks in frontend/src/features/plugin-history/api/plugin-history.queries.ts

**Checkpoint**: User Story 3 complete - admin can regenerate SQL, original preserved as superseded

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [ ] T056 [P] Add frontend component tests in frontend/src/features/plugin-history/__tests__/PluginHistoryWidget.test.tsx
- [ ] T057 [P] Add error boundary and loading states to PluginHistoryWidget in frontend/src/widgets/plugin-history/PluginHistoryWidget.tsx
- [ ] T058 Run all backend tests with ./gradlew test and fix any failures
- [ ] T059 Run frontend tests with npm test and fix any failures
- [ ] T060 Validate endpoints against OpenAPI contract in specs/014-plugin-history/contracts/plugin-history-api.yaml
- [ ] T061 Run quickstart.md validation scenarios manually

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3-5)**: All depend on Foundational phase completion
  - User stories can proceed in parallel (if staffed)
  - Or sequentially in priority order (P1 → P2 → P3)
- **Polish (Phase 6)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) - No dependencies on other stories
- **User Story 2 (P2)**: Can start after Foundational (Phase 2) - Uses same service but independent endpoints
- **User Story 3 (P3)**: Can start after Foundational (Phase 2) - Uses superseded fields from Setup

### Within Each User Story

- Tests MUST be written and FAIL before implementation (test-first)
- Backend before frontend
- Service methods before controller endpoints
- Controller endpoints before frontend components
- Story complete before moving to next priority

### Parallel Opportunities

**Phase 1 (Setup)**:
```
T002, T003, T004 can run in parallel (different files)
```

**Phase 2 (Foundational)**:
```
T008, T009, T010 can run in parallel (frontend files)
```

**Phase 3 (User Story 1)**:
```
Tests: T011, T012, T013, T014, T015 can run in parallel
Implementation: T025, T026 can run in parallel (frontend components)
```

**Phase 4 (User Story 2)**:
```
Tests: T030, T031, T032, T033 can run in parallel
Implementation: T041 runs in parallel with backend work
```

**Phase 5 (User Story 3)**:
```
Tests: T044, T045, T046 can run in parallel
Implementation: T053 runs in parallel with backend work
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T004)
2. Complete Phase 2: Foundational (T005-T010)
3. Complete Phase 3: User Story 1 (T011-T029)
4. **STOP and VALIDATE**: Test history viewing independently
5. Deploy/demo if ready - admins can now view all SQL generation history

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 → Test independently → Deploy/Demo (MVP!)
3. Add User Story 2 → Test independently → Deploy/Demo (adds clear capability)
4. Add User Story 3 → Test independently → Deploy/Demo (adds regenerate capability)
5. Each story adds value without breaking previous stories

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together
2. Once Foundational is done:
   - Developer A: User Story 1 (backend + frontend)
   - Developer B: User Story 2 (backend + frontend)
   - Developer C: User Story 3 (backend + frontend)
3. Stories complete and integrate independently

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Verify tests fail before implementing (test-first)
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- All endpoints require ROLE_ADMIN authorization (existing pattern)
