# Auth0 Java SDK Integration Research

**Date**: 2025-11-06
**Purpose**: Migration from Keycloak to Auth0 for Spring Boot 3.5.6 application
**Researcher**: Claude Code

---

## 1. Auth0 Java SDK Dependencies

### Decision: Use auth0-java 2.26.0 for Management API + Spring OAuth2 Resource Server for JWT validation

**Recommended Dependencies (Gradle):**

```kotlin
// Auth0 Management API Client
implementation("com.auth0:auth0:2.26.0")

// Spring Security OAuth2 Resource Server (JWT validation)
implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
implementation("org.springframework.boot:spring-boot-starter-security")
```

**Rationale:**
- **auth0-java 2.26.0** (released October 24, 2025) is the latest stable version with full Management API support
- **Spring OAuth2 Resource Server** is the recommended approach for Spring Boot 3.x + Spring Security 6 JWT validation
- No need for deprecated Keycloak adapters or proprietary Auth0 Spring libraries
- Follows Spring Security 6 best practices with native OAuth2 support
- Java 8+ compatible (application uses Java 21)

**Alternatives Considered:**
1. **okta-spring-boot-starter 3.0.5**: Simpler configuration but limited customization for Auth0-specific features (namespaced claims). Community feedback indicates difficulty overriding default JwtAuthenticationConverter.
2. **auth0-spring-security-api 1.0.0**: Deprecated legacy library, not compatible with Spring Security 6.
3. **Direct REST API calls**: No SDK - would require manual HTTP client management, token handling, and error parsing. Rejected due to complexity and lack of type safety.

**Known Limitations:**
- auth0-java SDK does **NOT** support automatic token refresh. Manual implementation required (see Section 2).
- No built-in retry logic for Management API calls - must implement manually.

---

## 2. Auth0 Management API Best Practices

### Decision: Implement custom token provider with caching, exponential backoff retry, and rate limit monitoring

**Recommended Implementation Pattern:**

```java
// Token Provider with Caching (24-hour TTL)
public class CachedAuth0TokenProvider implements TokenProvider {
    private String cachedToken;
    private Instant tokenExpiry;
    private final AuthAPI authAPI;

    public CachedAuth0TokenProvider(String domain, String clientId, String clientSecret) {
        this.authAPI = AuthAPI.newBuilder(domain, clientId, clientSecret).build();
    }

    @Override
    public String getToken() {
        if (cachedToken == null || Instant.now().isAfter(tokenExpiry)) {
            refreshToken();
        }
        return cachedToken;
    }

    private void refreshToken() {
        AuthRequest request = authAPI.requestToken("https://{domain}/api/v2/");
        TokenHolder holder = request.execute().getBody();
        this.cachedToken = holder.getAccessToken();
        this.tokenExpiry = Instant.now().plusSeconds(holder.getExpiresIn() - 300); // 5 min buffer
    }
}

// ManagementAPI Client with Custom Token Provider
ManagementAPI mgmt = ManagementAPI.newBuilder(domain, tokenProvider).build();
```

**Retry Logic with Exponential Backoff:**

```java
public class Auth0ManagementApiClient {
    private final ManagementAPI mgmt;
    private final RetryTemplate retryTemplate;

    public Auth0ManagementApiClient(ManagementAPI mgmt) {
        this.mgmt = mgmt;
        this.retryTemplate = RetryTemplate.builder()
            .maxAttempts(3)
            .exponentialBackoff(1000, 2.0, 10000) // 1s, 2s, 4s
            .retryOn(RateLimitException.class)
            .build();
    }

    public User getUser(String userId) {
        return retryTemplate.execute(ctx -> {
            Request<User> request = mgmt.users().get(userId, null);
            return request.execute().getBody();
        });
    }
}
```

**Rate Limit Monitoring:**

```java
// Check X-RateLimit-Remaining header before API calls
private void checkRateLimit(Response<?> response) {
    String remaining = response.getHeaders().get("X-RateLimit-Remaining");
    if (remaining != null && Integer.parseInt(remaining) < 5) {
        logger.warn("Approaching rate limit: {} requests remaining", remaining);
        // Consider delaying next request
    }
}
```

**Rationale:**
- **Token Caching**: Management API tokens valid for 24 hours - caching avoids unnecessary Authentication API calls (reduces latency by 200-500ms per operation)
- **Exponential Backoff**: Auth0 rate limits are **15 req/s** (paid tier) with bursts up to 50 req/s. Exponential backoff prevents retry storms during throttling (HTTP 429).
- **Rate Limit Headers**: `X-RateLimit-Remaining` header provides proactive warning before hitting limits
- **5-Minute Token Buffer**: Refresh token 5 minutes before expiry to avoid race conditions

