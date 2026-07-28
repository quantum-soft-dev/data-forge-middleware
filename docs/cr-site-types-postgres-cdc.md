# Change Request: Site Types & Postgres CDC Support

> **Historical change request (superseded).** Its `/api/dfc/**` REST/JSONL ingestion
> surface was removed by [CR-032](./cr-remove-client-api-v1.md). `SiteType` and stored
> schema concepts remain, while supported ingestion now uses Device Authorization and
> [Delta v2 gRPC](./delta-client-v2-guide.md).

## Context

Currently all sites in Data Forge Middleware are homogeneous — they upload full CSV snapshots each batch, and the server generates SQL deltas by comparing consecutive batches (CsvDiffService + Myers diff algorithm). There is no concept of site type, no table schema awareness, and no primary key knowledge — SQL UPDATE/DELETE statements use all columns in WHERE clauses.

This CR introduces **site types** (starting with `POSTGRES_CDC`) and **table schema storage**, enabling CDC-based sites to send compact JSONL deltas instead of full CSV snapshots after the initial load. The server uses PK from schema for precise WHERE clauses in generated SQL.

---

## Scope

| In Scope | Out of Scope |
|----------|-------------|
| `POSTGRES_CDC` site type | `MSSQL_CDC` (future CR) |
| Table schema endpoint & storage | DDL generation (CREATE/ALTER TABLE) |
| JSONL delta format (.jsonl.gz) | Schema versioning history/audit trail |
| Direct JSONL → SQL conversion | Rate limiting on new endpoints |
| PK-based WHERE for CDC SQL | PK enhancement for existing DBF sites (future) |
| Backward-compatible DBF default | Frontend schema management UI |

---

## 1. Site Types

### New Enum: `SiteType`
```
DBF           — Default. Full CSV snapshots each batch. Server diffs between batches.
POSTGRES_CDC  — CDC mode. First batch = full CSV. Subsequent = JSONL deltas.
```

### Assignment Rules
- Client sends `siteType` parameter during **Device Flow** registration
- If omitted → defaults to `DBF` (backward compatibility for existing clients)
- Site type is **immutable** after creation (changing type would invalidate batch history)
- Displayed in admin UI and user site list as a badge

### Files to Modify
- `src/main/java/com/bitbi/dfm/site/domain/Site.java` — add `siteType` field
- `src/main/java/com/bitbi/dfm/deviceauth/presentation/dto/DeviceAuthorizationRequestDto.java` — add optional `siteType` param
- `src/main/java/com/bitbi/dfm/deviceauth/domain/DeviceAuthorization.java` — store `siteType`
- `src/main/java/com/bitbi/dfm/deviceauth/application/DeviceAuthorizationService.java` — propagate `siteType`
- `src/main/java/com/bitbi/dfm/site/application/SiteService.java` — accept `siteType` in create methods
- `src/main/java/com/bitbi/dfm/site/presentation/dto/SiteResponseDto.java` — include `siteType` in response

### New File
- `src/main/java/com/bitbi/dfm/site/domain/SiteType.java` — enum

---

## 2. Table Schema Storage

### Concept
- Schema describes table structures per site: columns, types, PK, unique keys, nullable
- Stored server-side, persists between batches until client sends an update
- Client sends schema only when structure changes (e.g., column added/removed in PostgreSQL)

### Schema JSON Format
```json
{
  "tables": {
    "customers": {
      "columns": [
        {"name": "id", "type": "integer", "nullable": false},
        {"name": "name", "type": "varchar(255)", "nullable": false},
        {"name": "email", "type": "varchar(255)", "nullable": true}
      ],
      "primaryKey": ["id"],
      "uniqueKeys": [
        {"name": "uk_customers_email", "columns": ["email"]}
      ]
    },
    "orders": {
      "columns": [
        {"name": "order_id", "type": "integer", "nullable": false},
        {"name": "product_id", "type": "integer", "nullable": false},
        {"name": "quantity", "type": "integer", "nullable": false}
      ],
      "primaryKey": ["order_id", "product_id"],
      "uniqueKeys": []
    }
  }
}
```

