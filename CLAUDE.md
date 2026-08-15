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
- **React 19.2** + **TypeScript 5.6** + **Vite 7**
- **TanStack Query v5** + **TanStack Router v1** (code-based routes in `src/app/router.tsx`) + **shadcn/ui** + **Tailwind CSS 3.4**
- **@auth0/auth0-react 2.8.0** + **Axios** + **Zod** + **React Hook Form**
- **Vitest 4 + React Testing Library**
- **ESLint 10** (flat config in `frontend/eslint.config.js`) — **0 errors / 0 warnings**, enforced by the pre-commit hook and the `frontend-test` CI job (`npm run lint`, `--max-warnings 0`)

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
  - frontend → `npx tsc --noEmit` (from `frontend/`) + `npm --prefix frontend run lint` + `npm --prefix frontend test`
- "100% green" = **all tests pass**, not 100% code coverage.

### Gates summary
| Gate | When | Must be green |
|---|---|---|
| **Per-task** (commit) | before every commit | `./gradlew test -PexcludeIntegration` (+ frontend `tsc --noEmit`, `npm run lint` and `vitest` if touched) |
| **Before PR** | before opening the PR | `./gradlew integrationTest` (Testcontainers) |
| **Merge** (PR → develop) | before merge | full CI (`backend-test`, `frontend-test`) green + automated review |

Merging to `develop` does **not** deploy. Dev (GKE) is deployed explicitly with a `deploy-dev/*` tag; stage/prod deploy on push to `stage`/`main` (see `docs/cr-tag-driven-dev-deploy.md`).

### Enforcement
- **git pre-commit hook** (`.githooks/pre-commit`) runs the per-task gate and blocks red commits. Enable once per clone: `git config core.hooksPath .githooks`. Bypassing (`--no-verify`) is against policy.
- **CI required checks**: the `backend-test` job (`.github/workflows/ci-cd.yml`, runs `./gradlew test`) must be a **required status check** on PRs to `develop` (configure in GitHub branch protection).

### Conventions
- **Spec-driven**: each feature → `specs/NNN-name/` (spec → plan → tasks). Skills: `/specify`, `/plan`, `/tasks`, `/implement`, `/analyze`, `/clarify`. Larger design changes → `docs/cr-*.md`.
- **Conventional Commits**: `feat(scope):`, `fix(scope):`, `chore:`, `ci:`, `docs:`.
- **Migrations (Flyway)**: forward-only, sequential `V{N}__description.sql`; never edit an applied migration; backward-compatible defaults for new NOT NULL columns. Current at **V51**, next is **V52**. `MigrationDocumentationConsistencyTest` derives these values from the migration filenames and guards both agent instruction files against drift; Gradle tracks the docs and migration directory as test inputs, and the pre-commit hook runs the focused guard for agent-doc-only or migration-only changes.
- **API evolution (strangler)**: add a versioned surface alongside the old one, reusing the same application services; deprecate the old with a sunset, migrate clients, then remove it. Do **not** fork a separate service or duplicate the domain/persistence layer.

### «The current PR» — one resolution rule for every command

Whenever a command takes a PR number and none was given — `/merge`, `/code-review`, the `review`
skill, anything else — it means **the PR of the current session**, resolved in this order:

1. **From the branch you are on** — the normal case, since a session working an issue sits in its
   own worktree: `git rev-parse --abbrev-ref HEAD`, then
   `gh pr list --head <branch> --state open --json number,title`. Exactly one open PR → that is
   the target, no need to ask.
2. **On `develop`** (session working in the main tree, no branch of its own) → the PR this session
   actually worked on during the conversation. Name it and say where it came from.
3. **Still ambiguous** → do not guess. List the candidates and ask which one.
4. **Branch found but no open PR** → say so plainly: the work never reached a PR, there is nothing
   to review or merge.

Several open PRs on one branch is abnormal — show them and ask. And when *our* commands call
`/code-review` or `review`, they resolve the number first and pass it **explicitly**, so the
reviewer never has to guess.

### Working from a GitHub issue

Run `/github-issue <number>` (`.claude/commands/github-issue.md`). It drives the full lifecycle:

`Ready` (`status: ready`) → `In Progress` (`status: in progress`) + assignee + sprint milestone →
work in an **isolated tree** → TDD with the green per-task gate → docs in the same PR →
`./gradlew integrationTest` → PR into `develop` with `Closes #<n>` and `status: in review` → code
review loop until nothing is left open → merge `origin/develop` in, resolve conflicts, re-run the
gates → `status: ready to merge` → **stop and ask the human**. `/code-review ultra` is a deeper
cloud review only a human can launch.

