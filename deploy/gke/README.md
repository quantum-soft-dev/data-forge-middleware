# GKE deployment (forge)

forge runs in the **`forge`** namespace across three clusters, mirroring bitbi
(`bitbi-dev` / `bitbi-stage` / `bitbi-production`). Branch → cluster:
`develop` → dev, `stage` → stage, `main` → prod.

Manifests live in [`k8s/`](../../k8s): `base/` + `overlays/{dev,stage,prod}` (kustomize).
Backend (`forge-backend`, 8080) and frontend (`forge-frontend`, 80) are separate Deployments;
dev additionally runs an in-cluster `forge-redis`. **Dev is internal-only** (ClusterIP, no public
ingress) — bitbi reaches forge at `http://forge-backend.forge.svc.cluster.local:8080`.

## What kustomize applies vs. what is provisioned out-of-band

`kubectl apply -k` creates: namespace, ServiceAccount, ConfigMaps, Deployments, Services, HPA, PDB,
and (dev) Redis. It does **not** create the `forge-secrets` Secret — that is synced separately so
secrets never live in git. The backend pod will stay `Pending`/`CrashLoop` until `forge-secrets`
exists.

## Prerequisites (provisioned via Terraform — see `infra/`)

- Artifact Registry repo `forge-app` in the target project.
- GCP SA `{env}-forge-app@{project}.iam.gserviceaccount.com` + Workload Identity binding to
  `serviceAccount:{project}.svc.id.goog[forge/forge-app]`.
- Cloud SQL database `dfm` + user `dfm-app` (dev: on `dev-bitbi-db`).
- GCS bucket `dfm-{env}-uploads` + HMAC keys for the S3-compatible client.
- (stage/prod) Memorystore Redis instance.

## Manual dev deploy

```bash
# 1) target the dev cluster
gcloud container clusters get-credentials dev-bitbi-cluster \
  --region us-central1 --project bitbi-dev

# 2) sync secrets (fill forge-secrets.dev.env from forge-secrets.example.env first)
./deploy/gke/sync-secrets.sh deploy/gke/forge-secrets.dev.env

# 3) set image tags and apply
cd k8s/overlays/dev
kustomize edit set image \
  us-central1-docker.pkg.dev/bitbi-dev/forge-app/backend=us-central1-docker.pkg.dev/bitbi-dev/forge-app/backend:<sha> \
  us-central1-docker.pkg.dev/bitbi-dev/forge-app/frontend=us-central1-docker.pkg.dev/bitbi-dev/forge-app/frontend:<sha>
kubectl apply -k .

# 4) watch rollout (first boot runs Flyway V1..V28 — allow several minutes)
kubectl -n forge rollout status deploy/forge-backend --timeout=600s

# 5) reach the admin UI without a public ingress
kubectl -n forge port-forward svc/forge-frontend 8081:80
```

In CI this is automated by `.github/workflows/app-deploy.yml` (Workload Identity Federation).

## Placeholders to fill before a real deploy

Overlay ConfigMaps contain `REPLACE_*` / `dev-dfm.us.auth0.com` placeholders for Auth0
(domain, SPA client id) and, for stage/prod, Memorystore IP and forge domain. Replace them with the
real values from the current forge AWS deployment / Auth0 tenant.
