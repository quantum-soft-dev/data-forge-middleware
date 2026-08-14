# 042 — Tasks

WIP = 1. Each task is test-first and lands as one atomic Conventional Commit referencing #111.

- [x] **T01 — Design.** Record the phase allowlists, streaming attribution rule, egress
  pending gauge, compatibility boundary, documentation surfaces, and test strategy.
- [ ] **T02 — Meters.** Add red tests, then register phase timers on
  `DeltaMetrics` and the `delta.egress.pending` gauge (repository count + 5 s TTL).
- [ ] **T03 — Batch Parquet phases.** Add red tests, then attribute
  `download` / `decode` / `decimal_scan` / `write` / `upload` on the completed-batch
  path without buffering the changelog.
- [ ] **T04 — Egress phases.** Add red tests, then time
  `download` / `write` / `upload` on per-segment egress.
- [ ] **T05 — Checkpoint phases.** Add red tests, then time
  `download_frame` / `fold` / `parquet` / `upload` inside the existing cycle timer.
- [ ] **T06 — Documentation and delivery gates.** Update the Delta guide, unified
  Parquet CR and project journal; run the required gates and open the PR.
