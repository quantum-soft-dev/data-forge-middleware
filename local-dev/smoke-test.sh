#!/usr/bin/env bash
# Smoke-test the local Delta Client v2 gRPC server (feature 022).
# Mints a bearer token and calls GetSyncState for the seeded site.
#
# Prereqs:
#   - app running with the Delta gRPC server (port 9090) — see local-dev/README.md
#   - site seeded (local-dev/seed-delta-site.sql)
#   - grpcurl + python3 on PATH
#
# Env overrides: GRPC_HOST, SITE_ID, ACCOUNT_ID, JWT_SECRET
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
HOST="${GRPC_HOST:-localhost:9090}"
SITE_ID="${SITE_ID:-0de17a00-0000-4000-8000-0000000000a1}"

TOKEN="$(SITE_ID="$SITE_ID" python3 "$HERE/mint-token.py")"
echo "→ GetSyncState @ $HOST (site=$SITE_ID)"

grpcurl -plaintext \
  -import-path "$ROOT/src/main/proto" -proto delta-ingestion.proto \
  -H "authorization: Bearer ${TOKEN}" \
  -d "{\"site_id\":\"${SITE_ID}\"}" \
  "$HOST" com.bitbi.dfm.delta.v2.DeltaIngestion/GetSyncState
