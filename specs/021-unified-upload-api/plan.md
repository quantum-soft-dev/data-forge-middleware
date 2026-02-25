# Implementation Plan: Unified Data Upload API

**Branch**: `021-unified-upload-api` | **Date**: 2026-02-25 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/021-unified-upload-api/spec.md`

## Summary

Extend Data Forge Middleware to support unified data uploads from multiple database sources (MS SQL Server, DBF CDC). Add `MSSQL_CDC` and `DBF_CDC` site types, a heartbeat endpoint for server-to-client directives, client diagnostic log upload/management, admin force-rebaseline, mandatory schema enforcement with grace period, batch type tracking, and schema version pinning. The existing CDC SQL generation pipeline is reused for all CDC site types with no strategy changes. The admin frontend is extended with force-rebaseline and log-request actions on the site detail page, a Client Logs tab for viewing/downloading diagnostic logs, new site type badges, heartbeat/directive status display, and batch type indicators.

## Technical Context

**Language/Version**: Java 21 (LTS) + Spring Boot 3.5.6 | React 19.2 + TypeScript 5.6
**Primary Dependencies**: Spring Data JPA, Spring Security 6 (Auth0 OAuth2), AWS SDK v2 (S3), Hypersistence Utils (JSONB) | TanStack Query v5, React Router v6, shadcn/ui, Tailwind CSS 3.4
**Storage**: PostgreSQL 16 + AWS S3
**Testing**: JUnit 5 + Mockito + Testcontainers (PostgreSQL + LocalStack S3) | Vitest + React Testing Library
**Target Platform**: Linux server (Docker) | Modern browsers
**Project Type**: Web application (backend + frontend)
**Performance Goals**: Heartbeat endpoint <200ms response time
**Constraints**: Backward compatibility with existing DBF and PostgreSQL CDC clients; frontend follows Feature-Sliced Design
**Scale/Scope**: Backend: ~15 modified files, ~12 new files, 1 Flyway migration. Frontend: ~8 new files, ~5 modified files.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Constitution is unpopulated (template placeholders). No project-specific gates defined. Proceeding with standard engineering practices:

- **DDD Package Structure**: New `clientlog` aggregate follows existing pattern (domain/application/infrastructure/presentation)
- **Feature-Sliced Design**: Frontend follows existing patterns (features/, widgets/, entities/, pages/)
- **Testing**: Unit + integration + contract tests for backend; component tests for frontend
- **Backward Compatibility**: Grace period for DBF schema enforcement, nullable `batch_type` column
- **No Unnecessary Complexity**: Reusing existing CDC strategy, S3 patterns, scheduled task patterns, shadcn/ui components

No violations. Gate passes.

## Project Structure

### Documentation (this feature)

```text
specs/021-unified-upload-api/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 research findings
├── data-model.md        # Entity changes and migrations
├── quickstart.md        # Implementation guide
├── contracts/
│   ├── device-api.yaml  # Device-facing endpoint contracts
│   └── admin-api.yaml   # Admin-facing endpoint contracts
└── tasks.md             # (Phase 2 - created by /speckit.tasks)
```

### Source Code — Backend

```text
src/main/java/com/bitbi/dfm/
├── site/
│   ├── domain/
│   │   ├── Site.java                          # MODIFIED: heartbeat + directive fields
│   │   ├── SiteType.java                      # MODIFIED: add MSSQL_CDC, DBF_CDC, isCdc()
│   │   └── ForceFullUploadReason.java         # NEW: reason enum
│   ├── application/
│   │   ├── SiteService.java                   # MODIFIED: force rebaseline logic
│   │   ├── SiteSchemaService.java             # MODIFIED: type alias normalization
│   │   └── HeartbeatService.java              # NEW: heartbeat logic
│   └── presentation/
│       ├── HeartbeatController.java           # NEW: GET /api/v1/device/heartbeat
│       ├── SiteAdminController.java           # MODIFIED: force-rebaseline, request-logs endpoints
│       └── dto/
│           ├── HeartbeatResponseDto.java       # NEW
│           ├── ForceRebaselineRequestDto.java  # NEW
│           └── ForceRebaselineResponseDto.java # NEW
├── batch/
│   ├── domain/
│   │   ├── Batch.java                         # MODIFIED: batchType, schemaVersion, description
│   │   └── BatchType.java                     # NEW: enum BASELINE/DELTA
│   ├── application/
│   │   └── BatchLifecycleService.java         # MODIFIED: heartbeat check, schema enforcement, batchType
│   └── presentation/
│       ├── DeviceBatchController.java         # MODIFIED: accept BatchStartRequestDto
│       └── dto/
│           └── BatchStartRequestDto.java      # NEW
├── clientlog/                                  # NEW AGGREGATE
│   ├── domain/
│   │   ├── ClientDiagnosticLog.java           # NEW: entity
│   │   └── ClientDiagnosticLogRepository.java # NEW: repository interface
│   ├── application/
│   │   ├── ClientDiagnosticLogService.java    # NEW: upload, list, download
│   │   └── ClientLogRetentionScheduler.java   # NEW: 30-day cleanup
│   ├── infrastructure/
│   │   └── JpaClientDiagnosticLogRepository.java # NEW
│   └── presentation/
│       ├── ClientLogDeviceController.java     # NEW: POST /api/v1/device/logs
│       ├── ClientLogAdminController.java      # NEW: GET /admin/.../client-logs
│       └── dto/
│           ├── ClientLogResponseDto.java      # NEW
│           └── ClientLogListResponseDto.java  # NEW
├── plugin/
│   └── application/
│       ├── SqlGenerationService.java          # MODIFIED: route MSSQL_CDC, DBF_CDC
│       └── PluginHistoryService.java          # MODIFIED: set forceFullUpload on reinit
├── upload/
│   └── application/
│       └── FileUploadService.java             # MODIFIED: generalize CDC file validation
└── shared/
    ├── config/
    │   └── SecurityConfiguration.java         # MODIFIED: secure new endpoints
    └── exception/
        ├── GlobalExceptionHandler.java        # MODIFIED: new exception mappings
        ├── InvalidLogFileException.java       # NEW
        └── LogUploadLimitExceededException.java # NEW

