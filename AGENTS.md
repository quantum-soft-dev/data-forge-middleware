# data-forge-middleware Development Guidelines

## Tech Stack

### Backend
- **Java 25** (LTS) + **Spring Boot 3.5.6** + **Spring Security 6** (Auth0 OAuth2)
- **Spring Data JPA** + **PostgreSQL 16** (partitioned tables) + **Flyway 11**
- **AWS SDK v2** (S3) + **HikariCP** + **Micrometer** + **SpringDoc OpenAPI 3**
- **Auth0 2.26.0** (Management API) + **Hypersistence Utils** (JSONB)
- **Redis + Caffeine** (Spring Cache) + **Bucket4j** (rate limiting) + **Spring Retry**
- **Apache POI / commons-csv / commons-compress** + **java-diff-utils** (file diff) + **json-schema-validator**
- **JUnit 5 + Mockito + Testcontainers** (PostgreSQL + LocalStack S3)

### Frontend
- **React 19.2** + **TypeScript 5.6** + **Vite 5.4**
- **TanStack Query v5** + **TanStack Router v1** (code-based routes in `src/app/router.tsx`) + **shadcn/ui** + **Tailwind CSS 3.4**
- **@auth0/auth0-react 2.8.0** + **Axios** + **Zod** + **React Hook Form**
- **Vitest + React Testing Library**

## Project Structure

```
src/main/java/com/bitbi/dfm/
├── account/              # Account aggregate (multi-tenant root)
├── site/                 # Site aggregate + SiteType/SiteSchema (DBF, POSTGRES_CDC)
├── batch/                # Batch aggregate (upload sessions), retention, history
├── upload/               # File upload domain + schema upload
├── error/                # Error logging (partitioned)
├── auth/                 # Client JWT authentication (v1)
├── deviceauth/           # OAuth 2.0 Device Authorization Flow + refresh tokens (Auth V2)
├── device/               # Client API v2 controllers (/api/v1/device/**)
├── comparison/           # File comparison / diff visualization (Myers)
├── plugin/               # Plugin system (Bit BI integration)
├── settings/             # App settings (admin-configurable, app_settings)
├── config/               # Security, cache, async, metrics, OpenAPI configuration
└── shared/               # Cross-cutting concerns (events, exceptions, api routes)

src/main/resources/db/migration/   # Flyway SQL migrations
src/test/java/{contract,integration,[domain]}/
```

## Commands

```bash
./gradlew build                           # Build
./gradlew bootRun --args='--spring.profiles.active=dev'  # Run dev
./gradlew test                            # All tests (unit + contract + integration) — used by CI
./gradlew test -PexcludeIntegration       # Unit + contract only (fast, no Docker) — per-task gate
./gradlew integrationTest                 # Testcontainers integration suite only — before-PR gate
./gradlew flywayMigrate                   # Apply migrations
docker-compose up postgres localstack     # Start dependencies
```

## Architecture

### DDD (Package by Layered Feature)
- **Layers per domain**: `domain/` (aggregate, value objects, repository interface, events) → `application/` (services, schedulers) → `infrastructure/` (`Jpa*` repo impl, S3) → `presentation/` (controllers + `dto/`)
- **Aggregates**: Account, Site, Batch, ErrorLog, Plugin, Comparison, DeviceAuthorization, AppSetting
- **Value Objects**: JwtToken, FileChecksum, SiteCredentials, BatchStatus, SiteType, TableSchema
- **Events**: AccountDeactivatedEvent, BatchStartedEvent, PluginActivatedEvent
- **Repository Pattern**: Interface in domain, JPA in infrastructure

### Authentication
- **Device API** (`/api/v1/device/**`): OAuth 2.0 Device Authorization Flow with access + refresh tokens. It provides authorization and surviving metadata/read operations; ingestion uses Delta gRPC.
- **Retired client API** (`/api/dfc/**`): no controllers or authentication chain remain; requests are denied.
- **Admin API** (`/api/v1/**`): Auth0 OAuth2 (ROLE_ADMIN/ROLE_USER)
- **Plugin API** (`/api/v1/plugins/bit-bi/**`): API key via `X-Plugin-Api-Key` header; per-account rate limiting (Bucket4j token bucket)

### User Types
1. **Admin Users** (ROLE_ADMIN): Pure Auth0 users, NO PostgreSQL Account record
2. **Regular Users** (ROLE_USER): Auth0 + PostgreSQL Account (bidirectional via `identity_provider_user_id`)

### Database Patterns
- **Partitioning**: error_logs, plugin_audit_logs (monthly range)
- **Soft Delete**: isActive flag on accounts/sites
- **N+1 Prevention**: JOIN FETCH in @Query annotations
- **Cursor Pagination**: For large datasets (batches, audit logs)
- **JSONB**: site_schemas, plugin metadata, comparison diffs (Hypersistence Utils)
- **Caching**: Spring Cache (Redis + Caffeine) via `config/CacheConfiguration` (e.g. batch history)

