# DataForge UI - Frontend Application

React 19 + TypeScript frontend for account, site and batch management with Auth0 authentication.

## Tech Stack

- **React 19** with TypeScript (strict mode)
- **Vite 7** - Build tool
- **TanStack** - Query, Router (code-based routes in `src/app/router.tsx`), Table
- **@auth0/auth0-react** - OAuth 2.0 / OIDC authentication (Universal Login + PKCE)
- **React Hook Form + Zod** - Form handling and validation
- **shadcn/ui + Tailwind CSS** - UI components and styling
- **Recharts** - Data visualization
- **Vitest + React Testing Library** - Testing

## Prerequisites

- Node.js 20.x or later
- npm 10.x or later
- Running backend API at http://localhost:8080
- An Auth0 tenant with a Single Page Application client (see [Auth0 Configuration](#auth0-configuration))

## Quick Start

### 1. Install Dependencies

```bash
npm install
```

### 2. Configure Environment

Copy the local template and fill in your Auth0 SPA credentials:

```bash
cp .env.local.example .env.local
```

`.env.local` is gitignored and takes precedence over the committed `.env.development`:

```bash
# Auth0 Configuration
VITE_AUTH0_DOMAIN=your-tenant.us.auth0.com
VITE_AUTH0_CLIENT_ID=your-spa-client-id
VITE_AUTH0_AUDIENCE=https://api.dataforge.com
VITE_AUTH0_CLAIMS_NAMESPACE=https://api.dataforge.com

# API Configuration
VITE_API_BASE_URL=http://localhost:8080
```

> Use the **SPA** client ID, not the M2M client ID — the latter is for the backend.

### 3. Start Development Server

```bash
npm run dev
```

Application will be available at: http://localhost:3000

### 4. Build for Production

```bash
npm run build
```

Output will be in `dist/` directory.

## Available Scripts

| Command | Description |
|---------|-------------|
| `npm run dev` | Start development server (port 3000) |
| `npm run build` | Typecheck (`tsc`) then build for production |
| `npm run build:analyze` | Analyze bundle size |
| `npm run preview` | Preview production build |
| `npm run lint` | Run ESLint (0 errors / 0 warnings — enforced in CI) |
| `npm run test` | Run unit + integration tests (Vitest) |
| `npm run test:watch` | Run tests in watch mode |
| `npm run test:coverage` | Generate test coverage report |

## Project Structure

Feature-Sliced Design:

```
src/
├── app/              # Application root, providers, routing
├── pages/            # Route pages (Dashboard, Accounts, Sites, Comparisons, ...)
├── widgets/          # Composite UI widgets (Header, Delta Sync, Plugins, ...)
├── features/         # Feature slices (api/ queries + mutations, model/ types, ui/)
├── entities/         # Business entities (Account, Batch, Site, User Session)
└── shared/           # Shared utilities, UI primitives, API client, design tokens
```

## Features

### Authentication
- Auth0 Universal Login with PKCE
- Refresh token rotation (`useRefreshTokens`), tokens cached in memory
- Role-based route guards (`ROLE_ADMIN` / `ROLE_USER`) from a custom Auth0 claim
- Browser half of the OAuth 2.0 Device Authorization Flow (`/device-verify`)

### Dashboard
- Metrics and charts, global errors widget, plugin widgets
- Responsive layout (desktop, tablet, mobile)

### Admin
- Account management: list, create, view, lock/unlock, reset password
- Site detail with Upload History and Delta Sync tabs
- Plugin administration and app settings

### Account (owner)
- Sites, upload history, batch detail, file comparisons, plugins

## Development Workflow

### 1. Run Backend

```bash
# In repository root
cd ..
./gradlew bootRun --args='--spring.profiles.active=dev'
```

### 2. Run Frontend

```bash
npm run dev
```

### 3. Login

Navigate to http://localhost:3000 and click **Sign In with Auth0**. You are
redirected to the Auth0 Universal Login page and back to `/dashboard` after
authenticating.

> Users must be created through the admin UI of the environment you are using —
> see the account management docs. Roles are assigned via an Auth0 Post-Login
> Action that injects the custom claims.

## Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `VITE_AUTH0_DOMAIN` | Auth0 tenant domain | `your-tenant.us.auth0.com` |
| `VITE_AUTH0_CLIENT_ID` | Auth0 SPA client ID | `abc123...` |
| `VITE_AUTH0_AUDIENCE` | Auth0 API identifier (matches backend) | `https://api.dataforge.com` |
| `VITE_AUTH0_CLAIMS_NAMESPACE` | Namespace for the roles/accountId claims | `https://api.dataforge.com` |
| `VITE_API_BASE_URL` | Backend API base URL | `http://localhost:8080` |

In production these are **not** baked in at build time: the container injects
them into `window._env_` at startup via `public/env-config.js`, which takes
precedence over build-time values. See `src/shared/config/env.ts`.

## Auth0 Configuration

Required Auth0 setup:

1. **Application (Single Page Application):**
   - Application Type: `Single Page Application`
   - Allowed Callback URLs: `http://localhost:3000`, `https://app.domain.com`
   - Allowed Logout URLs: `http://localhost:3000`, `https://app.domain.com`
   - Allowed Web Origins: `http://localhost:3000`, `https://app.domain.com`
   - Grant Types: Authorization Code (PKCE) + Refresh Token
   - Refresh Token Rotation: Enabled

2. **API:**
   - Identifier must equal `VITE_AUTH0_AUDIENCE` and the backend's `AUTH0_AUDIENCE`

3. **Post-Login Action** injecting custom claims under
   `VITE_AUTH0_CLAIMS_NAMESPACE`:
   - `<namespace>/roles` → `ROLE_ADMIN` or `ROLE_USER`
   - `<namespace>/accountId` → PostgreSQL account id (regular users only)

## Troubleshooting

### Redirected straight back to the login page

1. Check the callback/logout/web-origin URLs match the app origin exactly
2. Clear browser cookies/sessionStorage
3. Verify Refresh Token grant and rotation are enabled on the SPA client

### Blocked requests to the Auth0 domain

If you deploy behind a proxy with a Content Security Policy, the Auth0 tenant
domain must be allowed in `connect-src` — auth0-spa-js calls `/oauth/token`
from the page. See `nginx.conf.example`.

### API 401 Unauthorized

1. Check the axios interceptor is attaching the Auth0 access token
2. Verify `VITE_AUTH0_AUDIENCE` matches the backend's configured audience
3. Verify the backend accepts tokens from your Auth0 issuer
4. Test the token manually: `curl -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/accounts`

### Missing role / "Auth0 configuration missing" on boot

1. `VITE_AUTH0_DOMAIN`, `VITE_AUTH0_CLIENT_ID` and `VITE_AUTH0_AUDIENCE` are all required
2. Roles come from the Post-Login Action — confirm the claims namespace matches
   `VITE_AUTH0_CLAIMS_NAMESPACE`

### Bundle Size Exceeded

1. Run `npm run build:analyze`
2. Use React.lazy for code splitting
3. Import only needed Recharts components

## Performance

- **Code Splitting**: Routes lazy-loaded
- **Caching**: React Query for server state
- **Virtualization**: TanStack Table for large datasets

## Browser Support

- Chrome/Edge (latest)
- Firefox (latest)
- Safari (latest)

## Related Documentation

- [Backend API](../README.md)
- [Development guidelines](../CLAUDE.md)
- [OpenAPI Docs](http://localhost:8080/swagger-ui.html)
