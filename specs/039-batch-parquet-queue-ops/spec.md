# 039 — Batch Parquet queue operations

## Problem

The durable `batch_parquet_artifacts` queue has user-visible terminal state, but operators can
only infer individual build outcomes from counters. They cannot see current queue depth or recover
an artifact that reached `ABANDONED` without direct SQL against versioned claim state.

## Requirements

- Export one `delta.batch-parquet.queue` gauge for every artifact status. Each gauge reads the
  current database count at scrape time and carries a `status` tag.
- Add an admin-only endpoint that lists a site's batch artifacts with their id, table, status,
  attempt count, last error, and timestamps. S3 keys, checksums, and claim tokens remain internal.
- Add an admin-only endpoint that requeues one artifact belonging to the site and batch in the
  route. It resets `ABANDONED`, or a `BUILDING` claim whose lease has expired, to `PENDING` with
  zero attempts and no claim token, error, or published metadata.
- Reject an unknown or route-mismatched artifact as `404`; reject `PENDING`, `FAILED`, `READY`,
  and a live `BUILDING` claim as `409`.
- Serialize recovery with worker state transitions by locking the artifact row. A late worker
  lease touch or publication must fail its existing claim-token check after recovery.
- Record every committed recovery as `BATCH_PARQUET_REQUEUE` in `admin_action_logs`, including
  artifact id, batch id, table name, and previous status.
- Owner routes and the download contract remain unchanged.

## REST contract

- `GET /api/v1/sites/{siteId}/delta/batches/{batchId}/parquet-artifacts`
  returns `200` and a table-name-sorted array (empty when the batch has no manifest rows).
- `POST /api/v1/sites/{siteId}/delta/batches/{batchId}/parquet-artifacts/{artifactId}/requeue`
  returns `200` with the reset projection, `404` for an unknown/mismatched row, and `409` when
  the current state is not recoverable.
- Both routes require `ROLE_ADMIN`; a regular user receives `403` and an unauthenticated caller
  receives `401`.

## Compatibility

- V50 only widens the existing `admin_action_logs.action_type` check constraint; it does not
  rewrite artifact rows.
- No gRPC, protobuf, owner REST, frontend, S3-key, query-key, or worker scheduling change.

