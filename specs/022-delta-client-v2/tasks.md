# Tasks — Delta Client v2 (022)

**Branch**: `feature/022-delta-client-v2` · **Design**: [docs/cr-delta-client-v2.md](../../docs/cr-delta-client-v2.md) · **Overview**: [plan.md](./plan.md)

## How to work this list

- **One checkbox = one test-first cycle = one atomic commit.** Tick it **after** the commit lands.
- **Test-first**: write the test(s) named in the subtask (red) → implement → all green → commit → tick.
- **WIP = 1**: do not start the next subtask until the current one is committed.
- **Per-commit gate** (pre-commit hook): `./gradlew test -PexcludeIntegration` green (+ `npm --prefix frontend test` if frontend touched).
- **Commit message**: Conventional Commit referencing the id, e.g. `feat(delta): add seq gap detection (T2.1)`.
- A **Task** is done when all its subtasks are checked.

Legend: `[ ]` todo · `[x]` done. Each subtask line ends with _(tests: …)_ describing the coverage that must be green.

---

## Task 0 — Pre-flight

- [x] **T0.1** Resolve OQ-1 (keyless duplicate rows) and record the decision in the CR _(no code; doc change)_ — **no duplicates; keyed tables = I/U/D; keyless = INSERT/DELETE only (no UPDATE)**
- [x] **T0.2** Resolve OQ-3 (how a site is flagged as Delta v2 ingestion) and record it in the CR _(no code; doc change)_ — **`client_api_version` (V1/V2), default V2; existing backfilled to V1**
- [x] **T0.3** Create branch `feature/022-delta-client-v2` off `develop` _(no tests)_
- [x] **T0.4** Add `spring-grpc` / `grpc-java` + protobuf gradle plugin; `delta-ingestion.proto` codegen wired into build _(tests: build compiles, generated stubs importable in a smoke test)_ — `com.google.protobuf` 0.9.4 + `protoc-gen-grpc-java`; `DeltaProtoCodegenSmokeTest`

## Task 1 — Contract & session skeleton (CR Phase 1)

- [x] **T1.1** Flyway `V29__delta_site_sync_state.sql`: `site_sync_state` table + JPA entity/repository; **add `sites.client_api_version` (V1/V2, default V2, backfill existing → V1)** _(tests: integration — default V2 + NOT NULL; CHECK rejects non-V1/V2; `site_sync_state` round-trip. Backfill-to-V1 is by DDL construction — test fixtures seed post-migration so take the V2 default)_
- [x] **T1.2** Bearer-token gRPC interceptor reusing Auth V2; derive `site_id` from token; reject missing/invalid _(tests: unit — valid binds `SITE_ID` context; missing / non-Bearer / invalid → UNAUTHENTICATED, handler not invoked. Cross-site enforcement is downstream in handlers via `DeltaAuthInterceptor.SITE_ID`)_
- [x] **T1.3** `GetSyncState` RPC returns `last_applied_seq` / `schema_version` / `RecoveryAction` _(tests: contract — in-process gRPC; empty state → 0/PROCEED; existing watermark returned. `DeltaIngestionService` + `DeltaSyncStateService`)_
- [x] **T1.4** `StreamChanges` skeleton: `SessionStart` → open batch via `BatchLifecycleService`; `SessionEnd` → complete batch; emit `SessionOpened` / `SessionCommitted` _(tests: contract — bidi happy-path opens+commits a batch. Interceptor extended to bind `ACCOUNT_ID`. Change persistence deferred to Task 2)_
- [x] **T1.5** One-active-session-per-site rule → `ACTIVE_SESSION_EXISTS` _(tests: contract — second concurrent SessionStart → in-band `ServerError{ACTIVE_SESSION_EXISTS}`, batch not completed. Reuses one-active-batch via `ActiveBatchExistsException`)_

## Task 2 — Changelog ingest (CR Phase 2)

