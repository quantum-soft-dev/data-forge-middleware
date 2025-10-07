# Technical Specifications: Implementation of the first version of the UI

## 1. Initialization of the UI project

**Goal:** Create a React 19 application with Feature-Sliced Design architecture

**Requirements:**
- Location: `${BASE_PROJECT_DIR}/ui`
- Technology stack: React 19
- Package manager: **npm**
- Architecture: **Feature-Sliced Design (FSD)**

### 1.1 Technology stack:

**Main libraries:**
- * *React 19** - UI framework
- **TypeScript** - typing
- **Vite** - build tool
- **TanStack Router** - routing
- **TanStack Query** - data fetching & caching
- **TanStack Table** - tables
- **TanStack Store** - state management
- **shadcn/ui** - UI components
- **Tailwind CSS** - styling
- **React Hook Form** - forms
- **Zod** - schema validation
- **sonner** - toast notifications

### 1.2 Project structure (FSD):

```
ui/
├── public/
├── src/
│   ├── app/                          # Application initialization
│   │   ├── providers/                # Providers
│   │   │   ├── query-provider.tsx   # TanStack Query Provider
│   │   │   ├── router-provider.tsx  # TanStack Router Provider
│   │   │   ├── auth-provider.tsx    # Auth Provider
│   │   │   └── index.ts
│   │   ├── styles/                   # Global styles
│   │   │   ├── globals.css
│   │   │   └── index.ts
│   │   ├── router/                   # Router configuration
│   │   │   ├── routes.tsx
│   │   │   └── index.ts
│   │   ├── App.tsx
│   │   └── main.tsx
│   │
│   ├── pages/                        # Application pages
│   │   ├── login/
│   │   │   ├── ui/
│   │   │   │   └── login-page.tsx
│   │   │   └── index.ts
│   │   ├── admin/
│   │   │   ├── dashboard/
│   │   │   │   ├── ui/
│   │   │   │   │   └── admin-dashboard-page.tsx
│   │   │   │   └── index.ts
│   │   │   ├── accounts/
│   │   │   │   ├── ui/
│   │   │   │   │   └── admin-accounts-page.tsx
│   │   │   │   └── index.ts
│   │   │   ├── account-details/
│   │   │   │   ├── ui/
│   │   │   │   │   └── account-details-page.tsx
│   │   │   │   └── index.ts
│   │   │   ├── sites/
│   │   │   │   ├── ui/
│   │   │   │   │   └── admin-sites-page.tsx
│   │   │   │   └── index.ts
│   │   │   ├── site-details/
│   │   │   │   ├── ui/
│   │   │   │   └── site-details-page.tsx
│   │   │   │   └── index.ts
│   │   │   ├── batches/
│   │   │   │   ├── ui/
│   │   │   │   │   └── admin-batches-page.tsx
│   │   │   │   └── index.ts
│   │   │   ├── batch-details/
│   │   │   │   ├── ui/
│   │   │   │   │   └── batch-details-page.tsx
│   │   │   │   └── index.ts
│   │   │   ├── errors/
│   │   │   │   ├── ui/
│   │   │   │   │   └── admin-errors-page.tsx
│   │   │   │   └── index.ts
│   │   │   └── index.ts
│   │   ├── user/
│   │   │   ├── dashboard/
│   │   │   │   ├── ui/
│   │   │   │   │   └── user-dashboard-page.tsx
│   │   │   │   └── index.ts
│   │   │   ├── batches/
│   │   │   │   ├── ui/
│   │   │   │   │   └── user-batches-page.tsx
│   │   │   │   └── index.ts
│   │   │   ├── batch-details/
│   │   │   │   ├── ui/
│   │   │   │   │   └── user-batch-detail-page.tsx
│   │   │   │   └── index.ts
│   │   │   └── index.ts
│   │   ├── not-found/
│   │   │   └── ui/
│   │   │       └── not-found-page.tsx
│   │   └── forbidden/
│   │       └── ui/
│   │           └── forbidden-page.tsx
│   │
│   ├── widgets/                      # Composite blocks
│   │   ├── header/
│   │   │   ├── ui/
│   │   │   │   └── header.tsx
│   │   │   └── index.ts
│   │   ├── sidebar/
│   │   │   ├── ui/
│   │   │   │   └── sidebar.tsx
│   │   │   └── index.ts
│   │   ├── admin-accounts-table/
│   │   │   ├── ui/
│   │   │   │   └── admin-accounts-table.tsx
│   │   │   ├── model/
│   │   │   │   └── use-accounts-table.ts
│   │   │   └── index.ts
│   │   ├── admin-sites-table/
│   │   │   ├── ui/
│   │   │   │   └── admin-sites-table.tsx
│   │   │   ├── model/
│   │   │   │   └── use-sites-table.ts
│   │   │   └── index.ts
│   │   ├── admin-batches-table/
│   │   ├── ui/
│   │   │   │   └── admin-batches-table.tsx
│   │   │   ├── model/
│   │   │   │   └── use-batches-table.ts
│   │   │   └── index.ts
│   │   ├── admin-errors-table/
│   │   │   ├── ui/
│   │   │   │   └── admin-errors-table.tsx
│   │   │   ├── model/
│   │   │   │   └── use-errors-table.ts
│   │   │   └── index.ts
│   │   ├── user-batch-list/
│   │   │   ├── ui/
│   │   │   │   └── user-batch-list.tsx
│   │   │   └── index.ts
│   │   ├── statistics-dashboard/
│   │   │   ├── ui/
│   │   │   │   └── statistics-dashboard.tsx
│   │   │   └── index.ts
│   │   └── account-stats-cards/
│   │       ├── ui/
│   │       │   └── account-stats-cards.tsx
│   │       └── index.ts
│   │
│   ├── features/                     # Features (user actions)
│   │   ├── auth/
│   │   │   ├── login/
│   │   │   │   ├── ui/
│   │   │   │   │   └── login-form.tsx
│   │   │   │   ├── model/
│   │   │   │   │   └── use-login.ts
│   │   │   │   └── index.ts
│   │   │   ├── logout/
│   │   │   │   ├── ui/
│   │   │   │   │   └── logout-button.tsx
│   │   │   │   └── index.ts
│   │   │   ├── generate-token/
│   │   │   │   ├── ui/
│   │   │   │   │   └── generate-token-button.tsx
│   │   │   │   ├── model/
│   │   │   │   │   └── use-generate-token.ts
│   │   │   │   └── index.ts
│   │   │   └── index.ts
│   │   ├── account/
│   │   │   ├── create-account/
│   │   │   │   ├── ui/
│   │   │   │   │   └── create-account-form.tsx
│   │   │   │   ├── model/
│   │   │   │   │   ├── use-create-account.ts
│   │   │   │   │   └── schema.ts
│   │   │   │   └── index.ts
│   │   │   ├── update-account/
│   │   │   │   ├── ui/
│   │   │   │   │   └── update-account-form.tsx
│   │   │   │   ├── model/
│   │   │   │   │   ├── use-update-account.ts
│   │   │   │   │   └── schema.ts
│   │   │   │   └── index.ts
│   │   │   ├── deactivate-account/
│   │   │   │   ├── ui/
│   │   │   │   │   └── deactivate-account-button.tsx
│   │   │   │   ├── model/
│   │   │   │   │   └── use-deactivate-account.ts
│   │   │   │   └── index.ts
│   │   │   └── index.ts
│   │   ├── site/
│   │   │   ├── create-site/
│   │   │   │   ├── ui/
│   │   │   │   │   └── create-site-form.tsx
│   │   │   │   ├── model/
│   │   │   │   │   ├── use-create-site.ts
│   │   │   │   │   └ ── schema.ts
│   │   │   │   └── index.ts
│   │   │   ├── update-site/
│   │   │   │   ├── ui/
│   │   │   │   │   └── update-site-form.tsx
│   │   │   │   ├── model/
│   │   │   │   │   ├── use-update-site.ts
│   │   │   │   │   └── schema.ts
│   │   │   │   └── index.ts
│   │   │   ├── deactivate-site/
│   │   │   │   ├── ui/
│   │   │   │   │   └── deactivate-site-button.tsx
│   │   │   │   ├── model/
│   │   │   │   │   └── use-deactivate-site.ts
│   │   │   │   └── index.ts
│   │   │   └── index.ts
│   │   ├── batch/
│   │   │   ├── start-batch/
│   │   │   │   ├── ui/
│   │   │   │   │   └── start-batch-button.tsx
│   │   │   │   ├── model/
│   │   │   │   │   └── use-start-batch.ts
│   │   │   │   └── index.ts
│   │   │   ├── upload-files/
│   │   │   │   ├── ui/
│   │   │   │   │   └ ── file-upload-form.tsx
│   │   │   │   ├── model/
│   │   │   │   │   └── use-upload-files.ts
│   │   │   │   └── index.ts
│   │   │   ├── complete-batch/
│   │   │   │   ├── ui/
│   │   │   │   │   └── complete-batch-button.tsx
│   │   │   │   ├── model/
│   │   │   │   │   └── use-complete-batch.ts
│   │   │   │   └── index.ts
│   │   │   ├── fail-batch/
│   │   │   │   ├── ui/
│   │   │   │   │   └── fail-batch-button.tsx
│   │   │   │   ├── model/
│   │   │   │   │   └── use-fail-batch.ts
│   │   │   │   └── index.ts
│   │   │   ├── cancel-batch/
│   │   │   │   ├── ui/
│   │   │   │   │   └── cancel-batch-button.tsx
│   │   │   │   ├── model/
│   │   │   │   │   └── use-cancel-batch.ts
│   │   │   │   └── index.ts
│   │   │   ├── delete-batch/
│   │   │   │   ├── ui/
│   │   │   │   │   └── delete-batch-button.tsx
│   │   │   │   ├── model/
│   │   │   │   │   └── use-delete-batch.ts
│   │   │   │   └── index.ts
│   │   │   └── index.ts
│   │   ├── error/
│   │   │   ├── log-error/
│   │   │   │   ├── ui/
│   │   │   │   │   └── log-error-form.tsx
│   │   │   │   ├ ── model/
│   │   │   │   │   ├── use-log-error.ts
│   │   │   │   │   └── schema.ts
│   │   │   │   └── index.ts
│   │   │   ├── export-errors/
│   │   │   │   ├── ui/
│   │   │   │   │   └── export-errors-button.tsx
│   │   │   │   ├── model/
│   │   │   │   │   └── use-export-errors.ts
│   │   │   │   └── index.ts
│   │   │   └── index.ts
│   │   └── index.ts
│   │
├── entities/                     # Business entities
├── user/
├── model/
│   │   │   │   ├── types.ts
│   │   │   │   └── store.ts         # TanStack Store
│   │   │   ├── api/
│   │   │   │   ├── user-api.ts
│   │   │   │   ├── queries.ts       # TanStack Query hooks
│   │   │   │   └── index.ts
│   │   │   ├── ui/
│   │   │   │   ├── user-card.tsx
│   │   │   │   └── user-avatar.tsx
│   │   │   └── index.ts
│   │   ├── account/
│   │   │   ├── model/
│   │   │   │   └── types.ts         # AccountDTO
│   │   │   ├── api/
│   │   │   │   ├── account-api.ts
│   │   │   │   ├── queries.ts       # useAccounts, useAccount, useAccountStats
│   │   │   │   ├── mutations.ts     # useCreateAccount, useUpdateAccount
│   │   │   │   └── index.ts
│   │   │   ├── ui/
│   │   │   │   ├── account-card.tsx
│   │   │   │   └── account-status-badge.tsx
│   │   │   └── index.ts
│   │   ├── site/
│   │   │   ├── model/
│   │   │   │   └── types.ts         # SiteDTO
│   │   │   ├── api/
│   │   │   │   ├── site-api.ts
│   │   │   │   ├── queries.ts       # useSites, useSite, useSiteStatistics
│   │   │   │   ├── mutations.ts     # useCreateSite, useUpdateSite
│   │   │   │   └── index.ts
│   │   │   ├── ui/
│   │   │   │   ├── site-card.tsx
│   │   │   │   └── site-status-badge.tsx
│   │   │   └── index.ts
│   │   ├── batch/
│   │   │   ├── model/
│   │   │   │   └── types.ts         # BatchDTO, BatchStatus
│   │   │   ├── api/
│   │   │   │   ├── batch-api.ts
│   │   │   │   ├── queries.ts       # useBatches, useBatch, useBatchDetails
│   │   │   │   ├── mutations.ts     # useStartBatch, useCompleteBatch
│   │   │   │   └── index.ts
│   │   │   ├── ui/
│   │   │   │   ├── batch-card.tsx
│   │   │   │   ├── batch-status.tsx
│   │   │   │   └── batch-file-list.tsx
│   │   │   └── index.ts
│   │   ├── file/
│   │   │   ├── model/
│   │   │   │   └── types.ts         # FileDTO
│   │   │   ├── api/
│   │   │   │   ├── file-api.ts
│   │   │   │   ├── queries.ts       # useFile
│   │   │   │   ├── mutations.ts     # useUploadFile
│   │   │   │   └── index.ts
│   │   │   ├── ui/
│   │   │   │   └── file-card.tsx
│   │   │   └── index.ts
│   │   ├── error/
│   │   │   ├── model/
│   │   │   │   └── types.ts         # ErrorLogDTO
│   │   │   ├── api/
│   │   │   │   ├── error-api.ts
│   │   │   │   ├── queries.ts       # useErrors, useErrorLog
│   │   │   │   ├── mutations.ts     # useLogError
│   │   │   │   └── index.ts
│   │   │   ├── ui/
│   │   │   │   ├─ ─ error-card.tsx
│   │   │   │   └── error-type-badge.tsx
│   │   │   └── index.ts
│   │   └── statistics/
│   │       ├── model/
│   │       │   └── types.ts         # StatisticsDTO
│   │       ├── api/
│   │       │   ├── statistics-api.ts
│   │       │   ├── queries.ts       # useGlobalStatistics
│   │       │   └── index.ts
│   │       ├── ui/
│   │       │   └── stat-card.tsx
│   │       └── index.ts
│   │
│   ├── shared/                       # Reusable modules
│   │   ├── api/                      # API configuration
│   │   │   ├── client.ts            # Fetch wrapper
│   │   │   ├── config.ts            # API config
│   │   │   ├── types.ts             # PaginationParams, PageResponse
│   │   │   └── index.ts
│   │   ├── lib/                      # Libraries and utilities
│   │   │   ├── auth/                # Auth library
│   │   │   │   ├── auth-context.tsx
│   │   │   │   ├── auth-provider.tsx
│   │   │   │   ├── use-auth.ts
│   │   │   │   ├── auth-service.ts
│   │   │   │   ├── token-storage.ts
│   │   │   │   ├── types.ts
│   │   │   │   └── index.ts
│   │   │   ├── hooks/               # Common hooks
│   │   │   │   ├── use-debounce.ts
│   │   │   │   ├── use-pagination.ts
│   │   │   │   └── index.ts
│   │   │   ├── router/              # Router utilities
│   │   │   │   ├── protected-route.tsx
│   │   │   │   └── index.ts
│   │   │   └── utils/               # Utilities
│   │   │       ├── format-date.ts
│   │   │       ├── format-file-size.ts
│   │   │       ├── cn.ts            # classnames helper
│   │   │       └── index.ts
│   │   ├── ui/                       # shadcn/ui components
│   │   │   ├── button.tsx
│   │   │   ├── input.tsx
│   │   │   ├── select.tsx
│   │   │   ├── table.tsx
│   │   │   ├── dialog.tsx
│   │   │   ├── card.tsx
│   │   │   ├── spinner.tsx
│   │   │   ├── badge.tsx
│   │   │   ├── form.tsx
│   │   │   ├── toast.tsx
│   │   │   ├── toaster.tsx
│   │   │   └── index.ts
│   │   ├── config/                   # Configuration
│   │   │   ├── routes.ts
│   │   │   └── env.ts
│   │   └── types/                    # Global types
│   │       └── index.ts
│   │
│   ├── components.json               # shadcn/ui config
│   ├── tailwind.config.ts
│   └── main.tsx
│
├── .env.example
├── .env.development
├── .env.production
├── package.json
├── tsconfig.json
├── vite.config.ts
├── postcss.config.js
└── README.md
```

