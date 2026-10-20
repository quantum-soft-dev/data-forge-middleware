# Bit BI Plugin Integration Guide

**Document Version**: 1.0.0
**Last Updated**: 2025-12-23
**Based on**: PRD-013 (Plugin System), PRD-001 (SQL Generation Extension)

## Table of Contents

1. [Environment Configuration](#environment-configuration)
2. [Overview](#overview)
3. [Architecture](#architecture)
4. [Authentication](#authentication)
5. [Plugin Activation Flow](#plugin-activation-flow)
6. [Plugin API Endpoints](#plugin-api-endpoints)
7. [Bit BI API v2](#bit-bi-api-v2)
8. [SQL Generation Feature](#sql-generation-feature)
9. [API Reference](#api-reference)
10. [Error Handling](#error-handling)
11. [Integration Nuances](#integration-nuances)
12. [Best Practices](#best-practices)
13. [OpenAPI Specification](#openapi-specification)

---

## Environment Configuration

### Development Environment

| Parameter | Value |
|-----------|-------|
| **DFM API Base URL** | `https://dev.dfm.bitbi.io` |
| **Auth0 Domain** | `dev-dfm.us.auth0.com` |
| **Auth0 Audience** | `https://dev-dfm.bitbi.io` |
| **Auth0 Claims Namespace** | `https://dev.dfm.bitbi.io` |
| **DFM SPA Client ID** | `2sTGyEnKDASQFT2qVbYHQeUpROOTvCJ9` |

> **Note**: For Bit BI integration, you will need to register your own Auth0 M2M application in the DFM Auth0 tenant. Contact the DFM team to obtain your `client_id` and `client_secret`.

### Production Environment

| Parameter | Value |
|-----------|-------|
| **DFM API Base URL** | `https://dfm.bitbi.io` |
| **Auth0 Domain** | `dfm.us.auth0.com` |
| **Auth0 Audience** | `https://dfm.bitbi.io` |
| **Auth0 Claims Namespace** | `https://dfm.bitbi.io` |

---

## Overview

Data Forge Middleware (DFM) provides an extensible plugin system that allows third-party applications to integrate with user accounts. **Bit BI** is the first OAuth-based integration plugin, enabling:

- **Account linking**: Connect Bit BI accounts to DFM accounts via OAuth2
- **Automatic SQL generation**: Generate PostgreSQL SQL files from CSV uploads
- **Site-level data access**: Retrieve SQL changes for specific sites

### Key Capabilities

| Feature | Description |
|---------|-------------|
| OAuth2 Plugin Activation | Link Bit BI tenant to DFM account |
| Plugin API Key | Authenticate API requests without OAuth tokens |
| SQL Change Tracking | Automatic INSERT/UPDATE/DELETE generation |
| Multi-site Support | Access data for all sites under an account |

---

## Architecture

```
┌─────────────────┐     OAuth2      ┌──────────────────────┐
│                 │ ◄────────────►  │                      │
│    Bit BI       │                 │   Data Forge         │
│   Application   │  Plugin API     │   Middleware         │
│                 │ ◄────────────►  │                      │
└─────────────────┘   (API Key)     └──────────────────────┘
                                              │
                                              ▼
                                    ┌──────────────────────┐
                                    │    PostgreSQL DB     │
                                    │  ─────────────────   │
                                    │  account_plugins     │
                                    │  plugin_sql_gen      │
                                    │  plugin_audit_logs   │
                                    └──────────────────────┘
                                              │
                                              ▼
                                    ┌──────────────────────┐
                                    │      AWS S3          │
                                    │  ─────────────────   │
                                    │  plugins/bit-bi/     │
                                    │  {accountId}/        │
                                    │  {siteName}/*.sql    │
                                    └──────────────────────┘
```

### Data Flow

1. **User initiates OAuth flow** from Bit BI
2. **User authenticates** via Auth0 (DFM identity provider)
3. **Bit BI receives** authorization code and exchanges for tokens
4. **Bit BI calls** plugin activation endpoint with `tenantId`
5. **DFM generates** Plugin API Key and returns it
6. **Bit BI stores** API Key for subsequent API calls
7. **Batch uploads** trigger automatic SQL generation
8. **Bit BI fetches** SQL changes via Plugin API

---

## Authentication

The plugin system uses two authentication methods:

### 1. OAuth2 (Plugin Activation)

Used for plugin activation/deactivation operations.

**Header**: `Authorization: Bearer {access_token}`

**Token Requirements**:
- Valid Auth0 JWT access token
- Must include `accountId` custom claim
- Token obtained via Auth0 authorization flow

**Auth0 Custom Claims**:
```json
{
  "https://dev.dfm.bitbi.io/accountId": "550e8400-e29b-41d4-a716-446655440000",
  "https://dev.dfm.bitbi.io/roles": ["ROLE_USER"]
}
```

### 2. Plugin API Key (Data Access)

Used for accessing SQL changes and site data after plugin is activated.

**Header**: `X-Plugin-Api-Key: plk_xxxxxxxxxxxxxxxxxxxxxxxxxxxxx`

**API Key Format**:
- Prefix: `plk_`
- Length: 32 alphanumeric characters
- Total: 36 characters
- Example: `plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6`

**Security**:
- API Key is returned **only once** — at activation, or from the rotation endpoint below
- Stored as a BCrypt hash (verification) plus a SHA-256 lookup handle (index); neither is reversible
- Rotate with `POST /api/v1/account/plugins/bit-bi/rotate-api-key` (see below)

### Rotating the API Key

The account owner rotates the key over HTTP, authenticated with their own OAuth2 token — the
same shape as the Parquet Export plugin's `rotate-password`. The same action sits in the UI as
**Rotate API key** on the plugin card in Dashboard → My Plugins, which shows the new key once in
a copy-and-acknowledge dialog.

```bash
curl -X POST https://api.dataforge.com/api/v1/account/plugins/bit-bi/rotate-api-key \
  -H "Authorization: Bearer {oauth2_access_token}"
```

```json
{
  "apiKey": "plk_x9Y8w7V6u5T4s3R2q1P0o9N8m7L6k5J4"
}
```

| Status | Meaning |
|---|---|
| 200 | New key issued — shown here once and never again |
| 401 | Not authenticated |
| 403 | Bit BI is not activated for this account |

The previous key stops authenticating **immediately**, so update every client before rotating.
The action is recorded in the plugin audit log as `API_KEY_ROTATED`.

Rotation also re-derives the indexed SHA-256 lookup handle. Activations issued before V42 have
no handle and are served by a fallback scan; rotating is what moves them onto the indexed path,
which is tracked by the `plugin.api.key.validation.legacy.hit` metric.

---

## Plugin Activation Flow

### Step 1: Initiate OAuth Flow (Bit BI Frontend)

```javascript
// Redirect user to Auth0 authorization endpoint
const authUrl = new URL('https://dev-dfm.us.auth0.com/authorize');
authUrl.searchParams.set('response_type', 'code');
authUrl.searchParams.set('client_id', 'YOUR_BITBI_CLIENT_ID');  // Bit BI's Auth0 client ID
authUrl.searchParams.set('redirect_uri', 'https://bitbi.io/callback');
authUrl.searchParams.set('scope', 'openid profile email');
authUrl.searchParams.set('audience', 'https://dev-dfm.bitbi.io');
authUrl.searchParams.set('state', generateRandomState());

window.location.href = authUrl.toString();
```

### Step 2: Exchange Code for Tokens (Bit BI Backend)

```bash
curl -X POST https://dev-dfm.us.auth0.com/oauth/token \
  -H "Content-Type: application/json" \
  -d '{
    "grant_type": "authorization_code",
    "client_id": "YOUR_BITBI_CLIENT_ID",
    "client_secret": "YOUR_BITBI_CLIENT_SECRET",
    "code": "AUTHORIZATION_CODE",
    "redirect_uri": "https://bitbi.io/callback"
  }'
```

**Response**:
```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "id_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "token_type": "Bearer",
  "expires_in": 86400
}
```

### Step 3: Activate Plugin (Bit BI Backend)

```bash
curl -X POST https://dev.dfm.bitbi.io/api/v1/plugins/bit-bi/activate \
  -H "Authorization: Bearer {access_token}" \
  -H "Content-Type: application/json" \
  -d '{
    "pluginData": {
      "tenantId": "bit-bi-tenant-acme"
    }
  }'
```

**Response (201 Created - New Activation)**:
```json
{
  "pluginId": "bit-bi",
  "pluginName": "Bit BI",
  "accountId": "550e8400-e29b-41d4-a716-446655440000",
  "isActive": true,
  "activatedAt": "2025-01-15T10:30:00Z",
  "apiKey": "plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6",
  "lastUsedAt": null
}
```

**Response (200 OK - Plugin Already Active)**:
```json
{
  "pluginId": "bit-bi",
  "pluginName": "Bit BI",
  "accountId": "550e8400-e29b-41d4-a716-446655440000",
  "isActive": true,
  "activatedAt": "2025-01-10T08:00:00Z",
  "apiKey": null,
  "lastUsedAt": "2025-01-14T15:45:00Z"
}
```

#### Calling `activate` again on an active plugin

A second `POST .../bit-bi/activate` for an account whose Bit BI plugin is **already active** has
no side effects beyond the configuration:

- the `pluginData` you send is merged into the stored one (keys you send are added or replaced,
  keys you omit are kept), so this is how `tenantId` is changed;
- **no new API key is issued**: the key you already hold keeps authenticating, and the response
  carries `"apiKey": null`;
- the SQL baselines are **not** moved: neither the baseline batch nor the per-table delta
  baselines are re-captured, so `/sql-changes` continues exactly where it was. Moving them is
  what `reinit` is for, and it is a separate, explicit owner action;
- the answer is `200 OK` with the current state of the activation.

Before issue #383 this call silently replaced the key (the one you held stopped working, and the
new one was shown to nobody) and re-captured the baselines; only an owner rotation restored
access. A **reactivation** — `activate` after `deactivate` — is unchanged: it issues a new key,
returns it once in this response, and the previous key stops authenticating.

> **Important**: Store the `apiKey` securely. It is returned **only for a new activation or a
> reactivation** and cannot be retrieved later. Re-posting `activate` for an already-active
> plugin updates the configuration, keeps the existing key and returns none. If the key is lost, rotate it —
> `POST /api/v1/account/plugins/bit-bi/rotate-api-key` (owner Auth0 token), or the **Rotate API
> key** action on the plugin card in Dashboard → My Plugins. The new key is shown once and the
> previous one stops authenticating immediately.

### Step 4: Store API Key

Store the API Key securely in your database, associated with the tenant:

```sql
UPDATE tenants
SET dfm_api_key = 'plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6',
    dfm_connected_at = NOW()
WHERE id = 'bit-bi-tenant-acme';
```

---

## Plugin API Endpoints

After activation, use the Plugin API Key for all subsequent requests.

### List Available Sites

Retrieve all sites available for the connected account.

```bash
curl -X GET https://dev.dfm.bitbi.io/api/v1/plugins/bit-bi/sites \
  -H "X-Plugin-Api-Key: plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6" \
  -H "Accept: application/json"
```

**Response (200 OK)**:
```json
{
  "sites": [
    {
      "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "domain": "store.example.com",
      "displayName": "Example Store"
    },
    {
      "id": "b2c3d4e5-f6a7-8901-bcde-f23456789012",
      "domain": "warehouse.example.com",
      "displayName": "Warehouse System"
    }
  ]
}
```

### Download Baseline Files

Before applying SQL deltas a client bootstraps from the site's baseline: one file per table.

> **Breaking change (issue #113).** These files used to be gzipped CSV named `<table>.csv.gz`.
> They are now **Parquet**, named `<table>.parquet` and served as
> `application/vnd.apache.parquet`. The old names are neither listed nor downloadable — a request
> for `<table>.csv.gz` answers `404`, deliberately rather than returning Parquet bytes under a
> `.gz` name. The middleware stopped writing the CSV snapshot altogether, so there is no
> compatibility window: a client must read Parquet to initialize a Delta (V2) site.
>
> Only the bytes change. Baseline bookkeeping is the same — the plugin captures checkpoint seqs
> and emits SQL only for records with `seq > baseline_seq(table)`, so the download still pairs
> with the SQL stream exactly as before.

```bash
curl -X GET https://dev.dfm.bitbi.io/api/v1/plugins/bit-bi/sites/{siteId}/files \
  -H "X-Plugin-Api-Key: plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6" \
  -H "Accept: application/json"
```

**Response (200 OK)**:
```json
{
  "files": [
    { "fileName": "customers.parquet", "fileSize": 20480, "lastModified": "2026-08-14T02:00:00Z" },
    { "fileName": "orders.parquet",    "fileSize": 91234, "lastModified": "2026-08-14T02:00:00Z" }
  ]
}
```

Download one by name:

```bash
curl -X GET https://dev.dfm.bitbi.io/api/v1/plugins/bit-bi/sites/{siteId}/files/customers.parquet \
  -H "X-Plugin-Api-Key: plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6" \
  --output customers.parquet
```

A table appears here only once its checkpoint snapshot is materialized, which requires the source
client to have submitted a schema for it.

**Columns.** A snapshot holds the table's declared columns and, since issue #369, one more as its
**last** column: `_row_hash`, the source client's row identifier (64 lowercase hex characters, or
null when the client sent none — every row until the client is updated). It is not a column of the
mirrored source table: a client creating or loading the mirror table from the snapshot must skip it,
or add it as a nullable text column of its own. The SQL stream does not use it — `DELETE`/`UPDATE`
still address rows by their key columns. **It is not a guaranteed unique key**: in the source
client's `track_duplicates` mode the hash names a row's content rather than one copy of it, so several
records of a keyless table carry one value, and the server itself never relies on its uniqueness. A
mirror must not declare it `UNIQUE` or a primary key, and must not deduplicate or collapse rows by it.
A null means the hash is unknown, not that the rows holding it are one row. See
`docs/delta-client-v2-guide.md` ("`row_hash`"). A site that never ingested through Delta answers with its
historical uploaded files instead, in their original formats.

**Decimals stop at 38 digits.** A `numeric(p,s)` column is a Parquet decimal of at most
`decimal(38, s)` (issue #382) — the most a 128-bit reader decodes exactly — whatever its declared
precision or the width its data asks for. A value that needs more than 38 digits at the column's
scale (on a DBF site, typically a number the source program stored in exponent form, `.12E+40`) is
**NULL** in the snapshot rather than a column wide enough to make the reader refuse the file; the
SQL stream still carries the value as text. On a keyless table that NULL is part of the row's
identity in the snapshot, so a later `UPDATE`/`DELETE` of that row from `/sql-changes` does not
reach the row loaded from the snapshot. A column declared with a scale above 38 is text. See
`docs/delta-client-v2-guide.md` ("A value too wide for any decimal column").

**Row groups.** A snapshot is split into row groups by size, not by row count: a writer closes a
group once its buffer reaches `delta.parquet.row-group-bytes` (`DELTA_PARQUET_ROW_GROUP_BYTES`). The
value is per deployment — **8 MiB on dev** (`test.dfm.bitbi.io`, issue #385), the 16 MiB default on
stage and prod — so the rows per group depend on the table's width and the deployment, not on a
constant a client can assume. Dev was lowered because Bit BI's reader decodes a row group whole and
refuses one above six million cells; at 16 MiB the widest table there reached about 6.2 million.
The rows are the same either way. A snapshot takes a new value on its next nightly build, while batch
and delta files already written keep the groups they were written with.

### Get SQL Changes

Retrieve SQL changes for a specific site after a given date.

```bash
curl -X GET "https://dev.dfm.bitbi.io/api/v1/plugins/bit-bi/sql-changes?siteId=a1b2c3d4-e5f6-7890-abcd-ef1234567890&since=2025-01-01T00:00:00Z" \
  -H "X-Plugin-Api-Key: plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6" \
  -H "Accept: text/plain"
```

**Response (200 OK)**:
```sql
-- SQL generated from batch: 2025-01-15T10:30:00Z
-- Source: customers.csv

INSERT INTO customers (id, name, email, phone)
VALUES ('cust-001', 'John Doe', 'john@example.com', '+1-555-0100');
--- END OF COMMAND "customers.csv:2" ---

UPDATE customers
SET phone = '+1-555-0200'
WHERE id = 'cust-002' AND name = 'Jane Smith' AND email = 'jane@example.com';
--- END OF COMMAND "customers.csv:3" ---

DELETE FROM customers
WHERE id = 'cust-003' AND name = 'Old Customer' AND email = 'old@example.com';
--- END OF COMMAND "customers.csv:4" ---

-- SQL generated from batch: 2025-01-16T14:00:00Z
-- Source: orders.csv

INSERT INTO orders (id, customer_id, amount, status)
VALUES ('ord-100', 'cust-001', 150.00, 'pending');
--- END OF COMMAND "orders.csv:2" ---
```

**Query Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `siteId` | UUID | Yes | Site identifier from `/sites` endpoint |
| `since` | ISO8601 | Yes | Return changes after this timestamp |

**Empty Response (200 OK)**:
If no changes exist after the specified date, an empty body is returned.

### Recovering a batch's SQL (delete + generate)

There is no "regenerate" action (a broken-by-design regeneration path was retired by issue #190).
Re-creating the SQL of one batch is two steps, both available in the My Plugins → SQL tab and on
the admin API: **delete** the generation (`DELETE .../generations/{generationId}?confirm=true`,
which removes the S3 file and frees the batch's one-generation slot), then **generate**
(`POST .../generate-sql` with the batch id — segment-backed Delta batches are supported).

**Caveat — delete + generate re-serves the batch to a client that already fetched it.** The new
generation gets a new `created_at`, and `/sql-changes` serves rows by that timestamp against the
client's `since` cursor. A client whose cursor has **not yet** passed the batch simply receives
the corrected SQL once. A client whose cursor **already passed** the batch will receive that
batch's SQL a **second time** — and the generated SQL is plain `INSERT`/`UPDATE`/`DELETE` with no
`ON CONFLICT`, so applying it twice duplicates rows or violates primary keys. For a batch the
client has already fetched, do not use delete + generate: use **reinit**
(`POST /api/v1/account/plugins/bit-bi/reinit`), which resets the baselines and has the client
re-download `/files`.

**Second limit — generate only re-creates SQL for a batch above the plugin's current delta
baselines.** `DeltaSqlGenerationStrategy` emits only records with `seq` above the captured
baseline, so after a reinit re-captured the baselines, delete + generate on an **older**
segment-backed batch renders zero statements and the batch settles as "No changes" — the deleted
SQL is not recoverable through this path. If generate produces nothing where SQL existed before,
the answer is again **reinit**.

---

## Bit BI API v2

Version 2 lets Bit BI mirror a site from the server's typed Parquet files instead of generated SQL,
holding the watermark on its own side. The design and the plan are
[`docs/cr-bitbi-parquet-plugin.md`](cr-bitbi-parquet-plugin.md); the spec is
`specs/044-bitbi-parquet/`. This section documents what is **shipped**: phase 1 — `/v2/sites`,
`/state` and `/schema` (issue #386), `/snapshot` and `/files/{fileId}` (issue #387) — and phase 2,
the changes feed `/changes` (issue #388).

**Same key, same activation, same limit.** Every v2 route is under
`/api/v1/plugins/bit-bi/v2`, authenticated by the `X-Plugin-Api-Key` of the account's `bit-bi`
activation — the key v1 uses; there is no separate activation and no mode. v1 and v2 calls draw
from **one** per-account rate-limit bucket (100 requests a minute). v1 keeps working unchanged
beside v2.

Every response body below is the exact wire form, pinned whole by the contract test
`BitBiV2ApiContractTest` (on the production security chain and a real server); only the ids, the
timestamp and the numbers are examples. Keys are always present and in this order; JSON parsers
need not rely on the order.

### GET /api/v1/plugins/bit-bi/v2/sites

The account's active sites — the same selection as v1's `/sites`.

```bash
curl https://dev.dfm.bitbi.io/api/v1/plugins/bit-bi/v2/sites \
  -H "X-Plugin-Api-Key: plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6"
```

**Response (200 OK)**:
```json
{"sites":[{"id":"0199baac-f852-753f-6fc3-7c994fc38654","siteName":"fyt-new","displayName":"FYT"}]}
```

### GET /api/v1/plugins/bit-bi/v2/sites/{siteId}/state

Where the site's history stands — what a consumer compares its watermark `(epoch, seq)` with.

**Response (200 OK)**:
```json
{"siteId":"0199baac-f852-753f-6fc3-7c994fc38654","epoch":3,"checkpointSeq":5020646,"lastAppliedSeq":5023845,"schemaVersion":2}
```

| Field | Meaning |
|-------|---------|
| `epoch` | The site's baseline epoch. It moves on a **history wipe** and on a **re-baseline** (a client's full `FULL_SNAPSHOT` that replaces the site's history) and on nothing else. A value different from the one stored with your watermark means every row you hold belongs to a discarded history: clear the site's tables and reload. It is not the ingestion client's `generation`, which a re-baseline does not move |
| `checkpointSeq` | The seq the current checkpoint snapshots are built at. `0` = no checkpoint yet (a new site, or right after a wipe or a re-baseline): wait for the nightly build |
| `lastAppliedSeq` | The highest seq the server has committed |
| `schemaVersion` | The version of the schema `/schema` serves; `0` = none submitted. When it moves, read `/schema` again before applying anything new |

A site that has never synced answers zeros everywhere:

```json
{"siteId":"0199baac-f852-753f-6fc3-7c994fc38654","epoch":0,"checkpointSeq":0,"lastAppliedSeq":0,"schemaVersion":0}
```

### GET /api/v1/plugins/bit-bi/v2/sites/{siteId}/schema

The schema the site's client declared, with the row identity to key each table by. It is served
because a Parquet file records neither the primary key nor the identity. Nothing is stored for it:
`identity` is derived from the declared `primaryKey` — `row_hash` when it is empty (every DBF
table; key rows by `_row_hash`), `primary_key` otherwise (key rows by those columns, ignore
`_row_hash`). Tables are sorted by name; columns keep their declared order.

**Response (200 OK)**:
```json
{"siteId":"0199baac-f852-753f-6fc3-7c994fc38654","schemaVersion":1,"tables":[{"table":"nsftrns","identity":"row_hash","primaryKey":[],"uniqueKeys":[],"columns":[{"name":"TR_NUM","type":"integer","nullable":true},{"name":"TR_DATE","type":"date","nullable":true}]},{"table":"orders","identity":"primary_key","primaryKey":["id"],"uniqueKeys":[{"name":"uk_orders_total","columns":["total"]}],"columns":[{"name":"id","type":"bigint","nullable":false},{"name":"total","type":"numeric(12,2)","nullable":true}]}]}
```

`type` is the PostgreSQL type as the client declared it. A declared `numeric(p,s)` can understate
the data, in which case the Parquet column is wider than declared (see
[`docs/delta-client-v2-guide.md`](delta-client-v2-guide.md)); load through casts. A site with no
submitted schema answers:

```json
{"siteId":"0199baac-f852-753f-6fc3-7c994fc38654","schemaVersion":0,"tables":[]}
```

### GET /api/v1/plugins/bit-bi/v2/sites/{siteId}/snapshot

The site's nightly checkpoint: one Parquet file per table, **every one at the same seq**,
`checkpointSeq` — the value `/state` answers. Load every `ready` table, then store
`(epoch, checkpointSeq)` as your watermark; the changes after it are exactly what the changes feed
will serve above that seq.

**Response (200 OK)**:
```json
{"siteId":"0199baac-f852-753f-6fc3-7c994fc38654","epoch":3,"checkpointSeq":5020646,"tables":[{"table":"nsfcard","status":"missing","fileId":null},{"table":"nsftrns","status":"ready","fileId":"Y2sxOjAxOTliYWFjLWY4NTItNzUzZi02ZmMzLTdjOTk0ZmMzODY1NDozOjUwMjA2NDY6bnNmdHJucw"},{"table":"orders","status":"ready","fileId":"Y2sxOjAxOTliYWFjLWY4NTItNzUzZi02ZmMzLTdjOTk0ZmMzODY1NDozOjUwMjA2NDY6b3JkZXJz"}]}
```

| Field | Meaning |
|-------|---------|
| `epoch` | The baseline epoch the files belong to — the same value as `/state` |
| `checkpointSeq` | The seq every `ready` file is at. `0` = no checkpoint yet; then `tables` is empty |
| `tables[].status` | `ready` — a file exists at `checkpointSeq`; `missing` — none does |
| `tables[].fileId` | Pass it to `/files/{fileId}`; `null` unless `ready`. Opaque: do not parse it |

Tables are sorted by name. **`missing` is never silent**: a table the site has a checkpoint row for
but no file at `checkpointSeq` is listed with `fileId: null`. It means one of: the build could not
write the table's snapshot (it gave up after repeated failures, and an operator has to look); the
table's state is older than `checkpointSeq`; or the table first appeared after `checkpointSeq` and
its first snapshot is being built — its rows then arrive in the changes after `checkpointSeq`. Do not
advance past a `missing` table you hold rows for.

**During the nightly build (around 02:00 UTC)** the build replaces tables one by one and moves
`checkpointSeq` only at the end — or not at all, if it stops part way, until the next night. The
listing does not follow it table by table: it keeps answering the previous build's files, all at
the previous `checkpointSeq`, until the pointer moves. A download therefore never mixes two builds
(v1's `/sites/{siteId}/files` does, in that window). Once `checkpointSeq` has moved, `/snapshot`
lists the new set.

A site with no Delta checkpoint answers `checkpointSeq: 0` and no tables; v2 does not serve the
historical uploaded CSV files v1's `/files` falls back to.

### GET /api/v1/plugins/bit-bi/v2/sites/{siteId}/changes

The changes after a watermark, **one row per batch** — one ingestion session — never per file. Each
batch carries the manifest of every table it touched. Batches come in **batch order**, the order the
sessions ran in, not the order their files were published.

| Parameter | Meaning |
|-----------|---------|
| `epoch` | The epoch your watermark belongs to. **Required** when `afterSeq` is above 0 |
| `afterSeq` | Your watermark's seq: only batches whose `lastSeq` is above it. Read within `epoch` only; default `0` = from the start of the current epoch |
| `cursor` | `nextCursor` of the previous page |
| `size` | Batches per page, 1 to 200; default 50 |

```bash
curl "https://dev.dfm.bitbi.io/api/v1/plugins/bit-bi/v2/sites/0199baac-f852-753f-6fc3-7c994fc38654/changes?epoch=3&afterSeq=5020646&size=2" \
  -H "X-Plugin-Api-Key: plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6"
```

**Response (200 OK)** — a page that has a next one:
```json
{"siteId":"0199baac-f852-753f-6fc3-7c994fc38654","epoch":3,"batches":[{"epoch":3,"batchId":"0199bb0f-1a2b-7c3d-8e4f-5a6b7c8d9e01","sessionMode":"continuous","firstSeq":5019800,"lastSeq":5023011,"readyAt":"2026-10-20T01:14:07.311Z","tables":[{"table":"nsftrns","status":"ready","fileId":"YnAxOjAxOTliYWFjLWY4NTItNzUzZi02ZmMzLTdjOTk0ZmMzODY1NDowMTk5YmIwZi0xYTJiLTdjM2QtOGU0Zi01YTZiN2M4ZDllMDE6bnNmdHJucw","rows":3212}]},{"epoch":3,"batchId":"0199bb10-4c2e-7a1d-9f00-3b5e1a2c7d41","sessionMode":"delta","firstSeq":5023012,"lastSeq":5023845,"readyAt":"2026-10-20T06:03:12.500Z","tables":[{"table":"nsfcard","status":"abandoned","fileId":null,"rows":25},{"table":"nsftrns","status":"ready","fileId":"YnAxOjAxOTliYWFjLWY4NTItNzUzZi02ZmMzLTdjOTk0ZmMzODY1NDowMTk5YmIxMC00YzJlLTdhMWQtOWYwMC0zYjVlMWEyYzdkNDE6bnNmdHJucw","rows":834},{"table":"tmp_x","status":"missing","fileId":null,"rows":4}]}],"nextCursor":"ZmMxOjAxOTliYWFjLWY4NTItNzUzZi02ZmMzLTdjOTk0ZmMzODY1NDoxNzkyNDc2MDAwMDAwMDAwOjAxOTliYjEwLTRjMmUtN2ExZC05ZjAwLTNiNWUxYTJjN2Q0MQ"}
```

The next page — the same parameters plus the cursor — and the last one for now:

```bash
curl "https://dev.dfm.bitbi.io/api/v1/plugins/bit-bi/v2/sites/0199baac-f852-753f-6fc3-7c994fc38654/changes?epoch=3&afterSeq=5020646&size=2&cursor=ZmMxOjAxOTliYWFjLWY4NTItNzUzZi02ZmMzLTdjOTk0ZmMzODY1NDoxNzkyNDc2MDAwMDAwMDAwOjAxOTliYjEwLTRjMmUtN2ExZC05ZjAwLTNiNWUxYTJjN2Q0MQ" \
  -H "X-Plugin-Api-Key: plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6"
```

```json
{"siteId":"0199baac-f852-753f-6fc3-7c994fc38654","epoch":3,"batches":[],"nextCursor":null}
```

| Field | Meaning |
|-------|---------|
| `epoch` (top level) | The site's current epoch — the same value as `/state`. Every listed batch belongs to it |
| `batches[].epoch` | The epoch the batch belongs to |
| `batches[].sessionMode` | `full_snapshot` replaces every table of the site; `delta` and `continuous` apply on top. `null` for a batch that did not record one |
| `batches[].firstSeq`, `lastSeq` | The batch's seq range. Once the batch is applied, store `(epoch, lastSeq)` as your watermark |
| `batches[].readyAt` | When the batch's last table settled (its file was published, or its build gave up); for a batch with no build at all, when the session ended |
| `tables[].status` | `ready` — the file exists; `abandoned` — the build gave up (an operator can requeue it); `missing` — no file and no build: the table has no declared schema, or the batch was never queued |
| `tables[].fileId` | Pass it to `/files/{fileId}`; `null` unless `ready` |
| `tables[].rows` | The file's rows when `ready`; otherwise the batch's records of the table |
| `nextCursor` | Pass it as `cursor` for the next page; `null` when the feed ends here for now |

**`afterSeq` is read within your epoch.** A history wipe restarts seq at 1, and a client may resend a
snapshot from seq 1, so a seq of a discarded epoch says nothing about the new one. When `epoch` is
not the site's current one, `afterSeq` is ignored and the feed starts at the beginning of the current
epoch — whose first batch is the `full_snapshot` that opened it. `afterSeq` above 0 without `epoch`
is `400`. Only the current epoch is ever listed.

**The feed ends before the first batch whose files are still being built** (or have not been queued
yet), so you never see batch N+1 before N is settled; poll again later. Only batches above your
`afterSeq` (and after your cursor) can end it: an older batch whose build is still retrying does not
stop a consumer that is already past it. A batch you passed as `abandoned` that an operator later requeues and
rebuilds does not appear again above your watermark: to pick its file up, reload the site (from
`/snapshot`, or from the epoch's start). An empty page with a `null`
cursor means "nothing more for now", not "the site is done".

**A cursor belongs to the site it was issued for**: one of another site, or one this server did not
issue, is `400`. A cursor is a position, not a watermark — keep `(epoch, lastSeq)` as your watermark
and use the cursor only to walk the pages of one poll.

**Rules for applying a batch** (answered to BitBi, v6):

- Apply a batch's rows **in `_seq` order**. The server does not net them: a row inserted and then
  updated in the same batch arrives as both.
- A `full_snapshot` batch is loaded **from its own files**, not from `/snapshot`: clear the site's
  tables and load every `ready` table of the batch. A table with no rows in the snapshot has no file
  and is not in the manifest — it is empty.
- `checkpointSeq` can fall **inside** a `continuous` batch: after loading `/snapshot`, the first batch
  of the feed may start below your position. Take only its rows with `_seq` above it.
- Seq is **dense within an epoch**: the batches of one epoch cover every seq from the first to the last
  without a gap. A batch the server ended (a timed-out session that had committed some records) is
  listed with every table `missing`, so a gap is never silent.
- A batch with any table not `ready`: stop the site and alert. An operator requeues an abandoned
  artifact, or you wait for the nightly checkpoint to pass the batch and reload from `/snapshot`.

| Status | When |
|--------|------|
| 200 | A page (possibly empty) |
| 400 | `size` out of 1–200, a negative `afterSeq` or `epoch`, `afterSeq` without `epoch`, or a cursor not issued for this site |
| 404 | The site does not exist or belongs to another account |

### GET /api/v1/plugins/bit-bi/v2/files/{fileId}

Download one file. The answer is a **302** whose `Location` is a presigned storage URL valid for
**60 seconds**; follow it (most HTTP clients do) **without** the API key — the URL carries its own
signature. The bytes come straight from storage; nothing passes through this server.

```bash
curl -L -o nsftrns.parquet \
  https://dev.dfm.bitbi.io/api/v1/plugins/bit-bi/v2/files/Y2sxOjAxOTliYWFjLWY4NTItNzUzZi02ZmMzLTdjOTk0ZmMzODY1NDozOjUwMjA2NDY6bnNmdHJucw \
  -H "X-Plugin-Api-Key: plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6"
```

```http
HTTP/1.1 302 Found
Location: https://storage.googleapis.com/dfm-dev-uploads/checkpoints/0199baac-f852-753f-6fc3-7c994fc38654/nsftrns/seq=5020646/snapshot.parquet?X-Amz-Algorithm=AWS4-HMAC-SHA256&...
Cache-Control: no-store
```

On dev the storage host is `storage.googleapis.com`, the bucket `dfm-dev-uploads`, path-style (the
bucket is the first path segment). Only the call to `/files` spends a token of the rate-limit bucket;
the storage download does not.

A `fileId` comes from `/snapshot` (a checkpoint file) or from `/changes` (a batch file); both go
through this route.

**A batch file** (from `/changes`) resolves while its batch belongs to the site's current epoch and
its file is `ready`; it is never rewritten, and it lives as long as its batch (45 days by default) —
then `404`.

**A checkpoint `fileId` names one version of one file.** It keeps leading to the object `/snapshot` listed
after the next build has replaced the table, until that superseded object is removed: the
server's storage cleanup may reclaim it on its first pass after the replacing build has moved
`checkpointSeq` — so download a listing promptly rather than keeping its ids for later. Then the
answer is `404`: read `/snapshot` again. A `fileId` from before a
history wipe or a re-baseline (an `epoch` you no longer hold) is `404` as well, even if the same
seq exists again in the new history.

| Status | When |
|--------|------|
| 302 | The file exists; `Location` is the presigned URL |
| 404 | The id is not one this server issued, belongs to **another account**, belongs to a discarded epoch, names a table the site no longer has, names a batch file that is not `ready`, or the object is gone — one body for all of them |
| 503 | Storage would not say whether the object exists; retry |

```json
{"timestamp":"2026-10-20T08:00:00.123456Z","status":404,"error":"Not Found","message":"File not found: Y2sxOjAxOTliYWFjLWY4NTItNzUzZi02ZmMzLTdjOTk0ZmMzODY1NDozOjUwMjA2NDY6b3JkZXJz","path":"/api/v1/plugins/bit-bi/v2/files/Y2sxOjAxOTliYWFjLWY4NTItNzUzZi02ZmMzLTdjOTk0ZmMzODY1NDozOjUwMjA2NDY6b3JkZXJz"}
```

### v2 errors

| Status | When | Body |
|--------|------|------|
| 400 | `/changes`: a parameter out of range, `afterSeq` without `epoch`, or a cursor not issued for this site | standard error body |
| 401 | Missing, unknown, rotated-away key, or a deactivated activation — checked before the path | the same members as the standard body, `message` = `Invalid or missing API key`; the key order is not fixed |
| 404 | The site does not exist **or belongs to another account** — the two are indistinguishable on purpose (v1 answers 403 for the second); also an unknown path under `/v2` with a valid key | standard error body |
| 429 | The account's bucket is empty | standard error body, `Retry-After` header |
| 503 | `/snapshot` or `/files`: object storage would not say whether a file exists | standard error body, `message` = `Object storage is temporarily unavailable. Please try again.` |

```json
{"timestamp":"2026-10-19T18:00:00.123456Z","status":404,"error":"Not Found","message":"Site not found: 0199bab0-ca3b-e41c-5521-2f4b33fda8b6","path":"/api/v1/plugins/bit-bi/v2/sites/0199bab0-ca3b-e41c-5521-2f4b33fda8b6/state"}
```

```json
{"timestamp":"2026-10-19T18:00:00.123456Z","status":429,"error":"Too Many Requests","message":"Rate limit exceeded. Retry after 17 seconds.","path":"/api/v1/plugins/bit-bi/v2/sites"}
```

A deactivated (soft-deleted) site of your own account is not in `/v2/sites` but still answers
`/state`, `/schema`, `/snapshot` and `/changes`, as v1's `/files` does.

---

## SQL Generation Feature

### How It Works

1. **Batch Upload Completes**: User uploads CSV files to a site
2. **Event Triggered**: `BATCH_COMPLETED` event is dispatched
3. **Plugin Check**: System checks if Bit BI plugin is active for the account
4. **Diff Calculation**: Current batch files are compared to previous batch
5. **SQL Generation**: INSERT/UPDATE/DELETE statements are generated
6. **S3 Storage**: SQL file is saved to S3
7. **Retrieval Ready**: SQL is available via Plugin API

### SQL Statement Types

#### INSERT (New Rows)

Generated when a row exists in the current batch but not in the previous batch.

```sql
INSERT INTO {table} (col1, col2, col3)
VALUES ('val1', 'val2', 'val3');
--- END OF COMMAND "{filename}:{line_number}" ---
```

#### UPDATE (Modified Rows)

Generated when a row exists in both batches but has changed values.
Uses **unchanged columns** in the WHERE clause for identification.

```sql
UPDATE {table}
SET changed_col = 'new_value'
WHERE unchanged_col1 = 'val1' AND unchanged_col2 = 'val2';
--- END OF COMMAND "{filename}:{line_number}" ---
```

#### DELETE (Removed Rows)

Generated when a row exists in the previous batch but not in the current batch.
Uses **all columns** in the WHERE clause.

```sql
DELETE FROM {table}
WHERE col1 = 'val1' AND col2 = 'val2' AND col3 = 'val3';
--- END OF COMMAND "{filename}:{line_number}" ---
```

### NULL Handling by Column Type

This table is in force when the site has a `TableSchema` for the CSV's table (issue #263).
PostgreSQL types on that schema are mapped onto the DBF types below. Without a schema for the
table, every column is **Character** — empty cells become `NULL` and every populated cell is a
quoted, escaped string, which is what the generator did for every column before the types were
wired through.

| DBF Type | Code | Empty String Becomes |
|----------|------|---------------------|
| Character | C | NULL |
| Numeric | N | NULL |
| Logical | L | NULL |
| Date | D | NULL |
| Float | F | NULL |
| DateTime | T | NULL |
| **Integer** | I | **0** |
| **Currency** | Y | **0** |

PostgreSQL → DBF mapping used on this path:

| PostgreSQL type | DBF type |
|-----------------|----------|
| `integer` / `int` / `bigint` / `smallint` / `serial` / `bigserial` | Integer |
| `money` | Currency |
| `numeric` / `decimal` | Numeric |
| `real` / `float` / `double precision` | Float |
| `boolean` | Logical |
| `date` | Date |
| `timestamp` / `timestamptz` | DateTime |
| anything else (`varchar`, `text`, …) | Character |

A numeric cell is emitted **unquoted** only when it is numeric-shaped (`12.50`, `-3`, `+1.2e-3`).
Anything else in a numeric column — including a SQL fragment such as
`0); DROP TABLE customers; --` — is quoted and escaped, the same as a Character cell. A non-finite
token (`NaN`, `Infinity`) is quoted as described below rather than emitted raw.

### NULL in a WHERE Clause

A value that renders as SQL `NULL` is compared with **`IS NULL`** in the WHERE clause of an UPDATE
or a DELETE, on both paths — a DBF/CSV cell of a type whose empty string becomes NULL (table above),
and a CDC / Delta v2 key column the client sent as NULL:

```sql
DELETE FROM people WHERE name = 'Alice' AND born IS NULL;
UPDATE items SET note = NULL WHERE code = 'A1' AND branch IS NULL;
```

Every other value is compared with `=`, so an index on the column stays usable. `SET col = NULL`
and `VALUES (..., NULL)` are assignments and keep the bare `NULL`. An empty **Integer** or
**Currency** cell is rendered `0` in the INSERT as well, so it is compared as `= 0`.

Before issue #370 the WHERE clause read `col = NULL`, which SQL never evaluates as true: the
statement applied without an error and changed **no row**. A table without a primary or unique key
is addressed by its whole row, so one NULL column was enough — typical for DBF, where an empty or
zero date arrives as NULL — and the mirror kept rows the source had deleted, or collected a
duplicate when a row was deleted and inserted again with a changed value. A key column that may
hold NULL (a nullable column in a unique key) is covered by the same rule.

**Files already delivered are not rewritten.** `GET /sql-changes` returns the stored objects, so a
batch generated before the fix comes back byte-identical, still carrying `= NULL`. A mirror that
applied such files and may have diverged is repaired by a **reinit** (a fresh baseline), not by
fetching the SQL again.

### Non-finite Numbers in Generated SQL

`NaN`, `Infinity` and `-Infinity` are emitted as **quoted** literals (`'NaN'`, `'Infinity'`,
`'-Infinity'`), which PostgreSQL coerces to the target column's type **where that type accepts a
non-finite value** — `numeric`, `real` and `double precision` do; an integral column does not, and
such a statement fails either way (`invalid input syntax for type integer` instead of
`column "nan" does not exist`). A bare `NaN` is a column name, not a literal, which is what made
every such statement fail before issue #233. The value is carried end to end for a `real` /
`double precision` column of a Delta v2 site; for a PostgreSQL `numeric` column the pipeline cannot
store it at all and writes NULL (issue #215), described in
[the Delta client guide](delta-client-v2-guide.md#a-value-the-column-type-cannot-hold).

On the DBF/CSV path the same quoting rule applies once the site schema has mapped a column to a
numeric DBF type. Without a schema the column is Character and the token is quoted anyway.

### Table Name Derivation

Table names are derived from CSV filenames:

| CSV Filename | Table Name |
|-------------|------------|
| `customers.csv` | `customers` |
| `customer-data.csv` | `customer_data` |
| `Order_Items.csv` | `order_items` |
| `data.csv.gz` | `data` |

### First Batch Handling

For the first batch uploaded to a site (no previous batch exists):
- All rows generate **INSERT** statements
- S3 path ends with `first-batch.sql`
- `comparison_batch_id` is NULL in database

---

## API Reference

### OAuth2 Endpoints (Authorization: Bearer token)

#### POST /api/v1/plugins/{pluginId}/activate

Activate a plugin for the authenticated account.

**Request**:
```json
{
  "pluginData": {
    "tenantId": "string (required, 1-64 chars, alphanumeric with hyphens)"
  }
}
```

**Response (201/200)**:
```json
{
  "pluginId": "string",
  "pluginName": "string",
  "accountId": "uuid",
  "isActive": true,
  "activatedAt": "iso8601",
  "apiKey": "string (plk_...) | null",
  "lastUsedAt": "iso8601 | null"
}
```

`apiKey` is set on `201` (new activation) and on a `200` that reactivated a deactivated plugin;
on a `200` for an already-active plugin it is `null`, and nothing but `pluginData` changes — see
[Calling `activate` again on an active plugin](#calling-activate-again-on-an-active-plugin).

#### DELETE /api/v1/plugins/{pluginId}/deactivate

Deactivate a plugin for the authenticated account.

**Response (204 No Content)**: Success

#### GET /api/v1/account/plugins

List active plugin integrations for the authenticated account.

**Query Parameters**:
- `page` (default: 0) - Page number
- `size` (default: 20, max: 100) - Page size
- `includeInactive` (default: false) - Include deactivated plugins

**Response (200)**:
```json
{
  "content": [
    {
      "pluginId": "bit-bi",
      "pluginName": "Bit BI",
      "isActive": true,
      "activatedAt": "2025-01-15T10:30:00Z",
      "deactivatedAt": null,
      "lastUsedAt": "2025-01-20T14:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

### Plugin API Endpoints (X-Plugin-Api-Key header)

#### GET /api/v1/plugins/bit-bi/sites

List available sites for the account.

**Response (200)**:
```json
{
  "sites": [
    {
      "id": "uuid",
      "domain": "string",
      "displayName": "string"
    }
  ]
}
```

#### GET /api/v1/plugins/bit-bi/sites/{siteId}/files

List the site's baseline files — one checkpoint snapshot per table, as `<table>.parquet`
(`<table>.csv.gz` before issue #113).

**Response (200)**:
```json
{
  "files": [
    {
      "fileName": "string",
      "fileSize": 0,
      "lastModified": "2026-08-14T02:00:00Z"
    }
  ]
}
```

#### GET /api/v1/plugins/bit-bi/sites/{siteId}/files/{fileName}

Download one baseline file. Checkpoint snapshots are returned as
`application/vnd.apache.parquet`; historical uploaded files keep their original content type.

**Response (200)**: file content · **404**: no such file for the site

#### GET /api/v1/plugins/bit-bi/sql-changes

Get SQL changes for a site.

**Query Parameters**:
- `siteId` (required) - Site UUID
- `since` (required) - ISO8601 timestamp

**Response (200)**: Plain text SQL statements

---

## Error Handling

### Standard Error Response Format

```json
{
  "timestamp": "2025-01-15T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Detailed error description",
  "path": "/api/v1/plugins/bit-bi/sql-changes"
}
```

### HTTP Status Codes

| Status | Meaning | Common Causes |
|--------|---------|---------------|
| 200 | Success | Request completed successfully |
| 201 | Created | New plugin activation created |
| 204 | No Content | Plugin deactivated successfully |
| 400 | Bad Request | Invalid parameters, schema validation failed |
| 401 | Unauthorized | Missing/invalid OAuth token or API Key |
| 403 | Forbidden | Site doesn't belong to account, no account found |
| 404 | Not Found | Plugin not registered or disabled |
| 406 | Not Acceptable | `Accept` excludes what the endpoint produces — e.g. `Accept: application/json` on `/sql-changes`, which produces `text/plain`. Not retryable unchanged |
| 429 | Too Many Requests | Rate limit exceeded |
| 500 | Server Error | Internal error (contact support) |

### What a status says about your API key

Every path under `/api/v1/plugins/bit-bi/` except `/activate` and `/deactivate` (which take an
Auth0 token) is authenticated by `X-Plugin-Api-Key` — the server owns the whole namespace, not a
list of today's routes (issue #384). So the status of a plugin-API call answers one question at a
time, and a client can act on it without guessing:

| Status | What it means | What to do |
|--------|---------------|------------|
| 401 | The key is missing, malformed, unknown, rotated away, or its activation is deactivated. Checked **before** the path, so a 401 says nothing about whether the route exists | Fix or re-issue the key (`POST /api/v1/account/plugins/bit-bi/rotate-api-key`); do not retry unchanged |
| 404 | The key is valid and no such route exists — e.g. a v2 route called before the server ships it, or a typo. On **v2**, also a site that does not exist or is another account's ([v2 errors](#v2-errors)) | Check the path, the site id and the server version; the key is fine |
| 403 | **v1 only**: the key is valid and the route exists, but the resource is not this account's (a site of another account) | Do not retry; a different key will not help unless it belongs to the owning account |
| 429 | The per-account rate limit is exhausted | Wait `Retry-After` seconds |

**A 403 is never "your key was accepted but the route is forbidden".** Before #384 an unknown path
fell outside the key's namespace, onto the Auth0 chain, and left the server as an empty 403 — a
client holding a valid key read that as a verdict on its key. Both halves are gone: an unknown path
with a valid key is 404, and an anonymous request to any Auth0 route (`/activate`,
`/api/v1/account/**`, …) is 401.

### Error Examples

**Invalid API Key (401)**:
```json
{
  "timestamp": "2025-01-15T10:30:00Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Invalid or missing API key",
  "path": "/api/v1/plugins/bit-bi/sites"
}
```

**Site Access Denied (403)**:
```json
{
  "timestamp": "2025-01-15T10:30:00Z",
  "status": 403,
  "error": "Forbidden",
  "message": "Site does not belong to account",
  "path": "/api/v1/plugins/bit-bi/sql-changes"
}
```

**Invalid Date Format (400)**:
```json
{
  "timestamp": "2025-01-15T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid date format. Expected ISO8601: 2025-01-15T00:00:00Z",
  "path": "/api/v1/plugins/bit-bi/sql-changes"
}
```

**Rate Limited (429)**:
```json
{
  "timestamp": "2025-01-15T10:30:00Z",
  "status": 429,
  "error": "Too Many Requests",
  "message": "Rate limit exceeded. Try again later.",
  "path": "/api/v1/plugins/bit-bi/sql-changes"
}
```

Response includes `Retry-After` header with seconds to wait.

---

## Integration Nuances

### 1. API Key Lifecycle

- **Generation**: API Key is generated during plugin activation
- **Single Return**: Key is returned **only once** in the activation or rotation response
- **Storage**: Key is stored as a BCrypt hash plus a SHA-256 lookup handle (not retrievable)
- **Rotation**: `POST /api/v1/account/plugins/bit-bi/rotate-api-key` issues a new key and
  invalidates the old one immediately. Re-posting `activate` for an already active plugin does
  **not** mint a key (#383) — the existing one keeps working; use the rotation endpoint to
  replace it.
- **Deactivation**: the key is no longer accepted — every request with it answers **401**, like an
  unknown key (validation matches active activations only)

### 2. Upsert Behavior

Plugin activation uses **upsert** semantics:
- First activation: Creates new record, issues the API key, sets the SQL baselines, returns 201
- Subsequent activations of an active plugin: merge `pluginData` only — same key, same
  baselines — and return 200 with `apiKey: null`
- Re-activation after deactivation: Sets `is_active=true`, issues and returns a new key,
  re-captures the SQL baselines, returns 200

### 3. Account vs Admin Users

| User Type | Has Account Record | Can Activate Plugins | Plugin List Returns |
|-----------|-------------------|---------------------|---------------------|
| ROLE_USER | Yes | Yes | Active plugins |
| ROLE_ADMIN | No | No (403) | Empty list |

Admin users have no `accountId` claim and cannot use plugin features.

### 4. Event Processing

- **Asynchronous**: Batch completion events are processed asynchronously
- **Isolated**: Plugin failures don't affect other plugins or core system
- **Timeout**: Plugin execution times out after 30 seconds
- **Audit**: All events are logged in `plugin_audit_logs` table

### 5. SQL Generation Timing

- SQL is generated immediately after `BATCH_COMPLETED` event
- Generation completes within 60 seconds for 100 CSV files (SC-001)
- API response time under 2 seconds (SC-002)
- Empty diffs (identical files) produce no SQL file

### 6. File Processing Limitations

| Limitation | Value |
|------------|-------|
| Max CSV files per batch | 100 |
| Supported file types | `.csv`, `.csv.gz` |
| Binary files | Skipped (not processed) |
| Encoding detection | UTF-8, Windows-1252, ISO-8859-1 |

### 7. Concurrent Requests

- Each batch generates a unique SQL file
- API requests are thread-safe
- Rate limiting prevents abuse

---

## Best Practices

### 1. Store API Key Securely

```python
# Good: Encrypt at rest
encrypted_key = encrypt(api_key, encryption_key)
store_in_database(tenant_id, encrypted_key)

# Bad: Plain text storage
store_in_database(tenant_id, api_key)  # DON'T DO THIS
```

### 2. Handle Token Refresh

```python
def call_dfm_api(endpoint, api_key):
    response = requests.get(
        f"https://dev.dfm.bitbi.io{endpoint}",
        headers={"X-Plugin-Api-Key": api_key}
    )

    if response.status_code == 401:
        # API Key might be invalidated, prompt re-connection
        notify_user_reconnect_required()

    return response
```

### 3. Poll for Changes Efficiently

```python
from datetime import datetime, timedelta

def poll_sql_changes(site_id, api_key, last_poll_time):
    """Poll for new SQL changes since last poll."""

    response = requests.get(
        f"https://dev.dfm.bitbi.io/api/v1/plugins/bit-bi/sql-changes",
        params={
            "siteId": site_id,
            "since": last_poll_time.isoformat() + "Z"
        },
        headers={"X-Plugin-Api-Key": api_key}
    )

    if response.status_code == 200:
        sql_content = response.text
        if sql_content:
            process_sql_changes(sql_content)
        return datetime.utcnow()

    return last_poll_time

# Poll every 5 minutes
last_poll = datetime.utcnow() - timedelta(days=7)
while True:
    last_poll = poll_sql_changes(site_id, api_key, last_poll)
    time.sleep(300)  # 5 minutes
```

### 4. Parse SQL Statement Delimiters

```python
import re

def parse_sql_statements(sql_content):
    """Parse SQL content into individual statements with metadata."""

    pattern = r'(INSERT|UPDATE|DELETE).*?--- END OF COMMAND "([^"]+):(\d+)" ---'

    statements = []
    for match in re.finditer(pattern, sql_content, re.DOTALL):
        statements.append({
            "sql": match.group(0).split("--- END OF COMMAND")[0].strip(),
            "source_file": match.group(2),
            "line_number": int(match.group(3)),
            "type": match.group(1)
        })

    return statements
```

### 5. Handle Rate Limits

```python
import time

def call_with_retry(url, headers, max_retries=3):
    for attempt in range(max_retries):
        response = requests.get(url, headers=headers)

        if response.status_code == 429:
            retry_after = int(response.headers.get("Retry-After", 60))
            time.sleep(retry_after)
            continue

        return response

    raise Exception("Max retries exceeded")
```

### 6. Cache Site List

```python
from functools import lru_cache
from datetime import datetime, timedelta

class SiteCache:
    def __init__(self, api_key, cache_ttl_minutes=60):
        self.api_key = api_key
        self.cache_ttl = timedelta(minutes=cache_ttl_minutes)
        self._cache = None
        self._cache_time = None

    def get_sites(self):
        if self._cache and datetime.utcnow() - self._cache_time < self.cache_ttl:
            return self._cache

        response = requests.get(
            "https://dev.dfm.bitbi.io/api/v1/plugins/bit-bi/sites",
            headers={"X-Plugin-Api-Key": self.api_key}
        )

        if response.status_code == 200:
            self._cache = response.json()["sites"]
            self._cache_time = datetime.utcnow()

        return self._cache
```

---

## OpenAPI Specification

The complete OpenAPI 3.0 specification for the Bit BI Plugin API is available at:

- **Swagger UI**: `https://dev.dfm.bitbi.io/swagger-ui.html`
- **OpenAPI JSON**: `https://dev.dfm.bitbi.io/v3/api-docs`
- **OpenAPI YAML**: `https://dev.dfm.bitbi.io/v3/api-docs.yaml`

### Specification (YAML)

```yaml
openapi: 3.0.3
info:
  title: Bit BI Plugin API
  description: |
    API for Bit BI plugin integration with Data Forge Middleware.

    ## Authentication

    Two authentication methods are supported:

    1. **OAuth2** - For plugin activation/deactivation (Authorization: Bearer token)
    2. **Plugin API Key** - For data access (X-Plugin-Api-Key header)

    ## Rate Limiting

    Plugin API endpoints are rate-limited. When exceeded, responses include:
    - Status: 429 Too Many Requests
    - Header: Retry-After (seconds to wait)
  version: 1.0.0
  contact:
    name: Data Forge Team
    email: support@dataforge.com

servers:
  - url: https://dev.dfm.bitbi.io
    description: Development environment
  - url: https://dfm.bitbi.io
    description: Production environment

tags:
  - name: Plugin Activation
    description: OAuth2-authenticated endpoints for plugin management
  - name: Plugin API
    description: API Key-authenticated endpoints for data access

security:
  - oauth2: []
  - PluginApiKey: []

paths:
  /api/v1/plugins/{pluginId}/activate:
    post:
      tags:
        - Plugin Activation
      summary: Activate a plugin
      description: |
        Activates a plugin for the authenticated account.

        **Upsert Behavior:**
        - If plugin is not activated: Creates new activation record (201)
        - If plugin is already active: merges pluginData only (200, apiKey null) — no new
          API key, no change to the SQL baselines
        - If plugin was deactivated: Reactivates with new pluginData and a new API key (200)

        **Important:** The API Key is returned only in this response. Store it securely.
      operationId: activatePlugin
      security:
        - oauth2: []
      parameters:
        - name: pluginId
          in: path
          required: true
          description: Plugin identifier
          schema:
            type: string
            pattern: '^[a-z0-9-]+$'
            minLength: 1
            maxLength: 64
            example: bit-bi
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ActivatePluginRequest'
            examples:
              bitbi:
                summary: Bit BI activation
                value:
                  pluginData:
                    tenantId: "bit-bi-tenant-acme"
      responses:
        '201':
          description: Plugin activated successfully (new activation)
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/PluginActivationResponse'
        '200':
          description: Plugin updated successfully (existing activation)
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/PluginActivationResponse'
        '400':
          description: Validation error (invalid plugin data schema)
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
        '401':
          description: Not authenticated
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
        '403':
          description: Account not found for JWT accountId claim
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
        '404':
          description: Plugin not found or not enabled
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'

  /api/v1/plugins/{pluginId}/deactivate:
    delete:
      tags:
        - Plugin Activation
      summary: Deactivate a plugin
      description: |
        Deactivates a plugin for the authenticated account.

        **Behavior:**
        - Sets is_active=false and records deactivated_at timestamp
        - Plugin will no longer receive events for this account
        - Activation record is preserved for audit purposes
        - API Key is no longer accepted: requests with it answer 401
      operationId: deactivatePlugin
      security:
        - oauth2: []
      parameters:
        - name: pluginId
          in: path
          required: true
          description: Plugin identifier
          schema:
            type: string
            pattern: '^[a-z0-9-]+$'
            minLength: 1
            maxLength: 64
            example: bit-bi
      responses:
        '204':
          description: Plugin deactivated successfully
        '401':
          description: Not authenticated
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
        '403':
          description: Plugin not activated for this account
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
        '404':
          description: Plugin not found
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'

  /api/v1/plugins/bit-bi/sites:
    get:
      tags:
        - Plugin API
      summary: List available sites
      description: Returns a list of all sites belonging to the account associated with the API key
      operationId: listSites
      security:
        - PluginApiKey: []
      responses:
        '200':
          description: Sites retrieved successfully
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/SiteListResponse'
              example:
                sites:
                  - id: "550e8400-e29b-41d4-a716-446655440000"
                    domain: "store.example.com"
                    displayName: "Example Store"
                  - id: "660e8400-e29b-41d4-a716-446655440001"
                    domain: "warehouse.example.com"
                    displayName: "Warehouse System"
        '401':
          description: Invalid or missing API key
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
        '429':
          description: Rate limit exceeded
          headers:
            Retry-After:
              description: Seconds to wait before retrying
              schema:
                type: integer
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'

  /api/v1/plugins/bit-bi/sql-changes:
    get:
      tags:
        - Plugin API
      summary: Retrieve SQL changes for a site
      description: |
        Returns all SQL changes (INSERT, UPDATE, DELETE statements) for a specific site after a given date.

        **Response Format:**
        - Content-Type: text/plain
        - Each statement ends with: `--- END OF COMMAND "{filename}:{line_number}" ---`
        - Statements are ordered chronologically
        - Empty body if no changes exist after the specified date
      operationId: getSqlChanges
      security:
        - PluginApiKey: []
      parameters:
        - name: siteId
          in: query
          required: true
          description: UUID of the site to retrieve changes for
          schema:
            type: string
            format: uuid
          example: "550e8400-e29b-41d4-a716-446655440000"
        - name: since
          in: query
          required: true
          description: ISO8601 timestamp. Returns changes after this date.
          schema:
            type: string
            format: date-time
          example: "2025-01-01T00:00:00Z"
      responses:
        '200':
          description: SQL changes retrieved successfully
          content:
            text/plain:
              schema:
                type: string
              example: |
                -- SQL generated from batch: 2025-01-15T10:30:00Z
                -- Source: customers.csv

                INSERT INTO customers (id, name, email)
                VALUES ('cust-001', 'John Doe', 'john@example.com');
                --- END OF COMMAND "customers.csv:2" ---
        '400':
          description: Invalid request parameters (e.g., invalid date format)
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
        '401':
          description: Invalid or missing API key
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
        '403':
          description: Site does not belong to account
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
        '429':
          description: Rate limit exceeded
          headers:
            Retry-After:
              description: Seconds to wait before retrying
              schema:
                type: integer
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'

  /api/v1/account/plugins:
    get:
      tags:
        - Plugin Activation
      summary: List account plugin integrations
      description: Returns a paginated list of plugin integrations for the authenticated account
      operationId: listAccountPlugins
      security:
        - oauth2: []
      parameters:
        - name: page
          in: query
          description: Page number (0-indexed)
          schema:
            type: integer
            minimum: 0
            default: 0
        - name: size
          in: query
          description: Page size
          schema:
            type: integer
            minimum: 1
            maximum: 100
            default: 20
        - name: includeInactive
          in: query
          description: Include deactivated plugins in results
          schema:
            type: boolean
            default: false
      responses:
        '200':
          description: List of plugin integrations
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/AccountPluginListResponse'
        '401':
          description: Not authenticated
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'

components:
  securitySchemes:
    oauth2:
      type: http
      scheme: bearer
      bearerFormat: JWT
      description: |
        OAuth2 JWT token from Auth0.

        Required claims:
        - `https://dev.dfm.bitbi.io/accountId` - Account UUID
        - `https://dev.dfm.bitbi.io/roles` - User roles (ROLE_USER)
    PluginApiKey:
      type: apiKey
      in: header
      name: X-Plugin-Api-Key
      description: |
        Plugin API Key obtained during plugin activation.

        Format: `plk_` followed by 32 alphanumeric characters.
        Example: `plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6`

  schemas:
    ActivatePluginRequest:
      type: object
      required:
        - pluginData
      properties:
        pluginData:
          type: object
          description: Plugin-specific data (validated against plugin's JSON Schema)
          additionalProperties: true
          example:
            tenantId: "bit-bi-tenant-acme"

    BitBiPluginData:
      type: object
      description: Plugin data schema for Bit BI
      required:
        - tenantId
      properties:
        tenantId:
          type: string
          description: Bit BI tenant identifier
          minLength: 1
          maxLength: 64
          pattern: '^[a-zA-Z0-9-_]+$'
          example: "bit-bi-tenant-acme"

    PluginActivationResponse:
      type: object
      properties:
        pluginId:
          type: string
          description: Plugin identifier
          example: "bit-bi"
        pluginName:
          type: string
          description: Human-readable plugin name
          example: "Bit BI"
        accountId:
          type: string
          format: uuid
          description: Account UUID
          example: "550e8400-e29b-41d4-a716-446655440000"
        isActive:
          type: boolean
          description: Whether the plugin is currently active
          example: true
        activatedAt:
          type: string
          format: date-time
          description: First activation timestamp
          example: "2025-01-15T10:30:00Z"
        apiKey:
          type: string
          description: |
            Plugin API Key for data access.
            **Important:** This is returned only once. Store it securely.
          pattern: '^plk_[a-zA-Z0-9]{32}$'
          example: "plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6"
        lastUsedAt:
          type: string
          format: date-time
          nullable: true
          description: Last event received timestamp
          example: "2025-01-20T14:00:00Z"

    SiteListResponse:
      type: object
      properties:
        sites:
          type: array
          description: List of sites belonging to the account
          items:
            $ref: '#/components/schemas/Site'

    Site:
      type: object
      properties:
        id:
          type: string
          format: uuid
          description: Unique site identifier
          example: "550e8400-e29b-41d4-a716-446655440000"
        domain:
          type: string
          description: Site domain name
          example: "store.example.com"
        displayName:
          type: string
          description: Human-readable site name
          example: "Example Store"

    AccountPluginListResponse:
      type: object
      properties:
        content:
          type: array
          items:
            $ref: '#/components/schemas/AccountPluginSummary'
        page:
          type: integer
          description: Current page number (0-indexed)
          example: 0
        size:
          type: integer
          description: Page size
          example: 20
        totalElements:
          type: integer
          format: int64
          description: Total number of elements
          example: 1
        totalPages:
          type: integer
          description: Total number of pages
          example: 1

    AccountPluginSummary:
      type: object
      properties:
        pluginId:
          type: string
          description: Plugin identifier
          example: "bit-bi"
        pluginName:
          type: string
          description: Human-readable plugin name
          example: "Bit BI"
        isActive:
          type: boolean
          description: Whether the plugin is currently active
          example: true
        activatedAt:
          type: string
          format: date-time
          description: Activation timestamp
          example: "2025-01-15T10:30:00Z"
        deactivatedAt:
          type: string
          format: date-time
          nullable: true
          description: Deactivation timestamp (if deactivated)
          example: null
        lastUsedAt:
          type: string
          format: date-time
          nullable: true
          description: Last event received timestamp
          example: "2025-01-20T14:00:00Z"

    ErrorResponse:
      type: object
      properties:
        timestamp:
          type: string
          format: date-time
          description: Error occurrence timestamp
          example: "2025-01-15T10:30:00Z"
        status:
          type: integer
          description: HTTP status code
          example: 400
        error:
          type: string
          description: HTTP status phrase
          example: "Bad Request"
        message:
          type: string
          description: Detailed error message
          example: "Invalid date format. Expected ISO8601: 2025-01-15T00:00:00Z"
        path:
          type: string
          description: Request path
          example: "/api/v1/plugins/bit-bi/sql-changes"
```

---

## Changelog

### v1.0.0 (2025-12-23)
- Initial documentation release
- Covers PRD-013 (Plugin System) and PRD-001 (SQL Generation)
- Added OpenAPI 3.0 specification
