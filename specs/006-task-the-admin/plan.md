# Implementation Plan: Admin User Management

**Branch**: `006-task-the-admin` | **Date**: 2025-10-28 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/006-task-the-admin/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

Implement comprehensive user management functionality in the admin console that allows administrators to create, lock/unlock, and reset passwords for users. All user operations must maintain synchronization between the local PostgreSQL database and Keycloak identity provider. The feature includes three prioritized capabilities: (P1) creating users with temporary passwords requiring change on first login, (P2) locking and unlocking user accounts for security control, and (P3) resetting user passwords to temporary values for support scenarios. Both backend REST APIs and frontend admin UI components are required.

## Technical Context

**Language/Version**: Java 21 (LTS) for backend, TypeScript 5.x for frontend
**Primary Dependencies**:
  - Backend: Spring Boot 3.5.6, Spring Security (OAuth2 Resource Server), Spring Data JPA, Keycloak Admin Client SDK
  - Frontend: React 19.2, TanStack Query, TanStack Router, React Hook Form + Zod, shadcn/ui
**Storage**: PostgreSQL 16 (existing accounts table extended with Keycloak user ID), Keycloak realm "dfm" (primary authentication and user store)
**Testing**:
  - Backend: JUnit 5, Mockito, Testcontainers (PostgreSQL + Keycloak), MockMvc for contract tests
  - Frontend: Vitest, Testing Library, Playwright for E2E
**Target Platform**: Web application (Linux server backend + browser-based admin UI)
**Project Type**: Web (backend + frontend)
**Performance Goals**:
  - Backend: <3 seconds for user CRUD operations, <1000ms p95 latency for API endpoints
  - Frontend: <500KB bundle size, Lighthouse score ≥90
**Constraints**:
  - Atomic operations across database (accounts table) and Keycloak (rollback on failure in either system)
  - 30-day temporary password expiration enforcement in Keycloak
  - Session continuation after account lock (no immediate termination)
  - Keycloak is source of truth for authentication, passwords, and user status
  - Existing Account entity must be extended (not duplicated) with Keycloak user ID correlation field
**Scale/Scope**:
  - Expected admin users: <50 concurrent
  - Expected total users in system: 10,000+
  - Admin UI: ~5-7 new pages/components (user list, create form, details view, lock/unlock controls, password reset dialog)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Backend Compliance

- ✅ **Principle I (DDD)**: User management logic will reside in domain layer (User entity with business invariants), application service (UserManagementService) orchestrates, infrastructure layer handles Keycloak integration
- ✅ **Principle II (PbLF)**: New `user` feature module at `com.bitbi.dfm.user` with nested layers (domain, application, infrastructure, presentation)
- ✅ **Principle III (TDD)**: Contract tests → Integration tests → Implementation → Unit tests workflow enforced
- ✅ **Principle IV (API-First)**: REST endpoints with OpenAPI 3.0 specs in contracts/, admin endpoints at `/api/admin/users/**`
- ✅ **Principle V (Security by Default)**: Keycloak OAuth2 authentication required (ROLE_ADMIN), input validation on all endpoints, password hashing via Keycloak
- ✅ **Principle VI (Database Optimization)**: User table with indexes on email, status columns; Flyway migration for schema changes; transactions for state changes
- ✅ **Principle VII (Observability)**: Structured logging for all admin actions, metrics for user operations (create/lock/unlock/reset counters), audit log persistence

### Frontend Compliance

- ✅ **Principle VIII (FSD Architecture)**: New user management features in `features/user-management/`, pages in `pages/admin/users/`, entities in `entities/user/`
- ✅ **Principle IX (Type Safety)**: TypeScript strict mode, Zod schemas for form validation with `z.infer` for types, no `any` usage
- ✅ **Principle X (React Query)**: All API calls through TanStack Query with query key factories, optimistic updates for lock/unlock operations
- ✅ **Principle XI (TDD Frontend)**: 70% unit tests (Vitest), 20% integration tests (Testing Library), 10% E2E (Playwright) with 80% coverage minimum
- ✅ **Principle XII (Keycloak SSO)**: Admin authentication via existing Keycloak integration (Authorization Code Flow + PKCE)
- ✅ **Principle XIII (Component Composition)**: Auth context from existing app layer, React Query for server state, no prop drilling beyond 2 levels
- ✅ **Principle XIV (Form Validation)**: React Hook Form + Zod for create user form, password reset form with client-side validation
- ✅ **Principle XV (Performance)**: Code splitting for user management routes, virtualization for user list if >100 users, bundle size monitored
- ✅ **Principle XVI (Accessibility)**: WCAG 2.1 AA compliance, semantic HTML, ARIA labels, keyboard navigation

### Development Standards Compliance

