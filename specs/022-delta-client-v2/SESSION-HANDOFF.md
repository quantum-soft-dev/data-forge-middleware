# Session Handoff — Delta Client v2 (022)

> Continuation memory for a fresh session. Read this first, then `tasks.md` (checkboxes), then the CR.

**Branch**: `feature/022-delta-client-v2` (HEAD `5d4167c`, **local only — NOT pushed**), 20 commits over `develop`. Working tree clean.
**Design**: [docs/cr-delta-client-v2.md](../../docs/cr-delta-client-v2.md) (+ `.ru.md`) · contract: [src/main/proto/delta-ingestion.proto](../../src/main/proto/delta-ingestion.proto)
**Plan / tasks**: [plan.md](./plan.md) · [tasks.md](./tasks.md)

## How to resume (mandatory dev policy — see CLAUDE.md "Development Policy")
- **Test-first (TDD), WIP=1, one atomic commit per subtask**, tick the `tasks.md` checkbox after the commit lands.
- Per-commit gate (pre-commit hook): `./gradlew test -PexcludeIntegration` (unit+contract, no Docker) must be green.
- Integration tasks: verify locally with `./gradlew test --tests "<FQN>"` (Docker is available; Testcontainers uses LocalStack `3.8`).
- Commit msgs: Conventional Commits + trailer `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Branch protection on `develop` requires the `backend-test` CI check green (PR-only, squash-merge).
- Enable the hook on a fresh clone once: `git config core.hooksPath .githooks`.

## Done ✅ (17 subtasks)
- **Task 0** — decisions recorded (OQ-1, OQ-3) + clarification.
- **Task 1** (T1.1–T1.5) — V29 `site_sync_state` + `sites.client_api_version`; gRPC Bearer auth interceptor (binds `SITE_ID`/`ACCOUNT_ID` via Auth V2 `TokenService`); `GetSyncState`; `StreamChanges` skeleton (session = batch via `BatchLifecycleService`); one-active-session → `ACTIVE_SESSION_EXISTS`.
- **Task 2** (T2.1–T2.8) — seq gap detection; in-session idempotency (`SessionChangeBuffer`); typed `Value` mapping (`ValueMapper`); keyless rule (`ChangeRecordValidator`); changelog segment persistence + S3 (V30, `ChangelogSegmentService`, `S3ChangelogSegmentStorage`); `SessionEnd` reconciliation (per-table counts, hard-fail); `SubmitSchema` (reuses `SiteSchemaService`); commit orchestration (persist segment → advance watermark → complete batch → `SessionCommitted`).
- **Task 3** — T3.1 fold engine (`ChangelogFold`); T3.2 checkpoint builder + scheduler (V31, `CheckpointService`/`CheckpointScheduler`); T3.3 CSV snapshot from checkpoint (`CsvSnapshotWriter`, `S3CheckpointStorage`).

Migrations added: **V29, V30, V31**. Delta package: `com.bitbi.dfm.delta.{domain,application,infrastructure,presentation}` (+ generated gRPC in `com.bitbi.dfm.delta.grpc.v2`).

## Key decisions (in CR)
- **2b**: changelog = source of truth + periodic checkpoints (not live state, not pure on-demand).
- **OQ-1**: client sends unique deltas; **keyless tables = INSERT/DELETE only** (no UPDATE); keyed tables = I/U/D.
- **OQ-3**: `sites.client_api_version` (V1 legacy HTTP / V2 Delta gRPC), **default V2**, existing backfilled V1.
- Transport: **gRPC bidi streaming + Protobuf**; protobuf-gradle-plugin 0.9.4 works on Gradle 9. grpc/protobuf versions 1.68.1 / 3.25.5.
- Session = Batch; reconciliation hard-fail; in-band `ServerError` for protocol failures.

## Next ⬜ — finish Task 3
- **T3.4** — wire Bit BI `GET /api/v1/plugins/bit-bi/sites/{siteId}/files` to serve the **reconstructed checkpoint CSV** for V2 sites. _Touches existing plugin code — regression risk._
  - Entry point: `plugin/presentation/BitBiPluginApiController` (listFiles ~L275, download ~L311) → delegates to `CsvFileQueryService.listFiles(accountId, siteId)` + a download path.
  - Plan: branch on the site's `client_api_version`. V2 → list/serve from `checkpoints` (`s3_key_csv`) via `S3CheckpointStorage`; V1 → existing behavior unchanged. Map checkpoint CSVs to the existing `FileDto`/download contract. Integration test: a V2 site's `/files` returns the reconstructed CSV; V1 unchanged.
- **T3.5** — changelog retention: prune `changelog_segments` below the durable checkpoint (keep audit window). Isolated; integration test (segments pruned, reconstruction still correct).

## Gotchas / deferred / follow-ups
- **gRPC server is NOT wired into Spring Boot lifecycle.** `DeltaIngestionService` is a `@Component` and fully tested via an **in-process** gRPC server in contract tests, but no real gRPC server bean listens on a port. Wiring a server (spring-grpc starter or a grpc-netty `Server` bean + interceptor registration) is still required for the service to be reachable. Not in any task yet — add before end-to-end use.
- **Keyless-validation not wired into the live stream**: `ChangeRecordValidator` (T2.4) is unit-tested but not called in the `StreamChanges` CHANGE handler (needs per-session schema lookup). Wire when convenient (planned alongside schema use).
- **`content_hash` not verified end-to-end**: T2.6 reconciles per-table counts only; the `SessionEnd.content_hash` is carried but not recomputed/compared server-side. Follow-up.
- **FK cascade**: `changelog_segments.batch_id` lacks `ON DELETE CASCADE` → batch retention deletion will hit a FK violation once segments exist. Spawned task `task_42e807af`. Test-only workaround is in `src/test/resources/test-data.sql` (deletes delta tables before batches); production migration still needed.
- **Future phases (beyond Task 3)**: Task 4 = Power BI Parquet change-feed egress (needs a Parquet writer — parquet-mr vs Arrow, decided in tasks T4.1). Task 5 = resume/backpressure/metrics + continuous-stream mode.

## Decision pending from user
Whether to (1) continue with T3.4/T3.5 autonomously, or (2) push the branch / open a draft PR for review before modifying the Bit BI plugin code (T3.4). Nothing pushed yet.

## Useful commands
```bash
./gradlew test -PexcludeIntegration                       # per-commit gate (unit+contract)
./gradlew test --tests "com.bitbi.dfm.delta.*"            # all delta unit/contract tests
./gradlew test --tests "com.bitbi.dfm.integration.Checkpoint*IntegrationTest"  # Docker
git log --oneline develop..feature/022-delta-client-v2    # the 20 commits
```
