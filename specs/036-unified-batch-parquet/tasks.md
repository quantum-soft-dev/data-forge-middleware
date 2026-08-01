# 036 — Tasks

WIP = 1. Each task is test-first and lands as one atomic Conventional Commit referencing it.
Per-task gate: `./gradlew test -PexcludeIntegration` (+ frontend `npx tsc --noEmit`, `npm run lint`,
and `npm test` when frontend is touched). Before PR: `./gradlew integrationTest`.

- [x] **T01 — Design and change request.** Add the spec, plan, ordered tasks, and
  `docs/cr-unified-batch-parquet.md` documenting the compatibility boundary and artifact contract.

- [x] **T02 — Aggregate batch table statistics.** Merge every non-null segment statistics map by
  table and operation, stable-sort by table, and defensively aggregate duplicate DTO rows in the
  frontend. Tests cover mixed operations, null legacy stats, total correctness, and one button.

- [x] **T03 — Durable artifact manifest.** Add V49 and the artifact aggregate/repository with
  unique `(batch_id, table_name)`, durable states, metadata, ordered claim query, and batch/site
  cleanup queries. Tests cover lifecycle transitions and concurrent claim behavior.

- [x] **T04 — File-backed streaming writer.** Add streaming raw-segment iteration and a two-pass
  file writer preserving types, `_op`, `_seq`, `_changed`, decimal widening, and ascending order.
  Upload through a file request body. Tests prove replay, bounded output shape, and cleanup helpers.

- [x] **T05 — Batch finalization queue.** Enqueue per-table rows inside Delta session completion,
  wake a bounded worker after commit, and finalize tables independently/idempotently from ordered
  non-provisional segments. Tests cover ordering, schema/render/upload failure, retry, and cleanup.

- [x] **T06 — Download and lifecycle cleanup.** Resolve the exact `READY` manifest from the owner
  endpoint, return deterministic `409` vs `404`, and delete unified artifacts/rows during retention,
  admin batch deletion, and site-history wipe. Add unit and contract coverage.

- [x] **T07 — Integration and delivery docs.** PostgreSQL + LocalStack coverage for multi-segment
  mixed-table output, ordered completeness, retry, retention, and wipe; update the Delta guide and
  docs index; run the before-PR integration gate.

- [x] **T08 — Review round 1.** Cap retries (`delta.batch-parquet.max-attempts`) so a deterministic
  failure becomes terminal instead of rebuilding the changelog every minute forever; backfill
  pre-feature batches on demand from the download path instead of answering a permanent `404`; skip
  the decimal scan pass for tables that declare no decimal column. Tests cover the attempt ceiling
  at repository and service level, the backfill/`409` path, and the single-pass replay.

- [x] **T09 — Review round 2.** Make the claim durable (committed before the build, `BUILDING` as
  the cross-replica guard, lease-based reclaim) so a process death still spends an attempt; add
  `ABANDONED` so a retryable `FAILED` answers `409` instead of "no such file"; refuse to backfill a
  batch whose session is still running; add a doubling failure backoff; close the segment stream in
  the service that opened it. Tests cover claim durability and lease takeover, the backoff and
  abandon boundaries, the IN_PROGRESS refusal, and the `409`/`404` split.

- [x] **T10 — Review round 3.** Give each claim a `claim_token` and renew its lease while the build
  runs, so the lease bounds worker death rather than build time and a superseded owner cannot write
  into its successor's attempt; raise `max-attempts` to 7 so the backoff spans ~1 h; log the drain
  failure with its stack trace. Tests cover the mid-build takeover and lease renewal by token.

- [x] **T11 — Review round 4 (five parallel reviewers).** Surface the `409` as progress on the
  frontend instead of a raw error toast; make the enqueue an insert-if-absent so concurrent
  backfills cannot collide on the unique index; derive artifact S3 keys from `(site, batch, table)`
  in retention and admin delete so a mid-build/failed row's object is still collected, and delete
  the object a finished build can no longer attach to a row; keep the claim's lease scheduling
  inside the published path and drain gracefully on shutdown. Tests: listener phases, the
  row-count guard, admin-delete ordering, provisional invisibility, insert idempotency, `409` UX,
  plus queue isolation between integration classes.

- [x] **T12 — Review round 5.** Stop answering `404` when a concurrent backfill already queued the
  work (the insert-if-absent count is not evidence — only the row is); add the Micrometer meters the
  sibling egress path has (`delta.batch-parquet.{artifacts,duration,reclaims}`); bring `CLAUDE.md`
  to V49/next V50 and add the 036 journal entry; rename `DeltaSegmentParquetQueryService` to
  `BatchParquetDownloadService`, which is what it now is.
