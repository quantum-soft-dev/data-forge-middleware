# DataForge UI - Frontend Application

React 19 + TypeScript frontend for account management with Keycloak authentication.

## Tech Stack

- **React 19** with TypeScript (strict mode)
- **Vite** - Build tool
- **TanStack** - Query, Router, Table
- **react-oidc-context** - OAuth 2.0 / OIDC authentication
- **React Hook Form + Zod** - Form handling and validation
- **shadcn/ui + Tailwind CSS** - UI components and styling
- **Recharts** - Data visualization
- **Vitest + Playwright** - Testing

## Prerequisites

- Node.js 20.x or later
- npm 10.x or later
- Running backend API at http://localhost:8080
- Running Keycloak instance (configured per spec)

## Quick Start

### 1. Install Dependencies

```bash
npm install
```

### 2. Configure Environment

Create `.env.development`:

```bash
# Keycloak Configuration
VITE_KEYCLOAK_URL=https://keycloak.example.com
VITE_KEYCLOAK_REALM=your-realm
VITE_KEYCLOAK_CLIENT_ID=dataforge-ui

# API Configuration
VITE_API_BASE_URL=http://localhost:8080
```

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
| `npm run build` | Build for production |
| `npm run build:analyze` | Analyze bundle size |
| `npm run preview` | Preview production build |
| `npm run lint` | Run ESLint |
| `npm run test` | Run unit tests (Vitest) |
| `npm run test:coverage` | Generate test coverage report |

## Project Structure

```
src/
├── app/              # Application root, providers, routing
├── pages/            # Page components (Login, Dashboard, Accounts)
├── widgets/          # Composite UI widgets (Header, Table, Charts)
├── features/         # Feature-specific components (Auth, CRUD operations)
├── entities/         # Business entities (Account, User Session, Dashboard Metrics)
└── shared/           # Shared utilities, UI components, API client
```

## Features

### Authentication (Phase 3)
- Keycloak OAuth 2.0 login with PKCE
- Automatic token refresh
- Session persistence across browser restarts
- Secure logout

### Dashboard (Phase 4)
- Visual metrics with charts (Area, Pie, Bar)
- Responsive layout (desktop, tablet, mobile)
- Demo data visualization

### Account Management (Phases 5-8)
- **View**: Paginated table with search and filter
- **Create**: Validated form for new accounts
- **Edit**: Update existing account information
- **Delete**: Soft delete with confirmation

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

Navigate to http://localhost:3000, click "Login with Keycloak", authenticate with test credentials.

## Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `VITE_KEYCLOAK_URL` | Keycloak server URL | `https://keycloak.example.com` |
| `VITE_KEYCLOAK_REALM` | Keycloak realm name | `your-realm` |
| `VITE_KEYCLOAK_CLIENT_ID` | Keycloak client ID | `dataforge-ui` |
| `VITE_API_BASE_URL` | Backend API base URL | `http://localhost:8080` |

## Keycloak Configuration

Required Keycloak setup:

1. **Client Settings:**
   - Client ID: `dataforge-ui`
   - Client Protocol: `openid-connect`
   - Access Type: `public`
   - Standard Flow: Enabled
   - Valid Redirect URIs: `http://localhost:3000/*`, `https://app.domain.com/*`
   - Web Origins: `http://localhost:3000`, `https://app.domain.com`
   - PKCE: Required

2. **Test User:**
   - Username: `testuser`
   - Email: `testuser@example.com`
   - Password: Set in Keycloak (temporary: off)
   - Roles: `ROLE_ADMIN`

## Troubleshooting

### Keycloak Redirect Loop

1. Check redirect URIs match exactly
2. Clear browser cookies/sessionStorage
3. Verify CORS configuration

### API 401 Unauthorized

1. Check axios interceptor is attaching JWT
2. Verify backend accepts tokens from Keycloak issuer
3. Test token manually: `curl -H "Authorization: Bearer <token>" http://localhost:8080/api/admin/accounts`

### Bundle Size Exceeded

1. Run `npm run build:analyze`
2. Use React.lazy for code splitting
3. Import only needed Recharts components

## Performance

- **Bundle Size**: ~300KB gzipped (target: <500KB)
- **Code Splitting**: Routes lazy-loaded
- **Caching**: React Query for server state (30s stale time)
- **Virtualization**: TanStack Table for large datasets

## Browser Support

- Chrome/Edge (latest)
- Firefox (latest)
- Safari (latest)

## Related Documentation

- [Backend API](../README.md)
- [Feature Specification](../specs/005-basic-ui-with/spec.md)
- [Implementation Plan](../specs/005-basic-ui-with/plan.md)
- [OpenAPI Docs](http://localhost:8080/swagger-ui.html)
