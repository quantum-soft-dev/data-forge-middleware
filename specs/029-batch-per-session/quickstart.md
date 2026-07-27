# Quickstart: Verifying Batch-per-Session (029)

## Automated

```bash
./gradlew test -PexcludeIntegration    # per-task gate (unit + contract)
./gradlew integrationTest              # before-PR gate (Testcontainers: PostgreSQL + LocalStack)
```

Key suites (added by this feature):
- `delta/BatchPerSessionIngestionTest` (integration): CONTINUOUS session streaming 250 records / 2 tables → exactly 1 COMPLETED batch, 3 segments, aggregated history row `250 changes · 2 tables`, no empty tail batch, per-segment egress artifacts exist before SessionEnd.
- `batch/BatchTimeoutSchedulerTest` (unit): activity-based cutoff matrix (live-old, silent-old, v1-legacy).
- `batch/BatchHistoryServiceTest` (unit): list aggregation over multi-segment batches + legacy single-segment batches.

## Manual smoke (dev profile)

1. `docker-compose up postgres localstack && ./gradlew bootRun --args='--spring.profiles.active=dev'`
2. Stream a CONTINUOUS session with >100 records via the Delta v2 client (or the gRPC test harness in `src/test/.../delta/support`).
3. Check:
   - Upload History shows **one** row for the run with the true total (not slices of 100, no `0 files · 0 B` row).
   - `changelog_segments` has N rows sharing that `batch_id`, S3 keys `delta/{siteId}/segments/{segmentId}.pb.gz`.
   - `batches.last_activity_at` advances during the stream.
   - Exactly one `Dispatching BATCH_COMPLETED` log line per session.
4. Timeout: open a session, stream a few records, kill the client without SessionEnd, wait past `batch.timeout-minutes` (dev override it lower) → batch becomes NOT_COMPLETED, site accepts a new session.

## Rollback

Behavioral change only + additive V41 (nullable column, index) — safe to roll the app image back; old code ignores `last_activity_at` and reads segments' stored `s3_key` values, so mixed data (old and new key formats, multi-segment batches) stays readable. Multi-segment batches created by 029 will render in old code's list view with one segment's count (the pre-029 undercount bug) — acceptable for rollback windows.
