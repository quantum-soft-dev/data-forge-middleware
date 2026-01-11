# Tasks: Global Error Handling (TDD)

**Input**: Design documents from `/specs/016-global-error-handling/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**TDD ОБЯЗАТЕЛЕН**: Тесты пишутся ПЕРЕД имплементацией. Red → Green → Refactor.
- Backend: JUnit 5 + Mockito + Testcontainers
- Frontend: Vitest + React Testing Library

**Organization**: Tasks grouped by user story. Within each story: Tests FIRST → Implementation.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: User story this task belongs to (US1, US2, US3, US4)
- **🔴 RED**: Test task (write failing test)
- **🟢 GREEN**: Implementation task (make test pass)

## Path Conventions

- **Backend**: `src/main/java/com/bitbi/dfm/error/`
- **Backend Tests**: `src/test/java/com/bitbi/dfm/error/`
- **Frontend**: `frontend/src/`
- **Frontend Tests**: `frontend/src/**/*.test.ts(x)`
- **Migrations**: `src/main/resources/db/migration/`

---

## Phase 1: Setup (Database & Domain Foundation)

**Purpose**: Database migration and core domain changes (no tests needed for migration/enum)

- [X] T001 Create Flyway migration V17__add_severity_and_is_read_to_error_logs.sql in src/main/resources/db/migration/
- [X] T002 [P] Create ErrorSeverity enum in src/main/java/com/bitbi/dfm/error/domain/ErrorSeverity.java

**Checkpoint**: Database schema ready, enum defined

---

## Phase 2: Foundational (Repository Layer with TDD)

**Purpose**: Core repository queries needed by ALL user stories

**⚠️ TDD**: Write repository tests FIRST, then implement queries

### 🔴 RED: Repository Tests

- [X] T003 [P] Write test for findGlobalErrorsByAccountId in src/test/java/com/bitbi/dfm/error/domain/ErrorLogRepositoryTest.java
- [X] T004 [P] Write test for countUnreadGlobalErrorsByAccountId in src/test/java/com/bitbi/dfm/error/domain/ErrorLogRepositoryTest.java
- [X] T005 [P] Write test for markAsReadByIds in src/test/java/com/bitbi/dfm/error/domain/ErrorLogRepositoryTest.java
- [X] T006 [P] Write test for markAllAsReadByAccountId in src/test/java/com/bitbi/dfm/error/domain/ErrorLogRepositoryTest.java

### 🟢 GREEN: Entity & Repository Implementation

- [X] T007 Modify ErrorLog entity to add severity and isRead fields in src/main/java/com/bitbi/dfm/error/domain/ErrorLog.java
- [X] T008 Modify ErrorLog.create() factory method to accept severity parameter in src/main/java/com/bitbi/dfm/error/domain/ErrorLog.java
- [X] T009 Add findGlobalErrorsByAccountId query to ErrorLogRepository in src/main/java/com/bitbi/dfm/error/domain/ErrorLogRepository.java
- [X] T010 [P] Add countUnreadGlobalErrorsByAccountId query to ErrorLogRepository
- [X] T011 [P] Add markAsReadByIds method to ErrorLogRepository
- [X] T012 [P] Add markAllAsReadByAccountId method to ErrorLogRepository
- [X] T013 Implement JpaErrorLogRepository with new queries in src/main/java/com/bitbi/dfm/error/infrastructure/JpaErrorLogRepository.java
- [X] T014 Run repository tests - verify all pass (GREEN)

**Checkpoint**: Repository layer complete with passing tests

---

## Phase 3: User Story 1 - Report Global Error from Client (Priority: P1) 🎯 MVP

**Goal**: Client can send global errors with severity level to server

**Independent Test**: POST /api/dfc/error with severity, verify saved with isRead=false

### 🔴 RED: Backend Tests for US1

- [X] T015 [P] [US1] Write contract test for POST /api/dfc/error with severity in src/test/java/com/bitbi/dfm/error/contract/ErrorLogControllerContractTest.java
- [X] T016 [P] [US1] Write integration test for logStandaloneError with severity in src/test/java/com/bitbi/dfm/error/integration/GlobalErrorIntegrationTest.java
- [X] T017 [P] [US1] Write unit test for ErrorLoggingService.logStandaloneError with severity in src/test/java/com/bitbi/dfm/error/application/ErrorLoggingServiceTest.java
- [X] T018 [US1] Run US1 tests - verify all FAIL (RED confirmed)

### 🟢 GREEN: Backend Implementation for US1

- [X] T019 [US1] Modify LogErrorRequestDto to add optional severity field in src/main/java/com/bitbi/dfm/error/presentation/dto/LogErrorRequestDto.java
- [X] T020 [US1] Modify ErrorLogResponseDto to include severity and isRead fields in src/main/java/com/bitbi/dfm/error/presentation/dto/ErrorLogResponseDto.java
- [X] T021 [US1] Update ErrorLoggingService.logStandaloneError to accept severity in src/main/java/com/bitbi/dfm/error/application/ErrorLoggingService.java
- [X] T022 [US1] Update ErrorLoggingService.logError to accept severity in src/main/java/com/bitbi/dfm/error/application/ErrorLoggingService.java
- [X] T023 [US1] Modify ErrorLogController POST /api/dfc/error to pass severity in src/main/java/com/bitbi/dfm/error/presentation/ErrorLogController.java
- [X] T024 [US1] Modify ErrorLogController POST /api/dfc/error/{batchId} to pass severity
- [X] T025 [US1] Run US1 tests - verify all PASS (GREEN confirmed)

**Checkpoint**: US1 complete. Clients can report errors with severity.

---

## Phase 4: User Story 2 - View Global Errors on Dashboard (Priority: P1)

**Goal**: User sees global errors on Dashboard widget with details and mark-as-read

**Independent Test**: Open Dashboard, verify widget shows errors, can view details, mark single as read

### 🔴 RED: Backend Tests for US2

- [X] T026 [P] [US2] Write contract test for GET /api/v1/account/errors in src/test/java/com/bitbi/dfm/error/contract/GlobalErrorUserControllerContractTest.java
- [X] T027 [P] [US2] Write contract test for GET /api/v1/account/errors/{errorId}
- [X] T028 [P] [US2] Write contract test for PATCH /api/v1/account/errors/{errorId}/read
- [X] T029 [P] [US2] Write unit test for GlobalErrorService.listGlobalErrors in src/test/java/com/bitbi/dfm/error/application/GlobalErrorServiceTest.java
- [X] T030 [P] [US2] Write unit test for GlobalErrorService.getGlobalError
- [X] T031 [P] [US2] Write unit test for GlobalErrorService.markAsRead
- [X] T032 [US2] Run US2 backend tests - verify all FAIL (RED confirmed)

### 🟢 GREEN: Backend Implementation for US2

- [X] T033 [P] [US2] Create GlobalErrorSummaryDto record in src/main/java/com/bitbi/dfm/error/presentation/dto/GlobalErrorSummaryDto.java
- [X] T034 [P] [US2] Create GlobalErrorResponseDto record in src/main/java/com/bitbi/dfm/error/presentation/dto/GlobalErrorResponseDto.java
- [X] T035 [US2] Create GlobalErrorService with listGlobalErrors, getGlobalError, markAsRead in src/main/java/com/bitbi/dfm/error/application/GlobalErrorService.java
- [X] T036 [US2] Create GlobalErrorUserController with GET /api/v1/account/errors in src/main/java/com/bitbi/dfm/error/presentation/GlobalErrorUserController.java
- [X] T037 [US2] Add GET /api/v1/account/errors/{errorId} endpoint to GlobalErrorUserController
- [X] T038 [US2] Add PATCH /api/v1/account/errors/{errorId}/read endpoint to GlobalErrorUserController
- [X] T039 [US2] Register routes in ApiRoutes.java in src/main/java/com/bitbi/dfm/shared/api/ApiRoutes.java
- [X] T040 [US2] Run US2 backend tests - verify all PASS (GREEN confirmed)

### 🔴 RED: Frontend Tests for US2

- [X] T041 [P] [US2] Write test for global-errors.api.ts in frontend/src/features/global-errors/api/global-errors.api.test.ts
- [X] T042 [P] [US2] Write test for GlobalErrorItem component in frontend/src/features/global-errors/ui/GlobalErrorItem.test.tsx
- [X] T043 [P] [US2] Write test for GlobalErrorDetails modal in frontend/src/features/global-errors/ui/GlobalErrorDetails.test.tsx
- [X] T044 [P] [US2] Write test for GlobalErrorList component in frontend/src/features/global-errors/ui/GlobalErrorList.test.tsx
- [X] T045 [P] [US2] Write test for GlobalErrorsWidget in frontend/src/widgets/global-errors/GlobalErrorsWidget.test.tsx
- [X] T046 [US2] Run US2 frontend tests - verify all FAIL (RED confirmed)

### 🟢 GREEN: Frontend Implementation for US2

- [X] T047 [P] [US2] Create global-error.types.ts in frontend/src/features/global-errors/model/global-error.types.ts
- [X] T048 [P] [US2] Create global-errors.api.ts in frontend/src/features/global-errors/api/global-errors.api.ts
- [X] T049 [US2] Create global-errors.queries.ts with TanStack Query hooks in frontend/src/features/global-errors/api/global-errors.queries.ts
- [X] T050 [US2] Create GlobalErrorItem.tsx component in frontend/src/features/global-errors/ui/GlobalErrorItem.tsx
- [X] T051 [US2] Create GlobalErrorDetails.tsx modal in frontend/src/features/global-errors/ui/GlobalErrorDetails.tsx
- [X] T052 [US2] Create GlobalErrorList.tsx with pagination in frontend/src/features/global-errors/ui/GlobalErrorList.tsx
- [X] T053 [US2] Create GlobalErrorsWidget.tsx in frontend/src/widgets/global-errors/GlobalErrorsWidget.tsx
- [X] T054 [US2] Add GlobalErrorsWidget to Dashboard page in frontend/src/pages/dashboard/DashboardPage.tsx
- [X] T055 [US2] Run US2 frontend tests - verify all PASS (GREEN confirmed)

**Checkpoint**: US2 complete. Users can view and manage errors on Dashboard.

---

## Phase 5: User Story 3 - Mark Multiple Errors as Read (Priority: P2)

**Goal**: User can bulk mark errors as read (selected or all)

**Independent Test**: Select multiple errors, verify bulk actions work

### 🔴 RED: Backend Tests for US3

- [X] T056 [P] [US3] Write contract test for POST /api/v1/account/errors/mark-as-read in src/test/java/com/bitbi/dfm/error/contract/GlobalErrorUserControllerContractTest.java
- [X] T057 [P] [US3] Write contract test for POST /api/v1/account/errors/mark-all-as-read
- [X] T058 [P] [US3] Write unit test for GlobalErrorService.markMultipleAsRead in src/test/java/com/bitbi/dfm/error/application/GlobalErrorServiceTest.java
- [X] T059 [P] [US3] Write unit test for GlobalErrorService.markAllAsRead
- [X] T060 [US3] Run US3 backend tests - verify all FAIL (RED confirmed)

### 🟢 GREEN: Backend Implementation for US3

- [X] T061 [P] [US3] Create MarkAsReadRequestDto record in src/main/java/com/bitbi/dfm/error/presentation/dto/MarkAsReadRequestDto.java
- [X] T062 [P] [US3] Create MarkAsReadResponseDto record in src/main/java/com/bitbi/dfm/error/presentation/dto/MarkAsReadResponseDto.java
- [X] T063 [US3] Add markMultipleAsRead and markAllAsRead to GlobalErrorService
- [X] T064 [US3] Add POST /api/v1/account/errors/mark-as-read endpoint to GlobalErrorUserController
- [X] T065 [US3] Add POST /api/v1/account/errors/mark-all-as-read endpoint to GlobalErrorUserController
- [X] T066 [US3] Run US3 backend tests - verify all PASS (GREEN confirmed)

### 🔴 RED: Frontend Tests for US3

- [X] T067 [P] [US3] Write test for useMarkMultipleAsRead mutation in frontend/src/features/global-errors/api/global-errors.queries.test.ts
- [X] T068 [P] [US3] Write test for useMarkAllAsRead mutation
- [X] T069 [P] [US3] Write test for checkbox selection in GlobalErrorList in frontend/src/features/global-errors/ui/GlobalErrorList.test.tsx
- [X] T070 [US3] Run US3 frontend tests - verify all FAIL (RED confirmed)

### 🟢 GREEN: Frontend Implementation for US3

- [X] T071 [US3] Add useMarkMultipleAsRead mutation to global-errors.queries.ts
- [X] T072 [US3] Add useMarkAllAsRead mutation to global-errors.queries.ts
- [X] T073 [US3] Add checkbox selection to GlobalErrorList.tsx
- [X] T074 [US3] Add "Mark Selected as Read" button to GlobalErrorList.tsx
- [X] T075 [US3] Add "Mark All as Read" button to GlobalErrorsWidget.tsx header
- [X] T076 [US3] Run US3 frontend tests - verify all PASS (GREEN confirmed)

**Checkpoint**: US3 complete. Bulk operations available.

---

## Phase 6: User Story 4 - Unread Errors Counter Badge (Priority: P2)

**Goal**: Dashboard widget shows badge with unread error count

**Independent Test**: Verify badge shows correct count, updates on actions

### 🔴 RED: Backend Tests for US4

- [X] T077 [P] [US4] Write contract test for GET /api/v1/account/errors/unread-count in src/test/java/com/bitbi/dfm/error/contract/GlobalErrorUserControllerContractTest.java
- [X] T078 [P] [US4] Write unit test for GlobalErrorService.getUnreadCount in src/test/java/com/bitbi/dfm/error/application/GlobalErrorServiceTest.java
- [X] T079 [US4] Run US4 backend tests - verify all FAIL (RED confirmed)

### 🟢 GREEN: Backend Implementation for US4

- [X] T080 [P] [US4] Create UnreadCountResponseDto record in src/main/java/com/bitbi/dfm/error/presentation/dto/UnreadCountResponseDto.java
- [X] T081 [US4] Add getUnreadCount to GlobalErrorService
- [X] T082 [US4] Add GET /api/v1/account/errors/unread-count endpoint to GlobalErrorUserController
- [X] T083 [US4] Run US4 backend tests - verify all PASS (GREEN confirmed)

### 🔴 RED: Frontend Tests for US4

- [X] T084 [P] [US4] Write test for useUnreadCount query hook in frontend/src/features/global-errors/api/global-errors.queries.test.ts
- [X] T085 [P] [US4] Write test for Badge display in GlobalErrorsWidget in frontend/src/widgets/global-errors/GlobalErrorsWidget.test.tsx
- [X] T086 [US4] Run US4 frontend tests - verify all FAIL (RED confirmed)

### 🟢 GREEN: Frontend Implementation for US4

- [X] T087 [US4] Add useUnreadCount query hook to global-errors.queries.ts
- [X] T088 [US4] Add Badge component to GlobalErrorsWidget header showing unread count
- [X] T089 [US4] Configure polling interval (30s) for unread count
- [X] T090 [US4] Run US4 frontend tests - verify all PASS (GREEN confirmed)

**Checkpoint**: US4 complete. Badge displays unread count.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Documentation, refactoring, final validation

- [X] T091 [P] Add OpenAPI annotations to GlobalErrorUserController for Swagger docs
- [X] T092 [P] Update ApiRoutes constants with all new error endpoints
- [X] T093 Validate message length truncation (10,000 chars) in ErrorLog.create()
- [X] T094 Verify existing error migration works correctly (severity=ERROR, is_read=true)
- [X] T095 [P] Run full test suite - all tests must pass
- [ ] T096 [P] Run quickstart.md verification steps manually
- [X] T097 Update CLAUDE.md with new endpoints and patterns

---

## Dependencies & Execution Order

### TDD Flow Per Phase

```
🔴 RED Phase:
   Write all tests for the phase
   Run tests → ALL MUST FAIL

