# Implementation Plan: Batch-per-Session Semantics for Delta Client v2 Ingestion

**Branch**: `029-batch-per-session` | **Date**: 2026-07-27 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/029-batch-per-session/spec.md`

## Summary

Redefine a Batch as one client upload attempt (one Delta v2 gRPC session) instead of one ~100-record storage slice. Continuous-mode segment sealing stays as the storage-durability mechanism but no longer completes/opens batches: N segments commit under the session's single batch, S3 keys become per-segment, `SessionEnd` completes the batch once (empty tail batches disappear), and `BATCH_COMPLETED` fires once per session. The 60-minute batch timeout for V2 streaming batches switches to last-activity-based (new nullable `batches.last_activity_at`, V40) so a live long session survives while a silent one still frees the site. Upload History list view aggregates per-batch totals across segments via a grouped query; detail view already aggregates. Per-segment egress and plugin queues are untouched.

## Technical Context

**Language/Version**: Java 25 (LTS), Spring Boot 3.5.6
**Primary Dependencies**: grpc-java (`DeltaIngestionService` on :9090), Spring Data JPA, AWS SDK v2 (S3), Flyway 11
**Storage**: PostgreSQL 16 (`batches`, `changelog_segments`), S3/LocalStack (segment blobs, egress Parquet)
**Testing**: JUnit 5 + Mockito (unit), MockMvc (contract), Testcontainers PostgreSQL + LocalStack (integration)
**Target Platform**: Linux server (GKE), local dev via docker-compose
**Project Type**: single backend project (no frontend changes — DTO shape unchanged)
**Performance Goals**: ingest hot path unchanged or cheaper (removes per-100 `completeBatch`+`startBatch` pair; adds 1 UPDATE per ≥100 records for activity touch)
**Constraints**: forward-only migration V40 (additive, nullable); no gRPC proto change; no REST DTO shape change; staged-resume mechanism preserved as-is
**Scale/Scope**: sessions up to millions of records → thousands of segments per batch; list aggregation must be SQL-side, not in-memory

## Constitution Check

Constitution intentionally empty; CLAUDE.md Development Policy applies (verified):
- [x] Feature branch `029-batch-per-session` off `develop`
- [x] Spec-driven: spec.md with Clarifications (product decisions from design discussion)
- [x] TDD task-by-task, WIP=1, per-task gate `./gradlew test -PexcludeIntegration`, before-PR gate `integrationTest`
- [x] Migration numbering: current V39 → this feature is **V40**
- [x] Docs required before merge: `docs/cr-batch-per-session.md`
- [x] No API forking: behavior change inside existing v2 surface (strangler rule N/A)

## Project Structure

### Documentation (this feature)

```text
specs/029-batch-per-session/
├── plan.md              # This file
├── research.md          # Phase 0: verified current behavior + decisions D1–D7
├── data-model.md        # Phase 1: V40, query changes, invariants
├── quickstart.md        # Phase 1: verification recipe
├── contracts/
│   └── session-batch-contract.md   # Phase 1: session↔batch behavioral contract
└── tasks.md             # Phase 2 (/tasks output)
```

### Source Code (repository root)

```text
src/main/java/com/bitbi/dfm/
├── delta/
│   ├── presentation/DeltaIngestionService.java      # seal no longer cycles batches; activity touch; no tail batch
│   ├── application/DeltaSessionCommitService.java   # split: commitSegment(...) vs session-end completion
│   ├── application/ChangelogSegmentService.java     # persist with per-segment S3 key
│   └── infrastructure/S3ChangelogSegmentStorage.java# uploadSegment keyed by segmentId
├── batch/
│   ├── domain/Batch.java                            # lastActivityAt field + touch method
│   ├── domain/BatchRepository.java                  # findExpiredBatches → COALESCE(last_activity_at, started_at)
│   ├── infrastructure/JpaBatchRepository.java
│   ├── application/BatchLifecycleService.java       # touchActivity(batchId) (bounded-cadence update)
│   ├── application/BatchTimeoutScheduler.java       # unchanged logic, new query semantics
│   └── application/BatchHistoryService.java         # list view: grouped per-batch segment aggregation
│   └── presentation/dto/BatchSummaryDto.java        # fromProjection(+aggregate) instead of (+single segment)
├── delta/domain/ChangelogSegmentRepository.java     # aggregate projection query (SUM, DISTINCT tables)
└── resources/db/migration/V40__batch_last_activity_and_segment_batch_index.sql

src/test/java/
├── delta/        # ingestion unit tests + BatchPerSessionIngestionTest (integration)
├── batch/        # scheduler + history aggregation unit tests
└── integration/  # end-to-end suite additions
```

**Structure Decision**: single backend project, DDD package-by-feature as per CLAUDE.md; all changes confined to `delta/` and `batch/` aggregates plus one Flyway migration. No frontend work (DTO contract stable).

## Phase 0 → 1 outputs

- research.md: current-behavior verification (code + production logs) and decisions D1–D7 (seal/lifecycle split, per-segment S3 keys, `last_activity_at` + COALESCE sweeper, SQL-side list aggregation, no rollover for perpetual sessions).
- data-model.md: V40 DDL, sweeper and aggregation queries, invariants.
- contracts/session-batch-contract.md: observable session↔batch obligations (gRPC behavior, REST DTO semantics, plugin events).
- quickstart.md: automated + manual verification, rollback notes.

## Complexity Tracking

No constitution violations; no added projects, patterns, or surfaces. Net code movement is a simplification of the ingestion lifecycle (removes the seal-time batch cycling).

## Progress Tracking

- [x] Phase 0: research.md complete, no NEEDS CLARIFICATION remaining
- [x] Phase 1: data-model.md, contracts/, quickstart.md complete
- [ ] Phase 2: tasks.md (produced by /tasks, not by /plan)
