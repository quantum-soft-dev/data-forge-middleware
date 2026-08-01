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
therefore replayed in segment sequence order into one writer. The replay is two-pass: pass one
computes the decimal precision envelope, preserving the existing lossless fallback for understated
schemas; pass two writes typed records. Both passes stream, and the completed file stays on disk
until uploaded.

## Durability and retries

The `batch_parquet_artifacts` manifest is the durable queue and public source of truth. A worker
claims one row with PostgreSQL row locking, writes to a unique local file, uploads to a stable key,
and sets `READY` only after `PutObject` succeeds. A crash or failure cannot expose the row as ready;
retries rebuild and overwrite the same logical object. Table failures are isolated.

## Compatibility

There is no Delta protobuf or client behavior change. Snapshot sealing and continuous-session
segment publication are unchanged. Unified artifacts are finalized only when the owning batch
closes. Authorization and short-lived presigned downloads retain the existing route and DTO.

## Lifecycle

Batch retention and explicit batch deletion remove the batch artifact rows and objects. A site
history wipe includes the batch-artifact prefix with the rest of `egress/{siteId}/`, and database
cascades remove the manifests with their batches.