- [x] **T2.1** Per-site `seq` validation + gap detection (`first_seq == server_last_seq + 1`) → `SEQUENCE_GAP` / `NEED_REBASELINE` _(tests: contract — DELTA gap rejected (NEED_REBASELINE); contiguous proceeds; FULL_SNAPSHOT exempt)_
- [x] **T2.2** Idempotency: dedup by `(site_id, seq)`; replay of applied seq ignored _(tests: unit — `SessionChangeBuffer` accepts strictly-increasing seq; duplicate / out-of-order / already-applied ignored)_
- [x] **T2.3** `ChangeRecord` typed `Value` mapping (null vs absent, decimal exactness, all op types) _(tests: unit — `ValueMapper`: each scalar type, explicit/empty null, exact BigDecimal, present-null vs absent via map containsKey)_
- [x] **T2.4** Keyless / all-fields-key handling: reject `UPDATE`; `DELETE`+`INSERT` round-trip _(tests: unit — `ChangeRecordValidator` rejects UPDATE for keyless tables; allows I/D for keyless and all ops for keyed. Wiring into the stream uses schema lookup, landed with T2.7)_
- [x] **T2.5** `changelog_segments` persistence + write raw segment to S3 _(V30 + `ChangelogSegment` entity/repo + `S3ChangelogSegmentStorage` + `ChangelogSegmentService`; integration test: S3 object + metadata row. Wiring into the session commit lands with T2.8)_
- [x] **T2.6** `SessionEnd` reconciliation (per-table counts), hard-fail → `RECONCILIATION_FAILED`, no commit _(tests: unit `SessionReconciler` + contract: mismatch → RECONCILIATION_FAILED, batch not completed. `content_hash` end-to-end verification is a follow-up)_
- [x] **T2.7** `SubmitSchema` over gRPC reusing `SiteSchemaService` (validation, version bump) _(tests: contract — valid schema returns version; `InvalidSchemaException` → INVALID_ARGUMENT. proto `SchemaRequest` → `SchemaUploadRequestDto` mapping)_
- [x] **T2.8** Advance `site_sync_state.last_applied_seq` atomically on commit _(tests: integration — `advanceWatermark` creates row + advances monotonically; contract — onSessionEnd orchestrates persist-segment → advance-watermark → complete-batch → `SessionCommitted` with real s3Key)_

## Task 3 — Checkpointing & reconstruction (CR Phase 3)

- [x] **T3.1** Fold engine: `latest checkpoint + deltas (M, now]` → current state (apply I/U/D, honor deletes) _(tests: unit `ChangelogFold` — INSERT/UPDATE-merge/DELETE within a fold; continues from a prior checkpoint state)_
- [x] **T3.2** Scheduler builds checkpoint; `checkpoints` table + pointer in `site_sync_state` _(V31; `CheckpointService.buildCheckpoint` folds S3 segments → per-table checkpoint rows + advances pointer; `CheckpointScheduler`. integration test: rows + pointer)_
- [x] **T3.3** Write `snapshot.csv.gz` (legacy) from checkpoint _(`CsvSnapshotWriter` (commons-csv) + unit test; `S3CheckpointStorage`; `CheckpointService` writes per-table CSV + attaches `s3_key_csv`; integration test: S3 object + content)_
- [x] **T3.4** Wire Bit BI `/sites/{siteId}/files` to serve the reconstructed CSV _(tests: integration — Bit BI download returns checkpoint CSV; behavior unchanged)_
- [x] **T3.5a** Incremental `buildCheckpoint`: seed from the latest **all-INSERT checkpoint frame**@M, fold only segments with `first_seq > M`, write new frame@now (+ CSV + rows + pointer). Makes reconstruction independent of pre-checkpoint segments (CR §8.D). _(tests: unit `ChangelogFold` retains key + emits frame; integration — checkpoint correct after pre-checkpoint segments removed, i.e. seeded from frame not full history)_
- [x] **T3.5b** Changelog retention: prune segments below durable checkpoint (keep audit window); wire into scheduler _(tests: integration — old segments pruned; audit window kept; reconstruction still correct)_

## Task 4 — Power BI egress (CR Phase 4)

- [x] **T4.1** Decide Parquet writer (parquet-mr vs Arrow); add dependency + smoke write _(tests: unit — write/read a typed Parquet file)_ — **parquet-avro 1.15.2** (Hadoop-free via OutputFile/InputFile + `PlainParquetConfiguration`; shaded `hadoop-client-api/runtime` 3.4.1 on classpath for the API types)
- [x] **T4.2a** PG-type → Avro/Parquet schema mapper from `site_schemas` (`varchar/text`→string, `integer`→int, `bigint`→long, `numeric(p,s)`→decimal logical, `double precision`→double, `boolean`→boolean, `date`→date, `timestamp`→timestamp-micros, `bytea`→bytes; nullable→union) _(tests: unit — field types/logical types/nullability match)_
- [x] **T4.2b** Write `snapshot.parquet` per table from checkpoint state (Value→typed-Avro coercion, in-memory OutputFile → `S3CheckpointStorage.uploadParquet` + `Checkpoint.attachParquet`; wire into `buildCheckpoint`) _(tests: integration — Parquet schema/types match `site_schemas`; row count)_
- [x] **T4.3** Materialize Parquet change-feed partitions `egress/{siteId}/{table}/_change_date=…/` _(tests: integration — partitions by change date; all-INSERT frame for checkpoint)_
- [ ] **T4.4** Manual: validate Power BI Incremental Refresh against the feed + floor _(no automated test; documented checklist in plan.md)_

