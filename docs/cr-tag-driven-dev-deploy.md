# CR: Tag-driven dev deploys (decouple deploy from merges to develop)

**Status:** implemented · **Date:** 2026-07-28 · **Scope:** CI/CD only (no app code changes)

## Problem

Since the GKE migration (022), every push to `develop` triggered `app-deploy.yml`: a full
image build and a rollout of the **dev** environment. With frequent feature merges this meant:

- constant redeploys/restarts of the dev stand (`test.dfm.bitbi.io`), interrupting manual
  testing and killing live Delta v2 gRPC sessions of the Windows client;
- a full Docker build + rollout burned CI minutes on every merge, even for merges nobody
  wanted deployed yet.

On top of that, `ci-cd.yml` still built and pushed backend/frontend images to **ghcr** on every
push to `develop` — a leftover from the AWS pipeline, unused since GKE builds its own images in
Artifact Registry.

## Options considered

1. **Intermediate `integration` branch** (features → integration, periodic merge → develop
   triggers deploy) — rejected: adds a two-step landing ritual and effectively just renames
   branches; the deploy cadence problem is a *trigger* problem, not a branch-topology problem.
2. **Manual-only deploys** (`workflow_dispatch`) — viable, kept as a secondary path.
3. **Tag-driven deploys** — **chosen**: explicit, git-native, leaves an audit trail of what was
   deployed and when, and can target any commit (including rollbacks to an older commit).

## New model

Merging to `develop` runs **tests only** (plus code-quality). Deploys are per-environment:

| Environment | Trigger |
|---|---|
| dev | push a `deploy-dev/*` tag — deploys **the tagged commit** |
| stage | push to `stage` branch (unchanged) |
| prod | push to `main` branch (unchanged) |
| any | `workflow_dispatch` on *App Deploy (GKE)* (unchanged) |

Deploy dev from the current commit:

```bash
TAG=deploy-dev/$(date +%Y%m%d-%H%M); git tag $TAG && git push origin $TAG
```

Conventions:

- Tag names are unique (timestamped), so `git tag -l 'deploy-dev/*'` is the dev deploy log.
- Rollback = tag an older commit.
- The `concurrency` group maps every `deploy-dev/*` ref to the single group `forge-app-dev`
  (unique tag names would otherwise defeat coalescing); `cancel-in-progress` stays on.
- GitHub Actions does **not** apply `paths` filters to tag pushes — a tag always deploys,
  which is the intended semantics (you asked for it explicitly).

## Changes

- `.github/workflows/app-deploy.yml`
  - `develop` removed from `on.push.branches`; `on.push.tags: ["deploy-dev/*"]` added.
  - `Resolve environment` maps `deploy-dev/*` → `dev` (the `develop` branch mapping is gone).
  - Concurrency group expression resolves `deploy-dev/*` refs to `dev`.
- `.github/workflows/ci-cd.yml`
  - Jobs `build` (jar artifact), `docker-build` and `docker-build-frontend` (ghcr images) set to
    dormant (`if: false`), mirroring the dormant AWS `deploy` job. They only fed the disabled AWS
    pipeline; restore their original `if:` conditions (kept in comments) to roll back to AWS.
  - Test jobs are unchanged: `backend-test` remains the required PR check.
- `.github/workflows/README.md` — rewritten to describe the current pipelines (was stale:
  Docker Hub secrets, staging-on-main).

## Security hardening (PR #69 review)

Making a wildcard tag a credential-bearing trigger widens the attack surface; three measures
address the review findings:

1. **Shell injection via ref name** — git allows `$(...)` in ref names, and `${{ github.ref_name }}`
   is textual substitution into the `run:` script. With fixed branch names this was unreachable;
   with `deploy-dev/*` a tag like `deploy-dev/$(cmd)` would execute after WIF auth. Fixed: the ref
   reaches the shell only via `env:` variables (`$REF_NAME`), never `${{ }}` interpolation.
2. **Tag ancestry guard** — before cloud auth, a tag-triggered run verifies the tagged commit is
   reachable from `origin/develop` (`git merge-base --is-ancestor`), so a tag on an unreviewed
   commit (e.g. with a modified workflow) fails before any credentials are minted.
3. **Environment binding + tag ruleset** (repo settings, not workflow code) — the deploy job is
   bound to GitHub Environments with deployment ref policies: dev ← `develop` branch or
   `deploy-dev/*` tag, stage ← `stage`, prod ← `main`; refs outside the policy (including
   `workflow_dispatch` from feature branches) are rejected at the environment gate. The
   `deploy-dev/*` tag namespace itself is restricted by a repository ruleset
   (creation/update/deletion limited to repo admins).

## Rollback

To restore auto-deploy of dev on every merge: re-add `develop` to `on.push.branches` and the
`develop) ENV=dev ;;` case in `app-deploy.yml`. The tag trigger can coexist with it.
