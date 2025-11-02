#!/bin/bash

# Setup Keycloak User Profile for accountId attribute
# This script configures the User Profile via REST API after Keycloak starts

set -e

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8081}"
ADMIN_USER="${KEYCLOAK_ADMIN_USER:-admin}"
ADMIN_PASS="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
REALM="${REALM:-dfm}"

echo "🔧 Configuring Keycloak User Profile for realm: $REALM"
echo ""

# Get admin token
echo "1️⃣ Authenticating..."
KC_TOKEN=$(curl -s -X POST "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "client_id=admin-cli" \
  -d "username=$ADMIN_USER" \
  -d "password=$ADMIN_PASS" \
  -d 'grant_type=password' | python3 -c "import sys, json; print(json.load(sys.stdin)['access_token'])")

if [ -z "$KC_TOKEN" ]; then
  echo "❌ Failed to get admin token"
  exit 1
fi

echo "✅ Authenticated successfully"
echo ""

# Get current User Profile configuration
echo "2️⃣ Fetching current User Profile configuration..."
CURRENT_CONFIG=$(curl -s -X GET "$KEYCLOAK_URL/admin/realms/$REALM/users/profile" \
  -H "Authorization: Bearer $KC_TOKEN" \
  -H 'Content-Type: application/json')

echo "✅ Current configuration retrieved"
echo ""

# Check if accountId attribute already exists
HAS_ACCOUNT_ID=$(echo "$CURRENT_CONFIG" | python3 -c "
import sys, json
config = json.load(sys.stdin)
attrs = config.get('attributes', [])
has_it = any(attr.get('name') == 'accountId' for attr in attrs)
print('true' if has_it else 'false')
")

if [ "$HAS_ACCOUNT_ID" = "true" ]; then
  echo "✅ accountId attribute already exists in User Profile"
  echo "   Nothing to do!"
  exit 0
fi

echo "3️⃣ Adding accountId attribute to User Profile..."

# Add accountId attribute to configuration
UPDATED_CONFIG=$(echo "$CURRENT_CONFIG" | python3 << 'EOF'
import sys, json

config = json.load(sys.stdin)

# Add accountId attribute
account_id_attr = {
    "name": "accountId",
    "displayName": "Account ID",
    "validations": {},
    "permissions": {
        "view": ["admin", "user"],
        "edit": ["admin"]
    },
    "multivalued": False
}

if 'attributes' not in config:
    config['attributes'] = []

config['attributes'].append(account_id_attr)

print(json.dumps(config, indent=2))
EOF
)

# Update User Profile
curl -s -X PUT "$KEYCLOAK_URL/admin/realms/$REALM/users/profile" \
  -H "Authorization: Bearer $KC_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "$UPDATED_CONFIG" > /dev/null

if [ $? -eq 0 ]; then
  echo "✅ Successfully added accountId attribute to User Profile"
  echo ""
  echo "========================================="
  echo "✅ User Profile Configuration Complete!"
  echo "========================================="
  echo ""
  echo "accountId attribute is now available with permissions:"
  echo "  - View: admin, user"
  echo "  - Edit: admin only"
else
  echo "❌ Failed to update User Profile"
  exit 1
fi
