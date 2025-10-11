# Data Model: Security System Separation

**Feature**: 003-separation-of-security
**Date**: 2025-10-10
**Status**: Complete

## Overview

This refactoring **does not introduce new database entities or schema changes**. The data model documentation describes the security configuration entities and their relationships in the Spring Security context.

## Security Configuration Entities

### 1. SecurityFilterChain

**Purpose**: Defines an independent authentication and authorization pipeline for a specific URL pattern

**Attributes**:
- `urlPattern`: String (e.g., `/api/dfc/**`, `/api/admin/**`)
- `order`: Integer (precedence for filter chain matching, lower = higher priority)
- `authenticationMechanism`: Enum { JWT, KEYCLOAK, NONE }
- `sessionManagement`: Enum { STATELESS, STATEFUL }
- `authorizedRoles`: List<String> (e.g., ["ROLE_ADMIN"], or empty for authenticated-only)

**Relationships**:
- **Has many** AuthenticationFilter (e.g., JwtAuthenticationFilter, OAuth2ResourceServerFilter)
- **Has one** AuthenticationEntryPoint (handles 401/403 responses)

**Lifecycle**:
1. **Created**: At Spring application startup via `@Bean` methods in `SecurityConfiguration`
2. **Matched**: On each HTTP request by `FilterChainProxy` based on `urlPattern`
3. **Executed**: Selected filter chain processes authentication and authorization
4. **Destroyed**: On Spring application shutdown

**Validation Rules**:
- `order` values must be unique across all SecurityFilterChain beans
- `urlPattern` patterns should not overlap (most specific pattern should have lowest order)
- JWT filter chains MUST have `sessionManagement = STATELESS`
- Keycloak filter chains MUST include OAuth2ResourceServerFilter

---

### 2. AuthenticationContext

**Purpose**: Represents the authenticated user or client for the current request

**Attributes**:
- `principal`: Object (JwtPrincipal for JWT, OAuth2Principal for Keycloak)
- `credentials`: Object (JWT token string or OAuth2 access token)
- `authorities`: Collection<GrantedAuthority> (e.g., [ROLE_ADMIN], [ROLE_CLIENT])
- `authenticationMethod`: Enum { JWT, KEYCLOAK }
- `authenticated`: Boolean
- `tokenType`: String ("Bearer")

**Relationships**:
- **Stored in** SecurityContextHolder (thread-local storage)
- **Created by** AuthenticationFilter (JwtAuthenticationFilter or OAuth2ResourceServerFilter)
- **Used by** Controllers via `@AuthenticationPrincipal` annotation

**Lifecycle**:
1. **Created**: After successful authentication by the filter chain
2. **Stored**: In SecurityContextHolder for the duration of the request
3. **Accessed**: By controllers via `SecurityContextHolder.getContext().getAuthentication()`
4. **Cleared**: After request completion by `SecurityContextPersistenceFilter`

**Validation Rules**:
- `authenticated` MUST be `true` for requests reaching controllers
- `authenticationMethod` MUST match the SecurityFilterChain that processed the request
- JWT contexts MUST have `principal` containing siteId and accountId claims
- Keycloak contexts MUST have at least one authority in `authorities`

---

### 3. APIEndpoint

**Purpose**: Represents a REST resource requiring authentication

**Attributes**:
- `urlPath`: String (e.g., `/api/dfc/batch/start`, `/api/admin/accounts`)
- `httpMethod`: Enum { GET, POST, PUT, DELETE, PATCH }
- `requiredAuthMethod`: Enum { JWT, KEYCLOAK }
- `requiredRoles`: List<String> (e.g., ["ROLE_ADMIN"], or empty)
- `controller`: String (class name, e.g., "BatchController")

**Relationships**:
- **Mapped to** SecurityFilterChain via URL pattern matching
- **Handled by** Controller method (Spring MVC `@RequestMapping`)

**Lifecycle**:
1. **Registered**: At Spring Boot startup via `@RequestMapping` annotation scanning
2. **Matched**: On each HTTP request by `DispatcherServlet`
3. **Protected**: By SecurityFilterChain before reaching controller
4. **Executed**: Controller method invoked after successful authentication

**Validation Rules**:
- All `/api/dfc/**` endpoints MUST accept JWT authentication only
- All `/api/admin/**` endpoints MUST accept Keycloak authentication only
- POST/PUT/DELETE endpoints MUST be explicitly mapped (no wildcard HTTP methods)

---

## Entity Relationships Diagram

