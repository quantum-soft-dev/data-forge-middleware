# Tasks: Plugin SQL Generation Extension

**Input**: Design documents from `/specs/001-plugin-sql-generation/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: TDD is MANDATORY for this feature. All tests must be written FIRST and verified to FAIL before implementation.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Backend**: `src/main/java/com/bitbi/dfm/plugin/` (following existing PbLF structure)
- **Tests**: `src/test/java/com/bitbi/dfm/plugin/`
- **Migrations**: `src/main/resources/db/migration/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Database schema and shared value objects needed by all user stories

- [x] T001 Create database migration V11__create_plugin_sql_generations_table.sql in src/main/resources/db/migration/
- [x] T002 [P] Create SqlGenerationStats record in src/main/java/com/bitbi/dfm/plugin/domain/SqlGenerationStats.java
- [x] T003 [P] Create CsvRowDiff record in src/main/java/com/bitbi/dfm/plugin/domain/CsvRowDiff.java
- [x] T004 [P] Create DbfColumnType enum in src/main/java/com/bitbi/dfm/plugin/domain/DbfColumnType.java
- [x] T005 [P] Create PluginApiKey value object in src/main/java/com/bitbi/dfm/plugin/domain/PluginApiKey.java

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**CRITICAL**: No user story work can begin until this phase is complete

- [x] T006 Create PluginSqlGeneration entity in src/main/java/com/bitbi/dfm/plugin/domain/PluginSqlGeneration.java
- [x] T007 Create PluginSqlGenerationRepository interface in src/main/java/com/bitbi/dfm/plugin/domain/PluginSqlGenerationRepository.java
- [x] T008 Implement JpaPluginSqlGenerationRepository in src/main/java/com/bitbi/dfm/plugin/infrastructure/persistence/JpaPluginSqlGenerationRepository.java
- [x] T009 [P] Create SqlChangesResponseDto record in src/main/java/com/bitbi/dfm/plugin/presentation/dto/SqlChangesResponseDto.java
- [x] T010 [P] Create SiteListResponseDto record in src/main/java/com/bitbi/dfm/plugin/presentation/dto/SiteListResponseDto.java
- [x] T011 [P] Create SiteDto record in src/main/java/com/bitbi/dfm/plugin/presentation/dto/SiteDto.java

**Checkpoint**: Foundation ready - user story implementation can now begin

---

## Phase 3: User Story 1 - Automatic Diff Generation on Batch Completion (Priority: P1)

**Goal**: Automatically compare CSV files between consecutive batches when BATCH_COMPLETED event fires for accounts with active Bit BI plugin

**Independent Test**: Upload two consecutive batches to a site with Bit BI plugin active and verify diff results are produced

### Tests for User Story 1 (TDD - Write FIRST)

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [x] T012 [P] [US1] Unit test CsvDiffServiceTest in src/test/java/com/bitbi/dfm/plugin/unit/CsvDiffServiceTest.java - test row comparison logic, DBF type handling, added/modified/deleted detection
- [x] T013 [P] [US1] Integration test SqlGenerationIntegrationTest batch completion trigger in src/test/java/com/bitbi/dfm/plugin/integration/SqlGenerationIntegrationTest.java - test BATCH_COMPLETED event triggers diff generation

### Implementation for User Story 1

- [x] T014 [US1] Implement CsvDiffService in src/main/java/com/bitbi/dfm/plugin/application/CsvDiffService.java - row-based comparison with HashMap for O(n), handle DBF types
- [x] T015 [US1] Extend BitBiPlugin.execute() to call SqlGenerationService on BATCH_COMPLETED event in src/main/java/com/bitbi/dfm/plugin/application/BitBiPlugin.java
- [x] T016 [US1] Add findPreviousBatchForSite query to BatchRepository in src/main/java/com/bitbi/dfm/batch/domain/BatchRepository.java
- [x] T017 [US1] Add metrics for diff generation (diff.files.processed, diff.duration) using Micrometer

**Checkpoint**: At this point, User Story 1 should be fully functional - batch completion triggers CSV diff generation

---

## Phase 4: User Story 2 - SQL File Generation from Diff Results (Priority: P1)

