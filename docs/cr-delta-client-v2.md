# Change Request: Delta Client v2 & Stateful Changelog

**Document Version**: 0.1.0 (DRAFT — design review)
**Last Updated**: 2026-06-05
**Status**: Proposal — not yet approved for implementation
**Supersedes (conceptually)**: [cr-site-types-postgres-cdc.md](./cr-site-types-postgres-cdc.md) (CDC v1)
**Related contract**: [delta-ingestion.proto](../src/main/proto/delta-ingestion.proto)
**Client guide**: [delta-client-v2-guide.md](./delta-client-v2-guide.md)
**Русская версия**: [cr-delta-client-v2.ru.md](./cr-delta-client-v2.ru.md)

---

## Context

CDC v1 ([cr-site-types-postgres-cdc.md](./cr-site-types-postgres-cdc.md)) introduced the idea of a client that computes changes at the source and sends **only deltas**: `POSTGRES_CDC` sites submit a schema, do a one-time full CSV load, then upload `.jsonl.gz` delta files (`I`/`U`/`D`) over the legacy HTTP client API (`/api/dfc/**`). The server converts those deltas **directly into SQL text** for the Bit BI plugin and keeps no composed state of its own.

This CR generalizes that idea into a first-class ingestion path — the **Delta Client v2** — and makes two structural changes:

1. **The server becomes stateful over a changelog.** Instead of being a stateless delta→SQL transcoder, the server stores an append-only **changelog** as the source of truth and periodically materializes **checkpoints** (full snapshots) from it. From the same changelog it serves three projections: legacy CSV (for Bit BI), a Parquet change feed (for Power BI), and re-baseline snapshots (for self-healing).
2. **A new transport.** Ingestion moves from multipart HTTP file uploads to a **gRPC bidirectional stream** with a typed Protobuf contract, replacing the per-batch `.jsonl.gz` upload with a streamed session.

The first-order efficiency win — sending only diffs instead of full snapshots — is inherited from CDC v1. This CR is about making that model **general, reliable, and consumable** (Power BI), not about a new compression trick.

### Why now

- The file-delivery client is **ours**, so we can capture changes at the source (cheapest, most correct place) rather than re-deriving them centrally with heuristics.
- The legacy DBF path derives `UPDATE` vs `DELETE`+`INSERT` by lexicographic adjacency **without a primary key** ([CsvDiffService](../src/main/java/com/bitbi/dfm/plugin/application/CsvDiffService.java)) — a known correctness gap. Moving the diff to the edge with declared keys fixes it.
- We want a clean, typed, incrementally-refreshable source for **Power BI** (see §12), which the current SQL-text output cannot provide.

---

## Relationship to CDC v1 — what changes

| Aspect | CDC v1 (current) | Delta Client v2 (this CR) |
|---|---|---|
| Who computes the diff | Client (Postgres WAL) | Client (Postgres WAL **or** local snapshot diff) |
| Transport | HTTP multipart `.jsonl.gz` upload to `/api/dfc/**` | gRPC bidirectional stream |
| Wire format | JSONL (`op`/`k`/`d`), string-typed | Protobuf `ChangeRecord`, **typed values** |
| Ordering / resume | implicit per batch | explicit per-site **`seq`** + watermark + resume |
| Keyless tables | not addressed (CDC requires PK) | **all-fields key** → `INSERT`/`DELETE` only (§6) |
| Server state | none (delta → SQL text) | **changelog (source of truth) + checkpoints** (§4) |
| Bit BI output | SQL text per batch | **reconstructed CSV** from changelog (§11) |
| Power BI output | none | **Parquet change feed + checkpoint floor** (§12) |
| Gap / drift handling | none | sequence-gap detection + re-baseline (§10) |

CDC v1's schema model (`site_schemas`, `POST /api/dfc/schema`, columns/PK/uniqueKeys) is **kept** and reused.

---

## Scope