🟢 GREEN Phase:
   Implement minimum code to pass tests
   Run tests → ALL MUST PASS

♻️ REFACTOR (implicit):
   Clean up code while keeping tests green
```

### Phase Dependencies

```
Phase 1 (Setup) ─────────────────────────────────┐
                                                 │
Phase 2 (Foundational + TDD) ────────────────────┤
   🔴 T003-T006 (tests)                          │
   🟢 T007-T014 (impl)                           │
                                                 │
          ┌──────────────────────────────────────┴───────────────────────────┐
          │                                                                  │
          ▼                                                                  │
Phase 3 (US1 + TDD) ──────────┐                                              │
   🔴 T015-T018 (tests)       │                                              │
   🟢 T019-T025 (impl)        │                                              │
          │                   │                                              │
          │                   ▼                                              │
          │         Phase 4 (US2 + TDD) ──────────┐                          │
          │            🔴 T026-T046 (tests)       │                          │
          │            🟢 T033-T055 (impl)        │                          │
          │                   │                   │                          │
          │                   │                   ▼                          │
          │                   │         Phase 5 (US3 + TDD) ─────┐           │
          │                   │            🔴 T056-T070 (tests)  │           │
          │                   │            🟢 T061-T076 (impl)   │           │
          │                   │                   │              │           │
          │                   ▼                   ▼              │           │
          │         Phase 6 (US4 + TDD) ──────────┘              │           │
          │            🔴 T077-T086 (tests)                      │           │
          │            🟢 T080-T090 (impl)                       │           │
          │                   │                                  │           │
          └───────────────────┴──────────────────────────────────┘           │
                                                 │                           │
                                                 ▼                           │
                                  Phase 7 (Polish) ◄─────────────────────────┘
