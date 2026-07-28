# GitHub Actions CI/CD

## Workflows

| File | Purpose |
|------|---------|
| `ci-cd.yml` | Tests + code quality. No deploys (AWS deploy + ghcr docker builds are dormant, kept for rollback). |
| `app-deploy.yml` | Builds images in GCP Artifact Registry and deploys to GKE (dev / stage / prod). |
| `infra-deploy.yml` | Infrastructure (Terraform) pipeline. |

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

The deploy job: builds backend + frontend images (`us-central1-docker.pkg.dev/<project>/forge-app/*`, tagged with the commit SHA), syncs `forge-secrets`, applies the kustomize overlay `k8s/overlays/<env>` and waits for rollout.

## Local gates (see CLAUDE.md)

```bash
./gradlew test -PexcludeIntegration   # per-task gate (pre-commit hook)
./gradlew integrationTest             # before-PR gate
./gradlew test                        # what CI runs
```