| In scope | Out of scope |
|---|---|
| gRPC `DeltaIngestion` service + Protobuf contract | Replacing the egress/Power BI read path (file-based, separate) |
| Per-site `seq` watermark, idempotency, gap detection | Full lakehouse (Iceberg/Delta/Hudi) — overkill at current scale |
| Changelog store (append-only segments) | Online point-queries against current state (no live mutable state table) |
| Periodic **checkpoints** (CSV + Parquet) from changelog | DDL generation (CREATE/ALTER TABLE) |
| All-fields key for keyless tables | Forcing existing legacy DBF/CDC HTTP sites off the old path immediately |
| Backward-compatible CSV reconstruction for Bit BI | Multi-region storage |
| Continuous-stream mode defined (impl. deferred) | Rate limiting tuning for gRPC (follow-up) |

### Non-goals (explicit)

- **Not** building a queryable database of customer data on the server. The server keeps a changelog + materialized files, **not** a live per-row mutable state table (see §4, decision against "Option 1").
- **Not** changing how Bit BI works. Bit BI keeps reading CSV from `/sites/{siteId}/files`; we change only how that CSV is produced (§11).

---

## 1. Architecture overview

```
        ┌────────────┐   gRPC stream (deltas)   ┌──────────────────────────────┐
        │ Delta      │ ───────────────────────► │  Ingestion (StreamChanges)   │
        │ Client v2  │ ◄─── acks / recovery ──── │  session = batch lifecycle   │
        └────────────┘                          └──────────────┬───────────────┘
                                                                │ append
                                                                ▼
                                                   ┌────────────────────────┐
                                                   │  CHANGELOG (S3)         │  ← source of truth
                                                   │  append-only segments   │     (append-only)
                                                   └───────────┬────────────┘
                                                               │ periodic fold (scheduler)
                                                               ▼
                                                   ┌────────────────────────┐
                                                   │  CHECKPOINT @seq        │  = all-INSERT frame
                                                   │  (per site / table)     │
                                                   └───┬──────────┬─────┬────┘
                                       legacy CSV ◄────┘          │     └────► re-baseline
                                  (Bit BI /files)                 ▼            (self-healing)
                                                        Parquet change feed
                                                        (Power BI floor + deltas)
```

**Key principle:** a *full snapshot* is just a changelog frame whose records are all `INSERT`. There is **one** logical artifact (the changelog) and **one** fold operation; CSV, Parquet feed, and re-baseline are three projections of it.

---

## 2. Core concepts

| Concept | Definition |
|---|---|
| **Session** | One ingestion unit = one `StreamChanges` stream = one **Batch** (`IN_PROGRESS` → `COMPLETED`). Reuses [BatchLifecycleService](../src/main/java/com/bitbi/dfm/batch/application/BatchLifecycleService.java). |
| **`seq`** | Per-site monotonic sequence number assigned by the **client** (the source of ordering). Strictly increasing across sessions. |
| **Watermark** | Highest `seq` the server has durably committed. Client stores its own copy; the two are aligned via `GetSyncState`. |
| **Changelog segment** | The records of one session, persisted append-only in object storage. Immutable. |
| **Checkpoint** | A materialized full snapshot of a table at a given `seq`, produced by the scheduler. Serves CSV, Parquet floor, and re-baseline. |

---

## 3. Session lifecycle (start / end)

A session is a bidirectional gRPC stream. Client sends exactly one `SessionStart`, then `ChangeRecord`s with strictly increasing `seq`, then exactly one `SessionEnd`. Server replies with `SessionOpened`, progressive `Ack`s, and a final `SessionCommitted` (or `ServerError`).

- **Start** (`SessionStart`): `mode` (`DELTA` | `FULL_SNAPSHOT`), `first_seq`, `schema_version`, `client_session_id`. Server validates `first_seq == server_last_seq + 1`, opens a batch, returns `SessionOpened` with `server_last_seq` and a `RecoveryAction`.
- **Body** (`ChangeRecord`): see §6. Server stages records, may emit `Ack(acked_seq)` for backpressure/progress.
- **End** (`SessionEnd`): `last_seq`, per-table counts, `content_hash`. Server reconciles (§10), commits the batch, appends the changelog segment, returns `SessionCommitted(committed_seq, segment_s3_key)`. Client advances its watermark.

