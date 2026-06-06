# Development Documentation — Delta Client v2 (022)

**Status**: Planning (not started)
**Branch**: `feature/022-delta-client-v2` → squash-merge into `develop`
**Design source**: [docs/cr-delta-client-v2.md](../../docs/cr-delta-client-v2.md) · contract: [src/main/proto/delta-ingestion.proto](../../src/main/proto/delta-ingestion.proto)
**Task list**: [tasks.md](./tasks.md)

This is the working document for the feature: the approach, the technologies, and a pointer to the detailed checkbox task list. The full design rationale lives in the CR — this file does **not** duplicate it.

---

## Approach

We follow the repository **Development Policy** (see `CLAUDE.md` → "Development Policy"):

- **Test-first (TDD)** for every unit of work: study → decide design → write the test set (red) → implement until green. If the approach changes mid-task, **delete obsolete tests and write new ones**; the bar is "adequately covered + 100% green".
- **Serial, WIP = 1**: one subtask at a time. Do not start the next until the current one is committed.
- **One atomic commit per subtask** (Conventional Commit referencing the subtask id), then tick its checkbox in `tasks.md`.
- **Gates**: per-commit `./gradlew test -PexcludeIntegration` (+ `npm --prefix frontend test` if touched) must be green (pre-commit hook enforces it); `./gradlew integrationTest` before the PR; CI `backend-test` green to merge.
- Each unit of work is small enough to be one red→green→commit cycle.

## Technologies

| Area | Choice |
|---|---|
| Transport | gRPC bidirectional streaming + Protobuf (`spring-grpc` / `grpc-java`), HTTP/2 + TLS |
| Auth | Auth V2 device-flow Bearer token in gRPC metadata (reuse `deviceauth`) |
| Lifecycle | Session = Batch (reuse `BatchLifecycleService`) |
| Persistence | Spring Data JPA + Flyway **V29** (`site_sync_state`, `changelog_segments`, `checkpoints`); reuse `site_schemas` |
| Storage | AWS SDK v2 S3 — raw changelog segments (bronze), CSV + Parquet checkpoints, Parquet change-feed (gold) |
| Parquet writer | TBD: `parquet-mr` vs Apache Arrow (decide in Task 5) |
| Tests | JUnit 5 + Mockito (unit), in-process gRPC server (contract), Testcontainers PostgreSQL + LocalStack (integration) |

## Architecture (summary)

Changelog is the source of truth; periodic **checkpoints** materialize full snapshots (model **2b**). A full snapshot is just an all-`INSERT` changelog frame. One fold → three projections: legacy CSV (Bit BI), Parquet change-feed (Power BI), re-baseline. See CR §1–§4 and the interaction diagrams CR §8.

## Scope

In/out of scope and non-goals: see [CR §Scope](../../docs/cr-delta-client-v2.md). Out: lakehouse, online point-queries, forcing legacy sites off the old path immediately.

## Blocking decisions (resolve before Task 1)

- **OQ-1** — do keyless tables contain byte-identical duplicate rows? (decides whether a row-multiplicity counter is needed)
- **OQ-3** — how a site is marked "Delta v2 ingestion" (reuse `site_type` / add `ingestion_protocol` flag / infer from first gRPC session)

## How to use the task list

`tasks.md` groups work into **Tasks** (coherent units, ≈ the CR phases). Each Task contains **subtasks as checkboxes** — one checkbox = one test-first cycle = one atomic commit. Tick a checkbox **after** its commit lands. A Task is done when all its subtasks are checked.
