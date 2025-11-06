# Implementation Plan: Auth0 Migration

**Branch**: `011-auth0-migration-migrate` | **Date**: 2025-11-06 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/011-auth0-migration-migrate/spec.md`

## Summary

Migrate Data Forge Middleware from Keycloak to Auth0 for user authentication and authorization. Replace Keycloak Admin Client with Auth0 Management API for backend user management, configure Spring Security OAuth2 Resource Server for JWT validation, and integrate Auth0 React SDK for frontend authentication with Universal Login.

**Key Technical Decisions**:
- Use `com.auth0:auth0:2.26.0` for Management API + Spring OAuth2 Resource Server for JWT validation
- Rename `keycloak_user_id` column to `identity_provider_user_id` (VARCHAR 64) for clean cutover
- Replace temporary passwords with Auth0 password change tickets (24-hour expiry URLs)
- Use Auth0 Actions to inject custom claims (roles, accountId) into JWT tokens
- Frontend uses `@auth0/auth0-react` v2.8.0 with in-memory token storage + refresh tokens
- Two-phase migration: bulk import users without passwords, send password reset links

## Technical Context

**Language/Version**: Java 21 (LTS), TypeScript 5.6, React 19.2
**Primary Dependencies**:
- Backend: `com.auth0:auth0:2.26.0`, Spring Security OAuth2 Resource Server, Spring Boot 3.5.6
- Frontend: `@auth0/auth0-react:2.8.0`, TanStack Query v5, TanStack Router
**Storage**: PostgreSQL 16 (rename `keycloak_user_id` → `identity_provider_user_id` VARCHAR 64)
**Testing**: JUnit 5 + Mockito + Testcontainers (backend), Vitest + React Testing Library (frontend)
**Target Platform**: Linux server (backend), Modern browsers (frontend - Chrome, Firefox, Safari, Edge)
**Project Type**: Web application (Spring Boot backend + React SPA frontend)
**Performance Goals**:
- Auth0 Management API calls: <5s (per FR-NFR-001)
- JWT validation latency: <50ms (per FR-NFR-005)
- Frontend auth flow: <5s from redirect to token receipt (per SC-002)
**Constraints**:
- Auth0 rate limits: 15 req/s (paid tier), 2 req/s (free tier)
- Migration time: <2 hours for 10K users (per FR-NFR-003)
- Zero Keycloak dependencies in production (per SC-005)
**Scale/Scope**:
- User migration: 10K+ existing users from Keycloak
- Admin endpoints: 5 endpoints (create, list, lock, unlock, reset password)
- Frontend routes: 10+ protected routes with Auth0 authentication
- Database schema: 1 column rename + expand, no new tables

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Backend Compliance (Constitution v1.1.1)

✅ **I. Domain-Driven Design (DDD)**: PASS
- Auth0UserId value object with validation
- Account aggregate maintains business invariants
- Domain event (AccountAuth0LinkedEvent) for integration tracking

✅ **II. Package by Layered Feature (PbLF)**: PASS
- Auth0 integration organized in auth module: domain, application, infrastructure, presentation
- Clear API boundaries between account and auth modules

✅ **III. Test-Driven Development (NON-NEGOTIABLE)**: PASS
- Contract tests defined in OpenAPI spec
- Integration tests required for Auth0 Management API calls
- Unit tests for domain logic (Auth0UserId validation, Account.linkIdentityProvider)
- Minimum 80% coverage enforced

✅ **IV. API-First Design**: PASS
- OpenAPI 3.0 specification in contracts/admin-api-auth0.openapi.yaml
- JSON request/response with standardized ErrorResponse
- Versioned endpoint (/api/v1/admin/accounts/with-auth0)

✅ **V. Security by Default**: PASS
- Auth0 OAuth2 JWT validation with Spring Security 6
- ROLE_ADMIN required for admin endpoints
- Input validation via Jakarta Bean Validation (@Valid, @NotBlank, @Email)
- Auth0 Management API credentials stored in environment variables (never in Git)

✅ **VI. Database Optimization**: PASS
- Flyway migration for schema change (keycloak_user_id → identity_provider_user_id)
- Unique index on identity_provider_user_id
- HikariCP connection pooling (already present)

✅ **VII. Observability & Monitoring**: PASS
- Micrometer metrics for Auth0 API calls
- Structured logging with SLF4J/Logback (JSON in production)
- MDC context (accountId, auth0UserId) for correlation
- Performance target: <5s p95 for Management API calls (per NFR-001)

### Frontend Compliance (Constitution v1.1.1)

✅ **VIII. Feature-Sliced Design (FSD Architecture)**: PASS
- Auth0 integration follows FSD layers: shared/lib/auth, entities/user, features/auth, pages/login
- No cross-layer dependencies violations

✅ **IX. Type Safety First (TypeScript Strict Mode)**: PASS
- TypeScript strict mode enabled
- Auth0 user type properly typed with custom claims interface
- No `any` types in Auth0 integration code

✅ **X. React Query for Server State**: PASS
- TanStack Query for API calls (already present)
- Auth0 SDK handles token management (separate from React Query)

✅ **XI. TDD for Frontend (NON-NEGOTIABLE)**: PASS
- Vitest + Testing Library tests required for Auth0 hooks
- Mock Auth0Provider in tests
- 80% coverage minimum

✅ **XII. Keycloak SSO Integration**: ⚠️ SUPERSEDED
- Replaced with Auth0 OAuth 2.0 / OIDC (Authorization Code Flow + PKCE)
- This principle will be updated post-migration to "OAuth 2.0 SSO Integration"

✅ **XIII. Component Composition over Prop Drilling**: PASS
- AuthenticationGuard HOC pattern (no prop drilling)
- useAuth0 hook for authentication state

✅ **XIV. Form Validation with Zod**: N/A
- No forms in Auth0 integration (Auth0 Universal Login handles forms)

✅ **XV. Performance & Bundle Optimization**: PASS
- Auth0 React SDK adds ~50KB gzipped (bundle size: <500KB total)
- Lazy loading for protected routes (React.lazy)

✅ **XVI. Accessibility & Security**: PASS
- Auth0 Universal Login meets WCAG 2.1 AA
- In-memory token storage prevents XSS attacks
- httpOnly cookies for refresh tokens

### Git Workflow Standards

✅ **Branch Naming**: PASS
- Branch: `011-auth0-migration-migrate` (follows feature/* pattern per constitution)

### Constitution Compliance Summary

**Status**: ✅ **ALL GATES PASSED**

No violations. Migration follows all architectural principles for both backend (DDD, PbLF, TDD) and frontend (FSD, TypeScript strict, TDD). Minor update needed post-migration: Principle XII should be renamed from "Keycloak SSO Integration" to "OAuth 2.0 SSO Integration" to reflect Auth0.

## Project Structure

### Documentation (this feature)

```
specs/011-auth0-migration-migrate/
├── plan.md                        # This file (Phase 0-1 complete)
├── research.md                    # Backend Auth0 SDK research
├── research-frontend.md           # Frontend Auth0 React SDK research
├── research-migration-strategy.md # Migration patterns research
├── data-model.md                  # Database schema + domain entities
├── quickstart.md                  # Setup & implementation guide
├── contracts/
│   └── admin-api-auth0.openapi.yaml # OpenAPI 3.0 spec for Auth0 endpoints
└── tasks.md                       # Next: Run /speckit.tasks command
```

### Backend Source Code (Spring Boot - Package by Layered Feature)

```
src/main/java/com/bitbi/dfm/
├── auth/                          # Auth0 integration module
│   ├── config/
│   │   ├── Auth0Properties.java             # @ConfigurationProperties for Auth0 config
│   │   ├── Auth0SecurityConfig.java         # Spring Security OAuth2 Resource Server
│   │   └── AudienceValidator.java           # Custom JWT audience validator
│   ├── domain/
│   │   └── Auth0UserId.java                 # Value object with validation
│   ├── application/
│   │   ├── Auth0TokenProvider.java          # Token caching (24-hour TTL)
│   │   └── Auth0UserManagementService.java  # User CRUD with retry logic
│   └── infrastructure/
│       └── Auth0ManagementApiClient.java    # Wrapper for Auth0 SDK
│
├── account/                       # Account aggregate (UPDATED for Auth0)
│   ├── domain/
│   │   ├── Account.java                     # UPDATED: keycloakUserId → identityProviderUserId
│   │   ├── AccountRepository.java           # UPDATED: query methods renamed
│   │   └── AccountAuth0LinkedEvent.java     # NEW: Domain event
│   ├── application/
│   │   ├── AccountService.java              # UPDATED: Auth0 integration
│   │   └── AccountQueryService.java         # UPDATED: Search with Auth0 filtering
│   ├── presentation/
│   │   ├── AccountAdminController.java      # UPDATED: Auth0 endpoints
│   │   └── dto/
│   │       ├── CreateAccountRequestDto.java  # UPDATED: Add role field
│   │       ├── CreateAccountResponseDto.java # UPDATED: passwordResetLink
│   │       ├── ResetPasswordResponseDto.java # UPDATED: passwordResetLink
│   │       └── AccountWithAuth0Dto.java      # NEW: Auth0-specific fields
│   └── infrastructure/
│       └── JpaAccountRepository.java        # UPDATED: identityProviderUserId queries
│
└── shared/
    ├── exception/
    │   ├── Auth0ServiceUnavailableException.java  # NEW: 503 errors
    │   └── Auth0RateLimitException.java           # NEW: 429 errors
    └── config/
        └── GlobalExceptionHandler.java      # UPDATED: Handle Auth0 exceptions

