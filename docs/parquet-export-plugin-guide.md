# Parquet Export Plugin Guide (028)

The `parquet-export` plugin gives an external client pull-based access to the Parquet files
produced by the Delta v2 pipeline: per-segment **delta** files and full **checkpoint** snapshots.
Listing is protected by HTTP Basic Auth; each listed file comes with a **registered one-time
download link** that redirects once to a short-lived S3 presigned URL and then dies.

## Why one-time links

S3 presigned URLs are stateless signatures — S3 cannot enforce single use. The middleware
registers each link as a `download_links` row and consumes it with an atomic
`UPDATE … WHERE consumed_at IS NULL AND expires_at > now()`: under any concurrency exactly one
request wins the 302 redirect; everyone else gets **410 Gone**. The presigned URL minted after a
won consume lives ~60 seconds — the residual exposure window.

## Activation & credentials

1. The account owner activates the plugin through the standard plugin API:

   ```bash
   curl -X POST https://host/api/v1/plugins/parquet-export/activate \
     -H "Authorization: Bearer $AUTH0_TOKEN" -H "Content-Type: application/json" \
     -d '{"pluginData":{}}'
   ```

   The response's `apiKey` field carries the Basic Auth credentials **exactly once**, in
   `login:password` form (e.g. `pex_Ab3xY9Qm2Lk4:Kf82…`). The login is stable; the password is
   stored only as a BCrypt hash and cannot be retrieved again.

2. Re-activating an already-active plugin is idempotent and never re-shows or rotates the
   credentials. Reactivation after a deactivation keeps the same login.

3. Rotation (owner API, Auth0):

   ```bash
   curl -X POST https://host/api/v1/account/plugins/parquet-export/rotate-password \
     -H "Authorization: Bearer $AUTH0_TOKEN"
   ```

   Returns `{login, password}` once; the old password stops authenticating immediately.
   Already-registered links are credential-independent and stay valid.

## Listing files

```
GET /api/v1/plugins/parquet-export/files
Authorization: Basic base64(login:password)
```

| Param | Meaning | Default |
|---|---|---|
| `since` | ISO 8601 datetime (offsets like `Z`/`+02:00` accepted, normalized to UTC); only files **produced after** this moment | epoch |
| `siteId` | limit to one site (must belong to the account) | all sites |
| `table` | table-name filter | all tables |
| `type` | `delta` \| `checkpoint` | both |
| `cursor` | opaque keyset cursor — the previous response's `nextCursor` | start |
| `size` | page size ≤ 100 | 50 |

`producedAt` is the delta segment's `egress_at` or the checkpoint's `updated_at`; the `since`
filter is strictly greater-than.

**Sync protocol (mandatory reading):**

1. **One sweep = one `since`.** Call with a fixed `since`, then keep passing the returned
   `nextCursor` until `hasMore` is `false`. Only after the sweep completes may you advance
   `since` to the max `producedAt` you observed. Never bump `since` between pages: many files
   share one `producedAt` (a segment fans out to one file per table), and a mid-sweep bump
   would silently skip the rest of that timestamp.
2. **Drive iteration by `hasMore`, not by an empty list.** A page can contain fewer than
   `size` entries — even zero — while `hasMore` is still `true` (delta candidates whose Parquet
   the egress skipped are dropped after pagination). The cursor advances past them.
3. **Always pass `since` in steady state.** `since` defaults to epoch; the server paginates in
   SQL so an unbounded sweep is safe, but it makes the client re-walk (and re-register links
   for) the entire history every time. Epoch is for the initial backfill only.

Response (one entry per file; a fresh one-time link is registered per entry on every call):

```json
{
  "files": [
    {
      "siteId": "…", "siteDomain": "shop.example.com",
      "table": "orders", "type": "delta",
      "firstSeq": 100, "lastSeq": 250, "seq": null,
      "producedAt": "2026-07-27T10:15:00",
      "fileName": "orders_seq100-250.parquet",
      "downloadUrl": "https://host/api/v1/plugins/parquet-export/download/<43-char-token>",
      "linkExpiresAt": "2026-07-27T11:15:00"
    }
  ],
  "size": 50, "hasMore": false, "nextCursor": null
}
```

