# LocalStack Initialization Scripts

## Overview
This directory contains initialization scripts that run automatically when LocalStack starts.

## init-s3.sh
Creates the `dfm-uploads` S3 bucket required by the Data Forge Middleware application.

### How it works
- LocalStack automatically executes scripts from `/etc/localstack/init/ready.d/` when the service is ready
- The script checks if the bucket exists before creating it (idempotent)
- Works with `PERSISTENCE: 1` enabled - won't fail if bucket already exists

### Important: Windows Users
**The script MUST use Unix line endings (LF), not Windows (CRLF).**

If you edit the script on Windows and get errors like `$'\r': command not found`, convert it:
```bash
# Using dos2unix
dos2unix docker/localstack/init-s3.sh

# Or using sed
sed -i 's/\r$//' docker/localstack/init-s3.sh
```

### Testing
To verify the bucket was created:
```bash
# Using AWS CLI
aws --endpoint-url=http://localhost:4566 s3 ls

# Using awslocal (if installed)
awslocal s3 ls

# Check LocalStack logs
docker logs dfm-localstack | grep -A 5 "Initializing S3 bucket"
```

### Manual bucket creation (if needed)
```bash
aws --endpoint-url=http://localhost:4566 s3 mb s3://dfm-uploads
```
