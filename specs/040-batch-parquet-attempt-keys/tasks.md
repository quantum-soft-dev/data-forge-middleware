# 040 — Tasks

WIP = 1. Tasks run strictly in order. Every code task starts with a failing test, finishes with
`./gradlew test -PexcludeIntegration` fully green, and lands as one atomic Conventional Commit
referencing #100.

- [x] **T01 — Design.** Record the takeover race, token-scoped key layout, lifecycle reclamation,
  legacy compatibility boundary, documentation surfaces, and test strategy in `spec.md`,
  `plan.md`, and this task ledger.
- [x] **T02 — Attempt-key policy and storage.** Add failing domain/storage tests, then implement
  deterministic batch prefixes and claim-token object keys in `BatchParquetArtifactKey` and require
  a claim token in `S3CheckpointStorage.uploadBatchParquet`. Keep legacy stable-key derivation for
  compatibility cleanup.
- [x] **T03 — Winning-claim publication.** Add failing finalizer unit and LocalStack concurrency
  coverage that pauses the old upload, reclaims the lease, publishes the successor, and completes
  different old bytes last. Then pass claim tokens into uploads, delete stale attempt keys safely,
  and prove manifest bytes/size/SHA-256 belong to the winner.
- [x] **T04 — Lifecycle reclamation.** Add failing retention/deletion/site-wipe tests for an
  unreferenced attempt object and a legacy stable row. Then add paginated generic prefix listing,
  enumerate batch prefixes during retention and after committed explicit deletion, preserve exact
  fallback cleanup on list failure, and verify the existing site-prefix wipe covers attempts.
- [ ] **T05 — Documentation and pre-PR gates.** Update the unified Parquet CR, Delta guide, docs
  index, project journal, and completed task ledger; verify documentation consistency; run
  `./gradlew test -PexcludeIntegration` and `./gradlew integrationTest` before opening the PR.
- [ ] **T06 — Review and synchronization.** Resolve every review finding test-first with one atomic
  commit per review task, record outcomes on the PR, merge current `origin/develop`, check Flyway
  numbering, rerun all applicable gates and CI, and stop at `status: ready to merge`.

## Dependencies and execution order

- T02 establishes the key API consumed by T03.
- T03 proves upload/publication isolation before T04 broadens lifecycle cleanup.
- T04 completes the acceptance criteria before T05 documents shipped behavior.
- No tasks are parallelized: repository policy requires WIP=1, and these tasks share the same
  finalization/lifecycle surfaces.
