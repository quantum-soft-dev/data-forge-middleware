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
- [x] **T05 — Integration coverage.** Red Testcontainers cases: default listing ignores
  segment/checkpoint seeds, `since` uses `ready_at`, NULL seq fallback, requeue-then-ready
  reappears, mixed READY/ABANDONED cursor.
- [x] **T06 — Documentation.** Plugin guide (new type, breaking default, client migration,
  latency/retention trade-offs), unified-batch CR note, CLAUDE.md Recent Changes, 028 contract
  pointer. Mark this file's checkboxes.
- [x] **T07 — Review fixes.** Scope the catalog seq fallback join, distrust mixed null-stats
  when recording `seqRange`, split the `since` predicate so READY can use the V51 index, and
  correct the OpenAPI download-URL sentence.
- [x] **T08 — Catalog publish order.** Serialize READY/ABANDONED publication with a transaction
  advisory lock so `ready_at`/`updated_at` cannot land before a sibling that already committed.
- [x] **T09 — Persist seq on abandon.** Keep `first_seq`/`last_seq` through abandon/requeue;
  drop the live LATERAL fallback; document that `lastSeq == null` never skips.
- [x] **T10 — Skip rule.** Skip only `ready` / `delta` / `checkpoint`; always surface abandoned.
- [x] **T11 — artifactId in the catalog.** Add `artifactId` to batch listing rows and the guide.
- [x] **T12 — Catalog index and cursor.** Align V51 with UNION ALL branches on
  `(ready_at, s3_key)` and `(updated_at, id)`.
