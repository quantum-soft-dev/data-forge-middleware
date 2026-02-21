# Postgres CDC Client Integration Guide

**Document Version**: 1.0.0
**Last Updated**: 2026-02-21
**Audience**: Developers integrating PostgreSQL CDC sources with Data Forge Middleware

## Table of Contents

1. [Overview](#overview)
2. [Full Lifecycle](#full-lifecycle)
3. [Step 1: Device Authorization](#step-1-device-authorization)
4. [Step 2: Submit Table Schema](#step-2-submit-table-schema)
5. [Step 3: Initial Load (CSV)](#step-3-initial-load-csv)
6. [Step 4: Delta Uploads (JSONL)](#step-4-delta-uploads-jsonl)
7. [Step 5: Schema Updates](#step-5-schema-updates)
8. [API Reference](#api-reference)
9. [JSONL Format Specification](#jsonl-format-specification)
10. [Schema JSON Specification](#schema-json-specification)
11. [Error Handling](#error-handling)
12. [Complete Example (Python)](#complete-example-python)
13. [Troubleshooting](#troubleshooting)

---

## Overview

Postgres CDC sites use **Change Data Capture** to stream incremental changes from PostgreSQL to Data Forge Middleware. Unlike DBF sites (which upload full CSV snapshots each batch), CDC sites:

1. Submit table schemas (columns, primary keys, unique keys)
2. Perform a one-time **initial full load** as CSV
3. Send subsequent batches as **JSONL delta files** containing only INSERT, UPDATE, DELETE operations

The server converts JSONL deltas directly into SQL statements using primary key information from the schema.

### Prerequisites

- PostgreSQL 10+ with logical replication enabled (`wal_level = logical`)
- PGoutput logical decoding plugin (built-in since PostgreSQL 10)
- A publication for the tables you want to replicate

---

## Full Lifecycle

```
┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│  1. POST /api/v1/device/authorize                                   │
│     { siteName: "pg-cdc-prod", siteType: "POSTGRES_CDC" }          │
│                         ↓                                           │
│  2. POST /api/v1/device/token  (poll until approved)                │
│                         ↓                                           │
│  3. POST /api/dfc/schema                                            │
│     { tables: { customers: { columns: [...], primaryKey: [...] } }} │
│                         ↓                                           │
│  4. POST /api/dfc/batch/start   →  Upload CSV files  →  /complete   │
│     (initial full load — baseline batch, no SQL generated)          │
│                         ↓                                           │
│  5. POST /api/dfc/batch/start   →  Upload JSONL files →  /complete  │
│     (delta batch — server converts JSONL → SQL)                     │
│                         ↓                                           │
│  6. Repeat step 5 for each CDC batch                                │
│                                                                     │
│  * If table structure changes → POST /api/dfc/schema (update)       │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Step 1: Device Authorization

Identical to the standard Device Flow, but with the `siteType` parameter set to `POSTGRES_CDC`.

### 1.1 Initiate Authorization

```http
POST /api/v1/device/authorize
Content-Type: application/json

{
  "siteName": "pg-cdc-prod",
  "siteDescription": "Production PostgreSQL CDC replication",
  "siteType": "POSTGRES_CDC"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `siteName` | string | Yes | Unique site identifier (max 255 chars) |
| `siteDescription` | string | No | Human-readable description (max 500 chars) |
| `siteType` | string | No | `POSTGRES_CDC` for CDC sites. Defaults to `DBF` if omitted |

**Response (200 OK):**
```json
{
  "deviceCode": "GmRhmhcxhwAzkoEqiMEg_DnyEysNkuNhszIySk9eS",
  "userCode": "WDJB-MJHT",
  "verificationUri": "https://app.dataforge.com/device-verify",
  "verificationUriComplete": "https://app.dataforge.com/device-verify?code=WDJB-MJHT",
  "expiresIn": 900,
  "interval": 5
}
```

### 1.2 Poll for Credentials

```http
POST /api/v1/device/token
Content-Type: application/json

{
  "deviceCode": "GmRhmhcxhwAzkoEqiMEg_DnyEysNkuNhszIySk9eS"
}
```

**Success Response (200 OK):**
```json
{
  "siteId": "550e8400-e29b-41d4-a716-446655440000",
  "siteName": "pg-cdc-prod",
  "accessToken": "eyJhbGci...",
  "refreshToken": "dGhpcyBpcyBhIHJlZnJlc2g...",
  "accessTokenExpiresAt": "2026-02-21T11:35:00Z",
  "refreshTokenExpiresAt": "2026-05-22T10:35:00Z",
  "apiBaseUrl": "https://api.dataforge.com"
}
```

See [Device Flow Client Guide](./device-flow-client-guide.md) for full polling logic and error handling.

### 1.3 Use the Access Token

The `accessToken` received in step 1.2 is used directly for all subsequent API calls:

```
Authorization: Bearer {accessToken}
```

Access tokens are valid for ~1 hour. When `accessTokenExpiresAt` is reached or you receive a `401 Unauthorized`, obtain a new token:

```http
POST /api/v1/device/auth/refresh
Content-Type: application/json

{
  "refreshToken": "dGhpcyBpcyBhIHJlZnJlc2g..."
}
```

**Response (200 OK):**
```json
{
  "accessToken": "eyJhbGci...",
  "refreshToken": "bmV3UmVmcmVzaFRva2Vu...",
  "accessTokenExpiresAt": "2026-02-21T12:35:00Z",
  "refreshTokenExpiresAt": "2026-05-22T10:35:00Z"
}
```

---

## Step 2: Submit Table Schema

**Before the first batch**, submit the structure of all tables that will be replicated. The server rejects batch uploads for CDC sites until a schema is provided.

```http
POST /api/dfc/schema
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "tables": {
    "customers": {
      "columns": [
        {"name": "id", "type": "integer", "nullable": false},
        {"name": "name", "type": "varchar(255)", "nullable": false},
        {"name": "email", "type": "varchar(255)", "nullable": true},
        {"name": "created_at", "type": "timestamp", "nullable": false}
      ],
      "primaryKey": ["id"],
      "uniqueKeys": [
        {"name": "uk_customers_email", "columns": ["email"]}
      ]
    },
    "orders": {
      "columns": [
        {"name": "id", "type": "integer", "nullable": false},
        {"name": "customer_id", "type": "integer", "nullable": false},
        {"name": "total", "type": "numeric(10,2)", "nullable": false},
        {"name": "status", "type": "varchar(20)", "nullable": false}
      ],
      "primaryKey": ["id"],
      "uniqueKeys": []
    }
  }
}
```

**Response (200 OK / 201 Created):**
```json
{
  "siteId": "550e8400-e29b-41d4-a716-446655440000",
  "schemaVersion": 1,
  "updatedAt": "2026-02-21T10:30:00Z"
}
```

### Schema Rules

- Each table **must** have at least one column
- `primaryKey` columns **must** exist in the column list
- `uniqueKeys` columns **must** exist in the column list
- Table and column names must match: `^[a-zA-Z_][a-zA-Z0-9_]{0,62}$`
- Schema persists until you send an updated version

See [Schema JSON Specification](#schema-json-specification) for full format details.

---

## Step 3: Initial Load (CSV)

The first batch uploads a **full snapshot** of all tables as CSV files. This becomes the baseline — no SQL is generated for this batch.

### 3.1 Start Batch

```http
POST /api/dfc/batch/start
Authorization: Bearer {accessToken}
```

**Response (201 Created):**
```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "siteId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "IN_PROGRESS",
  "createdAt": "2026-02-21T10:35:00Z",
  "updatedAt": "2026-02-21T10:35:00Z"
}
```

### 3.2 Upload CSV Files

Upload one CSV file per table. Filename determines table name (e.g., `customers.csv` → table `customers`).

```http
POST /api/dfc/batch/{batchId}/upload
Authorization: Bearer {accessToken}
Content-Type: multipart/form-data

files: customers.csv.gz
files: orders.csv.gz
```

CSV format requirements:
- First row = column headers (must match schema column names)
- Gzip compression supported (`.csv.gz`)
- Max file size: 500 MB per file
- UTF-8 encoding

**Response (200 OK):**
```json
{
  "status": "OK",
  "uploadedFiles": 2,
  "files": [
    {"fileName": "customers.csv.gz", "fileSize": 1234567, "uploadedAt": "2026-02-21T10:36:00"},
    {"fileName": "orders.csv.gz", "fileSize": 2345678, "uploadedAt": "2026-02-21T10:36:01"}
  ]
}
```

### 3.3 Complete Batch

```http
POST /api/dfc/batch/{batchId}/complete
Authorization: Bearer {accessToken}
```

**Response (200 OK):**
```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "COMPLETED",
  "completedAt": "2026-02-21T10:37:00Z"
}
```

> **Note:** The baseline batch does not trigger SQL generation. The Bit BI plugin downloads CSV files directly for initialization.

---

## Step 4: Delta Uploads (JSONL)

After the initial load, all subsequent batches contain **JSONL delta files** — one `.jsonl.gz` file per table with INSERT, UPDATE, DELETE operations.

### 4.1 Start Batch

```http
POST /api/dfc/batch/start
Authorization: Bearer {accessToken}
```

### 4.2 Upload JSONL Delta Files

```http
POST /api/dfc/batch/{batchId}/upload
Authorization: Bearer {accessToken}
Content-Type: multipart/form-data

files: customers.jsonl.gz
files: orders.jsonl.gz
```

Each `.jsonl.gz` file contains one JSON object per line:

```jsonl
{"op":"I","d":{"id":101,"name":"Alice","email":"alice@example.com","created_at":"2026-02-21T10:00:00"}}
{"op":"U","k":{"id":42},"d":{"email":"updated@example.com"}}
{"op":"U","k":{"id":55},"d":{"name":"Robert","email":"robert@new.com"}}
{"op":"D","k":{"id":7}}
{"op":"I","d":{"id":102,"name":"Bob","email":"bob@example.com","created_at":"2026-02-21T10:05:00"}}
```

### 4.3 Complete Batch

```http
POST /api/dfc/batch/{batchId}/complete
Authorization: Bearer {accessToken}
```

Upon completion, the server:
1. Parses each `.jsonl.gz` file
2. Looks up table schema (columns, PK) for each table
3. Generates SQL statements using PK in WHERE clauses
4. Stores SQL file in S3 for the Bit BI plugin

### Generated SQL Example

For the JSONL above, the server generates:

```sql
INSERT INTO customers (id, name, email, created_at) VALUES (101, 'Alice', 'alice@example.com', '2026-02-21T10:00:00');
--- END OF COMMAND "customers.jsonl:1" ---
UPDATE customers SET email = 'updated@example.com' WHERE id = 42;
--- END OF COMMAND "customers.jsonl:2" ---
UPDATE customers SET name = 'Robert', email = 'robert@new.com' WHERE id = 55;
--- END OF COMMAND "customers.jsonl:3" ---
DELETE FROM customers WHERE id = 7;
--- END OF COMMAND "customers.jsonl:4" ---
INSERT INTO customers (id, name, email, created_at) VALUES (102, 'Bob', 'bob@example.com', '2026-02-21T10:05:00');
--- END OF COMMAND "customers.jsonl:5" ---
```

---

## Step 5: Schema Updates

When a table structure changes in PostgreSQL (column added, removed, type changed), submit the updated schema **before** the next batch that reflects the change.

```http
POST /api/dfc/schema
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "tables": {
    "customers": {
      "columns": [
        {"name": "id", "type": "integer", "nullable": false},
        {"name": "name", "type": "varchar(255)", "nullable": false},
        {"name": "email", "type": "varchar(255)", "nullable": true},
        {"name": "phone", "type": "varchar(20)", "nullable": true},
        {"name": "created_at", "type": "timestamp", "nullable": false}
      ],
      "primaryKey": ["id"],
      "uniqueKeys": [
        {"name": "uk_customers_email", "columns": ["email"]}
      ]
    },
    "orders": {
      "columns": [
        {"name": "id", "type": "integer", "nullable": false},
        {"name": "customer_id", "type": "integer", "nullable": false},
        {"name": "total", "type": "numeric(10,2)", "nullable": false},
        {"name": "status", "type": "varchar(20)", "nullable": false}
      ],
      "primaryKey": ["id"],
      "uniqueKeys": []
    }
  }
}
```

**Response (200 OK):**
```json
{
  "siteId": "550e8400-e29b-41d4-a716-446655440000",
  "schemaVersion": 2,
  "updatedAt": "2026-02-21T14:00:00Z"
}
```

> **Important:** Always send the **full schema** (all tables), not just the changed table. The schema is replaced entirely on each update.

---

## API Reference

### Client API Endpoints (JWT Authentication)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/dfc/schema` | Submit/update table schema |
| `POST` | `/api/dfc/batch/start` | Start new batch |
| `POST` | `/api/dfc/batch/{id}/upload` | Upload files to batch |
| `POST` | `/api/dfc/batch/{id}/complete` | Complete batch |
| `POST` | `/api/dfc/batch/{id}/complete-with-warnings` | Complete with warnings |
| `POST` | `/api/dfc/batch/{id}/fail` | Mark batch as failed |
| `POST` | `/api/dfc/batch/{id}/cancel` | Cancel batch |
| `GET`  | `/api/dfc/batch/{id}` | Get batch status |
| `POST` | `/api/dfc/error` | Report client error |

### Device Flow Endpoints (No Authentication)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/device/authorize` | Initiate device authorization |
| `POST` | `/api/v1/device/token` | Poll for credentials |
| `POST` | `/api/v1/device/auth/refresh` | Refresh access token |

---

## JSONL Format Specification

### File Naming

- One file per table: `{table_name}.jsonl` or `{table_name}.jsonl.gz`
- Table name derived from filename: `customers.jsonl.gz` → table `customers`
- Gzip compression recommended for production

### Operations

Each line is a JSON object with the following structure:

#### INSERT (`op: "I"`)

Inserts a new row. `d` contains all column values.

```json
{"op":"I","d":{"id":1,"name":"John","email":"john@example.com","age":30}}
```

#### UPDATE (`op: "U"`)

Updates an existing row. `k` identifies the row by primary key. `d` contains **only changed columns**.

```json
{"op":"U","k":{"id":2},"d":{"name":"Jane Updated","email":"jane@new.com"}}
```

For composite primary keys:
```json
{"op":"U","k":{"order_id":100,"product_id":5},"d":{"quantity":3}}
```

#### DELETE (`op: "D"`)

Deletes a row. `k` identifies the row by primary key.

```json
{"op":"D","k":{"id":3}}
```

### Field Reference

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `op` | string | Yes | Operation: `"I"` (insert), `"U"` (update), `"D"` (delete) |
| `k` | object | U, D | Primary key values for row identification |
| `d` | object | I, U | Data: full row for INSERT, changed fields only for UPDATE |

### Value Types

| JSON Type | SQL Output | Example |
|-----------|-----------|---------|
| `"string"` | `'string'` (quoted, escaped) | `"name":"John"` → `'John'` |
| `123` | `123` (no quotes) | `"age":30` → `30` |
| `12.50` | `12.50` (no quotes) | `"total":12.50` → `12.50` |
| `true`/`false` | `true`/`false` | `"active":true` → `true` |
| `null` | `NULL` | `"phone":null` → `NULL` |
| _(absent)_ | _(not included in SQL)_ | Field not changed (UPDATE only) |

### Rules

1. **INSERT** must include all non-nullable columns
2. **UPDATE** `k` must contain all primary key columns from schema
3. **UPDATE** `d` must contain at least one changed column
4. **DELETE** `k` must contain all primary key columns from schema
5. Column names in `d` and `k` must match schema column names
6. Unknown columns (not in schema) are skipped with a warning
7. Malformed JSON lines are skipped with a warning; processing continues

---

## Schema JSON Specification

### Top-Level Structure

```json
{
  "tables": {
    "{table_name}": {
      "columns": [...],
      "primaryKey": [...],
      "uniqueKeys": [...]
    }
  }
}
```

### Column Definition

```json
{
  "name": "column_name",
  "type": "postgresql_type",
  "nullable": true
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | Yes | Column name (matches `^[a-zA-Z_][a-zA-Z0-9_]{0,62}$`) |
| `type` | string | Yes | PostgreSQL data type (e.g., `integer`, `varchar(255)`, `timestamp`, `numeric(10,2)`, `boolean`, `text`, `uuid`, `date`, `jsonb`) |
| `nullable` | boolean | Yes | Whether the column allows NULL values |

### Primary Key

```json
"primaryKey": ["id"]
```

- Array of column names forming the primary key
- Columns must exist in the `columns` list
- Supports composite keys: `["order_id", "product_id"]`
- **Required** — every table must have a primary key

### Unique Keys

```json
"uniqueKeys": [
  {"name": "uk_customers_email", "columns": ["email"]},
  {"name": "uk_orders_ref", "columns": ["account_id", "reference_number"]}
]
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | Yes | Constraint name |
| `columns` | array | Yes | Column names forming the unique key |

- May be empty array `[]` if no unique constraints exist
- Supports composite unique keys

### Complete Example

```json
{
  "tables": {
    "customers": {
      "columns": [
        {"name": "id", "type": "integer", "nullable": false},
        {"name": "name", "type": "varchar(255)", "nullable": false},
        {"name": "email", "type": "varchar(255)", "nullable": true},
        {"name": "balance", "type": "numeric(12,2)", "nullable": false},
        {"name": "is_active", "type": "boolean", "nullable": false},
        {"name": "metadata", "type": "jsonb", "nullable": true},
        {"name": "created_at", "type": "timestamp", "nullable": false}
      ],
      "primaryKey": ["id"],
      "uniqueKeys": [
        {"name": "uk_customers_email", "columns": ["email"]}
      ]
    },
    "order_items": {
      "columns": [
        {"name": "order_id", "type": "integer", "nullable": false},
        {"name": "product_id", "type": "integer", "nullable": false},
        {"name": "quantity", "type": "integer", "nullable": false},
        {"name": "unit_price", "type": "numeric(10,2)", "nullable": false}
      ],
      "primaryKey": ["order_id", "product_id"],
      "uniqueKeys": []
    }
  }
}
```

---

## Error Handling

### HTTP Status Codes

| Status | Meaning |
|--------|---------|
| 200 | Success |
| 201 | Created (batch started, schema submitted for first time) |
| 400 | Bad request (validation error, missing schema, wrong file type) |
| 401 | Authentication required or token expired |
| 403 | Forbidden (wrong site, inactive site) |
| 404 | Resource not found |
| 409 | Conflict (active batch already exists for site) |
| 413 | File too large (> 500 MB) |
| 429 | Too many requests / concurrent batch limit |
| 500 | Server error |

### CDC-Specific Errors

| Scenario | Status | Error Message |
|----------|--------|---------------|
| Batch start without schema | 400 | `Schema required for POSTGRES_CDC sites. Submit via POST /api/dfc/schema first.` |
| Invalid schema JSON | 400 | `Invalid schema: {details}` |
| PK column not in columns | 400 | `Primary key column '{name}' not found in table '{table}' columns` |
| Invalid table/column name | 400 | `Table name contains invalid characters: '{name}'` |
| JSONL uploaded to DBF site | 400 | `JSONL files not supported for DBF sites` |
| CSV uploaded as delta batch | 400 | `JSONL files expected for CDC delta batches` |

### Error Response Format

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Schema required for POSTGRES_CDC sites. Submit via POST /api/dfc/schema first."
}
```

---

## Complete Example (Python)

```python
import requests
import gzip
import json
import time
from pathlib import Path

class PostgresCdcClient:
    """Data Forge Middleware client for PostgreSQL CDC replication."""

    def __init__(self, base_url: str):
        self.base_url = base_url
        self.access_token = None
        self.refresh_token = None
        self.site_id = None

    def _headers(self) -> dict:
        return {"Authorization": f"Bearer {self.access_token}"}

    # ── Step 1: Device Authorization ──────────────────────────────

    def authorize(self, site_name: str, description: str = None) -> dict:
        """Initiate device authorization with POSTGRES_CDC type."""
        resp = requests.post(
            f"{self.base_url}/api/v1/device/authorize",
            json={
                "siteName": site_name,
                "siteDescription": description,
                "siteType": "POSTGRES_CDC"
            }
        )
        resp.raise_for_status()
        return resp.json()

    def poll_for_credentials(self, device_code: str, interval: int, expires_in: int) -> dict:
        """Poll until user approves. Returns credentials."""
        end_time = time.time() + expires_in
        while time.time() < end_time:
            resp = requests.post(
                f"{self.base_url}/api/v1/device/token",
                json={"deviceCode": device_code}
            )
            if resp.status_code == 200:
                return resp.json()
            error = resp.json().get("error")
            if error == "authorization_pending":
                time.sleep(interval)
                continue
            raise Exception(f"Authorization failed: {error}")
        raise Exception("Authorization timeout")

    # ── Step 2: Submit Schema ─────────────────────────────────────

    def submit_schema(self, tables: dict):
        """
        Submit table schema. Call before the first batch
        and whenever table structure changes.

        Args:
            tables: Dict of table_name -> {columns, primaryKey, uniqueKeys}
        """
        resp = requests.post(
            f"{self.base_url}/api/dfc/schema",
            headers=self._headers(),
            json={"tables": tables}
        )
        resp.raise_for_status()
        result = resp.json()
        print(f"Schema v{result['schemaVersion']} submitted")
        return result

    # ── Step 3: Initial Load (CSV) ────────────────────────────────

    def upload_initial_load(self, csv_files: dict[str, str]):
        """
        Upload initial full CSV snapshot.

        Args:
            csv_files: Dict of table_name -> file_path
                       e.g. {"customers": "/data/customers.csv.gz"}
        """
        batch_id = self._start_batch()

        files = []
        for table_name, file_path in csv_files.items():
            path = Path(file_path)
            files.append(("files", (path.name, open(path, "rb"))))

        try:
            resp = requests.post(
                f"{self.base_url}/api/dfc/batch/{batch_id}/upload",
                headers=self._headers(),
                files=files
            )
            resp.raise_for_status()
        finally:
            for _, (_, f) in files:
                f.close()

        self._complete_batch(batch_id)
        print(f"Initial load complete: batch={batch_id}")

    # ── Step 4: Delta Upload (JSONL) ──────────────────────────────

    def upload_deltas(self, changes: dict[str, list[dict]]):
        """
        Upload CDC delta batch.

        Args:
            changes: Dict of table_name -> list of operations
                     e.g. {"customers": [
                         {"op": "I", "d": {"id": 1, "name": "John"}},
                         {"op": "U", "k": {"id": 2}, "d": {"name": "Jane"}},
                         {"op": "D", "k": {"id": 3}}
                     ]}
        """
        batch_id = self._start_batch()

        files = []
        for table_name, operations in changes.items():
            jsonl_content = "\n".join(json.dumps(op) for op in operations)
            compressed = gzip.compress(jsonl_content.encode("utf-8"))
            filename = f"{table_name}.jsonl.gz"
            files.append(("files", (filename, compressed, "application/gzip")))

        resp = requests.post(
            f"{self.base_url}/api/dfc/batch/{batch_id}/upload",
            headers=self._headers(),
            files=files
        )
        resp.raise_for_status()

        self._complete_batch(batch_id)
        print(f"Delta batch complete: batch={batch_id}, tables={list(changes.keys())}")

    # ── Helpers ────────────────────────────────────────────────────

    def _start_batch(self) -> str:
        resp = requests.post(
            f"{self.base_url}/api/dfc/batch/start",
            headers=self._headers()
        )
        resp.raise_for_status()
        return resp.json()["id"]

    def _complete_batch(self, batch_id: str):
        resp = requests.post(
            f"{self.base_url}/api/dfc/batch/{batch_id}/complete",
            headers=self._headers()
        )
        resp.raise_for_status()


# ── Usage Example ──────────────────────────────────────────────────

if __name__ == "__main__":
    client = PostgresCdcClient("https://api.dataforge.com")

    # 1. Authorize (one-time)
    auth = client.authorize("pg-cdc-prod", "Production PostgreSQL")
    print(f"Go to: {auth['verificationUri']}")
    print(f"Enter code: {auth['userCode']}")
    creds = client.poll_for_credentials(
        auth["deviceCode"], auth["interval"], auth["expiresIn"]
    )
    client.site_id = creds["siteId"]
    client.access_token = creds["accessToken"]

    # 2. Submit schema
    client.submit_schema({
        "customers": {
            "columns": [
                {"name": "id", "type": "integer", "nullable": False},
                {"name": "name", "type": "varchar(255)", "nullable": False},
                {"name": "email", "type": "varchar(255)", "nullable": True},
            ],
            "primaryKey": ["id"],
            "uniqueKeys": [{"name": "uk_email", "columns": ["email"]}]
        }
    })

    # 3. Initial load
    client.upload_initial_load({
        "customers": "/data/customers.csv.gz"
    })

    # 4. Delta uploads (repeat as changes arrive from CDC)
    client.upload_deltas({
        "customers": [
            {"op": "I", "d": {"id": 101, "name": "Alice", "email": "alice@ex.com"}},
            {"op": "U", "k": {"id": 42}, "d": {"email": "updated@ex.com"}},
            {"op": "D", "k": {"id": 7}},
        ]
    })
```

---

## Troubleshooting

| Problem | Cause | Solution |
|---------|-------|----------|
| `400` "Schema required" on batch start | No schema submitted yet | Call `POST /api/dfc/schema` first |
| `400` "JSONL files expected for CDC delta batches" | Uploading CSV to a non-baseline batch | Use `.jsonl.gz` files for delta batches |
| `400` "Primary key column not found" | PK column name doesn't match `columns` list | Verify `primaryKey` entries exist in `columns` |
| `400` "Table name contains invalid characters" | Table name has spaces, dots, or special chars | Use only `[a-zA-Z_][a-zA-Z0-9_]*` |
| Missing operations in generated SQL | Malformed JSONL line skipped | Check server logs for JSONL parse warnings |
| `409` "Site already has an active batch" | Previous batch not completed/cancelled | Complete or cancel the active batch first |
| `413` "File too large" | File exceeds 500 MB | Split data into smaller files |
| `401` on any request | Access token expired | Refresh via `POST /api/v1/device/auth/refresh` |

### Extracting Schema from PostgreSQL

Use this query to generate schema JSON from your PostgreSQL database:

```sql
SELECT
    t.table_name,
    json_agg(
        json_build_object(
            'name', c.column_name,
            'type', c.data_type ||
                CASE
                    WHEN c.character_maximum_length IS NOT NULL
                    THEN '(' || c.character_maximum_length || ')'
                    WHEN c.numeric_precision IS NOT NULL AND c.data_type = 'numeric'
                    THEN '(' || c.numeric_precision || ',' || c.numeric_scale || ')'
                    ELSE ''
                END,
            'nullable', c.is_nullable = 'YES'
        ) ORDER BY c.ordinal_position
    ) AS columns
FROM information_schema.tables t
JOIN information_schema.columns c
    ON c.table_schema = t.table_schema AND c.table_name = t.table_name
WHERE t.table_schema = 'public'
    AND t.table_type = 'BASE TABLE'
GROUP BY t.table_name;
```

Use this query to extract primary keys:

```sql
SELECT
    tc.table_name,
    json_agg(kcu.column_name ORDER BY kcu.ordinal_position) AS primary_key
FROM information_schema.table_constraints tc
JOIN information_schema.key_column_usage kcu
    ON tc.constraint_name = kcu.constraint_name
    AND tc.table_schema = kcu.table_schema
WHERE tc.constraint_type = 'PRIMARY KEY'
    AND tc.table_schema = 'public'
GROUP BY tc.table_name;
```

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0.0 | 2026-02-21 | Initial release — Postgres CDC support |
