# CR: Bit BI plugin v2 — Parquet files instead of generated SQL

**Status:** in progress. Phase 1, part 1 (`/v2/sites`, `/state`, `/schema`) shipped with #386;
part 2 (`/snapshot`, `/files/{fileId}`) with #387; phase 2 (`/changes`) #388, the pilot #389.
Committed to `develop` with #386; until then the draft lived outside the repository.
**Spec directory:** `specs/044-bitbi-parquet/`.
**Flyway:** one migration expected (V61, phase 2). No frontend change. No proto change.
**History:** first draft 2026-10-01; revised 2026-10-05 after BitBi's follow-up questions
(`dfm-questions-v2-2026-10-05.md`): row identity per table, a table manifest per batch, the
dependency on #380, rows without a hash; and after their third round (`dfm-questions-v3-…`): full
moves from the old server, the go-ahead per site, the pilot comparison. The answers to BitBi are
`docs/handoff/bitbi-answers-2026-10-05.md` and `docs/handoff/bitbi-answers-v3-2026-10-05.md`
(handed to BitBi; not committed to this repository).

## Why

Bit BI today mirrors a site in two steps: it downloads the checkpoint Parquet of each table
(`GET /api/v1/plugins/bit-bi/sites/{siteId}/files`) and then applies SQL the server renders from
changelog segments (`GET /api/v1/plugins/bit-bi/sql-changes`). The SQL half is the expensive one:

- the server renders, stores and queues SQL per batch (`SqlGenerationService`, the delta-SQL queue,
  the heap guard, per-table baselines in `plugin_delta_baselines`);
- the SQL is **not idempotent**, so a re-delivered batch corrupts the mirror and the only repair is
  `reinit`;
- every class of defect fixed in #215, #233, #240, #263 and #370 came from rendering typed values
  and keys as SQL text.

