# 038 — Tasks

WIP = 1. Each task is test-first and lands as one atomic Conventional Commit referencing #97.

- [x] **T01 — Design.** Record the batch claim, shared replay, bounded-memory model,
  compatibility boundary, and test strategy.
- [ ] **T02 — Multi-table streaming writer.** Add red tests, then fan one replay out to all
  non-decimal tables and share one decimal-envelope scan across all decimal tables. Preserve
  per-table `_seq`, row counts, typed rows, output limits, and independent writer failures.
- [ ] **T03 — Batch claim and finalization.** Add red service/repository/integration tests, then
  claim retryable rows by batch under a transaction-scoped advisory lock, renew every token, build
  them together, and publish each outcome independently.
- [ ] **T04 — Documentation and delivery gates.** Update the 036 plan/change request, Delta guide,
  and project journal with the new cost/heap model; run backend and integration gates before PR.
- [ ] **T05 — Review follow-up and final synchronization.** Resolve every review surface, merge
  current `origin/develop`, rerun all gates, and stop at `status: ready to merge`.
