# Contract: `GET /api/v1/plugins/parquet-export/files` (041)

Same path, Basic Auth, keyset pagination, rate limit, and `/download/{token}` as 028.
Breaking change: omitted `type` lists **batch artifacts only**.

## Query

| Param | Type | Default | Notes |
|---|---|---|---|
| `since` | ISO 8601 | epoch | strictly greater than `producedAt` |
| `siteId` | UUID | all account sites | empty if not owned |
| `table` | string | all | |
| `type` | `batch` \| `delta` \| `checkpoint` | `batch` | unknown → 400 |
| `cursor` | opaque | start | previous `nextCursor` |
| `size` | 1..100 | 50 | |

## Batch item

```json
{
  "siteId": "0195…",
  "siteDomain": "shop.example.com",
  "table": "orders",
  "type": "batch",
  "batchId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "artifactId": "0195aaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "status": "ready",
  "firstSeq": 100,
  "lastSeq": 250,
  "seq": null,
  "producedAt": "2026-07-27T10:16:00",
  "fileName": "orders_batcha1b2c3d4-e5f6-7890-abcd-ef1234567890.parquet",
  "downloadUrl": "https://host/api/v1/plugins/parquet-export/download/<token>",
  "linkExpiresAt": "2026-07-27T11:16:00"
}
```

Abandoned item: `status=abandoned`, `downloadUrl=null`, `linkExpiresAt=null`,
`producedAt` is the row's `updated_at`. No `download_links` insert. Clients skip
`lastSeq <= applied_seq` only for `status=ready` and for `delta`/`checkpoint`. An
`abandoned` row is always an alert. `lastSeq == null` is never skippable.

## Client migration

Replace

```
GET /files?since=…
```

with

```
GET /files?type=delta&since=…
```

if the integration still consumes per-segment files. Checkpoint consumers must send
`type=checkpoint`. New integrations should stay on the default (`type=batch` or omitted).

## Unchanged

`hasMore` / `nextCursor` keyset over `(producedAt, s3Key)`. Drive iteration by `hasMore`.
One-time consume: 302 then 410. 401 / 429 behaviour unchanged.
