# Quickstart Guide: Data Forge Middleware

**Purpose**: Validate implementation by running end-to-end scenarios from feature specification

---

## Prerequisites

- Java 21 JDK installed
- Docker and Docker Compose running
- Gradle 9.0+ installed
- `curl` or Postman for API testing

---

## Local Development Setup

### 1. Start Infrastructure Services

```bash
# Start PostgreSQL 16, LocalStack S3, and Keycloak
docker-compose up -d

# Verify services are running
docker-compose ps

# Expected output:
# - postgres:16 on port 5432
# - localstack/localstack on port 4566 (S3-compatible)
# - quay.io/keycloak/keycloak on port 8080
```

### 2. Configure Application

Edit `src/main/resources/application-dev.yml`:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/dfm
    username: postgres
    password: postgres

s3:
  endpoint: http://localhost:4566  # LocalStack
  bucket-name: data-forge-dev-bucket
  access-key: test
  secret-key: test

keycloak:
  realm: dfm
  auth-server-url: http://localhost:8080
  resource: dfm-backend
  credentials:
    secret: dev-client-secret
```

### 3. Initialize Database

```bash
# Run Flyway migrations (automatic on first startup)
./gradlew flywayMigrate -Pprofile=dev

# Verify tables created
psql -h localhost -U postgres -d dfm -c "\dt"

# Expected tables:
# - accounts
# - sites
# - batches
# - uploaded_files
# - error_logs_2025_10 (partitioned)
# - flyway_schema_history
```

### 4. Seed Test Data (Optional)

```bash
# Create admin account in Keycloak
docker exec -it keycloak /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8080 --realm master --user admin --password admin

docker exec -it keycloak /opt/keycloak/bin/kcadm.sh create users -r dfm \
  -s username=admin -s enabled=true

docker exec -it keycloak /opt/keycloak/bin/kcadm.sh set-password -r dfm \
  --username admin --new-password admin123

docker exec -it keycloak /opt/keycloak/bin/kcadm.sh add-roles -r dfm \
  --uusername admin --rolename ADMIN
```

### 5. Run Application

```bash
# Start Spring Boot application
./gradlew bootRun --args='--spring.profiles.active=dev'

# Application starts on http://localhost:8080
# OpenAPI docs available at http://localhost:8080/swagger-ui.html
# Actuator health at http://localhost:8080/actuator/health
```

---

## Acceptance Scenario Validation

### Scenario 1: Client Authentication and Token Acquisition

**Given**: A registered site with valid domain and secret credentials

```bash
# Step 1: Create account via admin API (requires Keycloak admin token)
ADMIN_TOKEN=$(curl -X POST http://localhost:8080/realms/dfm/protocol/openid-connect/token \
  -d "client_id=dfm-backend" \
  -d "client_secret=dev-client-secret" \
  -d "grant_type=password" \
  -d "username=admin" \
  -d "password=admin123" | jq -r '.access_token')