src/main/resources/
├── db/migration/
│   └── V012__rename_keycloak_to_identity_provider.sql  # NEW: Schema migration
├── application.yml                          # UPDATED: Auth0 configuration
└── application-dev.yml                      # UPDATED: Auth0 dev settings

src/test/java/
├── contract/
│   └── Auth0AdminContractTest.java          # NEW: MockMvc tests for Auth0 endpoints
├── integration/
│   ├── Auth0ManagementApiIntegrationTest.java # NEW: Testcontainers + Mock Auth0
│   └── Auth0JwtValidationIntegrationTest.java # NEW: JWT validation tests
└── unit/
    ├── Auth0UserIdTest.java                 # NEW: Value object validation tests
    └── AccountTest.java                     # UPDATED: Test linkIdentityProvider()
```

### Frontend Source Code (React - Feature-Sliced Design)

```
frontend/src/
├── app/
│   ├── providers/
│   │   └── Auth0Provider.tsx                # NEW: Auth0 provider wrapper
│   └── routes.tsx                           # UPDATED: Protected routes with Auth0
│
├── shared/
│   ├── lib/
│   │   └── auth/
│   │       ├── AuthenticationGuard.tsx      # NEW: HOC for protected routes
│   │       ├── RoleGuard.tsx                # NEW: Role-based route guard
│   │       └── useAuth0Roles.ts             # NEW: Custom hook for role extraction
│   └── ui/
│       └── LoadingSpinner.tsx               # Used in AuthenticationGuard
│
├── entities/
│   └── user/
│       ├── model/
│       │   └── types.ts                     # UPDATED: Add Auth0 custom claims interface
│       └── api/
│           └── userApi.ts                   # UPDATED: Add Authorization header with Auth0 token
│
├── features/
│   └── auth/
│       ├── ui/
│       │   ├── LoginButton.tsx              # NEW: loginWithRedirect()
│       │   ├── LogoutButton.tsx             # NEW: logout()
│       │   └── UserProfile.tsx              # NEW: Display user info from Auth0
│       └── lib/
│           └── useAuth0User.ts              # NEW: Custom hook for user data
│
└── pages/
    ├── login/                               # REMOVE: Keycloak login page
    └── dashboard/
        └── DashboardPage.tsx                # UPDATED: Protected with AuthenticationGuard