### Site Types & Schemas (019)
- **SiteType** (immutable per site): `DBF` (full CSV snapshots, server diffs) | `POSTGRES_CDC` (CSV baseline + JSONL deltas)
- **site_schemas** (JSONB, one per site): columns, types, `primaryKey`, `uniqueKeys`. Required before first batch for CDC sites.

### Business Rules
- One active batch per site (query check)
- Max 5 concurrent batches per account
- 60-minute batch timeout (scheduled task)
- Retention cleanup schedule is configurable by admins (cron via `/api/v1/admin/settings/batch-retention-schedule`)

## Code Style

### Java
- **Records**: Immutable DTOs and value objects
- **Lombok**: `@Getter`, `@NoArgsConstructor` for JPA entities
- **Explicit types**: Avoid `var`
- **Optionals**: Return from repos, avoid in parameters

### Naming
- Controllers: `{Domain}Controller` (client), `{Domain}AdminController` (admin)
- Services: `{Domain}Service` (application layer)
- Repositories: `{Entity}Repository` interface, `Jpa{Entity}Repository` impl
- DTOs: Java records with `fromEntity()` factory methods

### Testing
- **Unit**: Mock dependencies, `shouldDoSomethingWhenCondition()`
- **Integration**: Testcontainers (PostgreSQL + LocalStack)
- **Contract**: MockMvc endpoint verification

## Development Policy

_This file is the single source of dev rules (the spec-kit constitution is intentionally unused/empty). The policy below is **mandatory** and applies equally to humans and AI agents working in this repo._

### Rule 1 — Feature branches
- Every feature is developed on its own branch `feature/NNN-name`, **branched off `develop`**.
- A feature lands via a **Pull Request into `develop`**, merged with **squash** (one feature = one squashed commit on `develop`).
- A feature **must be documented** in `docs/` (a `docs/cr-*.md` change request and/or feature guide). Undocumented features are not merge-ready.

### Rule 2 — Test-first (TDD), task-by-task, serial
- A feature is split into ordered tasks in **`specs/NNN-name/tasks.md`** (use `/tasks`).
- Work tasks **strictly one at a time (WIP = 1)**. Do **not** start the next task until the current one is committed.
- For each task, follow **test-first**:
  1. **Study** the task and decide the approach/design before writing code.
  2. **Write the test set first** — it expresses the intended behavior and starts **red** (failing).
  3. **Implement**, iterating until **all tests are green**.
  4. **Tests track the decision, not frozen.** If you change the approach mid-task, **delete the obsolete tests and write new ones** — never keep tests that no longer reflect the chosen design.
  5. **Bar:** the task must be **adequately covered** by tests (its behavior, edge cases, and failure modes), and **100% green**.
  6. Commit **one atomic commit per task** (Conventional Commit referencing the task), e.g. `feat(batch): add retention scheduler (T03)`.
- **Gate — per-task tests must be 100% green** before committing (enforced by the pre-commit hook):
  - backend → `./gradlew test -PexcludeIntegration` (unit + contract; fast, no Docker)
  - frontend → `npx tsc --noEmit` (from `frontend/`) + `npm --prefix frontend test`
- "100% green" = **all tests pass**, not 100% code coverage.

### Gates summary
| Gate | When | Must be green |
|---|---|---|
| **Per-task** (commit) | before every commit | `./gradlew test -PexcludeIntegration` (+ frontend `tsc --noEmit` and `vitest` if touched) |
| **Before PR** | before opening the PR | `./gradlew integrationTest` (Testcontainers) |
| **Merge** (PR → develop) | before merge | full CI (`backend-test`) green + automated review |

### Enforcement
- **git pre-commit hook** (`.githooks/pre-commit`) runs the per-task gate and blocks red commits. Enable once per clone: `git config core.hooksPath .githooks`. Bypassing (`--no-verify`) is against policy.
- **CI required checks**: the `backend-test` job (`.github/workflows/ci-cd.yml`, runs `./gradlew test`) must be a **required status check** on PRs to `develop` (configure in GitHub branch protection).

### Conventions
- **Spec-driven**: each feature → `specs/NNN-name/` (spec → plan → tasks). Skills: `/specify`, `/plan`, `/tasks`, `/implement`, `/analyze`, `/clarify`. Larger design changes → `docs/cr-*.md`.
- **Conventional Commits**: `feat(scope):`, `fix(scope):`, `chore:`, `ci:`, `docs:`.
- **Migrations (Flyway)**: forward-only, sequential `V{N}__description.sql`; never edit an applied migration; backward-compatible defaults for new NOT NULL columns. Current at **V52**, next is **V53**. `MigrationDocumentationConsistencyTest` derives these values from the migration filenames and guards both agent instruction files against drift; Gradle tracks the docs and migration directory as test inputs, and the pre-commit hook runs the focused guard for agent-doc-only or migration-only changes.
- **API evolution (strangler)**: add a versioned surface alongside the old one, reusing the same application services; deprecate the old with a sunset, migrate clients, then remove it. Do **not** fork a separate service or duplicate the domain/persistence layer.

