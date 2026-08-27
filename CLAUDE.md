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
- **Migrations (Flyway)**: forward-only, sequential `V{N}__description.sql`; never edit an applied migration; backward-compatible defaults for new NOT NULL columns. Current at **V57**, next is **V58**. `MigrationDocumentationConsistencyTest` derives these values from the migration filenames and guards both agent instruction files against drift; Gradle tracks the docs and migration directory as test inputs, and the pre-commit hook runs the focused guard for agent-doc-only or migration-only changes.
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

The merge into `develop` never happens without a human go-ahead — but the go-ahead can be given by
invoking a command as well as by answering a question, and **two** commands carry it: the
per-run `/github-issue-runner` (below) and the per-issue **`/task <n>`**, whose step 6 merges its
own PR because typing the command *is* the authorization for that one issue. Both still verify
every readiness condition; the authorization removes the question, not the checks. `/github-issue`
does **not** carry it: it stops at readiness and hands the decision over.

Normally, then, the go-ahead is per PR,
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

**Follow-ups: enrich an existing ticket before filing a new one.** Anything out of scope worth
doing later: first study **open and closed** issues (`gh issue list --state all --search …`,
plus `CLAUDE.md`/`docs/`) for the same **theme**, not just the same words. An open match —
including a related-but-not-identical one — gets your evidence as a comment, so the existing
ticket grows into one larger, run-sized problem instead of a sibling appearing. A closed match
is **never reopened**: almost always the observation is simply stale (the fix is already in
`develop`, your worktree is older — nothing needed, say so and move on), and only a true
regression, reproducing on current `origin/develop`, gets a new issue linking the old one and
the fixing commit. **A closed match carrying `duplicate` is the exception to "stale"** — it was
closed by another ticket's work rather than by its own PR, and that other ticket may still be
open (three of the seventeen labelled closes are today: #192 under #193, #218 under #205, #241
under #239), so read it as a pointer: take the
absorber from its closing comment and look at *its* state. Ask the search for labels, not just
titles (`--json number,title,state,labels,body`). Only when the theme has no
ticket yet, file one — framed as the **theme** (root cause / subsystem), not a one-symptom
note, so later findings have a place to land; described, labelled, milestoned, `Backlog` on
the board, sized so one run fixes it whole. Work on a finding never starts in the current
cycle.

**Every follow-up says what it will touch.** A keyword search finds a *duplicate*; it does not find
a *collision* unless somebody runs it: #190 and #200 were one piece of work, doable in neither order
alone — #200 a unique constraint, #190 a transaction annotation — and they were untangled only
because a backlog pass read every open ticket at once, which nothing in this process asks for. What
they shared was the files, and a file list makes that check mechanical instead of a thing somebody
has to think to do.
So every follow-up — from a review, or seen in passing mid-work, which is the commoner case —
states three things, in the body, before it is filed:

- **the files it expects to touch** — enough for an overlap check, not a plan;
- **whether it needs a Flyway migration, and whether it needs a `specs/NNN-*` directory** — the two
  collisions a file list cannot catch, since both sides add differently-named files and merge
  cleanly, one breaking startup and the other putting two features on one number, so "neither" is
  worth writing down (a `delta-ingestion.proto` field-number clash is invisible to git too, but both
  tickets must name the same file, so the line above already has it);
- **which open tickets live in those same files** — named, and "none found" is a valid answer.

**The third line needs a method, and the method is not what the first draft of this rule claimed.**
That draft said a keyword search cannot find these tickets; against this repository it can —
`gh issue list --state all --search "SqlGenerationService"` returns #185, #190, #200 and #210,
because GitHub indexes bodies and all four name the class. #190 and #200 are not invisible to each
other either: both titles begin *"SQL regeneration"*. The real failure was simpler and worse —
**nothing in the process asked anyone to run the check at all**, and the four colliding pairs #216
found surfaced only because somebody read every open ticket in one sitting, which no step requires.

So the line is cheap to answer and must actually be answered. One query per name you expect to
touch, printing the tickets rather than a yes/no:

```bash
gh issue list --state open --limit 200 \
  --json number,title,body \
  --jq '.[] | select((.title + .body) | test("SqlGenerationService";"i")) | "\(.number) \(.title)"'
```

`--jq` rather than a pipe into `grep`: `gh` emits the whole backlog as **one line** of compact JSON,
so a grep answers "something matched" while printing fifty thousand characters and naming nothing —
and the rule above asks for the tickets to be *named*. Keep `--limit` well above the open count;
the default sort is newest-first, so a low limit drops the oldest tickets, which are exactly the
pre-rule ones with no file list of their own. The argument to `test()` is a **regular expression**,
not a literal: escape `.`, `(` and `[` when the name carries them — an unbalanced bracket aborts
the query outright, and an unescaped `.` quietly widens the match (over-matching, so it fails safe).

The answer is written as **"none found"** rather than "none": a ticket filed before this rule
declares nothing, so the check is only ever as good as its prose, and the dispatcher re-checks
rather than trusting it.

`/github-issue-runner` computes the same overlap at step 2b, and when the ticket says it outright
that inference becomes a read — the dispatcher sees the clash before it puts two tickets in one
window rather than two hours into an executor's session.

**A ticket closed as absorbed carries `duplicate`, because `Done` cannot say it.** Folding one
ticket into another (`folds #NNN`) closes the absorbed issue, and project 16's built-in **`Item
closed`** workflow then sets its `Status` to `Done` — so `Done` mixes two different things: a
ticket closed by **its own** PR, and a ticket closed by **somebody else's**. The backlog pass of
2026-08-19 read **eight** of the second kind — not all of them, as the census below found — with
nothing telling them from the first, and for four of the eight the absorber was still open — the sharpest being **#200** (`priority: high`, SQL
regeneration unfixed in production) sitting in `Done` while #190 was open and `status: blocked`;
#210 under an open #185, #192 under an open #193, #218 under an open #205. The other four — #143,
#162, #204, #214 — were absorbed by #142, #149, #186 and #213, whose work *did* land. A person
reading the board treats all eight as shipped, and for four of them that is false.

The marker is the **`duplicate` label, and it is mandatory** — the decision taken for #230. All
three options are recorded, the two rejected ones first:

- **A separate column loses to the automation.** The project's workflows are readable, and
  `Item closed` is enabled:

  ```bash
  gh api graphql -f query='query{node(id:"PVT_kwDOB7LEnM4BeGrE"){... on ProjectV2{
    workflows(first:20){nodes{name enabled}}}}}'
  ```

  It fires on the close event, so a `Duplicate` column would be overwritten by every close and
  restored only by a manual move nothing enforces — the board would lie exactly as it does now, and
  lie *invisibly*, a rule appearing to exist. `Auto-close issue` is enabled too, so the column set
  is load-bearing in the other direction as well; and the columns are anyway the **state machine**
  this file walks in order, where an outcome is not a state.
- **"Leave them in `Done`, that is accepted" is what the board already does**, and the eight rows
  above are what it costs.
- **The label rides beside the automation instead of fighting it.** It survives the auto-move,
  `gh issue list --state closed --label duplicate` is the whole query, and it was already the
  de-facto marker: triage had applied it by hand to #218 and #229 before any rule said so, so this
  generalizes an existing practice rather than inventing one.

**What the label means is the part that had to be decided, and it is the durable fact:** *this
ticket was closed by another ticket's work, not by its own PR.* It is **not** "the work is still
unfixed" — that reading is unimplementable, because the person closing the ticket cannot know it
and it changes underneath them. #200 is the proof, inside ten days: it was labelled on 2026-08-19
with a comment saying the work was not done, and #190 merged the day after, so a label meaning
"still unfixed" was already stale and nobody was going to come back and strip it. So every
absorbed close is labelled, whatever the absorber's fate, and the live answer to "is it fixed?"
comes from the absorber — which is why **the closing comment is required beside the label** and
must name the absorber and say whether it is still open. The label narrows the question; the
comment answers it.

The **label's own description carries that meaning** — it reads "Closed by another ticket's work,
not by its own PR — the closing comment names the absorber", replacing GitHub's default "This issue
or pull request already exists", which states the rejected reading to anyone hovering it in the UI.

So: **whoever closes a ticket as absorbed applies `duplicate` and leaves that comment.** The card
stays in `Done` — that is the automation's answer and this rule does not fight it. What the label
buys is that **`Done` minus `duplicate` is the work that closed on its own merits**, and that the
absorbed set is one query rather than a re-reading of the backlog. **That invariant is only as good
as the backfill, so the backfill is a census rather than the issue's own list**: #230 named eight,
and review round 2 found six older folds carrying no label at all — #114 into #112, #123 and #124
into #122, #160 into #158, #163 into #159, #176 into #164 — plus #241, a duplicate of the **still
open** #239. Seventeen closes carry the label now (the eight, those seven, and #220/#229 which
triage had already marked), found by scanning the last comments of every closed issue for the
folding phrasings this repository uses, since no single wording is standard. A fold from before
that scan is the one thing the search rule cannot see, which is why the scan was worth doing once. The rule binds all three
closing sites — `/github-issue` (a finding that absorbs an open ticket), `/merge` (a PR whose issue
folds another) and `/github-issue-runner` (the dispatcher merging duplicates inside a run) — and
says nothing about *which* ticket survives, which stays a judgement about where the work is.

### Running several issues at once

`/github-issue-runner` (`.claude/commands/github-issue-runner.md`) is a **dispatcher**: it keeps
up to **three** issues in flight and picks up the next as a slot frees. Invoking it gives the
merge go-ahead **for that run** — one of the two standing exceptions to the per-PR gate above, the
other being `/task <n>`, which carries it for a single issue. Nothing else
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
follow the follow-ups rule above (enrich an existing ticket, else file the theme), and the
dispatcher merges duplicates and same-root-cause smalls into one run-sized ticket — findings are
for a later run, never additions to the current window. The run scripts are
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

`Done` is an outcome-blind column: the built-in `Item closed` workflow puts every closed issue
there, shipped or absorbed alike. The `duplicate` label is what separates the two — see "A ticket
closed as absorbed carries `duplicate`" above.

A closed ticket has **no** `status: *` label. The column is `Done`; the live labels (`ready`,
`in progress`, `in review`, `ready to merge`, `blocked`) are a state machine for open work, and
a closed ticket wearing one is a wrong answer to `gh issue list --label "status: in progress"`
and to `/merge`'s ambiguous-target lookup (`--label "status: ready to merge"`). `/merge` used
to remove only the two names a squash-merge is expected to carry, and only from the `Closes #<n>`
issue — so a ticket closed from `ready` / `in progress` / `blocked`, an absorbed close, a runner
duplicate close, a GitHub-UI close, or the auto-close `Closes` fires at squash time, kept
whatever it had (#89 still read in progress a month after it shipped). **Every close route
strips every `status:*` actually present.** Query then remove, because `gh issue edit
--remove-label` 404s if the named label is not on the issue, which is why a fixed two-name list
*was* the defect:

```bash
while IFS= read -r label; do
  gh issue edit <n> --remove-label "$label"
done < <(gh issue view <n> --json labels --jq '.labels[].name | select(startswith("status:"))')
```

The load-bearing backstop is `.github/workflows/strip-closed-status-labels.yml`: it fires on
`issues: closed` (covers the routes the commands never see) and weekly / `workflow_dispatch`
(covers a label added after close, an Action that failed, and a close performed with
`GITHUB_TOKEN` — GitHub does not chain `GITHUB_TOKEN` events, so `issues: closed` would not
fire for that one). A scheduled auto-strip is worth
having here — closed + `status:*` is always wrong, no false positive — and was declined as a
journal guard on #205, where a missing `AGENTS.md` entry needs a judgement. The Action is not a
reason for a command to skip the loop: a just-closed ticket still wearing a live label poisons
the next `gh issue list` in that same session.

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
| `SQL_GENERATION_ADOPTED` | This attempt lost the unique claim and adopted the winner's generation (issue #260; metadata: generationId, s3Key) |

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
- Migrations current at **V57**; next migration is **V58** (do not reuse numbers)

## Recent Changes
- fold-row-footprint: A folded row carries its column names once per table instead of once per row,
  and its key once instead of twice (issue #290). The fold is the checkpoint build's real ceiling
  (#152) — the one full-site copy left in heap, everything around it already streaming (#112, #126)
  — so the row's *representation* is the ceiling multiplied by whatever redundancy it carries, and
  it carried three kinds. Every row held its own `LinkedHashMap` of column **names**, and protobuf
  mints a fresh `String` per record, so five million rows retained five million copies of the same
  twenty names at a map entry plus a string header each. The **key columns** were held twice, in
  `key` and again in `data` — a duplication the UPDATE branch already treated as given ("an existing
  row already carries its key columns in data from the original INSERT"). And the **identity**
  string is a third copy of the key. The first two are gone: a table owns one canonical set of names
  (`FoldedTable`, an append-only layout) and a row is a `Value[]` aligned with it — one reference per
  column instead of an entry and a name — with `key()` and `data()` as read-only views that retain
  nothing, so `CheckpointFrame` and the snapshot writers are unchanged. The key is read back out of
  the values by the table's key column names, and the row whose key column never appears in its data
  (a client may send it in `key` only — `foldsARowWhoseDecimalKeyIsNonFinite` is exactly that shape)
  keeps a small side array for those columns alone, `null` otherwise. **The identity string is
  deliberately not shortened to a hash**, which the ticket named as the third candidate: a collision
  folds two distinct rows into one, i.e. silent data loss, and verifying the full key on a hit means
  keeping the full key anyway.
  **Measured rather than argued, because the estimate is what the ceiling is enforced against.**
  `ChangelogFoldFootprintTest` folds a synthetic wide site and compares the new estimate against the
  pre-#290 formula written out verbatim beside it, so the saving is stated by the test rather than by
  a comment that goes stale: a twenty-column row with eight-character string values goes
  **4747 → 2216 bytes, a factor of 2.1**, and the factor is ~3.5 on narrow numeric rows, where the
  names were most of the weight. Against the observed production budget (1 207 959 552 B, half of
  `-Xmx` 2.25 GiB) that moves the ceiling from ~254 k such rows to ~545 k. **The honest answer to the
  ticket's last question is therefore split**: `demo site iii` (439 693 applied) now fits where it
  did not, and `fyt-new` (4 992 131) still does **not** — it needs roughly 11 GiB of fold and no
  constant factor reaches it. Removing the ceiling means spilling the fold to disk with an external
  sort, deferred at #152 and still deferred; raising the pod's heap or setting
  `DELTA_CHECKPOINT_MAX_FOLD_BYTES` explicitly is the operational answer in the meantime.
  **The estimate is recalculated, not left to drift**, which is the half that would otherwise make the
  budget and `delta.checkpoint.fold.bytes` lie in the other direction: a row is now its header, its
  identity string, one array slot per column it spans and its values, while the column **names** are
  charged once to the table (`sharedEstimatedRetainedBytes`) on the record that introduces them —
  and never refunded, because a name outlives every row that used it. That is the one behavioural
  change in the accounting and it is what three `ChangelogFoldTest` size assertions had to be
  restated for (`applyChargesAnInsertAndRefundsItsDelete`, `applyNetsToZeroAcrossInsertUpdateAndDelete`,
  `applyChargesAnUpdateOfARowItHasNotSeen` now warm the table first, so each measures the row alone —
  expectations about *sizes*, not about data). `CheckpointServiceTest`'s 75 % WARN fixture derives its
  budget from the peak rather than from a row-count proportion for the same reason.
  Folding semantics are untouched and pinned by the unchanged data assertions: INSERT replaces,
  UPDATE merges, DELETE removes, an absent row's UPDATE still materialises its key columns, and
  `everySpellingValueMapperCallsNonFiniteFoldsUnderItsCanonicalIdentity` /
  `CheckpointFrameTest`'s round trip pass with no change to what they expect. `theRunningTotalAgreesWithAWalkOverTheFold` pins that the running total the ceiling uses equals a walk over the fold, so
  the two cannot drift apart. Mutation: charging the column name per row instead of per table
  (`declare` returning its cost unconditionally) fails the footprint test's per-row and
  once-per-table assertions; dropping the key-only side array fails
  `foldsARowWhoseDecimalKeyIsNonFinite`'s frame round trip.
  No migration (**V58 stays free**), no `specs/NNN-*`, no REST, gRPC, proto, DTO, configuration-key,
  metric-**name**, cache, S3-key or frontend change — only the internal representation of the fold
  and, as intended, the *values* of `delta.checkpoint.fold.bytes`. See `docs/delta-client-v2-guide.md`
  ("The first bound is heap").
- test-fixture-db-clock: The test fixtures are the fourth producer of a zone-independent
  `TIMESTAMP` column, and they say UTC out loud like the other three (issue #287, found working
  #286, which deliberately left them: about forty places, a sweep of its own size). A fixture
  seeding `CURRENT_TIMESTAMP` takes the clock in the **session's** zone, which pgjdbc sets from the
  JVM's, so off UTC it disagrees with the UTC the application writes — and a test comparing a
  seeded age against an application value is then deterministically red or green **for a reason
  that is not its own**, the #278/#279 class where the gate lies to a developer outside UTC.
  **The population was larger than the ticket's list, and finding that is what the guard bought:**
  the eight named files are 65 occurrences, but a first run of the extended scan surfaced two more
  spellings nobody had grepped for — `NOW()` in `BatchRetentionIntegrationTest` and, much the
  bigger set, **lowercase `now()` inside SQL string literals** across eleven further classes the
  ticket never named (`ParquetExportIntegrationTest`, `SegmentedRebaselineIntegrationTest`,
  `SqlGenerationConcurrentClaimIntegrationTest`, `DeviceAuthRefreshContractTest` among them). 174
  rewrites in 27 fixture files; three of the four statements in
  `DeltaBatchParquetQueueRestContractTest` already carried the right form — its lease-deadline
  arithmetic, where being wrong would have shown — which is what the ticket meant by "the correct
  shape is known where correctness happened to matter"; the fourth, a plain seed in the same file,
  did not.
  **The guard is #286's scanner with two more roots** — `src/test/java` and
  `src/test/resources/*.sql` — plus the shape-and-count exemption machinery of #280
  (`RawTimestampReadConventionTest`), because a test tree carries two things production SQL does
  not. **A `TIMESTAMPTZ` column is the exemption that matters**: for
  `device_authorizations.expires_at`/`created_at` (V21) the bare form is the *correct* one — the
  value is an instant, not a wall clock — and wrapping it yields a `timestamp` PostgreSQL then
  reinterprets in the session's zone on assignment, i.e. the fix would be the defect. It is the
  only such hit in the whole tree, established by mapping every occurrence to its column and every
  column to its migration rather than by inspection. The other exemptions are prose in classes with
  no database access, plus the one bare read `DatabaseClockUtcIntegrationTest` needs to measure how
  far the session's clock is from UTC. **The scanner's own file is the single file-wide exclusion**
  and it is checked rather than argued: its literals are the scan's input, so it must still carry
  the banned shapes (a stale exclusion hides whatever is written there next) and it must contain no
  `JdbcTemplate`/`EntityManager`/`DataSource`/`@Sql` **outside comments and literals** — read that
  way because the assertion itself, and the Javadoc explaining it, name those types in order to
  talk about them.
  **The third acceptance criterion is answered no, and the reason changes rather than
  disappears.** `TimestampRoundTripIntegrationTest` still does not switch the JVM's default zone.
  The hazard it named — a leaked non-UTC session reaching an unrelated fixture's bare
  `CURRENT_TIMESTAMP` — is what this ticket closes, but `TimeZone.setDefault` is a mutation of the
  whole process, shared with the background workers of ~24 cached contexts, and a pooled
  connection's session zone is fixed when it is **opened**, so it outlives the method that set it:
  the blast radius is the suite, not the test. What such a connection can still reach is smaller
  but not empty — a `TIMESTAMPTZ` column read into a `LocalDateTime` converts through that zone,
  and `DatabaseClockUtcIntegrationTest` reads the session clock on purpose. And nothing is bought
  that the repository does not already have safely (`SET LOCAL`, undone by its transaction) —
  which, stated rather than implied, is **not** a substitute here either: the conversion that test
  guards is Hibernate's, keyed on the JVM's zone and not the session's, so the CI blind spot stands
  and `TimestampProducerConventionTest` is what closes it.
  Test and documentation only: no production code, REST, gRPC, proto, DTO, migration (**V58 stays
  free**), `specs/NNN-*`, configuration-key, metric, S3-key or frontend change. Mutation-proven in
  both directions — restore one bare `CURRENT_TIMESTAMP` in `test-data.sql` and the scan names that
  line; leave an exemption whose file no longer matches its shape and count and the staleness check
  fails (observed for real mid-run, at 31 occurrences against a budget of 2). See `README.md`
  ("Time zones").
- db-clock-utc: The database clock — the third producer of a zone-independent `TIMESTAMP` column —
  says UTC itself instead of inheriting it from the session zone (issue #286, found working #282 and
  filed rather than folded in). #282 brought every **Java** producer to one convention and removed
  `hibernate.jdbc.time_zone`; six statements let **PostgreSQL** stamp the value, and PostgreSQL
  resolves `CURRENT_TIMESTAMP` in the session's zone, which pgjdbc sets from the JVM's default at
  connect time — measured on #282, where `SHOW TimeZone` reported the JVM's zone. Off UTC those six
  wrote local wall clock into columns every other path fills with UTC, and two of them
  (`refresh_tokens.revoked_at`, `account_plugins.last_used_at`/`updated_at`) sit on `Instant` fields
  whose entity writer always binds UTC — so one column received two different clocks.
  **Option 1 of the ticket, and the DB stays the time source.** `CAST(current_timestamp AT TIME ZONE
  'UTC' AS timestamp)`, the expression `JpaBatchParquetArtifactRepository.nextCatalogWatermark`
  already carried. Option 2 (bind `:now`) was rejected for the reason the ticket gave: it moves the
  clock into the application, and one clock across pods that disagree about the time is what #245
  chose deliberately for the queue markers. JPQL has no `AT TIME ZONE`, so the six become
  `nativeQuery = true` — SQL inside `@Query`, a contract neither the compiler nor CI checks, which
  is why the wired half is driven by the real statements rather than asserted from the source.
  **Two guards, because neither can do the other's job.** `DatabaseClockConventionTest` is static
  and scans string literals of `src/main/java` — SQL reaches PostgreSQL from here as a string, so
  this is the *opposite* use of `AsyncExecutorQualifierTest.strip`'s literal mask from #282's and
  #280's guards, which is why all three share one stripper. `CURRENT_TIMESTAMP`, `now()`,
  `clock_timestamp()`, `statement_timestamp()` and `transaction_timestamp()` fail unless
  `AT TIME ZONE 'UTC'` follows; `LOCALTIMESTAMP` and `CURRENT_DATE` fail outright, since they have
  already resolved the session zone and returned a value with none left in it, so wrapping would
  reinterpret rather than convert. `DatabaseClockUtcIntegrationTest` is the wired half and has
  teeth **in CI**, which the #282 round trip deliberately does not: it moves the session zone to
  `America/Los_Angeles` with `SET LOCAL` — undone by the transaction it runs in, where a plain
  `SET` would ride the pooled connection into another cached context and make this file the
  #226/#245 contamination it is protecting against — asserts the offset is really there before
  asserting anything about a column, then drives each of the six statements and reads the raw
  column back. Mutation-proven: restoring one bare `CURRENT_TIMESTAMP` reddens the scan and exactly
  one wired case.
  **Two populations are named as out of scope rather than left implied.** Eleven **applied**
  migrations declare columns `DEFAULT CURRENT_TIMESTAMP`; an applied migration is never edited, and
  those defaults cannot fire in production anyway — every INSERT on those tables goes through JPA
  with the column mapped, and the one native INSERT (`insertPendingIfAbsent`) binds its own `:now`.
  New migrations are held to the convention above an anchor of **V57**, a constant set once, the
  shape `MigrationDocumentationConsistencyTest` already has over these files. And ~40 test fixtures
  seed rows with the bare form: wrong in the same way, red only outside UTC, and a sweep of its own
  rather than a consequence of this one (**#287**).
  **Already-written rows**: on a UTC deployment the wrapped expression is byte-for-byte the old one,
  so no row changes meaning and there is no data migration; rows written by a non-UTC session —
  local development — shift by that session's offset. `ENV TZ=UTC` and `ContainerTimeZoneContractTest`
  (#280) stay, their role for the session zone dropping from load-bearing to belt-and-braces while
  they keep earning their place on logs. No REST, gRPC, proto, DTO, **migration (V58 stays free)**,
  `specs/NNN-*`, configuration-key, metric, S3-key or frontend change; the three repository methods
  keep their names, signatures and semantics — only the statement behind them says which zone it
  meant. See `README.md` ("Time zones").
- utc-write-convention: A zone-independent `TIMESTAMP` column has one producer, and the conversion
  that made two of them necessary is gone (issue #282, the write side #280 deferred). `src/main`
  held 65 `LocalDateTime.now()` calls in the legacy aggregates and 44
  `LocalDateTime.now(ZoneOffset.UTC)` calls in delta, and
  `spring.jpa.properties.hibernate.jdbc.time_zone: UTC` made **exactly the first set correct**,
  shifting the second by the JVM's offset — invisible on a UTC JVM, the #279/#280 blind spot again.
  **Outcome 2 of the ticket: the setting is removed and every producer yields UTC directly.**
  #280 had rejected that option because it "repairs 44 places and breaks 65"; normalising the
  producers is what this ticket is, so the option became available — and two facts, both established
  here rather than assumed, made it the right one. **The measurement that unblocked it**: on a JVM in
  `Asia/Jerusalem` with the setting pinned to a *third* zone (`America/New_York`), an `Instant` field
  on a `TIMESTAMP` column stored the UTC wall clock **unchanged**, while a `LocalDateTime` moved. So
  `Instant` binding never consulted the setting at all, and the ~15 `TIMESTAMP` columns held as
  `Instant` (`refresh_tokens`, `account_plugins`, `plugin_configs`, `admin_action_logs`,
  `comparison_results`, `file_comparisons`) — whose producers have no zone knob at the call site and
  so could not have been repaired by any convention — were never at risk. That was the one thing
  capable of making outcome 2 a much larger ticket, and it is now pinned by a test rather than left
  as a measurement in a comment. **The fact that decided against outcome 1**: `toInstant(ZoneOffset.UTC)`
  appears in **22 DTO files** across `batch/`, `site/`, `error/`, `upload/` and `delta/`, so the whole
  presentation layer already reads an in-memory `LocalDateTime` as a UTC wall clock — a convention
  `DeltaTimestampsTest` has asserted since 023 r3. Keeping the setting and standardising on
  `LocalDateTime.now()` (the 65-call majority, and the cheaper diff) would have made every
  Hibernate **read** return JVM-local wall clock and all 22 files emit the wrong instant off UTC.
  Outcome 1 was not the conservative choice; it was the one that contradicted the layer that already
  had a convention. What ships instead: `LocalDateTime` in memory equals the column equals UTC, on
  every path at once — JPA, raw JDBC (#280), the native `clock_timestamp() AT TIME ZONE 'UTC'`
  catalog watermark, and the DTOs — with no conversion anywhere.
  **Already-written data (the ticket's fourth criterion, answered rather than waved at)**: on a UTC
  JVM the removed conversion is the identity, so no stored row changes meaning and there is no data
  migration; the deployed container has declared `TZ=UTC` since #280. Rows written by a JVM outside
  UTC — local development only — shift by that JVM's offset.
  **The guards are two, because neither can do the other's job.** `TimestampProducerConventionTest`
  is static and bans the zero-argument `now()` in `src/` — `LocalDate` included, since that date
  picks which monthly partition of `error_logs` a UTC `occurred_at` lands in — and asserts in the
  same class that `hibernate.jdbc.time_zone` is **absent**, because with the setting back the ban
  would enforce the wrong producer. It reuses `AsyncExecutorQualifierTest.strip` (literal mask
  included) and reads configuration with whole-line comments blanked, after the first draft failed
  on the very comment recording why the setting is gone — a hazard its own test now pins.
  `TimestampRoundTripIntegrationTest` is the end-to-end statement #280 wanted and refused: write
  through JPA, read the same column through `JdbcTemplate`, require equality. With the conversion in
  place that test is red on every non-UTC JVM — #279 recreated by its own guard — and removing the
  conversion is what makes it true. **Where the teeth are is stated rather than assumed**: it runs in
  the ambient zone, so on a developer's machine outside UTC it is mutation-sensitive, while in CI —
  which runs in UTC, where the removed conversion is the identity — it would stay green, so what
  stops the setting coming back there is the static guard, which asserts its absence directly in
  every environment. **A cut that switched the JVM default zone (UTC, `Asia/Jerusalem`,
  `America/Los_Angeles`) to buy teeth in CI was written and withdrawn in review**, and the reason is
  worth keeping: pgjdbc takes a connection's PostgreSQL session zone from the JVM default *at connect
  time*, and this suite shares one database and one pool across cached contexts with
  `minimum-idle: 0` and a ten-second idle timeout — so a connection opened inside such a window
  carries a non-UTC session zone back into the pool, and a later JPQL `SET … = CURRENT_TIMESTAMP`
  from an unrelated test writes a local wall clock into a column everything else fills with UTC. That
  is the #226/#245 class of silent, order-dependent contamination, and much worse than the blind spot
  it was buying — the hazard is created by the very pgjdbc behaviour this entry documents two
  paragraphs down. Mutation-proven where each guard can be: restore the setting and the static guard
  goes red anywhere (and the round trip goes red off UTC); restore one bare `now()` and the scan goes
  red.
  **Two simplifications are consequences, not tidying**: `ChangelogSegmentQueueMarkerClobberTest.asStored()`
  (#278, part B) existed only to reapply the binding's conversion so a repository write could be
  compared with the raw column in any zone — it is the identity now and is gone, and with it the only
  entry of `RawTimestampReadConventionTest`'s allowlist. That test's scoping case was rewritten to
  drive a synthetic exemption rather than deleted as its own instruction proposed, since scoping is
  what the next entry will depend on and a test asserting nothing was the thing that instruction
  objected to.
  **Deliberately out of scope, and named rather than implied**: the 6 JPQL `SET … = CURRENT_TIMESTAMP`
  writes are a *third* producer, the database clock. Measured here: `SHOW TimeZone` reports the JVM's
  zone, because pgjdbc sets the session zone from it — so off UTC those statements write local wall
  clock into the same columns. Rewriting them as bound parameters moves the time source out of the
  database, which #245 chose deliberately for the queue markers, so it is its own decision and is
  filed rather than taken (**#286**). `ENV TZ=UTC` and `ContainerTimeZoneContractTest` (#280) stay: their role
  changes from load-bearing to belt-and-braces plus that session zone. No REST, gRPC, proto, DTO,
  migration (**V58 stays free**), `specs/NNN-*`, configuration-key *name*, metric, S3-key or frontend
  change. See `README.md` ("Time zones").
- batch-parquet-retention-hold: Changelog retention consults the third durable consumer of raw
  segments — the completed-batch Parquet build — and the two windows it cannot cover now say so
  instead of retrying into the same ending (issue #244, filed by review round 1 of PR #235 as
  bucket C2 of #212 and pre-existing before it). `batch_parquet_artifacts` is the durable queue of
  the 036/038 finalization and every attempt replays the batch's **raw** segments
  (`findByBatchIdOrderByFirstSeq`) — on the worker's backoff retries, on a 039 admin requeue, on
  the 037 legacy backfill — while `ChangelogRetentionService.prune` consulted only #212's two queue
  markers. A batch checkpointed and pruned while its artifact row was still `PENDING`/`FAILED`
  therefore lost its replay input permanently.
  **The prune predicate is extended, and the unit of the decision is the batch.** A below-checkpoint
  segment whose batch has an artifact row in `BatchParquetArtifactStatus.UNFINISHED`
  (`PENDING`/`BUILDING`/`FAILED`) is held back, all of that batch's segments together — a *partial*
  prune is worse than an empty one, since `expectedRowCount` is derived from the segments actually
  loaded, so the row-count guard agrees with the truncation and the artifact publishes `READY`
  silently missing rows. Counted on
  **`delta.retention.segments.held-back{reason=pending_batch_parquet}`** (a third tag value,
  registered at zero, nothing renamed) and named in the same per-site WARN. **Unlike #212's
  hold-back this one is bounded by construction**: a row leaves `UNFINISHED` after
  `delta.batch-parquet.max-attempts` (~1 h) as `READY` or `ABANDONED`, so it needs no age or count
  bound of the prune's own. The census is **one query per pass** over the candidate batch ids
  (`findBatchIdsWithStatusIn`), never one per segment, and the predicate travels **with** the
  conditional DELETE as a `NOT EXISTS` — the #212 rule, against the race that is real here: a lazy
  backfill or an admin requeue committing between retention's read and its delete creates exactly
  the work row that needs those segments. A refused delete whose markers still read processed can
  only have been refused by that predicate, and is counted as such.
  **The two windows the hold-back deliberately does not cover are the DoD's second half, and they
  are made explicit rather than closed.** `READY` and `ABANDONED` are terminal and prunable, so an
  `ABANDONED` row requeued a week later (039) and the legacy backfill of a pre-036 batch with no
  rows at all (037) can both find the segments gone — retention had nothing to consult when it ran.
  Before this, the replay's `IllegalStateException("Batch has no published changelog segments")`
  went into the generic catch as a *transient* failure: ~an hour of identical retries to reach the
  same `ABANDONED`, with the reason buried under them. New `BatchSegmentsUnavailableException` is
  classified **permanent** (abandoned on the first attempt, message naming retention, a wipe and a
  re-baseline as the takers), the admin requeue refuses with **409** (`ArtifactUnproducibleException`,
  a subclass of `ArtifactNotRequeueableException` so the route's existing catch and its documented
  409 are unchanged) instead of queueing an attempt whose only ending is that abandon, and the
  backfill logs the unproducible batch instead of answering 0 silently before the download 404s.
  **The residual is documented rather than implied**: a batch pruned only *in part* while all its
  rows were terminal can still be requeued or backfilled and renders only the surviving segments.
  In none of these cases are the records lost — they are in the site's checkpoint; the per-batch
  artifact is.
  Tests first and mutation-proven: with the census disabled four unit tests go red, with the
  permanent classification reverted one, with the requeue refusal removed one; the JPQL half of the
  DELETE predicate — SQL inside `@Query`, which neither the compiler nor CI catches — is driven
  against the real statement by an integration test, as #212's marker half already was. The
  admin-queue contract fixture now seeds the batch's segment (with both queue markers stamped, so
  the global site-blind queues cannot claim it) and gained the 409 case. No REST **route**, gRPC,
  proto, DTO, migration (**V58 stays free**), configuration-key, metric-**name**, S3-key or frontend
  change. See `docs/delta-client-v2-guide.md` ("Retention does not delete unprocessed work"),
  `docs/cr-unified-batch-parquet.md`.
- utc-read-convention: A zone-independent `TIMESTAMP` column has one documented reading convention,
  and the JVM zone the two paths agree in is declared rather than inherited (issue #280, found
  reviewing #278/#279 and filed as a decision rather than as a three-line edit). Two conventions
  existed and nothing asserted they matched: through **Hibernate**, `hibernate.jdbc.time_zone: UTC`
  reads a bound `LocalDateTime` as wall clock in the *JVM's* zone and stores that instant in UTC,
  converting back on read — symmetric, so it is self-consistent in any zone; through **raw JDBC**
  (`ParquetExportCatalogDao`, the only such reader in `src/main`) there is no conversion, so the
  value is the column's own, i.e. UTC. On a UTC JVM the two coincide, which is why CI and the
  deployed containers never showed it, and off UTC they differ by the zone's offset — exactly how
  #279 surfaced, as a test deterministically red for anyone outside UTC.
  **The owner chose option 1 + the guard, plus the container declaring UTC; option 2 was rejected,
  and the fact that decided it is not in the ticket.** Removing `hibernate.jdbc.time_zone: UTC`
  would unify the two *readers* and split the *writers*: `src/main` produces these values two ways
  — 65 `LocalDateTime.now()` calls in the legacy aggregates, which are correct precisely because
  the conversion is there, and 44 `LocalDateTime.now(ZoneOffset.UTC)` calls in the delta subsystem,
  already UTC and therefore converted a second time (off UTC they store `UTC − offset`). So option
  2 repairs 44 places and breaks 65, and is only coherent after all 109 producers are normalised —
  a run of its own, filed as **#282** rather than folded in, since this ticket's acceptance
  criteria are about reading.
  **What ships.** The three catalog reads become `rs.getObject("produced_at", LocalDateTime.class)`,
  which is also a narrow fix rather than a pure restatement: `rs.getTimestamp(col).toLocalDateTime()`
  cancels its own JVM-zone conversion for almost every value — which is what makes it look harmless
  — but not for a stored wall clock falling in the JVM zone's DST gap, where the local time does not
  exist, `Calendar` resolves it forward and the value returns shifted by the transition. That
  corrects, rather than contradicts, the `commit-gate-outside-ci` entry below, which calls the same
  substitution "a no-op" and `getTimestamp(...).toLocalDateTime()` "a net identity": it is the
  identity for every value except one landing in that gap, which is why the substitution alone was
  the wrong fix for #278's test and is still the right convention here. Both the
  `since` parameter and the cursor already bound as `LocalDateTime` through JDBC 4.2, so nothing on
  the writing side of this DAO moves and `producedAt` is unchanged for every Parquet Export client
  on a UTC deployment.
  **The guard is static, and that is a decision rather than a convenience**:
  `RawTimestampReadConventionTest` bans `rs.getTimestamp(…)`, `Timestamp.valueOf(…)` and
  `new Timestamp(…)` across `src/main` and `src/test`, because the obvious guard — read one row
  both ways and require the two to agree — is red on every JVM outside UTC, i.e. #279 recreated by
  the test meant to prevent it. It reuses `AsyncExecutorQualifierTest.strip` (now package-private)
  rather than growing a second scanner, and needs its literal mask, not only the comment stripping:
  its own fixtures are Java sources held in string literals. One exemption, carrying its reason —
  `ChangelogSegmentQueueMarkerClobberTest.asStored`, which models the Hibernate binding's conversion
  on purpose (#278, part B) — and an exemption whose file no longer names a banned shape fails the
  test, since a stale one is how a ban quietly stops being one.
  **The container's zone was an accident and is now a contract**: nothing in this repository set it,
  so the whole agreement rested on the base image's default. `ENV TZ=UTC` in both runtime stages of
  the `Dockerfile` — through `TZ` and not `-Duser.timezone` in `JAVA_OPTS`, which callers replace
  wholesale (`docker-compose.prod.yml`), so a flag added there would be dropped by the next
  deployment that tunes the heap. `ContainerTimeZoneContractTest` holds both halves: every runtime
  stage declares UTC, and no manifest pins another zone — an overlay doing so would satisfy the
  first check and defeat it in the same breath. Both guards are mutation-proven (restore
  `getTimestamp` → the scan names the three lines; drop `ENV TZ=UTC` or pin `TZ` in a manifest →
  the contract fails), and each carries unit tests over synthetic sources so a scanner that has
  gone blind is not mistaken for a clean tree.
  No REST, gRPC, proto, DTO, migration (**V58 stays free**), configuration-key, metric, cache,
  S3-key or frontend change; `hibernate.jdbc.time_zone: UTC` is deliberately untouched, so no
  already-written row changes meaning. See `README.md` ("Time zones").
  **Review round 1 found both guards blind in the same direction — passing while the thing they
  forbid is present.** The manifest scan read only `KEY: value`, so the **canonical Kubernetes env
  form** — `- name: TZ` on one line, `value: …` on the next, which is how a `Deployment` sets a
  variable and which wins over the image's `ENV` — was invisible: a manifest could pin
  `Europe/Berlin` with every assertion green, i.e. the container contract defeated in the one place
  it is actually written. All three spellings are read now (assignment, two-line env entry, flow
  mapping), a `valueFrom` whose value is not a literal reads as unresolved rather than as absent so
  the guard fails closed on what it cannot prove, and the reader has tests of its own. And the
  source ban exempted a whole **file** where its reason covered one **line**: the next
  `rs.getTimestamp` added to `ChangelogSegmentQueueMarkerClobberTest` — the one class whose subject
  is this very conversion — would have passed unseen. The exemption is scoped to a shape and a
  count, so a second use is a new decision rather than a consequence of the first. Both fixes are
  mutation-proven (a `- name: TZ` env entry in `deployment-backend.yaml`; a `getTimestamp` added
  beside the exempted `Timestamp.valueOf`).
- commit-gate-outside-ci: The per-task gate stopped lying in both directions (issue #278, folding
  **#279** — both found finishing #205/PR #255, both about the mandatory commit gate misreporting
  outside CI, and both claimed `build.gradle.kts`, so they could never have run in parallel).
  **Part B — red where nothing is broken.** `ChangelogSegmentQueueMarkerClobberTest` (#245) failed
  two methods on a branch whose diff was five markdown files and zero lines of code, deterministically,
  for anyone outside UTC — and `CLAUDE.md` both makes the gate mandatory and forbids `--no-verify`,
  so a docs-only commit cost a cycle chasing somebody else's test, the class of defect #207 and #226
  were filed for. **The ticket's diagnosis was wrong and that is the part worth keeping**: it named
  the read (`rs.getTimestamp` without a `Calendar`) and prescribed `rs.getObject(col,
  LocalDateTime.class)`; that change is a **no-op** here, since `getTimestamp(...).toLocalDateTime()`
  builds an instant in the JVM zone and converts it straight back, a net identity. The shift is on
  the **write**: `hibernate.jdbc.time_zone: UTC` (`application.yml`) makes Hibernate read a bound
  `LocalDateTime` as JVM-local wall clock and store the same instant in UTC. Production is symmetric —
  it writes and reads through Hibernate, so the conversion cancels, and `plugin_sql_retry_at` /
  `egress_retry_at` are compared against the database's own `CURRENT_TIMESTAMP` — while this class
  deliberately reads the **row** through `JdbcTemplate` (#245's reason: merge copies detached values
  onto the managed instance, so only the generated statement proves the mapping), where it does not.
  Writing `LocalDateTime.now(ZoneOffset.UTC)` and comparing it with the raw column therefore differed
  by the JVM's offset. Fixed by binding a JVM wall clock, as production does, and expecting the stored
  form (`asStored`, the identity in UTC); verified green under `TZ=UTC`, `Asia/Jerusalem`,
  `America/Los_Angeles` and `Australia/Sydney`, red outside UTC without it. Pinning the test JVM's
  zone (the ticket's option 3) stays **not taken**: it is a new build-wide invariant that would hide
  this class of defect rather than show it, and it was not needed. The `getTimestamp` +
  `LocalDateTime` shape has no other occurrence in `src/test`; the three in
  `ParquetExportCatalogDao` are production and a finding of their own (**#280**).
  **Part A — green where something is broken.** The "Recent Changes" journal ships to `CLAUDE.md`
  and `AGENTS.md` alike, and nothing held it: the omission is invisible in the diff of a PR that
  writes to one file. #205 fixed a single miss and answered "no guard needed, it is a review habit";
  four days later three more entries were missing (`double-nan-sql-literal`, `adopt-path-side-effects`,
  `signed-nan-classification`), each written to `CLAUDE.md` by its own PR — one miss is a slip, four
  in a week is a mechanism. #205's counter-argument was half right: the journals are genuinely unequal
  (81 slugs to 97), but 13 of the 16 differences are the block `AGENTS.md` stopped carrying and later
  resumed (`033`–`042`, `tag-driven-dev-deploy`, `plugin-secret-reveal`,
  `agent-migration-doc-consistency`), and since entries are only **prepended**, the newest slug of
  that block — `042-parquet-phase-metrics` — can never move. That is one constant set once, not a
  boundary anybody maintains, the shape `MigrationDocumentationConsistencyTest` (#104) already has
  over these two files. `AgentJournalConsistencyTest` asserts three things above that anchor:
  presence, no duplicate within either file (#205 found `split-scratch-ceilings` twice, the copies
  differing exactly in the clause #153 made stale), and — added here — that the shared entries appear
  in the **same order** in both, since an entry backfilled into the wrong place is the same defect one
  step quieter. That third assertion immediately found a pre-existing inversion, `split-scratch-ceilings`
  ahead of `ingestion-commit-no-s3` in `AGENTS.md` while #147 is newer than #138: fixed by moving the
  entries, not by weakening the check. All three mutation-proven (drop a backfilled entry, duplicate
  one, move one) and red before the backfill on exactly the three slugs. `build.gradle.kts` already
  declared both documents as test inputs (#104); the pre-commit hook's docs-only branch now runs
  **`com.bitbi.dfm.documentation.*`** rather than one named class, because naming classes one by one
  is how the next guard is added and silently never run. Test, documentation and hook only: no
  production code, REST, gRPC, proto, DTO, migration (**V58 stays free**), configuration-key, metric,
  S3-key or frontend change.
- retention-no-s3-in-transaction: The changelog retention pass no longer holds a HikariCP
  connection across its object deletes (issue #234). `ChangelogRetentionService.prune` was
  `@Transactional` around everything, so the batched `DeleteObjects` round trip ran with the pass's
  transaction — and every row lock it had taken — still open, on the nightly `CheckpointScheduler`
  tick, per site, serially, for a hold proportional to the site's backlog; the pool's floor
  arithmetic (#161) assumes background work releases between statements, and every neighbour on this
  path already states the invariant (#147 the ingestion commit, #164 both queue workers, #176 the
  Parquet Export listing). Retention was simply never named by those tickets. **The #164 shape,
  nothing more**: the wrapping `@Transactional` is gone, the below-checkpoint projection read and
  each conditional row delete (`deleteByIdIfProcessed`, already a single statement with its own
  `@Transactional` on the repository) are short transactions of their own, and the S3 half runs with
  nothing open — with a `refuseInsideTransaction()` guard checked before anything is read, because a
  caller that wrapped the pass would restore the hold while every assertion about what is pruned
  still passed. The other two DoD items were **already delivered by #212's review round 1** and are
  unchanged here: the delete is one batched 1000-key `DeleteObjects` (not one round trip per
  segment), and the ordering is row first, object after the delete reported success, so a crash in
  between leaves an unreferenced object the #158 orphan sweep reclaims rather than a row pointing at
  nothing. **The one behaviour change is stated rather than implied**: partial progress now stands
  where it used to roll back — a pass interrupted after fifty rows has pruned fifty segments — which
  is the intended direction (a pruned row is durable work, not a step of one atomic pass) and is
  also why the failed-object-delete catch stays: the rows are already gone, and a throw would report
  a healthy prune to `CheckpointScheduler` as this site's failure. **Review round 1 found the half
  that made partial progress unsafe**: the object delete was reached only by falling out of the
  loop, so an exception raised *inside* it — a lock timeout on one row, a pool timeout, a failover —
  left every row deleted so far committed with its key never handed to S3, i.e. the pass leaked
  precisely the objects the row-first ordering exists to bound (a site failing on row 1200 of 3000
  strands 1199 of them), and the reclaim path it was leaning on ships `delta.s3-orphan.dry-run:
  true`, inert until an operator turns it on. The delete now runs in a `finally`. The two test
  findings were the same class of over-claim: the annotation guard read only the *method-level*
  Spring `@Transactional`, so a class-level one — or the `jakarta` variant — would have restored the
  hold with the assertion green (it reads both, on the method and the declaring class, through
  `AnnotatedElementUtils`), and the integration spy asserted "exactly one `deleteObjects` in this
  context", which any concurrent batch-retention pass would have failed while blaming this ticket
  (it is scoped to this test's own key and its list is synchronized).
  **Round 2 found the same asymmetry one level up**: durable partial progress needs durable
  accounting, and the held-back counters, their WARN and the `Pruned N` INFO all sat *after* the
  `try`/`finally`, so a pass aborting on row 1200 of 3000 deleted 1199 rows and their objects while
  reporting nothing but `CheckpointScheduler`'s generic per-site failure — with the #212
  stuck-backlog alarm reading zero for a pass that had just observed the backlog. They moved into
  the `finally` with the delete. Two test corrections with it, both about a test claiming more than
  it can: the unit test's `isActualTransactionActive()` assertion was **vacuous** (a service built
  with `new`, a mocked repository, no transaction manager — nothing there could fail it, restoring
  `@Transactional` included), so it is gone and the method is named for what it does pin, the
  row-before-object order; and the mid-pass test left `deleteObjects` unstubbed, so its `verify`
  passed through an NPE swallowed by the delete's own catch — the success branch it documents was
  never taken.
  **Round 3** closed the same masking hazard one statement further and named the cost. The
  reporting tail was unguarded inside the `finally`, so a meter or a log appender failing during a
  rollout would have replaced the exception the loop was unwinding with — and `CheckpointScheduler`
  logs the message alone, so the lock timeout that actually ended the pass would have been gone;
  `reportPass` swallows its own failure deliberately, there being nowhere left to report it. The
  integration spy's *stub registration* was still racing (the round-1 fix guarded the result list
  only), which is the `UnfinishedStubbingException` flake class of #119/#159/#226 wearing this
  ticket's name: it is installed in `@BeforeEach`, records unconditionally and lets the assertion
  filter. And the cost is now written down beside the benefit rather than only the benefit: the
  hold drops, the *number* of transactions rises to one per pruned segment, so a large-backlog
  site's nightly visit takes longer in wall-clock terms with each acquisition independently subject
  to the 30 s `connection-timeout` — the deliberate trade of this shape everywhere it is used
  (#147, #164). **Round 4** was two more of the same asymmetry and one deferral. The swallow added
  in round 3 was unconditional, so on a *successful* pass a broken meter (Micrometer raises on a
  name/tag conflict, and this series is registered from more than one place) would have left the
  #212 alarm half-emitted — the first counter moved, the second not, both log lines lost — and
  `prune` returning normally with the error nowhere; it swallows only while the loop is
  already unwinding — round 5 then settled what it does otherwise, see below. And the object keys are flushed **every 1000 during the loop**
  rather than only at the end: a pod kill mid-pass strands at most one chunk instead of everything
  the pass had deleted, which matters precisely because the reclaim path ships dry-run. It costs
  nothing — `deleteObjects` chunks at 1000 anyway. The finding that was **not** taken is the
  transaction *count*: one `begin`/`commit` and one pool acquisition per pruned segment over an
  unbounded set, serially, under `CheckpointScheduler`'s `buildLock`, so a ~10^5-segment first
  prune after an outage can starve the later sites of that tick — a chunked conditional delete
  (`DELETE ... IN (...) ... RETURNING id`) needs a new repository method and native SQL, a wider
  decision than "take the S3 call out of the transaction", and the blast radius belongs with
  **#193**, which is exactly that theme and now carries the evidence.
  **Round 5 settled the reporting question that rounds 3 and 4 had pushed back and forth**, and the
  synthesis is worth keeping because both earlier positions were half right: swallowing silently
  leaves the #212 alarm half-emitted with no error anywhere (round 4's point), while rethrowing
  reaches `CheckpointScheduler`'s catch, which logs "Checkpoint build/retention failed" for a site
  whose checkpoint was built and whose rows were pruned — an operator sent to a healthy site
  (round 5's). A reporting failure is now logged **as a reporting failure** and the pass returns
  what it did. The masking route the round-3 comment claimed to have closed was still open in two
  places, both reached from the `finally`: the log line in `reportPass`'s catch and the WARN in
  `deletePrunedObjects`'s, either of which would replace the exception on its way out if the
  appender were the thrower — while unwinding, both now run through one `swallowing(Runnable)`
  helper instead. And the object-delete lines counted **this chunk** while reading "Pruned N
  changelog segment row(s)", so a 2500-row pass whose first chunk reported errors told an operator
  it had pruned 1000 — misreporting exactly the large backlogs this ticket is about; the wording is
  chunk-accurate now. **Round 6** was three consequences of round 5's own edits and one stale
  sentence in this entry: the reporting is attempted **step by step**, because one `try` around all
  four meant a throw from the first counter still skipped the second and both lines — the
  half-emitted alarm arrived at by another route; the WARN no longer claims "the pass completed",
  since `reportPass` also runs while the loop is unwinding; and the flushed chunk is handed over as
  a copy, the buffer being cleared on the next line while `deleteObjects` builds `subList` views
  over what it is given. A last round tightened the swallow to `Throwable` (bar `VirtualMachineError`):
  during context teardown the finally path can raise a `NoClassDefFoundError` from an SDK being torn
  down under it, which would both replace the loop's exception and escape `CheckpointScheduler`'s
  `catch (RuntimeException)` — ending the whole nightly tick instead of costing one site. The **successful** path got the
  symmetric treatment a round later — there is no in-flight exception to protect there, so the same
  class is logged rather than swallowed — and the mid-loop flush now takes its chunk out of the
  buffer before deleting it, so an `Error` escaping the flush cannot leave those keys for the
  `finally` to delete a second time. Tests were written first and are
  red against the old shape: `ChangelogRetentionOutsideTransactionTest` (fast gate) pins the absent
  annotation, the refusal and the row-before-object order, while the wired half lives in
  `ChangelogRetentionIntegrationTest` — only the application can show that the repository's own
  proxied short transactions have actually committed by the time the objects go, so a
  `@MockitoSpyBean` records `isActualTransactionActive()` at the real `deleteObjects`. No REST,
  gRPC, proto, DTO, migration (V57 is the last applied, V58 free), configuration-key, metric, S3-key
  or frontend change. See `docs/delta-client-v2-guide.md` ("No S3 inside the retention pass").
- docs-recent-changes-drifts: Four accumulating documents now describe the repository they are
  about (issue #205, folding **#218**). None is a code defect; all four are the case this file
  already calls out — one document says one thing and another says something else about the same
  place — and they are one branch because they share no files with each other and none with
  product code. **The guide's connection floor said four long ticks.** #158 / PR #198 added
  `DeltaS3OrphanSweeper` as a fifth `Cost.LONG` tick and moved the floor to
  `5 long ticks + 2 request reserve = 7`; round 4 of that review fixed the contradiction inside
  `application.yml`, the #158 journal entry says five, and `BackgroundConnectionDemandTest`
  derives the term from `ScheduledTaskInventoryTest.longRunningTaskCount()` — four annotated
  `Cost.LONG` tasks plus the programmatic `BatchRetentionScheduler` cron, so five. The guide was
  the only surface left carrying the old number, precisely because it lives outside the file that
  review touched. It says five. **`AGENTS.md` was missing `prefix-walk-paged`** (#199 / PR #203
  wrote it to `CLAUDE.md` only) and carried `split-scratch-ceilings` **twice**, the two copies
  differing in exactly the clause #153 made stale — an assertion and its superseded variant living
  in one file, which an agent reading top to bottom resolves by accident. The entry is restored
  between `test-profile-scratch-directory` and `s3-orphan-sweep`, its position in `CLAUDE.md`; the
  stale duplicate is deleted and the corrected copy kept. **`README.md` documented
  `./gradlew contractTest`** (#218), a task `build.gradle.kts` does not register — it declares
  exactly `test` (with `-PexcludeIntegration`) and `integrationTest` — so the one command a
  newcomer runs to check the contract suite failed with "task not found". It names the supported
  gate, matching "Commands" in this file and the gates table, with `--tests '*ContractTest'` for a
  single class; adding a real `contractTest` task was rejected as a new gate nobody asked for.
  **`docs/cr-bitbi-delta-sql.md` residual risk 3** still described the delta-SQL queue as holding
  a database connection across S3 as a live accepted trade-off, which #164 retired:
  `processNextPending` dropped its wrapping `@Transactional`, the claim and the mark are short
  repository transactions, and the class Javadoc, the guide ("No S3 inside a queue worker") and
  the #164 entry all say so. Removed rather than reworded — there is no residual risk left to
  state — and the list renumbers; the retention item keeps the narrowing #212 gave it.
  **The DoD's third item — is a guard worth having for the missing journal entry — is answered
  yes, and the reversal is the durable part.** #205 was written to answer *no*, on a census of
  2026-08-19 that found a single omission and three same-day merges landing correctly in both
  files. Finishing the ticket four days later re-ran that census and the premise was gone:
  `AGENTS.md` is missing **three more** fresh entries, each written to `CLAUDE.md` by its own PR —
  `adopt-path-side-effects` (#246), `double-nan-sql-literal` (#233), `signed-nan-classification`
  (#238). One omission is a slip; four in a week is a mechanism, and the mechanism is that the
  omission is **invisible in the diff** of a PR that touches only one of the two files, so no
  amount of review habit catches it. The *other* half of #205's reasoning — that a guard would
  need a hand-placed boundary, "a second convention maintained by nobody" — is half right and the
  half that is wrong is what unblocks the guard: the two journals are genuinely unequal (81 slugs
  to 97 today, 13 of the 16 differences being the `033`–`042` block plus
  `tag-driven-dev-deploy`, `plugin-secret-reveal` and `agent-migration-doc-consistency`, which
  `AGENTS.md` stopped carrying and later resumed), but entries are only ever **prepended and never
  removed**, so the anchor at the end of that gap — `042-parquet-phase-metrics` — never moves. It
  is one constant set once, not a convention, which is exactly the shape
  `MigrationDocumentationConsistencyTest` (#104) already has over these same two files. Above that
  anchor the predicate is mechanical and has no false positive, since the repository's convention
  is that an entry rides in both. Building it needs the three missing entries backfilled first, so
  it is **#278** rather than this branch, and this entry is what stops the answer being lost.
  Documentation only: no product code, test, REST, gRPC, proto, DTO, migration (**V58 stays
  free**), configuration-key, metric, S3-key or frontend change.
- unpaired-sql-started: A lost SQL-generation unique claim now writes a terminal
  `SQL_GENERATION_ADOPTED` instead of leaving the account's plugin log with an unpaired
  `SQL_GENERATION_STARTED` (issue #260, the gap #246 named and left open). Since #246 the loser of
  `uk_sql_gen_source_batch` adopts the winner's generation and writes no second
  `SQL_GENERATION_COMPLETED` — that entry named the S3 object the adopt path had just deleted —
  so a raced batch read as two "Generating SQL..." lines and one "SQL Generated". The vocabulary
  had no value for that outcome: reusing `COMPLETED` is the duplicate #246 removed, and `FAILED`
  is a `success = false` row claiming an error that did not happen. **New action type**, not
  retiring the pairing: `PluginActionType.SQL_GENERATION_ADOPTED`, V57 widens
  `chk_plugin_audit_logs_action_type` (NOT VALID + VALIDATE, the V44/V48 split), metadata names
  the adopted `generationId` and the winner's `s3Key`, and the Logs tab labels it **SQL Already
  Generated**. Written immediately (`@Async("pluginExecutor")` + save), like STARTED, because the
  attempt happened regardless of any surrounding transaction and the winner already owns the row.
  `SQL_REGENERATION_*` stay as read-only history (#190). The pairing invariant of
  `generateSqlForBatch` is stated and pinned: every exit that writes STARTED writes a terminal
  (`COMPLETED`, no-changes COMPLETED, `FAILED`, or `ADOPTED`); documented exceptions (baseline /
  missing batch data, #181 memory-pressure above STARTED, #261 semaphore timeout before
  `doGenerateSqlForBatch`) write neither. `SqlGenerationStartedPairingTest` holds the inventory
  (the adopt early-return writes ADOPTED before it returns); the behavioral exits live in
  `SqlGenerationServiceTest.DeltaV2Routing`; the real-constraint twin
  `SqlGenerationConcurrentClaimIntegrationTest` awaits exactly one ADOPTED row naming the
  surviving generation and key, still exactly one COMPLETED carrying the surviving key, still one
  `claims.lost`. No REST, gRPC, proto, DTO shape, configuration-key, metric-name, S3-key or
  TanStack-Query-key change; no `specs/NNN-*`. See `docs/020-sql-generation-optimization.md`.
- checkpoint-scratch-reserve: A completed-batch backlog can no longer fill the Parquet scratch
  directory for the length of the 02:00 sweep and abort every site's nightly checkpoint (issue
  **#193**, the asymmetry #150 named and left open). `delta.parquet.max-scratch-bytes` is one pool
  shared by three writers; the batch side degrades one artifact at a time, while a refused
  checkpoint frame (or, since #150 r2, a refused table snapshot) ends the build, and
  `CheckpointScheduler` walks sites serially — so a directory held full overnight froze
  `last_checkpoint_seq` and retention fleet-wide. **Reserved share**, the ticket's first option:
  batch writers may use at most the directory budget minus `delta.checkpoint.max-frame-temp-bytes`,
  already the declared size of the largest scratch file the checkpoint path holds, and it holds
  only one at a time (#178). That is a floor for the nightly sweep, not a ceiling — a checkpoint
  writer still sees the whole budget when the directory is idle — compared against batch live
  bytes, not the directory total, so a frame in flight does not shrink the batch share a second
  time — and batch cannot consume into the reserved bytes even after the frame is deleted, which
  is the gap before the table snapshot opens. Unbounded (the shipped default) ignores the reserve; a negative reserve is none; a
  reserve larger than the budget leaves batch with zero. A refused reservation keeps existing
  failure modes (`FAILED` + backoff for batch; the build ends for checkpoint) and is still off
  `delta.checkpoint.builds.aborted`, whose values are permanent by contract (#153) —
  `delta.parquet.scratch.refused` is the series. No new configuration key. Deployed arithmetic
  is unchanged (5 GiB directory, 1.5 GiB frame, 3.5 GiB batch share); the ceiling-budget guard
  now requires that share to fit at least one completed-batch artifact. Tests first, mutation
  by dropping the batch cap. No REST, gRPC, proto, DTO, migration (**V57 stays free**),
  configuration-key, metric-name, S3-key or frontend change. See
  `docs/delta-client-v2-guide.md` ("A reserved share for the nightly checkpoint").
- nonfinite-decimal-storage: A non-finite / unparseable decimal stays NULL for every destination,
  Parquet and SQL agreeing, and a padded finite token is kept (issue #240, the destination-aware
  fork deferred from #215 / PR #232). **The first fork is not taken.** Keeping `NaN` on a bare
  `numeric` (Avro STRING) or `double precision` (Avro DOUBLE) was implemented in PR #232 and
  reverted after a non-finite narrowed into a `bigint` wrote `0` uncounted; this run keeps today's
  rule rather than rewriting that coercion. Two gaps that rule still had are closed. **Data cells
  of the forbidden combination.** A column declared `numeric(p,s)` whose value arrives as
  `double_value` / a non-finite `string_value` was NULL in every Parquet artifact and `'NaN'` in
  the SQL stream — valid PostgreSQL storing a value the baseline does not have. Keys of that
  combination were already skipped (#233); `DeltaSqlGenerationStrategy` now degrades those *data*
  cells to null before `SqlStatementGenerator` (which has no schema and still keys on the wire
  type). A `double precision` or bare `numeric` destination is not a DECIMAL and is still quoted.
  **A padded finite decimal is a value, not a defect.** `isNonFiniteToken` already trimmed;
  `parseDecimal` was handed the token raw, so `" 1.5 "` — a legal number, and a shape
  `ChangelogFold.normalizeDecimal` already retries trimmed — was NULL and counted
  `reason=malformed`. `parseDecimal` trims. The guide's "A key column is the exception" paragraph
  names the consumer (SQL; both Parquet writers write a key cell NULL like any other), and the
  `hasUnrepresentableKey` wire-case gap is closed for keys (#233, `unaddressableKeyReason`) and
  for data (this). Tests first, mutation-shaped: the padded mapper/writer cases fail with the
  raw parse restored, the SQL-NULL case fails if the strategy keeps quoting. No REST, gRPC,
  proto, DTO, **no migration (V57 stays free)**, no `specs/NNN-*`, configuration-key, metric-name,
  S3-key or frontend change. See `docs/delta-client-v2-guide.md` ("A value the column type cannot
  hold", "A non-finite `double` is kept, and quoted").
- scheduled-interval-floor: A `@Scheduled(fixedDelayString)` of `0` no longer busy-loops, and a
  negative value no longer fails Spring's parser without naming the key (issue #251, the class of
  misconfiguration #185 closed for `plugin.sql-generation.delta-sweep-ms` and review of PR #247
  named at every other interval site). **Mechanism-level, not per-bean**:
  `ScheduledIntervalValidator` walks every `fixedDelayString` / `fixedRateString` placeholder
  during singleton construction — before `ScheduledAnnotationBeanPostProcessor` starts the ticks
  — and refuses `< 1` with a message naming the key and the value. A newly added interval key is
  validated without a constructor `@Value` copy; `ScheduledTaskInventoryTest` keeps the walk
  honest by requiring it to agree with an independent inventory scan (a scan gone blind is
  otherwise an empty, green validator). `initialDelayString` of `0` stays fire-immediately (the
  crash-recovery pass, the scratch sweep); ISO-8601 `PT0S` is the same busy-loop as `0` and is
  refused as `0`. **Same anonymous-refusal class**: `AccountProperties` now quotes
  `account.max-concurrent-batches` and the offending value. Test-profile overrides stay well
  above the floor. Tests first; mutation by restoring a `0` on any discovered key. No REST, gRPC,
  proto, DTO, migration (**V57 stays free**), configuration-key *name*, metric, S3-key or
  frontend change. See `docs/delta-client-v2-guide.md` ("One sweep interval"),
  `docs/020-sql-generation-optimization.md`.
- closed-status-labels: A closed ticket keeps no live `status: *` label (issue #257, found
  working #230). Status lives in two places — the Kanban column and the repo label — and this
  file already said a close ends with "статусные метки сняты". The label half was not actually
  cleaned: `/merge` step 5.3 removed exactly two names (`ready to merge`, `in review`) and only
  from the `Closes #<n>` issue, so a ticket closed from `ready` / `in progress` / `blocked`, an
  absorbed close, a runner duplicate close, a GitHub-UI close, or `Closes` auto-close before
  that step, kept whatever it had. Five closed issues wore a live status on 2026-08-20
  (#228/#226/#215 `ready to merge`, #93 `ready`, #89 `in progress` a month after it shipped);
  a day of runner merges added six more. `gh issue list --label "status: in progress"` and
  `/merge`'s ambiguous-target lookup are both read across states often enough that a closed
  ticket wearing one is a wrong answer waiting to happen. **Both a rule in the commands and
  repo automation, not a choice of one.** Commands cannot see a UI close or the auto-close
  `Closes` fires at squash time, before step 5.3. Automation cannot unstick the next `gh issue
  list` in the same session if the agent skips the strip. The command-side shape is **query
  then remove** — `gh issue edit --remove-label` 404s if the named label is not on the issue,
  which is why a fixed two-name list was the defect, and why #230's absorbed path naming three
  other labels would have failed the `duplicate` add on a ticket that was not in those three
  states. The load-bearing backstop is `.github/workflows/strip-closed-status-labels.yml` on
  `issues: closed` (prefix `status:`, issues not PRs) plus a weekly/`workflow_dispatch` sweep
  that lists repo labels with that prefix and strips them from closed issues — the sweep also
  covers a close performed with `GITHUB_TOKEN`, which GitHub will not chain into
  `issues: closed`. **A scheduled
  guard is worth having here and was declined as a journal guard on #205**: closed +
  `status:*` is always wrong and auto-strip has no false positive, whereas a missing
  `AGENTS.md` entry needs a judgement. Backfill is a census, not the issue's own five: the
  five plus the six that accumulated before this landed (eleven closed issues). No production code, REST, gRPC,
  proto, DTO, migration (**V57 stays free**), configuration-key, metric, S3-key or frontend
  change.
- queue-marker-clobber: A queue's mark cannot un-mark the other (issue #245, found reviewing
  #212). Both workers stamped their marker by saving the whole entity captured at claim, and
  `ChangelogSegment` has no `@Version`. Since #164 the claim lock is released before S3, so the
  SQL worker could save a snapshot whose `egress_at` was still `NULL` after the egress worker
  had already stamped it — and the other way round. Self-healing until #212 made those columns
  retention's predicate; a clobbered marker then held the segment back from pruning and
  `delta.retention.segments.held-back` counted phantom stalls. **Targeted `UPDATE ... SET
  plugin_sql_at` / `egress_at WHERE id = ?`**, the same shape `deferPluginSql`/`deferEgress`
  already used; `@Version` would have taken V57 (#260's). A mark of A also leaves B's retry
  columns (`x_attempts` / `x_retry_at`) intact. Tests first: the services never `save` the
  claim-time snapshot, and an interleaving test (mark A after B, both directions, plus the
  retry columns, plus two threads) requires both markers set. No REST, gRPC, proto, DTO,
  migration (V56 current, V57 free), configuration-key, metric-name, S3-key or frontend
  change. See `docs/delta-client-v2-guide.md` ("A queue's mark cannot un-mark the other").
- dbf-column-types: DBF SQL generation receives its column types from the site's `TableSchema`,
  so the documented empty-cell contract is the one in force and a non-numeric cell in a numeric
  column can no longer become raw SQL (issue #263, filed reviewing #233). The only production
  caller — `DbfSqlGenerationStrategy` — passed `Map.of()`, so `formatValue` always fell back to
  `CHARACTER`: empty `I`/`Y` cells became `NULL` rather than `0`, numerics were quoted, and the
  unquoted numeric branch was a latent injection the day a caller supplied real types
  (`0); DROP TABLE customers; --` emitted verbatim). **Wiring, not deletion**: types come from
  `SqlGenerationContext.tableSchemas` (now loaded for DBF as well as CDC), mapped by new
  `DbfColumnType.fromSqlType` (`integer`/`serial`/`bigint` → INTEGER empty→`0`, `money` →
  CURRENCY empty→`0`, `numeric`/`decimal` → NUMERIC, `real`/`double precision` → FLOAT, a
  one-letter DBF code still works). No schema for the table keeps the previous CHARACTER
  fallback. A numeric cell is unquoted only when it is numeric-shaped; anything else is quoted
  and escaped — the injection stays closed even with types live. Tests cover rendering
  **through** `DbfSqlGenerationStrategy` (the empty map is what let the divergence live in
  generator-only tests) plus the mapping and the injection cases on the generator. Guide
  `docs/bitbi-integration.md` describes the rendering actually in force. No REST, gRPC, proto,
  DTO, migration (V56 current, V57 free), configuration-key, metric, S3-key or frontend change.
- liveness-teardown-fk: `DeltaSessionLivenessIntegrationTest.cleanUpSeededData` no longer loses a
  race against its own ingestion (issue #265, seen once on CI at
  `DELETE FROM batches WHERE site_id = ?` as a `DataIntegrityViolationException` of the class's
  own `@AfterEach`, not an assertion; green on re-run of sha `ea6f9e34`). #226/#228 already
  swept the two non-cascade FKs onto `batches` by the relationship each constraint uses;
  what the teardown lacked was a guarantee that **nothing writes a new referencing row between
  those statements**. The CI log named only the exception type. **The constraint is
  `changelog_segments_batch_id_fkey` (V30, no `ON DELETE` action)** — a session commit landing
  after the segment sweep and before the batch delete blocks it exactly this way — and the
  twin is `fk_account_plugins_baseline_batch` (V25, `ON DELETE RESTRICT`).
  **`error_logs.batch_id` is not a blocker**: V5 had no cascade, V22 added
  `ON DELETE CASCADE`, and a planted error log now goes with the batch. **Shape:**
  quiesce the in-process gRPC server/channel (`awaitTermination`, so a still-running
  `SessionEnd` cannot outlive the test) **and** retry the teardown once after re-sweeping
  those two relationships; a remaining failure is an `IllegalStateException` quoting
  `SQLSTATE=` and `constraint=`, so the next occurrence is itself (the #226/#207
  complaint). Extracted as `SeededSiteTeardown` (site and account arms), tests first
  outside `**/integration/**` so the fast gate runs them: two leftover-then-clear
  methods construct the window, one per constraint, and require the retry to remove
  the row rather than merely survive; a third plants an `error_log` and requires the
  unfixed `DELETE FROM batches` to succeed; a fourth requires the named-failure
  wrapper to carry the structured prefix (mutation: dropping `SQLSTATE=` from the
  formatter fails it even though the PG message already contains the constraint
  name). Test-only — no production code, REST, gRPC, proto, DTO, migration
  (**V57 stays free**), configuration-key, metric, S3-key or frontend change.
- sql-queue-pod-refusal: The delta-SQL queue can tell a pod-level refusal from a segment's own
  failure (issue #261, the limit #243 named rather than closed). `processNextPending` exempted
  only `MemoryPressureAbortedException` from spending an attempt, so a semaphore timeout
  (`acquireSemaphore`, before any per-segment work) and a wrapped S3 `IOException` arrived as a
  plain `SqlGenerationException` and walked healthy heads towards
  `sql.generation.delta.segments.poisoned`. **The type is a shared parent**,
  `PodLevelAbortedException`: the queue rethrows every subclass, spends no attempt, moves
  neither `deferred` nor `poisoned`, and ends the drain — the next claim would meet it too.
  Memory pressure keeps its subclass; the semaphore timeout is new
  `SemaphoreTimeoutAbortedException`. The timeout seconds and wait-queue length stay in the WARN
  at the raise site, not in the exception message (that text reaches the owner's 500 body).
  **The wrapped S3 failure stays this segment's own**, decided rather than inherited: a bucket
  outage and a missing object for this batch arrive as the same wrap, and a missing object
  should poison. An outage that outlasts the doubling window is an incident either way; the
  per-wake bound plus "many at once means systemic" is how to read that population. Tests first
  (queue exemption red against the old MemoryPressure-only catch; concurrency timeout pins the
  raise site). No REST, gRPC, proto, DTO, migration (V56 current, V57 free), configuration-key,
  metric-name, S3-key or frontend change. See `docs/delta-client-v2-guide.md` ("How to read
  them"), `docs/020-sql-generation-optimization.md`.

- error-toast-401: The global error toast handler no longer speaks out of turn on
  every 401 path (issue #239, the four routes #225 documented rather than closed).
  None of them is about interceptor registration count. **The decision the ticket
  asked for:** a failed refresh rejects the original Axios 401, not the Auth0
  error, so `.response` and `.config` survive (`suppressErrorToast`,
  `getServerErrorStatus`); the Auth0 error is the recovery attempt and travels as
  `error.cause`. Rejecting the Auth0 error looked cheaper and would have closed
  routes 2–3 together, but it changes what every downstream caller receives from a
  failed refresh into something that is neither a 401 nor their request.
  **What a failed refresh says, and who says it.** Refresh succeeds and the retry
  succeeds → no toast (already pinned by #225). Refresh succeeds and the retry
  fails → one toast from the inner pass; the outer chain (re-entered by
  `apiClient.request`) sees that mark and stays quiet. Refresh fails
  for a named reason → that reason's toast alone (network during refresh, Auth0
  unavailable, or "Failed to refresh session"), owned by `interceptors.ts`, which
  now honours `suppressErrorToast` on its own toasts. Refresh fails on the
  expired-refresh-token branch → silence, so the logout redirect stays quiet, and
  the mark stops this handler filling that silence with the leftover-401 session
  toast.
  Refresh not initialized / already retried → one leftover-401 toast, chosen
  deliberately: "Your session is no longer valid. Please sign in again." A 401 is
  never a network error and never the generic unexpected-error default. Tests
  first over all four routes; `error-handler.test.ts` already wired
  `setupResponseInterceptor` beside the handler. No `App.tsx` change (registration
  order was already correct). Frontend only: no backend, REST, gRPC, proto, DTO,
  migration, configuration-key, metric, S3-key or route change. The "exactly one
  toast" claim in `docs/delta-client-v2-guide.md` is about download pills whose
  presign requests suppress the global toast, so it was true throughout and stays
  true — and `suppressErrorToast` now actually holds on the 401 path those
  callers can also hit.
  **Review round 1** trimmed the production comments that had pasted this
  write-up, corrected the over-claim that both interceptors mark on a failed
  retry (only the inner handler does; `AGENTS.md` already said so), pinned
  `suppressErrorToast` on Auth0-unavailable and the handler's Auth0 leak
  guard, and noted that `return apiClient.request` is deliberately not
  awaited so a rejected retry cannot enter the refresh-failure catch.
  **Review round 2** corrected the expired-token comment (and this entry) that
  still named a network toast: after rejecting the original 401, an unmarked
  pass would hit leftover `case 401`, not the no-response branch.
- failing-first-checkpoint: A first checkpoint build that keeps failing is no longer the same
  payload as one that is not due yet (issue #224, the bound #213 left open). Since #213 a site with
  `last_checkpoint_seq = 0` and records applied reads as a neutral **"No checkpoint yet"** — right
  for an afternoon ingest waiting on `delta.checkpoint.cron`. Every whole-site abort
  (`frame_too_large`, a fold over `max-fold-bytes`, a deferral, an S3 read denial) writes no
  `checkpoints` row and leaves the pointer at zero, so thirty failed nights carried byte-for-byte
  that payload; `nextCheckpointBuildAt` cannot separate them either, being the next cron occurrence
  recomputed per request. Bounding it by lag magnitude was the obvious answer and is still the wrong
  one — a first `FULL_SNAPSHOT` is unbounded, so that bound would report the largest sites as
  critical on day one, which is the defect #213 removed. **Shape 2 of the ticket**, not 1: a
  persisted abort of the scheduled visit (the forced rebuild already has `lastRebuildOutcome` —
  #186), not a `created_at` from which "the last scheduled occurrence has passed" would be inferred.
  V56 adds nullable `site_sync_state.last_checkpoint_build_abort` / `_abort_at` / `_message`.
  `CheckpointScheduler` writes them from its catch, and `DeltaSyncStateService.recordCheckpointBuildAbort`
  no-ops once `lastCheckpointSeq` is past zero, so a healthy build still writes nothing and a later
  abort of an already-checkpointed site does not take a column. Values: `FAILED`, `FOLD_TOO_LARGE`,
  `FRAME_TOO_LARGE`, `SCRATCH_FULL`, `FRAME_UNAVAILABLE`, `DEFERRED`. A discard under the build and a
  deferral cut short by shutdown are not recorded (#162). A wipe and a re-baseline drop the abort,
  because both zero the pointer and an abort about the discarded baseline would then read as "the
  first build of the new one already failed". Additive DTO: reason and time on both sync-state
  projections and on bulk health; `lastCheckpointBuildMessage` on the **admin** projection only, the
  same split as `lastRebuildMessage`. On the frontend the field is `z.string()`, not `z.enum`, and
  `getSyncStatus` reads it as `first-checkpoint-failed`: the chip says **Checkpoint failed**, the
  pill **Checkpoint failed · 1.2k**, the card names the abort. Stalled still wins.
  **Review round 1** wrapped the persist so a flush error cannot escape the per-site catch and
  end the tick, split prune out of that catch (a retention failure is not a first-checkpoint
  abort; a shutdown-ended build returns an empty fold and does not throw), recorded `DEFERRED`
  only on a spent wait (a probe is not an attempt), and painted contention aborts elevated
  rather than critical. No gRPC, proto, configuration-key, metric-name, S3-key or route change.
  See `docs/delta-client-v2-guide.md` ("A first checkpoint build that keeps failing").
- double-nan-sql-literal: A non-finite `double` reaches Bit BI as a quoted literal, so the SQL it is
  handed is valid PostgreSQL (issue #233). `SqlStatementGenerator.formatJsonValue` rendered every
  `Number` through `toString()`, and PostgreSQL `real`/`double precision` legitimately hold `NaN` and
  `±Infinity` — which the extractor sends as `double_value`, a real IEEE double — so the statement
  read `SET price = NaN`, where a bare `NaN` is an **identifier**, not a literal:
  `ERROR: column "nan" does not exist`. **The failure was invisible on this side**, which is what
  separates it from #215: generation succeeded, the file went to S3, the batch was marked processed,
  and the error surfaced only when Bit BI applied the file — taking the rest of it with it wherever a
  file is applied as one transaction. The three values are emitted as `'NaN'`, `'Infinity'`,
  `'-Infinity'`, which PostgreSQL coerces to the column's own type.
  **Quoted rather than NULLed, and the asymmetry with #215 is the decision worth keeping.** For a
  `numeric` column NULL is right because Parquet DECIMAL is a scaled integer and cannot hold the
  value at all, so nulling keeps the Parquet artifacts and the SQL stream saying the same thing about
  that cell. Parquet DOUBLE holds it natively, so here NULL would *create* that disagreement — the
  checkpoint baseline keeping a value the delta stream dropped — and would discard a value both
  consumers can carry. The same property normally removes any need for a key exception: PostgreSQL
  compares `NaN` equal to itself, so `WHERE reading = 'NaN'` addresses the row.
  **One combination is the exception, and review round 3 found that quoting turned its worst case
  from loud to silent**: the rendering keys on the wire case while the Parquet writers key on the
  **declared** type, so a column declared `numeric(p,s)` whose value arrives as `double_value` —
  which the wire contract forbids and nothing rejects at ingest — is NULL in every Parquet artifact
  and `'NaN'` in the SQL. As a *key* that used to be harmless precisely because the SQL was invalid
  and Bit BI rejected the file; `WHERE k = 'NaN'` applies cleanly against a baseline row whose key
  cell is NULL, matches nothing and diverges the mirror silently — the outcome the key guard exists
  to prevent. So `hasUnrepresentableKey` becomes `unaddressableKeyReason` (it now returns *why*, so
  the WARN can name the column and the reason) and also skips a non-finite `double_value` **whose declared column materialises as a
  Parquet DECIMAL**, asking `ParquetSchemaMapper.rendersAsParquetDecimal` — the field the writers
  actually build — rather than parsing the type name a second time, which would have got a **bare**
  `numeric` wrong (Avro STRING, carries the token losslessly, nothing to skip); a declaration Avro
  refuses though PostgreSQL accepts it (`numeric(2,5)`, `numeric(5,-2)`, `numeric(0)`) answers *no*
  rather than throwing, since that table has already lost its Parquet and a throw here would fail the
  whole batch's SQL. **Round 4 then widened the same guard to `string_value`** — the identical silent
  divergence one wire case over, and this half was never loud even before #233, which corrects this
  entry's earlier claim that "a string is quoted anyway" settled it: quoting settles SQL *validity*,
  not which row the statement *addresses*. An unparseable string needs no guard for the mirror
  reason — its SQL fails loudly at apply time — and an `INSERT` is skipped with the rest, since a row
  whose key this stream will always skip is one it could create and never address again.
  **The counter deliberately stays one series.** `sql.generation.delta.records.skipped.unrepresentable_key`
  is unchanged and untagged: the two reasons have different remedies (fix the source data / fix the
  client's `SubmitSchema` or its wire encoding) and the WARN names which, but the *alert* is the same
  one either way — a client is sending keys this pipeline cannot address — and a tag added to an
  existing untagged series is a contract change that breaks the dashboards reading it. The **data**
  cells of the forbidden combination are also newly silent rather than loud (`SET price = 'NaN'`
  applies where `SET price = NaN` was rejected), and that half is **not** guarded here: nulling them
  is coercion, which is #240's subject and the thing PR #232 reverted three times. Recorded there
  rather than argued in a commit message. The counter and WARN
  are the existing ones. The **data** cells of that combination still differ; that is coercion, which
  is #240's subject on both sides, and this ticket must not run in parallel with it. **Nor does the fix repair a file
  already written**: `/sql-changes` returns the stored objects, so a pre-fix batch comes back
  byte-identical — the recoveries are delete + generate (with its documented re-delivery caveat) or
  `reinit`, which is what the guide now says instead of the "just re-fetch" this entry first
  claimed. **The DBF path's `formatValue` had the same shape of hole** for a numeric
  token and is guarded too, through `ValueMapper.canonicalNonFinite` — widened from package-private
  rather than copied, since a second copy of that vocabulary is exactly what #238 was. That guard is
  belt-and-braces, and review round 2 found the sharper reason: that branch is **unreachable through
  the only production caller** — `DbfSqlGenerationStrategy` passes an *empty* `columnTypes` map, so
  every cell falls back to `CHARACTER` and is quoted and escaped already, `NaN` included. The guard
  is on the method's contract rather than on an observed defect, and the empty map is a finding of
  its own (**#263**): it also makes the documented per-type NULL/`0` handling dead, and leaves a
  non-numeric token in a numeric column returned raw — unquoted and unescaped — latent only because
  nothing supplies the map. Proven by mutation: with
  both branches removed six methods fail across `SqlStatementGeneratorTest` and
  `DeltaSqlGenerationStrategyTest`. No new metric — the value is not degraded, so there is nothing to
  count. No REST, gRPC, proto, DTO, migration, configuration-key,
  metric, S3-key or frontend change. See `docs/delta-client-v2-guide.md` ("A non-finite `double` is
  kept, and quoted"), `docs/bitbi-integration.md`.
- poison-segment-backoff: One deterministically failing segment no longer stalls every other site's
  queue work, and the egress queue has an error counter for the first time (issue #243, filed by
  review round 1 of PR #235 and sequenced after #212 and #185). Both segment queues claim the
  **globally** oldest per-site head with `LIMIT 1` (`findNextPendingPluginSql`,
  `findNextPendingEgress`), and the drain ended on the first `RuntimeException` — so a segment whose
  work always throws was offered first on every wake and nothing else in the fleet was produced.
  #212 changed that stall's ending rather than its mechanism: pending segments are no longer pruned,
  so what used to self-heal in days by silently losing one batch became permanent, bounded only by
  batch retention. **V55** adds `plugin_sql_attempts`/`plugin_sql_retry_at` and
  `egress_attempts`/`egress_retry_at`; a failed attempt records a deferral and the drain moves on to
  another site. The backoff filter applies to the **candidate row only** — the head-of-line
  `NOT EXISTS` is deliberately untouched, so the failing segment still blocks *its own* site, which
  is the per-site seq contract `/sql-changes` and the per-table delta files depend on. The delay
  starts at 60 s and doubles per attempt to 64x, the `delta.batch-parquet.retry-delay-seconds`
  shape.
  **The DoD's poison-skip was weighed and rejected, and that is the decision to review.** A skip
  (stamp the marker, move on) discards that batch's SQL or delta file permanently — a segment is
  the durable queue entry and nothing re-drives it once the marker is stamped — while the usual
  causes are operator-repairable: a declared schema that does not fit the data, an unreadable
  object, a ceiling set too low, all fixable, after which the retry succeeds. Skipping would turn a
  repairable stall into exactly the silent, unrecoverable loss #212 had just stopped. So the attempt
  count escalates **reporting** instead of taking a verdict, and the one bounded ending stays batch
  retention (`delta.retention.segments.deleted-pending`, #212's owner decision).
  **Loudness, since a skip is not what bounds it**: every failed attempt increments
  `sql.generation.delta.segments.deferred` or **`delta.egress.errors`** (the DoD's second item — the
  `sql.generation.errors` twin the egress side never had) with a WARN; past
  `*-poison-after-attempts` (7 ≈ an hour with the doubling, so a passing outage never reaches it)
  the line becomes an ERROR naming segment, site, seq range and next retry, and
  `delta.egress.segments.poisoned` / `sql.generation.delta.segments.poisoned` move. Those two are a
  **census, not an arrival rate** — the same segment counts again on every retry, once an hour past
  the cap — the `delta.retention.segments.held-back` caveat verbatim. All four series registered at
  zero.
  **The memory-pressure refusal is exempt** and still ends the drain: `MemoryPressureAbortedException`
  (#181) reads the *pod's* heap before any work, so every segment claimed during an episode would
  meet it — deferring it would walk healthy segments towards the poisoned report and let a transient
  overload become a verdict on the data, the rule #150/#162/#178 already hold. Pinned in
  `DeltaSqlQueueMemoryPressureTest`: no `deferPluginSql`, no counter movement.
  **Two smaller decisions.** The deferral is a **targeted UPDATE** carrying the marker predicate
  (`deferPluginSql`/`deferEgress`), not a save of the claimed entity: since #164 the claim lock is
  released before the work, so two replicas can attempt one segment and the increment has to happen
  in the database, and a segment whose work landed (or which a reinit re-enqueued) must not be
  pushed into a cooldown by a straggler — which also means these columns do not widen #245's
  whole-entity clobber on the write path that matters. And `clearPluginSqlBySiteId` resets the retry
  state with the marker: a reinit is the operator saying the cause is gone. New keys
  `delta.egress.{retry-delay-seconds,poison-after-attempts}` and
  `plugin.sql-generation.delta-{retry-delay-seconds,poison-after-attempts}`, validated in their
  consuming beans and named in the refusal (#185's rule, one shared `QueueRetryBackoff`). Proven by
  mutation: with the deferral put back to a rethrow, four unit tests fail across the two queues. No
  REST, gRPC, proto, DTO, configuration-key **rename**, S3-key or frontend change. See
  `docs/delta-client-v2-guide.md` ("One failing segment does not stall the other sites", Metrics),
  `docs/020-sql-generation-optimization.md`.
  **Review round 1 changed the blast radius, not the design.** A drain now stops after **one**
  deferral: continuing would walk the whole backlog during a *systemic* failure — an S3 outage, the
  database refusing connections, sustained semaphore contention — spending an attempt and a cooldown
  on every pending segment of every site, drowning the poisoned signal and delaying recovery by the
  accumulated cooldowns; one deferral per wake unblocks the queue, since the deferred segment is
  inside its cooldown and the next wake claims a different site's head (and wakes are per
  `BATCH_COMPLETED`, not only the 60 s sweep). A failure while the pod is **shutting down** spends
  no attempt either — the S3 client or the data source may already be closed, so it is the process
  ending, #162's rule again. The #164 "no transaction across S3" guard is checked **before** the
  queue claims anything, because swallowed into a deferral a caller that wrapped the drain in a
  transaction would read as "every segment in the queue is poison data" rather than as the wiring
  mistake it is. A deferral whose UPDATE matches **no row** (the work landed on another replica
  while this attempt was failing, or a reinit/retention moved it) is not reported at all, so the
  counters never send an operator after a segment that is already done. The guide gained the reading
  rule this implies — one segment climbing is that segment's data, many at once is systemic — and
  the honest caveat that an outage outlasting the whole doubling window does reach the threshold for
  every site's head. Two smaller ones: the ERROR names the configuration key that sets the
  threshold, and the unused `poisonAfterAttempts()` accessor is gone. **The entity Javadoc's claim
  was corrected rather than left standing**: the deferral write is targeted, but the new columns are
  still carried by the entity, so the success path's whole-entity `save` can write a stale
  `*_attempts`/`*_retry_at` back — #245's clobber now has four more columns, which is recorded there
  as evidence with the note that its targeted-UPDATE option is the one these two statements already
  demonstrate.
  **Round 2 found the same clobber one degree worse and closed that half inside this ticket**: the
  success path's whole-entity `save` does not merely write a stale count, it can **erase a live
  deferral** — the SQL queue finishing minutes after its claim writes the egress columns back as
  they were then, restarting the escalation from zero and making the poison immediately claimable,
  i.e. #245's clobber reaching the one bound this queue has. All four retry columns are therefore
  `updatable = false`: Hibernate leaves them out of the entity's own UPDATE while the bulk JPQL
  statements still write them, so the markers stay #245's work and the retry state is out of its
  reach. Pinned by an integration test that reads the **row** rather than the persistence context,
  since merge copies the detached values onto the managed instance whatever the mapping says and
  only the generated UPDATE leaves them out (red without the flag). Round 2 also moved the site and
  activation lookups and the mark write **inside** the try: `orElseThrow("Site not found")` for a
  segment whose `sites` row is gone was this ticket's own defect reached through the three
  statements the deferral did not wrap — no attempt, no cooldown, offered first on every wake. And
  both worker Javadocs and `docs/020` still described the pre-round-1 behaviour ("the drain moves on
  to another site"), which the guide contradicted three paragraphs later. **One finding is not fixed
  here and is #261**: only the memory-pressure refusal has a type, so a semaphore timeout — a
  pod-level condition raised before any work — is deferred like a data failure and can walk healthy
  heads to the poisoned ERROR; giving it a type means editing `SqlGenerationService`, which #246
  held in the same window, and the limit is stated in the guide rather than left implied.
  **Round 3** tightened three things and declined two. The deferral write is now **claim-scoped** —
  it requires the attempt count to still be the one the claim saw — because the marker predicate
  alone did not hold the property the comment claimed: `clearPluginSqlBySiteId` zeroes the count and
  re-`NULL`s the marker, so a straggler that started before an operator's reinit satisfied
  `plugin_sql_at IS NULL` and undid the reset. The residual is written down instead of implied (a
  reinit of a head already at zero attempts is indistinguishable from no reinit, and costs it one
  cooldown). The original failure is no longer lost when the deferral write **itself** throws —
  likeliest exactly when the segment's failure was the database — since this class is the only
  place that logs a top-level egress failure at all: the second exception carries the first as
  suppressed, and the refused-deferral branch logs the original with its DEBUG line. And both
  `@return` contracts said `false` meant "the queue is empty", which stopped being true when a
  deferral began ending the drain. **Declined, with the arithmetic rather than a promise**: a
  poisoned head costs one wake per cooldown, so K permanently poisoned heads waste ~K wakes an hour
  against a floor of ~60 (the sweep) on a quiet fleet — the reviewer's suggested re-wake after a
  deferral is precisely the round-1 defect, one systemic outage walking the whole backlog in a
  chain, so the trade is documented in the guide instead. The semaphore-timeout exemption was raised
  again and stays #261 for the same reason as in round 2.

- adopt-path-side-effects: The loser of the SQL-generation unique claim stops reporting the
  winner's success as its own (issue #246, a pre-existing defect promoted out of the withdrawn
  findings inbox #242 after #190/PR #236 made the race deterministic in a test). Since #164 two
  workers can race `generateSqlForBatch` for one batch — the idempotency guard
  (`existsBySourceBatchId`) runs before the S3 write and the INSERT after it, so both can pass the
  guard and only `uk_sql_gen_source_batch` (V11) decides it. `persistOrAdoptExisting` already did
  the correct durable thing: catch the `DataIntegrityViolationException`, delete the object **this**
  attempt uploaded, adopt the winner's row. What it could not say is *which* branch it returned
  through, so the loser carried on down the success path and did the winner's reporting a second
  time: a second `SQL_GENERATION_COMPLETED` audit entry whose `s3Key` was **the key it had just
  deleted** — the account sees two completed generations for one batch on
  `GET /api/v1/account/plugins/{pluginId}/logs`, one naming a dead object — and a second increment
  of `sql.generation.statements.{inserts,updates,deletes}`, which describe the batch, so a raced
  batch was doubled on every dashboard reading them. The generation itself was always the winner's;
  the lie was in audit and metrics. **The fix is to make the branch visible rather than to add an
  entry for it**: `persistOrAdoptExisting` returns a private `ClaimOutcome(generation, adopted)`,
  and an adopted one returns early — no completion audit, no statement counters, one INFO line
  naming the adopted generation. The ticket's alternative (an explicit "adopted existing generation"
  audit entry carrying the winner's key) was **not** taken: it needs a new `PluginActionType`, which
  is a stored-data value and a `chk_plugin_audit_logs_action_type` migration, to say something the
  DoD asks to be silent about — "exactly one `SQL_GENERATION_COMPLETED` row". The lost race is
  reported on a **counter** instead, new **`sql.generation.claims.lost`**, registered at zero so an
  alert predates the first occurrence; read it as *attempts that rendered and uploaded SQL for a
  batch another worker had already claimed* — wasted work, not a failure, since the batch has its
  SQL either way. **Review round 2 corrected that counter's operator guidance, which is the finding
  worth keeping**: the first wording called a steady rate alert-worthy, and a steady rate is the
  ordinary state of a busy fleet — since #164 the queue worker opens no transaction of its own, so
  the `FOR UPDATE SKIP LOCKED` claim in `findNextPendingPluginSql` releases its row lock when that
  query's own short transaction commits, and `plugin_sql_at` is set only *after* the render, so two
  overlapping drains (`plugin.sql-generation.delta-max-concurrent` defaults to **2**, and every
  replica has its own pool) pick the same global head segment deterministically and one always
  loses the unique. An alert written on the first wording would have fired continuously against
  healthy operation. It is a **waste rate**, read against the rate of completed generations, and
  the durable claim that would stop the second worker rendering at all belongs to the queue —
  evidence recorded on **#243**, which owns that query. **Two
  neighbouring series still move for both callers and that is deliberate**: `sql.generation.duration`
  takes a sample from each (both really did render the batch) and
  `sql.generation.semaphore.acquired` counts both permits; the loser's `SQL_GENERATION_STARTED`
  entry stands for the same reason — it did start. **Review named the cost of that last one and it
  is documented rather than fixed**: the adopt path is now the *only* exit of `generateSqlForBatch`
  leaving a started entry with no terminal companion (a failure writes `SQL_GENERATION_FAILED`, an
  empty diff writes `logSqlGenerationCompletedNoChanges`, and #181 moved `refuseUnderMemoryPressure`
  above the started entry precisely so a refusal costs no unterminated row), so a raced batch reads
  in the account's Logs tab as **two "Generating SQL..." lines and one "SQL Generated"** — a flat
  event log, so nothing spins, but a shape the log did not carry before, with `claims.lost` the
  series that says the unmatched line was a lost race rather than a crash. A terminal entry of its
  own needs a new `PluginActionType` — stored data plus a widening of
  `chk_plugin_audit_logs_action_type`, i.e. a migration — for a cosmetic asymmetry on a path that
  fires only on a genuine collision, and reusing an existing value would be worse than the gap
  (`SQL_GENERATION_COMPLETED` is the duplicate being removed; `SQL_GENERATION_FAILED` is a
  `success = false` row claiming an error that did not happen). Filed as the theme **#260**.
  **No test can start red against a property that
  already holds in the other direction**, so the negative assertions are paired with a winner test
  in the same class (`shouldAuditAndCountTheWinnerOfTheUniqueClaim`, which passes throughout and is
  what stops "never audited" from being satisfied by a service that audits nothing) and both were
  proven by mutation: with the `claim.adopted()` branch removed, the unit test and
  `SqlGenerationConcurrentClaimIntegrationTest` go red — the latter on two audit entries and doubled
  statement counters. That integration class is #190's own, already holding the race deterministic
  with a `@MockitoSpyBean` barrier at `storeSqlFile`; it gains the loser's three observables
  (one `SQL_GENERATION_COMPLETED` row for the batch **carrying the surviving key**, awaited because
  the audit write is deferred through `pluginAuditExecutor` and then held for half a second so a
  late second entry fails rather than slips past, #159's discipline; one set of statement
  increments; one `claims.lost`), with the counters read as a **delta** over a queue emptied first,
  since the registry is this context's and the series are shared with every other site (#175).
  No migration (**V55 stays free**), no REST, gRPC, proto, DTO, configuration-key, cache, S3-key or
  frontend change; no metric is renamed — `sql.generation.claims.lost` is new and
  `sql.generation.statements.*` keeps its name and simply stops double-counting.
  See `docs/020-sql-generation-optimization.md` ("Losing the unique claim is not a second success").
- absorbed-duplicates-marker: A ticket closed as absorbed carries the **`duplicate`** label, so the
  board stops reading an unfixed defect as shipped (issue #230, filed from the same backlog pass as
  #216 and deliberately not folded into it — that ticket is about what a follow-up states *before*
  filing, this one about what the board shows *after* closing). Folding one ticket into another
  (`folds #NNN`) closes the absorbed issue, and project 16's built-in **`Item closed`** workflow
  then sets its `Status` to `Done` — so `Done` mixes a ticket closed by its own PR with one closed
  by somebody else's. Eight such closes existed on 2026-08-19; for four of them the absorber was
  still open, the sharpest being **#200** (`priority: high`, SQL regeneration unfixed in
  production) sitting in `Done` while #190 was open and `status: blocked`, and the board could not
  tell them from #143, #162, #204 and #214, whose work really had landed with #142, #149, #186 and
  #213. **The two rejected options are recorded with the chosen one.** A separate column loses to
  the automation, and this was checked rather than assumed — the project's workflows are readable
  (`gh api graphql … ProjectV2{workflows(first:20){nodes{name enabled}}}`) and `Item closed` is
  enabled, so a `Duplicate` column would be overwritten by every close and restored only by a manual
  move nothing enforces, i.e. the board would lie exactly as it does now but *invisibly*, a rule
  appearing to exist; `Auto-close issue` is enabled as well, and the columns are anyway the state
  machine this file walks in order, where an outcome is not a state. "Leave them in `Done`, that is
  accepted" is what the board already does. The label rides beside the automation instead of
  fighting it, survives the auto-move, is one query
  (`gh issue list --state closed --label duplicate`), and was already triage's de-facto marker on
  #218 and #229 — so this generalizes an existing practice rather than inventing one.
  **The part that had to be decided is what the label means, and it is the durable fact** — *closed
  by another ticket's work, not by its own PR* — rather than "still unfixed". The second reading is
  unimplementable: the person closing cannot know it, and it changes underneath them. #200 proves
  that inside ten days, having been labelled on 2026-08-19 with a comment saying the work was not
  done and #190 merging the day after, leaving a label nobody was going to come back and strip. So
  every absorbed close is labelled whatever the absorber's fate, and the live answer to "is it
  fixed?" comes from the absorber — which is why the **closing comment is required beside the
  label** and must name the absorber and say whether it is still open. The label narrows the
  question; the comment answers it. Backfilled on all eight closes so the label has one meaning:
  #192, #200, #210, #218 (#220 and #229 already carried it) **and** #143, #162, #204, #214 — the
  counter-set was left unlabelled by the 2026-08-19 pass under the "still unfixed" reading, and
  labelling it is the consequence of choosing the other one. `Done` minus `duplicate` is now the
  work that closed on its own merits. The rule binds all three closing sites — `/github-issue`,
  `/merge` and `/github-issue-runner` — and says nothing about *which* ticket survives, which stays
  a judgement about where the work is. The `duplicate` label's **description** was rewritten to
  carry the same meaning ("Closed by another ticket's work, not by its own PR — the closing comment
  names the absorber"): GitHub's default text, "This issue or pull request already exists", states
  the rejected reading to anyone hovering it in the UI. **Review round 1 found the rule's own
  misreading one step upstream** and that is the finding worth keeping: the follow-ups search rule
  still told an agent that a *closed* match "almost always" means the fix is already in `develop`,
  so a `duplicate`-closed #192 or #218 — whose absorbers #193 and #205 are open — would have been
  read as shipped at the search step, which is #230's defect moved off the board and into
  `/github-issue`; the closed-match branch now names `duplicate` as the exception and the searches
  ask for labels. Three more from the same round: the absorbed close strips `status: *` like any
  other close (a ticket closed in `Done` still carrying `status: blocked` was literally #200); the
  "do not move the card" instruction was narrowed to "do not move it to another column, but check
  it reached `Done` and move it there by hand otherwise", since assuming the workflow fired
  contradicts this file's own "a command that exited 0 is not proof"; and the dispatcher rule had
  been extended to closes that are **not** absorbed at all — an issue merged by its own PR in the
  same run, and a finding already fixed in `develop` — which would have broken the very invariant
  the label buys. **Review round 2 found the invariant itself false at merge time and one prescribed
  command broken.** The backfill had followed #230's own list, and that list was an August census —
  six older folds carried no label (#114 into #112, #123 and #124 into #122, #160 into #158, #163
  into #159, #176 into #164), plus #241, a duplicate of the **still open** #239, so
  `gh issue list --state closed --label duplicate` was not "the absorbed set" and the new
  closed-match rule would have misread all seven as stale. Fixed by scanning the last comments of
  every closed issue for the folding phrasings this repository uses — there is no standard one — and
  labelling what it found: seventeen closes carry the label now, three of them with the absorber
  still open. The broken command was round 1's own fix: the label-aware search interpolated
  `join(\",\")`, a jq parse error, so an agent would have fallen back to the label-blind search —
  the defect the fix exists to close, shipped as a rule that fails every time. Three more:
  `/merge`'s trigger keyed on the literal `folds #NNN`, which appears nowhere in this repository
  (PR #236 says "folding #200"), so the step could never have fired; the absorb-and-close step had
  no guard against closing a ticket somebody is currently working — a real hazard while the
  dispatcher keeps three in flight, since closing an issue silently destroys the window of a PR
  carrying `Closes #<n>` — and no obligation to report the close, the heaviest of the three
  follow-up actions being the only one leaving no trace; and the dispatcher exclusion was one case
  too wide, since a deferred review finding fixed by somebody else's PR *is* an absorbed close.
  Documentation and command files only: no production code, test, REST, gRPC, proto, DTO,
  migration, configuration-key, metric, S3-key or frontend change (the label description and the
  backfill are repository metadata, not files).
- signed-nan-classification: `+NaN` and `-NaN` are classified `non_finite` and fold under the same
  identity as `NaN` (issue #238, found by review while finishing PR #217 and reported a second time
  by an independent findings pass). Both places that recognise PostgreSQL's non-finite spellings
  computed an **unsigned** form and then tested NaN against the **signed** one, so the two signed
  spellings fell through every non-finite branch. The cost was not the fall-through but where it
  landed: `isNonFiniteDecimal("-NaN")` was false, `BigDecimal` cannot parse the token, so
  `isMalformedDecimal` was **true** and the cell was counted
  `delta.parquet.unrepresentable-decimals{reason=malformed}` — a series `ValueMapper`'s own Javadoc
  defines as "a client defect somebody has to fix", where the whole point of splitting it from
  `non_finite` (#215, review round 1) was that the two want opposite responses from an operator. A
  signed NaN therefore paged someone to chase a bug that does not exist, which is the outcome the
  split was added to prevent. The second consequence is `ChangelogFold.normalizeDecimal`, which
  canonicalises `nan`/`NaN` to `"NaN"` so one source row does not fold into two identities — its own
  comment says so — and returned `trimmed` for the signed spelling, defeating that. PostgreSQL emits
  `NaN` unsigned, so a faithful `numeric` round trip never reaches this; it is reachable because the
  token is whatever the client chose to send, which is the premise `isNonFiniteToken`'s own comment
  states and the reason it strips the sign for infinity in the first place. The fix is one word in
  each of two places — the NaN spelling is tested against `unsigned`, and PostgreSQL has a single
  NaN whose sign carries no meaning, so `-NaN` canonicalises to `"NaN"` rather than to `"-NaN"`.
  **Deliberately not widened**: this is token classification only, not the destination-awareness
  #240 defers with three rounds of history as its warning — the same coercion path where each round's
  fix opened a hole in the next place. Both tests were red first
  (`ValueMapperTest.nonFiniteDecimalDegradesToNullInsteadOfThrowing` and
  `aSignedNanIsNonFiniteRatherThanMalformed`, `ChangelogFoldTest.nonFiniteKeySpellingsFoldToOneIdentity`),
  and the metric assertion is the one that pins the ticket's actual cost rather than the predicate.
  **Review corrected the justification, not the fix**: the first wording said PostgreSQL accepts
  these "with an optional sign", generalising the accurate "optional sign on infinity" — PostgreSQL
  rejects `'-NaN'::numeric` outright, so a signed NaN is evidence the *client* formats
  non-faithfully rather than a value the source held, and an operator reading the guide would
  otherwise have concluded there was nothing to ask the client about. It still counts as
  `non_finite`, since it is not a value this pipeline can repair. One pre-existing asymmetry this
  change widens was traced to #240 rather than fixed: `isNonFiniteToken` trims the token while
  `parseDecimal` is handed it raw, so a padded *finite* token (`" 1.5 "`, a shape
  `ChangelogFold.normalizeDecimal` already carries a review-round-3 comment about) is written NULL
  and counted `malformed` — silent loss of a legal number, and out of scope for a classification fix.
  **Round 3 removed the duplication that was the defect** rather than only its instance: the
  vocabulary now lives once, as package-private `ValueMapper.canonicalNonFinite`, with `ChangelogFold`
  calling it — two copies with nothing asserting they agree is exactly how the identical
  sign-handling slip came to exist in both, and the next spelling added to one would have left the
  other returning the raw token as a fold identity for a value the first calls non-finite.
  `ChangelogFoldTest.everySpellingValueMapperCallsNonFiniteFoldsUnderItsCanonicalIdentity` pins the
  agreement (proven by mutation: restoring a private copy missing `inf` fails it) and pins that the
  two infinities stay apart, which the shared canonicalisation must not collapse the way it
  deliberately collapses the NaN sign. Round 3 also caught this entry's own surfaces contradicting
  themselves: the guide said a signed NaN "says the client formats non-faithfully" and is "a client
  to ask about" while **nothing** would tell an operator so — `malformed` no longer moves for it,
  `non_finite` is documented as "nothing to repair", and no log line prints the token. The
  invisibility is deliberate and is now stated as such in both the guide and the counter's HELP: the
  loss is the ordinary one, so a signal of its own would page about a formatting quirk that costs
  nothing beyond the degradation already reported.
  No REST, gRPC, proto, DTO, migration, configuration-key, metric-**name**, S3-key or frontend
  change; `delta.parquet.unrepresentable-decimals` keeps both tag values and simply stops
  misclassifying between them. See `docs/delta-client-v2-guide.md` ("A value the column type cannot
  hold").
- retention-holds-pending-work: Changelog retention no longer deletes a segment whose plugin SQL
  or egress was never generated — pending work is not prunable, and both the hold-back and the one
  horizon that remains are visible (issue #212, found reviewing #181/PR #209, which it silently
  bounded). A changelog segment is also the durable entry of two work queues: `plugin_sql_at IS
  NULL` means the Bit BI delta-SQL queue still owes it, `egress_at IS NULL` the delta-Parquet
  egress — and both queues retry precisely by leaving the row pending ("a throw leaves the segment
  pending for the sweep"). `ChangelogRetentionService.prune` deleted such a row like any other once
  the checkpoint subsumed it and `delta.retention.audit-window-segments` (20) younger
  below-checkpoint segments accumulated, so the batch's SQL was lost permanently, silently, with no
  audit row marking the moment of loss — capping the retry guarantee #181 had just established.
  **The owner fixed the hybrid of the ticket's first two shapes**: the prune skips a
  below-checkpoint segment with either marker `NULL`
  (`ChangelogSegment.isPendingPluginSql()`/`isPendingEgress()` own the semantics; the queues' SQL
  and the prune's mirror them), and the hold-back is counted on
  **`delta.retention.segments.held-back{reason=pending_plugin_sql|pending_egress}`** (registered at
  zero; only segments the window would actually have pruned; a segment owing both moves both
  series; a census, not an arrival rate — with the blind spot stated: the prune runs only after a
  successful build, so a site whose build aborts nightly shows zero here while accumulating, and a
  reinit re-pends the audit window by design, a benign one-pass spike) plus one WARN per site per
  pass. The audit window keeps its meaning — held-back segments count toward it and are retained on
  top of it (pinned by `pendingSegmentsStillCountTowardTheAuditWindow`).
  **Review round 1 (ten lenses) then reshaped half of it, and its owner addendum moved the
  contract.** The addendum: "unbounded in time" was never true — `BatchRetentionService` is a
  second scheduled deleter of segments (rows and S3, per-site `retentionDays`, default 45 days, no
  marker check), so it is recognized as **the deliberate outer horizon** of the queues' retry, made
  observable rather than closed: **`delta.retention.segments.deleted-pending{reason=...}`**
  (registered at zero — a non-zero rate means work sat in a queue for the whole retention window)
  plus a WARN per batch; the explicit admin batch delete logs the pending count it destroys
  (informed override, deliberately off the meter); and every contract surface names the endings —
  queue drains, operator deletes segment or batch, re-baseline/wipe, batch retention. #185 is
  written in the **future** tense everywhere ("will be closed at source; still open"), since that
  fail-fast is the no-bound stance's load-bearing justification and it does not exist yet.
  **Three correctness findings were the round's core, all fixed in-PR.** (A1) The hold-back broke
  the contiguity proxy `historyPruned` rested on — prune deleted oldest-first unconditionally, so
  "head at seq 1" proved a lossless refold was possible; retaining an older pending segment while
  younger processed neighbours are pruned puts a gap *behind* a retained head (a reinit re-pends
  interleaved segments out of queue order — the concrete route), and a frame-gone site would have
  silently refolded the gapped history into a truncated checkpoint and advanced the pointer over
  the loss: `hasSeqGap` now requires contiguity from seq 1 (overlaps tolerated, only strictly
  uncovered sequences refuse), mutation-proven. (A2) The prune was check-then-act across
  statements while `clearPluginSqlBySiteId` re-`NULL`s `plugin_sql_at` site-wide, so a reinit
  committing between the read and the delete had its freshly-pending row deleted — object first:
  the row delete is now a **single conditional statement** (`deleteByIdIfProcessed`, the marker
  predicate travels with the DELETE), the object goes only after the row delete reported success
  (row-first, so a crash leaves an unreferenced object for the #158 sweep), and a refused delete
  re-reads and counts the row by what it says now. (A3) The hold-back defeated #149's bounded
  drain — a frame-gone site with one held-back below-pointer segment kept `segments` non-empty for
  ever, took `lossy_refold` nightly and spent no attempt: a site whose remaining segments all sit
  at or below the pointer (everything they hold is already inside the lost frame's fold) now takes
  the #149 drain under the unchanged `lossy_refold` tag — one attempt per retryable row per
  scheduled night, re-arm on the forced pass, then a **quiet** visit with
  `delta.checkpoint.tables.given-up` standing — while segments above the pointer keep the
  never-quiets contract; mutation-proven both ways.
  **The efficiency findings all trace to the same fact — the below-checkpoint set is unbounded
  now**: the prune reads a four-column projection instead of hydrating every entity (JSONB stats
  included), deletes objects in batched 1000-key `DeleteObjects` round trips instead of one per
  object (#234 keeps only the transaction-boundary half); the checkpoint build reads **seq
  coverage** (two longs per segment) and hydrates entities only above the fold's seed, so the idle
  visit — the nightly steady state of a site pinned to the work list by held-back segments, whose
  per-tick cost the guide now itemizes — loads no entity at all; and the re-baseline reset
  discards the old baseline with one projection and one bulk DELETE instead of a row-by-row loop
  under the `site_sync_state` row lock.
  **Bucket C stayed out by the follow-ups rule**: #243 (one poison segment stalls the whole global
  delta-SQL queue, and egress equivalently with no error counter — pre-existing, but #212 changed
  its ending from "self-heals by losing one batch" to "permanent until an operator acts, bounded
  by batch retention", so per-segment bounds are now their own decision), #244 (the
  completed-batch Parquet replay is a third durable consumer of raw segments no retention
  predicate consults — the guide scopes the hold-back guarantee to the two queue markers and names
  it), #245 (the two queue markers can clobber each other back to NULL — whole-entity saves, no
  `@Version` — self-healing before, but #212 builds a durability guarantee on those columns).
  Tests pin both directions and every review fix (mutation-proven: predicate disabled — 4 red;
  gap check removed — 1 red; drain branch removed — 2 red); the hold-back integration test's
  assertion messages re-read the markers so an improbable steal of the global queue head diagnoses
  itself; fixtures whose subject needs pruning mark their segments through one
  `BaseIntegrationTest.markSegmentsProcessed` helper. No REST, gRPC, proto, DTO, migration (V54
  still current, V55 free), configuration-key, S3-key or frontend change; both meter names are
  new, nothing existing is renamed. See `docs/delta-client-v2-guide.md` ("Retention does not
  delete unprocessed work", Metrics), `docs/020-sql-generation-optimization.md`,
  `docs/cr-bitbi-delta-sql.md` (the retention residual risk narrowed, not struck — listed as item
  4 then, renumbered to 3 by #205 when the obsolete connection-hold item above it was removed).
  **Round 2 reviewed what round 1 introduced, and three of its findings cut into round 1's own
  fixes.** The A3 drain was silencing far more than its justification named: with the default
  window of 20, retention never emptied a quiet site's below-checkpoint list even before #212, so
  a frame-gone site holding an ordinary *processed* window — a real, rebuild-recoverable data-loss
  state #212 did not create — would have gone quiet after five nights; the drain is now scoped to
  a held-back **pending** segment actually existing below the pointer
  (`existsCommittedPendingBelowCheckpoint`), the processed-only population keeps the never-quiets
  contract, and the false convergence claim in the Javadoc is rewritten (mutation-proven). The
  split of the old single segment read into a coverage read and an entity load opened a window the
  single read never had — a deleter that bumps no epoch (batch retention's horizon, a sibling
  replica's prune) removing rows between them would have had the frameless refold fold a silently
  gapped history — so contiguity is re-verified against the list actually folded, thrown without
  counting (the read-denial rule: transient, one tick). And the drain message was pass-aware-false:
  #186 shows it verbatim as `lastRebuildMessage`, and on the forced pass `settleSiteWide` re-arms —
  the text now says which happened. The rest: a stray diff3 marker the develop sync left in both
  journal files (deleted); the conditional DELETE's marker predicate — A2's whole fix — was pinned
  by nothing that reached the real SQL (an integration test now drives the real statement over a
  pending, half-pending and processed row); `SdkClientException` escaping `deleteObjects` would
  have rolled the row deletes back *after* earlier chunks' objects were destroyed — rows restored,
  objects gone, in bulk (caught now, the #158-round-2 gap); `deleted-pending` counted before
  anything was deleted, inflating the "permanently unproducible" series with phantom losses on
  every failing night (counted after the segment delete returns; pinned); the re-baseline reset's
  blanket site-wide DELETE could take a row committed between the key read and the delete
  (`deleteByIdIn` over exactly the collected refs now) and its afterCommit object deletes went one
  round trip per key (batched); `findBySiteIdOrderByFirstSeq` — now with zero production callers —
  carries a do-not-re-adopt Javadoc; the hold-back census counting lives once
  (`HeldBackTally`); and the informed-override's real surface is stated honestly (a server-side
  WARN; the delete's HTTP response carries no pending count — a REST change was deliberately
  avoided, a UI confirmation is its own decision if wanted).
- sql-generation-config-fail-fast: An out-of-range value anywhere in the `plugin.sql-generation.*`
  block fails the application context at startup, and the dead async generator is gone (issue #185,
  folding **#210** — both hygiene in `SqlGenerationService`, sequenced after #190). **Fail fast over
  a startup WARN is an owner decision recorded on the ticket**; the argued form lives in one place —
  `docs/020-sql-generation-optimization.md`, "One caveat on 'unbounded retry is safe'" — and in
  short: a GKE rolling update keeps old replicas serving while the rollout goes red, the WARN
  channel is proven unread (#174's "abort disabled" line), and the silent failures are the
  expensive ones (`800` disables the heap guard, a negative value is an endless retry loop, #181,
  ending in silent data loss once retention passes the pending segments, #212; clamping was ruled
  out by the ticket). **All five keys, two consuming constructors** — review round 1 caught the
  first cut validating three keys while the docs claimed the block: `SqlGenerationService` holds
  `heap-threshold-percent` ∈ [1..100], `max-concurrent` >= 1, `semaphore-timeout-seconds` >= 1, and
  `DeltaSqlSweepWorker` holds `delta-max-concurrent` >= 1 (0 used to crash-loop through
  `ArrayBlockingQueue`'s message-less `IllegalArgumentException`, the exact anonymous failure
  fail-fast replaces) and `delta-sweep-ms` >= 1 (0 was *accepted* by Spring and busy-looped the
  fallback sweep), through shared package-private `PluginConfigValidation` — deliberately not
  shared wider: the delta packages keep their own constructor checks. **The heap floor is 1, not
  the 0 first shipped** (round 1's F1): a live JVM's ceiling-rounded reading is never 0, so a
  strict `> 0` refuses every generation exactly like the `-1` beside it — a pathological value
  blessed by validation one unit above the cut, and a collision with this deployment's own
  "0 disables" convention (`delta.parquet.max-scratch-bytes`); 100 stays #174's documented
  off-switch, still pinned by `SqlGenerationStreamingTest`. Round 1 also killed the consequence
  text "0 permits deadlock the semaphore outright", which had been copied into five surfaces and
  was wrong in kind — `acquireSemaphore` uses a bounded `tryAcquire`, so the real signature is
  120-second timeouts retried for ever, a different incident to chase. The refusal names the key
  **and the value** ("but was N" — pinned literally, after round 1 showed `hasMessageContaining("0")`
  satisfied by static text), and the promise is scoped: it holds for a well-formed integer, while a
  value Spring cannot convert (`"80%"`, or an env var present but empty — `${VAR:80}` does not
  default for `""`) dies earlier in `@Value` conversion naming the constructor parameter, said in
  the yaml comment and docs/020 rather than closed with String-parsing constructors. Tests pin both
  boundaries of every range as `@ParameterizedTest`s in the two consumers' test classes.
  **Part 2**: `generateSqlForBatchAsync` is deleted — one grep hit in `src/`, the declaration; its
  Javadoc described the reinit flow that was removed (`PluginHistoryService`'s "SQL generation no
  longer triggered for reinit" comment stays as the record); as a correctly-qualified `@Async` site
  the #195 guard kept it alive while readers of the #161 inventory counted it as a `pluginExecutor`
  consumer. Round 1 then swept the prose the deletion left stale: `docs/reinit.md` documented the
  async regeneration as live down to a `sqlGenerationTriggered: true` example (it is always
  `false`), `AccountPluginsController`'s 202 comment promised background generation,
  `BackgroundConnectionDemandTest` still classified the deleted entry point,
  `AsyncExecutorQualifierTest`'s failure message counted 15-of-18 `@Async` sites (13 of 15 now),
  `PluginHistoryServiceTest`'s T017 display name asserted the deleted behaviour, and `PLUGIN_ID`
  carried the Javadoc of a max-files constant deleted long ago. No migration (**V55 stays free**),
  no REST, gRPC, proto, DTO, metric, S3-key, configuration-key-**name** or frontend change; key
  names and defaults are untouched — only an out-of-range value's fate changes.
  See `docs/020-sql-generation-optimization.md`.

- shared-fixture-hygiene: The shared fixture now sweeps leftover rows that block `DELETE FROM sites`
  / `DELETE FROM accounts`, and rows that have no path back to the seed at all (issue #228, folding
  **#229** and **#220**; parent #226 / PR #227 closed what blocked `DELETE FROM batches`). Three
  axes, because a general "sweep every non-cascading FK by its own relationship" covers 1 and 2
  and **cannot** cover 3. **(1)** `batches.account_id` and `sites.account_id` (V3 / V2, no cascade).
  `Batch.start(accountId, siteId)` takes the two independently, so a batch pairing an
  `%@example.com` account with a foreign-domain site survived the site-keyed `DELETE FROM batches`
  and blocked the account delete — the #226 symptom one statement later. **(2)**
  `device_authorizations.site_id` / `.account_id` (V21, no cascade). The fixture had no statement
  for that table; an approved leftover pointing at a seeded site blocked `DELETE FROM sites`.
  Live, not hypothetical: `DeviceFlowSessionSupersedeContractTest` hand-deleted its own rows to
  keep the next `@Sql` from failing, and that private cleanup is now dropped. **(3)** Rows outside
  the seed identity predicates: `*.test.local` (three integration classes that never hit
  `%@example.com` / `%.example.com`) and `{uuid}_example.com` (`BatchRetentionIntegrationTest`
  today — an owned account whose domain uses an underscore where `LIKE '%.example.com'` needs a
  literal dot, kept off `DELETE FROM accounts` only by a per-method `@Transactional` rollback).
  Widening `DELETE FROM sites` pulls those sites in, so every site-keyed statement above it
  (`error_logs` especially — V5, no cascade on `site_id` — plus `checkpoints`, `site_sync_state`
  and the segment sweep's `site_id` arm) widens in step; `ScriptUtils` splits on `;` and cannot
  parse a `DO $$` block, so the owned-account / owned-site / owned-batch subqueries are repeated.
  The same account-keyed batches / device-auth / sites sweep is in
  `DeltaSessionLivenessIntegrationTest.cleanUpSeededData` and
  `BatchTerminalTransitionLockingIntegrationTest.tearDown`. Leftover-then-clear guards of
  #119 / #226 pin each shape, mutation-red against the unfixed fixture (`batches_account_id_fkey`,
  `device_authorizations_site_id_fkey`, a remaining `*.test.local` account, `error_logs.site_id`
  blocking the pulled-in site). **#220 is the other half of the same unit of work**:
  `RunOwnedScratch` called `PropertyPlaceholderHelper(prefix, suffix, separator, boolean)`,
  `@Deprecated(since = "6.2", forRemoval = true)`, so every `compileTestJava` printed a
  `[removal]` warning that would become a red compile on the Boot bump that drops it — the same
  "the build blames the wrong change" complaint #207 and #226 were filed for. The 5-arg form with
  a null escape character keeps prefix, suffix, value separator and fail-on-unresolvable; the
  4-arg constructor was only ever that delegate. `ParquetScratchTestProfileTest` still fails when
  a scratch key is dropped from `application-test.yml`. Test-only — no production code, REST,
  gRPC, proto, DTO, **no migration (V55 stays free)**, configuration-key, metric, S3-key or
  frontend change.
- notnull-decimal-snapshot: A non-finite or malformed decimal in a `NOT NULL` column no longer costs
  the table its checkpoint snapshot (issue #237, residue of #215). #215 writes the unrepresentable
  cell as NULL and returns a tally so the WARN and
  `delta.parquet.unrepresentable-decimals` can fire; that only worked when the column was nullable.
  `ParquetSchemaMapper.toAvroSchema` — the **checkpoint** schema — unioned with null only for a
  nullable column, so a `NOT NULL` one became a REQUIRED Parquet field, parquet-avro threw
  `"Null-value for required field"` *before* the tally came back, `CheckpointService` recorded an
  opaque `tables.unmaterialized{reason=parquet_failed}` that never mentions decimals, the snapshot
  key was detached (a 404 for Bit BI, Parquet Export and the Delta Sync download) and one
  `materialize_attempts` was spent, deterministically, until the row gave up permanently (#149).
  `toDeltaAvroSchema` already forced every declared column nullable, so the delta and completed-batch
  artifacts were unaffected — **checkpoint-only**.
  **Option 1 of the ticket**, not 2 or 3: every checkpoint column is a `[null, T]` union with a null
  default, the declared constraint notwithstanding, sharing one `nullableColumn` helper with the
  delta mapper so the two artifacts cannot disagree the way they did. Option 2 (union only the
  decimal columns) is ruled out by a bare `numeric NOT NULL`, which maps to Avro STRING, not to a
  decimal logical type, yet is still degraded to NULL by `coerceValue` (the wire value, not the
  destination). Option 3 (skip the row) would have been the SQL-path treatment of a degraded *key*,
  and is the wrong shape here: a folded `UPDATE` with no prior `INSERT` seeds the row from its key
  columns plus the carried change, so a declared `NOT NULL` varchar can be legitimately absent —
  nothing about decimals — and refusing that row would drop it from the snapshot. The constraint is
  therefore not carried at all rather than carried until it fails; a consumer that needs the source
  constraint reads it from the schema it submitted, not from the Parquet field's repetition. The
  gzipped CSV retired by #113 never carried nullability either.
  Tests first: the two halves of the hazard (degradation; a `NOT NULL` decimal) had each been
  covered and never met — every #215 case declared the column nullable, every non-nullable decimal
  in the class was only ever fed a finite value. Four writer tests now put them in one row (scaled
  `numeric(12,2) NOT NULL` with `NaN` and with a malformed token; bare `numeric NOT NULL` with
  `Infinity`; a folded row missing a `NOT NULL` varchar) and require the file, the tally and a NULL
  cell; a mapper test requires every checkpoint field to be a nullable union, and a comparison test
  requires the checkpoint and delta schemas to agree field-for-field on the declared columns.
  Mutation: restoring the `if (column.nullable())` branch fails those. **Review round 1** added a
  fifth writer case that both overflows the declared precision (so `widenDecimalsToFit` actually
  reconstructs the field) and holds a `NaN` — the four original tests never tripped that pass, and
  its `: wider` branch is the only remaining production path that can emit a REQUIRED decimal —
  trimmed the method Javadoc off the ticket chronology, and qualified the guide's "no Parquet field
  is ever REQUIRED" (delta `_op`/`_seq` stay required). No REST, gRPC, proto, DTO,
  migration, configuration-key, metric-name, S3-key or frontend change; the Parquet schema of a
  checkpoint snapshot is the consumer-visible contract, and it is now the same nullable-union rule
  the other two artifacts already had. See `docs/delta-client-v2-guide.md` ("Schema JSON / type
  mapping", "A value the column type cannot hold").
- retire-sql-regeneration: The SQL regeneration path is gone, because it had never worked end to
  end and repairing it would have repaired a path that can serve no live batch (issue #190,
  folding #200 — the two were one piece of work: #190's transaction refusal fired first and #200's
  V11 unique forbade the second row the fix needed, so neither was completable alone). The owner
  recorded **Plan 2 — retire** on the ticket before implementation, and the deciding facts are
  worth keeping: `loadBatchDataForRegeneration` threw for segment-backed batches and required CSV
  `uploadedFiles`, which no batch has since 032 retired HTTP ingestion, so even fully fixed the
  Regenerate button answered "not supported" for every Delta site; the superseded-history model it
  preserved had no readers (`includeSuperseded` is always sent `false`) **and no rows** — since no
  regeneration ever completed, no production row was ever marked superseded; and recovery already
  exists on the same SQL tab (**Delete + Generate**, where manual `POST .../generate-sql` supports
  segment-backed batches) plus `reinit` for the full reset. **Deleted**:
  `PluginHistoryService.regenerateSql` (and with it the service's whole `SqlGenerationService`
  dependency), both `/regenerate` endpoints (owner `AccountPluginsController`, admin
  `PluginAdminController`), `RegenerateResultDto`, `SqlGenerationService.regenerateForBatch` /
  `doRegenerateForBatch`, `SqlGenerationPersistence.loadBatchDataForRegeneration`, the
  never-called `findActiveBySourceBatchId` (feature 014's unfinished half, fact 6 of the ticket),
  the three `PluginAuditService.logSqlRegeneration*` writers, and the Regenerate
  button/mutation/dialog on **both** frontend surfaces — `features/my-plugins` (owner SQL tab) and
  `features/plugin-history` + `PluginHistoryWidget` (admin history page), with the two route
  builders in `shared/api/apiRoutes.ts`. **Kept, deliberately**: `superseded`/`superseded_by`
  (V11/V14 columns; dropping them is a separate decision, **no migration here — V55 stays free**),
  the `includeSuperseded` query parameter (harmless, avoids an API change), and the
  `SQL_REGENERATION_*` values of `PluginActionType` — they are stored-data values a historical
  audit row could carry, so they become read-only history exactly like the columns (the frontend
  logs-tab label map keeps its entries for the same reason). **The one thing the retired path
  owned that must survive is the unique**: `uk_sql_gen_source_batch` is #164's durable claim for
  the delta-SQL queue (`persistOrAdoptExisting`), and the DoD's binding checkbox is now pinned by
  `SqlGenerationConcurrentClaimIntegrationTest` — two concurrent `generateSqlForBatch` calls for
  one batch against the **real** constraint (the mock twin in `SqlGenerationServiceTest` stubs the
  violation and cannot prove it), made deterministic by a `@MockitoSpyBean` barrier on
  `storeSqlFile` scoped to the test's site, so both callers pass the `existsBySourceBatchId` guard
  before either persists; both end with the same generation, exactly one row exists, and the
  loser's orphaned S3 object is deleted. The seeded segment is marked
  `plugin_sql_at`/`egress_at` immediately so the global queue (no site predicate, #175) can never
  hand it to a sweep worker. Proven by mutation, since the claim already holds: with the
  `DataIntegrityViolationException` catch removed the loser throws instead of adopting.
  **The #172 rollback-audit guard is rewired, not deleted**: its invariant (a deferred audit entry
  must not survive its publisher's rollback) belongs to the listener, not to regeneration, so
  `PluginHistoryIntegrationTest` now publishes through `logSqlGenerationCompleted` — the surviving
  deferred writer — and the audit hazard #172 recorded on #190 (entry standing while the supersede
  rolls back) is closed **by the deletion itself**: no code publishes `SQL_REGENERATION_COMPLETED`
  any more, so no guard test is needed. Obsolete tests deleted with the behaviour (Rule 2):
  the `RegenerateSql` nested classes in `PluginHistoryServiceTest`, `PluginHistoryIntegrationTest`
  (`@Disabled` since 014) and `PluginHistoryAdminControllerTest`, the regeneration cases in
  `SqlGenerationServiceTest`, `SqlGenerationStreamingTest`, `SqlGenerationConcurrencyTest`,
  `SqlGenerationOutsideTransactionTest` and `PluginAuditServiceDeferralTest`. Two REST endpoints
  disappear (never functional — every call since #164 answered 409, and before #164 died at commit
  on the unique); `/actuator/prometheus` loses `sql.regeneration.duration` and
  `sql.regeneration.errors`, series only a failed attempt could ever move (the #165 precedent for
  naming a removed series). **The delete+generate caveat is now documented where that recovery
  is described** (`docs/bitbi-integration.md` "Recovering a batch's SQL", both delete-generation
  `@Operation` descriptions, the delete dialog in the SQL tab): the new row gets a new
  `created_at`, so a client whose `since` cursor already passed the batch receives its SQL a
  second time — and the SQL is not idempotent — so for an already-fetched batch the answer is
  `reinit`. Pre-existing behaviour of that path, not new.
  **Review round 1 corrected the caveat itself before anything else** — the bold Swagger lead
  said "re-delivers to lagging cursors only", the exact inverse of its own body (re-delivery of
  the non-idempotent SQL hits precisely the cursor that **already passed** the batch; a lagging
  one receives it once, correctly), and the owner delete `@Operation` had no caveat at all while
  this entry claimed both carried it. It also named the **second limit** of delete+generate,
  now documented beside the first: generate renders only records above the plugin's current
  delta baselines, so after a reinit re-captured them an older segment-backed batch renders
  nothing, settles as "No changes" with no Generate button, and the deleted SQL is unrecoverable
  through the UI — the delete dialog and both guides say so and point at reinit. The concurrency
  test was hardened on six axes (spy verifies scoped to the test's account and
  `clearInvocations`, since a `@MockitoSpyBean` records since context refresh over a global
  queue; the backlog retired by one UPDATE instead of rendering it; the persist→mark window made
  harmless by creating the activation only after the mark, so a claim in the window takes the
  #175 skip branch; `max-concurrent=2` pinned by `@TestPropertySource` instead of inherited; the
  barrier failure named instead of sneaky-thrown as an S3 error; a bounded `awaitTermination`).
  `markAsSuperseded` is deleted too — an inviting documented mutator for a model nothing reads
  is a trap — and the admin history page drops its "Show superseded" checkbox and
  Superseded/Active badge (frontend only; the server keeps accepting `includeSuperseded`).
  `sql.generation.semaphore.queue.size` kept its only non-zero pin by moving the queueing test
  onto the surviving `generateSqlForBatch` path. One pre-existing finding was traced to the
  findings inbox (#242) rather than fixed: the adopt path's loser still logs
  `SQL_GENERATION_COMPLETED` with its own just-deleted `s3Key` and double-increments
  `sql.generation.statements.*` — since #164, made visible by this ticket's determinism.
  No migration, gRPC, proto, configuration-key, S3-key, TanStack-Query-key or route-path change
  beyond the two removed REST routes. See `docs/bitbi-integration.md`,
  `docs/cr-bitbi-delta-sql.md`, `docs/020-sql-generation-optimization.md`.
- followup-declares-its-files: A follow-up ticket states what it will touch, so a collision is read
  rather than inferred (issue #216, filed from the backlog pass that untangled #190/#200). **A
  keyword search finds a duplicate and finds a collision only if somebody runs it**: #200 was a
  unique constraint and #190 a transaction annotation, one piece of work doable in neither order
  alone, and they were untangled only because a backlog pass read every open ticket at once — which
  no step of this process asks for. What they shared was the files, and a declared file list turns
  that check from something someone has to think to do into something mechanical. (Review round 2
  disproved the first draft's sharper claim that search *cannot* find such pairs: against this
  repository `--search "SqlGenerationService"` returns #185, #190, #200 and #210, and #190/#200 both
  open with the words "SQL regeneration". The rule survives the correction; its justification had to
  change.) The same pass found three more such pairs (#185/#210 in `SqlGenerationService`,
  #193/#213 in `CheckpointService`/`CheckpointScheduler`, #213/#215 suspected in the egress render
  path — that last one later **disproved** by #213's own investigation, which is a fair illustration
  of how little a suspicion is worth without the file list). Every follow-up now carries three lines
  in its body: the files it expects to touch, whether it needs a Flyway migration **or a
  `specs/NNN-*` directory** — the two collisions a file list cannot catch, since both sides add
  differently-named files and merge cleanly, one breaking startup and the other putting two features
  on one number — and which open tickets live in those same files.
  **Review corrected four things about the rule and one claim about the repository.** The third line
  had **no method**, and the only search this file prescribes is the keyword one the rule's own
  thesis declares insufficient: an executor greps `SqlGenerationService`, finds neither #185
  ("threshold validation") nor #210 ("dead async method"), writes "none", and the dispatcher
  schedules exactly the pair the rule exists to separate — a false negative that reads as a verified
  answer. So the rule now prescribes reading bodies rather than the index
  (`gh issue list --state open --json number,title,body --jq …`, one query per name) and says
  **"none found"**, since
  pre-rule tickets carry no file line and the answer is only ever as good as their prose. The
  **consumer** was never updated either: `CLAUDE.md` promised the dispatcher's inference "becomes a
  read" while step 2b still described inference only, so 2b now reads the lines when present,
  **re-checks by grep anyway** (a declared list ages while the code moves) and falls back to
  inference when absent — a ticket's silence never means "no overlap". The requirement sat in
  `/github-issue`'s **search** step while every other body requirement lives in the filing step, the
  natural place to read as the spec for an issue body; it is in both now. And the lead sentence
  scoped it to "a ticket filed from a review" three lines above "every follow-up states three
  things" — the mid-work finding is the commoner case, so the narrower reading would have skipped it.
  **The claim about `AGENTS.md` was simply false**: the PR argued against touching it because adding
  a rule there "would be worse than the pointer that already exists", and `grep -c 'CLAUDE.md'
  AGENTS.md` was **0** — no pointer, no follow-up rule, while that file declares itself "the single
  source of dev rules". An agent whose harness loads only `AGENTS.md` would have filed tickets
  without the three lines, degrading the dispatcher's promised read to inference for exactly those.
  It now carries the condensed rule and the pointer.
  Documentation and command files only — no production code, test, REST, gRPC, proto, DTO,
  migration, configuration-key, metric, S3-key or frontend change.
- non-finite-decimal-null: A `NaN` or `+/-Infinity` arriving in a `numeric` column is written as
  NULL and reported, instead of costing the table its delta file, its checkpoint snapshot and its
  Bit BI SQL (issue #215, from the PDE soak on the live stand). PostgreSQL `numeric` holds all three
  and, since PostgreSQL-data-extractor#86, the extractor sends them as `decimal_value` tokens —
  where `new BigDecimal("Infinity")` threw. **The ticket's body described one symptom and its
  comment corrected the scope, which is the part worth keeping**: `ValueMapper` has *three*
  independent consumers, so the reported "skipping the table's delta file" was the mildest of them.
  `DeltaEgressService` logged an ERROR, skipped the file and still marked the segment egressed, so
  it was lost rather than retried; `CheckpointService` recorded
  `tables.unmaterialized{reason=parquet_failed}`, which spends a `materialize_attempts` and after
  `delta.checkpoint.max-materialize-attempts` nights gives the table up **permanently** (#149); and
  `DeltaSqlGenerationStrategy` threw into the SQL queue, so Bit BI never received that batch's SQL.
  A fourth throw site the ticket did not list — `DeltaParquetWriter`'s decimal-envelope scan — dies
  the same way, and the three secondary parses are guarded by `java == null`. That was written as
  "closing the mapper closes every path" and **review proved it false twice** — see the rounds
  below: the mapper is not the only thing on this path that parses a decimal.
  **Parquet DECIMAL is a scaled integer with no representation for any of the three**, so the DoD's
  first branch ("land correctly in parquet") is not available for the declared column type, and its
  second (reject at ingest) would need an `ErrorCode` in `delta-ingestion.proto` against a shipped
  Windows client *and* make a legal PostgreSQL row unreplicable. So the cell is stored NULL and the
  degradation is made loud in three registers, each sized to its own noise floor: one WARN per
  rendered table naming the table and the count (the workload that produces these writes one every
  two seconds, so a line per cell would bury the log), per-cell DEBUG in the SQL strategy where the
  column and seq are known, and **`delta.parquet.unrepresentable-decimals{reason=non_finite|malformed}`**, registered at zero so
  an alert predates the first occurrence. Read that series as **cells, not rows or files**: a row with
  two such columns counts twice, and the same source cell is counted again by each consumer that
  renders it, because each writes a separate artifact in which it is separately NULL.
  `isNonFiniteDecimal` is deliberately **narrower** than "`toJava` returned null" — a real SQL NULL,
  an unset value and a *malformed* token all answer false, since the three want different responses
  from an operator (nothing, nothing, and a client sending nonsense) and a counter that conflates
  them cannot be alerted on.
  **What this does not do is stated rather than implied**: `NaN` is not `NULL`, so soak #39's
  source-vs-server comparison still will not match. This removes the data loss and the silent skip;
  making the value survive would mean widening the column's Avro type, which changes the Parquet
  schema Bit BI, Parquet Export and the checkpoint download all read — weighed and not taken.
  Tests were written first and proven by mutation: with `parseDecimal` put back to
  `new BigDecimal(token)`, four methods fail across `ValueMapperTest` and
  `ParquetCheckpointWriterTest`. One incidental correction came from the gate — capturing the
  checkpoint count had switched `timeCheckpointPhase("parquet", ...)` from the `Runnable` overload
  to `Supplier`, which `CheckpointServiceTest` pins as part of the #111 phase guard; which overload
  times a phase is incidental to this change, so the timing shape was restored rather than the guard
  rewritten. No REST, gRPC, proto, DTO, migration, configuration-key, S3-key or frontend change.
  **Three review rounds then changed what this ships, and the history is the part worth keeping.**
  Round 1 found two regressions the fix had introduced: a degraded **key** column rendered
  `WHERE col = NULL`, which is never true, so a DELETE for a row keyed on a `NaN` numeric (a usable
  key — PostgreSQL compares `NaN` equal to itself) was emitted, applied, matched nothing and left the
  Bit BI mirror silently diverged, worse than the throw being removed; such a record is skipped now,
  loudly. And the guard was keyed on the **wire case**, so a `double_value` NaN — protobuf `double`
  carries it natively — still threw, i.e. "closing the mapper closes every path" was false.
  **Round 2 found the fix had a larger blast radius than the bug**: `ChangelogFold.encode` parses
  decimal keys with a bare `new BigDecimal` and runs *before* any Parquet writing, so a `NaN` key
  aborted the **whole site's** checkpoint build, deterministically, every night, with the pointer and
  retention frozen — where the original defect cost one table one file. That guard stays whatever
  else changes. Round 2 also made the writers destination-aware, keeping the token for a bare
  `numeric` (Avro STRING) and the double for `double precision` (Avro DOUBLE).
  **Round 3 found that destination rule had introduced silent corruption** — a non-finite narrowed
  into a `bigint` wrote `0`, uncounted — and it was **reverted** rather than patched again: two
  rounds running, a fix here had opened a hole elsewhere on the same coercion path. So the shipped
  rule is the simple one, NULL for every destination, with the Parquet and SQL paths agreeing; the
  two column types that could keep the value are a **deferred** piece of work, recorded with that
  history as its warning. The cost of the revert is stated rather than hidden: `NaN != NULL` now
  holds for *every* column type, so soak #39's source-vs-server comparison differs on more cells
  than the destination-aware form would have left.
  See `docs/delta-client-v2-guide.md` ("A value the column type cannot hold", Metrics).
- error-toast-once: `setupErrorHandler()` replaces its own interceptor instead of stacking another
  one behind it, so one API failure produces one toast (issue #225, found by review round 3 on
  #211/#223). It was the **only** `apiClient.interceptors.*.use(...)` in the application that kept
  no handle on its registration — both siblings in `shared/api/interceptors.ts` store the id and
  `eject` it first, precisely because they are called more than once — while `App.tsx` calls all
  three from **one** `useEffect` keyed on
  `[isLoading, isAuthenticated, getAccessTokenSilently, logout]`, which re-runs at least twice as
  `isLoading` falls and `isAuthenticated` rises, with `getAccessTokenSilently` changing identity on
  top. The new test reproduces the reported count exactly: three registrations, **three** toasts for
  one 404. **Idempotent registration, not moving the call out of the effect** — the issue asked for
  that shape and the reason is worth writing down: the effect *must* re-run, since the token getter
  it installs closes over `isAuthenticated`, so what has to be safe is the registration and not its
  cadence. **Two consequences fall out of the same line.** The accumulation also scrambled the
  **order** of the response chain, which is not obvious from the symptom: axios ejects by nulling
  the slot and registers by appending, and `setupResponseInterceptor` re-registers its 401 handler
  on every run while the toast handler did not, so from the second run onwards the chain was
  `[toast#1, 401#2, toast#2]` — the stale toast interceptor running **ahead** of the live refresh
  handler; it is now always `[401, toast]`, the order `App.tsx` writes. And `initTokenRefresh`,
  which the issue asked to audit because it is registered from the same effect, needs **nothing**:
  it registers no interceptor at all, only assigns two module-scope callbacks, so a repeat call
  overwrites where this one accumulated — `token-refresh.ts` is deliberately untouched.
  **No test can start red against a property that already holds in one direction**, so the
  assertion was proven by mutation both ways: with the `eject` removed it reads three toasts, and
  with the freshly registered id ejected instead of the previous one — "one toast" satisfied by
  leaving no handler at all — two of the three tests go red. Frontend-only: no backend, REST, gRPC,
  proto, DTO, migration, configuration-key, metric, S3-key, route or `App.tsx` change, and no doc
  named this code (the "exactly one toast" claim in `docs/delta-client-v2-guide.md` is about the
  download pills, whose presign requests suppress the global toast, so it was true throughout and
  stays true). One side finding was recorded rather than folded in, and two
  review rounds widened it from one route to **four** — which is the part of this ticket most worth
  remembering, because it says what the fix does **not** buy. The handler speaks out of turn on
  every 401 path, and none of the four is about registration count: the `switch` has no `case 401`,
  so a 401 rejected without a refresh reaches `default:`; a *failed* refresh rejects with the
  **Auth0 error** rather than the original response, which carries no `.response` and is therefore
  read as a network failure; that same error carries no `.config` either, so **`suppressErrorToast`
  is lost** and a caller that deliberately renders its own taxonomy gets the global toast anyway;
  and a refresh that *succeeds* retries through `apiClient.request(...)`, which **re-enters the
  whole chain**, so a retry that fails too toasts on the inner request and again on the outer
  rejection. **The chain order is pinned by a test rather than asserted in prose** (review round 3,
  which found this entry recording a delivered consequence that nothing held): a 401 whose refresh
  repairs it must produce **no** toast, which is false the moment the two are registered the other
  way round — registration count cannot see that, only the two interceptors together can, so it is
  the one test in this file that wires `setupResponseInterceptor` beside this handler. Round 3 also
  added `clearErrorHandler()`, the counterpart of `interceptors.ts`'s `clearResponseInterceptor()`:
  ejecting by a remembered index is safe only while nobody resets the response chain, and a teardown
  calling `.clear()` restarts the ids at 0, after which a remembered id names whatever now sits in
  that slot. The second is the ugliest and the fourth is the one that bounds this ticket's claim:
  on the expired-refresh-token branch `interceptors.ts` stays deliberately silent so the logout
  redirect is quiet, and this handler fills that silence with "Network error. Please check your
  connection and try again." — a lie about the user's connection; while the fourth is **two
  identical toasts for one failure with a correctly registered interceptor**, which is why the PR
  title claims only that the stacking stops, not that one failure now toasts once. All four
  contradict the comments in this file asserting a 401 is never toasted here, so **those comments
  are corrected rather than left standing** (the `AbstractIntegrationTest` precedent of #197): the
  file enumerates the four routes and names the finding as the open decision, and the test-file
  header's `suppressErrorToast` claim is narrowed for the same reason. Not fixed here — different
  mechanisms (two interceptors and a re-entrant chain, not one interceptor registered twice) and
  what a failed refresh should say is a behaviour decision of its own.
- fixture-clears-by-batch: The suite's shared-database cleanups clear `changelog_segments` by the
  relationship the constraint actually uses, not only by `site_id` (issue #226, filed by the
  `/github-issue-runner` dispatcher when `develop` went red on a change that could not have caused
  it). `test-data.sql` swept segments by `site_id` and then deleted `batches` by `site_id`, but the
  constraint standing in the way of the second statement is `changelog_segments_batch_id_fkey`, on
  **`batch_id`** — and only `site_id` carries `ON DELETE CASCADE`. The two are the same relationship
  for a segment the application wrote and **different** relationships for one a test wrote, because
  `ChangelogSegment.create(siteId, batchId, ...)` takes them independently and nothing requires the
  batch to belong to the site. A segment pairing a site the predicate does not match with a batch
  the next statement deletes therefore survived the sweep and blocked it — surfacing as a
  `ScriptStatementFailedException` **inside `@Sql`** in whichever class ran next, so the failure was
  reported against an innocent test and cost a full investigation each time, the complaint #207 was
  filed for one layer down. **Both** non-cascading references to
  `batches` are swept by the relationship their constraint uses, in **three** cleanups:
  `changelog_segments.batch_id` and — added in review round 1 — `account_plugins.baseline_batch_id`,
  which is `ON DELETE RESTRICT` (V25) and the only other FK to `batches` without a cascade, so an
  activation owned by an account outside `%@example.com` blocks the identical statement one
  constraint over. `test-data.sql`, `DeltaSessionLivenessIntegrationTest.cleanUpSeededData` and
  `BatchTerminalTransitionLockingIntegrationTest.tearDown` all carry the pair; the third seeds
  neither today and is fixed anyway, since "safe because nobody writes one yet" is how this sat
  latent. It is the shape `uploaded_files` already uses two statements earlier for the same
  constraint-shaped reason.
  **The rows were real rather than hypothetical, and the ticket asked for their origin before a
  fix.** Instrumenting the fixture over a full-suite run (a single statement carrying its payload in
  a cast error — Spring's `ScriptUtils` splits on `;` and does not understand a `DO $$ … $$` block,
  which is itself worth knowing before anyone puts one in that file) caught segments of
  `store-02.example.com` pointing at `store-01`'s seeded batch, `first_seq` 1 and 7, keys
  `delta/{store-02}/segments/{1,7}.pb.gz` — `DeltaSqlQueueRepositoryIntegrationTest.seedSegment`,
  which passed one `BATCH` constant for both of its sites. It is fixed **at source** as well
  (`batchOf`, so the batch follows the site), rather than only tolerated: a re-run of the
  instrumented suite finds no mismatched row anywhere except the one the new guard plants
  deliberately. Those rows also leaked into 51 other classes' fixtures before their own `@Sql`
  cleared them, which is the second mechanism the ticket records — `findNextPendingPluginSql` has
  **no site predicate** (the queue is global, #175), so a leaked pending head is claimed by an
  assertion in another class that believes it owns the database.
  **What was deliberately not concluded, and it is the important half**: this change is hardening
  that closes a proven mechanism, **not** a demonstrated fix for the failures that prompted the
  ticket. The captured mismatches are between two `%.example.com` sites, which the old sweep still
  removed, so they explain the cross-class leak but have never been shown to block anything. And a
  backlog pass on the ticket (comment of 16:08, which this work initially missed by reading the
  issue without its comments) established the sharper fact: **the fast gate cannot create the
  blocking row at all**, since `build.gradle.kts` excludes `**/integration/**` by path and no class
  outside that package persists a site whose domain falls outside `%.example.com` — verified again
  here, where the only such class is now this ticket's own guard. The originally observed failure
  was in the fast gate, so it has a mechanism this fix does not address (a row committed by another
  connection *between* the two statements is the open candidate, and `OR batch_id IN (...)` does
  nothing for it). Eight fast-gate passes and a full-suite pass did not reproduce it. The fix is therefore justified structurally (the
  constraint and the `uploaded_files` precedent) and pinned by a guard that **constructs** the
  blocking row, rather than by a reproduction; the two observed `develop` failures
  (`DeltaSessionLivenessIntegrationTest`, `DeltaSqlQueueRepositoryIntegrationTest`) are consistent
  with it but not proof of it. A rate hypothesis is recorded on the ticket rather than acted on:
  #207 raising the test JVM from 512 MB to 2 GiB removes the memory pressure that evicted cached
  Spring contexts, so more contexts stay alive against the one shared database.
  `TestDataFixtureCleanupContractTest` is the leftover-then-clear guard of #119 and #159 — it seeds
  exactly the blocking row (a site outside `%.example.com` holding a seeded batch), runs the **real**
  script through `ResourceDatabasePopulator` (the same splitter `@Sql` uses, so it cannot pass
  against a construct `ScriptUtils` would choke on), and requires the row to be **gone** rather than
  the script merely to survive, since "it did not throw" passes against a fixture that never had the
  row. **Two methods, one per constraint**, each mutation-proven against its own: the segment one
  fails on `changelog_segments_batch_id_fkey` against the unfixed sweep, and
  `shouldClearActivationReachableOnlyThroughItsBaselineBatch` fails on
  `fk_account_plugins_baseline_batch` against the account-only one. It lives outside
  `**/integration/**` so the per-task gate runs it, and removes its own account, site and batch,
  which the fixture by construction cannot reach — a leaking guard would have become the next #226.
  The stranded rows hang off a batch the guard creates itself rather than store-01's flagship one
  (review round 2): the batch-keyed readers — `findByBatchIdOrderByFirstSeq`,
  `SqlGenerationPersistence.loadBatchData`, `BatchHistoryService` — have no site predicate either, so
  a batch-parquet build would have replayed a segment whose object was never uploaded and failed an
  artifact for a batch this class does not own.
  Test-only — no production code, REST, gRPC, proto, DTO, migration, configuration-key, metric,
  S3-key or frontend change.
- first-checkpoint-state: A freshly ingested site stops reading as broken — the two surfaces that
  reported a scheduled wait as a failure now say what they are waiting for (issue #213, folding
  **#214**, both from the same PDE QA run). **The ticket's own first task was to establish which of
  two candidates the Upload History "File" column was, and the answer is candidate 1**: that pill
  presigns the unified completed-batch artifact of 036
  (`GET .../delta/batches/{batchId}/tables/{table}/parquet`), which
  `BatchParquetFinalizationService` enqueues on `BATCH_COMPLETED` — and since 029 a batch *is* a
  session, so a CONTINUOUS session holds its batch `IN_PROGRESS` for hours and there is **by design**
  nothing to link to for its whole life. Per-segment egress is not involved and has no defect here
  (`DeltaSessionCommitTransaction` wakes the worker on the commit itself; `delta.egress.sweep-ms` is
  only a backstop), so #215 and this ticket do not meet. The column rendered that as a bare em dash,
  which reads as "the file is missing"; it now says **"After session"** with the reason on hover, and
  only while the session is `IN_PROGRESS` — nothing enqueues an artifact for a session that failed,
  so promising one there would be a promise nothing keeps.
  **The lag surface is the same defect one level up.** `CheckpointScheduler.buildCheckpoints`
  (`delta.checkpoint.cron`, 02:00) is the only producer of checkpoints apart from a forced rebuild,
  so a FULL_SNAPSHOT committing at 15:45 cannot have one before the next night — but lag is
  `lastAppliedSeq − lastCheckpointSeq`, and against a pointer of **zero** every record the site has
  ever applied is backlog: the QA site read "Elevated — 1,155 records behind checkpoint" with an
  amber pill beside it in the site list. `lastCheckpointSeq == 0` is now a **state of its own**
  (`getSyncStatus` → `awaiting-first-checkpoint`), which is the canonical "no checkpoint" already
  used by the backend — the initial row carries it, a wipe and a re-baseline reset to it, and
  `CheckpointService` applies the same test before seeding from a frame. The chip is neutral, the
  number stays with the caption *records awaiting the first checkpoint*, and the lag track is
  **replaced rather than recoloured**: its bands and its 1k/10k ticks are a scale of "how far
  behind", and no position on it is true for a site with nothing to be behind. In its place goes the
  moment the wait ends.
  That moment is the one backend addition: **`nextCheckpointBuildAt`** on `DeltaSyncStateResponseDto`
  (both projections — it is the deployment's schedule, not a diagnosis, and the owner is exactly the
  user staring at a site with no checkpoint, so the `lastRebuildMessage` reasoning of #186 does not
  apply). New `CheckpointScheduleService` resolves the next occurrence of the cron in the JVM's own
  zone (the zone `@Scheduled` uses when the annotation names none) and shares **one constant** with
  the `@Scheduled` tick, so the promised hour cannot drift from the tick that keeps it; Spring's
  disabled `-`, a blank value and — defensively, since Spring would refuse to start on it — an
  unparseable expression all answer empty rather than throwing on a request path, and the UI then
  says only that the build is scheduled. On the frontend the field is `z.string().nullable()
  .optional().default(null)` for the #186/023-r3 reason: this payload drives the whole Delta Sync
  tab and must degrade rather than fail the parse.
  **Three limits are deliberate and all are stated in the guide, two of them corrected in review.**
  *Stalled still wins* over the pending checkpoint — a client that has not updated its sync state for
  a day is more actionable and is independent of whether a checkpoint exists. *A site with nothing
  applied is not in this state at all* (review r1): an all-zero row is what a wipe leaves and what
  `requestRebaseline` creates for a client that never connected, and such a site is on **neither** of
  `CheckpointScheduler`'s work lists — segments, unmaterialized `checkpoints` rows — so the promised
  build is one nothing keeps, and nothing is waiting either. And the state says *no checkpoint
  exists*, not *the build is healthy*: it **cannot age itself out**, because no persisted fact says
  how long a site has been waiting — `site_sync_state` has no creation timestamp, and every
  whole-site abort (`frame_too_large`, `lossy_refold`, `history_gone`, a fold over `max-fold-bytes`,
  a deferral) leaves no `checkpoints` row either, so thirty failed nights carry byte-for-byte the
  payload of an afternoon's ingest. Review proposed bounding it by lag magnitude and that is
  **declined with reasons**: a first FULL_SNAPSHOT is unbounded, so the bound would report the
  largest sites as critical on day one — the defect itself, aimed at the sites with most to lose.
  What is done instead: both surfaces keep the **count** (the site-list pill reads
  `No checkpoint · 1.2k` rather than dropping the number, which is what this entry had claimed and
  the pill did not do), and the card names the build the state should not outlive ("Still missing a
  day later? The build is not completing" — round 2 corrected both the claim and the label: the sweep
  walks sites serially and a build deferred behind the #178 fold budget is a *designed* miss that
  repairs itself next tick, so "the build is failing" was the same over-claim one notch quieter,
  and the value is the **next** occurrence rather than the first, recomputed per request).
  Separating the two payloads needs persisted state and a migration, filed as **#224**; the
  durable alarm stays `delta.checkpoint.builds.aborted`, `delta.checkpoint.tables.given-up` and
  `delta.seq.lag`.
  **The ticket's second shape — building a checkpoint when a site's first snapshot commits — was
  weighed and rejected**: it moves a whole-site fold onto the very commit path the nightly cron
  exists to keep clear, and it would have to queue behind the fold budget (#152/#178) and the scratch
  budget (#150), i.e. exactly the region #193 is parked on. Nothing here was losing or corrupting
  data — both halves are reporting — so the DoD's last item was taken as asked and **`priority: high`
  was dropped to `priority: medium`**. No migration (V54 is still the last applied, V55 free), no
  gRPC, proto, metric, cache, configuration-**key**, S3-key or route change; `delta.checkpoint.cron`
  gains a second reader and, for the first time, a declaration in `application.yml`
  (`DELTA_CHECKPOINT_CRON`, which relaxed binding already honoured) — its name and default are
  unchanged. See `docs/delta-client-v2-guide.md`
  ("A site whose first checkpoint is not due yet").
- device-verify-expired-card: An expired browser verification now reaches its dedicated **Code
  Expired** recovery card instead of being indistinguishable from an unknown code (issue #219).
  `DeviceAuthorizationService.getAuthorizationInfo` already throws separate expired and not-found
  exceptions, but `DeviceAuthorizationController` converted both into 404. The GET verification
  lookup now keeps 404 for an absent authorization and returns 410 Gone for expired; the OpenAPI
  annotations state both outcomes. `DeviceVerifyPage` maps only 410 to `expired`, so its existing
  instruction to start a new request on the device becomes reachable while 404 and all operational
  failures retain the existing error card. Tests pin both the controller status and the rendered
  recovery action. The uncalled `denyAuthorizationDelete` export is deleted rather than creating a
  second denial path alongside the used POST action.
  **Review then found the card still unreachable by the likelier route**: the approve action kept
  mapping the same exception to 404, and the confirm card does not poll (`useVerifyInfo` has
  `retry: false`, `staleTime` 30 s and no `refetchInterval`), so a code that was valid on load and
  expired while the card sat open produced "Failed to authorize device. Please try again." — a retry
  that can never succeed — instead of the recovery card. `POST /api/v1/device/verify` answers 410
  too, and both action handlers route it to the same state through one `isExpiredResponse` predicate
  shared with the lookup. The `DELETE` denial is deliberately untouched: `deny` does not test expiry,
  so it cannot raise the condition, and its OpenAPI text stopped claiming otherwise. Two smaller
  ones: the 404 message dropped "or expired" — with expiry at 410 a 404 really is an unknown code,
  and since #211 that string is the *only* report, so it was sending operators to restart an
  authorization instead of re-reading the code — and the new `410` annotations no longer declare an
  `ErrorResponseDto` body that the bodyless `.build()` responses never send. No migration, gRPC,
  proto, DTO, configuration-key, metric, S3-key, route-path or TanStack Query key change. See
  `docs/cr-device-verify-expired-card.md` and `docs/device-flow-client-guide.md`.
- device-verify-false-toast: The one error signal on the device authorization path stopped being
  wrong, and the direct URL the client prints works again (issue #211, two defects in one flow, both
  frontend). **The toast is an off-by-one, not a race.** A `user_code` is eight characters rendered
  as `XXXX-XXXX`, so the *formatted* value is nine characters long — but `DeviceVerifyPage` fired
  its lookup at `userCode.length >= 8`, which is the keystroke **before** last. Typing `M9Q2-4AML`
  therefore asked the backend about `M9Q2-4AM`, a seven-character code that cannot exist; the
  backend answered 404, the global axios interceptor toasted "Resource not found.", and the ninth
  character started a second lookup under a **new query key**, so the page's own error branch never
  saw the failure and the flow went on to create the site. That is exactly the reported shape: a
  transient toast over a verification that succeeds, with the page never showing an error. It is
  invisible to a paste (one state change, one lookup, the complete code), which is why the existing
  page test — written with `userEvent.paste` — could not see it. The threshold now lives in one
  place (`features/device-auth/model/userCode.ts`: `formatUserCode`, `isCompleteUserCode`,
  `USER_CODE_LENGTH`), shared by the query's `enabled`, the Continue button's `disabled`, the
  field's `maxLength` and the hook's own guard, which had the same `>= 8` written a second time.
  **Suppressing the 404 class was rejected as the fix and taken as the belt-and-braces**: the page
  renders a message per status already (400 "already processed", 404 "not found or expired"), so
  the interceptor was a *duplicate* report for every genuine failure and the *only* report for a
  superseded one — `getVerifyInfo` therefore opts out through the existing
  `suppressErrorToast` request flag (the `deltaSyncApi` precedent), which is one request rather
  than a status class, so a real 404 anywhere else still toasts. **The suppression forced the page's
  own wording to grow up**, which is user-visible for every status but 400: with the toast gone that
  message is the *only* report, and the old fallback called everything non-400 a bad code — so a
  network outage or a 500 told an operator holding a perfectly good code to retype it.
  `describeVerifyFailure` (`features/device-auth/model/verifyError.ts`) splits it into no-response,
  400, 403, 404 and everything else, quoting the server where it has wording of its own and reading
  status and body through the existing `getServerErrorStatus`/`getServerErrorMessage`, so the
  error-body contract keeps one home. **The second defect is the login
  redirect, and it is what forced the typing in the first place.** `?code=` was stripped on load,
  the field came up empty, and the operator retyped the code by hand — the keystrokes the first
  defect needs. TanStack Router was ruled out by experiment: it keeps unvalidated search params and
  leaves the href alone. The mechanism is Auth0: `cacheLocation="memory"` means nothing survives a
  page load, so **every** cold load of a protected route starts unauthenticated, the guard calls
  `loginWithRedirect`, an SSO session makes the round trip invisible (no prompt — which is why the
  report says the session was live), and `Auth0Provider.onRedirectCallback` then restores the
  address bar from `appState.returnTo` with `history.replaceState` — patched by `@tanstack/history`,
  so the router follows it. `returnTo` was `window.location.pathname`: everything after the path was
  gone before the app rendered. `currentReturnTo()` (`shared/lib/auth/returnTo.ts`) is
  path + search + hash and is used by `AuthenticationGuard`, `UserOnlyGuard` and
  `useAuth.signinRedirect`; it is assembled from the current location and stays root-relative, so it
  cannot become an off-origin redirect target. This fixes deep links with query parameters for
  **every** protected route, not only `/device-verify`. The **Try Again** button on the
  application-wide authentication-error screen (`App.tsx`) uses `retryReturnTo()` instead, and the
  difference is the one non-obvious rule in the module: that screen is reached after a failed
  `handleRedirectCallback`, so the location it renders at *is* the callback URL with Auth0's own
  parameters still on it — the SDK does not clean them up on the failure path — and returning there
  would write a **consumed** `code`/`state` back into the address bar for the SDK to re-read as a
  fresh callback. The test is the SDK's own `hasAuthParams` (`state` alongside a `code` or an
  `error`), and the missing `state` is precisely what keeps `/device-verify?code=XXXX-XXXX` — which
  has a `code` of its own — on the preserved side. The page also normalizes the code it takes
  from the URL, since that value is pasted by hand and need not arrive in the presentation the
  backend stores (`?code=m9q24aml` now resolves to `M9Q2-4AML` rather than 404ing), and a URL code
  that is *incomplete* pre-fills the input state instead of going straight to a confirmation card the
  lookup cannot fill — which used to render neither the details nor the spinner, i.e. a blank page.
  Three existing
  tests pinned the old path-only `returnTo` and were rewritten rather than left standing — the
  behaviour changed deliberately. Frontend only: no REST, gRPC, proto, DTO, migration,
  configuration-key, metric, S3-key, route-path or TanStack Query key change; `/device-verify` and
  its `?code=` parameter are the contract the PostgreSQL Data Extractor TUI prints and are untouched.
  See `docs/device-flow-client-guide.md` (Step 2 tips, Troubleshooting).
- test-jvm-heap-ceiling: The test JVM has a heap ceiling, and an allocation failure in it now names
  itself instead of an innocent test (issue #207, found by the `/github-issue-runner` dispatcher on
  a `develop` pipeline that went red on a change which could not have caused it).
  `tasks.withType<Test>` declared `useJUnitPlatform()` and nothing else, so every test JVM took
  Gradle's **512 MB** default — and CI runs the whole suite in one of them (`./gradlew test
  --no-daemon`, ~2470 tests, 444 classes, **24 cached Spring contexts** alive at once). The margin
  was whatever the runner happened to leave, which is why it fired intermittently and, worse, why
  it fired somewhere else each time: an `OutOfMemoryError` is an ordinary `Throwable`, so it
  unwound into whichever caller was on the stack — Spring's `ConstructorResolver`, re-reported as a
  `BeanCreationException` of a contract test that allocates nothing, and a retry in the same job
  named two *different* tests. `CLAUDE.md` tells every agent never to write off a red check without
  checking which test it is, so each occurrence cost a full investigation that ended at a name with
  nothing to do with the failure.
  **The number is measured, not chosen.** `./gradlew test -PtestHeapLog` (the opt-in this ticket
  adds, one `-Xlog:gc` file per `Test` task under `build/reports/test-heap/`) at a deliberately
  generous 3 GiB ran the suite green in 5m27s and never let G1 expand past **1014 MB**; the highest
  occupancy *after* a collection was **801 MB** and the highest before one **965 MB**. So a 1 GiB
  heap sits exactly on the cliff and the shipped default was under it — `maxHeapSize = "2g"`, about
  2.5x the live set, is the ceiling, with the guard's agreed range 1.5 GiB–4 GiB. The upper bound
  is the runner rather than the suite: `ubuntu-latest` has 16 GB shared with the PostgreSQL, Redis
  and LocalStack service containers, the Gradle build JVM and every Testcontainers image, and past
  that the kernel's OOM killer answers first — a failure that names nothing at all.
  **The DoD's second item answered explicitly: one JVM, no `forkEvery`.** Forking would bound the
  context accumulation by discarding the Spring `TestContext` cache, and that cache is exactly what
  makes 444 classes affordable — a fresh JVM rebuilds every context it needs, Flyway migration
  included, and restarts the Testcontainers singletons. It is the right tool for accumulation with
  no ceiling; this accumulation has one, being **one context per distinct configuration** (24), a
  property of the test classes and not of the test count, which is what the measurement shows
  levelling off under a gigabyte. `maxParallelForks` is excluded for an unrelated reason and is
  worth stating separately: the suite deliberately shares one PostgreSQL database across every
  context — `test-data.sql` deletes by `%.example.com`, the delta-SQL queue is global (#175), and
  #197 had to bound lock waits precisely because sibling contexts already contend on the same rows.
  **The self-naming half is `-XX:+ExitOnOutOfMemoryError`**, with
  `-XX:+HeapDumpOnOutOfMemoryError` + `-XX:HeapDumpPath=build/reports/test-oom` beside it because
  the JVM is about to disappear and the dump is the only evidence left for re-sizing. Verified
  against a real one by forcing the suite to 96 MB: the build prints
  `java.lang.OutOfMemoryError: Java heap space`, the dump path, `Terminating due to
  java.lang.OutOfMemoryError`, and fails as `Process 'Gradle Test Executor 4' finished with
  non-zero exit value 3` — **no test named, because no test was at fault**. The dump is
  deliberately *not* added to the CI artifact upload: at this ceiling it can reach two gigabytes,
  and the log line already carries the diagnosis the ticket asked for.
  **Three guards, and the third is the one worth reviewing.** `TestJvmHeapCeilingTest` (fast gate)
  requires `maxHeapSize` to be declared **exactly once** and inside `tasks.withType<Test>` — a
  second declaration on `tasks.named<Test>("test")` or `integrationTest` wins for that task and
  would quietly leave its sibling on the 512 MB default — requires the value to sit in the agreed
  range, requires both flags, and reads `Runtime.maxMemory()` to check that Gradle really launched
  *this* JVM with the declared value, since `GRADLE_OPTS`, `org.gradle.jvmargs` or a later `-Xmx`
  would make the build file documentation rather than configuration (that assertion caught its own
  premise during development: an init script setting a heap *before* the build script is silently
  overridden by it). Outside Gradle — an IDE run configuration, detected by the absence of the
  `dfm.test.parquet-scratch-root` property Gradle always sets — only the floor is required, the
  `RunOwnedScratch` fallback and for its reason. `TestJvmOutOfMemoryExitTest` is the wired half a
  build file cannot be: three **child** JVMs at 32 MB, one per branch of the claim — with the flag a
  real allocation failure exits 3, names `java.lang.OutOfMemoryError` and never reaches the child's
  own `catch` (and a heap dump *is* written, so the two flags do co-operate in that order); without
  it the identical child catches the error and exits 0, which is the swallow being removed and is
  what stops the first assertion from passing against a JVM that would have died anyway; and with
  the flag a `throw new OutOfMemoryError(...)` from ordinary code **stays catchable**, because it
  never reaches the VM's allocation-failure path — the property that keeps
  `BatchParquetFinalizationIntegrationTest`'s Mockito stub working, which otherwise reads as a
  landmine. `TestJvmHeapTest` asserts the reader itself over synthetic build scripts (a size
  literal with each suffix, `"2 GiB"` refused by name rather than read as two bytes, a flag named
  only in a comment not counted, an unbalanced brace inside a string literal not ending the block,
  and every declaration reported rather than the first) — the `LockWaitBoundTest` precedent, since
  a guard that misreads the file is worse than no guard. `RunOwnedScratch.projectRoot()` becomes
  public so `TestJvmHeap` locates `build.gradle.kts` the same way rather than growing a second idea
  of where the checkout is.
  **`.github/workflows/ci-cd.yml` is deliberately untouched**: the ceiling belongs in the build
  file, where it applies to a developer's run and to CI alike — the ticket's own point that the
  defect was invisible locally. The DoD's last item (`./gradlew test` green on `develop` twice in a
  row) can only be observed after the merge. No production code, REST, gRPC, proto, DTO, migration,
  configuration-key, metric, S3-key or frontend change. See `README.md` ("The test JVM").
  **Two rounds of review then hardened the guard rather than the value, and the finding that
  mattered was a hole in its own coverage.** `TestJvmHeapCeilingTest` lives under `config/` and
  `integrationTest` includes `**/integration/**` alone, so its dynamic half could only ever observe
  the `test` task's JVM: a `-Xmx` added to `integrationTest`'s own `jvmArgs` left all four
  assertions green while the Testcontainers suite — the task that boots the most Spring contexts —
  ran on 512 MB, which is #207's own defect in the worst place for it. Closed twice: a static check
  refusing **any** `-Xmx` in the build script (`maxHeapSize` is the only form the guard can read,
  and the override in a form it cannot read is the same override), and a twin in the `integration`
  package that reads `Runtime.maxMemory()` of the JVM that task is actually given — no Spring
  context, no container, in that package for the one reason that it is what the filter includes.
  Both fire under mutation. Round 1's finding was the reader: `"…${f("x")}…"` paired the nested
  literal's opening quote with the outer one and desynchronised from there, and both consequences
  were silent — an unpaired brace left in what the scan then read as code ran the block match past
  its own closing brace, and a `//` inside a literal blanked the rest of its line, declaration
  included. `build.gradle.kts` already had that shape three times and only re-balanced by luck, so
  the first mutation-proof fixture had to be rewritten to carry both hazards rather than the benign
  one. The scanner is now a Kotlin lexer (nested block comments, raw strings, char literals,
  recursive template expressions), and the Javadoc explaining it closed its own comment on the first
  attempt — the `AsyncExecutorQualifierTest` mistake, made again and caught by the compiler. Three
  more, all small and all the same class: `runChild` read the child's output to EOF *before*
  `waitFor`, so the deadline was unreachable and a hung child would have parked the JUnit thread for
  ever — the #197 failure mode inside the class about naming failures (the output goes to a file
  now, and the child is killed when it outstays the bound); `parseSize` caught the parse but not the
  multiplication, so `"17179869186g"` wrapped to exactly 2 GiB and passed every assertion while
  `"9999999999g"` was reported as a negative ceiling (`Math.multiplyExact`), and `"2 g"` — which
  Gradle passes through verbatim and the JVM refuses — was read as 2 GiB; and the IDE-branch message
  told a developer to add `-Xmx1536 MiB`, which is not an argument the JVM takes. One finding was
  answered with prose rather than code, deliberately: `-XX:+ExitOnOutOfMemoryError` fires for
  **every** VM-raised `OutOfMemoryError`, so `unable to create native thread` and `Metaspace` end
  the worker the same way and lose every remaining result — HotSpot has no per-message form, the
  trade still favours the flag over the swallow, and what an operator needs is the warning that
  raising the ceiling is the remedy for the first only and makes native-thread exhaustion likelier.
  The exactly-once message was also misdiagnosing the case it fires on: the shared assignment
  already covers the siblings, so the hazard is the *narrowed* task dropping below the floor, not a
  sibling left on Gradle's default.
- memory-abort-visible: A memory-pressure abort is a refusal that says so, instead of an empty
  `Optional` no caller could tell from "this batch produced no changes" (issue #181, found working
  #174 and named by it as the reason that defect was expensive rather than merely wrong).
  `generateSqlContent` returned `null` on the abort — which is also the legitimate answer for an
  empty diff and for a baseline batch — and the two call sites read it in the two worst possible
  ways. `DeltaSqlQueueService.processNextPending` ran `markPluginSqlProcessed()`, so the segment
  was **consumed** and the batch's SQL was lost for good: the comment beside that line has always
  stated the retry contract exactly ("throws on failure → mark is skipped → segment stays pending
  for the sweep"), and the abort was the one failure that did not throw. `doRegenerateForBatch`
  substituted a `-- No changes detected` artifact, **persisted** it, and `PluginHistoryService`
  then marked the original generation superseded — so on the admin route the abort did not merely
  drop work, it replaced a good generation with an empty one and reported success. That path is
  the one an operator uses to recover a dropped batch, and heap pressure is likeliest during a
  regeneration of a large batch, so recovering from one drop could destroy another.
  **The ticket's first shape, taken as written**: `MemoryPressureAbortedException` (a subclass of
  `SqlGenerationException`) is thrown where the `null` was, and every consequence then falls out of
  paths that already exist rather than out of new branches — the queue's mark is skipped, the
  regeneration never reaches `markAsSuperseded`, both `catch (RuntimeException)` blocks write a
  `SQL_GENERATION_FAILED` (regeneration: `SQL_REGENERATION_FAILED`) audit entry **naming the
  batch** (`GET /api/v1/account/plugins/{pluginId}/logs`, so the account sees it, not only an
  operator with a metrics console), and the two manual-generation endpoints answer **500 quoting
  the refusal** through the `catch (Exception)` they already had, where they used to answer 200
  "SQL generation skipped". The DoD's second half is therefore satisfied twice over — the batch is
  both retried and named — and `null` keeps its one remaining meaning. The regeneration half is a
  fix for a path **#190 currently blocks** (`regenerateSql` is `@Transactional`, so
  `refuseIfTransactionActive()` throws first): it is fixed here because the hazard returns the
  moment #190 lands.
  **Three reporting decisions came out of review, all in the same direction — the refusal must not
  be mistaken for a broken generation.** It is kept **off** `sql.generation.errors` /
  `sql.regeneration.errors` and logged as a **single WARN** at the point it is raised, carrying the
  reading and the threshold: those series and an ERROR-rate alert mean the same thing ("generation
  is broken") and this condition repairs itself when the heap does — #162's rule applied to
  `delta.checkpoint.builds.aborted`. Round 2 caught the half-measure here: the first fix left the
  raise-site ERROR in place and added a WARN in the catch, so one refusal produced both, and all
  three prose sources disagreed about which. It is refused **before** the `SQL_GENERATION_STARTED`
  entry, so an attempt costs one audit row instead of a pair announcing a generation that never
  started. And the exception **message** names neither the heap reading nor the configuration key —
  it reaches the owner endpoint's 500 body and the account-visible `errorMessage`, where a tenant
  can act on neither, so both numbers stay in the log line (the #186 round-1 precedent for
  `lastRebuildMessage`); round 2 also removed its promise that "the generation is retried
  automatically", which is true for a segment in the delta-SQL queue and false for both manual
  routes, and this type cannot tell which caller raised it.
  `sql.generation.aborted.memory_pressure` keeps its name and shifts meaning: it counted batches
  lost (each was refused once, then consumed) and now counts **refusals**, so one batch under a
  long episode increments it repeatedly — a rate, with the audit entries saying which batches.
  Two neighbouring series move without anything being wrong: `sql.generation.semaphore.acquired`
  counts the refused attempt (the permit is taken first), and `sql.generation.duration` takes no
  sample from it.
  **The ticket asked for the retry to be argued rather than assumed, and the argument is that this
  abort is not a property of the batch**: it is a pre-flight reading of the *pod's* heap taken
  before any work, so the same batch generates normally on a later sweep — the opposite of the
  deterministic Parquet size ceilings, whose refusal repeats identically for ever and which are
  therefore bounded by attempt counters (#149) or settled as `ABANDONED` (036). It cannot spin
  either: the throw ends the whole `DeltaSqlSweepWorker` drain, so there is exactly one refused
  attempt per **wake** — and review corrected the rate here, because the wake is not only the
  `plugin.sql-generation.delta-sweep-ms` tick (60 s): `BitBiPlugin.execute` wakes the pool on
  every `BATCH_COMPLETED` and a plugin reinit does too, so on a busy fleet an episode produces one
  refused attempt, one WARN and one audit row per completed batch. That is the intended visibility
  and it is repetitive; the tick is the floor of the rate, not its ceiling. The per-site
  head-of-line claim keeps the site's own order while it waits. The safety of an **unbounded**
  retry does rest on the threshold being sane, which is still unvalidated (**#185**): a mistyped
  `8` now stalls the queue for ever where it used to drop every batch's SQL silently — louder, and
  the work survives, but a stall with no bound of its own. Round 2 found that "recoverable" has a
  horizon of its own: `ChangelogRetentionService.prune` deletes below-checkpoint segments past
  `delta.retention.audit-window-segments` without regard for `plugin_sql_at IS NULL`, so a segment
  left pending long enough is deleted with its object and the SQL is lost after all, silently and
  without the audit row a refusal writes. Pre-existing — it bounds the retry window of *any*
  durable generation failure, not just this one — and filed as **#212**.
  **No controller, DTO or REST-contract change** — deliberately, since a dedicated 503 would touch
  both generate-SQL controllers, which #190 is parked on; a 500 whose body names memory pressure
  already satisfies "distinguishable at every caller", and the status code can move with #190.
  The DoD's third item is `DeltaSqlQueueMemoryPressureTest`, which wires the **real**
  `SqlGenerationService` behind the queue rather than a mock: `DeltaSqlQueueServiceTest`'s existing
  `shouldLeavePendingOnFailure` pins what a throw does and, by construction, could never pin that
  the abort **is** a throw. Its third method drives the same wiring below the threshold, so the
  first two cannot pass against a service that refuses everything. The refusal's own assertions were **proven red by mutation** — with the
  throw put back to `return null`, five test methods fail across the two classes — while the two
  "the broken-generation series did not move" assertions are pinned by the other mutation, removing
  the `instanceof MemoryPressureAbortedException` branch from the catch. One small correctness gain came
  with it: the check reads the heap **once** (`refuseUnderMemoryPressure`, extracted from
  `generateSqlContent` in review round 1 so the refusal precedes the audit), so the value logged is
  the value that tripped it rather than a second sample — `isMemoryPressureHigh` takes the reading
  as a parameter. Two more findings this review produced are tickets rather than edits:
  **#210** (`generateSqlForBatchAsync` has no callers and documents a reinit flow that was removed)
  and #212 above. No gRPC, proto, DTO, migration, configuration-key, metric-**name**,
  S3-key or frontend change; `sql.generation.aborted.memory_pressure` is unchanged and still
  registered at zero. See `docs/020-sql-generation-optimization.md`.
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
  is about. **The 30 s is measured against the suite's own deliberate waits, not against its
  slowest statement**, since `lock_timeout` bounds waiting for a lock and never holding one — and
  against the longest wait the suite *declares* rather than the longest one observed:
  `BatchParquetQueueServiceIntegrationTest` leaves an `UPDATE` on an operator's row lock and
  releases it only after an awaitility poll budgeted at **10 s**, which resolves in milliseconds
  on an idle machine and would have failed a healthy test on a loaded one; the fixed 1.5 s holds
  in `SiteHistoryWipeIntegrationTest` and `DeltaRebaselineIntegrationTest` are the easy ones. Two
  guards, the #187 shape: `LockWaitBoundTestProfileTest` holds the key on the fast gate, and
  `DatabaseLockWaitBoundIntegrationTest` holds what a **pooled** connection actually carries (a
  file can only show what was declared) and produces a genuinely blocked statement, requiring it
  to be aborted with 55P03 while the holder is still holding. They share `LockWaitBound` — one
  parser and the agreed 15 s–60 s range — rather than each carrying its own idea of a sane bound,
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
  before taking the lock no longer has its exception swallowed by the `Future`. **Round 2** closed
  the same hole one spelling wider and one symmetry short. The last-wins rule only recognised
  `SET` and `RESET lock_timeout`, so `RESET ALL` — which never names the GUC — and
  `set_config('lock_timeout', '0', false)` still read as the assignment before them; both are
  matched now (and `set_config` is read as a bound when it sets one), pinned by mutation. The
  probe's own failure was swallowed where the holder's had just been fixed: the task returns its
  throwable rather than throwing it, so a pool that could not hand out a connection — four of
  them, one pinned by the holder — was reported two minutes later as an unbounded lock wait; it is
  now reported as itself. And the floor message said “at or below” for an assertion that accepts
  the floor exactly. **Round 3** moved the value, which is the finding worth keeping: the
  derivation named the 1.5 s holds as the longest deliberate wait and had missed the 10 s
  awaitility budget above, so the shipped 10 s had **zero** margin over a test that blocks an
  `UPDATE` on purpose — 30 s now, with `MIN` raised to 15 s so the floor is the declared budget
  rather than the observed one. Three more on the reader, all the same class of hole as round 2's:
  `SET LOCAL lock_timeout` and `set_config(..., true)` are transaction-scoped and are undone by
  the commit Hikari makes after the init SQL, so they leave a pooled session exactly as unbounded
  as no statement at all while reading as the bound they name; `DISCARD ALL` joins `RESET ALL` as
  an undoing that never names the GUC; a digit run no `Duration` can hold now fails as this guard
  rather than as a raw `NumberFormatException`; and the static guard's missing-key message no
  longer arrives as the string "null" read as a malformed statement. Test-only — no production
  code, REST, gRPC, DTO, migration, production configuration-key, metric, S3-key or frontend
  change.
- async-executor-guard: Every `@Async` in `src/main/java` names the executor it runs on, and a
  newcomer that does not fails the build (issue #195, raised reviewing #194/#165).
  `AsyncConfiguration`'s Javadoc had stated the fact — every `@Async` site is an
  `@Async("pluginExecutor")` method in the plugin package — and **nothing kept it true**. The two
  ways it can stop being true are both invisible until production: an unqualified `@Async` falls
  through to `AsyncExecutionAspectSupport`'s default resolution, and since
  `TaskExecutionAutoConfiguration#applicationTaskExecutor` is
  `@ConditionalOnMissingBean(Executor.class)` and this application declares several, Boot's bounded
  pool **backs off** — so the live fallback is a `SimpleAsyncTaskExecutor` with a **new thread per
  invocation and no ceiling**; and a qualifier naming no bean is resolved lazily and throws on the
  **first invocation** of the method, not at startup. The first is exactly the shape
  `BackgroundConnectionDemandTest` (#161) exists to keep out and is invisible to **all three** of
  its discovery routes — not a `@Bean`, not a `max-concurrent` key, not a
  `new ThreadPoolTaskExecutor(...)` — which is why the guard belongs on the annotation rather than
  in that inventory. `AsyncExecutorQualifierTest` scans **two ways, because neither reaches
  everything**: `src/main/java` as text with comments stripped (total over the code this repository
  owns — nothing has to be concrete, independent or component-scannable, and the configuration
  classes carry a good deal of prose about this very rule, hence the stripping), and the loaded
  production classes through `MergedAnnotations` (the only way to resolve a value that is not a
  string literal, and the only way to see an `@Async` arriving through a meta-annotation; type-level
  as well as per-method, since a type-level one is the same defect at a larger radius). Every site
  either finds goes through both assertions, and a third test fails when the two stop agreeing on
  **which files** carry an `@Async` — a scan that has gone blind is otherwise indistinguishable from
  a clean application. The names are resolved against `BackgroundConnectionDemandTest.scanExecutorBeans()`
  itself (made package-private for it, the `ScheduledTaskInventoryTest.scanScheduledMethods()`
  precedent), so the set an `@Async` may name and the set the connection pool is sized against
  cannot drift apart: an executor visible to one and not the other is the gap both classes exist to
  close. That equality also asserts the **premise** rather than leaving it as prose — with no
  `Executor` bean declared, Boot's pool would no longer back off and the unbounded fallback would
  not be the live branch. **No test can start red against a property that already holds**, so it was
  proven by mutation, one per assertion: a bare `@Async` on `PluginEventDispatcher` (the unnamed
  check), `@Async("comparisonExecutor")` — the bean #165 deleted — (the declared-bean check), and an
  `@Async` on the `Plugin` **interface**, which `ClassPathScanningCandidateComponentProvider` will
  not reach (the drift check). The parser is asserted directly as well, over synthetic sources:
  bare, `@Async("")`, `@Async(value = "…")`, the fully qualified form, an expression argument (named
  but not evaluable from text — the annotation scan resolves it, which is why a site is read twice),
  `{@code @Async}` in Javadoc, and a method whose own parameter list must not be read as the
  qualifier. Test-only — no production code beyond the `AsyncConfiguration` Javadoc, and no REST,
  gRPC, proto, DTO, migration, configuration-key, metric, S3-key or frontend change. See
  `docs/delta-client-v2-guide.md` ("The connection pool is smaller than the threads that can ask it
  for a connection").
  **Review found five blind spots in the guard itself, and two of them mattered.** The scans were
  keyed by the class the reflection scan *reached* a site through, but `getAllDeclaredMethods` walks
  the hierarchy, so an `@Async` on a superclass was attributed to every concrete subclass's file —
  correct, properly-qualified code failing the drift check, with a message blaming a meta-annotation;
  a site is now attributed to the annotation's own source (`MergedAnnotation.getSource()`),
  de-duplicated by method signature, with bridge and synthetic methods skipped. And the comment
  stripper was the copied regex (`/\*.*?\*/|//[^\n]*`), which fires **inside string literals**:
  `"/api/v1/device/**"` opens a phantom block comment that runs to the next `*/`, deleting **2088
  characters — 40 lines — of real code** in `SecurityConfiguration` alone, so the text scan's claim
  to be total over the source was false. It is now a character scanner that copies string, character
  and text-block literals verbatim (the literals are kept rather than blanked, because the qualifier
  being read *is* a string literal), and **`BackgroundConnectionDemandTest` shares it** rather than
  keeping the broken twin — where the same hole would hide a pool construction from a scan whose
  whole job is to find what nobody declared. The Javadoc explaining that fix had to be rewritten
  too: written with `\u002a` escapes it closed its own comment, since Java resolves unicode escapes
  before it lexes. Three smaller ones: the two scans are compared **site by site per file** rather
  than by file key, because one file holds 15 of the application's 18 sites and a stripper accident
  there could have hidden fourteen with every assertion green; `TaskScheduler`-typed beans are
  excluded from the names an `@Async` may use (`@Async("taskScheduler")` passed both assertions and
  would park blocking async work on the pool whose size is derived over the `@Scheduled` inventory
  alone, postponing the fixed-delay sweeps #146 sized it for); and a duplicate executor bean name is
  its own failure rather than a silently dropped map entry reported as scan drift. The mutation set
  moved with the design: the interface probe now **passes**, because a default method is reached
  through its implementors and attributed to the interface's own file — the honest blind spot is a
  carrier the classpath scan cannot reach at all (an abstract class with no concrete subclass), which
  is what the drift check is now pinned against.
  **Round 2** was five more findings, all about the guard firing on *correct* code rather than
  missing anything, and one about what it costs. The stripper keeps literals verbatim (it must — the
  qualifier is one), so an `@Async` named in a log line or an exception message counted as a site;
  the kept literals are now **masked**, and a match beginning inside one is skipped. A `@Qualifier`
  on an executor `@Bean` is now a name an `@Async` may use, because that is what
  `BeanFactoryAnnotationUtils` resolves against. Executor beans are de-duplicated by method
  signature before the name-clash check, so a `@Bean` inherited from a base `@Configuration` — which
  `getAllDeclaredMethods` reports once per subclass — is not read as two beans clashing over one
  name, while the set compared with the connection audit keeps one entry per scanned type because
  that is what the audit itself produces. The assertion messages are suppliers and the classpath scan
  is memoized: the green path ran five ASM scans plus a `Class.forName` over every production class,
  on a gate that fires on every commit. And the case the class's own Javadoc names as the reason the
  annotation scan exists would have **failed** it: a meta-annotated `@Async` has its qualifier
  written in the annotation's own file, which the classpath scan skips because an annotation type is
  an interface, while the methods carrying the meta-annotation have no qualifier written on them at
  all — two files, one count each, so the site-by-site comparison could not have matched. Those sites
  are now excluded from the *comparison* and still required to name a declared executor by both
  assertions, verified with a probe annotation in each direction. The exclusion is stated in the
  failure message, so the two shapes only one scan can see by construction are visible where someone
  would otherwise be tempted to silence the check.
- rebuild-outcome: A forced checkpoint rebuild says what it did, where before it said only that it
  had stopped (issue #186, raised reviewing #183/#178 and older than both). `rebuild_requested` was
  the whole record of an operator's click — raised by `POST .../delta/checkpoints/rebuild`, released
  when the attempt settled — and **three of the four settling endings ran nothing at all**: the
  build threw, S3 would not say whether the seed frame is there (#157), or another build held the
  process's fold budget past `delta.checkpoint.fold-wait-seconds` (#178). From outside the pod all
  four were identical: the "Rebuild queued" chip vanished and the checkpoints did not change, and
  the only recourse was to notice that nothing had happened and click again — which is exactly what
  the code's own log line said to do, in a log the operator cannot read. **Holding the flag is not
  the fix and has been rejected twice** (#157 round 2, #178): nothing re-drives a held flag — the
  nightly tick calls `buildCheckpoint`, never `rebuildFromFrame` — and `requestRebuild`
  short-circuits while it is set, so a held flag leaves the operator unable even to ask again. So
  the ticket's **option 1** is taken and its options 2 and 3 (re-submitting a deferral, answering
  409 at request time) are not: both are palliatives that cover the deferral alone, and the deferral
  is the *least* permanent of the three. V54 adds `site_sync_state.last_rebuild_outcome` /
  `_outcome_at` / `_message`, all nullable, where NULL means "no finished attempt on record" — which
  is what every existing row is. `CheckpointRebuildOutcome` is
  `COMPLETED | FAILED | FRAME_UNAVAILABLE | DEFERRED | DISCARDED | NOTHING_TO_REBUILD`; an executor
  rejection (both at request time and in `resumePendingRebuilds`) is `FAILED` quoting the refusal's
  own words rather than a value of its own, since its remedy is the same "ask again" and only the
  startup one was ever invisible.
  **Four properties carry the design.** Releasing the flag and writing the verdict are **one write**
  (`SiteSyncState.recordRebuildOutcome`), because releasing it without saying why is the state being
  removed. The **shutdown ending writes nothing** and keeps the flag (#162): it has not finished, and
  a verdict would contradict a flag that is deliberately still up while `resumePendingRebuilds()`
  waits for the next process — so `delta.checkpoint.builds.deferred` and this verdict disagree by
  construction on exactly that case, and both are right. While the flag *is* up the verdict describes
  the **previous** attempt, so the UI gives the queued chip precedence — showing both would read as
  "queued, and it failed" for a rebuild that has not run yet. And **the verdict lives exactly as
  long as the checkpoints it describes**: `resetForWipe` and `resetForRebaseline` both drop it,
  because both delete every `checkpoints` row of the site (#142) and a verdict about them then
  describes nothing; it is deliberately *not* tied to `rebuild_requested`, which the re-baseline
  leaves standing — the flag says "a rebuild is owed", the verdict says "this is what the last one
  did". The message for `FAILED` is the failure's **own** text prefixed with
  the exception's simple name, since an S3 client error, a JDBC error or an interrupt frequently
  carries no message at all and "the rebuild failed" alone says nothing; for `FRAME_UNAVAILABLE`
  and `DEFERRED` it keeps the exception's diagnosis and **replaces its advice**, because both of
  those messages are worded for `CheckpointScheduler` — which really does revisit the site — and
  end by promising that the next tick tries again, which on this path is false and would tell the
  operator to wait for a retry that is never coming. Truncated to
  `MAX_REBUILD_MESSAGE_LENGTH` (1000) **in the entity**, because a value wider than the column throws
  at flush and would lose the verdict entirely, which is where this ticket started. `runRebuild`
  pre-sets `FAILED` before the `try`, so a `Throwable` that is not an `Exception` still settles as a
  verdict rather than as a bare flag release. `DeltaSyncStateService.clearRebuildRequested` is gone,
  replaced by `recordRebuildOutcome(siteId, outcome, message)` — there is no longer a way to release
  the flag without saying why. **DTO change** (additive): `DeltaSyncStateResponseDto` gains
  `lastRebuildOutcome` and `lastRebuildOutcomeAt` on both sync-state projections and
  `lastRebuildMessage` on the **admin** one only (`forAdmin` / `forOwner` replace `fromEntity`) —
  for `FAILED` that string is the exception's own text, and the owner endpoint would be the one
  place a tenant user could read a `PSQLException` or an S3 endpoint, on an action the owner cannot
  even request.
  On the frontend the field is deliberately **`z.string()` and not `z.enum`**, the
  `deltaSegmentSchema.mode` precedent of 023 r3: this payload drives the whole Delta Sync tab, so a
  value added on the server must degrade to an unrecognised chip rather than failing the parse and
  blanking the tab — `describeRebuildOutcome` renders `Rebuild: <value>` for anything it has not
  heard of. The message is clamped to four lines with the full string on hover. No gRPC, proto,
  configuration-key, metric, S3-key or route change. See `docs/delta-client-v2-guide.md`
  ("A forced rebuild says what it did").
  **Two rounds of review then changed six decisions, and the first finding of round 2 was this
  ticket's own property violated**: `CheckpointService` swallowed two more endings into an empty
  fold — a build discarded because the site was wiped or re-baselined under it (#136/#142), and a
  forced rebuild of a site with neither frame nor segments — and `runRebuild`, seeing a normal
  return, wrote `COMPLETED` for both. A green "Rebuilt" chip over a rebuild that published nothing
  is exactly the false success being removed, and the discarded one is worse than cosmetic: the
  verdict lands *after* the reset's own `clearRebuildOutcome()`, so it sticks. Both are thrown now
  (`BuildDiscardedException`, `NothingToRebuildException`), the third and fourth application of the
  rule #157 and #162 established — a caller cannot tell an empty fold from a finished build.
  `CheckpointScheduler` catches the discard and logs it at INFO where it used to see a silent empty
  fold, and `NothingToRebuildException` is thrown **only on the forced pass**, since for the nightly
  tick that state is the ordinary quiet visit to a site named by an unmaterialized row.
  **Review round 1 changed four decisions and one field's audience.** The `FAILED` message is the
  exception's own text, and `lastRebuildMessage` therefore now sits on the **admin projection
  only**: a `PSQLException` naming a constraint or an S3 error naming the bucket and endpoint would
  otherwise be readable by a tenant user on `GET /api/v1/account/sites/{siteId}/delta/sync-state`,
  which is the one surface that exposes it — and the owner cannot request a rebuild at all, so the
  outcome and its time are the whole of what that projection owes them (`forAdmin` / `forOwner`
  replace `fromEntity`; the same rule keeps storage keys off the segment projection). `DEFERRED`
  gained a **second text**, because `BuildDeferredException` also carries a non-blocking probe and a
  bare interrupt, and telling those to raise `delta.checkpoint.fold-wait-seconds` prescribes a
  remedy for contention that did not happen — the split is `waitWasSpent()`, exactly the one
  `delta.checkpoint.builds.deferred` already makes. The queue-refusal text **quotes the refusal**
  instead of asserting "the queue was full", #171's lesson verbatim: `ThreadPoolTaskExecutor` raises
  `RejectedExecutionException` for "executor shutting down" too, and naming the wrong one sends an
  operator after a capacity problem during a routine rollout. And the "travels with the flag" rule
  was **wrong in one direction**: `resetForRebaseline` deletes every `checkpoints` row (#142), so a
  verdict about them describes nothing afterwards for exactly the reason the wipe drops it — both
  now clear it, while the flag stays a separate question that the re-baseline deliberately does not
  answer. One more, on the UI: only a forced rebuild ever writes a verdict, so a `FAILED` one used
  to paint a **permanent** critical chip that outlived every nightly build that had since succeeded
  — `lastCheckpointAt` later than `lastRebuildOutcomeAt` is exact evidence that the condition
  cleared, so the chip keeps its label, its time and its message and drops the colour.
  **Round 3** hardened the write and closed the exception the taxonomy had left open. The
  truncation bounded length but not **content**, which is the same failure it exists to prevent and
  worse: `recordRebuildOutcome` is called from a `finally`, so a value PostgreSQL refuses would lose
  the verdict *and* strand `rebuild_requested` with no task behind it — a `U+0000` quoted out of a
  row by a JDBC error is rejected outright, and a cut at a `char` boundary can leave an unpaired
  surrogate the driver's UTF-8 encoder rejects; control characters are replaced with spaces and the
  cut steps back off a high surrogate. The queue-refusal path is now **shutdown-aware**: it settled
  a `TaskRejectedException` as a terminal `FAILED` even when the refusal *was* the pod closing,
  losing exactly the request `resumePendingRebuilds()` exists to re-drive — the same #162 rule every
  other ending here follows, and it reads `ApplicationShutdownSignal` rather than the exception's
  text because the two cases say the same thing (this closed **#204**, filed in round 1 to defer
  it). Three smaller ones: the value list in the migration's `COMMENT ON COLUMN`, both `@Operation`
  descriptions and the frontend schema doc still named four of the six outcomes; the scheduler's new
  catch **does** change one thing the guide claimed it did not — retention no longer runs for a
  discarded site in that tick, which matches the read-denial and deferral branches and is a no-op
  today only because both triggers imply a reset that just zeroed the pointer, now said out loud and
  pinned by a `CheckpointSchedulerTest` case; and the mute is documented as the **one-way** signal
  it is — `lastCheckpointAt` moves only when a build advances the pointer, so an idle site whose
  nightly rematerialize repaired everything keeps its loud chip, and what clears a verdict is
  another rebuild, which is what the chip already asks for.
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
