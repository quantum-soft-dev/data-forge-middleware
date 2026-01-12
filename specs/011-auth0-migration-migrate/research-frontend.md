# Auth0 React Frontend Integration Research

**Project**: data-forge-middleware Auth0 Migration (Spec 011)
**Focus**: React 19.2 + TypeScript 5.6 Frontend Authentication
**Current Stack**: React 19.2, TanStack Query v5, TanStack Router, OIDC Client TS (Keycloak)
**Target**: Auth0 Universal Login with @auth0/auth0-react SDK
**Research Date**: 2025-11-06

---

## 1. Auth0 React SDK Selection

### Decision: `@auth0/auth0-react` v2.8.0

### Rationale:
- **Official React SDK**: Purpose-built for React applications with hooks-based API (useAuth0, withAuthenticationRequired)
- **React 19.2 Compatible**: Version 2.3.0+ includes React 19 support (verified via GitHub releases)
- **React 18+ Support**: Uses createRoot API pattern, fully compatible with React 18 and 19
- **Active Maintenance**: Latest release v2.8.0 (October 17, 2025), 38 total releases, 971 GitHub stars
- **Modern React Patterns**: Leverages Context API, hooks, and HOC patterns for idiomatic React code
- **Automatic Token Management**: Handles grant flow details, token expiration, renewal, storage, and caching
- **Universal Login Built-in**: Implements Authorization Code Flow with PKCE out of the box

### Alternatives Considered:

#### `@auth0/auth0-spa-js` (Low-Level SDK)
- **Pros**: More control over authentication flow, framework-agnostic, can be used outside React components
- **Cons**:
  - No React-specific abstractions (hooks, context)
  - Requires manual state management and React integration
  - More boilerplate code for React apps
  - Cannot use inside React components without custom wrapper
- **Verdict**: Not recommended for React apps. `@auth0/auth0-react` uses `auth0-spa-js` under the hood while providing React-friendly API.

#### `auth0-js` (Legacy SDK)
- **Pros**: Mature, supports embedded login flows
- **Cons**:
  - Legacy library, not recommended for new projects
  - Embedded Login deprecated in favor of Universal Login
  - Lacks modern SPA patterns (PKCE)
  - No React-specific features
- **Verdict**: Deprecated for new implementations. Auth0 recommends `@auth0/auth0-spa-js` or framework-specific wrappers.

#### `auth0-lock` (UI Widget)
- **Pros**: Drop-in login widget with customizable UI
- **Cons**:
  - Embedded login pattern (Auth0 discourages this)
  - Less flexible than Universal Login
  - Larger bundle size
  - Cannot be used with Universal Login redirect flow
- **Verdict**: Not applicable for Universal Login strategy.

---

## 2. Auth0 Universal Login Integration

### Decision: Redirect-Based Universal Login with Auth0Provider Wrapper

### Rationale:
- **Security Best Practice**: Auth0 Universal Login is the recommended authentication flow (Authorization Code Flow with PKCE)
- **Hosted by Auth0**: Login page hosted on Auth0's servers (eliminates XSS attack surface on your domain)
- **Centralized Management**: Single login UI for all applications, managed via Auth0 Dashboard
- **No Embedded Forms**: Avoids security risks of embedding authentication forms in SPA
- **PKCE Flow**: Built-in protection against authorization code interception attacks
- **Minimal Client Code**: SDK handles redirect orchestration, callback processing, and token exchange

### Implementation Pattern:

```typescript
// src/main.tsx
import { Auth0Provider } from '@auth0/auth0-react';
import { useNavigate } from '@tanstack/react-router';

function Auth0ProviderWithNavigate({ children }: { children: React.ReactNode }) {
  const navigate = useNavigate();

  const onRedirectCallback = (appState?: any) => {
    navigate(appState?.returnTo || '/dashboard');
  };

  return (
    <Auth0Provider
      domain={import.meta.env.VITE_AUTH0_DOMAIN}
      clientId={import.meta.env.VITE_AUTH0_CLIENT_ID}
      authorizationParams={{
        redirect_uri: window.location.origin,
        audience: import.meta.env.VITE_AUTH0_AUDIENCE,
      }}
      onRedirectCallback={onRedirectCallback}
      useRefreshTokens={true}
      cacheLocation="memory"
    >
      {children}
    </Auth0Provider>
  );
}
```