### 1.3 Key principles of FSD:

1. **Layers (bottom-up):**
    - `shared` → `entities` → `features` → `widgets` → `pages` → `app`
    - The bottom layer is unaware of the top layer
    - Imports only down the hierarchy

2. **Public API (index.ts):**
    - Each module exports only what is necessary via `index.ts`
    - Direct imports from internal folders are prohibited

3. **Segments in slices:**
- `ui/` - components
- `model/` - business logic, types, store, schemas
- `api/` - API client, queries, mutations

4. **TanStack Query organization:**
- `queries.ts` - query hooks (useQuery, useSuspenseQuery)
- `mutations.ts` - mutation hooks (useMutation)
- `{entity}-api.ts` - low-level API functions

---

## 2. Authentication via Keycloak

**Goal:** Integration with Keycloak via Spring Security

**Location in FSD:** `shared/lib/auth/`

### 2.1 Backend (Spring Boot) - Configuration

**Requirements:**
- Use Spring Security + OAuth2 Client
- Configure integration with Keycloak via `application.yml`/`application.properties`
- Configuration must include:
- Keycloak realm
- Client ID and Client Secret

- Redirect URIs
    - Logout URL
- CORS settings for UI

**Security Schemes from OpenAPI:**
- `basicAuth`: Basic Auth with domain:clientSecret credentials
- `bearerAuth`: JWT Bearer token for client API endpoints
- `oauth2`: OAuth2 with Keycloak for admin endpoints

