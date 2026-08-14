# API Contract: Parquet Export Plugin

Base path: `/api/v1/plugins/parquet-export`

## 1. Activation (existing owner API, Auth0 OAuth2)

### POST `/api/v1/plugins/parquet-export/activate`

Handled by existing `PluginController.activate` (`POST /api/v1/plugins/{pluginId}/activate`). Request body: `{ "pluginData": {} }` (no required config; JSON Schema accepts empty object).

**201 Created** (new activation) / **200 OK** (reactivation):

```json
{
  "pluginId": "parquet-export",
  "displayName": "Parquet Export",
  "active": true,
  "apiKey": "pex_Ab3xY9Qm2Lk4:Kf82jdOq81mZnW4xTr5vBc7yLh0aPe3s"
}
```

`apiKey` carries `login:password` — shown **exactly once** (new activation or reactivation). Idempotent re-activation of an already-active plugin returns no credentials.

### POST `/api/v1/account/plugins/parquet-export/rotate-password` (Auth0, owner)

**200 OK**: `{ "login": "pex_Ab3xY9Qm2Lk4", "password": "…new raw…" }` — password shown once; old password invalid immediately.
**404**: plugin not active for account.

## 2. File listing (HTTP Basic Auth)

### GET `/files`

Headers: `Authorization: Basic base64(login:password)`

Query parameters:

| Param | Type | Default | Notes |
|---|---|---|---|
| `since` | ISO 8601 datetime (offset accepted, normalized to UTC) | epoch | strictly-greater filter on `producedAt` |
| `siteId` | UUID | — | must belong to the account (else empty result) |
| `table` | string | — | table name filter |
| `type` | `batch` \| `delta` \| `checkpoint` | `batch` | breaking change in 041: omitted type is batch-only |
| `cursor` | opaque string | — | previous response's `nextCursor` (keyset over `(producedAt, s3Key)`) |
| `size` | int 1..100 | 50 | |

**200 OK**:

```json
{
  "files": [
    {
      "siteId": "0195…", "siteDomain": "shop.example.com",
      "table": "orders", "type": "delta",
      "firstSeq": 100, "lastSeq": 250, "seq": null,
      "producedAt": "2026-07-27T10:15:00",
      "fileName": "orders_seq100-250.parquet",
      "downloadUrl": "https://host/api/v1/plugins/parquet-export/download/vN3…43chars…Qw",
      "linkExpiresAt": "2026-07-27T11:15:00"
    },
    {
      "siteId": "0195…", "siteDomain": "shop.example.com",
      "table": "orders", "type": "checkpoint",
      "firstSeq": null, "lastSeq": null, "seq": 250,
      "producedAt": "2026-07-27T10:20:00",
      "fileName": "orders_seq250.parquet",
      "downloadUrl": "https://host/api/v1/plugins/parquet-export/download/aB9…Xz",
      "linkExpiresAt": "2026-07-27T11:15:00"
    }
  ],
  "size": 50, "hasMore": false, "nextCursor": null
}
```

Side effect: one `download_links` row registered per returned file.

Pagination contract:

- keyset cursor, NOT offset paging: continue a sweep by passing `nextCursor`; `nextCursor` is
  non-null iff `hasMore` is true;
- a page may contain fewer than `size` entries (even zero) with `hasMore=true` — delta
  candidates dropped by the S3 existence probe still advance the cursor. Clients MUST iterate
  on `hasMore`, never on an empty `files` list;
- `since` may only be advanced between complete sweeps (after `hasMore=false`), to the max
  observed `producedAt` — never between pages of one sweep.

**Errors**:
- **400**: malformed `since` / `siteId` / `type` / `size` out of range.
- **401** + `WWW-Authenticate: Basic realm="parquet-export"`: missing/malformed/wrong credentials, or plugin inactive.
- **429** + `Retry-After: <seconds>`: per-account rate limit exceeded.

## 3. One-time download (anonymous)

### GET `/download/{token}`

No authentication. `{token}`: 43-char URL-safe string.

- **302 Found**: first use, link valid, activation still active. `Location`: S3 presigned URL (~60 s validity). Link atomically marked consumed.
- **410 Gone**: token exists but is consumed, expired, or its activation is deactivated. Body: error JSON with `reason`-free generic message.
- **404 Not Found**: token unknown (or purged).

Concurrency guarantee: for N concurrent requests with the same token, exactly one 302; all others 410.

## 4. Security wiring

- New stateless `SecurityFilterChain`, matcher `/api/v1/plugins/parquet-export/**`, ordered before the `/api/v1/**` catch-all:
  - `/files` → `ParquetExportBasicAuthFilter` (grants `ROLE_PLUGIN_CLIENT`, principal accountId, detail accountPluginId)
  - `/download/**` → `permitAll`
- `adminApiFilterChain`: `requestMatchers("/api/v1/plugins/parquet-export/**").denyAll()` carve-out.
- `TestSecurityConfig` mirrors the chain; `SecurityFilterChainTest` asserts routing.

## 5. Audit events

| Action | When | Metadata |
|---|---|---|
| `ACTIVATE`/`REACTIVATE`/`DEACTIVATE` | existing flow | existing |
| `PASSWORD_ROTATED` | rotation endpoint | — |
| `FILES_LISTED` | each successful `/files` call | filters, fileCount |
| `LINK_CONSUMED` | successful 302 | fileName, s3Key |
| `LINK_REJECTED` | 410/404 on download | reason: consumed/expired/unknown/inactive |
