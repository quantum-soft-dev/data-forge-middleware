# Quickstart: Token Refresh and Auto-Logout

**Date**: 2025-12-01
**Feature**: 012-key-caching-logic

## Prerequisites

- Node.js 18+ and npm installed
- Frontend development environment set up (`frontend/`)
- Auth0 tenant configured with refresh tokens enabled
- Access to running backend (for integration testing)

## Development Setup

```bash
# Navigate to frontend directory
cd frontend

# Install dependencies (if not already)
npm install

# Start development server
npm run dev

# Run tests in watch mode (TDD)
npm run test:watch
```

## Key Files to Create/Modify

### New Files

| File | Purpose |
|------|---------|
| `src/shared/api/token-refresh.ts` | Token refresh lock and refresh logic |
| `src/shared/api/types.ts` | Type definitions for token refresh |
| `src/shared/lib/auth/session-expiry.ts` | Session expiry state management |
| `src/entities/user-session/ui/SessionExpiredBanner.tsx` | UI component for session expiry message |

### Modified Files

| File | Changes |
|------|---------|
| `src/shared/api/interceptors.ts` | Add 401 response interceptor with refresh/retry logic |
| `src/shared/api/error-handler.ts` | Update 401 handling to integrate with refresh |
| `src/app/App.tsx` | Initialize token refresh callbacks |

## Implementation Order

1. **Types** - Define TypeScript types (`src/shared/api/types.ts`)
2. **Token Refresh Core** - Implement refresh lock (`src/shared/api/token-refresh.ts`)
3. **Session Expiry** - Implement sessionStorage logic (`src/shared/lib/auth/session-expiry.ts`)
4. **Interceptor** - Modify Axios interceptor (`src/shared/api/interceptors.ts`)
5. **Error Handler** - Update 401 handling (`src/shared/api/error-handler.ts`)
6. **UI Component** - Create session expiry banner (`src/entities/user-session/ui/SessionExpiredBanner.tsx`)
7. **Integration** - Wire up in App.tsx

## Testing Approach (TDD)

### Step 1: Write Tests First

```bash
# Create test file
touch src/shared/api/__tests__/token-refresh.test.ts

# Run tests in watch mode
npm run test:watch
```

### Step 2: Test Cases to Implement

**Unit Tests** (`token-refresh.test.ts`):
- `should refresh token on first 401 error`
- `should wait for existing refresh when multiple 401s occur`
- `should retry original request with new token after refresh`
- `should call logout when refresh fails with invalid_grant`
- `should call logout when refresh fails with missing_refresh_token`
- `should not retry more than once per request`

**Session Expiry Tests** (`session-expiry.test.ts`):
- `should set session expired flag in sessionStorage`
- `should get session expired flag from sessionStorage`
- `should clear session expired flag after reading`
- `should handle missing sessionStorage gracefully`

**UI Component Tests** (`SessionExpiredBanner.test.tsx`):
- `should render message when session is expired`
- `should not render when session is not expired`
- `should clear expired state after displaying`

### Step 3: Run Green

Implement minimal code to pass each test.

### Step 4: Refactor

Clean up code while keeping tests green.

## Quick Verification

### Manual Testing

1. **Token Refresh Success**:
   - Login to app
   - Wait for access token to expire (or use short-lived test token)
   - Make API request
   - Verify request succeeds without user intervention

2. **Token Refresh Failure (Logout)**:
   - Login to app
   - Invalidate refresh token in Auth0 dashboard (revoke session)
   - Make API request
   - Verify user is logged out and sees session expiry message

3. **Concurrent Requests**:
   - Open Network tab in DevTools
   - Trigger multiple API calls simultaneously with expired token
   - Verify only one token refresh request is made

### Automated Testing

```bash
# Run all frontend tests
npm run test

# Run with coverage
npm run test:coverage

# Run specific test file
npm run test -- token-refresh.test.ts
```

## Common Issues

### Issue: Tests fail with "getAccessTokenSilently is not a function"

**Solution**: Mock Auth0 hook properly:
```typescript
vi.mock('@auth0/auth0-react', () => ({
  useAuth0: () => ({
    getAccessTokenSilently: vi.fn().mockResolvedValue('new-token'),
    logout: vi.fn(),
    isAuthenticated: true,
  }),
}));
```

### Issue: Infinite retry loop on 401

**Solution**: Check that `_retry` flag is being set on request config:
```typescript
if (error.response?.status === 401 && !error.config._retry) {
  error.config._retry = true;
  // ... refresh logic
}
```

### Issue: Session expiry message not appearing

**Solution**: Verify sessionStorage is being set before logout:
```typescript
// Must happen BEFORE logout
setSessionExpired({ isExpired: true, reason: 'refresh_token_expired' });
// Then logout
logout({ logoutParams: { returnTo: window.location.origin } });
```

## Resources

- [Auth0 React SDK Docs](https://auth0.github.io/auth0-react/)
- [Axios Interceptors Guide](https://axios-http.com/docs/interceptors)
- [Research Document](./research.md)
- [Data Model](./data-model.md)
- [Feature Spec](./spec.md)
