# Implementation Plan: Basic UI with Keycloak Authentication and Subscriber Management

**Branch**: `feature/005-basic-ui-with` | **Date**: 2025-10-11 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/005-basic-ui-with/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

Build a modern React 19-based web application with Keycloak OAuth 2.0 authentication and full CRUD subscriber management capabilities. The system provides a dashboard with analytics visualization, paginated subscriber list (supporting up to 10,000 records), and form-based create/edit/delete operations. All functionality is protected behind Keycloak SSO with silent token refresh. The UI is responsive across desktop, tablet, and mobile devices.

## Technical Context

**Language/Version**:
- **Frontend**: React 19.2, TypeScript (strict mode)
- **Backend**: Java 21 (LTS) + Spring Boot 3.5.6 (existing - API integration only)

**Primary Dependencies**:
- **Build**: Vite
- **UI Framework**: shadcn/ui components, Tailwind CSS
- **State Management**: Zustand (React 19 compatible, ~3KB gzipped)
- **Data Fetching**: TanStack Query (React Query)
- **Routing**: TanStack Router (type-safe)
- **Tables**: TanStack Table (virtualization for 10K+ records)
- **Forms**: React Hook Form + Zod validation
- **Auth**: react-oidc-context (OAuth 2.0 / OIDC client)
- **Charts**: Recharts (via shadcn/ui components)
- **Notifications**: Sonner (toast notifications)

**Storage**: Backend REST API (existing) with PostgreSQL 16 (subscriber data)

**Testing**:
- **Unit**: Vitest + Testing Library (≥80% coverage target)
- **Integration**: Testing Library with mocked API calls
- **E2E**: Playwright for critical user flows

**Target Platform**: Modern web browsers (Chrome, Firefox, Safari, Edge - current and previous major version)

**Project Type**: Web application (frontend + backend API integration)

**Performance Goals**:
- Login to dashboard: <30 seconds
- Subscriber list load: <3 seconds (10K records with pagination)
- Search/filter: <2 seconds
- CRUD operations: <60 seconds (create), <45 seconds (edit), <10 seconds (delete)
- Dashboard load: <5 seconds
- Bundle size: <500KB (gzipped per constitution)
- Lighthouse score: ≥90

**Constraints**:
- Must NOT use keycloak.js library (React 19 incompatibility)
- Tokens stored in memory/sessionStorage (NOT localStorage per security constraint)
- Pagination mandatory for 10K subscriber limit
- Loading indicators for operations >2 seconds
- Client-side validation before submission
- Responsive design (1920x1080 desktop, 768x1024 tablet, 375x667 mobile)

**Scale/Scope**:
- Maximum 10,000 subscribers
- 6 user stories (P1-P6: Auth → Dashboard → View → Create → Edit → Delete)
- 21 functional requirements
- 3 entities (Subscriber, User Session, Dashboard Metrics)
- Single-user model (no collaborative editing)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Frontend Principles (Constitution v1.1.1)

| Principle | Status | Notes |
|-----------|--------|-------|
| **VIII. Feature-Sliced Design (FSD)** | ⚠️ MUST APPLY | Frontend architecture must follow FSD layers: app → pages → widgets → features → entities → shared. Bottom-up imports prohibited. |
| **IX. Type Safety First** | ✅ COMPLIANT | TypeScript strict mode required. Zod schemas for form validation. No `any` types. |
| **X. React Query for Server State** | ✅ COMPLIANT | TanStack Query for all API calls. Query key factories. Optimistic updates. |
| **XI. TDD for Frontend** | ⚠️ MUST APPLY | 70% unit (Vitest), 20% integration (Testing Library), 10% E2E (Playwright). Write tests BEFORE implementation. ≥80% coverage. |
| **XII. Keycloak SSO Integration** | ⚠️ NEEDS RESEARCH | OAuth 2.0 / OIDC + PKCE required. Token storage (memory/httpOnly cookies). Automatic refresh. CANNOT use keycloak.js per constraint. |
| **XIII. Component Composition** | ✅ COMPLIANT | Avoid prop drilling >2 levels. Context for auth. Custom hooks for reusable logic. |
| **XIV. Form Validation with Zod** | ✅ COMPLIANT | React Hook Form + Zod. Schema-derived types. Client-side validation. |
| **XV. Performance & Bundle** | ⚠️ MUST VERIFY | <500KB gzipped. React.lazy code splitting. TanStack Table virtualization for 10K records. Debounced search. |
| **XVI. Accessibility & Security** | ⚠️ MUST APPLY | WCAG 2.1 AA. Semantic HTML. ARIA labels. XSS prevention. CSP headers. |

### Backend Principles (Existing API - Integration Only)

| Principle | Status | Notes |
|-----------|--------|-------|
| **I-VII. DDD/PbLF/TDD** | ✅ N/A | Backend already exists. Frontend only integrates via REST API. |
| **IV. API-First Design** | ✅ COMPLIANT | OpenAPI 3.0 spec available at http://localhost:8080/v3/api-docs. |
| **V. Security by Default** | ⚠️ MUST INTEGRATE | Keycloak OAuth2 tokens required on all API calls. Frontend must attach JWT to requests. |

### Development Standards