**Alternatives Considered:**
1. **SimpleTokenProvider (SDK default)**: No caching, no automatic refresh - requires manual `setApiToken()` calls. Rejected due to poor performance (extra Authentication API call per Management API request).
2. **Redis-backed token cache**: Overkill for single-instance application. Consider if deploying multi-instance clusters.
3. **Fixed delay retry**: Less effective than exponential backoff during sustained throttling.

**Auth0 Rate Limits:**
- **Paid Tenants**: 15 req/s sustained, 50 req/s burst (token bucket algorithm)
- **Free/Trial Tenants**: 2 req/s sustained, 10 req/s burst
- **Per-Endpoint Limits**: Some endpoints (e.g., `/api/v2/users` POST) have stricter limits (see [Auth0 Rate Limit Policy](https://auth0.com/docs/troubleshoot/customer-support/operational-policies/rate-limit-policy/management-api-endpoint-rate-limits))

**Monitoring Recommendations:**
- Log all 429 responses with MDC context (operation, userId, accountId)
- Micrometer metric: `auth0.api.rate_limit.exceeded` (counter)
- Alert if rate limit hit >10 times/hour (indicates insufficient throttling)

---

## 3. Spring Security 6 + Auth0 JWT Validation

### Decision: Use spring.security.oauth2.resourceserver.jwt.issuer-uri with custom JwtAuthenticationConverter for permissions/roles extraction

**Configuration (application.yml):**

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://${auth0.domain}/  # Auto-discovers JWKS endpoint
          audiences: ${auth0.audience}  # Your Auth0 API Identifier

auth0:
  domain: your-tenant.us.auth0.com
  audience: https://your-api-identifier
```

**Security Configuration (Java):**

```java
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    @Value("${auth0.audience}")
    private String audience;

    @Bean
    public SecurityFilterChain adminApiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/admin/**")
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/admin/**").hasAuthority("ROLE_ADMIN")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder())
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())
                )
            );
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder jwtDecoder = JwtDecoders.fromIssuerLocation(issuerUri);

        // Validate audience claim
        OAuth2TokenValidator<Jwt> audienceValidator = new AudienceValidator(audience);
        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuerUri);
        OAuth2TokenValidator<Jwt> withAudience = new DelegatingOAuth2TokenValidator<>(withIssuer, audienceValidator);

        jwtDecoder.setJwtValidator(withAudience);
        return jwtDecoder;
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new Auth0PermissionsConverter());
        return converter;
    }
}

// Custom audience validator
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
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null));
    }
}
```

**Rationale:**
- **issuer-uri**: Enables auto-discovery of JWKS endpoint (`/.well-known/jwks.json`) - no manual key management required
- **Audience Validation**: Prevents token reuse across different APIs (critical security control)
- **Separate Filter Chains**: Admin API (`/api/admin/**`) uses Auth0 OAuth2, Client API (`/api/dfc/**`) can continue using custom JWT (backward compatibility during migration)
- **JwtAuthenticationConverter**: Extracts roles/permissions from Auth0 claims (see Section 4)

**Alternatives Considered:**
1. **jwk-set-uri**: Direct JWKS URL configuration. Rejected - issuer-uri is more flexible (handles issuer validation automatically).
2. **okta-spring-boot-starter**: Simplified config but locks into Okta/Auth0 conventions. Rejected due to limited control over JwtAuthenticationConverter.
3. **Manual JWT parsing**: Use io.jsonwebtoken (JJWT) library. Rejected - Spring OAuth2 Resource Server is production-ready and well-tested.

**Automatic Validation:**
Spring Security automatically validates:
- **exp (expiration)**: Token not expired
- **nbf (not before)**: Token not used too early
- **iss (issuer)**: Matches configured issuer-uri
- **aud (audience)**: Matches configured audience (with custom validator)
- **Signature**: RSA256/RS512 signature verified against JWKS public keys
- **Clock skew**: 60-second tolerance for timestamp validation

---

## 4. Auth0 Custom Claims Extraction (Roles/Permissions)

### Decision: Use namespaced custom claims (https://your-domain/roles) extracted via custom Converter<Jwt, Collection<GrantedAuthority>>

**Auth0 Actions - Add Custom Claims (JavaScript):**

```javascript
/**
 * Handler that will be called during the execution of a PostLogin flow.
 *
 * @param {Event} event - Details about the user and the context in which they are logging in.
 * @param {PostLoginAPI} api - Methods and utilities to help change the behavior of the login.
 */
exports.onExecutePostLogin = async (event, api) => {
  const namespace = 'https://dataforge-api.example.com';

  // Add roles to both access token and ID token
  if (event.authorization) {
    const roles = event.authorization.roles || [];
    api.accessToken.setCustomClaim(`${namespace}/roles`, roles);
    api.idToken.setCustomClaim(`${namespace}/roles`, roles);

    // Add account metadata (for accountId mapping)
    if (event.user.app_metadata && event.user.app_metadata.accountId) {
      api.accessToken.setCustomClaim(`${namespace}/accountId`, event.user.app_metadata.accountId);
    }
  }
};
```

**Spring Security Custom Converter (Java):**

```java
public class Auth0RolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String ROLES_CLAIM = "https://dataforge-api.example.com/roles";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();

        // Extract roles from namespaced claim
        List<String> roles = jwt.getClaimAsStringList(ROLES_CLAIM);
        if (roles != null) {
            roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .forEach(authorities::add);
        }

        // Also extract permissions if present
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        if (permissions != null) {
            permissions.stream()
                .map(SimpleGrantedAuthority::new)
                .forEach(authorities::add);
        }

        return authorities;
    }
}

