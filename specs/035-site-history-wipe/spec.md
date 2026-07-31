# 035 — Site history wipe (clean slate with generation epoch)

Issue: [#89](https://github.com/quantum-soft-dev/data-forge-middleware/issues/89) · Migration slot: **V48**

## Problem

There is no way to give a site a true clean slate.

- `POST .../delta/rebaseline` replaces the changelog baseline but keeps the site schema, the
  client's local seq counters, batches / upload history and plugin state.
- `SiteService.deleteSite` destroys the site itself, credentials included.

Operators need the middle ground: **wipe all server-side history of a site — batches, uploaded
files, changelog segments, checkpoints, schema, plugin SQL, error logs — so the site behaves as if
it had just been created**, while the Delta v2 client (dbf-data-extractor) reliably learns that all
counters reset and starts from zero.

The protocol cannot express that today. The only server→client signal is
`RecoveryAction.NEED_REBASELINE`, which means "upload a FULL_SNAPSHOT" but says nothing about the
client's local journal or seq counter, and there is no generation/epoch concept anywhere
(`site_sync_state`, proto).

## Decisions (from the issue, agreed)

| Question | Decision |
|---|---|
| Access | **admin + account owner** — the same dual surface as re-baseline |
| Scope | **everything, including `site_schemas`** — the client re-submits its schema like a brand-new site |
| Client signal | a new **`generation` (epoch)** counter in `site_sync_state` and the proto; the server also keeps answering `NEED_REBASELINE` |
| Bit BI plugin | **auto-reinit** — delta baselines are re-captured automatically after the first post-wipe checkpoint build |

## Functional requirements

**FR-01 — Wipe operation.** A synchronous, transactional wipe of one site's server-side history
(rows in-tx, S3 objects strictly after commit), returning a summary of what was destroyed.

**FR-02 — Per-site mutex.** The wipe locks the site's `site_sync_state` row (`SELECT … FOR UPDATE`,
upserting `SiteSyncState.initial` when absent) so concurrent wipes and session commits serialize.

**FR-03 — Live-session guard.** An `IN_PROGRESS` batch inside the liveness window (any session
mode) aborts the wipe with **409 `session-in-progress`**. No force-cancel: the gRPC stream holds
heap-local session state on whichever pod owns it. Stale `IN_PROGRESS` batches beyond the window
are deleted with the rest.

**FR-04 — Deletion order.** FKs dictate it: plugin SQL generations → plugin delta baselines →
changelog segments (incl. provisional) → checkpoints → error logs → detach
`account_plugins.baseline_batch_id` → batches (cascades uploaded files and comparisons) → site
schema → reset sync state.

**FR-05 — Baseline detach, not re-point.** `account_plugins.baseline_batch_id` referencing a
deleted batch is nulled (the FK is `ON DELETE RESTRICT`); the next completed batch of the account
becomes the baseline automatically, exactly as after a reinit with no batches. Recorded in the
audit details.

**FR-06 — Concurrency backstop.** After deleting the batches, re-count `batches WHERE site_id`; a
batch that committed concurrently rolls the whole wipe back → retryable **409 `concurrent-session`**.

**FR-07 — Generation epoch.** `site_sync_state.generation` is bumped by `resetForWipe()` and by
nothing else. In particular an ordinary re-baseline never bumps it. The row is **reset, never
deleted**, so the counter stays monotonic across wipes.

**FR-08 — Post-wipe rebaseline request.** `resetForWipe()` also raises `rebaseline_requested`, so
`GetSyncState` answers `NEED_REBASELINE` with no new code and the wipe inherits the
retry-until-snapshot-committed semantics of an ordinary re-baseline.

**FR-09 — Proto surface.** `generation` on `SyncStateResponse`, `SessionOpened` and `SessionStart`
(field 5 on each), plus `ErrorCode.GENERATION_MISMATCH = 7`. All additive/backward-compatible.

**FR-10 — Generation guard.** `start.generation != 0 && start.generation != state.generation` →
`ServerError(GENERATION_MISMATCH, NEED_REBASELINE)`. Without it a client that saw epoch N+1 but
crashed before resetting could open a DELTA session carrying epoch-N sequence numbers. A `0`
(old/unknown client) skips the guard.

**FR-11 — Bit BI auto-reinit.** The trigger is the **first post-wipe checkpoint build**, not the
FULL_SNAPSHOT commit: at commit time `DeltaRebaselineService.reset` has just deleted every
checkpoint in the same transaction, so a recapture there would freeze baseline 0 and the following
DELTA segments would generate SQL overlapping the checkpoint CSVs the plugin client downloads
(duplicate rows in Bit BI). Baselines must be checkpoint seqs, as in manual reinit.

**FR-12 — Exactly once.** `wipe_pending` is consumed by a conditional update, so the recapture runs
once per wipe. No active bit-bi activation → the flag is still cleared (a later activation covers
the site via `captureBaselines`).

**FR-13 — Site-scoped recapture.** The wipe must not touch sibling sites of the activation:
`recaptureForSite(activation, site)` is extracted from `recaptureForReinit`, which becomes a loop
over it (behaviour-preserving).

