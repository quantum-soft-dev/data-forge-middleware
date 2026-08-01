# Change request: one batch-level Delta Parquet per table

**Issue:** #93
**Feature:** 036-unified-batch-parquet

## Decision

Delta v2 keeps three deliberately distinct storage layers:

1. **Raw changelog segments** (`delta/{siteId}/segments/*.pb.gz`) are the authoritative durable
   journal and remain bounded by ingestion seals.
2. **Realtime segment egress** (`egress/{siteId}/{table}/delta/seq=*.parquet`) remains available to
   existing sequential consumers, including Parquet Export. This change does not remove or alter it.
3. **Completed-batch downloads** (`egress/{siteId}/batches/{batchId}/*.parquet`) are new unified
   user-facing artifacts: exactly one file for each table in a closed session.

The batch download API resolves layer 3 only. It never scans or guesses among layer-2 objects.

## Why raw segments are replayed

Parquet files have independent footers and cannot be byte-concatenated. Raw protobuf records are
therefore replayed in segment sequence order into one writer. A table that declares decimal columns
is replayed twice: pass one computes the decimal precision envelope, preserving the existing
lossless fallback for understated schemas; pass two writes typed records. A table with no decimal
column has nothing to measure and is replayed once — finalization runs per table, so the skipped
pass is a whole extra download of the batch changelog for every such table. Both passes stream, and
the completed file stays on disk until uploaded.

## Durability and retries

The `batch_parquet_artifacts` manifest is the durable queue and public source of truth. A worker
claims one row with PostgreSQL row locking, writes to a unique local file, uploads to a stable key,
and sets `READY` only after `PutObject` succeeds. A crash or failure cannot expose the row as ready;
retries rebuild and overwrite the same logical object. Table failures are isolated.

The claim is committed **before** the build runs, in its own transaction, and `BUILDING` — not a
held row lock — is what keeps other replicas off the row. That is what makes the attempt ceiling
real: a process that dies mid-build (an OOM on a large batch, a pod eviction) has already made its
incremented `attempt_count` durable, so the most expensive failure class cannot loop forever. It
also keeps the long S3 work off a database connection.

Each claim mints a `claim_token`, and its owner renews the lease (`updated_at`) every third of
`delta.batch-parquet.lease-seconds` (default 30 min) while it builds. The lease therefore bounds
*worker death*, not how long a build may legitimately take — without the renewal it would be a hard
ceiling on build time, and any batch slower than it would be reclaimed mid-flight and rebuilt from
scratch by the next worker, spending an attempt and a pool slot on every lapse. The token settles
the race the lease cannot: a reclaim mints a new one, so a previous owner that surfaces late finds
its token stale and discards its outcome instead of overwriting a live claim — or burying a build
that is still running under its own failure.

Retries are capped at `delta.batch-parquet.max-attempts` (default 7), with the failure backoff
doubling per attempt. Seven attempts span `60+120+240+480+960+1920s ≈ 63 min`, so a transient S3
outage has to outlast an hour before in-flight artifacts are given up on. Several failure classes
are deterministic and never recover — a table with no declared schema, data the declared schema
cannot render, or a batch whose segments a re-baseline removed — and rebuilding them forever would
re-download and re-write the whole batch changelog on a loop. Past the cap the row becomes
`ABANDONED`, logged at ERROR, and is never claimed again, which matches how the per-segment egress
worker already treats an unrenderable table (skip, don't wedge the queue). Resetting the row
requeues it once the underlying cause is fixed.

`ABANDONED` is a distinct status precisely because `FAILED` is not terminal: a row still holding
attempts is work in progress, and the download must say so rather than claim the file is missing.

## Compatibility

There is no Delta protobuf or client behavior change. Snapshot sealing and continuous-session
segment publication are unchanged. Unified artifacts are finalized only when the owning batch
closes. Authorization and short-lived presigned downloads retain the existing route and DTO.

## Lifecycle

Batch retention and explicit batch deletion collect exact object keys, remove the manifest rows
before their parent batches, and then delete the objects best-effort. A site-history wipe performs
the same exact-key cleanup and also walks the complete `egress/{siteId}/` prefix so realtime segment
egress and any orphan left by an interrupted cleanup are removed.

## Download readiness contract

The existing owner route is unchanged. It resolves the exact `(site, batch, table)` manifest and
returns the existing short-lived presigned-download DTO only for `READY`. `PENDING`, `BUILDING` and
`FAILED` all return `409 Conflict` — each means "an attempt is queued or running", and answering
`404` for a retryable failure would tell the user the table has no file minutes before the same
click starts working. Only an absent row or `ABANDONED` returns `404 Not Found`.

Manifest rows are written when a batch completes, so batches that closed before this change shipped
have none — and V49 deliberately does not backfill them at migration time, which would queue every
historical batch at once and push freshly completed sessions behind the whole backlog. Instead the
download path backfills lazily: a request for a batch with no manifest row enqueues that batch's
tables from its (untouched) raw segments and answers `409`, and the next click downloads the built
artifact. A batch with no published segments still answers `404`, so nothing that was 404 before
becomes a queued build.

The backfill only accepts a batch in a **terminal** status. A continuous session publishes
non-provisional segments while its batch is still `IN_PROGRESS`, so enqueueing then would build an
artifact from the seals so far and publish it `READY`; batch completion skips tables that already
have a row, and the finished session would serve a silently truncated download. Batch Detail aggregates
segment statistics by table, so rolling deployments and legacy duplicate DTO entries still render
one row and one request per table.