**Security Configuration:**
- Configure `SecurityFilterChain` to protect endpoints
- Configure role mapping (ADMIN, USER) from Keycloak roles
- Implement CORS filter for working with UI
- Public endpoints: `/api/v1/auth/token` (with optional authorization)

---

### 2.2 Frontend (UI) - Custom library

**Goal:** Create a custom auth service for React 19 (without keycloak-js)

#### Library structure:
```
shared/lib/auth/
├── auth-context.tsx      # React Context for state
├── auth-provider.tsx     # Provider component
├── use-auth.ts          # Custom hook
├── auth-service.ts      # API calls to Spring backend (fetch)
├── token-storage.ts     # Working with localStorage/sessionStorage
├── types.ts             # TypeScript types
└── index.ts             # Public API
```

#### Functionality:

1. **AuthService (auth-service.ts):**
```typescript
export const authService = {
  login: () => void;                    // Redirect to Keycloak
  handleCallback: () => Promise<void>;  // Handle callback
  logout: () => Promise<void>;          // Logout
     refreshToken: () => Promise<string>;  // Refresh token
     getUserInfo: () => Promise<User>;     // User information
     isAuthenticated: () => boolean;       // Authorization check
     generateToken: (credentials?: BasicAuth) => Promise<string>; // Token generation   };
   ```

