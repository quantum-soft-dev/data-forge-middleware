# 036 — Implementation plan

## Shape

Keep sequential segment egress as-is and add an independent completed-batch artifact queue. The
session commit transaction creates one `PENDING` manifest row per table from aggregated segment
statistics. After commit, a bounded worker claims rows with `FOR UPDATE SKIP LOCKED`, streams raw
segments twice, uploads a temp file with `RequestBody.fromFile`, and publishes metadata as `READY`.

## Layers

| Layer | Change |
|---|---|
| migration | V49 `batch_parquet_artifacts`, status check, unique `(batch_id, table_name)`, claim index |
| delta/domain | artifact entity/status/repository; ordered non-provisional batch segment query |
| delta/application | enqueue service, batch finalizer, bounded worker, streaming codec/writer |
| delta/infrastructure | streaming raw-segment reads; file upload/delete for unified artifacts |
| batch | aggregate detail stats; retention/admin deletion invokes artifact cleanup |
| presentation | owner download resolves one manifest; `409` while pending/building |
| frontend | defensive duplicate aggregation so rolling deployments never render duplicate keys/rows |
| docs | artifact-layer distinction, lifecycle, API readiness behavior |

## Finalization algorithm

1. Claim one retryable manifest row under a database row lock.
2. Resolve all non-provisional batch segments ordered by `first_seq` and the current site schemas.
3. Create a uniquely named temp file under the configured temp directory.
4. First streaming pass: visit only records for the target table and compute required decimal
   precision after declared-scale rounding.
5. Open one file-backed `AvroParquetWriter` with the widened schema.
6. Second streaming pass: visit the same segments/table, assert strictly ascending `_seq`, and write
   typed rows with `_op`, `_seq`, `_changed`, key, and data values.
7. Close the writer, compute file size and SHA-256 without loading the file, upload from the path,
   then set `READY` metadata.
8. On any failure mark only that artifact `FAILED`; always delete the temp file. A retry overwrites
   the same stable S3 key and cannot append or duplicate rows.

## Test strategy

- Unit: duplicate/mixed/null stats aggregation; frontend defensive aggregation.
- Unit: file-backed writer types, metadata fields, ordering, decimal widening, and replay count.
- Unit: enqueue/finalizer ordering, independent failure, retry/idempotency, and temp cleanup.
- Contract: ownership isolation plus `READY`/not-ready/not-found response taxonomy.
- Integration: PostgreSQL + LocalStack multi-segment/mixed-table finalization, ordered complete
  Parquet contents, upload failure retry, retention, and wipe cleanup.
