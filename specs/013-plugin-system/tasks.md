# Tasks: Plugin System & Bit BI OAuth Integration

**Input**: Design documents from `/specs/013-plugin-system/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/plugin-api.yaml

**Tests**: Test tasks included per Testing Strategy in plan.md (JUnit 5 + Mockito for unit, MockMvc for contract, Testcontainers for integration).

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

This project uses single-project structure with existing DDD package-by-layered-feature pattern:
- Source: `src/main/java/com/bitbi/dfm/`
- Tests: `src/test/java/com/bitbi/dfm/`
- Migrations: `src/main/resources/db/migration/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project dependencies, database schema, and core plugin infrastructure

- [x] T001 Add json-schema-validator dependency to build.gradle.kts (`implementation("com.networknt:json-schema-validator:1.5.4")`)
- [x] T002 [P] Create database migration V8__create_plugin_tables.sql for `plugin_configs` and `account_plugins` tables in src/main/resources/db/migration/
- [x] T003 [P] Create database migration V9__create_plugin_audit_logs_partitioned.sql for partitioned `plugin_audit_logs` table in src/main/resources/db/migration/
- [x] T004 [P] Create plugin package structure: src/main/java/com/bitbi/dfm/plugin/{domain,application,infrastructure,presentation}

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core domain objects, interfaces, and infrastructure that ALL user stories depend on

**CRITICAL**: No user story work can begin until this phase is complete

### Domain Layer

- [x] T005 [P] Create PluginEventType enum in src/main/java/com/bitbi/dfm/plugin/domain/PluginEventType.java (BATCH_COMPLETED, BATCH_FAILED, BATCH_EXPIRED, FILE_UPLOADED)
- [x] T006 [P] Create PluginActionType enum in src/main/java/com/bitbi/dfm/plugin/domain/PluginActionType.java (ACTIVATE, DEACTIVATE, REACTIVATE, EVENT_DISPATCHED, EVENT_FAILED, EVENT_TIMEOUT)
- [x] T007 [P] Create PluginEvent value object in src/main/java/com/bitbi/dfm/plugin/domain/PluginEvent.java (eventId, type, accountId, resourceId, metadata, occurredAt)
- [x] T008 [P] Create Plugin interface in src/main/java/com/bitbi/dfm/plugin/domain/Plugin.java (getId, getName, getVersion, getSupportedEvents, getSchemaJson, execute, onActivate, onDeactivate)
- [x] T009 Create PluginConfig entity in src/main/java/com/bitbi/dfm/plugin/domain/PluginConfig.java (depends on T005 for event types)
- [x] T010 Create AccountPlugin entity in src/main/java/com/bitbi/dfm/plugin/domain/AccountPlugin.java (depends on T005, includes activate/deactivate/reactivate/recordUsage methods)
- [x] T011 Create PluginAuditLog entity in src/main/java/com/bitbi/dfm/plugin/domain/PluginAuditLog.java (depends on T006)

### Repository Interfaces

- [x] T012 [P] Create PluginConfigRepository interface in src/main/java/com/bitbi/dfm/plugin/domain/PluginConfigRepository.java
- [x] T013 [P] Create AccountPluginRepository interface in src/main/java/com/bitbi/dfm/plugin/domain/AccountPluginRepository.java
- [x] T014 [P] Create PluginAuditLogRepository interface in src/main/java/com/bitbi/dfm/plugin/domain/PluginAuditLogRepository.java

### Infrastructure Layer

- [x] T015 [P] Create JpaPluginConfigRepository in src/main/java/com/bitbi/dfm/plugin/infrastructure/persistence/JpaPluginConfigRepository.java
- [x] T016 [P] Create JpaAccountPluginRepository in src/main/java/com/bitbi/dfm/plugin/infrastructure/persistence/JpaAccountPluginRepository.java (with findByAccountIdAndPluginId, findActiveByAccountId)
- [x] T017 [P] Create JpaPluginAuditLogRepository in src/main/java/com/bitbi/dfm/plugin/infrastructure/persistence/JpaPluginAuditLogRepository.java

### Plugin Registry

