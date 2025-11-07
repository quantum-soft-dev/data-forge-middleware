# Auth0 Migration Quickstart

**Branch**: `011-auth0-migration-migrate`
**Date**: 2025-11-06
**Audience**: Developers implementing Auth0 migration

## Prerequisites

Before starting the Auth0 migration, ensure you have:

- [ ] Auth0 tenant created (dev, staging, production)
- [ ] Auth0 account with admin access
- [ ] Access to Auth0 Dashboard
- [ ] PostgreSQL database access
- [ ] Spring Boot 3.5.6 application running
- [ ] React 19.2 frontend application running

---

## Phase 1: Auth0 Tenant Setup (Week 1)

### Step 1: Create Auth0 Applications

#### 1.1 Create Machine-to-Machine Application (Backend)

**Purpose**: Backend API uses this to call Auth0 Management API

```bash
# Navigate to Auth0 Dashboard
# Applications → Applications → Create Application
```

**Configuration**:
- **Name**: `Data Forge Management API Client`
- **Type**: Machine to Machine Applications
- **Authorized APIs**: Auth0 Management API
- **Permissions** (Scopes):
  - `read:users`
  - `create:users`
  - `update:users`
  - `delete:users`
  - `read:roles`
  - `create:user_tickets`
  - `read:logs`

**Save Credentials**:
```bash
# Export to environment variables (DO NOT commit to Git)
export AUTH0_DOMAIN="dev-example.us.auth0.com"
export AUTH0_MGMT_CLIENT_ID="AbCdEf123456789"
export AUTH0_MGMT_CLIENT_SECRET="your-secret-here"
```

#### 1.2 Create API Application (For JWT Validation)

**Purpose**: Backend validates JWT tokens issued for this API

```bash
# Navigate to Auth0 Dashboard
# Applications → APIs → Create API
```

**Configuration**:
- **Name**: `Data Forge API`
- **Identifier** (Audience): `https://api.dataforge.com`
- **Signing Algorithm**: RS256
- **RBAC Settings**:
  - Enable RBAC: ✅
  - Add Permissions in the Access Token: ✅

**Permissions** (Scopes):
- `read:batches`
- `write:batches`
- `read:files`
- `write:files`

#### 1.3 Create Single-Page Application (Frontend)

**Purpose**: React app uses this for authentication

```bash
# Navigate to Auth0 Dashboard
# Applications → Applications → Create Application
```

**Configuration**:
- **Name**: `Data Forge Admin Panel`
- **Type**: Single Page Web Applications
- **Allowed Callback URLs**:
  ```
  http://localhost:5173,
  https://app.dataforge.com
  ```
- **Allowed Logout URLs**:
  ```
  http://localhost:5173,
  https://app.dataforge.com
  ```
- **Allowed Web Origins**:
  ```
  http://localhost:5173,
  https://app.dataforge.com
  ```
- **Allowed Origins (CORS)**:
  ```
  http://localhost:5173,
  https://app.dataforge.com
  ```

**Advanced Settings**:
- **Grant Types**: Authorization Code, Refresh Token
- **Refresh Token Rotation**: Enabled
- **Refresh Token Expiration**: 30 days (absolute)
- **Refresh Token Leeway**: 0 seconds

**Save Credentials**:
```bash
# Frontend environment variables (.env.local)
VITE_AUTH0_DOMAIN=dev-example.us.auth0.com
VITE_AUTH0_CLIENT_ID=FrontendClientId123
VITE_AUTH0_AUDIENCE=https://api.dataforge.com
```

---

### Step 2: Create Auth0 Roles

#### 2.1 Create ROLE_USER

```bash
# Navigate to Auth0 Dashboard
# User Management → Roles → Create Role
```

**Configuration**:
- **Name**: `ROLE_USER`
- **Description**: Regular user with access to Data Forge features
- **Permissions**: Add API permissions
  - `Data Forge API`:
    - `read:batches`
    - `write:batches`
    - `read:files`
    - `write:files`

