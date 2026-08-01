# 036 — Unified batch/table Delta Parquet

## Problem

A Delta v2 session batch may own many durable changelog segments. Segment boundaries are internal
durability and memory-control details, but Batch Detail currently flattens every segment's table
statistics and resolves the batch/table download by returning the first matching segment artifact.
Large snapshots therefore show duplicate table rows and repeatedly download an incomplete slice.

## Requirements

- A completed Delta batch exposes one aggregated statistics row per table.
- Exactly one downloadable Parquet artifact is finalized per `(batch_id, table_name)`.
- Finalization reads non-provisional raw segments in ascending `first_seq` order and preserves
  ascending `_seq` order in the artifact.
- The writer and S3 upload are file-backed. Neither all table records nor the complete Parquet file
  may be retained in heap.
- Decimal precision widening remains lossless through a first streaming scan followed by a second
  streaming write pass. The scan pass is skipped for a table that declares no decimal column, since
  it has nothing to measure and each pass is a full replay of the batch changelog.
- Artifact state is durable and idempotent. A row is downloadable only in `READY`; failures remain
  retryable up to a configured attempt ceiling, after which the row is terminal (deterministic
  failures must not be rebuilt forever). Workers claim work with database locking.
- Batches completed before this feature shipped keep a working download: a request with no manifest
  row enqueues that batch from its raw segments instead of answering `404`.
- A missing or unusable table schema fails only that table artifact.
- Existing per-segment egress remains unchanged for realtime and Parquet Export consumers.
- Batch retention, admin batch deletion, and site-history wipe delete manifest rows and unified S3
  objects.
- The existing owner route remains unchanged:
  `GET /api/v1/account/sites/{siteId}/delta/batches/{batchId}/tables/{tableName}/parquet`.
- No protobuf, extractor/client, sequence, ACK, hashing, or snapshot-seal changes are permitted.

## Error contract

- `200`: the exact manifest row is `READY`; return the existing presigned-download DTO.
- `404`: the batch has nothing to build for that table (unknown table, no published segments), or
  finalization gave up on it because it has no renderable schema.
- `409`: the artifact exists but is still `PENDING` or `BUILDING` — including the first request for
  a pre-feature batch, which is enqueued on demand.

## Object layout

`egress/{siteId}/batches/{batchId}/{url-encoded-table}.parquet`

The key is stable and unique for the logical artifact. S3 `PutObject` completion precedes the
manifest transition to `READY`, so a partial upload is never advertised.
