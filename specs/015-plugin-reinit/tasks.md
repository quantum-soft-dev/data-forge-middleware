# Tasks: BitBi Plugin Reinit Option

**Input**: Design documents from `/specs/015-plugin-reinit/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/reinit-api.yaml

**Tests**: Not explicitly requested in spec. Test tasks included for critical paths only.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2)
- Include exact file paths in descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Repository method and enum additions needed by both user stories

- [X] T001 Add `REINIT` enum value to `src/main/java/com/bitbi/dfm/plugin/domain/PluginActionType.java`
- [X] T002 [P] Add `findLatestCompletedByAccountId(UUID accountId)` method to `src/main/java/com/bitbi/dfm/batch/domain/BatchRepository.java`
- [X] T003 [P] Implement `findLatestCompletedByAccountId` in `src/main/java/com/bitbi/dfm/batch/infrastructure/JpaBatchRepository.java`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Audit logging support for reinit operations that both stories will use

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T004 Add `logReinit()` method to `src/main/java/com/bitbi/dfm/plugin/application/PluginAuditService.java` for logging REINIT operations with metadata (deletedGenerations, batchId, success)

**Checkpoint**: Foundation ready - user story implementation can now begin

---

## Phase 3: User Story 1 - Initialize SQL on Plugin Activation (Priority: P1) 🎯 MVP

**Goal**: Automatically trigger SQL generation for the most recent completed batch when the plugin is newly activated or reactivated

**Independent Test**: Activate the plugin on an account that has existing completed batches, verify SQL changes become available via the plugin API within 60 seconds

**Functional Requirements**: FR-001, FR-002, FR-003, FR-011, FR-012

### Implementation for User Story 1

- [X] T005 [US1] Add async `initializeSqlFromLatestBatch(AccountPlugin, boolean isNewOrReactivation)` method to `src/main/java/com/bitbi/dfm/plugin/application/BitBiPlugin.java` that finds latest batch and triggers SQL generation
- [X] T006 [US1] Inject `BatchRepository` into `BitBiPlugin.java` for finding latest completed batch
- [X] T007 [US1] Modify `src/main/java/com/bitbi/dfm/plugin/application/PluginActivationService.java` to call `BitBiPlugin.initializeSqlFromLatestBatch()` after activation when `isNewActivation` or `isReactivation` is true
- [X] T008 [US1] Add unit test `shouldInitializeSqlOnNewActivation` in `src/test/java/com/bitbi/dfm/plugin/unit/BitBiPluginTest.java`
- [X] T009 [P] [US1] Add unit test `shouldNotInitializeSqlOnConfigUpdate` in `src/test/java/com/bitbi/dfm/plugin/unit/BitBiPluginTest.java`
- [X] T010 [P] [US1] Add unit test `shouldSkipInitializationWhenNoBatchesExist` in `src/test/java/com/bitbi/dfm/plugin/unit/BitBiPluginTest.java`

**Checkpoint**: At this point, User Story 1 should be fully functional - activating the plugin triggers SQL generation from the latest batch

---

## Phase 4: User Story 2 - Manual Plugin Reinitialization (Priority: P2)

**Goal**: Allow users to clear all SQL history and regenerate from the latest batch while preserving API key

**Independent Test**: Call the reinit endpoint on an account with existing SQL generations, verify all previous SQL changes are deleted and new SQL is generated from the latest batch

**Functional Requirements**: FR-004, FR-005, FR-006, FR-007, FR-008, FR-009, FR-010, FR-011, FR-013

### Implementation for User Story 2

- [X] T011 [US2] Create `ReinitResultDto` record in `src/main/java/com/bitbi/dfm/plugin/presentation/dto/ReinitResultDto.java` per contracts/reinit-api.yaml schema
- [X] T012 [US2] Add `reinit(String pluginId, UUID accountId)` method to `src/main/java/com/bitbi/dfm/plugin/application/PluginHistoryService.java` that validates active, deletes S3/DB records, triggers async SQL generation
- [X] T013 [US2] Reuse S3 deletion logic from existing `clearHistory()` method in `PluginHistoryService` (extract helper if needed)
- [X] T014 [US2] Add `POST /{pluginId}/reinit` endpoint to `src/main/java/com/bitbi/dfm/plugin/presentation/AccountPluginsController.java`
- [X] T015 [US2] Add OpenAPI annotations to reinit endpoint matching contracts/reinit-api.yaml
- [X] T016 [US2] Add validation: return 400 error if plugin is not active (FR-009)
- [X] T017 [US2] Add unit test `shouldReinitSuccessfully` in `src/test/java/com/bitbi/dfm/plugin/unit/PluginHistoryServiceTest.java`
- [X] T018 [P] [US2] Add unit test `shouldRejectReinitForInactivePlugin` in `src/test/java/com/bitbi/dfm/plugin/unit/PluginHistoryServiceTest.java`
- [X] T019 [P] [US2] Add unit test `shouldPreserveApiKeyOnReinit` in `src/test/java/com/bitbi/dfm/plugin/unit/PluginHistoryServiceTest.java`
- [X] T020 [US2] Add contract test for reinit endpoint in `src/test/java/com/bitbi/dfm/plugin/contract/AccountPluginsContractTest.java`

**Checkpoint**: At this point, User Story 2 should be fully functional - calling reinit clears history and regenerates SQL

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: Integration testing and documentation

- [X] T021 [P] Add integration test `shouldActivateAndGenerateSql` in `src/test/java/com/bitbi/dfm/plugin/integration/PluginHistoryIntegrationTest.java` (Testcontainers + LocalStack)
- [X] T022 [P] Add integration test `shouldReinitAndRegenerateSql` in `src/test/java/com/bitbi/dfm/plugin/integration/PluginHistoryIntegrationTest.java`
- [X] T023 Verify quickstart.md scenarios work end-to-end with running application
- [X] T024 Run `./gradlew test` to ensure all tests pass
- [X] T025 Run `./gradlew integrationTest` to ensure integration tests pass

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on T001 (REINIT enum) for audit logging
- **User Story 1 (Phase 3)**: Depends on T002, T003 (batch repository method)
- **User Story 2 (Phase 4)**: Depends on T001, T004 (REINIT enum and audit method), can run parallel to US1
- **Polish (Phase 5)**: Depends on both user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Phase 2 - No dependencies on US2
- **User Story 2 (P2)**: Can start after Phase 2 - No dependencies on US1 (shares async SQL generation pattern)

### Within Each User Story

- Models/DTOs before services
- Services before endpoints/controllers
- Core implementation before tests
- Unit tests can run parallel within a story

### Parallel Opportunities

- T002 and T003 can run in parallel (different files in batch domain)
- T009 and T010 can run in parallel (independent test cases)
- T018 and T019 can run in parallel (independent test cases)
- T021 and T022 can run in parallel (independent integration tests)
- **User Stories 1 and 2 can be worked on in parallel** once Phase 2 completes

---

## Parallel Example: User Story 2

```bash
# Launch these tests in parallel:
Task: "Add unit test shouldRejectReinitForInactivePlugin in src/test/java/com/bitbi/dfm/plugin/unit/PluginHistoryServiceTest.java"
Task: "Add unit test shouldPreserveApiKeyOnReinit in src/test/java/com/bitbi/dfm/plugin/unit/PluginHistoryServiceTest.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T003)
2. Complete Phase 2: Foundational (T004)
3. Complete Phase 3: User Story 1 (T005-T010)
4. **STOP and VALIDATE**: Test activation triggers SQL generation
5. Deploy/demo if ready - users get immediate value on activation

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 → Test activation → Deploy (MVP!)
3. Add User Story 2 → Test reinit endpoint → Deploy
4. Each story adds value without breaking previous stories

### Single Developer Strategy

1. T001-T004 sequentially (setup + foundational)
2. T005-T010 sequentially (US1 implementation)
3. T011-T020 sequentially (US2 implementation)
4. T021-T025 sequentially (polish)

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story (US1 or US2)
- Each user story is independently completable and testable
- Async SQL generation uses Spring `@Async` annotation
- Reinit reuses existing S3 deletion logic from `clearHistory()`
- API key is NOT regenerated during reinit (only during full reactivation)
- Commit after each task or logical group
- Total: 25 tasks (3 setup, 1 foundational, 6 US1, 10 US2, 5 polish)
