# Auth0 Migration Research: Keycloak to Auth0 User Migration Strategy

**Research Date**: 2025-11-06
**Purpose**: Production-grade migration strategy for existing Keycloak users to Auth0
**Scope**: User data export, bulk import, role migration, password handling, and rollback strategies

---

## 1. Keycloak User Export

### Decision: Keycloak Admin REST API with Pagination
**Recommended approach**: Use Keycloak Admin REST API `/admin/realms/{realm}/users` endpoint with pagination for bulk user export.

### Rationale:
1. **Most flexible method**: Works with running Keycloak instances without downtime
2. **Programmatic control**: Enables scripting and automation for large-scale exports
3. **Granular data access**: Can fetch users, roles, and role mappings separately through dedicated endpoints
4. **Pagination support**: Handles large datasets (1000+ users) efficiently with `first` and `max` query parameters
5. **Production-ready**: No need to stop Keycloak nodes (unlike CLI export)
6. **Selective export**: Can filter users by attributes, unlike full realm exports

### Technical Implementation:

**Step 1: Obtain Access Token**
```bash
curl https://$KEYCLOAK_HOST/auth/realms/master/protocol/openid-connect/token \
    -d "client_id=admin-cli" \
    -d "username=$ADMIN_NAME" \
    -d "password=$ADMIN_PASSWORD" \
    -d "grant_type=password"
```

**Step 2: Export Users (Paginated)**
```bash
# Export users in batches of 500
MAX=500
FIRST=0
while true; do
  RESPONSE=$(curl -X GET \
    "${KEYCLOAK_URL}/auth/admin/realms/${REALM_NAME}/users?max=${MAX}&first=${FIRST}" \
    -H "Authorization: Bearer ${ACCESS_TOKEN}")

  # Save to file
  echo "${RESPONSE}" >> users_batch_${FIRST}.json

  # Check if we got fewer than MAX results (last page)
  COUNT=$(echo "${RESPONSE}" | jq '. | length')
  if [ "$COUNT" -lt "$MAX" ]; then
    break
  fi

  FIRST=$((FIRST + MAX))
done
```

**Step 3: Export Realm Roles**
```bash
curl -X GET "${KEYCLOAK_URL}/auth/admin/realms/${REALM_NAME}/roles" \
     -H "Authorization: Bearer ${ACCESS_TOKEN}" \
     > realm_roles.json
```

**Step 4: Export User Role Mappings**
```bash
# For each user, fetch their role assignments
for user_id in $(jq -r '.[].id' users.json); do
  curl -X GET \
    "${KEYCLOAK_URL}/auth/admin/realms/${REALM_NAME}/users/${user_id}/role-mappings" \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    >> user_role_mappings.json
done
```

### Alternatives Considered:

**Alternative 1: CLI Export (`kc.sh export`)**
- **Pros**: Exports complete realm with users and roles in single command
- **Cons**:
  - Requires stopping all Keycloak nodes (production downtime)
  - File size issues for databases >50,000 users
  - Passwords masked in output (cannot export hashes)
  - Not suitable for running instances
- **Verdict**: Rejected due to downtime requirements

**Alternative 2: Admin Console Partial Export**
- **Pros**: User-friendly GUI interface
- **Cons**:
  - Does NOT export users at all (realm settings only)
  - Passwords masked with `*` symbols
  - Not suitable for user migration
- **Verdict**: Rejected - cannot export user data

**Alternative 3: Direct Database Export (PostgreSQL dump)**
- **Pros**: Complete data access including password hashes
- **Cons**:
  - Tightly coupled to Keycloak's internal schema (brittle)
  - Breaking changes between Keycloak versions
  - Violates encapsulation (bypasses API layer)
  - Complex data transformations required
- **Verdict**: Rejected - too fragile and maintenance-heavy

### Data Extractable via REST API:
- User ID (Keycloak UUID)
- Email (username)
- First name / last name
- Email verification status
- Enabled/disabled status
- Created timestamp
- User attributes (custom metadata)
- Realm role assignments
- Client role assignments
- Federated identities (social logins)
- **NOT AVAILABLE**: Password hashes (Keycloak does not expose these via API for security)

### Critical Limitations:
1. **No password hash export**: Keycloak Admin API does NOT expose password hashes for security reasons
2. **Pagination required**: Default limit is 100 users; must use `max` parameter for larger batches
3. **Rate limiting**: Must respect Keycloak's rate limits (typically not restrictive for admin API)
4. **Token expiration**: Access tokens expire (typically 1-5 minutes); may need refresh for long exports
5. **Keycloak version differences**: URL paths changed in Keycloak 17+ (removed `/auth` prefix)

---

## 2. Auth0 User Import Patterns

### Decision: Bulk User Import Job API with Post-Migration Role Assignment
**Recommended approach**: Use Auth0 Management API `POST /api/v2/jobs/users-imports` for bulk user import, followed by scripted role assignment.

### Rationale:
1. **Optimized for bulk operations**: Designed for large-scale migrations (500KB batches = ~2000 users per file)
2. **Supports password hashes**: Can import PBKDF2-SHA256 hashes from Keycloak (see section 4)
3. **Job-based processing**: Asynchronous execution with status tracking and error reporting
4. **Upsert capability**: `upsert: true` flag allows re-importing users with corrections
5. **Metadata support**: Can include `app_metadata` with `accountId` reference during import
6. **Production-tested**: Official Auth0 migration path used by thousands of customers

### Technical Implementation:

**Step 1: Prepare Import JSON File (500KB max)**
```json
[
  {
    "email": "user@example.com",
    "email_verified": true,
    "user_metadata": {
      "first_name": "John",
      "last_name": "Doe"
    },
    "app_metadata": {
      "accountId": "uuid-from-postgresql",
      "legacy_keycloak_id": "keycloak-user-uuid",
      "migrated_at": "2025-11-06T00:00:00Z"
    },
    "custom_password_hash": {
      "algorithm": "pbkdf2",
      "hash": {
        "value": "$pbkdf2-sha256$i=27500,l=64$9xdUcOPkoYYS/kr2URMArw$hashedvalue",
        "encoding": "utf8"
      }
    }
  }
]
```

**Step 2: Submit Import Job**
```bash
curl -X POST "https://${AUTH0_DOMAIN}/api/v2/jobs/users-imports" \
  -H "Authorization: Bearer ${MGMT_API_TOKEN}" \
  -F "users=@users_batch_1.json" \
  -F "connection_id=${DB_CONNECTION_ID}" \
  -F "upsert=false" \
  -F "send_completion_email=true"
```

**Step 3: Monitor Job Status**
```bash
# Response includes job_id
JOB_ID="job_abc123"

# Poll job status
curl -X GET "https://${AUTH0_DOMAIN}/api/v2/jobs/${JOB_ID}" \
  -H "Authorization: Bearer ${MGMT_API_TOKEN}"

# Get error details if failed
curl -X GET "https://${AUTH0_DOMAIN}/api/v2/jobs/${JOB_ID}/errors" \
  -H "Authorization: Bearer ${MGMT_API_TOKEN}"
```

**Step 4: Role Assignment (Separate Script - See Section 3)**

### Rate Limits & Concurrency:
- **File size limit**: 500KB per import job (~1500-2500 users depending on metadata size)
- **Concurrent jobs**: Maximum 2 concurrent import jobs per tenant
- **Job timeout**: 2 hours per job (auto-fail after timeout)
- **Data retention**: Job results deleted after 24 hours (must download error logs immediately)
- **Management API rate limits**:
  - Free/Trial: 2 req/sec (burst: 10 req/sec)
  - Paid: 15 req/sec (burst: 50 req/sec)
  - Exceeding limits returns HTTP 429

### Error Handling:
Common error codes from import jobs:
- `DUPLICATED_USER`: User already exists (resolve by deleting via "Delete a Connection User" endpoint)
- `CONFLICT_EMAIL`/`CONFLICT_USERNAME`: Email/username collision
- `MFA_FACTORS_FAILED`: Invalid MFA enrollment data
- `FORMAT`/`PATTERN`: Schema validation errors
- `INVALID_PASSWORD_HASH`: Unsupported or malformed password hash

**Retry Strategy**:
```javascript
// Exponential backoff for 429 responses
async function importBatchWithRetry(batch, maxRetries = 5) {
  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    try {
      const result = await auth0.jobs.importUsers(batch);
      return result;
    } catch (error) {
      if (error.statusCode === 429 && attempt < maxRetries) {
        const delay = Math.pow(2, attempt) * 1000; // 2s, 4s, 8s, 16s, 32s
        await sleep(delay);
      } else {
        throw error;
      }
    }
  }
}
```

### Alternatives Considered:

**Alternative 1: Management API Individual User Creation (POST /api/v2/users)**
- **Pros**: Immediate role assignment possible, granular control
- **Cons**:
  - Extremely slow for bulk operations (1 API call per user)
  - High risk of rate limiting (15 req/sec = 900 users/min max)
  - No batch error handling
  - For 10,000 users: ~12 minutes minimum (unrealistic with rate limit handling)
- **Verdict**: Rejected - not suitable for bulk migration

**Alternative 2: Automatic Migration with Custom Database Connection**
- **Pros**: Gradual migration as users log in, no password reset required
- **Cons**:
  - Requires keeping Keycloak running indefinitely
  - Users who never log in never migrate
  - Complex custom database scripts (Login + GetUser)
  - Keycloak must remain accessible from Auth0 (network/firewall concerns)
  - Doubles authentication latency during migration period
- **Verdict**: Rejected for full migration - better for gradual phased approach

**Alternative 3: Auth0 User Import/Export Extension**
- **Pros**: GUI-based import tool
- **Cons**:
  - Deprecated and no longer maintained by Auth0
  - Less flexible than Management API
  - Same underlying API limitations
- **Verdict**: Rejected - use official Management API instead

### Best Practices:
1. **Split large datasets**: Create 500KB batches to stay under file size limit
2. **Monitor concurrent jobs**: Only submit next batch when previous job completes
3. **Store job results externally**: Download error logs immediately (24-hour deletion)
4. **Use `upsert: true` for corrections**: Allows fixing import errors by re-importing users
5. **Test with small batch first**: Validate password hash format with 10-20 users before full migration
6. **Remove `auth0|` prefixes**: If exporting from Auth0 to reimport, strip `auth0|` prefix from user IDs
7. **Implement exponential backoff**: Handle 429 responses gracefully with increasing delays

---

## 3. Role Migration Patterns

### Decision: Two-Phase Role Migration with Management API
**Recommended approach**: Pre-create roles in Auth0, then assign roles to users via Management API after bulk import completes.

### Rationale:
1. **Bulk import limitation**: Auth0's bulk user import does NOT support role assignment in the JSON schema
2. **Data separation**: Auth0 stores RBAC data separately from user profiles (architectural decision)
3. **Flexibility**: Allows role mapping transformations (e.g., Keycloak `ROLE_ADMIN` → Auth0 `admin` role)
4. **Batch operations**: Can assign roles to multiple users in a single API call
5. **Auditable**: Role assignments create audit logs for compliance tracking

### Keycloak Role Hierarchy:
```
Keycloak Realm Roles:
├── ROLE_ADMIN (admin users)
├── ROLE_USER (standard users)
└── ROLE_VIEWER (read-only users)

Keycloak Client Roles (optional):
└── dataforge-client
    ├── batch.upload
    ├── batch.view
    └── error.log
```

