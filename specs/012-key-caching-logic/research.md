# Research: Token Refresh and Auto-Logout

**Date**: 2025-12-01
**Feature**: 012-key-caching-logic

## Overview

This document captures research findings for implementing automatic token refresh on 401 errors with Auth0 React SDK and Axios interceptors.

---

## 1. Auth0 Token Refresh Pattern

### Decision: Use `getAccessTokenSilently()` with `cacheMode: 'off'` for forced refresh

### Rationale

Auth0 React SDK provides `getAccessTokenSilently()` which:
- Automatically attempts token refresh using refresh token when access token is expired
- Returns cached token if still valid (default `cacheMode: 'on'`)
- Can force refresh with `cacheMode: 'off'` to bypass cache
- Throws specific errors when refresh fails: `missing_refresh_token`, `invalid_grant`, `login_required`

### Key Error Types

| Error | Meaning | Action |
|-------|---------|--------|
| `missing_refresh_token` | No refresh token available | Redirect to login |
| `invalid_grant` | Refresh token expired or revoked | Redirect to login |
| `login_required` | Session expired, re-authentication needed | Redirect to login |
| `consent_required` | User consent needed (rare) | Redirect to login with consent |

### Code Pattern (from Auth0 docs)

```typescript
try {
  token = await getAccessTokenSilently({ cacheMode: 'off' });
} catch (e) {
  if (e.error === 'missing_refresh_token' || e.error === 'invalid_grant' || e.error === 'login_required') {
    // Refresh token expired or missing - logout user
    logout({ logoutParams: { returnTo: window.location.origin } });
  }
}
```

### Alternatives Considered

1. **iframe fallback (`useRefreshTokensFallback: true`)**: Auth0 v2 disabled this by default. Uses hidden iframe to get new token. Not recommended due to browser third-party cookie restrictions.

2. **Popup authentication (`getTokenWithPopup`)**: Requires user interaction, not suitable for silent refresh.

**Rejected**: Both alternatives require user intervention or have browser compatibility issues.

---

## 2. Axios Response Interceptor Pattern

### Decision: Implement 401 interceptor that catches error, refreshes token, and retries original request

### Rationale

Axios interceptors provide clean separation of concerns:
- Response interceptor catches all 401 errors centrally
- Can retry original request with `instance(error.config)`
- Transparent to calling code (React Query hooks, components)

### Code Pattern (from Axios docs)

```javascript
instance.interceptors.response.use(undefined, async (error) => {
  if (error.response?.status === 401) {
    await refreshToken();
    return instance(error.config); // Retry original request
  }
  throw error;
});
```

### Alternatives Considered

1. **Per-request retry logic**: Handle 401 in each API call. Results in duplicated code across all hooks.

2. **React Query onError handler**: Global error handler in QueryClient. Cannot retry request automatically - only handles error reporting.

3. **HTTP-only refresh endpoint**: Dedicated backend endpoint for token refresh. Not applicable - Auth0 handles refresh tokens.

**Rejected**: All alternatives result in more code or less clean separation.

---

## 3. Concurrent Request Handling (Refresh Lock)

### Decision: Use Promise-based lock to prevent multiple simultaneous refresh attempts

### Rationale

When multiple requests fail with 401 simultaneously:
- First 401 triggers refresh
- Subsequent 401s should wait for ongoing refresh, not start new ones
- After refresh completes, all waiting requests should retry with new token

This prevents:
- Race conditions
- Multiple refresh token exchanges (which can invalidate tokens in rotation)
- Inconsistent state

### Code Pattern

```typescript
let isRefreshing = false;
let refreshPromise: Promise<string> | null = null;

async function refreshTokenWithLock(): Promise<string> {
  if (isRefreshing && refreshPromise) {
    return refreshPromise; // Wait for existing refresh
  }

  isRefreshing = true;
  refreshPromise = getAccessTokenSilently({ cacheMode: 'off' });

  try {
    const token = await refreshPromise;
    return token;
  } finally {
    isRefreshing = false;
    refreshPromise = null;
  }
}
```

### Alternatives Considered

