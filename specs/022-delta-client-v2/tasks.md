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
- [ ] **T0.4** Add `spring-grpc` / `grpc-java` + protobuf gradle plugin; `delta-ingestion.proto` codegen wired into build _(tests: build compiles, generated stubs importable in a smoke test)_

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
- [ ] **T2.7** `SubmitSchema` over gRPC reusing `SiteSchemaService` (validation, version bump) _(tests: contract — valid/invalid schema; version increment)_
- [ ] **T2.8** Advance `site_sync_state.last_applied_seq` atomically on commit _(tests: integration — watermark advances; concurrent safety)_

## Task 3 — Checkpointing & reconstruction (CR Phase 3)

- [ ] **T3.1** Fold engine: `latest checkpoint + deltas (M, now]` → current state (apply I/U/D, honor deletes) _(tests: unit — fold correctness incl. deletes, updates, keyless)_
- [ ] **T3.2** Scheduler builds checkpoint; `checkpoints` table + pointer in `site_sync_state` _(tests: integration — checkpoint@seq materialized; pointer updated)_
- [ ] **T3.3** Write `snapshot.csv.gz` (legacy) from checkpoint _(tests: integration — CSV bytes match folded state; gzip valid)_
- [ ] **T3.4** Wire Bit BI `/sites/{siteId}/files` to serve the reconstructed CSV _(tests: integration — Bit BI download returns checkpoint CSV; behavior unchanged)_
- [ ] **T3.5** Changelog retention: prune segments below durable checkpoint (keep audit window) _(tests: integration — old segments pruned; reconstruction still correct)_

## Task 4 — Power BI egress (CR Phase 4)

- [ ] **T4.1** Decide Parquet writer (parquet-mr vs Arrow); add dependency + smoke write _(tests: unit — write/read a typed Parquet file)_
- [ ] **T4.2** Write `snapshot.parquet` checkpoint (typed from `site_schemas`) _(tests: integration — Parquet schema/types match; row count)_
- [ ] **T4.3** Materialize Parquet change-feed partitions `egress/{siteId}/{table}/_change_date=…/` _(tests: integration — partitions by change date; all-INSERT frame for checkpoint)_
- [ ] **T4.4** Manual: validate Power BI Incremental Refresh against the feed + floor _(no automated test; documented checklist in plan.md)_

## Task 5 — Hardening & continuous mode (CR Phase 5)

- [ ] **T5.1** Resume (`RESUME_FROM`) — partial replay from staged data _(tests: contract — resume after mid-session drop)_
- [ ] **T5.2** Backpressure: progressive `Ack(acked_seq)` + flow control _(tests: contract — acks emitted; large session)_
- [ ] **T5.3** Metrics (Micrometer): sessions, seq lag, checkpoint duration, reconciliation failures _(tests: unit — meters registered/incremented)_
- [ ] **T5.4** Continuous-stream mode: server seals segments on time/size, emits `SessionCommitted` per segment _(tests: contract — segment sealed without `SessionEnd`)_

## Pre-PR (before opening the PR to `develop`)

- [ ] **PR.1** `./gradlew integrationTest` 100% green
- [ ] **PR.2** Feature documented in `docs/` (CR up to date; client guide for Delta Client v2)
- [ ] **PR.3** Open PR → `develop`; CI `backend-test` green; automated review addressed; squash-merge
