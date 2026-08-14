# 041 — Research

## Decision: default `type` is `batch`, not "all three"

- **Decision**: omitted `type` lists only batch artifacts. `delta` and `checkpoint` stay
  explicit filters. There is no `type=all`.
- **Rationale**: product owner, 13.08.2026. The plugin API has no version. The live pain is
  thousands of segment files; the default must stop producing them.
- **Alternatives considered**: default = all three (lossless but still floods the client);
  versioned path (no versioning on this API); query flag `prefer=batch` (another breaking
  surface).

## Decision: `since` / sort on `ready_at`, not batch close time

- **Decision**: `producedAt` for `READY` is `ready_at`. For `ABANDONED` it is `updated_at`.
- **Rationale**: a retried artifact can become ready long after the session ended. If `since`
  used `batches.completed_at`, a client that already advanced its watermark would never see it.
  The same rule makes an 039 requeue reappear after a new `READY`.
- **Alternatives considered**: sort by `completed_at` (loses late files); sort by `created_at`
  (PENDING time, not when the file exists).

## Decision: show `ABANDONED`, hide intermediate statuses

- **Decision**: catalog `IN ('READY','ABANDONED')`. No download link for abandoned.
- **Rationale**: abandoned is terminal and otherwise silent data loss. PENDING/BUILDING/FAILED
  will either become READY (and appear with a later `ready_at`) or ABANDONED.
- **Alternatives considered**: hide abandoned (client drifts); expose FAILED (retries would
  double-emit).

## Decision: cursor key for abandoned rows

- **Decision**: `COALESCE(s3_key, 'abandoned/' || id::text)`. Real keys start with `egress/`.
- **Rationale**: abandoned rows have null `s3_key` after `clearPublishedMetadata`. Keyset
  pagination needs a unique, stable tie-break.
- **Alternatives considered**: exclude abandoned from keyset (would drop them on page
  boundaries); use `batch_id || table_name` (collides if the same pair is requeued and the
  previous listing is still being walked — id is stabler).

## Decision: seq range stored at publish, batch-wide fallback

- **Decision**: writer writes min/max seq of published, non-provisional segments whose `stats`
  contain the table; if none, the batch-wide published min/max. Legacy NULL columns use the
  same fallback in SQL.
- **Rationale**: the file contains every record of that table for the batch. A site-global
  watermark `lastSeq <= applied_seq → skip` stays correct. `DeltaParquetWriter.FileWriteResult`
  does not currently expose seq bounds; reading the segments the writer already loaded avoids a
  writer contract change.
- **Alternatives considered**: add seq tracking to the Parquet writer (more precise per-record
  bounds, larger diff, not required for the watermark); always store batch-wide (simpler, less
  informative when a table only appears in the tail).

## Decision: filename uses the batch UUID

- **Decision**: `{table}_batch{batchId}.parquet` (canonical UUID).
- **Rationale**: `batches` has no integer number. The UUID matches the new `batchId` field and
  is unique in a client's download folder.
- **Alternatives considered**: first 8 hex chars (collision risk); lastSeq (`_batch250`) which
  collides across tables/batches more easily.

## Decision: no S3 HEAD for batch rows

- **Decision**: the manifest is the source of truth. Delta listings keep the `deltaExists`
  probe for schema-skipped segment files.
- **Rationale**: READY implies a completed PutObject; ABANDONED has no object. Extra HEADs
  would add latency and could drop a READY row if S3 is briefly unreachable.

## Decision: V51 index as specified

- **Decision**: btree `(site_id, ready_at, s3_key)`.
- **Rationale**: issue #109. Helps READY listings filtered by site. Abandoned rows have null
  `ready_at` and will not use it; they are rare.
- **Alternatives considered**: functional index on
  `(site_id, COALESCE(ready_at, updated_at), COALESCE(s3_key, id::text))` — better for mixed
  pages, more than the issue asked for.