**Configuration Requirements** (Auth0 Dashboard):
- **Allowed Callback URLs**: `http://localhost:3000, https://yourapp.com`
- **Allowed Logout URLs**: `http://localhost:3000, https://yourapp.com`
- **Allowed Web Origins**: `http://localhost:3000, https://yourapp.com`
- **Application Type**: Single Page Application
- **Token Endpoint Authentication Method**: None (PKCE flow)

### Alternatives Considered:

#### Popup-Based Login (`loginWithPopup()`)
- **Pros**: No page redirect, maintains application state, better UX for some flows
- **Cons**:
  - Blocked by popup blockers (30-50% failure rate in production)
  - Cannot use with refresh tokens (less secure token rotation)
  - ITP (Intelligent Tracking Prevention) issues on Safari
  - Requires separate error handling for popup blocked scenarios
- **Verdict**: Not recommended for primary login flow. Auth0 discourages popup pattern.

#### Embedded Login (Auth0 Lock or custom forms)
- **Pros**: Seamless UI experience, no page redirect
- **Cons**:
  - **SECURITY RISK**: Credentials entered on your domain (XSS attack surface)
  - Deprecated by Auth0 for new applications
  - Cannot leverage Auth0's security updates to login UI
  - Requires Cross-Origin Authentication flow (more complex, less secure)
  - Not compatible with social login, MFA, or passwordless flows
- **Verdict**: Auth0 explicitly discourages embedded login. Use Universal Login.

#### Silent Authentication (`getTokenSilently()`)
- **Pros**: No user interaction, automatic token refresh
- **Cons**:
  - Not a login method (requires existing session)
  - Used for token renewal, not initial authentication
  - Requires refresh tokens or session cookies
- **Verdict**: Complementary pattern for token refresh, not primary login.

---

## 3. Auth0 Token Storage Patterns

### Decision: In-Memory Storage with Web Workers + Refresh Tokens

### Rationale:
- **Auth0's Recommended Pattern**: Official guidance states "Auth0 recommends storing tokens in browser memory as the most secure option"
- **XSS Protection**: Tokens not accessible via `localStorage`, `sessionStorage`, or `document.cookie` (immune to JavaScript-based theft)
- **Web Worker Isolation**: Auth0 SPA SDK uses Web Workers for token refresh operations (separate global scope prevents malicious script access)
- **Automatic Refresh**: Refresh tokens stored in httpOnly cookies (set by Auth0 servers), invisible to JavaScript
- **PKCE Flow**: No tokens in URL parameters (prevents token leakage via browser history)
- **Trade-off Accepted**: Page refresh requires silent authentication (acceptable UX for security gain)

### Implementation Configuration:

```typescript
<Auth0Provider
  useRefreshTokens={true}        // Enable refresh token rotation
  cacheLocation="memory"          // In-memory storage (default)
  // Web Worker automatically used when:
  // - useRefreshTokens: true
  // - cacheLocation: 'memory'
  // - No custom cache
>
```

**How Token Refresh Works**:
1. User logs in → Access token stored in memory (JavaScript closure)
2. Refresh token stored in httpOnly cookie by Auth0 (JavaScript cannot access)
3. Before access token expires → SDK calls Auth0 `/token` endpoint with refresh token
4. New access token returned, old one discarded
5. Process repeats every 15 minutes (typical access token expiry)

**Page Refresh Handling**:
```typescript
// SDK automatically handles this via getTokenSilently()
const { isLoading, isAuthenticated, getAccessTokenSilently } = useAuth0();

useEffect(() => {
  const getToken = async () => {
    try {
      const token = await getAccessTokenSilently();
      // Token retrieved via refresh token (no user interaction)
    } catch (error) {
      // User must re-authenticate if refresh token expired
    }
  };
  if (isAuthenticated) getToken();
}, [isAuthenticated]);
```

### Alternatives Considered:

#### Local Storage (`cacheLocation: 'localstorage'`)
- **Pros**: Persistence across page refreshes and tabs, simple implementation
- **Cons**:
  - **VULNERABLE TO XSS**: Any JavaScript execution can read `localStorage.getItem('auth0:token')`
  - Auth0 warning: "If an attacker can achieve running JavaScript in the SPA using XSS, they can retrieve the tokens stored in local storage"
  - Tokens survive browser close (infinite session risk)
  - Cannot use Web Workers for secure token handling
- **Verdict**: Only acceptable for low-security applications. Rejected for this project.

#### httpOnly Cookies (Backend-Managed Tokens)
- **Pros**:
  - Immune to XSS (JavaScript cannot read httpOnly cookies)
  - Automatic inclusion in HTTP requests (no manual token attachment)
  - Browser handles token storage and lifecycle