ACCOUNT_ID=$(curl -X POST http://localhost:8080/admin/accounts \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","name":"Test User"}' | jq -r '.id')

echo "Account created: $ACCOUNT_ID"

# Step 2: Create site for account
SITE_RESPONSE=$(curl -X POST http://localhost:8080/admin/accounts/$ACCOUNT_ID/sites \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"domain":"store-01.example.com","displayName":"Test Store"}')

SITE_ID=$(echo $SITE_RESPONSE | jq -r '.id')
CLIENT_SECRET=$(echo $SITE_RESPONSE | jq -r '.clientSecret')

echo "Site created: $SITE_ID with secret: $CLIENT_SECRET"
```

**When**: The client requests authentication

```bash
# Step 3: Authenticate using domain:clientSecret
BASIC_AUTH=$(echo -n "store-01.example.com:$CLIENT_SECRET" | base64)

JWT_RESPONSE=$(curl -X POST http://localhost:8080/api/v1/auth/token \
  -H "Authorization: Basic $BASIC_AUTH")

JWT_TOKEN=$(echo $JWT_RESPONSE | jq -r '.token')
EXPIRES_IN=$(echo $JWT_RESPONSE | jq -r '.expiresIn')

echo "JWT token obtained, expires in: $EXPIRES_IN seconds"
```

**Then**: The system issues a time-limited token

```bash
# Verify token is valid and contains expected claims
echo $JWT_TOKEN | cut -d'.' -f2 | base64 -d | jq '.'
# Expected output includes: siteId, accountId, domain, iat, exp

# Verify expiration is 24 hours (86400 seconds)
test $EXPIRES_IN -eq 86400 && echo "✓ Token expiration correct" || echo "✗ Token expiration incorrect"
```

---

### Scenario 2: Batch Upload Lifecycle

**Given**: An authenticated client needs to upload files

```bash
# Step 1: Start new batch
BATCH_RESPONSE=$(curl -X POST http://localhost:8080/api/v1/batch/start \
  -H "Authorization: Bearer $JWT_TOKEN")

BATCH_ID=$(echo $BATCH_RESPONSE | jq -r '.batchId')
echo "Batch started: $BATCH_ID"
```

**When**: A new batch is started

```bash
# Verify batch is in IN_PROGRESS status
BATCH_DETAILS=$(curl -X GET http://localhost:8080/admin/batches/$BATCH_ID \
  -H "Authorization: Bearer $ADMIN_TOKEN")

BATCH_STATUS=$(echo $BATCH_DETAILS | jq -r '.status')
test "$BATCH_STATUS" == "IN_PROGRESS" && echo "✓ Batch status correct" || echo "✗ Batch status incorrect"

# Verify S3 path format: {accountId}/{domain}/{YYYY-MM-DD}/{HH-MM}/
S3_PATH=$(echo $BATCH_DETAILS | jq -r '.s3Path')
echo "S3 path: $S3_PATH"
```

**Then**: The system creates a unique batch identifier and dedicated storage location

```bash
# Verify S3 directory exists in LocalStack
aws --endpoint-url=http://localhost:4566 s3 ls s3://data-forge-dev-bucket/$S3_PATH

# Attempt to start another batch (should fail with 409 Conflict)
CONFLICT_RESPONSE=$(curl -X POST http://localhost:8080/api/v1/batch/start \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -w "%{http_code}" -o /dev/null -s)

test $CONFLICT_RESPONSE -eq 409 && echo "✓ Duplicate batch prevented" || echo "✗ Duplicate batch not prevented"
```

---

### Scenario 3: File Upload Operations

**Given**: An active batch exists

```bash
# Create test files
echo "sales,amount,date" | gzip > sales.csv.gz
echo "product,inventory" | gzip > inventory.csv.gz
```

**When**: One or more compressed CSV files are uploaded

```bash
# Upload files to batch
UPLOAD_RESPONSE=$(curl -X POST http://localhost:8080/api/v1/batch/$BATCH_ID/upload \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -F "files=@sales.csv.gz" \
  -F "files=@inventory.csv.gz")

UPLOADED_COUNT=$(echo $UPLOAD_RESPONSE | jq -r '.uploadedFiles')
echo "Uploaded $UPLOADED_COUNT files"

# Verify each file metadata
echo $UPLOAD_RESPONSE | jq '.files[] | {fileName, fileSize, uploadedAt}'
```

**Then**: Files are stored with metadata tracking and checksum verification

```bash
# Verify files exist in S3 (LocalStack)
aws --endpoint-url=http://localhost:4566 s3 ls s3://data-forge-dev-bucket/$S3_PATH

# Expected output:
# sales.csv.gz
# inventory.csv.gz

# Verify uploaded_files records in database
psql -h localhost -U postgres -d dfm -c \
  "SELECT original_file_name, file_size, checksum FROM uploaded_files WHERE batch_id = '$BATCH_ID';"
```

**Edge Case**: Duplicate filename

```bash
# Attempt to upload same file again (should fail with 400 Bad Request)
DUPLICATE_RESPONSE=$(curl -X POST http://localhost:8080/api/v1/batch/$BATCH_ID/upload \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -F "files=@sales.csv.gz" \
  -w "%{http_code}" -o /dev/null -s)

test $DUPLICATE_RESPONSE -eq 400 && echo "✓ Duplicate file rejected" || echo "✗ Duplicate file accepted"
```

---

### Scenario 4: Batch Completion

**Given**: Files are uploaded to an active batch

```bash
# Verify batch has uploaded files
BATCH_BEFORE=$(curl -X GET http://localhost:8080/admin/batches/$BATCH_ID \
  -H "Authorization: Bearer $ADMIN_TOKEN")

echo "Files before completion: $(echo $BATCH_BEFORE | jq -r '.uploadedFilesCount')"
```

**When**: All uploads complete successfully

```bash
# Complete the batch
COMPLETE_RESPONSE=$(curl -X POST http://localhost:8080/api/v1/batch/$BATCH_ID/complete \
  -H "Authorization: Bearer $JWT_TOKEN")

COMPLETED_AT=$(echo $COMPLETE_RESPONSE | jq -r '.completedAt')
FINAL_STATUS=$(echo $COMPLETE_RESPONSE | jq -r '.status')
FINAL_COUNT=$(echo $COMPLETE_RESPONSE | jq -r '.uploadedFilesCount')
TOTAL_SIZE=$(echo $COMPLETE_RESPONSE | jq -r '.totalSize')

echo "Batch completed at: $COMPLETED_AT"
echo "Final status: $FINAL_STATUS"
echo "Total files: $FINAL_COUNT"
echo "Total size: $TOTAL_SIZE bytes"
```

**Then**: The client can mark the batch as completed

```bash
# Verify status is COMPLETED
test "$FINAL_STATUS" == "COMPLETED" && echo "✓ Batch completed successfully" || echo "✗ Batch not completed"

# Verify cannot upload more files after completion
UPLOAD_AFTER_COMPLETE=$(curl -X POST http://localhost:8080/api/v1/batch/$BATCH_ID/upload \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -F "files=@sales.csv.gz" \
  -w "%{http_code}" -o /dev/null -s)

test $UPLOAD_AFTER_COMPLETE -eq 400 && echo "✓ Upload after completion rejected" || echo "✗ Upload after completion accepted"
```

---

### Scenario 5: Error Logging and Tracking

**Given**: A client encounters an error during file processing

```bash
# Submit error log related to batch
ERROR_RESPONSE=$(curl -X POST http://localhost:8080/api/v1/error/$BATCH_ID \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "FileReadError",
    "title": "Failed to read DBF file",
    "message": "File data.dbf is corrupted and cannot be read",
    "stackTrace": "com.bitbi.client.FileReadException: ...",
    "clientVersion": "1.0.0",
    "metadata": {
      "fileName": "data.dbf",
      "fileSize": 1048576,
      "encoding": "CP866"
    }
  }' \
  -w "%{http_code}" -o /dev/null -s)

test $ERROR_RESPONSE -eq 204 && echo "✓ Error logged successfully" || echo "✗ Error logging failed"
```

**When**: Error details are submitted

```bash
# Verify error is recorded in database
psql -h localhost -U postgres -d dfm -c \
  "SELECT type, title, batch_id FROM error_logs WHERE batch_id = '$BATCH_ID';"
```

**Then**: The system records the error with full context and batch is flagged

```bash
# Verify batch has hasErrors = true
BATCH_WITH_ERROR=$(curl -X GET http://localhost:8080/admin/batches/$BATCH_ID \
  -H "Authorization: Bearer $ADMIN_TOKEN")

HAS_ERRORS=$(echo $BATCH_WITH_ERROR | jq -r '.hasErrors')
test "$HAS_ERRORS" == "true" && echo "✓ Batch flagged with errors" || echo "✗ Batch not flagged"

# Query errors via admin API
ERRORS=$(curl -X GET "http://localhost:8080/admin/errors?batchId=$BATCH_ID" \
  -H "Authorization: Bearer $ADMIN_TOKEN")

echo "Errors for batch:"
echo $ERRORS | jq '.content[] | {type, title, occurredAt}'
```

---

### Scenario 6: Automatic Batch Timeout Handling

**Given**: A batch has been in progress beyond the timeout period

```bash
# Create new batch and leave it in IN_PROGRESS
TIMEOUT_BATCH=$(curl -X POST http://localhost:8080/api/v1/batch/start \
  -H "Authorization: Bearer $JWT_TOKEN" | jq -r '.batchId')

echo "Created batch for timeout test: $TIMEOUT_BATCH"

# Update batch's startedAt to simulate expired batch (direct DB update for testing)
psql -h localhost -U postgres -d dfm -c \
  "UPDATE batches SET started_at = NOW() - INTERVAL '65 minutes' WHERE id = '$TIMEOUT_BATCH';"
```

**When**: The scheduled cleanup task runs

```bash
# Trigger batch timeout scheduler manually (or wait for scheduled execution)
# Note: Actual trigger depends on implementation (manual endpoint or @Scheduled cron)

# For testing, call internal scheduler or wait for cron execution
# Example: curl -X POST http://localhost:8080/internal/batch/timeout-check

# Alternatively, wait for scheduled task (runs every 5 minutes)
echo "Waiting for scheduled timeout check (5 minutes)..."
# sleep 300
```

**Then**: Expired batches are automatically marked as incomplete

```bash
# Verify batch status changed to NOT_COMPLETED
TIMEOUT_STATUS=$(curl -X GET http://localhost:8080/admin/batches/$TIMEOUT_BATCH \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq -r '.status')

test "$TIMEOUT_STATUS" == "NOT_COMPLETED" && echo "✓ Batch marked as NOT_COMPLETED" || echo "✗ Batch timeout not handled"
```

---

## Health Check Validation

```bash
# General health check
curl http://localhost:8080/actuator/health | jq '.'

# Expected output:
# {"status":"UP","components":{"db":{"status":"UP"},"s3":{"status":"UP"}}}

# Database health
curl http://localhost:8080/actuator/health/db | jq '.'

# S3 health
curl http://localhost:8080/actuator/health/s3 | jq '.'

# Metrics
curl http://localhost:8080/actuator/metrics | jq '.names'

# Expected metrics include:
# - batch.started.total
# - batch.completed.total
# - files.uploaded.total
# - upload.duration.seconds
```

---

## Performance Validation

Test NFR-001 to NFR-004 (API response time < 1000ms p95):

```bash
# Benchmark authentication endpoint
ab -n 100 -c 10 \
  -H "Authorization: Basic $(echo -n "store-01.example.com:$CLIENT_SECRET" | base64)" \
  http://localhost:8080/api/v1/auth/token

# Check p95 latency in output (should be < 1000ms)

# Benchmark batch start
ab -n 100 -c 10 \
  -H "Authorization: Bearer $JWT_TOKEN" \
  http://localhost:8080/api/v1/batch/start

# Benchmark admin queries
ab -n 100 -c 10 \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8080/admin/accounts?page=0&size=20
```

---

## Cleanup

```bash
# Stop application
# Ctrl+C in terminal running bootRun

# Stop Docker services
docker-compose down -v

# Remove test files
rm sales.csv.gz inventory.csv.gz
```

---

## Success Criteria

- ✅ All 6 acceptance scenarios complete without errors
- ✅ Health checks return UP status
- ✅ Database schema matches migrations (5 tables + partitions)
- ✅ S3 files uploaded and retrievable
- ✅ JWT authentication works for client and admin endpoints
- ✅ API response times < 1000ms p95 (non-upload operations)
- ✅ Error logs stored with batch association
- ✅ Batch timeout handled automatically
- ✅ Metrics collected for batch/upload/error events

**Status**: ✅ Ready for TDD implementation following quickstart validation tests