**One active session per site** (mirrors the existing one-active-batch rule). A second concurrent `StreamChanges` is rejected with `ACTIVE_SESSION_EXISTS`.

---

## 4. Server state model — changelog + checkpoints (decision: "2b")

Three models were considered:

| Model | Description | Verdict |
|---|---|---|
| **1. Live materialized state** | Mutable per-(site,table,PK) current state, upserted on every delta | ❌ Turns the middleware into a mini-database; no consumer needs online point-queries |
| **2a. Pure changelog** | Store only the journal; fold the entire history on demand | ❌ Fold cost & storage grow unbounded with history |
| **2b. Changelog + periodic checkpoints** | Journal is source of truth; periodic snapshots bound reconstruction | ✅ **Chosen** |

**2b rationale:** reconstruction = `latest checkpoint + fold(deltas since)` → bounded cost. The checkpoint artifact simultaneously serves **four** consumers with one mechanism: (1) legacy CSV for Bit BI, (2) re-baseline / self-healing, (3) client bootstrap floor, (4) Power BI baseline floor (so old changelog can be pruned and first loads stay fast). No consumer requires a live queryable state table, so Model 1 is unjustified.

**Companion decisions (required for 2b to hold):**

- **Changelog retention** — once checkpoint@N is durable, raw segments with `seq ≤ N` may be pruned/cold-archived after an audit/replay window. Without this, 2b degrades into 2a on storage.
- **Checkpoint frequency** — a tunable knob: more frequent → cheaper reconstruction, more storage churn. Default: align to the egress cadence (daily), with a coarser "floor" snapshot (e.g. weekly) under Power BI.
- **Gap detection** — persist `last_applied_seq` per site; checkpoint time is a natural continuity check.

---

## 5. Data model (storage)

### 5.1 New tables (Flyway `V29__delta_ingestion.sql`, sketch)

```sql
-- Per-site ingestion watermark & checkpoint pointers
CREATE TABLE site_sync_state (
    site_id              UUID PRIMARY KEY REFERENCES sites(id) ON DELETE CASCADE,
    last_applied_seq     BIGINT NOT NULL DEFAULT 0,
    last_checkpoint_seq  BIGINT NOT NULL DEFAULT 0,
    last_checkpoint_at   TIMESTAMP,
    schema_version       INTEGER NOT NULL DEFAULT 0,
    updated_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Changelog segment metadata (records themselves live in object storage)
CREATE TABLE changelog_segments (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id       UUID NOT NULL REFERENCES sites(id) ON DELETE CASCADE,
    batch_id      UUID NOT NULL REFERENCES batches(id),
    first_seq     BIGINT NOT NULL,
    last_seq      BIGINT NOT NULL,
    record_count  BIGINT NOT NULL,
    content_hash  VARCHAR(128) NOT NULL,
    s3_key        VARCHAR(1000) NOT NULL,
    mode          VARCHAR(20) NOT NULL,      -- DELTA | FULL_SNAPSHOT
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_segment_site_first_seq UNIQUE (site_id, first_seq)
);
CREATE INDEX idx_segment_site_seq ON changelog_segments(site_id, last_seq);

-- Materialized checkpoints (one current per site/table)
CREATE TABLE checkpoints (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id        UUID NOT NULL REFERENCES sites(id) ON DELETE CASCADE,
    table_name     VARCHAR(63) NOT NULL,
    seq            BIGINT NOT NULL,
    row_count      BIGINT NOT NULL,
    s3_key_csv     VARCHAR(1000),
    s3_key_parquet VARCHAR(1000),
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_checkpoint_site_table UNIQUE (site_id, table_name)
);
```

`site_schemas` (CDC v1) is reused unchanged for column/PK/type metadata.

### 5.2 Object-storage layout

```
delta/{siteId}/segments/{batchId}.pb.gz              # bronze changelog (raw protobuf records, gzip)
egress/{siteId}/{table}/_change_date=YYYY-MM-DD/*.parquet   # gold change feed (Power BI)
checkpoints/{siteId}/{table}/seq={seq}/snapshot.csv.gz      # legacy CSV (Bit BI)
checkpoints/{siteId}/{table}/seq={seq}/snapshot.parquet     # Parquet floor (Power BI)
```

