# Quickstart: Unified Data Upload API

**Feature**: 021-unified-upload-api

## Overview

This feature extends the Data Forge Middleware to support a unified data upload protocol across multiple database sources (DBF, PostgreSQL, MS SQL Server). It adds new site types, a heartbeat endpoint for server-to-client directives, client diagnostic log uploads, admin force-rebaseline capabilities, and admin UI for site management, client logs viewing, and directive management.

## Key Components

### 1. New Site Types

Add `MSSQL_CDC` and `DBF_CDC` to the `SiteType` enum. All CDC types share the same JSONL processing and SQL generation pipeline.

**Files to modify**:
- `src/main/java/com/bitbi/dfm/site/domain/SiteType.java` — add enum values + `isCdc()` helper
- `src/main/java/com/bitbi/dfm/batch/application/BatchLifecycleService.java` — generalize CDC check
- `src/main/java/com/bitbi/dfm/upload/application/FileUploadService.java` — generalize file validation
- `src/main/java/com/bitbi/dfm/plugin/application/SqlGenerationService.java` — route new types to CDC strategy

### 2. Heartbeat Endpoint

New `GET /api/v1/device/heartbeat` endpoint.

**New files**:
- `src/main/java/com/bitbi/dfm/site/presentation/HeartbeatController.java`
- `src/main/java/com/bitbi/dfm/site/application/HeartbeatService.java`
- `src/main/java/com/bitbi/dfm/site/presentation/dto/HeartbeatResponseDto.java`

**Modified files**:
- `src/main/java/com/bitbi/dfm/site/domain/Site.java` — add heartbeat/directive fields
- `src/main/java/com/bitbi/dfm/batch/application/BatchLifecycleService.java` — add heartbeat check on batch start

### 3. Batch Type & Schema Pinning

Add `batchType` to batch start request and `schemaVersion` pinning.

**New files**:
- `src/main/java/com/bitbi/dfm/batch/domain/BatchType.java` — enum
- `src/main/java/com/bitbi/dfm/batch/presentation/dto/BatchStartRequestDto.java`

**Modified files**:
- `src/main/java/com/bitbi/dfm/batch/domain/Batch.java` — add fields
- `src/main/java/com/bitbi/dfm/batch/presentation/BatchController.java` — accept request body (V1 endpoint)
- `src/main/java/com/bitbi/dfm/batch/presentation/DeviceBatchController.java` — accept request body

### 4. Client Diagnostic Logs

New log upload, listing, and download endpoints.

**New files**:
- `src/main/java/com/bitbi/dfm/clientlog/domain/ClientDiagnosticLog.java`
- `src/main/java/com/bitbi/dfm/clientlog/infrastructure/JpaClientDiagnosticLogRepository.java`
- `src/main/java/com/bitbi/dfm/clientlog/application/ClientDiagnosticLogService.java`
- `src/main/java/com/bitbi/dfm/clientlog/presentation/ClientLogDeviceController.java`
- `src/main/java/com/bitbi/dfm/clientlog/presentation/ClientLogAdminController.java`
- `src/main/java/com/bitbi/dfm/clientlog/presentation/dto/ClientLogUploadRequestDto.java`
- `src/main/java/com/bitbi/dfm/clientlog/presentation/dto/ClientLogResponseDto.java`
- `src/main/java/com/bitbi/dfm/clientlog/application/ClientLogRetentionScheduler.java`

### 5. Force Rebaseline (Admin)

New admin endpoint and integration with plugin reinit.

**New files**:
- `src/main/java/com/bitbi/dfm/site/presentation/dto/ForceRebaselineRequestDto.java`
- `src/main/java/com/bitbi/dfm/site/presentation/dto/ForceRebaselineResponseDto.java`

**Modified files**:
- `src/main/java/com/bitbi/dfm/site/presentation/SiteAdminController.java` — add endpoint
- `src/main/java/com/bitbi/dfm/site/application/SiteService.java` — add force rebaseline logic
- `src/main/java/com/bitbi/dfm/plugin/application/PluginHistoryService.java` — set forceFullUpload on reinit

### 6. Schema Enforcement & Type Aliases

Mandatory schema for all site types + MSSQL type alias mapping.

**Modified files**:
- `src/main/java/com/bitbi/dfm/site/application/SiteSchemaService.java` — add type alias normalization
- `src/main/java/com/bitbi/dfm/batch/application/BatchLifecycleService.java` — enforce schema for all types (with grace period for DBF)

### 7. Database Migration

**New file**: `src/main/resources/db/migration/V29__unified_upload_api.sql`
- Add heartbeat/directive columns to `sites`
- Add `batch_type`, `schema_version`, `expected_file_count`, `description` to `batches`
- Create `client_diagnostic_logs` table

## Configuration

New application properties:

```yaml
# Heartbeat
heartbeat:
  required-interval-minutes: ${HEARTBEAT_REQUIRED_INTERVAL_MINUTES:5}

# Schema enforcement
schema:
  enforcement:
    dbf-grace-period-end: ${SCHEMA_DBF_GRACE_PERIOD_END:2026-12-31}

# Client logs
client-logs:
  max-file-size-mb: ${CLIENT_LOGS_MAX_FILE_SIZE_MB:10}
  max-uploads-per-day: ${CLIENT_LOGS_MAX_UPLOADS_PER_DAY:10}
  retention-days: ${CLIENT_LOGS_RETENTION_DAYS:30}
  retention-cron: ${CLIENT_LOGS_RETENTION_CRON:0 0 3 * * *}
```

