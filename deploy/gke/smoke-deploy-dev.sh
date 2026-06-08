#!/usr/bin/env bash
# One-shot SECRET-LESS smoke deploy of forge to the dev cluster (bitbi-dev / dev-bitbi-cluster).
#
# Uses EPHEMERAL generated secrets (DB password, JWT) + Terraform-generated GCS HMAC keys
# + dummy Auth0-management / plugin values. Validates: Terraform infra, image build/push to
# Artifact Registry, Cloud SQL connectivity + Flyway, GCS via S3-compat, in-cluster Redis,
# rollout and health. Auth0-management and Bit BI plugin features are NOT exercised (dummy creds).
#
# Fully reversible:  terraform -chdir=infra/environments/dev destroy   &&   kubectl delete ns forge
#
# Run from the repo root:  bash deploy/gke/smoke-deploy-dev.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

PROJECT=bitbi-dev
REGION=us-central1
CLUSTER=dev-bitbi-cluster
DB_INSTANCE=dev-bitbi-db
BACKEND_IMG="us-central1-docker.pkg.dev/${PROJECT}/forge-app/backend"
FRONTEND_IMG="us-central1-docker.pkg.dev/${PROJECT}/forge-app/frontend"
TAG="$(git rev-parse --short HEAD)"
GEN=/tmp/forge-dev-gen.env

export GOOGLE_OAUTH_ACCESS_TOKEN="$(gcloud auth print-access-token)"

echo "==================================================================="
echo " forge dev smoke deploy   project=$PROJECT cluster=$CLUSTER tag=$TAG"
echo "==================================================================="

# ── 1) Ephemeral secrets ──────────────────────────────────────────────────────
if [ ! -f "$GEN" ]; then
  {
    echo "DB_PASSWORD=$(openssl rand -hex 16)"
    echo "JWT_SECRET=$(openssl rand -hex 32)"
  } > "$GEN"
  echo "[1/6] generated ephemeral secrets -> $GEN"
else
  echo "[1/6] reusing existing ephemeral secrets -> $GEN"
fi
# shellcheck disable=SC1090
source "$GEN"

# ── 2) Terraform apply (AR repo, SA+WI, dfm DB+user, GCS bucket+HMAC) ──────────
echo "[2/6] terraform apply (dev) ..."
export TF_VAR_db_password="$DB_PASSWORD"
terraform -chdir=infra/environments/dev init -input=false -reconfigure >/tmp/forge-tf-init.log 2>&1
terraform -chdir=infra/environments/dev apply -input=false -auto-approve

HMAC_ID="$(terraform -chdir=infra/environments/dev output -raw gcs_hmac_access_id)"
HMAC_SECRET="$(terraform -chdir=infra/environments/dev output -raw gcs_hmac_secret)"
BUCKET="$(terraform -chdir=infra/environments/dev output -raw uploads_bucket)"
echo "      HMAC access id: ${HMAC_ID:0:6}…  bucket: $BUCKET"

# ── 3) Build & push images ────────────────────────────────────────────────────
echo "[3/6] build & push images ..."
gcloud auth configure-docker us-central1-docker.pkg.dev --quiet
docker build --target production -t "${BACKEND_IMG}:${TAG}" -t "${BACKEND_IMG}:latest" .
docker build -t "${FRONTEND_IMG}:${TAG}" -t "${FRONTEND_IMG}:latest" frontend
docker push "${BACKEND_IMG}:${TAG}";  docker push "${BACKEND_IMG}:latest"
docker push "${FRONTEND_IMG}:${TAG}"; docker push "${FRONTEND_IMG}:latest"

# ── 4) Target the cluster + create forge-secrets ──────────────────────────────
echo "[4/6] kube credentials + forge-secrets ..."
gcloud container clusters get-credentials "$CLUSTER" --region "$REGION" --project "$PROJECT"
PRIVATE_IP="$(gcloud sql instances describe "$DB_INSTANCE" --project "$PROJECT" --format='value(ipAddresses[0].ipAddress)')"
kubectl get namespace forge >/dev/null 2>&1 || kubectl create namespace forge
kubectl create secret generic forge-secrets \
  --from-literal=DB_URL="jdbc:postgresql://${PRIVATE_IP}:5432/dfm" \
  --from-literal=DB_USERNAME="dfm-app" \
  --from-literal=DB_PASSWORD="${DB_PASSWORD}" \
  --from-literal=JWT_SECRET="${JWT_SECRET}" \
  --from-literal=AUTH0_MGMT_CLIENT_ID="smoke-dummy-mgmt-client-id" \
  --from-literal=AUTH0_MGMT_CLIENT_SECRET="smoke-dummy-mgmt-secret" \
  --from-literal=AWS_ACCESS_KEY_ID="${HMAC_ID}" \
  --from-literal=AWS_SECRET_ACCESS_KEY="${HMAC_SECRET}" \
  --from-literal=SPRING_DATA_REDIS_PASSWORD="" \
  --from-literal=PLUGIN_BITBI_CLIENT_ID="smoke-dummy-plugin-client-id" \
  -n forge --dry-run=client -o yaml | kubectl apply -f -

# ── 5) Deploy (overlay references :latest) ────────────────────────────────────
echo "[5/6] kubectl apply -k k8s/overlays/dev ..."
kubectl apply -k k8s/overlays/dev

# ── 6) Wait for rollout + health ──────────────────────────────────────────────
echo "[6/6] waiting for rollout (Flyway V1..V28 on first boot — allow several minutes) ..."
kubectl -n forge rollout status deploy/forge-redis    --timeout=120s || true
kubectl -n forge rollout status deploy/forge-backend  --timeout=600s
kubectl -n forge rollout status deploy/forge-frontend --timeout=300s

echo "==================================================================="
echo " DONE. Pods:"
kubectl -n forge get pods -o wide
echo
echo " Health (backend):"
kubectl -n forge exec deploy/forge-backend -- sh -c 'wget -qO- http://localhost:8080/actuator/health || true' 2>/dev/null || \
  echo "  (use: kubectl -n forge port-forward deploy/forge-backend 8080:8080  then curl /actuator/health)"
echo "==================================================================="