**Goal**: Generate PostgreSQL SQL files (INSERT, UPDATE, DELETE) from diff results with proper formatting and NULL handling

**Independent Test**: Provide mock diff results and verify generated SQL statements match expected format

**Depends on**: US1 (diff results are input to SQL generation)

### Tests for User Story 2 (TDD - Write FIRST)

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [x] T018 [P] [US2] Unit test SqlStatementGeneratorTest in src/test/java/com/bitbi/dfm/plugin/unit/SqlStatementGeneratorTest.java - test INSERT, UPDATE, DELETE formatting, NULL handling by type, special character escaping, END OF COMMAND comments
- [x] T019 [P] [US2] Integration test SqlGenerationIntegrationTest full generation in src/test/java/com/bitbi/dfm/plugin/integration/SqlGenerationIntegrationTest.java - test end-to-end from batch completion to SQL file

### Implementation for User Story 2

- [x] T020 [US2] Implement SqlStatementGenerator in src/main/java/com/bitbi/dfm/plugin/application/SqlStatementGenerator.java - PostgreSQL syntax, value escaping, NULL/0 by type, END OF COMMAND comments
- [x] T021 [US2] Implement SqlGenerationService orchestration in src/main/java/com/bitbi/dfm/plugin/application/SqlGenerationService.java - find previous batch, call diff service, call statement generator, aggregate results
- [x] T022 [US2] Add metrics for SQL generation (sql.statements.generated, sql.generation.duration) using Micrometer

**Checkpoint**: At this point, User Stories 1 AND 2 should work together - batch completion generates SQL content

---

## Phase 5: User Story 5 - SQL File Storage in S3 (Priority: P2)

**Goal**: Store generated SQL files in S3 with structured path for retrieval

**Independent Test**: Trigger SQL generation and verify file exists at expected S3 path

**Depends on**: US2 (SQL content to store)

**Note**: Promoted before US3 (API) because US3 needs S3 storage to retrieve files

### Tests for User Story 5 (TDD - Write FIRST)

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [x] T023 [P] [US5] Integration test S3SqlFileStorageServiceTest in src/test/java/com/bitbi/dfm/plugin/integration/S3SqlFileStorageServiceTest.java - test S3 upload path structure, content retrieval, first batch vs comparison batch paths

### Implementation for User Story 5

- [x] T024 [US5] Implement S3SqlFileStorageService in src/main/java/com/bitbi/dfm/plugin/infrastructure/storage/S3SqlFileStorageService.java - upload SQL files with path plugins/bit-bi/{accountId}/{siteName}/{datetime}.sql
- [x] T025 [US5] Update SqlGenerationService to save SQL to S3 and record in plugin_sql_generations table in src/main/java/com/bitbi/dfm/plugin/application/SqlGenerationService.java
- [x] T026 [US5] Add metrics for S3 operations (s3.sql.upload.duration, s3.sql.file.size) using Micrometer

**Checkpoint**: At this point, US1 + US2 + US5 work together - batch completion generates and stores SQL files

---

## Phase 6: User Story 6 - Plugin API Key Generation (Priority: P2)

**Goal**: Generate Plugin API Key on Bit BI plugin activation for API authentication

**Independent Test**: Activate Bit BI plugin and verify API Key is generated and stored in plugin_data

**Note**: Promoted before US3/US4 (API) because those endpoints require API Key authentication

### Tests for User Story 6 (TDD - Write FIRST)

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [x] T027 [P] [US6] Unit test PluginApiKeyTest in src/test/java/com/bitbi/dfm/plugin/unit/PluginApiKeyTest.java - test key format (plk_ + 32 alphanumeric), generation, validation
- [ ] T028 [P] [US6] Integration test PluginApiKeyIntegrationTest in src/test/java/com/bitbi/dfm/plugin/integration/PluginApiKeyIntegrationTest.java - test key generation on activation, key rotation on re-activation, key validation performance (<50ms)

### Implementation for User Story 6