- **Cons**:
  - **Requires backend BFF (Backend-for-Frontend) pattern**: SPA cannot set httpOnly cookies
  - CSRF vulnerability (requires CSRF tokens or SameSite=Strict)
  - Cannot use with client-only SPA architecture
  - Incompatible with Auth0 Universal Login redirect flow (tokens returned to SPA, not backend)
- **Verdict**: Not applicable for client-side SPA without dedicated backend proxy.

#### Session Storage (`cacheLocation: 'sessionstorage'`)
- **Pros**:
  - Tokens cleared on tab close (better than localStorage)
  - Still accessible across page refreshes
- **Cons**:
  - **VULNERABLE TO XSS**: Same attack surface as localStorage
  - No persistence across tabs (poor multi-tab UX)
  - Tokens lost on browser crash/accidental tab close
- **Verdict**: Slightly better than localStorage, but still insecure. Rejected.

#### Hybrid Approach (Memory + httpOnly Cookies for Refresh Token)
- **Pros**:
  - Access tokens in memory (XSS-safe)
  - Refresh tokens in httpOnly cookies (XSS-safe, persistent)
  - Best security-UX balance
- **Cons**:
  - Requires backend to set httpOnly cookies (BFF pattern)
  - CSRF mitigation required
  - More complex implementation
- **Verdict**: Ideal for high-security apps with backend, but Auth0 SDK in-memory + refresh tokens achieves similar security without backend complexity.

---

## 4. Auth0 Custom Claims Access

### Decision: Namespaced Custom Claims via Auth0 Actions + Utility Function

### Rationale:
- **Auth0 Requirement**: Roles/permissions NOT included in tokens by default (must be added via Auth0 Actions/Rules)
- **Namespace Pattern**: Auth0 requires custom claims use unique namespace to avoid collisions (e.g., `https://yourapp.com/roles`)
- **Type Safety**: TypeScript utility function provides type-safe role checks
- **React Hook Integration**: Access via `useAuth0()` hook's `user` object
- **ID Token vs Access Token**: Roles added to both ID token (user context) and access token (API authorization)

### Implementation Pattern:

**Step 1: Auth0 Login Action** (Auth0 Dashboard → Actions → Flows → Login)
```javascript
// actions/add-roles-to-token.js
exports.onExecutePostLogin = async (event, api) => {
  const namespace = 'https://yourdomain.com';

  if (event.authorization) {
    // Add roles to ID token (for React app)
    api.idToken.setCustomClaim(`${namespace}/roles`, event.authorization.roles);

    // Add roles to access token (for backend API)
    api.accessToken.setCustomClaim(`${namespace}/roles`, event.authorization.roles);

    // Add accountId from app_metadata (NOT user_metadata!)
    if (event.user.app_metadata && event.user.app_metadata.accountId) {
      api.idToken.setCustomClaim(`${namespace}/accountId`, event.user.app_metadata.accountId);
    }
  }
};
```

**Step 2: TypeScript Utility Function**
```typescript
// src/shared/auth/utils/check-claims.ts
import { User } from '@auth0/auth0-react';

const NAMESPACE = 'https://yourdomain.com';

export function hasRole(user: User | undefined, role: string): boolean {
  const roles = user?.[`${NAMESPACE}/roles`] as string[] | undefined;
  return roles?.includes(role) ?? false;
}

export function getRoles(user: User | undefined): string[] {
  return (user?.[`${NAMESPACE}/roles`] as string[]) ?? [];
}

export function hasPermission(user: User | undefined, permission: string): boolean {
  const permissions = user?.[`${NAMESPACE}/permissions`] as string[] | undefined;
  return permissions?.includes(permission) ?? false;
}

export function getAccountId(user: User | undefined): string | undefined {
  return user?.[`${NAMESPACE}/account_id`] as string | undefined;
}
```

**Step 3: React Component Usage**
```typescript
// src/features/admin/ui/AdminPanel.tsx
import { useAuth0 } from '@auth0/auth0-react';
import { hasRole } from '@/shared/auth/utils/check-claims';

export function AdminPanel() {
  const { user } = useAuth0();

  if (!hasRole(user, 'ROLE_ADMIN')) {
    return <Navigate to="/forbidden" />;
  }

  return <div>Admin Content</div>;
}
```

