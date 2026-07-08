# Feature Specification: Delta Parquet Download in the UI

**Feature Branch**: `feature/025-delta-parquet-download` (stacked on `feature/024-visual-language-migration`)
**Created**: 2026-07-07
**Status**: Implemented (T1–T4)
**Input**: User request: «Добавь в UI ссылку на скачивание паркет файла» — the per-segment delta
Parquet files produced by the egress worker (022, Task 8) had no download path in the UI; only
the full checkpoint Parquet did (023).

## User Scenarios & Testing

### User Story 1 - Download a session's delta Parquet per table (Priority: P1)

A site owner opens the Batch Detail of a completed Delta v2 session and downloads the typed
delta Parquet file for any table in the session — one click per table, no S3 console access.

**Independent Test**: open a completed delta batch of a V2 site with declared schemas → the
"Table changes" card shows a **Parquet** pill per row → click downloads the file
(`egress/{siteId}/{table}/delta/seq={first}-{last}.parquet`, valid Parquet magic `PAR1`).

**Acceptance Scenarios**:

1. **Given** a completed delta session whose segment was egressed, **When** the owner clicks a
   table's Parquet pill, **Then** a fresh presigned URL (15 min) is minted per click and the
   download starts in the same tab (popup-safe), with a success toast.
2. **Given** a table without a declared schema (never egressed), **When** the pill is clicked,
   **Then** a 404 is translated into an explanatory toast (schema/egress), not a generic error.
3. **Given** any other failure (S3 outage → 503, auth, network), **Then** a generic retry toast
   is shown — never the schema explanation.
4. **Given** a session still IN_PROGRESS, **Then** no pills render (files may not exist yet).

## Requirements

- **FR-1**: REST endpoint (owner) presigns one table's delta Parquet of a batch:
  `GET /api/v1/account/sites/{siteId}/delta/batches/{batchId}/tables/{tableName}/parquet`.
  404 when the batch has no changelog segment (filtered by siteId) or the file does not exist;
  403 for a foreign site. URLs minted per click, never cached.
  _(The admin twin was descoped 2026-07-08: batch detail has no admin surface — clicks from
  /admin/sites land on a UserOnlyGuard route — so the endpoint and the `admin` prop were
  unreachable dead code. Re-add together with an admin batch-detail page.)_
- **FR-2**: UI — per-table Parquet pill in the delta Batch Detail "Table changes" card
  (completed sessions only), with an in-flight guard.
- **FR-3**: Storage failures during exists/presign MUST NOT surface as 500 — they map to
  503 Service Unavailable; no DB connection may be held across the S3 round-trips.
- **FR-4**: One shared presign-then-download flow for checkpoint and batch downloads
  (`features/delta-sync/lib/downloadPresigned.ts`); one shared batch-status→pill mapping
  (`features/upload-history/model/batchStatus.ts`).

## Out of Scope

- Combining tables into a single Parquet file (impossible in-format; a ZIP-of-files "download
  all" endpoint discussed as a possible follow-up).
- Owner-facing segments listing (P2 — untouched).
- Admin batch-detail navigation and the admin presign twin (descoped 2026-07-08, see FR-1;
  admin site-detail still links to the owner route).

## Success Criteria

- **SC-1**: Contract tests cover owner 200, both 404 modes, foreign-site 403 — green.
- **SC-2**: End-to-end verified on a live backend: click → presigned URL → valid Parquet bytes.
- **SC-3**: Per-task gates green (backend `./gradlew test -PexcludeIntegration`, frontend
  `npm --prefix frontend test`).