#### 2.2 Create ROLE_ADMIN

**Configuration**:
- **Name**: `ROLE_ADMIN`
- **Description**: Administrator with full access to Data Forge
- **Permissions**: Add API permissions
  - `Data Forge API`: (All permissions)
    - `read:batches`
    - `write:batches`
    - `read:files`
    - `write:files`

**Get Role IDs** (needed for migration script):
```bash
curl -X GET "https://${AUTH0_DOMAIN}/api/v2/roles" \
  -H "Authorization: Bearer ${MGMT_API_TOKEN}" \
  | jq '.[] | {name, id}'

# Output:
# { "name": "ROLE_USER", "id": "rol_abc123" }
# { "name": "ROLE_ADMIN", "id": "rol_xyz789" }
```

---

### Step 3: Create Auth0 Action (Add Custom Claims)

#### 3.1 Create Login Action

```bash
# Navigate to Auth0 Dashboard
# Actions → Flows → Login → Custom
# Click "+" to create new Action
```

**Action Configuration**:
- **Name**: `Add Custom Claims to Tokens`
- **Trigger**: Login / Post-Login
- **Runtime**: Node 18 (Recommended)

**Action Code** (`add-custom-claims.js`):

```javascript
/**
 * @param {Event} event - Details about the user and the context in which they are logging in.
 * @param {PostLoginAPI} api - Interface whose methods can be used to change the behavior of the login.
 */
exports.onExecutePostLogin = async (event, api) => {
  const namespace = 'https://api.dataforge.com';

  // Add roles to both access token and ID token
  if (event.authorization) {
    const roles = event.authorization.roles || [];
    api.idToken.setCustomClaim(`${namespace}/roles`, roles);
    api.accessToken.setCustomClaim(`${namespace}/roles`, roles);

    console.log(`[Auth0 Action] Added roles to token: ${roles.join(', ')}`);
  }

  // Add accountId from app_metadata
  const accountId = event.user.app_metadata?.accountId;
  if (accountId) {
    api.idToken.setCustomClaim(`${namespace}/accountId`, accountId);
    api.accessToken.setCustomClaim(`${namespace}/accountId`, accountId);
    console.log(`[Auth0 Action] Added accountId to token: ${accountId}`);
  } else {
    console.warn(`[Auth0 Action] Missing accountId in app_metadata for user ${event.user.user_id}`);
  }

  // Add email for convenience
  api.idToken.setCustomClaim(`${namespace}/email`, event.user.email);
  api.accessToken.setCustomClaim(`${namespace}/email`, event.user.email);
};
```

#### 3.2 Deploy Action

1. Click "Deploy" button (top right)
2. Navigate to **Actions → Flows → Login**
3. Drag "Add Custom Claims to Tokens" from right panel to the flow
4. Click "Apply" to save the flow

#### 3.3 Test Action

```bash
# Navigate to Auth0 Dashboard
# Actions → Flows → Login → Test (lightning bolt icon)
```

**Test Payload**:
```json
{
  "user": {
    "user_id": "auth0|test123",
    "email": "test@example.com",
    "app_metadata": {
      "accountId": "550e8400-e29b-41d4-a716-446655440000"
    }
  },
  "authorization": {
    "roles": ["ROLE_USER"]
  }
}
```

**Expected Output** (Console Logs):
```
[Auth0 Action] Added roles to token: ROLE_USER
[Auth0 Action] Added accountId to token: 550e8400-e29b-41d4-a716-446655440000
```

---

### Step 4: Configure Database Connection

```bash
# Navigate to Auth0 Dashboard
# Authentication → Database → Create DB Connection
```

**Configuration**:
- **Name**: `Username-Password-Authentication` (default)
- **Disable Sign Ups**: ❌ (users created via Management API only)
- **Requires Username**: ❌
- **Username Length**: N/A
- **Minimum Password Strength**: Good (or Fair for dev)

