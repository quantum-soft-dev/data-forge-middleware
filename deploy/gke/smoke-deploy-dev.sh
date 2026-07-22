#!/usr/bin/env bash
# One-shot SECRET-LESS smoke deploy of forge to the dev cluster (bitbi-dev / dev-bitbi-cluster).
#
# Uses EPHEMERAL generated secrets (DB password, JWT) + GCS HMAC keys + plugin dummy value.
# Auth0 Management creds: the backend eagerly fetches a Management API token at boot and the
# context FAILS if it's invalid — so AUTH0_MGMT_CLIENT_ID/SECRET must be REAL. Put them in
# /tmp/forge-dev-gen.env (sourced below); they fall back to dummy values only, which will
# crash-loop the backend. Validates: Terraform infra, image build/push to Artifact Registry,
# Cloud SQL connectivity + Flyway, GCS via S3-compat, in-cluster Redis, rollout and health.
# Bit BI plugin features are NOT exercised (dummy PLUGIN_BITBI_CLIENT_ID).
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

# ── 2) Terraform apply (AR repo, SA+WI, dfm DB+user, GCS bucket; no SA HMAC) ───
echo "[2/6] terraform apply (dev) ..."
export TF_VAR_db_password="$DB_PASSWORD"
terraform -chdir=infra/environments/dev init -input=false -reconfigure >/tmp/forge-tf-init.log 2>&1
terraform -chdir=infra/environments/dev apply -input=false -auto-approve
BUCKET="$(terraform -chdir=infra/environments/dev output -raw uploads_bucket)"

# GCS credentials: a USER-account HMAC key (org policy blocks SA HMAC).
# Create one in the Cloud Console (project bitbi-dev):
#   Cloud Storage → Settings → Interoperability → "Access keys for your user account" → Create key
# then add to /tmp/forge-dev-gen.env:   HMAC_ID=GOOG...   HMAC_SECRET=...
if [ -z "${HMAC_ID:-}" ] || [ -z "${HMAC_SECRET:-}" ]; then
  echo "ERROR: HMAC_ID / HMAC_SECRET missing in $GEN."
  echo "       Create a user-account HMAC key (Console → Cloud Storage → Settings →"
  echo "       Interoperability → 'Access keys for your user account' → Create key),"
  echo "       then append to $GEN:"
  echo "         HMAC_ID=GOOG..."
  echo "         HMAC_SECRET=..."
  echo "       and re-run this script. (Infra is already applied; bucket: $BUCKET)"
  exit 1
fi
echo "      HMAC access id: ${HMAC_ID:0:6}…  bucket: $BUCKET"

# ── 3) Build & push images ────────────────────────────────────────────────────
echo "[3/6] build & push images ..."
gcloud auth configure-docker us-central1-docker.pkg.dev --quiet
docker build --target production -t "${BACKEND_IMG}:${TAG}" -t "${BACKEND_IMG}:latest" .
docker build -t "${FRONTEND_IMG}:${TAG}" -t "${FRONTEND_IMG}:latest" frontend
docker push "${BACKEND_IMG}:${TAG}";  docker push "${BACKEND_IMG}:latest"
docker push "${FRONTEND_IMG}:${TAG}"; docker push "${FRONTEND_IMG}:latest"

# Capture the immutable pushed digests. The dev overlay pins :latest, so applying it does
# NOT roll the deployment when the tag string is unchanged — and a cached rebuild can even
# produce a byte-identical image under a new tag. Deploying by @sha256 digest below pins the
# EXACT artifact we just pushed, guaranteeing a fresh rollout + pull regardless of caching.
BACKEND_REF="$(docker inspect --format='{{index .RepoDigests 0}}' "${BACKEND_IMG}:${TAG}")"
FRONTEND_REF="$(docker inspect --format='{{index .RepoDigests 0}}' "${FRONTEND_IMG}:${TAG}")"
echo "      backend  -> ${BACKEND_REF}"
echo "      frontend -> ${FRONTEND_REF}"

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
  --from-literal=AUTH0_MGMT_CLIENT_ID="${AUTH0_MGMT_CLIENT_ID:-smoke-dummy-mgmt-client-id}" \
  --from-literal=AUTH0_MGMT_CLIENT_SECRET="${AUTH0_MGMT_CLIENT_SECRET:-smoke-dummy-mgmt-secret}" \
  --from-literal=AWS_ACCESS_KEY_ID="${HMAC_ID}" \
  --from-literal=AWS_SECRET_ACCESS_KEY="${HMAC_SECRET}" \
  --from-literal=SPRING_DATA_REDIS_PASSWORD="" \
  --from-literal=PLUGIN_BITBI_CLIENT_ID="smoke-dummy-plugin-client-id" \
  -n forge --dry-run=client -o yaml | kubectl apply -f -

# ── 5) Deploy, then pin the exact pushed digests ──────────────────────────────
echo "[5/6] kubectl apply -k k8s/overlays/dev (then pin @digest) ..."
kubectl apply -k k8s/overlays/dev
kubectl -n forge set image deploy/forge-backend  backend="${BACKEND_REF}"
kubectl -n forge set image deploy/forge-frontend frontend="${FRONTEND_REF}"

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
