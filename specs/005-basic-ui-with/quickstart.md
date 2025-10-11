# Quickstart: Basic UI Development Guide

**Feature**: Basic UI with Keycloak Authentication and Subscriber Management
**Branch**: `005-basic-ui-with` (should be renamed to `feature/005-basic-ui-with`)
**Date**: 2025-10-11

## Overview

This guide provides step-by-step instructions for setting up, developing, and deploying the Basic UI feature. Follow this guide to go from zero to a running React 19 application with Keycloak authentication and subscriber management.

---

## Prerequisites

### Required Software

- **Node.js**: 20.x LTS or later
- **npm**: 10.x or later (comes with Node.js)
- **Git**: 2.x or later
- **Java**: 21 LTS (for backend - already exists)
- **PostgreSQL**: 16 (for backend - already exists)
- **Keycloak**: Running instance (configured per Assumption #1)

### Recommended Tools

- **VS Code**: With extensions for TypeScript, ESLint, Prettier, Tailwind CSS
- **Browser DevTools**: Chrome DevTools or Firefox Developer Tools
- **Postman** or **Insomnia**: For API testing (optional)

### Knowledge Requirements

- TypeScript / JavaScript fundamentals
- React 19 concepts (hooks, components, context)
- REST API integration
- OAuth 2.0 / OpenID Connect basics
- Git workflow

---

## Project Setup

### 1. Create Frontend Directory

```bash
cd /Users/boris/projects/bit-bi/data-forge-middleware
mkdir frontend
cd frontend
```

### 2. Initialize React + Vite Project

```bash
# Create Vite project with React + TypeScript template
npm create vite@latest . -- --template react-ts

# Install dependencies
npm install
```

### 3. Install Core Dependencies

```bash
# TanStack libraries
npm install @tanstack/react-query @tanstack/react-router @tanstack/react-table @tanstack/react-store

# Authentication (OAuth 2.0 / OIDC)
npm install react-oidc-context oidc-client-ts

# Forms & Validation
npm install react-hook-form zod @hookform/resolvers

# HTTP Client
npm install axios

# UI Libraries
npm install tailwindcss postcss autoprefixer
npm install class-variance-authority clsx tailwind-merge
npm install @radix-ui/react-dialog @radix-ui/react-dropdown-menu @radix-ui/react-label
npm install @radix-ui/react-select @radix-ui/react-toast

# Charts (Recharts via shadcn/ui)
npm install recharts

# Notifications
npm install sonner

# Icons
npm install lucide-react
```

### 4. Install Development Dependencies

```bash
npm install --save-dev @types/node
npm install --save-dev eslint @typescript-eslint/eslint-plugin @typescript-eslint/parser
npm install --save-dev prettier eslint-config-prettier
npm install --save-dev vitest @vitest/ui jsdom @testing-library/react @testing-library/jest-dom
npm install --save-dev @playwright/test
npm install --save-dev msw
```

### 5. Configure Tailwind CSS

```bash
npx tailwindcss init -p
```

Update `tailwind.config.js`:

```javascript
/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {},
  },
  plugins: [],
};
```

Add Tailwind directives to `src/index.css`:

```css
@tailwind base;
@tailwind components;
@tailwind utilities;
```

### 6. Configure TypeScript

Update `tsconfig.json` to enable strict mode and path aliases:

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,

    /* Bundler mode */
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "react-jsx",

    /* Linting - STRICT MODE ENABLED */
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true,

    /* Path aliases for FSD architecture */
    "baseUrl": ".",
    "paths": {
      "@/*": ["./src/*"],
      "@/app/*": ["./src/app/*"],
      "@/pages/*": ["./src/pages/*"],
      "@/widgets/*": ["./src/widgets/*"],
      "@/features/*": ["./src/features/*"],
      "@/entities/*": ["./src/entities/*"],
      "@/shared/*": ["./src/shared/*"]
    }
  },
  "include": ["src"],
  "references": [{ "path": "./tsconfig.node.json" }]
}
```

### 7. Configure Vite

Update `vite.config.ts` for path aliases and React 19:

```typescript
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
      '@/app': path.resolve(__dirname, './src/app'),
      '@/pages': path.resolve(__dirname, './src/pages'),
      '@/widgets': path.resolve(__dirname, './src/widgets'),
      '@/features': path.resolve(__dirname, './src/features'),
      '@/entities': path.resolve(__dirname, './src/entities'),
      '@/shared': path.resolve(__dirname, './src/shared'),
    },
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
```

### 8. Create FSD Directory Structure

```bash
mkdir -p src/{app,pages,widgets,features,entities,shared}/{login,dashboard,subscribers}
mkdir -p src/entities/{subscriber,user-session,dashboard-metrics}/model
mkdir -p src/shared/{api,config,lib,ui,hooks}
mkdir -p tests/{unit,integration,e2e}
```

### 9. Configure Environment Variables

Create `.env.development`:

```bash
# Keycloak Configuration
VITE_KEYCLOAK_URL=https://keycloak.example.com
VITE_KEYCLOAK_REALM=your-realm
VITE_KEYCLOAK_CLIENT_ID=dataforge-ui

