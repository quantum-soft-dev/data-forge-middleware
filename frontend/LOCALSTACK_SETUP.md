# LocalStack S3 Setup Guide

This guide explains how to set up LocalStack S3 for development.

## Automatic Setup (Recommended)

The S3 bucket is **created automatically** when you start LocalStack with docker-compose:

```bash
docker-compose -f docker-compose.dev.yml up -d
```

The `docker/localstack/init-s3.sh` script runs automatically and creates the `dfm-uploads` bucket.

## Verify Bucket Creation

Check if the bucket was created:

```bash
aws --endpoint-url=http://localhost:4566 s3 ls s3://dfm-uploads
```

Or list all buckets:

```bash
aws --endpoint-url=http://localhost:4566 s3 ls
```

## Manual Setup (If Needed)

If the automatic setup didn't work, you can manually create the bucket:

### Option 1: Use the script

```bash
./scripts/create-s3-bucket.sh
```

### Option 2: AWS CLI command

```bash
aws --endpoint-url=http://localhost:4566 s3 mb s3://dfm-uploads --region us-east-1
```

## Configuration Details

- **Bucket Name**: `dfm-uploads`
- **Region**: `us-east-1`
- **Endpoint**: `http://localhost:4566`
- **Service**: S3 (LocalStack)

## Application Configuration

The backend is already configured to use LocalStack in `application-dev.yml`:

```yaml
aws:
  s3:
    endpoint: http://localhost:4566
    region: us-east-1
    bucket-name: dfm-uploads
    access-key: test
    secret-key: test
```

## Troubleshooting

### LocalStack not starting

Check logs:
```bash
docker logs dfm-localstack
```

### Bucket not created

1. Check if LocalStack is healthy:
```bash
curl http://localhost:4566/_localstack/health
```

2. Manually run the init script:
```bash
docker exec dfm-localstack /etc/localstack/init/ready.d/init-s3.sh
```

3. Or use the manual creation script:
```bash
./scripts/create-s3-bucket.sh
```

### AWS CLI not configured

If you get "Unable to locate credentials":
```bash
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1
```

Or use `awslocal` (LocalStack wrapper):
```bash
pip install awscli-local
awslocal s3 ls s3://dfm-uploads
```

## Testing File Upload

Test uploading a file:

```bash
echo "test content" > test.txt
aws --endpoint-url=http://localhost:4566 s3 cp test.txt s3://dfm-uploads/test.txt
```

List uploaded files:

```bash
aws --endpoint-url=http://localhost:4566 s3 ls s3://dfm-uploads/
```

## Clean Up

Remove all data (including bucket):

```bash
docker-compose -f docker-compose.dev.yml down -v
```

Next start will recreate everything automatically.

## Further Reading

- [LocalStack Documentation](https://docs.localstack.cloud/)
- [LocalStack S3 Documentation](https://docs.localstack.cloud/user-guide/aws/s3/)
- [AWS CLI with LocalStack](https://docs.localstack.cloud/user-guide/integrations/aws-cli/)