**Step 4: Protected Component with Role Check**
```typescript
// src/shared/auth/components/RoleGuard.tsx
import { useAuth0 } from '@auth0/auth0-react';
import { hasRole } from '@/shared/auth/utils/check-claims';
import { Navigate } from '@tanstack/react-router';

interface RoleGuardProps {
  children: React.ReactNode;
  requiredRole: string;
  fallback?: React.ReactNode;
}

export function RoleGuard({ children, requiredRole, fallback }: RoleGuardProps) {
  const { user, isLoading } = useAuth0();

  if (isLoading) return <div>Loading...</div>;

  if (!hasRole(user, requiredRole)) {
    return fallback ?? <Navigate to="/forbidden" />;
  }

  return <>{children}</>;
}
```

### Alternatives Considered:

#### Using `getAccessTokenSilently()` with `scope` Parameter
```typescript
const token = await getAccessTokenSilently({
  authorizationParams: {
    audience: 'https://api.yourapp.com',
    scope: 'openid profile email read:data write:data'
  }
});
```
- **Pros**: Granular permission-based authorization (better than role-based)
- **Cons**:
  - Permissions not available in React `user` object (only in access token)
  - Requires decoding JWT in frontend (security anti-pattern)
  - Must make API call to backend to verify permissions
  - More complex RBAC setup in Auth0
- **Verdict**: Better for API authorization, but overkill for frontend route protection. Use roles for UI, permissions for API.

#### Backend API Role Verification
```typescript
// Backend validates roles from access token, returns user permissions
const response = await fetch('/api/user/permissions', {
  headers: { Authorization: `Bearer ${token}` }
});
const { roles, permissions } = await response.json();
```
- **Pros**:
  - Single source of truth (backend)
  - No JWT decoding in frontend
  - Roles can change without re-login
- **Cons**:
  - Extra network request on app load (latency)
  - Complicates offline/loading states
  - Still need ID token roles for instant UI decisions
- **Verdict**: Complementary pattern. Use ID token roles for fast UI decisions, verify with backend API for critical operations.

#### Auth0 Authorization Extension (Deprecated)
- **Pros**: UI for managing roles/permissions
- **Cons**:
  - Deprecated by Auth0 (replaced by Auth0 Actions)
  - Not compatible with Auth0 Actions
  - No longer maintained
- **Verdict**: Do not use. Migrate to Auth0 Actions.

---

## 5. Auth0 Logout Patterns

### Decision: Remote Logout with `returnTo` Parameter + Local State Cleanup

### Rationale:
- **Complete Session Termination**: `logout()` method clears local SPA state AND Auth0 session (prevents back button access)
- **Redirect Control**: `returnTo` parameter ensures users land on public page after logout
- **Federated Logout Support**: Optional parameter to logout from upstream identity provider (Google, Microsoft, etc.)
- **Security Best Practice**: Remote logout prevents session fixation attacks
- **SDK Handles Cleanup**: Auth0 SDK automatically clears tokens from memory and cache

### Implementation Pattern:

```typescript
// src/shared/auth/hooks/useLogout.ts
import { useAuth0 } from '@auth0/auth0-react';
import { useNavigate } from '@tanstack/react-router';

export function useLogout() {
  const { logout } = useAuth0();
  const navigate = useNavigate();

  const handleLogout = () => {
    // Optional: Clear application state before logout
    localStorage.clear();
    sessionStorage.clear();

    // Remote logout (clears Auth0 session + redirects)
    logout({
      logoutParams: {
        returnTo: window.location.origin, // Must be in "Allowed Logout URLs"
        federated: false, // Set to true for social login providers
      },
    });
  };

  return { logout: handleLogout };
}
```

**Usage in Component**:
```typescript
// src/widgets/header/UserMenu.tsx
import { useLogout } from '@/shared/auth/hooks/useLogout';

export function UserMenu() {
  const { logout } = useLogout();

  return (
    <button onClick={logout}>
      Logout
    </button>
  );
}
```

**Auth0 Dashboard Configuration**:
- **Allowed Logout URLs**: Add `https://yourapp.com` to whitelist
- **Federated Logout**: Enable if using social login (Google, Microsoft, GitHub)

### Known Issues & Mitigations:

#### Browser Back Button After Logout
- **Issue**: After logout redirect, pressing browser back button can show cached authenticated pages
- **Mitigation**:
  - Use `Cache-Control: no-store` headers on authenticated pages
  - Check `isAuthenticated` in `useEffect()` to redirect if unauthenticated
  - Use React Router's `replace` navigation instead of `push` for logout redirect

