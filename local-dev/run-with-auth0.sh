#!/usr/bin/env bash
# Run the backend locally (dev profile) against a REAL Auth0 tenant.
# Reads local-dev/auth0.env (copy it from auth0.env.example and fill in first).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
ENV_FILE="${AUTH0_ENV_FILE:-$HERE/auth0.env}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE — copy local-dev/auth0.env.example to it and fill in your tenant." >&2
  exit 1
fi

# shellcheck disable=SC1090
source "$ENV_FILE"
: "${AUTH0_DOMAIN:?set AUTH0_DOMAIN in $ENV_FILE}"
: "${AUTH0_MGMT_CLIENT_ID:?set AUTH0_MGMT_CLIENT_ID in $ENV_FILE}"

export SPRING_PROFILES_ACTIVE=dev
echo "→ bootRun (dev) with Auth0 domain=$AUTH0_DOMAIN  issuer=${AUTH0_ISSUER:-<unset!>}"
cd "$ROOT"
exec ./gradlew bootRun