1. **Request queue pattern**: Queue all failed requests, refresh once, replay queue. More complex, same result.

2. **Debounce refresh calls**: Time-based debouncing. Can still result in multiple calls within window.

3. **Mutex/semaphore library**: External dependency for simple use case.

**Rejected**: Promise-based lock is simpler and sufficient for browser context (single-threaded).

---

## 4. Session Expiry State Management

### Decision: Use URL query parameter + sessionStorage for session expiry message

### Rationale

When refresh fails and user is redirected to login:
- Need to display "Session expired" message
- Cannot use React state (lost on redirect)
- Cannot use Auth0 appState (login redirect clears it)
- sessionStorage persists across navigation, cleared on tab close (security)

### Flow

1. Refresh fails → `sessionStorage.setItem('session_expired', 'true')`
2. Logout to Auth0 → Auth0 redirects to login page
3. Login page checks `sessionStorage.getItem('session_expired')`
4. If found, display message and `sessionStorage.removeItem('session_expired')`

### Alternatives Considered

1. **URL query parameter**: `?session_expired=true`. Visible in URL, shareable (bad UX).

2. **localStorage**: Persists beyond session. Could show stale message if user reopens browser.

3. **Context/Redux**: Lost on logout redirect.

4. **Cookie**: Requires backend involvement, more complex.

**Rejected**: sessionStorage provides right persistence scope without URL pollution.

---

## 5. Retry Behavior for Non-401 Errors

### Decision: Do not retry on non-401 errors after refresh succeeds

### Rationale

If token refresh succeeds but retried request still fails:
- Error is not authentication-related
- Could be authorization (403), not found (404), server error (500)
- Retrying would not help and could cause infinite loops

### Implementation

```typescript
const retried = error.config._retry; // Custom flag on config

if (error.response?.status === 401 && !retried) {
  error.config._retry = true; // Mark as retried
  const token = await refreshTokenWithLock();
  error.config.headers.Authorization = `Bearer ${token}`;
  return instance(error.config);
}

throw error; // Don't retry: already retried, or non-401 error
```

---

## 6. Edge Cases and Error Scenarios

### Network Error During Refresh

**Decision**: Show network error toast, do not logout

**Rationale**: Network failure is transient. User may recover connectivity. Logging out would lose their session unnecessarily.

### Manual Logout During Refresh

**Decision**: Abort pending refresh, proceed with logout

**Rationale**: User intent is clear. Cancel refresh promise if possible, or let it resolve harmlessly (auth state already cleared).

### Auth0 Service Unavailable

**Decision**: Show toast "Authentication service unavailable. Please try again later."

**Rationale**: Service outage is temporary. Keep user in app, allow retry when service recovers.

---

## 7. Testing Strategy

### Unit Tests (Vitest)

- `token-refresh.ts`: Lock mechanism, refresh success/failure paths
- `interceptors.ts`: 401 handling, retry logic, non-401 passthrough

### Integration Tests

- Mock Auth0 SDK (`getAccessTokenSilently`, `logout`)
- Mock Axios to simulate 401 responses
- Verify end-to-end flow: 401 → refresh → retry → success
- Verify failure flow: 401 → refresh fails → logout

### Test Scenarios

| Scenario | Expected Outcome |
|----------|------------------|
| Single 401 with valid refresh token | Token refreshed, request retried, succeeds |
| Single 401 with expired refresh token | User logged out, session expiry message shown |
| Multiple concurrent 401s | Single refresh, all requests retried |
| 401 followed by 403 after retry | 403 error thrown (not retried again) |
| Network error during refresh | Network error toast, user stays logged in |
| Manual logout during refresh | Logout completes, refresh aborted |

---

## Summary

| Topic | Decision |
|-------|----------|
| Token refresh | `getAccessTokenSilently({ cacheMode: 'off' })` |
| Refresh trigger | Axios response interceptor on 401 |
| Concurrent handling | Promise-based lock |
| Session expiry state | sessionStorage |
| Retry limit | Single retry per request (flag on config) |
| Error types triggering logout | `missing_refresh_token`, `invalid_grant`, `login_required` |