## Task 5 — Hardening & continuous mode (CR Phase 5)

- [x] **T5.1** Resume (`RESUME_FROM`) — partial replay from staged data _(tests: contract — resume after mid-session drop)_
- [x] **T5.2** Backpressure: progressive `Ack(acked_seq)` + flow control _(tests: contract — acks emitted; large session)_
- [x] **T5.3** Metrics (Micrometer): sessions, seq lag, checkpoint duration, reconciliation failures _(tests: unit — meters registered/incremented)_
- [x] **T5.4** Continuous-stream mode: server seals segments on time/size, emits `SessionCommitted` per segment _(tests: contract — segment sealed without `SessionEnd`)_

## Task 6 — Batch history: Delta per-table stats (post-review addition)

Delta v2 batches show "0 files" in Upload History (correct — Delta writes no `uploaded_files`,
only changelog segments). Surface the real per-run signal instead: tables touched + insert/update/
delete counts per table, computed once at commit and persisted alongside the segment.

- [x] **T6.1** Extract per-table I/U/D counting into `ChangeRecordStats.computeByTable(records)`; refactor `SessionReconciler.reconcile` to use it _(tests: unit — new `ChangeRecordStatsTest`; existing `SessionReconcilerTest` stays green unmodified in behavior)_
- [x] **T6.2** `V32__changelog_segment_stats.sql`: nullable `stats JSONB` on `changelog_segments`; `TableChangeStats` record + `ChangelogSegment.stats` (Hypersistence `JsonBinaryType`, mirrors `SiteSchema.schemaData`) _(tests: integration — persist/reload round-trip; null-safe for pre-migration rows)_
- [x] **T6.3** Wire `ChangeRecordStats.computeByTable` into `ChangelogSegmentService.persist`, store on the segment _(tests: unit — persisted segment's stats match the records passed in)_
- [x] **T6.4** `BatchDetailDto.deltaStats` (per-table list) sourced from `ChangelogSegmentRepository.findByBatchId`; wire into `BatchHistoryService.getBatchDetails` _(tests: unit service mapping — present for Delta batches, empty/absent for v1; contract — response shape)_
- [x] **T6.5** `BatchSummaryDto.deltaRecordCount` / `.deltaTableCount` (list-view signal, not full breakdown); bulk-fetch segments for a page via new `ChangelogSegmentRepository.findByBatchIdIn` _(tests: unit — `listBatchHistory` mapping, no N+1)_
- [x] **T6.6** Frontend: extend `BatchSummary`/`BatchDetail` types; `BatchListView` shows "N changes • M tables" instead of "0 files • 0 B" for Delta batches _(tests: vitest)_
- [x] **T6.7** Frontend: `BatchDetailView` renders a per-table insert/update/delete stats table for Delta batches _(tests: vitest)_
- [x] **T6.8** Docs: note the history stats surface in `docs/delta-client-v2-guide.md` / CR; refresh `SESSION-HANDOFF.md` _(no tests; doc change)_

## Task 7 — Disable HTTP file API for V2 sites (strangler enforcement)

With Delta Client v2 as the go-forward ingestion path, sites flagged `client_api_version = V2`
must no longer push whole CSV files over HTTP. Enforcement is **per-site** (strangler): V1 sites
keep working unchanged; V2 sites get **409 Conflict** with machine-readable `code:
"CLIENT_API_V2_REQUIRED"` on the guarded write endpoints. Drain/read endpoints (batch
complete/fail/cancel/get, file metadata) and client error logging (`/api/dfc/error`,
`/api/v1/device/errors`) stay available for all sites — Delta v2 has no error-reporting RPC yet.
Token issuance (`/api/dfc/auth`, `/api/v1/device/authorize|token|auth/**`) and the gRPC Delta
path are untouched.

- [x] **T7.1** `ClientApiVersionGuard.assertHttpFileApiAllowed(siteId)` (site/application) + nested `HttpFileApiDisabledException` (carries `code = CLIENT_API_V2_REQUIRED`) _(tests: unit — V1 site passes; V2 site throws; unknown site no-op (auth layer owns that failure))_
- [x] **T7.2** Enforce on legacy v1 endpoints: `POST /api/dfc/batch/start`, `POST /api/dfc/batch/{batchId}/upload`, `POST /api/dfc/schema` → 409 + code. Fixtures: pin seeded sites to `V1`, add V2 site `store-v2.example.com` + its IN_PROGRESS batch _(tests: contract — V2 site rejected on all three with code; V1 site still 201/200 (existing tests); complete/fail/cancel still allowed for V2 site)_
- [x] **T7.3** Enforce on device HTTP endpoints: `POST /api/v1/device/batches/start`, `POST /api/v1/device/files/batches/{batchId}/upload` → 409 + code via `DeviceControllerHelper` _(tests: contract — V2 site rejected with code; V1 site unaffected (existing tests); device error logging still works for V2 site)_
- [x] **T7.4** Docs: CR + `delta-client-v2-guide.md` (client migration note) + SESSION-HANDOFF refresh _(no tests; doc change)_

## Task 8 — Event-driven delta Parquet egress (replaces floor/change-feed egress)

Consumer model change (user decision): egress is a **sequence of per-segment delta Parquet files**
loaded in `seq` order, produced **as sessions commit** (worker pool, not the daily cron). A full
table is just the delta file of a `FULL_SNAPSHOT` session (all-INSERT) — the server never rebuilds
a floor for egress. Files carry typed columns from `site_schemas` plus `_op` (I/U/D) and `_seq`;
client v1 is keyless (INSERT/DELETE only), DELETE rows carry the key columns. The durable work
queue is `changelog_segments` itself (`egress_at IS NULL` = pending, picked with
`FOR UPDATE SKIP LOCKED`, per-site head first → per-site seq order, multi-instance safe). The
checkpoint cron stays for Bit BI CSV + retention frames; the floor `snapshot.parquet` and
`_change_date` change-feed (T4.2b/T4.3) are **removed**.

- [x] **T8.1** V33: nullable `egress_at` on `changelog_segments`; `ChangelogSegment.markEgressed()`; repository `findNextPendingEgress(limit)` — per-site **head** pending segments, `FOR UPDATE SKIP LOCKED` _(tests: integration — only per-site earliest pending returned; egressed excluded; pre-migration rows pending-safe)_
- [x] **T8.2** Delta Parquet writer: schema = mapped table schema + non-null `_op` string + `_seq` long; INSERT/UPDATE rows from `data`, DELETE rows from `key` _(tests: unit — round-trip via logicalTypeModel, ops/seq preserved, DELETE carries key values, typed columns match)_
- [ ] **T8.3** `DeltaEgressService.egressSegment(id)`: read segment records from S3, group by table, write `egress/{siteId}/{table}/delta/seq={first}-{last}.parquet` for schema'd tables, mark segment egressed; `delta.egress.segments` counter _(tests: integration — S3 objects + `egress_at` set; schema-less tables skipped but segment still marked; idempotent re-run)_
- [ ] **T8.4** `DeltaEgressWorker`: bounded pool (`delta.egress.max-concurrent`, default 2) + `wake()` after session commit (SessionEnd + continuous seal) + fallback sweep (`delta.egress.sweep-ms`); drain picks via T8.1 query until empty _(tests: integration — committed session produces delta parquet without cron; two pending segments of one site egress in seq order)_
- [ ] **T8.5** Remove floor Parquet + change-feed egress: `CheckpointService` writes CSV + frame only; drop `Checkpoint.attachParquet` / `uploadParquet` / `uploadChangeFeed` (+ V34 drops `checkpoints.s3_key_parquet`); delete obsolete tests (CheckpointParquet / CheckpointChangeFeed integration) _(tests: existing checkpoint CSV/frame suites stay green)_
- [ ] **T8.6** Docs: CR §12 + §8.D (en+ru) — egress = sequential delta Parquet; guide "What the server produces" rewrite (file contract, `_op`/`_seq`, full = FULL_SNAPSHOT segment); SESSION-HANDOFF refresh _(no tests; doc change)_

## Pre-PR (before opening the PR to `develop`)

- [x] **PR.1** `./gradlew integrationTest` 100% green — 164 tests, 0 failures (30 skipped)
- [x] **PR.2** Feature documented in `docs/` (CR up to date; [delta-client-v2-guide.md](../../docs/delta-client-v2-guide.md))
- [ ] **PR.3** Open PR → `develop`; CI `backend-test` green; automated review addressed; squash-merge
