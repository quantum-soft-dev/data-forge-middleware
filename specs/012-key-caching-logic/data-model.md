# Data Model: Token Refresh and Auto-Logout

**Date**: 2025-12-01
**Feature**: 012-key-caching-logic

## Overview

This feature is entirely frontend-based. There are no database schema changes. This document describes the client-side state models and data flow.

---

## Client-Side State Models

### 1. Token Refresh State

**Location**: `frontend/src/shared/api/token-refresh.ts`

```typescript
/**
 * Token refresh lock state.
 * Prevents multiple concurrent refresh attempts.
 */
interface TokenRefreshState {
  /** Whether a token refresh is currently in progress */
  isRefreshing: boolean;

  /** Promise that resolves when refresh completes (success or failure) */
  refreshPromise: Promise<string> | null;

  /** Callback to get fresh token from Auth0 */
  getAccessToken: (() => Promise<string>) | null;

  /** Callback to logout user on refresh failure */
  onLogout: (() => void) | null;
}
```

**State Transitions**:

```
┌─────────────┐     401 Error     ┌─────────────────┐
│    IDLE     │ ─────────────────►│   REFRESHING    │
│ isRefreshing│                   │  isRefreshing   │
│   = false   │                   │    = true       │
└─────────────┘                   └────────┬────────┘
       ▲                                   │
       │                                   │
       │         ┌─────────────────────────┼─────────────────────────┐
       │         │                         │                         │
       │         ▼                         ▼                         ▼
       │  ┌──────────────┐         ┌──────────────┐         ┌──────────────┐
       │  │   SUCCESS    │         │   FAILURE    │         │   FAILURE    │
       │  │  New Token   │         │ Network Err  │         │ Token Expired│
       │  └──────┬───────┘         └──────┬───────┘         └──────┬───────┘
       │         │                        │                        │
       └─────────┘                        │                        │
                                          │                        │
                                          ▼                        ▼
                                   ┌──────────────┐         ┌──────────────┐
                                   │ Show Error   │         │   LOGOUT     │
                                   │   Toast      │         │ + Session    │
                                   │              │         │   Expired    │
                                   └──────────────┘         └──────────────┘
```

---

### 2. Session Expiry State

**Location**: `frontend/src/shared/lib/auth/session-expiry.ts`

```typescript
/**
 * Session expiry state for displaying message on login page.
 * Uses sessionStorage for persistence across redirects.
 */
interface SessionExpiryState {
  /** Whether session was expired (triggers message display) */
  isExpired: boolean;

  /** Reason for expiry (for logging/analytics) */
  reason: 'refresh_token_expired' | 'manual_logout' | null;
}
```

**Storage Key**: `dfm_session_expired`

**Data Format** (sessionStorage):
```json
{
  "isExpired": true,
  "reason": "refresh_token_expired",
  "timestamp": "2025-12-01T10:30:00Z"
}
```

---

### 3. Request Retry Config Extension

**Location**: Extends Axios `InternalAxiosRequestConfig`

```typescript
/**
 * Extended Axios config to track retry state.
 * Prevents infinite retry loops.
 */
interface ExtendedAxiosRequestConfig extends InternalAxiosRequestConfig {
  /** Flag indicating this request has already been retried after 401 */
  _retry?: boolean;
}
```

---

## Data Flow

### Successful Token Refresh Flow

```
┌─────────┐     ┌─────────────┐     ┌──────────────┐     ┌────────────┐
│ API     │     │   Axios     │     │   Token      │     │   Auth0    │
│ Request │────►│ Interceptor │────►│   Refresh    │────►│    SDK     │
└─────────┘     └──────┬──────┘     │   Manager    │     └─────┬──────┘
                       │            └──────┬───────┘           │
                       │                   │                   │
                  401 Error                │ getAccessToken    │
                       │                   │ Silently()        │
                       │                   │◄──────────────────┘
                       │                   │    New Token
                       │                   │
                       │◄──────────────────┘
                       │    Retry with new token
                       │
                       ▼
                ┌──────────────┐
                │   Original   │
                │   Response   │
                └──────────────┘
```

### Failed Token Refresh Flow (Logout)

```
┌─────────┐     ┌─────────────┐     ┌──────────────┐     ┌────────────┐
│ API     │     │   Axios     │     │   Token      │     │   Auth0    │
│ Request │────►│ Interceptor │────►│   Refresh    │────►│    SDK     │
└─────────┘     └──────┬──────┘     │   Manager    │     └─────┬──────┘
                       │            └──────┬───────┘           │
                       │                   │                   │
                  401 Error                │ getAccessToken    │
                       │                   │ Silently()        │
                       │                   │◄──────────────────┘
                       │                   │    Error: invalid_grant
                       │                   │
                       │                   ▼
                       │            ┌──────────────┐
                       │            │ Set Session  │
                       │            │ Expired Flag │
                       │            └──────┬───────┘
                       │                   │
                       │                   ▼
                       │            ┌──────────────┐     ┌────────────┐
                       │            │   Logout     │────►│   Auth0    │
                       │            │   User       │     │   Login    │
                       │            └──────────────┘     └────────────┘
```

---

## External Dependencies

### Auth0 SDK State (Managed by @auth0/auth0-react)

```typescript
// From useAuth0() hook - we consume but don't manage
interface Auth0State {
  isAuthenticated: boolean;
  isLoading: boolean;
  user: User | undefined;
  error: Error | undefined;
  getAccessTokenSilently: (options?) => Promise<string>;
  logout: (options?) => void;
}
```

### Axios Error Object (Consumed in Interceptor)

```typescript
// Standard Axios error structure we consume
interface AxiosError {
  response?: {
    status: number;
    data: any;
    headers: any;
  };
  request?: any;
  message: string;
  config: InternalAxiosRequestConfig;
}
```

---

## Type Definitions Summary

All types to be created in `frontend/src/shared/api/types.ts`:

```typescript
// Export from shared/api/types.ts

/** Token refresh callback types */
export type GetAccessTokenFn = () => Promise<string>;
export type LogoutFn = () => void;

/** Auth0 error codes that trigger logout */
export type Auth0ExpiredErrorCode =
  | 'missing_refresh_token'
  | 'invalid_grant'
  | 'login_required';

/** Session expiry reasons */
export type SessionExpiryReason =
  | 'refresh_token_expired'
  | 'manual_logout';

/** Session expiry storage data */
export interface SessionExpiryData {
  isExpired: boolean;
  reason: SessionExpiryReason | null;
  timestamp: string;
}

/** Extended axios config with retry flag */
export interface RetryableAxiosConfig extends InternalAxiosRequestConfig {
  _retry?: boolean;
}
```

---

## No Database Changes

This feature operates entirely on the client side. There are no changes to:

- PostgreSQL schema
- Flyway migrations
- Backend entities or repositories
- Backend API endpoints

All state is managed in browser memory (token refresh lock) and sessionStorage (session expiry flag).