// Register in SecurityConfiguration
@Bean
public JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new Auth0RolesConverter());
    return converter;
}
```

**Handling Nested Claims (Alternative Pattern):**

For nested structures like `resource_access.user.roles`:

```java
public class Auth0NestedRolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Map<String, Object> resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess == null) {
            return Collections.emptyList();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> clientResource = (Map<String, Object>) resourceAccess.get("dataforge-api");
        if (clientResource == null) {
            return Collections.emptyList();
        }

        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) clientResource.get("roles");
        if (roles == null) {
            return Collections.emptyList();
        }

        return roles.stream()
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
            .collect(Collectors.toList());
    }
}
```

**Rationale:**
- **Namespaced Claims**: Auth0 **requires** namespaced custom claims (e.g., `https://your-domain/roles`) to prevent collision with standard OIDC claims. Non-namespaced claims silently fail.
- **Permissions Claim**: When Auth0 RBAC enabled, `permissions` claim automatically populated with user's permissions (no Action needed).
- **Both Tokens**: Add claims to **both** access token (API authorization) and ID token (user profile display).
- **ROLE_ Prefix**: Spring Security expects authorities with `ROLE_` prefix for `@PreAuthorize("hasRole('ADMIN')")` expressions.

**Alternatives Considered:**
1. **Flattening roles to scopes**: Convert roles to OAuth2 scopes (space-separated string). Rejected - loses semantic distinction between scopes and roles.
2. **Using `scope` claim**: Auth0 auto-populates `scope` with OAuth2 scopes. Rejected - mixing roles and scopes reduces clarity.
3. **No namespace**: Use simple claim names like `roles`. Rejected - violates Auth0 best practices, may fail validation.

**Auth0 Namespace Requirements:**
- Must use HTTP/HTTPS URL you control (e.g., `https://your-domain.com/claims/`)
- Cannot use `auth0.com`, `webtask.io`, `webtask.run`, or `urn:auth0` namespaces
- URL doesn't need to resolve (used as unique identifier only)
- Maximum 100KB total payload for custom claims

**Example JWT Payload:**

```json
{
  "iss": "https://your-tenant.us.auth0.com/",
  "sub": "auth0|507f1f77bcf86cd799439011",
  "aud": "https://dataforge-api.example.com",
  "iat": 1730905200,
  "exp": 1730991600,
  "azp": "client-id-here",
  "scope": "openid profile email",
  "https://dataforge-api.example.com/roles": ["ADMIN", "USER"],
  "https://dataforge-api.example.com/accountId": "550e8400-e29b-41d4-a716-446655440000",
  "permissions": ["read:admin-messages", "write:admin-messages"]
}
```

---

## 5. Auth0 Password Reset Patterns

### Decision: Use password change tickets (temporary URLs) instead of temporary passwords for better UX and security

**Auth0 Management API - Password Change Ticket:**

```java
public class Auth0PasswordResetService {

    private final ManagementAPI mgmt;

    public String generatePasswordResetLink(String userId, String clientId, String resultUrl) {
        PasswordChangeTicket ticket = new PasswordChangeTicket(userId);
        ticket.setResultUrl(resultUrl);  // Redirect after password change
        ticket.setClientId(clientId);     // Required for redirect to work
        ticket.setTTLSeconds(86400);      // 24 hours (max: 7 days)
        ticket.setMarkEmailAsVerified(false); // Don't auto-verify email

        Request<PasswordChangeTicket> request = mgmt.tickets().requestPasswordChange(ticket);
        PasswordChangeTicket result = request.execute().getBody();

        return result.getTicket(); // Returns full URL
    }

    // Alternative: Send email automatically via Auth0
    public void sendPasswordResetEmail(String email, String clientId) {
        PasswordChangeTicket ticket = new PasswordChangeTicket(userId);
        ticket.setEmail(email);          // Auth0 sends email automatically
        ticket.setClientId(clientId);
        ticket.setConnectionId(connectionId); // Database connection ID

        mgmt.tickets().requestPasswordChange(ticket).execute();
        // No return value - Auth0 handles email delivery
    }
}
```

**Comparison with Keycloak Pattern:**

