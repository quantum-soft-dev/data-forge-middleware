# 041 — Tasks

WIP = 1. Each task is test-first and lands as one atomic Conventional Commit referencing #109.

- [x] **T01 — Design.** Record the catalog contract, default-type break, abandoned visibility,
  `ready_at` watermark, V51 columns/index, writer seq-range rule, and documentation surfaces.
- [x] **T02 — Schema and aggregate.** Red tests for `first_seq`/`last_seq` lifecycle on
  `BatchParquetArtifact`, then V51 plus entity fields. Update AGENTS.md / CLAUDE.md migration
  pointers so `MigrationDocumentationConsistencyTest` stays green.
- [x] **T03 — Writer records the seq range.** Red finalization tests, then persist the table
  (or batch-wide fallback) range in `markReady`.
- [x] **T04 — Catalog query and default listing.** Red unit/contract tests for `type=batch`,
  omitted `type`, abandoned rows without a download URL, and cursor walk; then
  `findBatchFiles`, `FileType.BATCH`, DTO fields, and link registration that skips abandoned.
- [ ] **T05 — Integration coverage.** Red Testcontainers cases: default listing ignores
  segment/checkpoint seeds, `since` uses `ready_at`, NULL seq fallback, requeue-then-ready
  reappears, mixed READY/ABANDONED cursor.
- [ ] **T06 — Documentation.** Plugin guide (new type, breaking default, client migration,
  latency/retention trade-offs), unified-batch CR note, CLAUDE.md Recent Changes, 028 contract
  pointer. Mark this file's checkboxes.
