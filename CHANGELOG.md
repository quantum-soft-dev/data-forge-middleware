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
  - Database indexes on phone and company columns (partial indexes for non-null values)
  - Comprehensive test coverage (29 unit tests for VOs, 26 tests for converters)

- **Structured Response DTOs** - Type-safe API responses replacing Map<String, Object>
  - AccountResponseDto with maxConcurrentBatches field (configurable via application.yml)
  - Backward compatible isActive field (deprecated, will be removed in v2.0.0)
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

- **Security Documentation** - Frontend XSS protection analysis
  - Comprehensive security audit in frontend/SECURITY.md
  - Verified React JSX auto-escaping (no dangerouslySetInnerHTML usage)
  - Multi-layered validation (Zod schemas + backend DTOs + Value Objects)
  - Content Security Policy recommendations for production

- **Database Performance Optimization** - Indexes for account search
  - Partial indexes on phone and company columns (V10 migration)
  - ~30-50% smaller than full indexes (only non-null values indexed)
  - Improved query performance for searches by phone/company
  - Rollback script provided in db/rollback/V10__rollback_indexes_for_phone_and_company.sql

### Changed

- **⚠️ BREAKING CHANGE: Account Status Field** - API response format changed (with backward compatibility)
  - **Before (v2.x):** `isActive: boolean` (true/false)
  - **After (v3.0.0+):** `status: string` ("active"/"inactive") + `isActive: boolean` (deprecated)
  - **Backward Compatibility:** Both fields are included in API responses during deprecation period
    - `status`: New semantic field (recommended for new code)
    - `isActive`: Deprecated field (for backward compatibility, will be removed in v2.0.0 planned for 2026-Q1)
  - **Affected Endpoints:**
    - GET /api/admin/accounts/{id}
    - GET /api/admin/accounts
    - POST /api/admin/accounts
    - PUT /api/admin/accounts/{id}
  - **Migration Timeline:**
    - **v1.1.0 (2025-10-11)**: Both fields included, `isActive` marked as deprecated
    - **v1.x**: Deprecation period - migrate to `status` field
    - **v2.0.0 (2026-Q1)**: `isActive` field removed, only `status` field available
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

**After (v3.0.0 - Backward Compatible):**
```json
{
  "id": "123e4567-e89b-12d3-a456-426614174000",
  "email": "user@example.com",
  "name": "John Doe",
  "status": "active",
  "isActive": true,  // DEPRECATED: Will be removed in v2.0.0 (2026-Q1)
  "phone": "+1234567890",
  "company": "Acme Corp",
  "maxConcurrentBatches": 5
}
```

**Code Changes:**
```javascript
// Old code (still works during deprecation period)
if (account.isActive) {
  // account is active
}

// New code (recommended - migrate during v1.x releases)
if (account.status === 'active') {
  // account is active
}
```

**Migration Strategy:**
1. **v1.1.0 - v1.x**: Both fields available - migrate your code at your convenience
2. **v2.0.0 (2026-Q1)**: `isActive` field removed - must use `status` field

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

- **Test Coverage:** 443 passing tests (383 backend + 13 frontend unit tests)
  - Added 3 new tests for backward compatibility (AccountResponseDtoTest)
  - All tests pass with deprecated field warnings (expected behavior)
- **Database Migrations:**
  - V9__add_phone_and_company_to_accounts.sql (backward compatible)
  - V10__add_indexes_for_phone_and_company.sql (performance optimization)
  - Rollback scripts provided in db/rollback/ for emergency use
- **Frontend Bundle:** 8,581 npm dependencies, lazy-loaded routes for performance
- **Security:** Frontend XSS protection verified (React auto-escaping + multi-layer validation)
