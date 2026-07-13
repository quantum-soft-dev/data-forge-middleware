# Feature Specification: Bit BI SQL Generation from Delta v2 Changelog Segments

**Feature Branch**: `feature/022-delta-client-v2` (folded into the 022 stack by user decision)
**Created**: 2026-07-13
**Status**: In progress
**Input**: Analysis finding: for `clientApiVersion = V2` sites the Bit BI plugin silently
generates no SQL — `SqlGenerationService` reads only `UploadedFile` rows, which delta ingestion
never creates. Every `BATCH_COMPLETED` for a V2 site is a silent no-op, so `/sql-changes`
returns nothing for V2 sites forever, with no error or warning.

## User Scenarios & Testing

### User Story 1 - Incremental SQL for V2 sites via the unchanged client contract (Priority: P1)

A Bit BI client polling `GET /api/v1/plugins/bit-bi/sql-changes?siteId&since` for a Delta v2
site receives INSERT/UPDATE/DELETE SQL derived from the site's changelog segments, in per-site
sequence order, exactly as it does today for V1 DBF/CDC sites — no client changes.

**Acceptance Scenarios**:

1. **Given** an active bit-bi plugin and a V2 site with declared schemas, **When** a delta
   session commits a segment, **Then** a `PluginSqlGeneration` is produced containing the
   segment's changes as SQL (correct ops, PK-based WHERE, typed literals incl. bytea/decimal)
   and `/sql-changes` returns it.
2. **Given** two segments committed in seq order, **Then** `/sql-changes` returns their SQL in
   the same order (created_at ASC == seq order).
3. **Given** a table with no declared schema, **Then** its records are skipped with a warning
   (parity with the CDC strategy), other tables still generate.
4. **Given** a missed wake (process restart), **Then** the fallback sweep generates the SQL
   within one sweep interval — no segment is ever silently lost.
5. **Given** a V1 site, **Then** behavior is byte-identical to before (except plugin events now
   dispatch strictly after commit).

### User Story 2 - Correct bootstrap on activation/reinit (Priority: P1)

On plugin activation or reinit, the client downloads full checkpoint CSVs via `/files` and then
receives SQL only for changes **after** those snapshots — per table.

**Acceptance Scenarios**:

1. **Given** activation/reinit, **Then** per-table baseline seqs are captured from the site's
   current checkpoints; SQL includes only records with `seq > baseline_seq(table)`.
2. **Given** a table that appears after activation (no baseline row), **Then** its full retained
   history streams as INSERT SQL (bootstrap without CSV).
3. **Given** reinit, **Then** prior generations are deleted (existing behavior) and the site's
   segments re-enter the SQL queue so the checkpoint-lag gap is regenerated under the new
   baselines.

### User Story 3 - Rebaseline safety (Priority: P2)

A source-side rebaseline (FULL_SNAPSHOT segment) must not flood the BI client with duplicate
INSERTs.

**Acceptance Scenarios**:

1. **Given** a FULL_SNAPSHOT segment, **Then** no SQL is generated for it; baselines of the
   site's known tables are suspended (`Long.MAX_VALUE`) until reinit; an audit warning and a
   metric signal that reinit is required.
2. **Given** a table created after the snapshot, **Then** it still streams (default-0 baseline).

## Requirements

- **FR-1 (trigger reliability)**: V2 SQL generation is queue-driven: `changelog_segments.plugin_sql_at`
  (NULL = pending) + partial index; event wake (`BitBiPlugin.execute` → worker wake) plus a
  scheduled fallback sweep. Failure → rollback → retried by sweep. Inactive/absent plugin →
  segment marked processed (never generates, never accumulates).
- **FR-2 (commit visibility)**: plugin event dispatch happens strictly after the completing
  transaction commits (`@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)`),
  because `DeltaSessionCommitService` completes the batch inside its own transaction.
- **FR-3 (ordering)**: per-site head-of-line claiming by `first_seq` (`FOR UPDATE SKIP LOCKED`)
  so generations are created in seq order; `/sql-changes` contract (created_at ASC, since
  exclusive, cap 100) unchanged.
- **FR-4 (data mapping)**: proto `ChangeRecord` → existing `JsonlChangeRecord` →
  `SqlStatementGenerator.generateFromJsonl`. INSERT merges key into data; UPDATE sets changed
  cols WHERE key; DELETE WHERE key. Unknown data columns filtered by schema; line number = seq
  (statement terminator carries the seq).
- **FR-5 (typed literals)**: `byte[]` renders as PostgreSQL bytea hex literal `'\x…'`;
  `BigDecimal` renders unquoted via `toPlainString()`. V1 rendering unchanged.
- **FR-6 (baseline)**: per-table `plugin_delta_baselines(account_plugin_id, site_id, table_name,
  baseline_seq)` captured at activate/reinit from current `checkpoints.seq`; record emitted iff
  `seq > baseline_seq(table)` (missing row → 0). Batch-level baseline cases are bypassed for V2
  sites (unchanged for V1).
- **FR-7 (idempotency)**: `uk_sql_gen_source_batch` + `existsBySourceBatchId` guard reused;
  duplicate triggers are harmless.
- **FR-8 (rollout)**: V38 backfills `plugin_sql_at = created_at` for pre-existing segments (no
  retroactive SQL) and seeds baselines from current checkpoints for active bit-bi activations;
  existing V2 plugin users reinit once after deploy.
- **FR-9 (regenerate)**: `regenerateForBatch` explicitly rejects V2 sites with a clear error.

## Out of Scope

- Consuming parquet egress files for SQL generation.
- Any change to the Bit BI client contract (`/files`, `/sql-changes` shapes).
- V1 behavior changes beyond the AFTER_COMMIT listener fix.
- Capture-at-download baselines (documented as future hardening).

## Known residual risks (documented in `docs/cr-bitbi-delta-sql.md`)

1. Reinit→download overlap: checkpoints may fold forward between baseline capture and the
   client's CSV download; overlapping UPDATE/DELETE re-apply harmlessly, INSERTs may conflict
   client-side. Window = checkpoint fold cadence; guidance: download promptly after reinit.
2. FULL_SNAPSHOT suspends SQL until manual reinit (audit warning is the signal).
3. The claim transaction holds a DB connection across S3 I/O — same accepted trade-off as
   parquet egress, capped by `plugin.sql-generation.delta-max-concurrent` (default 2).
