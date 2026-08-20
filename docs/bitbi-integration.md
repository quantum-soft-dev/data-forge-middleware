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
7. [SQL Generation Feature](#sql-generation-feature)
8. [API Reference](#api-reference)
9. [Error Handling](#error-handling)
10. [Integration Nuances](#integration-nuances)
11. [Best Practices](#best-practices)
12. [OpenAPI Specification](#openapi-specification)

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

**Response (200 OK - Update Existing)**:
```json
{
  "pluginId": "bit-bi",
  "pluginName": "Bit BI",
  "accountId": "550e8400-e29b-41d4-a716-446655440000",
  "isActive": true,
  "activatedAt": "2025-01-10T08:00:00Z",
  "apiKey": "plk_x9Y8w7V6u5T4s3R2q1P0o9N8m7L6k5J4",
  "lastUsedAt": "2025-01-14T15:45:00Z"
}
```

> **Important**: Store the `apiKey` securely. It is returned **only for a new activation or a
> reactivation** and cannot be retrieved later. Re-posting `activate` for an already-active
> plugin updates the configuration and returns no key. If the key is lost, rotate it —
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
client to have submitted a schema for it. A site that never ingested through Delta answers with its
historical uploaded files instead, in their original formats.

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

On the DBF/CSV path the question does not arise today: `DbfSqlGenerationStrategy` calls the generator
with an **empty** column-type map, so every cell is treated as `Character` and is quoted and escaped
whatever it contains. That also means the per-type table above describes a mapping nothing currently
supplies — see issue #263. The quoting rule is on the generator's contract for the day a caller
passes real types, not a fix for an observed case.

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
  "apiKey": "string (plk_...)",
  "lastUsedAt": "iso8601 | null"
}
```

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
| 429 | Too Many Requests | Rate limit exceeded |
| 500 | Server Error | Internal error (contact support) |

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
  invalidates the old one immediately. Re-activating an already active plugin also mints a new
  key, but does **not** return it — use the rotation endpoint instead.
- **Deactivation**: API Key remains but returns 403 on all requests

### 2. Upsert Behavior

Plugin activation uses **upsert** semantics:
- First activation: Creates new record, returns 201
- Subsequent activations: Updates existing record, returns 200
- Re-activation after deactivation: Sets `is_active=true`, returns 200

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
        - If plugin is already active: Updates pluginData and timestamps (200)
        - If plugin was deactivated: Reactivates with new pluginData (200)

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
        - API Key remains but will return 403 on all requests
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
