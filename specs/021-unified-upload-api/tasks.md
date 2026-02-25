# Tasks: Unified Data Upload API

**Input**: Design documents from `/specs/021-unified-upload-api/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Not explicitly requested in the specification. Test tasks are omitted.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Backend**: `src/main/java/com/bitbi/dfm/` at repository root
- **Frontend**: `frontend/src/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Database migration, configuration properties, and shared exception classes

- [X] T001 Create Flyway migration with site directive columns, batch type/schema version columns, and client_diagnostic_logs table in src/main/resources/db/migration/V29__unified_upload_api.sql
- [X] T002 [P] Add heartbeat, schema enforcement, and client-logs configuration properties to src/main/resources/application.yml
- [X] T003 [P] Create InvalidLogFileException and LogUploadLimitExceededException in src/main/java/com/bitbi/dfm/shared/exception/

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Domain enums, entity field extensions, security configuration, and exception handler updates that ALL user stories depend on

**CRITICAL**: No user story work can begin until this phase is complete

- [X] T004 Add MSSQL_CDC and DBF_CDC enum values with isCdc() helper method to SiteType in src/main/java/com/bitbi/dfm/site/domain/SiteType.java
- [X] T005 [P] Create ForceFullUploadReason enum (ADMIN_REQUEST, PLUGIN_REINIT, SCHEMA_INCOMPATIBLE, DATA_CORRUPTION) in src/main/java/com/bitbi/dfm/site/domain/ForceFullUploadReason.java
- [X] T006 [P] Create BatchType enum (BASELINE, DELTA) in src/main/java/com/bitbi/dfm/batch/domain/BatchType.java
- [X] T007 Add heartbeat and directive fields (lastHeartbeatAt, forceFullUpload, forceFullUploadReason, forceFullUploadMessage, forceFullUploadSetAt, forceFullUploadSetBy, requestLogs, requestLogsMessage) with business methods to Site entity in src/main/java/com/bitbi/dfm/site/domain/Site.java
- [X] T008 Add batchType, schemaVersion, expectedFileCount, and description fields to Batch entity in src/main/java/com/bitbi/dfm/batch/domain/Batch.java
- [X] T009 Secure new endpoints (/api/v1/device/heartbeat, /api/v1/device/logs, /api/v1/admin/sites/*/force-rebaseline, /api/v1/admin/sites/*/request-logs, /api/v1/admin/sites/*/client-logs) in SecurityConfiguration in src/main/java/com/bitbi/dfm/shared/config/SecurityConfiguration.java
- [X] T010 Add InvalidLogFileException (400), LogUploadLimitExceededException (429), and HeartbeatRequiredException (428) mappings to GlobalExceptionHandler in src/main/java/com/bitbi/dfm/shared/exception/GlobalExceptionHandler.java

**Checkpoint**: Foundation ready — user story implementation can now begin

---

## Phase 3: US2 — Heartbeat Pre-Batch Sync Check (Priority: P1) MVP

**Goal**: Provide a heartbeat endpoint that returns site status, directives, schema info, and last batch info. Enforce heartbeat-before-batch requirement.

**Independent Test**: Call GET /api/v1/device/heartbeat with a valid JWT, verify response contains siteId, siteStatus, directives, schema version, lastCompletedBatch, and serverTime. Then attempt POST /batches/start without recent heartbeat and verify 428 rejection.

### Implementation for User Story 2

- [X] T011 [US2] Create HeartbeatResponseDto record (siteId, siteStatus, directives, schema, lastCompletedBatch, serverTime) in src/main/java/com/bitbi/dfm/site/presentation/dto/HeartbeatResponseDto.java
- [X] T012 [US2] Implement HeartbeatService with recordHeartbeat() that updates lastHeartbeatAt on Site and builds HeartbeatResponseDto with directives, schema version, and last completed batch info in src/main/java/com/bitbi/dfm/site/application/HeartbeatService.java
- [X] T013 [US2] Create HeartbeatController with GET /api/v1/device/heartbeat endpoint (Custom JWT auth, extracts siteId from token) in src/main/java/com/bitbi/dfm/site/presentation/HeartbeatController.java
- [X] T014 [US2] Add heartbeat timestamp validation to startBatch() — reject with 428 HeartbeatRequiredException if lastHeartbeatAt is null or older than configured interval in BatchLifecycleService in src/main/java/com/bitbi/dfm/batch/application/BatchLifecycleService.java

**Checkpoint**: Heartbeat endpoint operational, batch start enforces heartbeat requirement

---

## Phase 4: US8 — Batch Type Required on Batch Start (Priority: P2)

**Goal**: Require batchType (BASELINE/DELTA) in batch start requests. Pin schema version at batch start. Clear forceFullUpload directive on batch start.

**Independent Test**: POST /batches/start with `{"batchType": "DELTA"}` succeeds; POST without batchType returns 400. Verify batch record has correct batchType and schemaVersion. Verify forceFullUpload flag is cleared after batch start.

### Implementation for User Story 8

- [X] T015 [US8] Create BatchStartRequestDto record (batchType required, expectedFileCount optional, description optional) with Jakarta validation annotations in src/main/java/com/bitbi/dfm/batch/presentation/dto/BatchStartRequestDto.java
- [X] T016 [US8] Modify DeviceBatchController and BatchController to accept BatchStartRequestDto as @RequestBody on batch start endpoints in src/main/java/com/bitbi/dfm/batch/presentation/DeviceBatchController.java and src/main/java/com/bitbi/dfm/batch/presentation/BatchController.java
- [X] T017 [US8] Add batchType validation (reject null with 400), schema version pinning from current SiteSchema, and forceFullUpload clearing on batch start in BatchLifecycleService in src/main/java/com/bitbi/dfm/batch/application/BatchLifecycleService.java

**Checkpoint**: Batch start requires batchType, pins schema version, clears directives

---

## Phase 5: US1 — MSSQL CDC Client Uploads Data (Priority: P1) MVP

**Goal**: Support MSSQL_CDC site type through the full CDC flow: schema submission with MSSQL type aliases, CSV baseline, JSONL deltas, SQL generation.

**Independent Test**: Authorize a device with siteType MSSQL_CDC, submit a schema with nvarchar/datetime2 types, upload a CSV baseline batch, then upload a JSONL delta batch and verify SQL generation produces correct PostgreSQL statements.

### Implementation for User Story 1

- [X] T018 [US1] Add MSSQL type alias normalization map (nvarchar→VARCHAR, datetime2→TIMESTAMP, uniqueidentifier→UUID, money→MONEY, bit→BOOLEAN, ntext→TEXT, float→FLOAT, int→INTEGER) to schema submission in SiteSchemaService in src/main/java/com/bitbi/dfm/site/application/SiteSchemaService.java
- [X] T019 [P] [US1] Replace explicit POSTGRES_CDC routing with isCdc() check to route all CDC site types to CdcSqlGenerationStrategy in SqlGenerationService in src/main/java/com/bitbi/dfm/plugin/application/SqlGenerationService.java
- [X] T020 [P] [US1] Replace explicit POSTGRES_CDC file validation checks with isCdc() to support MSSQL_CDC and DBF_CDC file uploads in FileUploadService in src/main/java/com/bitbi/dfm/upload/application/FileUploadService.java

**Checkpoint**: MSSQL_CDC sites can complete full CDC flow (authorize → schema → baseline → delta → SQL)

---

## Phase 6: US7 — Mandatory Schema for DBF Sites (Priority: P2)

**Goal**: Enforce schema submission for DBF sites with a configurable grace period. During grace period, log warning but allow batch. After grace period, reject with SchemaRequiredException.

**Independent Test**: Start a DBF batch without schema during grace period — verify warning logged but batch proceeds. After grace period — verify 400 SchemaRequiredException. With schema — batch proceeds regardless.

### Implementation for User Story 7

- [X] T021 [US7] Extend schema enforcement in startBatch() to cover DBF sites: read dbf-grace-period-end config, log warning if no schema during grace period, reject with SchemaRequiredException after grace period in BatchLifecycleService in src/main/java/com/bitbi/dfm/batch/application/BatchLifecycleService.java

**Checkpoint**: DBF schema enforcement active with grace period protection

---

## Phase 7: US3 — Admin Forces Rebaseline (Priority: P2)

**Goal**: Admin can set forceFullUpload directive on CDC sites via API. Plugin reinit automatically sets the directive. Admin can request client logs via API.

**Independent Test**: POST /admin/sites/{siteId}/force-rebaseline with reason — verify site's forceFullUpload flag is set. Call GET /heartbeat — verify directive visible. Call POST /batches/start — verify flag cleared.

### Implementation for User Story 3

- [ ] T022 [P] [US3] Create ForceRebaselineRequestDto (reason required) and ForceRebaselineResponseDto (siteId, forceFullUpload, reason, message, setAt, setBy) records in src/main/java/com/bitbi/dfm/site/presentation/dto/
- [ ] T023 [US3] Add forceRebaseline(siteId, reason, message, adminEmail) and requestLogs(siteId, message) methods to SiteService in src/main/java/com/bitbi/dfm/site/application/SiteService.java
- [ ] T024 [US3] Add POST /{siteId}/force-rebaseline (ROLE_ADMIN) and POST /{siteId}/request-logs (ROLE_ADMIN) endpoints to SiteAdminController in src/main/java/com/bitbi/dfm/site/presentation/SiteAdminController.java
- [ ] T025 [US3] Set forceFullUpload with reason PLUGIN_REINIT on all active CDC sites for the account during plugin reinit in PluginHistoryService in src/main/java/com/bitbi/dfm/plugin/application/PluginHistoryService.java

**Checkpoint**: Admin can force-rebaseline and request logs via API, plugin reinit triggers rebaseline directive

---

## Phase 8: US4 — Client Uploads Diagnostic Logs (Priority: P2)

**Goal**: Clients can upload diagnostic log files (.log, .log.gz, .txt, .txt.gz) with metadata. Server validates file type, enforces 10MB limit and 10/day/site rate limit, stores in S3, clears requestLogs directive.

**Independent Test**: Upload a .log.gz file via POST /api/v1/device/logs with metadata — verify 201 with logId and expiresAt. Upload invalid file type — verify 400. Exceed daily limit — verify 429.

### Implementation for User Story 4

- [ ] T026 [P] [US4] Create ClientDiagnosticLog JPA entity with fields (id, siteId, accountId, s3Key, filename, fileSize, contentType, clientVersion, os, periodFrom, periodTo, tags as JSONB, description, uploadedAt, expiresAt) in src/main/java/com/bitbi/dfm/clientlog/domain/ClientDiagnosticLog.java
- [ ] T027 [P] [US4] Create ClientDiagnosticLogRepository interface with countBySiteIdAndUploadedAtAfter(), findBySiteIdOrderByUploadedAtDesc(Pageable), findByExpiresAtBefore() methods in src/main/java/com/bitbi/dfm/clientlog/domain/ClientDiagnosticLogRepository.java
- [ ] T028 [US4] Create JpaClientDiagnosticLogRepository extending JpaRepository implementing ClientDiagnosticLogRepository in src/main/java/com/bitbi/dfm/clientlog/infrastructure/JpaClientDiagnosticLogRepository.java
- [ ] T029 [US4] Implement ClientDiagnosticLogService with uploadLog() — validate file type (.log/.log.gz/.txt/.txt.gz), check 10MB size limit, check 10/day/site rate limit, upload to S3 at client-logs/{accountId}/{siteId}/{date}/{logId}/{filename}, clear requestLogs flag on site in src/main/java/com/bitbi/dfm/clientlog/application/ClientDiagnosticLogService.java
- [ ] T030 [US4] Create ClientLogDeviceController with POST /api/v1/device/logs multipart endpoint (Custom JWT auth) accepting file + metadata fields in src/main/java/com/bitbi/dfm/clientlog/presentation/ClientLogDeviceController.java

**Checkpoint**: Clients can upload diagnostic logs, server validates and stores in S3

---

## Phase 9: US5 — Admin Views and Downloads Client Logs (Priority: P3)

**Goal**: Admins can list and download client diagnostic logs per site. Automatic 30-day retention cleanup.

**Independent Test**: GET /admin/sites/{siteId}/client-logs returns paginated log list. GET /admin/sites/{siteId}/client-logs/{logId}/download returns presigned URL. Verify retention scheduler deletes expired logs.

### Implementation for User Story 5

- [ ] T031 [P] [US5] Create ClientLogResponseDto (logId, siteId, filename, fileSize, clientVersion, os, periodFrom, periodTo, tags, description, uploadedAt, expiresAt) and ClientLogListResponseDto (content, page, size, totalElements) records in src/main/java/com/bitbi/dfm/clientlog/presentation/dto/
- [ ] T032 [US5] Add listLogs(siteId, Pageable) and getDownloadUrl(siteId, logId) methods (presigned URL via S3PresignedUrlService, 15-min expiry) to ClientDiagnosticLogService in src/main/java/com/bitbi/dfm/clientlog/application/ClientDiagnosticLogService.java
- [ ] T033 [US5] Create ClientLogAdminController with GET /api/v1/admin/sites/{siteId}/client-logs (paginated) and GET /api/v1/admin/sites/{siteId}/client-logs/{logId}/download (presigned URL) endpoints accessible to ROLE_ADMIN and ROLE_USER in src/main/java/com/bitbi/dfm/clientlog/presentation/ClientLogAdminController.java
- [ ] T034 [US5] Create ClientLogRetentionScheduler with configurable cron that deletes expired logs from S3 and database in src/main/java/com/bitbi/dfm/clientlog/application/ClientLogRetentionScheduler.java

**Checkpoint**: Admins can browse and download client logs, expired logs auto-cleaned

---

## Phase 10: US6 — DBF CDC Delta Mode (Priority: P3)

**Goal**: DBF_CDC site type works through the full CDC flow using the same pipeline as MSSQL_CDC and POSTGRES_CDC (already generalized in US1).

**Independent Test**: Authorize a device with siteType DBF_CDC, upload CSV baseline, then upload JSONL delta — verify SQL generation.

### Implementation for User Story 6

- [ ] T035 [US6] Verify and update device authorization flow to accept DBF_CDC site type — review DeviceAuthorizationService and DeviceAuthorizationController for any remaining hardcoded SiteType checks in src/main/java/com/bitbi/dfm/auth/ and src/main/java/com/bitbi/dfm/site/

**Checkpoint**: DBF_CDC sites complete full CDC flow using shared pipeline

---

## Phase 11: US12 — New Site Types Displayed in Site List (Priority: P2) [Frontend]

**Goal**: Site list displays correct badges for all four site types with distinct visual styling. Extend frontend Site type with heartbeat/directive fields for downstream stories.

**Independent Test**: View site list containing MSSQL_CDC and DBF_CDC sites — verify "MSSQL CDC" and "DBF CDC" badges with distinct styling. Existing DBF and POSTGRES_CDC badges unchanged.

### Implementation for User Story 12

- [ ] T036 [US12] Add MSSQL_CDC and DBF_CDC to SiteType union, extend Site type with lastHeartbeatAt, forceFullUpload, forceFullUploadReason, requestLogs, requestLogsMessage fields, and add batchType to Batch type in frontend/src/entities/site/model/types.ts
- [ ] T037 [US12] Add "MSSQL CDC" and "DBF CDC" badge variants with distinct colors (e.g., blue for MSSQL, purple for DBF CDC) to SiteListItem in frontend/src/widgets/site-list/ui/SiteListItem.tsx

**Checkpoint**: All four site types visually distinguishable in site list

---

## Phase 12: US9 — Admin Forces Rebaseline via UI (Priority: P2) [Frontend]

**Goal**: Admin can trigger force-rebaseline from site detail page with a confirmation dialog. Button shown only for CDC sites. Active directive displayed as indicator.

**Independent Test**: Navigate to CDC site detail → click "Force Rebaseline" → enter reason → confirm → verify success toast and directive indicator. Verify button hidden for DBF sites.

### Implementation for User Story 9

- [ ] T038 [US9] Add forceRebaseline mutation (POST /admin/sites/{siteId}/force-rebaseline) and endpoint route to siteApi in frontend/src/features/site-crud/api/siteApi.ts and frontend/src/shared/api/apiRoutes.ts
- [ ] T039 [US9] Create ForceRebaselineDialog with AlertDialog, required reason Input, warning text, and confirm/cancel buttons using shadcn/ui in frontend/src/features/site-crud/ui/ForceRebaselineDialog.tsx
- [ ] T040 [US9] Add "Force Rebaseline" button visible only for CDC sites (isCdc check), active directive badge, and ForceRebaselineDialog integration to SiteDetailPage in frontend/src/pages/site-management/SiteDetailPage.tsx

**Checkpoint**: Admin can force-rebaseline CDC sites through UI

---

## Phase 13: US11 — Admin Views Client Logs in UI (Priority: P2) [Frontend]

**Goal**: Site detail page has a "Client Logs" tab showing paginated diagnostic log entries with metadata and download capability.

**Independent Test**: Navigate to site with uploaded logs → open "Client Logs" tab → verify table with filename, size, version, OS, tags, description, upload date. Click download → verify file downloads. Verify pagination. Verify empty state for site with no logs.

### Implementation for User Story 11

- [ ] T041 [US11] Create ClientLog and ClientLogListResponse TypeScript interfaces in frontend/src/features/client-logs/model/types.ts
- [ ] T042 [P] [US11] Create clientLogsApi with listClientLogs(siteId, page, size) and downloadClientLog(siteId, logId) functions in frontend/src/features/client-logs/api/clientLogsApi.ts
- [ ] T043 [P] [US11] Create useClientLogs and useClientLogDownload TanStack Query hooks in frontend/src/features/client-logs/api/clientLogsQueries.ts
- [ ] T044 [US11] Add client-logs endpoint routes (list, download) to apiRoutes in frontend/src/shared/api/apiRoutes.ts
- [ ] T045 [US11] Create ClientLogEntry component rendering filename, human-readable file size, client version, OS, tags as Badge components, truncated description, upload date, and download Button in frontend/src/features/client-logs/ui/ClientLogEntry.tsx
- [ ] T046 [US11] Create ClientLogsTab with paginated Table of ClientLogEntry rows, page size selector (20, 50, 100), previous/next pagination, and "No diagnostic logs uploaded yet" empty state in frontend/src/features/client-logs/ui/ClientLogsTab.tsx
- [ ] T047 [US11] Add "Client Logs" tab to SiteDetailPage tabs section rendering ClientLogsTab with siteId prop in frontend/src/pages/site-management/SiteDetailPage.tsx

**Checkpoint**: Admin can view and download client diagnostic logs in site detail UI

---

## Phase 14: US10 — Admin Requests Client Logs via UI (Priority: P3) [Frontend]

**Goal**: Admin can request client logs via a dialog on the site detail page. Button disabled when requestLogs directive is already active.

**Independent Test**: Click "Request Logs" → enter optional message → confirm → verify success toast. Verify button disabled when requestLogs already active.

### Implementation for User Story 10

- [ ] T048 [US10] Add requestLogs mutation (POST /admin/sites/{siteId}/request-logs) and endpoint route to siteApi in frontend/src/features/site-crud/api/siteApi.ts and frontend/src/shared/api/apiRoutes.ts
- [ ] T049 [US10] Create RequestLogsDialog with AlertDialog, optional message Input, and confirm/cancel buttons using shadcn/ui in frontend/src/features/site-crud/ui/RequestLogsDialog.tsx
- [ ] T050 [US10] Add "Request Logs" button (disabled when requestLogs active, showing "Logs Requested" indicator) and RequestLogsDialog integration to SiteDetailPage in frontend/src/pages/site-management/SiteDetailPage.tsx

**Checkpoint**: Admin can request client logs through UI

---

## Phase 15: US13 — Heartbeat & Directive Status on Site Detail (Priority: P3) [Frontend]

**Goal**: Site detail page displays last heartbeat time and active directive status for operational visibility.

**Independent Test**: View site detail for site with recent heartbeat → verify "Last heartbeat: X minutes ago". View site with no heartbeat → verify "Never". View site with active forceFullUpload → verify alert with reason. View site with requestLogs → verify indicator.

### Implementation for User Story 13

- [ ] T051 [US13] Add heartbeat status section (last heartbeat relative time or "Never") and directive indicators (forceFullUpload alert with reason/timestamp, requestLogs indicator) to SiteDetailPage info area in frontend/src/pages/site-management/SiteDetailPage.tsx

**Checkpoint**: Heartbeat and directive status visible on site detail page

---

## Phase 16: US14 — Batch Type Displayed in Batch History (Priority: P3) [Frontend]

**Goal**: Batch list shows "Baseline" or "Delta" badge per batch. Legacy batches without batchType show no badge.

**Independent Test**: View batch list with BASELINE and DELTA batches → verify correct badges. View legacy batch without batchType → verify no badge displayed.

### Implementation for User Story 14

- [ ] T052 [P] [US14] Add batchType badge column rendering "Baseline" (gray) or "Delta" (blue) Badge, null-safe for legacy batches, to FileTable in frontend/src/features/upload-history/ui/FileTable.tsx
- [ ] T053 [P] [US14] Add batchType indicator to batch info display in BatchSqlTab in frontend/src/features/my-plugins/ui/BatchSqlTab.tsx

**Checkpoint**: Batch type visually clear in upload history and SQL tabs

---

## Phase 17: Polish & Cross-Cutting Concerns

**Purpose**: End-to-end validation and backward compatibility verification

- [ ] T054 Run quickstart.md validation scenarios to verify all endpoints work end-to-end (heartbeat → batch start → upload → SQL generation for each site type)
- [ ] T055 Verify backward compatibility — existing DBF clients work without schema during grace period, existing POSTGRES_CDC clients unaffected, legacy batches without batchType display correctly

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup (Phase 1) — BLOCKS all user stories
- **US2 Heartbeat (Phase 3)**: Depends on Phase 2 — BLOCKS US8 (batch start needs heartbeat check)
- **US8 Batch Type (Phase 4)**: Depends on Phase 3 — BLOCKS US1 (MSSQL CDC needs batch start)
- **US1 MSSQL CDC (Phase 5)**: Depends on Phase 4
- **US7 DBF Schema (Phase 6)**: Depends on Phase 4 (modifies BatchLifecycleService after batch type changes)
- **US3 Force Rebaseline (Phase 7)**: Depends on Phase 2 only — can run in parallel with Phases 3-6
- **US4 Log Upload (Phase 8)**: Depends on Phase 2 only — can run in parallel with Phases 3-7
- **US5 Log Viewer (Phase 9)**: Depends on Phase 8 (US4)
- **US6 DBF CDC (Phase 10)**: Depends on Phase 5 (US1, which generalized CDC pipeline)
- **Frontend Phases (11-16)**: Depend on their corresponding backend phases being complete
- **Polish (Phase 17)**: Depends on all desired phases being complete

### User Story Dependencies

```
Phase 2 (Foundational)
  ├── Phase 3 (US2 Heartbeat) ──→ Phase 4 (US8 Batch Type) ──→ Phase 5 (US1 MSSQL CDC) ──→ Phase 10 (US6 DBF CDC)
  │                                       └──→ Phase 6 (US7 DBF Schema)
  ├── Phase 7 (US3 Force Rebaseline) [parallel with Phases 3-6]
  └── Phase 8 (US4 Log Upload) ──→ Phase 9 (US5 Log Viewer) [parallel with Phases 3-7]

Frontend (after backend complete):
  Phase 11 (US12 Badges) ──→ Phase 12 (US9 Rebaseline UI)
                          ──→ Phase 13 (US11 Logs UI)
                          ──→ Phase 14 (US10 Request Logs UI)
                          ──→ Phase 15 (US13 Heartbeat Status)
                          ──→ Phase 16 (US14 Batch Type Badge)
```

### Within Each User Story

- Domain objects (enums, entities, DTOs) before services
- Services before controllers
- Core implementation before integration points
- Story complete before moving to next priority

### Parallel Opportunities

**Backend parallel tracks** (after Phase 2):
- Track A: US2 → US8 → US1 → US7 → US6 (batch start & CDC pipeline)
- Track B: US3 (force rebaseline — independent)
- Track C: US4 → US5 (client logs — independent)

**Frontend parallel** (after Phase 11):
- Phases 12-16 can all run in parallel (different files/features)

**Within phases** (tasks marked [P]):
- Phase 1: T002 and T003 can run in parallel
- Phase 2: T005 and T006 can run in parallel
- Phase 5: T019 and T020 can run in parallel
- Phase 8: T026 and T027 can run in parallel
- Phase 13: T042 and T043 can run in parallel
- Phase 16: T052 and T053 can run in parallel

---

## Parallel Example: Backend Tracks

```bash
# After Phase 2 completes, launch 3 independent tracks:

# Track A: Batch start & CDC pipeline
Task: T011-T014 (US2 Heartbeat)
Task: T015-T017 (US8 Batch Type)
Task: T018-T020 (US1 MSSQL CDC)
Task: T021 (US7 DBF Schema)
Task: T035 (US6 DBF CDC)

# Track B: Force rebaseline (parallel with Track A)
Task: T022-T025 (US3 Force Rebaseline)

# Track C: Client logs (parallel with Track A and B)
Task: T026-T030 (US4 Log Upload)
Task: T031-T034 (US5 Log Viewer)
```

## Parallel Example: Frontend

```bash
# After Phase 11 (US12 Badges) completes, launch all remaining frontend phases in parallel:
Task: T038-T040 (US9 Force Rebaseline UI)
Task: T041-T047 (US11 Client Logs UI)
Task: T048-T050 (US10 Request Logs UI)
Task: T051 (US13 Heartbeat Status)
Task: T052-T053 (US14 Batch Type Badge)
```

---

## Implementation Strategy

### MVP First (US2 + US8 + US1 Only)

1. Complete Phase 1: Setup (migration, config)
2. Complete Phase 2: Foundational (enums, entities, security)
3. Complete Phase 3: US2 Heartbeat
4. Complete Phase 4: US8 Batch Type
5. Complete Phase 5: US1 MSSQL CDC
6. **STOP and VALIDATE**: Test MSSQL CDC end-to-end flow independently
7. Deploy/demo if ready — an MSSQL CDC client can complete the full upload cycle

### Incremental Delivery

1. Setup + Foundational → Foundation ready
2. US2 + US8 + US1 → MSSQL CDC flow works → **Deploy (MVP!)**
3. US7 → DBF schema enforcement active → Deploy
4. US3 → Force rebaseline via API → Deploy
5. US4 + US5 → Client diagnostic logs → Deploy
6. US6 → DBF CDC mode → Deploy
7. US12 → Frontend site type badges → Deploy
8. US9 + US11 → Force rebaseline UI + client logs UI → Deploy
9. US10 + US13 + US14 → Polish UI features → Deploy

### Parallel Team Strategy

With multiple developers after Phase 2 completes:

- **Developer A**: Track A — US2 → US8 → US1 → US7 → US6 (batch pipeline)
- **Developer B**: Track B+C — US3 → US4 → US5 (admin features + client logs)
- **Developer C**: Frontend — US12 → US9 + US11 → US10 + US13 + US14 (after backend endpoints ready)

---

## Notes

- [P] tasks = different files, no dependencies on incomplete tasks
- [Story] label maps task to specific user story for traceability
- Each user story is independently completable and testable after its dependencies
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- BatchLifecycleService is modified across 4 phases (US2, US8, US7, + foundational) — implement sequentially
- SiteDetailPage.tsx is modified across 5 frontend phases (US9, US11, US10, US13, + base) — implement sequentially or merge
- Existing POSTGRES_CDC behavior MUST remain unchanged throughout all modifications