`nextCursor` is non-null exactly when `hasMore` is `true`.

Errors: `400` malformed filters or cursor, `401` + `WWW-Authenticate: Basic` on bad credentials
or a deactivated plugin, `429` + `Retry-After` on the per-account rate limit.

> **Note:** the rate limit is the shared per-account plugin bucket (Bucket4j, 100 req/min) —
> an account that also uses the Bit BI plugin API shares this budget across both plugins.

## Downloading

```
GET /api/v1/plugins/parquet-export/download/{token}     ← no authentication
```

- **302 Found** — first use; `Location` is an S3 presigned URL valid ~60 s. The link is consumed
  atomically at this moment.
- **410 Gone** — link already consumed, expired (1 h after registration by default), or the
  plugin was deactivated.
- **404 Not Found** — unknown (or purged) token.

`curl -L -o file.parquet <downloadUrl>` follows the redirect in one step.

Edge case: if the S3 object was deleted between listing and download, the redirect still happens
and S3 answers 404 — the link is consumed; re-list to get a fresh entry.

## Housekeeping

A scheduled job deletes consumed/expired `download_links` rows older than the retention window.

## Operational notes

- **Anonymous download route**: `/download/{token}` has no application-level rate limit (the
  token is the credential; unknown tokens cost one indexed lookup and increment the
  `plugin.parquet.export.download.rejected{reason="unknown"}` Micrometer counter, with no audit
  write). For internet-facing deployments add ingress/WAF rate limiting on
  `/api/v1/plugins/parquet-export/download/**`.
- **`plugins.parquet-export.enabled=false`** only unregisters the plugin SPI bean — new
  activations become impossible, but existing activations keep authenticating and the
  `/files`, `/download` and rotate-password endpoints stay live. To cut off a specific client,
  deactivate its plugin (or rotate the password).
- **Login is a public identifier**: lookup happens before the BCrypt check, so response timing
  distinguishes known from unknown logins. This is by design — the secret is the password.
- **Tokens appear in URL paths**: `/download/{token}` shows up in ingress/proxy access logs.
  Single use + the 1 h TTL bound the exposure, but treat those logs accordingly (a logged
  token is dead after the first download and after expiry).

## Configuration (`application.yml`)

```yaml
plugin:
  parquet-export:
    link-ttl-seconds: 3600      # one-time link validity before first use
    presign-ttl-seconds: 60     # S3 presigned URL validity after consume
    purge-retention-days: 7     # keep consumed/expired rows this long
    purge-interval-ms: 3600000  # purge sweep cadence
    base-url: ""                # absolute prefix for download URLs; empty = derive from request.
                                # REQUIRED behind a proxy/ingress: without forwarded-headers
                                # handling the derived URL is the pod-local scheme/host.
plugins:
  parquet-export:
    enabled: true               # component toggle (matchIfMissing = true)
```

## Implementation map

- Security: dedicated filter chain (`SecurityConfiguration.parquetExportPluginApiFilterChain`,
  Order 4) — Basic Auth on `/files` via `ParquetExportBasicAuthFilter`, anonymous `/download/**`;
  denyAll carve-outs in the OAuth2 catch-all.
- Credentials: `ParquetExportCredentialsService` — `plugin_data = {login, passwordHash}` (BCrypt),
  login-first lookup = one BCrypt comparison per request.
- Catalog: `ParquetExportFileService` + `ParquetExportCatalogDao` — filtering, per-table
  fan-out (`jsonb_object_keys(stats)`) and keyset pagination `(producedAt, s3Key)` run in SQL
  (bounded to `size + 1` rows per source); the `deltaExists` probe runs per served page.
- Links: `DownloadLink` / `download_links` (V39), `DownloadLinkService` (atomic consume + 60 s
  presign), `DownloadLinkPurgeScheduler`.
- Audit: `PluginAuditService` — `FILES_LISTED`, `LINK_CONSUMED`, `LINK_REJECTED`,
  `PASSWORD_ROTATED` (plus the standard activation events).
- Spec & design: `specs/028-parquet-export-plugin/`.