## Key Implementation Patterns

### Auth0 Integration
- **Account creation**: Auth0-first with compensating transaction (delete Auth0 user if DB fails)
- **Custom claims**: `https://api.dataforge.com/roles`, `https://api.dataforge.com/accountId`
- **M2M token caching**: 24h TTL with 1h buffer, thread-safe refresh

### Error Handling
```
400 - IllegalArgumentException, validation errors
403 - AccessDeniedException, wrong token type
404 - NoHandlerFoundException
500 - Generic exceptions
```

### Plugin System
- **Plugin interface**: `Plugin.getId()`, `validateConfig()`, `onActivate()`, `onDeactivate()`
- **BitBiPlugin**: SQL generation from CSV uploads, API key auth
- **API key**: Generated on activation, BCrypt hashed, returned once only
- **Audit logs**: All plugin actions logged to partitioned table with JSONB metadata

#### Plugin Action Types
| Type | Description |
|------|-------------|
| `ACTIVATE` | Plugin activated for account |
| `DEACTIVATE` | Plugin deactivated |
| `SQL_GENERATION_STARTED` | SQL generation began for batch |
| `SQL_GENERATION_COMPLETED` | SQL generation finished (includes stats: insertCount, updateCount, deleteCount) |
| `SQL_GENERATION_FAILED` | SQL generation error (includes errorMessage) |

#### User-Facing Plugin Logs
- **Endpoint**: `GET /api/v1/account/plugins/{pluginId}/logs`
- **Filters**: `siteId`, `from`, `to` (ISO 8601), `page`, `size` (max 100)
- **Frontend**: Logs tab in My Plugins widget (Dashboard) with site filter, date range, page size selector
- **Data**: Action type, success/failure status, error messages, SQL generation statistics, siteId, siteDomain
- **Security**: Excludes sensitive data (IP, user agent, client IDs)

#### User-Facing Batch SQL Status
- **Endpoint**: `GET /api/v1/account/plugins/{pluginId}/batches`
- **Filters**: `siteId`, `page`, `size` (max 100)
- **Frontend**: SQL tab in My Plugins widget (Dashboard) with site filter, page size selector
- **Data**: Batch info, site domain, SQL generation status (isBaseline, hasSql, generationId)

### Bit BI Plugin API Endpoints
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/plugins/bit-bi/sites` | GET | List sites for account |
| `/api/v1/plugins/bit-bi/sites/{siteId}/files` | GET | List CSV files for site (for initialization) |
| `/api/v1/plugins/bit-bi/sites/{siteId}/files/{fileName}` | GET | Download CSV file (proxy from S3) |
| `/api/v1/plugins/bit-bi/sql-changes` | GET | Get SQL changes (params: siteId, since) |
| `/api/v1/account/plugins/{pluginId}/logs` | GET | Plugin activity logs (user-facing) |

### Bit BI Plugin Initialization Flow
1. **Activation/Reinit**: `baseline_batch_id` is set to the latest completed batch
2. **Baseline batch**: Client downloads CSV files via `/sites/{siteId}/files` endpoint (no SQL generated)
3. **Subsequent batches**: SQL deltas generated (INSERT/UPDATE/DELETE compared to previous batch)
4. **First batch after activation (no history)**: Becomes the baseline batch automatically

### Global Error Handling (016)
- **Device API**: `POST /api/v1/device/errors` with optional `severity` field (CRITICAL, ERROR, WARNING, INFO)
- **User API**: Auth0 OAuth2 with accountId claim
- **ErrorLog**: severity (enum), isRead (boolean) fields added
- **Dashboard**: GlobalErrorsWidget with unread badge (30s polling)

#### Global Error API Endpoints
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/account/errors` | GET | List global errors (params: page, size, unreadOnly) |
| `/api/v1/account/errors/unread-count` | GET | Get unread error count for badge |
| `/api/v1/account/errors/{errorId}` | GET | Get global error details |
| `/api/v1/account/errors/{errorId}/read` | PATCH | Mark single error as read |
| `/api/v1/account/errors/mark-as-read` | POST | Mark multiple errors as read (body: errorIds[]) |
| `/api/v1/account/errors/mark-all-as-read` | POST | Mark all errors as read |

### S3 File Storage
- **Path**: `{accountId}/{domain}/{date}/{time}/{filename}`
- **Plugins**: `plugins/{pluginId}/{accountId}/{siteName}/{datetime}.sql`
- **Retry**: 3 attempts, fixed 1s delay
- **Presigned URLs**: 15-minute expiry for downloads

