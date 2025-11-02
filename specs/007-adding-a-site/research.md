# Research & Technical Decisions

**Feature**: Site Management for Users and Admins
**Date**: 2025-10-30
**Status**: Complete

## Overview

This document captures research findings and technical decisions made during the planning phase for implementing site management functionality. All decisions align with the project constitution and extend existing architectural patterns.

## Research Areas

### 1. Password Generation Strategy

**Decision**: Implement client-side and server-side password generators with matching algorithm

**Rationale**:
- Client-side generator provides instant UX feedback without API round-trip (SC-001: complete within 2 minutes)
- Server-side generator needed for admin API creating sites on behalf of users
- Matching algorithms ensure consistency between user-generated and admin-generated passwords
- Use SecureRandom (Java) and crypto.getRandomValues (JS) for cryptographic strength

**Alternatives Considered**:
- **Server-only generation**: Rejected due to network latency impacting UX. Users expect instant password generation.
- **Third-party library (passay, zxcvbn)**: Rejected as overkill for simple 8-12 character alphanumeric generation. Adds unnecessary dependency.
- **Pre-generated password pool**: Rejected due to security concerns (predictability) and complexity of pool management.

**Implementation Details**:
- Character set: A-Z, a-z, 0-9 (62 characters)
- Length: Random between 8-12 characters
- Java: Use `SecureRandom` with character array mapping
- TypeScript: Use `crypto.getRandomValues()` for browser-native cryptographic randomness
- No special characters to avoid site client compatibility issues (per clarifications: "letters and numbers 8-12")

---

### 2. Site Identifier Composition

**Decision**: Use composite unique identifier pattern: `accountId + "_" + domain`

**Rationale**:
- Guarantees global uniqueness across all sites in the system (clarification answer from spec)
- Allows same domain name across different accounts (e.g., both User A and User B can monitor "example.com")
- Simple string concatenation, no complex UUID generation needed
- Deterministic: given accountId and domain, identifier is reproducible

**Alternatives Considered**:
- **UUID-based identifier**: Rejected because it doesn't encode business meaning. Composite key provides natural uniqueness and traceability.
- **Domain as primary key**: Rejected because it prevents multiple accounts from monitoring the same domain.
- **Sequence-based ID**: Rejected because it doesn't guarantee uniqueness in distributed systems and lacks business context.

**Implementation Details**:
- Backend: Generate identifier in `Site` entity constructor or domain service
- Database: Store in VARCHAR(300) column (accountId UUID 36 chars + "_" + domain 255 chars max)
- Add unique constraint on identifier column
- Index on identifier for fast lookups

---

### 3. Audit Logging Architecture

**Decision**: Create separate `AdminActionLog` aggregate with its own repository

**Rationale**:
- Audit logs are a distinct bounded context from Site management (DDD Principle I)
- Prevents Site entity from becoming bloated with audit concerns (Single Responsibility)
- Allows independent querying and retention policies for audit data
- Supports future expansion (admin actions on other entities beyond sites)

**Alternatives Considered**:
- **Embed audit trail in Site entity**: Rejected due to SRP violation. Site entity should focus on domain logic, not audit history.
- **Use Spring Data Envers**: Rejected as heavyweight for simple audit needs. Envers tracks all entity changes; we only need explicit admin actions.
- **Generic event logging via domain events**: Considered but rejected for complexity. Direct repository writes are simpler for this use case.

**Implementation Details**:
- Table: `admin_action_logs` with columns: `id`, `action_type`, `target_account_id`, `target_site_id`, `admin_account_id`, `ip_address`, `user_agent`, `timestamp`, `success`
- Repository: `AdminActionLogRepository` in `adminactionlog/` module
- Action types: `CREATE_SITE`, `DEACTIVATE_SITE`, `ACTIVATE_SITE`, `DELETE_SITE`
- Logged after successful operation (not speculative)
- Flyway migration: V008__add_admin_action_logs.sql

---

### 4. Frontend State Management Pattern

**Decision**: TanStack Query for all server state, no local state beyond form inputs

**Rationale**:
- Aligns with Constitution Principle X (React Query for Server State)
- Eliminates need for Redux/Zustand for site data (server state belongs in React Query cache)
- Automatic cache invalidation after mutations (create/update/delete sites)
- Built-in loading/error states reduce boilerplate

**Alternatives Considered**:
- **Redux Toolkit**: Rejected because sites are pure server state, not application state. React Query handles server state better with less boilerplate.
- **Zustand + manual fetching**: Rejected due to lack of built-in caching, refetching, and optimistic updates.
- **Local state with useState**: Rejected because it doesn't handle cache invalidation or background refetching.

