# forge infrastructure (Terraform)

Lean per-environment infra for `data-forge-middleware`. forge **reuses** bitbi's existing GKE
clusters, VPCs and Cloud SQL instances — this Terraform only provisions the forge-specific
resources via [`modules/forge-app`](modules/forge-app):

- Artifact Registry repo `forge-app`
- Workload-Identity service account `{env}-forge-app` (+ `cloudsql.client`, `secretmanager.secretAccessor`, WI binding to `forge/forge-app`)
- Cloud SQL database `dfm` + user `dfm-app` on the existing shared instance
- GCS bucket `dfm-{env}-uploads` + HMAC keys (for the S3-compatible object-storage client)
- Memorystore Redis (stage/prod only; dev uses in-cluster Redis)

## Layout

```
infra/
  modules/forge-app/        # the reusable module
  environments/dev/         # bitbi-dev,        instance dev-bitbi-db,   no Memorystore
  environments/stage/       # bitbi-stage,      instance stage-bitbi-db, Memorystore BASIC
  environments/prod/        # bitbi-production, instance prod-bitbi-db,  Memorystore STANDARD_HA
```

Remote state is the shared `gs://bitbi-terraform-state` bucket, prefix `forge/environments/{env}`.

## Usage

```bash
cd infra/environments/dev
export TF_VAR_db_password='<forge dfm-app password>'
terraform init
terraform plan
terraform apply
```

`db_password` is the only required sensitive variable; set it via `TF_VAR_db_password`
(GitHub Actions secret in CI), never in tfvars.

## Outputs to wire into the deploy

- `app_service_account_email` → matches the K8s ServiceAccount annotation in `k8s/overlays/{env}`.
- `gcs_hmac_access_id` / `gcs_hmac_secret` (sensitive) → `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` in `forge-secrets`.
- `uploads_bucket` → `AWS_S3_BUCKET_NAME` in `forge-config`.
- `redis_host` (stage/prod) → `SPRING_DATA_REDIS_HOST` in the overlay configmap.
