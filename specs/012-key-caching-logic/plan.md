# Implementation Plan: Token Refresh and Auto-Logout

**Branch**: `012-key-caching-logic` | **Date**: 2025-12-01 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/012-key-caching-logic/spec.md`

## Summary

Implement automatic token refresh on 401 API errors using Auth0's `getAccessTokenSilently()` with request retry logic. When refresh fails (refresh token expired), gracefully logout user and redirect to login page with session expiry message. Prevents multiple concurrent refresh attempts through a token refresh lock mechanism.

## Technical Context

**Language/Version**: TypeScript 5.6 (React 19.2)
**Primary Dependencies**: @auth0/auth0-react 2.8.0, Axios, TanStack Query v5
**Storage**: Memory (Auth0 SDK handles token storage securely)
**Testing**: Vitest + React Testing Library
**Target Platform**: Web (React SPA)
**Project Type**: Web application (frontend only - this feature is entirely client-side)
**Performance Goals**: Token refresh + retry < 3 seconds (per SC-003)
**Constraints**: No duplicate refresh attempts on concurrent 401s (per SC-004), transparent to user (per NFR-001)
**Scale/Scope**: Single-page application, all API calls through centralized Axios client

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| VIII. Feature-Sliced Design | ✅ PASS | Token refresh logic in `shared/api/` layer (interceptors), session expiry state in `entities/user-session/` |
| IX. Type Safety First | ✅ PASS | Will use explicit types for refresh state, queue, and error handling |
| X. React Query for Server State | ✅ PASS | Interceptor-level refresh transparent to React Query hooks |
| XI. TDD for Frontend | ✅ PASS | Will write tests first for interceptor logic, session expiry UI |
| XII. Keycloak SSO Integration | ⚠️ N/A | Constitution mentions Keycloak but project uses Auth0 - same principles apply |
| XIII. Component Composition | ✅ PASS | Session expiry message via context/hook, not prop drilling |
| XIV. Form Validation with Zod | ✅ N/A | No forms in this feature |
| XV. Performance & Bundle | ✅ PASS | Minimal code addition (<5KB), no new dependencies |
| XVI. Accessibility & Security | ✅ PASS | Session expiry message will be accessible, secure token handling via Auth0 SDK |

**Constitution Compliance**: All applicable principles satisfied. No violations requiring justification.

## Project Structure

### Documentation (this feature)

```
specs/012-key-caching-logic/
├── plan.md              # This file
├── research.md          # Phase 0: Auth0 token refresh patterns
├── data-model.md        # Phase 1: Token refresh state model
├── quickstart.md        # Phase 1: Implementation guide
├── contracts/           # Phase 1: Error handling contracts (N/A for this feature)
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```
frontend/
├── src/
│   ├── shared/
│   │   ├── api/
│   │   │   ├── interceptors.ts      # MODIFY: Add 401 interceptor with refresh logic
│   │   │   ├── error-handler.ts     # MODIFY: Update 401 handling to integrate with refresh
│   │   │   ├── token-refresh.ts     # NEW: Token refresh lock and queue management
│   │   │   └── client.ts            # EXISTING: Axios client (no changes)
│   │   └── lib/
│   │       └── auth/
│   │           └── session-expiry.ts # NEW: Session expiry state management
│   ├── entities/
│   │   └── user-session/
│   │       ├── api/
│   │       │   └── useAuth.ts       # EXISTING: Auth hook (minor integration)
│   │       └── ui/
│   │           └── SessionExpiredBanner.tsx # NEW: Session expiry UI component
│   ├── app/
│   │   └── providers/
│   │       └── Auth0Provider.tsx    # EXISTING: May need callback integration
│   └── pages/
│       └── login/                   # EXISTING: Display session expiry message
└── tests/
    ├── unit/
    │   └── shared/api/
    │       └── token-refresh.test.ts # NEW: Unit tests for refresh logic
    └── integration/
        └── token-refresh.integration.test.ts # NEW: Integration test with mock Auth0
```

**Structure Decision**: This feature modifies the existing `shared/api/` layer (interceptors) and adds a new token refresh module. Session expiry state is managed in `shared/lib/auth/` with UI component in `entities/user-session/ui/`. Follows FSD architecture - bottom-up imports only.

## Complexity Tracking

*No violations requiring justification. Feature follows standard frontend patterns.*
