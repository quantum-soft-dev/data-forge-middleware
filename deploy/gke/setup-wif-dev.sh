#!/usr/bin/env bash
#
# setup-wif-dev.sh — one-time Workload Identity Federation setup so GitHub Actions
# (app-deploy.yml / infra-deploy.yml) can authenticate to GCP for the forge dev env.
#
# Reuses the EXISTING bitbi WIF pool `github-actions` in bitbi-dev and adds:
#   - a forge-scoped OIDC provider `github-forge` (gated to THIS repo only, so the
#     existing bitbi `github` provider is left untouched)
#   - a dedicated deployer SA `forge-deployer@bitbi-dev` (least privilege)
#   - the IAM roles the two workflows need
#   - a workloadIdentityUser binding so the repo can impersonate the SA
#
# Idempotent: safe to re-run. Prints the two values you must store as GitHub
# secrets (GCP_WIF_PROVIDER, GCP_WIF_SA) at the end.
#
# Run as an operator with IAM admin on bitbi-dev (e.g. devops@bitbiapp.com):
#   bash deploy/gke/setup-wif-dev.sh
#
set -euo pipefail

PROJECT="bitbi-dev"
PROJECT_NUMBER="904971396915"
POOL="github-actions"                 # existing pool — do NOT recreate
PROVIDER="github-forge"               # new, forge-scoped provider
SA_NAME="forge-deployer"
SA_EMAIL="${SA_NAME}@${PROJECT}.iam.gserviceaccount.com"
REPO="quantum-soft-dev/data-forge-middleware"
STATE_BUCKET="bitbi-terraform-state"  # in bitbi-production (Terraform remote state)

# Set to "true" to also grant the broad roles needed by infra-deploy.yml
# (Terraform: manages SAs, IAM, Cloud SQL, GCS). app-deploy.yml does NOT need these.
GRANT_INFRA_ROLES="${GRANT_INFRA_ROLES:-false}"

echo "=================================================================="
echo " forge dev WIF setup   project=$PROJECT repo=$REPO"
echo "=================================================================="

# ── 1. Deployer service account ───────────────────────────────────────────────
if gcloud iam service-accounts describe "$SA_EMAIL" --project="$PROJECT" >/dev/null 2>&1; then
  echo "[1/5] SA $SA_EMAIL already exists"
else
  echo "[1/5] creating SA $SA_EMAIL"
  gcloud iam service-accounts create "$SA_NAME" --project="$PROJECT" \
    --display-name="forge GitHub Actions deployer (dev)"
fi

# ── 2. Roles for app-deploy.yml (build+push images, deploy to GKE, read Cloud SQL) ──
echo "[2/5] granting app-deploy roles"
for ROLE in \
  roles/artifactregistry.writer \
  roles/container.developer \
  roles/cloudsql.viewer ; do
  gcloud projects add-iam-policy-binding "$PROJECT" \
    --member="serviceAccount:${SA_EMAIL}" --role="$ROLE" \
    --condition=None --quiet >/dev/null
  echo "        + $ROLE"
done

# ── 3. (optional) Broad roles for infra-deploy.yml (Terraform) ────────────────
if [ "$GRANT_INFRA_ROLES" = "true" ]; then
  echo "[3/5] granting infra-deploy (Terraform) roles — BROAD"
  for ROLE in \
    roles/iam.serviceAccountAdmin \
    roles/resourcemanager.projectIamAdmin \
    roles/cloudsql.admin \
    roles/storage.admin \
    roles/artifactregistry.admin ; do
    gcloud projects add-iam-policy-binding "$PROJECT" \
      --member="serviceAccount:${SA_EMAIL}" --role="$ROLE" \
      --condition=None --quiet >/dev/null
    echo "        + $ROLE"
  done
  # Terraform remote state lives in a bucket in bitbi-production (cross-project).
  echo "        + objectAdmin on gs://${STATE_BUCKET} (cross-project TF state)"
  gcloud storage buckets add-iam-policy-binding "gs://${STATE_BUCKET}" \
    --member="serviceAccount:${SA_EMAIL}" --role="roles/storage.objectAdmin" --quiet >/dev/null
else
  echo "[3/5] skipping infra-deploy roles (GRANT_INFRA_ROLES=false) — app-deploy only"
fi

# ── 4. Forge-scoped OIDC provider under the existing pool ─────────────────────
if gcloud iam workload-identity-pools providers describe "$PROVIDER" \
     --project="$PROJECT" --location=global --workload-identity-pool="$POOL" >/dev/null 2>&1; then
  echo "[4/5] provider $PROVIDER already exists"
else
  echo "[4/5] creating OIDC provider $PROVIDER (gated to $REPO)"
  gcloud iam workload-identity-pools providers create-oidc "$PROVIDER" \
    --project="$PROJECT" --location=global --workload-identity-pool="$POOL" \
    --display-name="GitHub OIDC (forge)" \
    --issuer-uri="https://token.actions.githubusercontent.com" \
    --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.actor=assertion.actor" \
    --attribute-condition="attribute.repository == '${REPO}'"
fi

# ── 5. Let the repo impersonate the deployer SA ───────────────────────────────
echo "[5/5] binding workloadIdentityUser for repo $REPO"
PRINCIPAL="principalSet://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${POOL}/attribute.repository/${REPO}"
gcloud iam service-accounts add-iam-policy-binding "$SA_EMAIL" --project="$PROJECT" \
  --role="roles/iam.workloadIdentityUser" --member="$PRINCIPAL" --quiet >/dev/null

PROVIDER_RESOURCE="projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${POOL}/providers/${PROVIDER}"

echo "=================================================================="
echo " WIF READY — store these as GitHub repo secrets:"
echo "   GCP_WIF_PROVIDER = ${PROVIDER_RESOURCE}"
echo "   GCP_WIF_SA       = ${SA_EMAIL}"
echo ""
echo " Next: bash deploy/gke/push-github-secrets-dev.sh"
echo "=================================================================="