```

### Parallel Opportunities (Within TDD)

**🔴 RED Phase - Tests can be written in parallel:**
```
Phase 2 Tests: T003, T004, T005, T006 (parallel)
US1 Tests: T015, T016, T017 (parallel)
US2 Backend Tests: T026, T027, T028, T029, T030, T031 (parallel)
US2 Frontend Tests: T041, T042, T043, T044, T045 (parallel)
```

**🟢 GREEN Phase - Some implementations can be parallel:**
```
Phase 2 Queries: T010, T011, T012 (parallel)
US2 DTOs: T033, T034, T047, T048 (parallel)
US3 DTOs: T061, T062 (parallel)
```

---

## TDD Verification Commands

### Backend (Java)
```bash
# Run specific test class
./gradlew test --tests "GlobalErrorServiceTest"

# Run all error domain tests
./gradlew test --tests "com.bitbi.dfm.error.*"

# Run with Testcontainers (integration)
./gradlew integrationTest --tests "*GlobalError*"
```

### Frontend (TypeScript/Vitest)
```bash
# Run specific test file
cd frontend && npm test -- global-errors.api.test.ts

# Run all global-errors feature tests
cd frontend && npm test -- --grep "global-errors"

# Run with coverage
cd frontend && npm test -- --coverage
```

---

## Implementation Strategy (TDD)

### MVP First (US1 + US2)

1. Phase 1: Setup (migration, enum)
2. Phase 2: 🔴 Write repo tests → 🟢 Implement repo
3. Phase 3: 🔴 Write US1 tests → 🟢 Implement US1
4. Phase 4: 🔴 Write US2 tests → 🟢 Implement US2
5. **STOP and VALIDATE**: Full test suite green, MVP working
6. Deploy/demo MVP

### Incremental TDD Delivery

Each user story follows Red-Green-Refactor:
1. 🔴 Write ALL tests for the story (they fail)
2. 🟢 Implement minimum code (tests pass)
3. ♻️ Refactor while keeping tests green
4. Move to next story

---

## Task Count Summary (TDD)

| Phase | User Story | 🔴 Test Tasks | 🟢 Impl Tasks | Total |
|-------|------------|---------------|---------------|-------|
| Phase 1 | Setup | 0 | 2 | 2 |
| Phase 2 | Foundational | 4 | 8 | 12 |
| Phase 3 | US1: Report Error | 4 | 7 | 11 |
| Phase 4 | US2: View Errors | 16 | 14 | 30 |
| Phase 5 | US3: Bulk Mark | 8 | 10 | 18 |
| Phase 6 | US4: Badge | 6 | 8 | 14 |
| Phase 7 | Polish | 0 | 7 | 7 |
| **Total** | | **38** | **56** | **94** |

---

## Notes

- **TDD обязателен**: Каждая user story начинается с написания тестов
- 🔴 RED: Тесты должны ПАДАТЬ до имплементации
- 🟢 GREEN: Имплементация должна сделать тесты ЗЕЛЁНЫМИ
- [P] tasks = разные файлы, можно параллельно
- Коммит после каждого RED/GREEN цикла
- Никогда не пропускай шаг проверки что тесты падают/проходят
