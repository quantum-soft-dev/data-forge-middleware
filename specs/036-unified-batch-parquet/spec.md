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
  streaming write pass.
- Artifact state is durable and idempotent. A row is downloadable only in `READY`; failures remain
  retryable, and workers claim work with database locking.
- A missing or unusable table schema fails only that table artifact.
- Existing per-segment egress remains unchanged for realtime and Parquet Export consumers.
- Batch retention, admin batch deletion, and site-history wipe delete manifest rows and unified S3
  objects.
- The existing owner route remains unchanged:
  `GET /api/v1/account/sites/{siteId}/delta/batches/{batchId}/tables/{tableName}/parquet`.
- No protobuf, extractor/client, sequence, ACK, hashing, or snapshot-seal changes are permitted.

## Error contract

- `200`: the exact manifest row is `READY`; return the existing presigned-download DTO.
- `404`: there is no artifact for the `(site, batch, table)` tuple, including a table skipped or
  failed because it has no renderable schema.
- `409`: the artifact exists but is still `PENDING` or `BUILDING`.

## Object layout

`egress/{siteId}/batches/{batchId}/{url-encoded-table}.parquet`

The key is stable and unique for the logical artifact. S3 `PutObject` completion precedes the
manifest transition to `READY`, so a partial upload is never advertised.