"Isolated tree" is usually the Conductor workspace the session already sits in — that *is* a
worktree on its own branch off a freshly fetched `origin/develop`, so the command works in place
and does **not** nest a second worktree inside it. Nesting would hide the work from the diff
viewer, the Checks panel and `GetWorkspaceDiff`, which all show the *workspace branch*. Only
outside Conductor (a bare clone) does the command create `.worktrees/<n>-<slug>` on a branch
`<type>/<n>-<slug>` (`bug` → `fix/`, `enhancement` → `feature/`, else `chore/`; a spec-kit feature
keeps `feature/NNN-name`). The Conductor-generated branch name (`plissb/<workspace>`) is left
alone: PRs resolve by `--head <branch>`, and the issue number lives in the PR title and
`Closes #<n>`.

**Finding and fixing are separate for behaviour, and may be joined for mechanics.** A reviewer
offering `--fix` may be run with it, confined to the **mechanical** class — findings whose correct
edit is objective and settles nothing: formatting and line length, unused imports/locals, Java
naming **on private/local and new unpublished symbols only**, `var` → explicit type (Code Style
above), Javadoc on new public members, and `eslint --fix` within formatting-class rules (the
`--max-warnings 0` lint gate plus `tsc --noEmit` catch a bad one immediately).

Everything else is fixed by hand with a decision per finding. The carve-outs that matter here all
break a contract **the compiler and CI will not catch**: renaming a field/record component of a
DTO or JSONB model (Jackson binds by name), renaming a JPA entity field (the naming strategy maps
it to a column), anything in `src/main/proto/delta-ingestion.proto` (an already-shipped Windows
client is on the other end — see 035 and its taken field numbers), Micrometer metric names, cache
names, config keys and headers, SQL inside `@Query`, `@Transactional` semantics, an **applied**
Flyway migration, and on the frontend TanStack Query keys, route paths and Zod/API field names.
The test to apply to any future candidate: *can the edit break a contract in a way the compiler
and CI will not catch?* If yes, it is behavioural whatever style rule it cites. Where the line
runs is a human decision: an agent that thinks the mechanical class should move stops and says so.

**The review loop is visible in the PR.** The reviewer posts findings as a PR comment; after
fixing, post a reply comment answering every finding — fixed (sha + what changed), closed by the
reviewer's own `--fix` (`outcome: fixed` + sha), not fixed (reasoning + the issue filed for it),
or not applicable (why it was a false positive) — before the next round. `/code-review` is
single-shot per PR (it stops if it already reviewed that PR), so later rounds use the `review`
skill; silence is never "no findings". A fourth surface exists inside Conductor: the comments on
the workspace diff (`GetDiffComments`), which the human leaves in the Changes panel and which
exist before any PR — they block readiness like an unresolved thread, and `gh` cannot see them.
Findings *you* raise before the PR go to `DiffComment`, not GitHub. Review cleanliness is derived
from **all** review surfaces: `gh pr view --comments` omits inline `reviewThreads`, any thread with
`isResolved == false` blocks (even when `isOutdated`), a `PENDING` review visible to the acting
identity blocks, and a current `reviewDecision == CHANGES_REQUESTED` blocks. Closing an inline
finding is reply → ensure published → `resolveReviewThread`, with a state read after each
mutation. The ready-to-run GraphQL lives in `/github-issue` (step 8).

CI on a PR from a feature branch is `backend-test` + `frontend-test` only — `code-quality` and
`dependency-analysis` gate on `run_full_pipeline`, which is true for `develop`/`release`/`main`
alone, so their absence is not a failure. `./gradlew test -PexcludeIntegration` still needs Docker
(~30 contract tests use Testcontainers); without it the gate dies inside `Unsafe.java`. Never
write off a red check without checking which test it is.

The merge into `develop` never happens without a human go-ahead. Normally that go-ahead is per PR,
via `/merge <pr>` (invoking it is the authorization). That command re-checks readiness, merges
with **squash** (one issue = one commit, as the whole current history), closes the issue, moves
the card to `Done` and strips the `status: *` labels. `deleteBranchOnMerge` is off in the repo
settings, so `--delete-branch` is passed explicitly. Cleanup removes only what the command
created: a nested worktree is removed, a **Conductor workspace is never touched** — the human
archives it (or the "Auto-archive on PR close" setting does).

**Docs ship with the code** (Rule 1). The living journal is **"Recent Changes" in this file** plus
`docs/` (`cr-*.md` and the client guides); root `CHANGELOG.md` is deliberately frozen and carries
an "out of date" banner — do not resurrect it on your own initiative. Say "needed" or "not needed"
for each documentation surface rather than skipping silently.

**Follow-ups are tickets, not notes — but search before filing.** Anything out of scope worth
doing later: first search **open and closed** issues (`gh issue list --state all --search …`) and
grep `CLAUDE.md`/`docs/`, because several workspaces work this repo in parallel. An open match
gets your evidence as a comment; a closed match means either a regression (new issue linking the
old one and the fixing commit) or a stale observation (the fix is already in `develop` — no ticket
needed). Only if nothing matched, file the issue (described, labelled, milestoned, `Backlog` on
the board) — and do not start work on it in the current cycle.

### Running several issues at once

