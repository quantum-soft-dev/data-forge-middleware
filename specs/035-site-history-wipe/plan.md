# 035 — Implementation plan

## Shape

One new application service (`DeltaSiteWipeService`) shaped like `BatchRetentionService`: a
transactional phase doing set-based bulk deletes and collecting S3 keys + counts, followed by a
non-transactional batched S3 delete via `S3FileStorageService.deleteObjects`. Everything else is
either an additive column, an additive proto field, or a hook.

## Layers touched

| Layer | Change |
|---|---|
| `db/migration` | `V48__site_history_wipe.sql` — `site_sync_state.generation` + `wipe_pending`, `admin_action_logs.details` (JSONB) + CHECK extension for `SITE_HISTORY_WIPE`, CHECK extension for `DELTA_AUTO_REINIT` |
| `delta/domain` | `SiteSyncState.generation` / `wipePending` / `resetForWipe()` / `consumeWipePending()`; `SiteSyncStateRepository.findBySiteIdForUpdate` + `clearWipePending` |
| `delta/domain` | `ChangelogSegmentRepository.deleteBySiteId` / `findKeysBySiteId`; `CheckpointRepository.deleteBySiteId` / `findKeysBySiteId` |
| `delta/application` | new `DeltaSiteWipeService`; `CheckpointService` publishes `CheckpointRecordedEvent` |
| `delta/presentation` | proto fields; `DeltaIngestionService` emits `generation` + the `GENERATION_MISMATCH` guard; wipe endpoints on both controllers + request/response DTOs |
| `batch/domain` | `BatchRepository.deleteBySiteId`, `findIdsBySiteId` |
| `upload/domain` | `UploadedFileRepository.findS3KeysBySiteId` |
| `plugin/domain` | `PluginSqlGenerationRepository.findS3KeysBySiteId` / `deleteBySiteId`; `PluginDeltaBaselineRepository.deleteBySiteId`; `AccountPluginRepository.detachBaselineBatchesOfSite`; `PluginActionType.DELTA_AUTO_REINIT` |
| `plugin/application` | `PluginDeltaBaselineService.recaptureForSite`; new `PluginDeltaWipeReinitListener`; `PluginAuditService.logDeltaAutoReinit` |
| `error/domain` | `ErrorLogRepository.deleteBySiteId` |
| `account/domain` | `AdminActionType.SITE_HISTORY_WIPE`, `AdminActionLog.details` |
| frontend | `wipeSiteHistory` + `useWipeSiteHistory`, `WipeHistoryDialog`, Danger zone in `SiteDetailShell` |
| docs | `docs/delta-client-v2-guide.md`, `docs/site-history-wipe-client-guide.md`, `docs/README.md` |

## Key design points

**Package dependency direction.** `delta/**` must not import `plugin/**` (today the dependency is
strictly one-way, plugin → delta). The auto-reinit hook is therefore a Spring event:
`CheckpointService` publishes `CheckpointRecordedEvent(siteId, seq)` and a listener in
`plugin/infrastructure/events` consumes it. The listener is `@Transactional`, so consuming
`wipe_pending` and recapturing the baselines are atomic — the exactly-once property FR-12 needs —
without the delta package knowing that plugins exist.

`CheckpointService` is deliberately non-transactional (it spans S3 I/O), so the event is published
inline after `syncStateService.recordCheckpoint` and the listener opens its own short transaction.
That is "same transaction" in the sense that matters: flag-consumption and recapture cannot diverge.

**`wipe_pending` consumption** is a conditional `UPDATE … WHERE wipe_pending = TRUE` returning the
row count, following the `markRebaselineNotified` precedent: no load-modify-save, no lost update
against a concurrent wipe, and self-idempotent.

**Bulk deletes** are `@Modifying` JPQL/native queries rather than per-row loops: a wiped site can
have hundreds of thousands of rows. Keys are always collected *before* the corresponding delete,
inside the same transaction, and the S3 delete runs strictly after commit.

**Audit details.** `admin_action_logs` has no free-form column today; V48 adds a nullable `details`
JSONB (Hypersistence `JsonBinaryType`, the project's JSONB convention). Nullable and unread by every
existing path, so it is backwards-compatible.

## Test strategy

- Unit — `SiteSyncStateTest` (generation monotonicity, `resetForWipe` vs `resetForRebaseline`),
  `DeltaSiteWipeServiceTest` (order, key collection, detach, both 409 guards, S3-after-commit),
  `PluginDeltaBaselineServiceTest` (`recaptureForSite`), the requeue exclusion, and the
  checkpoint-hook listener (consumes once, tolerates no activation).
- Contract — MockMvc for both endpoints: 200 shape, 400/403/404/409.
- gRPC handler — `generation` emitted in `SyncStateResponse` and both `SessionOpened` paths;
  `GENERATION_MISMATCH` rejection; `generation = 0` skips the guard.
- Integration (Testcontainers PG + LocalStack) — seed a full site → wipe → every site-scoped table
  empty, S3 keys gone, generation bumped, audit rows present; then a full client cycle
  (GetSyncState → SubmitSchema → FULL_SNAPSHOT → checkpoint build) → baselines re-captured at
  checkpoint seqs (not `MAX_VALUE`), segments requeued, `wipe_pending` cleared,
  `DELTA_AUTO_REINIT` audited.
- Frontend — `WipeHistoryDialog` confirm-gating and the mutation.
