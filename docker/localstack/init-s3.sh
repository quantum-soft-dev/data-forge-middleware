#!/bin/bash

# Init script for LocalStack S3 bucket creation
# This script runs automatically when LocalStack is ready

BUCKET_NAME="dfm-uploads"

echo "Initializing S3 bucket: $BUCKET_NAME"

# Check if bucket exists
if awslocal s3 ls "s3://$BUCKET_NAME" 2>&1 | grep -q 'NoSuchBucket'; then
    echo "Bucket does not exist. Creating..."
    awslocal s3 mb "s3://$BUCKET_NAME"
    echo "Bucket created successfully"
else
    echo "Bucket already exists"
fi

# List all buckets
echo "Available S3 buckets:"
awslocal s3 ls

echo "S3 bucket initialization complete"