`/github-issue-runner` (`.claude/commands/github-issue-runner.md`) is a **dispatcher**: it keeps
up to **three** issues in flight and picks up the next as a slot frees. Invoking it gives the
merge go-ahead **for that run** — the one exception to the per-PR gate above. Nothing else
relaxes: every readiness condition and every `/merge` check is still verified, merges stay
serialized one at a time, the dispatcher writes no code itself, and an executor's report is
re-verified against GitHub before anything is merged.

**No agent can create a Conductor workspace** (local ones open on a human's click; the `conductor`
CLI covers cloud workspaces only and needs its own API token), so the dispatcher runs in one of
two modes. **Mode A (default under Conductor)**: it plans the order, hands the human a ready list
— issue, workspace name, the `/github-issue <n>` to type — then watches GitHub and merges. The
work stays visible in the app and each task keeps a real session. **Mode B (`agents`, or outside
Conductor)**: background subagents in `.worktrees/<n>-<slug>`, fully autonomous but invisible in
the sidebar. In mode A the dispatcher cannot read another workspace's Changes panel, so the
executor must confirm its own panel is clear before its PR counts as ready.

The pool is the `Backlog` and `Ready` columns of project 16, any milestone. Order is **logical,
not chronological** — dependencies (contract, refactor, migration, infrastructure before their
consumers) and, separately, overlap in the code touched: two issues editing the same product files
never run in parallel. Three overlaps here are invisible to git and must be sequenced by hand: the
next **Flyway migration number** (two branches both taking `V{N}` merge cleanly and break startup),
**field numbers in `delta-ingestion.proto`**, and `specs/NNN-*` directory numbers. Accumulating
files (`CLAUDE.md`, `specs/**/tasks.md`, `docs/`) are not overlap — both sides get merged.

The run stops for the human on an agent reporting blocked, a second dispatcher touching an issue
in this run's window, an unclear conflict, red CI on `develop`, a missing `project` scope, an issue
that turned out wider than written, or the same issue coming back blocked twice. Findings mid-run
become issues for a later run, never additions to the current window. The run scripts are
`nonconcurrent` (one shared docker-compose stack and a fixed 8080), so only one workspace can hold
the live stand at a time — sequence the tasks that need it.

#### Board identifiers — the single source, do not copy them elsewhere

Project **16** `Data Forge Middleware — Sprints`, owner `quantum-soft-dev`.
Project id `PVT_kwDOB7LEnM4BeGrE`, `Status` field id `PVTSSF_lADOB7LEnM4BeGrEzhYjWtc`.

| Column | option-id |
|---|---|
| Backlog | `f75ad846` |
| Ready | `8a89f088` |
| In Progress | `47fc9ee4` |
| Blocked | `3a5c4fbe` |
| In Review | `a34edc01` |
| Done | `98236657` |

```bash
gh project item-list 16 --owner quantum-soft-dev --format json --limit 100   # find by content.number
gh project item-add  16 --owner quantum-soft-dev --url <issue-url>           # if not on the board
gh project item-edit --project-id <project-id> --id <PVTI_...> \
    --field-id <status-field-id> --single-select-option-id <option-id>
```

If `item-edit` fails, never invent ids — re-read them with
`gh project field-list 16 --owner quantum-soft-dev --format json`; the board may have changed.
`/github-issue`, `/github-issue-runner` and `/merge` all read these ids from here, so they stay in
one place: a copy in each command would drift silently and start moving cards into a column that
no longer exists.

**Status lives in two places and both must be moved on every transition:** the Kanban board
(Projects v2 `Status`, project **16**) and the repo `status: *` labels. The ticket walks the
columns in order — jumping is not allowed:

`Backlog` → `Ready` (`status: ready`) → `In Progress` (`status: in progress`) → `In Review`
(`status: in review`, then `status: ready to merge` while awaiting the human) → `Done`.
`Blocked` (`status: blocked`) is the side exit at any point.

Moving the board needs the `project` token scope — if `gh project` fails with
`INSUFFICIENT_SCOPES`, run `gh auth refresh -s project` and say so instead of silently updating
only the label. Always re-read the board after a transition: a command that exited 0 is not proof
the state moved.

> `.specify/memory/constitution.md` is an **unfilled spec-kit template** (`[PRINCIPLE_1_NAME]`
> placeholders) and is intentionally unused. This file is the normative source for the process;
> do not cite the constitution as if it contained rules.

### Conductor workspaces