### 8. Frontend — Site Type Badges

Update site list item to render correct badges for new site types.

**Modified files**:
- `frontend/src/entities/site/model/types.ts` — add `MSSQL_CDC`, `DBF_CDC` to SiteType union
- `frontend/src/widgets/site-list/ui/SiteListItem.tsx` — add badge variants for new types

**Pattern reference**: Existing `SiteListItem.tsx` already renders DBF and POSTGRES_CDC badges with different styles. Add `MSSQL_CDC` (e.g., blue) and `DBF_CDC` (e.g., purple) variants.

### 9. Frontend — Force Rebaseline Dialog

New dialog component for confirming force-rebaseline with reason input.

**New files**:
- `frontend/src/features/site-crud/ui/ForceRebaselineDialog.tsx`

**Modified files**:
- `frontend/src/features/site-crud/api/siteApi.ts` — add `forceRebaseline` mutation
- `frontend/src/pages/site-management/SiteDetailPage.tsx` — add "Force Rebaseline" button (CDC sites only)

**Pattern reference**: Follows existing `AlertDialog` pattern from site deactivate/delete confirmations in `SiteListItem.tsx`. Uses `AlertDialog` + `Input` for reason field.

### 10. Frontend — Request Logs Dialog

New dialog for requesting client logs via heartbeat directive.

**New files**:
- `frontend/src/features/site-crud/ui/RequestLogsDialog.tsx`

**Modified files**:
- `frontend/src/features/site-crud/api/siteApi.ts` — add `requestLogs` mutation
- `frontend/src/pages/site-management/SiteDetailPage.tsx` — add "Request Logs" button

### 11. Frontend — Client Logs Tab

New feature module for viewing and downloading client diagnostic logs.

**New files**:
- `frontend/src/features/client-logs/api/clientLogsApi.ts` — API calls (list, download)
- `frontend/src/features/client-logs/api/clientLogsQueries.ts` — TanStack Query hooks
- `frontend/src/features/client-logs/model/types.ts` — TypeScript interfaces
- `frontend/src/features/client-logs/ui/ClientLogsTab.tsx` — main tab component with pagination
- `frontend/src/features/client-logs/ui/ClientLogEntry.tsx` — single log row

**Modified files**:
- `frontend/src/pages/site-management/SiteDetailPage.tsx` — add "Client Logs" tab
- `frontend/src/shared/api/apiRoutes.ts` — add client log endpoint routes

**Pattern reference**: Follows `PluginLogsTab.tsx` pattern — card-based entries with metadata, pagination, and download action. Uses `Badge` for tags, `Button` for download, standard pagination controls.

### 12. Frontend — Heartbeat & Directive Status

Display heartbeat and directive information on the site detail page.

**Modified files**:
- `frontend/src/pages/site-management/SiteDetailPage.tsx` — add heartbeat/directive info section
- `frontend/src/entities/site/model/types.ts` — extend Site type with heartbeat fields

**Display pattern**: Show "Last heartbeat: X minutes ago" (or "Never") and alert badges for active directives.

### 13. Frontend — Batch Type Badge

Show batch type in batch lists (upload history, SQL tab).

**Modified files**:
- `frontend/src/features/upload-history/ui/FileTable.tsx` — add batchType badge column
- `frontend/src/features/my-plugins/ui/BatchSqlTab.tsx` — add batchType in batch info

**Pattern reference**: Simple `Badge` component with "Baseline" (gray) or "Delta" (blue) variant. Null-safe — no badge for legacy batches.

## Testing Strategy

| Layer | Scope | Tool |
|-------|-------|------|
| Unit | HeartbeatService, ClientDiagnosticLogService, SiteType.isCdc() | JUnit 5 + Mockito |
| Unit | Type alias mapping, grace period logic | JUnit 5 |
| Integration | Heartbeat → batch start flow | Testcontainers (PostgreSQL) |
| Integration | Log upload → S3 storage → admin download | Testcontainers (PostgreSQL + LocalStack) |
| Contract | All new endpoints (heartbeat, logs, force-rebaseline) | MockMvc |
| Component | ForceRebaselineDialog, RequestLogsDialog, ClientLogsTab | Vitest + React Testing Library |
| Component | SiteListItem (new badges), batch type badges | Vitest + React Testing Library |

## Dependency Order

```
Backend:
1. Migration V29 (DB schema)
2. SiteType enum extension + isCdc() helper
3. Site entity new fields (heartbeat, directives)
4. BatchType enum + Batch entity fields
5. HeartbeatService + HeartbeatController
6. Batch start modifications (batchType, heartbeat check, schema pin)
7. Schema enforcement (grace period for DBF, type aliases)
8. SQL generation routing (MSSQL_CDC, DBF_CDC → CdcStrategy)
9. File upload validation extension
10. ClientDiagnosticLog entity + service + controllers
11. Force rebaseline admin endpoint + request-logs endpoint
12. Plugin reinit integration
13. Log retention scheduler

Frontend (can start after backend endpoints #5, #10, #11 are ready):
14. Site entity types update (MSSQL_CDC, DBF_CDC, heartbeat fields)
15. API routes + API client functions (force-rebaseline, request-logs, client-logs)
16. Site type badges (SiteListItem)
17. Force Rebaseline dialog + site detail button
18. Request Logs dialog + site detail button
19. Heartbeat & directive status on site detail
20. Client Logs tab (feature module + tab integration)
21. Batch type badge in upload history & SQL tab
```
