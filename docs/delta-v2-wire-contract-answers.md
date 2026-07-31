# Delta v2 wire contract — server answers to dbf-data-extractor#130

**Status**: answers to the twelve questions raised in
[dbf-data-extractor#130](https://github.com/quantum-soft-dev/dbf-data-extractor/issues/130).
**Canonical contract**: [`src/main/proto/delta-ingestion.proto`](../src/main/proto/delta-ingestion.proto)
**Server change**: PR #90 (feature 035, issue #89)
**Audience**: the dbf-data-extractor client team

Everything below is implemented in PR #90 unless a paragraph says otherwise. Where the client's
proposal was accepted verbatim, the answer says so and does not restate it.

---

## 1. Presence for `generation` — accepted

Declared exactly as proposed:

```protobuf
message SyncStateResponse { optional uint64 generation = 5; }
message SessionOpened     { optional uint64 generation = 5; }
message SessionStart      { optional uint64 generation = 6; }
```

The reasoning is the server's too: a site starts at epoch 0, and proto3 implicit presence does not
put a zero on the wire at all, so without `optional` "server too old to know about epochs" and
"server says epoch 0" are the same bytes. `optional` on a proto3 scalar changes the descriptor, not
the encoding, so this is wire-compatible with anything already deployed.

The server always sets the field on both response messages, epoch 0 included. **Absent means one
thing only: a server older than this release.**

## 2. First-epoch-change guard — accepted, and it was a real hole

The rule the client proposed is now the implemented rule:

| `SessionStart.generation` | server epoch | outcome |
|---|---|---|
| absent | any | guard skipped (old client) |
| present, equal | — | session proceeds |
| present, different (**0 vs 1 included**) | — | `GENERATION_MISMATCH` + `NEED_REBASELINE` |

This was found independently in the review of PR #90 and is fixed there. The guard runs **before**
`RESUME_FROM`, before the schema guard and before any staged-session restore — deliberately, because
a staged session lives in the serving pod's heap and survives a wipe of its own site, so resume is
the one path that can carry stale-epoch records back in.

## 3. Lifecycle of `generation` — confirmed, all six points

Initial value 0; only a history wipe increments it; an ordinary re-baseline never touches it;
monotonic and never reset (the wipe resets the row's other columns but the row itself is never
deleted); returned on every `GetSyncState`; returned on every `SessionOpened`, both `PROCEED` and
`RESUME_FROM`.

**No other operation changes it.** Deleting the site drops the row, but the site is gone with it, so
there is nothing left to compare against.

## 4. Error codes 7–11 — adopted as real members

The `reserved 7..11` in the review draft was a guard against a silent mismatch while the two enums
disagreed, not a position. The server now declares and emits the numbers the shipped client already
carries:

```protobuf
OVERFLOW               = 7;
OVERFLOW_BYTES         = 8;
SITE_INACTIVE          = 9;
SCHEMA_REQUIRED        = 10;
CONCURRENT_BATCH_LIMIT = 11;
GENERATION_MISMATCH    = 12;
```

No conflict with anything deployed: the server has never emitted 7–11, so no existing client can be
reading them as something else.

`SessionStart` field 5 stays `reserved` — see Q8.

## 5. Recovery matrix — confirmed, with the actions unchanged

`RecoveryAction` values were already what the client's table implies, so only the codes moved:

| Condition | Code (was) | Code (now) | `RecoveryAction` | What the client should do |
|---|---|---|---|---|
| per-session record cap exceeded | `INTERNAL` | `OVERFLOW` | `NEED_REBASELINE` | retry the session in CONTINUOUS mode |
| per-session byte budget exceeded | `INTERNAL` | `OVERFLOW_BYTES` | `NEED_REBASELINE` | retry in CONTINUOUS mode |
| site deactivated or deleted | `UNAUTHORIZED` | `SITE_INACTIVE` | `PROCEED` | stop the run; operator action needed |
| no schema on file | `SCHEMA_MISMATCH` | `SCHEMA_REQUIRED` | `PROCEED` | `SubmitSchema`, then retry |
| account-wide session cap reached | `ACTIVE_SESSION_EXISTS` | `CONCURRENT_BATCH_LIMIT` | `PROCEED` | back off, retry later |
| epoch disagreement | — | `GENERATION_MISMATCH` | `NEED_REBASELINE` | re-read sync state, drop the local journal, send a snapshot |
| this site already has a live session | `ACTIVE_SESSION_EXISTS` | unchanged | `PROCEED` | serialize against your own runs and retry |
| `first_seq` beyond the watermark | `SEQUENCE_GAP` | unchanged | `NEED_REBASELINE` | full snapshot |
| declared `schema_version` is stale | `SCHEMA_MISMATCH` | unchanged | `NEED_REBASELINE` | re-submit schema, then snapshot |
| keyless table sent an UPDATE | `SCHEMA_MISMATCH` | unchanged | `NEED_REBASELINE` | declare a key, or send INSERT/DELETE only |
| counts / hash / `last_seq` disagree | `RECONCILIATION_FAILED` | unchanged | `NEED_REBASELINE` | full snapshot |
| anything unexpected | `INTERNAL` | unchanged | `NEED_REBASELINE` | report; do not treat as overflow |

Note the `NEED_REBASELINE` on both overflow rows. It is not a demand for a full snapshot on the next
attempt — it says "this session committed nothing, start over"; the *how* is the CONTINUOUS retry in
the last column.

**Settled with the client team: no new `RecoveryAction`.** `NEED_REBASELINE` stays on both overflow
rows, and the mode the session was in decides what it means:

| Session mode at overflow | Client behaviour |
|---|---|
| `DELTA` | retry in CONTINUOUS mode |
| `CONTINUOUS` | **terminal** — a configuration error, not a retry |
| `FULL_SNAPSHOT` | **terminal** — a configuration error, not a retry |

A snapshot must not degrade into a CONTINUOUS session to get around a cap: CONTINUOUS publishes as
it goes, so it cannot offer the atomic replace-on-`SessionEnd` that makes a snapshot a snapshot.
Overflow in either of the last two rows means the caps are set wrong for this deployment, and the
fix is server configuration.

The server enforces the one invariant that can produce an unrecoverable snapshot overflow —
`delta.ingestion.snapshot-seal-records` **must** be below `delta.ingestion.max-session-records`,
checked at startup, so a bad pair fails the pod rather than the site. The shipped defaults (25 000
against 2 000 000) were already safe; nothing but this check kept them that way.

## 6. Reachability of typed overflow — yes, both, at a single point

The server already distinguishes the two internally: the per-session buffer returns distinct
`OVERFLOW` and `OVERFLOW_BYTES` results, and both come from the one place a record is accepted. Only
the outward mapping was lossy, and that is what changed. **After this release no overflow condition
reaches the client as `INTERNAL`.** `INTERNAL` now means what it says — an unexpected fault — and
the client is right to refuse to read it as overflow.

## 7. FULL_SNAPSHOT semantics after 033 — all five confirmed

A large snapshot streams without buffering the whole set, is sealed into bounded provisional
segments, replaces the old baseline only in the `SessionEnd` transaction, leaves the old baseline
visible if the stream drops, and is not bounded by the old single-session maximum.

What can still reject one:

| Limit | Default | Notes |
|---|---|---|
| `delta.ingestion.max-session-records` | 2 000 000 | per session, not per segment → `OVERFLOW` |
| `delta.ingestion.max-session-bytes` | auto = maxHeap / 8 | on-heap buffer budget → `OVERFLOW_BYTES` |
| `batch.timeout.minutes` | 60 | measured from last session activity, not from session start |

Segment size itself is not a rejection: it is the seal threshold, and a snapshot crossing it seals
and continues. That only holds while the seal fires *before* the cap, so the server now refuses to
start unless `delta.ingestion.snapshot-seal-records` (25 000) is below
`delta.ingestion.max-session-records` (2 000 000) — see Q5. Both caps are per session and count the
live buffer, so a sealed segment's records no longer count towards them.

## 8. `CONTINUOUS + snapshot=true` — no server-side need

The server never declared field 5 and has never read it. The client's plan — drop
`SessionStart.snapshot`, leave 5 reserved, send every bootstrap and re-baseline as `FULL_SNAPSHOT`,
keep CONTINUOUS for incremental change — is what the server already assumes. Agreed.

## 9. `last_applied_seq == 0` and AdoptSite — reliable

`last_applied_seq == 0` does mean the server holds no accepted baseline for the site. There is no
state in which committed data exists behind a zero watermark: the watermark advances in the same
transaction that publishes a segment. Data can exist physically while the watermark is 0 — the
provisional segments of a snapshot that has not reached `SessionEnd` — but those are invisible to
every reader, are not a baseline, and are discarded or published as a unit.

The client's guard (adopted baseline + server seq 0 → mandatory `FULL_SNAPSHOT`) is sound.

## 10. One-shot epoch adoption after AdoptSite — no server scenario breaks it

Accepting the first present generation without dropping the local baseline is safe, given the
client's own condition that server seq 0 still forces a `FULL_SNAPSHOT`. The dangerous shape would
be adopting an epoch while the server holds a baseline the local one disagrees with — and that is
exactly the case the seq-0 rule already sends through a snapshot.

Not consuming the intent on an absent generation is also right, and is the same rule as Q11.

## 11. Rolling deployment — no sticky routing needed

During a rollout a client can see, in any order: an old pod (field absent) and a new pod (field
present). The client's assumption is correct and is the rule the server recommends:

> **An absent `generation` never lowers, resets or invalidates a generation the client has already
> stored.** Only a *present* value that differs is an epoch change.

With that rule the interleaving is harmless, because the only way to observe a wipe is a present
value, and a wipe is durable — every new pod reports the new epoch, and old pods report nothing at
all rather than something stale. Sticky routing would add a failure mode without removing one.

Worth stating plainly: rollout order matters for a *wipe performed during* a rollout, not for the
epoch mechanism. Operators should not wipe mid-rollout; the server does not enforce that.

## 12. Canonical contract and delivery order

The server's `delta-ingestion.proto` is the single canonical wire contract. Any client-side copy is
a generated artefact of it.

The cross-repository descriptor check is welcome — names, field and enum numbers, wire types,
`optional` presence and reserved ranges all included.

**Settled**: the client repository vendors this server's `.proto` verbatim, pinned to the merge SHA
of PR #90, and its CI compares normalized descriptors. Nothing is required of the server build; the
`.proto` at that SHA is the reference. When the contract changes again the server will say so in the
PR description, with the new SHA to re-pin.

Delivery order is as proposed: merge PR #90 → deploy the server → release the client. **Do not
release a client that relies on typed errors or presence-aware generation before the server is
deployed** — against an older server both degrade quietly rather than break, but the epoch guard is
simply absent, which is the state PR #90 exists to end.

**Minimum server version.** **Settled: no capability or version RPC.** The probe is `GetSyncState` —
**if `generation` is present, the server is this release or newer**. That single bit is sufficient
because the presence-aware fields, the typed codes 7–12 and the new guard all ship in this one PR and
cannot arrive separately. If a later change ever splits them, the server will add an explicit
capability then rather than let the inference rot.

---

## Settled — nothing outstanding

The three questions this document originally left open were answered by the client team on
2026-07-31 and are folded into Q5 and Q12 above:

1. **No new `RecoveryAction`** — `NEED_REBASELINE` stays on both overflow rows; only `DELTA` +
   `OVERFLOW` switches to CONTINUOUS, and overflow in `CONTINUOUS` or `FULL_SNAPSHOT` is terminal.
   The server now fails startup on the configuration that could cause the unrecoverable case.
2. **Contract check** against this server's `.proto`, vendored client-side and pinned to the PR #90
   merge SHA; normalized descriptors compared in the client's CI.
3. **No capability RPC** — presence of `SyncStateResponse.generation` is the version probe.