2. **Token Management (token-storage.ts):**
   ```typescript
   export const tokenStorage = {
     getToken: () => string | null;
     setToken: (token: string) => void;
     removeToken: () => void;     isTokenExpired: () => boolean;
   };
   ```

3. **AuthContext & Provider (auth-provider.tsx):**
   ```typescript
   interface AuthState {
     isAuthenticated: boolean;
     user: User | null;     token: string | null;
     loading: boolean;
     error: string | null;
   }

   interface AuthContextValue extends AuthState {
     login: () => void;
     logout: () => Promise<void>;
     generateToken: (credentials?: BasicAuth) => Promise<void>;
   }   ```

4. **Custom Hook (use-auth.ts):**
```typescript
export const useAuth = () => {
const context = useContext(AuthContext);
if (!context) {
throw new Error(‘useAuth must be used within AuthProvider’);
}
return context;
};
```

5. **Protected Routes (shared/lib/router/protected-route.tsx):**
```typescript
import { useAuth } from ‘@/shared/lib/auth’;
import { Navigate } from ‘@tanstack/react-router’;   export const ProtectedRoute = ({ 
     children, 
     requiredRole 
   }: ProtectedRouteProps) => {
     const { isAuthenticated, user, loading } = useAuth();
     
     if (loading) {
       return <Spinner />;
     }
     
     if (!isAuthenticated) {       return <Navigate to="/login" />;
     }
     
     if (requiredRole && user?.role !== requiredRole) {
       return <Navigate to="/403" />;
     }
     
     return children;
   };   ```

#### Public API (shared/lib/auth/index.ts):
```typescript
export { AuthProvider } from ‘./auth-provider’;
export { useAuth } from ‘./use-auth’;
export { authService } from ‘./auth-service’;
export { tokenStorage } from ‘./token-storage’;
export type { AuthState, User, BasicAuth } from ‘./types’;
```

---

### 2.3 API Client with Bearer Token

**Location:** `shared/api/client.ts`

```typescript
import { tokenStorage } from ‘@/shared/lib/auth’;
import { API_BASE_URL } from ‘./config’;

interface FetchOptions extends RequestInit {
  token?: string;
}

export async function apiFetch<T>(
  endpoint: string,
  options: FetchOptions = {}
): Promise<T> {
  const { token, headers, ...restOptions } = options;
  
  const authToken = token || tokenStorage.getToken();
  
  const response = await fetch(`${API_BASE_URL}${endpoint}`, {
    ...restOptions,
    headers: {
      ‘Content-Type’: ‘application/json’,
      . ..(authToken && { Authorization: `Bearer ${authToken}` }),
      . ..headers,
    },
  });

  if (response.status === 401) {
    tokenStorage.removeToken();
    window.location.href = ‘/login’;
    throw new Error(‘Unauthorized’);
  }

  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error (error.message || ‘Request failed’);
  }

  return response.json();
}

// For multipart/form-data (file upload)
export async function apiUpload<T>(
  endpoint: string,
  formData: FormData
): Promise<T> {
  const token = tokenStorage.getToken();    const response = await fetch(`${API_BASE_URL}${endpoint}`, {
    method: ‘POST’,
    headers: {
      ... (token && { Authorization: `Bearer ${token}` }),
      // Do NOT specify Content-Type - the browser will set it itself with boundary
    },
    body: formData,
  });

  if (response.status === 401) {
    tokenStorage.removeToken();    window.location.href = ‘/login’;
    throw new Error(‘Unauthorized’);
  }

  if (!response.ok) {
    throw new Error(‘Upload failed’);
  }

  return response.json();
}
```

---

### 2.4 Backend ↔ Frontend Integration

**API endpoints (from OpenAPI):**
```
POST /api/v1/auth/token         # Token generation (optional authorization)
GET  /oauth2/authorization/keycloak  # Start OAuth flow (assumed)
```

**Authorization flow:**
1. User clicks “Login” → Redirect to `/oauth2/authorization/keycloak`
2. Keycloak processes authorization
3. Redirect back to the application with authorization code
4. Spring automatically exchanges the code for tokens
5. Frontend receives JWT token and saves it in tokenStorage
6. User is authorized ✅

---

## 3. Admin panel (role: ADMIN)

**Goal:** Create a management interface for administrators

### 3.1 Backend API refactoring

**CRITICAL to complete before starting UI development:**

Current API status (from OpenAPI):
- ❌ All endpoints return `Map<String, Object>`
- ❌ No typed DTOs
- ❌ Swagger does not contain the response structure

**Required changes:**

1. **Create DTO classes:**
```java
// Account DTOs
AccountDTO
CreateAccountRequest
UpdateAccountRequest
AccountListResponse extends PageResponse<AccountDTO>
AccountStatsDTO
AccountStatisticsDTO

// Site DTOs
SiteDTO
CreateSiteRequest
UpdateSiteRequest
SiteListResponse
SiteStatisticsDTO

// Batch DTOs
BatchDTO
BatchDetailsDTO
BatchListResponse extends PageResponse<BatchDTO>
BatchStatus (enum: PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED)

// File DTOs
FileDTO
UploadFileResponse

/ / Error DTOs
ErrorLogDTO
ErrorListResponse extends PageResponse<ErrorLogDTO>

// Statistics DTOs
GlobalStatisticsDTO

// Common DTOs
PageResponse<T> {
  List<T> content;
  int page;
  int size;
  long totalElements;
  int totalPages;
  boolean first;
  boolean last;
}
```

2. **Update all controllers**
3. **Update OpenAPI/Swagger schemas**
4. **Update tests - MANDATORY with every API change**

---

### 3.2 Using TanStack Query in Admin

#### Example: Accounts Entity

