# Quickstart Guide: Additions to BACKEND

**Feature**: DTO Response Standardization & Dual Authentication
**Date**: 2025-10-09
**Purpose**: Verify the feature implementation through end-to-end testing

## Prerequisites

- Java 21 installed
- Docker running (for PostgreSQL and LocalStack)
- Keycloak instance configured (for OAuth2 testing)
- Valid site credentials for JWT generation

## Setup

### 1. Start Infrastructure

```bash
# Start PostgreSQL and LocalStack
docker-compose up postgres localstack -d

# Wait for services to be ready
docker-compose logs -f postgres localstack
# Press Ctrl+C when "database system is ready" appears

# Create S3 bucket in LocalStack
aws --endpoint-url=http://localhost:4566 s3 mb s3://dataforge-uploads
```

### 2. Run Database Migrations

```bash
./gradlew flywayMigrate
```

### 3. Start Application

```bash
./gradlew bootRun --args='--spring.profiles.active=dev'
```

Wait for application to start (look for "Started DataForgeMiddlewareApplication").

## Test Scenarios

### Scenario 1: DTO Response Structure (FR-001, FR-002, FR-003)

**Goal**: Verify all endpoints return structured DTOs instead of Map<String, Object>

```bash
# Generate JWT token for testing
export JWT_TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/token \
  -u "testdomain:test-client-secret" \
  --data-urlencode "domain=testdomain" | jq -r '.token')

# Start a batch
BATCH_RESPONSE=$(curl -s -X POST http://localhost:8080/api/v1/batch/start \
  -H "Authorization: Bearer $JWT_TOKEN")

echo "Batch Response:"
echo $BATCH_RESPONSE | jq

# Extract batch ID
BATCH_ID=$(echo $BATCH_RESPONSE | jq -r '.id')

# Verify all required fields present
echo $BATCH_RESPONSE | jq -e '.id' > /dev/null && echo "✓ id field present"
echo $BATCH_RESPONSE | jq -e '.batchId' > /dev/null && echo "✓ batchId field present"
echo $BATCH_RESPONSE | jq -e '.siteId' > /dev/null && echo "✓ siteId field present"
echo $BATCH_RESPONSE | jq -e '.status' > /dev/null && echo "✓ status field present"
echo $BATCH_RESPONSE | jq -e '.s3Path' > /dev/null && echo "✓ s3Path field present"
echo $BATCH_RESPONSE | jq -e '.uploadedFilesCount' > /dev/null && echo "✓ uploadedFilesCount field present"
echo $BATCH_RESPONSE | jq -e '.totalSize' > /dev/null && echo "✓ totalSize field present"
echo $BATCH_RESPONSE | jq -e '.hasErrors' > /dev/null && echo "✓ hasErrors field present"
echo $BATCH_RESPONSE | jq -e '.startedAt' > /dev/null && echo "✓ startedAt field present"

# Verify field types
echo $BATCH_RESPONSE | jq -e '.id | type == "string"' > /dev/null && echo "✓ id is string (UUID)"
echo $BATCH_RESPONSE | jq -e '.uploadedFilesCount | type == "number"' > /dev/null && echo "✓ uploadedFilesCount is number"
echo $BATCH_RESPONSE | jq -e '.hasErrors | type == "boolean"' > /dev/null && echo "✓ hasErrors is boolean"
```

**Expected Result**: All field presence and type checks pass ✓

---

### Scenario 2: Dual Authentication on GET Endpoints (FR-005)

**Goal**: Verify GET endpoints accept both JWT and Keycloak tokens

#### Test 2a: GET with JWT token

```bash
# Get batch with JWT (should work)
curl -s -X GET "http://localhost:8080/api/v1/batch/$BATCH_ID" \
  -H "Authorization: Bearer $JWT_TOKEN" | jq

# Verify status 200
curl -s -w "\nHTTP Status: %{http_code}\n" \
  -X GET "http://localhost:8080/api/v1/batch/$BATCH_ID" \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -o /dev/null
```

**Expected Result**: HTTP Status: 200, BatchResponseDto returned

#### Test 2b: GET with Keycloak token

