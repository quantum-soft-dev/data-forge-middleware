# Data Model: Basic UI with Keycloak Authentication and Subscriber Management

**Date**: 2025-10-11
**Feature**: [spec.md](./spec.md)
**Status**: Phase 1 - Design

## Overview

This document defines the data structures, validation rules, and state management for the Basic UI feature. All types are defined using TypeScript with Zod schemas for runtime validation. The data model follows the Feature-Sliced Design (FSD) architecture with entities defined in the `entities/` layer.

## Entity Definitions

### 1. Subscriber Entity

**Purpose**: Represents a client or customer in the system with contact information and status.

**Location**: `frontend/src/entities/subscriber/model/types.ts`

#### TypeScript Type

```typescript
export interface Subscriber {
  id: string;              // UUID from backend
  name: string;            // Required, 2-100 characters
  email: string;           // Required, unique, valid email format
  phoneNumber: string | null;  // Optional, E.164 format recommended
  companyName: string | null;  // Optional, 2-100 characters
  status: SubscriberStatus;    // Enum: 'active' | 'inactive'
  createdAt: string;       // ISO 8601 datetime from backend
  updatedAt: string;       // ISO 8601 datetime from backend
}

export type SubscriberStatus = 'active' | 'inactive';

export interface SubscriberFilters {
  search?: string;         // Search across name, email, company
  status?: SubscriberStatus;
  page: number;            // 0-indexed
  pageSize: number;        // 50-100 recommended
  sortBy?: 'name' | 'email' | 'createdAt';
  sortOrder?: 'asc' | 'desc';
}

export interface SubscriberListResponse {
  content: Subscriber[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
```

#### Zod Schema

```typescript
import { z } from 'zod';

export const subscriberStatusSchema = z.enum(['active', 'inactive']);

export const subscriberSchema = z.object({
  id: z.string().uuid(),
  name: z.string().min(2, 'Name must be at least 2 characters').max(100, 'Name must be at most 100 characters'),
  email: z.string().email('Invalid email format'),
  phoneNumber: z.string().nullable(),
  companyName: z.string().min(2).max(100).nullable(),
  status: subscriberStatusSchema,
  createdAt: z.string().datetime(),
  updatedAt: z.string().datetime(),
});

export const subscriberFiltersSchema = z.object({
  search: z.string().optional(),
  status: subscriberStatusSchema.optional(),
  page: z.number().int().min(0),
  pageSize: z.number().int().min(1).max(100),
  sortBy: z.enum(['name', 'email', 'createdAt']).optional(),
  sortOrder: z.enum(['asc', 'desc']).optional(),
});

export const subscriberListResponseSchema = z.object({
  content: z.array(subscriberSchema),
  page: z.number().int().min(0),
  size: z.number().int().min(1),
  totalElements: z.number().int().min(0),
  totalPages: z.number().int().min(0),
});

// Infer TypeScript types from Zod schemas (alternative to manual types above)
export type Subscriber = z.infer<typeof subscriberSchema>;
export type SubscriberFilters = z.infer<typeof subscriberFiltersSchema>;
export type SubscriberListResponse = z.infer<typeof subscriberListResponseSchema>;
```

#### Form Schemas

```typescript
// Create Subscriber Form Schema
export const createSubscriberSchema = z.object({
  name: z.string()
    .min(2, 'Name must be at least 2 characters')
    .max(100, 'Name must be at most 100 characters')
    .trim(),
  email: z.string()
    .email('Invalid email format')
    .toLowerCase()
    .trim(),
  phoneNumber: z.string()
    .trim()
    .optional()
    .transform(val => val === '' ? null : val),
  companyName: z.string()
    .min(2, 'Company name must be at least 2 characters')
    .max(100, 'Company name must be at most 100 characters')
    .trim()
    .optional()
    .transform(val => val === '' ? null : val),
});

export type CreateSubscriberFormData = z.infer<typeof createSubscriberSchema>;

// Update Subscriber Form Schema (allows partial updates)
export const updateSubscriberSchema = createSubscriberSchema.partial().extend({
  id: z.string().uuid(),
});

export type UpdateSubscriberFormData = z.infer<typeof updateSubscriberSchema>;
```

#### Validation Rules