### Auth0 Role Mapping Strategy:

**Mapping Table**:
| Keycloak Realm Role | Auth0 Role | Auth0 Permissions |
|---------------------|------------|-------------------|
| `ROLE_ADMIN` | `admin` | `read:users`, `create:users`, `update:users`, `delete:users`, `manage:sites` |
| `ROLE_USER` | `user` | `upload:files`, `view:batches`, `view:history` |
| `ROLE_VIEWER` | `viewer` | `view:batches`, `view:history` |

**Note**: Keycloak client roles can be mapped to Auth0 API permissions if using Auth0's API authorization.

### Technical Implementation:

**Phase 1: Create Roles in Auth0 (One-Time Setup)**
```bash
# Create admin role
curl -X POST "https://${AUTH0_DOMAIN}/api/v2/roles" \
  -H "Authorization: Bearer ${MGMT_API_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "admin",
    "description": "Administrator role - migrated from Keycloak ROLE_ADMIN"
  }'

# Response includes role_id (e.g., "rol_abc123")
```

**Phase 2: Batch Role Assignment Script**
```javascript
const { ManagementClient } = require('auth0');

const auth0 = new ManagementClient({
  domain: process.env.AUTH0_DOMAIN,
  clientId: process.env.AUTH0_CLIENT_ID,
  clientSecret: process.env.AUTH0_CLIENT_SECRET,
  scope: 'read:users read:roles update:users'
});

// Map Keycloak roles to Auth0 role IDs
const roleMapping = {
  'ROLE_ADMIN': 'rol_admin_id',
  'ROLE_USER': 'rol_user_id',
  'ROLE_VIEWER': 'rol_viewer_id'
};

// Process users from Keycloak export with role assignments
async function migrateUserRoles(keycloakUsers, userRoleMappings) {
  for (const keycloakUser of keycloakUsers) {
    try {
      // Find Auth0 user by email (or legacy_keycloak_id in app_metadata)
      const auth0Users = await auth0.getUsersByEmail(keycloakUser.email);
      if (auth0Users.length === 0) {
        console.error(`User not found: ${keycloakUser.email}`);
        continue;
      }

      const auth0User = auth0Users[0];

      // Get Keycloak roles for this user
      const keycloakRoles = userRoleMappings[keycloakUser.id]?.realmRoles || [];

      // Map to Auth0 role IDs
      const auth0RoleIds = keycloakRoles
        .map(roleName => roleMapping[roleName])
        .filter(roleId => roleId !== undefined);

      if (auth0RoleIds.length === 0) {
        console.log(`No roles to assign for user: ${keycloakUser.email}`);
        continue;
      }

      // Assign roles (single API call per user)
      await auth0.assignRolestoUser(
        { id: auth0User.user_id },
        { roles: auth0RoleIds }
      );

      console.log(`✓ Assigned ${auth0RoleIds.length} roles to ${keycloakUser.email}`);

      // Rate limit handling: 15 req/sec for paid, 2 req/sec for free
      await sleep(100); // 10 users/sec (safe for paid plans)

    } catch (error) {
      console.error(`Failed to assign roles to ${keycloakUser.email}:`, error.message);
      // Log to error file for retry
      fs.appendFileSync('role_assignment_errors.log',
        JSON.stringify({ email: keycloakUser.email, error: error.message }) + '\n'
      );
    }
  }
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}
```

**Management API Endpoint**:
```bash
# Assign roles to a single user
curl -X POST "https://${AUTH0_DOMAIN}/api/v2/users/${USER_ID}/roles" \
  -H "Authorization: Bearer ${MGMT_API_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "roles": ["rol_admin_id", "rol_user_id"]
  }'
```

### Rate Limit Strategy:
- **Paid tenants**: 15 req/sec → max 900 users/min (54,000/hour)
- **Free tenants**: 2 req/sec → max 120 users/min (7,200/hour)
- **Implementation**: Sleep 100ms between requests (10 users/sec = safe for paid, slow for free)
- **Exponential backoff**: Retry 429 responses with increasing delays (2s, 4s, 8s, 16s, 32s)

### Performance Estimates:
- **1,000 users**: ~2 minutes (paid), ~8 minutes (free)
- **10,000 users**: ~20 minutes (paid), ~1.5 hours (free)
- **100,000 users**: ~3 hours (paid), ~14 hours (free)

### Alternatives Considered:

**Alternative 1: Auth0 Authorization Extension**
- **Pros**: Legacy GUI for managing groups/roles/permissions
- **Cons**:
  - Deprecated (feature-frozen, no new development)
  - Separate data model from Auth0 Core RBAC
  - Migration complexity (must move to Core RBAC eventually)
  - Limited API support
- **Verdict**: Rejected - use Auth0 Core RBAC instead

**Alternative 2: Store Roles in app_metadata**
- **Pros**: Can include roles during bulk import (no separate API calls)
- **Cons**:
  - Not real RBAC (just custom claims)
  - No centralized role management
  - Must implement custom authorization logic in application
  - No role-based API permissions
  - Cannot leverage Auth0's built-in RBAC features
- **Verdict**: Rejected - violates Auth0 best practices

**Alternative 3: Manual Role Assignment via Dashboard**
- **Pros**: Simple GUI interface for small datasets
- **Cons**:
  - Only shows first 50 roles in dropdown (limitation)
  - Not feasible for >100 users
  - Human error prone
  - No audit trail or rollback
- **Verdict**: Rejected for bulk migration - only for small corrections

### Critical Considerations:
1. **Dashboard limitation**: If you have >50 roles, only the first 50 appear in the UI; use Management API for all role assignments
2. **Role prerequisites**: Must create roles in Auth0 BEFORE assigning to users (cannot assign non-existent roles)
3. **Error logging**: Store failed role assignments in external log file for retry (crucial for large migrations)
4. **Idempotency**: Assigning the same role twice is safe (Auth0 ignores duplicate assignments)
5. **Verification**: After migration, spot-check user profiles to confirm role assignments succeeded

