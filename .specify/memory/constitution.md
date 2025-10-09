<!--
SYNC IMPACT REPORT
==================
Version Change: 1.0.0 → 1.1.0

Type: MINOR - New section added (Frontend/UI Principles)

Modified Sections:
- None (existing backend principles unchanged)

Added Sections:
- Section "Frontend Core Principles" (Principles VIII-XVI)
  * VIII. Feature-Sliced Design (FSD Architecture)
  * IX. Type Safety First (TypeScript Strict Mode)
  * X. React Query for Server State
  * XI. TDD for Frontend (70% Unit, 20% Integration, 10% E2E)
  * XII. Keycloak SSO Integration
  * XIII. Component Composition over Prop Drilling
  * XIV. Form Validation with Zod
  * XV. Performance & Bundle Optimization
  * XVI. Accessibility & Security
- Section "Frontend Development Standards"
- Section "Frontend Testing Requirements"
- Section "Frontend Code Quality Requirements"

Removed Sections:
- None

Templates Requiring Updates:
✅ .specify/memory/constitution.md - Updated
⚠️ .specify/templates/plan-template.md - Review: Add UI/Frontend planning sections
⚠️ .specify/templates/spec-template.md - Review: Add UI component specifications
⚠️ .specify/templates/tasks-template.md - Review: Add frontend task categories (UI, E2E tests)

Follow-up TODOs:
- Consider adding UI-specific quickstart scenarios to template
- Evaluate if frontend and backend should have separate constitution files for clarity
- Add ADR template reference for frontend architecture decisions

Bump Rationale:
MINOR version increment justified because:
1. New principles added (VIII-XVI for frontend)
2. No changes to existing backend principles (I-VII remain unchanged)
3. Backward compatible - backend development unaffected
4. Material expansion of governance scope to cover full-stack development
-->

# Data Forge Middleware Constitution

## Core Principles (Backend)

### I. Domain-Driven Design (DDD)
All domain logic resides in dedicated domain layer classes (entities, aggregates, value objects, domain services). Entities enforce business invariants through constructors and methods. Application services orchestrate workflows; domain services handle multi-entity logic. Infrastructure layer never contains business logic.

### II. Package by Layered Feature (PbLF)
Code is organized by feature modules (auth, batch, admin) with nested layers (domain, application, infrastructure, presentation). Each feature is self-contained with clear API boundaries. Shared infrastructure (config, security, monitoring) lives in common packages.

### III. Test-Driven Development (NON-NEGOTIABLE)
TDD mandatory for all features: Contract tests → Integration tests → Implementation → Unit tests. Red-Green-Refactor cycle strictly enforced. Tests must be written and approved by user before implementation begins. Minimum 80% code coverage required.

### IV. API-First Design
All functionality exposed via RESTful APIs with OpenAPI 3.0 specifications. Contract tests validate API behavior before implementation. JSON request/response format with standardized error responses. Versioned endpoints (/api/v1/).

### V. Security by Default
JWT authentication required for client APIs (24-hour expiration). Keycloak OAuth2/OIDC for admin endpoints with ROLE_ADMIN. Input validation on all endpoints. Sensitive data (client secrets) hashed with bcrypt. HTTPS required in production.

### VI. Database Optimization
PostgreSQL 16 with table partitioning for high-volume tables (error_logs by month). Indexes on all foreign keys and query columns. Flyway migrations for schema versioning. Connection pooling with HikariCP. Transactions for state changes.

### VII. Observability & Monitoring
Structured logging with SLF4J/Logback (JSON format in production). Metrics exposed via Micrometer/Prometheus. Health checks at /actuator/health. Error logs persisted with metadata (JSONB). Performance target: <1000ms p95 latency.

## Frontend Core Principles

### VIII. Feature-Sliced Design (FSD Architecture)
All frontend code MUST follow FSD methodology with strict layer separation: app → pages → widgets → features → entities → shared. Bottom-up imports are PROHIBITED. Each module exposes public API via index.ts. One file = one responsibility. Cross-layer dependencies flow downward only.

**Rationale**: FSD prevents architectural drift, enables parallel team work, and enforces clear module boundaries that scale with codebase growth.

### IX. Type Safety First (TypeScript Strict Mode)
TypeScript strict mode MUST be enabled. Avoid `any` - use `unknown` with type guards instead. All props, hooks, and utilities MUST have explicit types. Form types derived from Zod schemas via `z.infer`. Interfaces for objects, type aliases for unions/utilities.

**Rationale**: Type safety catches errors at compile time, improves IDE autocomplete, serves as living documentation, and prevents runtime type errors.