**entities/account/api/account-api.ts:**
```typescript
import { apiFetch } from ‘@/shared/api/client’;
import type { 
  AccountDTO, 
  CreateAccountRequest, 
  UpdateAccountRequest,
  PageResponse,
  PaginationParams 
} from ‘../model/types’;

export const accountApi = {  getAccounts: async (params: PaginationParams): Promise<PageResponse<AccountDTO>> => {
    const searchParams = new URLSearchParams({
      page: String(params.page || 0),
      size: String (params.size || 20),
      sort: params.sort || ‘createdAt,desc’,
    });
    return apiFetch<PageResponse<AccountDTO>>(`/admin/accounts?${searchParams}`);
  },

  getAccount: async (id: string): Promise<AccountDTO> => {
    return apiFetch<AccountDTO>(`/admin/accounts/${id}`);
  },

  createAccount: async (data: CreateAccountRequest): Promise<AccountDTO> => {
    return apiFetch<AccountDTO>(‘/admin/accounts’, {
      method: ‘POST’,      body: JSON.stringify(data),
    });
  },

  updateAccount: async (id: string, data: UpdateAccountRequest): Promise<AccountDTO> => {
    return apiFetch<AccountDTO>(`/admin/accounts/${id}`, {      method: ‘PUT’,
      body: JSON.stringify(data),
    });
  },

  deactivateAccount: async (id: string): Promise<void> => {
    return apiFetch<void>(`/admin/accounts/${id}`, {
      method: ‘DELETE’,
    });
  },

  getAccountStats: async (id: string): Promise<AccountStatsDTO> => {
    return apiFetch<AccountStatsDTO>(`/admin/accounts/${id}/stats`);  },

  getAccountStatistics: async (id: string): Promise<AccountStatisticsDTO> => {
    return apiFetch<AccountStatisticsDTO>(`/admin/accounts/${id}/statistics`);
  },
};
```

**entities/account/api/queries.ts:**
```typescript
import { 
  useQuery, 
  useSuspenseQuery,
  queryOptions 
} from ‘@tanstack/react-query’;
import { accountApi } from ‘./account-api’;
import type { PaginationParams } from ‘../model/types’;

// Query keys
export const accountKeys = {
  all: [‘accounts’] as const,
  lists: () => [...accountKeys.all, ‘list’] as const,
  list: (params: PaginationParams) => [...accountKeys.lists(), params] as const,
  details: () => [...accountKeys.all, ‘detail’] as const,  detail: (id: string) => [...accountKeys.details(), id] as const,
  stats: (id: string) => [...accountKeys.detail(id), ‘stats’] as const,
  statistics: (id: string) => [...accountKeys.detail(id), ‘statistics’] as const,
};

// Query options (for prefetching and SSR)
export const accountQueryOptions = {
  list: (params: PaginationParams) => 
    queryOptions({
      queryKey: accountKeys.list(params),
      queryFn: () => accountApi.getAccounts(params),
      staleTime: 30000, // 30 seconds
    }),
    
  detail: (id: string) =>
    queryOptions({
      queryKey: accountKeys.detail(id),
      queryFn: () => accountApi.getAccount(id),
      staleTime: 60000, // 1 minute    }),
    
  stats: (id: string) =>
    queryOptions({
      queryKey: accountKeys.stats(id),
      queryFn: () => accountApi.getAccountStats(id),
      staleTime: 60000,
    }),
};

// Hooks
export const useAccounts = (params: PaginationParams) => {
  return useQuery(accountQueryOptions.list(params));
};

export const useAccount = (id: string) => {
  return useQuery(accountQueryOptions.detail(id));
};

export const useAccountSuspense = (id: string) => {
  return useSuspenseQuery(accountQueryOptions.detail(id));
};

export const useAccountStats = (id: string) => {
  return useQuery(accountQueryOptions.stats (id));
};
```

**entities/account/api/mutations.ts:**
```typescript
import { useMutation, useQueryClient } from ‘@tanstack/react-query’;
import { accountApi } from ‘./account-api’;
import { accountKeys } from ‘./queries’;
import { toast } from ‘sonner’;

export const useCreateAccount = () => {
  const queryClient = useQueryClient();    return useMutation({
    mutationFn: accountApi.createAccount,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: accountKeys.lists() });
      toast.success(‘Account created successfully’);
    },
    onError: (error: Error) => {
      toast.error (error.message || ‘Failed to create account’);    },
  });
};

export const useUpdateAccount = () => {
  const queryClient = useQueryClient();
  
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateAccountRequest }) => 
      accountApi.updateAccount(id, data),
    onSuccess: (_, variables) => {      queryClient.invalidateQueries({ queryKey: accountKeys.detail(variables.id) });
      queryClient.invalidateQueries({ queryKey: accountKeys.lists() });
      toast.success(‘Account updated successfully’);
    },
    onError: (error: Error) => {
      toast.error(error.message || ‘Failed to update account’);    },
  });
};

export const useDeactivateAccount = () => {
  const queryClient = useQueryClient();
  
  return useMutation({
    mutationFn: accountApi.deactivateAccount,
    onSuccess: (_, id) => {
      queryClient.invalidateQueries({ queryKey: accountKeys.detail(id) });
      queryClient.invalidateQueries({ queryKey: accountKeys.lists() });
      toast.success(‘Account deactivated successfully’);
    },
    onError: (error: Error) => {
      toast.error(error.message || ‘Failed to deactivate account’);
    },
  });
};
```

---

### 3.3 Using TanStack Table

**widgets/admin-accounts-table/model/use-accounts-table.ts:**
```typescript
import { useMemo } from ‘react’;
import {
  useReactTable,
  getCoreRowModel,
  getPaginationRowModel,
  getSortedRowModel,
  getFilteredRowModel,
  type ColumnDef,
  type SortingState,
  type ColumnFiltersState,
} from ‘@tanstack/react-table’;
import { useAccounts } from ‘@/entities/account’ ;
import type { AccountDTO } from ‘@/entities/account/model/types’;

export const useAccountsTable = () => {
  const [sorting, setSorting] = useState<SortingState>( []);
  const [columnFilters, setColumnFilters] = useState<ColumnFiltersState>([]);
  const [pagination, setPagination] = useState({
    pageIndex: 0,
    pageSize: 20,
  });

  const { data, isLoading } = useAccounts({
    page: pagination.pageIndex,    size: pagination.pageSize,
    sort: sorting[0] 
      ? `${sorting[0].id},${sorting[0].desc ? ‘desc’ : ‘asc’}`
      : ‘createdAt,desc’,
  });  const columns = useMemo<ColumnDef<AccountDTO>[]>(
    () => [
      {
        accessorKey: ‘name’,
        header: ‘Name’,
      },
      {
        accessorKey: ‘email’,
        header: ‘Email’,
      },      {
        accessorKey: ‘status’,
        header: ‘Status’,
        cell: ({ row }) => <AccountStatusBadge status={row.original.status} />,
      },
      {        accessorKey: ‘createdAt’,
        header: ‘Created At’,
        cell: ({ row }) => formatDate(row.original.createdAt),
      },
      {
        id: ‘actions’,
        cell: ({ row }) => <AccountActions account={row.original} />,
      },
    ],
    []
  );  const table = useReactTable({
    data: data?.content ?? [],
    columns,
    pageCount: data?.totalPages ?? 0,
    state: {
      sorting,
      columnFilters,
      pagination,
    },
    onSortingChange: setSorting,
    onColumnFiltersChange: setColumnFilters,
    onPaginationChange: setPagination,
    getCoreRowModel: getCoreRowModel(),    getSortedRowModel: getSortedRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
    getPaginationRowModel: getPaginationRowModel(),
    manualPagination: true,
  });

  return {
    table,
    isLoading,
  };
};
```