| Standard | Status | Notes |
|----------|--------|-------|
| **Git Workflow** | ⚠️ PENDING | Branch created as `005-basic-ui-with` - should be renamed to `feature/005-basic-ui-with` per constitution standards. |
| **Testing Requirements** | ⚠️ MUST APPLY | 70% unit, 20% integration, 10% E2E. ≥80% overall, ≥95% critical paths, 100% utilities. |
| **Frontend Code Quality** | ✅ COMPLIANT | PascalCase components, camelCase hooks, TypeScript strict, no `any`, no prop drilling >2 levels. |
| **Security Requirements** | ⚠️ MUST APPLY | Tokens in httpOnly cookies/memory. HTTPS only. CSP headers. Centralized 401/403 handling. |

### ⚠️ **GATE VIOLATIONS REQUIRING JUSTIFICATION**

**None** - All constitution principles are applicable and achievable. The following items require research/planning but do not violate constitutional principles:

1. **OAuth 2.0 Library Selection** (Principle XII) - Need to choose between oidc-client-ts, react-oidc-context, or manual implementation given keycloak.js constraint.
2. **Charting Library Selection** (Principle XV - Performance) - Must select library that keeps bundle <500KB.
3. **FSD Layer Decomposition** (Principle VIII) - Need to plan specific FSD structure for auth, dashboard, and subscriber management features.
4. **Git Branch Rename** (Development Standards) - Current branch `005-basic-ui-with` should be `feature/005-basic-ui-with`.

## Project Structure

### Documentation (this feature)

```
specs/005-basic-ui-with/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   ├── api-contracts.yaml    # OpenAPI spec for backend integration
│   └── type-definitions.ts   # TypeScript types from Zod schemas
├── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
└── checklists/
    └── requirements.md  # Quality checklist (from /speckit.specify)
```

### Source Code (repository root)

**IMPORTANT**: This feature adds a NEW frontend application to an EXISTING backend project. The backend already implements subscriber management APIs.

```
# EXISTING BACKEND (NO CHANGES IN THIS FEATURE)
src/main/java/com/bitbi/dfm/
├── account/              # Account aggregate
├── site/                 # Site aggregate
├── batch/                # Batch aggregate
├── upload/               # File upload domain
├── error/                # Error logging
├── auth/                 # JWT authentication
└── shared/               # Cross-cutting concerns

# NEW FRONTEND (TO BE CREATED)
frontend/                 # React 19 application (NEW)
├── src/
│   ├── app/             # FSD Layer: Application setup
│   │   ├── App.tsx
│   │   ├── main.tsx
│   │   ├── router.tsx   # TanStack Router config
│   │   └── providers/   # React Query, Auth, Theme providers
│   │
│   ├── pages/           # FSD Layer: Page compositions
│   │   ├── login/       # Login page
│   │   ├── dashboard/   # Dashboard page
│   │   └── subscribers/ # Subscriber management pages
│   │       ├── list/
│   │       ├── create/
│   │       ├── edit/
│   │       └── delete/
│   │
│   ├── widgets/         # FSD Layer: Complex UI blocks
│   │   ├── header/      # App header with navigation
│   │   ├── dashboard-charts/  # Dashboard visualization widgets
│   │   └── subscriber-table/  # Paginated table widget
│   │
│   ├── features/        # FSD Layer: User interactions
│   │   ├── auth/
│   │   │   ├── login/
│   │   │   ├── logout/
│   │   │   └── token-refresh/
│   │   ├── subscriber-search/
│   │   ├── subscriber-create/
│   │   ├── subscriber-edit/
│   │   └── subscriber-delete/
│   │
│   ├── entities/        # FSD Layer: Business entities
│   │   ├── subscriber/
│   │   │   ├── model/   # Types, schemas
│   │   │   ├── api/     # React Query hooks
│   │   │   └── ui/      # Entity-specific components
│   │   ├── user-session/
│   │   └── dashboard-metrics/
│   │
│   └── shared/          # FSD Layer: Reusable infrastructure
│       ├── api/         # API client, interceptors
│       ├── config/      # Environment variables
│       ├── lib/         # Third-party lib wrappers
│       ├── ui/          # shadcn/ui components
│       └── hooks/       # Generic hooks
│
├── tests/
│   ├── unit/            # Vitest unit tests (70%)
│   ├── integration/     # Testing Library (20%)
│   └── e2e/             # Playwright (10%)
│       ├── auth.spec.ts
│       ├── dashboard.spec.ts
│       └── subscribers.spec.ts
│
├── public/              # Static assets
├── index.html
├── vite.config.ts
├── tsconfig.json
├── tailwind.config.js
├── package.json
└── README.md
```

**Structure Decision**: Web application with separate frontend and backend. Frontend follows Feature-Sliced Design (FSD) architecture per Constitution Principle VIII. Backend already exists with DDD/PbLF structure. Frontend integrates via REST API (OpenAPI spec at http://localhost:8080/v3/api-docs).

## Complexity Tracking

*No constitutional violations requiring justification.*

All complexity is justified by business requirements:
- **10,000 subscriber limit**: Requires TanStack Table virtualization (standard pattern).
- **OAuth 2.0 without keycloak.js**: Requires alternative library or manual implementation (technical constraint, not architectural complexity).
- **FSD architecture**: Required by constitution, not additional complexity.
- **Responsive design**: Standard web development practice for modern applications.

No simpler alternatives are rejected - all choices align with constitutional principles and feature requirements.