### X. React Query for Server State
All API calls MUST go through TanStack Query (React Query). No direct fetch/axios calls in components. Use query key factories for hierarchical caching. Implement optimistic updates for write operations. Configure staleTime and cacheTime per use case.

**Rationale**: React Query provides automatic caching, background refetching, request deduplication, and server state synchronization that would require hundreds of lines if implemented manually.

### XI. TDD for Frontend (NON-NEGOTIABLE)
Frontend TDD follows testing pyramid: 70% unit tests (Vitest), 20% integration tests (Testing Library), 10% E2E tests (Playwright). Write tests BEFORE implementation. Test behavior, not implementation details. Minimum 80% coverage, 95% for critical paths, 100% for utilities.

**Rationale**: Frontend bugs are expensive to debug in production. TDD catches UI regressions, validates user flows, and ensures refactoring safety.

### XII. Keycloak SSO Integration
Authentication MUST use Keycloak OAuth 2.0 / OpenID Connect with Authorization Code Flow + PKCE. Tokens stored in memory or httpOnly cookies (NEVER localStorage for sensitive tokens). Automatic token refresh via refresh token rotation. Role-based access control via Keycloak realm roles. Single sign-out through Keycloak.

**Rationale**: Keycloak provides enterprise-grade SSO, eliminates custom auth code, centralizes user management, and ensures security best practices.

### XIII. Component Composition over Prop Drilling
Avoid passing props through more than 2 levels. Use React Context for deeply nested shared state (theme, auth). Use React Query for server state. Prefer composition patterns (children props, render props) over prop drilling. Custom hooks for reusable logic.

**Rationale**: Prop drilling creates brittle component trees, makes refactoring difficult, and couples components unnecessarily.

### XIV. Form Validation with Zod
All forms MUST use React Hook Form + Zod for validation. Define one Zod schema per form. Form types inferred from schemas. Client-side validation before submission. Server-side error mapping to form fields. Accessible error messages linked to inputs.

**Rationale**: Zod provides type-safe validation schemas that serve as both runtime validators and TypeScript type sources, ensuring validation logic matches type definitions.

### XV. Performance & Bundle Optimization
Bundle size MUST stay under 500KB (gzipped). Use React.lazy for route-based code splitting. Implement virtualization for lists >100 items (TanStack Table). Debounce search inputs. Prefetch critical data. Monitor Lighthouse score ≥90.

**Rationale**: Performance directly impacts user experience and SEO. Large bundles cause slow page loads, especially on mobile networks.

### XVI. Accessibility & Security
All components MUST meet WCAG 2.1 AA standards. Use semantic HTML. Provide ARIA labels for interactive elements. Keyboard navigation support. XSS prevention via React's automatic escaping. CSP headers configured. No inline scripts.

**Rationale**: Accessibility is a legal requirement in many jurisdictions and improves UX for all users. Security vulnerabilities like XSS can compromise user data and trust.

## Development Standards

### Backend Code Quality Requirements
- Java 21 language features allowed (records, pattern matching, text blocks)
- Spring Boot 3.5.6 coding conventions followed
- No circular dependencies between feature modules
- Repository interfaces define explicit query methods (no magic methods)
- DTOs for API request/response (separate from domain entities)

### Frontend Code Quality Requirements
- **Naming Conventions**:
  * Components: PascalCase (Button, UserCard)
  * Hooks: camelCase with `use` prefix (useAuth, useDebounce)
  * Utils: camelCase (formatDate, validateEmail)
  * Types: PascalCase (User, ProductFormData)
  * Constants: UPPER_SNAKE_CASE (API_URL, MAX_RETRIES)
- **Component Rules**:
  * Functional components only (no classes)
  * Props typed via interface
  * Named exports (not default)
  * Destructure props in parameters
- **Anti-Patterns Prohibited**:
  * No `any` types (use `unknown` + type guards)
  * No array index as key (use stable unique ID)
  * No direct state mutation (immutable updates only)
  * No console.log in production code
  * No prop drilling beyond 2 levels

### Backend Testing Requirements
- Testcontainers for integration tests (PostgreSQL + LocalStack S3)
- Contract tests use MockMvc with JSON assertions
- Unit tests use Mockito for dependencies
- Integration tests verify full request-to-database flows
- Performance tests validate <1000ms p95 latency requirement

### Frontend Testing Requirements
- **Unit Tests (70%)**:
  * Tools: Vitest + Testing Library
  * Coverage: All utilities (100%), hooks, components (logic not styles), Zod schemas
  * Rules: Test behavior not implementation, use Testing Library queries, mock only external deps
