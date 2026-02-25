# Research: Unified Data Upload API (Server-Side)

**Feature**: 021-unified-upload-api
**Date**: 2026-02-25

## R1: SiteType Enum Extension

**Decision**: Add `MSSQL_CDC` and `DBF_CDC` to existing `SiteType` enum.

**Rationale**: The enum already has `DBF` and `POSTGRES_CDC` (added in feature 019, migration V28). The enum is stored as `VARCHAR(20)` in both `sites` and `device_authorizations` tables. New values fit within the column width. `@Enumerated(EnumType.STRING)` serialization means just adding enum constants is sufficient — no migration needed for the column itself.

**Alternatives considered**:
- Separate `uploadMode` + `sourceType` fields: Rejected per design doc §4.4 — single enum is simpler for validation, routing, and backward compatibility.

**Current state**:
- File: `src/main/java/com/bitbi/dfm/site/domain/SiteType.java`
- Values: `DBF`, `POSTGRES_CDC`
- Column: `site_type VARCHAR(20) NOT NULL DEFAULT 'DBF'` in `sites` and `device_authorizations`

---

## R2: CDC SQL Generation Strategy Reuse

**Decision**: The existing `CdcSqlGenerationStrategy` is already source-agnostic. It uses JSONL format and schema-based type mapping. No changes needed to the strategy itself — only the routing logic in `SqlGenerationService` must be updated to dispatch `MSSQL_CDC` and `DBF_CDC` to `CdcSqlGenerationStrategy`.

**Rationale**: The CDC strategy processes JSONL records using the schema's column types for SQL literal formatting. Since all CDC types use the same JSONL format and all SQL output uses PostgreSQL dialect, the strategy is inherently database-agnostic.

**Current state**:
- File: `src/main/java/com/bitbi/dfm/plugin/application/CdcSqlGenerationStrategy.java`
- Dispatch: `SqlGenerationService` selects strategy based on `site.getSiteType()`
- Currently only routes `POSTGRES_CDC` to CDC strategy

---

## R3: Schema Enforcement for DBF Sites

**Decision**: Add grace period mechanism for DBF schema enforcement. During grace period, log warning but allow batch. After grace period, reject with `SchemaRequiredException`.

**Rationale**: Existing DBF clients don't submit schemas. Immediate enforcement would break them. Grace period gives time to update.

**Current state**:
- `BatchLifecycleService.startBatch()` only checks schema for `POSTGRES_CDC` sites
- Need to extend check to all site types with grace period logic for `DBF`

**Implementation approach**:
- New configurable property: `schema.enforcement.dbf-grace-period-end` (ISO date, default: far future)
- Check: if `siteType == DBF && !hasSchema && now < gracePeriodEnd` → warn; else if `!hasSchema` → reject

---

## R4: MSSQL Type Aliases in Schema

**Decision**: Add type alias mapping in `SiteSchemaService` to normalize MSSQL-specific types to unified types.

**Rationale**: MSSQL clients will naturally submit types like `nvarchar`, `datetime2`, `uniqueidentifier`. These must map to the unified type system for correct SQL generation.

**Mapping** (from design doc §6.2):
| MSSQL Alias | Unified Type |
|-------------|-------------|
| `nvarchar`, `nvarchar(max)` | `VARCHAR` |
| `int` | `INTEGER` |
| `datetime2`, `datetime` | `TIMESTAMP` |
| `uniqueidentifier` | `UUID` |
| `money`, `smallmoney` | `MONEY` |
| `bit` | `BOOLEAN` |
| `ntext`, `text` | `TEXT` |
| `float` | `FLOAT` |

**Current state**:
- `SiteSchemaService` stores schema as raw JSONB — type strings are preserved as-is
- `CdcSqlGenerationStrategy` uses column types for SQL literal formatting
- Type normalization should happen at schema submission time (not at SQL generation)

---

## R5: Heartbeat Endpoint Design

**Decision**: New `GET /api/v1/device/heartbeat` endpoint with lightweight query to `sites` table + optional batch join.

**Rationale**: Must be fast (<200ms). Single query with optional join is sufficient. Last heartbeat timestamp stored in `sites` table.

