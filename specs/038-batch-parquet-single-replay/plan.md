# 038 — Batch Parquet single-replay plan

Issue #97 removes the table multiplier from completed-batch Parquet finalization. The public
download contract, manifest rows, stable S3 keys, and retry semantics introduced by 036 remain
unchanged.

## Design

1. A worker still selects the oldest retryable artifact row, but the claim transaction takes a
   PostgreSQL transaction-scoped advisory lock for its `batch_id` and claims every retryable row
   of that batch. The advisory lock closes the `SKIP LOCKED` race where two workers could select
   different table rows before either transaction publishes `BUILDING`. Active batch claims keep
   the remaining rows out of the candidate query until their leases expire or settle.
2. The writer accepts all claimed tables and fans each streamed `ChangeRecord` to the matching
   open `ParquetWriter`. It maintains the row count and previous sequence independently per table,
   preserving the row-count-vs-segment-stats guard and strict ascending `_seq` assertion.
3. When no claimed schema contains decimals, all files are produced by one changelog replay. If
   at least one schema contains decimals, one shared streaming scan computes every table's
   precision envelope and one shared write replay produces all files. The cost is therefore one
   or two batch replays, independent of table count.
4. One writer and row-group buffer is open per claimed table. Peak heap is bounded by the number
   of claimed tables multiplied by the Parquet writer buffer, rather than by the number of rows.
   Temp-file limits remain per artifact. A schema, render, size, row-count, or upload failure
   settles only its own manifest row; a raw-segment replay failure affects the whole claimed batch.
5. Lease renewal and publish remain token-checked per manifest row. Each output uses the existing
   stable logical key, so attempt-key isolation and orphan reclamation stay in follow-up #100.

## Compatibility

- No REST, gRPC, DTO, database migration, metric name, route, S3-key, or configuration-key change.
- Existing `batch_parquet_artifacts` rows remain valid. A rolling deployment can finish an older
  table-at-a-time claim; new workers wait for its active batch lease before grouping the remainder.
- Existing retry backoff and attempt ceilings continue per table.

## Test strategy

- Unit: multi-table writer routes mixed records, preserves per-table order/count/type metadata,
  uses one replay without decimals and two shared replays with decimals, and isolates one table's
  deterministic write failure.
- Unit: one claim groups all retryable rows of a batch, renews every token, publishes independent
  outcomes, and does not claim a batch while another worker owns the advisory lock.
- Integration: PostgreSQL + LocalStack multi-table finalization completes with one service drain,
  and repository locking prevents competing batch claims.
- Gates: `./gradlew test -PexcludeIntegration` before every code commit and
  `./gradlew integrationTest` before the PR.