**Password Policy**:
- Minimum length: 8 characters
- Include lowercase: ✅
- Include uppercase: ✅
- Include numbers: ✅
- Include symbols: ❌

**Brute Force Protection**:
- Enabled: ✅
- Max Attempts: 10
- Shields: Block from unknown IPs

**Enable for Applications**:
- ✅ Data Forge Admin Panel (SPA)

---

## Phase 2: Backend Integration (Week 2)

### Step 1: Update Dependencies

**File**: `build.gradle.kts`

```kotlin
dependencies {
    // Auth0 Management API
    implementation("com.auth0:auth0:2.26.0")
    implementation("com.auth0:java-jwt:4.4.0")

    // Spring Security OAuth2 Resource Server (already present)
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // REMOVE Keycloak dependencies
    // implementation("org.keycloak:keycloak-admin-client:23.0.1") // REMOVE
}
```

```bash
# Refresh dependencies
./gradlew build --refresh-dependencies
```

---

### Step 2: Update Application Configuration

**File**: `src/main/resources/application.yml`

```yaml
# Auth0 Configuration
auth0:
  domain: ${AUTH0_DOMAIN:dev-example.us.auth0.com}
  management-client-id: ${AUTH0_MGMT_CLIENT_ID}
  management-client-secret: ${AUTH0_MGMT_CLIENT_SECRET}
  api-audience: ${AUTH0_AUDIENCE:https://api.dataforge.com}
  database-connection: Username-Password-Authentication
  password-reset-ttl-seconds: 86400 # 24 hours
  roles-namespace: https://api.dataforge.com/roles

# Spring Security OAuth2 Resource Server
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://${auth0.domain}/
          audiences: ${auth0.api-audience}

# REMOVE Keycloak configuration
# keycloak:
#   realm: dataforge
#   auth-server-url: http://localhost:8081
#   ...
```

**File**: `src/main/resources/application-dev.yml`

```yaml
auth0:
  domain: dev-example.us.auth0.com
  management-client-id: ${AUTH0_MGMT_CLIENT_ID}
  management-client-secret: ${AUTH0_MGMT_CLIENT_SECRET}

logging:
  level:
    com.auth0: DEBUG
    com.bitbi.dfm.auth: DEBUG
```

---

### Step 3: Apply Database Migration

**File**: `src/main/resources/db/migration/V012__rename_keycloak_to_identity_provider.sql`

```sql
-- Rename column from keycloak_user_id to identity_provider_user_id
ALTER TABLE accounts
RENAME COLUMN keycloak_user_id TO identity_provider_user_id;

-- Expand column size from VARCHAR(36) to VARCHAR(64)
ALTER TABLE accounts
ALTER COLUMN identity_provider_user_id TYPE VARCHAR(64);

-- Update index name for clarity
DROP INDEX IF EXISTS idx_accounts_keycloak_user_id;
CREATE UNIQUE INDEX idx_accounts_identity_provider_user_id
ON accounts(identity_provider_user_id)
WHERE identity_provider_user_id IS NOT NULL;

-- Add comment for documentation
COMMENT ON COLUMN accounts.identity_provider_user_id IS
'External identity provider user ID. Keycloak format: UUID (36 chars). Auth0 format: auth0|{id} (up to 64 chars). NULL for accounts without external identity integration.';
```

```bash
# Run migration
./gradlew flywayMigrate

# Verify migration
./gradlew flywayInfo
```

---

### Step 4: Configure Spring Security

**File**: `src/main/java/com/bitbi/dfm/auth/config/Auth0SecurityConfig.java`