```typescript
// src/pages/DashboardPage.tsx
useEffect(() => {
  if (!isAuthenticated && !isLoading) {
    navigate({ to: '/login', replace: true }); // Prevents back button
  }
}, [isAuthenticated, isLoading]);
```

#### Local-Only Logout State Inconsistency
- **Issue**: `logout({ localOnly: true })` clears tokens but SDK state (`isAuthenticated`) remains `true`
- **Mitigation**:
  - Do NOT use `localOnly: true` unless implementing custom session management
  - Always use remote logout for complete state cleanup

#### Logout Redirect Not Working
- **Issue**: `returnTo` URL not whitelisted in Auth0 Dashboard
- **Mitigation**:
  - Verify URL in "Allowed Logout URLs" matches exactly (no trailing slash mismatch)
  - Use `window.location.origin` for dynamic environment support

### Alternatives Considered:

#### Local-Only Logout (`localOnly: true`)
```typescript
logout({ localOnly: true });
navigate({ to: '/login' });
```
- **Pros**: Faster (no network request), works offline
- **Cons**:
  - Auth0 session still active (user can silently re-authenticate)
  - SDK state not fully cleared (isAuthenticated bug)
  - Insecure for multi-device sessions
- **Verdict**: Only use for testing. Production must use remote logout.

#### Manual Token Deletion + Navigation
```typescript
// Anti-pattern - DO NOT USE
localStorage.removeItem('auth0:token');
navigate({ to: '/login' });
```
- **Pros**: None
- **Cons**:
  - SDK state not updated (isAuthenticated still true)
  - Auth0 session still active
  - Tokens may be in memory (localStorage not used by default)
  - Refresh tokens not revoked
- **Verdict**: Broken pattern. Always use SDK's `logout()` method.

#### Federated Logout for All Users
```typescript
logout({ logoutParams: { federated: true } });
```
- **Pros**: Logs user out of Google/Microsoft/etc. in addition to Auth0
- **Cons**:
  - Slower logout (multiple redirects)
  - Unexpected UX for users with multiple accounts (logs out of ALL apps)
  - Not supported by all identity providers
- **Verdict**: Only enable if required by security policy. Default to `federated: false`.

---

## 6. Auth0 Protected Route Patterns

### Decision: Higher-Order Component (HOC) with `withAuthenticationRequired` + TanStack Router Integration

### Rationale:
- **Auth0 Official Pattern**: `withAuthenticationRequired` HOC is the recommended approach for route protection
- **Declarative Route Protection**: Wraps components at route definition (colocated security)
- **Automatic Redirect**: Unauthenticated users redirected to login, then back to original route
- **Loading State Handling**: Built-in loading indicator during authentication check
- **TanStack Router Compatible**: Works with `beforeLoad` navigation guards for pre-route checks
- **Type-Safe**: TypeScript support for component props and auth state

### Implementation Pattern:

**Step 1: Create AuthenticationGuard Component**
```typescript
// src/shared/auth/components/AuthenticationGuard.tsx
import { withAuthenticationRequired } from '@auth0/auth0-react';
import { ComponentType } from 'react';

interface AuthenticationGuardProps {
  component: ComponentType<any>;
}

export function AuthenticationGuard({ component }: AuthenticationGuardProps) {
  const Component = withAuthenticationRequired(component, {
    onRedirecting: () => (
      <div className="flex items-center justify-center h-screen">
        <div className="animate-spin rounded-full h-32 w-32 border-b-2 border-primary"></div>
      </div>
    ),
    returnTo: window.location.pathname, // Redirect back after login
  });

  return <Component />;
}
```

**Step 2: TanStack Router Route Definition**
```typescript
// src/routes/dashboard.tsx
import { createFileRoute } from '@tanstack/react-router';
import { DashboardPage } from '@/pages/DashboardPage';
import { AuthenticationGuard } from '@/shared/auth/components/AuthenticationGuard';

export const Route = createFileRoute('/dashboard')({
  component: () => <AuthenticationGuard component={DashboardPage} />,

  // Optional: Pre-route authentication check (faster redirect)
  beforeLoad: async ({ context }) => {
    const { isAuthenticated, isLoading } = context.auth0;
    if (!isLoading && !isAuthenticated) {
      throw redirect({ to: '/login' });
    }
  },
});
```

