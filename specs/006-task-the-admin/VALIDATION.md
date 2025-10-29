# Quickstart Validation (T077)

This document provides instructions for validating all cURL commands from `quickstart.md`.

## Automated Validation Script

We've created `validate-quickstart.sh` which tests all API endpoints documented in `quickstart.md`.

### Prerequisites

1. **Backend running**: `./gradlew bootRun` (on port 8080)
2. **Keycloak running**: Keycloak server (on port 8081)
3. **Admin token**: Valid Keycloak admin token

### How to Run

```bash
# 1. Get admin token from Keycloak
# Option A: Via Keycloak UI
#   - Login to Keycloak Admin Console
#   - Navigate to your profile → Copy Access Token

# Option B: Via cURL (if you have admin credentials)
export KEYCLOAK_URL="http://localhost:8081"
export ADMIN_TOKEN=$(curl -s -X POST "$KEYCLOAK_URL/realms/dfm/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=admin-cli" \
  -d "username=admin" \
  -d "password=admin" \
  -d "grant_type=password" \
  | jq -r '.access_token')

# 2. Run validation script
cd specs/006-task-the-admin
./validate-quickstart.sh
```

### What the Script Tests

The script validates all cURL commands from `quickstart.md`:

1. ✅ **Create Account with Keycloak** (`POST /api/admin/accounts`)
   - Creates account with USER role
   - Verifies Keycloak integration
   - Extracts temporary password

2. ✅ **Lock Account** (`POST /api/admin/accounts/{id}/lock`)
   - Disables Keycloak user
   - Verifies account is locked

3. ✅ **Unlock Account** (`POST /api/admin/accounts/{id}/unlock`)
   - Re-enables Keycloak user
   - Verifies account is unlocked

4. ✅ **Reset Password** (`POST /api/admin/accounts/{id}/reset-password`)
   - Generates new temporary password
   - Verifies expiration date (30 days)

5. ✅ **Get Account Details** (`GET /api/admin/accounts/{id}`)
   - Verifies Keycloak integration fields
   - Confirms account status

### Expected Output

```
Checking prerequisites...
✓ Prerequisites OK

Starting API validation...

Test 1: Create Account with Keycloak (POST /api/admin/accounts)
✓ Account created successfully
  Account ID: acc-12345678-1234-1234-1234-123456789012
  Email: quickstart-test-1730236800@example.com
  Temporary Password: TempPass123!@#

Test 2: Lock Account (POST /api/admin/accounts/{accountId}/lock)
✓ Account locked successfully
  Keycloak user is now disabled

Test 3: Unlock Account (POST /api/admin/accounts/{accountId}/unlock)
✓ Account unlocked successfully
  Keycloak user is now enabled

Test 4: Reset Password (POST /api/admin/accounts/{accountId}/reset-password)
✓ Password reset successfully
  New Temporary Password: NewPass456!@#
  Expires At: 2025-11-28T10:00:00Z

Test 5: Get Account Details (GET /api/admin/accounts/{accountId})
✓ Account details retrieved successfully
  Keycloak User ID: keycloak-123456
  Account has Keycloak integration: true

========================================
All quickstart.md API tests completed!
========================================

Validated Commands:
  ✓ POST /api/admin/accounts (Create Account with Keycloak)
  ✓ POST /api/admin/accounts/{id}/lock (Lock Account)
  ✓ POST /api/admin/accounts/{id}/unlock (Unlock Account)
  ✓ POST /api/admin/accounts/{id}/reset-password (Reset Password)
  ✓ GET /api/admin/accounts/{id} (Get Account Details)
```

## Manual Validation

If you prefer to test manually, follow the cURL commands in `quickstart.md` section "API Testing" (lines 271-305).

### Example Manual Test Flow

```bash
# 1. Set your admin token
export ADMIN_TOKEN="your-keycloak-admin-token-here"

# 2. Create account
curl -X POST http://localhost:8080/api/admin/accounts \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "name": "Test User",
    "role": "USER"
  }'

# 3. Extract account ID from response and set it
export ACCOUNT_ID="paste-account-id-here"

# 4. Lock account
curl -X POST http://localhost:8080/api/admin/accounts/$ACCOUNT_ID/lock \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 5. Unlock account
curl -X POST http://localhost:8080/api/admin/accounts/$ACCOUNT_ID/unlock \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 6. Reset password
curl -X POST http://localhost:8080/api/admin/accounts/$ACCOUNT_ID/reset-password \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

## Validation Status

**Task**: T077 - Validate quickstart.md cURL commands

**Status**: ✅ **Script Created**

**Files**:
- `validate-quickstart.sh` - Automated validation script
- `VALIDATION.md` - This documentation

**Next Steps**:
1. Run the validation script when backend and Keycloak are available
2. Verify all API endpoints work as documented
3. Update quickstart.md if any discrepancies are found

**Note**: The validation script can be run as part of CI/CD pipeline or manual QA process.

## Troubleshooting

### Error: Backend is not running
```
Error: Backend is not running at http://localhost:8080
```
**Solution**: Start the backend with `./gradlew bootRun`

### Error: ADMIN_TOKEN environment variable is required
```
Error: ADMIN_TOKEN environment variable is required
```
**Solution**: Get token from Keycloak and export it:
```bash
export ADMIN_TOKEN="your-token-here"
```

### Error: 401 Unauthorized
**Solution**: Your token may have expired. Get a new token from Keycloak.

### Error: 403 Forbidden
**Solution**: Verify your admin client has "manage-users" role in Keycloak realm-management.

## Coverage

This validation covers:
- ✅ All cURL commands from quickstart.md (lines 276-305)
- ✅ All documented API endpoints
- ✅ Success cases for each operation
- ✅ Response structure verification
- ✅ Keycloak integration confirmation

**Coverage**: 100% of documented API commands in quickstart.md
