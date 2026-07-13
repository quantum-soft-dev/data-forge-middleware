# Tasks: 026-bitbi-delta-sql

Gate per task: `./gradlew test -PexcludeIntegration` (pre-commit hook). Before PR: `./gradlew integrationTest`.
One atomic Conventional Commit per task. See `plan.md` for design decisions D1–D6.

- [ ] **T1** — `BatchEventListener` → `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)`
      on both handlers; adjust tests relying on synchronous in-tx dispatch.
      Commit: `fix(plugin): dispatch plugin events after commit (T1)`
- [ ] **T2** — V38 migration + persistence: `changelog_segments.plugin_sql_at` (+partial index,
      backfill), `plugin_delta_baselines` table (+seed), `plugin_sql_generations.first_seq/last_seq`;
      `ChangelogSegment.pluginSqlAt`/`markPluginSqlProcessed()`; repo methods
      `findNextPendingPluginSql(limit)` (per-site head by first_seq, SKIP LOCKED),
      `clearPluginSqlBySiteId`; `PluginDeltaBaseline` entity/repo/JPA. Integration tests
      (Testcontainers) for migration + repo semantics.
      Commit: `feat(plugin): V38 delta-sql queue and per-table baselines (T2)`
- [ ] **T3** — `SqlStatementGenerator.formatJsonValue`: `byte[]` → `'\x<hex>'`,
      `BigDecimal` → `toPlainString()` unquoted; unit tests incl. V1-rendering unchanged.
      Commit: `fix(plugin): render bytea and BigDecimal SQL literals (T3)`
- [ ] **T4** — `DeltaSqlGenerationStrategy`: segment records → `JsonlChangeRecord` → SQL;
      op mapping, key-merge on INSERT, `seq > baseline` filter (boundary excluded),
      missing-schema skip + metric, unknown-column filter, FULL_SNAPSHOT skip, seq in
      terminator, stats. Unit tests with mocked `ChangelogSegmentService`.
      Commit: `feat(plugin): delta segment SQL generation strategy (T4)`
- [ ] **T5** — `SqlGenerationService` V2 branch: bypass batch-baseline cases, load segments not
      files, keep idempotency guard, persist first/last seq + null comparisonBatchId, reject
      `regenerateForBatch` for V2; existing V1 tests stay green.
      Commit: `feat(plugin): route delta v2 batches to segment strategy (T5)`
- [ ] **T6** — `DeltaSqlQueueService` (claim→generate→mark; inactive-plugin mark-skip;
      FULL_SNAPSHOT → suspend baselines MAX_VALUE + audit warn; failure → rollback) +
      `DeltaSqlSweepWorker` (wake + scheduled sweep + drain, clone of `DeltaEgressWorker`) +
      `BitBiPlugin.execute` routes V2 batches to `wake()`; config keys
      `plugin.sql-generation.delta-max-concurrent`, `delta-sweep-ms`.
      Commit: `feat(plugin): delta SQL sweep worker (T6)`
- [ ] **T7** — `PluginDeltaBaselineService.captureBaselines` from current checkpoints, wired into
      `BitBiPlugin.onActivate` + `PluginHistoryService.reinit`; reinit also clears
      `plugin_sql_at` for the account's V2 sites and wakes the worker.
      Commit: `feat(plugin): capture per-table delta baselines on activate/reinit (T7)`
- [ ] **T8** — `BitBiDeltaSqlIntegrationTest` (Testcontainers + LocalStack): activate → commit two
      delta segments → drain → `/sql-changes` in seq order with typed SQL → reinit →
      recapture/regenerate → FULL_SNAPSHOT → suspend + audit. Plus `docs/cr-bitbi-delta-sql.md`.
      Commit: `feat(plugin): delta SQL e2e + CR doc (T8)`