**Wire stays raw, Parquet lives at serving.** Ingest writes raw segments (bronze); the scheduler materializes Parquet (gold). This avoids the small-files cost of per-session Parquet and keeps Parquet's value where it pays off (Power BI).

---

## 6. Change record & keys

Each `ChangeRecord` carries `table`, `op`, `seq`, `key`, `data`, `source_ts` (see [proto](../src/main/proto/delta-ingestion.proto)).

- **INSERT** — `data` = full row; `key` = PK values.
- **UPDATE** — `key` = PK; `data` = changed columns only (after-image).
- **DELETE** — `key` = PK; `data` empty (tombstone).

### Keyless tables (all-fields key)

Most tables have a declared PK. For tables **without** one, `primary_key` is empty in the schema and the **entire set of columns is the identifying key**. Consequences (documented behavior, mirrors legacy DBF semantics):

1. **No `UPDATE` is possible.** Any field change re-keys the row, so it is expressed as `DELETE`(old full row) + `INSERT`(new full row). The client MUST NOT emit `UPDATE` for keyless tables; the server rejects it.
2. **Identical duplicate rows are ambiguous** under a full-row key (two byte-identical rows are indistinguishable). See Open Question OQ-1 — pending whether such duplicates occur; if they do, a row-multiplicity counter is required.

### Value typing

Values are typed on the wire (`Value` oneof: null / int / double / string / bool / decimal-as-string / bytes), so the server no longer infers types from strings as in CDC v1's JSONL. `decimal_value` carries exact numerics as strings to avoid float drift.

---

## 7. gRPC protocol

Full contract: [delta-ingestion.proto](../src/main/proto/delta-ingestion.proto). Three RPCs:

| RPC | Type | Purpose |
|---|---|---|
| `GetSyncState` | unary | Resume helper — returns `last_applied_seq`, `schema_version`, `RecoveryAction` before a session |
| `SubmitSchema` | unary | Replace full table schema (only on change) |
| `StreamChanges` | bidi stream | The session (start → records → end), with acks & recovery |

Auth: `Authorization: Bearer <accessToken>` in gRPC metadata; refresh via the existing device flow (`POST /api/v1/device/auth/refresh`). `site_id` is derived from the token.

---

## 8. Interaction flows

### A. Onboarding (one-time)

```
Client                                   Server
 │ POST /api/v1/device/authorize ────────────►│ create device authorization
 │ ◄──────────── deviceCode, userCode ─────────│
 │ (user approves in browser)                  │
 │ POST /api/v1/device/token (poll) ──────────►│
 │ ◄──── accessToken, refreshToken, siteId ────│
 │ SubmitSchema(tables, PK) ──────────────────►│ store site_schemas v1
 │ ◄──────────── schemaVersion = 1 ────────────│
 │ StreamChanges ▼  (mode = FULL_SNAPSHOT)      │
 │   SessionStart(first_seq = 1) ─────────────►│ batch start
 │   ChangeRecord(INSERT, …) × N ─────────────►│ stage records
 │   SessionEnd(last_seq = N) ────────────────►│ reconcile + batch complete
 │ ◄──── SessionCommitted(committed_seq = N) ──│ segment written; checkpoint@N floor
```

### B. Steady-state delta session (each period)

```
Client (e.g. hourly)                     Server
 │ GetSyncState(siteId) ──────────────────────►│
 │ ◄── last_applied_seq = 120, action=PROCEED ─│
 │ (client diffs since watermark = 120)         │
 │ StreamChanges ▼  (mode = DELTA)              │
 │   SessionStart(first_seq = 121) ───────────►│ check 121 == 120+1 ✓ → batch start
 │   ChangeRecord(UPDATE, k, d, seq=121) ─────►│
 │   ChangeRecord(INSERT, d,    seq=122) ─────►│
 │   ChangeRecord(DELETE, k,    seq=123) ─────►│
 │ ◄────────── Ack(acked_seq = 122) ───────────│ progressive watermark
 │   SessionEnd(last_seq=123, per_table) ─────►│ reconcile counts/hash → batch complete
 │ ◄──── SessionCommitted(committed_seq=123) ──│ append segment [121..123]
 │ (client advances watermark → 123)            │
```

