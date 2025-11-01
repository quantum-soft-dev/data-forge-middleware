#!/bin/bash

# LocalStack S3 Bucket Initialization Script
# This script creates the required S3 bucket for dfm-uploads

set -e

echo "Waiting for LocalStack to be ready..."
until curl -f http://localhost:4566/_localstack/health > /dev/null 2>&1; do
  echo "LocalStack is unavailable - sleeping"
  sleep 2
done

echo "LocalStack is ready!"

# Create S3 bucket
BUCKET_NAME="dfm-uploads"
REGION="us-east-1"

echo "Creating S3 bucket: ${BUCKET_NAME}"
awslocal s3 mb s3://${BUCKET_NAME} --region ${REGION} || echo "Bucket already exists"

# Verify bucket was created
echo "Verifying bucket exists..."
awslocal s3 ls s3://${BUCKET_NAME}

echo "S3 bucket initialization complete!"
