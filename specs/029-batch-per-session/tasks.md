# Tasks: Batch-per-Session Semantics for Delta Client v2 Ingestion

**Input**: Design documents from `/specs/029-batch-per-session/`
**Prerequisites**: plan.md, spec.md, research.md (D1–D7), data-model.md, contracts/session-batch-contract.md, quickstart.md

**Policy (CLAUDE.md)**: strictly serial, WIP = 1, test-first per task, one atomic Conventional Commit per task referencing the task id. Per-task gate: `./gradlew test -PexcludeIntegration` 100% green before every commit. `[P]` markers are intentionally absent — this repo forbids parallel task execution.

## Phase 1: Foundation (blocking)

- [x] **T001** Migration V41 + Batch activity field.
  Files: `src/main/resources/db/migration/V41__batch_last_activity.sql` (DDL per data-model.md: nullable `batches.last_activity_at`, `idx_changelog_segments_batch_id`, column comment); `src/main/java/com/bitbi/dfm/batch/domain/Batch.java` (`lastActivityAt` field + `touchActivity()` domain method setting it to now).
  Tests first: extend `src/test/java/com/bitbi/dfm/batch/domain/BatchTest.java` — `shouldTouchActivityUpdateLastActivityAt`, `shouldHaveNullLastActivityAtOnCreation`.
  Commit: `feat(batch): add last_activity_at to batches + segment batch_id index (T001)`

- [x] **T002** Per-segment S3 keys.
  Files: `src/main/java/com/bitbi/dfm/delta/infrastructure/S3ChangelogSegmentStorage.java` (`uploadSegment(UUID siteId, UUID segmentId, byte[])` — key `delta/{siteId}/segments/{segmentId}.pb.gz`; javadoc layout comment updated); `src/main/java/com/bitbi/dfm/delta/application/ChangelogSegmentService.java` (generate the segment UUID before upload, pass segment id to storage, persist the same id in the entity so `s3_key` matches the row).
  Tests first: adjust `ChangelogSegmentService`/storage unit tests to assert the key is derived from the segment id and differs across two segments of one batch.
  Commit: `feat(delta): store changelog segments under per-segment S3 keys (T002)`

## Phase 2: US1 — one upload attempt = one batch (P1, MVP)

- [x] **T003** Split segment commit from batch completion in `DeltaSessionCommitService`.
  New method `commitSegment(siteId, batchId, mode, firstSeq, committedSeq, records)`: persists segment + registers per-segment afterCommit egress dispatch, does **not** call `completeBatch`. Existing `commit(...)` becomes the SessionEnd path: final `commitSegment` (if records non-empty or session had no segments yet — the zero-record session still needs no segment, just completion) followed by `batchLifecycleService.completeBatch(batchId)`.
  Tests first: `DeltaSessionCommitServiceTest` — `commitSegmentShouldPersistWithoutCompletingBatch`, `commitShouldCompleteBatchOnce`, `commitWithZeroRecordsShouldCompleteBatchWithoutSegment`, egress dispatch still fires per segment.
  Commit: `feat(delta): split segment commit from session batch completion (T003)`

- [x] **T004** Ingestion lifecycle: seal keeps the batch; no tail batch; no per-seal batch cycling.
  File: `src/main/java/com/bitbi/dfm/delta/presentation/DeltaIngestionService.java`.
  `sealContinuous(true)`: call `commitSegment`, reset buffer/firstSeq/sinceAck, **do not** complete or start batches, do not bump session metrics counter. Clean close: final flush via the completing `commit` path exactly once (also for zero-record sessions); remove the pre-opened successor batch logic. Abort/error paths: fail the single batch (unchanged single call). Staged resume: unchanged (batch id survives).
  Tests first: ingestion unit tests — CONTINUOUS 250 records → 3 `commitSegment` calls + 1 completion on `SessionEnd`, all under one batchId; clean close with empty buffer → completion, no segment, no new batch; abort mid-stream fails the one batch.
  Commit: `feat(delta): one batch per ingestion session (T004)`

