# Delta Client v2 Integration Guide

**Document Version**: 1.0.0
**Last Updated**: 2026-06-06
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

---

## Overview

Delta Client v2 turns the middleware into a **stateful changelog service**. The client streams an
append-only sequence of typed change records (INSERT/UPDATE/DELETE) over a single gRPC bidirectional
stream; the server stores them as an immutable **changelog** (the source of truth) and periodically
materializes **checkpoints** (full snapshots) from it.

A client integration is three RPCs:

1. **`GetSyncState`** — ask the server where it is (its applied watermark) before opening a session.
2. **`SubmitSchema`** — declare table structure (columns, primary keys). Required before the first session and re-sent only when the source structure changes.
3. **`StreamChanges`** — the session: open it, push change records in `seq` order, end it. One session = one **batch** = one changelog **segment**.

> **A "full snapshot" is just a changelog frame whose records are all `INSERT`.** There is no separate
> snapshot message — bootstrap and re-baseline use the same `StreamChanges` stream with `mode = FULL_SNAPSHOT`.

### Key concepts

| Term | Meaning |
|---|---|
| **`seq`** | A per-site, strictly-increasing sequence number stamped on every change record. The server's durable high-water mark is `last_applied_seq`. |
| **Session** | One `StreamChanges` stream: `SessionStart` → `ChangeRecord*` → `SessionEnd`. Maps onto one Batch. |
| **Segment** | The changelog slice written when a session commits, addressed by an S3 key. |
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

| `ErrorCode` | Meaning | What to do |
|---|---|---|
| `SEQUENCE_GAP` | `first_seq != server_last_seq + 1` | Open a `FULL_SNAPSHOT` (action = `NEED_REBASELINE`). |
| `RECONCILIATION_FAILED` | `SessionEnd` counts ≠ accepted records | Fix your counts; re-baseline. Nothing was committed. |
| `ACTIVE_SESSION_EXISTS` | another session is live for this site | Serialize sessions; retry after the other ends/times out. |
| `SCHEMA_MISMATCH` | `schema_version` unknown/stale | `SubmitSchema`, then retry. |
| `UNAUTHORIZED` | token/site problem | Re-authenticate. |
| `INTERNAL` | server error | Retry with backoff. |

gRPC transport-level `UNAUTHENTICATED` (closed call, no `ServerError`) means the `authorization` metadata was
missing/invalid before the handler ran.

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

- **Bit BI** keeps using `GET /api/v1/plugins/bit-bi/sites/{siteId}/files`; the CSV it downloads is the
  **reconstructed checkpoint** (latest `snapshot.csv.gz` per table), unchanged for Bit BI.
- **Analytics consumers** read a **sequential delta Parquet stream** per table:
  `egress/{siteId}/{table}/delta/seq={first}-{last}.parquet` (zero-padded sequences — listing order is
  apply order). Each file is one committed session segment: typed columns from your submitted schema
  (all nullable) plus service columns `_op` (`INSERT`/`UPDATE`/`DELETE`), `_seq`, and `_changed`.
  `DELETE` rows carry the key columns. **`_changed`** disambiguates a null cell in an `UPDATE`: it is
  a comma-separated list of the columns the `UPDATE` actually carried, so for an `UPDATE` a null cell
  in a **listed** column means *set to SQL NULL* and a null cell in an **unlisted** column means
  *unchanged* (keep the prior value); it is null for `INSERT` (full row) and `DELETE` (key only).
  Apply the files sequentially by seq; a `FULL_SNAPSHOT` session produces an all-`INSERT` file — a
  full table — so bootstrap and re-baseline need no special handling. Only tables with a submitted
  schema are materialized.
- **Full per-table Parquet load**: each checkpoint build also writes the complete typed snapshot
  `checkpoints/{siteId}/{table}/seq={seq}/snapshot.parquet` (tables with a submitted schema only).
  Consumers that prefer a full load over replaying the delta stream download it from the Delta Sync
  UI (presigned URL, 15 min). Parquet is the target format; the checkpoint CSV is the legacy Bit BI
  path and will go away once Bit BI migrates.

Delta files appear **within seconds of `SessionCommitted`** — a session commit wakes a bounded egress
worker pool (no cron involved). The Bit BI checkpoint CSV is still built by the async scheduler.

## Upload History (dashboard) shows per-table stats, not files

