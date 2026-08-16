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
  the build that notices, through the epoch guard like every other write; the object it named joins
  the superseded snapshots already unreferenced under `checkpoints/{siteId}/` (#118, sweeper is
  #160). The third unfixable state, found in round 2 of #148's review, is a site whose frame is
  unreadable with **no segments behind it**: `historyPruned` is unconditionally true there, so it
  raised "refusing lossy refold" every night — wrong in kind, since with no frame and no changelog
  there is no history to refold, lossily or otherwise. It gets its own message and
  `delta.checkpoint.builds.aborted{reason=history_gone}`, and it spends an attempt on every
  still-retryable row of the site, which is what drains it: such a site is on the work list *only*
  because of those rows. `reason=lossy_refold` keeps its meaning for a site whose segments survive —
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
  and this one is repaired by the process that replaces it. It makes #146's "do not interrupt the
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
  `spring.datasource.hikari.maximum-pool-size` (10) — which says the scheduler alone cannot empty the
  connection pool, not that total background demand fits, since the three queue workers hold
  connections too (**#161**). Two tests carry it:
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
