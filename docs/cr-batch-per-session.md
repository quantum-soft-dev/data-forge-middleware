# CR: Batch-per-Session Semantics for Delta Client v2 Ingestion (029)

**Status**: Implemented (branch `029-batch-per-session`); both PR #60 review follow-ups closed by
`030-delta-session-liveness` — see [Session liveness and resume (030)](#session-liveness-and-resume-030)
**Spec**: `specs/029-batch-per-session/`
**Migration**: V41

## Motivation

A real onboarding run (site `fyt-new`, test env, 2026-07-27) streamed ~200 records of one table
over a single CONTINUOUS gRPC session and produced **three** Upload History rows: two
`100 changes · 1 tables` batches one second apart plus a trailing `0 files · 0 B` batch. The
customer read this as data corruption. Cause: the ingestion server closed the active batch and
opened a fresh one on **every** continuous-mode segment seal (`CONTINUOUS_SEAL_RECORDS = 100`,
plus time/byte triggers), and pre-opened a successor batch that closed empty at `SessionEnd`.

Product decision: **a batch is one client attempt to upload data in one go** — one row or a
million, it is one batch. Storage granularity is the server's internal concern and must not leak
into the UI or the plugin event stream.

## Semantics after 029

- One gRPC ingestion session ↔ one batch. Opened at `SessionStart` (or re-attached on staged
  resume — unless that batch is already terminal, see 030 below), completed exactly once at
  `SessionEnd`/clean close, failed on abort. A zero-record session completes its batch honestly
  with 0 changes. No pre-opened tail batches.
- Continuous seals (still every 100 records / `continuous-seal-millis` / `continuous-seal-bytes`)
  are pure durability events: each seals a segment **under the same batch**
  (`DeltaSessionCommitService.commitSegment` — persists the segment and advances the watermark,
  never touches the batch).
- `changelog_segments` ↔ `batches` is now genuinely many-to-one. Segment S3 keys are per-segment:
  `delta/{siteId}/segments/{segmentId}.pb.gz` (pre-029 rows keep their stored batch-derived keys;
  the `s3_key` column is authoritative).
- Upload History list aggregates per batch SQL-side: `deltaRecordCount` = SUM of segment record
  counts, `deltaTableCount` = COUNT of DISTINCT tables across segment stats
  (`ChangelogSegmentRepository.aggregateByBatchIds`). The detail view already aggregated.
- `BATCH_COMPLETED` fires once per session (plugins previously got one event per ~100 records).
  Per-segment pipelines are untouched: delta Parquet egress and the Bit BI delta-SQL queue still
  trigger at seal time, without waiting for the session to end.

## Activity-based timeout (V41)

`batches.last_activity_at` (nullable) is touched by the ingest path at a bounded cadence: session
start/resume, each Ack watermark (≥100 accepted records), each seal. The timeout sweeper's cutoff
is now `COALESCE(last_activity_at, started_at) < now - batch.timeout-minutes`:

- a live streaming session older than 60 minutes keeps running (its activity is fresh);
- a session silent for the whole window is failed (NOT_COMPLETED), freeing the site;
- v1 batches never touch activity → exact pre-029 behavior;
- the blanket V2 exclusion from the sweeper (review r4 of 022) is removed — it existed only
  because start-based expiry would kill long live sessions mid-commit.

A truly perpetual continuous client holds one batch open for the session's lifetime — accepted
for now (no forced rollover); the activity timeout still reclaims it if the stream goes silent.

## Session liveness and resume (030)

Review of PR #60 left two follow-ups open — both ways the 029 bookkeeping could kill a **live**
session. Closed by feature `030-delta-session-liveness` (`specs/030-delta-session-liveness/`), no
migration.

**The activity touch is lock-free.** `BatchLifecycleService.touchActivity()` was a `findById` +
`save()`, so the liveness stamp took part in `Batch`'s `@Version` optimistic locking. Any
concurrent write to the row — the timeout sweeper failing the batch, a segment commit, an error
flag — made one side lose, and when the loser was the touch the `OptimisticLockingFailureException`
surfaced inside `DeltaIngestionService.onNext`, which aborts the batch and `onError`s the stream: a
healthy streaming session killed by a bookkeeping write. It is now a targeted JPQL bulk update
(`UPDATE Batch b SET b.lastActivityAt = :now WHERE b.id = :batchId` in `JpaBatchRepository`), which
neither loads the aggregate nor bumps the version — a stamp is not a state transition and has no
business competing for it. The repository method carries its own `@Transactional`; the service runs
`SUPPORTS` so its best-effort swallow of a persistence failure sits outside that transaction. The
touch cadence itself is unchanged (session start/resume, Ack watermark, seal).

**Resume checks the batch is still live.** A staged (dropped, awaiting-resume) session can outlive
its batch, so `onSessionStart` no longer re-attaches blindly: it asks
`BatchLifecycleService.isBatchInProgress` first. When the staged batch is terminal it opens a
**fresh** batch and carries the staged buffer, mode and `firstSeq` into it, replying `RESUME_FROM`
as usual with the new `server_session_id`. Nothing staged before the drop is lost and the client
still replays only from the staged watermark, instead of running to the end and failing at the
final commit. The fresh batch obeys one-active-batch-per-site and surfaces `ACTIVE_SESSION_EXISTS`
when it cannot open. Note this makes a session's batch id observably re-assignable mid-resume; the
`SessionOpened.server_session_id` is authoritative.

**TTL ordering invariant.** `delta.ingestion.staged-ttl-millis` shipped at 65 min against a 60-min
`batch.timeout.minutes`, so the window above was routine rather than exceptional. It is now 50 min,
leaving room for the 5-minute staged sweep (`staged-sweep-millis`): worst-case eviction lands at
~55 min, inside the batch's life. The invariant `staged-ttl + staged-sweep < batch.timeout` is
asserted against the shipped `application.yml` defaults by `DeltaIngestionStagedTtlConfigTest`,
which also pins the `@Value` fallback to the yml default. **Raising `BATCH_TIMEOUT_MINUTES` or
`DELTA_STAGED_TTL_MILLIS` per environment must keep that ordering.**

### Re-baseline intent across a resume (030/T05)

**Provenance: this one is not a 029 follow-up.** It is older — it comes from the 022 staged-resume
path meeting the FULL_SNAPSHOT commit split, and it shipped in both. It was found while reviewing
030 and fixed there.

`rebaseline` was set only on the fresh-start path (`mode == FULL_SNAPSHOT`). A `FULL_SNAPSHOT` is
not `CONTINUOUS`, so a mid-stream drop stages it for resume, and the resume branch returns before
that assignment ever runs. The session then committed with `rebaseline = false`: **a
dropped-and-resumed re-baseline was silently committed as an ordinary delta.**
`DeltaSessionCommitService.commit` skipped `DeltaRebaselineService.reset`, so the prior segments and
checkpoints were never wiped and the new full snapshot folded *on top of* stale data — rows that had
disappeared survived, tables absent from the snapshot kept being served. That is data corruption,
and it lands on the longest and most drop-prone session type there is: the one clients run precisely
to repair a sequence gap.

The intent now travels with the staged session (`StagedSession.rebaseline`), carried explicitly
rather than re-derived from the restored mode string — the staged entry records what the session
decided, and re-deriving a flag from a proxy is the exact failure mode being fixed. `firstSeq`
already survived the drop, so `reset(siteId, firstSeq)` gets the original snapshot's first sequence
and the commit contract is unchanged. The invariant: **a resumed session commits with the same
re-baseline semantics it would have had without the drop** — across repeated drops, and across the
fresh-batch swap above.

By contrast `continuous` is deliberately *not* restored: `onError` routes continuous sessions to
`sealOnContinuousDrop()`, so only non-continuous sessions ever reach `stageForResume()`. A staged
session was never continuous, and setting the flag on resume would enable mid-stream seals that must
not happen. Noted in code so the asymmetry is not "fixed" later.

## Design decisions

See `specs/029-batch-per-session/research.md` (D1–D7) and
`specs/029-batch-per-session/contracts/session-batch-contract.md`. Highlights: seal/lifecycle
split at the commit-service level (D2), segment-id S3 keys with no backfill (D3), COALESCE
sweeper with bounded-cadence touches (D4), SQL-side list aggregation (D5).

## Compatibility / ops

- Migration V41 is additive (nullable column; the `changelog_segments.batch_id` index exists since V37); forward-only.
- No gRPC proto change; clients see the same protocol (per-seal `SessionCommitted` events
  included). No REST DTO shape change; the frontend is untouched.
- Rollback: old app code reads mixed data fine (stored `s3_key` values, single- and
  multi-segment batches); multi-segment batches would render in the old list view with one
  segment's count until rolled forward again.
- Watch after deploy: `delta.sessions.started/committed` now count sessions (previously inflated
  by one per seal); Upload History rows per client run drop accordingly.