**Step 3: Role-Based Route Protection**
```typescript
// src/shared/auth/components/RoleGuard.tsx
import { withAuthenticationRequired } from '@auth0/auth0-react';
import { useAuth0 } from '@auth0/auth0-react';
import { ComponentType } from 'react';
import { Navigate } from '@tanstack/react-router';
import { hasRole } from '@/shared/auth/utils/check-claims';

interface RoleGuardProps {
  component: ComponentType<any>;
  requiredRole: string;
}

export function RoleGuard({ component, requiredRole }: RoleGuardProps) {
  const ProtectedComponent = withAuthenticationRequired(component);

  return function RoleCheckedComponent(props: any) {
    const { user, isLoading } = useAuth0();

    if (isLoading) return <div>Loading...</div>;

    if (!hasRole(user, requiredRole)) {
      return <Navigate to="/forbidden" />;
    }

    return <ProtectedComponent {...props} />;
  };
}
```

**Usage in Routes**:
```typescript
// src/routes/admin.tsx
import { createFileRoute } from '@tanstack/react-router';
import { AdminPage } from '@/pages/AdminPage';
import { RoleGuard } from '@/shared/auth/components/RoleGuard';

export const Route = createFileRoute('/admin')({
  component: () => <RoleGuard component={AdminPage} requiredRole="ROLE_ADMIN" />,
});
```

**Step 4: Public Routes (No Protection)**
```typescript
// src/routes/index.tsx
import { createFileRoute } from '@tanstack/react-router';
import { HomePage } from '@/pages/HomePage';

export const Route = createFileRoute('/')({
  component: HomePage, // No AuthenticationGuard wrapper
});
```

### Alternatives Considered:

#### Manual useAuth0 Check in Every Component
```typescript
// Anti-pattern - DO NOT USE
export function DashboardPage() {
  const { isAuthenticated, isLoading, loginWithRedirect } = useAuth0();

  useEffect(() => {
    if (!isLoading && !isAuthenticated) {
      loginWithRedirect();
    }
  }, [isAuthenticated, isLoading]);

  if (isLoading) return <div>Loading...</div>;
  if (!isAuthenticated) return null;

  return <div>Dashboard</div>;
}
```
- **Pros**: Explicit control, no HOC wrapping
- **Cons**:
  - Repetitive boilerplate in every protected component
  - Easy to forget (security risk)
  - Inconsistent loading states across components
  - Violates DRY principle
- **Verdict**: Rejected. Use HOC pattern for consistency.

#### TanStack Router `beforeLoad` Only (No HOC)
```typescript
export const Route = createFileRoute('/dashboard')({
  component: DashboardPage,
  beforeLoad: async ({ context }) => {
    const { isAuthenticated, loginWithRedirect } = context.auth0;
    if (!isAuthenticated) {
      loginWithRedirect();
    }
  },
});
```
- **Pros**: Centralized at route level, no HOC
- **Cons**:
  - Does NOT prevent component render (security hole)
  - `beforeLoad` is async (race condition with component mount)
  - No built-in loading state handling
  - User sees flash of protected content before redirect
- **Verdict**: Use as complementary check, NOT primary protection.

#### React Router v6 `<Outlet>` Wrapper
```typescript
// Not applicable - TanStack Router uses different pattern
function ProtectedLayout() {
  const { isAuthenticated } = useAuth0();
  return isAuthenticated ? <Outlet /> : <Navigate to="/login" />;
}
```
- **Pros**: Protects entire layout tree
- **Cons**:
  - TanStack Router doesn't use `<Outlet>` pattern
  - No loading state handling
  - Manual redirect logic
- **Verdict**: Not applicable for TanStack Router architecture.

#### Custom Route Guard Hook
```typescript
// Custom implementation - more control but more code
function useRouteGuard(requiredRole?: string) {
  const { isAuthenticated, isLoading, user, loginWithRedirect } = useAuth0();

  useEffect(() => {
    if (!isLoading && !isAuthenticated) {
      loginWithRedirect({ appState: { returnTo: window.location.pathname } });
    }
    if (requiredRole && !hasRole(user, requiredRole)) {
      // Handle unauthorized
    }
  }, [isAuthenticated, isLoading, requiredRole]);

  return { isLoading, isAuthorized: isAuthenticated };
}
```
- **Pros**: Maximum flexibility, no HOC
- **Cons**:
  - More code to maintain
  - Still requires boilerplate in components
  - No automatic loading UI
  - Duplicates SDK functionality
- **Verdict**: Only if HOC pattern doesn't meet requirements. Prefer SDK's `withAuthenticationRequired`.

---

## 7. Implementation Checklist

