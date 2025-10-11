# Research: Basic UI with Keycloak Authentication and Subscriber Management

**Date**: 2025-10-11
**Feature**: [spec.md](./spec.md)
**Status**: Complete

## Overview

This document captures technical research decisions made during Phase 0 planning for the Basic UI feature. All NEEDS CLARIFICATION items from the Technical Context have been resolved through systematic evaluation of alternatives.

## Research Topics

### 1. OAuth 2.0 / OpenID Connect Client Library

**Problem**: Keycloak.js library is incompatible with React 19. Need alternative OAuth 2.0 / OIDC client library that supports:
- Authorization Code Flow + PKCE
- Automatic token refresh with rotation
- Silent background token refresh
- Token storage in memory/sessionStorage (NOT localStorage)
- Integration with TanStack Query

**Decision**: **react-oidc-context**

**Rationale**:
react-oidc-context is the optimal choice for React 19 with Keycloak because it provides a production-ready, battle-tested OIDC implementation with automatic token refresh, minimal bundle size impact (~21.7 KB gzipped), and native React integration via hooks and context API. The library is actively maintained (July 2025 commits), has 220K+ weekly downloads, supports React 16.14.0+, and includes official Keycloak sample implementations, eliminating security risks inherent in manual OAuth2 implementations.

**Alternatives Considered**:
- **oidc-client-ts (direct)**: Rejected because it requires manual React state management and lacks React-specific abstractions like useAuth() hook, increasing implementation complexity and potential for errors.
- **Manual OAuth2 PKCE implementation**: Rejected due to high security risk (missing critical edge cases), significant development effort (200+ LOC vs 30 LOC), lack of automatic security updates, and testing burden across multiple browsers and Keycloak versions.

**Integration Plan**:
- Wrap application root with `<AuthProvider>` component configured for Keycloak (authority, client_id, redirect_uri, automaticSilentRenew: true)
- Use `useAuth()` hook to access tokens and user state within React components
- Create axios interceptor component that uses `useAuth()` to inject bearer tokens into all API requests via `axios.interceptors.request.use()`
- Configure TanStack Query's `queryClient` to use the axios instance with authentication interceptor (authentication handled at network layer, independent of query logic)
- Store tokens in sessionStorage (default) for security with automatic cleanup on tab close

**Bundle Size Impact**:
~21.7 KB minified + gzipped (react-oidc-context + oidc-client-ts dependency combined), well within the 500KB total constraint. The library uses native browser crypto.subtle APIs (no crypto library dependencies) and has zero effect on tree-shaking other dependencies.

**Security Considerations**:
- Implements OAuth 2.0 Security Best Current Practice (BCP)
- PKCE enabled by default
- Token refresh rotation supported
- No XSS risk from localStorage (uses sessionStorage)
- Automatic token cleanup on session end

---

### 2. Charting / Visualization Library

**Problem**: Dashboard requires "visually appealing" charts and graphs for demo data visualization while maintaining strict bundle size constraint (<500KB total application size gzipped).

**Decision**: **Recharts** (via shadcn/ui Chart component)

**Rationale**:
Recharts offers the best balance for this use case: it's React 19 compatible (with simple package.json override), fully TypeScript-supported, integrates seamlessly with shadcn/ui's chart components and Tailwind CSS, and provides a declarative component-based API perfect for demo dashboards. The 139KB gzipped bundle size is acceptable within the <500KB total constraint, and shadcn/ui's copy-paste approach with tree-shaking ensures you only include the chart types you actually use.

**Alternatives Considered**:
- **Chart.js (71KB) + react-chartjs-2**: Smallest bundle at ~71-118KB gzipped, but react-chartjs-2 lacks official React 19 peer dependency support (open GitHub issue as of Dec 2024) and requires imperative Canvas API rather than declarative React components.
- **Nivo (125KB)**: Excellent D3-powered charts with modular architecture, but 125KB base size is larger than needed for demo data, and styling customization is more complex than Recharts with Tailwind.
- **Victory (117KB)**: Strong performance but 117KB gzipped with less active maintenance than Recharts, and doesn't integrate as naturally with shadcn/ui ecosystem.
- **D3.js (70KB+ minimum)**: Maximum flexibility but requires significant development effort for basic charts, poor tree-shaking effectiveness (can bloat to 1000+ lines for single function), and steep learning curve unsuitable for demo dashboards.
- **Visx (12KB core, modular)**: Smallest modular option from Airbnb with excellent performance, but low-level primitives require more implementation work than Recharts' ready-to-use components for demo purposes.

**Bundle Size Impact**:
Estimated: 130-140KB gzipped for Recharts with 3-5 chart types through shadcn/ui. Using shadcn/ui's copy-paste chart components ensures only imported Recharts primitives are bundled (tree-shaking effective). This leaves 360-370KB for the rest of the React 19 application within the 500KB total constraint.

