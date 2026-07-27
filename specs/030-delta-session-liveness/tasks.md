# 030 — Delta session liveness: tasks

**Status**: T01–T04 implemented. Both 029/PR #60 follow-ups closed; documented in
`docs/cr-batch-per-session.md` → "Session liveness and resume (030)".

Closes the two follow-ups left open by review of feature 029 (batch-per-session, PR #60).
Both are ways a **live** Delta v2 gRPC session gets killed by server-side bookkeeping.

Scope: `delta/`, `batch/`, `src/main/resources/application.yml` (`delta.ingestion.*`),
`docs/cr-batch-per-session.md`. **No Flyway migration** (V42 is taken by another stream).

WIP = 1, test-first. Gate before every commit: `./gradlew test -PexcludeIntegration`.

---

## Problem 1 — the liveness touch fights the optimistic lock

`BatchLifecycleService.touchActivity()` does `findById` + `save()`. `Batch` carries `@Version`, so
the liveness write takes part in the optimistic-locking protocol. Any concurrent write to the same
row — the timeout sweeper marking the batch `NOT_COMPLETED`, a segment commit, an error flag —
makes one of the two lose and throw `OptimisticLockingFailureException`. When the loser is the
touch, the exception surfaces inside `DeltaIngestionService.onNext`, which aborts the batch and
`onError`s the stream: a healthy, streaming session dies from a bookkeeping write.

The touch is *not* a state transition — it only stamps `last_activity_at`. It has no business
competing for the version.

## Problem 2 — staged TTL outlives the batch timeout

`delta.ingestion.staged-ttl-millis = 3_900_000` (65 min) > `batch.timeout.minutes = 60`. In the
5-minute window between them, a staged (dropped, awaiting-resume) session is still resumable while
its batch has already been reaped into `NOT_COMPLETED`. The resume path re-attaches blindly, and
the session fails much later — at the final commit — after the client has re-sent its records.

---

## T01 — lock-free activity touch

**Design.** Replace the read-modify-write with a targeted `@Modifying` bulk update in
`JpaBatchRepository`:

```
UPDATE Batch b SET b.lastActivityAt = :now WHERE b.id = :id
```

JPQL bulk updates do not bump `@Version` (only `UPDATE VERSIONED` does), so the liveness write
never participates in optimistic locking and never collides with a concurrent transition. This
removes the race at the source rather than muting the symptom. The repository returns the affected
row count so the existing "batch not found → warn and skip" behaviour survives.

A secondary `DataAccessException` swallow stays in `touchActivity` as defence in depth (the method
is contractually best-effort), but it is *not* the fix.

**Tests (red first)**
- `BatchLifecycleServiceTest.TouchActivity`: delegates to the repository's targeted update, never
  loads/saves the aggregate; warns (no throw) on 0 rows updated; survives a repository that throws
  `OptimisticLockingFailureException`.
- `DeltaIngestionStreamChangesContractTest`: a session whose `touchActivity` throws
  `OptimisticLockingFailureException` still acks, seals and commits — the ingest path survives.
- `DeltaSessionLivenessIntegrationTest` (Testcontainers): N concurrent `touchActivity` calls racing
  a `completeBatch` / `failBatch` on the same batch — no exception reaches the caller, the terminal
  status wins, `last_activity_at` is stamped.
- `BatchTest`: `isExpired` still measures from `startedAt` when `lastActivityAt` is null (v1
  batches keep their old timeout semantics).

**Files.** `batch/domain/BatchRepository.java`, `batch/infrastructure/JpaBatchRepository.java`,
`batch/application/BatchLifecycleService.java`.

Commit: `fix(delta): make activity touch lock-free (T01)`

---

## T02 — guard the staged resume against a reaped batch

**Design.** In `DeltaIngestionService.onSessionStart`, before re-attaching to a staged session,
check that its batch is still `IN_PROGRESS` (new `BatchLifecycleService.isBatchInProgress`, false
when missing). If it is not, open a **fresh** batch and carry the staged buffer, mode and
`firstSeq` into it, then reply `RESUME_FROM` as usual. The staged records survive, the client
re-sends only what it never got acked, and the failure is surfaced at resume time instead of at the
final commit. If the fresh batch cannot be opened (`ActiveBatchExistsException`), emit
`ACTIVE_SESSION_EXISTS` exactly like a normal session start.

**Tests (red first)**
- `DeltaIngestionStreamChangesContractTest`: resume whose staged batch is `NOT_COMPLETED` opens a
  new batch, replies `RESUME_FROM` with the new server session id, and commits the staged +
  replayed records under the new batch (no data loss); resume whose batch is still `IN_PROGRESS`
  re-attaches as before (no new batch); resume onto a reaped batch that cannot open a new one emits
  `ACTIVE_SESSION_EXISTS`.
- `BatchLifecycleServiceTest`: `isBatchInProgress` is true only for `IN_PROGRESS`, false for
  terminal and for a missing batch.

**Files.** `delta/presentation/DeltaIngestionService.java`,
`batch/application/BatchLifecycleService.java`.

Commit: `fix(delta): open a fresh batch when a staged resume hits a reaped batch (T02)`

---

## T03 — align staged TTL with the batch timeout

**Design.** Drop `delta.ingestion.staged-ttl-millis` to `3_000_000` (50 min), below
`batch.timeout.minutes` (60 min) with room for the 5-minute staged sweep granularity: worst-case
eviction lands at ~55 min, still inside the batch's life. Update the `@Value` fallback in
`DeltaIngestionService` to the same number so the annotation default and the yml agree.

**Tests (red first)**
- `DeltaIngestionStagedTtlConfigTest`: reads the shipped `application.yml` defaults and asserts
  `staged-ttl-millis + staged-sweep-millis < batch.timeout.minutes * 60_000`, and that the
  `@Value` fallback in `DeltaIngestionService` matches the yml default (drift guard).

**Files.** `src/main/resources/application.yml`, `delta/presentation/DeltaIngestionService.java`.

Commit: `chore(delta): align staged session TTL with the batch timeout (T03)`

---

## T04 — document, close the follow-ups

Add a "Session liveness and resume" section to `docs/cr-batch-per-session.md` covering the
lock-free touch, the resume guard and the TTL ordering invariant; mark both 029 review follow-ups
as closed with a pointer to this feature.

Commit: `docs(delta): document session liveness and resume hardening (T04)`

---

## T05 — rebaseline intent must survive a staged resume

Added after review of T01–T04 (owner-approved scope extension). **The bug predates this feature —
it comes from the 022 resume path meeting the 029 commit split, not from the 029 follow-ups.**

**The bug.** `rebaseline` is set only on the fresh-start path, when `mode == FULL_SNAPSHOT`. A
`FULL_SNAPSHOT` is not `CONTINUOUS`, so a mid-stream drop routes it to `stageForResume()`; the
resume branch then `return`s before that assignment. At `SessionEnd` the commit therefore gets
`rebaseline = false`: **a dropped-and-resumed re-baseline silently commits as an ordinary delta.**
`DeltaSessionCommitService.commit` skips `DeltaRebaselineService.reset`, the old segments and
checkpoints are never wiped, and the new full snapshot folds *on top of* stale data. That is data
corruption, and it lands on the longest, most drop-prone session type there is — the one clients
run precisely to repair a sequence gap.

**Not a bug: `continuous`.** `onError` routes continuous sessions to `sealOnContinuousDrop()`; only
non-continuous sessions reach `stageForResume()`. A staged session was never continuous, so
`continuous = false` after resume is the correct value — restoring it would at best be a no-op and
at worst enable seals where they must not happen. Documented in place, code unchanged.

**Design.** Carry `rebaseline` explicitly in `StagedSession` rather than re-deriving it from the
restored `sessionMode`. The staged entry should record what the session *decided*, not a proxy we
re-infer — re-deriving a flag from a stand-in is the exact failure mode being fixed here, and it
would silently drop any future re-baseline not implied by the mode string. The invariant: a resumed
session commits with the same re-baseline semantics it would have had without the drop — across
repeated drops, and across T02's fresh-batch swap.

`firstSeq` already survives the drop (it is part of `StagedSession`), so `reset(siteId, firstSeq)`
receives the original snapshot's first sequence and the commit contract needs no change.

**Tests (red first)**
- `DeltaIngestionStreamChangesContractTest`:
  - **regression sentinel, written first**: a plain `DELTA` session that drops, resumes and ends
    must **never** call `reset` — an inverted condition would start wiping baselines on ordinary
    delta sessions;
  - a `FULL_SNAPSHOT` that drops, resumes and ends resets the baseline at the original `firstSeq`;
  - the intent survives two drops and two resumes;
  - it survives T02's swap onto a fresh batch (staged batch reaped by the sweeper).
- `DeltaSessionLivenessIntegrationTest` (Testcontainers): the observable result — old segments and
  checkpoints are actually gone after a dropped-and-resumed `FULL_SNAPSHOT` commits, while a
  dropped-and-resumed `DELTA` session leaves them intact.

**Files.** `delta/presentation/DeltaIngestionService.java`.

Commit: `fix(delta): preserve rebaseline intent across staged resume (T05)`
