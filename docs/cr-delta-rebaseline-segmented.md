# CR: Segmented Re-baseline (033)

**Status**: Implemented (branch `issue-82`)
**Spec**: `specs/033-delta-rebaseline-segmented/`
**Migration**: V46
**Issue**: [#82](https://github.com/quantum-soft-dev/data-forge-middleware/issues/82) — *re-baseline is
impossible for sites larger than the session buffer cap*
**Scope**: Delta v2 gRPC ingestion (`FULL_SNAPSHOT` sessions). No proto change, no client change.

## Motivation

Site `e5d56c37-9fbb-4b21-83df-ea9a2829970c` (dev/test, ~4.96M records) stopped ingesting entirely
after an owner clicked **Full re-baseline** in the Delta Sync widget. No batch has been created on
the server since. The four steps form a closed loop:

1. `SiteSyncState.requestRebaseline()` sets `rebaseline_requested`; `GetSyncState` answers
   `NEED_REBASELINE` while the flag is up.
2. The flag is cleared **only** by a successful `FULL_SNAPSHOT` commit
   (`SiteSyncState.resetForRebaseline`, called from `DeltaSessionCommitService.commit`). There is no
   cancel API.
3. `SessionMode` has three mutually exclusive values. `rebaseline` is set only for `FULL_SNAPSHOT`;
   `continuous` only for `CONTINUOUS`. **A re-baseline therefore never seals segments** — the whole
   snapshot is buffered on-heap in one session.
4. The session buffer is capped at `delta.ingestion.max-session-records` (2,000,000) and
   `max-session-bytes` (auto = maxHeap/8). A 4.96M-record snapshot exceeds the record cap by 2.5x.
   On overflow the server answers `INTERNAL` + `RecoveryAction.NEED_REBASELINE` with the advice
   *"stream large datasets in CONTINUOUS mode"* — advice a re-baseline cannot follow, and a recovery
   action that sends the client straight back into `FULL_SNAPSHOT`.

**Any site larger than the session buffer cap is permanently bricked by the "Full re-baseline"
button.** Raising the caps is not an acceptable fix: the byte budget exists because a 439k-row
snapshot OOM'd a 1536Mi pod on 2026-07-23 (027).

The same cliff exists for an ordinary `DELTA` session above the caps — it is rejected with
`NEED_REBASELINE`, which today lands in the same trap. This CR does not change the `DELTA` cap, but
it does make the resulting re-baseline completable.

## What makes this harder than "just seal the snapshot too"

A sealed segment is immediately visible to **three** independent consumers, none of which is
mode-aware:

| Consumer | Query | Effect on an in-flight snapshot |
|---|---|---|
| Checkpoint fold | `CheckpointService:78` → `findBySiteIdOrderByFirstSeq` — every segment of the site, no upper bound at the watermark | folds the partial snapshot **on top of** the old state: rows that should have disappeared survive (exactly the corruption re-baseline exists to prevent) |
| Delta Parquet egress | `findNextPendingEgress` | publishes a partial snapshot downstream |
| Bit BI SQL | `findNextPendingPluginSql` → `DeltaSqlQueueService:96` | calls `suspendBaselines` once **per segment** instead of once per snapshot |

Two more consequences surface only because `FULL_SNAPSHOT`, unlike `CONTINUOUS`, does send a
`SessionEnd`:

- `SessionReconciler.reconcile` and `ChangelogContentHash.matches` verify against
  `buffer.accepted()`. After a mid-stream seal the buffer holds only the tail, so both checks would
  compare the client's whole-session totals against a fraction of the records.
- `DeltaRebaselineService.reset` deletes **all** segments of the site — including the ones this very
  session just sealed.

## Design

### 1. A re-baseline seals, but silently

`onChange` extends the seal trigger from `continuous` to `continuous || rebaseline`, reusing the
existing record (100), byte (`continuous-seal-bytes`) and time (`continuous-seal-millis`) thresholds.
The buffer therefore never approaches either cap, and the caps go back to being what 027 intended:
a backstop against misconfiguration, not a ceiling on dataset size.

**Intermediate `FULL_SNAPSHOT` seals emit no `SessionCommitted`.** For a periodic session
(`DELTA` / `FULL_SNAPSHOT`) that event is documented as terminal — *"the server replies
`SessionCommitted{committed_seq, segment_s3_key}` and closes its side"*
(`docs/delta-client-v2-guide.md`). Emitting it mid-snapshot would tell a conforming client the
session is over. The client keeps receiving the progressive `Ack`s it already gets every 100 records
(documented as *"progress, not durability"*) and exactly one `SessionCommitted` at `SessionEnd`.

The wire contract for every mode is therefore **unchanged**, and the bricked site is fixed by a
server deploy alone — no new Windows client build.

### 2. Snapshot segments are provisional until the session ends

V46 adds `changelog_segments.provisional BOOLEAN NOT NULL DEFAULT FALSE`. Segments sealed mid-snapshot
are written with `provisional = true` and are excluded from all three consumer queries above. The
durable watermark is **not** advanced on a provisional seal, so `GetSyncState` keeps reporting the
pre-snapshot watermark.

A provisional segment is thus invisible to everything except the session that wrote it. Storage is
spent, nothing is published.

### 3. The flip is one transaction

`SessionEnd` runs, in the single existing commit transaction:

1. `rebaselineService.reset(siteId, sessionFirstSeq)` — delete every **non-provisional** segment of
   the site plus all checkpoint rows, reset the watermark to `sessionFirstSeq - 1`, clear
   `rebaseline_requested`. S3 object deletes stay deferred to `afterCommit`.
2. Persist the tail segment (non-provisional).
3. `UPDATE changelog_segments SET provisional = false WHERE batch_id = :batchId`.
4. `advanceWatermark(committedSeq)`, `completeBatch(batchId)`.

*Implementation note:* the planned `keepBatchId` argument on `reset` proved unnecessary. `reset`
sources its delete set from `findBySiteIdOrderByFirstSeq`, which excludes provisional rows, so the
snapshot being written is out of scope by construction rather than by an explicit exclusion.

Old baseline destroyed **exactly once**, and only after the new one is durably complete. A drop at
any point before step 1 leaves the previous baseline byte-for-byte intact and still served — the
deferred-destruction guarantee from review r4 is strengthened, not weakened.

### 4. Reconciliation survives seals

A session-scoped accumulator carries running per-table `TableChangeStats` and a streaming SHA-256
across seals. `ChangelogContentHash.compute` already digests record-by-record, so it splits into an
incremental `Hasher` without changing the canonical encoding — the hash a client computes is
unchanged.

The accumulator is staged with the session (`StagedSession`) so a dropped-and-resumed snapshot still
reconciles against its whole-session totals, preserving the 030/T05 invariant.

### 5. Garbage collection

A snapshot that never completes leaves provisional segments behind. They are invisible, but they
occupy `UNIQUE (site_id, first_seq)` slots and S3 space. They are collected:

- at `FULL_SNAPSHOT` `SessionStart` — `DeltaRebaselineService.deleteProvisional(siteId)` drops any
  provisional segments left by an earlier failed attempt (rows in-transaction, S3 objects after
  commit) before the new attempt begins. This is the guaranteed path;
- on `abortBatch` — a snapshot rejected mid-session drops its own provisional segments immediately,
  which only shortens how long the storage and the `UNIQUE (site_id, first_seq)` slots stay held.

### 6. Bit BI baseline suspension becomes idempotent

After the flip, all N snapshot segments enter the delta-SQL queue as `FULL_SNAPSHOT`.
`DeltaSqlQueueService` currently calls `suspendBaselines` per such segment, each writing a plugin
audit failure and incrementing `sql.generation.delta.rebaseline.detected`. It becomes a no-op when
the account's baselines are already suspended, so one re-baseline produces one signal.

The product rule is unchanged: a `FULL_SNAPSHOT` still requires a manual plugin reinit
(`docs/cr-bitbi-delta-sql.md`).

### 7. A snapshot retry no longer collides with the session it abandons

Found while writing the T07 integration test, and pre-existing rather than introduced here. A staged
session's batch is deliberately left `IN_PROGRESS` so a `DELTA` reconnect can re-attach to it. A
`FULL_SNAPSHOT` never re-attaches — it abandons the staged session — but the batch stayed active, so
the retry was rejected with `ACTIVE_SESSION_EXISTS` until the staged sweeper ran (~50 min).

That was tolerable while a large re-baseline could never succeed anyway. It is not tolerable now: a
multi-million-record snapshot takes long enough that a mid-stream drop is likely, and an hour of
dead time per attempt would undo the fix. A non-`DELTA` `SessionStart` now fails the session it
supersedes.

## Review round (post-implementation)

A high-effort review found three data-loss/wedge bugs, all rooted in the same mistake: provisional
segments were written into an unpartitioned unique keyspace, and published by a key (the batch) that
the session can change under them. The fixes:

1. **Uniqueness is now partial** (`WHERE provisional = FALSE`). A provisional segment was competing
   for `(site_id, first_seq)` slots with the baseline it is replacing — which is only deleted at
   `SessionEnd` — so a snapshot re-bootstrapping at an already-used sequence died on its first seal.
   The same collision let an abandoned snapshot's orphan wedge every later session at that sequence.
2. **A resume onto a replacement batch re-points its already-sealed segments** to that batch.
   Publication is batch-keyed and `reset()` cannot see provisional rows, so segments left under a
   reaped batch were neither published nor deleted: the snapshot committed a baseline missing
   everything streamed before the drop, watermark advanced, client told it succeeded. Reconciliation
   did not catch it — the totals travel with the staged session and still matched.
3. **Garbage collection is batch-scoped, with a scheduled backstop.** The site-wide sweep let one
   session delete another's in-flight snapshot; and no path at all collected a snapshot whose pod
   died. `sweepOrphanedProvisionalSegments` collects provisional rows whose batch is no longer
   running — a staged session keeps its batch `IN_PROGRESS`, so a resumable one is never swept.

Four more, lower-severity:

4. **`flushAutomatically = true`** on both `@Modifying` bulk updates. `clearAutomatically` alone
   detached `reset()`'s still-unflushed `SiteSyncState` and checkpoint deletes whenever the query
   spaces did not intersect — leaving `rebaseline_requested` set after a *successful* snapshot.
5. **Provisional segments park their queue markers** instead of relying on a `provisional` predicate
   in the pollers. A replica on the previous release has no notion of the column and would otherwise
   claim a half-streamed snapshot during a rolling deploy and stamp it processed for good.
6. **A snapshot seals on `delta.ingestion.snapshot-seal-records` (25,000), not 100.** The continuous
   threshold bounds staleness for a trickle stream; on a 5M-row snapshot it produced 50,000 segments,
   each seal stalling record intake for a full S3 round trip.
7. **Cross-pod retry** — the in-memory supersede is a no-op when the retry lands on another replica,
   so a non-DELTA start now also reclaims an active batch that has been silent past the staged TTL,
   using the same conditional update the timeout sweeper uses.

Also: the Bit BI reinit signal now fires once per re-baseline rather than once per segment that
introduces a new table; the lag metric is emitted for snapshot seals (it went silent for the whole
duration of a large one); the checkpoint-seq read moved out of the per-seal path; and
`SessionTotals`/`ChangelogContentHash` gained per-record overloads to drop the per-record wrapper
allocations.

**Known, not fixed here — resolved since by 036:** the per-batch Parquet download
(`.../batches/{batchId}/tables/{table}/parquet`) returned the first matching segment's file. That was
pre-existing — a `CONTINUOUS` batch has had many segments since 029 — and correcting it meant its own
change: see `docs/cr-unified-batch-parquet.md`, which replaced the segment scan with one unified
artifact per `(batch, table)`.

## Downstream contract change

`docs/delta-client-v2-guide.md` claimed a `FULL_SNAPSHOT` egress file *"is a full table — so
bootstrap and re-baseline need no special handling"*. With a segmented snapshot that is no longer
true: a re-baseline now produces N all-`INSERT` Parquet files under
`egress/{siteId}/{table}/delta/seq=...`.

Consumers that apply delta files **in seq order** — the model `CONTINUOUS` already requires, and what
the zero-padded key ordering exists for — reach the identical final state. Consumers that treated a
single `FULL_SNAPSHOT` file as a whole-table replacement would truncate. The guide is corrected
accordingly; Bit BI is unaffected (it skips `FULL_SNAPSHOT` segments entirely) and Parquet Export is
unaffected (it filters `type=delta|checkpoint` and hands out whole files).

## Rejected alternatives

- **Raise the caps.** Explicitly rejected by the issue and by 027 — the byte budget exists because a
  smaller snapshot already OOM'd a pod.
- **Allow `rebaseline` together with `CONTINUOUS`.** Requires the client to choose a different mode
  for a re-baseline, i.e. a client change, so the currently-bricked site stays bricked until that
  ships. It also collides with `CONTINUOUS` having no `SessionEnd`, which is precisely the signal
  that makes the atomic flip possible.
- **Wipe the old baseline on the first sealed segment** (the issue's own first suggestion). Simpler
  and needs no migration, but after the first seal the old baseline is gone: a drop mid-snapshot
  leaves the site serving a fraction of its data. That contradicts the issue's own acceptance
  criterion *"a drop mid-snapshot leaves the previous baseline usable"*.

## Acceptance

- A site with >2M records completes a full re-baseline end to end.
- `rebaseline_requested` is cleared; old segments and checkpoints are discarded exactly once.
- A drop mid-snapshot leaves the previous baseline usable and re-arms a clean retry.
- Integration test with a snapshot larger than `max-session-records`.

## Operational note

Sites already bricked by the button (e.g. `e5d56c37-…`) are unblocked by this deploy: the client's
next `FULL_SNAPSHOT` attempt completes instead of overflowing. The pre-fix workaround — clearing
`rebaseline_requested` in `site_sync_states`, after which the client falls back to ordinary
`CONTINUOUS` delta from its watermark — remains valid and needs no code.
