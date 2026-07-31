# Site history wipe — Delta v2 client contract (generation epoch)

Server issue: [#89](https://github.com/quantum-soft-dev/data-forge-middleware/issues/89) ·
Client issue: `quantum-soft-dev/dbf-data-extractor#124` · Migration: **V48**

This is the normative contract for the client side. The server behaviour it describes is
implemented; see `docs/delta-client-v2-guide.md` → "Site history wipe and the generation epoch" for
the operator-facing half.

## Why a new field at all

An operator can now wipe **all** server-side history of a site — batches, uploaded files, changelog
segments, checkpoints, the table schema, plugin SQL and error logs — while the site itself, and its
credentials, survive. Afterwards the server is in the state of a site that has just been created.

`RecoveryAction.NEED_REBASELINE` cannot express that. It says "send a full snapshot"; it says
nothing about the client's local journal, its sequence counter, or its cached schema-version ack. A
client that obeyed it after a wipe would upload a complete dataset numbered from its own pre-wipe
counter — internally consistent, and wrong.

So `site_sync_state.generation` is an **epoch**. A wipe increments it. Nothing else does — an
ordinary re-baseline explicitly does not. A value the client has not seen before means: *everything
you know about this site is void.*

## Protocol additions

All additive; an old client that ignores them keeps working (see "Old clients" below).

| Message | Field | Number | Type |
|---|---|---|---|
| `SyncStateResponse` | `generation` | 5 | `optional uint64` |
| `SessionOpened` | `generation` | 5 | `optional uint64` |
| `SessionStart` | `generation` | **6** | `optional uint64` |
| `ErrorCode` | `GENERATION_MISMATCH` | **12** | enum value |

### Why explicit presence

A site starts at epoch 0, and proto3 implicit presence never puts a zero on the wire — so a plain
`uint64` cannot distinguish "this server predates epochs" from "this server says epoch 0". That is
not cosmetic: the **first** wipe of any site moves it 0 → 1, and a guard reading "0 means an old
client" waves through a correct client at exactly the moment its sequence numbers stop meaning
anything. `optional` closes it (dbf-data-extractor#130 Q1/Q2). It changes the descriptor, not the
encoding, so it is wire-compatible with anything already deployed.

The server always sets the field on both response messages, zero included. **Absent means an older
server, and nothing else.**

### Why 6 and 12, not 5 and 7

Both 5 and 7 are taken **on the client side**, and neither collision is catchable at decode time:

- The client's proto carries five private `ErrorCode` values at 7–11 (`OVERFLOW`, `OVERFLOW_BYTES`,
  `SITE_INACTIVE`, `SCHEMA_REQUIRED`, `CONCURRENT_BATCH_LIMIT`) and dispatches on them *code-first*,
  ahead of `ServerError.action`. A server sending 7 for "stale epoch" would be read as `OVERFLOW`
  and answered by opening a CONTINUOUS session — carrying exactly the pre-wipe sequence numbers the
  epoch exists to discard.
- The client's `SessionStart` carries `bool snapshot = 5`. `bool` and `uint64` share the varint wire
  type, so `snapshot = true` would decode as `generation = 1` and get a legitimate session rejected.

`SessionStart` field 5 therefore stays `reserved`: bootstrap and re-baseline both travel as
`mode = FULL_SNAPSHOT`, so the flag has no server-side purpose and is being dropped client-side.

### Enum divergence — resolved

`ErrorCode` 7–11 used to exist only in the client's proto, and the same conditions reached it under
generic codes it could not act on. The server now **declares and emits** the client's numbers
(dbf-data-extractor#130 Q4–Q6):

| Condition | Was | Now |
|---|---|---|
| session record overflow | `INTERNAL (6)` | `OVERFLOW (7)` + `NEED_REBASELINE` |
| session byte overflow | `INTERNAL (6)` | `OVERFLOW_BYTES (8)` + `NEED_REBASELINE` |
| site inactive or deleted | `UNAUTHORIZED (4)` | `SITE_INACTIVE (9)` + `PROCEED` |
| schema required before first session | `SCHEMA_MISMATCH (2)` | `SCHEMA_REQUIRED (10)` + `PROCEED` |
| account concurrent-batch cap | `ACTIVE_SESSION_EXISTS (5)` | `CONCURRENT_BATCH_LIMIT (11)` + `PROCEED` |

`ACTIVE_SESSION_EXISTS (5)` keeps its narrower meaning: *this site* already has a live session. The
client's `OVERFLOW → switch to CONTINUOUS` recovery can finally fire, because its trigger now
arrives — previously overflow was indistinguishable from a genuine fault. Full rationale and the
complete recovery matrix: [`delta-v2-wire-contract-answers.md`](./delta-v2-wire-contract-answers.md).

## Normative client algorithm

The client persists a per-site `last_seen_generation` (`uint64`, initially absent/0).

1. **On every connect**, call `GetSyncState`. If `response.generation != last_seen_generation`:
   - *First observation with no local journal* (stored value absent and nothing to throw away):
     adopt the value silently, no reset.
   - Otherwise:
     1. Drop the local journal/changelog for the site; reset the local sequence counter to 0; forget
        the cached schema-version ack.
     2. **Persist `last_seen_generation = response.generation` before starting the upload.** This
        order is load-bearing: re-running the comparison is idempotent, so a crash after the local
        wipe and before the upload is harmless, whereas a crash after uploading and before
        persisting would repeat the whole snapshot on every restart.
2. `SubmitSchema` — the server holds no schema after a wipe.
3. `SessionStart{mode: FULL_SNAPSHOT, first_seq: 1, schema_version: <from step 2>,
   generation: <stored>}`, stream the complete dataset as INSERTs from seq 1, then `SessionEnd`.
4. Every subsequent `SessionStart` echoes the stored generation. On `ServerError{GENERATION_MISMATCH}`
   or any `NEED_REBASELINE`, restart from step 1. A mismatch observed in `SessionOpened.generation`
   (a wipe that landed after the session opened) → abort the session and restart from step 1.

Send the field on every `SessionStart` once the client tracks epochs at all — including when the
stored value is 0. **Omitting it** is what identifies a client too old to track them, and a client
that omits it is not protected by the guard.

## Server-side guard

```
start.hasGeneration() && start.generation != state.generation
  → ServerError(GENERATION_MISMATCH, NEED_REBASELINE)
```

Presence, not a zero: a present `0` against a server at `1` — the first wipe of any site — is a
mismatch and is refused.

It runs *before* the resume branch, not beside the schema-version check: a staged session lives in
the heap of the pod that owns it and outlives a wipe of its own site, so a resume is precisely the
path that can carry stale-epoch records back in.

## Old clients

A wipe also raises `rebaseline_requested`, so `GetSyncState` answers `NEED_REBASELINE` with
`last_applied_seq = 0` and `schema_version = 0`. An old client obeys and uploads a FULL_SNAPSHOT
from its own un-reset counter, which the server accepts — a snapshot is gap-exempt
(`serverLastSeq = firstSeq - 1`) — and there is no SCHEMA_MISMATCH loop, because the schema guard is
skipped while either side reports version 0 and a conforming client re-submits its schema on a
version mismatch. Data-wise the site recovers. The full "counters reset to zero" semantics needs the
updated client.

## Shipping order

**Server first, then the client.** Nothing breaks in the other order — the fields are additive and an
older server simply omits `generation` while emitting the pre-#130 error codes — but a client shipped
first runs unguarded, which is the state this feature exists to end. Detecting which server you have:
`generation` present on `SyncStateResponse` means this release or newer, and typed error codes ship
in the same change, so one probe answers both.

That holds **only** with the numbers above. Shipping a client that uses 5 and 7 against a server that
uses 6 and 12 leaves both sides internally correct while disagreeing on the wire — the most expensive
kind of disagreement to find.

## Test list

- `GetSyncState` returns the site's generation; 0 for a site that has never been wiped, and the field
  is **present** on the wire at 0.
- `SessionOpened.generation` is emitted on both the PROCEED and RESUME_FROM paths.
- A `SessionStart` whose present generation disagrees with the server is refused with
  `GENERATION_MISMATCH` + `NEED_REBASELINE`, in DELTA **and** in FULL_SNAPSHOT mode.
- A present `generation = 0` against a server at generation 1 is refused — the first-wipe case.
- An **absent** generation skips the guard (old client opens normally).
- A client ahead of the server (e.g. server restored from a backup) is refused as well — the two
  disagree about which history they are discussing, whichever way round.
- Client-side: adopt-without-reset on first observation; local wipe precedes persisting the new
  generation; mid-stream abort-and-restart on a `SessionOpened.generation` mismatch.
