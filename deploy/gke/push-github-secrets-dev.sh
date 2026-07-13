#!/usr/bin/env bash
#
# push-github-secrets-dev.sh — set the 9 GitHub Actions secrets that app-deploy.yml
# and infra-deploy.yml need, sourcing values from local state, never printing them.
#
# Sources of truth:
#   GCP_WIF_PROVIDER / GCP_WIF_SA  <- output of setup-wif-dev.sh (hard-coded below)
#   APP_DB_PASSWORD / JWT_SECRET   <- /tmp/forge-dev-gen.env (prepare-infra-dev.sh)
#   AUTH0_* / GCS_HMAC_* / PLUGIN  <- the LIVE k8s secret forge-secrets (current
#                                     source of truth, so CI deploys match runtime)
#
# Requires: gh (authenticated), kubectl (context on dev-bitbi-cluster).
#   bash deploy/gke/push-github-secrets-dev.sh
#
set -euo pipefail

REPO="quantum-soft-dev/data-forge-middleware"
GEN_ENV="/tmp/forge-dev-gen.env"
NS="forge"

# From setup-wif-dev.sh output:
WIF_PROVIDER="projects/904971396915/locations/global/workloadIdentityPools/github-actions/providers/github-forge"
WIF_SA="forge-deployer@bitbi-dev.iam.gserviceaccount.com"

# ── helpers ───────────────────────────────────────────────────────────────────
gen_val()  { grep -E "^$1=" "$GEN_ENV" | head -1 | cut -d= -f2-; }
k8s_val()  { kubectl -n "$NS" get secret forge-secrets -o jsonpath="{.data.$1}" | base64 -d; }
set_secret() {  # $1 = secret name, $2 = value (via stdin, not argv)
  if [ -z "${2:-}" ]; then echo "  ! $1: EMPTY — skipped"; return; fi
  printf '%s' "$2" | gh secret set "$1" -R "$REPO" >/dev/null
  echo "  ✓ $1"
}

[ -f "$GEN_ENV" ] || { echo "ABORT: $GEN_ENV missing — run prepare-infra-dev.sh first"; exit 1; }
kubectl -n "$NS" get secret forge-secrets >/dev/null 2>&1 || { echo "ABORT: forge-secrets not in cluster"; exit 1; }

echo "Setting 9 GitHub secrets on $REPO ..."

# (1)(2) WIF
set_secret GCP_WIF_PROVIDER "$WIF_PROVIDER"
set_secret GCP_WIF_SA       "$WIF_SA"

# (3)(4) from gen.env — must match what Cloud SQL currently expects
set_secret APP_DB_PASSWORD  "$(gen_val DB_PASSWORD)"
set_secret JWT_SECRET       "$(gen_val JWT_SECRET)"

# (5)(6) Auth0 Management M2M — mapped to AUTH0_MGMT_* in the workflow
set_secret AUTH0_CLIENT_ID     "$(k8s_val AUTH0_MGMT_CLIENT_ID)"
set_secret AUTH0_CLIENT_SECRET "$(k8s_val AUTH0_MGMT_CLIENT_SECRET)"

# (7)(8) GCS HMAC (user-account HMAC) — stored as AWS_* in the cluster secret
set_secret GCS_HMAC_ACCESS_ID "$(k8s_val AWS_ACCESS_KEY_ID)"
set_secret GCS_HMAC_SECRET    "$(k8s_val AWS_SECRET_ACCESS_KEY)"

# (9) plugin
set_secret PLUGIN_BITBI_CLIENT_ID "$(k8s_val PLUGIN_BITBI_CLIENT_ID)"

echo "Done. Verify: gh secret list -R $REPO"