# 036 — Implementation plan

## Shape

Keep sequential segment egress as-is and add an independent completed-batch artifact queue. A
post-commit completion callback creates one `PENDING` manifest row per table from aggregated
segment statistics (or raw-record discovery for legacy null stats). A bounded worker claims rows
with `FOR UPDATE SKIP LOCKED`, streams raw segments twice, uploads a temp file with
`RequestBody.fromFile`, and publishes metadata as `READY`.

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

1. Claim one retryable manifest row under a database row lock, minting a `claim_token` and
   committing the claim *before* the build so a lost process still spends its attempt (T09/T10).
   The owner renews the lease while it builds; a claim is reclaimable only once the lease lapses.
2. Resolve all non-provisional batch segments ordered by `first_seq` and the current site schemas.
3. Create a uniquely named temp file under the configured temp directory.
4. First streaming pass, **only when the table declares a decimal column** (T08 — each pass is a
   full replay of the changelog): visit only records for the target table and compute required
   decimal precision after declared-scale rounding.
5. Open one file-backed `AvroParquetWriter` with the widened schema.
6. Second streaming pass: visit the same segments/table, assert strictly ascending `_seq`, and write
   typed rows with `_op`, `_seq`, `_changed`, key, and data values.
7. Enforce the configured byte ceiling as the writer emits the local file, then close it, compute
   file size and SHA-256 without loading it, upload from the path,
   then set `READY` metadata — but only if the row is still held by this claim's token; otherwise
   discard the outcome, and delete the object when the row is gone entirely (T11).
8. On a transient failure mark only that artifact `FAILED`, or `ABANDONED` once it has used up
   `max-attempts` (T09); a deterministic byte-limit failure abandons immediately. Always delete the
   temp file. A retry overwrites the same stable S3 key and cannot append or duplicate rows.

## Test strategy

- Unit: duplicate/mixed/null stats aggregation; frontend defensive aggregation.
- Unit: file-backed writer types, metadata fields, ordering, decimal widening, and replay count.
- Unit: enqueue/finalizer ordering, independent failure, retry/idempotency, and temp cleanup.
- Contract: ownership isolation plus `READY`/not-ready/not-found response taxonomy.
- Integration: PostgreSQL + LocalStack multi-segment/mixed-table finalization, ordered complete
  Parquet contents, upload failure retry, retention, and wipe cleanup.