```java
@Configuration
@EnableWebSecurity
public class Auth0SecurityConfig {

    @Value("${auth0.roles-namespace}")
    private String rolesNamespace;

    @Value("${auth0.api-audience}")
    private String audience;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Bean
    @Order(1)
    public SecurityFilterChain auth0FilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/admin/**")
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder())
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())
                )
            )
            .csrf(csrf -> csrf.disable());

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuerUri);

        // Custom audience validator
        OAuth2TokenValidator<Jwt> audienceValidator = new AudienceValidator(audience);
        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuerUri);
        OAuth2TokenValidator<Jwt> withAudience =
            new DelegatingOAuth2TokenValidator<>(withIssuer, audienceValidator);

        decoder.setJwtValidator(withAudience);
        return decoder;
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter =
            new JwtGrantedAuthoritiesConverter();

        // Extract roles from Auth0 custom claim
        grantedAuthoritiesConverter.setAuthoritiesClaimName(rolesNamespace);
        grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        return converter;
    }
}
```

**File**: `src/main/java/com/bitbi/dfm/auth/config/AudienceValidator.java`

```java
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {
    private final String audience;

    public AudienceValidator(String audience) {
        this.audience = audience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (jwt.getAudience().contains(audience)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(
            new OAuth2Error("invalid_token", "Required audience not found", null)
        );
    }
}
```

---

### Step 5: Test Backend Integration

```bash
# Start application
./gradlew bootRun --args='--spring.profiles.active=dev'

# Get Management API token (for testing)
curl -X POST "https://${AUTH0_DOMAIN}/oauth/token" \
  -H "Content-Type: application/json" \
  -d '{
    "client_id": "'"${AUTH0_MGMT_CLIENT_ID}"'",
    "client_secret": "'"${AUTH0_MGMT_CLIENT_SECRET}"'",
    "audience": "https://'"${AUTH0_DOMAIN}"'/api/v2/",
    "grant_type": "client_credentials"
  }' | jq -r '.access_token'

# Test JWT validation endpoint
curl -X GET "http://localhost:8080/api/admin/accounts" \
  -H "Authorization: Bearer ${YOUR_AUTH0_JWT}"
```

---

## Phase 3: Frontend Integration (Week 2-3)

### Step 1: Install Dependencies

```bash
cd frontend
npm install @auth0/auth0-react
```

**File**: `frontend/package.json`

```json
{
  "dependencies": {
    "@auth0/auth0-react": "^2.8.0",
    "react": "^19.2.0",
    "react-dom": "^19.2.0"
  }
}
```

---

### Step 2: Configure Auth0Provider

**File**: `frontend/src/main.tsx`

```typescript
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { Auth0Provider } from '@auth0/auth0-react';
import { BrowserRouter } from 'react-router-dom';
import App from './App';

const domain = import.meta.env.VITE_AUTH0_DOMAIN!;
const clientId = import.meta.env.VITE_AUTH0_CLIENT_ID!;
const audience = import.meta.env.VITE_AUTH0_AUDIENCE!;

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <Auth0Provider
        domain={domain}
        clientId={clientId}
        authorizationParams={{
          redirect_uri: window.location.origin,
          audience: audience,
          scope: 'openid profile email'
        }}
        useRefreshTokens={true}
        cacheLocation="memory"
        onRedirectCallback={(appState) => {
          window.history.replaceState(
            {},
            document.title,
            appState?.returnTo || window.location.pathname
          );
        }}
      >
        <App />
      </Auth0Provider>
    </BrowserRouter>
  </StrictMode>
);
```

---

### Step 3: Create Authentication Guard

**File**: `frontend/src/shared/lib/auth/AuthenticationGuard.tsx`

```typescript
import { ComponentType } from 'react';
import { withAuthenticationRequired } from '@auth0/auth0-react';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';

interface AuthenticationGuardProps {
  component: ComponentType;
}

export function AuthenticationGuard({ component }: AuthenticationGuardProps) {
  const Component = withAuthenticationRequired(component, {
    onRedirecting: () => <LoadingSpinner />,
    returnTo: window.location.pathname,
  });

  return <Component />;
}
```

---

### Step 4: Update Protected Routes

**File**: `frontend/src/app/routes.tsx`

