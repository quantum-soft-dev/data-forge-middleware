# 041 — Data model

## `batch_parquet_artifacts` (existing, V49)

V51 additions:

| Column | Type | Null | Notes |
|---|---|---|---|
| `first_seq` | BIGINT | yes | table (or fallback) range; set on `markReady`; cleared with published metadata |
| `last_seq` | BIGINT | yes | same lifecycle as `first_seq` |
| index `idx_batch_parquet_artifacts_catalog` | `(site_id, ready_at, s3_key)` | | catalog keyset support |

Existing status machine is unchanged: `PENDING → BUILDING → READY | FAILED | ABANDONED`,
`requeue` back to `PENDING`. `clearPublishedMetadata` also nulls the new seq columns so a
requeued row cannot keep a stale range.

## Catalog row (application)

```
CatalogRow(
  siteId, siteDomain, table, type,
  firstSeq, lastSeq, seq,
  producedAt, s3Key,
  batchId,          -- set for BATCH, null otherwise
  status            -- "ready" | "abandoned" for BATCH, null otherwise
)
```

Batch SQL projection:

- `type` = `BATCH`
- `produced_at` = `ready_at` if `READY`, else `updated_at`
- `s3_key` = `COALESCE(s3_key, 'abandoned/' || id)`
- `first_seq`/`last_seq` = `COALESCE(artifact columns, batch published MIN/MAX)`
- `seq` = null
- filter: `status IN ('READY','ABANDONED')` and `produced_at > :since`

## HTTP file item

Existing `ParquetFileResponseDto` plus:

| Field | Batch READY | Batch ABANDONED | Delta / checkpoint |
|---|---|---|---|
| `type` | `batch` | `batch` | `delta` / `checkpoint` |
| `batchId` | uuid | uuid | null |
| `status` | `ready` | `abandoned` | null |
| `firstSeq` / `lastSeq` | range | range | delta: segment range; checkpoint: null |
| `seq` | null | null | checkpoint only |
| `downloadUrl` / `linkExpiresAt` | registered link | null | registered link |
| `fileName` | `{table}_batch{batchId}.parquet` | same | unchanged |