**FR-14 — Requeue fix (drive-by, repairs manual reinit too).**
`clearPluginSqlBySiteId` currently re-enqueues **all** segments including `FULL_SNAPSHOT` ones, and
`DeltaSqlQueueService.processNextPending` unconditionally routes FULL_SNAPSHOT to
`suspendBaselines` — so any recapture that re-enqueues while snapshot segments still exist has its
fresh baselines immediately re-suspended. Exclude `FULL_SNAPSHOT` from the requeue.

**FR-15 — REST.**

| Surface | Endpoint |
|---|---|
| Owner | `POST /api/v1/account/sites/{siteId}/delta/wipe` |
| Admin | `POST /api/v1/sites/{siteId}/delta/wipe` |

Request (GitHub-style confirmation, mismatch → 400): `{ "confirm": "shop-42.example.com" }`.
Response 200:

```json
{
  "generation": 4,
  "deletedBatches": 123, "deletedSegments": 45, "deletedCheckpoints": 12,
  "deletedFiles": 678, "deletedSqlGenerations": 9, "deletedErrorLogs": 33,
  "deletedBytes": 123456789,
  "s3DeleteErrors": 0,
  "baselineBatchDetached": false
}
```

Errors: 400 wrong/missing confirmation · 403 foreign site · 404 unknown site · 409
`{"status": "session-in-progress"}` or `{"status": "concurrent-session"}`.

**FR-16 — Audit.** One `admin_action_logs` row in the wipe transaction: new
`AdminActionType.SITE_HISTORY_WIPE`, with details (counts, bytes, new generation, initiator, whether
the baseline batch was detached). One `plugin_audit_logs` row for the auto-reinit: new
`PluginActionType.DELTA_AUTO_REINIT`.

**FR-17 — Frontend.** A "Danger zone" section on the site-detail shell (owner + admin) with a
`WipeHistoryDialog` whose destructive button unlocks only when the typed text equals the site
domain. 409 → toast "a sync session is currently running — stop the client and retry".

**FR-18 — Legacy sites.** Allowed for both V2 and legacy DBF sites: the delta steps are no-ops, the
upserted sync-state row still gives the site `generation >= 1`, so the contract holds if it later
becomes V2.

## Client contract (dbf-data-extractor)

The client persists per-site `last_seen_generation` (uint64, initially 0/absent).

1. On every connect: `GetSyncState`. If `response.generation != stored` (stored absent + no local
   journal → adopt silently):
   1. Drop the local journal/changelog for the site; reset the local seq counter to 0; forget the
      cached schema-version ack.
   2. Persist `generation = response.generation` **before** starting the upload (crash-safe:
      re-running the comparison is idempotent).
2. `SubmitSchema` (the server holds none post-wipe).
3. `SessionStart{mode: FULL_SNAPSHOT, first_seq: 1, schema_version: <from step 2>,
   generation: <stored>}` → stream the complete dataset as INSERTs from seq 1 → `SessionEnd`.
4. Every subsequent `SessionStart` echoes the stored generation. On
   `ServerError{GENERATION_MISMATCH}` or any `NEED_REBASELINE` → re-run from step 1. A mismatch
   observed in `SessionOpened.generation` → abort the session, re-run from step 1.

### Old client degradation

- Post-wipe `GetSyncState` returns `action=NEED_REBASELINE`, `last_applied_seq=0`,
  `schema_version=0`. An old client obeys and uploads a FULL_SNAPSHOT from its own un-reset seq
  counter — accepted (FULL_SNAPSHOT is gap-exempt; `serverLastSeq = firstSeq - 1`). Data-wise the
  site recovers.
- No SCHEMA_MISMATCH loop: the schema guard is skipped while either side reports version 0, and
  conforming clients re-submit the schema on version mismatch (`0 != local`).
- Net: old clients recover, but the full "counters reset to zero" semantics needs the updated client.

## Failure modes / non-goals

- **Wipe vs active stream** — rejected 409 inside the tx (row locks serialize against session commits).
- **Client reconnecting mid-wipe** — the end-of-tx batch re-count catches a batch that committed
  before the wipe did; the residual window (batch tx still open at wipe commit) can land epoch-N
  data after the wipe. Accepted: narrow, the generation bump still forces a client reset on the next
  `GetSyncState`, and the operator can re-run the wipe — cheaper than locking the hot ingestion path.
- **Wipe vs SQL sweep / egress workers** — both claim segments `FOR UPDATE SKIP LOCKED`; short
  mutual waits only.
- **Wipe vs checkpoint scheduler** — a build started pre-wipe may resurrect a stale checkpoint row;
  same exposure as today's rebaseline reset. The post-wipe snapshot re-zeroes it and the next build
  overwrites.
- **Partial S3 failure** — rows are already gone (safe direction); failed keys logged and counted in
  the response; orphans accepted.
- **Never-synced / legacy DBF site** — sync-state row upserted, zero counts, 200, generation bumped.
- **Double-click / concurrent wipes** — serialized by the row lock; the second wipe deletes nothing
  and bumps generation again (harmless: one extra client reset).
- **Pending re-baseline / provisional segments** — consumed by the wipe (flag re-raised by
  `resetForWipe`, provisional rows deleted).
- **Non-goal**: deleting egress Parquet objects (`egress/{siteId}/…`) — tracked nowhere in the DB
  and would need a paginated prefix walk that does not exist. Left as orphans (existing
  checkpoint-orphan precedent). Optional follow-up: a paginated prefix sweep.