| Feature | Keycloak (Current) | Auth0 Password Change Ticket |
|---------|-------------------|------------------------------|
| **User Experience** | Admin generates temp password, shares manually | User clicks link in email, sets own password |
| **Security** | Password transmitted over email/Slack (risk) | Ticket URL only (password never transmitted) |
| **Expiration** | 30 days (user.attributes) | 24 hours default (max 7 days) |
| **Email Delivery** | Manual (admin sends) | Automatic (Auth0 SMTP) or Manual (return URL) |
| **Session Persistence** | Password resets don't terminate sessions | Same - existing sessions continue |
| **Revocation** | Cannot revoke temp password | Cannot revoke ticket (wait for expiry) |

**Rationale:**
- **Better UX**: Users receive email with link, set their own password (self-service). No need to remember/type temporary password.
- **Security**: Ticket URL never exposes password. One-time use prevents replay attacks.
- **Reduced Admin Work**: Auth0 handles email delivery (SMTP configured in dashboard). No manual password sharing.
- **Auth0 Standard**: Password change tickets are the Auth0-recommended pattern (matches Universal Login flow).

**Alternatives Considered:**
1. **Temporary password via user.user_metadata**: Auth0 doesn't support temporary passwords like Keycloak's `temporary` flag. Would require custom Action + client-side logic. Rejected - complex, non-standard.
2. **Force password reset on first login**: Set `user.user_metadata.force_password_change = true`, check in Action. Rejected - requires building custom password reset UI (Auth0 Universal Login doesn't support this natively).
3. **Direct password update (PATCH /api/v2/users/{id})**: Set `password` field directly. Rejected - admin knows password (security risk) + no expiration mechanism.

**Implementation Notes:**
- **Required Scope**: Management API token needs `create:user_tickets` scope
- **Connection ID**: Required for email-based flow. Get from Auth0 Dashboard → Authentication → Database connections.
- **Result URL**: Must be registered in Auth0 Dashboard → Applications → Allowed Callback URLs (security check)
- **Client ID**: Must be a valid application in your Auth0 tenant

**Migration Impact:**
- **Breaking Change**: Existing admin API endpoint `POST /api/admin/accounts/{id}/reset-password` returns ticket URL instead of temporary password
- **Response DTO Change**:
  ```java
  // OLD (Keycloak)
  public record PasswordResetResponseDto(String temporaryPassword, Instant expiresAt) {}

  // NEW (Auth0)
  public record PasswordResetResponseDto(String resetUrl, Instant expiresAt) {}
  ```

---

## 6. Auth0 User Blocking Patterns

### Decision: Use PATCH /api/v2/users/{id} with blocked field (equivalent to Keycloak enabled/disabled)

**Auth0 Management API - Block/Unblock User:**

```java
public class Auth0UserManagementService {

    private final ManagementAPI mgmt;

    /**
     * Block user (prevent login)
     * Equivalent to Keycloak: user.setEnabled(false)
     */
    public User blockUser(String userId) {
        User updateRequest = new User();
        updateRequest.setBlocked(true);

        Request<User> request = mgmt.users().update(userId, updateRequest);
        return request.execute().getBody();
    }

    /**
     * Unblock user (restore login)
     * Equivalent to Keycloak: user.setEnabled(true)
     */
    public User unblockUser(String userId) {
        User updateRequest = new User();
        updateRequest.setBlocked(false);

        Request<User> request = mgmt.users().update(userId, updateRequest);
        return request.execute().getBody();
    }

    /**
     * Check if user is blocked
     */
    public boolean isUserBlocked(String userId) {
        Request<User> request = mgmt.users().get(userId, null);
        User user = request.execute().getBody();
        return user.isBlocked();
    }
}
```

**Two Types of Blocks in Auth0:**

1. **Global Block (user.blocked = true)**:
   - Set via PATCH `/api/v2/users/{id}` with `{"blocked": true}`
   - **Permanent** until admin unblocks (no auto-expiry)
   - Visible in Auth0 Dashboard (red "Blocked" badge)
   - Prevents all authentication attempts
   - Does **NOT** terminate existing sessions (same as Keycloak)

2. **IP-based Anomaly Detection Block**:
   - Automatic - triggered by suspicious login patterns (brute force, credential stuffing)
   - Managed via `/api/v2/user-blocks` endpoints (separate from user profile)
   - Auto-expires after cooldown period (configurable in Attack Protection settings)
   - To unblock: `DELETE /api/v2/user-blocks/{id}` or `DELETE /api/v2/user-blocks?identifier={email}`

**Comparison with Keycloak:**

| Feature | Keycloak user.enabled | Auth0 user.blocked |
|---------|----------------------|-------------------|
| **Field Value** | `false` = blocked | `true` = blocked |
| **Semantic** | Positive (enabled) | Negative (blocked) |
| **API Scope** | N/A (Admin Client SDK) | `update:users` |
| **Session Impact** | No termination | No termination |
| **Dashboard UI** | Toggle "Enabled" | Toggle "Block User" |
| **Audit Log** | `DISABLE_USER` event | `blocked` property change |

**Rationale:**
- **Direct Mapping**: `keycloak.enabled = false` → `auth0.blocked = true` (inverse logic)
- **Same Behavior**: Blocked users cannot login, existing sessions continue (matches Keycloak behavior)
- **Simple API**: Single PATCH request (no special endpoints like Keycloak's `executeActionsEmail`)
- **Native Field**: `blocked` is first-class User property (not custom metadata)

**Alternatives Considered:**
1. **user_metadata.blocked**: Store block status in metadata. Rejected - requires custom Action to enforce block (not atomic with authentication).
2. **Delete user instead of blocking**: Irreversible. Rejected - soft delete pattern preferred for audit/compliance.
3. **Deny rule/Action**: Create Action that checks custom blocklist. Rejected - overcomplicated, not queryable via Management API.

**Implementation Notes:**
- **Required Scope**: Management API token needs `update:users` scope
- **Partial Update**: Only send `{"blocked": true/false}` in request body (SDK handles this - create new `User()` object, set only blocked field)
- **Error Handling**: If user already blocked, API returns success (idempotent operation)
- **Prevent Self-Block**: Check admin's user ID before blocking (business logic, not enforced by API)

**Migration Impact:**
- **Rename Service Methods**:
  ```java
  // OLD (Keycloak)
  keycloakAdminClient.disableUser(userId);
  keycloakAdminClient.enableUser(userId);

  // NEW (Auth0)
  auth0UserService.blockUser(userId);
  auth0UserService.unblockUser(userId);
  ```
- **Invert Logic**:
  ```java
  // OLD: user.isEnabled() == true → user can login
  // NEW: user.isBlocked() == false → user can login
  ```
- **Admin Action Log**: Update action types (`LOCK_ACCOUNT` / `UNLOCK_ACCOUNT` remain same, underlying API changes)

---

## 7. Auth0 Actions for Custom Claims

### Decision: Implement Login/Post-Login Action to inject roles, permissions, and account metadata into JWT

**Action Implementation (JavaScript):**

```javascript
/**
 * Handler that will be called during the execution of a PostLogin flow.
 * Location: Actions → Library → Custom → "Add Roles and Permissions to Tokens"
 * Trigger: Login / Post Login
 */
exports.onExecutePostLogin = async (event, api) => {
  const namespace = 'https://dataforge-api.example.com';

  // 1. Add RBAC roles (if enabled in API settings)
  if (event.authorization) {
    const roles = event.authorization.roles || [];
    api.accessToken.setCustomClaim(`${namespace}/roles`, roles);
    api.idToken.setCustomClaim(`${namespace}/roles`, roles);

    console.log(`[Action] Added ${roles.length} roles for user ${event.user.user_id}`);
  }

  // 2. Add RBAC permissions (automatic if RBAC enabled)
  // Auth0 automatically adds "permissions" claim - no code needed

  // 3. Add account metadata (accountId from PostgreSQL accounts table)
  if (event.user.app_metadata && event.user.app_metadata.accountId) {
    const accountId = event.user.app_metadata.accountId;
    api.accessToken.setCustomClaim(`${namespace}/accountId`, accountId);

    console.log(`[Action] Mapped Auth0 user ${event.user.user_id} to accountId ${accountId}`);
  } else {
    console.warn(`[Action] User ${event.user.user_id} missing accountId in app_metadata`);
    // Optional: deny access if accountId required
    // api.access.deny('missing_account_id', 'Account not linked to Auth0 user');
  }

  // 4. Add email verification status (for enforcing email verification)
  if (event.user.email_verified) {
    api.accessToken.setCustomClaim(`${namespace}/email_verified`, true);
  } else {
    console.warn(`[Action] User ${event.user.email} has unverified email`);
    // Optional: deny access until email verified
    // api.access.deny('email_not_verified', 'Please verify your email address');
  }

  // 5. Add custom business logic (example: prevent locked accounts)
  if (event.user.app_metadata && event.user.app_metadata.account_locked) {
    api.access.deny('account_locked', 'Your account has been locked by an administrator');
    return;
  }

  console.log(`[Action] Successfully processed login for ${event.user.email}`);
};

/**
 * Optional: Handler for PostLogin continuation (MFA, additional checks)
 */
exports.onContinuePostLogin = async (event, api) => {
  // Handle continuation after MFA or other challenges
};
```

**Setting app_metadata (accountId) during user creation:**

```java
public class Auth0AccountSyncService {

    private final ManagementAPI mgmt;

    /**
     * Create Auth0 user and link to PostgreSQL account
     */
    public User createAuth0User(Account account) {
        User user = new User();
        user.setEmail(account.getEmail());
        user.setName(account.getName());
        user.setConnection("Username-Password-Authentication"); // Database connection
        user.setPassword(generateSecurePassword());
        user.setVerifyEmail(true);

        // Link to PostgreSQL account via app_metadata
        Map<String, Object> appMetadata = Map.of(
            "accountId", account.getId().toString(),
            "created_by", "admin_api",
            "sync_timestamp", Instant.now().toString()
        );
        user.setAppMetadata(appMetadata);

        Request<User> request = mgmt.users().create(user);
        User createdUser = request.execute().getBody();

        // Update PostgreSQL with Auth0 user ID
        account.setKeycloakUserId(createdUser.getId()); // Rename to auth0UserId in migration
        accountRepository.save(account);

        return createdUser;
    }
}
```

**Rationale:**
- **Login/Post-Login Trigger**: Executes on **every** authentication (login, token refresh, silent auth). Ensures tokens always have latest roles/permissions.
- **app_metadata vs user_metadata**:
  - `app_metadata`: Admin-controlled, not editable by user (use for accountId, internal flags)
  - `user_metadata`: User-editable (use for preferences, profile fields)
- **Namespace Requirement**: Prevents collision with OIDC standard claims (`sub`, `aud`, `iss`, etc.)
- **Permissions Auto-Added**: When API has RBAC enabled, Auth0 automatically includes `permissions` array claim (no Action code needed)

**Alternatives Considered:**
1. **Pre-User Registration Action**: Runs before user creation. Rejected - can't add claims to tokens (only modify user profile).
2. **M2M Token Action**: Runs for machine-to-machine client credentials flow. Rejected - doesn't apply to user authentication.
3. **Store roles in user_metadata**: User-editable, security risk. Rejected - roles must be admin-controlled (use app_metadata + RBAC).
4. **External API call in Action**: Fetch accountId from PostgreSQL during login. Rejected - adds 100-200ms latency to every login. Better to store in app_metadata.

**Action Configuration:**
1. **Create Action**: Auth0 Dashboard → Actions → Library → Build Custom → "Add Roles to Tokens"
2. **Add Dependencies**: None needed (built-in `api` object)
3. **Add Secrets**: If calling external APIs (e.g., PostgreSQL lookup), add connection string as secret
4. **Deploy**: Click "Deploy" to save
5. **Attach to Flow**: Actions → Flows → Login → Drag custom Action into flow → Apply

**Testing:**
```bash
# Test JWT payload includes custom claims
curl -X POST https://your-tenant.us.auth0.com/oauth/token \
  -H 'content-type: application/json' \
  -d '{
    "client_id": "YOUR_CLIENT_ID",
    "client_secret": "YOUR_CLIENT_SECRET",
    "audience": "https://dataforge-api.example.com",
    "grant_type": "client_credentials"
  }' | jq -r '.access_token' | jwt decode -

# Expected output includes:
# "https://dataforge-api.example.com/roles": ["ADMIN"],
# "https://dataforge-api.example.com/accountId": "550e8400-...",
# "permissions": ["read:admin-messages", "write:admin-messages"]
```

**Performance:**
- **Execution Time**: Actions add 10-50ms to login flow (measured by Auth0)
- **Limits**:
  - Max 20 seconds execution time (hard timeout)
  - Max 3 Actions per flow (Login/Post-Login)
  - Max 100KB token payload (including custom claims)
- **Logging**: All `console.log()` statements appear in Auth0 Dashboard → Monitoring → Logs (searchable, exportable)

**Error Handling:**
```javascript
exports.onExecutePostLogin = async (event, api) => {
  try {
    // ... custom claims logic
  } catch (error) {
    console.error('[Action Error]', error.message);
    // Allow login to continue even if custom claims fail
    // OR deny access: api.access.deny('action_error', error.message);
  }
};
```

---

## 8. Migration Strategy Summary

### Keycloak → Auth0 Mapping

| Keycloak Concept | Auth0 Equivalent | Notes |
|-----------------|------------------|-------|
| Realm | Tenant | One Auth0 tenant per environment (dev, prod) |
| Client | Application | M2M app for Management API access |
| User enabled=false | User blocked=true | **Inverse logic** |
| User attributes | app_metadata | Admin-controlled user metadata |
| Realm roles | RBAC Roles | Configured in Dashboard → User Management → Roles |
| Client roles | RBAC Permissions | Configured per API (Dashboard → Applications → APIs) |
| Group membership | Roles | Auth0 roles replace Keycloak groups |
| Custom claims | Actions (Login) | JavaScript code injecting claims |
| Temporary password | Password change ticket | URL-based instead of password string |
| Admin Client SDK | Management API (auth0-java) | Similar programming model |
| Resource Server | OAuth2 Resource Server | Spring Security native support |

### Key Dependencies

```kotlin
// build.gradle.kts additions
dependencies {
    // Auth0 Management API
    implementation("com.auth0:auth0:2.26.0")

    // Spring Security (already present, verify versions)
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // Optional: Resilience (for retry logic)
    implementation("org.springframework.retry:spring-retry:2.0.4")
}
```

### Configuration Changes

```yaml
# application.yml - replace Keycloak config with Auth0
auth0:
  domain: ${AUTH0_DOMAIN:your-tenant.us.auth0.com}
  audience: ${AUTH0_AUDIENCE:https://dataforge-api.example.com}
  management-api:
    client-id: ${AUTH0_MGMT_CLIENT_ID}
    client-secret: ${AUTH0_MGMT_CLIENT_SECRET}

spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://${auth0.domain}/
          audiences: ${auth0.audience}

# Remove old Keycloak config
# keycloak:
#   auth-server-url: ...
#   realm: ...
```

### Backward Compatibility

**Dual Authentication Support (Migration Phase):**

```java
@Configuration
@EnableWebSecurity
public class DualAuthSecurityConfiguration {

    // Admin API - Auth0 OAuth2
    @Bean
    @Order(1)
    public SecurityFilterChain auth0FilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/admin/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(auth0Converter()))
            );
        return http.build();
    }

    // Client API - Custom JWT (backward compatibility)
    @Bean
    @Order(2)
    public SecurityFilterChain customJwtFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/dfc/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .addFilterBefore(customJwtFilter(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

---

## 9. Recommended Implementation Order

1. **Phase 1: Infrastructure Setup** (Week 1)
   - Create Auth0 tenant (dev, staging, prod)
   - Configure M2M application for Management API
   - Create database connection (Username-Password-Authentication)
   - Set up RBAC (roles: ADMIN, USER)
   - Add Gradle dependencies

2. **Phase 2: JWT Validation** (Week 1-2)
   - Configure Spring OAuth2 Resource Server (issuer-uri, audience)
   - Implement custom JwtAuthenticationConverter for roles extraction
   - Create Auth0 Login/Post-Login Action (add custom claims)
   - Test JWT validation with Postman/curl
   - Deploy Action to dev tenant

3. **Phase 3: Management API Integration** (Week 2-3)
   - Implement CachedAuth0TokenProvider with retry logic
   - Create Auth0UserManagementService (create, block, unblock, reset password)
   - Implement Auth0AccountSyncService (bidirectional mapping)
   - Replace KeycloakAdminClient calls with Auth0 equivalents
   - Update admin API endpoints (return password reset URLs instead of temp passwords)

4. **Phase 4: Database Migration** (Week 3)
   - Add `auth0_user_id` column to accounts table (rename from keycloak_user_id)
   - Migrate existing users: bulk export from Keycloak, import to Auth0 via Management API
   - Set app_metadata.accountId for all Auth0 users
   - Verify bidirectional mapping (PostgreSQL ↔ Auth0)

5. **Phase 5: Testing & Rollout** (Week 4)
   - Integration tests (Testcontainers + Auth0 Management API)
   - Contract tests (MockMvc with JWT tokens)
   - Staging environment deployment
   - User acceptance testing (UAT)
   - Production deployment (feature flag for rollback)

---

## 10. References

### Official Documentation
- [Auth0 Spring Security 6 Quickstart](https://auth0.com/docs/quickstart/backend/java-spring-security5/01-authorization)
- [Auth0 Java SDK GitHub](https://github.com/auth0/auth0-java) (2.26.0)
- [Auth0 Management API v2 Reference](https://auth0.com/docs/api/management/v2)
- [Spring Security OAuth2 Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [Auth0 Actions Documentation](https://auth0.com/docs/secure/tokens/json-web-tokens/create-custom-claims)
- [Auth0 Rate Limit Policy](https://auth0.com/docs/troubleshoot/customer-support/operational-policies/rate-limit-policy)

### Community Resources
- [Stack Overflow: JwtAuthenticationConverter for nested claims](https://stackoverflow.com/questions/72226464/how-configure-the-jwtauthenticationconverter-for-a-specific-claim-structure)
- [Baeldung: Spring Security Map Authorities from JWT](https://www.baeldung.com/spring-security-map-authorities-jwt)
- [Auth0 Community: Custom Claims in Spring Security](https://community.auth0.com/t/how-to-parse-roles-put-in-custom-claims-in-spring-security/46801)

### Additional Reading
- [Auth0 vs Keycloak Feature Comparison](https://frontegg.com/blog/auth0-management-api-basics-tutorial-and-5-best-practices)
- [Auth0 Performance Best Practices](https://auth0.com/docs/troubleshoot/performance-best-practices)
- [Spring Boot Authorization Tutorial with Auth0](https://auth0.com/blog/spring-boot-authorization-tutorial-secure-an-api-java/)

---

## Appendix A: Complete Code Example (User Management Service)

```java
package com.bitbi.dfm.auth.infrastructure;

import com.auth0.client.mgmt.ManagementAPI;
import com.auth0.client.mgmt.filter.UserFilter;
import com.auth0.exception.Auth0Exception;
import com.auth0.json.mgmt.tickets.PasswordChangeTicket;
import com.auth0.json.mgmt.users.User;
import com.auth0.net.Request;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class Auth0UserManagementService {

    private final ManagementAPI mgmt;
    private final String clientId;
    private final String connectionId;

    public Auth0UserManagementService(
            ManagementAPI mgmt,
            @Value("${auth0.client-id}") String clientId,
            @Value("${auth0.connection-id}") String connectionId) {
        this.mgmt = mgmt;
        this.clientId = clientId;
        this.connectionId = connectionId;
    }

    /**
     * Create new Auth0 user with account metadata
     */
    @Retryable(
        value = {Auth0Exception.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2.0)
    )
    public User createUser(String email, String name, UUID accountId) throws Auth0Exception {
        User user = new User();
        user.setEmail(email);
        user.setName(name);
        user.setConnection("Username-Password-Authentication");
        user.setPassword(generateSecurePassword());
        user.setVerifyEmail(true);

        // Link to PostgreSQL account
        Map<String, Object> appMetadata = Map.of(
            "accountId", accountId.toString(),
            "created_by", "admin_api",
            "sync_timestamp", Instant.now().toString()
        );
        user.setAppMetadata(appMetadata);

        Request<User> request = mgmt.users().create(user);
        return request.execute().getBody();
    }

    /**
     * Block user (prevent login)
     */
    @Retryable(
        value = {Auth0Exception.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2.0)
    )
    public User blockUser(String userId) throws Auth0Exception {
        User updateRequest = new User();
        updateRequest.setBlocked(true);

        Request<User> request = mgmt.users().update(userId, updateRequest);
        return request.execute().getBody();
    }

    /**
     * Unblock user (restore login)
     */
    @Retryable(
        value = {Auth0Exception.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2.0)
    )
    public User unblockUser(String userId) throws Auth0Exception {
        User updateRequest = new User();
        updateRequest.setBlocked(false);

        Request<User> request = mgmt.users().update(userId, updateRequest);
        return request.execute().getBody();
    }

    /**
     * Generate password reset ticket (24-hour expiry)
     */
    @Retryable(
        value = {Auth0Exception.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2.0)
    )
    public String generatePasswordResetLink(String userId, String resultUrl) throws Auth0Exception {
        PasswordChangeTicket ticket = new PasswordChangeTicket(userId);
        ticket.setResultUrl(resultUrl);
        ticket.setClientId(clientId);
        ticket.setTTLSeconds(86400); // 24 hours

        Request<PasswordChangeTicket> request = mgmt.tickets().requestPasswordChange(ticket);
        PasswordChangeTicket result = request.execute().getBody();

        return result.getTicket();
    }

    /**
     * Get user by Auth0 ID
     */
    @Retryable(
        value = {Auth0Exception.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2.0)
    )
    public User getUser(String userId) throws Auth0Exception {
        Request<User> request = mgmt.users().get(userId, new UserFilter());
        return request.execute().getBody();
    }

    /**
     * Search users by email
     */
    @Retryable(
        value = {Auth0Exception.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2.0)
    )
    public List<User> searchUsersByEmail(String email) throws Auth0Exception {
        UserFilter filter = new UserFilter()
            .withQuery("email:\"" + email + "\"")
            .withSearchEngine("v3");

        Request<List<User>> request = mgmt.users().list(filter);
        return request.execute().getBody();
    }

    /**
     * Update user app_metadata (e.g., link accountId)
     */
    @Retryable(
        value = {Auth0Exception.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2.0)
    )
    public User updateAppMetadata(String userId, Map<String, Object> metadata) throws Auth0Exception {
        User updateRequest = new User();
        updateRequest.setAppMetadata(metadata);

        Request<User> request = mgmt.users().update(userId, updateRequest);
        return request.execute().getBody();
    }

    /**
     * Delete user (hard delete - use with caution)
     */
    @Retryable(
        value = {Auth0Exception.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2.0)
    )
    public void deleteUser(String userId) throws Auth0Exception {
        Request<Void> request = mgmt.users().delete(userId);
        request.execute();
    }

    private String generateSecurePassword() {
        // Generate 16-character password: uppercase, lowercase, digits, special chars
        String upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String lower = "abcdefghijklmnopqrstuvwxyz";
        String digits = "0123456789";
        String special = "!@#$%^&*";
        String all = upper + lower + digits + special;

        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder(16);

        // Ensure at least one character from each category
        password.append(upper.charAt(random.nextInt(upper.length())));
        password.append(lower.charAt(random.nextInt(lower.length())));
        password.append(digits.charAt(random.nextInt(digits.length())));
        password.append(special.charAt(random.nextInt(special.length())));

        // Fill remaining with random characters
        for (int i = 4; i < 16; i++) {
            password.append(all.charAt(random.nextInt(all.length())));
        }

        // Shuffle to avoid predictable pattern
        List<Character> chars = password.chars()
            .mapToObj(c -> (char) c)
            .collect(Collectors.toList());
        Collections.shuffle(chars, random);

        return chars.stream()
            .map(String::valueOf)
            .collect(Collectors.joining());
    }
}
```

---

**End of Research Document**