### API Endpoint
```
POST /api/dfc/schema
Authentication: Custom JWT (same as batch upload)
Content-Type: application/json
```

**Validation rules:**
- Each table must have at least one column
- PK columns must exist in column list
- Unique key columns must exist in column list
- Column names validated against PostgreSQL identifier pattern (`^[a-zA-Z_][a-zA-Z0-9_]{0,62}$`)
- Table names validated against same pattern

**Timing rules:**
- `POSTGRES_CDC` sites: schema **required** before first batch (server rejects `startBatch` without schema → 400)
- `DBF` sites: schema **optional** (backward compatible, works as before without it)

### Storage: `site_schemas` Table
```sql
CREATE TABLE site_schemas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id UUID NOT NULL REFERENCES sites(id) ON DELETE CASCADE,
    schema_data JSONB NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_site_schemas_site_id UNIQUE (site_id)
);
```
- One row per site (UNIQUE on site_id)
- `schema_version` increments on each update (optimistic concurrency)
- CASCADE delete when site is deleted

### New Files
- `src/main/java/com/bitbi/dfm/site/domain/SiteSchema.java` — JPA entity
- `src/main/java/com/bitbi/dfm/site/domain/TableSchema.java` — value object (parsed schema per table)
- `src/main/java/com/bitbi/dfm/site/domain/SiteSchemaRepository.java` — domain interface
- `src/main/java/com/bitbi/dfm/site/infrastructure/JpaSiteSchemaRepository.java` — JPA impl
- `src/main/java/com/bitbi/dfm/site/application/SiteSchemaService.java` — upsert, get, validate
- `src/main/java/com/bitbi/dfm/upload/presentation/SchemaUploadController.java` — POST /api/dfc/schema
- `src/main/java/com/bitbi/dfm/upload/presentation/dto/SchemaUploadRequestDto.java` — request DTO
- `src/main/java/com/bitbi/dfm/upload/presentation/dto/SchemaResponseDto.java` — response DTO

### Files to Modify
- `src/main/java/com/bitbi/dfm/site/application/SiteService.java` — delete schema on site hard-delete
- `src/main/java/com/bitbi/dfm/batch/application/BatchLifecycleService.java` — reject batch start for CDC sites without schema
- Security config — permit `/api/dfc/schema` for device JWT

---

## 3. JSONL Delta Format

### File Convention
- One `.jsonl.gz` file per table (e.g., `customers.jsonl.gz`)
- Table name derived from filename (same as CSV: `customers.jsonl` → table `customers`)

### Operations
```jsonl
{"op":"I","d":{"id":1,"name":"John","email":"j@ex.com","age":30}}
{"op":"U","k":{"id":2},"d":{"name":"Jane Updated"}}
{"op":"U","k":{"id":5},"d":{"email":"new@ex.com","age":31}}
{"op":"D","k":{"id":3}}
```

| Field | Description | Present in |
|-------|-------------|------------|
| `op` | Operation: `I` (insert), `U` (update), `D` (delete) | All |
| `k` | Key fields (PK values) for row identification | U, D |
| `d` | Data fields (full row for INSERT, changed fields only for UPDATE) | I, U |

### Type Handling
- JSON `null` → SQL `NULL`
- JSON number → SQL number (no quotes)
- JSON string → SQL string (escaped, quoted)
- JSON boolean → SQL boolean
- Missing field in `d` (UPDATE) → field not changed, not included in SET

### New Files
- `src/main/java/com/bitbi/dfm/plugin/domain/JsonlChangeRecord.java` — record(op, key, data)
- `src/main/java/com/bitbi/dfm/plugin/application/JsonlParserService.java` — parse .jsonl.gz from S3

---

## 4. CDC Batch Flow

