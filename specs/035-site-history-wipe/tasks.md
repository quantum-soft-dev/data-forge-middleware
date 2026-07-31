# 035 — Tasks

WIP = 1. Each task is test-first and lands as one atomic Conventional Commit referencing it.
Per-task gate: `./gradlew test -PexcludeIntegration` (+ frontend `tsc --noEmit`, `npm run lint`,
`vitest` when the frontend is touched). Before the PR: `./gradlew integrationTest`.

- [x] **T01 — Migration V48 + epoch on the sync state.**
  `V48__site_history_wipe.sql`: `site_sync_state.generation BIGINT NOT NULL DEFAULT 0`,
  `site_sync_state.wipe_pending BOOLEAN NOT NULL DEFAULT FALSE`, `admin_action_logs.details JSONB`,
  CHECK extension `chk_action_type` (+`SITE_HISTORY_WIPE`), CHECK extension
  `chk_plugin_audit_logs_action_type` (+`DELTA_AUTO_REINIT`).
  `SiteSyncState`: the two fields, `resetForWipe()`, `consumeWipePending()`.
  `AdminActionType.SITE_HISTORY_WIPE`, `AdminActionLog.details` + `successForSite(..., details)`,
  `PluginActionType.DELTA_AUTO_REINIT`.
  Tests: `SiteSyncStateTest` — `resetForWipe` zeroes watermark/checkpoint/schema, raises
  `rebaselineRequested`, clears `rebaselineNotifiedAt`/`rebuildRequested`, sets `wipePending`,
  increments `generation`; generation monotonic across repeated wipes; `resetForRebaseline` does
  **not** bump it; `consumeWipePending` returns true once then false.

- [x] **T02 — Proto + gRPC generation contract.**
  `generation = 5` on `SyncStateResponse` / `SessionOpened` / `SessionStart`,
  `ErrorCode.GENERATION_MISMATCH = 7`. `DeltaSyncStateService.SyncStateView` carries `generation`;
  `DeltaIngestionService` emits it in `getSyncState` and both `SessionOpened` paths (PROCEED and
  RESUME_FROM) and rejects a mismatched non-zero client generation with
  `ServerError(GENERATION_MISMATCH, NEED_REBASELINE)`.
  Tests: handler tests for each of the four emissions, the rejection, and `generation = 0` skipping
  the guard.

- [x] **T03 — `DeltaSiteWipeService`.**
  Repository additions (site-scoped key lookups + bulk deletes, `findBySiteIdForUpdate`,
  `detachBaselineBatchesOfSite`), the service itself (lock → live-session guard → collect keys →
  ordered deletes → detach → schema delete → `resetForWipe` → audit → batch re-count guard;
  S3 delete after commit), `SiteHistoryWipeSummary`, and the two 409 exceptions.
  Tests: `DeltaSiteWipeServiceTest` — deletion order, key collection incl. provisional segments,
  baseline detach reported, live session → `SessionInProgressException`, re-count > 0 →
  `ConcurrentSessionException`, S3 deferred to after commit, never-synced site → zero counts +
  generation 1.

- [x] **T04 — REST endpoints.**
  `POST /api/v1/account/sites/{siteId}/delta/wipe` and `POST /api/v1/sites/{siteId}/delta/wipe`,
  `SiteHistoryWipeRequestDto` (confirm = site domain) + `SiteHistoryWipeResponseDto`, 409 mapping.
  Tests: contract (MockMvc) for 200 shape, 400 (missing/wrong confirm), 403 (foreign site),
  404 (unknown site), 409 (both statuses), on both surfaces.

- [x] **T05 — Bit BI auto-reinit.**
  `PluginDeltaBaselineService.recaptureForSite` (with `recaptureForReinit` looping over it),
  `CheckpointRecordedEvent` published by `CheckpointService`, `PluginDeltaWipeReinitListener`
  consuming `wipe_pending` and recapturing, `PluginAuditService.logDeltaAutoReinit`, and the
  `FULL_SNAPSHOT` exclusion in `clearPluginSqlBySiteId`.
  Tests: recapture is site-scoped, flag consumed exactly once, absent/inactive activation still
  clears the flag, requeue excludes FULL_SNAPSHOT segments.

- [x] **T06 — Integration (Testcontainers).**
  Seed a full site (batches, files, segments incl. provisional, checkpoints, error logs, schema,
  plugin SQL + baselines, `baseline_batch_id`) → wipe → every site-scoped table empty, S3 objects
  gone, generation bumped, audit rows present. Then the post-wipe client cycle → baselines
  re-captured at checkpoint seqs, segments requeued, `wipe_pending` cleared, `DELTA_AUTO_REINIT`
  audited.

- [x] **T07 — Frontend.**
  `wipeSiteHistory` on both namespaces, `useWipeSiteHistory` invalidating
  sync-state/checkpoints/segments, `WipeHistoryDialog` (confirm-gated destructive button),
  "Danger zone" on `SiteDetailShell`, 409 toast.
  Tests: dialog gating + mutation.

- [ ] **T08 — Docs.**
  `docs/delta-client-v2-guide.md` (generation contract + wipe recovery sequence),
  `docs/site-history-wipe-client-guide.md` (the dbf-data-extractor contract),
  `docs/README.md` index, `CLAUDE.md` recent-changes entry.
