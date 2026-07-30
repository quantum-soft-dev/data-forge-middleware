# Tasks — 033 Segmented re-baseline

Fixes [#82](https://github.com/quantum-soft-dev/data-forge-middleware/issues/82). Design:
`docs/cr-delta-rebaseline-segmented.md`.

WIP = 1. Every task is test-first (red → green → one atomic commit). Gate before each commit:
`./gradlew test -PexcludeIntegration` must be 100% green. Gate before the PR:
`./gradlew integrationTest`.

Scope guard: `src/main/java/com/bitbi/dfm/delta/**`,
`src/main/java/com/bitbi/dfm/plugin/application/DeltaSqlQueueService.java`,
`src/main/resources/db/migration/**`, `src/test/java/com/bitbi/dfm/{delta,integration,plugin}/**`,
`docs/`, `specs/033-delta-rebaseline-segmented/**`.

Invariants that must hold at every commit:

- **No proto change, no client change.** `SessionCommitted` stays terminal for `DELTA` /
  `FULL_SNAPSHOT` and per-seal for `CONTINUOUS`.
- **Deferred destruction (review r4).** The old baseline survives until the new one is durably
  complete — strengthened here, never weakened.
- **Re-baseline intent survives a drop (030/T05).** A staged-and-resumed snapshot still commits as
  a re-baseline.

---

## T01 — Incremental session totals (stats + content hash)

**Tests first**: `ChangelogContentHashTest` — an incremental `Hasher` fed record-by-record in two
chunks produces the byte-identical digest to `compute(allRecords)` (the canonical encoding must not
change, or every client's hash breaks). New `SessionTotalsTest` — per-table
insert/update/delete counts accumulate across chunks; empty totals reconcile against an empty
declaration.

**Implementation**
- `ChangelogContentHash.Hasher` (`update(List<ChangeRecord>)` / `hex()`); `compute(...)` becomes a
  thin wrapper so existing callers and the canonical form are untouched.
- `SessionTotals` (delta/application): running `Map<String, TableChangeStats>` + `Hasher`, exposing
  `reconcile(Map<String, TableStats>)` and `hashMatches(String)`.
- `SessionReconciler` gains an overload taking accumulated stats; the list-based one delegates.

Commit: `feat(delta): incremental session totals surviving segment seals (T01)`

---

## T02 — V46 migration + `provisional` column + consumer exclusions

**Tests first**: migration-shape test asserting `V46__changelog_segments_provisional.sql` is
additive (`NOT NULL DEFAULT FALSE`, no `UPDATE`/`DROP`) and adds the partial index. Repository
integration test — a provisional segment is invisible to `findBySiteIdOrderByFirstSeq`,
`findNextPendingEgress` and `findNextPendingPluginSql`, and becomes visible after the flip.

**Implementation**
- `V46__changelog_segments_provisional.sql`: `ADD COLUMN provisional BOOLEAN NOT NULL DEFAULT FALSE`
  (existing rows are committed baselines, so `FALSE` is the correct backfill) + narrow the two
  pending partial indexes to `WHERE ... IS NULL AND provisional = FALSE`.
- `ChangelogSegment.provisional` + `markCommitted()`.
- Exclude `provisional = TRUE` from `findBySiteIdOrderByFirstSeq`, `findNextPendingEgress`,
  `findNextPendingPluginSql`. `findRecentBySiteId` (admin UI) deliberately keeps showing them so an
  in-flight snapshot is observable.
- `findProvisionalBySiteId` + `flipProvisionalByBatchId` for T04.

V46 is owned exclusively by this feature (last applied is V45).

Commit: `feat(delta): provisional changelog segments and V46 migration (T02)`

---

## T03 — Rebaseline reset keeps the current session's segments

**Simplification found while implementing:** the planned `keepBatchId` parameter is unnecessary.
`findBySiteIdOrderByFirstSeq` — the query `reset` already sources its delete set from — excludes
provisional rows as of T02, so the in-flight snapshot's own segments are out of scope by
construction. T03 therefore only adds the GC entry point and pins the guarantee with tests.

**Tests first**: `DeltaRebaselineServiceTest` — `reset` sources its delete set from the
committed-only query and never touches provisional segments; `deleteProvisional(siteId)` removes
rows **and** (after commit) objects, without touching checkpoints or the watermark; it is a no-op
when nothing was left behind.

**Implementation**: `DeltaRebaselineService.deleteProvisional(UUID siteId)` for the
start-of-snapshot GC, reusing the deferred-S3-delete path.

Commit: `feat(delta): collect orphaned provisional segments before a retry (T03)`

---

## T04 — Commit service: provisional seals and the atomic flip

**Tests first**: `DeltaSessionCommitServiceTest` — `commitProvisionalSegment` persists a provisional
segment, does **not** advance the watermark, does **not** wake egress and does **not** touch the
batch; the rebaseline `commit` runs reset → persist tail → flip → advance → complete **in that
order**, using the session's first seq (not the tail segment's) for the watermark reset; a
non-rebaseline commit is unchanged.

**Implementation**
- `DeltaSessionCommitService.commitProvisionalSegment(...)`.
- `commit(..., boolean rebaseline, long sessionFirstSeq)` → `reset(siteId, sessionFirstSeq, batchId)`,
  persist tail, `flipProvisionalByBatchId(batchId)`, `advanceWatermark`, `completeBatch`.

Commit: `feat(delta): commit a re-baseline as provisional segments flipped atomically (T04)`

---

## T05 — Ingestion service: seal a FULL_SNAPSHOT silently

**Tests first**, in `DeltaIngestionStreamChangesContractTest`:
- a `FULL_SNAPSHOT` session past the seal threshold emits **no** intermediate `SessionCommitted`,
  only `Ack`s, and exactly one `SessionCommitted` at `SessionEnd`;
- a snapshot exceeding `max-session-records` commits successfully instead of `INTERNAL`/OVERFLOW;
- reconciliation and `content_hash` at `SessionEnd` are computed over the **whole** session;
- a drop mid-snapshot stages the session, and the resumed session still commits as a re-baseline
  (030/T05 preserved) with whole-session totals;
- `FULL_SNAPSHOT` `SessionStart` GCs provisional leftovers from a previous failed attempt;
- `CONTINUOUS` and `DELTA` behaviour is byte-identical to today (regression pins).

**Implementation**
- seal trigger `continuous || rebaseline`; `sealSegment(boolean announce)` — announce only when
  `continuous`; provisional seals for `rebaseline`.
- track `sessionFirstSeq` separately from the per-segment `firstSeq`.
- wire `SessionTotals`; carry it and `sessionFirstSeq` in `StagedSession`.
- `deleteProvisional(siteId)` on `FULL_SNAPSHOT` start; `deleteByBatchId` on `abortBatch` /
  staged-TTL eviction.

Commit: `fix(delta): seal a re-baseline in bounded segments (T05, #82)`

---

## T06 — Bit BI baseline suspension is idempotent

**Tests first**: `DeltaSqlQueueServiceTest` — N `FULL_SNAPSHOT` segments of one snapshot produce
exactly one `suspendBaselines` effect, one audit entry and one
`sql.generation.delta.rebaseline.detected` increment; a later, genuinely new snapshot suspends again.

**Implementation**: `DeltaSqlQueueService` skips the suspend when the account's baselines for the
site are already suspended. The product rule (a `FULL_SNAPSHOT` requires a manual plugin reinit) is
unchanged.

Commit: `fix(plugin): suspend delta baselines once per re-baseline, not per segment (T06)`

---

## T07 — Integration: a snapshot larger than the record cap

**Tests first** (this task *is* the test), `SegmentedRebaselineIntegrationTest` on Testcontainers:
- with `delta.ingestion.max-session-records` lowered, a `FULL_SNAPSHOT` well above the cap completes
  end to end; `rebaseline_requested` is cleared; the checkpoint folds **only** the snapshot (rows
  absent from it are gone); old segments/checkpoints discarded exactly once;
- a drop mid-snapshot leaves the previous baseline fully readable (checkpoint fold and egress see
  the old state, watermark unmoved) and the flag still set;
- the retry after such a drop GCs the orphans and succeeds.

Commit: `test(delta): re-baseline larger than the session record cap (T07)`

---

## T08 — Docs

**Implementation**
- `docs/delta-client-v2-guide.md`: a re-baseline now spans multiple segments; correct the
  *"FULL_SNAPSHOT egress file is a full table"* claim to *apply delta files in seq order*; document
  that intermediate snapshot seals are silent on the wire.
- Fix the stale statements found while researching #82 (they misdescribe current behaviour):
  `cr-delta-client-v2.md` "opening the next segment under a fresh batch" (superseded by 029);
  `cr-delta-client-v2.md` + guide "a continuous session that drops mid-segment loses its unsealed
  tail" (superseded by `sealOnContinuousDrop`); `first_seq` must be **≤** `server_last_seq + 1`, not
  `==`; `mode` list missing `CONTINUOUS`.
- `CLAUDE.md`: Recent Changes entry, migration pointer to V46/next V47.

Commit: `docs(delta): segmented re-baseline and delta v2 doc corrections (T08)`

---

## Outcome

All eight tasks landed. Two deviations from the plan, both recorded in
`docs/cr-delta-rebaseline-segmented.md`:

- **T03** dropped the planned `keepBatchId` parameter — T02's query change already excludes
  provisional rows from `reset`'s delete set, so the exclusion is structural rather than a parameter.
- **T07** surfaced a pre-existing gap: a `FULL_SNAPSHOT` retry after a drop was rejected with
  `ACTIVE_SESSION_EXISTS` until the staged sweeper ran (~50 min), because the abandoned session's
  batch stayed `IN_PROGRESS`. A non-`DELTA` start now fails the session it supersedes. Without this,
  every dropped attempt at a large snapshot cost an hour of dead time — enough to undo the fix.

`SessionReconciler` was removed in T05: replacing its only production call site with `SessionTotals`
left it with no callers.

Gates: `./gradlew test -PexcludeIntegration` green at every commit; `./gradlew integrationTest`
green over the full suite (190 tests, 0 failures).