### Full Lifecycle
```
1. Device Flow registration (siteType=POSTGRES_CDC)
        ↓
2. POST /api/dfc/schema (table structures with PK)
        ↓
3. First batch: CSV files (full table snapshot) → baseline, no SQL generated
        ↓
4. Subsequent batches: JSONL files (.jsonl.gz) → direct JSONL → SQL conversion
        ↓
5. Schema change detected → POST /api/dfc/schema (updated)
        ↓
6. Next batch: JSONL with new structure → SQL uses updated schema
```

### File Type Validation per Site Type
| Site Type | Baseline batch | Subsequent batches |
|-----------|---------------|-------------------|
| `DBF` | CSV/CSV.GZ | CSV/CSV.GZ |
| `POSTGRES_CDC` | CSV/CSV.GZ | JSONL/JSONL.GZ |

### Determining Baseline vs Subsequent
- Use existing AccountPlugin `baseline_batch_id` logic
- Or check if any previous completed batch exists for the site

### Files to Modify
- `src/main/java/com/bitbi/dfm/upload/application/FileUploadService.java` — file type validation based on site type + batch position

---

## 5. SQL Generation Changes

### Strategy Pattern

Current: `SqlGenerationService` always uses `CsvDiffService` → `SqlStatementGenerator`

New: `SqlGenerationService` dispatches to appropriate strategy based on site type:

```
SqlGenerationService
    ├── DbfSqlGenerationStrategy  (site_type=DBF)
    │   └── CsvDiffService → SqlStatementGenerator (existing flow)
    └── CdcSqlGenerationStrategy  (site_type=POSTGRES_CDC)
        └── JsonlParserService → SqlStatementGenerator (new methods)
```

### CDC SQL Generation (JSONL → SQL)
For each JSONL line:
- `I` → `INSERT INTO {table} ({columns}) VALUES ({values})`
- `U` → `UPDATE {table} SET {changed_cols} WHERE {pk_cols}` (PK from schema)
- `D` → `DELETE FROM {table} WHERE {pk_cols}` (PK from schema)

Type-aware value formatting using column types from schema (integer → no quotes, varchar → quoted, etc.)

### New Files
- `src/main/java/com/bitbi/dfm/plugin/application/SqlGenerationStrategy.java` — interface
- `src/main/java/com/bitbi/dfm/plugin/application/DbfSqlGenerationStrategy.java` — extracted from SqlGenerationService
- `src/main/java/com/bitbi/dfm/plugin/application/CdcSqlGenerationStrategy.java` — JSONL → SQL

### Files to Modify
- `src/main/java/com/bitbi/dfm/plugin/application/SqlGenerationService.java` — refactor to use strategy pattern, load schema
- `src/main/java/com/bitbi/dfm/plugin/application/SqlStatementGenerator.java` — add methods for JSONL-based generation with PK WHERE

### No Changes Needed
- `BitBiPlugin.java` — calls SqlGenerationService which handles strategy internally
- `BatchEventListener` — unchanged event flow
- `BitBiPluginApiController` — baseline file download works for both types

---

## 6. Database Migration

### V28__add_site_types_and_schemas.sql

```sql
-- 1. Add site_type to sites (DBF default for backward compatibility)
ALTER TABLE sites ADD COLUMN site_type VARCHAR(20) NOT NULL DEFAULT 'DBF';
CREATE INDEX idx_sites_site_type ON sites(site_type);

-- 2. Add site_type to device_authorizations
ALTER TABLE device_authorizations ADD COLUMN site_type VARCHAR(20) NOT NULL DEFAULT 'DBF';

-- 3. Create site_schemas table
CREATE TABLE site_schemas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id UUID NOT NULL REFERENCES sites(id) ON DELETE CASCADE,
    schema_data JSONB NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_site_schemas_site_id UNIQUE (site_id)
);

CREATE INDEX idx_site_schemas_site_id ON site_schemas(site_id);
```

**No data migration needed** — all existing sites get `DBF` via DEFAULT.

---

## 7. Frontend Changes

### TypeScript Types
```typescript
// Add to Site type
export type SiteType = 'DBF' | 'POSTGRES_CDC';

export interface Site {
  // ...existing fields
  siteType: SiteType;
}
```

