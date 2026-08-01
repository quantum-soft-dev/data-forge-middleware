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

Retries are capped at `delta.batch-parquet.max-attempts` (default 5). Several failure classes are
deterministic and never recover — a table with no declared schema, data the declared schema cannot
render, or a batch whose segments a re-baseline removed — and rebuilding them every retry delay
forever would re-download and re-write the whole batch changelog on a loop. Past the cap the row is
left `FAILED`, logged at ERROR, and never claimed again; the download answers `404`, which matches
how the per-segment egress worker already treats an unrenderable table (skip, don't wedge the
queue). Resetting `attempt_count` requeues it after the underlying cause is fixed.

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
returns the existing short-lived presigned-download DTO only for `READY`. `PENDING` or `BUILDING`
returns `409 Conflict`; an absent or `FAILED` row returns `404 Not Found`.

Manifest rows are written when a batch completes, so batches that closed before this change shipped
have none — and V49 deliberately does not backfill them at migration time, which would queue every
historical batch at once and push freshly completed sessions behind the whole backlog. Instead the
download path backfills lazily: a request for a batch with no manifest row enqueues that batch's
tables from its (untouched) raw segments and answers `409`, and the next click downloads the built
artifact. A batch with no published segments still answers `404`, so nothing that was 404 before
becomes a queued build. Batch Detail aggregates
segment statistics by table, so rolling deployments and legacy duplicate DTO entries still render
one row and one request per table.
