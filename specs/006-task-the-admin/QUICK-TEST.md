# Quick Test Guide - Admin User Management MVP

**Status**: Build verified ✅ | Bean conflicts resolved ✅ | Ready for runtime testing

## Prerequisites Status

Before running, ensure these services are available:

1. **PostgreSQL 16**: Running on `localhost:5432`
2. **Keycloak**: Running on `localhost:8081` (optional for basic startup test)

## Quick Start (Minimal Test)

### Step 1: Start the Application

```bash
# From project root
./gradlew bootRun --args='--spring.profiles.active=dev'
```

### Step 2: Watch for Successful Startup

Look for these log messages:

```
✅ Started DataForgeMiddlewareApplication in X.XXX seconds
✅ Tomcat started on port 8080
```

If you see errors about Keycloak connection:
- This is **expected** if Keycloak is not running
- The app should still start (graceful degradation)
- You can test other endpoints

### Step 3: Verify Basic Health

```bash
# Check application health
curl http://localhost:8080/actuator/health

# Expected response:
{
  "status": "UP"
}
```

### Step 4: Access Swagger UI

```bash
# Open in browser
open http://localhost:8080/swagger-ui.html
```

**What to verify**:
- [ ] Swagger UI loads without errors
- [ ] "Admin - Accounts" tag is visible
- [ ] POST `/api/admin/accounts/with-keycloak` endpoint exists
- [ ] Request schema shows: email, name, phone, company, role
- [ ] Response schema shows: account, temporaryPassword

## Full Testing (With Keycloak)

If Keycloak is not running, start it:

```bash
# Using Docker
docker run -d \
  --name keycloak-test \
  -p 8081:8080 \
  -e KEYCLOAK_ADMIN=admin \
  -e KEYCLOAK_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:23.0.1 \
  start-dev

# Wait 30-60 seconds for Keycloak to start
curl http://localhost:8081/health/ready
```

### Configure Keycloak (One-Time Setup)

1. **Access Admin Console**:
   - URL: http://localhost:8081/admin
   - Username: `admin`
   - Password: `admin`

2. **Create Realm "dfm"** (if not exists):
   - Click dropdown (top-left) → "Create Realm"
   - Name: `dfm`
   - Click "Create"

3. **Configure admin-cli Client**:
   - Go to: Clients → `admin-cli`
   - Settings tab:
     - Capability config:
       - ☑ Client authentication: ON
       - ☑ Service accounts roles: ON
     - Click "Save"
   - Credentials tab:
     - Copy the "Client secret"
   - Service Account Roles tab:
     - Click "Assign role"
     - Filter by clients: select "realm-management"
     - Select these roles:
       - ☑ `manage-users`
       - ☑ `view-users`
     - Click "Assign"

4. **Set Environment Variable**:
   ```bash
   export KEYCLOAK_ADMIN_CLIENT_SECRET="paste-secret-here"
   ```

5. **Restart Application**:
   - Stop the running app (Ctrl+C)
   - Restart: `./gradlew bootRun --args='--spring.profiles.active=dev'`

### Test Account Creation

```bash
# Get admin token
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8081/realms/dfm/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=admin-cli" \
  -d "client_secret=$KEYCLOAK_ADMIN_CLIENT_SECRET" \
  -d "grant_type=client_credentials" \
  | python3 -c "import sys, json; print(json.load(sys.stdin)['access_token'])")

# Create test account
curl -v -X POST http://localhost:8080/api/admin/accounts/with-keycloak \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "testuser@example.com",
    "name": "Test User",
    "role": "USER"
  }'
```

**Expected Response (201 Created)**:
```json
{
  "account": {
    "id": "...",
    "keycloakUserId": "...",
    "email": "testuser@example.com",
    "name": "Test User",
    "isActive": true,
    "keycloakEnabled": true,
    "passwordTemporary": true,
    "passwordExpiresAt": "..."
  },
  "temporaryPassword": "Ab3$xY9!pQ2z"
}
```

### Verify in Database

```bash
psql -U postgres -d dfm -c "
  SELECT id, email, name, keycloak_user_id, is_active
  FROM accounts
  WHERE email = 'testuser@example.com';
"
```

### Verify in Keycloak

1. Go to: http://localhost:8081/admin/master/console/#/dfm/users
2. Search: `testuser@example.com`
3. Click on the user
4. Check:
   - ☑ User exists
   - ☑ Username = email
   - ☑ Email is set
   - ☑ Enabled = true
   - ☑ Attributes tab contains `accountId`

## Troubleshooting

### App Fails to Start: "Access key ID cannot be blank"

**Fix**:
```bash
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
```

Or use default values in application-dev.yml (already set).

### App Fails to Start: "Connection refused to PostgreSQL"

**Fix**:
```bash
# Check if PostgreSQL is running
pg_isready -h localhost -p 5432

# If not running (macOS)
brew services start postgresql@16

# Or use Docker
docker run -d -p 5432:5432 \
  -e POSTGRES_DB=dfm \
  -e POSTGRES_PASSWORD=postgres \
  --name postgres-dfm \
  postgres:16
```

### Cannot Get Admin Token: "Client secret not valid"

**Fix**: Reconfigure Keycloak admin-cli client (see steps above)

### 403 Forbidden When Creating Account

**Fix**: Ensure admin-cli has `manage-users` role in Service Account Roles

## Success Criteria (Minimal)

✅ Application starts without errors
✅ Swagger UI accessible at http://localhost:8080/swagger-ui.html
✅ Endpoint visible: POST `/api/admin/accounts/with-keycloak`
✅ Health check returns UP

## Success Criteria (Full)

✅ Can create account via API (201 response)
✅ Account exists in PostgreSQL with keycloak_user_id
✅ User exists in Keycloak with accountId attribute
✅ Temporary password is 12 chars, mixed complexity
✅ Audit log entry created
✅ Metrics show successful creation

## Next Steps

Once basic testing passes:
- See `TESTING.md` for comprehensive test scenarios
- Test error cases (duplicate email, validation)
- Verify rollback mechanism
- Check metrics and logs
- Proceed with User Story 2 (Lock/Unlock) or UI completion

## Quick Metrics Check

```bash
# View all account creation metrics
curl -s http://localhost:8080/actuator/prometheus | grep account_created

# Expected output:
# account_created_success_total 1.0
# account_created_failure_total 0.0
# account_creation_duration_seconds_count 1.0
# account_creation_duration_seconds_sum 1.234
```

## Support

- Full testing guide: `TESTING.md`
- Implementation details: `quickstart.md`
- Architecture decisions: `research.md`
- API contract: `contracts/account-management-api.yaml`
