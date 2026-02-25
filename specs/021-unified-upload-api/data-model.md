# Data Model: Unified Data Upload API (Server-Side)

**Feature**: 021-unified-upload-api
**Date**: 2026-02-25

## Entity Changes

### 1. SiteType Enum (Modified)

**File**: `src/main/java/com/bitbi/dfm/site/domain/SiteType.java`

| Value | Upload Mode | Description | Status |
|-------|------------|-------------|--------|
| `DBF` | SNAPSHOT | Full CSV snapshots, server diffs | Existing |
| `POSTGRES_CDC` | CDC | CSV baseline + JSONL deltas | Existing |
| `MSSQL_CDC` | CDC | CSV baseline + JSONL deltas (MS SQL source) | **New** |
| `DBF_CDC` | CDC | CSV baseline + JSONL deltas (DBF source) | **New** |

**New helper method**: `isCdc()` → returns `true` for `POSTGRES_CDC`, `MSSQL_CDC`, `DBF_CDC`

---

### 2. Site Entity (Modified)

**File**: `src/main/java/com/bitbi/dfm/site/domain/Site.java`

New fields added to existing entity:

| Field | Type | Nullable | Default | Description |
|-------|------|----------|---------|-------------|
| `lastHeartbeatAt` | LocalDateTime | Yes | null | Last heartbeat call timestamp |
| `forceFullUpload` | Boolean | No | false | Heartbeat directive: force CSV rebaseline |
| `forceFullUploadReason` | String | Yes | null | Reason code: `ADMIN_REQUEST`, `PLUGIN_REINIT`, `SCHEMA_INCOMPATIBLE`, `DATA_CORRUPTION` |
| `forceFullUploadMessage` | String | Yes | null | Human-readable message |
| `forceFullUploadSetAt` | LocalDateTime | Yes | null | When the flag was set |
| `forceFullUploadSetBy` | String | Yes | null | Who set the flag (email) |
| `requestLogs` | Boolean | No | false | Heartbeat directive: request client logs |
| `requestLogsMessage` | String | Yes | null | Message for log request |

**Business methods**:
- `setForceFullUpload(String reason, String message, String setBy)` — sets flag + metadata
- `clearForceFullUpload()` — clears flag + metadata
- `recordHeartbeat()` — updates `lastHeartbeatAt` to current time
- `setRequestLogs(String message)` — sets log request flag
- `clearRequestLogs()` — clears log request flag

**Validation rules**:
- `forceFullUpload` always returns `false` for `DBF` sites (enforced in getter or service layer)
- `forceFullUploadReason` must be one of the defined reason codes

---

### 3. BatchType Enum (New)

**File**: `src/main/java/com/bitbi/dfm/batch/domain/BatchType.java`

| Value | Description |
|-------|-------------|
| `BASELINE` | Full CSV upload, no SQL generation |
| `DELTA` | Change data (JSONL for CDC, CSV for DBF snapshot) |

---

### 4. Batch Entity (Modified)

**File**: `src/main/java/com/bitbi/dfm/batch/domain/Batch.java`

New fields:

| Field | Type | Nullable | Default | Description |
|-------|------|----------|---------|-------------|
| `batchType` | BatchType | Yes | null | BASELINE or DELTA (nullable for backward compat with old rows) |
| `schemaVersion` | Integer | Yes | null | Schema version pinned at batch start |
| `expectedFileCount` | Integer | Yes | null | Client-declared expected file count |
| `description` | String | Yes | null | Human-readable batch description |

**Validation**: `batchType` required for new batches (enforced in service, not DB constraint, for backward compat).

---

### 5. ClientDiagnosticLog Entity (New)

**File**: `src/main/java/com/bitbi/dfm/clientlog/domain/ClientDiagnosticLog.java`

| Field | Type | Nullable | Default | Description |
|-------|------|----------|---------|-------------|
| `id` | UUID | No | gen_random_uuid() | Primary key |
| `siteId` | UUID | No | — | FK → sites |
| `accountId` | UUID | No | — | FK → accounts |
| `s3Key` | String | No | — | S3 storage path |
| `filename` | String | No | — | Original filename |
| `fileSize` | Long | No | — | File size in bytes |
| `contentType` | String | Yes | — | MIME type |
| `clientVersion` | String | Yes | — | Client application version |
| `os` | String | Yes | — | Client OS |
| `periodFrom` | LocalDateTime | Yes | — | Log period start |
| `periodTo` | LocalDateTime | Yes | — | Log period end |
| `tags` | List&lt;String&gt; | Yes | — | Tags for filtering (JSONB) |
| `description` | String | Yes | — | Problem description |
| `uploadedAt` | LocalDateTime | No | now() | Upload timestamp |
| `expiresAt` | LocalDateTime | No | now() + 30 days | Auto-delete date |