`.conductor/settings.toml` (shared, read from `develop` on the remote — it only takes effect once
merged) gives every new workspace `npm --prefix frontend ci` + `core.hooksPath`, run scripts for
the compose stack / backend / Vite (`--port $CONDUCTOR_PORT`, since `vite.config.ts` pins 3000)
and the per-task test gate, `run_mode = "nonconcurrent"` (one shared stack, fixed 8080/5432/6379/
4566), and `archive = "git worktree prune"` so a nested worktree does not outlive its workspace —
deliberately not `docker compose down`, which would kill a stack another workspace is using.
`.worktreeinclude` copies the gitignored `frontend/.env.local` and `local-dev/auth0.env` into each
new workspace; listing that file at all **replaces** Conductor's default `.env*` pattern, so
anything else needed must be added there too. If `frontend/node_modules` is missing in a fresh
workspace, the setup script did not run: run it by hand and say so rather than skipping the
frontend gate.

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
| `/api/v1/account/plugins/bit-bi/rotate-api-key` | POST | Rotate the API key (owner OAuth2; new key shown once) |

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
- Migrations current at **V51**; next migration is **V52** (do not reuse numbers)

## Recent Changes
- checkpoint-rematerialize: A checkpoint table left without a snapshot is rematerialized from the
  existing frame, without waiting for new segments (issue #128). `CheckpointService.materialize`
  used to detach Parquet on a per-table failure, save the row at the new `seq` and still advance
  the pointer — after which `buildCheckpoint` saw an empty `newSegments` and returned the seed,
  so even `POST .../delta/checkpoints/rebuild` was a no-op on a quiet site. A scheduled build now
  retries any row whose `s3_key_parquet` is null; a forced rebuild rematerializes every table from
  the frame (`buildCheckpoint(siteId, true)`). Neither path moves the pointer, re-uploads the
  frame, or publishes `CheckpointRecordedEvent` — the fold has not changed, retention stays
  monotonic. A same-seq rematerialize that fails keeps a still-valid last-good key; detach is
  only for an advancing seq. After a full prune the frame is still enough — leftover changelog
  rows are not required. No API shape, DTO, migration, configuration, metric name or frontend
  change. See `docs/delta-client-v2-guide.md`.
- order-dependent-test-flakes: Two `backend-test` flakes no longer depend on suite order or a
  one-second clock (issue #119). `BatchRetentionScheduleAdminControllerIntegrationTest` was
  asserting the CONFIG fallback against a shared `app_settings` row that the sibling PUT (and any
  other class that wrote the table) left behind — `test-data.sql` never touches it.
  `BaseIntegrationTest.clearAppSettings()` deletes the table, the retention-schedule class calls
  it in `@BeforeEach`, and a leftover-then-clear test pins the isolation.
  `SqlGenerationConcurrencyTest` no longer waits for S3 work to start and then for
  `tryAcquire(1s)` to expire: the first holder blocks at the first statement after acquire, and
  `regenerateForBatch` is asserted via the semaphore queue gauge. No API, DTO, migration,
  configuration or frontend change.
- checkpoint-parquet-on-disk: The V2 checkpoint build materializes its snapshots on disk, one table
  at a time, and every V2 Parquet writer takes an explicit row-group budget (issue #112, which
  absorbed #114). `ParquetCheckpointWriter.toParquet` used to copy a table's folded rows into a
  `List<GenericRecord>` and encode them into a `ByteArrayOutputStream`, so a build held a whole
  table's Parquet file in heap on top of the fold — the last unbounded consumer on this path after
  036 put completed-batch artifacts on disk and #113 removed the CSV twin.
  `ParquetCheckpointWriter.writeParquet(Path, …)` streams instead: the rows arrive as an `Iterable`
  and are traversed (a second time only when a decimal envelope must be measured — `widenDecimalsToFit`
  is now a single pass and is skipped outright when no column declares a decimal), the encoded file
  goes through the same `FileOutputFile` the batch writer uses, and
  `S3CheckpointStorage.uploadParquet` takes that `Path` instead of a `byte[]`. `CheckpointService`
  runs write → upload → delete per table, so one row-group buffer and one scratch file exist at a
  time and the peak of *materialization* stops scaling with the table count. An **unusable scratch
  directory** (missing, read-only, out of inodes) aborts the build instead of being counted as a
  table-level skip — it would hit every table alike, and skipping would detach every last-good
  snapshot while the pointer advanced. A later rematerialize (#128) can restore a per-table hole,
  but a systemic scratch failure must not throw those keys away first. A failure *during* a write stays a per-table skip, so one
  oversized or unrenderable table still cannot freeze the pointer and stop retention. **The site fold
  stays in heap, and so does the all-tables frame the build serializes at the end** — off-heap
  folding and streaming the frame (#126) are deliberately separate tickets, and
  `delta.checkpoint.duration{phase=fold}` is the meter that shows when it starts to matter. New keys: `delta.parquet.row-group-bytes`
  (`DELTA_PARQUET_ROW_GROUP_BYTES`, default **16 MiB** — an eighth of parquet-mr's implicit
  default, kept high enough that a multi-GB batch artifact's footer stays in the hundreds of row
  groups) applied at all four `AvroParquetWriter`
  builders — one budget for the checkpoint, egress and batch paths, since they share one pod's heap;
  `delta.checkpoint.temp-dir` / `delta.checkpoint.max-temp-bytes` (`DELTA_CHECKPOINT_TEMP_DIR`,
  `DELTA_CHECKPOINT_MAX_TEMP_BYTES`, default 10 GiB) mirror the batch-parquet pair. Crossing the
  ceiling raises `ArtifactSizeLimitExceededException` during the write and the existing per-table
  catch records it as `delta.checkpoint.tables.unmaterialized{reason=parquet_failed}`. `FileOutputFile`
  and `ArtifactSizeLimitExceededException` moved out of `DeltaParquetWriter` into the package now
  that both writers use them. S3 keys, the checkpoint contract, meter names, codec (Snappy), the
  `_op`/`_seq`/`_changed` schema, REST, gRPC and the frontend are unchanged; no migration. See
  `docs/delta-client-v2-guide.md`.
- wipe-checkpoint-orphans: Site history wipe walks `checkpoints/{siteId}/` after the database work
  instead of trusting the keys on the rows (issue #118). The `checkpoints` row is one per
  `(site, table)` and reused across builds — each build writes `…/{table}/seq={seq}/snapshot.parquet`
  under a new `seq` and replaces the key, and since #113 a build that cannot materialize Parquet
  nulls it outright — so every earlier object was already unreferenced and nothing else sweeps that
  prefix (retention prunes segments; no lifecycle rule here). The walk also removes the
  `_frame/seq={seq}/frame.pb.gz` reload frames, which no row ever named, so nothing else could ever
  remove them — dead bytes, **not** a stale-read path: a build writes its frame at the seq it ends on
  and advances the pointer only afterwards, so the frame the next build reads is always the one that
  epoch just wrote, overwriting any pre-wipe namesake. Same belt-and-braces shape the unified batch
  artifacts already use — exact keys from the rows **plus** a post-commit prefix walk — and each
  prefix is listed independently, so one failing listing costs neither the other prefix nor the
  recorded keys; the final key list is de-duplicated. The S3 phase also stops throwing: it now issues
  one round trip per thousand keys, and a client-side failure escaping `deleteObjects` would report a
  committed wipe as a 500, so it is caught and every key handed over is reported as
  `s3DeleteErrors` — a floor, not a census, since a failed 1000-key batch counts as one.
  `S3CheckpointStorage.checkpointPrefix(siteId)` joins `egressPrefix`. No API
  shape, DTO, migration, configuration or frontend change. Follow-ups from review: #122 (the walk has
  no `lastModified` cut-off, so a concurrent post-commit build's object — and, worse for checkpoints,
  the frame its restored pointer names — can be swept; true for `egress/` since 036), #123 (a prefix
  that could not be listed at all, and a failed delete batch, are both under-reported in the wipe
  response), #124 (a mid-pagination listing failure discards the pages already read). See
  `docs/delta-client-v2-guide.md` ("Site history wipe and the generation epoch").
- batch-parquet-idle-poll: An idle completed-batch Parquet poll no longer pays cluster-wide costs
  (issue #115). `settleExpiredClaims()` — the first thing `finalizeNext()` does, on every drain
  iteration of every worker on every replica — used to open a transaction, take the
  `parquet-export-catalog-publish` advisory lock and `UPDATE` the single-row
  `batch_parquet_catalog_watermark` **before** discovering that `abandonExpiredClaims` had nothing
  to settle, which is the normal case. It now reads the same predicate first through
  `BatchParquetArtifactRepository.hasSpentExpiredClaims` (`SELECT EXISTS … LIMIT 1`, served by the
  existing partial `idx_batch_parquet_artifacts_claim`) and opens the settle transaction only on a
  non-empty answer. The monotonicity guarantee is untouched: when there *is* something to settle,
  `updated_at` is still stamped from the watermark taken under that same lock, so an `ABANDONED`
  row cannot land at or before an already-visible sibling. Losing the probe race to a peer costs
  one wasted watermark tick, never a missed settlement. No REST, gRPC, DTO, configuration-key,
  metric, S3-key, migration, or frontend change. See `docs/cr-unified-batch-parquet.md`
  ("Durability and retries").
- checkpoint-csv-removal: The V2 checkpoint build stopped writing `snapshot.csv.gz` (issue #113).
  Only the typed Parquet is materialized, so a build no longer copies the whole folded state into a
  second row representation (`ValueMapper`) and gzips it into an on-heap `byte[]`. A table with no
  declared schema now yields **no** artifact — logged and counted as
  `delta.checkpoint.tables.unmaterialized{reason=no_schema|parquet_failed}` instead of being masked
  by the CSV. **Breaking change for the Bit BI client:** `GET /api/v1/plugins/bit-bi/sites/{siteId}/files`
  serves `<table>.parquet` (`application/vnd.apache.parquet`); the retired `<table>.csv.gz` name
  404s rather than resolving to Parquet bytes, and the historical-uploads fallback is now keyed on
  the site having no checkpoints at all, not on the format being absent. `CsvFileQueryService` →
  `CheckpointFileQueryService`. Owner/admin `.../delta/checkpoints/{table}/download?format=csv`
  answers **410 Gone** (checked before the lookup, so it is the same answer for every table);
  `hasCsv` is gone from the checkpoint DTO and the UI's CSV pill with it. `checkpoints.s3_key_csv`
  is **kept** (no migration): site wipe still deletes the objects earlier builds wrote — it is the
  only sweeper that reads them, changelog retention prunes segments and never touched checkpoints.
  See `docs/delta-client-v2-guide.md`, `docs/bitbi-integration.md`, `docs/cr-bitbi-delta-sql.md`.
- 042-parquet-phase-metrics: Parquet and checkpoint duration meters are split by `phase` so a
  cycle can be attributed (issue #111). `delta.batch-parquet.duration` and
  `delta.checkpoint.duration` keep their names; `delta.egress.duration` is new. Every series
  carries `{phase=...}` (`total` is the whole cycle; Prometheus cannot mix tagged and untagged
  series). Inner phases are `download`/`decode`/`decimal_scan`/`write`/`upload` (batch),
  `download`/`write`/`upload` (egress), and `download_frame`/`fold`/`parquet`/`upload`
  (checkpoint). `delta.egress.pending` gauges `changelog_segments` with `egress_at IS NULL`.
  Writers, queues, S3 keys, REST, gRPC, and frontend are unchanged. See
  `docs/delta-client-v2-guide.md` (Metrics), `specs/042-parquet-phase-metrics/`.
- 041-parquet-export-batch-files: Parquet Export lists one completed-batch Parquet per table and
  makes that the unversioned default (issue #109). `GET /api/v1/plugins/parquet-export/files`
  accepts `type=batch` from `batch_parquet_artifacts` (`READY`/`ABANDONED`); omitted `type` is
  now batch-only — **existing clients that still read per-segment files must add `type=delta`**.
  Sort/`since` use `ready_at` (or `updated_at` for abandoned) so a late or requeued artifact
  reappears; those timestamps are {@code GREATEST(previous + 1µs, clock_timestamp())} under a
  short advisory lock so a lagging replica clock cannot land at or before an already-visible
  sibling. Abandoned rows have `status=abandoned`,
  `artifactId` for admin requeue, and no download URL. V51 adds nullable
  `first_seq`/`last_seq` (writer-filled; kept on abandon; catalog never live-queries segments)
  and partial catalog indexes for READY `(ready_at, s3_key)` / ABANDONED `(updated_at, 'abandoned/'||id)`.
  No owner/admin delta, gRPC, S3-key, or frontend
  change. See `docs/parquet-export-plugin-guide.md`, `docs/cr-unified-batch-parquet.md`,
  `specs/041-parquet-export-batch-files/`.
- plugin-secret-reveal: Activating a plugin from the UI no longer discards the secret it was issued (issue #107). The backend already returned it in `PluginActivationResponseDto.apiKey` for a new activation or a reactivation — bit-bi sends the raw `plk_` key, parquet-export sends `login:password` — but the frontend type omitted the field, so the only copy the account ever gets died in the response. `parsePluginSecret` (`features/my-plugins/model/pluginSecret.ts`) turns it into an `api-key`/`basic-auth` shape, the activation and rotation mutations hand it to their caller, and `PluginSecretDialog` reveals it once with per-field copy buttons; the value lives in widget state only while that dialog is open and never reaches the query cache, a toast or a log. The existing rotation endpoints (`POST /api/v1/account/plugins/bit-bi/rotate-api-key`, `.../parquet-export/rotate-password`) gain their first UI: a **Rotate API key** / **Rotate password** action on an active plugin card, answering through the same dialog. Frontend only — no API, DTO, migration or configuration change. See `docs/parquet-export-plugin-guide.md`, `docs/bitbi-integration.md`.
- agent-migration-doc-consistency: `AGENTS.md` and `CLAUDE.md` now agree that V50 is current and V51 is next (issue #104). `MigrationDocumentationConsistencyTest` derives the highest Flyway version from `src/main/resources/db/migration/` and requires both migration pointers in both agent instruction files to match it. Gradle declares the docs and migration directory as test inputs, while the pre-commit hook runs this focused guard for agent-doc-only or migration-only commits, so local incremental builds cannot silently accept drift. No database, runtime, API, configuration, or frontend change.
- 040-batch-parquet-attempt-keys: Completed-batch Parquet claims now upload to immutable `egress/{siteId}/batches/{batchId}/attempts/{claimToken}/{table}.parquet` objects (issue #100), so a superseded `PutObject` that completes last cannot replace the winning claim's bytes. The manifest publishes only the current token's exact key; stale finishers delete only their own object. Batch retention and explicit deletion paginate the logical batch prefix after database work and retain recorded/legacy exact-key fallbacks, while site wipe keeps its whole-site egress walk, so a process-death object with no published metadata remains discoverable. Existing stable-key rows require no migration and stay downloadable/cleanable. A forced LocalStack takeover test makes the old upload physically different, completes it last, and proves the READY size/SHA-256 belong to the winner. No REST, gRPC, DTO, configuration, metric, migration, or frontend change. See `docs/cr-unified-batch-parquet.md`, `docs/delta-client-v2-guide.md`, `specs/040-batch-parquet-attempt-keys/`.
- 039-batch-parquet-queue-ops: Operators can observe and recover the durable completed-batch Parquet queue without production SQL (issue #98). `delta.batch-parquet.queue{status=pending|building|ready|failed|abandoned}` gauges count live database rows at scrape time. ROLE_ADMIN routes `GET /api/v1/sites/{siteId}/delta/batches/{batchId}/parquet-artifacts` and `POST .../{artifactId}/requeue` expose a safe diagnostic projection and reset `ABANDONED` or lease-expired `BUILDING` under a row lock to a zero-attempt `PENDING` lifecycle. Recovery clears the claim/error/unpublished metadata, is protected from late workers by the existing claim-token check, and is audited as `BATCH_PARQUET_REQUEUE`; V50 widens `admin_action_logs.chk_action_type`. No owner REST, gRPC, protobuf, S3-key, worker scheduling, frontend, or configuration-key change. See `docs/cr-unified-batch-parquet.md`, `docs/delta-client-v2-guide.md`, `specs/039-batch-parquet-queue-ops/`.
- 038-batch-parquet-single-replay: Completed-batch Parquet finalization removes the table multiplier from raw-changelog replay (issue #97). A transaction-scoped PostgreSQL advisory lock serializes each batch claim across replicas, then the worker claims every currently retryable table row, opens one file-backed writer per renderable table, and fans out a shared ordered record stream. Batches without decimals cost one full replay; if any claimed table declares decimals, one shared envelope scan plus one shared write replay preserves lossless widening. Heap is bounded by open writers × Parquet row-group buffer rather than batch rows; row-count guards, per-table ascending `_seq`, claim tokens, retries, and independent table outcomes remain. `delta.batch-parquet.duration` now measures the grouped batch build. No REST, gRPC, DTO, configuration-key, metric-name, S3-key, migration, or frontend change. See `docs/cr-unified-batch-parquet.md`, `specs/038-batch-parquet-single-replay/`.
- 037-batch-parquet-hardening: Completed-batch Parquet finalization no longer participates in the Delta ingestion commit (issue #99): enqueue runs `AFTER_COMMIT`, while lazy download backfill remains recovery and discovers legacy `stats IS NULL` tables by replaying their raw segment records. Legacy batches skip only the unknowable row-count assertion. Spent expired claims are bulk-abandoned before the retry query, so 16+ dead rows cannot postpone live work; the local byte ceiling now stops the writer during output and abandons that deterministic artifact immediately. Admin batch deletion delegates to one transactional application service, and artifact-key policy lives in delta/domain instead of batch presentation/application. Its separately tracked late superseded-`PutObject` race is closed by feature 040. No REST, gRPC, configuration-key, metric, or migration change. See `specs/037-batch-parquet-hardening/`.
- 036-unified-batch-parquet: A completed Delta session exposes exactly one Parquet per table instead of a segment slice — `GET .../delta/batches/{batchId}/tables/{table}/parquet` (owner, issue #93) resolves a durable `batch_parquet_artifacts` manifest row (V49) and never guesses among the realtime per-segment egress objects, which are unchanged for existing consumers. After the batch commit, completion enqueues one row per table; a bounded worker claims a row (the claim commits **before** the build, so a process that dies mid-build still spends an attempt), replays the batch's non-provisional segments with a file-backed streaming writer, and publishes `READY` only after its claim-scoped `PutObject` returns. `claim_token` + a lease renewed during the build keep two workers from publishing one manifest attempt; failures back off by doubling up to `max-attempts` (7 ≈ 1 h) and then become `ABANDONED`. Download answers `409` while an attempt is queued/running/pending retry and `404` only when absent or abandoned; a batch that predates the feature is backfilled on the first click. Retention/admin deletion paginate the logical batch prefix and keep exact-key fallbacks; site wipe walks `egress/{siteId}/`. Config `DELTA_BATCH_PARQUET_*`; meters `delta.batch-parquet.{artifacts,duration,reclaims}`. See `docs/cr-unified-batch-parquet.md`, `docs/delta-client-v2-guide.md`, `specs/036-unified-batch-parquet/`.
- 035-site-history-wipe: A site can be given a true clean slate — `POST .../delta/wipe` (owner + admin, issue #89) destroys batches, uploaded files, changelog segments, checkpoints, the site schema, plugin SQL and error logs, and keeps the site. V48 adds `site_sync_state.generation` (an epoch bumped by a wipe and by nothing else — an ordinary re-baseline never moves it) plus `wipe_pending`, and `admin_action_logs.details`. The epoch travels on the wire as **`optional`** (explicit presence): `SyncStateResponse.generation`/`SessionOpened.generation` (field 5), `SessionStart.generation` (field **6**) and `ErrorCode.GENERATION_MISMATCH` (**12**) — 5 and 7 are taken in the shipped client's proto, so `SessionStart` field 5 stays `reserved`. The guard keys on presence, not on a zero: absent = old client, present-and-different (0 vs 1 included, i.e. a site's first wipe) = refused. `ErrorCode` 7–11 are no longer reserved — the server declares and emits the client's `OVERFLOW`/`OVERFLOW_BYTES`/`SITE_INACTIVE`/`SCHEMA_REQUIRED`/`CONCURRENT_BATCH_LIMIT` (dbf-data-extractor#130). Bit BI re-captures its baselines automatically on the first post-wipe checkpoint (`DELTA_AUTO_REINIT`). See `docs/delta-v2-wire-contract-answers.md`, `docs/site-history-wipe-client-guide.md`, `docs/delta-client-v2-guide.md`, `specs/035-site-history-wipe/`.
- 034-delta-rebaseline-cancellation: A requested full re-baseline can be taken back — `DELETE .../delta/rebaseline` (owner + admin, issue #84). V47 records `batches.session_mode` and `site_sync_state.rebaseline_notified_at`, the two facts that make the outcome answerable: `cancelled` / `snapshot-in-progress` (a live FULL_SNAPSHOT is reported and left alone, so a drop before its commit still re-arms the retry) / `client-notified` / `not-requested`. A running snapshot also surfaces as `snapshotInProgress` on the sync-state projection. See `docs/delta-client-v2-guide.md` ("Cancelling a re-baseline").
- 033-delta-rebaseline-segmented: A re-baseline is sealed into bounded segments instead of buffering the whole snapshot, so a site above `delta.ingestion.max-session-records` is no longer bricked by the "Full re-baseline" button (issue #82). Seals are silent on the wire (`SessionCommitted` stays terminal for periodic sessions) and the segments are `provisional` (V46) — invisible to the checkpoint fold, the egress queue and the Bit BI SQL queue — until `SessionEnd` discards the old baseline and publishes them in one transaction. No proto or client change. See `docs/cr-delta-rebaseline-segmented.md`, `specs/033-delta-rebaseline-segmented/`.
- 032-remove-client-api-v1: Retired `/api/dfc/**`, credential-based token issuance, V1-only branches, and HTTP multipart ingestion. V45 migrates stored sites to V2 and temporarily normalizes V1 writes from old pods during a rolling deployment. Historical uploaded CSV files remain readable through the Bit BI files API fallback.
- tag-driven-dev-deploy: merges to `develop` run tests only — dev (GKE) deploys **only** via `deploy-dev/*` tags (`TAG=deploy-dev/$(date +%Y%m%d-%H%M); git tag $TAG && git push origin $TAG`); stage/prod still deploy on push to `stage`/`main`; ghcr docker jobs in ci-cd.yml dormant (`if: false`, AWS rollback only). See `docs/cr-tag-driven-dev-deploy.md`.
- 029-batch-per-session: Batch = one Delta v2 ingestion session (see `docs/cr-batch-per-session.md`, `specs/029-batch-per-session/`). Continuous seals commit segments under the session's single batch (many segments : 1 batch; per-segment S3 keys `segments/{segmentId}.pb.gz`); no per-seal batch cycling, no empty tail batches; `BATCH_COMPLETED` once per session; Upload History list aggregates SUM(records)/DISTINCT tables SQL-side (`aggregateByBatchIds`); batch timeout for streaming batches counts from `batches.last_activity_at` (V41, touched at start/ack/seal) — the V2 sweeper exclusion is removed.
- 028-parquet-export-plugin: Parquet Export plugin (see `docs/parquet-export-plugin-guide.md`, `specs/028-parquet-export-plugin/`). Second Plugin SPI impl `parquet-export`: Basic Auth credentials minted at activation (login plaintext + BCrypt hash in plugin_data, shown once as `login:password`; rotation via `POST /api/v1/account/plugins/parquet-export/rotate-password`); `GET /api/v1/plugins/parquet-export/files` (Basic Auth, filters since/siteId/table/type=batch|delta|checkpoint, default batch, per-account rate limit) registers one-time download links (`download_links`, V39; V40 unique login index); anonymous `GET /download/{token}` consumes atomically → 302 to ~60s presigned URL, then 410; purge scheduler; dedicated security filter chain (Order 4). Config `plugin.parquet-export.*`.
- 022-delta-client-v2: Delta Client v2 gRPC ingestion (see `docs/cr-delta-client-v2.md`, `docs/delta-client-v2-guide.md`, `specs/022-delta-client-v2/`). gRPC server on :9090 (`DeltaIngestionService`) — SessionStart/StreamChanges/SessionEnd/GetSyncState/SubmitSchema; the `delta/` aggregate (SiteSyncState, ChangelogSegment, Checkpoint), changelog fold + CSV/Parquet checkpoints, event-driven per-segment delta Parquet egress. Migrations V29–V36. Sites default to `client_api_version = V2`.
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