# API Configuration
VITE_API_BASE_URL=http://localhost:8080

# Feature Flags
VITE_ENABLE_DEMO_DATA=true
```

Create `.env.production`:

```bash
# Keycloak Configuration
VITE_KEYCLOAK_URL=https://keycloak.production.com
VITE_KEYCLOAK_REALM=prod-realm
VITE_KEYCLOAK_CLIENT_ID=dataforge-ui

# API Configuration
VITE_API_BASE_URL=https://api.domain.com

# Feature Flags
VITE_ENABLE_DEMO_DATA=false
```

---

## Development Workflow

### 1. Start Backend API

```bash
# In repository root (backend directory)
./gradlew bootRun --args='--spring.profiles.active=dev'

# Verify backend is running
curl http://localhost:8080/actuator/health
```

### 2. Start Keycloak

Ensure Keycloak is running and configured with:
- Realm created
- Client created (dataforge-ui, Public client type, PKCE required)
- Valid redirect URIs configured (http://localhost:3000/*)
- Test users created

### 3. Start Frontend Development Server

```bash
cd frontend
npm run dev
```

Navigate to: http://localhost:3000

### 4. TDD Workflow

**Step 1: Write Test (RED)**

```bash
# Create test file (e.g., for login feature)
touch src/features/auth/login/LoginButton.test.tsx

# Run tests in watch mode
npm run test:watch
```

**Step 2: Implement Code (GREEN)**

```bash
# Create implementation file
touch src/features/auth/login/LoginButton.tsx

# Implement minimal code to pass test
```

**Step 3: Refactor**

- Improve code quality
- Extract reusable logic
- Ensure tests still pass

**Step 4: Verify Coverage**

```bash
npm run test:coverage
```

Target: ≥80% overall, ≥95% critical paths, 100% utilities.

### 5. Git Workflow

```bash
# Create feature branch (correct naming per constitution)
git checkout -b feature/005-basic-ui-with

# Make changes
git add .
git commit -m "feat: implement login page with Keycloak OAuth 2.0"

# Push to remote
git push origin feature/005-basic-ui-with

# Create PR when ready
gh pr create --title "feat: Basic UI with Keycloak auth" --body "Implements P1-P6 user stories"
```

---

## Running Tests

### Unit Tests (Vitest)

```bash
# Run all unit tests
npm run test

# Run tests in watch mode
npm run test:watch

# Run tests with coverage
npm run test:coverage

# Run tests for specific file
npm run test LoginButton
```

### Integration Tests (Testing Library)

```bash
# Run integration tests
npm run test:integration

# Example: Test subscriber CRUD flow
npm run test SubscriberCrud
```

### E2E Tests (Playwright)

```bash
# Install Playwright browsers (first time only)
npx playwright install

# Run E2E tests
npm run test:e2e

# Run E2E tests in UI mode (interactive)
npx playwright test --ui

# Run specific E2E test
npx playwright test auth.spec.ts
```

---

## Building for Production

### 1. Build Frontend

```bash
cd frontend
npm run build
```

Output: `frontend/dist/` directory

### 2. Analyze Bundle Size

```bash
npm run build:analyze
```

**Target**: <500KB gzipped total

If bundle exceeds limit:
- Review Recharts usage (use only needed chart types)
- Enable code splitting with React.lazy
- Check for duplicate dependencies
- Use Vite's bundle analyzer to identify large modules

### 3. Preview Production Build

```bash
npm run preview
```

Navigate to: http://localhost:4173

### 4. Deploy

**Option A: Static Hosting (Netlify, Vercel)**

```bash
# Build output is in frontend/dist/
# Configure build command: cd frontend && npm run build
# Configure publish directory: frontend/dist
```

**Option B: Docker**

```dockerfile
# frontend/Dockerfile
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