**S3 path**: `client-logs/{accountId}/{siteId}/{date}/{logId}/{filename}`

**Relationships**:
- Many-to-one with Site (via siteId)
- Many-to-one with Account (via accountId)

---

### 6. ForceFullUploadReason Enum (New)

**File**: `src/main/java/com/bitbi/dfm/site/domain/ForceFullUploadReason.java`

| Value | Description |
|-------|-------------|
| `ADMIN_REQUEST` | Admin manually requested rebaseline |
| `PLUGIN_REINIT` | Plugin reinitialization |
| `SCHEMA_INCOMPATIBLE` | Breaking schema change |
| `DATA_CORRUPTION` | Detected data corruption |

---

## Database Migrations

### Migration: V29__unified_upload_api.sql

```sql
-- 1. Add heartbeat and directive columns to sites
ALTER TABLE sites ADD COLUMN last_heartbeat_at TIMESTAMP;
ALTER TABLE sites ADD COLUMN force_full_upload BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE sites ADD COLUMN force_full_upload_reason VARCHAR(50);
ALTER TABLE sites ADD COLUMN force_full_upload_message TEXT;
ALTER TABLE sites ADD COLUMN force_full_upload_set_at TIMESTAMP;
ALTER TABLE sites ADD COLUMN force_full_upload_set_by VARCHAR(255);
ALTER TABLE sites ADD COLUMN request_logs BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE sites ADD COLUMN request_logs_message TEXT;

-- 2. Add batch type and schema version to batches
ALTER TABLE batches ADD COLUMN batch_type VARCHAR(20);
ALTER TABLE batches ADD COLUMN schema_version INTEGER;
ALTER TABLE batches ADD COLUMN expected_file_count INTEGER;
ALTER TABLE batches ADD COLUMN description TEXT;

-- 3. Create client_diagnostic_logs table
CREATE TABLE client_diagnostic_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id UUID NOT NULL REFERENCES sites(id) ON DELETE CASCADE,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    s3_key VARCHAR(500) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL CHECK (file_size > 0),
    content_type VARCHAR(100),
    client_version VARCHAR(100),
    os VARCHAR(200),
    period_from TIMESTAMP,
    period_to TIMESTAMP,
    tags JSONB,
    description TEXT,
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_client_diagnostic_logs_site_id ON client_diagnostic_logs(site_id);
CREATE INDEX idx_client_diagnostic_logs_account_id ON client_diagnostic_logs(account_id);
CREATE INDEX idx_client_diagnostic_logs_expires_at ON client_diagnostic_logs(expires_at);
CREATE INDEX idx_client_diagnostic_logs_uploaded_at ON client_diagnostic_logs(uploaded_at);
```

---

## State Transitions

### Force Full Upload Lifecycle

```
IDLE (forceFullUpload=false)
  │
  ├── Admin calls POST /force-rebaseline ──→ PENDING (forceFullUpload=true)
  ├── Plugin reinit ──────────────────────→ PENDING (forceFullUpload=true)
  ├── Schema incompatible change ─────────→ PENDING (forceFullUpload=true)
  │
  └── PENDING
        │
        ├── Client calls GET /heartbeat ──→ Client sees directive
        │
        └── Client calls POST /batches/start ──→ IDLE (flag cleared immediately)
```

### Heartbeat → Batch Start Flow

```
Client                              Server
  │                                    │
  ├─ GET /heartbeat ──────────────────>│  Record lastHeartbeatAt
  │<──── 200 {directives, status} ─────┤
  │                                    │
  ├─ POST /batches/start ────────────->│  Check lastHeartbeatAt > now - window
  │  {batchType: "DELTA"}              │  Pin schemaVersion
  │<──── 201 {batchId, ...} ──────────┤  Clear forceFullUpload if needed
```