**React 19 Compatibility Note**:
Requires `package.json` override for `react-is` dependency to match React 19 version - standard workaround documented in shadcn/ui React 19 migration guide.

**Recommended Chart Types for Dashboard**:
1. **Area Chart** - Trending metrics over time (user activity, system load)
2. **Bar Chart** - Category comparisons (feature usage, resource allocation)
3. **Line Chart** - Time-series data (performance metrics, API response times)
4. **Pie/Donut Chart** - Proportional distributions (storage usage, error types)
5. **Radial/Radar Chart** - Multi-dimensional system health scores

All chart types are available through shadcn/ui's chart component library with consistent Tailwind styling and built-in responsive behavior.

---

## Best Practices Research

### OAuth 2.0 / OIDC Integration with Keycloak

**Flow**: Authorization Code Flow + PKCE (Proof Key for Code Exchange)
- Most secure flow for SPAs in 2025
- Protects against authorization code interception attacks
- Required by OAuth 2.0 Security Best Current Practice

**Token Storage Strategy**:
- **Access Token**: SessionStorage (short-lived, 8 hours per SC-012)
- **Refresh Token**: SessionStorage (rotation on each refresh)
- **User Info**: React Context (memory only)
- **Avoid**: localStorage (XSS vulnerability), cookies (CSRF risk without proper setup)

**Token Refresh Pattern**:
- Silent refresh: triggered automatically before expiration (react-oidc-context handles this)
- No user interruption (per FR-019 and clarification)
- Fallback: If refresh fails, redirect to login