| Field | Required | Min Length | Max Length | Format | Uniqueness |
|-------|----------|------------|------------|--------|------------|
| id | Yes (backend) | N/A | N/A | UUID v4 | Yes (backend) |
| name | Yes | 2 | 100 | Unicode | No |
| email | Yes | N/A | N/A | RFC 5322 | Yes (backend check) |
| phoneNumber | No | N/A | N/A | E.164 recommended | No |
| companyName | No | 2 | 100 | Unicode | No |
| status | Yes | N/A | N/A | Enum | No |
| createdAt | Yes (backend) | N/A | N/A | ISO 8601 | No |
| updatedAt | Yes (backend) | N/A | N/A | ISO 8601 | No |

**Client-side Validation** (per FR-018):
- Field format validation (email, lengths)
- Required field presence
- Trim whitespace
- Transform empty strings to null for optional fields

**Server-side Validation** (per FR-010, Constraint 5):
- Email uniqueness (authoritative check)
- Final data integrity validation
- Returns 400 Bad Request with field-specific errors
- Returns 409 Conflict for duplicate email

#### State Transitions

```
[CREATE] → active (default status)
active → inactive (soft delete or deactivation)
inactive → active (reactivation)
```

**Delete Operation**: Soft delete - status set to 'inactive', record retained in database (per Assumption #4).

---

### 2. User Session Entity

**Purpose**: Represents an authenticated user's session with Keycloak tokens and identity information.

**Location**: `frontend/src/entities/user-session/model/types.ts`

#### TypeScript Type

```typescript
export interface UserSession {
  user: UserInfo;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: AuthError | null;
}

export interface UserInfo {
  sub: string;              // Subject identifier (user ID)
  name?: string;            // Display name
  preferred_username?: string;  // Username
  email?: string;           // Email address
  email_verified?: boolean; // Email verification status
  roles?: string[];         // Keycloak roles (e.g., ['ROLE_ADMIN', 'ROLE_USER'])
}

export interface AuthError {
  error: string;            // Error code (e.g., 'login_required', 'access_denied')
  error_description?: string;  // Human-readable error description
}

export interface AuthConfig {
  authority: string;        // Keycloak URL: https://keycloak.example.com/realms/{realm}
  client_id: string;        // Client ID from Keycloak
  redirect_uri: string;     // http://localhost:3000/callback or https://app.domain.com/callback
  post_logout_redirect_uri: string;  // Redirect after logout
  scope: string;            // 'openid profile email' (standard scopes)
  automaticSilentRenew: boolean;  // true (silent token refresh)
  loadUserInfo: boolean;    // true (fetch user info from /userinfo endpoint)
}

export interface TokenInfo {
  access_token: string;     // JWT access token
  token_type: string;       // 'Bearer'
  expires_at: number;       // Unix timestamp (seconds)
  refresh_token?: string;   // Refresh token (if rotation enabled)
  id_token?: string;        // ID token (OIDC)
  scope: string;            // Granted scopes
}
```

#### Zod Schema

```typescript
import { z } from 'zod';

export const userInfoSchema = z.object({
  sub: z.string(),
  name: z.string().optional(),
  preferred_username: z.string().optional(),
  email: z.string().email().optional(),
  email_verified: z.boolean().optional(),
  roles: z.array(z.string()).optional(),
});

export const authErrorSchema = z.object({
  error: z.string(),
  error_description: z.string().optional(),
});

export const userSessionSchema = z.object({
  user: userInfoSchema,
  isAuthenticated: z.boolean(),
  isLoading: z.boolean(),
  error: authErrorSchema.nullable(),
});

export type UserSession = z.infer<typeof userSessionSchema>;
export type UserInfo = z.infer<typeof userInfoSchema>;
export type AuthError = z.infer<typeof authErrorSchema>;
```

#### Storage Strategy

| Data | Storage Location | Lifetime | Security |
|------|------------------|----------|----------|
| Access Token | SessionStorage (react-oidc-context) | Until expiration or tab close | XSS mitigation via sessionStorage |
| Refresh Token | SessionStorage (react-oidc-context) | Until rotation or tab close | Rotation on each refresh |
| User Info | React Context (memory) | Until logout or tab close | No persistence |
| Auth State | React Context (memory) | Until logout or tab close | No persistence |

**Notes**:
- SessionStorage cleared on tab close (per Constraint 2)
- No localStorage usage (XSS vulnerability concern)
- Tokens never logged or exposed in console
- Silent refresh at 80% of access token lifetime (react-oidc-context default)

#### State Transitions

```
unauthenticated → loading (login initiated)
loading → authenticated (login success)
loading → unauthenticated (login failure)
authenticated → loading (logout initiated)
loading → unauthenticated (logout complete)
authenticated → authenticated (silent token refresh)
```

---

### 3. Dashboard Metrics Entity

**Purpose**: Represents analytical data displayed on the dashboard (demo/fake data initially).

**Location**: `frontend/src/entities/dashboard-metrics/model/types.ts`

#### TypeScript Type

```typescript
export interface DashboardMetrics {
  summary: MetricsSummary;
  charts: DashboardCharts;
  lastUpdated: string;      // ISO 8601 datetime (fake timestamp for demo)
}

export interface MetricsSummary {
  totalSubscribers: number;
  activeSubscribers: number;
  inactiveSubscribers: number;
  subscribersThisMonth: number;  // New subscribers in current month
}

export interface DashboardCharts {
  subscriberTrend: TrendData[];      // Area chart: subscribers over time
  statusDistribution: PieData[];     // Pie chart: active vs inactive
  monthlyGrowth: BarData[];          // Bar chart: monthly subscriber growth
  topCompanies: BarData[];           // Bar chart: top 5 companies by subscriber count
}

export interface TrendData {
  date: string;             // ISO 8601 date (YYYY-MM-DD)
  count: number;            // Subscriber count at date
}

export interface PieData {
  name: string;             // Category name (e.g., 'Active', 'Inactive')
  value: number;            // Count
  color: string;            // Hex color (e.g., '#10b981' for green)
}

export interface BarData {
  label: string;            // X-axis label (e.g., 'January', 'Company A')
  value: number;            // Y-axis value
}
```

#### Zod Schema

```typescript
import { z } from 'zod';

export const trendDataSchema = z.object({
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  count: z.number().int().min(0),
});

export const pieDataSchema = z.object({
  name: z.string(),
  value: z.number().int().min(0),
  color: z.string().regex(/^#[0-9a-fA-F]{6}$/),
});

export const barDataSchema = z.object({
  label: z.string(),
  value: z.number().int().min(0),
});

export const dashboardChartsSchema = z.object({
  subscriberTrend: z.array(trendDataSchema),
  statusDistribution: z.array(pieDataSchema),
  monthlyGrowth: z.array(barDataSchema),
  topCompanies: z.array(barDataSchema),
});

export const metricsSummarySchema = z.object({
  totalSubscribers: z.number().int().min(0),
  activeSubscribers: z.number().int().min(0),
  inactiveSubscribers: z.number().int().min(0),
  subscribersThisMonth: z.number().int().min(0),
});

export const dashboardMetricsSchema = z.object({
  summary: metricsSummarySchema,
  charts: dashboardChartsSchema,
  lastUpdated: z.string().datetime(),
});

export type DashboardMetrics = z.infer<typeof dashboardMetricsSchema>;
export type TrendData = z.infer<typeof trendDataSchema>;
export type PieData = z.infer<typeof pieDataSchema>;
export type BarData = z.infer<typeof barDataSchema>;
```

#### Demo Data Generation

**Approach**: Generate fake data client-side using a seeded random number generator for consistent demo experience.

**Data Characteristics**:
- `totalSubscribers`: 450 (realistic mid-size company)
- `activeSubscribers`: 380 (84% active rate)
- `inactiveSubscribers`: 70 (16% inactive rate)
- `subscribersThisMonth`: 35 (8% monthly growth)
- `subscriberTrend`: 12 months of historical data (linear growth with noise)
- `topCompanies`: 5 companies with 15-50 subscribers each

**Notes**:
- Demo data hardcoded in `entities/dashboard-metrics/model/demo-data.ts`
- Future: Replace with API call to backend `/api/admin/metrics` endpoint
- Chart components accept demo data via props (no API integration in Phase 1)

---

## React Query Integration

### Query Keys

```typescript
// entities/subscriber/api/keys.ts
export const subscriberKeys = {
  all: ['subscribers'] as const,
  lists: () => [...subscriberKeys.all, 'list'] as const,
  list: (filters: SubscriberFilters) => [...subscriberKeys.lists(), filters] as const,
  details: () => [...subscriberKeys.all, 'detail'] as const,
  detail: (id: string) => [...subscriberKeys.details(), id] as const,
};

// entities/dashboard-metrics/api/keys.ts
export const dashboardKeys = {
  all: ['dashboard'] as const,
  metrics: () => [...dashboardKeys.all, 'metrics'] as const,
};

// entities/user-session/api/keys.ts (minimal - auth handled by react-oidc-context)
export const authKeys = {
  all: ['auth'] as const,
  session: () => [...authKeys.all, 'session'] as const,
};
```

### React Query Hooks

```typescript
// entities/subscriber/api/useSubscribers.ts
export function useSubscribers(filters: SubscriberFilters) {
  return useQuery({
    queryKey: subscriberKeys.list(filters),
    queryFn: () => fetchSubscribers(filters),
    staleTime: 30_000, // 30 seconds
    gcTime: 5 * 60 * 1000, // 5 minutes (formerly cacheTime)
  });
}

// entities/subscriber/api/useCreateSubscriber.ts
export function useCreateSubscriber() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (data: CreateSubscriberFormData) => createSubscriber(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: subscriberKeys.lists() });
      toast.success('Subscriber created successfully');
    },
    onError: (error: ApiError) => {
      toast.error(error.message || 'Failed to create subscriber');
    },
  });
}

// Similar hooks: useUpdateSubscriber, useDeleteSubscriber
```

---

## API Error Handling

### Error Response Type

```typescript
export interface ApiError {
  timestamp: string;        // ISO 8601 datetime
  status: number;           // HTTP status code (400, 403, 404, 409, 413, 500)
  error: string;            // HTTP status text (e.g., 'Bad Request', 'Conflict')
  message: string;          // Human-readable error message
  path: string;             // API path that failed
  fieldErrors?: Record<string, string>;  // Field-specific validation errors
}
```

### Error Handling Strategy

**Centralized Error Handler** (axios interceptor):

```typescript
// shared/api/client.ts
axios.interceptors.response.use(
  response => response,
  error => {
    const apiError: ApiError = error.response?.data || {
      timestamp: new Date().toISOString(),
      status: error.response?.status || 500,
      error: 'Unknown Error',
      message: error.message || 'An unexpected error occurred',
      path: error.config?.url || '',
    };

    // Handle specific status codes
    if (apiError.status === 401) {
      // Unauthorized - trigger re-authentication
      // react-oidc-context handles this automatically
    } else if (apiError.status === 403) {
      // Forbidden - show error, redirect to dashboard
      toast.error('You do not have permission to perform this action');
    } else if (apiError.status === 409) {
      // Conflict - show field-specific error (e.g., duplicate email)
      toast.error(apiError.message);
    }

    return Promise.reject(apiError);
  }
);
```

---

## State Management Strategy

### Server State (TanStack Query)

- Subscriber list
- Subscriber detail
- Dashboard metrics

**Characteristics**: Fetched from backend API, cached, automatically refetched on stale.

### Client State (React Context + Hooks)

- User session (react-oidc-context manages this)
- UI state (modal open/close, selected rows, etc.)
- Form state (React Hook Form manages this)

**Characteristics**: Transient, not persisted, component-scoped or context-scoped.

### URL State (TanStack Router)

- Pagination (page, pageSize)
- Filters (search, status, sortBy, sortOrder)
- Selected subscriber ID (for edit/delete modals)

**Characteristics**: Shareable via URL, bookmarkable, browser back/forward support.

---

## Summary

All data model definitions are complete with:
- ✅ TypeScript types for type safety
- ✅ Zod schemas for runtime validation
- ✅ Form schemas for client-side validation
- ✅ Validation rules documented
- ✅ State transition diagrams
- ✅ React Query integration patterns
- ✅ Error handling strategy
- ✅ Storage strategy (tokens, session, cache)

**Next Steps**:
- Generate API contracts (OpenAPI spec + TypeScript types)
- Generate quickstart guide
- Update agent context
