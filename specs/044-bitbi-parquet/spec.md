# 044 — Bit BI plugin v2: Parquet files instead of generated SQL

**Design**: [`docs/cr-bitbi-parquet-plugin.md`](../../docs/cr-bitbi-parquet-plugin.md)
**Issues**: phase 1 — [#386](https://github.com/quantum-soft-dev/data-forge-middleware/issues/386)
(sites, state, schema), [#387](https://github.com/quantum-soft-dev/data-forge-middleware/issues/387)
(snapshot, file redirect); phase 2 — [#388](https://github.com/quantum-soft-dev/data-forge-middleware/issues/388)
(changes feed); pilot — [#389](https://github.com/quantum-soft-dev/data-forge-middleware/issues/389)
**Status**: phase 1 part 1 implemented (#386); the rest open

## Problem

Bit BI mirrors a site from a checkpoint download plus SQL the server renders from changelog
segments. The SQL is expensive to produce, not idempotent (a re-delivered batch corrupts the mirror,
repaired only by `reinit`), and the source of a whole class of defects (#215, #233, #240, #263,
#370). The server already writes the same changes as typed Parquet per table per completed batch,
in batch order, with `_op`, `_seq`, `_changed` and `_row_hash`.

## Goal

A second, versioned API surface of the same `bit-bi` plugin from which a consumer mirrors a site
out of those Parquet files and holds its own watermark `(epoch, seq)`. v1 keeps working unchanged
beside it.

## Contract

Under `/api/v1/plugins/bit-bi/v2`, authenticated by the `bit-bi` activation's
`X-Plugin-Api-Key`, rate-limited by the per-account bucket v1 uses:

| Route | Phase | Returns |
|---|---|---|
| `GET /sites` | 1 (#386) | The account's active sites |
| `GET /sites/{siteId}/state` | 1 (#386) | `siteId`, `epoch`, `checkpointSeq`, `lastAppliedSeq`, `schemaVersion` |
| `GET /sites/{siteId}/schema` | 1 (#386) | `siteId`, `schemaVersion`, per table `table`, `identity`, `primaryKey`, `uniqueKeys`, `columns` |
| `GET /sites/{siteId}/snapshot` | 1 (#387) | One checkpoint Parquet per table at `checkpointSeq` |
| `GET /files/{fileId}` | 1 (#387) | `302` to a short-lived presigned URL |
| `GET /sites/{siteId}/changes?afterSeq=&cursor=&size=` | 2 (#388) | Completed batches above `afterSeq`, in batch order, each with its table manifest |

Rules the phase-1 routes hold:

- `epoch` = `site_sync_state.baseline_epoch`: moved by a history wipe and by a re-baseline, never by
  anything else, never sent to the ingestion client. `generation` is not it.
- `identity` = `row_hash` when the declared `primaryKey` is empty (every DBF table), otherwise
  `primary_key`. Derived, not stored.
- `schemaVersion` in `/state` and `/schema` comes from one source, `site_schemas.schema_version`;
  `0` when no schema was submitted.
- A site of another account answers `404` with the body of an absent site. An unknown path under
  `/v2` answers `401` without a valid key and `404` with one (#384).
- `429` carries `Retry-After` and the standard error body.

Exact bodies: `docs/bitbi-integration.md`, "Bit BI API v2".

## Out of scope

Retiring `/sql-changes` (phase 5, after a sunset that is not scheduled), an activation mode, an
on-demand snapshot or SQL render, per-segment latency (decisions 1–5 of the CR).

## Acceptance (phase 1, part 1 — #386)

- The three routes answer with the key of an active `bit-bi`; `401` without a key or with a revoked
  one; `404` on another account's site; `429` over the limit.
- Contract tests on the production security chain, full JSON through `WireJson`.
- `epoch` changes after a wipe and after a re-baseline — integration test.