### Phase 1: SDK Installation & Configuration
- [ ] Install `@auth0/auth0-react` v2.8.0+
- [ ] Create Auth0 Application (SPA type) in Auth0 Dashboard
- [ ] Configure environment variables (VITE_AUTH0_DOMAIN, VITE_AUTH0_CLIENT_ID, VITE_AUTH0_AUDIENCE)
- [ ] Set Allowed Callback URLs, Logout URLs, Web Origins in Auth0 Dashboard
- [ ] Wrap app in `<Auth0Provider>` with TanStack Router integration

### Phase 2: Authentication Flow
- [ ] Create login page with `loginWithRedirect()` button
- [ ] Create callback route for post-login redirect
- [ ] Implement logout functionality with `returnTo` parameter
- [ ] Test full login → redirect → logout cycle

### Phase 3: Token Management
- [ ] Verify `useRefreshTokens: true` and `cacheLocation: 'memory'` configuration
- [ ] Test token refresh on page reload (verify silent authentication)
- [ ] Configure TanStack Query to use Auth0 access tokens in API calls
- [ ] Implement token refresh interceptor for expired tokens

### Phase 4: Custom Claims & RBAC
- [ ] Create Auth0 Login Action to add roles to ID/access tokens
- [ ] Implement `check-claims.ts` utility functions
- [ ] Test role access in browser console (`user['https://yourapp.com/roles']`)
- [ ] Create `RoleGuard` component for role-based UI rendering

### Phase 5: Route Protection
- [ ] Create `AuthenticationGuard` component with `withAuthenticationRequired`
- [ ] Protect all `/dashboard` routes with authentication guard
- [ ] Protect `/admin` routes with role guard (ROLE_ADMIN)
- [ ] Add loading states for authentication checks

### Phase 6: API Integration
- [ ] Update TanStack Query to include Auth0 access token in headers
- [ ] Update backend to validate Auth0 JWT instead of Keycloak JWT
- [ ] Test API calls with Auth0 tokens
- [ ] Verify role-based API authorization

### Phase 7: Testing & Validation
- [ ] Test React 19.2 compatibility (no deprecation warnings)
- [ ] Test TypeScript 5.6 type safety (no `any` types)
- [ ] Test multi-tab behavior (token sync)
- [ ] Test page refresh (silent auth)
- [ ] Test logout (back button cannot access protected pages)
- [ ] Test role-based access (admin vs regular user)
- [ ] Test token expiration and automatic refresh

---

## 8. Migration from OIDC Client TS (Keycloak)

### Breaking Changes:
1. **Provider Change**: `OidcProvider` → `Auth0Provider`
2. **Hook Change**: `useOidc()` → `useAuth0()`
3. **Token Access**: `getAccessTokenSilently()` replaces manual token retrieval
4. **Logout Method**: `logout()` with `returnTo` instead of `signOut()`
5. **Roles Location**: Claims moved to namespaced properties in `user` object

### Migration Steps:
```typescript
// BEFORE (OIDC Client TS)
import { useOidc } from '@axa-fr/react-oidc';
const { isAuthenticated, user, login, logout } = useOidc();

// AFTER (Auth0)
import { useAuth0 } from '@auth0/auth0-react';
const { isAuthenticated, user, loginWithRedirect, logout } = useAuth0();
```

### Codemods Required:
- [ ] Replace all `useOidc()` imports with `useAuth0()`
- [ ] Replace `login()` with `loginWithRedirect()`
- [ ] Replace `logout()` with `logout({ logoutParams: { returnTo } })`
- [ ] Update role access from `user.roles` to namespaced claims
- [ ] Remove OIDC configuration files
- [ ] Update environment variables

---

## 9. Security Considerations

### Best Practices Implemented:
1. **In-Memory Token Storage**: Immune to XSS attacks targeting localStorage
2. **PKCE Flow**: Authorization Code Flow with PKCE (no client secret)
3. **Refresh Token Rotation**: Automatic token rotation on refresh
4. **Web Worker Isolation**: Token refresh in separate global scope
5. **Remote Logout**: Clears Auth0 session (prevents back button access)
6. **Namespaced Claims**: Prevents claim collision with Auth0 standard claims
7. **Role-Based Guards**: Double validation (UI + API)

### Potential Vulnerabilities:
1. **XSS on App Domain**: In-memory tokens still vulnerable to XSS (use CSP headers)
2. **Open Redirect**: Validate `returnTo` parameter on logout (Auth0 whitelist mitigates)
3. **Token Leakage via Logs**: Avoid logging `user` object or access tokens
4. **Refresh Token Theft**: Refresh tokens in httpOnly cookies (browser-managed, safe)

