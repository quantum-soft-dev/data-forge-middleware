# 036 — Tasks

WIP = 1. Each task is test-first and lands as one atomic Conventional Commit referencing it.
Per-task gate: `./gradlew test -PexcludeIntegration` (+ frontend `npx tsc --noEmit`, `npm run lint`,
and `npm test` when frontend is touched). Before PR: `./gradlew integrationTest`.

- [x] **T01 — Design and change request.** Add the spec, plan, ordered tasks, and
  `docs/cr-unified-batch-parquet.md` documenting the compatibility boundary and artifact contract.

- [x] **T02 — Aggregate batch table statistics.** Merge every non-null segment statistics map by
  table and operation, stable-sort by table, and defensively aggregate duplicate DTO rows in the
  frontend. Tests cover mixed operations, null legacy stats, total correctness, and one button.

- [ ] **T03 — Durable artifact manifest.** Add V49 and the artifact aggregate/repository with
  unique `(batch_id, table_name)`, durable states, metadata, ordered claim query, and batch/site
  cleanup queries. Tests cover lifecycle transitions and concurrent claim behavior.

- [ ] **T04 — File-backed streaming writer.** Add streaming raw-segment iteration and a two-pass
  file writer preserving types, `_op`, `_seq`, `_changed`, decimal widening, and ascending order.
  Upload through a file request body. Tests prove replay, bounded output shape, and cleanup helpers.

- [ ] **T05 — Batch finalization queue.** Enqueue per-table rows inside Delta session completion,
  wake a bounded worker after commit, and finalize tables independently/idempotently from ordered
  non-provisional segments. Tests cover ordering, schema/render/upload failure, retry, and cleanup.

- [ ] **T06 — Download and lifecycle cleanup.** Resolve the exact `READY` manifest from the owner
  endpoint, return deterministic `409` vs `404`, and delete unified artifacts/rows during retention,
  admin batch deletion, and site-history wipe. Add unit and contract coverage.

- [ ] **T07 — Integration and delivery docs.** PostgreSQL + LocalStack coverage for multi-segment
  mixed-table output, ordered completeness, retry, retention, and wipe; update the Delta guide and
  docs index; run the before-PR integration gate.