**Implementation approach**:
- New columns on `sites`: `last_heartbeat_at TIMESTAMP`, `force_full_upload BOOLEAN DEFAULT false`, `force_full_upload_reason VARCHAR(50)`, `force_full_upload_message TEXT`, `force_full_upload_set_at TIMESTAMP`, `force_full_upload_set_by VARCHAR(255)`, `request_logs BOOLEAN DEFAULT false`, `request_logs_message TEXT`
- New controller: `HeartbeatController` under `/api/v1/device/heartbeat`
- New service: `HeartbeatService` — reads site + last completed batch
- Batch start validation: check `last_heartbeat_at > now() - heartbeatWindow`

**Config**: `heartbeat.required-interval-minutes` (default: 5)

---

## R6: Batch Type Field

**Decision**: Add `batch_type VARCHAR(20)` to `batches` table and require it in batch start request.

**Rationale**: Currently batches have no type — the system infers behavior from site type and batch ordering. Explicit `batchType` (BASELINE/DELTA) simplifies logic and enables mixed-mode CDC.

**Current state**:
- `Batch` entity has no `batchType` field
- `BatchController.startBatch()` takes no request body — site/account from JWT
- Need: new request DTO, new column, migration

**Implementation approach**:
- New enum: `BatchType { BASELINE, DELTA }`
- New column: `batch_type VARCHAR(20)` in `batches` table (nullable for backward compat with existing rows)
- New request DTO: `BatchStartRequestDto { batchType, expectedFileCount, description }`
- Validation: `batchType` required for all new batches

---

## R7: Schema Version Pinning

**Decision**: Store `schema_version INTEGER` on batch record at start time.

**Rationale**: Currently schema is not pinned to batches. If schema changes mid-batch, SQL generation could use inconsistent types. Pinning at batch start prevents this.

**Current state**:
- `SiteSchema` has `schemaVersion` (integer, incremented on update)
- `Batch` has no `schemaVersion` field
- Need: new column on `batches`, set at batch start

---

## R8: Client Diagnostic Logs Storage

**Decision**: New `client_diagnostic_logs` table + S3 storage at `client-logs/{accountId}/{siteId}/{date}/{logId}/{filename}`.

**Rationale**: Follows existing S3 path pattern. Separate S3 prefix for client logs keeps them organized. 30-day retention with scheduled cleanup mirrors batch retention pattern.

**Current state**:
- No client log infrastructure exists
- S3 upload pattern: `S3FileStorageService.uploadFile()` with retry
- Presigned URL pattern: `S3PresignedUrlService.generatePresignedUrl()` with 15-min expiry
- Retention cleanup pattern: `BatchRetentionScheduler` with configurable cron

---

## R9: Force Rebaseline Admin Endpoint

**Decision**: New `POST /api/v1/admin/sites/{siteId}/force-rebaseline` endpoint under admin API with `ROLE_ADMIN` security.

**Rationale**: Follows existing admin controller pattern (`SiteAdminController`). Uses Auth0 OAuth2 authentication.

**Current state**:
- `SiteAdminController` at `src/main/java/com/bitbi/dfm/site/presentation/SiteAdminController.java`
- Admin endpoints use `@PreAuthorize("hasRole('ADMIN')")` class-level annotation
- Route secured by SecurityConfiguration Order 4 filter chain (`/api/v1/**` → Auth0 OAuth2)

---

## R10: Plugin Reinit Integration

**Decision**: When plugin reinit occurs, automatically set `forceFullUpload = true` with reason `PLUGIN_REINIT` on all sites for that account.

**Rationale**: After reinit, the plugin needs a new baseline. All CDC sites should re-upload full data.

**Current state**:
- `PluginHistoryService.reinit()` already clears SQL history and sets new baseline batch
- Need to add: set `forceFullUpload` on all active CDC sites for the account
- DBF sites excluded (every batch is already a full snapshot)

---

## R11: File Upload Validation Extension

**Decision**: Extend `FileUploadService` to support `MSSQL_CDC` and `DBF_CDC` with same rules as `POSTGRES_CDC`.

**Rationale**: All CDC types follow identical file validation logic (CSV for baseline, JSONL for deltas).

**Current state**:
- `FileUploadService` has explicit `if (siteType == SiteType.POSTGRES_CDC)` checks
- Need to generalize to `if (siteType.isCdc())` or check upload mode

**Implementation approach**: Add helper method `SiteType.isCdc()` that returns `true` for `POSTGRES_CDC`, `MSSQL_CDC`, `DBF_CDC`.