- [x] T029 [US6] Implement PluginApiKeyService in src/main/java/com/bitbi/dfm/plugin/application/PluginApiKeyService.java - generate key, validate key, find account by key (JSONB containment query)
- [ ] T030 [US6] Extend BitBiPlugin activation to generate and store API Key in plugin_data in src/main/java/com/bitbi/dfm/plugin/application/BitBiPlugin.java
- [ ] T031 [US6] Update extended JSON schema for account_plugins.plugin_data (add apiKey field validation)

**Checkpoint**: At this point, API Key infrastructure is ready for API endpoints

---

## Phase 7: User Story 3 - Retrieve SQL Changes via Plugin API (Priority: P1)

**Goal**: Expose GET /api/v1/plugins/bit-bi/sql-changes endpoint for retrieving SQL changes by site and date

**Independent Test**: Make API requests with valid/invalid credentials and verify correct responses

**Depends on**: US5 (S3 storage), US6 (API Key auth)

### Tests for User Story 3 (TDD - Write FIRST)

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T032 [P] [US3] Contract test BitBiPluginApiContractTest GET /sql-changes in src/test/java/com/bitbi/dfm/plugin/contract/BitBiPluginApiContractTest.java - test 200 OK with SQL content, 200 OK empty body, 400 invalid params, 401 unauthorized, 403 forbidden (site not owned)
- [ ] T033 [P] [US3] Integration test PluginApiIntegrationTest GET /sql-changes in src/test/java/com/bitbi/dfm/plugin/integration/PluginApiIntegrationTest.java - test end-to-end with Testcontainers

### Implementation for User Story 3