```typescript
import { Routes, Route } from 'react-router-dom';
import { AuthenticationGuard } from '@/shared/lib/auth/AuthenticationGuard';
import { DashboardPage } from '@/pages/dashboard';
import { ProfilePage } from '@/pages/profile';

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route
        path="/dashboard"
        element={<AuthenticationGuard component={DashboardPage} />}
      />
      <Route
        path="/profile"
        element={<AuthenticationGuard component={ProfilePage} />}
      />
    </Routes>
  );
}
```

---

### Step 5: Test Frontend Integration

```bash
# Start frontend
npm run dev

# Open browser
open http://localhost:5173

# Click login button → should redirect to Auth0 Universal Login
# After login → should redirect back to app with user info
```

---

## Phase 4: Migration Execution (Week 3-4)

### Step 1: Export Keycloak Users

**Script**: `scripts/export-keycloak-users.sh`

```bash
#!/bin/bash

KEYCLOAK_URL="https://keycloak.example.com"
REALM="dataforge"
ADMIN_TOKEN="your-admin-token"

curl -X GET "${KEYCLOAK_URL}/admin/realms/${REALM}/users?max=10000" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  | jq '.[] | {
      email: .email,
      firstName: .firstName,
      lastName: .lastName,
      username: .username,
      roles: .realmRoles,
      accountId: .attributes.accountId[0]
    }' > keycloak-users-export.json

echo "Exported $(jq '. | length' keycloak-users-export.json) users"
```

---

### Step 2: Run Migration Script

**File**: `src/main/java/com/bitbi/dfm/migration/KeycloakToAuth0Migration.java`

See `research-migration-strategy.md` for complete migration script.

```bash
# Run migration
./gradlew bootRun --args='--spring.profiles.active=migration'

# Monitor progress
tail -f logs/migration.log
```

---

## Verification Checklist

### Backend
- [ ] Auth0 Management API connection works
- [ ] JWT validation works with Auth0 tokens
- [ ] Custom claims (roles, accountId) extracted correctly
- [ ] Admin endpoints require ROLE_ADMIN
- [ ] Password reset returns link (not temporary password)
- [ ] Blocked users cannot login
- [ ] Database migration applied successfully

### Frontend
- [ ] Login redirects to Auth0 Universal Login
- [ ] After login, user redirected back to app
- [ ] Protected routes require authentication
- [ ] User info displayed correctly
- [ ] Logout works and clears session
- [ ] Refresh tokens work on page reload

### Migration
- [ ] All users exported from Keycloak
- [ ] All users imported to Auth0
- [ ] Roles assigned correctly
- [ ] Password reset emails sent
- [ ] PostgreSQL updated with Auth0 user IDs

---

## Troubleshooting

### Backend Issues

**Problem**: JWT validation fails with "Invalid issuer"

**Solution**: Check `issuer-uri` in application.yml matches Auth0 domain:
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://dev-example.us.auth0.com/ # Must end with /
```

**Problem**: Custom claims not found in JWT

**Solution**: Verify Auth0 Action is deployed and attached to Login flow:
```bash
# Check Action logs in Auth0 Dashboard
# Actions → Library → Add Custom Claims to Tokens → Logs
```

### Frontend Issues

**Problem**: "Redirect URI mismatch" error

**Solution**: Add redirect URI to Auth0 Application settings:
```
Auth0 Dashboard → Applications → Data Forge Admin Panel → Settings → Allowed Callback URLs
```

**Problem**: Token refresh fails

**Solution**: Enable refresh token rotation:
```
Auth0 Dashboard → Applications → Data Forge Admin Panel → Settings → Advanced Settings → Grant Types
Check: Authorization Code, Refresh Token
```

---

## Next Steps

1. **Week 4**: Monitor migration progress, address password reset completion rate
2. **Week 5**: Cutover to Auth0 only, remove Keycloak fallback
3. **Week 6+**: Monitor for 90 days, then decommission Keycloak

---

**Status**: Ready for implementation
**Estimated Time**: 4 weeks (1 developer)
**Prerequisites**: Auth0 tenant setup complete
