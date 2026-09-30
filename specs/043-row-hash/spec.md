# 043 — Client row identity (`ChangeRecord.row_hash`)

**Issue**: [#369](https://github.com/quantum-soft-dev/data-forge-middleware/issues/369)
**Client counterpart**: dbf-data-extractor #156 (hash stability), #157 (sending it)
**Client CR**: [`documents/cr-row-hash-server.ru.md`](https://github.com/quantum-soft-dev/dbf-data-extractor/blob/develop/documents/cr-row-hash-server.ru.md) in `dbf-data-extractor`
**Documentation follow-up**: [#373](https://github.com/quantum-soft-dev/data-forge-middleware/issues/373)
**Status**: implemented

## Problem

The client computes a SHA-256 of each DBF row's canonical form — the key of its keyless diff,
kept in its `state.db`. The server and the data consumers (Bit BI, Parquet Export) have no stable
identifier of a row within a table: a keyless table's identity is the whole row
(`ChangelogFold.identityOf`), and Parquet carries only `_op`/`_seq`/`_changed`.

## Contract

`ChangeRecord.row_hash = 7` (`bytes`), opaque to the server:

- exactly 32 bytes; the row identity is `(table, row_hash)` — unique within a table in the client's
  normal mode, **not** under `[ingestion.keyless] track_duplicates = true`, where one hash names a
  row's content and N copies go out as N `INSERT`s with it (CR §3.2); the server never relies on
  uniqueness;
- `INSERT` — the row's hash; `DELETE` — the value its `INSERT` carried; `UPDATE` — undefined, empty;
- a row inserted before the field existed has no hash on the server while its `DELETE` may carry
  one (CR §2, §3.3), so a `DELETE` is matched by `key` and its hash is not a matching condition;
- identical in `DELTA`, `FULL_SNAPSHOT`, `CONTINUOUS`;
- empty = absent (older client, older segments and frames) — accepted as before;
- any other length is dropped at ingestion with one WARN per session; the session commits;
- not part of `content_hash`;
- additive: `key` stays authoritative (fold identity, delta `DELETE` rows, Bit BI SQL `WHERE`).

## Server behaviour

| Surface | Behaviour |
|---|---|
| Ingestion | `RowHash.isMalformed` → `RowHash.dropMalformed`, one WARN per session |
| Segment | carried byte for byte (`ChangelogCodec` writes the message as is) |
| Fold | `FoldedRow.rowHash`: set by the creating record, kept by `UPDATE`, gone with `DELETE`; +72 bytes in `estimatedRetainedBytes` for a row that has one |
| Frames | `CheckpointFrame` (fold), `CheckpointFrameWriter` (streamed bootstrap and merge), `ChangelogMerge` (patched → frame row's hash; replaced/recreated/new → the delta row's) |
| Parquet | trailing nullable `_row_hash` string, 64 lowercase hex — delta file, batch file, checkpoint `snapshot.parquet` |
| Bit BI SQL | unchanged |

## Decisions (owner, 2026-09-29)

1. Parquet form: lowercase hex string (as the client shows it), not `fixed(32)`.
2. In `snapshot.parquet` from the start, not only in delta/batch files.
3. Wrong length: ignore with WARN, do not fail the session (no `ErrorCode` for it).

Taken during implementation and confirmed by the owner on 2026-09-29 (#373): `_row_hash` is
**appended** after the declared columns rather than placed beside `_op`/`_seq`/`_changed` as CR §4.5
sketches, so no column a positional reader knew moves. The CR is to be corrected on the client side.

## Out of scope

- Folding by `row_hash` (identity = hash instead of the full row).
- Bit BI SQL addressing rows by `_row_hash` (needs DDL in the mirror tables).