### UI Changes
- Site list: show type badge (DBF = gray, POSTGRES_CDC = blue)
- Admin site details: display site type
- Optional: filter sites by type

### Files to Modify
- `frontend/src/entities/site/model/types.ts` — add SiteType
- `frontend/src/widgets/site-list/` — display type badge
- Admin site views — display siteType field

---

## 8. Error Handling

| Scenario | HTTP | Message |
|----------|------|---------|
| CDC batch start without schema | 400 | "Schema required for POSTGRES_CDC sites. Submit via POST /api/dfc/schema first." |
| Invalid schema JSON structure | 400 | "Invalid schema: {details}" |
| PK column not in column list | 400 | "Primary key column '{name}' not found in table '{table}' columns" |
| JSONL uploaded to DBF site | 400 | "JSONL files not supported for DBF sites" |
| CSV uploaded to CDC non-baseline batch | 400 | "JSONL files expected for CDC delta batches" |
| Malformed JSONL line | Skip + warning | Log line number and error, continue processing |
| Missing `k` field in UPDATE/DELETE | Error | Fail file processing, log error |
| Unknown column in JSONL (not in schema) | Skip + warning | Log warning, exclude column from SQL |

---

## 9. Implementation Phases

### Phase 1: Database + Domain Foundation
1. Flyway V28 migration
2. `SiteType` enum
3. `Site` entity — add `siteType` field
4. `SiteSchema` entity + `TableSchema` value object
5. `SiteSchemaRepository` interface + JPA implementation

### Phase 2: Schema API
6. `SiteSchemaService` (upsert, get, validate)
7. `SchemaUploadController` (POST /api/dfc/schema)
8. Request/Response DTOs
9. Security config update

### Phase 3: Device Flow + Site Type Propagation
10. `DeviceAuthorizationRequestDto` — add optional `siteType`
11. `DeviceAuthorization` entity — add `siteType`
12. `DeviceAuthorizationService` — propagate type
13. `SiteService` — create with type
14. `SiteResponseDto` — include type

### Phase 4: Batch & Upload Validation
15. `BatchLifecycleService` — schema check for CDC
16. `FileUploadService` — file type validation per site type

### Phase 5: SQL Generation Strategy
17. `SqlGenerationStrategy` interface
18. `DbfSqlGenerationStrategy` (extract from SqlGenerationService)
19. `JsonlChangeRecord` + `JsonlParserService`
20. `CdcSqlGenerationStrategy` (JSONL → SQL)
21. `SqlStatementGenerator` — PK WHERE + typed value methods
22. `SqlGenerationService` refactoring (strategy dispatch)

### Phase 6: Frontend
23. TypeScript types update
24. Site type badge display

---

## 10. Verification Plan

### Unit Tests
- `SiteType` enum values, default behavior
- `SiteSchema` entity — create, update, version increment
- `TableSchema` value object — parsing, PK extraction
- `JsonlParserService` — all op types, malformed lines, gzip, unknown columns
- `CdcSqlGenerationStrategy` — INSERT/UPDATE/DELETE from JSONL with PK WHERE
- `SqlStatementGenerator` — new PK-based methods, typed value formatting
- `SiteSchemaService` — upsert, validation, CDC pre-batch check

### Integration Tests (Testcontainers)
- Schema JSONB persistence + unique constraint + cascade delete
- Full Device Flow with siteType parameter
- CDC batch rejected without schema (400)
- End-to-end: JSONL upload → batch complete → SQL generation
- DBF backward compatibility (no schema, works as before)

### Contract Tests (MockMvc)
- POST /api/dfc/schema — validation, auth, response format
- Device auth with/without siteType parameter
- SiteResponseDto includes siteType field

### Manual E2E
1. Create POSTGRES_CDC site via Device Flow
2. Send schema via POST /api/dfc/schema
3. Upload first batch (CSV) — baseline, no SQL
4. Upload second batch (JSONL deltas) — verify SQL generated with PK WHERE
5. Update schema — verify next batch uses new structure
6. Verify existing DBF sites unchanged