```bash
# Get Keycloak token (adjust URL/credentials for your setup)
export KEYCLOAK_TOKEN=$(curl -s -X POST \
  "http://localhost:8180/realms/dataforge/protocol/openid-connect/token" \
  -d "grant_type=client_credentials" \
  -d "client_id=admin-client" \
  -d "client_secret=admin-secret" | jq -r '.access_token')

# Get batch with Keycloak token (should work)
curl -s -X GET "http://localhost:8080/api/v1/batch/$BATCH_ID" \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" | jq

# Verify status 200
curl -s -w "\nHTTP Status: %{http_code}\n" \
  -X GET "http://localhost:8080/api/v1/batch/$BATCH_ID" \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" \
  -o /dev/null
```

**Expected Result**: HTTP Status: 200, BatchResponseDto returned

---

### Scenario 3: JWT-Only on Write Operations (FR-006, FR-007)

**Goal**: Verify POST/PUT/DELETE endpoints reject Keycloak tokens with 403

#### Test 3a: POST with JWT (should succeed)

```bash
# Start batch with JWT
curl -s -w "\nHTTP Status: %{http_code}\n" \
  -X POST http://localhost:8080/api/v1/batch/start \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -o /dev/null
```

**Expected Result**: HTTP Status: 201

#### Test 3b: POST with Keycloak (should fail with 403)

```bash
# Attempt to start batch with Keycloak token
ERROR_RESPONSE=$(curl -s -X POST http://localhost:8080/api/v1/batch/start \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN")

echo "Error Response:"
echo $ERROR_RESPONSE | jq

# Verify 403 status and ErrorResponseDto structure
echo $ERROR_RESPONSE | jq -e '.status == 403' > /dev/null && echo "✓ Status is 403"
echo $ERROR_RESPONSE | jq -e '.error == "Forbidden"' > /dev/null && echo "✓ Error is Forbidden"
echo $ERROR_RESPONSE | jq -e '.message == "Authentication failed"' > /dev/null && echo "✓ Generic message (FR-014)"
echo $ERROR_RESPONSE | jq -e '.timestamp' > /dev/null && echo "✓ Timestamp present"
echo $ERROR_RESPONSE | jq -e '.path' > /dev/null && echo "✓ Path present"
```

**Expected Result**: HTTP Status: 403, ErrorResponseDto with generic message

---

### Scenario 4: Dual Token Detection (FR-015)

**Goal**: Verify requests with both tokens return 400 Bad Request

```bash
# Send request with both JWT and Keycloak tokens
DUAL_TOKEN_RESPONSE=$(curl -s -X GET "http://localhost:8080/api/v1/batch/$BATCH_ID" \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "X-Keycloak-Token: Bearer $KEYCLOAK_TOKEN")

echo "Dual Token Response:"
echo $DUAL_TOKEN_RESPONSE | jq

# Verify 400 status
echo $DUAL_TOKEN_RESPONSE | jq -e '.status == 400' > /dev/null && echo "✓ Status is 400"
echo $DUAL_TOKEN_RESPONSE | jq -e '.message | contains("Ambiguous")' > /dev/null && echo "✓ Ambiguous auth message"
```

**Expected Result**: HTTP Status: 400, ErrorResponseDto mentioning ambiguous authentication

---

### Scenario 5: Admin Endpoints (Keycloak Only) (FR-008, FR-009)

**Goal**: Verify admin endpoints reject JWT tokens and accept Keycloak only

#### Test 5a: Admin endpoint with Keycloak (should succeed)

```bash
# List accounts with Keycloak token
curl -s -X GET http://localhost:8080/api/v1/admin/accounts \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" | jq

# Verify status 200
curl -s -w "\nHTTP Status: %{http_code}\n" \
  -X GET http://localhost:8080/api/v1/admin/accounts \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" \
  -o /dev/null
```

**Expected Result**: HTTP Status: 200, Array of AccountResponseDto

#### Test 5b: Admin endpoint with JWT (should fail)

```bash
# Attempt to list accounts with JWT
ERROR_RESPONSE=$(curl -s -X GET http://localhost:8080/api/v1/admin/accounts \
  -H "Authorization: Bearer $JWT_TOKEN")

echo "Error Response:"
echo $ERROR_RESPONSE | jq

# Verify 403 status
echo $ERROR_RESPONSE | jq -e '.status == 403' > /dev/null && echo "✓ Status is 403"
echo $ERROR_RESPONSE | jq -e '.message == "Authentication failed"' > /dev/null && echo "✓ Generic message"
```

