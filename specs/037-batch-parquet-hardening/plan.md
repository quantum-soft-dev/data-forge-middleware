# 037 — Batch Parquet hardening plan

Issue #99 tightens the completed-batch Parquet pipeline introduced by 036 without changing its
REST contract or manifest schema.

## Design

1. Completion enqueue runs after the ingestion transaction commits, then wakes the worker. Lazy
   backfill remains the recovery path if the post-commit callback fails. When any legacy segment
   has `stats IS NULL`, enqueue discovers its tables from the segment records and finalization
   treats the aggregate row count as unknown instead of rejecting a complete replay.
2. Admin batch deletion delegates to a transactional application service. Unified artifact key
   derivation moves to the delta domain, so batch presentation/application code does not import
   delta S3 infrastructure. Database deletion of artifact rows, changelog segments, and the batch
   is one transaction; S3 cleanup remains best effort.
3. The retry query excludes expired `BUILDING` rows whose attempts are already spent. A bulk
   settle query abandons those rows before the next claim, removing the scan limit that could
   postpone real work behind sixteen stranded rows.
4. The finalizer computes aggregate stats once. The configured temp-file ceiling is enforced by
   the file-backed writer while bytes are written and is terminal on the first attempt, because a
   retry cannot make the same deterministic artifact smaller.
5. A superseded uploader still writes the stable logical object key. Changing that layout safely
   requires attempt-scoped objects plus orphan reclamation and is intentionally split into a
   follow-up #100 rather than folded into this bounded hardening pass.

## Compatibility

- No REST, gRPC, DTO, metric, cache, route, configuration-key, or database-schema change.
- `delta.batch-parquet.max-temp-bytes` keeps its existing name and default.
- Existing manifest rows and stable S3 keys remain readable and cleanable.

## Test strategy

- Unit: listener phase/order; table discovery and unknown legacy counts; bounded writer and
  deterministic abandon; controller delegation and transactional deletion ordering.
- Integration: repository bulk-settle/query boundary and a queue with more than sixteen spent
  claims still reaches retryable work in the same drain.
- Gates: `./gradlew test -PexcludeIntegration` before every code commit and
  `./gradlew integrationTest` before the PR.