**Keycloak Configuration Requirements** (Assumed to exist per Dependency #1):
- Client Type: Public (SPA cannot securely store client secret)
- Valid Redirect URIs: http://localhost:3000/*, https://app.domain.com/*
- Web Origins: http://localhost:3000, https://app.domain.com
- PKCE: Required
- Proof Key for Code Exchange Code Challenge Method: S256

### React Query Integration Patterns

**Query Key Factory Pattern**:
```
subscriberKeys = {
  all: ['subscribers'] as const,
  lists: () => [...subscriberKeys.all, 'list'] as const,
  list: (filters: SubscriberFilters) => [...subscriberKeys.lists(), filters] as const,
  details: () => [...subscriberKeys.all, 'detail'] as const,
  detail: (id: string) => [...subscriberKeys.details(), id] as const,
}
```

**Optimistic Updates for CRUD**:
- Create: Append to list immediately, rollback on error
- Update: Update in cache, rollback on error
- Delete: Remove from list, rollback on error
- Benefit: Instant UI feedback, better perceived performance

**Pagination Strategy**:
- Use `useInfiniteQuery` for large datasets (10K subscribers)
- Page size: 50-100 records per page (balance between API calls and memory)
- Prefetch next page on scroll to 80% (smooth UX)

### TanStack Table Virtualization

**Virtualization for 10K Records**:
- Only render visible rows (DOM nodes limited to viewport + buffer)
- Row height: Fixed (easier) or dynamic (more flexible)
- Estimated performance: 60 FPS scrolling with 10K rows
- Memory footprint: ~50-100KB for 10K row data (minimal)

**Column Configuration**:
- ID: 80px, fixed
- Name: 200px, flexible
- Email: 250px, flexible
- Phone: 150px, flexible
- Company: 200px, flexible
- Status: 100px, fixed (badge)
- Created: 150px, fixed
- Actions: 120px, fixed (edit/delete buttons)

### Form Validation Best Practices

**Zod Schema Pattern**:
- Define schema once, infer TypeScript types
- Reuse schema for create and update (with `.partial()` for optional updates)
- Custom refinements for business rules (e.g., email uniqueness check)

**Validation Timing**:
- onChange: For fields with real-time feedback (e.g., password strength)
- onBlur: For most fields (balance UX and performance)
- onSubmit: Final validation before API call (always)

**Error Display**:
- Field-level errors: Displayed below input, linked via aria-describedby
- Form-level errors: Displayed at top (e.g., "Email already exists")
- Accessible: ARIA labels, focus management, keyboard navigation

### Responsive Design Breakpoints

**Tailwind CSS Breakpoints** (aligned with shadcn/ui):
- Mobile: < 640px (sm) - Single column, stacked forms
- Tablet: 640px - 1024px (sm-lg) - 2 columns, sidebar navigation
- Desktop: ≥ 1024px (lg+) - Full layout, multi-column tables

**Component Adaptations**:
- Navigation: Hamburger menu (mobile) → Sidebar (tablet+)
- Tables: Horizontal scroll (mobile) → Full table (desktop)
- Forms: Stacked (mobile) → Grid (tablet+)
- Modals: Full screen (mobile) → Centered (desktop)

---

## Technology Stack Finalization

### Updated Dependencies (from Technical Context)

**Frontend**:
- React 19.2, TypeScript (strict mode)
- Vite (build tool)
- shadcn/ui components + Tailwind CSS
- Zustand (state management) - React 19 compatible alternative to TanStack Store
- TanStack Query (server state, data fetching)
- TanStack Router (type-safe routing)
- TanStack Table (virtualization for 10K+ records)
- React Hook Form + Zod (forms, validation)
- **react-oidc-context** (OAuth 2.0 / OIDC client) - ✅ RESOLVED
- **Recharts** (charts via shadcn/ui) - ✅ RESOLVED
- Sonner (toast notifications)
- axios (HTTP client with interceptors)

**Testing**:
- Vitest + Testing Library (unit, integration)
- Playwright (E2E)
- MSW (Mock Service Worker for API mocking)

**Development**:
- ESLint + TypeScript ESLint
- Prettier (code formatting)
- Husky (git hooks)
- lint-staged (pre-commit checks)

### Estimated Bundle Size Budget

| Category | Estimated Size (gzipped) | Percentage |
|----------|--------------------------|------------|
| React 19 + ReactDOM | ~45 KB | 9% |
| TanStack libraries (Query, Router, Table) | ~32 KB | 6.4% |
| Zustand | ~3 KB | 0.6% |
| react-oidc-context + oidc-client-ts | ~22 KB | 4% |
| Recharts (3-5 charts) | ~135 KB | 27% |
| shadcn/ui components (10-15 components) | ~60 KB | 12% |
| React Hook Form + Zod | ~20 KB | 4% |
| Tailwind CSS (purged) | ~15 KB | 3% |
| axios | ~15 KB | 3% |
| Sonner | ~5 KB | 1% |
| Application code (estimated) | ~100 KB | 20% |
| Vendor chunks (misc) | ~48 KB | 9.6% |
| **Total** | **~500 KB** | **100%** |

**Notes**:
- Budget is at maximum; monitor bundle size during development
- Code splitting via React.lazy for routes can reduce initial load
- Tree-shaking effectiveness critical (especially Recharts via shadcn/ui)

---

## Integration Architecture

### Authentication Flow Diagram

```
1. User → Application (unauthenticated)
2. Application → Keycloak (/auth/realms/{realm}/protocol/openid-connect/auth)
3. User authenticates in Keycloak
4. Keycloak → Application (/callback with authorization code)
5. react-oidc-context exchanges code for tokens (PKCE)
6. Tokens stored in sessionStorage
7. Application renders Dashboard
8. API calls include JWT in Authorization header
9. Token expiring → Silent refresh (iframe)
10. Logout → Keycloak end session → Clear sessionStorage
```

### Data Flow Architecture

```
Component (UI Layer)
  ↓ uses hook
React Query Hook (Data Layer)
  ↓ calls
Axios Instance (Network Layer)
  ↓ adds JWT via interceptor
Backend API (Spring Boot)
  ↓ validates JWT
PostgreSQL (Data Store)
```

**Separation of Concerns**:
- UI: Renders data, captures user input
- Data: Manages server state (caching, refetching)
- Network: Handles HTTP, authentication
- Backend: Business logic, persistence

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Bundle size exceeds 500KB | Medium | High | Monitor bundle size in CI, use bundle analyzer, code split routes |
| React 19 compatibility issues | Low | Medium | react-oidc-context and Recharts both compatible (with package.json override) |
| Keycloak token refresh failures | Low | High | Implement fallback to login, add retry logic, log errors for monitoring |
| 10K subscriber performance | Low | Medium | TanStack Table virtualization tested for 100K+ rows, pagination reduces load |
| OAuth 2.0 misconfiguration | Medium | High | Follow react-oidc-context examples, test with actual Keycloak instance early |

---

## Assumptions Validation

The following assumptions from spec.md have been validated through research:

1. ✅ **Keycloak Configuration** - react-oidc-context supports standard Keycloak OIDC configuration
2. ✅ **Backend API Availability** - Integration via axios interceptor pattern (standard approach)
3. ✅ **Browser Support** - All selected libraries support modern evergreen browsers
4. ✅ **Token Storage** - sessionStorage supported by react-oidc-context (default)
5. ✅ **Dashboard Data** - Recharts handles demo/fake data easily via component props

---

## Next Steps

Phase 0 (Research) is now complete. Proceed to Phase 1 (Design & Contracts):

1. Generate `data-model.md` - Define TypeScript types and Zod schemas for Subscriber, User Session, Dashboard Metrics
2. Generate `contracts/` - Define OpenAPI contract for backend API integration + TypeScript type definitions
3. Generate `quickstart.md` - Document frontend setup, development workflow, and deployment
4. Update agent context via `.specify/scripts/bash/update-agent-context.sh claude`
5. Re-evaluate Constitution Check post-design

**Phase 1 Output Files**:
- `data-model.md`
- `contracts/api-contracts.yaml`
- `contracts/type-definitions.ts`
- `quickstart.md`
- `.claude/settings.local.json` (updated)
