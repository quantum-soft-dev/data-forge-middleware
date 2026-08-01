# 037 — Tasks

WIP = 1. Each task is test-first and lands as one atomic Conventional Commit referencing #99.

- [x] **T01 — Design.** Record the bounded hardening design, compatibility boundary, test plan,
  and the late-upload item that requires a dedicated follow-up.
- [ ] **T02 — Safe enqueue and legacy stats.** Move enqueue after commit, discover tables from
  legacy null-stats segments, and skip only the row-count assertion when expected counts are
  unknowable. Tests first.
- [ ] **T03 — Deletion layering and atomicity.** Put artifact key derivation in the delta domain
  and route admin deletion through a transactional application service; remove delta
  infrastructure imports from batch presentation/application code. Tests first.
- [ ] **T04 — Queue and publish policy.** Bulk-settle spent expired claims, remove the scan loop,
  compute aggregate stats once, enforce the byte ceiling during file output, and abandon an
  oversized deterministic artifact immediately. Tests first.
- [ ] **T05 — Documentation and delivery.** Update 036/037 docs, `AGENTS.md`, and the project
  journal; run all gates, open the PR, complete review, synchronize with `develop`, and stop at
  `status: ready to merge`.
