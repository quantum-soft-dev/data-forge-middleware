# 036 — Implementation plan

## Shape

Keep sequential segment egress as-is and add an independent completed-batch artifact queue. A
post-commit completion callback creates one `PENDING` manifest row per table from aggregated
segment statistics (or raw-record discovery for legacy null stats). A bounded worker serializes a
batch claim with a transaction-scoped advisory lock, claims its retryable rows with `FOR UPDATE`,
fans one shared raw-segment replay into all non-decimal writers (or one shared decimal scan plus one
shared write replay), uploads temp files with `RequestBody.fromFile`, and publishes each row's
metadata as `READY` independently.

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

1. Select the oldest retryable manifest row, acquire the batch's transaction-scoped advisory lock,
   then row-lock and claim every currently retryable sibling, minting a `claim_token` per table.
   Commit the claims *before* the build so a lost process still spends its attempts (T09/T10/038).
   The owner renews every lease while it builds; a claim is reclaimable only once its lease lapses.
2. Resolve all non-provisional batch segments ordered by `first_seq` and the current site schemas.
3. Create one uniquely named temp file per claimed/renderable table.
4. If any claimed schema declares a decimal column, make one shared streaming pass over the batch
   and compute required precision per table/column after declared-scale rounding.
5. Open one file-backed `AvroParquetWriter` per table with its widened schema.
6. Make one shared streaming write pass: route each record by table, assert strictly ascending
   `_seq` per table, and write typed rows with `_op`, `_seq`, `_changed`, key, and data values.
7. Enforce the configured byte ceiling as the writer emits the local file, then close it, compute
   file size and SHA-256 without loading it, upload from the path,
   then set `READY` metadata — but only if the row is still held by this claim's token; otherwise
   discard the outcome, and delete the object when the row is gone entirely (T11).
8. On a transient failure mark only that artifact `FAILED`, or `ABANDONED` once it has used up
   `max-attempts` (T09); a deterministic byte-limit failure abandons immediately. Always delete the
   temp file. A retry overwrites the same stable S3 key and cannot append or duplicate rows.

The cost is one batch replay without decimal columns or two shared replays when any decimal is
present, independent of table count. Heap is bounded by open writers × a row-group buffer, not rows.

## Test strategy

- Unit: duplicate/mixed/null stats aggregation; frontend defensive aggregation.
- Unit: file-backed writer types, metadata fields, ordering, decimal widening, and replay count.
- Unit: enqueue/finalizer ordering, independent failure, retry/idempotency, and temp cleanup.
- Contract: ownership isolation plus `READY`/not-ready/not-found response taxonomy.
- Integration: PostgreSQL + LocalStack multi-segment/mixed-table finalization, ordered complete
  Parquet contents, upload failure retry, retention, and wipe cleanup.
