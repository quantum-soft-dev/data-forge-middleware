#!/bin/bash

# Script to manually create S3 bucket in LocalStack
# Usage: ./scripts/create-s3-bucket.sh

set -e

BUCKET_NAME="data-forge-bucket"
ENDPOINT_URL="http://localhost:4566"
REGION="us-east-1"

echo "Creating S3 bucket in LocalStack..."
echo "Bucket: ${BUCKET_NAME}"
echo "Endpoint: ${ENDPOINT_URL}"
echo "Region: ${REGION}"
echo ""

# Check if LocalStack is running
if ! curl -f ${ENDPOINT_URL}/_localstack/health > /dev/null 2>&1; then
  echo "ERROR: LocalStack is not running!"
  echo "Start it with: docker-compose -f docker-compose.dev.yml up -d localstack"
  exit 1
fi

echo "LocalStack is running ✓"
echo ""

# Create bucket
echo "Creating bucket..."
aws --endpoint-url=${ENDPOINT_URL} s3 mb s3://${BUCKET_NAME} --region ${REGION} 2>&1 || echo "Bucket may already exist"

echo ""
echo "Verifying bucket..."
aws --endpoint-url=${ENDPOINT_URL} s3 ls s3://${BUCKET_NAME}

echo ""
echo "✓ S3 bucket setup complete!"
echo ""
echo "You can now upload files to: s3://${BUCKET_NAME}"
