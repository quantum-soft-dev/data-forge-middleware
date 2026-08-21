# GitHub Actions CI/CD

## Workflows

| File | Purpose |
|------|---------|
| `ci-cd.yml` | Tests + code quality. No deploys (AWS deploy + ghcr docker builds are dormant, kept for rollback). |
| `app-deploy.yml` | Builds images in GCP Artifact Registry and deploys to GKE (dev / stage / prod). |
| `infra-deploy.yml` | Infrastructure (Terraform) pipeline. |
| `strip-closed-status-labels.yml` | On `issues: closed` (and a weekly/`workflow_dispatch` sweep), removes every `status:*` label from a closed issue. Closed tickets keep no live status label (issue #257). |

## CI (`ci-cd.yml`)

- **Push** to `develop`, `main`, `release`, `feature/**`, `bugfix/**`, `hotfix/**`, `NNN/**` and **PRs** to `develop`/`main`/`release`.
- All branches/PRs: `backend-test` (full `./gradlew test` with Postgres/Redis/LocalStack services) + `frontend-test` (vitest).
- `develop`/`main`/`release` additionally run `code-quality` (checkstyle/SpotBugs, non-blocking) and `dependency-analysis`.
- **Dormant jobs** (`if: false`, kept for a quick AWS rollback): `build` (jar artifact), `docker-build` / `docker-build-frontend` (ghcr images), `deploy` (AWS ECS). GKE builds its own images, so these are not needed since the GKE migration (022).
- Required status check for PRs to `develop`: `backend-test`.

## Deploy (`app-deploy.yml`, GKE)

Merging to `develop` does **not** deploy — it only runs tests. Deploys are triggered per environment:

| Environment | Trigger | Cluster |
|-------------|---------|---------|
| dev | push tag `deploy-dev/*` (deploys the tagged commit) | bitbi-dev / dev-bitbi-cluster |
| stage | push to `stage` branch | bitbi-stage / stage-bitbi-cluster |
| prod | push to `main` branch | bitbi-production / prod-bitbi-cluster |
| any | `workflow_dispatch` (Actions → App Deploy (GKE) → Run workflow) | per input |

Deploy dev from the current commit:

```bash
TAG=deploy-dev/$(date +%Y%m%d-%H%M); git tag $TAG && git push origin $TAG
```

Tags are unique (timestamped), so the tag history is the dev deploy log. Concurrent deploys to the same environment are coalesced (`concurrency` + `cancel-in-progress`).

Guardrails: a `deploy-dev/*` tag must point at a commit reachable from `origin/develop` (checked before cloud auth); the `deploy-dev/*` namespace is admin-only via a repository tag ruleset; the deploy job is bound to GitHub Environments with deployment ref policies (dev ← `develop` branch or `deploy-dev/*` tag; stage ← `stage`; prod ← `main`). `workflow_dispatch` is subject to the same environment policies, so dev can also be deployed from `develop` HEAD without a tag — but not from feature branches.

The deploy job: builds backend + frontend images (`us-central1-docker.pkg.dev/<project>/forge-app/*`, tagged with the commit SHA), syncs `forge-secrets`, applies the kustomize overlay `k8s/overlays/<env>` and waits for rollout.

## Local gates (see CLAUDE.md)

```bash
./gradlew test -PexcludeIntegration   # per-task gate (pre-commit hook)
./gradlew integrationTest             # before-PR gate
./gradlew test                        # what CI runs
```