### C. Gap / recovery

```
 │ GetSyncState ──────────────────────────────►│
 │ ◄── last_applied_seq = 123 ─────────────────│
 │ SessionStart(first_seq = 130) ─────────────►│ 130 ≠ 124 → SEQUENCE_GAP
 │ ◄── SessionOpened(action = NEED_REBASELINE) │
 │ StreamChanges ▼  (mode = FULL_SNAPSHOT) ────►│ client re-sends full snapshot
 │   ChangeRecord(INSERT, …) × all rows ──────►│ → new checkpoint; seq realigned
```

### D. Server-side checkpoint (async, no client)

```
Scheduler (e.g. nightly)                 Server
 │ for each site / table:                       │
 │   load latest checkpoint@M                   │
 │   fold changelog (M, now] → current state    │
 │   write checkpoint@now (all-INSERT frame):   │
 │     ├─ snapshot.csv.gz   → legacy (Bit BI)    │
 │     └─ snapshot.parquet  → Power BI floor     │
 │   materialize Parquet change-feed partitions  │
 │   prune changelog segments older than retention│
```

### E. Consumers (pull only)

```
Power BI (incremental refresh, hourly):
   reads checkpoint floor (rarely) + change-feed partitions since watermark (each run)
   → folds latest-per-key in the model

Bit BI (unchanged):
   GET /api/v1/plugins/bit-bi/sites/{siteId}/files
   → server returns reconstructed CSV (= latest checkpoint)
```

---

## 9. Modes: periodic vs continuous

One protocol, two modes, selected by `SessionStart.mode`.

- **Periodic session (default, recommended first):** `mode = DELTA` (or `FULL_SNAPSHOT` to bootstrap/re-baseline). Client opens `StreamChanges` on a schedule (hourly/daily), drains accumulated deltas, sends `SessionEnd`. One session = one segment, reconciled at `SessionEnd`. Matches the freshness requirement and the batch model; lowest risk.
- **Continuous stream:** `mode = CONTINUOUS`. Client keeps the stream open and pushes changes as they occur, never sending `SessionEnd`; the **server seals** a segment once it reaches a size threshold (`CONTINUOUS_SEAL_RECORDS`; a time threshold is a follow-up) and emits `SessionCommitted` per sealed segment, opening the next segment under a fresh batch. The final partial segment is flushed on stream close. Near-real-time, same contract.
  - **Implementation note (T5.4):** continuous is signalled by the explicit `SessionMode.CONTINUOUS` enum value (not merely the absence of `SessionEnd`), so the server knows to seal on threshold without conflicting with periodic `SessionEnd` reconciliation. Periodic `DELTA`/`FULL_SNAPSHOT` behaviour is unchanged. A continuous session that drops mid-segment loses its unsealed tail; the client reconnects and continues from the committed watermark (resume/`RESUME_FROM` is implemented for periodic `DELTA` only — see §10/T5.1).

---

## 10. Reconciliation, gap detection & recovery

- **Ordering / idempotency:** records carry strictly increasing per-site `seq`. The server dedups by `(site_id, seq)`; re-delivery of an already-applied `seq` is ignored (at-least-once safe).
- **Gap detection:** at `SessionStart`, `first_seq` must equal `server_last_seq + 1`. Otherwise `SEQUENCE_GAP` → `NEED_REBASELINE` (or `RESUME_FROM` for a partial replay the server can satisfy from staged data).
- **End-of-session reconciliation:** `SessionEnd` carries per-table counts and a `content_hash`. On mismatch the server **refuses to commit** (`RECONCILIATION_FAILED`) and requests recovery. Decision: **hard-fail** — integrity of the composed base outweighs a lenient skip (contrast CDC v1, which skips malformed JSONL lines with a warning).
- **Self-healing:** any `FULL_SNAPSHOT` session re-establishes a clean checkpoint floor, erasing accumulated drift.

---

## 11. Backward compatibility — simulate the old DB (CSV from changelog)

