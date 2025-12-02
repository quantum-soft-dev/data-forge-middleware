# API Contracts: Token Refresh and Auto-Logout

**Date**: 2025-12-01
**Feature**: 012-key-caching-logic

## Overview

This feature does not introduce new API endpoints. It modifies the frontend's handling of existing API error responses.

---

## HTTP Status Codes Handled

### 401 Unauthorized

**Current Behavior** (before this feature):
- Toast message: "Session expired. Please log in again."
- No automatic action

**New Behavior** (after this feature):
1. Attempt token refresh via Auth0 SDK
2. If refresh succeeds: retry original request with new token
3. If refresh fails: logout user and redirect to login with session expiry message

### Error Response Format (from backend)

The backend returns standard error responses for 401:

```json
{
  "timestamp": "2025-12-01T10:30:00Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "JWT token has expired",
  "path": "/api/v1/batches"
}
```

The frontend interceptor triggers on `status === 401` regardless of response body content.

---

## Auth0 Token Errors

These errors are returned by Auth0 SDK, not the backend API.

| Error Code | Description | Frontend Action |
|------------|-------------|-----------------|
| `missing_refresh_token` | No refresh token in cache | Logout + show session expiry |
| `invalid_grant` | Refresh token expired/revoked | Logout + show session expiry |
| `login_required` | User session ended at Auth0 | Logout + show session expiry |
| `consent_required` | User consent needed (rare) | Redirect to consent screen |

### Auth0 Error Object Structure

```typescript
interface Auth0Error {
  error: string;           // Error code (e.g., "invalid_grant")
  error_description: string; // Human-readable description
  message?: string;        // Additional details
}
```

---

## No New Endpoints

This feature only modifies:

1. **Axios Response Interceptor**: New 401 handling logic
2. **Frontend Auth State**: Session expiry flag in sessionStorage

Backend API behavior is unchanged. All existing endpoints continue to return 401 for expired tokens.

---

## Contract Tests Not Applicable

Since no new endpoints are introduced, no OpenAPI specifications or contract tests are needed for this feature.

Frontend tests will verify:
- 401 interception and retry behavior
- Token refresh lock mechanism
- Session expiry state persistence
- UI component rendering for session expiry message
