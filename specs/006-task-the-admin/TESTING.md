# Testing Guide: Admin User Management with Keycloak

**Feature**: 006-task-the-admin
**Date**: 2025-10-28
**Status**: MVP Core Implementation Complete (27/82 tasks)

## Prerequisites

Before testing, ensure you have:

1. **PostgreSQL 16** running on `localhost:5432`
2. **Keycloak** running on `localhost:8081`
3. **Keycloak configured** with:
   - Realm: `dfm`
   - Admin client: `admin-cli` with service account
   - Client secret configured in environment
4. **LocalStack** (optional, for S3 features)

## Environment Setup

### Option 1: Using Docker Compose

If you have a `docker-compose.yml`:

```bash
# Start services
docker-compose up -d postgres keycloak

# Wait for services to be ready
docker-compose ps
```

### Option 2: Manual Setup

**PostgreSQL:**
```bash
# Ensure PostgreSQL is running
psql -U postgres -c "SELECT version();"
```

**Keycloak:**
```bash
# If using Docker
docker run -d \
  --name keycloak \
  -p 8081:8080 \
  -e KEYCLOAK_ADMIN=admin \
  -e KEYCLOAK_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:23.0.1 \
  start-dev

# Access Keycloak Admin Console
# http://localhost:8081/admin
# Username: admin
# Password: admin
```

### Keycloak Configuration

1. **Create Realm "dfm"** (if not exists):
   - Go to http://localhost:8081/admin
   - Click "Create Realm"
   - Name: `dfm`
   - Save

2. **Configure Admin Client**:
   - Go to Clients → `admin-cli`
   - Settings tab:
     - Service Accounts Enabled: `ON`
     - Save
   - Credentials tab:
     - Copy the Client Secret
   - Service Account Roles tab:
     - Assign "realm-management" → "manage-users"
     - Assign "realm-management" → "view-users"

3. **Set Environment Variable**:
   ```bash
   export KEYCLOAK_ADMIN_CLIENT_SECRET="your-client-secret-here"
   ```

## Running the Application

### Start with Development Profile

```bash
# From project root
./gradlew bootRun --args='--spring.profiles.active=dev'
```

Expected output:
```
Started DataForgeMiddlewareApplication in X.XXX seconds
```

### Verify Application Started

```bash
# Check health endpoint
curl http://localhost:8080/actuator/health

# Expected: {"status":"UP"}
```

## Testing Scenarios

### 1. API Documentation (Swagger UI)

**Access**: http://localhost:8080/swagger-ui.html

**What to check**:
- [ ] Swagger UI loads successfully
- [ ] "Admin - Accounts" tag exists
- [ ] POST `/api/admin/accounts/with-keycloak` endpoint is visible
- [ ] Request/response schemas are documented

### 2. Create Account with Keycloak (Success Case)

**Endpoint**: POST `/api/admin/accounts/with-keycloak`

**Prerequisites**:
- Obtain admin JWT token from Keycloak
- Set ROLE_ADMIN in token

**Request**:
```bash
# Get admin token (adjust based on your Keycloak setup)
ADMIN_TOKEN=$(curl -X POST http://localhost:8081/realms/dfm/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=admin-cli" \
  -d "client_secret=$KEYCLOAK_ADMIN_CLIENT_SECRET" \
  -d "grant_type=client_credentials" \
  | jq -r '.access_token')

# Create account
curl -X POST http://localhost:8080/api/admin/accounts/with-keycloak \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "testuser@example.com",
    "name": "Test User",
    "phone": "+1234567890",
    "company": "Test Corp",
    "role": "USER"
  }' | jq
```

**Expected Response** (201 Created):
```json
{
  "account": {
    "id": "uuid",
    "keycloakUserId": "uuid",
    "email": "testuser@example.com",
    "name": "Test User",
    "phone": "+1234567890",
    "company": "Test Corp",
    "isActive": true,
    "keycloakEnabled": true,
    "passwordTemporary": true,
    "passwordExpiresAt": "2025-11-27T...",
    "lastLogin": null,
    "createdAt": "2025-10-28T...",
    "updatedAt": "2025-10-28T..."
  },
  "temporaryPassword": "Ab3$xY9!pQ2z"
}
```