- [x] T018 Create PluginRegistry component in src/main/java/com/bitbi/dfm/plugin/domain/PluginRegistry.java (depends on T008, collects List<Plugin> via constructor injection)
- [x] T019 Create PluginStartupValidator in src/main/java/com/bitbi/dfm/plugin/infrastructure/PluginStartupValidator.java (ApplicationRunner, validates plugins have DB configs, <100ms per SC-005)

### Async Configuration

- [x] T020 Create PluginAsyncConfiguration in src/main/java/com/bitbi/dfm/plugin/infrastructure/PluginAsyncConfiguration.java (ThreadPoolTaskExecutor bean "pluginExecutor", 5 core, 10 max, 50 queue)

### Security Configuration

- [x] T021 Add plugin API filter chain to SecurityConfiguration.java (@Order(3), /api/v1/plugins/**, oauth2ResourceServer with jwtAuthenticationConverter)

**Checkpoint**: Foundation ready - user story implementation can now begin

---

## Phase 3: User Story 2 - Activate a Plugin for an Account (Priority: P1)

**Goal**: Enable third-party applications to activate plugins for user accounts via authenticated API endpoint

**Independent Test**: Call activation endpoint with valid OAuth token and verify database record is created with correct plugin data

**Why US2 First**: US2 (plugin activation) is the foundational API required before US1 (OAuth flow) can complete. The OAuth flow culminates in calling the activation endpoint.

### Validation Service

- [x] T022 [US2] Create PluginDataValidator service in src/main/java/com/bitbi/dfm/plugin/application/PluginDataValidator.java (JSON Schema validation with caching, throws PluginDataValidationException)

### DTOs

- [x] T023 [P] [US2] Create ActivatePluginRequestDto in src/main/java/com/bitbi/dfm/plugin/presentation/dto/ActivatePluginRequestDto.java (@NotNull pluginData Map)
- [x] T024 [P] [US2] Create PluginActivationResponseDto in src/main/java/com/bitbi/dfm/plugin/presentation/dto/PluginActivationResponseDto.java (pluginId, pluginName, accountId, isActive, activatedAt, lastUsedAt)

### Application Service

- [x] T025 [US2] Create PluginActivationService in src/main/java/com/bitbi/dfm/plugin/application/PluginActivationService.java (activate method with upsert logic per FR-005, calls plugin.onActivate hook per FR-006)

### Domain Exceptions

- [x] T026 [P] [US2] Create PluginNotFoundException in src/main/java/com/bitbi/dfm/plugin/domain/exception/PluginNotFoundException.java
- [x] T027 [P] [US2] Create PluginDataValidationException in src/main/java/com/bitbi/dfm/plugin/domain/exception/PluginDataValidationException.java
- [x] T028 [P] [US2] Create PluginNotEnabledException in src/main/java/com/bitbi/dfm/plugin/domain/exception/PluginNotEnabledException.java

### Controller

- [x] T029 [US2] Create PluginController in src/main/java/com/bitbi/dfm/plugin/presentation/PluginController.java (POST /api/v1/plugins/{pluginId}/activate endpoint, returns 201/200 based on create/update)

### Exception Handling

- [x] T030 [US2] Add plugin exception handlers to GlobalExceptionHandler.java (PluginNotFoundException -> 404, PluginDataValidationException -> 400, PluginNotEnabledException -> 404)

### Tests for User Story 2

- [x] T031 [P] [US2] Create PluginDataValidatorTest unit test in src/test/java/com/bitbi/dfm/plugin/unit/PluginDataValidatorTest.java (valid/invalid tenantId, schema caching)
- [x] T032 [P] [US2] Create PluginActivationServiceTest unit test in src/test/java/com/bitbi/dfm/plugin/unit/PluginActivationServiceTest.java (activate calls onActivate, exceptions)
- [x] T033 [P] [US2] Create AccountPluginTest unit test in src/test/java/com/bitbi/dfm/plugin/unit/AccountPluginTest.java (activate/deactivate/reactivate methods)
- [x] T034 [US2] Create PluginActivationContractTest in src/test/java/com/bitbi/dfm/plugin/contract/PluginActivationContractTest.java (POST activate → 201/200/400/404)
- [x] T035 [US2] Create PluginActivationIntegrationTest in src/test/java/com/bitbi/dfm/plugin/integration/PluginActivationIntegrationTest.java (Testcontainers, full activation flow)

**Checkpoint**: Plugin activation API is functional and tested. Users can activate plugins via POST /api/v1/plugins/{pluginId}/activate

---

## Phase 4: User Story 1 - Connect DFM Account to Bit BI (Priority: P1)

**Goal**: Enable Bit BI users to connect their DFM account to Bit BI via OAuth flow

**Independent Test**: Complete OAuth flow from Bit BI and verify account-plugin link is created

**Dependency**: Requires US2 (activation endpoint) to be complete

### Bit BI Plugin Implementation

- [x] T036 [US1] Create BitBiPlugin in src/main/java/com/bitbi/dfm/plugin/application/BitBiPlugin.java (@Component, implements Plugin interface, BATCH_COMPLETED event, tenantId schema)

### Configuration

- [x] T037 [US1] Add Bit BI plugin configuration to application.yml (plugins.bitbi.enabled, plugins.bitbi.client-id placeholders)
- [x] T038 [US1] Add Bit BI seed data to V18 migration (INSERT INTO plugin_configs for bit-bi) [Note: Already in V8__create_plugin_tables.sql]

### Tests for User Story 1

- [x] T039 [US1] Create BitBiPluginTest unit test in src/test/java/com/bitbi/dfm/plugin/unit/BitBiPluginTest.java (plugin ID, supported events, schema validation, onActivate)

**Checkpoint**: Bit BI plugin is registered, can be activated, and is tested. OAuth flow completes successfully.

---

## Phase 5: User Story 3 - Deactivate a Plugin Integration (Priority: P2)

**Goal**: Allow users to revoke plugin access by deactivating the integration

**Independent Test**: Deactivate an active plugin and verify subsequent API calls are rejected

### Application Service Extension

- [x] T040 [US3] Add deactivate method to PluginActivationService in src/main/java/com/bitbi/dfm/plugin/application/PluginActivationService.java (sets is_active=false, deactivated_at, calls plugin.onDeactivate hook)

### Domain Exception

- [x] T041 [P] [US3] Create PluginNotActivatedException in src/main/java/com/bitbi/dfm/plugin/domain/exception/PluginNotActivatedException.java

### Controller Extension

- [x] T042 [US3] Add DELETE /api/v1/plugins/{pluginId}/deactivate endpoint to PluginController.java (returns 204 on success)

### Exception Handling

- [x] T043 [US3] Add PluginNotActivatedException handler to GlobalExceptionHandler.java (-> 403 Forbidden)

### Tests for User Story 3

- [x] T044 [US3] Create PluginDeactivationContractTest in src/test/java/com/bitbi/dfm/plugin/contract/PluginDeactivationContractTest.java (DELETE deactivate → 204/403/404)

**Checkpoint**: Users can deactivate plugin integrations. Deactivated plugins no longer receive events.

---

## Phase 6: User Story 4 - View Active Plugin Integrations (Priority: P2)

**Goal**: Allow users to see which third-party applications have access to their account

**Independent Test**: Activate plugins and retrieve the list to verify accurate display

### DTO

- [x] T045 [P] [US4] Create AccountPluginSummaryDto in src/main/java/com/bitbi/dfm/plugin/presentation/dto/AccountPluginSummaryDto.java (pluginId, pluginName, isActive, activatedAt, deactivatedAt, lastUsedAt - NO pluginData per FR-012)
- [x] T046 [P] [US4] Create AccountPluginListResponseDto in src/main/java/com/bitbi/dfm/plugin/presentation/dto/AccountPluginListResponseDto.java (PageResponseDto wrapper)

### Query Service

- [x] T047 [US4] Create PluginQueryService in src/main/java/com/bitbi/dfm/plugin/application/PluginQueryService.java (listAccountPlugins with pagination, includeInactive filter)

### Controller

- [x] T048 [US4] Create AccountPluginsController in src/main/java/com/bitbi/dfm/plugin/presentation/AccountPluginsController.java (GET /api/v1/account/plugins endpoint)

### Tests for User Story 4

- [x] T049 [US4] Create AccountPluginsContractTest in src/test/java/com/bitbi/dfm/plugin/contract/AccountPluginsContractTest.java (GET list → 200, includeInactive filter, empty list)

**Checkpoint**: Users can view their active plugin integrations without exposing sensitive plugin data.

---

## Phase 7: User Story 5 - Receive Batch Completion Notifications (Priority: P2)

**Goal**: Activated plugins automatically receive events when batch processing completes

**Independent Test**: Complete a batch upload and verify the plugin's execute method is called with correct event data

### Event Extension

- [x] T050 [US5] Extend BatchCompletedEvent in src/main/java/com/bitbi/dfm/shared/domain/events/BatchCompletedEvent.java to include accountId field (nullable for backward compatibility)

### Event Listener

- [x] T051 [US5] Create BatchEventListener in src/main/java/com/bitbi/dfm/plugin/infrastructure/events/BatchEventListener.java (@EventListener for BatchCompletedEvent, creates PluginEvent, calls dispatcher)

### Event Dispatcher

- [x] T052 [US5] Create PluginEventDispatcher in src/main/java/com/bitbi/dfm/plugin/application/PluginEventDispatcher.java (async dispatch with 30s timeout per FR-008, isolated failures, updates last_used_at per FR-018)

### Batch Service Integration

- [x] T053 [US5] Update BatchLifecycleService to include accountId in BatchCompletedEvent (existing file modification)

### Tests for User Story 5

- [x] T054 [P] [US5] Create PluginEventDispatcherTest unit test in src/test/java/com/bitbi/dfm/plugin/unit/PluginEventDispatcherTest.java (dispatch to subscribed, timeout, isolated failures)
- [x] T055 [US5] Create PluginEventDispatchIntegrationTest in src/test/java/com/bitbi/dfm/plugin/integration/PluginEventDispatchIntegrationTest.java (Testcontainers, event triggers plugin, deactivated skipped)

**Checkpoint**: Batch completion events are dispatched to subscribed plugins within 500ms (SC-003).

---

## Phase 8: User Story 6 - Admin Views Plugin Audit Trail (Priority: P3)

**Goal**: Administrators can monitor plugin usage and troubleshoot issues via audit log

**Independent Test**: Perform plugin operations and verify they appear in audit log with correct metadata

### Audit Service

- [x] T056 [US6] Create PluginAuditService in src/main/java/com/bitbi/dfm/plugin/application/PluginAuditService.java (logActivation, logDeactivation, logEventDispatch, logEventFailure methods)

### Audit Filter

- [x] T057 [US6] Create PluginAuditFilter in src/main/java/com/bitbi/dfm/plugin/infrastructure/PluginAuditFilter.java (OncePerRequestFilter, ContentCachingRequestWrapper, SHA-256 hashing per FR-014)

### Integration with Activation Service

- [x] T058 [US6] Integrate PluginAuditService calls into PluginActivationService.java (log activate/deactivate/reactivate actions)

### Integration with Event Dispatcher

- [x] T059 [US6] Integrate PluginAuditService calls into PluginEventDispatcher.java (log EVENT_DISPATCHED, EVENT_FAILED, EVENT_TIMEOUT)

### Admin DTOs

- [x] T060 [P] [US6] Create PluginAuditLogEntryDto in src/main/java/com/bitbi/dfm/plugin/presentation/dto/PluginAuditLogEntryDto.java
- [x] T061 [P] [US6] Create PluginAuditLogPageResponseDto in src/main/java/com/bitbi/dfm/plugin/presentation/dto/PluginAuditLogPageResponseDto.java
- [x] T062 [P] [US6] Create PluginConfigResponseDto in src/main/java/com/bitbi/dfm/plugin/presentation/dto/PluginConfigResponseDto.java

### Admin Controller

- [x] T063 [US6] Create PluginAdminController in src/main/java/com/bitbi/dfm/plugin/presentation/PluginAdminController.java (GET /api/v1/admin/plugins, GET /api/v1/admin/plugins/audit with filters)

### Admin Query Service

- [x] T064 [US6] Create PluginAdminQueryService in src/main/java/com/bitbi/dfm/plugin/application/PluginAdminQueryService.java (listRegisteredPlugins, queryAuditLogs with filters)

### Security Configuration

- [x] T065 [US6] Add admin plugin routes to SecurityConfiguration.java (/api/v1/admin/plugins/** requires ROLE_ADMIN)

### Tests for User Story 6

- [x] T066 [US6] Create PluginAdminContractTest in src/test/java/com/bitbi/dfm/plugin/contract/PluginAdminContractTest.java (GET plugins/audit → 200, filters, 403 unauthorized)
- [x] T067 [US6] Create PluginAuditIntegrationTest in src/test/java/com/bitbi/dfm/plugin/integration/PluginAuditIntegrationTest.java (Testcontainers, audit entries created)

**Checkpoint**: Administrators can query audit logs and diagnose plugin issues within 5 minutes (SC-007).

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Final improvements and validation

- [x] T068 [P] Add MDC context logging (pluginId, accountId) to PluginActivationService and PluginEventDispatcher
- [x] T069 [P] Add Micrometer metrics (plugin.activation.duration, plugin.event.dispatch.duration, plugin.event.dispatch.count)
- [x] T070 Run quickstart.md validation scenarios
- [x] T071 Verify all performance criteria met (SC-002: activation <200ms, SC-003: dispatch <500ms, SC-005: startup <100ms)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately ✅ COMPLETE
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories ✅ COMPLETE
- **User Story 2 (Phase 3)**: Depends on Foundational phase - Activation API ✅ COMPLETE
- **User Story 1 (Phase 4)**: Depends on US2 completion - OAuth flow uses activation endpoint ✅ COMPLETE
- **User Story 3 (Phase 5)**: Depends on Foundational phase - can run parallel to US2/US1 ✅ COMPLETE
- **User Story 4 (Phase 6)**: Depends on Foundational phase - can run parallel to other stories ✅ COMPLETE
- **User Story 5 (Phase 7)**: Depends on Foundational phase - can run parallel to other stories ✅ COMPLETE
- **User Story 6 (Phase 8)**: Depends on US2, US3, US5 for meaningful audit data ✅ COMPLETE
- **Polish (Phase 9)**: Depends on all user stories being complete ✅ COMPLETE

### User Story Dependencies

```
Phase 1: Setup ✅
    │
    ▼
Phase 2: Foundational ✅
    │
    ├───────────────────────────────────────────┐
    │                                           │
    ▼                                           │
Phase 3: US2 (Activate) ✅ ────┐                │
    │                          │                │
    ▼                          │                │
Phase 4: US1 (OAuth+BitBI) ✅  │                │
    │                          │                │
    │                          ▼                ▼
    │                    Phase 5: US3 ✅   Phase 6: US4 ✅
    │                    (Deactivate)      (List Plugins)
    │                          │                │
    │                          ▼                │
    │                    Phase 7: US5 ✅        │
    │                    (Events)               │
    │                          │                │
    └──────────────────────────┼────────────────┘
                               │
                               ▼
                    Phase 8: US6 ✅ (Admin Audit)
                               │
                               ▼
                    Phase 9: Polish ✅
```

### Within Each User Story

- DTOs and exceptions (marked [P]) can run in parallel
- Repositories before services
- Services before controllers
- Controllers depend on services, DTOs, and exception handlers
- Tests can run in parallel where marked [P]

### Parallel Opportunities

- All Setup tasks marked [P] can run in parallel (T002, T003, T004) ✅
- All Foundational enums/value objects marked [P] can run in parallel (T005, T006, T007, T008) ✅
- All repository interfaces marked [P] can run in parallel (T012, T013, T014) ✅
- All JPA repositories marked [P] can run in parallel (T015, T016, T017) ✅
- Test tasks marked [P] can run in parallel within each phase
- DTOs within each story marked [P] can run in parallel

---

## Test Summary

| User Story | Contract Tests | Unit Tests | Integration Tests |
|------------|---------------|------------|-------------------|
| US2 (Activate) | T034 | T031, T032, T033 | T035 |
| US1 (Bit BI) | - | T039 | - |
| US3 (Deactivate) | T044 | - | - |
| US4 (List) | T049 | - | - |
| US5 (Events) | - | T054 | T055 |
| US6 (Admin) | T066 | - | T067 |

**Total**: 4 Contract Tests, 5 Unit Tests, 3 Integration Tests = **12 Test Tasks**

---

## Implementation Strategy

### MVP First (User Stories 1 + 2) ✅ COMPLETE

1. ✅ Complete Phase 1: Setup
2. ✅ Complete Phase 2: Foundational (CRITICAL - blocks all stories)
3. ✅ Complete Phase 3: User Story 2 Implementation (Activation API)
4. ✅ Complete Phase 4: User Story 1 Implementation (Bit BI Plugin + OAuth)
5. ✅ Add tests for US2 + US1
6. ✅ Deploy/demo ready

### Incremental Delivery

1. ✅ Complete Setup + Foundational → Foundation ready
2. ✅ Add US2 (Activate) → ✅ Test activation API → Deploy/Demo (MVP Milestone 1)
3. ✅ Add US1 (BitBI) → ✅ Test OAuth flow → Deploy/Demo (MVP Milestone 2)
4. ✅ Add US3 (Deactivate) → ✅ Test → US4 (List) + US5 (Events) → Test → Deploy/Demo
5. ✅ Add US6 (Admin Audit) → ✅ Test admin features → Deploy/Demo
6. ✅ Complete Polish phase → Final release

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- ✅ marks completed tasks (All phases complete)

---

## Phase 10: Admin UI (Added 2025-12-20)

**Purpose**: Frontend admin interface for viewing plugins and audit logs

### API Routes

- [x] T072 [P] Add ADMIN_PLUGINS routes to frontend/src/shared/api/apiRoutes.ts

### Entity Layer

- [x] T073 [P] Create plugin types in frontend/src/entities/plugin/model/types.ts (PluginConfig, PluginAuditLogEntry, AuditLogFilters)
- [x] T074 [P] Create query key factory in frontend/src/entities/plugin/api/keys.ts
- [x] T075 [P] Create PluginStatusBadge component in frontend/src/entities/plugin/ui/PluginStatusBadge.tsx
- [x] T076 [P] Create ActionTypeBadge component in frontend/src/entities/plugin/ui/ActionTypeBadge.tsx
- [x] T077 [P] Create barrel export frontend/src/entities/plugin/index.ts

### Feature Layer

- [x] T078 [P] Create API client in frontend/src/features/plugin-admin/api/pluginAdminApi.ts
- [x] T079 [P] Create TanStack Query hooks in frontend/src/features/plugin-admin/api/pluginQueries.ts
- [x] T080 [P] Create PluginListView component in frontend/src/features/plugin-admin/ui/PluginListView.tsx
- [x] T081 [P] Create AuditLogFilters component in frontend/src/features/plugin-admin/ui/AuditLogFilters.tsx
- [x] T082 [P] Create AuditLogTable component in frontend/src/features/plugin-admin/ui/AuditLogTable.tsx
- [x] T083 [P] Create barrel export frontend/src/features/plugin-admin/index.ts

### Widget Layer

- [x] T084 [P] Create PluginListWidget in frontend/src/widgets/plugin-admin/PluginListWidget.tsx
- [x] T085 [P] Create AuditLogWidget in frontend/src/widgets/plugin-admin/AuditLogWidget.tsx

### Page and Routing

- [x] T086 Create PluginsAdminPage in frontend/src/pages/admin/plugins/PluginsAdminPage.tsx
- [x] T087 Add /admin/plugins route to frontend/src/app/router.tsx
- [x] T088 Add "Plugins" link to Header for admin users in frontend/src/widgets/header/Header.tsx

### Documentation

- [x] T089 [P] Update specs/013-plugin-system/spec.md with Admin UI Requirements section
- [x] T090 [P] Update specs/013-plugin-system/tasks.md with Phase 10 tasks

**Checkpoint**: Admin can view registered plugins and audit logs via /admin/plugins

---

## Implementation Complete

All phases of the Plugin System & Bit BI OAuth Integration feature have been implemented:
- Phase 1-8: Core backend implementation and testing
- Phase 9: Polish & Cross-Cutting Concerns (MDC logging, Micrometer metrics, validation)
- Phase 10: Admin UI for viewing plugins and audit logs

The feature is ready for deployment.