Bit BI and any legacy consumer keep using `GET /api/v1/plugins/bit-bi/sites/{siteId}/files`. The change: the CSV they download is no longer the raw client upload — it is the **reconstructed checkpoint** (the latest `snapshot.csv.gz` for each table), produced by the fold in §8.D.

- On-schedule materialization (not per-request) keeps serving cheap.
- This preserves Bit BI's existing CSV-diff path **without any change to Bit BI** during the transition.
- The reconstructed CSV is bit-for-bit a valid full snapshot, so Bit BI's baseline/initialization flow is unaffected.

---

## 12. Power BI / Bit BI egress

- **Power BI** consumes the **Parquet change feed** (`egress/{siteId}/{table}/_change_date=…/`) via Incremental Refresh, with the **checkpoint Parquet** as the immutable floor underneath. The snapshot is modeled as an all-`INSERT` frame, so Power BI folds one uniform changelog (latest-per-key, honor deletes). See the egress design discussion; this CR produces the artifacts it reads.
- **Bit BI** is unchanged (§11).

This egress is **read-only and file-based**; it is intentionally decoupled from the gRPC ingestion path.

---

## 13. Transport rationale (why gRPC)

The first-order economy is already captured by **sending only diffs** (inherited from CDC v1). Transport is second-order:

- **gRPC/Protobuf** gives a **typed contract** (`.proto`), compact binary payloads (smaller than JSON), native **streaming** (the session model), progressive acks/backpressure, and client codegen. It also answers "don't invent your own format" — the change envelope is a standard IDL, not a bespoke convention.
- **Cost:** needs an HTTP/2-capable path (proxies/LB), more setup than REST, harder ad-hoc debugging. Spring supports gRPC via `spring-grpc` / `grpc-java`.

At current scale the bandwidth delta over HTTP/2 + compressed JSONL is modest; gRPC is chosen primarily for the **typed streaming contract and resumability**, which the reliability model (§10) leans on.

---

## 14. Migration & coexistence (strangler)

- The gRPC `DeltaIngestion` service is **additive**, alongside the existing `/api/dfc/**` and `/api/v1/device/**` HTTP APIs. Nothing is removed on day one.
- Existing legacy DBF/CDC HTTP sites keep working on the old path. Sites move to Delta Client v2 individually; on first `FULL_SNAPSHOT` session a site's changelog/checkpoint lineage begins.
- Once a site is on v2, its Bit BI CSV is served from checkpoints (§11) instead of raw uploads — transparent to Bit BI.
- The legacy server-side diff (`CsvDiffService`, `DbfSqlGenerationStrategy`) is **deprecated** as sources migrate, and retired when no site depends on it.
- **HTTP file path closed per site (Task 7).** Once a site is flagged `client_api_version = V2`, the HTTP file-path **write** endpoints reject it with `409 Conflict` and machine-readable `code: "CLIENT_API_V2_REQUIRED"` (`ClientApiVersionGuard`): `POST /api/dfc/batch/start`, `POST /api/dfc/batch/{batchId}/upload`, `POST /api/dfc/schema`, `POST /api/v1/device/batches/start`, `POST /api/v1/device/files/batches/{batchId}/upload`. Drain endpoints (batch complete/complete-with-warnings/fail/cancel/get, file metadata) stay open so an in-flight batch can be closed after the flip, and the client error-log endpoints (`POST /api/dfc/error`, `/api/v1/device/errors`) stay open for **all** sites — Delta v2 has no error-reporting RPC yet. V1 sites are unaffected.

---

## 15. Security

- gRPC auth via Bearer token in metadata; same Auth V2 device-flow tokens & refresh as `/api/v1/device/**`.
- `site_id` derived from the token; cross-site `seq` or schema writes rejected.
- TLS required (h2 over TLS). mTLS optional follow-up for high-assurance clients.
- Egress Parquet/CSV inherit existing S3 access controls; no customer-identifying ingestion metadata (IPs, client ids) is written into the egress projections.

---

## 16. Implementation phases

### Phase 1 — Contract & session skeleton
1. `delta-ingestion.proto` + gradle gRPC codegen (`spring-grpc`).
2. `DeltaIngestionService` (gRPC) with `StreamChanges` wired to `BatchLifecycleService` (session = batch).
3. `GetSyncState` + `site_sync_state` table (`V29`).
4. Bearer-token interceptor reusing Auth V2.

