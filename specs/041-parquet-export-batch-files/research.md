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
  Both timestamps are assigned inside a short transaction that first takes a process-wide
  PostgreSQL advisory lock (`parquet-export-catalog-publish`), so a later commit cannot stamp
  an earlier watermark than a sibling that already became visible.
- **Rationale**: a retried artifact can become ready long after the session ended. If `since`
  used `batches.completed_at`, a client that already advanced its watermark would never see it.
  The same rule makes an 039 requeue reappear after a new `READY`. A wall-clock stamp taken
  before commit would still lose a slow publisher: worker A stamps `t1` and stalls, B stamps
  `t2 > t1` and commits, the client stores `since=t2`, then A commits and vanishes forever.
- **Alternatives considered**: sort by `completed_at` (loses late files); sort by `created_at`
  (PENDING time, not when the file exists); client-side overlap + dedup (requires `artifactId`
  and a protocol change); pod-local `LocalDateTime.now()` under the lock (still loses a row
  when a replica clock lags). The lock is kept; the stamp is
  `GREATEST(previous + 1µs, clock_timestamp())` from `batch_parquet_catalog_watermark`.

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

## Decision: seq range stored at publish, no live fallback

- **Decision**: writer writes min/max seq of published, non-provisional segments whose `stats`
  contain the table; if none, the batch-wide published min/max. `clearPublishedMetadata` keeps
  that pair. The catalog reads only the stored columns. `lastSeq == null` is unknown — never skip.
- **Rationale**: the file contains every record of that table for the batch. A live
  `MIN`/`MAX` over `changelog_segments` becomes NULL once retention prunes the batch, and
  JavaScript `null <= n` is `true`, so clients would skip a still-listed READY/ABANDONED file.
  There is no production READY history to backfill in V51.
- **Alternatives considered**: add seq tracking to the Parquet writer (more precise per-record
  bounds, larger diff, not required for the watermark); always store batch-wide (simpler, less
  informative when a table only appears in the tail); V51 backfill from remaining segments
  (no such rows in prod); LATERAL fallback (wrong after retention, and paid on every row).

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

## Decision: V51 catalog indexes match UNION ALL branches

- **Decision**: two partial indexes — `(site_id, ready_at, s3_key) WHERE status='READY'` and
  `(site_id, updated_at, COALESCE(s3_key, 'abandoned/'||id)) WHERE status='ABANDONED'`.
  `findBatchFiles` is `UNION ALL` of those branches, each limited, then merged by
  `(produced_at, s3_key)`.
- **Rationale**: a single btree on raw `(site_id, ready_at, s3_key)` cannot serve the mixed
  CASE/COALESCE order. After splitting statuses, each branch uses its own columns.
- **Alternatives considered**: one expression index on the CASE/COALESCE (matches a single
  mixed query, unused after UNION ALL); keep the mixed scan (sorts the whole account).