- **Integration Tests (20%)**:
  * Test: Component interactions, forms with validation, mocked API calls, navigation flows
  * Approach: Complete user flows, mocked responses, FSD layer integration
- **E2E Tests (10%)**:
  * Tools: Playwright
  * Coverage: Critical user journeys, auth flow, core business processes
- **Coverage Targets**:
  * Overall: ≥80%
  * Critical modules: ≥95%
  * Utilities/helpers: 100%

### Security Requirements
- **Backend**: No credentials in source code, client secrets hashed, JWT with site context, Keycloak for admin, S3 IAM roles
- **Frontend**: XSS prevention via React escaping, tokens in httpOnly cookies, HTTPS only, CSP headers, CORS config, centralized 401/403 handling

## Implementation Workflow

### Backend Task Execution Process
1. Read contract test specification from tasks.md
2. Write failing contract/integration test
3. Get user approval for test coverage
4. Implement minimal code to pass tests (domain → infrastructure → application → presentation)
5. Refactor while keeping tests green
6. Verify 80% coverage threshold met
7. Mark task completed in tasks.md

### Frontend Task Execution Process (TDD)
1. Create feature branch
2. Write tests first (RED phase) - watch mode
3. Implement minimal code (GREEN phase)
4. Refactor for clarity (REFACTOR phase)
5. Verify coverage ≥80%
6. Commit with conventional commit message
7. Push and create PR

### Pull Request Requirements
- **Backend**: All tests passing, coverage ≥80%, Flyway migration if schema changed, API contract updated, constitution compliance
- **Frontend**: All tests passing (unit + integration + E2E for critical paths), coverage ≥80%, TypeScript strict mode passing, ESLint 0 errors, bundle size <500KB, accessibility checked (a11y), tested on major browsers
- **Both**: Code follows FSD/PbLF architecture, no console.log, error handling present, documentation updated

## Technology Stack

### Backend
- **Runtime**: Java 21 (LTS)
- **Framework**: Spring Boot 3.5.6
- **Security**: Spring Security (JWT + OAuth2 Resource Server / Keycloak)
- **Data**: Spring Data JPA, PostgreSQL 16, Flyway 11, HikariCP
- **Storage**: AWS SDK v2 (S3)
- **Observability**: Micrometer, Logback + Logstash Encoder, SpringDoc OpenAPI
- **Testing**: JUnit 5, Mockito, Testcontainers

### Frontend
- **Runtime**: React 19.2, TypeScript
- **Build**: Vite
- **Architecture**: Feature-Sliced Design (FSD)
- **State**: TanStack Query (React Query)
- **UI**: shadcn/ui, Tailwind CSS, Sonner (toasts)
- **Routing**: TanStack Router (type-safe)
- **Forms**: React Hook Form + Zod
- **Tables**: TanStack Table
- **Auth**: Keycloak (OAuth 2.0 / OIDC + PKCE)
- **Testing**: Vitest, Testing Library, Playwright

## Governance

This constitution supersedes all other development practices. Amendments require documentation in this file with version increment and ratification date. All PRs must verify constitutional compliance during review. Complexity must be justified against business requirements. Use CLAUDE.md for runtime development context.

### Amendment Process
1. Propose amendment with rationale
2. Assess version bump type (MAJOR / MINOR / PATCH)
3. Update constitution with new version and amendment date
4. Propagate changes to dependent templates (plan, spec, tasks)
5. Update CLAUDE.md if runtime guidance affected
6. Commit with message: `docs: amend constitution to vX.Y.Z (<summary>)`

### Versioning Policy
- **MAJOR**: Breaking changes to principles (removal, redefinition, incompatible governance)
- **MINOR**: New principles added, material expansion of existing guidance
- **PATCH**: Clarifications, wording improvements, typo fixes, non-semantic refinements

### Compliance Review
All PRs MUST verify:
- [ ] Code follows architectural principles (DDD/PbLF for backend, FSD for frontend)
- [ ] TDD process followed (tests written first)
- [ ] Coverage thresholds met (≥80% overall, ≥95% critical, 100% utilities)
- [ ] Security requirements satisfied (auth, validation, no secrets in code)
- [ ] Performance targets met (backend <1000ms p95, frontend bundle <500KB)
- [ ] Documentation updated (README, ADR if applicable, code comments for complex logic)

**Version**: 1.1.0 | **Ratified**: 2025-10-06 | **Last Amended**: 2025-10-09
