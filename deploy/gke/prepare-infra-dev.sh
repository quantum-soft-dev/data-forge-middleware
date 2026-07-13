#!/usr/bin/env bash
# Prepare the forge DEV infrastructure foundation — NO image build, NO app secrets, NO deploy.
#
# Stands up only the durable, idempotent base so a later smoke/CI deploy has somewhere to land:
#   • Terraform (project bitbi-dev): Artifact Registry repo, app service account + Workload
#     Identity binding + IAM (Cloud SQL client, Secret accessor), Cloud SQL `dfm` DB + `dfm-app`
#     user, GCS uploads bucket + IAM.  (SA-HMAC and Memorystore are disabled for dev.)
#   • Kubernetes `forge` namespace on dev-bitbi-cluster.
#
# It does NOT build/push images or create the `forge-secrets` Secret — those need the
# user-account HMAC and real Auth0 creds and are handled by smoke-deploy-dev.sh later.
# The generated dfm-app DB password is persisted to /tmp/forge-dev-gen.env so the later
# deploy reuses the SAME password (Terraform would otherwise reset it on the next apply).
#
# Reversible:  terraform -chdir=infra/environments/dev destroy   &&   kubectl delete ns forge
#
# Run from the repo root:  bash deploy/gke/prepare-infra-dev.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

PROJECT=bitbi-dev
REGION=us-central1
CLUSTER=dev-bitbi-cluster
GEN=/tmp/forge-dev-gen.env

export GOOGLE_OAUTH_ACCESS_TOKEN="$(gcloud auth print-access-token)"

echo "==================================================================="
echo " forge dev INFRA prep   project=$PROJECT cluster=$CLUSTER"
echo "==================================================================="

# ── 1) dfm-app DB password (persisted so a later deploy matches) ───────────────
if [ ! -f "$GEN" ]; then
  {
    echo "DB_PASSWORD=$(openssl rand -hex 16)"
    echo "JWT_SECRET=$(openssl rand -hex 32)"
  } > "$GEN"
  echo "[1/3] generated ephemeral secrets -> $GEN"
else
  echo "[1/3] reusing existing ephemeral secrets -> $GEN"
fi
# shellcheck disable=SC1090
source "$GEN"

# ── 2) Terraform apply (GCP foundation only) ──────────────────────────────────
echo "[2/3] terraform apply (dev) ..."
export TF_VAR_db_password="$DB_PASSWORD"
terraform -chdir=infra/environments/dev init -input=false -reconfigure >/tmp/forge-tf-init.log 2>&1
terraform -chdir=infra/environments/dev apply -input=false -auto-approve
BUCKET="$(terraform -chdir=infra/environments/dev output -raw uploads_bucket)"
SA_EMAIL="$(terraform -chdir=infra/environments/dev output -raw app_service_account_email)"
AR_REPO="$(terraform -chdir=infra/environments/dev output -raw artifact_registry_repo)"

# ── 3) Kubernetes namespace ───────────────────────────────────────────────────
echo "[3/3] kube credentials + namespace ..."
gcloud container clusters get-credentials "$CLUSTER" --region "$REGION" --project "$PROJECT"
kubectl apply -f k8s/base/namespace.yaml

echo "==================================================================="
echo " INFRA READY"
echo "   service account : $SA_EMAIL"
echo "   artifact repo   : $AR_REPO"
echo "   uploads bucket  : $BUCKET"
echo "   namespace       : $(kubectl get ns forge -o jsonpath='{.metadata.name}')"
echo
echo " Next: create a user-account HMAC (Console → Cloud Storage → Settings →"
echo "       Interoperability → 'Access keys for your user account'), append"
echo "       HMAC_ID / HMAC_SECRET to $GEN, then run:"
echo "         bash deploy/gke/smoke-deploy-dev.sh"
echo "==================================================================="