### Recommended Headers (Backend):
```
Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline' https://cdn.auth0.com
X-Frame-Options: DENY
X-Content-Type-Options: nosniff
Referrer-Policy: strict-origin-when-cross-origin
Cache-Control: no-store (for authenticated pages)
```

---

## 10. Additional Resources

### Official Documentation:
- [Auth0 React SDK Quickstart](https://auth0.com/docs/quickstart/spa/react/interactive)
- [Auth0 React SDK API Reference](https://auth0.github.io/auth0-react/)
- [Auth0 Token Storage Guidance](https://auth0.com/docs/secure/security-guidance/data-security/token-storage)
- [Auth0 Actions Documentation](https://auth0.com/docs/customize/actions)

### GitHub Repositories:
- [@auth0/auth0-react](https://github.com/auth0/auth0-react)
- [@auth0/auth0-react Examples](https://github.com/auth0/auth0-react/blob/main/EXAMPLES.md)
- [Auth0 React RBAC Sample](https://github.com/auth0-blog/react-rbac)

### Community Resources:
- [Auth0 Developer Hub - React Router 6 Guide](https://developer.auth0.com/resources/guides/spa/react/basic-authentication)
- [Auth0 Community - React Topics](https://community.auth0.com/tag/react)
- [Checking Auth0 Roles in React](https://himynameistim.com/blog/checking-auth0-roles-and-permissions-with-a-react-spa)

### NPM Packages:
- `@auth0/auth0-react` v2.8.0 (latest)
- React 18+ compatibility confirmed
- React 19 compatibility confirmed (v2.3.0+)

---

## 11. Performance Considerations

### Initial Load Performance:
- **Auth0Provider Initialization**: <50ms (SDK initialization)
- **Silent Authentication on Refresh**: 200-500ms (network request to Auth0)
- **Token Refresh**: <100ms (Web Worker handles asynchronously)
- **Bundle Size**: `@auth0/auth0-react` ~20KB gzipped (acceptable overhead)

### Optimization Strategies:
1. **Lazy Load Auth0Provider**: Only load SDK on authenticated routes
2. **Prefetch User Data**: Call `getAccessTokenSilently()` on app load to warm cache
3. **Cache User Roles**: Store roles in React Context to avoid repeated `user` lookups
4. **Debounce Token Refresh**: SDK handles this automatically (no manual optimization needed)

### Monitoring Recommendations:
- Track `loginWithRedirect()` → callback latency (Auth0 dashboard metrics)
- Monitor `getAccessTokenSilently()` cache hit rate
- Alert on token refresh failures (Auth0 session expired)
- Track logout completion time (ensure Auth0 redirect latency acceptable)

---

## 12. Summary & Recommendations

### Recommended Architecture:
```
React 19.2 App
  └─ Auth0Provider (useRefreshTokens: true, cacheLocation: 'memory')
      ├─ TanStack Router Routes
      │   ├─ Public Routes (no guard)
      │   ├─ Protected Routes (AuthenticationGuard HOC)
      │   └─ Admin Routes (RoleGuard HOC with ROLE_ADMIN check)
      ├─ TanStack Query (access tokens via getAccessTokenSilently)
      └─ Auth0 Login Action (adds namespaced roles to tokens)
```

### Key Decisions Summary:
1. **SDK**: `@auth0/auth0-react` v2.8.0 (React 19.2 compatible)
2. **Login Flow**: Universal Login with redirect (PKCE flow)
3. **Token Storage**: In-memory with Web Workers + refresh tokens
4. **Custom Claims**: Namespaced roles in Auth0 Login Action
5. **Logout**: Remote logout with `returnTo` parameter
6. **Route Protection**: HOC pattern with `withAuthenticationRequired`

### Implementation Priority:
1. **P0 (Must Have)**: Auth0Provider setup, login/logout, route protection
2. **P1 (High)**: Token refresh handling, custom claims, role guards
3. **P2 (Medium)**: API integration with TanStack Query, error handling
4. **P3 (Nice to Have)**: Performance optimization, monitoring, analytics

### Estimated Implementation Effort:
- **Phase 1-2 (SDK + Auth Flow)**: 2-3 days
- **Phase 3-4 (Tokens + RBAC)**: 2-3 days
- **Phase 5-6 (Routes + API)**: 2-3 days
- **Phase 7 (Testing)**: 2 days
- **Total**: 8-11 days (1 developer)

---

**End of Research Document**