---

## 4. Password Migration Handling

### Decision: Force Password Reset for All Users (Keycloak Does Not Expose Password Hashes)
**Recommended approach**: Import users WITHOUT passwords, then send password reset emails using Auth0 password change tickets.

### Rationale:
1. **Keycloak limitation**: Admin REST API does NOT expose password hashes for security reasons
2. **Database access risk**: Direct database access to extract hashes is fragile (schema changes) and violates encapsulation
3. **Security benefit**: Forced reset ensures all users get fresh Auth0-managed passwords (bcrypt)
4. **User communication**: Provides opportunity to announce migration and new features
5. **Industry standard**: Common pattern for identity provider migrations

### Auth0 Password Change Ticket Pattern:

**Step 1: Import Users Without Passwords**
```json
{
  "email": "user@example.com",
  "email_verified": true,
  "app_metadata": {
    "accountId": "uuid-from-postgresql",
    "legacy_keycloak_id": "keycloak-user-uuid",
    "requires_password_reset": true
  }
  // NO password or custom_password_hash field
}
```

**Step 2: Send Password Reset Emails (Batch)**
```javascript
const { ManagementClient } = require('auth0');

async function sendPasswordResetEmails(users) {
  const auth0 = new ManagementClient({
    domain: process.env.AUTH0_DOMAIN,
    clientId: process.env.AUTH0_CLIENT_ID,
    clientSecret: process.env.AUTH0_CLIENT_SECRET
  });

  for (const user of users) {
    try {
      // Generate password change ticket
      const ticket = await auth0.createPasswordChangeTicket({
        user_id: user.auth0UserId,
        result_url: 'https://yourdomain.com/password-reset-success',
        ttl_sec: 604800, // 7 days
        mark_email_as_verified: false,
        includeEmailInRedirect: false
      });

      console.log(`✓ Password reset sent to ${user.email}: ${ticket.ticket}`);

      // Rate limiting
      await sleep(100); // 10 req/sec

    } catch (error) {
      console.error(`Failed to send reset to ${user.email}:`, error.message);
      fs.appendFileSync('password_reset_errors.log',
        JSON.stringify({ email: user.email, error: error.message }) + '\n'
      );
    }
  }
}
```

**Step 3: User Experience Flow**
1. User receives email: "Action Required: Set Your New Password"
2. Clicks link in email (valid for 7 days)
3. Redirected to Auth0 Universal Login password reset page
4. Sets new password (Auth0 enforces password policy)
5. Redirected to `result_url` after success
6. User can now log in with new password

### Alternative: Direct Database Access (NOT RECOMMENDED)

If you MUST preserve passwords, you can query Keycloak database directly:

```sql
-- Query Keycloak PostgreSQL database
SELECT
  u.email,
  u.id as keycloak_user_id,
  c.credential_data::jsonb->>'hashIterations' as iterations,
  c.credential_data::jsonb->>'algorithm' as algorithm,
  c.secret_data::jsonb->>'value' as hash,
  c.secret_data::jsonb->>'salt' as salt
FROM
  user_entity u
  INNER JOIN credential c ON c.user_id = u.id
WHERE
  c.type = 'password'
  AND u.realm_id = '<your-realm-id>';
```

**Transform to Auth0 PHC Format**:
```javascript
function transformKeycloakHashToAuth0(keycloakHash, keycloakSalt, iterations = 27500) {
  // Remove base64 padding
  const saltNoPadding = keycloakSalt.replace(/=+$/, '');
  const hashNoPadding = keycloakHash.replace(/=+$/, '');

  // CRITICAL: l=64 for SHA-256 (NOT l=32!)
  const phcString = `$pbkdf2-sha256$i=${iterations},l=64$${saltNoPadding}$${hashNoPadding}`;

  return {
    algorithm: 'pbkdf2',
    hash: {
      value: phcString,
      encoding: 'utf8'
    }
  };
}
```

**Why NOT Recommended**:
1. **Keycloak version coupling**: Schema changes between versions (15, 17, 18, 21+)
2. **PHC format complexity**: Easy to misconfigure (`l=64` vs `l=32` mistake causes login failures)
3. **Testing burden**: Must validate each user can log in (high support cost if hashes fail)
4. **Security concern**: Direct database access bypasses API security controls
5. **One-time use**: Database export creates point-in-time snapshot (users who change password after export will fail)

### Alternatives Considered:

**Alternative 1: Automatic Migration with Custom Database Connection**
- **Pros**: No password reset required, gradual migration
- **Cons**:
  - Keycloak must stay online indefinitely
  - Complex custom database scripts
  - Adds latency to every login (dual authentication)
  - Users who never log in never migrate
- **Verdict**: Rejected for full cutover - better for long-term gradual migration

**Alternative 2: Temporary Passwords (Manual Distribution)**
- **Pros**: Users can log in immediately
- **Cons**:
  - Admin must generate and distribute 1000s of passwords
  - Security risk (passwords sent via email/Slack)
  - Poor user experience (typing random passwords)
  - Auth0 doesn't support Keycloak-style temporary password expiry
- **Verdict**: Rejected - password change tickets provide better UX

**Alternative 3: Social Login Only (Disable Passwords)**
- **Pros**: No password migration needed at all
- **Cons**:
  - Requires all users have social accounts (Google, Microsoft, etc.)
  - Breaking change for users who prefer passwords
  - Not suitable for enterprise B2B scenarios
- **Verdict**: Rejected - passwords are core requirement

### Password Reset Email Template Customization:

Auth0 allows customizing the password reset email:

**Dashboard → Branding → Email Templates → Change Password**

```html
<!DOCTYPE html>
<html>
<head>
  <title>Password Reset Required</title>
</head>
<body>
  <h2>Action Required: Set Your New Password</h2>
  <p>Hello {{user.name}},</p>
  <p>
    We've recently migrated to a new authentication system to improve your
    experience. As part of this migration, you'll need to set a new password
    for your account.
  </p>
  <p>
    <a href="{{url}}">Click here to set your new password</a>
  </p>
  <p>This link will expire in 7 days.</p>
  <p>
    If you didn't request this change, please contact support immediately.
  </p>
  <p>Thanks,<br>The Team</p>
</body>
</html>
```

### Migration Communication Plan:

1. **T-7 days**: Announce migration to all users via email ("Upcoming: Improved Login Experience")
2. **T-1 day**: Reminder email with migration date/time
3. **T-0 (Migration Day)**: Import users to Auth0, send password reset emails
4. **T+1 hour**: Monitor password reset completion rate
5. **T+1 day**: Follow-up email to users who haven't reset (reminder)
6. **T+3 days**: Final reminder email
7. **T+7 days**: Password reset links expire, manual support required

### Success Metrics:
- **Target**: 80% password reset completion within 48 hours
- **Acceptable**: 90% completion within 7 days
- **Monitor**: Support tickets related to password reset (should be <5% of user base)

---

## 5. Migration Rollback Strategy

### Decision: Hybrid Rollback with Legacy System Failover
**Recommended approach**: Keep Keycloak running in read-only mode during migration; implement application-level failover to Keycloak if Auth0 fails; plan 2-week parallel run before Keycloak shutdown.

### Rationale:
1. **Risk mitigation**: Production system remains available if Auth0 migration encounters critical issues
2. **Data preservation**: Keycloak database serves as authoritative backup during transition
3. **Gradual cutover**: Allows testing Auth0 in production with safety net
4. **Compliance**: Maintains audit trail and user data accessibility during migration
5. **Reversibility**: Can roll back to Keycloak within 2-week window if needed

### Migration Timeline:

```
Phase 1: Pre-Migration (Week -2 to Week 0)
├── Export Keycloak users and roles via Admin API
├── Transform data to Auth0 format
├── Test Auth0 import with 50-100 test users
├── Validate authentication works
└── Create rollback plan document

Phase 2: Migration Execution (Week 0)
├── Freeze Keycloak user writes (read-only mode)
├── Import users to Auth0 (bulk import jobs)
├── Send password reset emails to all users
├── Assign roles via Management API
├── Verify user count matches (Keycloak vs Auth0)
└── Deploy application with Auth0 failover

Phase 3: Parallel Run (Week 0 to Week 2)
├── Route 100% traffic to Auth0
├── Keep Keycloak accessible as failover
├── Monitor Auth0 authentication success rates
├── Monitor password reset completion rate
├── Track and resolve import errors
└── New user signups go to Auth0 only

Phase 4: Cutover (Week 2)
├── Verify 80%+ users have reset passwords
├── Verify zero Auth0 authentication failures for 48 hours
├── Disable Keycloak user authentication
├── Archive Keycloak database (cold backup)
└── Decommission Keycloak instance

Phase 5: Post-Migration (Week 3+)
├── Keep Keycloak database backup for 90 days
├── Monitor Auth0 metrics (login success, latency)
├── Resolve any late-discovered migration issues
└── Final Keycloak shutdown after 90 days
```

### Technical Implementation:

**Step 1: Keycloak Read-Only Mode**

Prevent new user registrations and password changes during migration window:

```java
// Keycloak Event Listener SPI (custom extension)
public class ReadOnlyModeEventListener implements EventListenerProvider {

  @Override
  public void onEvent(Event event) {
    // Block user mutations during migration
    if (event.getType() == EventType.REGISTER ||
        event.getType() == EventType.UPDATE_PASSWORD ||
        event.getType() == EventType.UPDATE_EMAIL) {
      throw new ErrorResponseException(
        "read_only_mode",
        "User modifications are temporarily disabled during system migration",
        Response.Status.SERVICE_UNAVAILABLE
      );
    }
  }
}
```

Alternatively, use Keycloak admin console to:
1. Disable user registration in realm settings
2. Disable password reset flow
3. Communicate maintenance window to users

**Step 2: Application Failover Logic**

Implement dual authentication with Auth0 primary, Keycloak fallback:

```java
// Spring Security configuration with failover
@Configuration
public class AuthenticationFailoverConfig {

  @Autowired
  private Auth0JwtDecoder auth0JwtDecoder;

  @Autowired
  private KeycloakJwtDecoder keycloakJwtDecoder;

  @Bean
  public JwtDecoder failoverJwtDecoder() {
    return token -> {
      try {
        // Try Auth0 first (primary)
        return auth0JwtDecoder.decode(token);
      } catch (JwtException auth0Error) {
        logger.warn("Auth0 authentication failed, falling back to Keycloak: {}",
          auth0Error.getMessage());

        try {
          // Fallback to Keycloak (secondary)
          Jwt keycloakJwt = keycloakJwtDecoder.decode(token);

          // Log failover event for monitoring
          metricsRegistry.counter("auth.failover.keycloak").increment();

          return keycloakJwt;
        } catch (JwtException keycloakError) {
          // Both failed - reject authentication
          throw new JwtException("Authentication failed on both Auth0 and Keycloak", auth0Error);
        }
      }
    };
  }
}
```

**Step 3: Database Backup Strategy**

