#!/usr/bin/env bash
# Create/replace the `forge-secrets` Kubernetes Secret in the `forge` namespace
# from a dotenv file. Idempotent (apply via dry-run).
#
# Usage: ./sync-secrets.sh <env-file>
#   e.g. ./sync-secrets.sh forge-secrets.dev.env
#
# Assumes the current kubectl context targets the intended cluster.
set -euo pipefail

ENV_FILE="${1:?usage: sync-secrets.sh <env-file>}"
NAMESPACE="forge"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "error: env file not found: $ENV_FILE" >&2
  exit 1
fi

# Ensure namespace exists (no-op if already applied via kustomize).
kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 || kubectl create namespace "$NAMESPACE"

# Build --from-literal args from non-comment, non-empty lines.
ARGS=()
while IFS= read -r line; do
  [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
  ARGS+=("--from-literal=${line}")
done < "$ENV_FILE"

kubectl create secret generic forge-secrets \
  "${ARGS[@]}" \
  --namespace "$NAMESPACE" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "forge-secrets synced to namespace/$NAMESPACE ($(kubectl config current-context))"
