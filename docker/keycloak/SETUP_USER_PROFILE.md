# Keycloak User Profile Setup for accountId Attribute

## Why This Is Needed

Since Keycloak 26, custom user attributes must be declared in the **User Profile** before they can be set on users.

Our application needs to store `accountId` (PostgreSQL UUID) as a Keycloak user attribute to enable bidirectional mapping between Keycloak and our database.

## Automatic Setup (Recommended)

The `accountId` attribute is **automatically configured** when importing `dfm-realm.json`.

The realm configuration includes:

```json
{
  "attributes": {
    "userProfileEnabled": "true"
  },
  "userProfile": {
    "attributes": [
      {
        "name": "accountId",
        "displayName": "Account ID",
        "permissions": {
          "view": ["admin", "user"],
          "edit": ["admin"]
        },
        "multivalued": false
      }
    ]
  }
}
```

### Verify Automatic Setup

1. Access Keycloak Admin Console: http://localhost:8081
2. Login: `admin` / `admin`
3. Select realm: **dfm**
4. Go to: **Realm Settings** → **User Profile** tab
5. You should see `accountId` attribute already configured

## Manual Setup (If Needed)

If for some reason the attribute was not imported automatically:

### 1. Access Keycloak Admin Console

- URL: http://localhost:8081
- Username: `admin`
- Password: `admin`

### 2. Navigate to User Profile

1. Select realm: **dfm** (top-left dropdown)
2. Go to: **Realm Settings** → **User Profile** tab
3. Click: **Create attribute** button

### 3. Configure accountId Attribute

**General Settings:**
- **Attribute name**: `accountId`
- **Display name**: `Account ID`
- **Required for**: Leave unchecked (optional attribute)
- **Multivalued**: No

**Permissions:**
- **Who can view?**: `admin`, `user`
- **Who can edit?**: `admin`

### 4. Save Configuration

Click **Save** button at the bottom.

## How It Works

1. **User Creation**: When creating a user with `ROLE_USER`, our application automatically sets the `accountId` attribute via Keycloak Admin Client.

2. **Admin Users**: Users with `ROLE_ADMIN` do NOT get an `accountId` attribute (they only manage the system, not use it).

3. **JWT Token Claims**: The `accountId` attribute is included in JWT tokens via the protocol mapper configured in `dfm-realm.json`:
   ```json
   {
     "name": "account-id-mapper",
     "protocol": "openid-connect",
     "protocolMapper": "oidc-usermodel-attribute-mapper",
     "config": {
       "user.attribute": "accountId",
       "claim.name": "accountId",
       "access.token.claim": "true"
     }
   }
   ```

4. **Bidirectional Mapping**:
   - Keycloak → PostgreSQL: `keycloak_user_id` column in `accounts` table
   - PostgreSQL → Keycloak: `accountId` attribute on Keycloak user

## Troubleshooting

### Attribute Not Appearing in JWT Token

1. Verify User Profile attribute is created correctly
2. Check that protocol mapper `account-id-mapper` exists in client configuration
3. Clear browser cache and get a fresh token
4. Use jwt.io to decode token and verify `accountId` claim exists

### Attribute Not Saved on User

Check application logs for:
```
DEBUG c.b.d.a.i.KeycloakAdminClient - Set accountId=xxx for user yyy
INFO  c.b.d.a.i.KeycloakAdminClient - Successfully updated user attributes
VERIFICATION: Attributes after update: {accountId=[...]}
```

If `VERIFICATION` shows `null`, the User Profile attribute is not properly configured.

### Permission Denied When Setting Attribute

Ensure the Keycloak Admin Client has sufficient permissions:
- Client: `admin-cli`
- Grant type: `password`
- Username: `admin` (or service account with admin role)

## References

- [Keycloak 26 User Profile Documentation](https://www.keycloak.org/docs/latest/server_admin/#user-profile)
- [Keycloak Upgrading Guide - Version 26](https://www.keycloak.org/docs/latest/upgrading/#user-profile-enabled-by-default)
