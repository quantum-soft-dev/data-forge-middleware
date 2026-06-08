# CR: Migrate data-forge-middleware from AWS to GKE

**Feature:** `022-gke-migration` · **Branch:** `feature/022-gke-migration` · **Status:** in progress

## Background

`data-forge-middleware` (forge/DFM) is deployed on AWS ECS Fargate (`us-east-1`): backend +
frontend (nginx), RDS PostgreSQL, external Redis, S3 bucket `dfm-prod-uploads`, Auth0. The service
is consumed by **bitbi (DynamicVisualizer)**, which already runs on **GKE** using the model:
three separate GCP projects/clusters — `bitbi-dev` / `bitbi-stage` / `bitbi-production`, an app
namespace, kustomize overlays, Terraform infra, GitHub Actions + Workload Identity Federation.

## Goal

Move forge to the same model: three clusters, namespace **`forge`**, branch → cluster
(`develop`→dev, `stage`→stage, `main`→prod). Two stages:

1. **Dev migration** into the `forge` namespace of `dev-bitbi-cluster`, testing, reconnect bitbi-dev.
2. **Prod cutover** into `prod-bitbi-cluster`.

## Agreed decisions

| Topic | Decision |
|---|---|
| Object storage | Migrate to **GCS** via the S3-compatible XML API (S3 client code change) |
| DB/cache (dev) | Reuse Cloud SQL `dev-bitbi-db` (new `dfm` database) + **in-cluster Redis** |
| Data | Copy prod data (pg_dump RDS→Cloud SQL, objects S3→GCS) |
| Automation | Full pipeline: Terraform + GitHub Actions + WIF |
| Ingress (dev) | **Internal-only** (ClusterIP); bitbi → `forge-backend.forge.svc.cluster.local:8080`; admin UI via port-forward |
| Terraform state | Shared `bitbi-terraform-state`, prefix `forge/environments/{env}` |

## Changes

### Stage 1 — GCS support (S3-compatibility) ✅

The production S3 beans are now configuration-driven — the same `prod` profile works with both AWS S3
and GCS, differing only by env vars. No SDK bump (2.28.11 does not yet send default flexible
checksums); GCS mode is enabled via the legacy `serviceConfiguration`.

- `upload/infrastructure/S3Configuration.java` — new properties (`s3.endpoint`, `s3.access-key`,
  `s3.secret-key`, `s3.path-style-access-enabled`, `s3.checksum.enabled`); helpers
  `resolveCredentials` (static HMAC ↔ IAM default), `shouldOverrideEndpoint`, `serviceConfiguration`
  (for GCS: `pathStyleAccessEnabled(true)`, `chunkedEncodingEnabled(false)`, `checksumValidationEnabled(false)`).
- `resources/application.yml` — `path-style-access-enabled` and `checksum.enabled` are now env-driven.
- Test `upload/infrastructure/S3ConfigurationTest.java` — covers credential selection,
  endpoint-override decision, and the service-config toggles.

**GCS env (GKE):** `AWS_S3_ENDPOINT=https://storage.googleapis.com`, `S3_PATH_STYLE_ACCESS=true`,
`S3_CHECKSUM_ENABLED=false`, `AWS_S3_REGION=auto`, `AWS_ACCESS_KEY_ID/SECRET=<GCS HMAC>`,
`AWS_S3_BUCKET_NAME=dfm-dev-uploads`. AWS mode: keep the defaults (endpoint `s3.amazonaws.com`,
checksum on, path-style off).

### Stage 2 — infra (Terraform), k8s (kustomize), CI/CD (GitHub Actions + WIF) ✅ (artifacts)

- **k8s/** — kustomize `base` + `overlays/{dev,stage,prod}`: `forge-backend`/`forge-frontend`,
  HPA, PDB, WI ServiceAccount, ConfigMaps. Dev is internal-only + in-cluster `forge-redis`. All
  overlays pass `kubectl kustomize`. Helpers in `deploy/gke/`.
- **infra/** — `modules/forge-app` + `environments/{dev,stage,prod}` (lean: reuses bitbi clusters/
  VPCs/Cloud SQL instances; creates AR repo, SA+WI, `dfm` DB + user, GCS bucket + HMAC, Memorystore
  for stage/prod). State shares `bitbi-terraform-state` (`forge/environments/{env}`).
  `terraform validate` is green; dev `terraform plan` = 10 to add, 0 change, 0 destroy.
- **CI/CD** — `.github/workflows/app-deploy.yml` (build backend+frontend → push AR → sync
  `forge-secrets` → kustomize apply → rollout) and `infra-deploy.yml` (Terraform plan/apply via WIF).
  Branch → env: `develop`→dev, `stage`→stage, `main`→prod.

**Secret mapping** (existing forge GitHub secrets → GKE needs, from `deploy-script/config/dev.json.template`):

| Existing secret | GKE target | Note |
|---|---|---|
| `APP_DB_PASSWORD` | `DB_PASSWORD` (k8s) + `TF_VAR_db_password` | password for app user `dfm-app` |
| `JWT_SECRET` | `JWT_SECRET` | — |
| `AUTH0_CLIENT_ID` | `AUTH0_MGMT_CLIENT_ID` | in forge this is the **Management API** client id |
| `AUTH0_CLIENT_SECRET` | `AUTH0_MGMT_CLIENT_SECRET` | Management API secret |
| `PLUGIN_BITBI_CLIENT_ID` | `PLUGIN_BITBI_CLIENT_ID` | — |
| `DB_MASTER_PASSWORD` | (Stage 3) `pg_dump` from AWS RDS | user `bitbi` |
| `REDIS_PASSWORD` | — | not needed (GKE Redis has no AUTH) |
| `GHCR_PAT` | — | not needed (images → Artifact Registry) |

**Additional GitHub secrets required:** `GCP_WIF_PROVIDER`, `GCP_WIF_SA` (Workload Identity
Federation), `GCS_HMAC_ACCESS_ID`, `GCS_HMAC_SECRET` (from `terraform output` after apply).

**Non-secret dev values** are already in the overlay (Auth0 domain `dev-dfm.us.auth0.com`, audience
`https://dev-dfm.bitbi.io`, claims namespace `https://dev.dfm.bitbi.io`, SPA client
`2sTGyEnKDASQFT2qVbYHQeUpROOTvCJ9`). In GKE-dev `DB_USERNAME=dfm-app` (Cloud SQL), not `dmf` as on AWS.

### Stage 3 — data migration and bitbi-dev reconnect

pg_dump RDS→Cloud SQL/`dfm`, S3→GCS rsync; switch bitbi-dev `DFM_*` to the new forge endpoint.

## Risks

- **Checksum/chunked-encoding** — without disabling them GCS rejects PutObject (solved via
  `S3_CHECKSUM_ENABLED=false`).
- **Path-style** is mandatory for GCS; the presigner must use the same endpoint/creds (otherwise 403).
- Verification against real GCS happens only at deploy time (LocalStack tests don't cover GCS specifics).
