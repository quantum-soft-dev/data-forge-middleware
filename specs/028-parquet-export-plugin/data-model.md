# Phase 1 Data Model: Parquet Export Plugin

## New table: `download_links` (migration V39)

```sql
CREATE TABLE download_links (
    id                UUID PRIMARY KEY,
    token             VARCHAR(64)  NOT NULL,          -- URL-safe base64 of 32 random bytes (43 chars)
    account_plugin_id BIGINT       NOT NULL REFERENCES account_plugins(id),
    s3_key            VARCHAR(1000) NOT NULL,
    file_name         VARCHAR(255) NOT NULL,
    expires_at        TIMESTAMP    NOT NULL,
    consumed_at       TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_download_links_token ON download_links (token);
CREATE INDEX idx_download_links_purge ON download_links (created_at);
CREATE INDEX idx_download_links_account_plugin ON download_links (account_plugin_id);
```

Notes:
- `token`: generated from `SecureRandom` 32 bytes → Base64 URL-safe without padding (43 chars); unique index doubles as the lookup path.
- `account_plugin_id` is `BIGINT` matching `account_plugins.id` (IDENTITY `Long`).
- Timestamps are UTC `LocalDateTime`, consistent with the rest of the schema.
- No FK to file-producing tables: `s3_key` is a snapshot of the derived key at listing time.

## Entity: `DownloadLink` (`plugin/domain/DownloadLink.java`)

| Field | Type | Notes |
|---|---|---|
| `id` | `UUID` | assigned in factory |
| `token` | `String` | immutable |
| `accountPluginId` | `Long` | owning activation |
| `s3Key` | `String` | S3 object key |
| `fileName` | `String` | download filename (sanitized later by presigner) |
| `expiresAt` | `LocalDateTime` | `created + linkTtl` |
| `consumedAt` | `LocalDateTime` | null until consumed |
| `createdAt` | `LocalDateTime` | |

Factory: `DownloadLink.register(accountPluginId, s3Key, fileName, ttl)` — generates id + token, stamps times. Entity is effectively write-once; consumption happens via a bulk-update query (not entity mutation) for atomicity.

## Repository: `DownloadLinkRepository` (domain) / `JpaDownloadLinkRepository` (infrastructure)

```java
Optional<DownloadLink> findByToken(String token);
List<DownloadLink> saveAll(...);                       // batch insert per listing call
int consume(String token, LocalDateTime now);           // @Modifying UPDATE ... WHERE consumed_at IS NULL
                                                        //   AND expires_at > :now
                                                        //   AND account_plugin active (join/subselect)
int purge(LocalDateTime cutoff);                        // @Modifying DELETE (consumed or expired) AND created_at < cutoff
```

`consume` returns affected-row count: `1` → winner; `0` → caller SELECTs by token to map 404 (absent) vs 410 (present but consumed/expired/inactive).

Active-activation check inside `consume` (native query):

```sql
UPDATE download_links dl
   SET consumed_at = :now
 WHERE dl.token = :token
   AND dl.consumed_at IS NULL
   AND dl.expires_at > :now
   AND EXISTS (SELECT 1 FROM account_plugins ap
                WHERE ap.id = dl.account_plugin_id AND ap.is_active = true)
```

## Plugin data shape (`account_plugins.plugin_data` for `parquet-export`)

```json
{
  "login": "pex_Ab3xY9Qm2Lk4",
  "passwordHash": "$2a$10$..."
}
```

- `login`: `pex_` + 12 alphanumerics, `SecureRandom`, generated once at first activation, stable across rotations/reactivations.
- `passwordHash`: BCrypt; replaced atomically on rotation via `AccountPlugin.updatePluginData(...)`.
- Raw password: 32 alphanumerics, returned exactly once as `login:password` through `ActivationResult.apiKey` (activation) or the rotation response.

## `plugin_configs` seed (in V39)

