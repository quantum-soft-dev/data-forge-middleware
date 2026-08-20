# CR: Bit BI SQL Generation from Delta v2 Changelog Segments (026)

**Status**: Implemented (T1–T8, on `feature/022-delta-client-v2`)
**Spec**: `specs/026-bitbi-delta-sql/` (spec / plan / tasks)
**Migration**: V38

## Problem

The Bit BI plugin generated incremental SQL only from a batch's `UploadedFile` rows. Delta v2
sites (gRPC ingestion, 022) never create uploaded files — their data lives in changelog
segments — so every completed delta batch was a **silent no-op**: `/sql-changes` stayed empty
forever, with no error. Only the `/files` endpoint had been adapted (checkpoint CSVs).

## What changed

### Data path (segments → SQL)

```
DeltaSessionCommitService.commit (segment + COMPLETED batch, one tx)
  └─ BatchCompletedEvent (now AFTER_COMMIT) → BitBiPlugin.execute
       └─ V2 batch? → DeltaSqlSweepWorker.wake()          V1: inline as before
            └─ DeltaSqlQueueService.processNextPending()   (+60s fallback sweep)
                 ├─ claim per-site head pending segment (FOR UPDATE SKIP LOCKED,
                 │   changelog_segments.plugin_sql_at IS NULL — egress-queue pattern)
                 ├─ SqlGenerationService.generateSqlForBatch   (semaphore, idempotency,
                 │    └─ DeltaSqlGenerationStrategy             audit, S3, persistence reused)
                 │        proto ChangeRecord → JsonlChangeRecord → SqlStatementGenerator
                 └─ markPluginSqlProcessed  (failure → rollback → sweep retries)
```

- **Ordering**: per-site head-of-line claiming keeps generations in segment seq order, so the
  unchanged `/sql-changes` contract (`created_at ASC`, `since` exclusive, cap 100) streams
  correctly. `plugin_sql_generations.first_seq/last_seq` record each generation's range.
- **Reliability**: the segment row is the durable work-queue entry; a missed wake or failed
  generation is retried by the sweep. Accounts without an active bit-bi activation mark-skip.
- **AFTER_COMMIT listener fix** (applies to V1 too): plugin events used to dispatch from inside
  the completing transaction; the async worker could run before the segment/batch row committed.

### Baselines (bootstrap correctness)

Per-table `plugin_delta_baselines(account_plugin_id, site_id, table_name, baseline_seq)`:

- Captured at **activation** and **reinit** from the site's current `checkpoints.seq` — the
  client downloads exactly those checkpoint snapshots via `/files`, so SQL is emitted only for
  records with `seq > baseline_seq(table)`. Since issue #113 those snapshots are Parquet
  (`<table>.parquet`); the bookkeeping above is unchanged, only the downloaded format is.
- **No row → baseline 0**: a table that appears later streams its full retained history as
  INSERT SQL (bootstrap without a baseline file).
- **Reinit** deletes prior generations (existing behavior), recaptures baselines, re-enqueues
  the site's segments (`plugin_sql_at = NULL`) and wakes the worker — the checkpoint-lag gap
  regenerates under the new baselines.
- The **batch-level** baseline (`account_plugins.baseline_batch_id`) still governs V1 sites and
  is bypassed for V2 sites.

### FULL_SNAPSHOT (source rebaseline)

A FULL_SNAPSHOT segment re-sends the whole dataset; rendering it as INSERTs would duplicate the
client's data. Instead: no SQL is emitted, the site's known tables are **suspended**
(`baseline_seq = Long.MAX_VALUE`), an audit entry (visible in plugin logs) and metric
`sql.generation.delta.rebaseline.detected` signal that a **plugin reinit is required**. Tables
created after the snapshot keep streaming (default-0).

### Typed SQL literals

`SqlStatementGenerator` now renders `byte[]` as a PostgreSQL bytea hex literal (`'\xdeadbeef'`)
and `BigDecimal` via `toPlainString()` (no scientific notation). Delta values arrive typed
through `ValueMapper`; previously `byte[]` would stringify to `[B@...` garbage. V1 rendering is
unchanged.

### S3 key collision fix

SQL file keys had second-granularity timestamps (`plugins/bit-bi/{acc}/{site}/{ts}.sql`); two
generations within the same second silently overwrote each other. Routine for V2 (time-sealed
sessions complete seconds apart) — keys now carry a random suffix (`{ts}_{rand8}.sql`).

## Migration V38

- `changelog_segments.plugin_sql_at` (+ partial index `site_id, first_seq WHERE plugin_sql_at
  IS NULL`); **backfilled to `created_at`** — pre-existing segments never retro-generate.
- `plugin_delta_baselines` table; **seeded** from current checkpoints for active bit-bi
  activations.
- `plugin_sql_generations.first_seq/last_seq` (nullable; delta path only).

## Config

| Key | Default | Meaning |
|---|---|---|
| `plugin.sql-generation.delta-max-concurrent` | 2 | delta-SQL worker pool size |
| `plugin.sql-generation.delta-sweep-ms` | 60000 | fallback sweep interval |

Metrics added: `sql.generation.delta.segments.processed`, `.records.skipped.no_schema`,
`.records.skipped.empty_update`, `.segments.skipped.inactive`, `.rebaseline.detected`.

## Operator / client guidance

- **Existing V2 activations must reinit once after deploy** — history before V38 is marked
  processed, and reinit establishes consistent baselines (then re-download `/files`).
- **Download `/files` promptly after reinit.** Checkpoints fold forward asynchronously; if one
  advances between baseline capture and the CSV download, the overlapping range appears in both
  the CSV and the SQL stream (UPDATE/DELETE re-apply harmlessly; INSERTs may conflict
  client-side). The window is bounded by the checkpoint fold cadence.
- **After a source rebaseline (FULL_SNAPSHOT)** the plugin suspends SQL for the site's tables;
  the audit log carries the warning. Reinit the plugin, re-download `/files`, resume polling.
- SQL regeneration was **retired entirely** (issue #190; it never worked end to end and could not
  serve segment-backed batches). Re-creating one batch's SQL is delete the generation + manual
  `POST .../generate-sql`, which supports segment-backed batches — with two caveats: the new row
  gets a new `created_at`, so a client whose `since` cursor already passed the batch receives its
  SQL a second time (and the SQL is not idempotent); and generate only renders records above the
  plugin's current delta baselines, so after a reinit re-captured them an older batch renders
  nothing. In both cases the answer is `reinit`.

## Known residual risks

1. Reinit→download overlap (above) — small window, documented behavior.
2. FULL_SNAPSHOT requires a manual reinit; until then affected tables emit no SQL (audit is the
   signal).
3. If retention ever outpaces a fully-backlogged queue, unprocessed segments could be purged;
   sweep cadence (60 s) vs retention (days) makes this negligible.

## Verification

- Unit/contract: `./gradlew test -PexcludeIntegration` (per-task gate, green per commit).
- Integration: `./gradlew integrationTest`, incl. `BitBiDeltaSqlIntegrationTest` — e2e
  segments → queue → SQL in S3 → `/sql-changes` (seq order, bytea/decimal literals), reinit
  regeneration, FULL_SNAPSHOT suspension; `DeltaSqlQueueRepositoryIntegrationTest` — V38 +
  queue/baseline persistence semantics.
