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
  the declared schema cannot render. A table stopped instead by the shared *directory* budget
  (`DELTA_PARQUET_MAX_SCRATCH_BYTES`, issue #150) is **not** counted here at all: that refusal is
  systemic rather than a verdict on one table, so it ends the build and shows on
  `delta.parquet.scratch.refused` instead. A later scheduled build (or a forced rebuild) **rematerializes
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
  of this bounds is materialization, not reconstruction: **the fold is still in heap**, and since
  issue #152 it has a ceiling of its own — `DELTA_CHECKPOINT_MAX_FOLD_BYTES`, see "The first bound
  is heap" below. `delta.checkpoint.duration{phase=fold}` is still the number to watch on a very
  large site.

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

  **`lossy_refold` is a fact about the site's own data again (issue #157).** It used to need a
  caveat: `S3CheckpointStorage` read a `403` as absence — correct for a missing key, since
  least-privilege IAM answers HEAD-on-a-missing-key that way, having no `s3:ListBucket`, and
  indistinguishable from a blanket read denial on keys that exist. A bucket-policy or IAM outage
  therefore tripped this counter for every pruned-history site in the same tick, for something a
  permission fix clears. A denied HEAD is now resolved by a one-key listing, and a
  denial that cannot be resolved answers `UNKNOWN`: the build **skips that site** untouched and
  increments `delta.s3.read-denied` instead. So a permissions incident shows up as that meter
  rising, plainly labelled, and this one keeps its promise — the aborts that do not repair
  themselves.
- **A repeat costs nothing durable.** The frame is written first, so an abort leaves no snapshot
  object, no `checkpoints` row and no moved pointer. Before #153 the per-table snapshots were
  uploaded first, at the *new* seq, and their rows saved; the previous seq's objects were then
  unreferenced — a `checkpoints` row is one per `(site, table)` and holds one key, and at the time
  nothing but a site wipe swept `checkpoints/{siteId}/` (#118) — so every failed nightly build left
  another orphaned generation in the bucket, one the daily sweep of #158 now reclaims.

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
never looks at it. A site wipe sweeps `checkpoints/{siteId}/` (#118) but its cut-off (#122)
spares objects newer than its own start, so a frame orphaned *by* a wipe needs a second wipe to
collect — the same as the snapshot objects a discarded build has always left. Since **#158** that
prefix has a sweeper of its own as well, which collects a stranded frame a day after it was written
whatever produced it.

**When is a per-table failure the table's verdict? (issues #149, #162)** The rematerialize of #128
and the work list of #137 together gave every unmaterialized `checkpoints` row a nightly retry with
nothing bounding it, while the per-table catch recorded a verdict for *any* failure, including one
that was not about the table at all. Both halves are answered in `CheckpointService`, and the
answers pull in opposite directions.

- **A retry is bounded by attempts, not by time.** A row that ends a build without a snapshot spends
  one attempt (`checkpoints.materialize_attempts`, `last_materialize_failure_at`, V53). At
  `DELTA_CHECKPOINT_MAX_MATERIALIZE_ATTEMPTS` (`delta.checkpoint.max-materialize-attempts`, default
  **5**) the nightly rematerialize stops trying and the row stops naming its site, so the frame
  download and whole-site fold that discovering as much used to cost stop with it. Attempts rather
  than seconds because the retry runs from a once-a-night cron: a delay would only ever express
  itself as skipped nights, and a counter survives a restart without a clock. What is bounded is the
  **dedicated** retry — a site with new segments is visited for its segments, and the incremental
  build there writes every table in its fold whatever the counter says, because that work is
  happening regardless.
- **Giving up is visible, not silent.** `delta.checkpoint.tables.given-up` gauges the rows that have
  spent their attempts. A non-zero value is usually a site whose client streams a table it never
  submitted a schema for; it becomes an incident when it climbs, because each of those rows is a
  table permanently missing from the Bit BI files listing, Parquet Export (`type=checkpoint`) and the
  Delta Sync download.
- **Giving up is not a dead end.** Four things put a row back: submitting the schema and letting the
  next incremental build write it, `POST .../delta/checkpoints/rebuild` (which re-arms the row
  deliberately, whether or not that attempt succeeds), a re-baseline, and a history wipe — the last
  two by deleting the rows outright. None of them is manual SQL.
- **A table that no longer exists loses its row.** The fold is the whole of a site's state, so a
  `checkpoints` row for a table absent from it describes a table whose last row was `DELETE`d at the
  source: `CheckpointFrame` emits nothing for an empty table, so the next frame never mentions it and
  no later build — not even a forced rebuild — could ever reach it again. Such a row is now deleted
  by the build that notices, after that build has written its own tables. The snapshot object it
  named joins the superseded snapshots already unreferenced under `checkpoints/{siteId}/`, which
  a site wipe (#118) and the daily orphan sweep (#158) both collect. Two limits are deliberate. The reap **never empties a site**: if every table were emptied at
  the source the fold would be empty and every row would go, and "this site has no checkpoint rows"
  is load-bearing elsewhere — `CheckpointFileQueryService` reads it as "not a Delta site yet" and
  falls back to the pre-Delta uploaded CSVs, which is exactly what it must not hand a Bit BI client
  as a current baseline. And it runs **inside a build that has work**, so a dropped table whose row
  kept a live key survives on a completely idle site until the next build with anything to do, or a
  forced rebuild; making the reap its own reason to fold a site nightly would put back the cost this
  ticket removed, for a stale listing entry rather than a missing artifact. A site whose fold is
  empty is instead **settled site-wide** — one attempt per still-retryable row, or a re-arm on a
  forced rebuild — so sparing its rows does not hand it back the unbounded nightly visit.
- **A frame that is gone with no segments behind it is not a lossy refold.** That state used to raise
  the "refusing lossy refold" alarm every night, which is wrong in kind — with no frame *and* no
  changelog there is no history to refold, lossily or otherwise — and had no exit but manual SQL. It
  now has its own message and its own
  `delta.checkpoint.builds.aborted{reason=history_gone}`, and a **scheduled** build spends an
  attempt on every still-retryable row of the site, which is what ends the nightly alarm: such a
  site is on the work list only because of those rows. A **forced** rebuild re-arms them instead —
  it is the operator asserting the cause is dealt with, and the documented recovery must not be the
  fastest way to exhaust the retry it restores. Recovery proper is a re-baseline or a history wipe.
  `reason=lossy_refold` keeps its meaning for a site whose segments survive — data that still
  exists, an alarm that must keep shouting, and a site that is visited for those segments anyway.

  **`history_gone` means gone, not unreadable (issue #157).** When #149 shipped, an S3 HEAD denial
  read as absence, so a bucket-policy or IAM read outage made every segment-less site look
  `history_gone` — and unlike the pre-#149 alarm, which repeated nightly and healed itself the
  moment the permission came back, the drain is durable: after
  `DELTA_CHECKPOINT_MAX_MATERIALIZE_ATTEMPTS` such nights the rows give up and, having no segments,
  the site names itself on no work list and is reachable only through a forced rebuild. That
  trade no longer has to be made. A denied HEAD is resolved by a one-key listing, and
  a denial that survives it answers `UNKNOWN`: the build skips the site, spends no attempt,
  and counts `delta.s3.read-denied`. This abort is now reached only when S3 itself says the frame is
  not there.
- **A build that is only ending records nothing (#162).** Spring publishes `ContextClosedEvent` and
  then destroys the singletons, so a build still running when a pod is replaced makes its next call
  against a closed `S3Client` or `HikariDataSource` — a failure that reads exactly like a broken
  table. Recording it detached a healthy `s3_key_parquet` on an advancing seq, and the table answered
  404 until the next nightly rematerialize. `ApplicationShutdownSignal` is checked before the frame
  upload, between tables and in the per-table catch: the build ends with the last-good keys, the
  pointer and the attempt counters untouched, and `CheckpointScheduler` stops walking sites for the
  same reason. It is deliberately **not** on `delta.checkpoint.builds.aborted` — that meter is for
  aborts that never repair themselves, and this one is repaired by the process that replaces it.
  **The forced path is the exception to that last clause**: a scheduled tick is found again by the
  nightly work list, but `POST .../delta/checkpoints/rebuild` has only its durable
  `rebuild_requested` flag, so a rebuild cut short by a rollout leaves the flag set (does not
  log "completed", and writes no #186 verdict, since it has not finished) and
  `resumePendingRebuilds()` re-drives it on the next start. Without that, an
  operator's click during a deployment would disappear silently — on the very action that is the
  documented recovery from a row that has given up.
- **An idle visit is cheap.** The "is there anything to rematerialize here?" probe now runs *before*
  the frame download and the fold, so a site named by the tick that turns out to have no retryable
  row costs one query against `checkpoints`. #137's invariant is unchanged: a site with every table
  materialized and no leftover segments is not visited at all.

**"S3 will not say" is a third answer, not a missing object (issue #157).** Every refusal above —
`lossy_refold`, `history_gone`, and the per-table rematerialize they drain — starts from the seed
frame reading as *absent*, and until this ticket absence was inferred from a status code that means
two different things. HEAD on a missing key answers `404` only with `s3:ListBucket`; without that
permission S3 hides existence behind a **403**, so reading a 403 as absence is the only workable
choice — and that reading is kept. What was wrong is the other direction: a blanket read denial on
keys that *do* exist answers 403 as well, so one IAM or bucket-policy change presented as N sites
having lost their checkpoint history in the same tick — and since #149 it also **spent** those
sites' finite rematerialize attempts, after which a segment-less site is named by no work list and
does not come back when the permission does.

`S3CheckpointStorage.presence(key)` now answers `PRESENT` / `ABSENT` / `UNKNOWN`:

- A denied HEAD is resolved by **listing** the key: `ListObjectsV2(prefix=<key>, max-keys=1)`, with
  the result checked for the exact key so a longer sibling sharing the prefix cannot answer for it.
  Not by *reading* it — AWS applies the same existence-hiding rule to `GetObject` as to
  `HeadObject`, so the ranged read the ticket suggested would answer 403 for a missing key on
  exactly the deployment that needs resolving, and every absence would degrade into `UNKNOWN`.
  `ListObjectsV2` is a **bucket** action with no such rule: granted, it says truthfully whether the
  key is there. The extra round trip is paid on the 403 path only.
- **This application requires `s3:ListBucket` anyway**, which is what makes the probe decidable
  rather than hopeful: site wipe walks `egress/{siteId}/` and `checkpoints/{siteId}/` (#118, #122)
  and the nightly batch retention lists the batch prefix (#100). The deployed task role grants it
  (`deploy-script/template-1763397226530.yaml`, `S3BucketAccess`), which also means HEAD *does*
  answer 404 for a missing key there and the 403 path is the rare one it is described as. A
  deployment that lacks it cannot run those paths at all — and could not distinguish absence from denial by any means, because
  hiding one behind the other is precisely what the missing permission does. **That premise is
  checked at startup**, not assumed: one `ListObjectsV2` against the bucket on
  `ApplicationReadyEvent` logs `S3 list permission confirmed`, or an **ERROR** naming the grant to
  add. It does not fail the context — a briefly unreachable S3 must not stop a pod from serving,
  and the permission can equally be fixed while it runs. Without that line the failure would be
  quiet: every absent object would answer `UNKNOWN`, the sites owning them would be skipped, and
  `delta.s3.read-denied` would climb by one per missing key instead of marking an incident. The
  check lists under `checkpoints/` rather than the bucket root, because a grant carrying an
  `s3:prefix` condition would 403 at the root while the probe works; it carries a 5 s timeout of its
  own, since a ready listener runs before Boot publishes readiness and the SDK's default 30 s x 3
  would hold the pod out of the Service endpoints.
- Only when the listing is denied too is the answer `UNKNOWN`, and that is a real read outage rather
  than a guess about one. It increments **`delta.s3.read-denied`** and logs a WARN naming the key.
- The checkpoint build **skips a site whose frame presence is unknown**: nothing is folded,
  uploaded, saved or counted, no attempt is spent, and the next tick asks again. It is deliberately
  not on `delta.checkpoint.builds.aborted`, whose contract is aborts that never repair themselves —
  this one repairs itself the moment the permission is restored. The skip is **thrown**
  (`CheckpointService.FramePresenceUnknownException`) rather than returned as an empty fold, for the
  reason #162 made the shutdown case distinguishable: `CheckpointScheduler` logs it and moves to the
  next site, while a **forced rebuild** logs an ERROR saying it did not run instead of reporting a
  rebuild that never happened, and records the verdict `FRAME_UNAVAILABLE` (#186) so the operator
  can see it without the log. Its durable `rebuild_requested` flag is still **released**, unlike
  the shutdown case: a shutdown implies the restart that re-drives the flag, a bucket-policy
  incident does not, the nightly tick runs `buildCheckpoint` rather than `rebuildFromFrame`, and
  `POST .../delta/checkpoints/rebuild` answers "already queued" while the flag is set — so holding
  it would leave the operator unable to ask again once the permission returned. Click Rebuild again
  after the fix.
- Parquet Export's file listing drops a delta row only on a **known** absence, so a denial no longer
  hides a download that is there (`GET /api/v1/plugins/parquet-export/files?type=delta`).

**During an incident, read `delta.s3.read-denied`.** Rising there and flat on
`delta.checkpoint.builds.aborted` is a permissions problem: no site has been written off, the
pointers are where they were, and the backlog visible in `delta.seq.lag` drains once the policy is
fixed. The reverse — `builds.aborted` moving while `read-denied` stays at zero — is the site's own
data, which is what those tag values were always meant to say.

**The first bound is heap, not disk (issue #152).** Everything in the sizing note that follows is
about local disk, and on the checkpoint path that is the *second* thing a growing site runs into.
The first is the fold: `CheckpointService` reconstructs the site into one map — a table per table, an entry per
surviving row — and holds it for the length of the build, while the frame and the per-table
snapshots stream to disk and cost one buffer each. On a pod whose memory limit is 2–3 Gi, that fold
is what runs out, and it runs out as an `OOMKilled`: no skip, no metric, the whole process gone with
whatever ingest was in flight, and the build's scratch left on the volume for the sweeper (#141).
Two things now stand between a large site and that:

- **Nothing but the fold is retained.** The seed frame is streamed
  (`S3CheckpointStorage.openFrame` → `GZIPInputStream` → one record at a time) instead of arriving
  as a gzipped `byte[]` that was then expanded into a `List` of every record in the site, and the
  new segments are folded as they stream rather than collected into a second list and folded in one
  call that copied the seed as well. That took the peak from roughly four full-site copies to one.
- **The one that remains has a ceiling.** `DELTA_CHECKPOINT_MAX_FOLD_BYTES`
  (`delta.checkpoint.max-fold-bytes`, **0 = auto = half the max heap**) bounds the fold in
  *estimated retained bytes*, and a build that crosses it is refused —
  `delta.checkpoint.builds.aborted{reason=fold_too_large}`, an ERROR line naming the site and the
  key, and nothing written: the abort happens before the frame upload, so the pointer, the per-table
  keys and the frame stay exactly where they were. A refusal costs one site's build; the OOMKill it
  replaces cost the pod.

Three things to know before tuning it. The unit is an **estimate** — a coarse per-row object-graph
figure (map entries, column-name strings, the values, the row's identity string), not the records'
wire size, because the fold retains a Java object graph an order of magnitude larger than its
protobuf encoding; it is within a small factor of the truth, which is what a budget expressed as a
fraction of `-Xmx` needs. It has one known blind spot, and it is worth a lower key where it applies:
a character is charged one byte, which is what compact strings give for Latin-1 text, so a site
whose string data is Cyrillic or CJK is held as UTF-16 and under-counted by roughly its string
payload. It is **derived rather than declared beside the deployment**, unlike the
scratch ceilings in the note below, because a process cannot see how big its scratch volume is but can always
see its own heap. And it is **half, not the quarter capacity planning would ask for** — the `2 x`
that shapes the scratch budget (the nightly sweep and a forced rebuild on `deltaRebuildExecutor` are
not mutually excluded) plus the ingest the pod serves would put the number at a quarter, but this
ceiling is not a capacity plan: it is the last line before an `OOMKill`, and what it does to a
build it refuses is permanent. Before this change the seed path held two to three full-site copies
at once, so a site that builds successfully today can have a fold near half the heap — sizing the
guard at the planning value would have refused, on the first tick after the deployment that made
its build cheaper, a build that fits. Set the key explicitly to a quarter if you want the
concurrency headroom.

**Size it before you trust it.** A build logs its own estimate, so one night of
`logging.level.com.bitbi.dfm.delta.application.CheckpointService=DEBUG` tells you what every site's
fold actually weighs on this deployment — worth doing before lowering the key from the default, and
the only way to know whether a site is near the edge without waiting for the WARN.

Like `frame_too_large`, this abort **does not repair itself** — a site's history does not shrink on
its own, so every following tick ends the same way with retention frozen at the pointer. The fixes
are to raise the key together with the pod's heap, or to re-baseline the site so its fold starts
from what the source still holds. A build that has not crossed the ceiling still says where it
stands: its **peak** estimate — the same running total the ceiling is enforced against, not the size
the fold happened to end at — is logged at DEBUG, at WARN once a site passes **75%** of the budget,
and recorded on `delta.checkpoint.fold.bytes`, so the band below the ceiling can carry an alert
rather than living in a log sink (`max(delta_checkpoint_fold_bytes_max)` against the configured
budget is the query). A build that aborted is deliberately absent from that series — its one
over-budget sample belongs to the counter, not to the number read as "how much room is left" — and
so is an idle visit, which answers before folding at all.

**The budget belongs to the process, not to a build (issue #178).** Enforced per build it was not
actually a bound at all: two concurrent folds of 45% each crossed nothing and still exhausted the
heap between them, which one JVM produces whenever a forced rebuild runs beside the nightly sweep —
the `2 x` this note's disk formula still reserves for. What closes it is an **exclusion**: one
checkpoint build folds at a time in a JVM, so the configured ceiling is the whole of what the
checkpoint path may hold. The reservation covers the build, not just the fold loop, because the
folded state is what the snapshot writer iterates — the heap is held until the last table is
uploaded.

A build that finds the budget taken **waits**, up to `DELTA_CHECKPOINT_FOLD_WAIT_SECONDS`
(`delta.checkpoint.fold-wait-seconds`, default **600**), and is otherwise **deferred**: nothing
folded, nothing uploaded, no row saved, no materialize attempt spent, `delta.checkpoint.builds.deferred`
incremented and a WARN naming the site. The nightly sweep skips that site's retention too — the
pointer did not move, so there is nothing new to prune — and carries on to the next; a forced
rebuild releases its `rebuild_requested` flag, records the verdict `DEFERRED` (#186) and says to
ask again. Deferral is deliberately **not** a fifth value on `delta.checkpoint.builds.aborted`: every
value there is a refusal that never repairs itself, and this one clears the moment the neighbouring
build finishes. `delta.checkpoint.builds.deferred` counts only the deferrals that **spent a wait** —
the sweep's non-blocking probes after the first one, and a wait cut short by a shutdown, are not
contention and would otherwise put hundreds of increments on the meter for a single collision.

Four properties of the wait, in the order they are likely to matter:

- **The nightly tick pays it once per pass, and comes back once.** After one spent wait the sweep
  keeps visiting sites but takes the budget only when it is free, and the moment a site does get it
  — whether its build then succeeds or fails — the ordinary behaviour resumes for everything after
  it; sites deferred along the way are retried in **one** further pass at the end of the tick. Paid
  per site the wait would multiply: a build that never finished would turn a 200-site tick into
  `200 x fold-wait-seconds`, over which the scheduler's own lock skips the following nights and
  retention freezes for *every* site rather than the contended one.
  **What the rule costs, and it is a real trade:** it bounds the tick's duration, not the
  collision's reach. A holder that outlasts both waits defers every site, so nothing is built or
  pruned that night. Waiting per site instead would build those sites — the tick would simply finish
  later — but only while the holder eventually releases; a genuinely stuck build would park a
  scheduler thread and hold the build lock for `N x` the wait, across the following nights,
  achieving nothing either way. So the loss is bounded, visible and gone by the next tick, and the
  deployment-level answer is the key: a site whose single build runs longer than
  `fold-wait-seconds` needs that key raised, which the meter below measures directly.
- **Contention is visible before the first deferral.** `delta.checkpoint.fold.wait` (a timer,
  recorded for every build that reached the fold, deferrals included) is the band below
  `fold-wait-seconds` — the budget is taken outside `phase=total`, so a build that waited nine
  minutes and then ran looks exactly like one that waited none on `delta.checkpoint.duration`, and
  `builds.deferred` stays at zero because it did run.
  `max(delta_checkpoint_fold_wait_seconds_max)` against the configured wait is the query.
- **It ends when the context starts closing.** `deltaRebuildExecutor` waits for its tasks on
  shutdown and never interrupts them, so an unaware wait would hold a rollout for the executor's
  whole termination period and then time out. A shutdown ends the wait as a deferral — but *that*
  one is not contention: it is not counted on `delta.checkpoint.builds.deferred` (a rollout must not
  move an alerting series), and the forced rebuild keeps its `rebuild_requested` flag for it (issue
  #162's case) instead of releasing it.
- **It is bounded on purpose**, and clamped at a day however large the key is set. Waiting for ever
  behind a build that has stalled would freeze the sweep silently until the pod is replaced.

One consequence worth stating rather than hiding: the budget is taken **before** the site's state is
read, so even a visit that turns out to have nothing to do holds it for one query and one S3
presence check, and can in principle be deferred. The order is deliberate — reading first would make
the fold as stale as the wait is long, and a rebuild parked behind the nightly sweep would fold a
segment list that retention had already deleted from S3 behind the advanced pointer. The semaphore
is fair and taken per site, so a rebuild queues behind **one** site's build rather than behind the
sweep — if `delta.checkpoint.builds.deferred` moves at all on this deployment, single-site builds
are running longer than the wait and that key is what to raise.

The exclusion is per JVM, which is the right scope for heap: a deployment running the sweep on
several replicas has that many independent budgets, exactly as it has that many heaps.

**And the fold is still a fold.** Nothing here makes a site of any size buildable — off-heap or
spillable folding, named as a later ticket since #112 and #126, remains the open question. This
ceiling is the honest interim: it turns an unattributable pod death into a named, counted refusal,
and gives the number that says which sites are near it.

**Sizing note — the scratch budget is declared by the deployment, not by the application.**
`DELTA_CHECKPOINT_MAX_TEMP_BYTES`, `DELTA_CHECKPOINT_MAX_FRAME_TEMP_BYTES` and
`DELTA_BATCH_PARQUET_MAX_TEMP_BYTES` (all 10 GiB by default)
are enforced **per file**, inside each writer. Since issue #150 one more key,
`DELTA_PARQUET_MAX_SCRATCH_BYTES`, bounds the *directory* those files share — see "One key bounds
the directory" below for what it does and why the three above are still there. On GKE the volume
under it is the manifest (issue #131): the backend container mounts a
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

**One key bounds the directory (issue #150).** `DELTA_PARQUET_MAX_SCRATCH_BYTES` is the only guard
that is about the sum rather than about a file, and the deployed value is the volume less the
reserve:

```
DELTA_PARQUET_MAX_SCRATCH_BYTES  <=  sizeLimit - 1Gi
5Gi                              <=  6Gi       - 1Gi
```

The reserved gigabyte is not decoration: scratch a dead container left behind survives on the
pod-private volume until the next sweep tick (#141) and its owner is gone, so no reservation covers
it; and kubelet acts on usage *exceeding* the limit rather than reaching it — solving to exact
equality would make the budget itself an eviction.

It is **charged as bytes are written**, by the same two counting streams that already enforce the
per-file ceilings, and released when the file is deleted. It is not reserved at the ceiling up
front: the ceilings are 1 GiB against artifacts in the low hundreds of MiB, so a three-table batch
would reserve 3 GiB it never uses and be refused for ever. What that costs is stated rather than
hidden — a running total cannot choose *which* writer to refuse, so two large writers can each take
half and both be stopped where either alone would have fitted. The heap twin of this budget (#178)
could avoid that by running one fold at a time; disk cannot, because a batch build genuinely needs
one open file per claimed table and serializing them means replaying the segments once per table,
the multiplier #038 removed.

**A refusal is transient, and every meter says so.** Crossing a per-file ceiling is a verdict on the
artifact — deterministic, identical on every retry. Crossing the directory budget says only that the
volume was busy, so:

- a **checkpoint table** ends the build, and is deliberately **not** a value on
  `delta.checkpoint.tables.unmaterialized`. A full directory is a *systemic* scratch failure — every
  remaining table would meet it too — which is the same answer an unusable scratch directory has had
  since #112, for the reason stated there: skipping would detach every last-good snapshot while the
  pointer advanced. Skipping just this table looks gentler and is not. The pointer would move to the
  new seq with the table's row still at the old one and nothing marking it as owing a rewrite (the
  nightly rematerialize keys on a **null** `s3_key_parquet`), so a site that then went quiet would
  serve a snapshot silently missing every change in between, indefinitely, with retention having
  already pruned the segments below the new pointer. Detaching instead would fix the retry and 404 a
  healthy artifact for a neighbour's disk use — and on a site's **first** build, where no row exists
  to detach, a refusal across every table would leave `checkpoints` empty with the pointer advanced,
  which the Bit BI files API reads as "not a Delta site yet" and answers with the historical
  uploaded CSVs as if they were the current baseline. Ending the build has none of that: no object,
  no row, no pointer, no attempt spent, and the whole seq redone on the next tick;
- the **frame** ends the build, as an oversized frame does, but is deliberately **absent** from
  `delta.checkpoint.builds.aborted`, whose tag values are refusals that never repair themselves.
  **Know what that costs before sizing the key** — and it is the same for the snapshots above, since
  both end the build. Since #153 the frame is written first and is the largest file a build
  produces, and `CheckpointScheduler` walks sites serially — so a directory held full for the length
  of the 02:00 sweep aborts *every* site's build at its first write, and retention is frozen
  fleet-wide for that night. Nothing reserves a share for the checkpoint path, so until #193 the
  mitigation is entirely "size the key with room to spare" — read
  `max_over_time(delta_parquet_scratch_bytes[7d])` before lowering it. The batch side degrades one artifact at a time;
  this side degrades by whole sites. Nothing is lost (the next night repeats the fold), the pre-#150
  behaviour on this deployment was a kubelet eviction of the pod, which is worse — but the asymmetry
  is real, `delta.parquet.scratch.refused{writer=checkpoint_frame}` is the series to alert on, and
  giving the checkpoint path a reserved share is **#193**;
- a **completed-batch artifact** takes the ordinary backoff instead of the first-attempt
  `ABANDONED` its own ceiling earns it, so the download answers `409` rather than a permanent `404`
  — **while attempts remain**. The type distinction buys back the first attempt, not the cap: a
  directory held full across the whole ~1 h backoff window still walks the artifact through its
  `DELTA_BATCH_PARQUET_MAX_ATTEMPTS` and ends in `ABANDONED`, needing the admin requeue like any
  other exhausted row. That is the pre-existing treatment of any transient failure rather than
  something this budget introduced, but it is the reason to size the key with headroom rather than
  to the exact worst case.

`delta.parquet.scratch.refused{writer=checkpoint_frame|checkpoint_table|batch_artifact}` counts
them, and `delta.parquet.scratch.bytes` gauges the live total — **including when the budget is
unset**, which is the shipped default and how the key is sized before it is turned on
(`max_over_time(delta_parquet_scratch_bytes[7d])` against the volume).

`ParquetScratchCeilingBudgetTest` recomputes the inequality from the manifests, so raising the
budget or shrinking the `sizeLimit` on its own fails the build rather than quietly restoring the
eviction. It also requires the **frame** ceiling to be the wider of the two per-file checkpoint
keys, because that is the one whose failure is expensive, and every per-file ceiling to sit at or
under the directory budget — a ceiling above it can never be reached, so it would be dead
configuration that reads as live.

**Per JVM.** The budget is a real bound only where the directory is pod-private
(`DELTA_PARQUET_SCRATCH_PRIVATE_TO_POD`, #141), which the deployed `emptyDir` is. On a shared
volume it would be a per-replica share, and the volume would need the replica count multiplied in.

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
  Bit BI / Parquet Export listing and stays gone. The nightly rematerialize retries it and fails
  identically, but no longer forever: since #149 the row spends one attempt per night and drops out
  of the retry after `DELTA_CHECKPOINT_MAX_MATERIALIZE_ATTEMPTS`, after which
  `delta.checkpoint.tables.given-up` is the only thing still saying so. Raise
  `DELTA_CHECKPOINT_MAX_TEMP_BYTES` and force a rebuild to re-arm the row.
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

**Recomputing the budget.** Since #150 there is nothing to recompute on the application side:

```
peak = DELTA_PARQUET_MAX_SCRATCH_BYTES + orphan_residue
orphan_residue = whatever a container restart left behind, until the next sweep tick
                    # outside every reservation, because the process that held them is gone
                    # bounded by DELTA_PARQUET_SCRATCH_ORPHAN_SWEEP_MS (1 h) on this
                    # deployment, not by the 4 h age window — see "Orphans outlive a
                    # container restart" below, and "One sweep interval means the tick
                    # runs when it is due" for the scheduler that bound assumes
```

**What the estimate used to be, and why it could not be one.** It read
`2 x max(table snapshot, reload frame) + max-concurrent x tables claimed per batch x artifact`. Both
multipliers were problems. The `2 x` was the two checkpoint build paths — the cron sweep and a
forced rebuild on `deltaRebuildExecutor` — which #178 has since made mutually exclusive, so it is
now conservative rather than tight. The batch term was the real one: a build opens one scratch file
per claimed table, and no per-file ceiling can bound a *count*, so the inequality only ever fixed
the single-claimed-table case. Lowering a ceiling shrank the peak roughly in proportion and never
bounded it. The directory budget replaces both, which is why the deployed per-file ceilings did not
have to move when it landed.

That budget is disk only. The heap peak of the same build is a separate sum — the fold
(`DELTA_CHECKPOINT_MAX_FOLD_BYTES`, and since issue #178 that is **one** fold per process, not one
per build) plus one Parquet row-group buffer per open writer (`DELTA_PARQUET_ROW_GROUP_BYTES`,
16 MiB) — and it is the one that fails as an eviction-like `OOMKilled` rather than as a skip. See
"The first bound is heap" above.

There is no distributed lock on the sweep, so "one site at a time" is per pod: each replica runs
its own — and so is the scratch budget, which is why the volume it is measured against must be
pod-private. #128 also raised how often large files are written, since a scheduled build now
rematerializes every table whose snapshot is missing and a forced rebuild rewrites all of them.

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
of `spring.task.scheduling.pool.size` (**7**, overridable with `SPRING_TASK_SCHEDULING_POOL_SIZE`)
— so the nightly checkpoint build, which can hold its thread for hours, leaves threads for the
scratch sweep, the batch timeout sweep and the monthly partition creation. Without the bean the
choice followed `spring.threads.virtual.enabled`: with it on, Spring Boot builds a
`SimpleAsyncTaskScheduler` that runs **every fixed-delay task on one internal thread** (the scratch
sweep among them) and ignores the pool-size key entirely; with it off, a pool of one. Two
consequences for an operator:

- **Setting `SPRING_TASK_SCHEDULING_POOL_SIZE` to 1 restores the bug.** The residue term above then
  has no bound anyone can quote, because a sweep tick can sit behind a whole checkpoint build.
- **Raise it, and keep it below `spring.datasource.hikari.maximum-pool-size` (10).** Eleven of the
  sixteen scheduled tasks open a connection; a pool as wide as Hikari's lets a burst of ticks take
  the connections request threads need. Raising it also moves the connection-pool derivation
  described next, because this pool is its largest single term.

The bean also fixes three shutdown settings in code, because a pool changes what a rollout does to a
running task: the threads are **daemon**, they are **not interrupted** on context close (as they were
when they were virtual threads), and the queue of not-yet-due ticks is **dropped**. Boot's default
would have called `shutdownNow()`, and an interrupted checkpoint build did not merely stop —
`CheckpointService` caught the exception per table and detached that table's snapshot key, so a
02:00 deployment would leave a table answering `404` until the nightly rematerialize. A doomed build
should die with the process instead. Since #149/#162 the per-table catch also recognises the closing
context and records nothing at all (see "When is a per-table failure the table's verdict?"), which
makes the two guards belt and braces rather than one load-bearing setting: the interrupt is still
not sent, and a failure that arrives anyway is no longer written down. Dropping the queue is what keeps that from costing anything: a
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

## The connection pool is smaller than the threads that can ask it for a connection

That is deliberate (issue **#161**), and worth knowing before reading a `connection-timeout` in the
logs as a bug. The audited background pools declare **35** threads between them — the scheduler (7),
`pluginExecutor` (10), `pluginExecutionExecutor` (8), `pluginAuditExecutor` (2), the three queue
workers (2 each), the forced-rebuild executor (1) and the batch-parquet lease renewer (1) — before a
single HTTP or gRPC request asks for one, and both request layers are unbounded
(virtual-thread-per-request, and grpc-java's default cached pool).
`spring.datasource.hikari.maximum-pool-size` is **10**.

Nothing deadlocks, because **background work does not hold one connection while waiting for a
second** — the shape that turns a shortage into a deadlock rather than a delay. A shortage therefore
costs a wait of up to `connection-timeout` and a retry on the next tick, which background work
survives. **No exception remains**: the two this section used to name are both closed — the
delta-SQL worker pinning a connection across the generation semaphore by **#164**, and the deferred
plugin audit listener by **#171** (see below). The pool is sized so the
consumers that *cannot* absorb a wait are covered outright — the ones that pin a connection
across S3 I/O instead of releasing it between statements:

```
5 scheduled ticks classified Cost.LONG   (not the scheduler's 7 threads — a short tick that
                                          waits simply runs again on its next wake)
+ 2 kept for request threads
= 7 <= 10
```

Two of those five — the checkpoint build and the S3 orphan sweep of **#158** — hold their *thread*
for minutes and never a connection across S3, so the floor is an over-estimate by two on purpose:
one per-task audit rather than two that could disagree.

The bound from the other side is the cluster's, and it is the one to check before raising anything:
the pool is per replica, `max_connections` is not, and a cron tick fires on **every replica at the
same instant**. `(maxReplicas 6 + maxSurge 1) x 10 = 70`, plus 10 for
`superuser_reserved_connections`, `psql`, migrations and exporters, against PostgreSQL's default of
100. **That 100 is an assumption** — this repository never names the database, `DB_URL` arrives from
a secret, and the budget further assumes this cluster is alone on that server, which is worth
checking if dev and stage share one. So the ten was **left where it was on purpose**: no load
measurement of `hikari_connections_pending` or `hikari_connections_acquire_seconds` exists, the
derivation shows ten already covers what has to be covered, and raising a pool against an unverified
`max_connections` fails as `FATAL: sorry, too many clients already` on every replica rather than as a
slow query. Both meters are exported by the Micrometer binding on `/actuator/prometheus` already, if
you want the measurement.

So, in order, to give the pool more room:

1. `SHOW max_connections` on the actual server, record it beside the key, and raise
   `DEFAULT_MAX_CONNECTIONS` in `BackgroundConnectionDemandTest` — the test fails until both move
   together, which is what stops the pool quietly outgrowing the database. At the default the
   ceiling already allows up to 12.
2. Or lower a remaining long holder — after **#164** the two queue workers are short (claim/mark
   only; S3 and the generation semaphore run with no transaction open), so the floor is the four
   long scheduled ticks plus the request reserve. Shortening or dropping a `Cost.LONG` tick is
   what moves it now.

`BackgroundConnectionDemandTest` discovers the inventory three ways — every `@Bean` returning an
`Executor`, every `max-concurrent` property, and every pool constructed directly in
`src/main/java` — so a new background pool fails the build rather than the connection pool.

One shape evades all three, and `AsyncExecutorQualifierTest` (**#195**) is what catches it: an
`@Async` written **without** naming an executor. It is not a `@Bean`, not a `max-concurrent` key and
not a `new ThreadPoolTaskExecutor(...)`, yet it is a pool — Boot's `applicationTaskExecutor` backs
off in the presence of the `Executor` beans this application declares, so the fallback is a
`SimpleAsyncTaskExecutor` that starts a **new thread per invocation with no ceiling**. Neither that
nor a qualifier naming a bean nothing declares (resolved on first invocation, thrown there) fails
the build or the startup on its own, so both are asserted: every `@Async` in `src/main/java` names
an executor, and that name is one of the beans above — **except a `TaskScheduler`**, which is
excluded deliberately: `spring.task.scheduling.pool.size` is derived over the `@Scheduled` inventory
alone (#146), so a blocking async method parked there would postpone the fixed-delay sweeps that
pool was sized for, without appearing in any inventory.

### The deferred plugin audit write has a lane of its own

Issue **#171**. A plugin audit entry that describes a *state change* is written by
`PluginAuditEventListener` after the publishing transaction resolves — and `afterCommit` runs
**before** Spring unbinds the publisher's `ConnectionHolder`, so anything that writes on that thread
still holds the connection of the transaction that just committed. The listener's own write is
`REQUIRES_NEW`, so it would ask for a second one. On `pluginExecutor` that happened whenever the
pool was full (10 threads, 50 queue slots, `CallerRunsPolicy`), and the cost was not only a stall:
an exception thrown from an `afterCommit` callback propagates to the caller of `commit()`, so a
connection timeout there surfaced as a failure of an operation that had already succeeded.

Those writes now go to **`pluginAuditExecutor`** — 2 threads and a 500-deep queue, with the default
`AbortPolicy`, the one place the three plugin pools differ. The listener hands the entry over with an
explicit `execute` rather than `@Async`, so a full executor is a `RejectedExecutionException` *it*
catches, with the entry still in hand: the ERROR line names the plugin, the account and the action,
which a rejection handler could not, because by then the task is an opaque `FutureTask`. A lane of
its own also stops one INSERT queueing behind plugin dispatch work that takes seconds. The listener
additionally refuses to write when a transaction is active on the thread the write ends up running
on, so the invariant is a property of the write rather than of the executor wired in front of it.

What an operator should know: **a full audit executor loses entries**, deliberately. Filling a
500-deep queue that two threads drain one INSERT at a time means the database is not accepting
writes, in which case the entry was not going to be written anyway; auditing must never become the
reason an operation fails or waits. That ERROR line is the only trace such an entry leaves, and it
quotes the rejection's own message so a full queue (a database problem) reads differently from an
executor shutting down (a pod stopping, and not a problem at all).

**A stopping pod drops them too, and that is the same decision.** This pool discards its queue on
shutdown — `shutdownNow`, 5 seconds for the two writes already in flight, daemon threads — where
the sibling pools wait up to 60 s for a queue a tenth the size. So an entry still queued when the
context closes is lost, and one being written has 5 s to finish. Waiting instead would not be the
safe choice it looks like: an orderly shutdown keeps executing the whole queue while
`awaitTermination` merely bounds how long the container blocks, and non-daemon threads would then
hold the JVM open past `terminationGracePeriodSeconds` (30) — a SIGKILL, in the middle of exactly
the slow-database episode that filled the queue.

`PluginAuditService`'s immediate (non-deferred) audit methods are unchanged: they are plain
`@Transactional`, still run on `pluginExecutor`, and do not have this shape. One new metric series
appears, nothing is renamed: Boot's `TaskExecutorMetricsAutoConfiguration` binds every
`ThreadPoolTaskExecutor`, so `/actuator/prometheus` gains `executor_*{name="pluginAuditExecutor"}`.

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
| `/api/v1/account/sites/{siteId}/delta/sync-state` · `/api/v1/sites/{siteId}/delta/sync-state` | GET | owner · admin | Watermark, checkpoint pointer, schema version, `rebaselineRequested`/`rebuildRequested` flags and `nextCheckpointBuildAt` (when the scheduled build next runs — #213); 404 until the client first connects |
| `.../delta/checkpoints` | GET | owner · admin | Per-table checkpoint rows with the `hasParquet` presence flag (`hasCsv` removed — #113) |
| `.../delta/checkpoints/{table}/download?format=parquet` | GET | owner · admin | Fresh presigned URL (15 min) per click. `format=csv` answers `410 Gone`: the snapshot is no longer produced |
| `.../delta/batches/{batchId}/tables/{table}/parquet` | GET | owner | Fresh presigned URL (15 min) for the exact unified completed-batch/table artifact. `409` while an attempt is queued, running or pending retry (`PENDING`/`BUILDING`/`FAILED`); `404` when absent or abandoned after `max-attempts` (for example, no renderable schema). Admin twin descoped 2026-07-08 — no admin batch-detail surface |
| `/api/v1/sites/{siteId}/delta/batches/{batchId}/parquet-artifacts` | GET | admin | Queue diagnostics for unified completed-batch artifacts: table, status, attempts, last error, and timestamps; storage and claim internals are omitted |
| `/api/v1/sites/{siteId}/delta/batches/{batchId}/parquet-artifacts/{artifactId}/requeue` | POST | admin | Audited recovery: reset `ABANDONED`, or `BUILDING` after its lease expires, to a fresh `PENDING` lifecycle; `409` for a live/unrecoverable state and `404` for a route mismatch |
| `/api/v1/sites/{siteId}/delta/segments?limit=20` | GET | admin | Recent changelog segments (seq range, records, mode, createdAt) |
| `/api/v1/sites/{siteId}/delta/checkpoints/rebuild` | POST | admin | Forced out-of-schedule checkpoint rebuild (sets `rebuild_requested`; released with a `lastRebuildOutcome` verdict when the attempt finishes — #186) |
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

**A site whose first checkpoint is not due yet (issue #213)**: `CheckpointScheduler.buildCheckpoints`
(`delta.checkpoint.cron`, `0 0 2 * * *` by default) is the only producer of checkpoints apart from an
operator-forced rebuild, so a site whose FULL_SNAPSHOT commits at 15:45 has none until 02:00. That is
the design, but the lag model could not say it: lag is `lastAppliedSeq − lastCheckpointSeq`, and
against a pointer of zero every record the site has ever applied counts as backlog — a freshly
ingested site read as **"Elevated — 1,155 records behind checkpoint"**, with the site-list pill amber
beside it.

`lastCheckpointSeq == 0` is now a **state of its own** on every sync surface. It is the canonical
"no checkpoint": the initial `site_sync_state` row carries it, a history wipe and a re-baseline reset
to it, and `CheckpointService` applies the same test before seeding a build from a frame. The Delta
Sync tab shows a neutral **"No checkpoint yet"** chip, keeps the number but labels it *records
awaiting the first checkpoint*, and **replaces** the lag track rather than recolouring it — the
track's bands and its 1k/10k ticks are a scale of "how far behind", and no position on it is true for
a site with nothing to be behind. In its place goes the moment the wait ends, from the new
`nextCheckpointBuildAt` on the projection — labelled *next* scheduled build, since it is recomputed
per request and stops coinciding with the first the moment that one passes: the next occurrence of
`delta.checkpoint.cron` (declared in `application.yml`, `DELTA_CHECKPOINT_CRON`), resolved
in the JVM's own zone (the zone `@Scheduled` uses), null when the schedule names none — the sweep can
be switched off with Spring's `-`, and promising a time the payload does not carry would be the same
class of lie. `CheckpointScheduleService` and the `@Scheduled` tick share one constant, so the answer
cannot drift from the tick that produces it.

Three limits are deliberate. **Stalled still wins**: a client that has not updated its sync state for
a day is both more actionable and independent of whether a checkpoint exists. **A site with nothing
applied is not in this state at all**: an all-zero row — what a wipe leaves, and what a re-baseline
requested for a client that never connected creates — is on neither of `CheckpointScheduler`'s work
lists (segments, unmaterialized `checkpoints` rows), so promising it a build would be a promise
nothing keeps, and there is nothing waiting either. And the state says *no checkpoint exists*, not
*the build is healthy*: it **cannot age itself out**, because nothing persisted says how long a site
has been waiting — `site_sync_state` has no creation timestamp, and every whole-site abort
(`frame_too_large`, `lossy_refold`, `history_gone`, a fold over `max-fold-bytes`, a deferral) leaves
no `checkpoints` row either, so a first build that has failed thirty nights carries byte-for-byte the
payload of a site ingested this afternoon. It is deliberately **not** bounded by lag magnitude: a
first FULL_SNAPSHOT is unbounded, so that bound would report the largest sites as critical on day one,
which is the defect this removes. What is done instead — both surfaces keep the count, and the card
names the build the state should not outlive ("Still missing a day later? The build is not
completing" — a day rather than "after that", because the sweep walks sites serially and a build
deferred behind the fold budget of #178 is a designed miss that repairs itself next tick) — with the
durable alarm staying where it belongs (`delta.checkpoint.builds.aborted`,
`delta.checkpoint.tables.given-up`, `delta.seq.lag`); separating the two payloads needs persisted
state and a migration, filed as **#224**. Building a checkpoint on the ingest path when a site's first
snapshot commits was the alternative and was **not** taken: it moves a whole-site fold onto the
commit that the nightly cron exists to keep off it, and it would have to queue behind the same fold
budget (#152/#178) and scratch budget (#150) — a cost this ticket has no reason to introduce, since
what was wrong was the reporting rather than the schedule.

**The Upload History File column is the same defect, not an egress delay (issue #214, folded in)**:
the **File** pill on a delta batch serves the unified completed-batch artifact of 036
(`GET .../delta/batches/{batchId}/tables/{table}/parquet`), which `BatchParquetFinalizationService`
enqueues on `BATCH_COMPLETED`. Since 029 a batch *is* a session, so a CONTINUOUS session holds its
batch `IN_PROGRESS` for hours and there is by design nothing to link to for its whole life, however
promptly the per-segment egress worker runs (it is woken by the commit itself — the sweep interval is
only a backstop). The column showed a bare em dash, which reads as "the file is missing". It now says
**"After session"** with the reason on hover, and only for a session still in progress: nothing
enqueues an artifact for a session that failed, so promising one there would be a promise nothing
keeps.

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
build that cannot materialize Parquet nulls the key outright. Changelog retention prunes segments
and no lifecycle rule covers this prefix, so before #118 a long-lived site left one orphan per table
per build behind the operation whose whole contract is "clean slate" — and until #158 gave the
prefix a daily sweep, this walk was the only thing that removed them at all. The
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
found the frame at N present, seeded the fold from the **discarded** baseline's frame and folded the
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

### A forced rebuild says what it did (issue #186)

`rebuild_requested` used to be the whole record of an operator's click: raised by the request,
released when the attempt settled. Three of the four settling endings ran **nothing at all** — the
build threw, S3 would not say whether the seed frame is there (#157), or another build held the
process's fold budget past `delta.checkpoint.fold-wait-seconds` (#178) — and from outside the pod
all four looked the same: the "Rebuild queued" chip vanished and the checkpoints did not change.
The only recourse was to notice that nothing had happened and click again, which is exactly what
the code's own log line said to do, in a log the operator cannot read.

Holding the flag instead is **not** the fix and has been rejected twice (#157 round 2, #178).
Nothing re-drives a held flag: the nightly tick calls `buildCheckpoint`, never `rebuildFromFrame`,
and `requestRebuild` short-circuits while the flag is set, so a held flag leaves the operator unable
even to ask again. So the flag keeps its semantics exactly, and the attempt now leaves a **verdict**
beside it — V54's `site_sync_state.last_rebuild_outcome` / `_outcome_at` / `_message`, published on
both sync-state projections as `lastRebuildOutcome`, `lastRebuildOutcomeAt` and
`lastRebuildMessage`.

| Ending | Verdict | Flag | What to do |
|---|---|---|---|
| The rebuild ran | `COMPLETED` (no message) | released | nothing |
| It threw | `FAILED` — the exception's type and text | released | fix what the message names, ask again |
| The rebuild queue would not take it | `FAILED` — the executor's own refusal, quoted | released | ask again |
| S3 would not say whether the frame is there (#157) | `FRAME_UNAVAILABLE` | released | restore the bucket policy or IAM grant (`delta.s3.read-denied`), then ask again |
| Another build held the fold budget (#178) | `DEFERRED` | released | ask again once the nightly build has finished, or raise `delta.checkpoint.fold-wait-seconds` |
| A wipe or re-baseline replaced the baseline under it (#136/#142) | `DISCARDED` | released | ask again if the rebuild is still wanted, against the new baseline |
| The site has no frame and no segments | `NOTHING_TO_REBUILD` | released | nothing to do: the site has no checkpoint history to rebuild from |
| The process is shutting down (#162) | **none written** | **kept** | nothing — the next process re-drives it at startup |

The two middle endings used to reach `DeltaCheckpointRebuildService` as an ordinary empty fold and
were reported as `COMPLETED` — a green "Rebuilt" chip over a rebuild that published nothing.
`CheckpointService` throws for both now (`BuildDiscardedException`, `NothingToRebuildException`),
which is the rule #157 and #162 established: a caller cannot tell an empty fold from a finished
build. `NothingToRebuildException` is thrown **only on the forced pass**, because for the nightly
tick that state is the routine quiet visit to a site named by an unmaterialized checkpoint row.

`CheckpointScheduler` logs the discard at INFO and **skips retention for that site in that tick**,
where the silent empty fold used to fall through to it. That is deliberate and matches the read
denial and the deferral beside it — the build moved no pointer, so there is nothing new to prune —
and today it changes nothing either way, since both triggers imply a wipe or a re-baseline that has
just zeroed `last_checkpoint_seq`. It is worth stating rather than leaving implicit: a future source
of `EpochChangedException` that left the pointer standing would skip a prune that had work to do,
until the next tick.

Four properties are worth keeping in mind when reading the pair:

- **Releasing the flag and writing the verdict are one write.** Releasing it without saying why is
  the state this removed, so the two cannot come apart.
- **The shutdown ending deliberately writes nothing.** It keeps `rebuild_requested`, so the request
  has not finished; a verdict there would contradict the flag that is still up, and the UI's
  "Rebuild queued" chip is the correct thing to show while `resumePendingRebuilds()` waits for the
  next process.
- **While the flag is up, the verdict describes the *previous* attempt.** The Delta Sync tab gives
  the queued chip precedence for that reason — showing both would read as "queued, and it failed"
  for a rebuild that has not run yet.
- **The verdict lives exactly as long as the checkpoints it describes.** A site history wipe drops
  it, and so does an ordinary re-baseline, which deletes every `checkpoints` row of the site
  (#142) — the verdict would describe nothing afterwards. It is deliberately *not* tied to
  `rebuild_requested`, which a re-baseline leaves standing: the flag says "a rebuild is owed", the
  verdict says "this is what the last one did".
- **A verdict superseded by a later checkpoint stops shouting.** Only a forced rebuild writes one,
  so a `FAILED` verdict would otherwise paint a critical chip for ever, outliving every nightly
  build that has since succeeded. Where `lastCheckpointAt` is later than `lastRebuildOutcomeAt` the
  UI keeps the label, the time and the message and drops the colour — a checkpoint built since is
  exact evidence that the condition cleared. It is a **one-way** signal: `lastCheckpointAt` moves
  only when a build advances the pointer, so an idle site whose nightly rematerialize quietly
  repaired everything keeps its loud chip. What clears a verdict is another rebuild — which is what
  the chip's own message asks for — and this only spares the operator that round trip when the
  answer is already in the payload.

The message is bounded at 1,000 characters by the writer — a value wider than the column would
throw at flush and lose the verdict entirely, which is where this ticket started. It is shown
clamped in the card, with the full string on hover. For `FAILED` it is the exception's own type and
text (the type included, because an S3 client error, a JDBC error or an interrupt frequently
carries no message at all). For `FRAME_UNAVAILABLE` and `DEFERRED` it keeps the exception's
diagnosis but **replaces its advice**: both of those messages are worded for
`CheckpointScheduler`, which really does revisit the site, and end by promising that the next tick
tries again — which on this path is false, since the nightly tick calls `buildCheckpoint` and never
`rebuildFromFrame`, so a forced rebuild is retried only when somebody asks again. `DEFERRED` has
two texts, and only a deferral that **spent its wait** names contention and prescribes raising
`delta.checkpoint.fold-wait-seconds` — the same split `delta.checkpoint.builds.deferred` makes,
since a non-blocking probe and an interrupted wait are not contention.

**`lastRebuildMessage` is on the admin projection only.** For a `FAILED` verdict it is the
exception's own text — a `PSQLException` naming a constraint or a column, an S3 error naming the
bucket and endpoint — and `GET /api/v1/account/sites/{siteId}/delta/sync-state` is the one place a
tenant user could read it. The account owner cannot request a rebuild in the first place (the route
is ROLE_ADMIN), so the owner projection carries `lastRebuildOutcome` and `lastRebuildOutcomeAt` and
leaves the message null — the same rule that keeps storage keys off the segment projection and
claim tokens off the artifact projection.

### No S3 inside a queue worker (issue #164)

The same invariant as the ingestion commit applies to the two queue workers and to the Parquet
Export listing (issue **#164**, folding **#176**). A HikariCP connection must not be held across a
network round trip, and the delta-SQL worker must not hold one across the generation semaphore
either (up to `plugin.sql-generation.semaphore-timeout-seconds`, 120 s).

`DeltaEgressService.egressNextPending` and `DeltaSqlQueueService.processNextPending` open **no**
transaction of their own. The pending-row claim (`FOR UPDATE SKIP LOCKED`) and the later
`egress_at` / `plugin_sql_at` write are the repository's short transactions; the S3 download,
Parquet render, per-table uploads, semaphore wait and SQL `PutObject` run with nothing open. A
crash between the two halves leaves the row pending and the sweep retries — the same keys are
overwritten. Two workers can now claim the same pending SQL segment; `uk_sql_gen_source_batch`
is the durable claim (the loser adopts the winner's row and deletes its own orphaned SQL object).
`generateSqlForBatch` acquires the semaphore *before* any transaction and throws if
one is already open, so the hold cannot return silently. `loadBatchData` and
`saveGenerationRecord` live on `SqlGenerationPersistence` so their `@Transactional` is a real
proxy boundary (they were `protected` self-invocations). `ParquetExportFileService.listFiles`
queries the catalog through `ParquetExportCatalogQuery` and only then probes S3; a row is still
dropped only on a known absence (issue **#157**) and dropped candidates still advance the cursor.

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
unreachable without the row. That is not new (the upload always preceded the watermark advance and
the batch completion, and nothing deleted it when those failed), and moving it ahead of the whole
transaction adds one more way to get there — a failure inside `reset` itself, i.e. the row-lock wait
behind a concurrent wipe. A client retries the whole session, so a repeatedly failing large snapshot
leaves one full-size copy per attempt. Those objects are reclaimed by the sweep below (**#158**); a
compensating delete in the caller is deliberately *not* how, because an exception can surface after
the transaction committed (an `AFTER_COMMIT` listener throwing) and the delete would then destroy a
live segment.

### Objects no row references are reclaimed (issue #158)

Every object under `delta/{siteId}/segments/` and `checkpoints/{siteId}/` is written **before**, or
independently of, the row that names it, and until #158 nothing reclaimed one that ended up with no
row. Both deleters collect keys *from* rows, the site history wipe walks these prefixes for the one
site being wiped, changelog retention prunes segments and never touches checkpoints,
`ParquetScratchOrphanSweeper` sweeps local scratch rather than S3, and there is no bucket lifecycle
rule for either prefix. Three populations accumulated:

- a **segment** whose ingestion transaction did not commit (above), one full-size copy per client
  retry;
- the **superseded generation** of every advancing checkpoint build — the `checkpoints` row is one
  per `(site, table)` and carries a single key, so the previous `seq`'s `snapshot.parquet` is
  unreferenced the moment the next build writes its own — plus any `_frame/seq={n}/frame.pb.gz`
  the pointer never adopted, which **no row ever names** (that one is reclaimed once the pointer has
  passed its sequence, see the guard table);
- **everything** belonging to a site deleted with `DELETE /api/v1/sites/{siteId}`, which hard-deletes
  the row and does not touch either prefix.

`DeltaS3OrphanSweeper` runs daily (`delta.s3-orphan.sweep-ms`, first pass 10 minutes after start)
and does the same four things for each prefix: list one site's objects, keep only the key shapes
this application writes, drop everything S3 reports younger than `delta.s3-orphan.min-age-seconds`,
then subtract the keys the rows still name and delete the remainder. The prefixes differ in one
place only — which rows answer that last question. For segments it is `changelog_segments.s3_key`;
for checkpoints it is the `checkpoints` keys **plus** the frame at
`site_sync_state.last_checkpoint_seq`, the one live artifact that exists only by implication.

The site list comes from the **bucket**, not the database, and that is not an optimization: a
hard-deleted site has no row to enumerate, and it is the population with the most to reclaim.

**One page at a time** (issue #199). Those four things happen per *page* of the listing, not per
prefix: `S3PrefixLister.forEachPage` hands the walk over one `ListObjectsV2` page at a time and the
sweep keeps nothing else but counters and a ten-key sample for the dry-run line, so its heap is
bounded by a page instead of by a site's history. It matters here more than at the walk's other
callers, because the first deleting pass is by construction the **largest listing this application
ever takes** — the premise of the whole sweep is that superseded generations accumulated nightly for
months with nothing reclaiming them — and it runs on a scheduler thread beside the checkpoint fold
budget (#152, #178) and the Parquet scratch budget (#150), neither of which knows about it.

The **row set** is deliberately not paged, and that asymmetry is the point: the listing is bounded by
nothing, while the rows are bounded by what still exists — retention prunes segments, and
`checkpoints` is one row per table. So it stays **one read per site**, made lazily on the first page
that produces a candidate, which is the same single query the sweep always made: no S3 call and no
database call is added or removed by #199. For a site whose prefix fits one page — the overwhelming
majority — "rows read after the listing" is therefore unchanged. Beyond the first page the ordering
guard is weaker and the **age window** is what carries it: a row can only appear for an object whose
write is still in flight, and the window is a day past the longest such gap. The checkpoint pointer
read with those rows is *older* than the pages that follow, and an older pointer protects strictly
more keys, so that half errs only towards keeping the object.

The **delete** is buffered rather than paged, which is a different question from the listing: a page
and a `DeleteObjects` chunk are both 1000 keys, so one round trip per page would turn the sparse
steady state — a handful of superseded objects every thousand keys — into one request per page on a
tick that walks every site prefix in the bucket. Orphans are judged per page and queued; a full
chunk goes out during the walk and the remainder when the site ends, so the peak is under two chunks
of keys.

The complete-listing callers are untouched: the site history wipe compares every key against one
instant and `requireCompleteKeys` (batch deletion, retention) needs the whole set or none, so both
keep `S3PrefixLister.listAll`.

**Which raises the question nothing in S3 can answer: does this bucket belong to this database?**
Every deleter before this one worked forward from a row, so a stranger's objects were never at risk;
this one reads "no rows for this site" as "dead". Two deployments sharing a bucket keep separate
databases and therefore separate site ids, so each would read the other's live prefixes as
unreferenced and delete their changelog and checkpoint seed. A site's own `sites` row is the
closest available proof — so a prefix whose site this database has never heard of is **left alone
and logged**, unless `delta.s3-orphan.reclaim-unknown-sites` says the bucket is exclusive to this
deployment. Default off. It proves *site-id knowledge*, not exclusivity: a database restored from
another environment's dump shares the ids, and then the guard passes on prefixes it should not, so
the precondition an operator has to check by hand is "no other deployment writes this bucket". Everything this ticket was opened for belongs to sites that still
exist, so the default reclaims it; only the hard-deleted-site case waits for that acknowledgement,
and turning it on where the bucket is shared is the one way this sweep can destroy live data.

Every guard fails towards keeping the object:

| Guard | What it stops | What it costs when it fires wrongly |
|---|---|---|
| **Age** — strictly older than `min-age-seconds`, default 24 h; a missing `LastModified` counts as new | Deleting an object whose row is not written yet: a segment mid-commit, and the frame between `uploadFrame(N)` and the `recordCheckpoint(N)` that adopts it | One interval of delay |
| **Shape** — only `{segmentId}.pb.gz`, `_frame/seq={n}/frame.pb.gz`, `{table}/seq={n}/snapshot.parquet\|.csv.gz` | Deleting an artifact kind added later that this sweep has never heard of | That kind accumulates, exactly as everything did before #158 |
| **Rows read after the first page that produced a candidate** — for a prefix of one page that is "after the listing"; beyond it the rows predate the later pages and the **age window** is the guarantee instead (a row appears only for an object whose write is still in flight, and the window is a day past the longest such gap) | Using a row set from before a row that was written during the walk | Nothing on the first page; beyond it, what the age window costs |
| **A row set that cannot be read skips the site**, every remaining page of it included | Reading "the query failed" as "no references" | One interval of delay |
| **A truncated walk sweeps only the pages it was handed** (what earlier pages already reclaimed stands — they were judged against the rows, not against the walk finishing) | Nothing invented | One interval of delay |
| **A prefix whose site has no `sites` row is left alone** unless `reclaim-unknown-sites` is set | Deleting another deployment's live objects out of a shared bucket | A hard-deleted site's objects stay until the bucket is declared exclusive |
| **A checkpoint key at or above `last_checkpoint_seq` is left alone** (a pointer of **zero** is "no checkpoint", not a sequence, so it protects nothing — otherwise a wiped site would be immune for as long as its restarted counters took to climb back) | The one race the age window does not cover: `seq`-addressed keys are *rewritable*, so a key that was weeks old at listing time can be uploaded and adopted before the delete lands | A frame a build never adopted waits until the pointer passes it, which a live site does nightly |
| **Dry run** (`delta.s3-orphan.dry-run`, default **true**) | Deleting anything at all before an operator has seen what one pass would take | Nothing is reclaimed until the flag is cleared |

**The age window is the key to get right.** Lower it below the longest possible checkpoint build and
a live frame can be deleted, at which point the site reads as `history_gone` and gives its
checkpoint rows up after `delta.checkpoint.max-materialize-attempts` nights (#149). Raise it if a
build on the largest site can take longer than a day; the only cost of raising it is storage.

**The first pass reports; it does not delete.** `delta.s3-orphan.dry-run` ships **true**, so an
upgrade logs one INFO per prefix — how many objects would be reclaimed and a sample of their keys —
and takes nothing. That is deliberate and it is the rollout step: the set this sweep would take on a
deployment that has been running for months cannot be inspected after the fact, and it includes the
last good `snapshot.parquet` of every table whose key has ever been detached. Read a pass, then set
`delta.s3-orphan.dry-run=false`. `delta.s3-orphan.enabled=false` turns the sweep off entirely; it is
on by default because an orphan is resolved by nothing else. A non-positive age window is refused at
startup, but only while the sweep is enabled: a rollback that still crash-loops the pod on the value
it is rolling back would not be one.

**Detaching a key is now a destructive act with a one-day fuse.** When a table cannot be
materialized on an advancing seq, `abandonStaleSnapshot` nulls `s3_key_parquet`, and the last good
object at the previous seq used to stay in the bucket — 404 for consumers, but still there for an
operator willing to re-attach it by hand. From this sweep on it is unreferenced by construction and
goes a day later, so a row retired by `delta.checkpoint.max-materialize-attempts` (#149) loses that
snapshot for good rather than merely making it unreachable. The same is true of a row reaped by
`reapTablesAbsentFromTheFold`. That is the intended reading of "no row ⇒ dead" — nothing in the
application ever re-attaches an old key, a repair writes a new object — but it is worth knowing
before the first sweep on a fleet that has been detaching keys for months.

**One site's listing is held in memory at a time**, and the first pass after this ships is the
largest it will ever be — the premise of the ticket is that these prefixes have been accumulating
for months. The peak is one site's object count (a key plus an instant each), not the bucket's: a
site with fifty tables and two years of nightly builds is in the tens of thousands of entries, a few
megabytes, and it is the same listing a site history wipe already materializes for one site. Reading
the population before it is deleted is what `dry-run` and `delta.s3-orphan.candidates` are for.
Streaming the walk page by page instead of materializing it is **#199**.

**The sweep is not serialized across replicas, on purpose.** Both obvious locks are the wrong shape
here: a transaction-scoped `pg_advisory_xact_lock` would hold a connection for the whole walk, and a
session-level lock the same, which is exactly the hold #164 removed from the queue workers. Deletes
are idempotent, so an overlap costs a duplicated `ListObjectsV2` walk and a duplicated count — the
loser's `DeleteObjects` succeeds on an already-deleted key. **Read the counters per replica**: a
`prefix=segments` rate of two a day across two replicas is one orphan, not two. In practice the
passes rarely overlap (each replica's first tick is 10 minutes after its own start), and where they
do the second finds the objects already gone.

Read the three meters accordingly. **`delta.s3-orphan.candidates` is the one to alert on**, but read
it as a **census, not an arrival rate**, whenever the sweep is not deleting: nothing removes the
backlog between passes, so while `dry-run` is on (or `reclaim-unknown-sites` is holding a population
back, or deletes keep failing) the same N objects are re-counted every day and a rate alert would
read a static backlog as "N new orphans a day". It counts what the sweep found unreferenced whether
or not it was allowed to delete it, so it is the only one that moves while `dry-run` is on — which is the shipped default, and an alert written on a
flat `reclaimed` would page on a deployment that is simply not deleting yet. Under `prefix=segments`
a steady rate means ingestion commits are failing after their upload, and is worth chasing; under
`prefix=checkpoints` it is the ordinary superseded generation of every advancing build, so a
**zero** rate on a busy fleet is the surprising reading. Once `dry-run` is off,
`candidates - reclaimed - delete-failed` is zero by construction.
`delta.s3-orphan.delete-failed` counts unreferenced objects the bucket refused, as
`candidates - deleted` rather than as the number of error entries the SDK returned (one entry covers
a whole failed 1000-key chunk, which would report a bucket-wide denial as a trickle). Nothing is
lost — the next sweep sees the same keys — so a sustained rate is a permissions or availability
problem.

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
`delta.checkpoint.builds.aborted{reason=frame_too_large|lossy_refold|history_gone|fold_too_large}`,
`delta.checkpoint.builds.deferred`, `delta.checkpoint.fold.wait`,
`delta.checkpoint.fold.bytes`, `delta.checkpoint.tables.given-up`, `delta.s3.read-denied`,
`delta.parquet.scratch.bytes`, `delta.parquet.scratch.refused{writer=...}`,
`delta.s3-orphan.candidates{prefix=segments|checkpoints}`,
`delta.s3-orphan.reclaimed{prefix=segments|checkpoints}`,
`delta.s3-orphan.delete-failed{prefix=segments|checkpoints}`,
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
| `delta.checkpoint.fold.bytes` (summary) | `delta_checkpoint_fold_bytes_count` / `_sum` / `_max` |
| `delta.checkpoint.fold.wait` (timer) | `delta_checkpoint_fold_wait_seconds_count` / `_sum` / `_max` |
| `delta.checkpoint.duration{phase=...}` (timer) | `delta_checkpoint_duration_seconds_count` / `_sum` / `_max` |
| `delta.checkpoint.tables.unmaterialized{reason=no_schema\|parquet_failed}` | `delta_checkpoint_tables_unmaterialized_total{reason=...}` |
| `delta.checkpoint.builds.aborted{reason=frame_too_large\|lossy_refold\|history_gone\|fold_too_large}` | `delta_checkpoint_builds_aborted_total{reason=...}` |
| `delta.checkpoint.builds.deferred` | `delta_checkpoint_builds_deferred_total` |
| `delta.checkpoint.tables.given-up` | `delta_checkpoint_tables_given_up` |
| `delta.s3.read-denied` | `delta_s3_read_denied_total` |
| `delta.s3-orphan.candidates{prefix=segments\|checkpoints}` | `delta_s3_orphan_candidates_total{prefix=...}` |
| `delta.s3-orphan.reclaimed{prefix=segments\|checkpoints}` | `delta_s3_orphan_reclaimed_total{prefix=...}` |
| `delta.s3-orphan.delete-failed{prefix=segments\|checkpoints}` | `delta_s3_orphan_delete_failed_total{prefix=...}` |
| `delta.parquet.scratch.bytes` | `delta_parquet_scratch_bytes` |
| `delta.parquet.scratch.refused{writer=...}` | `delta_parquet_scratch_refused_total{writer=...}` |

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
On **checkpoint**, `download_frame` is only the seed frame — and only the time spent reading its
bytes off the network, since issue #152 streamed it: the transfer is now interleaved with the fold
rather than finished before it, so the two are separated by a counting stream rather than by
sequence. Segment GetObject/parse is inside `fold`, as is the seed frame's own fold, which before
#152 fell between the two phases and was timed by neither. `decimal_scan` / `write` / `parquet` are encode work; `upload` is
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
