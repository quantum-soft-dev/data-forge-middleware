# 039 — Tasks

WIP = 1. Each task is test-first and lands as one atomic Conventional Commit referencing #98.

- [x] **T01 — Design.** Record the queue metrics, admin REST contract, recovery concurrency rule,
  audit strategy, compatibility boundary, documentation surfaces, and test strategy.
- [x] **T02 — Queue depth gauges.** Add red tests, then register a database-backed gauge for every
  artifact status and verify the values remain live.
- [x] **T03 — Recovery domain and application service.** Add red aggregate/service/repository tests,
  then implement locked list/requeue operations, expired-lease validation, and transactional audit.
- [x] **T04 — Admin REST and migration.** Add red contract/integration tests, then expose the list
  and requeue routes, extend the audit constraint with V50, and document the OpenAPI contract.
- [x] **T05 — Documentation and delivery gates.** Update the unified Parquet CR, Delta guide,
  docs index and project journal; run all required backend/integration gates and open the PR.
- [x] **T06 — Review fixes.** Resolve the grouped-metrics query and recovery-concurrency coverage
  findings test-first, run the per-task gate, and record each outcome on the PR.
- [x] **T07 — Review edge cases.** Start the metric TTL only after a successful grouped query,
  cover negative monotonic-clock values, and make the PostgreSQL lock assertion observable.
- [x] **T08 — Final review and synchronization.** Complete a clean repeat review, merge current
  `origin/develop`, rerun all gates and CI, and stop at `status: ready to merge`.