### Phase 2 — Changelog ingest
5. Per-site `seq` validation, `(site_id, seq)` idempotency, gap detection.
6. `changelog_segments` persistence; write raw segment to S3 on commit.
7. `SessionEnd` reconciliation (counts + `content_hash`), hard-fail path.
8. `SubmitSchema` over gRPC (reuse `SiteSchemaService`); keyless / all-fields-key handling.

### Phase 3 — Checkpointing & reconstruction
9. Scheduler: fold `latest checkpoint + deltas` → checkpoint (`checkpoints` table).
10. Write `snapshot.csv.gz` (legacy) + `snapshot.parquet` (floor).
11. Wire Bit BI `/sites/{siteId}/files` to serve reconstructed CSV.
12. Changelog retention/pruning behind checkpoints.

### Phase 4 — Power BI egress
13. Materialize Parquet change-feed partitions (`_change_date`).
14. Validate against a real Power BI Incremental Refresh setup.

### Phase 5 — Hardening & continuous mode
15. Resume (`RESUME_FROM`), backpressure tuning, metrics.
16. Continuous-stream mode (server-sealed segments).

---

## 17. Verification plan

### Unit
- `seq` ordering / idempotency (duplicate, out-of-order, gap).
- Keyless tables: `UPDATE` rejected; `DELETE`+`INSERT` round-trips.
- `Value` typing (null vs absent, decimal exactness).
- Fold: `checkpoint + deltas` → expected state (I/U/D, deletes honored).
- Reconciliation: count/hash mismatch → `RECONCILIATION_FAILED`.

### Integration (Testcontainers + in-process gRPC)
- Full session: start → records → end → segment + watermark advanced.
- Gap → `NEED_REBASELINE`; full-snapshot recovery restores a clean checkpoint.
- Checkpoint job: changelog → CSV + Parquet; Bit BI `/files` serves reconstructed CSV.
- Retention: segments below checkpoint pruned; reconstruction still correct.
- Backward-compat: a Bit BI client sees no behavioral change.

### Manual E2E
1. Onboard a site, full-snapshot bootstrap, verify checkpoint@N.
2. Several delta sessions; verify changelog + folded state.
3. Kill client mid-session; `GetSyncState` resume.
4. Force a gap; verify re-baseline.
5. Point Power BI at the Parquet feed; confirm incremental refresh.

---

## 18. Open questions / deferred decisions

- **OQ-1 (keyless duplicates): RESOLVED — no.** The client guarantees it sends only unique deltas. **Tables with a primary/unique key** get the full `INSERT/UPDATE/DELETE` set (UPDATE = changed columns matched by key); for DBF tables UPDATEs are rare but supported when a key is declared. **Keyless tables** (all-fields key) do a set comparison and emit **only INSERT / DELETE** (a row that no longer matches → DELETE, a new row → INSERT) — **no UPDATE** (confirms §6). Full-row key is treated as unique → **no row-multiplicity counter needed**.
- **OQ-2 (seq granularity):** per-site `seq` (chosen — simpler continuity) vs per-table `seq` (more parallelism). Revisit if per-table throughput becomes a bottleneck.
- **OQ-3 (site ingestion flag): RESOLVED.** Add a site field **`client_api_version`** (`V1` = legacy HTTP `/api/dfc`, `V2` = Delta gRPC). **`V2` is the default** for new sites; existing sites are **backfilled to `V1`** in the V29 migration so the legacy path keeps working. `site_type` (DBF / POSTGRES_CDC — data semantics) is orthogonal and unchanged.
- **OQ-4 (checkpoint cadence):** default frequencies for the table checkpoint vs the Power BI floor; tune against real data volumes.
- **OQ-5 (segment wire format at rest):** raw Protobuf vs JSONL for bronze segments (Protobuf assumed; JSONL easier to debug).

---

## Version history

| Version | Date | Changes |
|---|---|---|
| 0.1.0 | 2026-06-05 | Initial draft for design review |
