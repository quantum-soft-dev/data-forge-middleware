# Tasks: Delta Parquet Download in the UI (025)

**Branch**: `feature/025-delta-parquet-download` | **Discipline**: WIP=1, TDD, one Conventional
Commit per task, per-task gates green (backend + frontend).

- [x] **T1** Backend: per-batch delta Parquet presign endpoints (owner + admin)
  `DeltaSegmentParquetQueryService` (segment by batchId → `deltaExists` → presign 15 min);
  `deltaKey`/`deltaExists` extracted on `S3CheckpointStorage`; endpoints on both delta
  controllers. Tests first: `DeltaBatchParquetRestContractTest` (6 cases: owner/admin 200,
  404 no-file, 404 no-segment, 403 foreign site, 403 non-admin).
  Commit: `feat(delta): per-batch delta Parquet download endpoints (025-T1)`

- [x] **T2** Frontend: Parquet pills in delta Batch Detail
  `presignBatchTableParquet` in `deltaSyncApi`; File column with per-table pill (completed
  sessions only); guide updated (`docs/delta-client-v2-guide.md`). Also fixed the
  `bg-transparent` status-pill regression from 024/T036. Tests first: `DeltaBatchDetail.test.tsx`.
  Commit: `feat(upload-history): parquet download pills in delta batch detail (025-T2)`

- [x] **T3** Review fixes, backend
  Dropped `@Transactional` holding a HikariCP connection across S3 HEAD/presign; wrapped raw
  `SdkClientException` in `CheckpointStorageException`; mapped it to **503** in
  `GlobalExceptionHandler` (test first in `GlobalExceptionHandlerTest`).
  Commit: `fix(delta): release DB connection during S3 round-trips, 503 on storage failure (025-T3)`

- [x] **T4** Review fixes, frontend
  Shared `openPresignedDownload` (same-tab anchor — Safari-popup-safe after await; success
  toast; 404 vs generic error taxonomy) reused by `DeltaSyncWidget` and `DeltaBatchDetail`;
  `admin` prop; in-flight guard; status pill via shared `STATUS_LABELS`/`STATUS_VARIANT`
  (`COMPLETED_WITH_WARNINGS` amber everywhere); maps extracted to
  `features/upload-history/model/batchStatus.ts`.
  Commit: `fix(upload-history): shared presigned-download flow and status mapping (025-T4)`

- [x] **T5** Descope the admin presign twin (post-review, 2026-07-08)
  Review found the admin endpoint + `admin` prop unreachable: batch detail has no admin
  surface (clicks from /admin/sites land on a UserOnlyGuard route). Removed the admin
  endpoint from `DeltaSyncAdminController`, the `admin` prop from `DeltaBatchDetail`, the
  scope param from `presignBatchTableParquet`; contract tests now 4 owner-only cases.
  Re-add together with an admin batch-detail page (see spec FR-1 note).
  Commit: `refactor(delta): drop unreachable admin batch-parquet presign (025-T5)`

## Verification

- Contract: 4/4 green after T5 descope (was 6/6); frontend suite green; backend fast gate green.
- Live e2e (2026-07-07): click on `nsfbook` → presigned `egress/.../seq=879446-879482.parquet`,
  file bytes valid (`PAR1` head+tail).
- Before-PR: `./gradlew integrationTest` required after stacking on 022/023/024 merges.