---

## Keycloak Configuration

### Required Keycloak Setup

1. **Create Realm** (if not exists):
   - Name: `your-realm`
   - Enabled: Yes

2. **Create Client**:
   - Client ID: `dataforge-ui`
   - Client Protocol: `openid-connect`
   - Access Type: `public`
   - Standard Flow Enabled: Yes
   - Direct Access Grants Enabled: No
   - Valid Redirect URIs: `http://localhost:3000/*`, `https://app.domain.com/*`
   - Web Origins: `http://localhost:3000`, `https://app.domain.com`
   - Proof Key for Code Exchange: Required

3. **Create Test User**:
   - Username: `testuser`
   - Email: `testuser@example.com`
   - First Name: `Test`
   - Last Name: `User`
   - Email Verified: Yes
   - Credentials: Set password (temporary: off)

4. **Assign Roles**:
   - Add `ROLE_ADMIN` to test user for admin API access

---

## Troubleshooting

### Issue: "Module not found" errors

**Solution**: Verify path aliases in `tsconfig.json` and `vite.config.ts` match.

### Issue: Keycloak redirect loop

**Solution**:
1. Check redirect URIs in Keycloak client config
2. Verify `redirect_uri` in AuthProvider matches exactly
3. Clear browser cookies/sessionStorage
4. Check browser console for CORS errors

### Issue: API calls return 401 Unauthorized

**Solution**:
1. Verify Keycloak token is being attached to requests (check Network tab)
2. Check axios interceptor is configured correctly
3. Ensure backend is accepting tokens from Keycloak (check issuer URL)
4. Test token with: `curl -H "Authorization: Bearer <token>" http://localhost:8080/api/admin/subscribers`

### Issue: Bundle size exceeds 500KB

**Solution**:
1. Run `npm run build:analyze` to identify large modules
2. Use React.lazy for route-based code splitting
3. Import only needed Recharts components via shadcn/ui (not full library)
4. Check for duplicate dependencies: `npm dedupe`

### Issue: Tests failing with "Cannot find module"

**Solution**:
1. Update `vitest.config.ts` with same path aliases as `vite.config.ts`
2. Install missing test dependencies: `@testing-library/react`, `jsdom`
3. Check test file imports use correct aliases (`@/features/...`)

---

## Useful Commands

### Package Management

```bash
# Install dependencies
npm install

# Update dependencies (check for breaking changes first)
npm update

# Audit for vulnerabilities
npm audit

# Fix vulnerabilities
npm audit fix
```

### Code Quality

```bash
# Lint code
npm run lint

# Format code
npm run format

# Type check
npm run typecheck
```

### Development

```bash
# Start dev server
npm run dev

# Build for production
npm run build

# Preview production build
npm run preview

# Clean build artifacts
rm -rf dist node_modules && npm install
```

---

## Next Steps

After completing quickstart:

1. **Generate tasks.md**: Run `/speckit.tasks` to break down implementation into testable tasks
2. **Implement P1 (Auth)**: Start with highest priority user story
3. **Write tests first**: Follow TDD workflow (RED → GREEN → REFACTOR)
4. **Verify coverage**: Aim for ≥80% overall, ≥95% critical paths
5. **Create PR**: Follow PR requirements from constitution

---

## Resources

### Documentation

- **React 19**: https://react.dev
- **TypeScript**: https://www.typescriptlang.org/docs
- **Vite**: https://vitejs.dev
- **TanStack Query**: https://tanstack.com/query/latest
- **TanStack Router**: https://tanstack.com/router/latest
- **TanStack Table**: https://tanstack.com/table/latest
- **react-oidc-context**: https://github.com/authts/react-oidc-context
- **Recharts**: https://recharts.org
- **shadcn/ui**: https://ui.shadcn.com
- **Tailwind CSS**: https://tailwindcss.com

### Internal Documentation

- **Specification**: [spec.md](./spec.md)
- **Research Findings**: [research.md](./research.md)
- **Data Model**: [data-model.md](./data-model.md)
- **API Contracts**: [contracts/api-contracts.yaml](./contracts/api-contracts.yaml)
- **Type Definitions**: [contracts/type-definitions.ts](./contracts/type-definitions.ts)
- **Implementation Plan**: [plan.md](./plan.md)

### Backend API

- **OpenAPI Spec**: http://localhost:8080/v3/api-docs
- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **Health Check**: http://localhost:8080/actuator/health
