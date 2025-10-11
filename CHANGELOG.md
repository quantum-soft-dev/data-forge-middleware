# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Admin UI with Keycloak Authentication** - Complete React-based admin interface for account management
  - Feature-Sliced Design architecture (entities, features, widgets, pages)
  - React Query for server state management
  - TanStack Router with route guards for protected routes
  - Keycloak SSO integration via react-oidc-context
  - Environment-aware logging utility to prevent token leakage in production
  - Account CRUD operations with form validation
  - Responsive UI with Tailwind CSS and shadcn/ui components

- **Phone and Company Fields** - Enhanced Account entity with optional contact information
  - `phone` field (VARCHAR(50)) with E.164 format validation via Phone Value Object
  - `company` field (VARCHAR(255)) with length validation (2-255 chars) via Company Value Object
  - JPA AttributeConverters for automatic persistence (PhoneConverter, CompanyConverter)
  - Comprehensive test coverage (29 unit tests for VOs, 26 tests for converters)

- **Structured Response DTOs** - Type-safe API responses replacing Map<String, Object>
  - AccountResponseDto with maxConcurrentBatches field (configurable via application.yml)
  - AccountWithStatsResponseDto for detailed account statistics
  - PageResponseDto<T> for paginated responses
  - All DTOs include static `fromEntity()` factory methods

- **Route Guards** - Authentication protection for protected frontend routes
  - beforeLoad guards in /dashboard and /accounts routes
  - Automatic redirect to login page for unauthenticated users
  - Auth context passed through TanStack Router context

- **Improved Error Handling** - Field-specific error messages in AccountService
  - createAccount() wraps validation exceptions with field context
  - updateAccount() wraps each field update independently
  - Exception messages now indicate which field caused validation failure

- **Configuration Externalization** - AccountProperties for business rules
  - `account.max-concurrent-batches` configurable via application.yml (default: 5)
  - Environment variable override: ACCOUNT_MAX_CONCURRENT_BATCHES

### Changed

- **⚠️ BREAKING CHANGE: Account Status Field** - API response format changed
  - **Before:** `isActive: boolean` (true/false)
  - **After:** `status: string` ("active"/"inactive")
  - **Affected Endpoints:**
    - GET /api/admin/accounts/{id}
    - GET /api/admin/accounts
    - POST /api/admin/accounts
    - PUT /api/admin/accounts/{id}
  - **Migration:** Update API consumers to use `status === "active"` instead of `isActive === true`
  - **Why:** Improved semantic clarity and future extensibility (e.g., "suspended", "pending")

- **Security Configuration** - Separate authentication filter chains
  - Client API (/api/dfc/**) now accepts only custom JWT tokens
  - Admin API (/api/admin/**) now accepts only Keycloak OAuth2 tokens
  - Keycloak role mapping ensures ROLE_ prefix compatibility with Spring Security
  - Authentication audit logging with token type detection

- **Account Entity** - Explicit JPA converter annotations
  - Added `@Convert(converter = PhoneConverter.class)` to phone field
  - Added `@Convert(converter = CompanyConverter.class)` to company field
  - Improves code readability and IDE navigation

### Deprecated

- **GET /api/admin/accounts/{id}/stats** - Deprecated in favor of canonical endpoint
  - Use GET /api/admin/accounts/{id}/statistics instead
  - Deprecated endpoint will be removed in v4.0.0
  - Warning logged when deprecated endpoint is called

### Fixed

- Keycloak role authorization now works with roles sent without ROLE_ prefix
- Frontend token data no longer leaks to production console logs
- Account phone and company fields now properly validated at all layers
- Environment variable naming inconsistency between .env.example and .env.development

### Security

- Console.log() statements in interceptors replaced with environment-aware logger
- Token data only logs in development with REACT_APP_DEBUG_AUTH=true
- Route guards prevent unauthenticated access to protected frontend routes

---

## Migration Guide: v2.x → v3.0.0

### Account Status Field

**Before (v2.x):**
```json
{
  "id": "123e4567-e89b-12d3-a456-426614174000",
  "email": "user@example.com",
  "name": "John Doe",
  "isActive": true
}
```

**After (v3.0.0):**
```json
{
  "id": "123e4567-e89b-12d3-a456-426614174000",
  "email": "user@example.com",
  "name": "John Doe",
  "status": "active",
  "phone": "+1234567890",
  "company": "Acme Corp",
  "maxConcurrentBatches": 5
}
```

**Code Changes:**
```javascript
// Old code
if (account.isActive) {
  // account is active
}

// New code
if (account.status === 'active') {
  // account is active
}
```

### Statistics Endpoint

**Before (v2.x):**
```bash
GET /api/admin/accounts/{id}/stats
```

**After (v3.0.0):**
```bash
GET /api/admin/accounts/{id}/statistics  # Canonical endpoint
GET /api/admin/accounts/{id}/stats        # Deprecated, will be removed in v4.0.0
```

### Environment Configuration

If deploying v3.0.0, update your environment configuration:

**New Configuration Properties:**
```yaml
# application.yml or environment variables
account:
  max-concurrent-batches: 5  # ACCOUNT_MAX_CONCURRENT_BATCHES

# Keycloak (updated defaults)
keycloak:
  realm: dfm                    # KEYCLOAK_REALM
  auth-server-url: http://localhost:8081  # KEYCLOAK_AUTH_SERVER_URL
  resource: dfm-backend         # KEYCLOAK_RESOURCE
```

---

## [2.0.0] - 2025-10-09

(Previous version history would go here)

---

## Notes

- **Test Coverage:** 440 passing tests (380 backend + 13 frontend unit tests)
- **Database Migration:** V9__add_phone_and_company_to_accounts.sql (backward compatible)
- **Frontend Bundle:** 8,581 npm dependencies, lazy-loaded routes for performance
