# 039 — Batch Parquet queue operations plan

Issue #98 adds the operational surface that the durable completed-batch Parquet queue has lacked
since feature 036.

## Design

1. A dedicated Micrometer `MeterBinder` registers one gauge per
   `BatchParquetArtifactStatus`. The gauge suppliers share one repository `COUNT(*) GROUP BY
   status` snapshot for at most five seconds, so a collection reads all statuses with one database
   round trip while remaining bounded-fresh.
2. `BatchParquetQueueService` owns the list and recovery use cases. Listing filters by both site
   and batch. Recovery selects the same tuple plus artifact id under a pessimistic row lock, then
   accepts only `ABANDONED` or `BUILDING` older than the configured lease.
3. The aggregate resets recovery state atomically: `PENDING`, attempt count zero, cleared claim
   token/error/published metadata, and a new `updated_at`. Existing claim-token checks make late
   work from the displaced attempt harmless.
4. The recovery transaction writes a site-scoped `AdminActionLog` with the previous status and
   artifact identifiers. V50 extends `chk_action_type` with `BATCH_PARQUET_REQUEUE`.
5. `DeltaSyncAdminController` exposes an admin-only list and requeue route. The response DTO omits
   storage and claim internals and includes the fields needed to diagnose a stuck artifact.

## Test strategy

- Unit: gauges are registered for every status, share one grouped query, default absent statuses
  to zero, and refresh after the bounded snapshot TTL.
- Unit: aggregate recovery clears all mutable attempt/publication state and rejects unrelated
  states; service recovery enforces the expired-lease rule, route tuple, locking, and audit data.
- Contract/integration: admin can list and requeue an abandoned or expired-building artifact;
  live/unrecoverable states return `409`, route mismatch returns `404`, user/anonymous access is
  rejected, persistence and audit rows match the response, and Prometheus exposes every status.
  A real PostgreSQL concurrency test holds the recovery row lock against a stale worker lease
  touch, while a rollback test proves the reset and audit row commit atomically.
- Gates: `./gradlew test -PexcludeIntegration` before each code commit and
  `./gradlew integrationTest` before opening the PR.

## Documentation surfaces

- Needed: `docs/cr-unified-batch-parquet.md`, `docs/delta-client-v2-guide.md`, `docs/README.md`,
  `CLAUDE.md` endpoint table/recent changes/migration number, OpenAPI annotations, and this spec.
- Not needed: protobuf/wire-contract docs, client plugin guides, frontend/UI docs, CI/deployment
  docs, and root `CHANGELOG.md`; none of those contracts change.
