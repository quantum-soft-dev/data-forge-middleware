# Delta Client v2 Integration Guide

**Document Version**: 1.1.0
**Last Updated**: 2026-08-01
**Audience**: Developers building a client that streams database changes into Data Forge Middleware over the Delta Client v2 gRPC API
**Contract**: [`src/main/proto/delta-ingestion.proto`](../src/main/proto/delta-ingestion.proto) · **Design**: [`docs/cr-delta-client-v2.md`](./cr-delta-client-v2.md)

## Table of Contents

1. [Overview](#overview)
2. [Retired CDC v1 migration note](#retired-cdc-v1-migration-note)
3. [The gRPC service](#the-grpc-service)
4. [Authentication](#authentication)
5. [Full lifecycle](#full-lifecycle)
6. [Step 1 — Get sync state](#step-1--get-sync-state)
7. [Step 2 — Submit schema](#step-2--submit-schema)
8. [Step 3 — Stream changes](#step-3--stream-changes)
9. [Change records & value typing](#change-records--value-typing)
10. [Keyless tables](#keyless-tables)
11. [Reconciliation](#reconciliation)
12. [Recovery: gaps, resume, re-baseline](#recovery-gaps-resume-re-baseline)
13. [Continuous mode](#continuous-mode)
14. [Backpressure & acks](#backpressure--acks)
15. [Error codes & recovery actions](#error-codes--recovery-actions)
16. [Schema JSON / type mapping](#schema-json--type-mapping)
17. [What the server produces (egress)](#what-the-server-produces-egress)
18. [End-to-end example (pseudocode)](#end-to-end-example-pseudocode)
19. [Troubleshooting](#troubleshooting)
20. [Server-side observability](#server-side-observability-83)

---

## Overview

Delta Client v2 turns the middleware into a **stateful changelog service**. The client streams an
append-only sequence of typed change records (INSERT/UPDATE/DELETE) over a single gRPC bidirectional
stream; the server stores them as an immutable **changelog** (the source of truth) and periodically
materializes **checkpoints** (full snapshots) from it.

A client integration is three RPCs:

1. **`GetSyncState`** — ask the server where it is (its applied watermark) before opening a session.
2. **`SubmitSchema`** — declare table structure (columns, primary keys). Required before the first session and re-sent only when the source structure changes.
3. **`StreamChanges`** — the session: open it, push change records in `seq` order, end it. One
   session is one **batch**, backed by one or more bounded changelog **segments**.

> **A "full snapshot" is just a changelog frame whose records are all `INSERT`.** There is no separate
> snapshot message — bootstrap and re-baseline use the same `StreamChanges` stream with `mode = FULL_SNAPSHOT`.

### Key concepts

| Term | Meaning |
|---|---|
| **`seq`** | A per-site, strictly-increasing sequence number stamped on every change record. The server's durable high-water mark is `last_applied_seq`. |
| **Session** | One `StreamChanges` stream: `SessionStart` → `ChangeRecord*` → `SessionEnd`. Maps onto one Batch. |
| **Segment** | An internal bounded changelog slice sealed during a session. A batch owns one or more segments; segment boundaries are not user-facing backup boundaries. |
| **Checkpoint** | A server-materialized full snapshot at a `seq`, produced asynchronously. You never write it. |
| **Watermark** | `last_applied_seq` — the highest `seq` the server has durably committed for your site. |

---

## Retired CDC v1 migration note

The REST/JSONL `/api/dfc/**` ingestion API is retired. It has no controllers or token
issuance path and must not be used as a fallback. The historical differences are:

| | Retired CDC v1 | Delta Client v2 (gRPC) |
|---|---|---|
| Transport | REST file uploads | gRPC bidirectional streaming + Protobuf |
| Change format | JSONL text lines, types inferred from strings | Typed `Value` on the wire |
| Ordering | per-file | per-site monotonic `seq` with gap detection |
| Recovery | re-upload | `GetSyncState` + `RESUME_FROM` / re-baseline |
| Reliability | best-effort (malformed lines skipped) | **hard-fail** reconciliation at `SessionEnd` |
| Power BI | none | typed Parquet change feed + checkpoint floor |

V45 migrates every stored site marker to `V2`; `V1` is no longer an application enum
value. Device Authorization and refresh remain available at `/api/v1/device/**`, as do
surviving batch/file metadata reads and `POST /api/v1/device/errors`. All ingestion is
performed through the gRPC service below.

---

## The gRPC service

Proto package `com.bitbi.dfm.delta.v2`, service `DeltaIngestion`:

```protobuf
service DeltaIngestion {
  rpc GetSyncState (SyncStateRequest) returns (SyncStateResponse);
  rpc SubmitSchema (SchemaRequest)    returns (SchemaResponse);
  rpc StreamChanges (stream ClientEvent) returns (stream ServerEvent);
}
```

Generate stubs from [`delta-ingestion.proto`](../src/main/proto/delta-ingestion.proto) for your language
(`protoc` / `grpc_tools`). The transport requires **HTTP/2 + TLS** end to end (mind any proxy/LB in front).

On the GKE test environment the endpoint is published on the **same host and port as the HTTP API**:
`https://test.dfm.bitbi.io:443` (TLS on, no separate gRPC host or port). Routing is by the gRPC path
prefix at the load balancer — see `deploy/gke/README.md`, "gRPC ingestion".

---

## Authentication

Delta v2 reuses **Auth V2** (the OAuth 2.0 Device Authorization Flow — see
[`device-flow-client-guide.md`](./device-flow-client-guide.md)). Every gRPC call must carry the access
token in the **`authorization`** metadata header:

```
authorization: Bearer <accessToken>
```

- The server derives your `site_id` and `account_id` from the token. You do **not** choose which site you act on — the token does.
- A missing / malformed / expired token closes the call with gRPC status `UNAUTHENTICATED`; the handler never runs.
- Refresh the access token via `POST /api/v1/device/auth/refresh` before it expires and attach the new one to subsequent calls. For a long-lived `StreamChanges` stream, ensure the token is valid for the stream's lifetime (or use shorter sessions).

Onboarding obtains the token once:

```
POST /api/v1/device/authorize   { siteName, siteType }   → deviceCode, userCode
POST /api/v1/device/token (poll until approved)          → accessToken, refreshToken, siteId
```

---

## Full lifecycle

```
Client                                            Server
  │ (device flow → accessToken, siteId)               │
  │ SubmitSchema(tables, primaryKey) ────────────────►│ store site_schemas v1
  │ ◄──────────────── schemaVersion = 1 ──────────────│
  │                                                    │
  │ === bootstrap (one-time) ===                       │
  │ StreamChanges  mode = FULL_SNAPSHOT                │
  │   SessionStart(first_seq = 1) ───────────────────►│ open batch
  │   ChangeRecord(INSERT, …) × all rows ────────────►│ stage
  │   SessionEnd(last_seq = N, per_table) ───────────►│ reconcile + commit
  │ ◄──── SessionCommitted(committed_seq = N) ─────────│ segment + checkpoint floor
  │                                                    │
  │ === steady state (each period) ===                 │
  │ GetSyncState() ──────────────────────────────────►│
  │ ◄──── last_applied_seq = 120, PROCEED ─────────────│
  │ (diff your source since seq 120)                   │
  │ StreamChanges  mode = DELTA                         │
  │   SessionStart(first_seq = 121) ─────────────────►│ check 121 == 120+1 ✓
  │   ChangeRecord(…) seq 121..123 ──────────────────►│
  │ ◄──────────── Ack(acked_seq = …) ──────────────────│ progressive
  │   SessionEnd(last_seq = 123, per_table) ─────────►│ reconcile + commit
  │ ◄──── SessionCommitted(committed_seq = 123) ───────│
```

---

## Step 1 — Get sync state

Call `GetSyncState` before opening a session to align your watermark with the server (especially after a
restart or crash).

```protobuf
message SyncStateResponse {
  uint64 last_applied_seq    = 1; // highest seq durably committed by the server
  int32  schema_version      = 2; // schema version the server currently holds
  uint64 last_checkpoint_seq = 3; // seq of the latest materialized checkpoint
  RecoveryAction action      = 4; // PROCEED | NEED_REBASELINE
}
```

- Open your next **DELTA** session at `first_seq = last_applied_seq + 1`.
- If `last_applied_seq == 0`, the site has never synced — **bootstrap** with a `FULL_SNAPSHOT` session.
- Compare `schema_version` with the version you produced records against; if the server is behind, call `SubmitSchema` first.

---

## Step 2 — Submit schema

Send the full schema for **all** tables (replace-on-write). Required before the first session; re-send only
when the source structure changes — each call bumps `schema_version`.

```protobuf
message SchemaRequest { map<string, TableSchema> tables = 1; }
message TableSchema {
  repeated Column    columns      = 1;
  repeated string    primary_key  = 2; // MAY be empty → keyless table (see below)
  repeated UniqueKey unique_keys  = 3;
}
message Column { string name = 1; string type = 2; bool nullable = 3; }
```

`type` is a PostgreSQL type spelling (lowercase), e.g. `"bigint"`, `"varchar(255)"`, `"numeric(10,2)"`,
`"timestamp"`. See [type mapping](#schema-json--type-mapping).

---

## Step 3 — Stream changes

Open a single bidirectional stream. The client sends `ClientEvent`s; the server sends `ServerEvent`s.

```protobuf
message ClientEvent { oneof event { SessionStart start; ChangeRecord change; SessionEnd end; } }
message ServerEvent { oneof event { SessionOpened opened; Ack ack; SessionCommitted committed; ServerError error; } }

message SessionStart {
  SessionMode mode              = 1; // DELTA | FULL_SNAPSHOT | CONTINUOUS
  uint64      first_seq         = 2; // seq of the first ChangeRecord in this session
  int32       schema_version    = 3;
  string      client_session_id = 4; // your idempotency / resume key
}
```

**Protocol:**

1. Send exactly one `SessionStart` first.
2. The server replies `SessionOpened{server_session_id, server_last_seq, action, resume_from_seq}`. `server_session_id` is the batch id — read it from each `SessionOpened` rather than caching it, since a [resume](#resume-after-a-mid-session-drop) may hand you a different batch. If `action != PROCEED`, follow [recovery](#recovery-gaps-resume-re-baseline).
3. Send `ChangeRecord`s with strictly-increasing `seq`. The server emits periodic `Ack(acked_seq)`.
4. Send exactly one `SessionEnd{last_seq, per_table, content_hash}` (omit in [continuous mode](#continuous-mode)).
5. The server replies `SessionCommitted{committed_seq, segment_s3_key}` and closes its side, or `ServerError` if it refuses to commit.

A periodic session emits **exactly one** `SessionCommitted`, and it is terminal. A large
`FULL_SNAPSHOT` is sealed into bounded segments internally (033), but those seals are deliberately
silent on the wire — `Ack` remains your only progress signal, so this sequence is unchanged. Only
[continuous mode](#continuous-mode) emits `SessionCommitted` per sealed segment.

**Only one active session per site.** A concurrent `SessionStart` while a session is live is rejected with
`ACTIVE_SESSION_EXISTS` — serialize your sessions per site.

### `content_hash` (optional integrity check)

`SessionEnd.content_hash` lets the server verify it accepted exactly the records you sent. It is
**optional**: send an empty string to skip the check. When non-empty, a mismatch fails the session
with `RECONCILIATION_FAILED`. The hash is **lowercase hex SHA-256** over a canonical, wire-order-independent
serialization (protobuf map order is not stable across languages):

- records in `seq` order; each contributes `op ␟ <len:table> ␟ seq ␟ key-cols ␟ data-cols ␞`
  (`␟` = `0x1F`, `␞` = `0x1E`);
- columns **sorted by name**, each `<len:name>=<len:tagged-value>` followed by `␝` (`0x1D`);
- **every variable-length token is length-prefixed** as `<len>:<token>` (byte length of the UTF-8
  token) so a value that itself contains a separator byte cannot forge a different record set;
- value tags: `I`nt, `D`ouble (**the IEEE-754 bit pattern as a decimal `long`, i.e.
  `Double.doubleToLongBits` — not a formatted decimal, which is not reproducible across languages**),
  `S`tring, boo`L`ean, deci`M`al (string form), `B`ytes (hex), `N`ull — so `1`, `"1"`, and `true`
  never collide.

See `ChangelogContentHash` for the reference implementation.

**`SessionEnd.last_seq`** must equal the highest `seq` you sent (the last `ChangeRecord`'s). The
server rejects a non-zero mismatch with `RECONCILIATION_FAILED`; send `0` to opt out of the check.

**Session size limit.** A single session is capped both by **record count**
(`delta.ingestion.max-session-records`, default 2,000,000) and by the **cumulative serialized size**
of its records (`delta.ingestion.max-session-bytes`; the default `0` means auto — an eighth of the
server's max heap, since the server buffers the whole session on-heap until commit). A dataset
exceeding either cap is rejected with `INTERNAL` (naming the cap that tripped) — stream it in
[continuous mode](#continuous-mode), whose seals reset the buffer far below the caps (there they
only backstop; `continuous-seal-bytes < max-session-bytes` is enforced at startup). Overflow
rejections are counted by the `delta.sessions.overflow` meter, tagged `reason=records|bytes`.

Since 033 the caps apply only to a periodic **`DELTA`** session: `CONTINUOUS` and `FULL_SNAPSHOT`
both seal as they stream, so their buffers never approach either cap. A snapshot of any size
completes — the caps used to make a re-baseline impossible above 2M records, and clicking
"Full re-baseline" on such a site bricked it permanently ([#82](https://github.com/quantum-soft-dev/data-forge-middleware/issues/82)).

---

## Change records & value typing

```protobuf
message ChangeRecord {
  string             table     = 1;
  Op                 op        = 2;  // INSERT | UPDATE | DELETE
  uint64             seq       = 3;  // per-site, strictly increasing
  map<string, Value> key       = 4;  // PK values (all columns for keyless tables)
  map<string, Value> data      = 5;
  google.protobuf.Timestamp source_ts = 6; // optional source commit time
}
```

| Op | `key` | `data` |
|---|---|---|
| `INSERT` | PK values | **full row** |
| `UPDATE` | PK values | **changed columns only** (after-image) |
| `DELETE` | PK values | empty (tombstone) |

### Value typing

```protobuf
message Value {
  oneof v {
    bool   is_null       = 1; // true => SQL NULL
    int64  int_value     = 2;
    double double_value  = 3;
    string string_value  = 4;
    bool   bool_value    = 5;
    string decimal_value = 6; // exact numeric carried as a string
    bytes  bytes_value   = 7;
  }
}
```

**Rules (important — FR-004):**

- **Integers** (`integer`, `bigint`, …) → `int_value`.
- **Floating point** (`real`, `double precision`) → `double_value`.
- **Booleans** → `bool_value`.
- **`numeric` / `decimal`** → `decimal_value` as the **exact decimal string** (e.g. `"19.99"`). Never send these as `double_value` — that loses precision.
- **`date`** → `string_value` as ISO date `YYYY-MM-DD` (e.g. `"2024-03-10"`).
- **`timestamp`** → `string_value` as an ISO‑8601 timestamp (e.g. `"2024-03-10T10:30:00Z"`).
- **`bytea`** → `bytes_value`.
- **Text** (`varchar`, `text`, `uuid`, …) → `string_value`.
- **SQL NULL** → set `is_null = true`. Presence matters: a column **absent** from the `data` map of an `UPDATE` means "unchanged"; a column present with `is_null = true` means "set to NULL".

The server parses dates/timestamps/decimals **by the declared column type** when it writes the typed Parquet
egress, so the schema you submit must match the values you send.

---

## Keyless tables

A table with an **empty `primary_key`** is *keyless*: the **entire set of columns is its identifying key**
(mirrors legacy DBF semantics). Consequences:

- **Never emit `UPDATE`.** Any field change re-keys the row — express it as `DELETE`(old full row) + `INSERT`(new full row). The server **rejects** `UPDATE` for keyless tables.
- Send the full row in both `key` and `data` for `INSERT`; the full old row in `key` for `DELETE`.

Tables with a declared primary key use the normal `INSERT`/`UPDATE`/`DELETE` semantics above.

---

## Reconciliation

`SessionEnd` carries per-table counts the server checks against what it actually accepted. A mismatch is a
**hard failure** — the server refuses to commit and returns `RECONCILIATION_FAILED`.

```protobuf
message SessionEnd {
  uint64                  last_seq     = 1;
  map<string, TableStats> per_table    = 2; // for reconciliation
  string                  content_hash = 3; // integrity hash over records
}
message TableStats { uint64 inserts = 1; uint64 updates = 2; uint64 deletes = 3; }
```

- `per_table[table]` must exactly equal the number of `INSERT` / `UPDATE` / `DELETE` records you sent for that table in this session.
- For a **resumed** session, the counts are **cumulative for the whole logical session** (the records staged before the drop *plus* the replayed tail), because the server commits one segment spanning both. Track your session totals so you can report them after a resume.

---

## Recovery: gaps, resume, re-baseline

The server tells you what to do via `RecoveryAction`:

```protobuf
enum RecoveryAction { PROCEED = 0; RESUME_FROM = 1; NEED_REBASELINE = 2; }
```

### Sequence gap → re-baseline
At `SessionStart`, a DELTA/CONTINUOUS session may not skip ahead: `first_seq` must be **≤
`server_last_seq + 1`**. A forward gap (`first_seq > server_last_seq + 1`) returns
`ServerError{code = SEQUENCE_GAP, action = NEED_REBASELINE}`; recover with a `FULL_SNAPSHOT` session.

A `first_seq` **at or below** the watermark is treated as a **replay** (e.g. you retried after a lost
`SessionCommitted` ack): the server proceeds and de-duplicates the already-committed seqs, so a lost
ack costs a cheap replay rather than a full re-baseline.

### Resume after a mid-session drop
If a DELTA session's stream drops **before** `SessionEnd` (transport error or a half-close without an end),
the server retains what it had staged and keeps the batch open. On your next DELTA `SessionStart`, the server
replies:

```
SessionOpened{ action = RESUME_FROM, resume_from_seq = <highest staged seq + 1>, server_session_id = <batch id> }
```

Replay your records starting at `resume_from_seq` (records ≤ the staged watermark are de-duplicated if you
re-send them), then `SessionEnd` with **cumulative** per-table counts. The session commits one segment
spanning the staged + replayed records.

> **`server_session_id` may change on resume — do not assume it is the same batch.** Usually the resume
> re-attaches to the batch the dropped session opened. But if that batch was already reclaimed while you
> were away (the timeout sweeper marks a silent batch `NOT_COMPLETED`), the server opens a **fresh** batch
> and carries your staged records into it. Your staged data and `resume_from_seq` are unaffected — only the
> id changes. Always take `server_session_id` from the `SessionOpened` you just received: never cache the
> id from before the drop, and never treat a changed id as an error.
>
> If the site has meanwhile acquired another active session, the resume is rejected with
> `ServerError{code = ACTIVE_SESSION_EXISTS}` instead.

> Resume staging is **in-memory** on the server and is evicted after a TTL (server config
> `delta.ingestion.staged-ttl-millis`, default **50 minutes**, deliberately below the 60-minute batch
> timeout so a staged session cannot outlive its own batch). If the server restarts, or you do not
> reconnect within the TTL, the staged data is gone and you'll get a `SEQUENCE_GAP` /
> `NEED_REBASELINE` — fall back to a `FULL_SNAPSHOT`.
>
> **Acks are progress, not durability.** `Ack(acked_seq)` means the server has *received and buffered*
> up to that seq, not that it is durably committed — durability is `SessionCommitted`/the watermark.
> Do not discard records you have only seen acked; keep them until the session commits, so you can
> replay after a drop or restart.

### Open a `FULL_SNAPSHOT` to self-heal
Any `FULL_SNAPSHOT` session erases accumulated drift and resets the floor — use it for bootstrap, after a gap,
or whenever you're unsure of alignment.

---

## Continuous mode

For near-real-time ingestion, open a session with `mode = CONTINUOUS` and **never send `SessionEnd`** — push
change records as they occur and keep the stream open.

- The server **seals** a segment automatically once it reaches a record count (fixed at 100), a byte size
  (`delta.ingestion.continuous-seal-bytes`, default 16 MiB — so fat records seal into more, smaller
  segments), or a time since the last seal (`delta.ingestion.continuous-seal-millis`, default 5 min),
  emitting a `SessionCommitted{committed_seq, segment_s3_key}` for each sealed segment, and continues
  accumulating the next.
- **Batch = session (029)**: every segment sealed during the session commits under the session's
  single batch — sealing is a durability event, not a batch boundary. Upload History shows one row
  per session with the aggregated totals (sum of records, union of tables), however many segments
  the stream sealed. The batch's timeout is measured from **last session activity**, so a live
  stream can legitimately run for hours while a silent one is reclaimed after
  `batch.timeout.minutes` (env `BATCH_TIMEOUT_MINUTES`, default 60).
- When you close the stream gracefully, the server flushes the final segment (skipped when the tail
  buffer is empty — no degenerate segment is written) and completes the session's batch, so a clean
  close never leaves the site blocked and never produces an empty extra history row. A session that
  streamed nothing at all still completes its one batch with 0 changes.
- Gap detection applies as for DELTA.
- **No reconciliation** (there is no `SessionEnd`). If the stream **drops** mid-segment, the server
  **durably seals the unsealed tail** and advances the watermark, then closes the batch — the tail is
  **not** lost. Reconnect and continue from the last `committed_seq` the server advanced to (query
  `GetSyncState` if unsure); because the drop-seal advanced the watermark past your last received ack,
  open the next session at `server_last_seq + 1`.

Periodic (`DELTA` / `FULL_SNAPSHOT`) mode is unchanged by this — pick continuous only when you want a
long-lived stream.

---

## Backpressure & acks

```protobuf
message Ack { uint64 acked_seq = 1; } // server has durably staged records up to this seq
```

- The server emits `Ack(acked_seq)` progressively (roughly every ~100 accepted records). Treat it as the
  durable watermark and bound your in-flight (unacked) window accordingly.
- The server also applies inbound flow control (it pulls one record at a time). Respect gRPC stream
  readiness on your side — don't blast records faster than the stream accepts them.

---

## Error codes & recovery actions

`ServerError` arrives **in-band** on the stream (not as a gRPC status) so it can carry a recovery action:

```protobuf
message ServerError { ErrorCode code = 1; string message = 2; RecoveryAction action = 3; uint64 resume_from_seq = 4; }
```

| `ErrorCode` | № | `action` | Meaning | What to do |
|---|---|---|---|---|
| `SEQUENCE_GAP` | 1 | `NEED_REBASELINE` | `first_seq != server_last_seq + 1` | Open a `FULL_SNAPSHOT`. |
| `SCHEMA_MISMATCH` | 2 | `NEED_REBASELINE` | declared `schema_version` is stale, or a keyless table sent an UPDATE | `SubmitSchema` / declare a key, then snapshot. |
| `RECONCILIATION_FAILED` | 3 | `NEED_REBASELINE` | `SessionEnd` counts, hash or `last_seq` ≠ accepted records | Fix your counts; re-baseline. Nothing was committed. |
| `UNAUTHORIZED` | 4 | `PROCEED` | token problem | Re-authenticate. |
| `ACTIVE_SESSION_EXISTS` | 5 | `PROCEED` | another session is live **for this site** | Serialize your own runs; retry after the other ends/times out. |
| `INTERNAL` | 6 | `NEED_REBASELINE` | unexpected server fault | Retry with backoff. Never read as overflow — overflow is typed. |
| `OVERFLOW` | 7 | `NEED_REBASELINE` | per-session record cap exceeded | From `DELTA`: retry in CONTINUOUS mode. From `CONTINUOUS` or `FULL_SNAPSHOT`: **terminal**, see below. |
| `OVERFLOW_BYTES` | 8 | `NEED_REBASELINE` | per-session byte budget exceeded | Same rule as `OVERFLOW`. |
| `SITE_INACTIVE` | 9 | `PROCEED` | site **deactivated or deleted** while the stream was open | Stop the run; an operator must reactivate it. |
| `SCHEMA_REQUIRED` | 10 | `PROCEED` | no schema on file yet | `SubmitSchema`, then retry. A snapshot hits the same wall. |
| `CONCURRENT_BATCH_LIMIT` | 11 | `PROCEED` | the **account** is at its concurrent-session cap | Back off and retry later; another site is holding the slot. |
| `GENERATION_MISMATCH` | 12 | `NEED_REBASELINE` | session epoch ≠ server epoch (the site's history was wiped) | Re-read sync state, drop the local journal, send a snapshot. |

gRPC transport-level `UNAUTHENTICATED` (closed call, no `ServerError`) means the `authorization` metadata was
missing/invalid before the handler ran. That — not `SITE_INACTIVE` — is the usual symptom of a deactivated or
deleted site, because the token is checked when the stream opens. The in-band `SITE_INACTIVE` covers the
narrower case where the site changed *after* that check and before `SessionStart`.

**Overflow is only retryable from `DELTA`.** A `CONTINUOUS` session is already the escape hatch, and a
`FULL_SNAPSHOT` must not degrade into one — CONTINUOUS publishes as it goes and cannot offer the atomic
replace-on-`SessionEnd` that makes a snapshot a snapshot. Overflow in either mode therefore means the server's
caps are misconfigured for this deployment, not that the session should be retried differently. The server
refuses to start when `delta.ingestion.snapshot-seal-records` is not below `delta.ingestion.max-session-records`,
which is the one pairing that could otherwise make a site permanently un-re-baselineable.

Rows 9–11 widened in #83, when a deactivated site, a schema-less site and the account concurrency limit stopped
escaping the typed protocol as a bare `Status.INTERNAL`. They took their numbers in
[dbf-data-extractor#130](./delta-v2-wire-contract-answers.md), which also split `OVERFLOW`/`OVERFLOW_BYTES` out
of `INTERNAL`: a client cannot safely treat `INTERNAL` as "too big, switch to CONTINUOUS" when it may equally be
a genuine fault. Against a server older than that reconciliation these five arrive as their pre-#130 codes
(`UNAUTHORIZED`, `SCHEMA_MISMATCH`, `ACTIVE_SESSION_EXISTS`, `INTERNAL`) — probe with the presence of
`SyncStateResponse.generation` if you need to know which you are talking to.

---

## Schema JSON / type mapping

The schema you submit drives the **typed Parquet** the server produces for Power BI. PostgreSQL types map to
Parquet (via Avro logical types) as:

| PostgreSQL type | Parquet / Avro |
|---|---|
| `varchar(n)`, `text`, `char`, `uuid`, `citext` | string |
| `integer`, `int`, `int4`, `serial`, `smallint`, `int2` | int (32-bit) |
| `bigint`, `int8`, `bigserial` | long (64-bit) |
| `real`, `float4` | float |
| `double precision`, `float8` | double |
| `numeric(p,s)`, `decimal(p,s)` | decimal (logical, precision/scale) |
| `boolean`, `bool` | boolean |
| `date` | date (logical) |
| `timestamp[…]` | timestamp-micros (logical) |
| `bytea` | bytes |
| *anything else* | string (lossless fallback) |

`nullable: true` columns become a nullable union. Column and table names must be valid PostgreSQL identifiers
(`^[A-Za-z_][A-Za-z0-9_]{0,62}$`).

Declare `numeric(p,s)` truthfully: values are rescaled to the declared scale on write, and if a value needs
more precision than declared, the server **widens the Parquet decimal type to fit** (logged server-side as a
schema-vs-data warning) rather than failing the file — but the declared schema is what consumers see, so an
understated precision means per-file type drift. A table whose data cannot be rendered against its declared
schema at all (e.g. unparsable values) is skipped from typed Parquet egress — the segment still completes for
the remaining tables.

---

## What the server produces (egress)

You don't write these — they're how downstream tools read your data:

- **Durable raw journal**: every batch is stored as one or more compressed protobuf changelog
  segments. Ingestion seals bound memory and failure recovery; they are authoritative inputs for
  checkpoints and Parquet materialization, but are not exposed as individual backup downloads.
- **Bit BI** keeps using `GET /api/v1/plugins/bit-bi/sites/{siteId}/files`; what it downloads is the
  **reconstructed checkpoint** (latest `snapshot.parquet` per table), listed as `<table>.parquet`.
  **Breaking change (issue #113):** this used to be `<table>.csv.gz` served from a gzipped CSV
  snapshot the checkpoint build wrote alongside the Parquet. That build step is gone, so the old
  name is no longer listed and no longer downloadable — a Bit BI client must read Parquet. The
  baseline contract is otherwise unchanged: `plugin_delta_baselines` still captures checkpoint
  seqs, and SQL is still emitted only for records with `seq > baseline_seq(table)`.
- **Realtime analytics consumers** keep reading a **sequential segment Parquet stream** per table:
  `egress/{siteId}/{table}/delta/seq={first}-{last}.parquet` (zero-padded sequences — listing order is
  apply order). Each file is one committed session segment: typed columns from your submitted schema
  (all nullable) plus service columns `_op` (`INSERT`/`UPDATE`/`DELETE`), `_seq`, and `_changed`.
  `DELETE` rows carry the key columns. **`_changed`** disambiguates a null cell in an `UPDATE`: it is
  a comma-separated list of the columns the `UPDATE` actually carried, so for an `UPDATE` a null cell
  in a **listed** column means *set to SQL NULL* and a null cell in an **unlisted** column means
  *unchanged* (keep the prior value); it is null for `INSERT` (full row) and `DELETE` (key only).
  Apply the files sequentially by seq; a `FULL_SNAPSHOT` session produces all-`INSERT` files, so
  bootstrap and re-baseline need no special handling **provided you apply files in seq order**. Do
  not treat a single `FULL_SNAPSHOT` file as a whole-table replacement: since 033 a snapshot larger
  than the session buffer is sealed into several segments, so one table's snapshot can span several
  files (see [re-baseline](#recovery-gaps-resume-re-baseline)). Only tables with a submitted schema
  are materialized.
- **Completed-batch download**: when a session closes, the server asynchronously replays all of its
  non-provisional raw segments in sequence order and writes exactly one unified artifact per table:
  `egress/{siteId}/batches/{batchId}/attempts/{claimToken}/{encodedTable}.parquet`. The manifest's
  winning key is the artifact returned by the Batch Detail Parquet button. It contains every
  applicable batch record once in ascending `_seq` order; physical seal boundaries do not appear in
  the UI or download contract. One batch claim opens a file-backed writer per retryable table and
  fans a shared streaming replay out by table name. With no decimal columns the changelog is read
  once; if any claimed table declares decimals, one shared envelope scan precedes one shared write
  replay. Replay cost is therefore one or two full reads, independent of table count. Heap is
  bounded by open writers × one Parquet row-group buffer rather than batch rows.
  `DELTA_BATCH_PARQUET_MAX_TEMP_BYTES` is enforced per artifact during the write; an artifact that
  crosses it is abandoned on its first deterministic attempt rather than fully rewritten on every
  retry.
- **Full per-table Parquet load**: each checkpoint build also writes the complete typed snapshot
  `checkpoints/{siteId}/{table}/seq={seq}/snapshot.parquet` (tables with a submitted schema only).
  Consumers that prefer a full load over replaying the delta stream download it from the Delta Sync
  UI (presigned URL, 15 min). It is the **only** format a checkpoint build materializes since issue
  #113 — a table with no submitted schema therefore produces no snapshot at all, which is counted as
  `delta.checkpoint.tables.unmaterialized{reason=no_schema}`. Since issue #112 the snapshot is built
  **on disk, one table at a time**: the table's folded rows are streamed into a scratch file and the
  file is streamed to S3, then deleted, so a build holds one Parquet row-group buffer and one
  scratch file at a time instead of a whole table's encoded bytes per table. `DELTA_CHECKPOINT_TEMP_DIR`
  (default `java.io.tmpdir` — point it at a scratch volume if the node's default is small) and
  `DELTA_CHECKPOINT_MAX_TEMP_BYTES` (default 10 GiB, **1 GiB on the deployed volume** — see the
  sizing note) govern that file; a table that would cross the
  ceiling is stopped during the write and skipped as
  `delta.checkpoint.tables.unmaterialized{reason=parquet_failed}`, exactly like a table whose data
  the declared schema cannot render. A later scheduled build (or a forced rebuild) **rematerializes
  a missing snapshot from the frame** without waiting for new segments (issue #128): the pointer,
  the frame and retention stay where they were, and only tables whose `s3_key_parquet` is still
  null are rewritten (a forced rebuild rewrites every table). After a full prune the frame is
  still enough — leftover changelog rows are not required, and since issue #137 the nightly tick
  still *finds* such a site: it visits the union of the sites named by `changelog_segments` and the
  sites with a checkpoint row whose `s3_key_parquet` is null. Before that it walked the segment
  table alone, so a site pruned to nothing — `DELTA_RETENTION_AUDIT_WINDOW_SEGMENTS=0`, or simply a
  table detached for longer than the window — was never revisited and only a forced rebuild could
  restore it. Having checkpoints at all is deliberately *not* a reason to visit: a site whose tables
  are all materialized and whose segments are gone stays untouched. A same-seq rematerialize that fails
  leaves a still-valid last-good key in place; detach is only for an advancing seq. An **unusable scratch directory**
  (missing, read-only, out of inodes) is the one failure that is not a table-level skip: it would
  hit every table alike, and detaching every last-good snapshot while the pointer advanced would
  throw away the keys rematerialize is supposed to restore, so the build aborts with the pointer
  and keys where they were and the next run redoes it (`CheckpointScheduler` catches per site, so
  the sweep continues). An aborted
  build can leave tables split across two seqs — those already written carry the new seq, the rest
  the previous one — until the next successful build re-materializes all of them; the per-table rows
  were never written atomically, so a consumer that needs one consistent instant should compare the
  `seq` of the tables it downloads. Since issue #126 the reload frame
  (`checkpoints/{siteId}/_frame/seq={seq}/frame.pb.gz`) is written the same way: one `ChangeRecord`
  at a time into a scratch file, then streamed to S3, then deleted. The on-disk form is unchanged
  (gzipped length-delimited protobuf — the next build's `parse` still reads it). Since issue #153 it
  is written **before** the per-table snapshots rather than after them, so a build that cannot
  produce it ends before a single snapshot object exists at the new seq — see "A frame that does not
  fit" below. The file is uploaded and deleted before the snapshot loop starts, not held open across
  it: the scratch budget below counts one file per build path, not one per artifact.
  It goes into the same
  `DELTA_CHECKPOINT_TEMP_DIR`, but since issue #138 it has **its own ceiling**,
  `DELTA_CHECKPOINT_MAX_FRAME_TEMP_BYTES` (`delta.checkpoint.max-frame-temp-bytes`, default 10 GiB —
  the value the shared key carried, so an unset key behaves exactly as before): crossing it
  **aborts the build** rather than skipping a table, because the frame is the next
  incremental seed and there is nothing else to fall back on. That is why it is a separate key and
  deliberately the wider of the two — the per-table ceiling can be pushed down under a scratch
  volume, where the cost of guessing low is a visible, self-healing hole, while the same reduction
  applied to the frame would stop the pointer and with it retention. The writers delete their own scratch
  file in `finally`; if the process dies first, `ParquetScratchOrphanSweeper` removes
  `checkpoint-*` / `batch-parquet-*` files older than `DELTA_PARQUET_SCRATCH_ORPHAN_AGE_SECONDS`
  (`delta.parquet.scratch-orphan-age-seconds`, default **4 hours**) from the configured directories,
  on startup and then every `DELTA_PARQUET_SCRATCH_ORPHAN_SWEEP_MS` (default 1 hour). Frame scratch
  files use the same `checkpoint-` prefix, so they are swept too. Age is the only safe filter on a
  volume this process may share: a sibling replica may be writing into it, and the batch-parquet
  lease is renewed for the life of a live build, so it is not a bound on file age. Where the
  directory is written by this pod alone — the GKE `emptyDir` — say so with
  `DELTA_PARQUET_SCRATCH_PRIVATE_TO_POD` (`delta.parquet.scratch-private-to-pod`, default
  **false**, set `true` in `k8s/base/configmap.yaml`): the sweep cutoff then becomes the **later**
  of the age window and this JVM's start, so scratch a dead predecessor left behind goes on the
  first tick instead of four hours later (issue #141). It is the JVM's start rather than "the first
  run" on purpose — a rebuild resumed at startup can overlap that tick, and files this process
  created are never in scope. Once the process has been up longer than the age window the two rules
  coincide again. Leave the flag false for a genuinely shared volume, and do not shorten the age
  instead: the lease half of the reasoning is independent of the mount. Raise the
  age if a create-to-delete
  interval can exceed four hours (completed-batch files are created before replay starts). What all
  of this bounds is materialization, not reconstruction: **the fold is still in heap**.
  `delta.checkpoint.duration{phase=fold}` is the number to watch on a
  very large site.

**A frame that does not fit (issue #153).** Crossing `DELTA_CHECKPOINT_MAX_FRAME_TEMP_BYTES` is
deterministic for a given fold: the 02:00 tick aborts again the next night, and the next, until the
ceiling is raised or the site is re-baselined. Two things follow, and both are handled:

- **The abort is counted, not only logged.** `delta.checkpoint.builds.aborted{reason=frame_too_large}`
  is incremented at the abort and the series is registered at zero from startup, so an alert can be
  written before the first occurrence. It is the whole build, not a table: `last_checkpoint_seq`
  does not move, so `ChangelogRetentionService` prunes nothing and the site's `changelog_segments`
  grow for as long as it lasts. `delta.seq.lag` is the companion series showing how far behind the
  site has fallen; the counter says *why*, the lag says *how bad*. The ERROR line names the key and
  the site. `reason=lossy_refold` is the second value and the other permanent freeze — a seed frame
  that reads as absent over a pruned history — so **alert on the meter, not on one tag**. The aborts
  that are *not* on it are the ones that pass: an unreadable scratch directory and an S3 refusal on
  the frame cost one tick, and a build discarded because the site's history was replaced under it
  (#136, #142) is a normal outcome of an operator action.

  **Read `lossy_refold` per site before acting.** `S3CheckpointStorage.exists` treats a `403` as
  absence on purpose — least-privilege IAM answers HEAD-on-a-missing-key that way, having no
  `s3:ListBucket` — so a bucket-policy or IAM read outage makes the frame read as absent for *every*
  site at once and each pruned-history site increments the counter for something a permission fix
  clears. **Many sites in one tick is a permissions incident; one site alone is the real thing.**
  The 403 branch logs a WARN naming the key, which is the tiebreaker. Distinguishing the two at
  source is #157.
- **A repeat costs nothing durable.** The frame is written first, so an abort leaves no snapshot
  object, no `checkpoints` row and no moved pointer. Before #153 the per-table snapshots were
  uploaded first, at the *new* seq, and their rows saved; the previous seq's objects were then
  unreferenced — a `checkpoints` row is one per `(site, table)` and holds one key, and nothing but a
  site wipe sweeps `checkpoints/{siteId}/` (#118) — so every failed nightly build left another
  orphaned generation in the bucket.

**"Nothing durable" is about the bucket, not about the consumers.** Three things do change for as
long as the abort lasts, and none of them is new damage — they are the freeze itself, made visible:

- **Retention is stopped.** `last_checkpoint_seq` is what `ChangelogRetentionService.prune` keys
  off, so `changelog_segments` and their S3 objects accumulate for the whole site.
- **The checkpoint snapshots stop refreshing.** `checkpoints/{siteId}/{table}/seq={seq}/snapshot.parquet`
  stays at the last successful build's seq while ingestion continues, so Bit BI, Parquet Export
  (`type=checkpoint`) and the Delta Sync download serve data that is increasingly stale but never
  wrong (the per-segment egress and completed-batch Parquet are unaffected — they do not go through
  the checkpoint). Before #153 those objects *were* rewritten each night, at a seq the pointer never
  adopted; that is precisely the write that orphaned the previous generation, so the refresh and the
  orphaning were the same act and could not be kept apart. **The consequence to watch is a Bit BI
  re-initialization**: it captures its baseline from the current checkpoint once, so re-initializing
  a site whose builds are aborting freezes that plugin on however old the last successful build is.
  Check `delta.checkpoint.builds.aborted` for the site before re-initializing it, and raise the
  ceiling first.
- **A detached table is not repaired while it lasts.** The rematerialize of #128 runs on the idle
  `RETRY_MISSING` pass, which is only reached when there are no new segments — and a site whose
  frame is over the ceiling is not idle. A table whose `s3_key_parquet` was nulled therefore stays
  404 until a build completes.

All three end when the build succeeds, and none of them can be fixed by waiting. There is
deliberately **no backoff**: the retry is now cheap in storage terms, and suppressing it would need
per-site state that the abort, by design, does not write. What is *not* free is the work — the frame
download, every new segment's download and the whole fold — repeated once a night. Fix the cause
rather than waiting it out: raise the ceiling (each GiB of frame costs two GiB of volume, see
below), and remember the counter will keep rising until a build succeeds.

**A failure after the frame is uploaded leaves the frame object behind.** An epoch discard (#136,
#142) or any throw in the snapshot loop — a scratch file that cannot be created because the volume
ran out of inodes or was remounted read-only, `getTableSchemas` failing — now happens with
`_frame/seq=N/frame.pb.gz` already in the bucket and the pointer still at the previous seq. Where
the old ordering orphaned nothing on those paths, this one orphans one frame; it accumulates only as
fast as `seq` advances, since a repeat at the same seq overwrites the same key. That is unreferenced
but harmless, and the
invariant that makes it harmless is worth stating exactly: **a build only ever reads the frame at
`last_checkpoint_seq`, and `uploadFrame(N)` always precedes the `recordCheckpoint(N)` that names
it** — so the frame a build seeds from is always the one written by the build that moved the pointer
there. A later build that legitimately ends at seq N overwrites the orphan; one that ends elsewhere
never looks at it. Only a site wipe sweeps `checkpoints/{siteId}/` (#118), and its cut-off (#122)
spares objects newer than its own start, so a frame orphaned *by* a wipe needs a second wipe to
collect — the same as the snapshot objects a discarded build has always left. Giving that prefix a
sweeper of its own is **#160**.

**Sizing note — the scratch budget is declared by the deployment, not by the application.**
`DELTA_CHECKPOINT_MAX_TEMP_BYTES`, `DELTA_CHECKPOINT_MAX_FRAME_TEMP_BYTES` and
`DELTA_BATCH_PARQUET_MAX_TEMP_BYTES` (all 10 GiB by default)
are enforced **per file**, inside each writer. Nothing in the application bounds the *directory*
those files share. On GKE that bound is the manifest (issue #131): the backend container mounts a
`parquet-scratch` `emptyDir` with `sizeLimit: 6Gi` at `/scratch/parquet`, both `*_TEMP_DIR` keys
point at it through `k8s/base/configmap.yaml`, and the container declares
`ephemeral-storage` **request and limit of 8Gi** — 6 GiB of scratch plus 2 GiB of headroom for the
writable layer and logs. The request is the half that matters for placement: without it the
scheduler does not account for local disk at all, and two builds on one node can drive it into disk
pressure and evict unrelated pods. `emptyDir` is deliberate over a PersistentVolume — the scratch
dies with the pod, so nothing accumulates across pod generations, and being pod-private it is what
licenses the sweeper's second rule (`DELTA_PARQUET_SCRATCH_PRIVATE_TO_POD`, see "Orphans outlive a
container restart"; within *one* pod's lifetime that sweep is load-bearing, not belt-and-braces) —
and the default (node-disk) medium is deliberate too, since `medium: Memory` would
be a tmpfs charged against the container's *memory* limit. Request equals limit so the reserved
amount is the enforced amount; GKE Autopilot caps a pod's ephemeral storage at 10 GiB, so the total
has to stay under that.

**The guards fail differently, and the deployed one is the harshest.** Crossing an application
ceiling is graceful and observable: a checkpoint table is skipped as
`delta.checkpoint.tables.unmaterialized{reason=parquet_failed}`, a completed-batch artifact is
abandoned, the frame aborts its build with the pointer and the keys where they were. Crossing the
volume `sizeLimit` is a **kubelet eviction of the whole pod** — no skip, no metric, an in-flight
ingest stream dies with it.

**Which is why the deployed ceilings are not the defaults (issue #138).** With all three keys at
10 GiB the volume was the binding constraint and the eviction happened first. Lowering the
checkpoint key was not possible while it governed two files at once: an oversized *table* is
skipped and rematerialized by a later build, while an oversized *frame* ends the build, because it
is the next incremental seed. The frame now answers to its own
`DELTA_CHECKPOINT_MAX_FRAME_TEMP_BYTES`, and `k8s/base/configmap.yaml` sets the three ceilings
beside the `*_TEMP_DIR` keys that put the writers on the volume — the application defaults stay at
10 GiB, because nothing in the application knows how large the directory it was handed is.

The deployed values are the formula below solved for 6Gi:

```
2 x max(checkpoint table, checkpoint frame) + max-concurrent x batch  <=  sizeLimit - 1Gi
2 x max(1Gi,              1.5Gi)            + 2              x 1Gi   =   5Gi        <=  5Gi
```

The reserved gigabyte is not decoration: scratch a dead container left behind survives on the
pod-private volume until the next sweep tick (#141), and kubelet acts on usage *exceeding* the
limit rather than reaching it — solving to exact equality would make the modelled peak an eviction.

`ParquetScratchCeilingBudgetTest` recomputes exactly that from the manifests — including the batch
concurrency, taken from the ConfigMap or from the `application.yml` default — so raising a ceiling,
raising `max-concurrent`, or shrinking the `sizeLimit` on its own fails the build rather than
quietly restoring the eviction. It also requires the **frame** ceiling to be the wider of the two,
because that is the one whose failure is expensive.

**Read the batch term as one claimed table per build, and the whole thing as a floor on the
guarantee rather than the budget.** A batch build opens one scratch file *per claimed table*, so a
two-table batch already doubles that term and a 6Gi volume can be overrun again — a count is not
something a per-file ceiling can bound. Only a directory-wide reservation can, and that is **#150**;
until it lands the deployment's protection is real but partial. The reserved gigabyte is an
allowance for restart residue, not a proof about it either — a dead build leaves one file per
claimed table, the same multiplier.

**Before rolling new ceilings out to a site that has been running a while, measure instead of
assuming.** One listing settles whether any of them is already too low, and a frame that is
*already* larger than its ceiling turns a working site into one whose build aborts every night
(#153) — the one regression these values can cause:

```bash
gsutil du -h gs://<bucket>/checkpoints/<siteId>/_frame/   # largest reload frame
gsutil du -h gs://<bucket>/egress/<siteId>/batches/       # largest completed-batch artifact
```

**Crossing a ceiling is cheaper than an eviction, but none of the three outcomes repairs itself.**
For scale, the largest artifact on record is in the low hundreds of MiB, so these ceilings are 3–5x
headroom — but a *deterministically* oversized artifact fails the same way on every retry:

- **a checkpoint table** is skipped and, on a seq-advancing build, `s3_key_parquet` is **detached**
  (keeping it would serve an older snapshot under a newer seq), so the table disappears from the
  Bit BI / Parquet Export listing and stays gone; the nightly rematerialize retries it and fails
  identically (#149). Raise `DELTA_CHECKPOINT_MAX_TEMP_BYTES`.
- **the frame** aborts the build: `last_checkpoint_seq` does not move, retention stays frozen, and
  every following build repeats the fold from scratch — but since #153 it repeats it for free, and
  the abort raises `delta.checkpoint.builds.aborted{reason=frame_too_large}` instead of only an
  ERROR line (see "A frame that does not fit" above). Raise
  `DELTA_CHECKPOINT_MAX_FRAME_TEMP_BYTES` first when a site outgrows the pair, and note each extra
  GiB of frame costs **two** GiB of volume, so `sizeLimit` and `ephemeral-storage` move in the same
  commit.
- **a completed-batch artifact** is `ABANDONED` on the first attempt and answers `404` from then on.
  Raise `DELTA_BATCH_PARQUET_MAX_TEMP_BYTES` and requeue the row through
  `POST /api/v1/sites/{siteId}/delta/batches/{batchId}/parquet-artifacts/{artifactId}/requeue`.

In every case the records themselves are still in the changelog segments and their per-segment
Parquet; what is lost is the derived artifact until the ceiling is raised.

**Recomputing the budget.** The peak is a checkpoint build and completed-batch builds running at
once:

```
peak = checkpoint_peak + batch_peak + orphan_residue
checkpoint_peak = 2 x max(largest per-table snapshot, whole-site reload frame)
                    # each term capped by its own key since #138: DELTA_CHECKPOINT_MAX_TEMP_BYTES
                    # for the snapshot, DELTA_CHECKPOINT_MAX_FRAME_TEMP_BYTES for the frame
                    # one file at a time PER BUILD (the cron sweep walks one site and one table
                    # at a time, and since #126 the frame is written the same way) — but there
                    # are two build paths per pod: CheckpointScheduler's ReentrantLock guards
                    # only the cron thread, while a forced rebuild runs rebuildFromFrame on the
                    # separate single-thread `deltaRebuildExecutor`. An admin pressing "Rebuild"
                    # during the 02:00 sweep, or resumePendingRebuilds() firing at startup,
                    # gives two concurrent checkpoint scratch files in one JVM.
batch_peak      = delta.batch-parquet.max-concurrent (2)
                    x tables claimed per batch          # one scratch file per claimed table,
                                                        # all opened before the shared replay
                    x largest per-table artifact
orphan_residue  = whatever a container restart left behind, until the next sweep tick
                    # bounded by DELTA_PARQUET_SCRATCH_ORPHAN_SWEEP_MS (1 h) on this
                    # deployment, not by the 4 h age window — see "Orphans outlive a
                    # container restart" below, and "One sweep interval means the tick
                    # runs when it is due" for the scheduler that bound assumes
```

There is no distributed lock on the sweep, so "one site at a time" is per pod: each replica runs
its own. `delta.batch-parquet.max-concurrent` and the table count per site are the two multipliers
to check before assuming the budget still holds; #128 also raised how often large files are written,
since a scheduled build now rematerializes every table whose snapshot is missing and a forced
rebuild rewrites all of them.

**Orphans outlive a container restart.** When scratch lived in the container's writable layer, a
restarted container got an empty `/tmp`. An `emptyDir` is cleared only when the *pod* goes away, so
after a liveness kill or an OOM kill mid-build the dead attempt's files stay on the volume — while
a lease-expired batch claim is retried within `DELTA_BATCH_PARQUET_LEASE_SECONDS` (30 min) and
allocates a second full set. The age filter alone would hold that residue for four hours, which is
why this deployment declares the volume pod-private
(`DELTA_PARQUET_SCRATCH_PRIVATE_TO_POD: "true"` in `k8s/base/configmap.yaml`, issue **#141**):
everything present when the JVM starts belongs to a previous container, so the restarted process
drops it on its first sweep tick and the residue term shrinks to at most one sweep interval. Do
**not** shorten `DELTA_PARQUET_SCRATCH_ORPHAN_AGE_SECONDS` to chase the same effect — a live
build's files are exactly as old as the build, so a lower age deletes live work. And if the two
`*_TEMP_DIR` keys are ever moved off the pod-private volume, the flag has to move with them.

**"One sweep interval" means the tick runs when it is due.** That is a statement about the
scheduler, so the scheduler is pinned rather than inherited (issue **#146**).
`SchedulingConfiguration` declares the application's `TaskScheduler` — a `ThreadPoolTaskScheduler`
of `spring.task.scheduling.pool.size` (**6**, overridable with `SPRING_TASK_SCHEDULING_POOL_SIZE`)
— so the nightly checkpoint build, which can hold its thread for hours, leaves threads for the
scratch sweep, the batch timeout sweep and the monthly partition creation. Without the bean the
choice followed `spring.threads.virtual.enabled`: with it on, Spring Boot builds a
`SimpleAsyncTaskScheduler` that runs **every fixed-delay task on one internal thread** (the scratch
sweep among them) and ignores the pool-size key entirely; with it off, a pool of one. Two
consequences for an operator:

- **Setting `SPRING_TASK_SCHEDULING_POOL_SIZE` to 1 restores the bug.** The residue term above then
  has no bound anyone can quote, because a sweep tick can sit behind a whole checkpoint build.
- **Raise it, and keep it below `spring.datasource.hikari.maximum-pool-size` (12).** Ten of the
  fifteen scheduled tasks open a connection; a pool as wide as Hikari's lets a burst of ticks take
  the connections request threads need. Raising it also moves the connection-pool derivation
  described next, because this pool is its largest single term.

The bean also fixes three shutdown settings in code, because a pool changes what a rollout does to a
running task: the threads are **daemon**, they are **not interrupted** on context close (as they were
when they were virtual threads), and the queue of not-yet-due ticks is **dropped**. Boot's default
would have called `shutdownNow()`, and an interrupted checkpoint build does not merely stop —
`CheckpointService` catches the exception per table and detaches that table's snapshot key, so a
02:00 deployment would leave a table answering `404` until the nightly rematerialize. A doomed build
should die with the process instead. Dropping the queue is what keeps that from costing anything: a
plain shutdown still runs already-queued *delayed* tasks, and every cron tick is queued as one, so
the pod would otherwise sit with parked threads until the monthly partition job came due. The
scheduler also stops triggering the moment the context closes, rather than when its own bean is
destroyed — otherwise a tick due in between could open a transaction on a `DataSource` its peers had
already closed.
`spring.task.scheduling.shutdown.await-termination-period` still applies if a deployment wants
shutdown to wait for what is running.

One side effect worth knowing before the next dashboard change: Boot binds executor metrics to a
`ThreadPoolTaskScheduler` and did not bind the scheduler this replaces, so `/actuator/prometheus`
grows an `executor_*{name="taskScheduler"}` family. Nothing was renamed or removed. Read
`executor_queued_tasks` on it as "ticks not yet due" — the delayed queue holds every future
execution, about fifteen at rest — rather than as a backlog.

`ScheduledTaskInventoryTest` guards both numbers **as they are declared in the YAML** — every
profile, so one that resizes the connection pool has to resize this one too — and fails when a new
`@Scheduled` method lands without the audit behind the size being redone. An environment override is
outside its reach by definition: `SPRING_TASK_SCHEDULING_POOL_SIZE` set at deploy time is checked by
nobody, so treat it the way the scratch ceilings are treated and change it in the manifests, in the
open.

### The connection pool is smaller than the threads that can ask it for a connection

That is deliberate (issue **#161**), and worth knowing before reading a `connection-timeout` in the
logs as a bug. The audited background pools declare **32** threads between them — the scheduler (6),
`pluginExecutor` (10), `pluginExecutionExecutor` (8), the three queue workers (2 each), the
forced-rebuild executor (1) and the batch-parquet lease renewer (1) — before a single HTTP or gRPC
request asks for one, and both request layers are unbounded (virtual-thread-per-request, and
grpc-java's default cached pool). `spring.datasource.hikari.maximum-pool-size` is **12**.

Nothing deadlocks, because **no unit of background work ever needs two connections at once**: every
`REQUIRES_NEW` that could nest is arranged not to. A shortage therefore costs a wait of up to
`connection-timeout` and a retry on the next tick, which background work survives. The pool is sized
so the consumers that *cannot* absorb a wait are covered outright — the ones that pin a connection
across S3 I/O instead of releasing it between statements: the scheduler's ceiling, the egress
workers and the delta-SQL workers, `6 + 2 + 2`, plus 2 kept for request threads.

The bound from the other side is the cluster's, and it is the one to check before raising anything:
the pool is per replica, `max_connections` is not, and a cron tick fires on **every replica at the
same instant**. `(maxReplicas 6 + maxSurge 1) x 12 = 84`, plus 10 for
`superuser_reserved_connections`, `psql`, migrations and exporters, against PostgreSQL's default of
100. **That 100 is an assumption** — this repository never names the database, `DB_URL` arrives from
a secret — and so is the pool: twelve is *derived* from that budget, not observed. No load
measurement of `hikari_connections_pending` or `hikari_connections_acquire_seconds` exists, and both
are already exported by the Micrometer binding on `/actuator/prometheus` if you want one.

So, in order, to give the pool more room:

1. `SHOW max_connections` on the actual server, record it beside the key, and raise
   `DEFAULT_MAX_CONNECTIONS` in `BackgroundConnectionDemandTest` — the test fails until both move
   together, which is what stops the pool quietly outgrowing the database.
2. Or lower a long holder: each `-1` on `DELTA_EGRESS_MAX_CONCURRENT` or
   `PLUGIN_SQL_GENERATION_DELTA_MAX_CONCURRENT` frees a slot without touching the database.
3. Better than either, shorten the holds themselves — both of those workers keep a connection open
   across S3 round trips, and one of them across a semaphore wait as well, which is issue **#164**.

`BackgroundConnectionDemandTest` discovers the inventory three ways — every `@Bean` returning an
`Executor`, every `max-concurrent` property, and every pool constructed directly in
`src/main/java` — so a new background pool fails the build rather than the connection pool.

**6 GiB is an assumption, not a measurement**: we have no observed maximum for
a checkpoint frame or a batch artifact on test/prod. The only sized artifact on record is the 439k-row
snapshot that OOMed a 1536Mi pod (see the comment in `k8s/overlays/dev/deployment-backend-patch.yaml`),
whose Parquet and gzipped-protobuf forms land in the tens to low hundreds of MiB — so 6 GiB is
roughly an order of magnitude of headroom over the largest thing we know about. Replace it with a
measurement when one exists: read the object sizes under `checkpoints/{siteId}/_frame/` and
`egress/{siteId}/batches/`, feed the largest into the formula above, and move the `sizeLimit`, the
`ephemeral-storage` pair and the three per-file ceilings together — keeping the ~2 GiB gap between
`sizeLimit` and `ephemeral-storage`, and the ceilings inside the peak formula above.

The overlays do **not** repeat any of this. `dev` and `stage` patch `resources` with cpu/memory
only, and because those are strategic merge patches over maps, the base `ephemeral-storage` entries
merge in and survive — verified with `kubectl kustomize k8s/overlays/{dev,stage,prod}`. Converting
either patch to a JSON-6902 `replace` on the whole `resources` object would silently drop the disk
budget.

**Verify the mount, not just the path.** The two `*_TEMP_DIR` keys live in the shared `forge-config`
ConfigMap while the volume is declared on the backend Deployment, and both writers call
`Files.createDirectories` on the configured path. If the two ever drift — a debug pod, a future Job,
an overlay that loses the mount — nothing fails loudly: `/scratch/parquet` is silently created on
the unbounded writable layer instead, which is the exact state this budget exists to prevent. So
check that it is a mount:

```bash
kubectl exec deploy/forge-backend -n forge -- df -h /scratch/parquet   # its own filesystem line
kubectl exec deploy/forge-backend -n forge -- ls -la /scratch/parquet  # checkpoint-* / batch-parquet-*
```

**The first rollout can stall on placement.** This is the first time `ephemeral-storage`
participates in scheduling, and the deployment surges before it retires (`maxSurge: 1`,
`maxUnavailable: 0`), so the new pod must be placed while the old one still holds its 8Gi. A node
pool without that much allocatable ephemeral storage free leaves the surge pod `Pending` and
`kubectl rollout status` just times out. Check capacity before the first `deploy-dev/*` tag:

```bash
kubectl describe node | grep -A6 "Allocated resources"
kubectl get pods -n forge -o wide          # a Pending backend pod is the symptom
```

Realtime segment files appear **within seconds of `SessionCommitted`**. After the ingestion commit,
the completion callback opens a new transaction, enqueues unified batch/table artifacts in a
durable manifest, and wakes a separate bounded worker pool; callback failures are contained and
cannot turn the already committed session into an apparent client failure. A
manifest becomes `READY` only after its stable S3 object is complete. PostgreSQL advisory locking
serializes the short batch claim across replicas; each table keeps its own claim token, lease,
attempt count, and outcome. Failed table artifacts retry
independently with a doubling backoff, up to `DELTA_BATCH_PARQUET_MAX_ATTEMPTS` (default 7, which
spans about an hour) — past that the table is abandoned (and logged at ERROR) rather than rebuilt
forever, since the common causes (no declared schema, data the schema cannot render) never recover
on their own. A worker claim is durable, so a build killed by a restart still spends its attempt;
the row is picked up again once `DELTA_BATCH_PARQUET_LEASE_SECONDS` (default 30 min) pass with no
sign of life, which a live build refreshes as it goes. Operators can tune the completed-batch pool and disk policy with
`DELTA_BATCH_PARQUET_MAX_CONCURRENT`, `DELTA_BATCH_PARQUET_SWEEP_MS`,
`DELTA_BATCH_PARQUET_RETRY_DELAY_SECONDS`, `DELTA_BATCH_PARQUET_MAX_ATTEMPTS`,
`DELTA_BATCH_PARQUET_LEASE_SECONDS`, `DELTA_BATCH_PARQUET_MAX_TEMP_BYTES`,
`DELTA_BATCH_PARQUET_TEMP_DIR`, `DELTA_PARQUET_SCRATCH_ORPHAN_AGE_SECONDS`,
`DELTA_PARQUET_SCRATCH_ORPHAN_SWEEP_MS`, and `DELTA_PARQUET_SCRATCH_PRIVATE_TO_POD`. The checkpoint
path has its own pair of disk ceilings, `DELTA_CHECKPOINT_MAX_TEMP_BYTES` (per table) and
`DELTA_CHECKPOINT_MAX_FRAME_TEMP_BYTES` (the reload frame); all of them are per file and must be set
against the directory budget in the sizing note above.

Batches that completed **before** this feature shipped have no manifest row. A finished batch is
backfilled on demand: the first Parquet click enqueues its tables from the raw segments and answers
`409` (finalizing), and the next click downloads the artifact. A session still running is never
backfilled — its artifact would be missing everything it seals afterwards.

Pre-V32 segments whose per-table `stats` are null are also backfillable: the server discovers their
tables from the raw records and omits only the unavailable expected-row-count check. Expired claims
that have already spent their attempts are bulk-abandoned before the retry query, so they cannot
delay live work until another sweep. That settlement is guarded by a cheap index probe, so the
common case — nothing to abandon — costs one read instead of a cluster-wide advisory lock and a
catalog-watermark write on every poll of every worker.

## Upload History (dashboard) shows per-table stats, not files

A Delta v2 session writes no `uploaded_files` — the `Batch` row it produces always has
`uploadedFilesCount=0`. The dashboard's Upload History surfaces the real per-run signal instead:
`GET /api/v1/history/batches/{batchId}` returns `deltaStats: [{table, inserts, updates, deletes}]`
(summed by table across every segment in the batch), and the list
endpoint returns lightweight `deltaRecordCount`/`deltaTableCount` totals. Both are empty/null for
v1 file-based batches. The batch detail additionally carries the session `mode`
(DELTA / CONTINUOUS / FULL_SNAPSHOT) and `seqRange {first, last}` (feature 023, B9).

## Delta Sync UI (feature 023)

Since feature 023 the dashboard has a monitoring/management layer over Delta v2 — a **Delta Sync**
tab on the site-detail page (V2 sites only) plus a sync-health badge in the site list. It is backed
by REST endpoints under the existing UI namespaces (Auth0 OAuth2; owner routes verify site
ownership, admin routes require ROLE_ADMIN):

| Endpoint | Method | Access | Purpose |
|---|---|---|---|
| `/api/v1/account/sites/{siteId}/delta/sync-state` · `/api/v1/sites/{siteId}/delta/sync-state` | GET | owner · admin | Watermark, checkpoint pointer, schema version, `rebaselineRequested`/`rebuildRequested` flags; 404 until the client first connects |
| `.../delta/checkpoints` | GET | owner · admin | Per-table checkpoint rows with the `hasParquet` presence flag (`hasCsv` removed — #113) |
| `.../delta/checkpoints/{table}/download?format=parquet` | GET | owner · admin | Fresh presigned URL (15 min) per click. `format=csv` answers `410 Gone`: the snapshot is no longer produced |
| `.../delta/batches/{batchId}/tables/{table}/parquet` | GET | owner | Fresh presigned URL (15 min) for the exact unified completed-batch/table artifact. `409` while an attempt is queued, running or pending retry (`PENDING`/`BUILDING`/`FAILED`); `404` when absent or abandoned after `max-attempts` (for example, no renderable schema). Admin twin descoped 2026-07-08 — no admin batch-detail surface |
| `/api/v1/sites/{siteId}/delta/batches/{batchId}/parquet-artifacts` | GET | admin | Queue diagnostics for unified completed-batch artifacts: table, status, attempts, last error, and timestamps; storage and claim internals are omitted |
| `/api/v1/sites/{siteId}/delta/batches/{batchId}/parquet-artifacts/{artifactId}/requeue` | POST | admin | Audited recovery: reset `ABANDONED`, or `BUILDING` after its lease expires, to a fresh `PENDING` lifecycle; `409` for a live/unrecoverable state and `404` for a route mismatch |
| `/api/v1/sites/{siteId}/delta/segments?limit=20` | GET | admin | Recent changelog segments (seq range, records, mode, createdAt) |
| `/api/v1/sites/{siteId}/delta/checkpoints/rebuild` | POST | admin | Forced out-of-schedule checkpoint rebuild (sets `rebuild_requested`, cleared on completion) |
| `.../delta/rebaseline` | POST | owner · admin | Sets persistent `rebaseline_requested` (V35) → `GetSyncState` answers `NEED_REBASELINE` on next connect; cleared when the FULL_SNAPSHOT session **commits**, so a snapshot that drops part-way re-arms a clean retry |
| `.../delta/rebaseline` | DELETE | owner · admin | Takes a pending request back (issue #84): clears `rebaseline_requested` only — watermark, checkpoints and segments untouched → `GetSyncState` answers `PROCEED` again. Idempotent, always `200`, `status` says what it achieved: `cancelled` (called off before the client was told), `snapshot-in-progress` (a FULL_SNAPSHOT is uploading and still replaces the baseline), `client-notified` (the client already holds NEED_REBASELINE and may start at any moment), `not-requested` (nothing was pending) |
| `/api/v1/account/sites/delta/health` · `/api/v1/accounts/{accountId}/sites/delta/health` | GET | owner · admin | Bulk health inputs for all V2 sites of an account (site-list badge, one query per poll) |

All endpoints are documented in the OpenAPI spec (`/v3/api-docs`, Swagger UI).

**Client-visible effect**: the "Request full re-baseline" UI action is now the public trigger for
the `NEED_REBASELINE` recovery path described above — previously the flag had no public writer.

**Cancelling a re-baseline (issue #84)**: the request is no longer a one-way switch. While
`rebaselineRequested` is set, the Delta Sync widget shows a **Cancel request** button next to the
"Full snapshot scheduled on next connect" pill; confirming it issues the `DELETE` above and the
site returns to ordinary delta from its existing watermark — nothing else in `site_sync_state` moves,
and no checkpoint or segment is touched. This only helps *before* the client starts its
FULL_SNAPSHOT session: the cancellation never reaches a session already in flight, which keeps its
own re-baseline intent and wipes the old baseline when it commits (`DeltaRebaselineService.reset`
runs inside the commit transaction, not at session start — review r4). The request dialog now
spells out that cost (the whole dataset is re-uploaded) and that the request can be taken back
until the client starts.

Because the flag is consumed at commit, it stays raised for the whole snapshot upload, so clearing
it says nothing on its own — and neither does "some batch is open", since a CONTINUOUS session holds
its batch `IN_PROGRESS` for hours (029: batch = session). V47 records the two facts that do answer
it: `batches.session_mode` (stamped at SessionStart, carried onto the replacement batch a resumed
session mints) and `site_sync_state.rebaseline_notified_at` (stamped once when `GetSyncState` first
answers NEED_REBASELINE, cleared with the flag). `DeltaRebaselineCancellationService` then reports:

| status | meaning |
|---|---|
| `cancelled` | The request was taken back before the client was ever told — it cannot act on it. |
| `snapshot-in-progress` | A **live** FULL_SNAPSHOT session is uploading (a batch the timeout sweeper would reap does not count). It keeps its own intent and replaces the baseline when it commits, and the request is deliberately **left standing** — that is what re-arms a clean retry if the snapshot dies part-way. Reported whether or not a request was pending, so a second operator is not told "nothing to cancel" about a running snapshot. |
| `client-notified` | The client already holds NEED_REBASELINE but has not opened a session yet (it may be extracting its dataset) — or the site has an open session whose mode the server cannot see (a batch from before V47, or one opened by the previous release mid-rollout). Nothing is observable — the snapshot may still arrive. |
| `not-requested` | Nothing was pending and no snapshot is running. |

The request is cleared in every case **except** a live snapshot, which is left untouched: 033 holds
the flag to the snapshot's commit precisely so a snapshot that dies part-way is retried rather than
silently downgraded to an ordinary delta on a baseline that was never replaced, and a cancellation
must not quietly take that away. A running snapshot also shows up as `snapshotInProgress` on the
sync-state projection, so both the Delta Sync card and the header chip keep saying "Full snapshot in
progress" instead of leaving it in a toast that disappears. The cancel button is withheld while the
request `POST` is still unacknowledged — the two calls are unordered, and a `DELETE` that wins the
race would be answered `not-requested` moments before the flag appears. Making a cancellation
actually *stop* an uploading snapshot would mean aborting the session (refusing the wipe at commit is
unsafe — it is the silent FULL_SNAPSHOT-as-delta downgrade 030/T05 guards against); that is not
implemented.

**Unified Delta Parquet in the UI (feature 036, issue #93)**: the delta Batch Detail sums insert,
update, and delete counts across all batch segments before rendering. The "Table changes" card has
exactly one row and one **Parquet** pill per table. One click presigns the manifest's unified object
(`egress/{siteId}/batches/{batchId}/attempts/{claimToken}/{encodedTable}.parquet` for new claims),
never the first realtime segment slice. Existing manifests may still point to the compatible
root-level stable layout. While an attempt is queued, running or awaiting retry the endpoint returns
`409`; a missing or abandoned artifact returns `404`. Tables without a declared/renderable schema
fail independently and do not block other tables. A finished batch that predates the feature is
enqueued by the first click (`409`) and downloads on the next one.

**Download error toasts (review rounds 2–3)**: a failed pill click shows exactly one toast. The
server's `ErrorResponseDto.message` wins when present (e.g. the 503 "Object storage is temporarily
unavailable. Please try again." on an S3 outage, or a specific 404 reason); a bodyless 404 (file
genuinely absent) falls back to the pill's own hint ("no declared schema / not egressed yet" for
delta Parquet, "may not have been built yet" for checkpoints); anything else gets a generic retry
toast. The global interceptor toast is suppressed on presign requests and on the 20-second
sync-state poll (404 is the normal state for a never-connected site).

### Site history wipe and the generation epoch (issue #89)

A re-baseline replaces the changelog baseline and nothing else: the site schema, the upload history,
the plugin state and — crucially — the client's own sequence counter all survive it. Deleting the
site destroys the site itself, credentials included. **Wiping a site's history** is the middle
ground: everything the server knows about the site is destroyed and the site keeps its identity.

| Surface | Endpoint |
|---|---|
| Owner | `POST /api/v1/account/sites/{siteId}/delta/wipe` |
| Admin | `POST /api/v1/sites/{siteId}/delta/wipe` |

The body must echo the site's **name** — the same string the UI shows in the page title and the
client authenticates with. Anything else is a `400`.

```json
{ "confirm": "shop-42" }
```

`200` reports what went:

```json
{
  "generation": 4,
  "deletedBatches": 123, "deletedSegments": 45, "deletedCheckpoints": 12,
  "deletedFiles": 678, "deletedSqlGenerations": 9, "deletedErrorLogs": 33,
  "deletedBytes": 123456789,
  "s3DeleteErrors": 0,
  "prefixesNotSwept": 0,
  "baselineBatchDetached": false
}
```

`409` means the wipe did not happen and says which of two things to do about it: `session-in-progress`
— an ingestion session is live, stop the client (or wait for the 60-minute sweeper) and retry;
`concurrent-session` — a batch committed while the wipe ran, so the whole wipe was rolled back and can
simply be retried. Rows are deleted inside one transaction and the S3 objects strictly after it
commits, so a rollback never leaves rows pointing at files that are gone; objects the bucket refuses
are counted in `s3DeleteErrors` and left as orphans (the same trade-off retention makes).
`prefixesNotSwept` is a separate count of whole-site prefixes (`egress/{siteId}/` and
`checkpoints/{siteId}/`) that could not be listed, or were listed only partially — do not quote it
as an object count. The Danger zone tells the operator to **repeat the wipe**, which is safe and
finishes the cleanup (issue #122). A listing that dies mid-pagination still sweeps the pages it
already read. Objects whose S3 `LastModified` is in the wipe's own second or later are skipped — the bucket
timestamp is second-resolution, so a concurrent `PutObject` in that second cannot be told apart
from a pre-wipe object. A concurrent rebuild or egress worker therefore cannot have its fresh
object deleted while the row that names it survives.

**What the client sees.** `NEED_REBASELINE` alone cannot express a wipe: it means "send a full
snapshot", not "your counters are meaningless now". So `site_sync_state.generation` (V48) is an epoch
that a wipe bumps and **nothing else does** — an ordinary re-baseline never moves it. (The
server-internal `baseline_epoch` of V52 is the counter a re-baseline *does* move; it never reaches
the client — see "The guard's epoch is not `generation`" below.) It is emitted
on every surface the client reads before deciding what to send:

- `optional uint64 SyncStateResponse.generation` (field 5)
- `optional uint64 SessionOpened.generation` (field 5), on both the PROCEED and RESUME_FROM paths, so
  a long-lived client that never re-polls `GetSyncState` still observes a wipe mid-flight
- `optional uint64 SessionStart.generation` (field **6**) — the last epoch the client saw

All three carry **explicit presence** (dbf-data-extractor#130). A site starts at epoch 0 and proto3
implicit presence never puts a zero on the wire, so without `optional` "this server predates epochs"
and "this server says epoch 0" are indistinguishable. The server always sets the field on both
response messages, zero included; **absent means an older server, and nothing else**.

The guard keys on presence, not on a zero: an **absent** `SessionStart.generation` is an old client
and skips the check, while a **present** value that disagrees — `0` against a server at `1` included
— is refused with `ErrorCode.GENERATION_MISMATCH` (**12**) + `NEED_REBASELINE`. That last case is not
hypothetical: every site's first wipe moves it 0 → 1, so a "non-zero means new client" test would
have waved a correct client through exactly when its sequence numbers had become meaningless. The
guard runs *ahead* of the resume branch: a staged session is heap-local and outlives a wipe of its
own site, so a resume is exactly the path that can carry stale-epoch records back in.

> **Field numbers.** The obvious numbers were not free. The dbf-data-extractor client carries five
> private `ErrorCode` values at 7–11 (`OVERFLOW`, `OVERFLOW_BYTES`, `SITE_INACTIVE`,
> `SCHEMA_REQUIRED`, `CONCURRENT_BATCH_LIMIT`) and a `bool snapshot = 5` on `SessionStart` that this
> server never declared. The two enums are now reconciled: the server **declares and emits** 7–11
> with the client's meanings (see the error table below), and `SessionStart` field 5 stays `reserved`
> — bootstrap and re-baseline both travel as `FULL_SNAPSHOT`, so the client's `snapshot` flag has no
> server-side purpose. Full rationale and the delivery order: [`delta-v2-wire-contract-answers.md`](./delta-v2-wire-contract-answers.md).

**Client recovery sequence.** The client persists a per-site `last_seen_generation` (initially 0):

1. On every connect, call `GetSyncState`. If `response.generation` differs from the stored value
   (absent + no local journal → adopt it silently):
   1. drop the local journal/changelog for the site, reset the local seq counter to 0, forget the
      cached schema-version ack;
   2. persist the new generation **before** starting the upload — re-running the comparison is
      idempotent, so a crash between the two is safe in that order and only in that order.
2. `SubmitSchema` — the server holds none after a wipe.
3. `SessionStart{mode: FULL_SNAPSHOT, first_seq: 1, schema_version: <from step 2>, generation: <stored>}`,
   stream the whole dataset as INSERTs from seq 1, then `SessionEnd`.
4. Every later `SessionStart` echoes the stored generation. `ServerError{GENERATION_MISMATCH}` or any
   `NEED_REBASELINE` → back to step 1; a mismatch seen in `SessionOpened.generation` → abort the
   session and go back to step 1.

**Old clients still recover.** A wipe also raises `rebaseline_requested`, so `GetSyncState` answers
`NEED_REBASELINE` with `last_applied_seq = 0` and `schema_version = 0`. An old client obeys and
uploads a FULL_SNAPSHOT from its own un-reset counter, which is accepted (a snapshot is gap-exempt —
`serverLastSeq = firstSeq - 1`), and there is no SCHEMA_MISMATCH loop because the schema guard is
skipped while either side reports version 0. Data-wise the site recovers; only the "counters reset to
zero" semantics needs the updated client.

**Bit BI re-initializes itself.** After a wipe the plugin's delta baselines are re-captured
automatically — no manual `POST /reinit` on this path. The trigger is the **first checkpoint built
after the wipe**, not the FULL_SNAPSHOT commit: at commit time every checkpoint of the site has just
been deleted in the same transaction, so a recapture there would freeze baseline 0 and the DELTA
segments that follow would generate SQL overlapping the checkpoint snapshots the plugin client
downloads (duplicate rows in Bit BI). Baselines must be checkpoint seqs, exactly as in a manual reinit. The
window between snapshot and checkpoint behaves as before: snapshot segments suspend the site's
baselines, so no SQL is produced while there is nothing consistent to base it on. One
`DELTA_AUTO_REINIT` entry lands in `plugin_audit_logs`. **An ordinary re-baseline is unchanged** and
still needs a manual reinit.

**Both egress layers go too.** Unified completed-batch manifests record the exact winning key. New
claims write immutable
`egress/{siteId}/batches/{batchId}/attempts/{claimToken}/{encodedTable}.parquet` objects; rows made
before feature 040 keep their root-level stable keys and need no migration. Realtime segment objects
have no dedicated object manifest, so the wipe performs a paginated walk of
`egress/{siteId}/…`; that covers every segment file, every unified attempt, and any object left by
an interrupted publication or cleanup. This is correctness, not housekeeping: realtime keys are
derived from sequence numbers (`egress/{siteId}/{table}/delta/seq={first}-{last}.parquet`) and a wipe
sends those numbers back to zero. A listing failure is logged and the wipe still reports success
because the database rows are already gone; so is a delete phase that fails outright, which reports
every key it was handed as `s3DeleteErrors`. Treat that count as a floor rather than a census — a
whole failed 1000-key delete batch is recorded as one entry (#123). A prefix that could not be
listed, or was listed only partially, is reported separately as `prefixesNotSwept` rather than
folded into that number (issue #122). Any non-zero value of either field means the same thing:
orphans remain, and re-running a wipe is safe and is how they get swept. Ordinary batch retention and explicit admin batch deletion likewise remove
the unified manifest rows, preserve recorded/legacy exact-key fallbacks, and paginate the complete
batch prefix so a process-death attempt with no published metadata is still found. Explicit admin
deletion defers prefix enumeration and object removal until its database transaction commits.
Listing/deletion failures remain best effort. Realtime segment cleanup remains on its existing
lifecycle path.

**The checkpoint prefix is walked the same way (issue #118).** The `checkpoints` row is one per
`(site, table)` and reused across builds: each build writes
`checkpoints/{siteId}/{table}/seq={seq}/snapshot.parquet` under a new `seq` and replaces the key on
the row, so the previous build's object is unreferenced from that moment on — and since issue #113 a
build that cannot materialize Parquet nulls the key outright. Nothing else sweeps them: changelog
retention prunes segments and no lifecycle rule covers this prefix, so before #118 a long-lived site
left one orphan per table per build behind the operation whose whole contract is "clean slate". The
wipe therefore paginates `checkpoints/{siteId}/…` after the database work, exactly as it does for
egress, keeping the keys recorded on the rows as the fallback for a failed listing. The walk also
takes the `_frame/seq={seq}/frame.pb.gz` reload frames, which no row has ever named — the wipe is the
only thing that can remove them at all. They are dead bytes rather than a stale read: a build writes
its frame at the seq it ends on and advances the checkpoint pointer only afterwards, so the frame the
next build reads is by construction the one this epoch just wrote, overwriting any pre-wipe namesake
at that key.

**A checkpoint build that overlaps the wipe is discarded (issue #136).** The `LastModified` cut-off
protects a concurrent build's *objects*; its *rows* need the epoch. `CheckpointService.buildCheckpoint`
is non-transactional by design — it spans the frame download, one download per segment and one
upload per table — and it runs from the nightly `CheckpointScheduler` as well as from a forced
rebuild, so it can still be mid-flight when a wipe commits. Left alone it would re-insert the
`checkpoints` rows the wipe deleted and, worse, restore a pre-wipe `last_checkpoint_seq` on a site
whose epoch has just restarted at zero. `ChangelogRetentionService.prune` keys off that pointer:
the new epoch's segments all sit far below it, so they would be pruned as "below checkpoint" down to
`delta.retention.audit-window-segments` — and the next build would then find a pointer it cannot
honour (no frame, history pruned) and refuse the lossy refold, leaving the site's checkpoint pipeline
stuck until someone intervened.

Each database write of a build therefore runs in its own short transaction that first takes the
`site_sync_state` row lock the wipe holds for the whole of its transaction, then compares the site's
**epoch pair** with the one the build read when it started (`CheckpointEpochGuard`). Only two
orderings remain: the write commits before the wipe takes the lock, so the wipe's own deletes remove
it, or it waits for the wipe and is then refused. A refused write ends the whole build — it is not a
per-table skip — and the build logs `Discarding the checkpoint build for site …` and returns an empty
fold; the pointer, the rows and the frame are all left to the new epoch. No S3 traffic happens inside
the lock, so the build still holds a database connection only for the length of a single statement.
Snapshot objects the discarded build had already uploaded stay behind as orphans (the cut-off
deliberately spares them from the walk); re-running the wipe sweeps them, as it does for every other
orphan on this path.

**The guard's epoch is not `generation` (issue #142).** A **re-baseline** does the same damage to a
build in flight — `DeltaRebaselineService.reset` deletes every `checkpoints` row of the site and
zeroes `last_checkpoint_seq` inside the FULL_SNAPSHOT `SessionEnd` commit — and yet it must leave
`generation` exactly where it is, because that field is the wire epoch and moving it would tell the
client to drop its journal and reset its counters. Keyed on `generation` the guard saw nothing and
the build restored the pre-re-baseline pointer, and unlike the wipe case this was **silent**: `reset`
deletes the segment objects but not the checkpoint `_frame/seq=N/frame.pb.gz`, so the next build
found `frameExists(N)` true, seeded the fold from the **discarded** baseline's frame and folded the
new snapshot on top — rows deleted at the source reappearing in every checkpoint Parquet served to
Bit BI and Parquet Export, with no pruning alarm and no "refusing lossy refold".

So there are two epochs on `site_sync_state`:

| Column | Moved by | Travels to the client | Read by |
|---|---|---|---|
| `generation` (V48) | a history wipe, and nothing else | yes — `SyncStateResponse` / `SessionOpened` / `SessionStart` | the client's counter-reset guard |
| `baseline_epoch` (V52) | a history wipe **and** a re-baseline | never | `CheckpointEpochGuard`, `CheckpointRecordedEvent` |

The guard carries and compares **both** (`SiteEpoch`), and never compares one against the other.
`baseline_epoch` looks like the strictly stronger signal, but only within one version: a pod that
predates V52 bumps `generation` alone, so during a rolling deployment a wipe issued from an old pod
would be invisible to a new pod watching only the baseline epoch — #136's hole, re-opened through
#142's fix. A move of either counter, from either version, refuses the write.

**Read the epoch no later than the data it guards.** `CheckpointService.run` reads the sync state
*before* the segment list. The other way round, a reset committing between the two hands the build
the pre-reset segments together with the **new** epoch: every guarded write then compares equal and
is approved, and the build folds the discarded baseline, uploads a frame at its last seq and moves
the pointer there — the same resurrection, arrived at through the guard. This order makes the epoch
only ever older-or-equal to the data, which is the direction the guard refuses.

`reset` now takes the same `site_sync_state` row lock the wipe holds, and takes it **before** it
deletes anything: loading the row last left a window between the checkpoint deletes and the epoch
bump in which a guarded write could still land. With the lock taken first the two orderings above are
again the only ones, and the old frame stays a harmless orphan because the pointer that would name it
can no longer come back. `reset` runs as the first statement of the FULL_SNAPSHOT commit, so that row
lock is held for the rest of that transaction — but everything that follows it is a statement: the
tail segment's `PutObject` happens **before** the transaction opens (issue #147, below), so the
per-site mutex is never held across a network call.

**The event published after the build carries that epoch too (issue #142).**
`CheckpointRecordedEvent` is published one statement *after* the guarded pointer write commits, so a
wipe can commit in the gap and the event then describes a history that no longer exists. Publishing
it inside the guard's transaction is not an option: `DeltaWipeReinitListener` is a synchronous
`@EventListener` in its own `REQUIRES_NEW` transaction and its `wipe_pending` update would block on
the row lock the suspended guard transaction still holds — a self-deadlock. The event therefore
carries the epoch pair, and the flag is taken by a conditional statement scoped to it
(`clearWipePending(siteId, generation, baselineEpoch)` — both counters, for the rolling-deployment
reason above). Without that predicate a pre-wipe event consumed the new
wipe's `wipe_pending`, `recaptureForSite` froze baselines from a `checkpoints` table the wipe had just
emptied — zero baselines — and the automatic Bit BI re-initialization below was lost until someone
ran a manual reinit.

**Still a non-goal**: an ordinary **re-baseline** leaves superseded checkpoint objects behind,
including the old reload frame. They are addressed by key from the live `checkpoints` rows and the
checkpoint pointer, both of which the reset clears, so no stale read is possible through them.

**Forced rebuild semantics (review r3, issue #128)**: `POST .../checkpoints/rebuild` is idempotent
— a second request while one is pending answers `202 {"status": "already-queued"}` and queues
nothing. A full rebuild queue answers 503 (flag cleared); rebuild flags orphaned by a restart are
re-driven on startup, so the "Rebuild queued" chip can no longer stick forever. The queued build
calls `rebuildFromFrame`: it rematerializes every table from the existing frame even
when there are no new segments, and it does not move the checkpoint pointer.

### No S3 inside the ingestion commit (issue #147)

The commit of a session — the tail segment's row, the watermark advance, the batch completion, and
on a FULL_SNAPSHOT the baseline reset — is one transaction, and every step in it is a statement. The
segment's `PutObject` happens **before** that transaction is opened:
`ChangelogSegmentService.prepare` serializes, uploads and returns a `PreparedSegment`;
`DeltaSessionCommitTransaction` then opens the transaction and writes the row from it. The
non-transactional `DeltaSessionCommitService` is what enforces the order, and `prepare` throws if a
transaction is already active, so the hold cannot be reintroduced silently.

This is the same invariant `CheckpointEpochGuard` states for the checkpoint build ("no S3 traffic
inside the lock") and the reason it matters here is the row lock above: since #142 `reset` takes the
`site_sync_state` row lock as the commit's first statement, and a multi-second upload of a large
snapshot tail used to sit inside that hold, pinning a HikariCP connection with it.

What it costs: an upload whose transaction then fails leaves an object nobody references. The key
carries a freshly minted segment id (`delta/{siteId}/segments/{segmentId}.pb.gz`), so the bytes are
unreachable without the row — but also unreclaimable: segment objects are deleted by key **from
their rows**, and a site history wipe's prefix walks cover `egress/` and `checkpoints/` only. That is
not new (the upload always preceded the watermark advance and the batch completion, and nothing
deleted it when those failed), and moving it ahead of the whole transaction adds one more way to get
there — a failure inside `reset` itself, i.e. the row-lock wait behind a concurrent wipe. A client
retries the whole session, so a repeatedly failing large snapshot leaves one full-size copy per
attempt. Reclaiming them is tracked as **issue #158**; a compensating delete in the caller is
deliberately *not* it, because an exception can surface after the transaction committed (an
`AFTER_COMMIT` listener throwing) and the delete would then destroy a live segment.

---

## End-to-end example (pseudocode)

```python
# Stubs generated from delta-ingestion.proto
channel = grpc.secure_channel("ingest.example.com:443", creds)
stub = DeltaIngestionStub(channel)
md = [("authorization", f"Bearer {access_token}")]

# 1. Submit schema (once / on change)
stub.SubmitSchema(SchemaRequest(tables={
    "customers": TableSchema(
        columns=[
            Column(name="id",         type="bigint",        nullable=False),
            Column(name="email",      type="varchar(255)",  nullable=True),
            Column(name="balance",    type="numeric(10,2)", nullable=False),
            Column(name="created_at", type="timestamp",     nullable=False),
        ],
        primary_key=["id"],
    )
}), metadata=md)

# 2. Align watermark
state = stub.GetSyncState(SyncStateRequest(site_id=site_id), metadata=md)
seq = state.last_applied_seq                       # 0 → bootstrap
mode = SessionMode.FULL_SNAPSHOT if seq == 0 else SessionMode.DELTA

# 3. Stream a session
def client_events():
    yield ClientEvent(start=SessionStart(mode=mode, first_seq=seq + 1,
                                         schema_version=state.schema_version,
                                         client_session_id=str(uuid4())))
    inserts = 0
    for row in diff_since(seq):                    # your source diff
        s = next_seq()
        yield ClientEvent(change=ChangeRecord(
            table="customers", op=Op.INSERT, seq=s,
            key={"id": Value(int_value=row.id)},
            data={
                "id":         Value(int_value=row.id),
                "email":      Value(is_null=True) if row.email is None else Value(string_value=row.email),
                "balance":    Value(decimal_value=str(row.balance)),   # exact decimal as string
                "created_at": Value(string_value=row.created_at.isoformat()),
            }))
        inserts += 1
    yield ClientEvent(end=SessionEnd(last_seq=current_seq(),
                                     per_table={"customers": TableStats(inserts=inserts)}))

for ev in stub.StreamChanges(client_events(), metadata=md):
    if ev.HasField("opened"):
        if ev.opened.action == RecoveryAction.RESUME_FROM:
            ... # replay from ev.opened.resume_from_seq
        elif ev.opened.action == RecoveryAction.NEED_REBASELINE:
            ... # reopen with FULL_SNAPSHOT
    elif ev.HasField("ack"):
        advance_local_window(ev.ack.acked_seq)
    elif ev.HasField("committed"):
        persist_watermark(ev.committed.committed_seq)   # advance only on commit
    elif ev.HasField("error"):
        handle(ev.error.code, ev.error.action)
```

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Call closed with `UNAUTHENTICATED` | missing/expired `authorization` Bearer token | refresh the access token; attach `authorization: Bearer …` metadata |
| `SEQUENCE_GAP` at `SessionStart` | `first_seq` > `last_applied_seq + 1` (forward gap) | `GetSyncState` then open at `+1`, or `FULL_SNAPSHOT` to re-baseline (a replay at ≤ watermark is accepted, not a gap) |
| `RECONCILIATION_FAILED` | `SessionEnd` per-table counts, `content_hash`, or `last_seq` ≠ records sent | count exactly per table; set `last_seq` to your highest seq; for resume use **cumulative** counts |
| `INTERNAL` "exceeded … record limit" | one non-continuous session buffered too many records | stream large datasets in `CONTINUOUS` mode |
| `INTERNAL` "exceeded … byte buffer budget" | one non-continuous session buffered too many bytes (fat rows can trip this far below the record cap) | stream large datasets in `CONTINUOUS` mode, or raise `DELTA_MAX_SESSION_BYTES` alongside pod memory |
| `ACTIVE_SESSION_EXISTS` | another session live for this site (or a prior dropped batch not yet timed out), or the account is at `MAX_CONCURRENT_BATCHES` | serialize sessions; for a resumable DELTA drop, reconnect to get `RESUME_FROM` |
| `SCHEMA_MISMATCH` at `SessionStart` on a fresh CDC site | the site is `POSTGRES_CDC` and has no schema on file | `SubmitSchema` first — a `FULL_SNAPSHOT` hits the same wall |
| `UNAUTHORIZED` on a token that still validates | the **site** was deactivated or deleted after this stream opened | an operator must reactivate the site; retrying and re-authenticating will not help |
| Records sent, no `SessionCommitted`, stream closes cleanly | change records were streamed without a `SessionStart` the server accepted | check the server log for `Delta change records discarded`; open a session first and read its `SessionOpened` before streaming |
| `UPDATE` rejected | the table is keyless (empty `primary_key`) | emit `DELETE` + `INSERT` instead |
| Wrong Parquet types / parse errors in egress | wire value doesn't match declared type | send decimals/dates/timestamps as **strings**; keep `SubmitSchema` in sync with the data |
| Watermark did not move after a continuous drop | stream dropped mid-segment | the server durably seals the unsealed tail and closes the batch — reconnect and continue from `GetSyncState.last_applied_seq`, which already includes it |

---

## Server-side observability (#83)

Until #83 the ingestion path carried no logger at all, and since client API v1 was retired it is the
only way a batch can be created — so a rejected session left no trace anywhere and an incident could
only be investigated by proving the *absence* of log lines. When a client reports a problem, these are
the lines to grep in the backend pod. All are bounded **per session and per seal, never per record**.

| Line (prefix) | Level | Carries |
|---|---|---|
| `auth_failure: gRPC …` | WARN | the gRPC method, the reason, the peer — a call rejected before any handler ran |
| `Delta schema submitted` / `Delta schema rejected` | INFO / WARN | `siteId`, `schemaVersion`, table names — the upstream cause of later `SCHEMA_MISMATCH`es |
| `Delta session start` | INFO | `siteId`, `mode`, `firstSeq`, `schemaVersion` as the client declared them |
| `Delta session opened` | INFO | `batchId`, `action`, `serverLastSeq` (or `resumeFromSeq` + `stagedRecords` on a resume), `rebaseline` |
| `Delta segment sealed` | INFO | `segment` S3 key, `records`, `bytes`, `firstSeq`, `committedSeq` — one per continuous seal |
| `Delta snapshot segment sealed (provisional…)` | INFO | the same fields for a re-baseline seal (033). Deliberately **not** accompanied by a `SessionCommitted` on the wire, and not yet visible to any consumer — it is published only when the snapshot commits |
| `Delta session committed` | INFO | `committedSeq`, `records`, `segment`, `rebaseline` |
| `Delta session transport drop` | INFO | **why** the stream broke: the gRPC `status` and the cause. One per abnormal ending, including one with no session open at all |
| `Delta session staged for resume` | INFO | a mid-stream drop parked for reconnect: `stagedRecords`, `resumeFromSeq` |
| `Delta session rejected` | WARN | **every** in-band `ServerError`: `ErrorCode`, `RecoveryAction`, `batchId`, message |
| `Delta change records discarded` | WARN | a client streaming records the server never opened a session for — once per stream, not per record |
| `Delta staged session evicted` | WARN | a client that dropped and never came back; its buffered records are discarded |
| `Delta session failed` | ERROR | an unexpected fault, with the stack trace `Status.INTERNAL` never carried |

A session that opens and commits normally produces three lines (`start`, `opened`, `committed`) plus
one per seal. If a client claims it uploaded and you see no `Delta session start` for its site, the
request never reached the handler — look for `auth_failure` next. A session that ends on
`transport drop` instead of `committed` broke below the protocol; the `status` on that line says
whether the client cancelled, a deadline expired, or the server itself closed the stream at
`delta.grpc.max-connection-age-seconds` (routine for a long `CONTINUOUS` session). A stream that
shows `Delta change records discarded` and nothing else never opened a session at all: the client
streamed records past a `SessionStart` that was rejected or never sent, and the server dropped
every one of them.

### Metrics

Micrometer meters for the same events (`delta.sessions.started`, `delta.sessions.committed`,
`delta.sessions.overflow{reason=records|bytes}`, `delta.reconciliation.failures`, `delta.seq.lag`,
`delta.checkpoint.duration{phase=...}`, `delta.checkpoint.tables.unmaterialized{reason=...}`,
`delta.checkpoint.builds.aborted{reason=...}`,
`delta.egress.segments`, `delta.egress.duration{phase=...}`,
`delta.egress.pending`, `delta.batch-parquet.duration{phase=...}`) are exposed on
`/actuator/prometheus` and `/actuator/metrics/**`.

Both used to be denied outright, which is why the counters were unreadable during the incident that
opened #83. They are now served to the source addresses listed in
`dfm.observability.metrics-scrape.allowed-cidrs` (`METRICS_SCRAPE_ALLOWED_CIDRS`, comma-separated):

- **Empty is the default**, and empty means nobody — an environment that does not set the variable
  keeps the old 403, so nothing is opened by merging this. A malformed entry is dropped with a WARN
  (that range stays denied) rather than failing the application context.
- On GKE dev the value is `10.4.0.0/14,127.0.0.1/32,::1/128`: the pod range the managed-Prometheus
  collector scrapes from, plus loopback in both address families for `kubectl port-forward`.
  Matching is per address family, so an IPv4 entry never covers an IPv6 caller. Nothing outside the
  cluster can present such a source address — the frontend nginx only proxies `/api`, so
  `/actuator` has no external route at all, and load-balancer traffic would arrive from a Google
  front-end range. The decision reads the socket peer, which is why
  `server.forward-headers-strategy` is pinned to `none`.
- The rest of the actuator surface (`/actuator/env`, `/actuator/beans`, …) stays denied to everyone,
  and is not exposed by `management.endpoints.web.exposure.include` in the first place.

Collection on dev is a `PodMonitoring` (`k8s/overlays/dev/podmonitoring.yaml`, 30 s interval) picked
up by the managed-Prometheus collector that already runs in the cluster; the samples land in Cloud
Monitoring under `prometheus.googleapis.com/<exported name>/counter` and are queryable with PromQL.

**Use the exported name, not the Micrometer one** — a query written as `delta.sessions.started` or
even `delta_sessions_started` selects no series. Dots become underscores and every counter gains a
`_total` suffix:

| Micrometer meter | Prometheus / Cloud Monitoring |
|---|---|
| `delta.sessions.started` | `delta_sessions_started_total` |
| `delta.sessions.committed` | `delta_sessions_committed_total` |
| `delta.sessions.overflow{reason=records\|bytes}` | `delta_sessions_overflow_total{reason=...}` |
| `delta.reconciliation.failures` | `delta_reconciliation_failures_total` |
| `delta.egress.segments` | `delta_egress_segments_total` |
| `delta.egress.pending` | `delta_egress_pending` |
| `delta.egress.duration{phase=...}` (timer) | `delta_egress_duration_seconds_count` / `_sum` / `_max` |
| `delta.batch-parquet.queue{status=...}` | `delta_batch_parquet_queue{status=...}` |
| `delta.batch-parquet.duration{phase=...}` (timer) | `delta_batch_parquet_duration_seconds_count` / `_sum` / `_max` |
| `delta.seq.lag` (summary) | `delta_seq_lag_count` / `_sum` / `_max` |
| `delta.checkpoint.duration{phase=...}` (timer) | `delta_checkpoint_duration_seconds_count` / `_sum` / `_max` |
| `delta.checkpoint.tables.unmaterialized{reason=no_schema\|parquet_failed}` | `delta_checkpoint_tables_unmaterialized_total{reason=...}` |
| `delta.checkpoint.builds.aborted{reason=frame_too_large\|lossy_refold}` | `delta_checkpoint_builds_aborted_total{reason=...}` |

Duration timers always carry a `phase` label (Prometheus cannot mix tagged and untagged series
of the same name). `{phase="total"}` is the whole cycle. Inner phases:

| Meter | Inner phases |
|---|---|
| `delta.batch-parquet.duration` | `download`, `decode`, `decimal_scan`, `write`, `upload` |
| `delta.egress.duration` | `download`, `write`, `upload` |
| `delta.checkpoint.duration` | `download_frame`, `fold`, `parquet`, `upload` |

**Row-group budget.** Every V2 Parquet writer — checkpoint snapshot, per-segment egress and
completed-batch artifact — takes its row-group size from `DELTA_PARQUET_ROW_GROUP_BYTES`
(`delta.parquet.row-group-bytes`, default 16 MiB). A writer buffers one row group in heap before
flushing it, so with file-backed writers this value, times the number of writers open at once, is
what bounds a build's memory: a batch claim opens one writer per claimed table, and parquet-mr's
own default (~128 MB) multiplied that way does not fit a 2–3 Gi pod. It is a **memory ceiling, not
a compression knob** — lowering it costs a little compression ratio and adds row-group metadata,
raising it buys nothing but risk above the heap it costs. It is also the floor on how many row
groups a big artifact carries: a multi-GB completed-batch file at 16 MiB keeps its footer (row
groups × columns, parsed in full before a reader touches data) in the hundreds of entries, which is
why the default sits at the top of the range rather than the bottom. Move it only together with the
pod's heap. The
per-segment egress writer renders its (seal-bounded, ≤ 16 MiB of records) file in memory, so there
the budget bounds only the encoder's own buffer; it takes the same key deliberately, because all
three writers share one pod's heap and one tuning decision.
`DELTA_PARQUET_SCRATCH_ORPHAN_AGE_SECONDS` / `DELTA_PARQUET_SCRATCH_ORPHAN_SWEEP_MS` /
`DELTA_PARQUET_SCRATCH_PRIVATE_TO_POD` sit next to
that key because both file-backed writers share the same recovery: leftover `checkpoint-*` and
`batch-parquet-*` files older than the age — or, on a volume declared pod-private, older than the
running JVM — are deleted from the configured temp directories.

Phase meanings differ by meter. On **batch-parquet**, `download` is GetObject / stream
`read` and `decode` is protobuf parse excluding the record consumer. On **egress**,
`download` is the whole `readRecords` (buffer + parse; there is no `decode` phase).
On **checkpoint**, `download_frame` is only the seed frame; segment GetObject/parse
is inside `fold`. `decimal_scan` / `write` / `parquet` are encode work; `upload` is
PutObject. `delta.egress.pending` is `COUNT(*)` of `changelog_segments` with
`egress_at IS NULL`, refreshed at most every five seconds.

A PromQL `_sum` that does not filter `phase` double-counts (inner phases + `total`).
Cycle rate and the write-share rule below must use `phase="total"`.

**Reading the write share.** Compare `phase="write"` (or `parquet` on a checkpoint) to
`phase="total"` over the same window:

- write < 20% of the cycle → encode is not the bottleneck.
- 20–50% → stay on Java first (file-backed checkpoint, smaller row-group).
- > 50% while S3 phases (`download` / `upload` / `download_frame`) look normal → then a
  native renderer is worth discussing.

So the Cloud Monitoring type for the session counter is
`prometheus.googleapis.com/delta_sessions_started_total/counter`. Read the raw values by hand with:

```bash
kubectl -n forge port-forward deploy/forge-backend 8080:8080
curl -s localhost:8080/actuator/prometheus | grep '^delta_'
```
