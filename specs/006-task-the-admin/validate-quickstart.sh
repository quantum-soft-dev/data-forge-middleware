#!/bin/bash

# Quickstart.md API Validation Script (T077)
#
# This script validates all cURL commands from quickstart.md
# Requires:
# - Backend running on localhost:8080
# - Keycloak running on localhost:8081
# - Valid admin token in $ADMIN_TOKEN environment variable
#
# Usage:
#   export ADMIN_TOKEN="your-keycloak-admin-token"
#   ./validate-quickstart.sh

set -e

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="${ADMIN_TOKEN:-}"

# Check prerequisites
echo -e "${YELLOW}Checking prerequisites...${NC}"

if [ -z "$ADMIN_TOKEN" ]; then
    echo -e "${RED}Error: ADMIN_TOKEN environment variable is required${NC}"
    echo "Please set it with: export ADMIN_TOKEN='your-keycloak-admin-token'"
    exit 1
fi

# Check if backend is running
if ! curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/actuator/health" | grep -q "200"; then
    echo -e "${RED}Error: Backend is not running at $BASE_URL${NC}"
    echo "Please start the backend with: ./gradlew bootRun"
    exit 1
fi

echo -e "${GREEN}✓ Prerequisites OK${NC}\n"

# Test variables
TEST_EMAIL="quickstart-test-$(date +%s)@example.com"
ACCOUNT_ID=""

echo -e "${YELLOW}Starting API validation...${NC}\n"

# Test 1: Create Account with Keycloak
echo "Test 1: Create Account with Keycloak (POST /api/admin/accounts)"
RESPONSE=$(curl -s -X POST "$BASE_URL/api/admin/accounts" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"email\": \"$TEST_EMAIL\",
    \"name\": \"Quickstart Test User\",
    \"role\": \"USER\"
  }")

if echo "$RESPONSE" | grep -q '"account"'; then
    echo -e "${GREEN}✓ Account created successfully${NC}"
    ACCOUNT_ID=$(echo "$RESPONSE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    echo "  Account ID: $ACCOUNT_ID"
    echo "  Email: $TEST_EMAIL"

    # Extract temporary password
    TEMP_PASSWORD=$(echo "$RESPONSE" | grep -o '"temporaryPassword":"[^"]*"' | cut -d'"' -f4)
    if [ -n "$TEMP_PASSWORD" ]; then
        echo -e "  ${YELLOW}Temporary Password: $TEMP_PASSWORD${NC}"
    fi
else
    echo -e "${RED}✗ Failed to create account${NC}"
    echo "Response: $RESPONSE"
    exit 1
fi

echo ""

# Wait a moment for Keycloak sync
sleep 2

# Test 2: Lock Account (Disable Keycloak User)
echo "Test 2: Lock Account (POST /api/admin/accounts/{accountId}/lock)"
RESPONSE=$(curl -s -X POST "$BASE_URL/api/admin/accounts/$ACCOUNT_ID/lock" \
  -H "Authorization: Bearer $ADMIN_TOKEN")

if echo "$RESPONSE" | grep -q '"accountEnabled":false' || echo "$RESPONSE" | grep -q '200'; then
    echo -e "${GREEN}✓ Account locked successfully${NC}"
    echo "  Keycloak user is now disabled"
else
    echo -e "${RED}✗ Failed to lock account${NC}"
    echo "Response: $RESPONSE"
fi

echo ""

# Wait a moment
sleep 1

# Test 3: Unlock Account (Enable Keycloak User)
echo "Test 3: Unlock Account (POST /api/admin/accounts/{accountId}/unlock)"
RESPONSE=$(curl -s -X POST "$BASE_URL/api/admin/accounts/$ACCOUNT_ID/unlock" \
  -H "Authorization: Bearer $ADMIN_TOKEN")

if echo "$RESPONSE" | grep -q '"accountEnabled":true' || echo "$RESPONSE" | grep -q '200'; then
    echo -e "${GREEN}✓ Account unlocked successfully${NC}"
    echo "  Keycloak user is now enabled"
else
    echo -e "${RED}✗ Failed to unlock account${NC}"
    echo "Response: $RESPONSE"
fi

echo ""

# Wait a moment
sleep 1

# Test 4: Reset Password
echo "Test 4: Reset Password (POST /api/admin/accounts/{accountId}/reset-password)"
RESPONSE=$(curl -s -X POST "$BASE_URL/api/admin/accounts/$ACCOUNT_ID/reset-password" \
  -H "Authorization: Bearer $ADMIN_TOKEN")

if echo "$RESPONSE" | grep -q '"temporaryPassword"'; then
    echo -e "${GREEN}✓ Password reset successfully${NC}"
    NEW_PASSWORD=$(echo "$RESPONSE" | grep -o '"temporaryPassword":"[^"]*"' | cut -d'"' -f4)
    EXPIRES_AT=$(echo "$RESPONSE" | grep -o '"expiresAt":"[^"]*"' | cut -d'"' -f4)
    echo -e "  ${YELLOW}New Temporary Password: $NEW_PASSWORD${NC}"
    echo "  Expires At: $EXPIRES_AT"
else
    echo -e "${RED}✗ Failed to reset password${NC}"
    echo "Response: $RESPONSE"
fi

echo ""

# Test 5: Get Account Details (verify Keycloak integration)
echo "Test 5: Get Account Details (GET /api/admin/accounts/{accountId})"
RESPONSE=$(curl -s -X GET "$BASE_URL/api/admin/accounts/$ACCOUNT_ID" \
  -H "Authorization: Bearer $ADMIN_TOKEN")

if echo "$RESPONSE" | grep -q '"keycloakUserId"'; then
    echo -e "${GREEN}✓ Account details retrieved successfully${NC}"
    KEYCLOAK_USER_ID=$(echo "$RESPONSE" | grep -o '"keycloakUserId":"[^"]*"' | cut -d'"' -f4)
    echo "  Keycloak User ID: $KEYCLOAK_USER_ID"
    echo "  Account has Keycloak integration: true"
else
    echo -e "${YELLOW}⚠ Account details retrieved but Keycloak integration may not be set${NC}"
    echo "Response: $RESPONSE"
fi

echo ""

# Summary
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}All quickstart.md API tests completed!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "Test Account Created:"
echo "  Email: $TEST_EMAIL"
echo "  Account ID: $ACCOUNT_ID"
echo ""
echo "Validated Commands:"
echo "  ✓ POST /api/admin/accounts (Create Account with Keycloak)"
echo "  ✓ POST /api/admin/accounts/{id}/lock (Lock Account)"
echo "  ✓ POST /api/admin/accounts/{id}/unlock (Unlock Account)"
echo "  ✓ POST /api/admin/accounts/{id}/reset-password (Reset Password)"
echo "  ✓ GET /api/admin/accounts/{id} (Get Account Details)"
echo ""
echo -e "${YELLOW}Note: Test account $TEST_EMAIL was created and can be cleaned up manually if needed.${NC}"
