# Implementation Plan: Site Management for Users and Admins

**Branch**: `007-adding-a-site` | **Date**: 2025-10-30 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/007-adding-a-site/spec.md`

## Summary

This feature implements comprehensive site management capabilities for both regular users and administrators. Users can create, view, activate, deactivate, and delete sites through a self-service interface in Account Management. Administrators can perform the same operations on behalf of users through the User Management section. The implementation extends the existing Site domain with frontend UI components following Feature-Sliced Design (FSD) architecture and integrates with Keycloak-based authentication for both user and admin roles.

**Technical Approach**: Extend existing backend Site domain with new admin endpoints and audit logging. Build FSD-compliant React frontend with shadcn/ui components, TanStack Query for API state management, and React Hook Form + Zod for validation. Implement Keycloak authentication with role-based access control (regular users vs ROLE_ADMIN).

## Technical Context

**Language/Version**: Backend: Java 21, Frontend: TypeScript 5.6 with React 18.3
**Primary Dependencies**: Backend: Spring Boot 3.5.6, Spring Security 6, Spring Data JPA; Frontend: React 18.3, TanStack Query v5, TanStack Router, shadcn/ui, Tailwind CSS
**Storage**: PostgreSQL 16 (sites table already exists, extend with admin_action_logs table via Flyway migration)
**Testing**: Backend: JUnit 5 + Mockito + Testcontainers; Frontend: Vitest + React Testing Library + Playwright
**Target Platform**: Web application (browser-based SPA for frontend, JVM for backend)
**Project Type**: Web (separate backend and frontend codebases)
**Performance Goals**: Site list loading <2 seconds for 50 sites (SC-007), site deactivation <1 second (SC-002), overall p95 latency <1000ms
**Constraints**: Frontend bundle size <500KB (gzipped), 80% test coverage minimum, WCAG 2.1 AA accessibility
**Scale/Scope**: Support unlimited sites per account, 50 sites typical maximum per user, admin operations across all user accounts

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Backend Compliance

- ✅ **DDD (Principle I)**: Site entity already exists in domain layer. Extend with domain service for password generation and validation logic. Admin audit logging via domain events or application service.
- ✅ **PbLF (Principle II)**: Extend existing `site/` feature module. Add new endpoints to `site/presentation/SiteController` and `site/presentation/SiteAdminController`. Audit logging in `site/infrastructure/AdminActionLogRepository`.
- ✅ **TDD (Principle III)**: Contract tests → Integration tests → Implementation → Unit tests. Tests written before implementation, approved by user.
- ✅ **API-First (Principle IV)**: Generate OpenAPI contracts for new endpoints in Phase 1. RESTful design with JSON request/response DTOs.
- ✅ **Security (Principle V)**: Keycloak OAuth2 for admin endpoints (ROLE_ADMIN required). Regular user endpoints use existing Keycloak session auth. Input validation on domain and password fields.
- ✅ **Database (Principle VI)**: New `admin_action_logs` table via Flyway migration. Index on `target_account_id` and `created_at` for audit queries. Existing `sites` table requires no schema changes (uses existing columns).
- ✅ **Observability (Principle VII)**: Log admin actions via SLF4J. Add metrics for site creation/deletion rates via Micrometer. Health check unaffected.

### Frontend Compliance

- ✅ **FSD (Principle VIII)**: Structure: `pages/site-management`, `widgets/site-list`, `features/site-crud`, `entities/site`, `shared/ui/components`. Strict layer separation enforced.
- ✅ **Type Safety (Principle IX)**: TypeScript strict mode enabled. Zod schemas for form validation, types inferred via `z.infer`. No `any` types.
- ✅ **React Query (Principle X)**: All API calls via TanStack Query hooks: `useSites`, `useCreateSite`, `useUpdateSiteStatus`, `useDeleteSite`. Query key factory for cache invalidation.
- ✅ **Frontend TDD (Principle XI)**: Write tests first. 70% unit tests (utilities, hooks), 20% integration tests (user flows), 10% E2E (critical paths). 80% coverage minimum.
- ✅ **Keycloak SSO (Principle XII)**: Use existing Keycloak integration. Frontend checks user roles via token claims. Admin routes protected by ROLE_ADMIN guard.
- ✅ **Component Composition (Principle XIII)**: Site list as reusable widget. Form components composed from shadcn/ui primitives. No prop drilling (use TanStack Query + React Context for auth).
- ✅ **Form Validation (Principle XIV)**: React Hook Form + Zod. Schema: `CreateSiteFormSchema` (domain: string, password: string). Client-side validation before API call.
- ✅ **Performance (Principle XV)**: Lazy load site management page via React.lazy. Virtualize site list if >100 sites (TanStack Table). Bundle size monitored.
- ✅ **Accessibility (Principle XVI)**: Semantic HTML, ARIA labels for actions, keyboard navigation. Delete confirmation dialog accessible. Form errors linked to inputs.

### Gate Evaluation

**Status**: ✅ **PASS** - All principles satisfied. No violations requiring justification.

**Notes**:
- Site domain already exists in backend (CLAUDE.md confirms). This feature extends existing structure, no new aggregates.
- Frontend follows FSD from the start. No architecture refactoring needed.
- Keycloak integration already configured for both user and admin roles.

## Project Structure

### Documentation (this feature)

```
specs/007-adding-a-site/
├── spec.md              # Feature specification (completed)
├── plan.md              # This file (/speckit.plan output)
├── research.md          # Phase 0 output (generated below)
├── data-model.md        # Phase 1 output (generated below)
├── quickstart.md        # Phase 1 output (generated below)
├── contracts/           # Phase 1 output (generated below)
│   ├── user-site-management.yaml      # OpenAPI for user endpoints
│   └── admin-site-management.yaml     # OpenAPI for admin endpoints
└── tasks.md             # Phase 2 output (/speckit.tasks - not created by /speckit.plan)
```

### Source Code (repository root)

```
# Backend (existing structure, extending site/ module)
src/main/java/com/bitbi/dfm/
├── site/
│   ├── domain/
│   │   ├── Site.java                  # Existing entity (no changes needed)
│   │   ├── SiteRepository.java        # Existing interface (no changes needed)
│   │   └── PasswordGenerator.java     # NEW: Domain service for password generation
│   ├── application/
│   │   └── SiteService.java           # Existing service (extend with new methods)
│   ├── infrastructure/
│   │   ├── JpaSiteRepository.java     # Existing repository (no changes needed)
│   │   └── JpaAdminActionLogRepository.java  # NEW: Audit log repository
│   └── presentation/
│       ├── SiteController.java        # Existing controller (extend with new endpoints)
│       ├── SiteAdminController.java   # MODIFY: Add site management endpoints
│       └── dto/
│           ├── CreateSiteRequestDto.java      # NEW: Site creation DTO
│           ├── SiteResponseDto.java           # MODIFY: Extend with more fields
│           └── AdminActionLogResponseDto.java # NEW: Audit log DTO
├── adminactionlog/                     # NEW: Admin action logging aggregate
│   ├── domain/
│   │   ├── AdminActionLog.java        # NEW: Entity for audit trail
│   │   └── AdminActionLogRepository.java # NEW: Repository interface
│   └── infrastructure/
│       └── JpaAdminActionLogRepository.java  # NEW: JPA implementation