```bash
#!/bin/bash
# Keycloak PostgreSQL backup script

BACKUP_DIR="/backups/keycloak-migration-$(date +%Y%m%d)"
mkdir -p "${BACKUP_DIR}"

# Full database dump (includes password hashes)
pg_dump -h localhost -U keycloak -d keycloak \
  --format=custom \
  --compress=9 \
  --file="${BACKUP_DIR}/keycloak_full_$(date +%Y%m%d_%H%M%S).dump"

# Export critical tables separately (faster restore)
psql -h localhost -U keycloak -d keycloak -c \
  "COPY (SELECT * FROM user_entity) TO '${BACKUP_DIR}/users.csv' CSV HEADER;"

psql -h localhost -U keycloak -d keycloak -c \
  "COPY (SELECT * FROM credential) TO '${BACKUP_DIR}/credentials.csv' CSV HEADER;"

psql -h localhost -U keycloak -d keycloak -c \
  "COPY (SELECT * FROM user_role_mapping) TO '${BACKUP_DIR}/user_roles.csv' CSV HEADER;"

# Verify backup integrity
pg_restore --list "${BACKUP_DIR}/keycloak_full_*.dump" > "${BACKUP_DIR}/backup_contents.txt"

echo "Backup completed: ${BACKUP_DIR}"
```

**Step 4: Rollback Decision Tree**

```
Is Auth0 authentication failing for >10% of users?
├─ YES → Immediate rollback
│   ├── Switch application to Keycloak-only mode
│   ├── Disable Auth0 JWT validation
│   ├── Communicate incident to users
│   └── Root cause analysis (RCA) before retry
│
└─ NO → Continue monitoring
    │
    Are critical features broken in Auth0 (MFA, SSO, etc.)?
    ├─ YES → Rollback within 24 hours
    │   └── Same procedure as above
    │
    └─ NO → Proceed with migration
        │
        Have <50% of users reset passwords after 7 days?
        ├─ YES → Extended parallel run (2 weeks → 4 weeks)
        │   └── Increase communication efforts
        │
        └─ NO → Proceed to cutover after 2 weeks
```

**Step 5: Rollback Execution Script**

```bash
#!/bin/bash
# Emergency rollback to Keycloak

echo "INITIATING EMERGENCY ROLLBACK TO KEYCLOAK"

# Step 1: Update application configuration
kubectl set env deployment/data-forge-api \
  AUTH_PROVIDER=KEYCLOAK \
  AUTH0_ENABLED=false \
  KEYCLOAK_ENABLED=true

# Step 2: Restart application pods
kubectl rollout restart deployment/data-forge-api

# Step 3: Verify Keycloak accessibility
curl -f https://keycloak.yourdomain.com/auth/realms/master/.well-known/openid-configuration

if [ $? -eq 0 ]; then
  echo "✓ Keycloak is accessible"
else
  echo "✗ CRITICAL: Keycloak is not accessible - RESTORE FROM BACKUP"
  exit 1
fi

# Step 4: Monitor application logs
kubectl logs -f deployment/data-forge-api --tail=100

echo "Rollback complete. Monitor authentication success rate."
```

### Alternatives Considered:

**Alternative 1: Big Bang Cutover (No Fallback)**
- **Pros**: Clean migration, no dual-system complexity
- **Cons**:
  - High risk - no safety net if Auth0 fails
  - Downtime required for full cutover
  - Cannot rollback without restoring Keycloak from scratch
- **Verdict**: Rejected - too risky for production

**Alternative 2: Automatic Migration (Custom Database Connection)**
- **Pros**: Zero-downtime gradual migration, no big bang event
- **Cons**:
  - Keycloak must run indefinitely (until 100% migration)
  - Complex custom database scripts
  - Slower user migration (only on login)
  - Higher latency during transition (dual authentication)
- **Verdict**: Rejected for full migration - better for long-term gradual approach

**Alternative 3: Blue-Green Deployment (Dual Tenants)**
- **Pros**: Instant rollback by switching DNS/load balancer
- **Cons**:
  - Requires duplicate Auth0 tenant (cost)
  - User sessions split between tenants (confusing)
  - Data sync complexity (user changes in Keycloak during testing)
- **Verdict**: Rejected - overkill for this migration

### Rollback Triggers:

Initiate rollback if any of these conditions occur:

1. **Authentication failure rate >10%** in first 24 hours
2. **Critical Auth0 outage** (status.auth0.com reports major incident)
3. **Data loss detected** (user count mismatch >1%)
4. **MFA/SSO integration failures** (if using social logins or enterprise SSO)
5. **Compliance violation** (audit logs missing, GDPR data access issues)
6. **User revolt** (>50 support tickets about login issues in first week)

### Monitoring Metrics:

Track these metrics during parallel run:

```javascript
// CloudWatch / Datadog / Grafana dashboard
{
  "auth.auth0.login.success": "counter",          // Auth0 successful logins
  "auth.auth0.login.failure": "counter",          // Auth0 failed logins
  "auth.keycloak.fallback.count": "counter",      // Keycloak failover events
  "auth.login.latency.p95": "timer",              // 95th percentile login latency
  "auth.password.reset.count": "counter",         // Password reset requests
  "auth.password.reset.completed": "counter",     // Completed password resets
  "auth.user.migration.status": "gauge",          // % users migrated
  "auth.auth0.api.errors": "counter"              // Auth0 Management API errors
}

// Alert thresholds
alerts:
  - name: "High Auth0 Failure Rate"
    condition: "auth.auth0.login.failure / auth.auth0.login.success > 0.10"
    severity: "critical"
    action: "page on-call engineer"

  - name: "Low Password Reset Completion"
    condition: "auth.password.reset.completed / total_users < 0.50 after 7 days"
    severity: "warning"
    action: "slack #engineering channel"

  - name: "Auth0 API Degradation"
    condition: "auth.login.latency.p95 > 2000ms"
    severity: "warning"
    action: "slack #engineering channel"
```

### Rollback Success Criteria:

