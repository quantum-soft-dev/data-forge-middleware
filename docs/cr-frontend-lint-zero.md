# CR — Frontend ESLint to zero, and into the gates

**Issue:** [#77](https://github.com/quantum-soft-dev/data-forge-middleware/issues/77)
**Branch:** `issue-77` → `develop`
**Status:** implemented

## Context

PR #76 (ESLint 10 bump) revealed that the linter had **never run** in this
repository: there was no config at all, `npm run lint` failed with "ESLint
couldn't find a configuration file", and nothing — CI, pre-commit hook,
Dockerfile — invoked it. The major bump required a flat config, so one was
added; on never-linted code it reported **105 problems (74 errors / 31
warnings)**.

This change works that backlog down to **0 errors / 0 warnings** and makes lint
a gate so it stays there.

## What the linter actually found

Two findings were real defects, not style:

1. **Route guards remounted their subtree on every render.**
   `AuthenticationGuard` and `UserOnlyGuard` called
   `withAuthenticationRequired(component, …)` *while rendering*. The HOC returns
   a new component type per call, so React unmounted and remounted the whole
   protected page on every render of the guard, resetting its state
   (`react-hooks/static-components`). Both guards now inline the SDK's check —
   `useAuth0()` + `loginWithRedirect()` — the way `RoleGuard` already did.

2. **A dead link hidden by a cast.** `ComparisonListWidget` navigated to
   `` `/comparisons/${id}` as any ``. That route does not exist; every other
   caller uses `/account/comparisons/{id}`. The `as any` was there purely to
   silence the router's typed-path error (`@typescript-eslint/no-explicit-any`).

Two more were latent correctness risks:

3. **Hooks called conditionally.** `SiteList` and `CreateSiteForm` picked their
   admin/user mutation with `accountId ? useAdminX(accountId) : null`, making
   hook order depend on a prop (`react-hooks/rules-of-hooks`). Both hooks are
   now called unconditionally.

4. **A stale dependency.** `DeviceVerifyPage`'s URL-sync effect compared against
   a `userCode` it had left out of its dependency array.

## Changes by category

| Rule | Count | Resolution |
|---|---|---|
| `react-hooks/rules-of-hooks` | 4 | Call both hook variants, select the result |
| `react-hooks/static-components` | 2 | Guards inline `useAuth0` instead of building an HOC in render |
| `react-hooks/set-state-in-effect` | 7 | Derive during render (lazy initializer / prev-value adjust) |
| `react-hooks/exhaustive-deps` | 1 | Gone with the effect it belonged to |
| `@typescript-eslint/no-unused-vars` | 21 | `_name` convention taught to the rule; the rest deleted |
| `@typescript-eslint/no-explicit-any` | 29 | `getServerErrorMessage` / new `getServerErrorStatus`; typed claims and forms |
| `preserve-caught-error` | 5 | `new Error(msg, { cause: error })` (needs `ES2022.Error` in tsconfig `lib`) |
| `react-refresh/only-export-components` | 22 | Context/hook split out of the provider module; shadcn helpers allow-listed; route table exempt |
| `react-hooks/preserve-manual-memoization` | 3 | Optional-chained deps hoisted into locals |
| `react-hooks/incompatible-library` | 6 | Rule off — see below |
| `import/no-relative-packages` | 2 | Stale `eslint-disable` for a rule this config never defined |
| unused disable directives | 2 | Removed |

### Deliberate suppressions

Three, all at config level with the reason in `frontend/eslint.config.js`:

- **`react-hooks/incompatible-library`: off.** React Compiler is not enabled in
  this build (`vite.config.ts` runs `@vitejs/plugin-react` without
  `babel-plugin-react-compiler`). The rule only reports that the compiler *would
  skip* components using `react-hook-form`'s `watch()` or TanStack
  table/virtual instances — third-party APIs we cannot change. Turn it back on
  if the compiler is ever switched on.
- **`react-refresh/only-export-components`: `allowExportNames`** for
  `badgeVariants`, `buttonVariants`, `tableCellNumeric` — shadcn primitives ship
  their styling helper next to the component.
- **`react-refresh/only-export-components`: off for `src/app/router.tsx`** — the
  route table declares one lazy page component per route beside the router it
  exports.

### Derived state instead of effects

The seven `set-state-in-effect` findings were all "copy a prop or a query result
into local state", which costs an extra render pass showing the stale value.
They now use one of two patterns:

- lazy `useState` initializer, where the source is read once on mount
  (`SessionExpiredBanner`);
- adjust-during-render keyed on the last value seen (`useAuth`,
  `AdminSettingsPage`, `SiteListItem`, `DeviceVerifyPage`) — the pattern React
  documents for "adjusting state when a prop changes".

`useAuth` additionally ignores a token response that arrives after the session
has flipped, which the previous version would have applied to the new session.

## Tests

Test-first per task; new coverage for the code whose behaviour moved:

- `SiteList.test.tsx` — admin vs. user endpoint selection (new)
- `AuthenticationGuard` / `UserOnlyGuard` — protected subtree survives a parent
  re-render, anonymous users are redirected with the current path (rewritten +
  new)
- `AdminSettingsPage.test.tsx` — cron editor seeding and saving (new)
- `DeviceVerifyPage.test.tsx` — all page-state transitions (new)
- `useAuth` — role extraction, anonymous state, session end (extended)
- `SiteListItem` — retention draft reset vs. preservation (extended)

Totals moved from 105 files / 1001 passed to **110 files / 1026 passed**
(19 skipped, unchanged).

## Gate decision

Lint is now part of the gates — that was the point of getting to zero:

- **pre-commit** (`.githooks/pre-commit`): `npm --prefix frontend run lint` runs
  alongside `tsc --noEmit` and vitest when anything under `frontend/` is staged.
- **CI** (`.github/workflows/ci-cd.yml`, `frontend-test` job): a `Lint frontend`
  step before the tests.

`npm run lint` runs with `--max-warnings 0`, so a warning fails the build the
same as an error. `--report-unused-disable-directives` keeps suppressions from
outliving their finding.
