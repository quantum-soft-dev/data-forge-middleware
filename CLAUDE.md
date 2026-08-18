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
- **Migrations (Flyway)**: forward-only, sequential `V{N}__description.sql`; never edit an applied migration; backward-compatible defaults for new NOT NULL columns. Current at **V53**, next is **V54**. `MigrationDocumentationConsistencyTest` derives these values from the migration filenames and guards both agent instruction files against drift; Gradle tracks the docs and migration directory as test inputs, and the pre-commit hook runs the focused guard for agent-doc-only or migration-only changes.
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
- Migrations current at **V53**; next migration is **V54** (do not reuse numbers)

## Recent Changes
- test-lock-wait-bound: A statement of the integration suite that waits on a lock now fails and
  names itself instead of stopping the run (issue #197, the residual behind #159 and #175). Every
  cached Spring context keeps its background workers alive against the one shared PostgreSQL
  database, so the class under test can wait on a lock a sibling context holds —
  `@Sql("/test-data.sql")` deleting the `%.example.com` rows, a `clearPluginSqlGenerations`
  delete, the delta-SQL queue's claim — and PostgreSQL's default `lock_timeout` is **0**, wait for
  ever: the JUnit thread never returns, CI kills the job after its own much longer timeout, and no
  test is named. `spring.datasource.hikari.connection-init-sql: SET lock_timeout = '10s'` in
  `src/test/resources/application-test.yml` is the whole of it; the blocked statement is then
  aborted by the server with SQLSTATE **55P03** and "canceling statement due to lock timeout",
  which names the cause as well as the test. **Database-side rather than a JUnit `@Timeout`**, the
  first of the ticket's three candidates: a plain `@Timeout` is only checked after the method
  returns, so a hung method still hangs, and a preemptive one abandons a thread parked in the
  driver while it still holds a pooled connection. **`statement_timeout` is deliberately not set**
  — it would bound legitimate work (these queries scan rows accumulated across the whole run) and
  bounds none of the hangs that are not statements, so it buys flakes rather than the failure this
  is about. **The 10 s is measured against the suite's own deliberate waits, not against its
  slowest statement**, since `lock_timeout` bounds waiting for a lock and never holding one: the
  longest deliberate wait is `SiteHistoryWipeIntegrationTest`, which holds the `site_sync_state`
  row for 1.5 s while a checkpoint build blocks on it on purpose. Two guards, the #187 shape:
  `LockWaitBoundTestProfileTest` holds the key on the fast gate, and
  `DatabaseLockWaitBoundIntegrationTest` holds what a **pooled** connection actually carries (a
  file can only show what was declared) and produces a genuinely blocked statement, requiring it
  to be aborted with 55P03 while the holder is still holding. They share `LockWaitBound` — one
  parser and the agreed 5 s–60 s range — rather than each carrying its own idea of a sane bound,
  the `RunOwnedScratch` precedent. The probe blocks on an **advisory** lock rather than on a row:
  `lock_timeout` is one GUC over the whole lock manager (PostgreSQL applies it to "a table, index,
  row, or other database object" alike, and nothing configures the kinds separately), so an
  advisory lock proves the bound for the row locks the ticket is about, while a literal key is the
  one lock in this shared database that no other class and no background worker can be holding —
  the probe cannot become the hazard it tests for, and it leaves no row behind for the next class
  to count. Its own wait is bounded as well (the future is read with the bound plus a margin, and
  the statement cancelled before the failure is reported), so a regression fails the guard instead
  of hanging the run the guard is about. Two comments that stated the opposite are corrected
  rather than left standing (`AbstractIntegrationTest.clearPluginSqlGenerations`,
  `BitBiDeltaSqlIntegrationTest.drainQueue`). **Review round 1 corrected the guards, not the
  bound**, and two of the five are worth remembering. The parser read the *first* `lock_timeout`
  in the init statement while PostgreSQL applies the last one of a multi-statement string, so `SET
  lock_timeout = '10s'; RESET lock_timeout` passed the fast gate while every pooled connection
  waited for ever — the exact false green the guard exists to prevent, now red under mutation
  (`LockWaitBoundTest`), with `RESET` and `TO DEFAULT` read as the zero they restore and an
  unrecognised unit failing by name instead of silently meaning milliseconds (`'30000us'` is 30 ms
  and used to read as 30 s). And the probe's bounded wait started at submit time rather than when
  the blocked statement existed: the holder pins one of the profile's four connections and Hikari
  waits 30 s for one, longer than the budget for the abort, so a momentarily busy pool would have
  been reported as “the profile does not bound a lock wait”. Two smaller ones: the English message
  assertion was dropped for the locale-independent 55P03 it duplicated, and a holder that failed
  before taking the lock no longer has its exception swallowed by the `Future`. Test-only — no
  production code, REST, gRPC, DTO, migration, production configuration-key, metric, S3-key or
  frontend change.
- test-profile-scratch-directory: The `test` profile names both Parquet scratch directories, so no
  context taking the profile's defaults writes or deletes in the machine-wide temp directory
  (issue #187, the half #168 named and deliberately left open). The exception is deliberate and is
  #168's own: `CheckpointParquetIntegrationTest` overrides both keys to per-class directories it
  creates under `java.io.tmpdir` — named `dfm-it-scratch-*` precisely so no sweeper's prefix
  matches them — and its decoy file, planted there for one assertion, is removed in a `finally`. Undeclared, `delta.checkpoint.temp-dir` and
  `delta.batch-parquet.temp-dir` fall back to `${java.io.tmpdir}`, and
  `ParquetScratchOrphanSweeper`'s `@Scheduled` carries `initialDelayString = "0"` — so **every**
  cached Spring context swept the host's temp directory once at refresh (#167 slowed the cadence to
  an hour; it does not remove the refresh pass) and deleted any regular file named `checkpoint-*` or
  `batch-parquet-*` older than four hours, whoever wrote it. That is what #168 saw from the other
  side: its leak assertion lost a file it did not own, deleted by another JVM. The suite was the
  perpetrator as well as the victim, and the population is not hypothetical — two worktrees running
  `./gradlew integrationTest` at once is the ordinary shape of `/github-issue-runner`.
  **Option 1 of the ticket, not option 2**: making the sweep inert under `test`
  (`scratch-orphan-age-seconds` far in the future) is one line and would stop the deletions, but it
  leaves the suite writing production-shaped scratch into `/tmp` and removes the only place the
  scheduled sweeper runs against a real directory at all — `ParquetScratchOrphanSweeperTest` drives
  the object over `@TempDir`s, never the wired bean over the wired directories. Both keys now read
  `${dfm.test.parquet-scratch-root:build/test-scratch/parquet}/{checkpoint,batch-parquet}`, and the
  root is supplied **absolutely** by `tasks.withType<Test>` in `build.gradle.kts`
  (`layout.buildDirectory`), so it is this build's directory whatever working directory the JVM is
  given and `clean` removes what a run leaves; the relative default keeps an IDE run that sets no
  system property inside the build tree rather than back in `/tmp`. Per worktree by construction,
  which is the property the ticket is really buying. The two writers get **separate**
  subdirectories, the split `CheckpointParquetIntegrationTest` already makes for its own context
  (#168), so a completed-batch drain cannot put a file where a checkpoint assertion looks.
  **Two guards, because neither surface sees what the other does**, sharing one definition of "a
  directory this run owns" (`RunOwnedScratch`) rather than each carrying its own.
  `ParquetScratchTestProfileTest` is static and on the fast gate: both keys declared, each resolved
  value (placeholders expanded against system properties, the way the `Environment` would) inside
  the run's tree, and the two directories **distinct** — a copy-paste pointing both at
  `…/checkpoint` otherwise passes everything silently.
  `ParquetScratchSweepIsolationIntegrationTest` holds the **wired** context on the shared
  `BaseIntegrationTest`: the directories this context actually resolved are the run's own — the
  only guard that can see an **OS environment override**, since `DELTA_CHECKPOINT_TEMP_DIR` binds
  to this key and outranks every `application*.yml` — and an aged `checkpoint-*` inside the
  configured directory is **still deleted**, which is what stops "fixed" from meaning "the sweeper
  was switched off under test" and keeps the literal prefix the test shares with the
  package-private production `ParquetScratch` from drifting unnoticed. Both were **proven red by
  mutation** (the two keys removed). **Two shapes were tried and rejected in review**: planting an
  aged `checkpoint-*` in `java.io.tmpdir` and asserting it survives reads as the more direct
  proof, but any *other* process sweeping that directory — a locally running backend under `dev`,
  or a concurrent worktree on a branch predating this fix, which is the ordinary shape of
  `/github-issue-runner` — decides it, and it would fail accusing this suite of the very defect it
  guards, so the wired configuration answers it deterministically instead — and the behaviour the
  probe was there for is structural rather than assumed, since `sweep()` iterates exactly the
  directories the bean was constructed with, over which `ParquetScratchOrphanSweeperTest` already
  drives every rule (age, prefix, pod-private cutoff, a missing directory); and "the run's tree" is **not** an ancestor directory named `build`,
  since an IntelliJ run configured to build with the IDE compiles to `out/` and a guard that goes
  red on a developer's build layout rather than on a regression is worse than no guard — it is the
  Gradle-supplied root when present, the checkout (located by `settings.gradle.kts`) otherwise.
  Test-only — no production code, REST, gRPC, DTO, migration,
  production configuration-key, metric, S3-key or frontend change; `k8s/` and the deployed
  `*_TEMP_DIR` keys are untouched, so the sizing arithmetic of #131/#138/#150 is unaffected.
- prefix-walk-paged: The shared `ListObjectsV2` walk has a page-by-page form, and the orphan sweep
  uses it, so its heap is bounded by a page rather than by a site's history (issue #199, raised
  reviewing #198/#158). `S3PrefixLister.listAll` materialized a whole prefix into a
  `List<S3ListedObject>` before any caller could filter it, and `DeltaS3OrphanSweeper` then copied
  subsets of that into `candidates` and `orphans` — one site's whole listing plus two derivations,
  on a scheduler thread, fleet-wide, beside the checkpoint fold budget (#152/#178) and the Parquet
  scratch budget (#150), neither of which knows about it. The peak is one site's object count rather
  than the bucket's, which is why #158 shipped as it was; what makes it worth a ticket is that the
  sweep's **first deleting pass is by construction the largest listing this application ever takes**
  — its own premise is that superseded generations accumulated nightly for months with nothing
  reclaiming them. New `S3PrefixLister.forEachPage(client, bucket, prefix, Consumer<List<…>>)`
  returns `S3PrefixWalk(objectsRead, truncated)`; `listAll` is now that walk with a collecting
  consumer, so there is one implementation and the truncation contract of #122 is literally the same
  object in both — pages already handed over stand, the flag says the walk stopped early. **The
  complete-listing callers keep their semantics unchanged**: the wipe compares every key against one
  instant and `requireCompleteKeys` (batch deletion, retention) needs the whole set or none, so both
  still call `listAll`. One behaviour the split *added*: the consumer runs **outside** the catch that
  classifies truncation, because this walk's consumer deletes objects and can therefore raise the
  very exception types the walk reads as "the listing stopped early" — a caller's failure reported
  as a short listing would be hidden behind a flag meaning the opposite. **What is bounded and what
  deliberately is not.** The listing is bounded by nothing; the row set is bounded by what still
  exists (retention prunes segments, `checkpoints` is one row per table). So the listing is consumed
  a page at a time and the row set stays **one read per site**, taken lazily on the first page that
  produces a candidate — the same single query #158 made, so no S3 call and no database call is
  added or removed and this is a heap change and nothing else. The cost is stated rather than hidden:
  for a site whose prefix fits one page — the overwhelming majority — "rows read after the listing"
  is unchanged, and beyond the first page that ordering guard is weaker, with the **age window**
  carrying it (a row can only appear for an object whose write is still in flight, and the window is
  a day past the longest such gap). The checkpoint pointer read with those rows is *older* than the
  pages that follow, and an older pointer protects strictly more keys, so `couldStillBeAdopted` errs
  only towards keeping the object. A failed row read now ends the **site**, every remaining page of
  it included, rather than the page that asked; the held-back and dry-run lines are accumulated and
  logged **once per site** with a ten-key sample, so an operator reads the same one line per prefix
  as before rather than one per page. `S3ChangelogSegmentStorage.walkPrefix` and
  `S3CheckpointStorage.walkPrefix` join their `listPrefix` twins — except on the segment side, where
  the materializing form had no caller left and is **removed** rather than kept as an invitation to
  restore the peak. **The delete is buffered rather than paged**, which review had to point out: a
  page and a `DeleteObjects` chunk are both 1000 keys, so deleting per page would have turned the
  sparse steady state — a few superseded objects every thousand keys — into one round trip per page
  on a tick that walks every site prefix in the bucket: a site of two thousand pages holding four
  thousand thinly spread orphans would have taken up to **two thousand** round trips where the
  whole-listing version took four.
  Orphans are judged per page and queued, every full chunk goes out during the walk and the
  remainder in the `finally`, so the peak is under two chunks of keys — the same order as the page
  the walk hands over — and the bound this ticket is about is untouched. Pinned by tests on both sides: the
  walk hands pages over separately, keeps the pages already handed over on a mid-walk failure, and
  does **not** swallow a consumer's `S3Exception`; the sweep sends orphans thinly spread over two
  pages in **one** round trip and flushes a full chunk mid-walk (a 1500-key page leaves as 1000 then
  500, so batching cannot become accumulating), reads the row set once for three pages, and — the
  assertion that actually pins the design — records "rows" *before* "walk-finished", which a
  materializing implementation cannot do. No REST, gRPC, proto, DTO, migration, configuration-key, metric-name,
  S3-key or frontend change. See `docs/delta-client-v2-guide.md` ("Objects no row references are
  reclaimed").
- s3-orphan-sweep: Objects under `delta/{siteId}/segments/` and `checkpoints/{siteId}/` that no row
  references are reclaimed, by one mechanism for both prefixes (issue #158, which folded **#160** —
  the same defect in the second prefix, and the hard part, proving an object is dead without a row
  to name it, is one design decision rather than two). Every object on these paths is written
  **before**, or independently of, the row that names it, and nothing reclaimed one that ended up
  with no row: both deleters collect keys *from* rows, the site history wipe walks these prefixes
  for the one site being wiped (#118/#122), `ChangelogRetentionService` prunes segments and never
  touches checkpoints, `ParquetScratchOrphanSweeper` (#127/#141) sweeps *local* scratch, and there
  is no bucket lifecycle rule for either. **The obvious fix is the wrong one and the ticket says
  why**: a compensating delete in the caller's `catch` cannot be safe, because an exception can
  surface *after* the transaction committed — an `afterCommit` synchronization or an `AFTER_COMMIT`
  listener throwing, and `BatchEventListener` and `BatchParquetFinalizationListener` both run there
  — so the delete would destroy a live, referenced object. Any compensation has to prove the
  transaction did **not** commit; a sweep proves the stronger thing directly, by asking the rows.
  `DeltaS3OrphanSweeper` does the same four things per site per prefix — list, keep the key shapes
  this application writes, drop everything younger than the age window, subtract the keys the rows
  still name — and the prefixes differ in one place only: which rows answer the last question.
  `changelog_segments.s3_key` for one; for the other the `checkpoints` keys **plus the frame at
  `site_sync_state.last_checkpoint_seq`**, the one live artifact no row stores, which is why
  `S3CheckpointStorage.frameKey` had to become public. **The site list comes from the bucket, not
  the database**, and that is not an optimization: `SiteService.deleteSite` hard-deletes the site
  row and touches neither prefix (its own Javadoc has asked for "periodic orphan detection" since
  2025), so a deleted site's objects outlive every row that could enumerate them — the population
  with the most to reclaim. `S3PrefixLister` gains the delimiter twin of its existing walk
  (`listChildPrefixes`, `S3ChildPrefixListing`), truncating the same way. **Every guard fails
  towards keeping the object**, which is the half worth reviewing: an object is a candidate only
  when S3 reports it strictly older than `delta.s3-orphan.min-age-seconds` (**24 h**, and a missing
  `LastModified` counts as new), which is what protects a segment mid-commit and — much the longer
  window — a frame between `uploadFrame(N)` and the `recordCheckpoint(N)` that adopts it; only the
  three key shapes the writers produce are candidates, so an artifact kind added later accumulates
  as everything did before rather than being deleted by a sweeper that never heard of it; the rows
  are read **after** the listing, so a row committed in between still protects its object; a row set
  that could not be read skips the site outright rather than reading "no rows" as "no references";
  and a truncated listing sweeps only what it read. Both directions are pinned by mutation — with
  the age filter removed four tests go red, with the frame protection removed one does — and by a
  LocalStack test for the three things a mock cannot prove (the delimiter listing really answers one
  prefix per site, the shapes match what the writers emit, the batched delete removes what it was
  handed). **On by default**, because an orphan is resolved by nothing else: `delta.s3-orphan.enabled`
  exists as the rollback, not as the safety. New keys `delta.s3-orphan.{enabled,min-age-seconds,
  sweep-ms,initial-delay-ms}` (24 h cadence, first pass 10 min after start — nothing here is crash
  recovery, unlike the queue-drain sweeps). New meters `delta.s3-orphan.reclaimed` and
  `delta.s3-orphan.delete-failed`, tagged `prefix=segments|checkpoints`, every series registered at
  zero; read them asymmetrically — under `segments` a steady rate means ingestion commits are failing
  after their upload, under `checkpoints` it is the ordinary superseded generation of every advancing
  build, so a **zero** rate there is the surprising reading. The tick joins the #146 inventory as
  `Cost.LONG` (it walks every site prefix in the bucket), which moves the connection-pool floor to
  `5 long ticks + 2 request reserve = 7 <= 10` — and the borrowed count is now an over-estimate by
  **two**, since neither the checkpoint build nor this sweep holds a connection across S3 (#164's
  rule: each repository call is its own short transaction). It is safe beside the other ticks for the
  reason every deleter there is: it only ever deletes objects older than a day that no row names,
  and everything a live build, commit or neighbouring tick is working on is younger by orders of
  magnitude. **The one number an operator must not get wrong** is the age window: below the longest
  possible checkpoint build a live frame can be deleted, and such a site reads as `history_gone` and
  gives its checkpoint rows up after `delta.checkpoint.max-materialize-attempts` nights (#149) —
  raising it costs only storage. No REST, gRPC, proto, DTO, migration, existing configuration-key,
  existing metric-name, S3-key or frontend change. See `docs/delta-client-v2-guide.md` ("Objects no
  row references are reclaimed", Metrics).
  **Two review findings changed the design rather than the prose.** The first: nothing establishes
  that the bucket belongs to *this* database, and this is the first deleter that reads "no rows for
  this site" as "dead" — two deployments sharing a bucket keep separate databases and therefore
  separate site ids, so each would have deleted the other's changelog and checkpoint seed ten
  minutes after startup. A site's own `sites` row is the ownership proof and is exact, so a prefix
  whose site this database has never heard of is left alone and logged unless
  `delta.s3-orphan.reclaim-unknown-sites` (default **false**) declares the bucket exclusive — which
  keeps the two populations this ticket was opened for (a failed commit's segment, every advancing
  build's superseded generation) reclaimed by default, since both belong to sites that still exist,
  and puts only the hard-deleted-site case behind the acknowledgement. The second:
  `delta.s3-orphan.delete-failed` counted the SDK's error *entries*, and `deleteObjects` records one
  per failed 1000-key chunk, so a bucket-wide denial — the case the meter exists for — would have
  read as a trickle; it counts `candidates - deleted`, which is truthful in both branches and makes
  the two counters sum to the candidate set. Three smaller ones: the age-window validation now runs
  only when the sweep is enabled (a rollback that crash-loops the pod on the value it is rolling
  back is not one), the integration test purges both prefixes before and after (checkpoint keys
  carry a sequence, not a run identity, so an earlier class's `_frame/seq=2/` would have made its
  precondition false-green — #168's lesson), and `delta.s3-orphan.reclaimed` says out loud that it
  is per replica. **Two things were deliberately not changed.** The sweep is *not* serialized across
  replicas: both obvious locks (`pg_advisory_xact_lock`, a session-level lock) would hold a
  connection for the whole walk, the exact hold #164 removed from the queue workers, while deletes
  are idempotent — an overlap costs a duplicate listing and a duplicate count, nothing else. And a
  detached `s3_key_parquet` is now **destructive with a one-day fuse**: the last good object of a
  table retired by #149 used to sit unreachable in the bucket and is now reclaimed. That is the
  intended reading of "no row ⇒ dead" — nothing ever re-attaches an old key, a repair writes a new
  object — and it is stated in the guide rather than special-cased, because no rule could tell that
  object from a superseded one.
  **Review round 2 changed the shipped defaults and closed one race.** The sweep now **reports
  instead of deleting** on a fresh deployment — `delta.s3-orphan.dry-run` ships **true**, logging
  one INFO per prefix with a count and a sample of keys — because the set it would take on a
  deployment running for months cannot be inspected after the fact and includes the last good
  `snapshot.parquet` of every table whose key `abandonStaleSnapshot` has ever detached, which an
  operator can still re-attach by hand today; the deployment that ships this happens before anyone
  reads a guide, so the acknowledgement had to be the default rather than the documentation. The
  race: a **checkpoint key is `seq`-addressed and therefore rewritable**, unlike a segment key with
  its freshly minted id — a build uploads at a sequence above the pointer and adopts it a moment
  later, so a frame that was weeks old and unreferenced at listing time could become the live seed
  before the delete landed, and the guard table's claim that every guard fails towards keeping the
  object was not true for that one. Anything at or above `last_checkpoint_seq` is now left alone;
  a stranded frame waits until the pointer passes it, which a live site does nightly, and a site
  with no sync-state row (the hard-deleted case) has no pointer and cannot be built either, so
  nothing there needs protecting. Three smaller ones: the delete is chunked in this class as well
  as inside `deleteObjects`, because that method catches `S3Exception` but not
  `SdkClientException`, so a network failure part-way through would have reported already-deleted
  objects as left behind; the ownership read joined the same catch as the row read, so a pool blip
  on one site no longer ends the pass; and the test profile pins `enabled: false` structurally
  instead of relying on a suite shorter than the 10-minute initial delay (#167's rule about
  incidental safety). The ownership claim was also corrected: the `sites` row proves **site-id
  knowledge, not bucket exclusivity** — a database restored from another environment's dump shares
  the ids — so that precondition is stated as one an operator checks by hand.
  **Round 3** found that the seq guard round 2 added had a hole of its own and closed three
  robustness gaps. The hole: `last_checkpoint_seq` is **zero** for a site that has never completed a
  build and for every site after a wipe or a re-baseline, so `seq >= pointer` made the whole
  `checkpoints/{siteId}/` prefix immune — a site wiped at five million would never have been swept,
  since its restarted counters take months or for ever to climb back, which is precisely the
  population the wipe's own truncated prefix walk (#122/#123) leaves behind. Zero is now read as
  "no checkpoint", the same test `CheckpointService` itself applies before seeding from a frame.
  Three more: an unparseable `seq` (the shape filter bounds the digits by nothing, so a key above
  `Long.MAX_VALUE` reaches the parse) now keeps its object, as its own comment already promised
  instead of throwing; `sweepSite` is wrapped per site, so anything escaping a path the inner
  catches do not cover costs one site rather than the rest of the pass **and** the second prefix;
  and the WARN says "see the failure logged above" instead of printing an empty error list on the
  throw path. Plus a third meter, **`delta.s3-orphan.candidates`** — counted before the dry-run
  branch, because with dry-run shipping true `reclaimed` stays flat at zero for ever and an alert
  written on it would page on a deployment that simply has not been switched to deleting yet; once
  the flag is off, `candidates - reclaimed - delete-failed` is zero by construction. One finding was
  **deliberately deferred with a ticket**: `S3PrefixLister` materializes a whole prefix, and this
  sweep's first deleting pass is by construction the largest listing this application ever takes —
  bounded by one site's history rather than the bucket's, a few megabytes for a site with fifty
  tables and two years of builds, and the same listing a wipe already takes, so it ships as is and
  the page-by-page walk is **#199**.
  **Round 4** was mostly about a contradiction this ticket introduced elsewhere:
  `spring.task.scheduling.pool.size` is derived as "the long ticks plus one for the burst of short
  ticks on the same second", and adding a fifth `Cost.LONG` tick spent that margin without the
  derivation beside the key being updated — the prose still said four of fifteen while the
  connection-floor paragraph in the same file already said five. **The pool moves 6 → 7**, which is
  what the comment's own "adding a scheduled task that blocks means revisiting this" prescribes; it
  stays below `spring.datasource.hikari.maximum-pool-size` (10), and the connection floor is
  unchanged at `5 long ticks + 2 request reserve = 7` because that term counts long *ticks*, not
  scheduler threads. `BackgroundConnectionDemandTest` caught it (its audited total moves 34 → 35),
  which is the guard doing exactly what #161 built it for. Three smaller ones: `listSites()` was the
  last S3 call outside a catch, so a failure the lister does not convert into a truncated result
  would have cost the *other* prefix as well; an unknown site now has its candidates **counted
  before** it is held back, since otherwise the population `reclaim-unknown-sites` governs is
  invisible until the flag asserting its precondition is already set — a dry run that cannot size
  the thing it exists to size; and both the guide and the meter's Javadoc now say that
  `delta.s3-orphan.candidates` is a **census, not an arrival rate** while the sweep is not deleting,
  because nothing removes the backlog between passes and a rate alert would read a static backlog as
  that many new orphans a day.
- delta-sql-inactive-branch-test: The delta-SQL queue's inactive-activation branch has a test
  (issue #175, the gap #159 named and deliberately left open). Since 026 `BitBiPlugin.execute` does
  nothing on `BATCH_COMPLETED` but wake the sweep worker, so the decision that an account without an
  **active** bit-bi activation gets no SQL lives in `DeltaSqlQueueService.processNextPending()` —
  and no test reached it: `BitBiDeltaSqlIntegrationTest` only ever drained with an active
  activation, while `SqlGenerationIntegrationTest`'s dispatcher test can only reach
  `PluginEventDispatcher`'s early return (pinned there through `last_used_at`). Two methods, one per
  **way into** the branch — a deactivated activation (the `isActive` filter) and no activation row
  at all (the empty `Optional`) — share one helper asserting all three observables of a segment the
  method seeded: no `plugin_sql_generations` row for that batch, `plugin_sql_at` **set**, and
  `sql.generation.delta.segments.skipped.inactive` incremented. The second is the property the
  ticket is really about — the branch is not a plain `return` precisely so those segments do not
  accumulate for ever — and the counter is what tells an operator a segment was *skipped* rather
  than lost. The counter is the assertion that needed care, and two review rounds went into it: the
  registry is **this** Spring context's while the row is claimable by any of them, so it is read as
  a **delta** (the series is shared with every other site's skips) across a queue that was **emptied
  first**, and the claim loop deliberately does **not** require this thread to win — a worker of
  this same context increments this same registry, and demanding the local call win the race was
  itself a flake. Only a peer in another cached context could move a different registry; that
  residual is what the hour-long `plugin.sql-generation.delta-sweep-ms` of the test profile (#159,
  #167) keeps improbable. Inferring "my call marked it" from *pending before, processed after* was
  an intermediate shape and was **strictly weaker** — it could not tell a peer's claim landing
  during the call from its own, and when it could not attribute at all it silently skipped the
  counter, so a build that stopped incrementing would have passed. #159's discipline is kept
  otherwise: scoped to the batches the method seeded, waited for rather than sampled.
  `drainQueue()` is now **bounded** by a deadline, which is a real consequence of the same
  property: a claimed-but-unmarked segment is handed straight back by `findNextPendingPluginSql`,
  so the loop never ends — the exact failure mode being pinned used to **hang** the suite instead of
  failing it. The bound is a wall clock and not an iteration count because the queue is **global**
  (no site predicate, and `test-data.sql` only clears the `%.example.com` sites), so a count "well
  above what this class seeds" would call another class's healthy backlog a stuck row; the message
  names both causes for the same reason. **The tests were
  proven red by mutation**, since the branch already exists and no test can start red against it:
  dropping `.filter(AccountPlugin::isActive)` fails the deactivated case (a generation appears),
  returning early instead of marking fails both (the bounded drain fires), and dropping the counter
  increment fails both. The metric name is a published contract and is unchanged. Test-only — no
  production code, REST, gRPC, DTO, migration, configuration-key, metric-name, S3-key or frontend
  change.
- scratch-directory-budget: One key bounds the whole file-backed Parquet scratch **directory**,
  where every guard before it bounded a single file (issue #150, split out of #138 and named as
  out of scope by #131 before that). `delta.checkpoint.max-temp-bytes` bounds one table snapshot,
  `delta.checkpoint.max-frame-temp-bytes` one reload frame, `delta.batch-parquet.max-temp-bytes` one
  completed-batch artifact — and the thing that evicts the pod is the directory they share, whose
  file **count** no per-file key can bound: a completed-batch build opens one scratch file per
  claimed table (#038), so a ten-table batch puts ten files on the 6Gi volume however low that
  ceiling is set. #138's deployed inequality
  (`2 x max(table, frame) + max-concurrent x batch <= sizeLimit - 1Gi`) was therefore a **floor on
  the guarantee, not the budget**, and it said so. New `delta.parquet.max-scratch-bytes`
  (`DELTA_PARQUET_MAX_SCRATCH_BYTES`, **0 = unbounded**, the shipped default, so an upgrade changes
  nothing) is held by `ParquetScratchBudget` and taken as a `ScratchLease` beside each scratch file,
  released when the file is deleted — after the delete, not after the upload, since the bytes are on
  the volume until the file is gone. **Charged as bytes are written, not reserved at the ceiling**:
  the ticket offered both, and the pessimistic form cannot work here, because the deployed ceilings
  are 1 GiB against artifacts in the low hundreds of MiB, so a three-table batch would reserve 3 GiB
  it never uses and be refused for ever on a 5 GiB budget. The two counting streams that already
  enforce the per-file ceilings (`CappedOutputStream`, `FileOutputFile`) do the charging, so a
  writer is stopped mid-file by either bound in the same place and by the same mechanism.
  **The cost of a shared running total is stated rather than hidden**, because it is exactly what
  #178 rejected for the heap twin of this budget: a total cannot choose *which* writer to refuse —
  the byte that crosses it belongs to whoever happens to be writing one — so two large writers can
  each take half and both be stopped where either alone would have fitted. #178 could answer that
  with exclusion (a fair `Semaphore(1)`, one fold at a time) because one fold at a time is a real
  mode of operation; disk cannot, because a batch build genuinely needs one open file per claimed
  table and serializing them means replaying the segments once per table — the multiplier #038
  removed. What **does** carry over from #178 unchanged is the rule about reporting: transient
  contention never becomes a tag value on a meter contracted to mean permanent. So
  `ScratchBudgetExceededException` is its own type (not a subclass of
  `ArtifactSizeLimitExceededException`, whose catches mean "this artifact is deterministically too
  big"), and a completed-batch artifact takes the ordinary
  backoff instead of the first-attempt `ABANDONED` its own ceiling earns it (which falls out of
  `DeltaParquetWriter.failure()` classifying only `ArtifactSizeLimitExceededException` as
  permanent), while on the **checkpoint** side — frame *or* table snapshot — the build ends, off
  `delta.checkpoint.builds.aborted` and off `delta.checkpoint.tables.unmaterialized` alike.
  **Two review rounds went into that last sentence and both corrections matter.** The first draft
  skipped the table and fell through to `abandonStaleSnapshot`, which on an advancing seq detaches
  `s3_key_parquet` — a healthy last-good snapshot 404ing for Bit BI, Parquet Export and the Delta
  Sync download, plus a spent `materialize_attempts` against `delta.checkpoint.tables.given-up`,
  for a neighbouring batch worker's disk use. Round 1 replaced that with leaving the row untouched;
  **round 2 showed that was worse**, and the case is worth remembering: the pointer still advanced,
  so the table's row stayed at the old seq with *nothing* marking it as owing a rewrite — the
  nightly rematerialize keys on a **null** `s3_key_parquet` — and a site that then went quiet served
  a snapshot silently missing every change in between, indefinitely, with retention having already
  pruned the segments below the new pointer. Sharper still on a site's **first** build, where
  `findOrCreate`'s row is never saved either: a refusal across every table left `checkpoints` empty
  with the pointer advanced, and `CheckpointFileQueryService` reads "no checkpoint rows" as "not a
  Delta site yet" and would have handed a Bit BI client the historical uploaded CSVs as its current
  baseline. Ending the build has none of that, and it is not a new policy but the one **#112 already
  wrote down** for an unusable scratch directory, in the same words: a systemic scratch failure must
  not be counted as per-table skips, because "skipping would detach every last-good snapshot while
  the pointer advanced". So `delta.checkpoint.tables.unmaterialized` gains **no new tag value** after
  all, and `delta.parquet.scratch.refused{writer=...}` is the whole of the reporting. The
  classification walks the **cause chain** (as `DeltaParquetWriter.failure()` already does for the
  per-file exception), because nothing wraps it today but a future wrap would fall through to
  `parquet_failed` and do the detach this decision exists to avoid. Two more corrections from round
  1: the refusal message printed the
  **whole** budget where it said "left to it" — with 4.9 GiB held by neighbours a writer refused for
  1 MiB read like the "artifact too big" verdict this type exists to distinguish itself from, and
  `DeltaParquetWriter.failure()` copies that text verbatim into `batch_parquet_artifacts.last_error`
  where it is the operator's primary diagnostic — so it now names the bytes actually free; and the
  refusal is counted **once per lease** rather than once per refused write, because `FileOutputFile`
  does not latch the way `CappedOutputStream` does (Parquet unwinds a write failure through a
  `close()` that still emits its footer), which would otherwise have reported two or more refusals
  per refused artifact and a different number for the frame. Two meters, both registered in the budget over the injected
  `MeterRegistry` (the `CheckpointGivenUpMetrics`/`S3CheckpointStorage` shape, avoiding a cycle with
  `DeltaMetrics`, which documents them without owning them): **`delta.parquet.scratch.refused`**
  `{writer=checkpoint_frame|checkpoint_table|batch_artifact}`, every value registered at zero so an
  alert predates the first refusal, and **`delta.parquet.scratch.bytes`**, a gauge of live reserved
  bytes that follows the writers **even when no budget is configured** — unbounded is the default,
  so `max_over_time` of that series against the volume is the only way to size the key before
  turning it on. Reservation is **chunked** at 1 MiB so a per-byte gzip write is not a per-byte
  atomic operation; the over-reservation that costs is at most one chunk per live writer, and it
  errs towards refusing early. Deployed value in `k8s/base/configmap.yaml` beside the `*_TEMP_DIR`
  keys, for #138's reason (the process cannot see how large the directory it was handed is):
  **5 GiB**, the 6Gi `parquet-scratch` `sizeLimit` less the gigabyte kept free for restart residue —
  which no lease covers, since the process that held them is gone — and for kubelet acting on usage
  *exceeding* the limit. Per JVM, so it is a true bound only where
  `delta.parquet.scratch-private-to-pod` (#141) holds, which the deployed `emptyDir` does.
  `ParquetScratchCeilingBudgetTest` drops the multiplier and asserts the subtraction, keeps the
  frame-wider-than-snapshot invariant and the manifest-drift guards, and adds one: every per-file
  ceiling must sit **at or under** the directory budget, since a ceiling above it can never be
  reached and would be dead configuration that reads as live. The per-file ceilings and
  `delta.batch-parquet.max-concurrent` are otherwise **unchanged** — they keep their per-artifact
  job, and the `2 x` #178 left conservative simply disappears rather than being retuned. **One
  asymmetry is documented rather than fixed** and was filed from the same review as **#193**: since
  #153 the frame is written first and is the largest file a build produces, and
  `CheckpointScheduler` walks sites serially, so a directory held full for the length of the 02:00
  sweep aborts *every* site's build at its first write and freezes retention fleet-wide for that
  night — where the batch side degrades one artifact at a time. Nothing is lost (the next night
  repeats the fold) and the pre-#150 behaviour was a kubelet eviction of the pod, which is worse,
  but the right split of the pool needs a number nobody has yet, which is what
  `delta.parquet.scratch.bytes` is now there to supply. Round 3 added three more, all small and all
  about the contract rather than the arithmetic: a closed `ScratchLease` now ignores a later charge
  (through a closed lease `granted` is zero again, so the whole file's bytes would be taken from the
  directory with nobody left to release them — a permanent shrink for the life of the pod, and the
  exact shape a future ordering slip would take); the "409, not 404" claim for a refused batch
  artifact is qualified in both guides, since the type distinction buys back the **first attempt,
  not the cap** — a directory full across the whole backoff window still exhausts
  `max-attempts` and ends `ABANDONED`; and the leases are released even when the scratch file could
  not be deleted, which is now stated with its reasoning rather than left contradicting the comment
  above it (holding one would shrink the directory permanently and end every later build, while an
  undeleted file is the same ownerless residue the reserved gigabyte and
  `ParquetScratchOrphanSweeper` already cover). No REST,
  gRPC, proto, DTO, migration, existing configuration-key, existing metric-name, S3-key or frontend
  change. See `docs/delta-client-v2-guide.md` ("Sizing note", Metrics),
  `docs/cr-unified-batch-parquet.md`.
- rollback-audit-guard: `Should not audit a regeneration that rolled back` guards its invariant at
  the listener that owns it, instead of through a path that stopped working (issue #172). **Neither
  of the ticket's two candidate causes was right, and the evidence was in the failure line all
  along**: both recorded reds — PR #166 sha `41a15110` and PR #180 sha `6f076501` — are
  `AssertionError at PluginHistoryIntegrationTest.java:127`, which is the fixture's
  `orElseThrow(... "fixture did not produce a generation")`, not the audit assertion twenty lines
  below it. That is #174: `plugin.sql-generation.heap-threshold-percent: 100` did not disable the
  memory-pressure abort, `generateSqlForBatch` returned `Optional.empty()` whenever the single
  `./gradlew test` JVM went above 99% of `max`, and the test could not build its fixture. Fixed in
  `4fe3150d`; the audit assertion had never once been the thing that failed. The **second** finding
  is why this is not a one-line ticket: that assertion had by then stopped being able to fail at
  all. #164 gave `SqlGenerationService.regenerateForBatch` a `refuseIfTransactionActive()` guard and
  left `PluginHistoryService.regenerateSql` `@Transactional` around it, so the call throws before it
  regenerates anything — and `assertThatThrownBy(...).isInstanceOf(IllegalStateException.class)`
  accepted that refusal as the test's own sentinel failure, since both are
  `IllegalStateException`. Zero audit rows then meant "nothing ran". Proven by adding
  `.hasMessage("caller fails after regenerating")`, which is red on `develop` against the #164
  refusal text — and the same hole is a **live 409 on both Regenerate SQL endpoints and the
  My Plugins button behind them**, filed as **#190** rather than fixed here, because moving the
  regeneration out of the caller's transaction is a behaviour decision with its own consequence for
  the audit (published with no transaction active, `fallbackExecution = true` writes it
  immediately, so no rollback can take it back — already true for `SQL_GENERATION_COMPLETED` since
  #164). So the vehicle is retired rather than repaired: the entry is published through
  `PluginAuditService.logSqlRegenerationCompleted`, the production writer of that action type, into
  a `TransactionTemplate` that then throws. **What that buys, point by point.** The account is the
  method's own `UUID`, so the count names what this method produced instead of every row the shared
  `TEST_ACCOUNT_ID` has (the ticket's isolation hypothesis, closed by construction — and with it the
  DELETE-then-count window a neighbour could land in). No batch, no CSV, no S3 and no
  `SqlGenerationService`, so the one thing that actually went red is gone rather than made less
  likely. And a **committing** publication is asserted right after the rolled-back one, because
  "zero rows" is exactly the assertion that cannot tell a working guard from a write that is broken,
  dropped or never wired — which is how this test came to assert nothing. Its power was checked the
  way #171 checked its own: with `AFTER_COMMIT` mutated to `AFTER_COMPLETION` — the escape the
  ticket hypothesised — the test is red, and review turned that one-off into a standing guard.
  Every test in `PluginAuditEventListenerTest` invokes a listener method **directly**, so the
  annotations that decide whether Spring calls it at all were pinned by nothing; two reflective
  assertions in the `BatchEventListenerPhaseTest` shape now hold the phase *and*
  `fallbackExecution` on both methods, so the same mutation is caught at unit cost and the
  Testcontainers test is no longer the only thing between the suite and a phase change. One hazard
  is deliberately **not** guarded here and is recorded on **#190** instead: `regenerateSql` writes
  after the generation returns (`markAsSuperseded` + save), so once #190 moves the generation out
  of the caller's transaction, a failure in those two lines rolls the supersede back while the
  entry — published with no transaction active, hence written at once by `fallbackExecution` —
  stands. That is this invariant reached by a route that does not exist yet.
  The `during(2s)` window and the sibling
  `shouldAuditRolledBackHistoryClear` (whose `clearHistory` vehicle still works) are untouched.
  Test-only — no production code, REST, gRPC, DTO, migration, configuration-key, metric, S3-key or
  frontend change.
- drop-comparison-executor: `AsyncConfiguration#comparisonExecutor` is gone — a `@Bean` declaring
  core 2 / max 5 / queue 10 and calling `initialize()`, with no `@Async("comparisonExecutor")` site,
  no `@Qualifier` and no injection anywhere in `src/main` or `src/test` (issue #165, found by #161's
  audit). **The first checkbox was a fork — delete it, or add the caller if an async comparison path
  was intended and lost — and the history settles it as deletion**: the executor and the method it
  was written for (`ComparisonService.createComparisonAsync`, quoted in the bean's own Javadoc)
  arrived together in `570a0ec9`, and `fd29ff4d` deleted the method two days later as "unused
  `@Async` infrastructure … synchronous processing is used for MVP" — both commits on the
  `009-markdown-user-story` branch, so the pool was orphaned **before** feature 009 ever merged and
  no caller has existed on `develop` for a day. The comparison feature is synchronous by decision,
  not by accident, and a future async path would want a pool sized for what it then does rather than
  this one. **What it cost while it sat there** is what the ticket is really about: it reads as five
  more threads until someone checks, which is expensive in exactly the place #161 had to enumerate
  every background pool that can take a database connection — the audit recorded it as `Hold.NONE`
  with a pointer to this issue rather than discounting it silently. Nothing about the connection
  derivation moves: `Hold.NONE` never entered a sum, so `AUDITED_BACKGROUND_THREADS` stays **34**,
  the floor stays `4 long ticks + 2 request reserve = 6 <= 10`, and the guide's list of pools never
  named it. **One operator-visible consequence**: Boot's `TaskExecutorMetricsAutoConfiguration` binds
  every `ThreadPoolTaskExecutor`, so `/actuator/prometheus` **loses** the
  `executor_*{name="comparisonExecutor"}` family — a series that only ever reported an idle pool
  (`executor_active_threads` 0 for the life of every pod), but a dashboard panel or a recording rule
  naming it will go empty rather than to zero. Nothing is renamed; the #146/#171 precedent is that a
  changed `executor_*` series is called out. `@EnableAsync` stays on the class, and the review
  corrected the reason: `PluginAsyncConfiguration` carries it too, every `@Async` site in the
  application is an `@Async("pluginExecutor")` method in the plugin package, and the annotation is
  idempotent — so this one is **redundant** rather than load-bearing, kept as the application-wide
  declaration instead of leaving async proxying to depend on a configuration in the plugin package.
  The class Javadoc, which described only the
  deleted pool down to a usage example of the method that no longer exists, is rewritten around that
  and around `deltaRebuildExecutor` (the one pool left, reached by `@Qualifier` and a direct submit,
  not by `@Async`). The test-first half is the inventory itself: removing the entry from
  `BackgroundConnectionDemandTest` while the bean still existed failed
  `shouldFindExactlyTheAuditedExecutorBeans` and `shouldFindExactlyTheAuditedPoolConstructions`
  (`AsyncConfiguration.java` 2 → 1), so no new test was added. **What that guard is worth on a
  re-declaration is narrower than it looks**, and review made both the comment and this entry say so:
  the scan fails on *any* new `@Bean` returning an `Executor` and is satisfied once someone
  classifies it — `Hold.NONE` included, which is exactly what carried this bean through the whole of
  #161. It forces the judgement; noticing that the answer is "nothing can reach it" stays a
  reviewer's job. `specs/009-markdown-user-story/`
  is deliberately untouched: its `tasks.md` T061 and `quickstart.md` snippet record what that feature
  planned in 2025, and the plan was already contradicted by its own PR-review commit. No REST, gRPC,
  proto, DTO, migration, configuration-key, metric-**name**, S3-key or frontend change.
- checkpoint-scratch-test-isolation: `CheckpointParquetIntegrationTest` writes and lists its
  checkpoint scratch in a directory of its own instead of the machine-wide `java.io.tmpdir`
  (issue #168). The test profile declares neither `delta.checkpoint.temp-dir` nor
  `delta.batch-parquet.temp-dir`, so both fell back to the host's temp directory, and the leak
  assertion was a **difference** between two listings of it —
  `assertEquals(scratchBefore, scratchSnapshots(), …)`. **The direction of the failure is the
  interesting part**: the class never left a file, it *lost* a pre-existing one — `scratchBefore`
  held a `checkpoint-*` file the second listing no longer had, because another JVM's
  `ParquetScratchOrphanSweeper` had deleted it by age (#127, and unconditionally past that JVM's
  start where #141's flag is set). Any host process writing that prefix does it; two worktrees
  running `./gradlew integrationTest` at once (`/github-issue-runner`) do it routinely, which is how
  it was seen while working #149. Not a product bug and not a regression — possible from the day the
  assertion was written, and invisible on a rerun. `@DynamicPropertySource` now points both keys at
  directories this class creates, so the listing is **exact** rather than differential: it drops the
  `checkpoint-<site>` prefix filter and requires the directory to be empty, which also catches a
  future writer that picks another name. `@DynamicPropertySource` rather than `@TestPropertySource`
  because a literal path is the same string in every worktree and on every host — only
  `Files.createTempDirectory` is unique per JVM, which is the whole property being bought. It costs
  one more cached Spring context (the customizer is part of the context cache key), the same price
  the three classes already carrying `@TestPropertySource` pay; `application-test.yml`'s
  `minimum-idle: 0` is what makes an idle cached context cheap enough for that to be acceptable.
  The **checkpoint and batch-parquet directories are separate**, so a completed-batch queue drain in
  this context cannot put a file in the directory the checkpoint assertion requires to be empty —
  and overriding the batch key at all is what keeps this context's own sweeper off the host's
  `/tmp`. Audit of the ticket's second checkbox: every other listing assertion over a scratch
  directory (`CheckpointServiceTest`, `BatchParquetFinalizationServiceTest`,
  `ParquetScratchOrphanSweeperTest`) already lists a JUnit `@TempDir`, so this class was the only
  one on the shared directory. **Deliberately not fixed here**: every *other* integration context
  still boots a sweeper aimed at `java.io.tmpdir` and deletes any host file named `checkpoint-*` /
  `batch-parquet-*` older than four hours — the suite is the perpetrator as well as the victim —
  which is a whole-profile decision (`application-test.yml` plus a Gradle-supplied path) rather than
  an assertion, filed as **#187**. Test-only — no production code, REST, gRPC, DTO, migration,
  configuration-key, metric, S3-key or frontend change.
- audit-listener-own-lane: The deferred plugin audit write can no longer be handed back to the
  thread that published it (issue #171, the last of the two exceptions #161 documented rather than
  asserted away). `PluginAuditEventListener.onAuditEntryReady` carried `@Async("pluginExecutor")`
  + `@Transactional(REQUIRES_NEW)` + `@TransactionalEventListener(AFTER_COMMIT)`. On the normal path
  the hand-off makes `REQUIRES_NEW` equivalent to `REQUIRED`; with `pluginExecutor` full (10 threads,
  50 queue slots) its `CallerRunsPolicy` ran the listener **inline on the publishing thread**, which
  is inside the commit synchronization with its own `ConnectionHolder` still bound — Spring releases
  it in `afterCompletion`, not `afterCommit` — so the write asked the pool for a **second connection
  while holding one**, the single hold-and-wait shape in the application's background work. The
  second cost is the one easy to miss: an exception from an `afterCommit` callback propagates to the
  caller of `commit()`, so a 30 s `connection-timeout` surfaced as a failure of an operation that had
  already committed. **The ticket's second candidate — drop `REQUIRES_NEW` and rely on the
  `AFTER_COMMIT` guarantee — is wrong, and that is the correction this ticket needed**: on precisely
  the inline path, `REQUIRED` finds the publisher's transaction still `isExistingTransaction()`
  (`ConnectionHolder.transactionActive` is cleared in `doCleanupAfterCompletion`, after the trigger),
  joins a transaction that has already committed, and the row is silently lost — which is what the
  method's own comment had always warned about. So the fix is the first candidate, made specific:
  **a lane of its own plus a guard**, deliberately both. New `pluginAuditExecutor` (2 threads, queue
  500) carries the two listener methods; `pluginExecutor` keeps `CallerRunsPolicy` untouched for
  plugin dispatch and the immediate `PluginAuditService` methods, which are plain `REQUIRED` and do
  not have this shape. Two threads because each task is one INSERT, and a 500-deep queue because
  filling it means the database is not accepting writes at all. **The hand-off is an explicit
  `execute`, not `@Async`** — the correction review forced, and the reason is the log line: a
  rejection handler receives the `@Async` proxy's opaque `FutureTask` and could only ever print pool
  statistics, so a burst of drops would be forty identical lines naming none of the activations,
  reinits or rotations that went unrecorded. Submitting by hand puts the `RejectedExecutionException`
  where the entry still is, so the ERROR names plugin, account and action; the executor therefore
  keeps the default `AbortPolicy`, which is safe precisely because the listener catches it rather
  than letting it reach the publisher (that is what a `void @Async` rejection would have done, at
  submit time, on the publishing thread — the very failure being removed). The guard is the half
  that survives a future edit: `persist` refuses outright when
  `TransactionSynchronizationManager.isActualTransactionActive()`, so the invariant is a property of
  the write rather than of the executor wired in front of it. It needed the transaction to move off
  the listener — a new package-private `PluginAuditEntryWriter` bean owns the `REQUIRES_NEW`, since a
  `@Transactional` method invoked on `this` is not proxied and the check has to run *before* the
  transaction opens (the `DeltaSessionCommitTransaction` shape of #147). Two smaller review
  corrections: the listener's catch logs the exception rather than only `getMessage()` (the failure
  that surfaced this carried no message at all), and this pool **discards its queue on shutdown**
  (`shutdownNow`, 5 s for the two in-flight writes, daemon threads) where the siblings wait 60 s for
  a queue a tenth the size. Round 2 corrected that last one twice over: waiting *looks* like the
  conservative choice and is not, because an orderly shutdown keeps executing the whole queue while
  `awaitTerminationSeconds` only bounds how long the container blocks — and `ThreadPoolTaskExecutor`
  threads are not daemons, so those 500 INSERTs would hold the JVM open past
  `terminationGracePeriodSeconds: 30` and produce the SIGKILL the bound was written to prevent, in
  the middle of the slow-database episode that filled the queue. The test moved with it: it now
  asserts the queued tasks **never ran**, since "shutdown() returned quickly" is true either way.
  Two more from round 2: the rejection ERROR quotes the refusal's own message, because
  `TaskRejectedException` also means "executor shutting down" and sending an operator to the database
  during a routine rollout is the same defect the previous fix removed elsewhere; and one gain worth
  recording that came free — the catch now sits **outside** the `REQUIRES_NEW` boundary, so a failed
  `save` no longer marks the transaction rollback-only and throws `UnexpectedRollbackException` out
  of the listener, which on the old inline path went straight into `commit()`. Proven by an integration test on the wired application, because the
  listener is invoked by Spring's transaction synchronization and the inline path exists only under
  saturation: it fills the executor **until it actually refuses** (submitting exactly
  `max + queueCapacity` would flake, since the pool is the shared context's singleton and an earlier
  class's write in flight frees a slot a moment later), publishes inside a real transaction and
  asserts the row is absent the instant the publishing thread returns — an entry already committed by
  then can only have been written by that thread inside `afterCommit`. Verified red against an inline
  executor with the guard removed, which is what a saturated `CallerRunsPolicy` degenerates to. It
  extends `BaseIntegrationTest` so it joins the shared context rather than caching one more, each of
  which drains the queue workers once at refresh (#167's residual) — two unrelated delta classes went
  red in CI before that. `BackgroundConnectionDemandTest` drops the exception from its class documentation (the
  ticket's third checkbox), adds the new bean at `Hold.SHORT` and moves the audited total **32 → 34**;
  the floor is unchanged at `4 long ticks + 2 request reserve = 6 <= 10`, since the new threads are
  short holders. **The trade an operator is buying**: under saturation — and now also when the pod
  stops — the entry is lost rather than written late, which is new; before, it was written inline at
  the cost of the publisher. No REST, gRPC, proto, DTO, migration, configuration-key, metric-**name**,
  S3-key or frontend change; `/actuator/prometheus` does gain an
  `executor_*{name="pluginAuditExecutor"}` family, because Boot binds every `ThreadPoolTaskExecutor`
  (the #146 precedent for calling a new series out). See
  `docs/delta-client-v2-guide.md` ("The deferred plugin audit write has a lane of its own").
- shared-fold-heap-budget: The checkpoint fold's heap ceiling is a reservation for the **process**,
  not a fresh allowance every build gets a copy of (issue #178, raised reviewing #177).
  `delta.checkpoint.max-fold-bytes` (#152) is enforced by one `BudgetedFold` per build, and one JVM
  holds two: `CheckpointScheduler`'s `ReentrantLock` serializes the cron thread alone, while
  `DeltaCheckpointRebuildService` runs `rebuildFromFrame` on the separate single-thread
  `deltaRebuildExecutor` (and `resumePendingRebuilds()` fires one at startup). Two folds at 45% of
  the budget each therefore crossed nothing, were refused nothing, left
  `delta.checkpoint.builds.aborted` at zero — and `OOMKilled` the pod with in-flight ingest, which
  is the exact failure #152 exists to replace. **Exclusion rather than a shared running total**, the
  ticket's second option instead of its `AtomicLong` sketch, and the reason is what the sketch could
  not answer: when the sum crosses, the record that crosses it belongs to whichever build happens to
  be applying one, so the nightly build of a small site could be refused because an operator clicked
  "rebuild" on a large one — a regression against the path #152 actually protected — and that
  refusal would have to be reported somewhere, which for a condition clearing the moment the
  neighbour finishes cannot be `builds.aborted` without breaking that meter's "never repairs itself"
  contract (#153). New `CheckpointFoldBudget` (a **fair** `Semaphore(1)`) lets one build fold at a
  time, which is what makes the existing number a bound on the process at all; a collision's victim
  is deterministically the build that arrived second, and its outcome is a **deferral**. The
  reservation covers the **whole build**, not the fold loop — the folded state is what
  `writeSnapshots` iterates, so the heap is held until the last table is uploaded. A build that
  cannot have it within new `delta.checkpoint.fold-wait-seconds`
  (`DELTA_CHECKPOINT_FOLD_WAIT_SECONDS`, default **600**; 0 = do not wait, negative treated as 0)
  throws `CheckpointFoldBudget.BuildDeferredException` — thrown rather than returned as an empty
  fold for the #157/#162 reason, since `DeltaCheckpointRebuildService` cannot tell an empty fold
  from a finished build — and is counted on new **`delta.checkpoint.builds.deferred`** (untagged,
  registered at zero so an alert predates the first occurrence). `CheckpointScheduler` logs it,
  skips retention for that site (the pointer did not move) and carries on to the next;
  the forced rebuild **releases** `rebuild_requested` and says to ask again, settled like the #157
  read denial and not like the #162 shutdown, because nothing re-drives a held flag here — the
  nightly tick calls `buildCheckpoint`, never `rebuildFromFrame`, and `requestRebuild`
  short-circuits while the flag is set. Two rounds of review then corrected **the wait**, and each
  correction is load-bearing. It is paid **once per pass, not once per site**
  (`buildCheckpoint(siteId, mayWait)`): a holder that never finished would otherwise turn a
  200-site tick into `200 x` the wait, over which `CheckpointScheduler`'s own `tryLock` skips the
  following nights and retention freezes for **every** site rather than the contended one — the
  sweep now keeps visiting and simply stops paying the wait, resuming the ordinary terms as soon as
  a site does get the budget. Round 2 then caught the opposite extreme in that same fix: latched for
  the rest of the tick, one 40-minute forced rebuild would cost the **whole night** (every remaining
  site missing its non-blocking acquire), so deferred sites are collected and retried in **one**
  further pass at the end — a tick spends at most two waits, and a passing collision costs only the
  sites visited while it lasted. Round 2 also moved the budget **in front of the reads**: `run()`
  loaded the sync state, the segment list and the frame's presence and only then queued for the
  budget, so a rebuild parked behind the sweep folded a segment list up to `fold-wait-seconds` plus
  a whole build stale — and since the neighbour advances the pointer and `ChangelogRetentionService`
  then deletes the below-checkpoint objects, the waiter woke up and folded keys that were gone. The
  window existed before this ticket but was a few S3 round trips wide; now everything is read inside
  the exclusion, at the cost of an idle visit holding it for one query and one HEAD. Round 3 then
  named the residual out loud instead of leaving it implied: the per-pass rule bounds the **tick's
  duration, not the collision's reach** — a holder outlasting both waits defers every site and the
  night builds nothing — and that is the deliberate trade against a stuck build parking a scheduler
  thread and the build lock for `N x` the wait across the following nights; the deployment-level
  answer is the key, which `delta.checkpoint.fold.wait` now measures. Round 3 also found that the
  latch was dropped only on a clean return (so a read denial, which fires for **every** site of a
  tick, left the rest of the pass probing although the collision was over); that
  `delta.checkpoint.builds.deferred` counted the non-blocking probes as well, ~400 increments per
  collision whose prescribed remedy — raise the wait — is wrong for 398 of them, so only a
  **spent** wait is now counted and logged at WARN (`waitWasSpent()`); and that the permit was
  acquired outside the `try` whose `finally` releases it, so a throwing meter would have leaked the
  process's only permit for the life of the pod.
  It is **shutdown-aware** (sliced `tryAcquire` re-reading `ApplicationShutdownSignal`),
  because `deltaRebuildExecutor` has `waitForTasksToCompleteOnShutdown(true)` and non-daemon
  threads — Spring never interrupts a task parked there, so an unaware wait would hold context close
  for the whole `awaitTerminationSeconds` and then time out; a shutdown ends the wait as a deferral
  and `DeltaCheckpointRebuildService` settles *that* one as #162 (flag kept) rather than as #157
  (flag released), and `CheckpointService` re-checks the signal immediately after acquiring so an
  inherited budget does not fold a whole site during the termination grace period. And the wait got
  a meter, **`delta.checkpoint.fold.wait`**: the budget is taken *outside* `phase=total`, so a
  deferred build contributes no duration sample at all (a ten-minute wait would otherwise be the
  maximum an operator reads that timer from) — **and neither does a build that waited nine minutes
  and then ran**, which `builds.deferred` also misses because it did run, so without this series
  contention was invisible right up to the first deferral. A wait **cut short** by the shutdown is
  kept off that counter (`BuildDeferredException.endedEarly()`): it is not contention, and a rollout
  that catches a build waiting must not move an alerting series — #162's rule again. The wait is
  also clamped at a day, because saturating seconds→millis moved the overflow into the nanosecond
  deadline, where a very large key collapsed the wait to one 500 ms slice. Per JVM
  deliberately — heap is per pod, so N replicas have N budgets exactly as they have N heaps; no
  distributed lock is implied and `CheckpointScheduler`'s "run the sweep on one instance" note is
  unchanged. Fairness plus taking it **per site** is what bounds the wait: a rebuild queues behind
  one site's build rather than behind the whole sweep. **The `2 x` in the disk formula is now
  conservative** rather than tight, since two checkpoint builds can no longer overlap at all — but
  it is deliberately left where it is, because the deployed ceilings and the directory-wide
  reservation are **#150**, for which this exclusion is a candidate shape. No REST, gRPC, proto,
  DTO, migration, S3-key, existing configuration-key or frontend change. See
  `docs/delta-client-v2-guide.md` ("The first bound is heap", Metrics).
- heap-threshold-disable: `plugin.sql-generation.heap-threshold-percent: 100` disables the
  memory-pressure abort, which is what it was always taken to mean and never did (issue #174).
  `SqlGenerationService.isMemoryPressureHigh()` compared `>=` against a reading that
  `getHeapUsagePercent()` **ceiling**-rounds, so any usage strictly above 99% reported 100 and
  tripped a threshold of 100 — `generateSqlContent` returned null, `generateSqlForBatch` an
  `Optional.empty()` that reads as "no changes detected", and nothing retried. **Three** places set
  100 with a comment saying they were switching the check off: `application-test.yml` (so **every**
  Spring integration test that generates SQL carried the latent abort, surfacing as a
  `SqlGenerationServiceTest` assertion or an `awaitGenerationFor` timeout in whichever class was
  running when the heap of the single `./gradlew test` JVM got close enough to `max`),
  `SqlGenerationServiceTest`, and `SqlGenerationConcurrencyTest` — the third named by review, and
  the one that matters most as evidence, since it is a class already tracked as intermittently red
  on a clean tree. The comparison is now **strict**, which is the ticket's first option
  and the only one of its three that fixes both halves at once: the reading is clamped at 100, so
  100 is a true off switch, **and** for an integer threshold `T` the identity `ceil(x) > T ⟺ x > T`
  removes the rounding artifact the ticket's third option was aimed at — the predicate is exactly
  "usage is strictly above `T`%", where before it fired from `T-1` upwards. A documented sentinel
  (option 2) was rejected as the only one of the three that leaves the arithmetic wrong for the
  shipped default of 80. Production behaviour at 80 shifts by up to one percentage point in the
  direction of aborting **less**, and the threshold now means what `application.yml` says beside it.
  The clamp is the load-bearing half of "100 disables it": without it the guarantee would rest on
  the collector never reporting `used > max`, and a reading of 101 would abort while startup had
  just logged the check as disabled — so `heapUsagePercent(used, max)` is a pure function tested
  directly, and `getHeapUsagePercent()` only reads the `MemoryMXBean` into it. `init()` also logs
  the value and whether it disables the check, because the abort's only other signal is
  `sql.generation.aborted.memory_pressure` (name unchanged) and it does not name the batch: an
  aborted generation is indistinguishable from an empty one at the caller, and on the **admin
  regeneration** path it is worse than silent — `doRegenerateForBatch` persists a
  `-- No changes detected` artifact and `PluginHistoryService` then supersedes the original, so an
  abort there replaces a good generation with an empty one. That is pre-existing and out of this
  ticket's scope (both call sites are **#181**), but it is why the abort was expensive rather than
  merely wrong. Since it is the only signal, `init()` also **registers the counter at zero** — the
  `DeltaMetrics`/`delta.checkpoint.builds.aborted` treatment, so an alert can predate the first
  occurrence instead of appearing with it; the name is unchanged, but `/actuator/prometheus` now
  carries the series from startup rather than from the first abort. Five generation-path tests stub the reading (1/10/80/81/100 against thresholds of
  0/80/100) instead of observing the real heap, and three pin the arithmetic itself: the ceiling at
  79.01%/80.00%/80.01%, the clamp at `used == max` and `used > max`, and 0 for an undefined maximum.
  Left undone deliberately and filed as **#185**: the key is still accepted unvalidated, so a
  mistyped `800` disables the guard and a negative value aborts every generation — silently, by way
  of #181. No REST, gRPC, DTO, migration, configuration-**key**, metric-**name**, S3-key or
  frontend change. See
  `docs/020-sql-generation-optimization.md`.
- hold-connection-across-s3: A HikariCP connection is no longer held across S3 (or a 120 s
  semaphore wait) at the three call sites the pool audit named (issue #164, folding **#176**).
  `DeltaEgressService.egressNextPending` and `DeltaSqlQueueService.processNextPending` drop
  their wrapping `@Transactional`; the claim query and the mark write are the repository's
  short transactions, and S3 runs with nothing open. A crash between them leaves the row
  pending and the sweep retries — the same keys are overwritten. `generateSqlForBatch`
  acquires the semaphore *before* any transaction and **throws** if one is already open, so
  the 120 s wait cannot pin a connection and the hold cannot return silently.
  `SqlGenerationPersistence` is the proxied home of `loadBatchData` /
  `saveGenerationRecord` / `loadBatchDataForRegeneration` — they were `protected` and
  self-invoked, so their `@Transactional` was inert. `ParquetExportFileService.listFiles`
  queries the catalog through `ParquetExportCatalogQuery` (`@Transactional(readOnly=true)`)
  and only then probes S3; a row is still dropped only on a **known** absence (#157) and
  dropped candidates still advance the cursor. Both queue workers move from `Hold.LONG` to
  `Hold.SHORT` in `BackgroundConnectionDemandTest`; the floor beside
  `maximum-pool-size` is now `4 long ticks + 2 request reserve = 6` against the unchanged
  10. The #171 audit-listener exception is untouched. No REST, gRPC, proto, DTO, migration,
  configuration-key, metric, S3-key or frontend change. See `docs/delta-client-v2-guide.md`
  ("No S3 inside a queue worker", "The connection pool is smaller than the threads that can
  ask it for a connection").
- test-profile-sweep-cadence: A newly added queue-drain sweep can no longer keep production cadence
  under the `test` profile unnoticed (issue #167, the hole #159 closed for one key).
  `ScheduledTaskTestProfileCadenceTest` sits beside the #146 inventory: every `@Scheduled` interval
  whose placeholder ends in `sweep-ms` must be **declared** in `application-test.yml` at ≥ 1 h
  (inheriting a coincidentally-slow production default is not enough — that is how
  `plugin.sql-generation.delta-sweep-ms` stayed at 60 s for the life of 026), every tick whose
  effective test-profile period is shorter than that hour is on an explicit allowlist with a reason,
  and every `*sweep-ms` task that still fires at context refresh (`initialDelay` 0 / unset) is on a
  second allowlist. The two remaining sub-hour ticks are hardcoded and so cannot be slowed from YAML
  — `BatchTimeoutScheduler` (`cron 0 */5 * * * *`) and `DeviceAuthorizationService` (`fixedRate`
  300000) — and are allowlisted: the first only UPDATEs batches already past
  `batch.timeout.minutes` (test-data seeds `IN_PROGRESS` at `CURRENT_TIMESTAMP`), the second is a
  bulk UPDATE of expired `device_authorizations` that `test-data.sql` does not seed. Two
  property-backed ticks that *could* be slowed were: `delta.ingestion.staged-sweep-millis` and
  `provisional-sweep-millis` (5 min / 15 min) plus an explicit
  `delta.parquet.scratch-orphan-sweep-ms: 3600000` so a later cut of the production default cannot
  restore a fast tick. **No production default moved** — `plugin.sql-generation.delta-sweep-ms`
  stays 60000; an `initialDelayString` on the three queue workers would delay the crash-recovery
  pass by 60 s and is deliberately not added. The residual is still one drain per cached context at
  refresh. No REST, gRPC, DTO, migration, S3-key or frontend change.
- checkpoint-fold-heap-bound: The checkpoint path's first bound is heap, and it is now a refusal
  rather than an `OOMKilled` (issue #152, raised reviewing #151). #112 and #126 put the per-table
  snapshot and the reload frame on disk, and #138 set the deployed ceilings below the volume — but
  the two consumers that scale with a **site's row count** were still in heap on the same path, so
  on a 2–3Gi pod the disk ceilings were never what a growing site reached first. **Three copies were
  removable and one was not.** `S3CheckpointStorage.downloadFrame` read the gzipped frame into one
  `byte[]` and `ChangelogCodec.parse` expanded it into a `List` of every record in the site;
  `CheckpointService.materialize` then collected every new segment's records into a second list
  before a single `ChangelogFold.fold(seed, …)` call whose first act is `deepCopy(initial)` — four
  full-site copies at the peak, of which the `deepCopy` (a whole second fold, live at once) is the
  one the ticket's own description missed. Now `openFrame` returns an `InputStream`, the mirror of
  the `uploadFrame` that has streamed from a file since #126, and new
  `ChangelogFold.apply(state, record)` folds one record **in place** — so the frame, then each
  segment, is folded as it arrives and every record is dropped immediately; `fold(initial, records)`
  keeps its copy-then-apply contract for the callers that do have a starting state to preserve
  (tests, and nothing in production). Peak: ~4 full-site copies → 1. **What remains is the fold
  itself**, still one entry per surviving row for the length of the build, which is why the second
  half is a ceiling and not more streaming: `delta.checkpoint.max-fold-bytes`
  (`DELTA_CHECKPOINT_MAX_FOLD_BYTES`, **0 = auto = half the max heap**) aborts the build
  with `FoldTooLargeException`, an ERROR naming site and key, and
  `delta.checkpoint.builds.aborted{reason=fold_too_large}` — a fourth value on #153's meter,
  deliberately, because it is a **permanent** abort by that meter's contract: a site's history does
  not shrink on its own, so every following tick ends identically with retention frozen at the
  pointer. Nothing durable is written, because the fold precedes the frame upload — the build's
  first side effect since #153. Three decisions a reviewer should weigh: **the unit is estimated
  retained heap, not serialized bytes** (`ChangelogFold.estimatedRetainedBytes`, a coarse per-row
  object-graph figure over map entries, column-name strings, values and the row's identity string) —
  `delta.ingestion.max-session-bytes` counts wire bytes and assumes a 3–5x retained ratio, which
  does not transfer to a fold that duplicates key columns and re-parses every column name per row,
  where the ratio runs to ~20x for narrow rows and no single wire-byte ceiling could be both safe
  and useful; **the budget is derived, not declared beside the deployment** (the opposite of #138's
  scratch ceilings, and for a stated reason — a process cannot see how large its scratch volume is,
  but it can always see its own `-Xmx`), so no ConfigMap change and
  `ParquetScratchCeilingBudgetTest` is untouched; and **half rather than the quarter capacity
  planning asks for** — the second review round's main finding, and the correction matters: the
  `2 x` of the scratch budget plus live ingest says a quarter, but this ceiling is not a capacity
  plan, it is the last line before an OOMKill and its refusal is permanent (retention freezes with
  the pointer). Since the seed path used to hold two to three full-site copies, a site building
  successfully **today** can have a fold near half the heap — a quarter would have refused it on
  the first tick after the deployment that made its build cheaper, which is the one regression this
  ticket could introduce. A quarter is available explicitly for whoever wants the headroom, and
  one night at DEBUG on `CheckpointService` sizes the key against real folds before it is lowered. The running total costs one
  addition per record, not a walk: `apply` returns what each record did to the state's size. A build
  logs its **peak** estimate at DEBUG, **WARNs at 75%** of the budget and records it on new
  **`delta.checkpoint.fold.bytes`** (a summary; an aborted build and an idle visit are both absent
  from it on purpose), so the band below the ceiling can carry an alert instead of living in a log
  sink — the same reasoning #153 used for the abort counter. The ceiling is **per build, not per
  process**: two concurrent folds at 45% each still exhaust the heap, which takes a forced rebuild
  beside the nightly sweep and is the heap twin of #150, filed from this review as **#178**. A site
  approaching the cliff is visible before the first abort — the peak and not the final size, raised in review: the
  ceiling is enforced on the running total, so a fold that rises and falls back (a night's inserts
  followed by the deletes retiring them) would otherwise stay silent until the tick whose peak
  crosses. Three more review findings, all in the same
  region: an abort mid-frame recorded `download_frame=0` and charged the transfer to `fold` (the
  counter is now written by `foldFrame` whichever way it ends); `foldFrame`'s `IOException` catch
  was unreachable for the failure it named, since `ChangelogCodec.forEach` wraps every read and
  parse failure into an `UncheckedIOException` mentioning neither site nor key — both are caught now
  and re-thrown as the `CheckpointStorageException` the removed `download` used to produce; and
  `ChangelogSegmentService.forEachRecord`'s `finally` **replaced** an in-flight exception when
  closing a partially consumed S3 stream failed, which is exactly what an aborted fold produces —
  the abort would have reached `CheckpointService` as an `UncheckedIOException`, missing its counter
  and its ERROR line, so the close failure is now suppressed onto the primary. `VALUE_BYTES` also
  went 24 → 40 (a generated protobuf message carries `memoizedSize`, an `unknownFields` reference
  and the oneof case), and the estimate's one blind spot is documented rather than papered over: a
  character is charged one byte, true for compact Latin-1 strings, so a site whose string data is
  Cyrillic or CJK is under-counted by roughly its string payload and wants a lower key. **Off-heap or spillable folding is deliberately not attempted** —
  the ticket's middle option, and the one that would have made this a rewrite; this is its stated
  interim, and the guide says so where the "later ticket" has been named since #112. Two meter
  notes: `phase=download_frame` keeps its meaning through `TimingInputStream` (the transfer now
  interleaves with the fold instead of finishing before it), and `phase=fold` **additionally covers
  the seed frame's fold**, which used to fall between the two phases and be timed by neither — the
  series' values move, its name and tag do not. No REST, gRPC, proto, DTO, migration, S3-key or
  frontend change. See `docs/delta-client-v2-guide.md` ("The first bound is heap", Metrics).
- s3-presence-tristate: A read denial and an absent object are two different answers again, at the
  call site that acts on them (issue #157, filed reviewing #154 and made durable by #149).
  `S3CheckpointStorage.exists` returned `false` for a genuine 404 and for a 403 alike. The
  404-as-403 half is correct and stays: HEAD answers 404 for a missing key only with
  `s3:ListBucket`, and without it S3 hides existence behind a 403, so reading that as absence is the
  only workable choice. The other direction was the bug — a blanket read denial on keys that *do*
  exist answers 403 as well, so one IAM or bucket-policy change made the checkpoint frame read as
  absent for every site in the same tick, tripping `delta.checkpoint.builds.aborted`, a meter whose
  documented contract is "aborts that do not repair themselves", for a condition a permission fix
  clears. **#149 raised the stakes from noisy to irreversible**: it decides `history_gone` by the
  same call, and such a site spends one `checkpoints.materialize_attempts` per tick, so after
  `DELTA_CHECKPOINT_MAX_MATERIALIZE_ATTEMPTS` (5) nights its rows give up and — having no segments —
  the site is named by neither work-list query and **does not recover when the permission does**.
  New `presence(String)` → `PRESENT|ABSENT|UNKNOWN` combines the ticket's first two options, because
  neither alone is enough: the tri-state without a probe would answer `UNKNOWN` for **every** missing
  key wherever HEAD cannot 404, while a probe without the tri-state would still have to collapse a
  real outage into one of the two lies. **The probe is a one-key `ListObjectsV2`, not the ranged
  `GetObject` the ticket proposed** — raised in review, and the correction is the crux: AWS applies
  the same existence-hiding rule to `GetObject` as to `HeadObject`, so a ranged read answers 403 for
  a missing key on exactly the deployment that needs resolving, and every absence would have
  degraded into `UNKNOWN` — sites skipped forever, `delta.s3.read-denied` counting one per missing
  key, which is precisely the noise the counter was named to avoid. `ListObjectsV2` is a **bucket**
  action with no such rule, and this application **requires `s3:ListBucket` anyway** (site wipe walks
  two prefixes, #118/#122; the nightly batch retention lists the batch prefix, #100), so the probe is
  decidable wherever the application can run at all. The result is matched against the exact key,
  since a prefix listing also returns a longer sibling. `CheckpointService` **ends the build** for a
  site whose frame presence is unknown: nothing folded, uploaded, saved or counted, no attempt spent,
  pointer and per-table keys untouched, retried next tick — deliberately **not** on
  `delta.checkpoint.builds.aborted`, since this one repairs itself. It is **thrown**
  (`FramePresenceUnknownException`) rather than returned as an empty fold, the second review
  correction and the same reasoning #162 used for the shutdown case: `DeltaCheckpointRebuildService`
  cannot tell an empty fold from a finished build, so a forced rebuild would have logged "completed"
  and spent the durable `rebuild_requested` flag on a build that never ran — on the very action the
  `history_gone` message names as the recovery. **Round 2 then corrected the other half**: it logs
  an ERROR rather than "completed", but it still **releases** the flag, because the shutdown
  analogy does not carry — a shutdown implies the restart that re-drives it, an incident does not,
  the nightly tick calls `buildCheckpoint` and never `rebuildFromFrame`, and `requestRebuild`
  short-circuits while the flag is set, so holding it would leave the operator unable to ask again
  once the permission returned. `CheckpointScheduler` logs the skip apart from a build failure,
  since during an outage it fires for every site and "build/retention failed" would send an operator
  to the sites rather than to the bucket policy. **The `s3:ListBucket` premise is checked, not
  assumed** (also round 2): one `ListObjectsV2` on `ApplicationReadyEvent` logs it confirmed or
  raises an ERROR naming the grant to add, without failing the context — otherwise a deployment
  lacking it would degrade quietly, every absence answering `UNKNOWN` and the new counter climbing
  by one per missing key. Round 3 bounded and aimed that check: it lists under **`checkpoints/`**
  rather than the bucket root (a grant with an `s3:prefix` condition would 403 at the root while the
  probe works, i.e. a false alarm on every pod start) and carries a **5 s** `apiCallTimeout` of its
  own, because a ready listener runs *before* Boot publishes `ACCEPTING_TRAFFIC` and the SDK default
  (30 s x 3) would have kept the pod out of the Service endpoints long enough to stall a rollout.
  The deployed task role does grant it (`deploy-script/template-1763397226530.yaml`), so on this
  deployment HEAD answers 404 for a missing key and the 403 path really is the rare one. Follow-up
  filed from round 3: **#176** — the Parquet Export listing used to probe S3 inside its read-only
  transaction, up to two round trips per row on the denial path, the sibling of #164 (folded
  into #164 and closed). New counter **`delta.s3.read-denied`** (registered in `S3CheckpointStorage`
  over the injected `MeterRegistry`, the `CheckpointGivenUpMetrics` shape — infrastructure must not
  depend on `delta.application`, and `DeltaMetrics` documents it without owning it) counts the
  **unresolved** denial only, which is also why the ticket's suggested `delta.s3.head-denied` name
  was not used. `deltaExists` → `deltaPresence`, `frameExists` → `framePresence`; `exists` stays as
  the yes/no form (`presence == PRESENT`, so an undecidable answer is never a yes) for the
  integration suite. Parquet Export's file listing drops a delta row only on a **known** absence, so
  a denial no longer hides a present download — and with the listing probe a genuinely missing object
  still resolves to `ABSENT` while object reads are denied, so dead links need read *and* list to be
  refused together. The `lossy_refold` caveat is **removed** rather than reworded in
  `docs/delta-client-v2-guide.md` and in `DeltaMetrics.checkpointBuildAborted` — the meter is a fact
  about the site's own data again — and the `history_gone` ERROR line drops its "check for a 403
  first" note for the same reason. Proven against LocalStack for the half a mock cannot: the probe
  tells a real object from a missing one and from a prefix sibling, through a real listing; only the
  denial is injected (`DelegatingS3Client`), because LocalStack community enforces neither IAM nor
  bucket policies. No REST, gRPC, proto, DTO, migration, configuration-key, S3-key or frontend
  change. See `docs/delta-client-v2-guide.md` ("S3 will not say", Metrics),
  `docs/parquet-export-plugin-guide.md`.
- checkpoint-table-verdict: A per-table checkpoint failure is now bounded when it is deterministic
  and not recorded at all when it is the process ending (issue #149, folding **#162**). Both halves
  are one decision in `CheckpointService` — *when is a per-table failure the table's verdict?* — and
  they pull opposite ways, which is why splitting them would have produced two fixes that fight.
  **The retry is bounded in attempts, not seconds.** V53 adds `checkpoints.materialize_attempts` +
  `last_materialize_failure_at` and a partial index on the null-key side; a row that ends a build
  without a snapshot spends one attempt, and at `delta.checkpoint.max-materialize-attempts`
  (`DELTA_CHECKPOINT_MAX_MATERIALIZE_ATTEMPTS`, default **5**) it drops out of the nightly
  rematerialize (#128) and stops naming its site on the tick's work list (#137). Attempts rather
  than a delay because the retry runs from a once-a-night cron: a backoff could only ever express
  itself as skipped nights, and a counter needs no clock to survive a restart. What is bounded is
  the **dedicated** retry — a site with new segments is visited for its segments and the incremental
  build there writes every table in its fold whatever the counter says, since that work is happening
  regardless; a failure that leaves a still-valid last-good key spends nothing, because the retry
  exists for rows with nothing to serve. Giving up is neither silent nor final: new gauge
  **`delta.checkpoint.tables.given-up`** (`CheckpointGivenUpMetrics`, snapshot-cached like
  `delta.batch-parquet.queue`) is the standing signal that replaces the nightly alarm — without it
  the fix for "retried forever" would have been "silent forever", each retired row being a table
  permanently absent from the Bit BI files listing, Parquet Export `type=checkpoint` and the Delta
  Sync download — and four things re-arm a row: submitting the schema and letting the next
  incremental build write it, `POST .../delta/checkpoints/rebuild` (which re-arms deliberately,
  whether or not that attempt succeeds), a re-baseline, a wipe. **The state with no exit at all was
  a different one**: a table whose last row was `DELETE`d at the source stays in the fold for the
  build that sees the deletion (an empty inner map) but `CheckpointFrame` emits no record for it, so
  the next frame never mentions it and both snapshot passes, which iterate the fold, could never
  reach it again — while its row survived (only a wipe or a re-baseline deletes checkpoint rows) and
  named its site nightly for work not even a forced rebuild could do. That row is now **deleted** by
  the build that notices, through the epoch guard like every other write — **after** that build has
  written its own tables and **never all of them** (both raised in review): an empty fold would take
  every row, and `CheckpointFileQueryService` reads "no checkpoint rows" as "not a Delta site yet"
  and would serve pre-Delta uploaded CSVs as the current baseline. Sparing them re-opened the hole
  one round later (the per-table settle lives inside the snapshot loop, which an empty fold never
  enters), so a site whose whole fold is empty is now **settled site-wide** — one attempt per
  retryable row, a re-arm on a forced rebuild — and drains like every other unfixable state. The object it named joins the
  superseded snapshots already unreferenced under `checkpoints/{siteId}/` (#118, sweeper is #160). The third unfixable state, found in round 2 of #148's review, is a site whose frame is
  unreadable with **no segments behind it**: `historyPruned` is unconditionally true there, so it
  raised "refusing lossy refold" every night — wrong in kind, since with no frame and no changelog
  there is no history to refold, lossily or otherwise. It gets its own message and
  `delta.checkpoint.builds.aborted{reason=history_gone}`, and a scheduled build spends an attempt
  on every still-retryable row of the site, which is what drains it (a **forced** rebuild re-arms
  them instead — raised in review: the documented recovery must not be the fastest way to exhaust
  the retry it restores): such a site is on the work list *only* because of those rows. The drain is
  durable where the pre-#149 alarm self-healed, and `S3CheckpointStorage.exists` reads a **403 as
  absence** by design, so a multi-night IAM read outage retires those rows and leaves the site
  reachable only by a forced rebuild — accepted (an outage that long has already broken every
  checkpoint download) and documented, with #157 the fix at source. `reason=lossy_refold` keeps its meaning for a site whose segments survive —
  real data, an alarm that must keep shouting, and a site visited for those segments anyway, so no
  counter could ever quiet it. **Part 2 (#162) is the opposite verdict.** Spring publishes
  `ContextClosedEvent` and *then* destroys the singletons, so a build still running when a pod is
  replaced calls a closed `S3Client` or `HikariDataSource` and fails in a way indistinguishable from
  a broken table; the per-table catch recorded that, `abandonStaleSnapshot` detached
  `s3_key_parquet` on the advancing seq, and the table answered 404 until the next nightly
  rematerialize — for a build that was not failing, only ending. New
  `shared/lifecycle/ApplicationShutdownSignal` (a one-way flag set by the event, therefore set
  *before* anything is closed — a `@PreDestroy` of our own would answer "no" for exactly the window
  that matters) is checked before the frame upload, between tables and inside the per-table catch:
  the build ends with last-good keys, pointer and attempt counters untouched, and
  `CheckpointScheduler` stops walking sites for the same reason. Deliberately **not** on
  `delta.checkpoint.builds.aborted`: that meter's contract is aborts that never repair themselves,
  and this one is repaired by the process that replaces it — **except on the forced path**, raised
  in review: a scheduled tick is found again by the nightly work list, while
  `POST .../delta/checkpoints/rebuild` has only its durable `rebuild_requested` flag, so
  `DeltaCheckpointRebuildService` now leaves that flag set (and does not log "completed") when the
  build ended on the shutdown, and `resumePendingRebuilds()` re-drives it after the restart. It makes #146's "do not interrupt the
  pool" belt-and-braces rather than the single load-bearing setting. **Fourth**, the "is there
  anything to rematerialize?" probe moved *before* the frame download and the fold (raised in #148's
  review, deferred here), so an idle visit costs one query against `checkpoints` instead of a
  whole-site fold thrown away — and it reads *retryable* rows, since a row that has given up is
  unmaterialized forever and would otherwise keep the visit expensive after the retry stopped.
  #137's invariant is untouched and pinned: a site with every table materialized and no leftover
  segments is not visited at all. No REST, gRPC, proto, DTO, S3-key or frontend change — the
  given-up state is deliberately not on `DeltaCheckpointResponseDto`, since adding a field there is
  a Zod/API contract change the gauge and the guide cover without one. See
  `docs/delta-client-v2-guide.md` ("When is a per-table failure the table's verdict?", Metrics).
- sql-generation-test-isolation: The two plugin SQL-generation integration classes assert on the
  generation they produced instead of sampling every generation a site has (issue #159, folding
  #163). `plugin_sql_generations` is never named by `test-data.sql` — it is emptied only as a side
  effect of three `ON DELETE CASCADE`s, for the accounts and sites whose
  `LIKE '%@example.com'` / `'%.example.com'` filters match, and only at the instant the script
  runs — so `findBySiteId(SITE)` + `hasSize(n)` + `get(0)` counted rows no test method owns. The
  same line was seen failing **both ways**, which is why narrowing the query alone is not the fix:
  "was 2" when something else wrote a generation for the site during the method, "was 0" when the
  test's own row was not visible yet. Assertions now name the source batch
  (`findBySourceBatchId`, unique) **and** wait for it with awaitility; where `hasSize(1)` was
  standing in for "the baseline batch produced none", that is now said directly and scoped
  (`assertNoGenerationFor`), and the negative assertions hold for half a second rather than
  sampling once, so a late writer fails the test instead of slipping past.
  `BitBiDeltaSqlIntegrationTest` keeps the day-wide `findBySiteIdAndCreatedAtAfter` call the
  `/sql-changes` ordering assertions are about, and filters it to the batches the method seeded.
  `SqlGenerationIntegrationTest`'s `findAll()).isEmpty()` — an assertion that the whole shared
  database held no generation at all — became a test of the guard that actually exists. Two rounds
  of review were spent on it and the second correction was the interesting one: since 026
  `BitBiPlugin.execute` only calls `DeltaSqlSweepWorker.wake()` on BATCH_COMPLETED, so **no**
  assertion about a generation can pin `PluginEventDispatcher`'s early return — the generation
  comes from `DeltaSqlQueueService` draining segments, and a site with no segments produces none
  whatever the dispatcher does. The observable that does carry it is `last_used_at`, stamped by
  `PluginUsageService` only after a dispatch executed the plugin: the test gives the second account
  a **deactivated** activation and asserts the stamp stays null, which fails if the `isActive`
  predicate is removed. The queue's own inactive-activation branch stays untested and is **#175**. The widest single change is one line of
  `application-test.yml`: `plugin.sql-generation.delta-sweep-ms: 3600000`, the slow-sweep treatment
  `delta.egress.sweep-ms` and `delta.batch-parquet.sweep-ms` already had and 026 never gave this
  queue. At the shipped 60s the tick fired in **every cached Spring context for the whole run**,
  and `DeltaSqlQueueService.processNextPending()` renders inside its transaction, S3 included — so
  a context whose test class had finished long ago could hold row locks on `changelog_segments`
  while the next class's `@Sql("/test-data.sql")` tried to delete those rows, which is the
  `ScriptStatementFailedException` that took `BitBiDeltaSqlIntegrationTest`'s two follow-on methods
  down with the first. The explicit wake (`DeltaSqlSweepWorker.wake()` from `BitBiPlugin` on
  BATCH_COMPLETED and from `PluginDeltaBaselineService` on reinit) is untouched, so the paths the
  tests exercise still run. **It does not close the window entirely** and the key says so:
  `@Scheduled(fixedDelayString=…)` carries no initial delay, so every context still drains once
  when it is created — one drain per cached context instead of one per context per minute, with
  the annotation-level fix and the guard that would have caught the missing key tracked in #167.
  `clearAppSettings()` moves from `BaseIntegrationTest` to `AbstractIntegrationTest` —
  `SqlGenerationIntegrationTest` extends the latter — and is joined there by
  `clearPluginSqlGenerations(UUID...)`, which deletes **by site** rather than table-wide: an
  unqualified `DELETE` would take exactly the locks whose collision with `@Sql` this ticket is
  about, which is the one way `clearAppSettings`'s single-row precedent does not carry over. It is
  called in both classes' `@BeforeEach` and pinned in the #119 shape by a leftover-then-clear test,
  whose own assertions on the site are by content rather than by count — a guard test must not
  re-introduce the shape it retires. Audit of the same assertion shape elsewhere: the eight
  occurrences in `PluginHistoryIntegrationTest` all sit inside `@Disabled` nested classes and were
  left alone, and its live `Should not audit a regeneration that rolled back` — which went red on a
  PR touching no plugin code while this one was open — is a **different** defect kept out of this
  window: `plugin_audit_logs` counted by account, where the interesting hypothesis is not isolation
  at all but an audit surviving the caller's rollback through the listener's
  `AFTER_COMMIT` + `fallbackExecution` + `REQUIRES_NEW` (**#172**). Test-only — no production code,
  REST, gRPC, DTO, migration, production configuration-key, metric, S3-key or frontend change.
- hikari-pool-sizing: `spring.datasource.hikari.maximum-pool-size` **stays 10**, and now says why
  (issue #161). What the ticket was missing was never the number — it was the derivation and a guard
  that keeps it true, which is `BackgroundConnectionDemandTest`, a **wider** audit than #146's, where
  the scheduler pool is only one term. Its first result was that the ticket's own list was an
  undercount: not `6 + 2 + 2 + 2` but **32** threads, because `pluginExecutor` (max 10),
  `pluginExecutionExecutor` (max 8), `deltaRebuildExecutor` (1) and the `batch-parquet-lease`
  renewal thread (1, an `Executors.newSingleThreadScheduledExecutor` that no inventory would surface
  because it is neither a bean nor a config key) hold connections too — and **both request layers are
  unbounded** on top of that (virtual-thread-per-request; grpc-java's default cached pool, since
  `GrpcServerConfig` never calls `.executor(...)`). **So "cover the peak" was never available**: 32
  per replica times seven pods is a quarter of a thousand connections. What makes a pool smaller than
  its callers *safe* rather than reckless is that **background work does not hold one connection while
  waiting for a second** — the shape that turns a shortage into a deadlock instead of a delay — so a
  thread that cannot get one waits 30 s and runs again on its next tick. `CheckpointEpochGuard`'s
  `REQUIRES_NEW` runs under a non-transactional build and `CheckpointRecordedEvent` is published
  outside the guard's transaction. **Two exceptions exist and review corrected the first draft, which
  had claimed there were none**: `PluginAuditEventListener` is `REQUIRES_NEW` *and* `AFTER_COMMIT`, so
  with `pluginExecutor` saturated (10 threads plus 50 queue slots) `CallerRunsPolicy` runs it inline on
  the publishing thread, which still holds its own connection because Spring releases it in
  `afterCompletion` rather than `afterCommit` (**#171** — the easy misreading is that
  `PluginAuditService`'s own methods are `REQUIRED`, which they are; the listener is not); and the
  delta-SQL worker pins a connection while waiting on a semaphore whose permit holders need
  connections from this same pool to release it (part of **#164**). Both are timed, so they cost a
  delay rather than a permanent stall, and both are documented rather than asserted away. The size therefore comes from two bounds: a **floor** over
  the consumers that cannot absorb a wait because they pin a connection across S3 I/O instead of
  releasing it between statements, and a **ceiling** from the cluster, since the pool is per replica,
  `max_connections` is not, and a cron tick fires on every replica in the same second —
  `(maxReplicas 6 + maxSurge 1) x 10 = 70`, plus 10 for
  `superuser_reserved_connections`/psql/migrations/exporters, against PostgreSQL's default 100.
  **The floor's scheduler term is four, not six**, and that correction is the whole reason the number
  did not move: the pool has to reserve for the four ticks `ScheduledTaskInventoryTest` already
  classifies `Cost.LONG` (the test now exports that count, so adding one tightens both derivations at
  once), not for six threads — counting the whole pool would repeat exactly the conflation of "holds a
  thread" with "holds a connection" that this audit exists to undo, and it is what first pushed the
  answer to 12. `4 + 2 (egress) + 2 (delta-sql) + 2 for requests = 10` at the time; **#164** later
  took the two workers out of the floor (`4 + 2 for requests = 6`). **Raising it was rejected as
  the one genuinely dangerous option**: the 100 is an assumption (`DB_URL` comes from a secret, and
  the budget also assumes nothing else shares that server, though dev and stage reach `(3+1) x pool`
  each), no observation of `hikari_connections_pending` exists, and overshooting `max_connections`
  fails as `FATAL: sorry, too many clients already` on every replica rather than as a slow query.
  The ceiling does allow up to **12** at the default, so the two bounds leave a window rather than
  pinning one value; the runbook to use it (read `SHOW max_connections`, record it, raise
  `DEFAULT_MAX_CONNECTIONS` in the test) is beside the key. The test discovers the inventory **three
  ways** so a new pool fails the build instead of the connection pool: every `@Bean` returning an
  `Executor` (re-invoking each zero-arg factory to read the `maxPoolSize` actually declared, and
  *failing* rather than skipping on a shape it cannot read, so the check cannot pass vacuously),
  every `max-concurrent` property, and every pool constructed directly in `src/main/java`. The
  cluster inputs are read from every manifest, base and overlays, selecting HPAs by `kind` and
  `scaleTargetRef` rather than by filename, and a percentage `maxSurge` fails outright because it
  cannot be budgeted for. Two findings were filed rather than folded in: **#164** —
  `DeltaEgressService.egressNextPending` and `DeltaSqlQueueService.processNextPending` are both
  `@Transactional` around S3 round trips, the second around a **120 s** wait on the SQL-generation
  semaphore as well, which is why four of the ten slots are spoken for (and
  `SqlGenerationService.loadBatchData`/`saveGenerationRecord` are `protected` and self-invoked, so
  their `@Transactional` is inert); **#165** — `comparisonExecutor` is a dead bean, five threads with
  no `@Async` site and no injection. **No value changed anywhere**: documentation, one new test and
  two shared helpers on the old one. See `docs/delta-client-v2-guide.md` ("The connection pool is
  smaller than the threads that can ask it for a connection"), `README.md`.
- scheduler-pool: `@Scheduled` runs on a pool this application declares instead of on whatever
  Spring Boot's auto-configuration happened to pick (issue #146). `SchedulingConfiguration` builds
  the `taskScheduler` bean from Boot's own `ThreadPoolTaskSchedulerBuilder`, and
  `spring.task.scheduling.pool.size: 6` (standard key, so `SPRING_TASK_SCHEDULING_POOL_SIZE`
  overrides it; no placeholder of our own) is the size. **The ticket's option 1 — set the key and
  stop — would have changed nothing**: with `spring.threads.virtual.enabled=true` Boot builds a
  `SimpleAsyncTaskScheduler`, that key configures a builder whose product is never used, and the
  bean is what makes it live again. The diagnosis needed the same correction. Under virtual threads
  a cron or fixed-rate tick is handed off to a fresh virtual thread, so the flagship scenario (the
  02:00 checkpoint build postponing the scratch sweep) was **already** isolated; what really shared
  one thread were the eight **fixed-delay** ticks, which Spring runs on the scheduler's own thread
  by design, since fixed-delay semantics need the previous run to have finished. The load-bearing
  scratch sweep (#141) is one of them, so the exposure was real — just through the batch-parquet,
  egress and SQL sweeps rather than through the checkpoint build. It also rested on a flag nobody
  would connect to scheduling: turning virtual threads off silently drops the whole application to a
  single scheduling thread, which is the reported failure exactly. Six is derived and asserted, not
  chosen: **above** the four tasks that can hold a thread for minutes (checkpoint build, retention
  cleanup, the orphaned-provisional sweep in its 500-segment worst case, and the batch timeout sweep
  once a backlog makes its unbounded query long) so a short tick always finds a thread, and **below**
  `spring.datasource.hikari.maximum-pool-size` (10) — which says the scheduler alone cannot empty
  the connection pool, not that total background demand fits, since the queue workers and the plugin
  executors hold connections too; that wider audit is **#161** above. Two tests carry it:
  `ScheduledTaskIsolationTest` runs the shipped property set and proves a blocking fixed-delay tick
  and a blocking cron tick each leave a neighbour running (it also pins the auto-configured
  scheduler's serialization, so a framework change is visible), and `ScheduledTaskInventoryTest`
  fails whenever a `@Scheduled` method is added, removed or moved — the audit of what may now run
  beside what is only defensible against a known list. That guard immediately found **two tasks the
  ticket's list of eleven missed**: `DeltaIngestionService#evictStaleStagedSessions` and
  `#sweepOrphanedProvisionalSegments`, which spell the annotation out in full and are therefore
  invisible to a grep for `@Scheduled` (fifteen tasks in total, counting
  `BatchRetentionScheduler`'s programmatic cron trigger on the same scheduler). The audit found no
  task unsafe beside the others: each owns its rows or its files, the ones that could collide
  already carry their own guard (`ReentrantLock`, `AtomicBoolean`, `FOR UPDATE SKIP LOCKED` claims
  with leases), and the only genuinely new pairing — the two provisional-segment deletes — is one
  the base deployment's two replicas could already produce. A pool also stops a periodic task
  overlapping itself — and changes what a rollout does to a running one, so three shutdown settings
  are fixed in code rather than left to Boot's defaults: the threads are **daemon**, they are **not
  interrupted** on context close (which is what they were as virtual threads), and the queue of
  not-yet-due ticks is **dropped** — a plain shutdown still runs queued *delayed* tasks and every
  cron tick is queued as one, so without it the pod would sit with parked threads until the monthly
  partition job came due, and any `await-termination-period` would be certain to time out. Waiting
  also opts out of Spring's early-stop signal, so a small `ThreadPoolTaskScheduler` subclass shuts the
  executor down on `ContextClosedEvent` — otherwise the scheduler keeps triggering until its own bean
  is destroyed, past the `@PreDestroy` of its peers, and `BatchRetentionScheduler`'s programmatic cron
  (which nothing cancels) could start a cleanup on a closed `DataSource`. `shutdownNow()` would
  not merely stop the 02:00 build — `CheckpointService` catches the interrupt per table and detaches
  that table's snapshot key, so a deployment at the wrong minute would leave a 404 behind until the
  nightly rematerialize; a doomed build should die with the process instead
  (`spring.task.scheduling.shutdown.await-termination-period` still applies if a deployment wants to
  wait). Virtual threads stay
  on for the web layer; they never reached `@Async`, whose sites all name an `Executor` bean that
  made Boot's virtual-thread `applicationTaskExecutor` back off. Only scheduling is pinned. **One metrics note**: Boot's
  `TaskExecutorMetricsAutoConfiguration` binds a `ThreadPoolTaskScheduler` and did not bind the
  `SimpleAsyncTaskScheduler` before it, so `/actuator/prometheus` gains an
  `executor_*{name="taskScheduler"}` family — nothing renamed or removed, and read
  `executor_queued_tasks` there as "not yet due" (a `DelayedWorkQueue` holds every future tick, ~15
  at rest), not as a backlog. No REST, gRPC, DTO, migration, S3-key or frontend change. See
  `docs/delta-client-v2-guide.md` ("One sweep interval means the tick runs when it is due").
- frame-first-checkpoint: The checkpoint reload frame is written before the per-table snapshots it
  gates, and a frame that cannot fit its ceiling is counted (issue #153, reachable in production
  since #138 lowered the deployed ceilings onto the volume). `CheckpointService.materialize`
  serialized the frame *after* every table's Parquet had been written, uploaded and saved at the new
  seq — yet the frame is the one artifact that cannot be skipped, so crossing
  `delta.checkpoint.max-frame-temp-bytes` ends the build. Crossing it is **deterministic for the
  same fold**, so the 02:00 tick repeated the abort every night, and each repeat had already paid
  for a full set of per-table uploads the pointer then never adopted: a `checkpoints` row is one per
  `(site, table)` and carries a single key, so the previous seq's objects were unreferenced the
  moment the next build wrote its own, and nothing but a site wipe sweeps `checkpoints/{siteId}/`
  (#118) — one orphaned generation per night, indefinitely. The frame now goes first, so an abort
  costs nothing durable (no snapshot object, no row, pointer untouched as before). It is uploaded
  and **deleted before the snapshot loop**, not held open across it: the deployed budget is
  `2 x max(table, frame)`, one file per build path and not one per artifact (#131/#138), so holding
  both would silently double the checkpoint term — `stillKeepsOneCheckpointScratchFileOnDiskAtATime`
  pins that. New counter `delta.checkpoint.builds.aborted{reason=frame_too_large|lossy_refold}`,
  registered at zero from startup so an alert predates the first occurrence; `delta.seq.lag` stays
  the companion series (the counter says why, the lag says how bad). The tag values are the two
  aborts that **do not repair themselves** — the second is the pre-existing "refusing lossy refold",
  equally permanent and equally invisible until now, so an alert written on this meter cannot miss
  half the population; an unreadable scratch directory and an S3 refusal cost one tick and are
  deliberately absent, as is a build discarded because the site's history was replaced (#136/#142),
  which is a normal outcome. One caveat is documented in both the meter's Javadoc and the guide:
  `S3CheckpointStorage.exists` treats a **403 as absence** on purpose (least-privilege IAM has no
  `s3:ListBucket`, so HEAD-on-a-missing-key answers 403), so a read outage trips `lossy_refold` on
  every pruned-history site at once — many sites in one tick is a permissions incident, one site
  alone is the real thing. Distinguishing them at source is **#157**, filed from this review.
  The existing per-table
  `delta.checkpoint.tables.unmaterialized` is untouched and the two must not be confused: this one
  is the whole site's pointer, and with it retention. **Deliberately no backoff and no UI surface** —
  the retry is now free in storage terms, and suppressing it (or flagging the site in Delta Sync)
  needs per-site state the abort by design does not write; that is the same state #149 will have to
  decide on for its per-table twin. What a repeat still costs, and what the guide now says out loud:
  retention stopped, the checkpoint snapshots frozen at the last successful seq for Bit BI / Parquet
  Export / the UI (stale, never wrong — before #153 they were rewritten nightly at a seq the pointer
  never adopted, which *is* the write that orphaned the previous generation), and a table detached
  by #128/#149 left unrepaired, since the rematerialize runs on the idle `RETRY_MISSING` pass and a
  site whose frame is oversized is never idle. `CheckpointEpochGuard` gains
  `requireEpoch(siteId, epoch)` — the same row-lock check with nothing to write — called once
  **before** the frame. Writing and PUTting a multi-GiB frame is the longest stretch of a build that
  touches no row, and putting it first would otherwise have made the build's first contact with the
  `site_sync_state` lock come *after* it instead of before: a wipe that had already committed would
  be noticed only once its object was in the bucket, and a stalled pre-wipe PUT landing after a
  new-epoch build wrote the same seq would overwrite a fresh frame with the discarded fold — the
  resurrection the guard exists to stop. The check keeps that window exactly as wide as it was when
  `writeSnapshots` ran first; it is not held across the S3 call (`REQUIRES_NEW`, committed before).
  The residual window remains and is pinned by
  `leavesAnOrphanFrameWhenTheWipeCommitsAfterThePreCheck`: a wipe committing after the check leaves
  the frame object as well as the snapshots a discarded build has always left — the same
  already-accepted litter, spared by the wipe's own cut-off (#122) so a
  second wipe collects it (giving that prefix a sweeper of its own, rather than only the wipe, is
  **#160**, filed from this review as the sibling of #158). It is harmless **not** because the new epoch never reaches that seq (a
  wipe resets the client's counters, so the site re-traverses the same range and a later build may
  legitimately end there and overwrite it) but because a build only ever seeds from the frame at
  `last_checkpoint_seq`, and `uploadFrame(N)` always precedes the `recordCheckpoint(N)` that names
  it. `Files.createDirectories`
  moved out of `writeSnapshots` into `prepareScratchDirectory()`, called by both paths, so an
  unusable scratch directory still aborts the build (#112) rather than failing as a scratch-file
  error. No REST, gRPC, DTO, migration, configuration-key, S3-key or frontend change. See
  `docs/delta-client-v2-guide.md` ("A frame that does not fit", Metrics).
- ingestion-commit-no-s3: The ingestion commit no longer performs S3 I/O while it holds database
  locks (issue #147, raised reviewing #142). `DeltaSessionCommitService.commit` was `@Transactional`
  and called `ChangelogSegmentService.persist`, whose `PutObject` therefore ran inside the
  transaction — every row lock it had taken, and a HikariCP connection, held for the length of an
  upload that takes seconds for a large FULL_SNAPSHOT tail. #142 made one consequence concrete:
  `DeltaRebaselineService.reset` takes the `site_sync_state` row lock **before** it deletes anything
  (it has to — loading the row last left a window between the checkpoint deletes and the epoch bump),
  and `reset` is the commit's first statement, so on that path the per-site mutex spanned the upload.
  Nothing was incorrect (what waits on that lock is short, and a guarded checkpoint write is about to
  be refused anyway), but it contradicted the invariant this subsystem states everywhere else —
  `CheckpointEpochGuard`'s "No S3 traffic happens inside the lock", `DeltaSiteWipeService` and
  `DeltaRebaselineService.deleteOldObjectsAfterCommit` deferring every object delete to
  `afterCommit`. **Upload first, transaction second** (the ticket's first option):
  `ChangelogSegmentService.prepare(siteId, batchId, mode, firstSeq, records)` serializes, hashes,
  mints the segment id, uploads, and returns a new `PreparedSegment`; `persistPrepared` /
  `persistPreparedProvisional` write the row from it. `DeltaSessionCommitService` keeps its public
  API and becomes a **non-transactional** orchestrator; the unchanged one-transaction body (reset →
  row → publish provisional → watermark → complete batch, plus the `afterCommit` egress wake) moved
  to a new `DeltaSessionCommitTransaction` bean — a separate bean because a `@Transactional` method
  invoked on `this` is not proxied, so the transaction would have started in the wrong place.
  `persist`/`persistProvisional` remain as the composed pair for callers with no transaction of their
  own (test fixtures), and `prepare` **throws** if an actual transaction is active, so the hold cannot
  return silently. #142's ordering is untouched: `reset` still takes the lock first and still runs
  before the tail row is written, and its own S3 deletes stay on `afterCommit`. **The rollback
  trade-off is not new** — the upload was never compensated on rollback when it sat inside the
  transaction either, and the key carries a freshly minted segment id
  (`delta/{siteId}/segments/{segmentId}.pb.gz`), so an object left by a failed commit is unreachable
  without its row; pinned by a test rather than left implicit. What the review added: the *window*
  is wider than before, because a failure inside `reset` (the row-lock wait behind a concurrent wipe)
  can now strand a full-size snapshot tail, and nothing sweeps `delta/{siteId}/segments/` — filed as
  **#158**. A compensating delete in the caller is deliberately not the fix: an exception can also
  surface *after* the transaction committed (an `AFTER_COMMIT` listener throwing), and the delete
  would then destroy a live segment. Proven where it matters by an
  integration test that spies the real `S3ChangelogSegmentStorage` and asserts
  `isActualTransactionActive()` is false at the `PutObject`, on both the plain and the re-baseline
  path — the unit tests can only pin call order, since the transaction comes from a Spring proxy. No
  REST, gRPC, proto, DTO, migration, configuration-key, metric, S3-key or frontend change. See
  `docs/delta-client-v2-guide.md` ("No S3 inside the ingestion commit").
- split-scratch-ceilings: The checkpoint scratch ceiling is two keys, and the deployed values sit
  below the volume so the application refuses before kubelet evicts (issue #138). Since #126 one key
  governed two files with opposite failure semantics — an oversized per-table snapshot is skipped
  (`delta.checkpoint.tables.unmaterialized{reason=parquet_failed}`) and repaired by a later
  rematerialize (#128), while an oversized **reload frame ends the build**, because the frame is the
  next incremental seed. The single key therefore had to be set for the harsher of the two, which is
  why #131 left all the ceilings at 10 GiB above the 6Gi `parquet-scratch` `emptyDir` it declared:
  the volume was the binding constraint and its failure mode is a **kubelet eviction of the pod** —
  no skip, no metric, in-flight ingest dies with it. New
  `delta.checkpoint.max-frame-temp-bytes` (`DELTA_CHECKPOINT_MAX_FRAME_TEMP_BYTES`) bounds the frame
  alone and defaults to the **same 10 GiB** the shared key carried, so an unset key behaves as
  before — and unset it inherits `delta.checkpoint.max-temp-bytes` rather than a literal, so an
  operator who had lowered the single key for a small disk does not silently lose the frame bound;
  `delta.checkpoint.max-temp-bytes` keeps its name and its per-table, graceful meaning, and
  `delta.batch-parquet.max-temp-bytes` keeps its name, its default and its per-file scope.
  **The application defaults did not move** — the
  process cannot know how large the directory it was handed is, so the values that must sit below
  the volume are declared beside it, in `k8s/base/configmap.yaml` next to the `*_TEMP_DIR` keys
  (the split #141 used for `scratch-private-to-pod`). They are the sizing note's own worst case
  solved for the 6Gi volume with a gigabyte kept free for restart residue and for the fact that
  kubelet acts on *exceeding* the limit —
  `2 x max(table 1Gi, frame 1.5Gi) + max-concurrent 2 x batch 1Gi = 5Gi <= 6Gi - 1Gi`,
  the `2 x` being the two checkpoint build paths (the cron sweep and a forced rebuild on
  `deltaRebuildExecutor` are not mutually excluded), each holding one file at a time.
  `ParquetScratchCeilingBudgetTest` recomputes that from the manifests — reading the batch
  concurrency from the ConfigMap or the `application.yml` default — requires the **frame** ceiling
  to be the wider of the two, fails if an overlay redefines any side, and fails *closed* if the
  temp dirs and the mount drift apart, so the volume and the ceilings cannot separate silently.
  The batch term assumes **one claimed table per build**, which makes this a
  **floor on the guarantee, not the budget**: a real build opens one file per claimed table, a
  count no per-file key can bound — the directory-wide reservation
  (`delta.parquet.max-scratch-bytes`) is filed as **#150** rather than folded in here.
  What an operator must know before this deployment change: **none of the three refusals repairs
  itself** when the artifact is deterministically oversized. A checkpoint table is skipped *and*
  has `s3_key_parquet` detached on a seq-advancing build, so it 404s for Bit BI / Parquet Export
  and the nightly rematerialize fails identically (#149); the **frame** aborts the build, freezing
  the pointer and retention (#153, filed from this review, since fixed the orphaning half and added
  `delta.checkpoint.builds.aborted` — the freeze itself still needs the key raised); a completed-batch artifact is `ABANDONED` on the first attempt and
  404s until the key is raised and the row requeued (039's admin route). In each case the records
  themselves stay in the segments — what is lost is the derived artifact. Raise the frame ceiling
  first, and remember it costs two GiB of volume per GiB. No REST, gRPC,
  DTO, migration, metric, S3-key or frontend change. See `docs/delta-client-v2-guide.md`
  ("Sizing note"), `docs/cr-unified-batch-parquet.md`.
- checkpoint-tick-work-list: The nightly checkpoint tick no longer walks past a site whose changelog
  was pruned to nothing (issue #137). `CheckpointScheduler.buildCheckpoints` iterated
  `changelog_segments.findDistinctSiteIds()` alone, which is the list of sites with *ingestion* work
  — but since #128 a build can also **rematerialize a detached snapshot from the frame with no
  segments at all**, and that is exactly the state retention leaves behind: with
  `delta.retention.audit-window-segments=0` (or a table detached for longer than the default window
  of 20) the site keeps no segment row, so the retry the rematerialize path exists for never ran and
  only `POST .../delta/checkpoints/rebuild` could restore the table. The tick now visits the union of
  that list and new `CheckpointRepository.findSiteIdsWithUnmaterializedCheckpoints()`
  (`SELECT DISTINCT c.siteId … WHERE c.s3KeyParquet IS NULL`, served per row by the existing
  `checkpoints` scan). The predicate is the point: **having checkpoints is not a reason to visit** —
  a site with every table materialized and no leftover segments is still skipped, so the union does
  not degrade into "sweep every site that ever checkpointed". Segment sites keep their position and
  the set de-duplicates, so a site on both lists is built once. Nothing else moves: a visit is the
  ordinary `buildCheckpoint` + `prune` pair, an idle `RETRY_MISSING` pass does not touch the pointer
  or the frame, and a build discarded by `CheckpointEpochGuard` (#136/#142) stays a normal outcome
  rather than an error. `CheckpointService` is untouched. No REST, gRPC, DTO, migration,
  configuration-key, metric, S3-key or frontend change. See `docs/delta-client-v2-guide.md`.
- scratch-pod-private-sweep: `ParquetScratchOrphanSweeper` deletes scratch older than the running
  JVM where the directory is declared pod-private (issue #141). #127's age filter was the only rule,
  startup pass included, and #131 then moved the deployed scratch onto a `parquet-scratch`
  `emptyDir` with `sizeLimit: 6Gi` — a volume cleared only when the **pod** goes away, so it
  survives a liveness or OOM kill. A container restart mid-build therefore parked one
  `batch-parquet-*` per claimed table (and any `checkpoint-*`) on the volume for the full **4 h**
  window, while the lease-expired claim was retried after `DELTA_BATCH_PARQUET_LEASE_SECONDS`
  (30 min) and allocated a second full set; the penalty for filling that directory is a kubelet
  **eviction**, not wasted disk. New key `delta.parquet.scratch-private-to-pod`
  (`DELTA_PARQUET_SCRATCH_PRIVATE_TO_POD`, default **false**, `"true"` in
  `k8s/base/configmap.yaml` beside the two `*_TEMP_DIR` keys it describes) makes the sweep cutoff
  the **later** of `now - age` and this JVM's start, so it is never softer than the age filter and
  converges back to it once the process outlives the window. The bound is the **JVM's start**, not
  "the first tick": the sweeper's `initialDelay` is 0 and `resumePendingRebuilds()` fires at
  startup too, so an unconditional startup sweep would race a live writer. It is
  `ProcessHandle.current().info().startInstant()` rather than the bean's own construction, so a
  future eager writer running during context refresh stays out of scope, and it is truncated
  to whole seconds because file mtime can be second-resolution — a file written in the startup
  second must round to "not older". Startup logs the mode and the directories, the only signal an
  operator has left if a deployment is patched out of band. Default false leaves #127's reasoning
  untouched for a shared
  volume, where a file older than this JVM can belong to a live sibling; the lease half is
  independent of the mount, so **lowering the age globally is still not the fix**. Chosen over an
  unconditional startup sweep (the ticket's option 2) because "older than my start" proves the file
  is not *mine*, not that it is not *live* — that step needs the volume claim, which the deployment
  makes in the same directory as the mount, and a test parses both base manifests to enforce the
  one direction that is a safety property: **flag set ⇒** both temp-dir keys name
  `/scratch/parquet` **and** the volume behind it is an `emptyDir` (turning the flag off stays
  available as a rollback, and an overlay may only ever set it to false). The "at most one sweep
  interval" bound assumed the tick runs when it is due, which #146 has since made true by pinning
  the scheduler (see scheduler-pool above). No REST, gRPC, DTO, metric, S3-key,
  migration, or frontend change. See `docs/delta-client-v2-guide.md` ("Sizing note", "Orphans
  outlive a container restart"), `docs/cr-unified-batch-parquet.md`.
- checkpoint-baseline-epoch: The checkpoint epoch guard now covers a **re-baseline** as well as a
  wipe, and the event published after a build can no longer act on a history that is already gone
  (issue #142, consolidating #143). `DeltaRebaselineService.reset` deletes every `checkpoints` row of
  the site and zeroes `last_checkpoint_seq` inside the FULL_SNAPSHOT `SessionEnd` commit, yet must
  leave `generation` alone — that is the wire epoch (035) and moving it would tell the shipped Windows
  client to drop its journal and reset its counters. `CheckpointEpochGuard` (#136) keyed on
  `generation`, so a build overlapping a re-baseline passed the check and restored the pre-re-baseline
  pointer; unlike the wipe case it was **silent**, because `reset` leaves the checkpoint
  `_frame/seq=N/frame.pb.gz` in place and the next build then seeded the fold from the **discarded**
  baseline's frame — rows deleted at the source reappearing in every checkpoint Parquet, no pruning
  alarm, no "refusing lossy refold". V52 adds `site_sync_state.baseline_epoch` (`BIGINT NOT NULL
  DEFAULT 0`), a **second monotonic counter moved by both** a wipe and a re-baseline and **never sent
  to the client**; `generation` keeps its wire meaning. The guard compares the **pair** (`SiteEpoch`)
  rather than the apparently-stronger `baseline_epoch` alone, because neither subsumes the other
  across a rolling deployment: a pod that predates V52 bumps `generation` only, so a wipe issued from
  one mid-rollout would be invisible to a new pod watching the baseline epoch — #136's hole re-opened
  through #142's fix. `CheckpointService.run` also reads the sync state **before** the segment list;
  the other order let a reset committing in between pair the pre-reset segments with the *new* epoch,
  so every guarded write compared equal, was approved, and the build folded the discarded baseline at
  the new epoch — the same resurrection arrived at through the guard. A per-site `baseline_id` (UUID)
  was the alternative and was rejected: it
  carries no ordering, so a discard log line could not say "moved from 3 to 4", and it would have to
  be generated where an increment does. `reset` also takes the `site_sync_state` row lock **before**
  it deletes anything (as the wipe already did) — loading the row last left a window between the
  checkpoint deletes and the epoch bump in which a guarded write could still land. Second half:
  `CheckpointRecordedEvent` gains the epoch pair and `DeltaWipeReinitListener` takes the flag through
  `clearWipePending(siteId, generation, baselineEpoch)`. The event is published one statement **after** the
  guarded pointer write commits and cannot move inside that transaction — the listener is a
  synchronous `@EventListener` with `@Transactional(REQUIRES_NEW)` and its `wipe_pending` UPDATE would
  block on the row lock the suspended guard transaction holds (self-deadlock) — so a wipe committing
  in the gap used to have its `wipe_pending` spent by the stale event, `recaptureForSite` froze zero
  baselines from the just-emptied `checkpoints` table, and the automatic Bit BI re-init of #89 was
  lost until a manual reinit. The epoch predicate makes that take a no-op instead, leaving the flag
  for the first genuine post-wipe checkpoint. `EpochChangedException.getExpectedGeneration/
  getActualGeneration` → `getExpectedEpoch/getActualEpoch` (introduced in #136, never published);
  `SyncStateView` gains `baselineEpoch` + a derived `epoch()` (application-layer record, not a wire
  DTO). Follow-up from review: **#147** — `reset` runs first inside `DeltaSessionCommitService.commit`,
  so its row lock is now held across the tail segment's S3 upload; the lock ordering is the fix for a
  real hole and the waiters are short, but S3 inside the ingestion commit transaction is worth
  removing. No REST, gRPC, proto, configuration-key, metric, S3-key or frontend change.
  See `docs/delta-client-v2-guide.md` ("Site history wipe and the generation epoch").
- checkpoint-wipe-serialization: A checkpoint build that overlaps a site history wipe is discarded
  instead of resurrecting the epoch it was built for (issue #136, the row-side half of #122).
  `CheckpointService.buildCheckpoint` is non-transactional by design — frame download, one download
  per segment, one upload per table — and runs from `CheckpointScheduler` as well as
  `DeltaCheckpointRebuildService`, so its writes could land after the wipe committed: the deleted
  `checkpoints` rows came back and, worse, a **pre-wipe `last_checkpoint_seq`** was restored on a site
  whose epoch had just restarted at 0. `ChangelogRetentionService.prune` keys off that pointer, so
  the new epoch's segments read as "below checkpoint" and were pruned to `audit-window-segments`,
  after which the next build hit the deliberate "refusing lossy refold" throw and the site's
  checkpoint pipeline was stuck. New `CheckpointEpochGuard.inEpoch(siteId, generation, write)` runs
  **each** build write (the three `checkpointRepository.save` paths and `recordCheckpoint`) in its own
  short transaction that takes the `site_sync_state` row lock the wipe already holds for its whole
  transaction and re-reads `generation`. Two orderings survive: the write commits before the wipe
  takes the lock (the wipe's own deletes remove it) or it waits and is refused. A refusal is **not** a
  per-table skip — it escapes the per-table catch, ends the build, logs
  `Discarding the checkpoint build for site …` and returns an empty fold, so the pointer, the rows and
  the frame all stay with the new epoch. No S3 traffic inside the lock: the build still holds a
  connection for one statement at a time. Objects the discarded build had already uploaded stay as
  orphans — the #122 cut-off deliberately spares them from the walk and re-running the wipe sweeps
  them. Retention needed no change: it re-reads the pointer, and post-wipe that is 0. No REST, gRPC,
  DTO, migration, configuration-key, metric, S3-key or frontend change.
  See `docs/delta-client-v2-guide.md` ("Site history wipe and the generation epoch").
- parquet-scratch-budget: The deployment now declares how much local disk the file-backed Parquet
  writers may use (issue #131) — manifests and documentation only, no application code. The backend
  container mounts a `parquet-scratch` `emptyDir` (`sizeLimit: 6Gi`) at `/scratch/parquet`,
  `DELTA_CHECKPOINT_TEMP_DIR` / `DELTA_BATCH_PARQUET_TEMP_DIR` point at it through
  `k8s/base/configmap.yaml` instead of `java.io.tmpdir` (the container's unbounded writable layer),
  and the container declares `ephemeral-storage` **request and limit of 8Gi** — 6 GiB of scratch
  plus ~2 GiB for logs and the writable layer, request == limit because Autopilot normalizes them
  and caps a pod at 10 GiB. The *request* is the half that matters for placement: without it the
  scheduler ignored local disk entirely, so two builds could push a node into disk pressure and
  evict unrelated pods. `emptyDir` over a PersistentVolume keeps #127's orphan sweeper
  belt-and-braces rather than load-bearing; the default node-disk medium is deliberate
  (`medium: Memory` would be a tmpfs charged against the memory limit). **The overlays needed no
  patch**: `dev`/`stage` patch `resources` with cpu/memory through a strategic merge, and
  `requests`/`limits` are maps, so the base `ephemeral-storage` merges in and survives — verified
  by rendering all three overlays; both patches carry a comment warning that a JSON-6902 `replace`
  on `resources` would silently drop it. `*_MAX_TEMP_BYTES` are **unchanged at 10 GiB** and remain
  per-file, so on this deployment the volume is the binding constraint and the failure mode is a
  kubelet **eviction of the pod**, not the graceful per-table skip
  (`delta.checkpoint.tables.unmaterialized{reason=parquet_failed}`) the app-level ceiling gives:
  making the app refuse first needs separate snapshot/frame ceilings (#126 put two files with
  opposite failure semantics under one key) and is issue #138. **6 GiB is an assumption, not a
  measurement** — no observed maximum for a checkpoint frame or batch artifact exists; the
  worst-case formula is in `docs/delta-client-v2-guide.md` ("Sizing note") together with how to
  replace the guess. Two multipliers there are easy to get wrong: the checkpoint term is
  **`2 x max(table snapshot, whole-site frame)`**, not `1 x` — `CheckpointScheduler`'s
  `ReentrantLock` guards only the cron thread, while a forced rebuild runs `rebuildFromFrame` on the
  separate single-thread `deltaRebuildExecutor`, so a rebuild during the 02:00 sweep (or
  `resumePendingRebuilds()` at startup) puts two checkpoint scratch files in one JVM — and the batch
  term is `max-concurrent (2) x tables x artifact`, because a build opens one scratch file per
  claimed table before the shared replay. Also new: **orphans now outlive a container restart**. The
  writable layer was discarded when a container restarted; an `emptyDir` is cleared only when the
  pod goes away, so a liveness/OOM kill mid-build leaves that attempt's files on the volume until
  `DELTA_PARQUET_SCRATCH_ORPHAN_AGE_SECONDS` (4 h) ages them out, while the batch claim is retried
  after `DELTA_BATCH_PARQUET_LEASE_SECONDS` (30 min) and allocates a second set — budgeted as a
  residue term, with #141 tracking the unconditional-startup-sweep fix. No API, DTO, migration,
  metric, S3-key or frontend change. See `docs/delta-client-v2-guide.md`,
  `docs/cr-unified-batch-parquet.md`.
- wipe-prefix-sweep: The site-history wipe's post-commit prefix walk is bounded, resumable and
  honest (issue #122, consolidating #123 and #124). `S3PrefixLister` walks `ListObjectsV2` page by
  page and returns the pages already read plus `lastModified` per object and a truncation flag —
  `S3CheckpointStorage.listPrefix` (wipe) and `S3FileStorageService.listAllKeys` (batch
  deletion/retention — complete or throw) both use it. The wipe takes its start instant before the transaction
  and skips objects newer than that on **both** `egress/{siteId}/` and `checkpoints/{siteId}/`, so
  a concurrent rebuild or egress `PutObject` cannot have its fresh object deleted while the row
  that names it survives. S3 `LastModified` is second-resolution, so anything in the wipe's own
  second is treated as newer. A prefix that could not be listed, or was listed only partially, is
  reported as `prefixesNotSwept` on `SiteHistoryWipeSummary` / the wipe DTO — distinct from
  `s3DeleteErrors`, which the UI quotes as an object count. The Danger zone tells the operator to
  repeat the wipe rather than inventing a number. `BatchDeletionService` and `BatchRetentionService`
  keep their complete-listing behaviour and fall back to recorded exact keys only on a truncated
  listing. No migration, configuration-key, gRPC, or S3-key change. The row-side race in which
  `CheckpointService.buildCheckpoint` re-inserts a pre-wipe pointer after the wipe commits is
  **not** closed here — the cut-off protects the objects; serializing the build behind the
  `site_sync_state` lock is a separate decision, since taken in #136 (see
  checkpoint-wipe-serialization above).
- checkpoint-rematerialize: A checkpoint table left without a snapshot is rematerialized from the
  existing frame, without waiting for new segments (issue #128). `CheckpointService.materialize`
  used to detach Parquet on a per-table failure, save the row at the new `seq` and still advance
  the pointer — after which `buildCheckpoint` saw an empty `newSegments` and returned the seed,
  so even `POST .../delta/checkpoints/rebuild` was a no-op on a quiet site. A scheduled build now
  retries any row whose `s3_key_parquet` is null; a forced rebuild rematerializes every table from
  the frame (`rebuildFromFrame`). Neither path moves the pointer, re-uploads the
  frame, or publishes `CheckpointRecordedEvent` — the fold has not changed, retention stays
  monotonic. A same-seq rematerialize that fails keeps a still-valid last-good key; detach is
  only for an advancing seq. After a full prune the frame is still enough — leftover changelog
  rows are not required. No API shape, DTO, migration, configuration, metric name or frontend
  change. See `docs/delta-client-v2-guide.md`.
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
- stream-checkpoint-frame: The checkpoint build no longer collects the all-INSERT reload frame into
  a `List<ChangeRecord>` and a gzip `byte[]` (issue #126, the leftover copy after #112 put each
  table's Parquet on disk). `CheckpointFrame.records` walks the fold one record at a time,
  `ChangelogCodec.write` gzips that sequence to an `OutputStream`; the build wraps it in
  `CappedOutputStream` under the same `delta.checkpoint.temp-dir` / `delta.checkpoint.max-temp-bytes`
  as the snapshot, and `S3CheckpointStorage.uploadFrame` takes the `Path` (`RequestBody.fromFile`). The file is deleted
  in `finally`. Crossing the ceiling aborts the build — the frame is the next seed, unlike a single
  oversized table which is still skipped. On-disk bytes are the same gzipped length-delimited
  protobuf `parse` already reads. **The site fold stays in heap** (a later ticket). No API, DTO,
  proto, migration, configuration-key, meter, S3-key or frontend change. See
  `docs/delta-client-v2-guide.md`.
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
  stays in heap**; the all-tables frame that used to sit beside it is now file-backed (issue #126).
  Off-heap folding remains a separate ticket, and
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