Proceed with Keycloak shutdown ONLY after:
- ✅ 80% password reset completion rate (minimum)
- ✅ 99% authentication success rate for 14 consecutive days
- ✅ Zero critical Auth0 outages during parallel run
- ✅ <10 support tickets related to authentication in past week
- ✅ All admin users successfully logged in via Auth0
- ✅ MFA/SSO integrations tested and working (if applicable)
- ✅ Audit logs verified (all login events captured in Auth0 logs)
- ✅ Keycloak database backed up and archived (tested restore)

---

## 6. Auth0 Rate Limiting Considerations

### Decision: Batch Processing with Exponential Backoff and Job Queue Management
**Recommended approach**: Split user import into 500KB batches, respect 2 concurrent job limit, implement exponential backoff for 429 responses, delay role assignments until import completes.

### Rationale:
1. **Avoid service degradation**: Hitting rate limits causes delays and potential data inconsistencies
2. **Predictable migration time**: Batch processing allows accurate time estimation
3. **Error resilience**: Exponential backoff gracefully handles transient rate limit errors
4. **Cost optimization**: Paid tier limits (15 req/sec) sufficient for most migrations
5. **Production safety**: Prevents overwhelming Auth0 tenant during business hours

### Auth0 Rate Limits Overview:

| Operation | Free/Trial Tier | Paid Tier | Notes |
|-----------|----------------|-----------|-------|
| **Management API (general)** | 2 req/sec (burst: 10) | 15 req/sec (burst: 50) | Applies to most endpoints |
| **Bulk User Import Jobs** | 2 concurrent jobs | 2 concurrent jobs | Shared across entire tenant |
| **Import File Size** | 500KB max | 500KB max | ~1500-2500 users per file |
| **Import Job Timeout** | 2 hours | 2 hours | Auto-fail after timeout |
| **Job Result Retention** | 24 hours | 24 hours | Download errors immediately |
| **POST /users (individual)** | 2 req/sec | 15 req/sec | Not recommended for bulk |
| **POST /users/{id}/roles** | 2 req/sec | 15 req/sec | Use for role assignment |
| **GET /users** | 2 req/sec | 15 req/sec | List/search users |
| **POST /tickets/password-change** | 2 req/sec | 15 req/sec | Password reset emails |

**429 Response Headers**:
```
X-RateLimit-Limit: 15
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1699555200  (Unix timestamp)
```

### Migration Time Estimates:

**Scenario: 10,000 Users (Import + Roles + Password Resets)**

| Phase | Free Tier | Paid Tier |
|-------|-----------|-----------|
| **User Import (bulk)** | ~10 batches × 30 min = **5 hours** | ~10 batches × 15 min = **2.5 hours** |
| **Role Assignment** | 10,000 ÷ 2 req/sec = **1.4 hours** | 10,000 ÷ 15 req/sec = **11 minutes** |
| **Password Reset Emails** | 10,000 ÷ 2 req/sec = **1.4 hours** | 10,000 ÷ 15 req/sec = **11 minutes** |
| **Total Migration Time** | **~8 hours** | **~3 hours** |
| **With 429 Retries (10%)** | **~9 hours** | **~3.5 hours** |

**Scenario: 100,000 Users (Import + Roles + Password Resets)**

| Phase | Free Tier | Paid Tier |
|-------|-----------|-----------|
| **User Import (bulk)** | ~100 batches × 30 min = **50 hours** | ~100 batches × 15 min = **25 hours** |
| **Role Assignment** | 100K ÷ 2 req/sec = **14 hours** | 100K ÷ 15 req/sec = **1.8 hours** |
| **Password Reset Emails** | 100K ÷ 2 req/sec = **14 hours** | 100K ÷ 15 req/sec = **1.8 hours** |
| **Total Migration Time** | **~78 hours (3.25 days)** | **~29 hours (1.2 days)** |

### Best Practices:

1. **Run during off-peak hours**: Schedule migration during low-traffic periods (e.g., 2 AM - 6 AM)
2. **Monitor rate limit headers**: Check `X-RateLimit-Remaining` after every request
3. **Implement circuit breaker**: Stop migration if consecutive 429 errors >10 (possible Auth0 incident)
4. **Log all API calls**: Track request timestamps, response times, and errors for debugging
5. **Test with small batch**: Import 100 users first to verify rate limit handling works
6. **Split by geography**: If multi-region, consider staggered migrations to reduce load
7. **Pre-create roles**: Create all Auth0 roles BEFORE starting user import (avoids rate limit contention)
8. **Batch password resets**: Send password reset emails in batches of 1000 at a time (easier to track progress)

### Alternatives Considered:

**Alternative 1: Ignore Rate Limits (Naive Approach)**
- **Pros**: Faster initial development
- **Cons**:
  - Migration fails with 429 errors
  - Data inconsistencies (partial imports)
  - Must manually retry failed batches
  - Auth0 may throttle tenant further
- **Verdict**: Rejected - production systems must handle rate limits

**Alternative 2: Upgrade to Enterprise Tier**
- **Pros**: Higher rate limits (50 req/sec+), dedicated support
- **Cons**:
  - Significant cost increase ($$$)
  - Overkill for one-time migration
  - Still subject to bulk import limits (2 concurrent jobs)
- **Verdict**: Consider if migration SLA is critical (<8 hours for 100K users)

**Alternative 3: Auth0 Professional Services**
- **Pros**: Auth0 handles migration, dedicated migration engineer
- **Cons**:
  - Expensive ($10K-50K depending on complexity)
  - Less control over process
  - Still subject to same API limits
- **Verdict**: Consider for >500K users or complex custom requirements

---

## Summary & Recommendations

### Recommended Migration Path:

