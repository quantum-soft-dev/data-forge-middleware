# 042 — Tasks

WIP = 1. Each task is test-first and lands as one atomic Conventional Commit referencing #111.

- [x] **T01 — Design.** Record the phase allowlists, streaming attribution rule, egress
  pending gauge, compatibility boundary, documentation surfaces, and test strategy.
- [x] **T02 — Meters.** Add red tests, then register phase timers on
  `DeltaMetrics` and the `delta.egress.pending` gauge (repository count + 5 s TTL).
- [x] **T03 — Batch Parquet phases.** Add red tests, then attribute
  `download` / `decode` / `decimal_scan` / `write` / `upload` on the completed-batch
  path without buffering the changelog.
- [x] **T04 — Egress phases.** Add red tests, then time
  `download` / `write` / `upload` on per-segment egress.
- [x] **T05 — Checkpoint phases.** Add red tests, then time
  `download_frame` / `fold` / `parquet` / `upload` inside the existing cycle timer.
- [x] **T06 — Documentation and delivery gates.** Update the Delta guide, unified
  Parquet CR and project journal; run the required gates and open the PR.
- [x] **T07 — Review fixes.** Record download/decode on replay failure, stop `write`
  before SHA-256, and tighten the guide's phase definitions.
