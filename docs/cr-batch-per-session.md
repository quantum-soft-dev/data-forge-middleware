# CR: Batch-per-Session Semantics for Delta Client v2 Ingestion (029)

**Status**: Implemented (branch `029-batch-per-session`)
**Spec**: `specs/029-batch-per-session/`
**Migration**: V40

## Motivation

A real onboarding run (site `fyt-new`, test env, 2026-07-27) streamed ~200 records of one table
over a single CONTINUOUS gRPC session and produced **three** Upload History rows: two
`100 changes · 1 tables` batches one second apart plus a trailing `0 files · 0 B` batch. The
customer read this as data corruption. Cause: the ingestion server closed the active batch and
opened a fresh one on **every** continuous-mode segment seal (`CONTINUOUS_SEAL_RECORDS = 100`,
plus time/byte triggers), and pre-opened a successor batch that closed empty at `SessionEnd`.

Product decision: **a batch is one client attempt to upload data in one go** — one row or a
million, it is one batch. Storage granularity is the server's internal concern and must not leak
into the UI or the plugin event stream.

## Semantics after 029

- One gRPC ingestion session ↔ one batch. Opened at `SessionStart` (or re-attached on staged
  resume), completed exactly once at `SessionEnd`/clean close, failed on abort. A zero-record
  session completes its batch honestly with 0 changes. No pre-opened tail batches.
- Continuous seals (still every 100 records / `continuous-seal-millis` / `continuous-seal-bytes`)
  are pure durability events: each seals a segment **under the same batch**
  (`DeltaSessionCommitService.commitSegment` — persists the segment and advances the watermark,
  never touches the batch).
- `changelog_segments` ↔ `batches` is now genuinely many-to-one. Segment S3 keys are per-segment:
  `delta/{siteId}/segments/{segmentId}.pb.gz` (pre-029 rows keep their stored batch-derived keys;
  the `s3_key` column is authoritative).
- Upload History list aggregates per batch SQL-side: `deltaRecordCount` = SUM of segment record
  counts, `deltaTableCount` = COUNT of DISTINCT tables across segment stats
  (`ChangelogSegmentRepository.aggregateByBatchIds`). The detail view already aggregated.
- `BATCH_COMPLETED` fires once per session (plugins previously got one event per ~100 records).
  Per-segment pipelines are untouched: delta Parquet egress and the Bit BI delta-SQL queue still
  trigger at seal time, without waiting for the session to end.

## Activity-based timeout (V40)

`batches.last_activity_at` (nullable) is touched by the ingest path at a bounded cadence: session
start/resume, each Ack watermark (≥100 accepted records), each seal. The timeout sweeper's cutoff
is now `COALESCE(last_activity_at, started_at) < now - batch.timeout-minutes`:

- a live streaming session older than 60 minutes keeps running (its activity is fresh);
- a session silent for the whole window is failed (NOT_COMPLETED), freeing the site;
- v1 batches never touch activity → exact pre-029 behavior;
- the blanket V2 exclusion from the sweeper (review r4 of 022) is removed — it existed only
  because start-based expiry would kill long live sessions mid-commit.

A truly perpetual continuous client holds one batch open for the session's lifetime — accepted
for now (no forced rollover); the activity timeout still reclaims it if the stream goes silent.

## Design decisions

See `specs/029-batch-per-session/research.md` (D1–D7) and
`specs/029-batch-per-session/contracts/session-batch-contract.md`. Highlights: seal/lifecycle
split at the commit-service level (D2), segment-id S3 keys with no backfill (D3), COALESCE
sweeper with bounded-cadence touches (D4), SQL-side list aggregation (D5).

## Compatibility / ops

- Migration V40 is additive (nullable column + `idx_changelog_segments_batch_id`); forward-only.
- No gRPC proto change; clients see the same protocol (per-seal `SessionCommitted` events
  included). No REST DTO shape change; the frontend is untouched.
- Rollback: old app code reads mixed data fine (stored `s3_key` values, single- and
  multi-segment batches); multi-segment batches would render in the old list view with one
  segment's count until rolled forward again.
- Watch after deploy: `delta.sessions.started/committed` now count sessions (previously inflated
  by one per seal); Upload History rows per client run drop accordingly.