**widgets/admin-accounts-table/ui/admin-accounts-table.tsx:**
```typescript
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from ‘@/shared/ui/table’;
import { Button } from ‘@/shared/ui/button’;
import { Spinner } from ‘@/shared/ui/spinner’;
import { useAccountsTable } from ‘../model/use-accounts-table’;
import { flexRender } from ‘@tanstack/react-table’;

export const AdminAccountsTable = () => {
  const { table, isLoading } = useAccountsTable();  if (isLoading) {
    return <Spinner />;
  }

  return (
    <div className="space-y-4">
      <Table>
        <TableHeader>
          {table.getHeaderGroups().map((headerGroup) => (
            <TableRow key={headerGroup.id}>
              {headerGroup.headers.map((header) => (                <TableHead key={header.id}>
                  {header.isPlaceholder
                    ? null
                    : flexRender(
                        header.column.columnDef.header,
                        header.getContext()
                      )}
                </TableHead>
              ))}
            </TableRow>
          ))}
        </TableHeader>
        <TableBody>          {table.getRowModel().rows?.length ? (
            table.getRowModel().rows.map((row) => (
              <TableRow key={row.id}>
                {row.getVisibleCells().map((cell) => (
                  <TableCell key={cell.id}>
                    {flexRender(
                      cell.column.columnDef.cell,
                      cell.getContext()                    )}
                  </TableCell>
                ))}
              </TableRow>
            ))
          ) : (
            <TableRow>
              <TableCell
                colSpan={columns.length}
                className="h-24 text-center"
              >
                No results.
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>      {/* Pagination */}
      <div className="flex items-center justify-end space-x-2">
        <Button
          variant="outline"
          size="sm"
          onClick={() => table.previousPage()}
          disabled={!table.getCanPreviousPage()}
        >
          Previous
        </Button>
        <Button          variant=“outline”
          size="sm"
          onClick={() => table.nextPage()}
          disabled={!table.getCanNextPage()}
        >
          Next
        </Button>
      </div>
    </div>
  );
};
```

---

### 3.4 Using React Hook Form + Zod

**features/account/create-account/model/schema.ts:**
```typescript
import { z } from ‘zod’;

export const createAccountSchema = z.object({
  name: z.string().min(2, ‘Name must be at least 2 characters’),  email: z.string().email(‘Invalid email address’),
  domain: z.string().min(1, ‘Domain is required’),
  description: z.string().optional(),
});

export type CreateAccountFormData = z.infer<typeof createAccountSchema>;
```

**features/account/create-account/ui/create-account-form.tsx:**
```typescript
import { useForm } from ‘react-hook-form’;
import { zodResolver } from ‘@hookform/resolvers/zod’;
import { useCreateAccount } from ‘@/entities/account’;
import { createAccountSchema, type CreateAccountFormData } from ‘../model/schema’;
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from ‘@/shared/ui/form’;
import { Input } from ‘@/shared/ui/input’;
import { Button } from ‘@/shared/ui/button’;
import { Textarea } from ‘@/shared/ui/textarea’;

export const CreateAccountForm = ({ onSuccess }: { onSuccess?: () => void }) => {
  const { mutate, isPending } = useCreateAccount();
  
  const form = useForm<CreateAccountFormData> ({
    resolver: zodResolver(createAccountSchema),
    defaultValues: {
      name: ‘’,
      email: ‘’,
      domain: ‘’,
      description: ‘’,
    },
  });

  const onSubmit = (data: CreateAccountFormData) => {    mutate(data, {
      onSuccess: () => {
        form.reset();
        onSuccess?.();
      },
    });
  };  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
        <FormField
          control={form.control}
          name="name"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Name</FormLabel>
              <FormControl>                <Input placeholder="Account name" {...field} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        <FormField
          control={form.control}
          name="email"
          render={({ field }) => (            <FormItem>
              <FormLabel>Email</FormLabel>
              <FormControl>
                <Input type="email" placeholder="email@example.com" {...field} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />        <FormField
          control={form.control}
          name="domain"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Domain</FormLabel>
              <FormControl>
                <Input placeholder="example.com" {...field} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />        <FormField
          control={form.control}
          name="description"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Description</FormLabel>
              <FormControl>
                <Textarea placeholder="Optional description" {...field} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />        <Button type="submit" disabled={isPending}>
          {isPending ? ‘Creating...’ : ‘Create Account’}
        </Button>
      </form>
    </Form>
  );
};
```

---

## # 3.5 Admin panel functionality (summary)

#### Modules:
1. **Global Dashboard** - `/admin/dashboard`
    - API: `GET /admin/accounts/../statistics`
    - Widget: `statistics-dashboard`

2. **Account Management** - `/admin/accounts`
    - List: `GET /admin/accounts`
    - Create: `POST /admin/accounts`    - Details: `GET /admin/accounts/{id}`
- Edit: `PUT /admin/accounts/{id}`
- Deactivate: `DELETE /admin/accounts/{id}`
- Statistics: `GET /admin/accounts/{id}/stats`, `/statistics`

3. **Site management** - `/admin/sites`
- List: `GET /admin/accounts/{accountId}/sites`
- Create: `POST /admin/accounts/{accountId}/sites`
- Details: `GET /admin/sites/{id}`    - Edit: `PUT /admin/sites/{id}`
- Deactivate: `DELETE /admin/sites/{id}`
- Statistics: `GET /admin/sites/{id}/statistics`