1. **Export Phase (Week -2)**
   - Use Keycloak Admin REST API to export users (paginated, 500 users/batch)
   - Export realm roles and user-role mappings
   - **Cannot export password hashes** (Keycloak API limitation)

2. **Testing Phase (Week -1)**
   - Test import with 50-100 users in Auth0 development tenant
   - Test password reset email flow
   - Validate role assignments
   - Test application authentication with Auth0 JWTs

3. **Migration Execution (Week 0)**
   - **T-0 hours**: Set Keycloak to read-only mode (disable user writes)
   - **T+1 hours**: Start bulk user import to Auth0 (batches of 1500-2500 users, NO passwords)
   - **T+3 hours**: Complete user import, verify user count matches
   - **T+4 hours**: Assign roles via Management API (15 req/sec = ~900 users/min)
   - **T+5 hours**: Send password reset emails to all users (15 req/sec = ~900 users/min)
   - **T+6 hours**: Deploy application with Auth0 authentication + Keycloak failover
   - **T+7 hours**: Route 100% traffic to Auth0, monitor metrics

4. **Parallel Run (Week 0-2)**
   - Keep Keycloak running as failover authentication
   - Monitor Auth0 authentication attempts (expect low success until password resets complete)
   - Monitor password reset completion rate (target: 80% within 7 days)
   - Resolve import errors (retry failed users)
   - Track and fix any late-discovered migration issues

5. **Cutover (Week 2)**
   - Verify 80%+ password reset completion
   - Verify 99% authentication success for users who reset passwords
   - Disable Keycloak authentication (keep database backup)
   - Communicate migration completion to users
   - Archive Keycloak database for 90 days (compliance)

6. **Cleanup (Week 3+)**
   - Monitor Auth0 metrics for 30 days
   - Handle remaining password reset requests (stragglers)
   - Final Keycloak shutdown after 90-day retention period
   - Update runbooks and documentation

### Risk Mitigation:

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| **Users cannot log in (no password)** | Very High | Critical | Send password reset emails immediately after import; clear user communication |
| **Low password reset completion** | High | High | Multi-channel communication (email, dashboard banner); extend cutover window |
| **Auth0 rate limiting** | High | Medium | Implement exponential backoff; run during off-peak hours |
| **Role assignment errors** | Medium | Medium | Log all errors to file; retry failed assignments after main migration |
| **Auth0 service outage** | Low | Critical | Keep Keycloak running as failover; implement application-level failover |
| **Data loss (users missing)** | Low | Critical | Verify user count matches Keycloak export; audit before Keycloak shutdown |

### Success Metrics:

- ✅ 100% of Keycloak users imported to Auth0 (zero missing users)
- ✅ 80%+ password reset completion within 7 days (minimum acceptable)
- ✅ 99% authentication success rate after password reset
- ✅ <50 support tickets related to password reset in first week
- ✅ Zero production outages during migration window
- ✅ All roles successfully assigned to users
- ✅ Keycloak database backed up and archived

### Key Differences from "Preserve Passwords" Approach:

This approach differs significantly from attempting to preserve password hashes:

| Aspect | Password Preservation | Forced Reset (Recommended) |
|--------|----------------------|---------------------------|
| **Keycloak API Support** | ❌ No password hash export | ✅ Full user data export |
| **Database Access Required** | ✅ Yes (fragile) | ❌ No (API-only) |
| **User Experience** | ✅ Seamless (same password) | ⚠️  One-time reset required |
| **Migration Complexity** | ❌ High (hash transformation) | ✅ Low (standard import) |
| **Security** | ⚠️  Preserves old hashes | ✅ Fresh Auth0 bcrypt hashes |
| **Testing Burden** | ❌ High (validate each hash) | ✅ Low (standard flow) |
| **Rollback Risk** | ❌ High (hash errors) | ✅ Low (users just reset again) |
| **Support Burden** | ❌ High (login failures) | ⚠️  Medium (reset assistance) |

---

## Additional Resources

### Auth0 Documentation:
- [Bulk User Import](https://auth0.com/docs/manage-users/user-migration/bulk-user-imports)
- [Bulk User Import Schema](https://auth0.com/docs/manage-users/user-migration/bulk-user-import-database-schema-and-examples)
- [Configure Automatic Migration](https://auth0.com/docs/manage-users/user-migration/configure-automatic-migration-from-your-database)
- [Assign Roles to Users](https://auth0.com/docs/manage-users/access-control/configure-core-rbac/rbac-users/assign-roles-to-users)
- [Management API Rate Limits](https://auth0.com/docs/troubleshoot/customer-support/operational-policies/rate-limit-policy/management-api-endpoint-rate-limits)
- [Password Change Tickets](https://auth0.com/docs/api/management/v2#!/Tickets/post_password_change)

### Keycloak Documentation:
- [Keycloak Admin REST API](https://www.keycloak.org/docs-api/latest/rest-api/index.html)
- [Importing and Exporting Realms](https://www.keycloak.org/server/importExport)

### Community Resources:
- [Auth0 Community: Keycloak Migration](https://community.auth0.com/t/migrate-users-from-keycloak-to-auth0-custom-password-hash-problems/99467)
- [Auth0 Community: Role Assignment During Import](https://community.auth0.com/t/assign-role-to-users-when-bulk-importing-via-management-api/37304)
- [Stack Overflow: Export Keycloak Users](https://stackoverflow.com/questions/65200310/export-users-and-roles-from-keycloak)

---

**Document Version**: 1.0
**Last Updated**: 2025-11-06
**Prepared By**: Claude Code (AI Assistant)
**Review Status**: Ready for review

**IMPORTANT NOTE**: This migration strategy recommends **forcing all users to reset passwords** due to Keycloak Admin API not exposing password hashes. Alternative approaches involving direct database access are technically possible but NOT recommended due to fragility, complexity, and security concerns.