**Implementation Details**:
- Query keys: `['sites', accountId]` for user sites, `['admin', 'sites', accountId]` for admin view
- Mutations: `useCreateSite`, `useUpdateSiteStatus`, `useDeleteSite` with automatic invalidation
- Optimistic updates for status changes (activate/deactivate) to provide instant feedback
- Stale time: 30 seconds (sites don't change frequently)
- Cache time: 5 minutes (keep data for quick navigation back)

---

### 5. Form Validation Strategy

**Decision**: Zod schemas with React Hook Form integration

**Rationale**:
- Constitution Principle XIV mandates React Hook Form + Zod
- Type-safe validation: Zod schema generates TypeScript types via `z.infer<typeof schema>`
- Client-side validation prevents unnecessary API calls (improves SC-003: 95% success rate)
- Server-side validation as final gate (never trust client)

**Alternatives Considered**:
- **Yup validation**: Rejected because Zod has better TypeScript integration and is lighter weight.
- **Manual validation logic**: Rejected due to lack of type safety and high maintenance burden.
- **Server-only validation**: Rejected because it degrades UX with unnecessary round-trips for obvious errors.

**Implementation Details**:
- Schema:frontend/src/features/site-crud/model/schemas.ts
  ```typescript
  export const CreateSiteFormSchema = z.object({
    domain: z.string()
      .min(3, "Domain must be at least 3 characters")
      .max(255, "Domain too long")
      .regex(/^[a-z0-9.-]+$/, "Domain can only contain lowercase letters, numbers, dots, and hyphens"),
    password: z.string()
      .min(8, "Password must be at least 8 characters")
  });
  ```
- Backend DTO validation uses Jakarta Bean Validation (@NotBlank, @Size, @Pattern) matching Zod rules

---

### 6. Site List Rendering Strategy

**Decision**: Standard rendering for ≤50 sites, recommend virtualization if >100 sites in future

**Rationale**:
- SC-007 targets 50 sites maximum with <2 second load time
- Virtualization adds complexity and is unnecessary for small lists
- shadcn/ui Table component handles 50 rows efficiently
- If user needs grow beyond 100 sites, switch to TanStack Table with virtualization

**Alternatives Considered**:
- **Virtualization from start**: Rejected as premature optimization. Adds complexity for no current benefit.
- **Pagination**: Rejected based on clarification ("no pagination needed for initial release"). Virtualization is better UX than pagination for this scale.
- **Infinite scroll**: Rejected because sites list is finite and small. Pagination or virtualization are more predictable patterns.

**Implementation Details**:
- Use shadcn/ui Table component with array.map() for rendering rows
- Sort by `createdAt DESC` (per clarification: newest first)
- No pagination controls in MVP
- Monitor performance metrics; if load time exceeds 2s with >50 sites, add virtualization in future iteration

---

### 7. Keycloak Role-Based Access Control

**Decision**: Use existing Keycloak integration, no custom role logic in frontend

**Rationale**:
- Constitution Principle XII mandates Keycloak SSO
- Backend already configured with Keycloak OAuth2 Resource Server (CLAUDE.md)
- Roles embedded in JWT access token (`realm_access.roles` claim)
- Frontend checks `ROLE_ADMIN` for admin routes, no custom auth logic needed

**Alternatives Considered**:
- **Custom roles table in PostgreSQL**: Rejected because Keycloak is the source of truth for auth. Duplicating roles creates sync issues.
- **Permission-based access (RBAC vs ABAC)**: Rejected as overkill. Simple role check (admin vs user) is sufficient for this feature.

**Implementation Details**:
- Backend: `@PreAuthorize("hasRole('ADMIN')")` on admin endpoints
- Frontend: Check `keycloak.hasRealmRole('ADMIN')` for route guards
- Admin routes: `/admin/users/:accountId/sites`
- User routes: `/account/sites`
- No hardcoded role checks in business logic; rely on Spring Security and Keycloak SDK

---

### 8. Soft Delete vs Hard Delete

**Decision**: Soft delete (isActive flag) for sites, preserve historical data

**Rationale**:
- FR-021 mandates soft delete to preserve audit trails
- Existing `sites` table already has `is_active` column (CLAUDE.md)
- Preserves batch and upload history references (foreign key integrity)
- Enables site reactivation if user deletes by mistake

**Alternatives Considered**:
- **Hard delete with orphan cleanup**: Rejected due to data integrity concerns. Deleting site would cascade delete batches, losing history.
- **Archive table pattern**: Rejected as unnecessary complexity. Soft delete achieves same goal with single table.

**Implementation Details**:
- Update `Site.isActive = false` instead of deleting row
- Filter queries: `WHERE is_active = true` for active sites list
- Admin audit log records DELETE_SITE action even though it's soft delete
- Batch and upload records retain foreign key to site ID (no cascade delete)

---

## Technology Stack Confirmation

### Backend
- **Java 21**: LTS version, existing project standard
- **Spring Boot 3.5.6**: Existing version, no upgrade needed
- **Spring Data JPA**: Existing ORM, extend repositories
- **Flyway 11**: Existing migration tool, add V008 migration
- **PostgreSQL 16**: Existing database, no changes

### Frontend
- **React 18.3**: Existing version from constitution
- **TypeScript 5.6**: Existing version, strict mode enabled
- **Vite 5.4**: Existing build tool
- **TanStack Query v5**: Existing state management (React Query)
- **TanStack Router**: Existing routing (type-safe)
- **shadcn/ui**: Existing component library (Radix UI + Tailwind)
- **React Hook Form**: Existing form library
- **Zod**: Existing validation library
- **Vitest**: Existing test runner
- **React Testing Library**: Existing component testing
- **Playwright**: Existing E2E testing

**Decision**: No new dependencies required. All necessary libraries already in project.

---

## Performance Considerations

### Backend Performance
- **Database queries**: Add index on `sites.account_id` and `sites.is_active` for fast filtering
- **Audit log writes**: Async logging to avoid blocking API response (use `@Async` annotation)
- **Connection pooling**: Existing HikariCP configuration sufficient (no changes needed)
- **Target**: <1000ms p95 latency (Constitution Principle VII)

### Frontend Performance
- **Bundle size**: Site management adds ~30KB (forms + table), well within 500KB budget
- **Code splitting**: Lazy load site management page with React.lazy()
- **API caching**: TanStack Query caches site list for 5 minutes
- **Optimistic updates**: Activate/deactivate shows immediate feedback before API confirms
- **Target**: <2 second initial load for 50 sites (SC-007)

---

## Security Considerations

### Backend Security
- **Authentication**: Keycloak JWT validation on all endpoints (existing Spring Security config)
- **Authorization**: `@PreAuthorize("hasRole('ADMIN')")` for admin endpoints
- **Input validation**: Jakarta Bean Validation on DTOs (@NotBlank, @Size, @Pattern)
- **Password hashing**: bcrypt for site passwords (existing Site entity logic)
- **SQL injection**: Prevented by JPA parameterized queries

### Frontend Security
- **XSS prevention**: React automatic escaping (no dangerouslySetInnerHTML used)
- **CSRF**: Not needed for stateless JWT auth (no cookies for auth tokens)
- **Token storage**: Keycloak SDK handles token storage (follows Constitution Principle XII)
- **Role checks**: ROLE_ADMIN verified server-side; frontend checks are UX convenience only

---

## Testing Strategy

### Backend Testing
1. **Contract tests**: MockMvc tests for each endpoint (HTTP method, path, request/response structure)
2. **Integration tests**: Testcontainers with PostgreSQL, full request-to-database flow
3. **Unit tests**: Mockito for SiteService methods (password generation, validation logic)
4. **Coverage target**: ≥80% overall, ≥95% for SiteService critical paths

### Frontend Testing
1. **Unit tests (70%)**: Vitest for utilities (password generator), hooks (TanStack Query hooks), Zod schemas
2. **Integration tests (20%)**: Testing Library for forms, site list rendering, user interactions
3. **E2E tests (10%)**: Playwright for critical paths (create site, delete site, admin creates site for user)
4. **Coverage target**: ≥80% overall, 100% for password generator utility

---

## Open Questions Resolved

All technical unknowns from plan.md Technical Context have been researched and resolved:

- ✅ Password generation: Client-side + server-side with matching algorithm
- ✅ Site identifier: Composite key (accountId + "_" + domain)
- ✅ Audit logging: Separate AdminActionLog aggregate
- ✅ Frontend state: TanStack Query, no Redux/Zustand needed
- ✅ Form validation: Zod + React Hook Form
- ✅ Site list rendering: Standard rendering (no virtualization for ≤50 sites)
- ✅ Access control: Existing Keycloak integration, ROLE_ADMIN checks
- ✅ Soft delete: Use existing is_active flag

No additional research required. Ready to proceed to Phase 1 (Design & Contracts).

---

## References

- Constitution: `/Users/boris/projects/bit-bi/data-forge-middleware/.specify/memory/constitution.md` (v1.1.1)
- Feature Spec: `/Users/boris/projects/bit-bi/data-forge-middleware/specs/007-adding-a-site/spec.md`
- CLAUDE.md: `/Users/boris/projects/bit-bi/data-forge-middleware/CLAUDE.md` (existing architecture)
- Spring Security docs: https://spring.io/guides/topicals/spring-security-architecture
- TanStack Query docs: https://tanstack.com/query/latest
- Zod docs: https://zod.dev/