4. **Batch management** - `/admin/batches`
- List: `GET /admin/batches`
- Details: `GET /admin/batches/{id}`    - Deletion: `DELETE /admin/batches/{id}`

5. **Viewing errors** - `/admin/errors`
- List: `GET /admin/errors`
- Export: `GET /admin/errors/export`

---

## 4. User interface (role: USER)

### 4.1 User Dashboard

**API:** Need to add:
```
GET /api/v1/user/dashboard
```

**Alternative:** Use `/api/v1/batch/{id}` for the latest batch

**entities/batch/api/queries.ts:**
```typescript
export const useUserDashboard = () => {
  return useQuery({
    queryKey: [‘user’, ‘dashboard’],    queryFn: async () => {
      // Get the user's latest batch
      const batches = await batchApi.getBatches({ page: 0, size: 1 });      const lastBatch = batches.content[0];
      
      return {
        lastBatch,
        totalBatches: batches.totalElements,
        recentBatches: batches.content,
      };
    },
    staleTime: 30000,
  });
};
```

-- -

### 4.2 File upload workflow

**1. Start batch:**
```typescript
// features/batch/start-batch/model/use-start-batch.ts
export const useStartBatch = () => {
  const queryClient = useQueryClient();    return useMutation({
    mutationFn: batchApi.startBatch,
    onSuccess: (newBatch) => {
      queryClient.invalidateQueries({ queryKey: batchKeys.lists() });
      toast.success (‘Batch started’);
      return newBatch;
    },
  });
};
```

**2. Uploading files:**
```typescript
// features/batch/upload-files/model/use-upload-files.ts
import { apiUpload } from ‘@/shared/api/client’;

export const useUploadFiles = (batchId: string) => {
  const queryClient = useQueryClient();
  
  return useMutation({
    mutationFn: async (files: File[]) => {
      const formData = new FormData();      files.forEach(file => formData.append(‘files’, file));
      
      return apiUpload(`/api/v1/batch/${batchId}/upload`, formData);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: batchKeys.detail(batchId) });
      toast.success(‘Files uploaded’);
    },
    onError: (error: Error) => {
      toast.error(error.message || ‘Upload failed’);
    },
  });
};
```

**3. UI for uploading files:**
```typescript
// features/batch/upload-files/ui/file-upload-form.tsx
import { useDropzone } from ‘react-dropzone’;
import { useUploadFiles } from ‘../model/use-upload-files’;

export const FileUploadForm = ({ batchId }: { batchId: string }) => {  const { mutate, isPending } = useUploadFiles(batchId);
  const [selectedFiles, setSelectedFiles] = useState<File[]>([]);

  const { getRootProps, getInputProps, isDragActive } = useDropzone({    onDrop: (acceptedFiles) => {
      setSelectedFiles(prev => [...prev, ...acceptedFiles]);
    },
    multiple: true,
  });

  const handleUpload = () => {
    if (selectedFiles.length > 0) {
      mutate(selectedFiles);
    }
  };  return (
    <div className="space-y-4">
      <div
        {...getRootProps()}
        className={cn(
          “border-2 border-dashed rounded-lg p-8 text-center cursor-pointer”,
          isDragActive && “border-primary bg-primary/5”
        )}      >
        <input {...getInputProps()} />
        <p>
          {isDragActive
            ? “Drop files here...”
            : “Drag & drop files here, or click to select”}
        </p>
      </div>

      {selectedFiles.length > 0 && (        <div className="space-y-2">
          <h3 className="font-semibold">Selected Files:</h3>
          <ul className="space-y-1">
            {selectedFiles.map((file, index) => (
              <li key={index} className="text-sm">
                {file.name} ({formatFileSize(file.size)})              </li>
            ))}
          </ul>
        </div>
      )}

      <Button onClick={handleUpload} disabled={isPending || selectedFiles.length === 0}>
        {isPending ? ‘Uploading...’ : ‘Upload Files’}
      </Button>
    </div>
  );
};
```

---

## 5. TanStack Router Setup

### 5.1 Router configuration

**app/router/routes.tsx:**
```typescript
import { createRouter, createRoute, createRootRoute } from ‘@tanstack/react-router’;
import { QueryClient } from ‘@tanstack/react-query’;
import { ProtectedRoute } from ‘@/shared/lib/router’;

// Root route
const rootRoute = createRootRoute({
  component: () => <RootLayout />,
});

// Auth routes
const loginRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: ‘/login’,  component: () => <LoginPage />,
});

// Admin routes
const adminRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: ‘/admin’,
  component: () => (
    <ProtectedRoute requiredRole="ADMIN">
      <AdminLayout />
    </ProtectedRoute>
  ),
});

const adminDashboardRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: ‘/dashboard’,
  component: () => <AdminDashboardPage />,
  loader: ({ context: { queryClient } }) => {
    return queryClient.ensureQueryData(
      statisticsQueryOptions.global()
    );
  },
});

const adminAccountsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: ‘/accounts’,
  component: () => <AdminAccountsPage />,
  loader: ({ context: { queryClient } }) => {
    return queryClient.ensureQueryData(
      accountQueryOptions.list({ page: 0, size: 20 })    );
  },
});

const adminAccountDetailRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: ‘/accounts/$accountId’,
  component: () => <AccountDetailsPage />,  loader: ({ context: { queryClient }, params }) => {
    return queryClient.ensureQueryData(
      accountQueryOptions.detail(params.accountId)
    );
  },
});

// User routes
const userRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: ‘/user’,
  component: () => (
    <ProtectedRoute requiredRole="USER">      <UserLayout />
    </ProtectedRoute>
  ),
});

const userDashboardRoute = createRoute({
  getParentRoute: () => userRoute,
  path: ‘/dashboard’,
  component: () => <UserDashboardPage />,
});

const userBatchesRoute = createRoute({
  getParentRoute: () => userRoute,
  path: ‘/batches’,
  component: () => <UserBatchesPage />,
});

// Route tree
const routeTree = rootRoute.addChildren([
  loginRoute,
  adminRoute.addChildren([
    adminDashboardRoute,
    adminAccountsRoute,
    adminAccountDetailRoute,
    // ... other admin routes
  ]),
  userRoute.addChildren([    userDashboardRoute,
    userBatchesRoute,
    // ... other user routes
  ]),
]);

// Create router
export const router = createRouter({
  routeTree,
  context: {
    queryClient: undefined!, // Will be set in App
  },
});

declare module ‘@tanstack/react-router’ {  interface Register {
    router: typeof router;
  }
}
```