```sql
INSERT INTO plugin_configs (plugin_id, client_id, display_name, is_enabled, config)
VALUES ('parquet-export', 'parquet-export-internal', 'Parquet Export', true, '{}');
```

(`client_id` is NOT NULL UNIQUE in V8; parquet-export has no Auth0 M2M client, so a reserved sentinel value is used — same nullability constraint honored.)

## File catalog derivation (no new tables)

### Delta files

Source: `changelog_segments s JOIN sites st ON st.id = s.site_id`

Predicates: `st.account_id = :accountId`, `s.egress_at IS NOT NULL`, `s.egress_at > :since`, optional `s.site_id = :siteId`, `s.stats IS NOT NULL`.

Fan-out: one candidate file per `(segment, table)` for each `table` in `stats` keys (optionally filtered by `:table`). Derived key: `S3CheckpointStorage.deltaKey(siteId, table, firstSeq, lastSeq)`; kept only if `deltaExists(...)` (drops schema-skipped/poison tables). `fileName = "{table}_seq{first}-{last}.parquet"` (same as 025 owner endpoint). `producedAt = egress_at`.

### Checkpoint files

Source: `checkpoints c JOIN sites st ON st.id = c.site_id`

Predicates: `st.account_id = :accountId`, `c.s3_key_parquet IS NOT NULL`, `c.updated_at > :since`, optional site/table filters. `fileName = "{table}_seq{seq}.parquet"` (matches `DeltaCheckpointQueryService`). `producedAt = updated_at`.

### Pagination & ordering

- Order: `producedAt ASC, id ASC` within each source; page assembled after the segment→table fan-out.
- `page`/`size` (`size` ≤ 100, default 50). Because a segment fans out to several files, the service paginates over the **flattened, ordered file list**: fetch candidate rows with a windowed query (bounded overshoot: `size` segments ≥ `size` files), flatten, probe existence, slice. Simpler correctness-first approach acceptable at current scale; response carries `hasMore` + the last `producedAt` so clients can also advance `since` instead of paging deep.
- Recommended client pattern (documented in guide): poll with `since = max(producedAt seen)`, no deep paging.

## Response DTOs (presentation/dto)

`ParquetFileListResponseDto`:

```json
{
  "files": [
    {
      "siteId": "…", "siteDomain": "…",
      "table": "orders", "type": "delta",
      "firstSeq": 100, "lastSeq": 250,          // null for checkpoint
      "seq": null,                                // checkpoint's seq, null for delta
      "producedAt": "2026-07-27T10:15:00Z",
      "fileName": "orders_seq100-250.parquet",
      "downloadUrl": "https://…/api/v1/plugins/parquet-export/download/{token}",
      "linkExpiresAt": "2026-07-27T11:15:00Z"
    }
  ],
  "page": 0, "size": 50, "hasMore": false
}
```

Errors follow the existing `ErrorResponseDto` shape (`timestamp/status/error/message/path`).

## Configuration (`application.yml`)

```yaml
plugin:
  parquet-export:
    link-ttl-seconds: 3600        # one-time link validity before first use
    presign-ttl-seconds: 60       # S3 presigned URL validity after consume
    purge-retention-days: 7       # keep consumed/expired rows this long
    purge-interval-ms: 3600000    # purge sweep cadence
    base-url: ""                  # absolute prefix for download URLs; empty → derive from request
```

## New enum values (`PluginActionType`, additive)

`FILES_LISTED`, `LINK_CONSUMED`, `LINK_REJECTED`, `PASSWORD_ROTATED`.

## State transitions (DownloadLink)

```
REGISTERED (consumed_at NULL, now < expires_at)
   │ GET /download/{token}  — atomic UPDATE wins
   ▼
CONSUMED  (consumed_at set)          → further GETs: 410
REGISTERED ── time passes ──► EXPIRED (now ≥ expires_at) → GET: 410
REGISTERED ── plugin deactivated ──► INERT (consume blocked)  → GET: 410
any state ── purge (older than retention) ──► deleted → GET: 404
```
