# Session Handoff — Delta Client v2 (022)

> Continuation memory for a fresh session. Read this first, then `tasks.md` (checkboxes), then the CR.

**Branch**: `feature/022-delta-client-v2` (**pushed**; tracks `origin/...`). Working tree clean.
**PR**: draft [#39](https://github.com/quantum-soft-dev/data-forge-middleware/pull/39) → `develop` (WIP; do not merge until Tasks 4–5 done + `integrationTest` green).
**Design**: [docs/cr-delta-client-v2.md](../../docs/cr-delta-client-v2.md) (+ `.ru.md`) · contract: [src/main/proto/delta-ingestion.proto](../../src/main/proto/delta-ingestion.proto)
**Plan / tasks**: [plan.md](./plan.md) · [tasks.md](./tasks.md)

## How to resume (mandatory dev policy — see CLAUDE.md "Development Policy")
- **Test-first (TDD), WIP=1, one atomic commit per subtask**, tick the `tasks.md` checkbox in the same commit.
- Per-commit gate (pre-commit hook): `./gradlew test -PexcludeIntegration` (unit+contract) must be green.
- Integration tasks: verify locally with `./gradlew test --tests "<FQN>"` (Docker/LocalStack available).
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`. Push updates PR #39 + CI.

## Done ✅ (Tasks 0–3 complete, Task 4 automated work done — only manual T4.4 left)
- **Tasks 0–2** — contract+auth skeleton, changelog ingest (seq gap, idempotency, typed Value, keyless rule, segment persistence V30, reconciliation, SubmitSchema, watermark). See git log.
- **Task 3 — DONE**:
  - T3.1 fold engine; T3.2 checkpoint builder+scheduler (V31); T3.3 CSV snapshot.
  - **T3.4** — Bit BI `/sites/{siteId}/files` serves reconstructed checkpoint CSV for V2 sites (branch on `Site.clientApiVersion`; V1 unchanged). Added `ClientApiVersion` enum + mapped `sites.client_api_version` on `Site`.
  - **T3.5a** — `buildCheckpoint` is **incremental**: seeds from an all-INSERT **checkpoint frame**@M (S3 `checkpoints/{site}/_frame/seq={M}/frame.pb.gz`), folds only segments `first_seq > M`, writes a new frame@now. `ChangelogFold` now keeps the structured key per row (`FoldedRow{key,data}`); `CheckpointFrame.toRecords`; `ChangelogCodec` (extracted gzip+delimited-protobuf codec).
  - **T3.5b** — `ChangelogRetentionService.prune(siteId)`: deletes segments with `last_seq ≤ last_checkpoint_seq` (S3 + row), keeping `delta.retention.audit-window-segments` (default 20); wired into `CheckpointScheduler` after each build.
- **T4.1** — Parquet writer = **parquet-avro 1.15.2** (parquet-mr, not Arrow). Hadoop-free write/read via `OutputFile`/`InputFile` + `PlainParquetConfiguration`; shaded `hadoop-client-api/runtime` 3.4.1 on classpath only for the API types (relocated, no Spring/protobuf clash). Smoke test round-trips a typed file.
- **T4.2a** — `ParquetSchemaMapper`: PG-type → Avro record schema (`varchar/text`→string, `integer`→int, `bigint`→long, `numeric(p,s)`→decimal logical, `double precision`→double, `boolean`→boolean, `date`→date logical, `timestamp`→timestamp-micros, `bytea`→bytes; unknown→string; nullable→`[null,T]` union). Unit-tested.
- **T4.2b** — `ParquetCheckpointWriter.toParquet(table, TableSchema, rows)`: typed Parquet from the folded state. Coerces each wire `Value` **by declared column type** (not oneof case) — dates/timestamps/decimals arrive as ISO/decimal **strings** (FR-004), parsed and written through standard Avro logical-type conversions (`logicalTypeModel()` shared by reader). Hadoop-free in-memory `OutputFile` (ByteArrayOutputStream), **SNAPPY** (verified Hadoop-free). Floor bytes → `S3CheckpointStorage.uploadParquet` (`checkpoints/{site}/{table}/seq={seq}/snapshot.parquet`) + `Checkpoint.attachParquet`. Wired into `buildCheckpoint` (inject `SiteSchemaService`) — **only tables with a declared schema get Parquet**; CSV unchanged. Unit round-trip + integration.
- **T4.3** — Parquet **change feed**: same floor frame written to `egress/{siteId}/{table}/_change_date=YYYY-MM-DD/seq={seq}.parquet` (`LocalDate.now()`). Floor frame == floor snapshot (all-INSERT), so the same bytes go to both. Added `S3CheckpointStorage.uploadChangeFeed` + `listKeys`. Integration test.

Migrations: **V29, V30, V31** (no new migration for Task 3 frames — frame key is derived from `last_checkpoint_seq`). Delta pkg: `com.bitbi.dfm.delta.{domain,application,infrastructure,presentation}` (+ gRPC in `delta.grpc.v2`).

## Task 5 — DONE ✅ (Hardening & continuous mode)
- **T5.1 resume (`RESUME_FROM`)** — a periodic DELTA session that drops before `SessionEnd` (transport error or half-close) is staged **in-memory** keyed by site, leaving its batch active. A DELTA reconnect re-attaches to that batch and replies `SessionOpened(action=RESUME_FROM, resume_from_seq=staged+1)`; `SessionEnd` commits one segment spanning staged + replayed records. FULL_SNAPSHOT discards staged data. In-memory only → lost on restart (client then falls back to gap detection / re-baseline). _Per-session active-batch cleanup on give-up is the 60-min timeout; durable staging is a follow-up._
- **T5.2 backpressure** — progressive `Ack(acked_seq)` every `ACK_INTERVAL=100` accepted records + inbound gRPC flow control (`ServerCallStreamObserver.disableAutoRequest` + `request(1)` per record).
- **T5.3 metrics** — `DeltaMetrics` (`delta.sessions.started/.committed`, `delta.reconciliation.failures`, `delta.checkpoint.duration` timer, `delta.seq.lag` summary); wired into `DeltaIngestionService` + `CheckpointService`. `/actuator/metrics`.
- **T5.4 continuous mode** — new `SessionMode.CONTINUOUS` (proto regen). Server seals a segment at `CONTINUOUS_SEAL_RECORDS=100` (size trigger; **time trigger is a follow-up**), emits `SessionCommitted` per segment, opens the next under a fresh batch; final partial flushed on close. No `SessionEnd`. Periodic DELTA/FULL_SNAPSHOT reconciliation untouched. CR §9 updated.

**Integration suite green**: `./gradlew integrationTest` → 164 tests, 0 failures (30 skipped). Per-commit gate green on every commit.

## Task 6 — DONE ✅ (Batch history: Delta per-table stats, post-review addition)
Upload History showed real Delta batches as "0 files" (correct — Delta writes no `uploaded_files` —
but not useful). Added the real per-run signal:
- **T6.1** `ChangeRecordStats.computeByTable` extracted from `SessionReconciler` (shared, behavior unchanged).
- **T6.2** V32: nullable `stats` JSONB on `changelog_segments` + `ChangelogSegment.stats` (Hypersistence, mirrors `SiteSchema.schemaData`) + `TableChangeStats` record.
- **T6.3** `ChangelogSegmentService.persist` computes and stores the per-table stats on commit.
- **T6.4** `BatchDetailDto.deltaStats` (per-table list, sorted by table) via `ChangelogSegmentRepository.findByBatchId`.
- **T6.5** `BatchSummaryDto.deltaRecordCount`/`.deltaTableCount` (list-view totals) via bulk `findByBatchIdIn` (one query per page, not per batch).
- **T6.6/T6.7** Frontend: `BatchListView` shows "N changes • M tables"; `BatchDetailView` renders a Table/Inserted/Updated/Deleted breakdown (hides the file list/download-excel-compare actions, which don't apply) for Delta batches. v1 batches unaffected.
- **T6.8** This entry + [docs/delta-client-v2-guide.md](../../docs/delta-client-v2-guide.md) "Upload History" section.

All null/empty for v1 file-based batches. Backend gate + full frontend suite (797 tests) green on every commit.

**Found mid-session, not yet actioned**: [ui-requirements.md](./ui-requirements.md) — a design brief (not
written by this agent) for a much larger "Delta Sync" screen/tab (sync-state card, checkpoints table,
force-rebuild/re-baseline actions) requiring **new backend endpoints** (`.../delta/sync-state`,
`.../delta/checkpoints`, `.../delta/segments`, `.../delta/checkpoints/rebuild`, `.../delta/rebaseline`).
Its §5 ("Screen 1") is exactly what Task 6 above implements; §6–§8 (the "Delta Sync" tab) are new, unscoped
work — surfaced to the user, not started.

## Task 7 — DONE ✅ (HTTP file API disabled for V2 sites, strangler)
- **T7.1** `ClientApiVersionGuard` (site/application): `assertHttpFileApiAllowed(siteId)` — V2 site → `HttpFileApiDisabledException` → **409 + `code: CLIENT_API_V2_REQUIRED`**; V1/unknown sites pass through.
- **T7.2** Legacy v1 write endpoints guarded: `POST /api/dfc/batch/start`, `POST /api/dfc/batch/{id}/upload`, `POST /api/dfc/schema`.
- **T7.3** Device HTTP write endpoints guarded: `POST /api/v1/device/batches/start`, `POST /api/v1/device/files/batches/{id}/upload` (`DeviceControllerHelper.handleHttpFileApiDisabledException`).
- **Still open for V2 sites** (deliberate): batch drain (complete/fail/cancel/get, file metadata), client error logging (`/api/dfc/error`, `/api/v1/device/errors` — Delta v2 has no error-reporting RPC yet), token issuance; gRPC path untouched.
- **Fixtures**: `test-data.sql` sites pinned to `V1` explicitly (post-V29 they silently took the V2 column default — enforcement would have broken every file-path test); test-scoped `test-data-v2-site.sql` adds `store-v2.example.com` (V2) + its IN_PROGRESS batch. Contract suite: `FileApiClientVersionContractTest` (8 tests: 5 reject, 3 still-open).
- Docs: CR §14 (en+ru) + guide section "HTTP file API is closed for V2 sites".

## Task 8 — DONE ✅ (Event-driven delta Parquet egress; floor/change-feed retired)
User decision: egress = **sequence of per-segment delta Parquet files** applied in seq order; full
table = the delta file of a `FULL_SNAPSHOT` session; produced **as sessions commit**, not by cron.
- **Contract**: `egress/{site}/{table}/delta/seq={first}-{last}.parquet` (zero-padded → lexicographic = apply order); typed columns from `site_schemas` (all nullable) + non-null `_op` (INSERT/UPDATE/DELETE) + `_seq`; DELETE rows carry key columns (client v1 is keyless: INSERT/DELETE only, full rows). Only schema'd tables materialize.
- **Queue**: `changelog_segments` itself — V33 `egress_at` (NULL = pending; pre-existing rows backfilled so no historical storm) + partial index; picked per-site **head** `FOR UPDATE SKIP LOCKED` (`findNextPendingEgress`) → per-site seq order, sites in parallel, multi-instance safe.
- **Flow**: `DeltaSessionCommitService` registers an afterCommit wake → `DeltaEgressWorker` (bounded pool `delta.egress.max-concurrent`=2, discard-on-saturation, drain-until-empty) → `DeltaEgressService.egressNextPending` (claim+egress in one txn; crash → rollback → still pending). Fallback sweep `delta.egress.sweep-ms`=60s (test profiles: 1h so tests see only explicit wakes). Meter `delta.egress.segments`.
- **Retired (T8.5)**: floor `snapshot.parquet` + `_change_date` change-feed; V34 drops `checkpoints.s3_key_parquet`; obsolete integration tests deleted. Checkpoint cron now = Bit BI CSV + frame + retention only. This supersedes the old "Task 4 egress follow-ups" concerns.
- **Follow-ups**: a poison segment retries via sweep forever (warn-log only, no dead-letter); `ParquetCheckpointWriter.toParquet`/`ParquetSchemaMapper.toAvroSchema` no longer called from prod (kept as tested primitives of the shared writer); egress files are never pruned server-side (consumer owns lifecycle).

## Next ⬜
- **T4.4** — **manual** Power BI Incremental Refresh validation (no automated test; checklist in plan.md). _Cannot be run by the agent._ Egress is in place: floor `checkpoints/{site}/{table}/seq={seq}/snapshot.parquet` + change feed `egress/{site}/{table}/_change_date=…/`.
- **Decide on `ui-requirements.md` §6–§8** ("Delta Sync" tab + new endpoints) — separate scope from Task 6, user's call whether/when to pick up.
- **PR.3** — mark PR #39 ready, ensure CI `backend-test` green, address automated review, squash-merge. _Gated on the manual T4.4; user's call._

**PR.2 DONE** ✅ — [`docs/delta-client-v2-guide.md`](../../docs/delta-client-v2-guide.md) (gRPC contract, auth, full lifecycle, value typing/FR-004, keyless rules, reconciliation, resume/gap/re-baseline, continuous mode, backpressure, error codes, type mapping, pseudocode). CR links it.

## Gotchas / deferred / follow-ups
- **gRPC server NOT wired into Spring Boot lifecycle** — `DeltaIngestionService` is tested via in-process gRPC only; no netty `Server` bean listens on a port. Wire before end-to-end use.
- **Resume staging is in-memory** (`DeltaIngestionService.stagedSessions`) — lost on restart; unbounded across sites that drop-and-never-resume (mitigated only by the 60-min batch timeout, which doesn't evict the map entry). Durable staging + eviction is a follow-up.
- **Continuous mode**: only a **size** seal trigger (`CONTINUOUS_SEAL_RECORDS`); a **time** trigger is unimplemented. A continuous drop loses the unsealed tail and leaves the batch active → a reconnect hits `ACTIVE_SESSION_EXISTS` until timeout (no continuous resume).
- **Keyless-validation not wired into the live stream** (`ChangeRecordValidator` unit-tested, not called in the CHANGE handler).
- **`content_hash` not verified end-to-end** (T2.6 reconciles counts only).
- **FK cascade**: `changelog_segments.batch_id` lacks `ON DELETE CASCADE` → batch-retention deletion will hit FK once segments exist. Spawned task `task_42e807af`. Test workaround in `src/test/resources/test-data.sql` (deletes delta tables before batches); prod migration still needed.

## Spec side (separate CLIENT repo, C#/.NET — coordinated, not in this repo)
Client spec/plan/tasks shared via `/Users/boris/VM-Shared/`. **B-3 closed**: DBF→PostgreSQL `Column.type` mapping pinned in that `spec.md` (server stores `Column.type` free-form in `site_schemas` JSONB, so client is free in lowercase PG spellings). The old `cr-dbf-delta-client-v2.ru.md` is **dead** — ignore it; remaining CR refs in the client docs left as-is per user.

## Useful commands
```bash
./gradlew test -PexcludeIntegration                       # per-commit gate (unit+contract)
./gradlew test --tests "com.bitbi.dfm.delta.*"            # delta unit/contract
./gradlew test --tests "com.bitbi.dfm.integration.Checkpoint*IntegrationTest"  # Docker
git log --oneline develop..feature/022-delta-client-v2
```