### Frontend (Feature-Sliced Design)
```
features/{feature}/api/     # API client, queries, mutations
features/{feature}/model/   # Types
features/{feature}/ui/      # Components
widgets/{feature}/          # Container components
pages/{feature}/            # Route pages
```

## Known Limitations

1. Test coverage ~16% (legacy code untested). Note: a jacoco 80% verification task is declared but **not wired into the build gate**.
2. Rate limiting only on the Plugin API (per-account, Bucket4j); no global rate limiting yet
3. S3 single region only
4. In-memory batch counting (not atomic across instances)
5. Basic retry logic (no exponential backoff)

## API Documentation

- Swagger UI: `/swagger-ui.html`
- OpenAPI spec: `/v3/api-docs`

## Active Technologies
- Java 25 (LTS) + Spring Boot 3.5.6, Spring Security 6 (Auth0 OAuth2), Spring Data JPA, AWS SDK v2 (S3)
- gRPC + Protobuf (Delta Client v2 ingestion, port 9090) (022-delta-client-v2)
- PostgreSQL 16 (partitioned `error_logs` table), Flyway 11 (016-global-error-handling)
- PostgreSQL 16: `site_schemas` (JSONB), `device_authorizations`, `app_settings` tables (019, Auth V2)
- Migrations current at **V52**; next migration is **V53** (do not reuse numbers)

