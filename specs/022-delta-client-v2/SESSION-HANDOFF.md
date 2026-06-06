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

## Done ✅ (Tasks 0–3 complete, T4.1 done)
- **Tasks 0–2** — contract+auth skeleton, changelog ingest (seq gap, idempotency, typed Value, keyless rule, segment persistence V30, reconciliation, SubmitSchema, watermark). See git log.
- **Task 3 — DONE**:
  - T3.1 fold engine; T3.2 checkpoint builder+scheduler (V31); T3.3 CSV snapshot.
  - **T3.4** — Bit BI `/sites/{siteId}/files` serves reconstructed checkpoint CSV for V2 sites (branch on `Site.clientApiVersion`; V1 unchanged). Added `ClientApiVersion` enum + mapped `sites.client_api_version` on `Site`.
  - **T3.5a** — `buildCheckpoint` is **incremental**: seeds from an all-INSERT **checkpoint frame**@M (S3 `checkpoints/{site}/_frame/seq={M}/frame.pb.gz`), folds only segments `first_seq > M`, writes a new frame@now. `ChangelogFold` now keeps the structured key per row (`FoldedRow{key,data}`); `CheckpointFrame.toRecords`; `ChangelogCodec` (extracted gzip+delimited-protobuf codec).
  - **T3.5b** — `ChangelogRetentionService.prune(siteId)`: deletes segments with `last_seq ≤ last_checkpoint_seq` (S3 + row), keeping `delta.retention.audit-window-segments` (default 20); wired into `CheckpointScheduler` after each build.
- **T4.1** — Parquet writer = **parquet-avro 1.15.2** (parquet-mr, not Arrow). Hadoop-free write/read via `OutputFile`/`InputFile` + `PlainParquetConfiguration`; shaded `hadoop-client-api/runtime` 3.4.1 on classpath only for the API types (relocated, no Spring/protobuf clash). Smoke test round-trips a typed file.

Migrations: **V29, V30, V31** (no new migration for Task 3 frames — frame key is derived from `last_checkpoint_seq`). Delta pkg: `com.bitbi.dfm.delta.{domain,application,infrastructure,presentation}` (+ gRPC in `delta.grpc.v2`).

## Next ⬜ — Task 4 (Power BI Parquet egress)
- **T4.2** — write `snapshot.parquet` per table from the checkpoint state, **typed from `site_schemas`**. Needs: (a) PG-type→Avro/Parquet schema mapper (`varchar(n)/text`→string, `integer`→int, `bigint`→long, `numeric(p,s)`→decimal logical, `double precision`→double, `boolean`→boolean, `date`→date, `timestamp`→timestamp-micros, `bytea`→bytes; nullable→union); (b) `Value`→typed-Avro coercion (dates/decimals arrive as ISO/decimal **strings** per client FR-004 — parse by declared type); (c) in-memory `OutputFile` (ByteArrayOutputStream) → `S3CheckpointStorage.uploadParquet` + `Checkpoint.attachParquet`; (d) wire into `CheckpointService.buildCheckpoint` (inject `SiteSchemaService.getTableSchemas`). **Consider splitting T4.2a (schema mapper, unit) / T4.2b (write+coerce+S3+wire, integration).** Test: Parquet schema/types match `site_schemas`; row count.
- **T4.3** — materialize Parquet change-feed partitions `egress/{siteId}/{table}/_change_date=…/` (all-INSERT frame for the checkpoint floor). Integration test.
- **T4.4** — **manual** Power BI Incremental Refresh validation (no automated test; checklist in plan.md). _Cannot be run by the agent._

## Task 5 (Hardening & continuous mode) — not started
T5.1 resume (`RESUME_FROM`), T5.2 backpressure/`Ack`, T5.3 Micrometer metrics, T5.4 continuous-stream mode.

## Gotchas / deferred / follow-ups
- **gRPC server NOT wired into Spring Boot lifecycle** — `DeltaIngestionService` is tested via in-process gRPC only; no netty `Server` bean listens on a port. Wire before end-to-end use.
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