**Verification Steps**:
1. [ ] Response status is 201
2. [ ] `temporaryPassword` is 12 characters with mixed case, digits, special chars
3. [ ] `keycloakUserId` is a valid UUID
4. [ ] `keycloakEnabled` is true
5. [ ] `passwordTemporary` is true
6. [ ] `passwordExpiresAt` is ~30 days in the future

### 3. Verify in PostgreSQL

```bash
psql -U postgres -d dfm -c "
  SELECT id, email, name, keycloak_user_id, is_active, created_at
  FROM accounts
  WHERE email = 'testuser@example.com';
"
```

**Expected**:
- [ ] Account exists in database
- [ ] `keycloak_user_id` matches response
- [ ] `is_active` is true

### 4. Verify in Keycloak

1. Go to http://localhost:8081/admin/master/console/#/dfm/users
2. Search for `testuser@example.com`

**Expected**:
- [ ] User exists in Keycloak
- [ ] User is enabled
- [ ] Email is set correctly
- [ ] Username equals email
- [ ] Attributes contain `accountId` with PostgreSQL UUID

### 5. Verify Audit Log

```bash
psql -U postgres -d dfm -c "
  SELECT action_type, status, target_account_id, admin_account_id, created_at
  FROM admin_action_logs
  ORDER BY created_at DESC
  LIMIT 5;
"
```

**Expected**:
- [ ] Entry with `action_type` = 'CREATE_ACCOUNT'
- [ ] `status` = 'SUCCESS'
- [ ] `target_account_id` matches created account
- [ ] `created_at` is recent

### 6. Verify Metrics

**Access**: http://localhost:8080/actuator/prometheus

**Search for**:
```
account_created_success_total
account_created_failure_total
account_creation_duration_seconds_count
account_creation_duration_seconds_sum
account_creation_duration_seconds_max
```

**Expected**:
- [ ] `account_created_success_total` >= 1
- [ ] `account_creation_duration_seconds_count` >= 1
- [ ] Duration metrics show realistic values (0.5-3 seconds)

### 7. Error Handling: Duplicate Email

```bash
# Try to create same email again
curl -X POST http://localhost:8080/api/admin/accounts/with-keycloak \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "testuser@example.com",
    "name": "Duplicate User",
    "role": "USER"
  }' | jq
```

**Expected Response** (409 Conflict or 400 Bad Request):
```json
{
  "timestamp": "2025-10-28T...",
  "status": 409,
  "error": "Conflict",
  "message": "Account with email already exists",
  "path": "/api/admin/accounts/with-keycloak"
}
```

**Verification**:
- [ ] Response status is 409 or 400
- [ ] Error message is descriptive
- [ ] `account_created_failure_total` metric incremented
- [ ] No duplicate in database
- [ ] No duplicate in Keycloak

### 8. Error Handling: Invalid Email

```bash
curl -X POST http://localhost:8080/api/admin/accounts/with-keycloak \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "invalid-email",
    "name": "Test User",
    "role": "USER"
  }' | jq
```

**Expected Response** (400 Bad Request):
```json
{
  "timestamp": "2025-10-28T...",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed: email must be valid format",
  "path": "/api/admin/accounts/with-keycloak"
}
```

**Verification**:
- [ ] Response status is 400
- [ ] Validation error message is clear
- [ ] No account created in database
- [ ] No user created in Keycloak

### 9. Error Handling: Missing Required Fields

```bash
curl -X POST http://localhost:8080/api/admin/accounts/with-keycloak \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test2@example.com"
  }' | jq
```

**Expected Response** (400 Bad Request):
```json
{
  "timestamp": "2025-10-28T...",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed: name is required, role is required",
  "path": "/api/admin/accounts/with-keycloak"
}
```

### 10. Rollback Verification

To test rollback, you need to simulate a failure. One way is to:

1. Shut down Keycloak temporarily
2. Try to create an account (should fail during Keycloak creation)
3. Verify no account in PostgreSQL

