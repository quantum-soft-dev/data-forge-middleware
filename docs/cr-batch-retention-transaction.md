# CR: batch retention deletes in a real transaction, and keeps a site's only baseline (issue #344)

## What was wrong

Batch retention — the nightly `BatchRetentionScheduler` pass (`batch.retention.cron`, default
`0 0 2 * * *`, overridable from the admin settings) and the manual
`POST /api/v1/batches/cleanup` (`BatchCleanupAdminController`) — **had never deleted a batch in
production** since `d4ae8ca4` (2026-02-08). `BatchRetentionService.cleanupSiteInDb` carried
`@Transactional`, but it was `protected` and invoked on `this`, so the Spring proxy never ran and no
transaction existed. The first bulk delete (`deleteByComparisonBatchId`) then threw
`TransactionRequiredException`, the per-batch catch recorded it, and the pass reported
`deletedBatches=0, errors=N` — every night, for every expired batch:

```
Failed to cleanup batch in DB: batchId=…, error=Executing an update/delete query
Retention cleanup completed: candidates=1, deletedBatches=0, deletedFiles=0, deletedBytes=0, errors=1
```

Batches, uploaded files, changelog segments, plugin SQL generations and batch Parquet artifacts
therefore accumulated without bound, and the "outer horizon" of the queues' retry that #212
describes (`delta.retention.segments.deleted-pending`) never fired. The candidate query's
`FOR UPDATE SKIP LOCKED` ran without a transaction too, so its lock was released as soon as the
statement returned. The integration tests could not see it: both carried `@Transactional` on the
method, and the test's own transaction stood in for the missing one — the #164 shape
(`SqlGenerationPersistence`) exactly.

## What changes

**One transaction per batch, in a bean of its own.** `BatchRetentionTransaction`
(`batch/application`) owns the database phase — the #147 / #164 shape: a separate bean because a
`@Transactional` method invoked on `this` is not proxied.

- `deleteBatch(siteId, batchId, cutoff)` — `@Transactional`. It first re-locks the batch with the
  candidate predicate (`BatchRepository.lockCleanupCandidate`, `FOR UPDATE SKIP LOCKED`), so the
  lock is held until the rows are gone; a batch that another pass is deleting, or that stopped
  being a candidate since the listing, is skipped (neither deleted nor an error). Then it deletes
  plugin SQL generations, batch Parquet artifact rows, changelog segment **rows**
  (`deleteMetadataByBatchId`) and the batch, and returns every object key.
- `describeBatch(siteId, batchId)` — `@Transactional(readOnly = true)`, the dry run's read.
- Both refuse to run **without** an active transaction (`IllegalStateException`), so a future
  self-invocation fails naming the wiring instead of as `TransactionRequiredException`.

**Per batch, not per site**, deliberately: a failing batch is recorded in the summary and the pass
moves on, and inside one site-wide transaction that cannot work — after the first SQL error
PostgreSQL refuses every statement until the transaction ends, so one bad batch would roll the
whole site back. The cost is one short transaction per deleted batch (at most
`batch.retention.cleanup-limit`, 1000, per pass), the same trade #234 made for changelog retention.

**`BatchRetentionService` is a non-transactional orchestrator.** It lists the candidates, runs one
`BatchRetentionTransaction` per batch, and deletes a site's objects in one batched `DeleteObjects`
after all of that site's transactions have committed — rows first, objects after, so a failure in
between leaves an unreferenced object rather than a row naming a missing one. `runCleanup` refuses
to run inside a caller's transaction: the batch transactions would join it instead of committing on
their own, and it would hold its connection across the object deletes. Two consequences:

- **Segment objects now leave after the commit.** `ChangelogSegmentService.deleteByBatchId` used to
  delete each segment object inside the (would-be) transaction; they now join the batch's other
  keys. `deletedFiles` therefore counts them, in a real pass and a dry run alike.