- [ ] T034 [US3] Implement PluginApiKeyAuthenticationFilter in src/main/java/com/bitbi/dfm/plugin/presentation/PluginApiKeyAuthenticationFilter.java - extract X-Plugin-Api-Key header, validate, set authentication context
- [ ] T035 [US3] Configure Spring Security for /api/v1/plugins/bit-bi/** to use PluginApiKeyAuthenticationFilter
- [ ] T036 [US3] Implement BitBiPluginApiController GET /sql-changes in src/main/java/com/bitbi/dfm/plugin/presentation/BitBiPluginApiController.java - validate siteId ownership, fetch SQL generations, concatenate S3 content, return text/plain
- [ ] T037 [US3] Add SqlChangesQueryService for retrieving and concatenating SQL files in src/main/java/com/bitbi/dfm/plugin/application/SqlChangesQueryService.java

**Checkpoint**: At this point, User Story 3 should be fully functional - API returns SQL changes

---

## Phase 8: User Story 4 - List Available Sites via Plugin API (Priority: P2)

**Goal**: Expose GET /api/v1/plugins/bit-bi/sites endpoint for listing account's sites

**Independent Test**: Make API request with valid API Key and verify site list matches account's sites

**Depends on**: US6 (API Key auth)

### Tests for User Story 4 (TDD - Write FIRST)

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T038 [P] [US4] Contract test BitBiPluginApiContractTest GET /sites in src/test/java/com/bitbi/dfm/plugin/contract/BitBiPluginApiContractTest.java - test 200 OK with site list, 401 unauthorized
- [ ] T039 [P] [US4] Integration test PluginApiIntegrationTest GET /sites in src/test/java/com/bitbi/dfm/plugin/integration/PluginApiIntegrationTest.java - test end-to-end with Testcontainers

### Implementation for User Story 4

- [ ] T040 [US4] Implement BitBiPluginApiController GET /sites in src/main/java/com/bitbi/dfm/plugin/presentation/BitBiPluginApiController.java - extract accountId from auth, fetch sites, map to SiteDto

**Checkpoint**: At this point, User Story 4 should be fully functional - API returns site list

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [ ] T041 [P] Add OpenAPI documentation annotations to BitBiPluginApiController
- [ ] T042 [P] Add MDC context (siteId, batchId, accountId) to all SQL generation operations for structured logging
- [ ] T043 [P] Add plugin audit log entries for SQL generation events using PluginAuditLogRepository
- [ ] T044 Run all tests and verify coverage with ./gradlew test jacocoTestReport
- [ ] T045 Run quickstart.md validation scenarios manually
- [ ] T046 Update CLAUDE.md with Plugin SQL Generation section documenting new components

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **US1 Diff Generation (Phase 3)**: Depends on Foundational
- **US2 SQL Generation (Phase 4)**: Depends on US1 (uses diff results)
- **US5 S3 Storage (Phase 5)**: Depends on US2 (stores SQL content)
- **US6 API Key (Phase 6)**: Depends on Foundational (can parallelize with US1-US5)
- **US3 SQL Changes API (Phase 7)**: Depends on US5 + US6 (needs storage + auth)
- **US4 Sites API (Phase 8)**: Depends on US6 (needs auth)
- **Polish (Phase 9)**: Depends on all user stories being complete

### User Story Dependencies (Visual)

```
Setup → Foundational ─┬─→ US1 (Diff) → US2 (SQL Gen) → US5 (S3) ─┬─→ US3 (SQL API)
                      │                                          │
                      └─→ US6 (API Key) ────────────────────────┴─→ US4 (Sites API)
```

### Critical Path

1. **MVP**: Setup → Foundational → US1 → US2 → US5 → US6 → US3 (provides core value)
2. **Full Feature**: + US4 + Polish

### Within Each User Story (TDD Order)

1. Tests MUST be written and FAIL before implementation
2. Unit tests before integration tests
3. Domain/application layer before infrastructure
4. Implementation to pass tests (Green)
5. Refactor if needed while keeping tests green

### Parallel Opportunities

**Phase 1 (Setup)**:
```bash
# Run in parallel (different files):
T002 SqlGenerationStats
T003 CsvRowDiff
T004 DbfColumnType
T005 PluginApiKey
```

**Phase 2 (Foundational)**:
```bash
# Run in parallel (different files):
T009 SqlChangesResponseDto
T010 SiteListResponseDto
T011 SiteDto
```

**Tests within each User Story**:
```bash
# US1 tests in parallel:
T012 CsvDiffServiceTest
T013 SqlGenerationIntegrationTest (batch trigger part)

# US3 tests in parallel:
T032 BitBiPluginApiContractTest (/sql-changes)
T033 PluginApiIntegrationTest (/sql-changes)
```

**US6 can run in parallel with US1-US5** (API Key generation is independent of SQL generation flow)

---

## Implementation Strategy

### MVP First (Core SQL Generation + API)

1. Complete Phase 1: Setup (T001-T005)
2. Complete Phase 2: Foundational (T006-T011)
3. Complete Phase 3: US1 - Diff Generation (T012-T017)
4. Complete Phase 4: US2 - SQL Generation (T018-T022)
5. Complete Phase 5: US5 - S3 Storage (T023-T026)
6. Complete Phase 6: US6 - API Key (T027-T031)
7. Complete Phase 7: US3 - SQL Changes API (T032-T037)
8. **STOP and VALIDATE**: Test end-to-end: batch upload → SQL generation → API retrieval

### Incremental Delivery

1. **Milestone 1** (US1+US2+US5): Batch completion generates and stores SQL files
   - Verify: Upload batches, check S3 for SQL files
2. **Milestone 2** (+ US6): API Key generation works
   - Verify: Activate plugin, get API Key
3. **Milestone 3** (+ US3): SQL Changes API works (MVP COMPLETE!)
   - Verify: API returns SQL content
4. **Milestone 4** (+ US4): Sites API works
   - Verify: API returns site list
5. **Milestone 5** (Polish): Documentation, logging, audit trail

### Estimated Task Count by Phase

| Phase | Tasks | Parallelizable |
|-------|-------|----------------|
| Setup | 5 | 4 |
| Foundational | 6 | 3 |
| US1 (P1) | 6 | 2 |
| US2 (P1) | 5 | 2 |
| US5 (P2) | 4 | 1 |
| US6 (P2) | 5 | 2 |
| US3 (P1) | 6 | 2 |
| US4 (P2) | 3 | 2 |
| Polish | 6 | 3 |
| **Total** | **46** | **21** |

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- TDD is MANDATORY: All tests must be written FIRST and verified to FAIL
- Each user story should be independently testable after completion
- Commit after each task or logical group
- Stop at any checkpoint to validate progress
- US1+US2 are tightly coupled (diff → SQL gen) but US2 depends on US1 output
- US3+US4 both require US6 (API Key auth) to function