A Delta v2 session writes no `uploaded_files` — the `Batch` row it produces always has
`uploadedFilesCount=0`. The dashboard's Upload History surfaces the real per-run signal instead:
`GET /api/v1/history/batches/{batchId}` returns `deltaStats: [{table, inserts, updates, deletes}]`
(computed once at commit from the accepted records and persisted on the segment), and the list
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
| `.../delta/checkpoints` | GET | owner · admin | Per-table checkpoint rows with `hasCsv`/`hasParquet` presence flags |
| `.../delta/checkpoints/{table}/download?format=csv\|parquet` | GET | owner · admin | Fresh presigned URL (15 min) per click |
| `.../delta/batches/{batchId}/tables/{table}/parquet` | GET | owner | Fresh presigned URL (15 min) for one table's **delta** Parquet of a session's segment (feature 025); 404 when the table has no declared schema / not yet egressed. Admin twin descoped 2026-07-08 — no admin batch-detail surface |
| `/api/v1/sites/{siteId}/delta/segments?limit=20` | GET | admin | Recent changelog segments (seq range, records, mode, createdAt) |
| `/api/v1/sites/{siteId}/delta/checkpoints/rebuild` | POST | admin | Forced out-of-schedule checkpoint rebuild (sets `rebuild_requested`, cleared on completion) |
| `.../delta/rebaseline` | POST | owner · admin | Sets persistent `rebaseline_requested` (V35) → `GetSyncState` answers `NEED_REBASELINE` on next connect; cleared when the FULL_SNAPSHOT session starts |
| `.../delta/rebaseline` | DELETE | owner · admin | Takes a pending request back (issue #84): clears `rebaseline_requested` only — watermark, checkpoints and segments untouched → `GetSyncState` answers `PROCEED` again. Idempotent: `200 {"status": "cancelled"}` when a request was pending, `200 {"status": "not-requested"}` otherwise |
| `/api/v1/account/sites/delta/health` · `/api/v1/accounts/{accountId}/sites/delta/health` | GET | owner · admin | Bulk health inputs for all V2 sites of an account (site-list badge, one query per poll) |

All endpoints are documented in the OpenAPI spec (`/v3/api-docs`, Swagger UI).

**Client-visible effect**: the "Request full re-baseline" UI action is now the public trigger for
the `NEED_REBASELINE` recovery path described above — previously the flag had no public writer.

**Cancelling a re-baseline (issue #84)**: the request is no longer a one-way switch. While
`rebaselineRequested` is set, the Delta Sync widget shows a **Cancel request** button next to the
"Full snapshot scheduled on next connect" pill; confirming it issues the `DELETE` above and the
site returns to ordinary delta from its existing watermark — nothing else in `site_sync_state` moves,
and no checkpoint or segment is touched. This only helps *before* the client starts its
FULL_SNAPSHOT session; once the session starts, `resetForRebaseline` has already dropped the old
baseline and the snapshot must run to completion. The request dialog now spells out that cost
(the whole dataset is re-uploaded) and that the request can be taken back until the client starts.

**Delta Parquet in the UI (feature 025)**: the delta Batch Detail's "Table changes" card carries a
per-table **Parquet** pill for completed sessions — one click presigns and opens the segment's
egressed delta file (`egress/{siteId}/{table}/delta/seq={first}-{last}.parquet`). Tables without a
declared schema have no file (the egress worker skips them).

**Download error toasts (review rounds 2–3)**: a failed pill click shows exactly one toast. The
server's `ErrorResponseDto.message` wins when present (e.g. the 503 "Object storage is temporarily
unavailable. Please try again." on an S3 outage, or a specific 404 reason); a bodyless 404 (file
genuinely absent) falls back to the pill's own hint ("no declared schema / not egressed yet" for
delta Parquet, "may not have been built yet" for checkpoints); anything else gets a generic retry
toast. The global interceptor toast is suppressed on presign requests and on the 20-second
sync-state poll (404 is the normal state for a never-connected site).

**Forced rebuild semantics (review r3)**: `POST .../checkpoints/rebuild` is idempotent — a second
request while one is pending answers `202 {"status": "already-queued"}` and queues nothing. A full
rebuild queue answers 503 (flag cleared); rebuild flags orphaned by a restart are re-driven on
startup, so the "Rebuild queued" chip can no longer stick forever.

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
| `ACTIVE_SESSION_EXISTS` | another session live for this site (or a prior dropped batch not yet timed out) | serialize sessions; for a resumable DELTA drop, reconnect to get `RESUME_FROM` |
| `UPDATE` rejected | the table is keyless (empty `primary_key`) | emit `DELETE` + `INSERT` instead |
| Wrong Parquet types / parse errors in egress | wire value doesn't match declared type | send decimals/dates/timestamps as **strings**; keep `SubmitSchema` in sync with the data |
| Server seems to lose the unsealed tail (continuous) | stream dropped mid-segment | reconnect and continue from the last `committed_seq`; only sealed segments are durable |