src/main/resources/
├── db/migration/
│   └── V29__unified_upload_api.sql            # NEW: migration
└── application.yml                            # MODIFIED: new config properties

src/test/java/com/bitbi/dfm/
├── site/
│   ├── domain/SiteTypeTest.java               # NEW: isCdc() tests
│   ├── application/HeartbeatServiceTest.java  # NEW
│   └── presentation/HeartbeatControllerTest.java # NEW: contract tests
├── batch/
│   └── application/BatchLifecycleServiceTest.java # MODIFIED: new scenarios
├── clientlog/
│   ├── application/ClientDiagnosticLogServiceTest.java # NEW
│   ├── presentation/ClientLogDeviceControllerTest.java # NEW
│   └── presentation/ClientLogAdminControllerTest.java  # NEW
├── plugin/
│   └── application/SqlGenerationServiceTest.java # MODIFIED: new routing tests
└── upload/
    └── application/FileUploadServiceTest.java # MODIFIED: new site type scenarios
```

### Source Code — Frontend

```text
frontend/src/
├── entities/
│   └── site/
│       └── model/types.ts                     # MODIFIED: add MSSQL_CDC, DBF_CDC to SiteType
├── features/
│   ├── client-logs/                            # NEW FEATURE
│   │   ├── api/
│   │   │   ├── clientLogsApi.ts               # API calls (list, download)
│   │   │   └── clientLogsQueries.ts           # TanStack Query hooks
│   │   ├── model/
│   │   │   └── types.ts                       # ClientLog, ClientLogListResponse types
│   │   └── ui/
│   │       ├── ClientLogsTab.tsx              # Log list with pagination
│   │       └── ClientLogEntry.tsx             # Single log row
│   ├── site-crud/
│   │   ├── api/
│   │   │   └── siteApi.ts                     # MODIFIED: forceRebaseline, requestLogs mutations
│   │   └── ui/
│   │       ├── ForceRebaselineDialog.tsx      # NEW: confirmation dialog
│   │       └── RequestLogsDialog.tsx          # NEW: log request dialog
│   └── upload-history/
│       └── ui/
│           └── FileTable.tsx                  # MODIFIED: add batchType badge column
├── widgets/
│   └── site-list/
│       └── ui/
│           └── SiteListItem.tsx               # MODIFIED: new site type badges
├── pages/
│   └── site-management/
│       └── SiteDetailPage.tsx                 # MODIFIED: heartbeat status, directive indicators,
│                                              #   Force Rebaseline button, Request Logs button,
│                                              #   Client Logs tab
└── shared/
    └── api/
        └── apiRoutes.ts                       # MODIFIED: add new endpoint routes
```

**Structure Decision**: Backend follows existing DDD package-by-feature structure. New `clientlog` aggregate mirrors `error` and `upload` aggregates. Frontend follows existing Feature-Sliced Design. New `client-logs` feature module contains API hooks, types, and UI components. Site detail page and site list widget are extended with new functionality.

## Complexity Tracking

No constitution violations to justify. All decisions follow existing patterns:
- CDC strategy reuse (no new SQL generation code)
- S3 storage via existing `S3FileStorageService`
- Scheduled cleanup via existing pattern (`BatchRetentionScheduler`)
- Admin endpoints via existing `SiteAdminController` pattern
- Frontend: shadcn/ui Dialog/Badge/Table components, TanStack Query hooks, same pagination pattern