**Or** modify the code temporarily to simulate database failure after Keycloak creation:

```java
// In KeycloakAccountSyncService, after keycloak user creation:
throw new RuntimeException("Simulated DB failure");
```

**Expected**:
- [ ] Keycloak user is deleted (rollback)
- [ ] No account in PostgreSQL
- [ ] Error logged in console
- [ ] Audit log shows FAILED status
- [ ] `account_created_failure_total` metric incremented

## Application Logs

### Check Structured Logs

```bash
# Tail application logs
tail -f logs/application.log

# Or if running in terminal, check stdout
```

**Look for**:
```
INFO  KeycloakAccountSyncService - Creating Keycloak user for email: testuser@example.com
INFO  KeycloakAccountSyncService - Keycloak user created with ID: uuid
INFO  KeycloakAccountSyncService - Account created in database with ID: uuid
INFO  KeycloakAccountSyncService - Bidirectional mapping established for account ID: uuid
```

## Known Issues / Limitations

1. **Admin Account ID Placeholder**: The endpoint currently uses a placeholder UUID for `adminAccountId`. In production, this should be extracted from the JWT token's subject claim.

2. **No Immediate Session Termination**: When an account is locked (User Story 2, not yet implemented), existing sessions continue until natural expiration.

3. **Keycloak Admin Client Secret**: Must be configured via environment variable. Not suitable for production without secrets management.

4. **No Rate Limiting**: The endpoint lacks request throttling. Consider adding rate limiting for production.

## Troubleshooting

### Issue: "Access key ID cannot be blank"

**Cause**: S3 configuration not set properly

**Fix**:
```bash
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
# Or ensure application-dev.yml has defaults
```

### Issue: "Keycloak Admin Client 403 Forbidden"

**Cause**: Service account doesn't have necessary roles

**Fix**:
1. Go to Keycloak Admin Console
2. Clients → admin-cli → Service Account Roles
3. Add "realm-management" → "manage-users"
4. Add "realm-management" → "view-users"

### Issue: Connection refused to PostgreSQL

**Cause**: PostgreSQL not running or wrong port

**Fix**:
```bash
# Check PostgreSQL status
pg_isready -h localhost -p 5432

# Start PostgreSQL (macOS)
brew services start postgresql@16

# Or use Docker
docker run -d -p 5432:5432 -e POSTGRES_PASSWORD=postgres postgres:16
```

### Issue: Flyway migration fails

**Cause**: Database schema out of sync

**Fix**:
```bash
# Check migration status
./gradlew flywayInfo

# If needed, clean and re-migrate (CAUTION: drops all data)
./gradlew flywayClean flywayMigrate
```

## Success Criteria

✅ **MVP is ready for User Story 1** if all of the following pass:

- [ ] Application starts without errors
- [ ] Swagger UI accessible and shows new endpoint
- [ ] Can create account with valid data (201 response)
- [ ] Account exists in both PostgreSQL and Keycloak
- [ ] Temporary password is 12 characters, mixed complexity
- [ ] Keycloak user has `accountId` attribute
- [ ] PostgreSQL account has `keycloak_user_id`
- [ ] Audit log entry created with SUCCESS status
- [ ] Metrics show successful creation
- [ ] Duplicate email returns 409/400 error
- [ ] Invalid email returns 400 validation error
- [ ] Missing required fields returns 400 error
- [ ] Rollback works (Keycloak user deleted on DB failure)

## Next Steps After Testing

If all tests pass:
1. **Implement User Story 2** (Lock/Unlock) - T038-T050
2. **Implement User Story 3** (Reset Password) - T051-T062
3. **Add comprehensive tests** - T017-T018, T035-T037
4. **Complete UI** - T028-T034
5. **Polish & cross-cutting concerns** - T063-T082

## Support

For issues or questions:
- Check `CLAUDE.md` for development guidelines
- Review `specs/006-task-the-admin/research.md` for architectural decisions
- Consult `specs/006-task-the-admin/quickstart.md` for implementation details