```
┌──────────────────────────┐
│  SecurityFilterChain     │
│  - urlPattern            │◄─────┐
│  - order                 │      │
│  - authenticationMethod  │      │
└─────────┬────────────────┘      │
          │ has many              │ matches
          ▼                       │
┌──────────────────────────┐      │
│  AuthenticationFilter    │      │
│  - filterClass           │      │
│  - priority              │      │
└─────────┬────────────────┘      │
          │ creates               │
          ▼                       │
┌──────────────────────────┐      │
│  AuthenticationContext   │      │
│  - principal             │      │
│  - authorities           │      │
│  - authenticationMethod  │      │
└─────────┬────────────────┘      │
          │ used by               │
          ▼                       │
┌──────────────────────────┐      │
│  APIEndpoint             │──────┘
│  - urlPath               │
│  - requiredAuthMethod    │
│  - requiredRoles         │
└──────────────────────────┘
```

---

## Configuration Mappings

### JWT Filter Chain Configuration

| URL Pattern | Authentication | Session | Roles | Entry Point |
|-------------|----------------|---------|-------|-------------|
| `/api/dfc/batch/**` | JWT | STATELESS | (any authenticated) | JwtAuthenticationEntryPoint |
| `/api/dfc/error/**` | JWT | STATELESS | (any authenticated) | JwtAuthenticationEntryPoint |
| `/api/dfc/upload/**` | JWT | STATELESS | (any authenticated) | JwtAuthenticationEntryPoint |

**Filter Order**: 1 (highest priority)

### Keycloak Filter Chain Configuration

| URL Pattern | Authentication | Session | Roles | Entry Point |
|-------------|----------------|---------|-------|-------------|
| `/api/admin/accounts/**` | OAuth2/Keycloak | STATELESS | ROLE_ADMIN | OAuth2AuthenticationEntryPoint |
| `/api/admin/sites/**` | OAuth2/Keycloak | STATELESS | ROLE_ADMIN | OAuth2AuthenticationEntryPoint |
| `/api/admin/batches/**` | OAuth2/Keycloak | STATELESS | ROLE_ADMIN | OAuth2AuthenticationEntryPoint |

**Filter Order**: 2 (second priority)

### Default Filter Chain Configuration

| URL Pattern | Authentication | Session | Roles | Entry Point |
|-------------|----------------|---------|-------|-------------|
| `/actuator/health` | None | N/A | (public) | N/A |
| `/v3/api-docs/**` | None | N/A | (public) | N/A |
| `/swagger-ui/**` | None | N/A | (public) | N/A |
| `/**` (all others) | DENY | N/A | (denied) | Http403ForbiddenEntryPoint |

**Filter Order**: 3 (lowest priority)

---

## State Transitions

### Authentication Flow States

```
[Unauthenticated Request]
         │
         ▼
[FilterChainProxy matches URL pattern]
         │
         ├──► JWT Filter Chain (/api/dfc/**)
         │    │
         │    ▼
         │   [JwtAuthenticationFilter validates token]
         │    │
         │    ├──► Valid JWT → [Authenticated] → [Controller]
         │    └──► Invalid JWT → [401 Unauthorized]
         │
         └──► Keycloak Filter Chain (/api/admin/**)
              │
              ▼
             [OAuth2ResourceServerFilter validates token]
              │
              ├──► Valid Keycloak + ROLE_ADMIN → [Authenticated] → [Controller]
              ├──► Valid Keycloak - no ROLE_ADMIN → [403 Forbidden]
              └──► Invalid Keycloak → [401 Unauthorized]
```

### Wrong Token Type Flow

```
[JWT Token on /api/admin/**]
         │
         ▼
[Keycloak Filter Chain attempts OAuth2 validation]
         │
         ▼
[JWT is not a valid OAuth2 token]
         │
         ▼
[401 Unauthorized]
         │
         ▼
[AuthenticationAuditLogger logs: tokenType=JWT, endpoint=/api/admin/**, status=401]
```

---

## No Database Schema Changes

**Existing Tables Unchanged**:
- `accounts` - No changes
- `sites` - No changes
- `batches` - No changes
- `uploaded_files` - No changes
- `error_logs` - No changes

**Existing Migrations Unchanged**:
- No new Flyway migrations required
- All authentication logic is in Spring Security configuration, not database

**Data Integrity**:
- No impact on existing data
- No data migration scripts needed
- Rollback requires only code revert (no database rollback)

---

## Security Context Storage

Authentication contexts are stored in:
1. **SecurityContextHolder** (thread-local) - Duration: Single HTTP request
2. **No persistent storage** - Session management is STATELESS for both JWT and Keycloak

This refactoring maintains the existing stateless authentication model.

---

**Phase 1 Data Model Status**: ✅ Complete - Security configuration entities documented, no database changes
