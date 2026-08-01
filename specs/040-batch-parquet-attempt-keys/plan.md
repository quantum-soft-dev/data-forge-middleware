# 040 — Batch Parquet attempt keys plan

**Branch:** `plissb/issue-100` | **Date:** 2026-08-01 | **Spec:** `spec.md`

Issue #100 removes the remaining physical-object race in completed-batch Parquet finalization while
keeping the manifest and download contracts backward compatible.

## Summary

Each claim uploads to an immutable token-scoped object below the logical batch prefix. Publication
stores that exact key only if the claim still owns the manifest row; a stale finisher deletes only
its own key. Batch retention and explicit deletion enumerate the batch prefix after database work,
while site wipe retains its existing paginated site-prefix walk. Existing stable-key rows remain
opaque recorded keys and require no migration.

## Technical context

**Language/version:** Java 25  
**Primary dependencies:** Spring Boot 3.5.6, Spring Data JPA, AWS SDK v2  
**Storage:** PostgreSQL 16 manifest rows and S3-compatible object storage  
**Testing:** JUnit 5, Mockito, Testcontainers PostgreSQL, LocalStack S3  
**Target platform:** Linux service pods with concurrent queue workers  
**Performance goal:** No extra listing on the upload/download hot path; prefix enumeration occurs
only during lifecycle deletion.  
**Constraints:** WIP=1, test-first, no schema/API/config change, no production-code commit until the
per-task backend gate is green.

## Policy check

- Work stays on the Conductor-created branch; the spec directory uses the next free number, 040.
- Each code task starts with a focused failing test and lands as one atomic commit referencing #100.
- No Flyway migration is needed; `s3_key` already stores an opaque key up to 1000 characters.
- The behavior and lifecycle contract are documented in the existing unified-Parquet CR and Delta
  client guide before the PR.
- The full backend gate runs before every code commit; integration tests run before the PR and again
  after synchronization with `develop`.

## Design

### 1. Key policy and compatibility

`BatchParquetArtifactKey` keeps the legacy stable derivation for fallback cleanup and adds:

- batch prefix: `egress/{siteId}/batches/{batchId}/`;
- attempt key:
  `egress/{siteId}/batches/{batchId}/attempts/{claimToken}/{encodedTable}.parquet`.

The random claim token, rather than `attempt_count`, is the uniqueness boundary because operator
requeue resets the count to zero. Table encoding remains byte-for-byte compatible with the current
policy. `S3CheckpointStorage.uploadBatchParquet` requires the claim token and returns the exact
attempt key it wrote. Download continues to trust the recorded `s3_key`, so legacy root-level
stable keys need no rewrite or alternate read path.

### 2. Winner publication and stale cleanup

The finalizer passes each `Claim.token` into its upload. `publish` retains the existing row/token
check before `markReady`; therefore only the current claim can attach an attempt key to the
manifest. When a stale claim has uploaded a file, it deletes that exact attempt key best-effort.
The missing-row path retains the same behavior. A transaction/commit ambiguity after publication
does not trigger eager deletion, because the row may already be READY; later prefix cleanup owns
that orphan-safe case.

The takeover integration test makes the two physical uploads different: the old uploader pauses
after producing its temp file, a successor reclaims and publishes, then the old test uploader
changes its own file bytes and completes last. A shared stable key would fail the checksum
assertion; token-scoped keys preserve the winner and allow the stale key to be deleted.

### 3. Deterministic lifecycle discovery

`S3FileStorageService` gains paginated prefix enumeration as the generic cleanup capability.
Batch retention carries the successfully deleted candidates' batch prefixes out of the database
phase, enumerates them, merges those keys with recorded/legacy fallback keys, and performs its
existing best-effort bulk delete. Explicit batch deletion captures the batch/site prefix before
removing rows and enumerates it in the existing after-commit cleanup callback. Listing errors are
reported/logged but never roll database deletion back; exact recorded or legacy-derived fallback
keys still go through deletion.

The prefix catches current winners, stale uploads, and process-death orphans without a new table or
scheduled reaper. A late upload racing deletion is still safe when its process continues: its
publication sees the removed row and deletes its unique key. Site-history wipe already paginates
all of `egress/{siteId}/`, so only regression coverage/documentation changes there.

## Test strategy

- Domain/storage unit tests: legacy key unchanged; tokens produce distinct URL-safe attempt keys;
  upload uses the supplied token; prefix derivation is stable.
- Finalizer unit tests: the claim token reaches storage, only the winning token publishes, and a
  stale completed attempt deletes only its own returned key.
- Concurrency integration: force lease takeover with the old upload completing last and different;
  assert the READY object's bytes, length, and SHA-256 match the winning manifest and the stale
  object is absent.
- Lifecycle unit/integration: retention and explicit deletion enumerate the batch prefix, preserve
  exact-key fallback on list failure, and delete unpublished attempts; site wipe removes an attempt
  object through its existing site-prefix walk; a legacy stable READY row remains cleanable.
- Gates: `./gradlew test -PexcludeIntegration` before code commits and
  `./gradlew integrationTest` before PR, followed by both again after merging `origin/develop`.

## Project structure

```text
specs/040-batch-parquet-attempt-keys/
├── spec.md
├── plan.md
└── tasks.md

src/main/java/com/bitbi/dfm/
├── delta/domain/BatchParquetArtifactKey.java
├── delta/infrastructure/S3CheckpointStorage.java
├── delta/application/BatchParquetFinalizationService.java
├── batch/application/BatchRetentionService.java
├── batch/application/BatchDeletionService.java
└── upload/infrastructure/S3FileStorageService.java

src/test/java/com/bitbi/dfm/
├── delta/domain/BatchParquetArtifactTest.java
├── delta/infrastructure/S3CheckpointStorageBatchArtifactTest.java
├── delta/application/BatchParquetFinalizationServiceTest.java
├── batch/application/{BatchRetentionServiceTest,BatchDeletionServiceTest}.java
└── integration/{BatchParquetFinalizationIntegrationTest,BatchRetentionIntegrationTest,
    SiteHistoryWipeIntegrationTest}.java
```

## Documentation surfaces

- Needed: `docs/cr-unified-batch-parquet.md`, `docs/delta-client-v2-guide.md`, `docs/README.md`,
  `CLAUDE.md` Recent Changes, and feature 040 spec/plan/tasks.
- Not needed: OpenAPI/REST tables (no endpoint or error change), protobuf/wire-contract docs (no
  gRPC change), plugin/client guides (no consumer workflow change), frontend/UI docs (frontend
  untouched), README/application configuration (no key/default change), migration journal (no
  migration), CI/deployment docs, and root `CHANGELOG.md` (frozen by policy).

## Complexity tracking

No policy exception. Prefix listing reuses an existing S3 lifecycle pattern and avoids a new
database entity, migration, scheduler, or public contract.