---

## 6. Technical requirements

### 6.1 package.json:

```json
{
  “name”: “data-forge-ui”,
  ‘version’: “1.0.0”,  “type”: “module”,
  “scripts”: {
    “dev”: “vite”,
    “build”: “tsc && vite build”,
    “preview”: “vite preview”,
    “lint”: “eslint . --ext ts,tsx”,
    ‘format’: “prettier --write \”src/**/*.{ts,tsx,css}\“” ,
    “type-check”: “tsc --noEmit”
  },
  “dependencies”: {
    ‘react’: “^19.0.0”,
    “react-dom”: “^19.0.0”,    “@tanstack/react-router”: “^1.77.0”,
    “@tanstack/react-query”: “^5.62.0”,
    “@tanstack/react-table”: “^8.20.0”,    “@tanstack/react-store”: “^0.5.0”,
    “react-hook-form”: “^7.54.0”,
    “@hookform/resolvers”: “^3.9.1”,
    ‘zod’: “^3.24.1”,
    “sonner”: “^1.7.0”,
    “react-dropzone”: “^14.3.5”,
    “class-variance-authority”: “^0.7.1”,
    ‘clsx’: “^2.1.1”,
    “tailwind-merge”: “^2.5.0”,
    “lucide-react”: “^0.460.0”
  },
  ‘devDependencies’: {
    “@types/react”: “^19.0.0”,
    “@types/react-dom”: “^19.0.0”,
    “@vitejs/plugin-react”: “^4.3.0”,    “typescript”: “^5.6.0”,
    “vite”: “^6.0.0”,
    ‘tailwindcss’: “^3.4.0”,    “postcss”: “^8.4.0”,
    “autoprefixer”: “^10.4.0”,
    ‘eslint’: “^8.57.0”,
    “@typescript-eslint/eslint-plugin”: “^8.0.0”,    “@typescript-eslint/parser”: “^8.0.0”,
    ‘prettier’: “^3.3.0”,
    “prettier-plugin-tailwindcss”: “^0.6.0”  }
}
```

---

### 6.2 Configuration files:

**vite.config.ts:**
```typescript
import { defineConfig } from ‘vite’;
import react from ‘@vitejs/plugin-react’;
import { TanStackRouterVite } from ‘@tanstack/router-plugin/vite’;
import path from ‘path’;

export default defineConfig({
  plugins: [    TanStackRouterVite(),
    react(),
  ],
  resolve: {
    alias: {
      ‘@’: path.resolve(__dirname, ‘./src’),
    },
  },
  server: {
    port: 3000,
    proxy: {
      ‘/api’: {        target: ‘http://localhost:8080’,
        changeOrigin: true,
      },
      ‘/admin’: {        target: ‘http://localhost:8080’,
        changeOrigin: true,
      },
    },
  },
});
```

**tailwind.config.ts:**
```typescript
import type { Config } from ‘tailwindcss’;

export default {  darkMode: [‘class’],
  content: [
    ‘./index.html’,
    ‘./src/**/*.{js,ts,jsx,tsx}’,
  ],
  theme: {
    extend: {
      colors: {
        border: ‘hsl(var(--border))’,
        input: ‘hsl(var(--input))’,        ring: ‘hsl(var(--ring))’,
        background: ‘hsl(var(--background))’,
        foreground: ‘hsl(var(--foreground))’,
        primary: {
          DEFAULT: ‘hsl(var(--primary))’,
          foreground: ‘hsl(var(--primary-foreground))’,
        },        // ... other shadcn/ui colors
      },
    },
  },
  plugins: [require(‘tailwindcss-animate’)],
} satisfies Config;
```

**components.json (for shadcn/ui):**
```json
{
  “$schema”: “https://ui.shadcn.com/schema.json”,
  “style”: “default”,
  “rsc”: false,
  “tsx”: true,
  “tailwind”: {
    ‘config’: “tailwind.config.ts”,    “css”: “src/app/styles/globals.css”,
    “baseColor”: “slate”,
    “cssVariables”: true
  },
  “aliases”: {
    “components”: “@/shared/ui”,
    ‘utils’: “@/shared/lib/utils”
  }
}
```

---

## 7. Execution order

### Step 1: Backend Refactoring ⚠️ CRITICAL
1. ✅ Create all DTO classes
2. ✅ Update controllers
3. ✅ Update OpenAPI
4. ✅ Update ALL tests

### Stage 2: Project Initialization
1. ✅ `npm create vite@latest ui -- --template react-ts`
2. ✅ Install dependencies: `npm install`
3. ✅ Configure shadcn/ui: `npx shadcn@latest init`
4. ✅ Create FSD structure
5. ✅ Configure Tailwind CSS
6. ✅ Configure aliases

### Step 3: Shared layer
1. ✅ API client (fetch wrapper)
2. ✅ Auth library
3. ✅ shadcn/ui components
4. ✅ Utilities

### Stage 4: App layer
1. ✅ Query Provider
2. ✅ Router Provider
3. ✅ Auth Provider
4. ✅ Router configuration

### Stage 5: Entities layer
1. ✅ User, Account, Site, Batch, File, Error, Statistics
2. ✅ API clients
3. ✅ TanStack Query hooks (queries.ts, mutations.ts)

### Stage 6: Features layer
1. ✅ Auth features
2. ✅ Account CRUD
3. ✅ Site CRUD
4. ✅ Batch operations
5. ✅ Error handling

### Stage 7: Widgets layer
1. ✅ Header, Sidebar
2. ✅ Admin tables (with TanStack Table)
3. ✅ User components

### Stage 8: Pages layer
1. ✅ Login
2. ✅ Admin pages
3. ✅ User pages

---

## 8. Acceptance criteria

### Backend:
- ✅ All APIs return typed DTOs
- ✅ OpenAPI is up to date
- ✅ All tests are updated

### Frontend:
- ✅ FSD architecture
- ✅ TanStack Query for all API calls
- ✅ TanStack Table for tables
- ✅ TanStack Router for routing
- ✅ React Hook Form + Zod for forms
- ✅ shadcn/ui components
- ✅ Tailwind CSS styling
- ✅ Bearer token authorization
- ✅ Protected routes
- ✅ Toast notifications (sonner)

