#!/usr/bin/env bash
# Ensure the localstack S3 bucket the backend expects exists (idempotent).
#
# The backend writes uploads + Delta v2 changelog segments / checkpoints to S3,
# and S3HealthIndicator turns /actuator/health red (503) if the bucket is missing.
# docker/localstack/init-s3.sh is supposed to create it on a fresh localstack start,
# but a persisted/reused container can come up without it — run this to be sure.
#
# Env overrides: AWS_S3_BUCKET_NAME (default dfm-uploads), LOCALSTACK_CONTAINER (default dfm-localstack)
set -euo pipefail

BUCKET="${AWS_S3_BUCKET_NAME:-dfm-uploads}"
CONTAINER="${LOCALSTACK_CONTAINER:-dfm-localstack}"

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "✗ localstack container '$CONTAINER' is not running — start it: docker compose up -d localstack" >&2
  exit 1
fi

if docker exec "$CONTAINER" awslocal s3 ls "s3://$BUCKET" >/dev/null 2>&1; then
  echo "✓ bucket s3://$BUCKET already exists"
else
  docker exec "$CONTAINER" awslocal s3 mb "s3://$BUCKET"
  echo "✓ created bucket s3://$BUCKET"
fi
