# LocalStack S3 Configuration

This directory contains initialization scripts for LocalStack S3 service.

## Automatic Bucket Creation

The `init-s3.sh` script automatically creates the required S3 bucket when LocalStack starts.

### How it works

1. LocalStack mounts the script to `/etc/localstack/init/ready.d/`
2. Scripts in this directory run automatically after LocalStack is ready
3. The script creates the `dfm-uploads` bucket in `us-east-1` region

### Bucket Details

- **Bucket Name**: `dfm-uploads`
- **Region**: `us-east-1`
- **Endpoint**: `http://localhost:4566`

## Manual Verification

Check if bucket exists:
```bash
aws --endpoint-url=http://localhost:4566 s3 ls s3://dfm-uploads
```

List all buckets:
```bash
aws --endpoint-url=http://localhost:4566 s3 ls
```

## Manual Creation (if needed)

If you need to manually create the bucket:
```bash
aws --endpoint-url=http://localhost:4566 s3 mb s3://dfm-uploads --region us-east-1
```

## Troubleshooting

View LocalStack logs:
```bash
docker logs dfm-localstack
```

Check if init script ran:
```bash
docker exec dfm-localstack cat /etc/localstack/init/ready.d/init-s3.sh
```

Test LocalStack health:
```bash
curl http://localhost:4566/_localstack/health
```
