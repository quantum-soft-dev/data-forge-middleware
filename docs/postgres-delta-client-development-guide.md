# PostgreSQL Delta Client — Development Guide

**Document Version**: 1.0.0
**Last Updated**: 2026-08-03
**Audience**: Developers planning and building a new PostgreSQL client that streams database
changes into Data Forge Middleware
**Status**: consolidated planning reference. The normative sources it consolidates are listed in
[References](#14-references); on any disagreement the proto file and the server code win.

This document is self-contained: everything a new client needs — authorization, the gRPC wire
protocol, value typing, session lifecycle, recovery, limits, and the Postgres-side capture
considerations — is described here, with pointers to the deeper per-topic guides.

> **What replaced what.** The REST/JSONL `/api/dfc/**` ingestion API (CDC v1) is retired and not
> callable — do not read `postgres-cdc-client-guide.md` as a protocol reference; it is archived.
> All ingestion goes through the **Delta Client v2 gRPC** service. REST survives only for
> onboarding/auth (`/api/v1/device/**`) and error reporting.

## Table of Contents

1. [Architecture: what the client is responsible for](#1-architecture-what-the-client-is-responsible-for)
2. [Environments & endpoints](#2-environments--endpoints)
3. [Onboarding & authentication (Device Authorization Flow)](#3-onboarding--authentication-device-authorization-flow)
4. [The gRPC contract](#4-the-grpc-contract)
5. [Schema submission & type mapping](#5-schema-submission--type-mapping)
6. [Streaming sessions](#6-streaming-sessions)
7. [Change records & value typing](#7-change-records--value-typing)
8. [Recovery model](#8-recovery-model)
9. [The generation epoch (site history wipe)](#9-the-generation-epoch-site-history-wipe)
10. [Error codes — complete matrix](#10-error-codes--complete-matrix)
11. [Capturing changes from PostgreSQL](#11-capturing-changes-from-postgresql)
12. [Client-side persistent state](#12-client-side-persistent-state)
13. [Auxiliary REST API](#13-auxiliary-rest-api)
14. [References](#14-references)
15. [Implementation checklist](#15-implementation-checklist)

---

## 1. Architecture: what the client is responsible for

The middleware is a **stateful changelog service**. The client streams an append-only sequence of
typed change records (INSERT/UPDATE/DELETE) over one gRPC bidirectional stream; the server stores
them as an immutable changelog and materializes checkpoints. The whole integration is three RPCs:

| RPC | Purpose |
|---|---|
| `GetSyncState` | Ask the server for its durable watermark + recovery action before opening a session |
| `SubmitSchema` | Declare table structure (columns, PK, unique keys). Before the first session; re-send only on structure change |
| `StreamChanges` | The session: `SessionStart` → `ChangeRecord*` → `SessionEnd`. One session = one batch |

The client owns:

- **Capture** — extracting changes from the source PostgreSQL (see [§11](#11-capturing-changes-from-postgresql)).
- **Sequencing** — stamping every record with a per-site, strictly increasing `seq` (`uint64`,
  starts at 1) and persisting the counter across restarts.
- **Buffering until commit** — records may be discarded only after `SessionCommitted`, never on
  `Ack` alone.
- **Token lifecycle** — device-flow onboarding once, then refresh-token rotation forever.
- **Recovery** — obeying `RecoveryAction` and the generation epoch.

There is no server-side notion of "transaction" on the wire: the changelog is a flat ordered
stream. Bootstrap and re-baseline are not special message types — they are a session whose records
are all INSERTs (`mode = FULL_SNAPSHOT`).

### Key terms

| Term | Meaning |
|---|---|
| `seq` | Per-site strictly-increasing sequence number on every change record |
| Watermark | `last_applied_seq` — highest `seq` the server has durably committed for the site |
| Session | One `StreamChanges` stream; maps onto one Batch (visible in Upload History) |
| Segment | The changelog slice written when a session (or a continuous seal) commits |
| Checkpoint | Server-materialized full snapshot at a seq; the client never writes it |
| Generation | Epoch of the site's server history; bumped only by an operator wipe |

---

## 2. Environments & endpoints

| Environment | HTTP base URL | gRPC endpoint |
|---|---|---|
| Dev (GKE) | `https://dev.dfm.bitbi.io` | same host, port 443 |
| Test (GKE) | `https://test.dfm.bitbi.io` | `test.dfm.bitbi.io:443` (confirmed live) |
| Production | TBD | TBD |

- gRPC is published on the **same host and port as the HTTP API** — TLS on 443, no separate gRPC
  host. Routing is by gRPC path prefix at the load balancer.
- Transport is **HTTP/2 + TLS end-to-end**. Any proxy between the client and the server must pass
  HTTP/2 through (gRPC dies on an HTTP/1.1 hop).
- The server enforces a max connection age (`delta.grpc.max-connection-age-seconds`); a long-lived
  stream being closed with GOAWAY is **routine**, not an error — reconnect and resume
  (see [§6.4 Continuous mode](#64-continuous-mode)).

---

## 3. Onboarding & authentication (Device Authorization Flow)

Full detail: [`device-flow-client-guide.md`](./device-flow-client-guide.md). Summary of the
contract:

### 3.1 One-time onboarding (RFC 8628)

```
POST /api/v1/device/authorize        (public, no auth)
{ "siteName": "shop-42", "siteDescription": "optional", "siteType": "POSTGRES_CDC" }
  → { deviceCode, userCode, verificationUri, verificationUriComplete, expiresIn: 900, interval: 5 }

  (show userCode / QR to the operator; they approve in the browser)

POST /api/v1/device/token            (poll every `interval` seconds)
{ "deviceCode": "..." }
  → 400 { "error": "authorization_pending" }   keep polling
  → 200 { siteId, siteName, accessToken, refreshToken,
          accessTokenExpiresAt, refreshTokenExpiresAt, apiBaseUrl }
```

**Set `siteType: "POSTGRES_CDC"` for a Postgres client.** It is immutable per site and defaults to
`DBF` when omitted. A CDC site requires a submitted schema before its first session
(`SCHEMA_REQUIRED` otherwise).

Terminal poll errors: `access_denied` (user refused), `expired_token` (15-minute window passed —
restart from `/authorize`), `invalid_grant` (wrong code), `slow_down` (increase interval).

### 3.2 Token lifecycle

- **Access token**: JWT, ~1 hour. Carried on every HTTP call as `Authorization: Bearer <token>`
  and on every gRPC call as metadata `authorization: Bearer <token>`.
- **Refresh token**: opaque, 43 chars (`^[A-Za-z0-9_-]{43}$`), ~90 days.
  `POST /api/v1/device/auth/refresh { "refreshToken": "..." }` → new access token **and possibly a
  rotated refresh token — always store the returned value**. 400/401 on refresh means the token is
  expired or revoked: re-run the device flow.
- The token **is** the site identity: the server derives `site_id` and `account_id` from it. The
  client never chooses which site it acts on.
- Persist `refreshToken` + `refreshTokenExpiresAt` (+ `siteId`, `apiBaseUrl`) in secure local
  config; do **not** persist access tokens — obtain a fresh one via refresh at startup.
- For a long-lived gRPC stream, ensure the access token is valid for the stream's lifetime or use
  shorter sessions; gRPC auth is checked **when the stream opens**.

Missing/expired token on gRPC ⇒ transport-level `UNAUTHENTICATED` (the call closes, no in-band
error). That is also the usual symptom of a **deactivated or deleted site**, because the site check
runs at stream open.

---

## 4. The gRPC contract

Canonical contract: [`src/main/proto/delta-ingestion.proto`](../src/main/proto/delta-ingestion.proto)
— vendor it verbatim into the client repo, pinned to a server merge SHA, and (recommended, as the
dbf-data-extractor client does) compare normalized descriptors in CI. Proto package
`com.bitbi.dfm.delta.v2`:

```protobuf
service DeltaIngestion {
  rpc GetSyncState  (SyncStateRequest)   returns (SyncStateResponse);
  rpc SubmitSchema  (SchemaRequest)      returns (SchemaResponse);
  rpc StreamChanges (stream ClientEvent) returns (stream ServerEvent);
}
```

### 4.1 `GetSyncState`

```protobuf
message SyncStateRequest  { string site_id = 1; }   // also derived from the token; sent for explicitness
message SyncStateResponse {
  uint64 last_applied_seq    = 1;  // durable server watermark
  int32  schema_version      = 2;  // schema version the server holds (0 = none)
  uint64 last_checkpoint_seq = 3;
  RecoveryAction action      = 4;  // PROCEED | NEED_REBASELINE
  optional uint64 generation = 5;  // epoch; ABSENT = server predates epochs (see §9)
}
```

Call it on **every connect**. Rules:

- Next DELTA session opens at `first_seq = last_applied_seq + 1`.
- `last_applied_seq == 0` ⇒ the server holds no accepted baseline — bootstrap with `FULL_SNAPSHOT`.
  (Reliable: the watermark advances in the same transaction that publishes a segment; committed
  data behind a zero watermark cannot exist.)
- `action == NEED_REBASELINE` ⇒ a full snapshot is required (operator pressed "Full re-baseline",
  or the site was wiped, or the changelog was pruned).
- `generation` differs from the stored one ⇒ **wipe protocol**, [§9](#9-the-generation-epoch-site-history-wipe).
- Server `schema_version` behind yours ⇒ `SubmitSchema` first.

### 4.2 Presence probe

`SyncStateResponse.generation` **present** ⇔ the server is at least the release that also ships
typed error codes 7–12 and the epoch guard (they cannot arrive separately). Against an older server
the five typed codes arrive as their legacy codes (`UNAUTHORIZED`, `SCHEMA_MISMATCH`,
`ACTIVE_SESSION_EXISTS`, `INTERNAL`) — see [§10](#10-error-codes--complete-matrix).

---

## 5. Schema submission & type mapping

```protobuf
message SchemaRequest  { map<string, TableSchema> tables = 1; }  // FULL schema, replace-on-write
message SchemaResponse { int32 schema_version = 1; google.protobuf.Timestamp updated_at = 2; }

message TableSchema {
  repeated Column    columns     = 1;
  repeated string    primary_key = 2;  // MAY be empty → keyless table (§7.2)
  repeated UniqueKey unique_keys = 3;
}
message Column    { string name = 1; string type = 2; bool nullable = 3; }
message UniqueKey { string name = 1; repeated string columns = 2; }
```

- Send the schema for **all** tables in one call — it replaces what the server holds and bumps
  `schema_version`. Required before the first session of a CDC site; re-send only on source
  structure change.
- `type` is a lowercase PostgreSQL type spelling: `"bigint"`, `"varchar(255)"`, `"numeric(10,2)"`,
  `"timestamp"`, `"date"`, `"boolean"`, `"bytea"`, …
- Table and column names must match `^[A-Za-z_][A-Za-z0-9_]{0,62}$`.
- Declare `numeric(p,s)` truthfully — the declared scale/precision is what Parquet consumers see;
  understated precision causes per-file type drift (the server widens to fit but logs a warning).

The submitted schema drives the typed Parquet the server produces:

| PostgreSQL type | Parquet / Avro |
|---|---|
| `varchar(n)`, `text`, `char`, `uuid`, `citext` | string |
| `integer`, `int`, `int4`, `serial`, `smallint`, `int2` | int (32-bit) |
| `bigint`, `int8`, `bigserial` | long (64-bit) |
| `real`, `float4` | float |
| `double precision`, `float8` | double |
| `numeric(p,s)`, `decimal(p,s)` | decimal (logical) |
| `boolean`, `bool` | boolean |
| `date` | date (logical) |
| `timestamp[…]` | timestamp-micros (logical) |
| `bytea` | bytes |
| *anything else* | string (lossless fallback) |

---

## 6. Streaming sessions

```protobuf
message ClientEvent { oneof event { SessionStart start; ChangeRecord change; SessionEnd end; } }
message ServerEvent { oneof event { SessionOpened opened; Ack ack; SessionCommitted committed; ServerError error; } }

message SessionStart {
  SessionMode mode              = 1;  // DELTA | FULL_SNAPSHOT | CONTINUOUS
  uint64      first_seq         = 2;
  int32       schema_version    = 3;  // version the records were produced against
  string      client_session_id = 4;  // client-generated; idempotency/resume key
  reserved 5;                         // NOT free (legacy client field) — never reclaim
  optional uint64 generation    = 6;  // last epoch the client saw (§9); send it always
}
enum SessionMode { DELTA = 0; FULL_SNAPSHOT = 1; CONTINUOUS = 2; }
```

### 6.1 Protocol sequence

1. Send exactly one `SessionStart` first.
2. Server replies `SessionOpened{server_session_id, server_last_seq, action, resume_from_seq,
   generation}`. Read `server_session_id` (== batch id) from **each** `SessionOpened` — a resume may
   hand you a different batch. If `action != PROCEED`, follow [§8](#8-recovery-model).
3. Send `ChangeRecord`s with strictly increasing `seq`. The server emits `Ack(acked_seq)` roughly
   every ~100 accepted records.
4. Send exactly one `SessionEnd{last_seq, per_table, content_hash}` — **omit in CONTINUOUS mode**.
5. Server replies `SessionCommitted{committed_seq, segment_s3_key}` (terminal for periodic
   sessions) or an in-band `ServerError`.

**One active session per site** — a concurrent `SessionStart` gets `ACTIVE_SESSION_EXISTS`.
Serialize your own runs. Separately, the **account** has a concurrent-session cap across its sites
(`CONCURRENT_BATCH_LIMIT` ⇒ back off and retry).

**Acks are progress, not durability.** `Ack` means received-and-buffered; only
`SessionCommitted`/the watermark is durable. Keep records replayable until commit. Bound your
in-flight (unacked) window and respect gRPC stream readiness — the server pulls one record at a
time (inbound flow control).

### 6.2 Reconciliation (`SessionEnd`)

```protobuf
message SessionEnd { uint64 last_seq = 1; map<string, TableStats> per_table = 2; string content_hash = 3; }
message TableStats { uint64 inserts = 1; uint64 updates = 2; uint64 deletes = 3; }
```

- `per_table[table]` must exactly equal the INSERT/UPDATE/DELETE counts you sent for that table in
  this session. Mismatch ⇒ `RECONCILIATION_FAILED`, **nothing committed**.
- `last_seq` must equal the highest sent seq (send `0` to opt out of that check).
- After a **resume**, the counts are **cumulative** for the whole logical session (staged before
  the drop + replayed tail).
- `content_hash` is optional (empty string skips it). When set: lowercase hex SHA-256 over a
  canonical serialization — records in seq order, columns sorted by name, every variable-length
  token length-prefixed, typed value tags (`I`nt, `D`ouble as IEEE-754 bits via
  `Double.doubleToLongBits`, `S`tring, `L`(bool), `M`(decimal string), `B`ytes hex, `N`ull),
  separators `0x1F`/`0x1E`/`0x1D`. Reference implementation: server class `ChangelogContentHash`.
  Recommended once the client is stable; skip in the first milestone.

### 6.3 Session size limits (periodic DELTA only)

| Limit | Config | Default |
|---|---|---|
| Records per session | `delta.ingestion.max-session-records` | 2,000,000 |
| Bytes per session | `delta.ingestion.max-session-bytes` | auto = server maxHeap / 8 |

The server buffers a periodic DELTA session on-heap until commit; exceeding a cap ⇒ `OVERFLOW` /
`OVERFLOW_BYTES` (nothing committed) — **retry that workload in CONTINUOUS mode**.
`CONTINUOUS` and `FULL_SNAPSHOT` seal as they stream and are not subject to these caps: a snapshot
of any size completes (segments seal every `snapshot-seal-records = 25,000`), and overflow in those
modes is a server misconfiguration, terminal for the client (see [§10](#10-error-codes--complete-matrix)).

### 6.4 Continuous mode

For near-real-time ingestion: `mode = CONTINUOUS`, never send `SessionEnd`, keep pushing records.

- Server **seals** a segment on record count (100), byte size
  (`delta.ingestion.continuous-seal-bytes`, 16 MiB) or time (`continuous-seal-millis`, 5 min),
  emitting one `SessionCommitted{committed_seq, segment_s3_key}` **per seal** — that is your durable
  watermark advancing; you may discard records ≤ `committed_seq`.
- The whole stream is **one batch** (batch = session). Batch timeout counts from last activity
  (`BATCH_TIMEOUT_MINUTES`, 60), so a live stream can run for hours; a silent one is reclaimed.
- Graceful close: the server flushes the non-empty tail and completes the batch.
- Stream **drop** mid-segment: the server durably seals the unsealed tail, advances the watermark
  and closes the batch — the tail is **not** lost. Reconnect, `GetSyncState`, open the next session
  at `last_applied_seq + 1` (the drop-seal advanced it past your last ack).
- No reconciliation and no content hash in this mode.
- Expect periodic server-initiated GOAWAY (`max-connection-age`); treat reconnect as routine.

**Recommended for a Postgres CDC client**: CONTINUOUS is the natural fit for a logical-replication
feed (unbounded, low latency). Periodic DELTA fits a cron-style "diff and push" client. Both are
valid; the protocol is the same.

---

## 7. Change records & value typing

```protobuf
message ChangeRecord {
  string             table     = 1;
  Op                 op        = 2;  // INSERT | UPDATE | DELETE
  uint64             seq       = 3;
  map<string, Value> key       = 4;  // PK values; all columns for keyless tables
  map<string, Value> data      = 5;
  google.protobuf.Timestamp source_ts = 6;  // optional source commit time
}
```

| Op | `key` | `data` |
|---|---|---|
| `INSERT` | PK values | **full row** |
| `UPDATE` | PK values | **changed columns only** (after-image) |
| `DELETE` | PK values | empty (tombstone) |

### 7.1 Value rules (hard requirements)

```protobuf
message Value { oneof v {
  bool is_null = 1; int64 int_value = 2; double double_value = 3; string string_value = 4;
  bool bool_value = 5; string decimal_value = 6; bytes bytes_value = 7; } }
```

- `integer`/`bigint`/… → `int_value`; `real`/`double precision` → `double_value`;
  `boolean` → `bool_value`; `bytea` → `bytes_value`; text-ish (`varchar`, `text`, `uuid`) →
  `string_value`.
- **`numeric`/`decimal` → `decimal_value` as the exact decimal string** (`"19.99"`). Never as
  `double_value` — precision loss.
- **`date` → `string_value`** as `YYYY-MM-DD`; **`timestamp` → `string_value`** as ISO-8601
  (`"2024-03-10T10:30:00Z"`). The server parses these by the **declared column type** for Parquet.
- **SQL NULL → `is_null = true`. Presence matters**: in an `UPDATE`, a column *absent* from `data`
  means "unchanged"; *present with `is_null = true`* means "set to NULL".

### 7.2 Keyless tables

Empty `primary_key` ⇒ the full column set is the identifying key. Then:

- **Never send `UPDATE`** (rejected). Any change = `DELETE`(old full row in `key`) +
  `INSERT`(new full row in `key` and `data`).

For a Postgres client, prefer declaring the real PK; keyless mode exists for legacy DBF semantics.

---

## 8. Recovery model

```protobuf
enum RecoveryAction { PROCEED = 0; RESUME_FROM = 1; NEED_REBASELINE = 2; }
```

### 8.1 Gap / replay at `SessionStart`

- DELTA/CONTINUOUS must not skip ahead: `first_seq ≤ server_last_seq + 1`. A forward gap ⇒
  `SEQUENCE_GAP` + `NEED_REBASELINE` ⇒ open a `FULL_SNAPSHOT`.
- `first_seq` **at or below** the watermark is a **replay** (e.g. lost `SessionCommitted` ack): the
  server proceeds and de-duplicates already-committed seqs. A lost ack costs a cheap replay, not a
  re-baseline.

### 8.2 Resume after a mid-session drop (DELTA)

If a DELTA stream drops before `SessionEnd`, the server stages what it received (in-memory,
TTL = `delta.ingestion.staged-ttl-millis`, default **50 min**) and keeps the batch open. Your next
DELTA `SessionStart` gets:

```
SessionOpened{ action = RESUME_FROM, resume_from_seq = <highest staged + 1>, server_session_id = <batch id> }
```

Replay from `resume_from_seq` (re-sent lower seqs are de-duplicated), then `SessionEnd` with
**cumulative** counts. Notes:

- `server_session_id` **may change** on resume (the old batch may have been reclaimed) — always
  take it from the `SessionOpened` you just received.
- Server restart or TTL expiry loses the staged data ⇒ `SEQUENCE_GAP`/`NEED_REBASELINE` ⇒ snapshot.
- If another session grabbed the site meanwhile: `ACTIVE_SESSION_EXISTS`.

### 8.3 Re-baseline

Any `FULL_SNAPSHOT` session erases drift and resets the floor: stream the complete dataset as
INSERTs. Use it for bootstrap, after a gap, on `NEED_REBASELINE` (the dashboard's "Full
re-baseline" button raises it server-side; it can also be cancelled server-side before your
session starts — you simply see `PROCEED` again). A snapshot is gap-exempt: its `first_seq`
continues your counter (`server_last_seq` is treated as `first_seq - 1`) — except after a wipe,
where you restart from 1 (§9). Since 033, snapshots of any size stream through (sealed into
provisional segments, published atomically at `SessionEnd`; a drop leaves the old baseline intact
and the re-baseline request re-armed).

---

## 9. The generation epoch (site history wipe)

Full contract: [`site-history-wipe-client-guide.md`](./site-history-wipe-client-guide.md). This is
**mandatory** for a new client — implement it from day one.

An operator can wipe all server-side history of a site (batches, files, segments, checkpoints,
schema, plugin SQL, error logs) while the site and its credentials survive. `NEED_REBASELINE`
cannot express "your counters are void", so `generation` is an epoch: **bumped only by a wipe**,
never by a re-baseline. Carried with explicit presence (`optional`) on:

| Message | Field # |
|---|---|
| `SyncStateResponse.generation` | 5 |
| `SessionOpened.generation` | 5 (both PROCEED and RESUME_FROM) |
| `SessionStart.generation` | **6** (client → server, echo of last seen) |

Absent on a response = older server, nothing else. **Always send** `SessionStart.generation` once
you track epochs — including value 0; omitting it disables the guard for you.

**Normative client algorithm** (persist per-site `last_seen_generation`, initially 0):

1. On every connect, `GetSyncState`. If `response.generation != stored`:
   - first observation with no local journal → adopt silently;
   - otherwise: drop the local journal, **reset the seq counter to 0**, forget the cached
     schema-version ack; then persist the new generation **before** starting the upload (that
     order is load-bearing — a crash between the two steps is safe only that way).
2. `SubmitSchema` (the server holds none after a wipe).
3. `SessionStart{mode: FULL_SNAPSHOT, first_seq: 1, schema_version: <step 2>, generation: <stored>}`;
   stream the full dataset as INSERTs from seq 1; `SessionEnd`.
4. Every later `SessionStart` echoes the stored generation. `GENERATION_MISMATCH` or any
   `NEED_REBASELINE` → back to step 1. A mismatch in `SessionOpened.generation` mid-flight → abort
   the session, back to step 1.

Server guard: a **present** `SessionStart.generation` that differs from the server's (0 vs 1
included — every site's first wipe) ⇒ `GENERATION_MISMATCH` + `NEED_REBASELINE`. The guard runs
before the resume branch.

Rolling deployments: an **absent** generation from an old pod never lowers/invalidates a stored
one; only a present, different value is an epoch change.

---

## 10. Error codes — complete matrix

`ServerError` arrives **in-band** on the stream (not as a gRPC status), so it can carry a recovery
action:

```protobuf
message ServerError { ErrorCode code = 1; string message = 2; RecoveryAction action = 3; uint64 resume_from_seq = 4; }
```

| Code | № | `action` | Meaning | Client behaviour |
|---|---|---|---|---|
| `SEQUENCE_GAP` | 1 | `NEED_REBASELINE` | `first_seq > server_last_seq + 1` | Full snapshot. |
| `SCHEMA_MISMATCH` | 2 | `NEED_REBASELINE` | stale `schema_version`, or keyless table sent UPDATE | Re-submit schema / declare a key; snapshot. |
| `RECONCILIATION_FAILED` | 3 | `NEED_REBASELINE` | counts / hash / `last_seq` disagree | Fix counting; snapshot. Nothing was committed. |
| `UNAUTHORIZED` | 4 | `PROCEED` | token problem | Refresh / re-authenticate. |
| `ACTIVE_SESSION_EXISTS` | 5 | `PROCEED` | this **site** has a live session | Serialize own runs; retry after it ends / times out. |
| `INTERNAL` | 6 | `NEED_REBASELINE` | unexpected server fault | Retry with backoff. **Never read as overflow.** |
| `OVERFLOW` | 7 | `NEED_REBASELINE` | session record cap | From DELTA: retry in CONTINUOUS. From CONTINUOUS/FULL_SNAPSHOT: **terminal** (server misconfig). |
| `OVERFLOW_BYTES` | 8 | `NEED_REBASELINE` | session byte budget | Same rule as `OVERFLOW`. |
| `SITE_INACTIVE` | 9 | `PROCEED` | site deactivated/deleted mid-stream | Stop; operator action needed. |
| `SCHEMA_REQUIRED` | 10 | `PROCEED` | no schema on file | `SubmitSchema`, retry. A snapshot hits the same wall. |
| `CONCURRENT_BATCH_LIMIT` | 11 | `PROCEED` | **account**-wide session cap | Back off, retry later. |
| `GENERATION_MISMATCH` | 12 | `NEED_REBASELINE` | epoch disagreement (wipe) | §9: re-read sync state, drop journal, snapshot from seq 1. |

Transport-level `UNAUTHENTICATED` (call closed, no `ServerError`) = bad/missing token metadata, or
a deactivated/deleted site detected at stream open.

**Old-server degradation** (probe: `SyncStateResponse.generation` absent): 7→6, 8→6, 9→4, 10→2,
11→5; the epoch guard does not exist. Decide early whether the client must support pre-#130
servers; if the deployed servers are current, treat "generation absent" as an unsupported-server
error and simplify.

---

## 11. Capturing changes from PostgreSQL

The middleware does not care *how* changes are captured — it sees only the typed changelog. This
section is design guidance for the capture layer, driven by what the wire protocol needs.

### 11.1 Recommended: logical replication

Use a logical replication slot with `pgoutput` (or `wal2json`/decoderbufs) on the source:

- `wal_level = logical`; a `PUBLICATION` for the replicated tables; one **replication slot** per
  site (the slot is your source-side resume token).
- **`REPLICA IDENTITY`** determines what UPDATE/DELETE events carry. `DEFAULT` (PK only) is
  sufficient: the protocol needs `key` = PK values and `data` = changed columns. Tables without a
  PK need `REPLICA IDENTITY FULL` — or better, get a PK; see keyless caveats in §7.2.
- **TOAST caveat**: unchanged TOASTed columns are *not* present in the UPDATE event with
  `REPLICA IDENTITY DEFAULT` — which maps exactly onto the protocol's "absent = unchanged"
  semantics. Just make sure your decoder distinguishes "absent" from "null" (wal2json and pgoutput
  both do).
- Map events: pgoutput INSERT → `INSERT` (full row); UPDATE → `UPDATE` (key = PK/old identity,
  data = new values — you may send the full after-image; sending only changed columns is an
  optimization, both are protocol-legal); DELETE → `DELETE` (key only).
- `source_ts` ← the transaction commit timestamp.

### 11.2 Sequencing: LSN → seq

The protocol needs a strictly-increasing dense-enough `uint64` starting at 1 — LSNs are increasing
but you must not send gaps the server would flag... it doesn't: only `first_seq` at session open is
gap-checked; *within* a session seqs must be strictly increasing, not contiguous. Still, the simplest
robust design is a **local counter**:

- Maintain a per-site monotonic counter; stamp each outgoing record with `counter++`.
- Persist `(counter, source LSN)` **atomically together** on every durable point
  (`SessionCommitted` / seal in CONTINUOUS). On restart: resume decoding from the saved LSN,
  confirm the slot (`pg_replication_slot_advance` / standby status update only up to the LSN whose
  records are **committed** by the middleware — never advance the slot past unacked data), and
  align the counter with `GetSyncState.last_applied_seq`.
- Rule for slot feedback: **the replication slot may only be advanced past WAL whose records the
  middleware has durably committed** (`SessionCommitted`). This makes the middleware, not local
  disk, the source of durability, and lets the client replay after any crash.

### 11.3 Bootstrap (initial snapshot)

1. `SubmitSchema` (introspect `information_schema` / `pg_catalog` for columns, types, PK, unique
   constraints).
2. Create the replication slot **first** (or use a consistent-snapshot export:
   `CREATE_REPLICATION_SLOT ... EXPORT_SNAPSHOT`), then read the table contents in that snapshot —
   this guarantees no gap between snapshot and the change stream.
3. Stream the snapshot as a `FULL_SNAPSHOT` session (all INSERTs, seq from 1 or from
   `watermark + 1`).
4. Switch to CONTINUOUS (or periodic DELTA) from the slot, seq continuing after the snapshot's
   `last_seq`.

The same path implements re-baseline (`NEED_REBASELINE`) and post-wipe recovery (§9 — with the seq
counter reset to 0 first).

### 11.4 Alternative: query-based diff

For sources where logical replication is unavailable, a periodic diff (by updated-at column /
full-table compare) pushed as DELTA sessions is protocol-legal — that is the DBF client's model.
Costs: no deletes without full compare, higher latency, more source load. Plan for logical
replication as the primary mode.

### 11.5 Schema drift

On source DDL (column added/dropped/type change): pause the stream at a transaction boundary,
re-introspect, `SubmitSchema` (bumps `schema_version`), continue with the new
`SessionStart.schema_version`. The server rejects a stale declared version with `SCHEMA_MISMATCH`.

---

## 12. Client-side persistent state

Everything the client must keep across restarts (atomically where noted):

| State | Why |
|---|---|
| `refreshToken`, `refreshTokenExpiresAt`, `siteId`, `apiBaseUrl` | auth without re-onboarding |
| per-site `seq` counter | strictly-increasing seq across sessions |
| source resume token (LSN / slot position) — **atomic with the seq counter** | replay after crash |
| `last_seen_generation` | wipe detection (§9); persisted *after* local reset, *before* upload |
| last acked `schema_version` | know when to re-submit |
| unacked/uncommitted record buffer (or the ability to re-read from source) | replay after drop; acks are not durability |
| `client_session_id` of an in-flight session | idempotency/resume key |

---

## 13. Auxiliary REST API

All with `Authorization: Bearer <accessToken>`. Ingestion-related REST is gone; what survives and
is useful to the client:

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/v1/device/auth/refresh` | POST | refresh tokens (public, body carries refreshToken) |
| `/api/v1/device/errors` | POST | report a standalone client error: `{ type, message, severity?, metadata? }`, severity ∈ `CRITICAL,ERROR,WARNING,INFO` (default ERROR) → 201. Surfaces in the dashboard's Global Errors widget |
| `/api/v1/device/errors/batches/{batchId}` | POST | same, tied to a batch (use `server_session_id` as the batch id) |
| `/api/v1/device/batches/{id}` | GET | batch metadata (status, timestamps) |
| `/api/v1/device/batches/{id}/complete` · `/fail` · `/cancel` | POST | legacy batch lifecycle — **not used** for gRPC sessions (the stream manages its batch); do not call these on a session's batch |

**Report errors.** Wire the client's fatal/retryable failures to `POST /api/v1/device/errors` —
that is the operator's visibility into a struggling site (unread badge, severity filter on the
dashboard).

Server-side observability that will help during development: the backend logs every session event
(`Delta session start/opened/committed/rejected/transport drop`, `auth_failure`) and exports
Micrometer meters (`delta_sessions_started_total` etc.) — see the "Server-side observability"
section of [`delta-client-v2-guide.md`](./delta-client-v2-guide.md).

---

## 14. References

Normative, in order of precedence:

1. [`src/main/proto/delta-ingestion.proto`](../src/main/proto/delta-ingestion.proto) — the wire contract (vendor + pin).
2. [`delta-client-v2-guide.md`](./delta-client-v2-guide.md) — full integration guide (lifecycle, limits, egress, troubleshooting, observability).
3. [`site-history-wipe-client-guide.md`](./site-history-wipe-client-guide.md) — normative generation-epoch contract.
4. [`delta-v2-wire-contract-answers.md`](./delta-v2-wire-contract-answers.md) — settled wire-contract decisions (error code numbers, presence, overflow semantics, version probe).
5. [`device-flow-client-guide.md`](./device-flow-client-guide.md) — device authorization / token refresh, with examples.
6. [`cr-delta-client-v2.md`](./cr-delta-client-v2.md) — design rationale.
7. `postgres-cdc-client-guide.md` — **archived**, historical only.

Prior art: the `dbf-data-extractor` Windows client (Rust) implements this same contract (device
flow + gRPC Delta v2, whole-session retry on `UNAVAILABLE`, vendored proto with descriptor check
in CI) — the closest working reference for client-side structure.

## 15. Implementation checklist

Suggested milestone order (each independently testable against the dev/test environment):

1. **Auth**: device flow onboarding (`siteType: POSTGRES_CDC`), credential persistence, refresh
   with rotation, startup logic (refresh-or-onboard).
2. **Proto + channel**: vendor the proto, generate stubs, TLS channel, `authorization` metadata,
   `GetSyncState` round-trip. Decide the old-server policy (§10) here.
3. **Schema**: source introspection → `SubmitSchema`; identifier validation; type spelling.
4. **Bootstrap**: consistent snapshot → `FULL_SNAPSHOT` session (INSERTs, per-table counts,
   `SessionEnd`), watermark persistence on `SessionCommitted`.
5. **Steady state**: logical replication decode → CONTINUOUS session; LSN↔seq bookkeeping; seal
   handling (`SessionCommitted` per segment); slot feedback only up to committed seq; GOAWAY
   reconnect.
6. **Recovery**: replay-at-or-below-watermark, `RESUME_FROM` (DELTA), `SEQUENCE_GAP` → snapshot,
   full error-code matrix, backoff.
7. **Generation epoch** (§9): persist/echo/reset — with the first-wipe (0→1) test case.
8. **Hardening**: `content_hash`, error reporting to `/api/v1/device/errors`, schema-drift
   handling, metrics/logging, soak test against `test.dfm.bitbi.io`.
