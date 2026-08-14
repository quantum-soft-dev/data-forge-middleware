# 041 — Parquet Export lists one file per table per batch

**Feature Branch**: `feature/041-parquet-export-batch-files`
**Created**: 2026-08-14
**Status**: Draft
**Issue**: #109
**Input**: Parquet Export clients still consume per-segment delta files even though the server
already materializes one completed-batch Parquet per table (036–040).

## Problem

A large Delta v2 snapshot (example: site `fyt-new`, ~5M changes, 87 tables) is sealed into
hundreds of segments. Realtime egress then fans each segment out per table, so the plugin catalog
returns thousands of files. The client registers thousands of one-time links and concatenates
them, while `batch_parquet_artifacts` already holds exactly one file per table for that session.

Those artifacts are only reachable through the owner Auth0 route
`GET /api/v1/account/sites/{siteId}/delta/batches/{batchId}/tables/{table}/parquet`. The plugin
catalog (`ParquetExportCatalogDao`) reads `changelog_segments` and `checkpoints` only, and
`type` accepts `delta` | `checkpoint`.

## User Scenarios & Testing

### User Story 1 — List one batch file per table (Priority: P1)

A Parquet Export client lists files with `type=batch` (or with no `type`, which now means the
same thing) and receives one catalog row per `READY` or `ABANDONED` artifact, ordered and
filtered by the artifact's ready time.

**Why this priority**: this is the product default and the only way the client stops pulling
thousands of segment slices.

**Independent Test**: seed `READY` artifacts and list `/files` with no `type` and with
`type=batch`; both return those rows with `type=batch`, `batchId`, seq range, filename
`{table}_batch{batchId}.parquet`, and a one-time `downloadUrl`.

**Acceptance Scenarios**:

1. **Given** a `READY` artifact for table `orders`, **When** the client lists `/files` without
   `type` or with `type=batch`, **Then** the row has `type=batch`, `status=ready`,
   `batchId`, `seq=null`, `firstSeq`/`lastSeq` from the artifact (null when never stored),
   `producedAt=ready_at`, and a registered download URL.
2. **Given** `PENDING`/`BUILDING`/`FAILED` rows for the same batch, **When** the client lists,
   **Then** those rows are absent.
3. **Given** `type=delta` or `type=checkpoint`, **When** the client lists, **Then** the existing
   segment/checkpoint sources are queried and batch artifacts are not.

### User Story 2 — Abandoned artifacts are visible (Priority: P1)

A table that exhausted `DELTA_BATCH_PARQUET_MAX_ATTEMPTS` must not vanish. The client sees
`status=abandoned` with no download URL and can alert an operator to requeue.

**Why this priority**: silent loss of a table's batch is worse than a missing file the client
can report.

**Independent Test**: seed an `ABANDONED` artifact; listing returns it with `status=abandoned`,
`downloadUrl=null`, `linkExpiresAt=null`, and no `download_links` row.

**Acceptance Scenarios**:

1. **Given** an `ABANDONED` artifact, **When** listed, **Then** `producedAt` is `updated_at`,
   `status` is `abandoned`, and no one-time link is registered. Clients must surface this row
   even when `lastSeq <= applied_seq`.
2. **Given** a mixed page of `READY` and `ABANDONED` rows, **When** the client walks `nextCursor`,
   **Then** every row is returned exactly once.

### User Story 3 — Default listing is batch-only (Priority: P1)

A request with no `type` used to return `delta` + `checkpoint`. It now returns only batch files.
Existing integrations that still want segments must send `type=delta`.

**Why this priority**: product decision (13.08.2026). The plugin API is unversioned; the new
default is the behaviour operators need.

**Independent Test**: an account with both egressed segments and `READY` artifacts receives only
the artifacts when `type` is omitted, and still receives segments when `type=delta`.

### User Story 4 — Late-ready and requeued artifacts reappear (Priority: P2)

`since` and sort order use `ready_at` (or `updated_at` for abandoned), never the batch close
time. An artifact that finishes after retries, or that an operator requeues (039) and that later
becomes `READY`, must appear in the next sweep.

**Why this priority**: a watermark based on batch completion would permanently hide late files.

**Independent Test**: list with `since=T0`; then set the same artifact's `ready_at` to `T1>T0`
(as after requeue + rebuild); a second list with the same `since` returns it again.

**Acceptance Scenarios**:

1. **Given** a `READY` artifact whose `ready_at` is after the client's `since`, **When** listed,
   **Then** it is included even if the batch closed earlier.
2. **Given** a row with `first_seq`/`last_seq` NULL, **When** listed, **Then** the catalog
   emits `lastSeq: null` and does not query `changelog_segments`. Clients must never skip
   a file whose `lastSeq` is null.

## Edge Cases

- Intermediate statuses never appear; they surface later as `READY` (or `ABANDONED`).
- Abandoned rows have no S3 object; the cursor still needs a unique key — use the synthetic
  `abandoned/{artifactId}` so keyset pagination stays total-ordered.
- A site not owned by the account yields an empty page (existing account-scope join).
- Unknown `type` remains HTTP 400 (`delta`, `checkpoint`, or `batch`).
- Retention still deletes artifacts with the batch; the guide must warn that the client poll
  interval has to be shorter than the retention window.

## Requirements

- **FR-001**: `GET /api/v1/plugins/parquet-export/files` accepts `type=batch` and, when `type`
  is omitted, queries only batch artifacts.
- **FR-002**: Batch rows come from `batch_parquet_artifacts` with status `READY` or `ABANDONED`.
- **FR-003**: Sort and `since` use `ready_at` for `READY` and `updated_at` for `ABANDONED`.
- **FR-004**: Response adds `batchId`, `artifactId` and `status` (`ready` | `abandoned`). `seq` is null.
- **FR-005**: `READY` rows get a one-time download link. `ABANDONED` rows do not.
- **FR-006**: Filename is `{table}_batch{batchId}.parquet`.
- **FR-007**: V51 adds nullable `first_seq`/`last_seq` and partial catalog indexes for the
  READY `(ready_at, s3_key)` and ABANDONED `(updated_at, 'abandoned/' || id)` listing branches.
- **FR-008**: The writer stores the table's seq range on `markReady` and on `markAbandoned`
  when the range is known. A NULL stored range is emitted as `lastSeq: null`; the catalog
  does not query `changelog_segments`. Clients must never skip a file whose `lastSeq` is null.
- **FR-009**: `type=delta` and `type=checkpoint` keep their current sources and pagination.
- **FR-010**: Requeue that later reaches `READY` is listed again because `ready_at` is new.
- **FR-011**: Materialization, S3 keys, workers, owner/admin delta routes, and one-time link
  consume semantics are unchanged.

## Success Criteria

- **SC-001**: A session with N tables produces at most N catalog rows in the default listing,
  not one row per (segment × table).
- **SC-002**: An abandoned table is visible to the plugin client.
- **SC-003**: Existing clients that add `type=delta` keep receiving segment files.
- **SC-004**: Per-task unit/contract gate and the integration suite are green.

## Assumptions

- Seq watermarks stay site-global; a batch-wide fallback range is a correct skip watermark
  because the file contains every record of that table for the batch.
- Filename uses the batch UUID (there is no integer batch number).
- No `type=all`; combining sources is no longer a default or a supported filter value.
- Frontend is unchanged (plugin listing is Basic Auth, not a UI screen).

## Out of scope

- Changing artifact materialization, claim tokens, S3 layout, or retry policy.
- Owner/admin download routes.
- Shortening batch-file latency (session must close first).
- Changing retention.
- A new plugin API version.