**Expected Result**: HTTP Status: 403, ErrorResponseDto with generic message

---

### Scenario 6: Authentication Audit Logging (FR-013)

**Goal**: Verify auth failures are logged with IP, endpoint, method, status, tokenType

```bash
# Trigger auth failure
curl -s -X POST http://localhost:8080/api/v1/batch/start \
  -H "Authorization: Bearer invalid-token" \
  -o /dev/null

# Check application logs
docker-compose logs app | tail -50 | grep "auth_failure"
```

**Expected Log Entry** (JSON format in production):
```json
{
  "event": "auth_failure",
  "timestamp": "2025-10-09T...",
  "ip": "127.0.0.1",
  "endpoint": "/api/v1/batch/start",
  "method": "POST",
  "status": 401,
  "tokenType": "jwt",
  "message": "Authentication failed"
}
```

**Verification**:
- `ip` field present with client IP
- `endpoint` field matches request path
- `method` field matches HTTP method
- `status` field is 401 or 403
- `tokenType` field indicates "jwt" or "keycloak"

---

### Scenario 7: Error Response Standardization (FR-004)

**Goal**: Verify all error responses use ErrorResponseDto

```bash
# Trigger various error conditions
echo "=== 404 Not Found ==="
curl -s -X GET http://localhost:8080/api/v1/batch/00000000-0000-0000-0000-000000000000 \
  -H "Authorization: Bearer $JWT_TOKEN" | jq

echo "\n=== 409 Conflict (duplicate batch) ==="
# Start batch twice
curl -s -X POST http://localhost:8080/api/v1/batch/start \
  -H "Authorization: Bearer $JWT_TOKEN" -o /dev/null
curl -s -X POST http://localhost:8080/api/v1/batch/start \
  -H "Authorization: Bearer $JWT_TOKEN" | jq

echo "\n=== 413 Payload Too Large ==="
# Upload file > 500MB (adjust path)
dd if=/dev/zero of=/tmp/large-file bs=1M count=501
curl -s -X POST "http://localhost:8080/api/v1/batch/$BATCH_ID/upload" \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -F "file=@/tmp/large-file" | jq
rm /tmp/large-file

# Verify all use ErrorResponseDto structure
# (status, error, message, timestamp, path fields)
```

**Expected Result**: All errors return ErrorResponseDto with consistent structure

---

## Cleanup

```bash
# Stop application (Ctrl+C)

# Stop infrastructure
docker-compose down

# Clean up test files
rm -f /tmp/large-file
```

## Success Criteria

- ✓ All API responses use structured DTOs (no Map<String, Object>)
- ✓ GET endpoints on batch/error/upload controllers accept both JWT and Keycloak tokens
- ✓ POST/PUT/DELETE endpoints on batch/error/upload controllers accept JWT only (403 on Keycloak)
- ✓ Admin endpoints accept Keycloak only (403 on JWT)
- ✓ Dual token requests return 400 Bad Request
- ✓ Auth failures logged with IP, endpoint, method, status, tokenType
- ✓ All errors use ErrorResponseDto with generic auth failure messages

## Troubleshooting

**Issue**: JWT token generation fails
- **Solution**: Check site credentials in database, verify `client_secret` is correct

**Issue**: Keycloak token unavailable
- **Solution**: Start Keycloak via `docker-compose up keycloak`, configure realm/client

**Issue**: Dual token detection not working
- **Solution**: Verify `DualAuthenticationFilter` is registered before authentication filters

**Issue**: Auth failures not logged
- **Solution**: Check `AuthenticationAuditLogger` is wired into Spring Security failure handler

**Issue**: DTO fields missing
- **Solution**: Verify `fromEntity()` mapping includes all fields, run unit tests

## Next Steps

After successful quickstart validation:
1. Run full test suite: `./gradlew test`
2. Verify code coverage ≥80%: `./gradlew jacocoTestReport`
3. Update OpenAPI docs in Swagger UI: http://localhost:8080/swagger-ui.html
4. Coordinate deployment with API consumers (breaking change)