- **`delta.retention.segments.deleted-pending` is counted after the commit**, so a batch whose
  transaction rolls back is never a phantom loss on that series (#212 R2-4, now by construction).

## Historical v1 upload batches are kept while they are a site's only baseline (owner decision 1)

The first working pass would otherwise have deleted **every** v1 upload batch: HTTP ingestion was
removed on 2026-07-28 (#032), so all of them are older than the default `retention_days` (45). For
a site with no checkpoint, those uploaded CSVs are the only baseline the Bit BI files API can serve
(`GET /api/v1/plugins/bit-bi/sites/{siteId}/files`, `CheckpointFileQueryService`'s fallback to
historical uploads). The rule, part of the one candidate predicate
(`JpaBatchRepository.CLEANUP_CANDIDATE_PREDICATE`):

> retention does not take a batch that has `uploaded_files` rows while its site has **no**
> `checkpoints` row.

Once the site gets a checkpoint the fallback is closed for it (#113) and its v1 batches are ordinary
candidates. A plugin baseline batch (`account_plugins.baseline_batch_id`) stays excluded as before.
The listing, the per-batch lock and the dry run all use this one predicate, so `dryRun` reports
exactly what a real pass would take.

## Rollout — with a safety catch (owner decision 2)

The first working night deletes the whole accumulated backlog of expired batches (up to
`batch.retention.cleanup-limit` = 1000 per pass; further nights continue). Retention cannot be
switched off from the admin UI — `BatchRetentionScheduleService` validates the cron with
`CronExpression.parse`, and `-` does not pass — so the cron is parked on a date that never comes.
On every environment, in order **test → stage → prod**:

1. **Before the deploy**, park the schedule:
   `PUT /api/v1/admin/settings/batch-retention-schedule` with cron `0 0 0 31 2 *` (31 February),
   and confirm in the backend log that `Retention cleanup scheduler configured` shows it.
2. Deploy.
3. **Dry run**: `POST /api/v1/batches/cleanup` with `{"dryRun": true}` (optionally `siteId` /
   `accountId` / `limit`). Read `candidates`, `deletedFiles`, `deletedBytes`, and make sure no v1
   batch of a site without checkpoints is among the candidates.
4. Restore the schedule: `PUT … batch-retention-schedule` with `0 0 2 * * *`.

## Other self-invoked `@Transactional` in `batch/application`

Checked, **no change needed**: `BatchLifecycleService` is `@Transactional` at class level and its
internal `getBatch` (readOnly) runs under the caller's read-write transaction;
`BatchTimeoutScheduler.checkExpiredBatches`, `BatchRetentionScheduleService.updateCron`,
`BatchDeletionService.deleteBatch` and `BatchHistoryService` are only entered through their
proxies.

## Contracts

No REST route, DTO shape, gRPC/proto, migration (V58 stays free), configuration key, metric name,
S3 key or frontend change. The meaning of `deletedFiles` widens to include changelog segment
objects (they are deleted by this pass as before, and are now counted).

## Tests

- `BatchRetentionIntegrationTest` — no `@Transactional` on any method; `runCleanup` is called as the
  scheduler calls it. Red on the old code with the production error. Covers: an expired batch with
  its uploaded file, error log, Parquet artifacts, a changelog segment and their objects; the plugin
  baseline; a v1 batch of a site without checkpoints (kept) and with one (deleted); the dry run
  applying the same rule; a batch locked by another transaction (skipped, then deleted once
  released); refusal inside a caller's transaction.
- `BatchRetentionTransactionTest` — the no-transaction refusal, the proxyable shape of both entry
  points, the skip under the lock, the delete order and returned keys, the read-only describe.
- `BatchRetentionServiceTest` — the orchestrator: dry run, rows before objects, skipped and failing
  batches, prefix-listing fallback, best-effort S3 errors, the #212 counter only after a commit.
- Mutations: dropping `@Transactional` from `deleteBatch` reddens four integration tests and the
  proxy-shape unit test; replacing the checkpoint clause of the predicate with `TRUE` reddens the
  v1-kept and dry-run integration tests.