src/main/resources/
└── db/migration/
    └── V008__add_admin_action_logs.sql  # NEW: Flyway migration for audit table

src/test/java/com/bitbi/dfm/site/
├── contract/
│   ├── SiteContractTest.java          # EXTEND: Add new endpoint tests
│   └── SiteAdminContractTest.java     # EXTEND: Add site management tests
├── integration/
│   └── SiteManagementIntegrationTest.java  # NEW: End-to-end flow tests
└── application/
    └── SiteServiceTest.java            # EXTEND: Add unit tests for new methods

# Frontend (new FSD structure for site management)
frontend/src/
├── app/
│   ├── providers/
│   │   └── query-client.ts            # Existing TanStack Query setup
│   └── router.tsx                     # EXTEND: Add site management routes
├── pages/
│   ├── site-management/
│   │   ├── index.ts                   # Public API
│   │   ├── SiteManagementPage.tsx     # NEW: User site management page
│   │   └── SiteManagementPage.test.tsx # NEW: Page integration tests
│   └── admin/
│       └── user-sites/
│           ├── index.ts                   # Public API
│           ├── UserSitesPage.tsx          # NEW: Admin site management page
│           └── UserSitesPage.test.tsx     # NEW: Page integration tests
├── widgets/
│   └── site-list/
│       ├── index.ts                   # Public API
│       ├── SiteList.tsx               # NEW: Reusable site list widget
│       ├── SiteList.test.tsx          # NEW: Widget tests
│       └── ui/
│           ├── SiteListItem.tsx       # NEW: Individual site row component
│           └── SiteListItem.test.tsx  # NEW: Component tests
├── features/
│   └── site-crud/
│       ├── index.ts                   # Public API
│       ├── model/
│       │   ├── queries.ts             # NEW: TanStack Query hooks
│       │   ├── queries.test.ts        # NEW: Query hooks tests
│       │   └── schemas.ts             # NEW: Zod validation schemas
│       └── ui/
│           ├── CreateSiteForm.tsx     # NEW: Site creation form
│           ├── CreateSiteForm.test.tsx # NEW: Form tests
│           ├── DeleteSiteDialog.tsx    # NEW: Confirmation dialog
│           └── DeleteSiteDialog.test.tsx # NEW: Dialog tests
├── entities/
│   └── site/
│       ├── index.ts                   # Public API
│       ├── model/
│       │   └── types.ts               # NEW: Site entity types
│       └── api/
│           ├── siteApi.ts             # NEW: API client functions
│           └── siteApi.test.ts        # NEW: API client tests
└── shared/
    ├── api/
    │   └── client.ts                  # Existing Axios instance
    ├── lib/
    │   ├── password-generator.ts      # NEW: Client-side password generator
    │   └── password-generator.test.ts # NEW: Utility tests
    └── ui/
        └── components/                # Existing shadcn/ui components

frontend/tests/
└── e2e/
    ├── site-management.spec.ts        # NEW: E2E tests for user flows
    └── admin-site-management.spec.ts  # NEW: E2E tests for admin flows
```

**Structure Decision**: Web application structure (Option 2) selected. Backend extends existing `site/` module following PbLF architecture. Frontend implements new FSD structure with clear layer separation (app → pages → widgets → features → entities → shared). Site management is a new feature slice, reusable across user and admin contexts.

## Complexity Tracking

*No constitution violations detected. This section is not applicable.*

All principles satisfied without exceptions. Feature extends existing architecture patterns without introducing new complexity.