The server already produces the same changes as typed Parquet: one file per table per completed
batch (036), ordered per site (#378), with `_op`, `_seq`, `_changed` and `_row_hash`. Version 2 lets
Bit BI consume those files directly and holds the watermark on the consumer side.

## Row identity: two modes, chosen per table

How a consumer finds "the same row" depends on the source, and v2 states it per table instead of
leaving the consumer to infer it.

| Mode | Where it occurs | The row's key | Operations that arrive |
|---|---|---|---|
| **`row_hash`** | **Every DBF table** (DBF has no primary keys). Later also a Postgres or MSSQL table that has no primary key | `_row_hash`: the client's SHA-256 of the whole row | `INSERT` and `DELETE` only. A changed row is a `DELETE` of the old row and an `INSERT` of the new one; there is no `UPDATE` |
| **`primary_key`** | Future Postgres and MSSQL sources, wherever the source table has a primary key | the declared `primaryKey` columns | `INSERT`, `UPDATE` (only the columns in `_changed`), `DELETE` |

The mode is derived from what the client declared in `SubmitSchema`: a non-empty `primaryKey` means
`primary_key`, otherwise `row_hash`. Nothing is stored for it.

Facts the rules rest on (see `docs/delta-client-v2-guide.md`, "`row_hash`"):

- In the client's normal mode `_row_hash` is **unique within a keyless table**: the client sends only
  unique rows (OQ-1 of `docs/cr-delta-client-v2.md`). Observed on dev, 2026-09-30: every row of
  `fiattime` (148 870 INSERTs) and `nsfcard` (13 INSERTs, 12 DELETEs) carries a hash, all distinct.
- That uniqueness is by collapsing: identical records of a DBF table are sent as **one** row, so where
  the DBF holds duplicates the mirror holds fewer rows, and deleting one of two identical records
  sends nothing until the last copy is gone (extractor team, 2026-10-05). By design; BitBi is told.
- The understated `decimal` precisions of `fyt-new` are not a client defect: the declared precision
  comes from the DBF header and covers normally written values, while the source program stores an
  overflowing number in exponential notation (`.12E+17` in a 7-character field). Server-side widening
  per file stays the answer; the consumer loads through casts.
- Under the client's off-normal `[ingestion.keyless] track_duplicates = true` it is **not** unique: N
  identical rows share one hash, and a `DELETE` removes one of them. The checkpoint fold keys a
  keyless row by its whole content, so in that mode a snapshot holds one row where the source holds N
  — snapshot and change stream disagree. The mode is off by default; v2 does not support it.
- `INSERT` and the later `DELETE` of a row carry the same hash; `UPDATE` carries none. In a snapshot a
  row's hash is the one of the record that created it, kept through later `UPDATE`s — so for a
  `primary_key` table it no longer describes the row's content and must not be used.
- A row written **before the client sent the field** (issue #369; e.g. `nsfdoc` batches of
  2026-09-17 have no `_row_hash` column at all) has a NULL hash, in the snapshot as well. A
  `row_hash`-mode consumer cannot delete such a row by hash. **A site moved from the old server starts with
  a full `FULL_SNAPSHOT`, and a site already here with such rows gets one re-baseline before it
  moves**, so every row it holds carries a hash; the consumer then treats
  a NULL hash in a `row_hash` table as an error, not as a case to handle.

The server's own identity is unchanged: it keys a row by `key` (the whole row for a keyless table),
not by the hash (#369 declined that deliberately). v2 only tells the consumer which key to use.

## Target design

**One plugin, a second API surface.** The plugin id stays `bit-bi`, the API key
(`X-Plugin-Api-Key`) and the per-account rate limit stay. A versioned surface is added beside the
existing one, reusing the application services Parquet Export already uses
(`ParquetExportCatalogQuery`, `CheckpointFileQueryService`). Nothing is forked.

| Endpoint (under `/api/v1/plugins/bit-bi/v2`) | Returns |
|---|---|
| `GET /sites` | The account's sites (same data as v1) |
| `GET /sites/{siteId}/state` | `epoch`, `checkpointSeq`, `lastAppliedSeq`, `schemaVersion` |
| `GET /sites/{siteId}/schema` | Per table: columns and types, `identity` (`row_hash` or `primary_key`), `primaryKey`, `uniqueKeys` |
| `GET /sites/{siteId}/snapshot` | One checkpoint Parquet per table, all at `checkpointSeq` |
| `GET /sites/{siteId}/changes?afterSeq=&cursor=&size=` | Completed batches above `afterSeq`, in batch order, each with its table manifest |
| `GET /files/{fileId}` | `302` to a short-lived presigned URL |

Schema example:

```json
{ "table": "nsftrns", "identity": "row_hash",    "primaryKey": [],     "columns": [ ... ] }
{ "table": "orders",  "identity": "primary_key", "primaryKey": ["id"], "columns": [ ... ] }
```

A `changes` row is a **batch**, not a file:

```json
{
  "epoch": 3, "batchId": "…", "sessionMode": "delta", "firstSeq": 5023012, "lastSeq": 5023845,
  "tables": [
    { "table": "nsftrns", "status": "ready",     "fileId": "…", "rows": 834 },
    { "table": "nsfcard", "status": "abandoned", "fileId": null, "rows": 25 },
    { "table": "tmp_x",   "status": "missing",   "fileId": null, "rows": 4 }
  ]
}
```

The manifest lists every table the batch touched, taken from `batches.table_stats` (V58, written seal
by seal), so a table whose file was never produced is visible:

- `ready` — the file exists;
- `abandoned` — the build gave up (an operator can requeue it, 039);
- `missing` — no file and no artifact row: the table has no declared schema, or the batch was never
  queued (#380).

Only `ready` lets the consumer advance past the batch.

Decisions built into the design:

- **The cursor is `(epoch, seq)`, not time.** `changes` filters by `afterSeq`, so stitching a
  snapshot to the changes after it is one request, not a client-side scan. Parquet Export keeps its
  `since`/`producedAt` protocol unchanged.
- **The schema is served,** because a Parquet file records neither the primary key nor the row
  identity mode. For a DBF site every table answers `identity: row_hash`.
- **Downloads are authenticated redirects.** The API key is sent on every request, so the one-time
  links of Parquet Export (`download_links`) are not needed here. No download goes through the
  application's heap, unlike v1's proxied stream.
- **v2 needs no per-consumer state on the server.** The watermark is the consumer's; v2 reads
  neither `baseline_batch_id` nor `plugin_delta_baselines`.
- **No activation mode.** Every `bit-bi` activation can call both surfaces. SQL keeps being
  generated ahead of time for all of them, exactly as today, until `/sql-changes` is retired.

### Consumer protocol

1. `GET /state`; if `checkpointSeq` is 0, wait for the first checkpoint.
2. `GET /schema`; load every table from `/snapshot`; store `(epoch, checkpointSeq)` as the watermark.
3. Poll `/changes?afterSeq=<watermark seq>`; apply each batch in one transaction, then advance the
   watermark to its `lastSeq`. Re-applying after a crash is safe because the watermark moves with
   the data.
4. A batch with `sessionMode = full_snapshot` and an unseen `batchId`, or an `epoch` different from
   the stored one: clear the site's tables and reload (from that batch, or from `/snapshot`).
5. A batch with any table not `ready`: stop the site and alert. Recovery: an operator requeues the
   abandoned artifact; or the consumer waits for the nightly checkpoint to pass the batch and reloads
   from `/snapshot`.
6. A changed `/schema` (`schemaVersion` moved): re-read it before applying the next batch.

### Applying rows

- **`row_hash` table:** `INSERT` inserts the row with its `_row_hash`; `DELETE` removes the row with
  that `_row_hash`. Store `_row_hash` as a column of the mirror table.
- **`primary_key` table:** `INSERT`/`UPDATE` is an upsert by primary key (`UPDATE` touches only the
  columns in `_changed`), `DELETE` removes by key. Ignore `_row_hash`.
- **Types:** load through a staging table with casts. A decimal column can be wider in one file than
  in another when the declared schema understates the data, and a non-finite numeric arrives as NULL.

## Plan

| Phase | Scope | Notes |
|---|---|---|
| **0. Prerequisites** | `PLUGIN_PARQUET_EXPORT_BASE_URL` in the dev overlay (links are issued as `http://`); decide what to do with batches published before #378 that are listed out of order on dev; client fixes the understated decimals of `fyt-new`; #380 (in progress) | Found on dev, 2026-10-01. Independent of the rest |
| **1. Read surface** | `/v2/sites`, `/state`, `/schema` (with `identity`), `/snapshot`, `/files/{fileId}`; contract tests; guide | No migration. Reuses the checkpoint rows and storage and the site-schema read (v1's `CheckpointFileQueryService` keeps its own contract, see `specs/044-bitbi-parquet/plan.md`) |
| **2. Changes feed** | `/changes` with `afterSeq`, an explicit `epoch` and the table manifest per batch | **V61**: the epoch a batch belongs to has to be stored with the batch (`site_sync_state.baseline_epoch` is per site and moves). Catalog query gains a seq predicate and its index. Starts after #380 is merged: the feed inherits its ordering guarantee |
| **3. Pilot** | One site (proposed: `fyt-new`) mirrored through both surfaces at once; compare row counts and per-table checksums daily | Runs on dev. Exit criterion: seven nights without a difference. BitBi's own FYT mirror is stale since 2026-07-26, so the comparison is v2 against v1 on this server, not against it |
| **4. Migration** | Bit BI's sites move from the old server one by one, each with its written go-ahead: new site here, extractor switched, full initial snapshot; a sunset date for `/sql-changes` is announced | Not scheduled (decision 3). On the consumer side a move is a load from `/snapshot`; nothing changes in this server's code |
| **5. Removal** | Delete SQL generation, its queue, `plugin_sql_generations`, `plugin_delta_baselines`, the v1 routes | Separate ticket after the sunset. Removes `sql.generation.*` metrics |

Each phase is one ticket and one PR; phases 1 and 2 can be reviewed by the Bit BI side before the
pilot starts.

## What it does not change

- Parquet Export (`parquet-export`) and its contract.
- gRPC ingestion, the proto, the Windows client.
- The Parquet artifacts themselves: same files, same keys, same columns.
- How the server identifies a row (by `key`, not by `row_hash`).

## Risks

- **Latency.** A batch file appears after `SessionEnd`, so a site that holds a `CONTINUOUS` session
  for hours is behind until it ends, where `/sql-changes` follows each segment. Accepted (decision 2):
  v2 has no per-segment feed. The DBF client's steady state is short daily `DELTA` sessions;
  `CONTINUOUS` is opened for a change set of about a million records.
- **Checkpoint lag.** `/snapshot` is up to a day old. The changes above it are always retained
  (retention prunes only below the checkpoint), so the stitch has no gap.
- **Retention.** Batch files live as long as the batch (45 days by default). A consumer offline for
  longer reloads from `/snapshot`.
- **`track_duplicates`** on the client breaks the `row_hash` mode (above). Not supported by v2.
- **Rows without a hash** — closed by the re-baseline before a site moves (above).
- **Type drift** until clients declare `numeric(p,s)` truthfully.
- **SQL nobody reads.** With no mode, the server keeps generating SQL for a consumer that has moved
  to v2. It is today's load, not a new one, and it ends with phase 5.

## Decisions (owner)

2026-10-01:

1. **One plugin, no mode.** The id stays `bit-bi` and an activation has no `sql`/`parquet` switch;
   the consumer chooses the surface by calling it. A mode was considered and dropped: its only
   purpose was to stop generating unread SQL.
2. **Batch latency is enough.** Changes are delivered per completed batch; no per-segment feed.
3. **No sunset date for `/sql-changes` yet.** Phases 4 and 5 are not scheduled; v1 keeps working
   beside v2 until a date is set.
4. **The snapshot is the nightly checkpoint only.** No on-demand build.
5. **SQL is not generated on demand.** Rejected for three reasons: retention deletes the changelog
   segments SQL is rendered from unless generation is already pending (#212), so nothing would be
   left to render at request time; `/sql-changes` pages by the generation's creation time, which
   would become the request time and re-deliver or skip non-idempotent SQL; and the render would
   move from a bounded background queue into the request thread.

2026-10-05:

6. **Row identity is per table, two modes.** DBF tables are all keyless and keyed by `_row_hash`;
   Postgres and MSSQL sources will use their primary key where the table has one. `/schema` states
   the mode as `identity`.
7. **A batch in `/changes` carries its table manifest**, so a table with no file is `missing`
   rather than silent.
8. **No row reaches a v2 consumer without a hash.** BitBi's sites live on the old server, and moving
   one is a full move: the site is created on this server and loads from scratch with a
   `FULL_SNAPSHOT`, so every row carries a hash; the old site receives nothing afterwards. A site
   already on this server with rows written before #369 gets one re-baseline before it moves.
9. **A site moves only with BitBi's written go-ahead for that site** (their question 41). The
   extractor's run frequency is set on the site's side, not by DFM; BitBi asks for hourly runs, which
   the server takes without change.
10. **All five BitBi sites move** (Eden, Roshel Rehovot, Roshel-Beer-Sheva, FYT, GesherGolan) to the
   server where `fyt-new` runs (`test.dfm.bitbi.io`, overlay `k8s/overlays/dev`); FYT first, as the
   pilot.

2026-10-06 (BitBi's request v4):

11. **Row groups are bounded in bytes, not rows.** BitBi asked for at most 25 000 rows per row
    group; their memory grows with a group's size, which `delta.parquet.row-group-bytes` (16 MiB)
    already bounds. They are asked whether a byte bound fits; if 16 MiB is too much, the key is
    lowered. A row-count limit is not built (parquet-java 1.15.2 has no such builder option).
12. **Decimal widening stops at precision 38** (#382): a value needing more is written NULL and
    counted, the way a non-finite decimal is. 38 is the limit of BitBi's reader, DuckDB and Spark.

2026-10-19 — choices made while implementing #386 (phase 1, part 1); not owner decisions, open to review:

13. **`epoch` is `site_sync_state.baseline_epoch`.** It moves on a wipe and on a re-baseline and is
    never sent to the ingestion client; `generation` (the wire epoch) is not it, because a
    re-baseline does not move it. Pinned by `BitBiV2StateIntegrationTest` over a real wipe and a
    real re-baseline reset.
14. **`schemaVersion` has one source, `site_schemas.schema_version`,** in `/state` and `/schema`
    alike. The copy in `site_sync_state` is written by a second transaction after the schema upsert,
    so reading it would let the two endpoints disagree about one schema for that window.
15. **Another account's site answers 404, the body of an absent one** — a key cannot learn which
    site ids exist. v1 answers 403 there and keeps doing so.
16. **No security change for a v2 route.** Since #384 the plugin key owns the whole
    `/api/v1/plugins/bit-bi/**` namespace (`BitBiPluginApiPaths`), so v2 needs only its controller;
    an unknown path under `/v2` is 401 without a key and 404 with one.
17. **A 429 carries the standard error body** beside `Retry-After` (v1's JSON routes answer an
    empty body). v1 and v2 draw from one bucket per account.
18. **A deactivated site of the key's own account** is not listed by `/v2/sites` but answers
    `/state` and `/schema`, as v1's `/files` and `/sql-changes` serve it.

2026-10-20 — choices made while implementing #387 (phase 1, part 2); not owner decisions, open to review:

19. **`/snapshot` stays at the pointer through a build instead of answering 409.** A build saves each
    table's row at the new seq before it moves `last_checkpoint_seq`, and a build that stops part
    way (a scratch refusal, a shutdown) leaves the rows like that until the next night, so a 409
    would have meant up to a day without a snapshot. The objects at the pointer survive the window
    — the orphan sweep keeps every checkpoint object at or above the pointer — so a row that moved
    on is answered from the object at the pointer, one HEAD per such table, and only while rows and
    pointer disagree.
20. **A table with no file at the pointer is listed as `missing`, `fileId: null`.** That covers a
    table the build gave up on (#149), a row below the pointer, and a table first seen by the
    running build; the response does not tell them apart, since only the first needs an operator
    and telling it from the third would need a read the listing cannot afford per table.
21. **`fileId` = base64url of `ck1:<siteId>:<epoch>:<seq>:<table>`, unsigned, no table.** It names a
    version, so it keeps naming the listed object after the next build; the key is derived by the
    build's own key function. The epoch is in it because a wipe or a re-baseline restarts seq and
    the same key can later hold other data. Signing was weighed and not taken: resolution re-checks
    ownership, epoch, the table's row and the object, so a signature would protect nothing and add
    a secret whose rotation invalidates every id. #388 adds a second kind beside `ck1`.
22. **`/files` presigns for 60 s and answers 404 for every refusal with one body** (another account,
    a never-issued id, a discarded epoch, a gone object), so an id cannot be used to probe which
    sites or versions exist. Storage that will not say whether the object exists is 503, not 404.

The exact response bodies are in `docs/bitbi-integration.md` ("Bit BI API v2").

## Open questions for the client team

- ~~Is `track_duplicates` off on every DBF site?~~ Answered 2026-10-05: off by default; only a manual
  edit of `config.toml` turns it on, so the file is checked on each site as part of its move.
- Future Postgres/MSSQL client: does it send `row_hash` for tables with a primary key; does a change
  of a primary-key value arrive as an `UPDATE` or as `DELETE` + `INSERT`; does a Postgres table
  without a primary key use the same `row_hash` mode as DBF? **Deferred** (2026-10-05): both
  extractors are still in development and these decisions are not taken. Nothing in v2 waits for
  them: the `identity` field already covers both modes, and BitBi's sites are all DBF.

## Tickets

Phases 0–3 as tickets. Open-ticket overlap was checked by file name on 2026-10-01: none found,
except #380 (a batch whose Parquet enqueue was lost; in progress since 2026-10-05), which lives in the
batch-Parquet queue and limits the order guarantee `/changes` inherits.

| # | Ticket | Expected files | Migration / spec | Blocked by |
|---|---|---|---|---|
| A | Dev issues Parquet Export links as `https` | `k8s/overlays/dev/configmap-patch.yaml`, a manifest guard test | neither | — |
| B | Batches published before #378 are listed out of order on dev | decision first: leave and document, or re-stamp `ready_at` | V61 only if re-stamped | — |
| C | Bit BI v2 read surface: sites, state, schema with `identity` (**#386**, shipped); snapshot, file redirect (**#387**, shipped) | new `BitBiV2ApiController` + DTOs, `CheckpointFileQueryService`, `ApiRoutes`, `SecurityConfiguration`, `docs/bitbi-integration.md` | `specs/044-bitbi-parquet/` | — |
| D (**#388**) | Bit BI v2 changes feed with `afterSeq`, `epoch` and the per-batch table manifest | `ParquetExportCatalogDao`, `ParquetExportCatalogQuery`, `Batch`, the v2 controller | **V61** (epoch stored with the batch, seq index) | C; #380; B if it takes V61 |
| E (**#389**) | Pilot: one dev site (`fyt-new`) through both surfaces, daily comparison | no product code; a runbook in `docs/` | neither | D, deploy tag |

B and D both want V61, so they must not run in parallel. C and A are independent of everything.