frontend/tests/
├── unit/
│   ├── AuthenticationGuard.test.tsx         # NEW: Test HOC behavior
│   └── useAuth0Roles.test.ts                # NEW: Test role extraction
└── integration/
    └── auth-flow.test.tsx                   # NEW: Full auth flow test with mocked Auth0
```

### Database Schema Changes

```sql
-- Existing accounts table (UPDATED)
CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    identity_provider_user_id VARCHAR(64) UNIQUE,  -- RENAMED + EXPANDED from keycloak_user_id (36)
    phone VARCHAR(20),
    company VARCHAR(200),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    max_concurrent_batches INTEGER NOT NULL DEFAULT 5,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Index (UPDATED)
CREATE UNIQUE INDEX idx_accounts_identity_provider_user_id
ON accounts(identity_provider_user_id)
WHERE identity_provider_user_id IS NOT NULL;

-- Existing admin_action_logs table (NO CHANGES)
-- Already captures Auth0 operations via admin_account_id column
```

**Structure Decision**: Web application (Option 2) with backend (Spring Boot) and frontend (React SPA). Backend follows Package by Layered Feature (PbLF) with auth module for Auth0 integration. Frontend follows Feature-Sliced Design (FSD) with Auth0 integration in shared/lib/auth. Database schema requires minimal changes (1 column rename + expand).

## Complexity Tracking

*Fill ONLY if Constitution Check has violations that must be justified*

**Status**: N/A - No constitution violations. All gates passed.