- [x] **T005** List-view aggregation across a batch's segments.
  Files: `src/main/java/com/bitbi/dfm/delta/domain/ChangelogSegmentRepository.java` + `Jpa` impl (aggregate projection query per data-model.md: SUM(record_count), COUNT(DISTINCT jsonb table keys) for `batch_id IN (:ids)`); `src/main/java/com/bitbi/dfm/batch/application/BatchHistoryService.java` (replace pick-one-segment bulk fetch with the aggregate map); `src/main/java/com/bitbi/dfm/batch/presentation/dto/BatchSummaryDto.java` (`fromProjection(projection, DeltaAggregate)`).
  Tests first: `BatchHistoryServiceTest` — multi-segment batch sums records and unions tables; legacy single-segment batch renders as today; v1 batch keeps nulls. Contract test for `GET /api/v1/history/batches` DTO shape unchanged.
  Commit: `feat(batch): aggregate delta totals across session segments in history list (T005)`

## Phase 3: US2 — activity-based timeout (P2)

- [x] **T006** Activity touch on the ingest path.
  Files: `src/main/java/com/bitbi/dfm/batch/application/BatchLifecycleService.java` (`touchActivity(batchId)` — single UPDATE, no optimistic-lock inflation); `DeltaIngestionService` — touch at SessionStart/resume, at each Ack watermark emission (every ≥100 accepted records), and inside each seal.
  Tests first: unit tests — Ack emission touches activity; seal touches activity; v1 upload path never touches.
  Commit: `feat(batch): track last session activity on streaming batches (T006)`

- [x] **T007** Sweeper cutoff on `COALESCE(last_activity_at, started_at)`.
  Files: `src/main/java/com/bitbi/dfm/batch/domain/BatchRepository.java` / `Jpa` impl (`findExpiredBatches` query change); `BatchTimeoutScheduler` untouched logic-wise.
  Tests first: `BatchTimeoutSchedulerTest` / repository test — live-old batch (old started_at, fresh activity) survives; silent batch (fresh started_at is irrelevant, stale activity) fails after cutoff; legacy batch with NULL activity behaves exactly as today.
  Commit: `feat(batch): time out streaming batches by last activity (T007)`

## Phase 4: US3 + verification

- [x] **T008** End-to-end integration suite (Testcontainers + LocalStack), `src/test/java/integration/BatchPerSessionIngestionIT.java` (align naming with existing integration suites):
  (a) CONTINUOUS session 250 records / 2 tables → exactly 1 COMPLETED batch, 3 segments sharing batch_id, per-segment S3 objects under segment-id keys, egress artifacts produced before SessionEnd;
  (b) history endpoints: list row `250 changes · 2 tables`, detail aggregates identically; no `0 files/0 B` row;
  (c) exactly one `BATCH_COMPLETED` plugin dispatch per session (spy/captor on dispatcher);
  (d) zero-record session → 1 COMPLETED batch, 0 changes;
  (e) abort mid-stream → 1 FAILED batch, earlier segments durable.
  Commit: `test(delta): batch-per-session end-to-end integration suite (T008)`

- [x] **T009** Docs: `docs/cr-batch-per-session.md` (motivation incl. the fyt-new incident, semantics, D1–D7, migration V41, ops notes: rollback behavior, timeout semantics); update `docs/delta-client-v2-guide.md` (batch = session; continuous slicing invisible in history) and `CLAUDE.md` (Recent Changes entry, migration pointer V41 → next V42).
  Commit: `docs(delta): batch-per-session change request and guide updates (T009)`

## Dependencies

T001 → T006/T007 (column must exist); T002 → T003/T004 (key API); T003 → T004; T004 → T005 (multi-segment batches become producible) → T008; T006 → T007 → T008; T008 → T009. Strictly serial execution order: T001…T009.

## Gates

- Every task: `./gradlew test -PexcludeIntegration` green before commit (pre-commit hook enforces).
- Before PR: `./gradlew integrationTest` green (covers T008).
- PR into `develop`, squash merge, docs present (T009).