- ✅ **Git Workflow**: Feature branch `006-task-the-admin` follows `###-feature-name` pattern (compatible with feature/** convention)
- ✅ **Backend Code Quality**: Java 21 features allowed, Spring Boot conventions, no circular dependencies, DTOs for API responses separate from entities
- ✅ **Frontend Code Quality**: PascalCase components, camelCase hooks (use* prefix), destructured props, named exports, no `any` types
- ✅ **Testing Requirements**: Testcontainers for backend integration, Vitest + Testing Library for frontend, 80% coverage minimum
- ✅ **Security Requirements**: No credentials in code, Keycloak for admin auth, XSS prevention via React escaping, HTTPS only

### Gate Result: ✅ PASSED

No constitutional violations. All principles satisfied by planned architecture.

## Project Structure

### Documentation (this feature)

```
specs/[###-feature]/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```
# Backend (Java/Spring Boot)
src/main/java/com/bitbi/dfm/
├── account/                       # EXISTING: Account aggregate (EXTENDED)
│   ├── domain/
│   │   ├── Account.java          # EXTENDED: Add keycloakUserId field
│   │   └── AccountRepository.java # EXTENDED: Add Keycloak lookup methods
│   ├── application/
│   │   ├── AccountService.java   # EXISTING
│   │   ├── KeycloakAccountSyncService.java  # NEW: Keycloak sync logic
│   │   └── TemporaryPasswordGenerator.java  # NEW: Password generation utility
│   ├── infrastructure/
│   │   ├── JpaAccountRepository.java        # EXISTING: Extend with Keycloak queries
│   │   └── KeycloakAdminClient.java         # NEW: Keycloak REST client wrapper
│   └── presentation/
│       ├── AccountAdminController.java      # EXTENDED: Add lock/unlock/reset endpoints
│       └── dto/
│           ├── CreateAccountRequestDto.java # EXISTING
│           ├── AccountResponseDto.java      # EXTENDED: Add Keycloak fields
│           └── ResetPasswordResponseDto.java # NEW: Password reset response
├── shared/                        # EXISTING: Shared infrastructure
│   ├── config/
│   │   └── KeycloakAdminConfig.java         # NEW: Keycloak client bean
│   ├── security/
│   └── exception/

src/main/resources/
└── db/migration/
    └── V006__extend_accounts_with_keycloak.sql  # NEW: Add keycloak_user_id column

src/test/java/com/bitbi/dfm/account/
├── contract/                      # EXTENDED: Add new endpoint tests
│   └── AccountAdminControllerContractTest.java
├── integration/                   # EXTENDED: Add Keycloak integration tests
│   └── KeycloakAccountSyncIntegrationTest.java # NEW
└── application/                   # NEW: Service layer unit tests
    └── KeycloakAccountSyncServiceTest.java

# Frontend (React/TypeScript)
frontend/src/
├── app/                           # Existing app layer
│   ├── providers/
│   └── router/
├── pages/                         # NEW: Admin user management pages
│   └── admin/
│       └── users/
│           ├── index.ts
│           ├── UsersListPage.tsx
│           ├── UserDetailsPage.tsx
│           └── CreateUserPage.tsx
├── features/                      # NEW: User management features
│   └── user-management/
│       ├── index.ts
│       ├── api/
│       │   ├── userQueries.ts    # TanStack Query hooks
│       │   └── userMutations.ts
│       ├── ui/
│       │   ├── CreateUserForm.tsx
│       │   ├── LockUserButton.tsx
│       │   ├── UnlockUserButton.tsx
│       │   └── ResetPasswordDialog.tsx
│       └── model/
│           ├── userSchemas.ts    # Zod validation schemas
│           └── types.ts          # TypeScript types
├── entities/                      # NEW: User entity
│   └── user/
│       ├── index.ts
│       ├── model/
│       │   └── types.ts          # User domain types
│       └── ui/
│           ├── UserCard.tsx
│           └── UserStatusBadge.tsx
├── shared/
│   ├── api/                       # Existing API client
│   └── ui/                        # Existing UI components
└── widgets/                       # NEW: User management widgets
    └── user-management/
        ├── index.ts
        └── UserListTable.tsx      # TanStack Table implementation

frontend/tests/
├── unit/                          # NEW: Vitest unit tests
│   └── user-management/
├── integration/                   # NEW: Testing Library integration tests
│   └── user-management/
└── e2e/                          # NEW: Playwright E2E tests
    └── user-management.spec.ts
```

**Structure Decision**: Web application architecture selected (Option 2 from template). Backend **extends existing `account` module** (no new user module created - avoiding duplication). Frontend follows Feature-Sliced Design (FSD) with user management distributed across pages/, features/, entities/, and widgets/ layers. This maintains consistency with existing codebase structure and reuses the established Account aggregate as the user entity.

## Complexity Tracking

*Fill ONLY if Constitution Check has violations that must be justified*

No constitutional violations detected. This section intentionally left empty.