## Recent Changes
- split-scratch-ceilings: The checkpoint scratch ceiling is two keys, and the deployed values sit below the volume so the app refuses before kubelet evicts (issue #138). Since #126 one key governed two files with opposite failure semantics: an oversized per-table snapshot is skipped (`delta.checkpoint.tables.unmaterialized{reason=parquet_failed}`, repaired by #128's rematerialize), an oversized reload frame **ends the build** because it is the next incremental seed — so the key had to be set for the harsher of the two and #131 left all ceilings at 10 GiB above its 6Gi `parquet-scratch` `emptyDir`, making a kubelet **eviction** the first thing that happens. New `delta.checkpoint.max-frame-temp-bytes` (`DELTA_CHECKPOINT_MAX_FRAME_TEMP_BYTES`) bounds the frame alone and, unset, inherits `delta.checkpoint.max-temp-bytes` (10 GiB by default), so an unset key behaves as before even for an operator who had lowered the single key; `delta.checkpoint.max-temp-bytes` keeps its per-table meaning and `delta.batch-parquet.max-temp-bytes` keeps its name, default and per-file scope. The application **defaults did not move** — a process cannot know how big its scratch directory is, so the deployed values live beside the volume in `k8s/base/configmap.yaml` (the split #141 used for `scratch-private-to-pod`), and they are the sizing note's worst case solved for 6Gi: `2 x max(table 1Gi, frame 2Gi) + max-concurrent 2 x batch 1Gi = 6Gi`. `ParquetScratchCeilingBudgetTest` recomputes it from the manifests, requires the frame ceiling to be the wider of the two, fails closed if the temp dirs and the mount drift apart, and fails if an overlay redefines any side. The batch term assumes one claimed table per build, so it is a floor on the guarantee, not the budget: a real build opens one file per claimed table, which only a directory-wide reservation can bound — filed as **#150**, not folded in. None of the three refusals repairs itself when the artifact is deterministically oversized: a table is skipped *and* detached (404s, retried nightly — #149), the frame aborts the build and freezes retention while orphaning a snapshot generation per night (**#153**), a batch artifact is ABANDONED on the first attempt (raise the key + admin requeue). Raise the frame ceiling first (two GiB of volume per GiB). No REST, gRPC, DTO, migration, metric, S3-key or frontend change. See `docs/delta-client-v2-guide.md` ("Sizing note").
- checkpoint-tick-work-list: The nightly checkpoint tick visits the union of the sites named by `changelog_segments` and the sites with a checkpoint row whose `s3_key_parquet` is null (issue #137). `CheckpointScheduler` walked the segment list alone, so a site pruned to nothing (`delta.retention.audit-window-segments=0`, or a table detached for longer than the default window of 20) was never revisited — even though since #128 a build rematerializes a detached snapshot from the frame with no segments at all. New `CheckpointRepository.findSiteIdsWithUnmaterializedCheckpoints()`; having checkpoints is deliberately not a reason to visit, only having an unmaterialized one is, and the set de-duplicates so a site on both lists is built once. `CheckpointService` untouched. No REST, gRPC, DTO, migration, configuration-key, metric, S3-key or frontend change. See `docs/delta-client-v2-guide.md`.
- scratch-pod-private-sweep: `ParquetScratchOrphanSweeper` also deletes scratch older than the running JVM where the directory is declared pod-private (issue #141). Since #131 the deployed scratch is an `emptyDir` with a `sizeLimit`, cleared only when the *pod* goes away, so a liveness/OOM kill mid-build parked one `batch-parquet-*` per claimed table on the volume for the full 4 h age window while the lease-expired claim allocated another set — and the penalty for filling it is a kubelet eviction. New key `delta.parquet.scratch-private-to-pod` (`DELTA_PARQUET_SCRATCH_PRIVATE_TO_POD`, default **false**, `"true"` in `k8s/base/configmap.yaml`) makes the cutoff the **later** of `now - age` and this JVM's start (truncated to whole seconds, since mtime can be second-resolution). The bound is the JVM start rather than "the first tick" because `resumePendingRebuilds()` can overlap it — an unconditional startup sweep would race a live writer. Default false keeps #127's rule intact: on a shared volume a file older than this JVM can belong to a live sibling, and the lease is not a bound on file age either way, so lowering the age globally is still not the fix. The reference is `ProcessHandle.current().info().startInstant()`, not the bean's construction, and startup logs the mode and the directories. A test parses both base manifests and enforces the one direction that is a safety property — flag set ⇒ both temp-dir keys name `/scratch/parquet` and the volume behind it is an `emptyDir` — so turning the flag off stays available as a rollback. Left open and ticketed as **#146**: the sweep tick shares one `TaskScheduler` thread with the all-sites checkpoint build. No REST, gRPC, DTO, metric, S3-key, migration, or frontend change.
- checkpoint-baseline-epoch: The checkpoint epoch guard covers a **re-baseline** as well as a wipe, and the event published after a build can no longer act on a history that is already gone (issue #142, folding #143). `DeltaRebaselineService.reset` deletes every `checkpoints` row and zeroes `last_checkpoint_seq` inside the FULL_SNAPSHOT `SessionEnd` commit, yet must leave `generation` alone — that is the wire epoch (035). Keyed on `generation` the guard saw nothing, the build restored the pre-re-baseline pointer, and the next build then seeded the fold from the **discarded** baseline's frame (`reset` leaves it in S3): rows deleted at the source reappeared in every checkpoint Parquet, silently. V52 adds `site_sync_state.baseline_epoch`, moved by a wipe **and** a re-baseline and never sent to the client; the guard compares the **pair** (`generation`, `baseline_epoch`), because neither subsumes the other during a rolling deployment — an old pod bumps `generation` alone. `CheckpointService.run` reads the sync state **before** the segment list (the other order let a reset committing in between pair the old data with the new epoch, so every guarded write was approved), and `reset` takes the `site_sync_state` row lock before it deletes anything. `CheckpointRecordedEvent` carries the epoch and `clearWipePending(siteId, generation, baselineEpoch)` scopes the take, closing #143 without the self-deadlock that publishing inside the guard transaction would cause (`DeltaWipeReinitListener` is synchronous + `REQUIRES_NEW`). Follow-up: #147 (the ingestion commit holds locks across the segment S3 upload). No REST, gRPC, proto, configuration-key, metric, S3-key or frontend change. See `docs/delta-client-v2-guide.md`.
- checkpoint-wipe-serialization: A checkpoint build that overlaps a site history wipe is discarded instead of restoring the epoch it was built for (issue #136, the row-side half of #122). `CheckpointService.buildCheckpoint` is non-transactional and runs from both the cron scheduler and the forced rebuild, so its writes could land after the wipe committed and restore a pre-wipe `last_checkpoint_seq`; retention then pruned the new epoch's segments as "below checkpoint" and the next build refused the lossy refold. `CheckpointEpochGuard.inEpoch(siteId, generation, write)` runs each build write (the `checkpointRepository.save` paths and `recordCheckpoint`) in a short transaction that takes the `site_sync_state` row lock the wipe holds and re-checks `generation`; a refusal escapes the per-table catch and ends the whole build with an empty fold. No S3 traffic inside the lock. No REST, gRPC, DTO, migration, configuration-key, metric, S3-key or frontend change. See `docs/delta-client-v2-guide.md`.
- parquet-scratch-budget: The deployment declares how much local disk the file-backed Parquet writers may use (issue #131) — manifests and documentation only, no application code. The backend container mounts a `parquet-scratch` `emptyDir` (`sizeLimit: 6Gi`) at `/scratch/parquet`, `DELTA_CHECKPOINT_TEMP_DIR` / `DELTA_BATCH_PARQUET_TEMP_DIR` point at it via `k8s/base/configmap.yaml` instead of the unbounded writable layer, and the container declares `ephemeral-storage` request == limit == 8Gi (6Gi scratch + ~2Gi logs/writable layer; Autopilot caps a pod at 10Gi). The request is what makes the scheduler reserve disk at all. The overlays need no patch: `dev`/`stage` patch `resources` through a strategic merge over maps, so the base `ephemeral-storage` survives (verified by rendering all three). `*_MAX_TEMP_BYTES` stay at 10 GiB and stay per-file, so the volume is the binding constraint and the failure mode is a kubelet **eviction**, not a graceful per-table skip — splitting the checkpoint ceiling so the app refuses first is #138. 6 GiB is an assumption, not a measurement; the worst-case formula lives in `docs/delta-client-v2-guide.md` ("Sizing note") — note the checkpoint term is `2 x` (the cron sweep and `deltaRebuildExecutor` are not mutually excluded), and orphans now outlive a container restart because an `emptyDir` is cleared only with the pod (#141). No API, DTO, migration, metric, S3-key or frontend change.
- wipe-prefix-sweep: The site-history wipe's post-commit prefix walk is bounded, resumable and honest (issue #122, consolidating #123 and #124). `S3PrefixLister` walks `ListObjectsV2` page by page and returns the pages already read plus `lastModified` and a truncation flag — `S3CheckpointStorage` and `S3FileStorageService` share it. The wipe skips objects whose S3 `LastModified` is in its own second or later on both `egress/{siteId}/` and `checkpoints/{siteId}/`. A prefix that could not be listed, or was listed only partially, is reported as `prefixesNotSwept` (distinct from `s3DeleteErrors`); the Danger zone tells the operator to repeat the wipe. `BatchDeletionService` and `BatchRetentionService` keep their complete-listing behaviour and fall back to recorded exact keys only on a truncated listing. No migration, configuration-key, gRPC, or S3-key change.
- checkpoint-rematerialize: A checkpoint table left without a snapshot is rematerialized from the existing frame, without waiting for new segments (issue #128). A scheduled build retries any row whose `s3_key_parquet` is null; a forced rebuild rematerializes every table from the frame (`rebuildFromFrame`). Neither path moves the pointer, re-uploads the frame, or publishes `CheckpointRecordedEvent`. A same-seq rematerialize that fails keeps a still-valid last-good key; after a full prune the frame is still enough. No API shape, DTO, migration, configuration, metric name or frontend change. See `docs/delta-client-v2-guide.md`.
- parquet-scratch-orphan-sweep: File-backed Parquet scratch left behind when a process dies between
  `createTempFile` and `finally` is swept by prefix and age (issue #127).
  `ParquetScratchOrphanSweeper` lists `delta.checkpoint.temp-dir` and
  `delta.batch-parquet.temp-dir` (deduplicated; they default to the same `java.io.tmpdir`),
  deletes regular files named `checkpoint-*` / `batch-parquet-*` whose last-modified time is
  strictly older than `delta.parquet.scratch-orphan-age-seconds`
  (`DELTA_PARQUET_SCRATCH_ORPHAN_AGE_SECONDS`, default **4 hours**), and runs at startup then
  every `delta.parquet.scratch-orphan-sweep-ms` (`DELTA_PARQUET_SCRATCH_ORPHAN_SWEEP_MS`, default
  1 hour). Frame scratch from #126 uses the same `checkpoint-` prefix, so it is swept too.
  Age is the only safe filter: a sibling replica may be writing into the same volume,
  and the batch-parquet lease is renewed for the life of a live build, so it is not a bound on
  file age. The writers still delete their own files on the happy path; this is the recovery
  for a persistent scratch volume. No REST, gRPC, DTO, metric, migration, or frontend change.
  See `docs/delta-client-v2-guide.md`, `docs/cr-unified-batch-parquet.md`.
- stream-checkpoint-frame: The checkpoint build streams the all-INSERT reload frame to a scratch file and uploads it with `RequestBody.fromFile` (issue #126). `CheckpointFrame.records` emits one `ChangeRecord` at a time, `ChangelogCodec.write` gzips to an `OutputStream`, `CappedOutputStream` applies `delta.checkpoint.max-temp-bytes` at the file, and `S3CheckpointStorage.uploadFrame` takes a `Path`. Crossing the ceiling aborts the build (the frame is the next seed); a single oversized table is still skipped. On-disk bytes are unchanged — `parse` still reads them. The site fold stays in heap. No API, DTO, proto, migration, configuration-key, meter, S3-key or frontend change. See `docs/delta-client-v2-guide.md`.
- order-dependent-test-flakes: Two `backend-test` flakes no longer depend on suite order or a one-second clock (issue #119). `BatchRetentionScheduleAdminControllerIntegrationTest` asserts the CONFIG fallback after `BaseIntegrationTest.clearAppSettings()` wipes the shared `app_settings` row (the table is not in `test-data.sql`). `SqlGenerationConcurrencyTest` holds the semaphore at the first statement after acquire and asserts `regenerateForBatch` via the queue gauge instead of `tryAcquire(1s)`. No API, DTO, migration, configuration or frontend change.
- checkpoint-parquet-on-disk: The V2 checkpoint build writes each table's snapshot to a scratch file and streams it to S3 (issue #112, absorbing #114), instead of encoding a whole table into a `byte[]`: `ParquetCheckpointWriter.writeParquet(Path, …)` takes an `Iterable` of rows, `S3CheckpointStorage.uploadParquet` takes a `Path`, and `CheckpointService` does write → upload → delete per table, so the peak of materialization is one row-group buffer plus one scratch file rather than one Parquet per table. An unusable scratch directory aborts the build rather than detaching every table's snapshot key (a write-time failure stays a per-table skip). The site fold stays in heap on purpose; the all-tables frame that used to sit beside it is file-backed as of #126. New config: `delta.parquet.row-group-bytes` (`DELTA_PARQUET_ROW_GROUP_BYTES`, default 16 MiB) for every V2 Parquet writer, and `delta.checkpoint.temp-dir` / `delta.checkpoint.max-temp-bytes` mirroring the batch-parquet keys; crossing the ceiling is counted as `delta.checkpoint.tables.unmaterialized{reason=parquet_failed}`. `FileOutputFile` and `ArtifactSizeLimitExceededException` are now package-level classes shared by both writers. No S3-key, meter-name, API, migration or frontend change.
- wipe-checkpoint-orphans: Site history wipe paginates `checkpoints/{siteId}/` after the database work (issue #118), because the `checkpoints` row is one per `(site, table)` and reused across builds — every superseded `seq=` snapshot was already unreferenced, and the `_frame/` reload frames were never referenced at all, so nothing else could remove them (dead bytes, not a stale read: a build overwrites the frame at the seq it ends on before advancing the pointer). Exact keys from the rows are kept as the fallback, each prefix is listed independently, and the key list is de-duplicated. The S3 delete phase is caught rather than thrown so a committed wipe never answers 500; every key handed to a failed phase is reported as `s3DeleteErrors` (a floor, not a census). Adds `S3CheckpointStorage.checkpointPrefix(siteId)`. No API shape, DTO, migration, configuration or frontend change. Follow-ups from review: #122 (no `lastModified` cut-off on the walk), #123 (under-reported sweep failures), #124 (a mid-pagination listing failure discards the pages already read).
- batch-parquet-idle-poll: An idle completed-batch Parquet poll no longer takes the cluster-wide `parquet-export-catalog-publish` advisory lock or moves `batch_parquet_catalog_watermark` (issue #115). `settleExpiredClaims()` reads `hasSpentExpiredClaims` (`SELECT EXISTS … LIMIT 1` over the existing partial claim index) first and opens the locked settle transaction only when there is something to abandon; when there is, `updated_at` is still stamped from the watermark under that lock, so `ABANDONED` ordering against `READY` siblings is unchanged. No API, migration, metric, or configuration change.
- checkpoint-csv-removal: The V2 checkpoint build no longer writes `snapshot.csv.gz` (issue #113) — typed Parquet is the only materialized format, and a table without a declared schema produces no artifact (counted as `delta.checkpoint.tables.unmaterialized`). Bit BI's `/api/v1/plugins/bit-bi/sites/{siteId}/files` serves `<table>.parquet` (breaking change for that client; the old `.csv.gz` name 404s), `CsvFileQueryService` → `CheckpointFileQueryService`, and owner/admin `checkpoints/{table}/download?format=csv` answers 410 Gone. `checkpoints.s3_key_csv` is kept — site wipe still deletes the objects earlier builds wrote (it is the only sweeper that reads them).
- 032-remove-client-api-v1: Retired `/api/dfc/**`, credential-based token issuance, V1-only branches, and HTTP multipart ingestion. V45 migrates stored sites to V2 and temporarily normalizes V1 writes from old pods during a rolling deployment. Historical uploaded CSV files remain readable through the Bit BI files API fallback.
- 029-batch-per-session: Batch = one Delta v2 ingestion session (see `docs/cr-batch-per-session.md`, `specs/029-batch-per-session/`). Continuous seals commit segments under the session's single batch (many segments : 1 batch; per-segment S3 keys `segments/{segmentId}.pb.gz`); no per-seal batch cycling, no empty tail batches; `BATCH_COMPLETED` once per session; Upload History list aggregates SUM(records)/DISTINCT tables SQL-side (`aggregateByBatchIds`); batch timeout for streaming batches counts from `batches.last_activity_at` (V41, touched at start/ack/seal) — the V2 sweeper exclusion is removed.
- 028-parquet-export-plugin: Parquet Export plugin (see `docs/parquet-export-plugin-guide.md`, `specs/028-parquet-export-plugin/`). Second Plugin SPI impl `parquet-export`: Basic Auth credentials minted at activation (login plaintext + BCrypt hash in plugin_data, shown once as `login:password`; rotation via `POST /api/v1/account/plugins/parquet-export/rotate-password`); `GET /api/v1/plugins/parquet-export/files` (Basic Auth, filters since/siteId/table/type=delta|checkpoint, per-account rate limit) registers one-time download links (`download_links`, V39; V40 unique login index); anonymous `GET /download/{token}` consumes atomically → 302 to ~60s presigned URL, then 410; purge scheduler; dedicated security filter chain (Order 4). Config `plugin.parquet-export.*`.
- 022-delta-client-v2: Delta Client v2 gRPC ingestion (see `docs/cr-delta-client-v2.md`, `docs/delta-client-v2-guide.md`, `specs/022-delta-client-v2/`). gRPC server on :9090 (`DeltaIngestionService`) — SessionStart/StreamChanges/SessionEnd/GetSyncState/SubmitSchema; the `delta/` aggregate (SiteSyncState, ChangelogSegment, Checkpoint), changelog fold + Parquet checkpoints (CSV removed by #113), event-driven per-segment delta Parquet egress. Migrations V29–V36. Sites default to `client_api_version = V2`.
- 025-delta-parquet-download: Per-batch delta Parquet download endpoints + UI pills (`BatchParquetDownloadService`, owner route `/api/v1/account/sites/{siteId}/delta/batches/{batchId}/tables/{table}/parquet`), documented in `docs/delta-client-v2-guide.md` ("Delta Parquet in the UI").
- 024-visual-language-migration: Entire frontend unified on the monitoring visual language (see `docs/cr-visual-language-migration.md`, `specs/024-visual-language-migration/`). Single token source `shared/ui/tokens.ts` (+ remapped shadcn CSS vars: `--primary` #3C82D8, `--radius` 10px; Tailwind semantic utilities `ink.*`/`brand.*`/`surface.*`/`hairline`/`separator`/`danger.*`/`warn.*`/`shadow-panel`). All `shared/ui/ui/*` primitives restyled (Badge = alpha pills `info|neutral|success|warning|critical|stalled` + `dot`; Button incl. `destructive-outline`/`compact`; hairline Table without uppercase headers; site-detail Tabs treatment as default; new `shared/ui/page-header.tsx`). Old language mechanically absent (grep audits in `specs/024-…/quickstart.md` = 0 hits). Visual-only: no API/behavior changes; P2 (owner lite segments) and `.dark` block untouched.
- 023-delta-sync-ui: UI layer over Delta v2 (see `docs/delta-client-v2-guide.md` "Delta Sync UI", `specs/022-delta-client-v2/ui-redesign-tasks.md`). Backend: REST `/api/v1/account/sites/{siteId}/delta/**` (owner) + `/api/v1/sites/{siteId}/delta/**` (admin) — sync-state, checkpoints + per-click presigned downloads, segments (admin), rebuild/rebaseline triggers, bulk site health; persistent rebaseline/rebuild flags (V35); full checkpoint Parquet reinstated (V36, reverts V34). Frontend: site-detail shell (`/account/sites/$siteId`, `/admin/sites/$siteId`) with Upload history + Delta Sync tabs, `widgets/delta-sync/DeltaSyncWidget` (lag track, activity sparkline/throughput, checkpoints Table|Cards, rebuild/re-baseline AlertDialogs), delta Batch Detail redesign, site-list sync-health pill, `features/delta-sync` (Zod DTOs, severity model, monitoring tokens). Pending product decisions: P1 Geist font (F13 skipped), P2 owner lite segments (segments/throughput admin-only).
- 021-unified-upload-api: Unified upload API consolidation across client API versions
- 020-sql-generation-optimization: Concurrency control (semaphore max 2), merge-join CSV diff algorithm (~6x→~1x memory), streaming S3 parsing, memory backpressure (heap threshold), thread pool reduction (10/20→4/8), eager GC. Config: `plugin.sql-generation.max-concurrent`, `heap-threshold-percent`, `semaphore-timeout-seconds`
- 019-site-types-postgres-cdc: SiteType (DBF/POSTGRES_CDC), site_schemas (JSONB), JSONL delta uploads, PK-aware SQL generation strategy. Auth V2: device flow + refresh tokens, site_name. See `docs/cr-site-types-postgres-cdc.md`, `docs/postgres-cdc-client-guide.md`
- 015-plugin-reinit: Plugin reinitialization (re-baseline). See `docs/reinit.md`
- 018-plugin-filtering: Added filtering (siteId, from, to) and siteDomain to plugin logs API, siteId filter to batches API, frontend PluginTabFilters component with site dropdown, date range, and page size (20, 30, 50, 100)
- 017-csv-file-initialization: Added baseline_batch_id to account_plugins, new /sites/{siteId}/files endpoints for CSV download, SQL generation skipped for baseline batch
- 016-global-error-handling: Added severity and isRead to ErrorLog, GlobalErrorUserController with user-facing endpoints, frontend GlobalErrorsWidget on Dashboard
- 014-plugin-history: Added Java 21 (LTS) + Spring Boot 3.5.6, Spring Security 6 (Auth0 OAuth2), Spring Data JPA, AWS SDK v2 (S3)
