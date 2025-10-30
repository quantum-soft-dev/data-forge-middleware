# Implementation Plan: Security System Separation

**Branch**: `003-separation-of-security` | **Date**: 2025-10-10 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/003-separation-of-security/spec.md`

## Summary

Refactor the existing dual authentication system to completely separate JWT authentication (for file uploader clients) from Keycloak authentication (for admin UI). This involves:
- Creating separate security filter chains for `/api/dfc/**` (JWT-only) and `/api/admin/**` (Keycloak-only)
- Migrating existing endpoints from `/api/v1/**` to the new URL patterns
- Ensuring test environment mirrors production security configuration
- Maintaining 100% test pass rate throughout refactoring

This is a **breaking change** requiring all clients to update immediately to the new URL patterns.

## Technical Context

**Language/Version**: Java 21 (LTS)
**Primary Dependencies**: Spring Boot 3.5.6, Spring Security 6, Spring Data JPA
**Storage**: PostgreSQL 16 (existing, no schema changes required)
**Testing**: JUnit 5, Mockito, Testcontainers (PostgreSQL + LocalStack S3)
**Target Platform**: Linux server (Docker containers)
**Project Type**: Backend web application (Spring Boot REST API)
**Performance Goals**: <100ms authentication processing time for both JWT and Keycloak
**Constraints**:
- 100% test pass rate required (389 tests must pass)
- Zero downtime deployment not possible (breaking change)
- Existing JWT and Keycloak infrastructure must be preserved
- Test environment must mirror production security behavior
**Scale/Scope**:
- Refactoring ~11 controller classes (batch, error-log, file-upload, admin controllers)
- 2 security configuration classes (production + test)
- Estimated ~5-7 integration tests need updates
- No database schema changes

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### ✅ Passes

- **Principle I (DDD)**: Refactoring authentication infrastructure layer, no domain logic changes
- **Principle II (PbLF)**: Changes isolated to `shared/config` and controller presentation layers
- **Principle III (TDD)**: Test-first approach mandated - update tests before implementation
- **Principle V (Security by Default)**: Strengthens security by eliminating dual-auth complexity
- **Principle VII (Observability)**: Enhanced auth failure logging (FR-011)
- **Backend Testing**: All existing Testcontainers-based integration tests preserved
- **Git Workflow**: Feature branch `003-separation-of-security` follows `feature/**` convention

### ⚠️ Requires Justification

- **Breaking Change**: FR-010 mandates breaking change with no migration period
  - **Why Needed**: Dual authentication system creates security ambiguity and test flakiness (9/11 tests failing due to TestSecurityConfig divergence)
  - **Simpler Alternative Rejected**: Gradual migration would require maintaining three security zones (`/api/v1/**` dual-auth, `/api/dfc/**` JWT-only, `/api/admin/**` Keycloak-only) increasing complexity and maintenance burden

### 🔄 Re-check After Phase 1

✅ **Test Environment Parity (FR-008)**: research.md Decision 3 documents TestSecurityConfig refactoring to use identical SecurityFilterChain structure as production. This addresses the root cause of 9/11 test failures.

✅ **No Additional Dependencies**: All required technologies (Spring Boot 3.5.6, Spring Security 6) already present. No new dependencies introduced.

✅ **Domain Boundaries Preserved**: URL pattern migration affects only presentation layer (@RequestMapping annotations). Domain logic (batch, error, upload, account, site aggregates) unchanged. Controller-to-service mappings remain identical.

## Project Structure

### Documentation (this feature)

```
specs/003-separation-of-security/
├── plan.md              # This file
├── research.md          # Phase 0: Architecture decisions
├── data-model.md        # Phase 1: Security entities (minimal - no DB changes)
├── quickstart.md        # Phase 1: Developer guide for refactoring
├── contracts/           # Phase 1: Updated OpenAPI specs for new URL patterns
│   ├── jwt-endpoints.yaml
│   └── keycloak-endpoints.yaml
└── tasks.md             # Phase 2: Task breakdown (created by /speckit.tasks)
```

### Source Code (repository root)

```
src/main/java/com/bitbi/dfm/
├── shared/
│   ├── config/
│   │   ├── SecurityConfiguration.java          # MODIFY: Separate filter chains
│   │   └── ActuatorConfiguration.java          # REVIEW: May need CORS updates
│   └── auth/
│       ├── DualAuthenticationFilter.java       # REMOVE: No longer needed
│       └── AuthenticationAuditLogger.java      # MODIFY: Enhanced logging
│
├── batch/presentation/
│   └── BatchController.java                    # MODIFY: Change @RequestMapping to /api/dfc/batch
│
├── error/presentation/
│   └── ErrorLogController.java                 # MODIFY: Change @RequestMapping to /api/dfc/error
│
├── upload/presentation/
│   └── FileUploadController.java               # MODIFY: Change @RequestMapping to /api/dfc/upload
│
├── account/presentation/
│   └── AccountAdminController.java             # VERIFY: Already uses /admin/accounts
│
└── site/presentation/
    └── SiteAdminController.java                # VERIFY: Already uses /admin/sites

src/test/java/com/bitbi/dfm/
├── config/
│   └── TestSecurityConfig.java                 # REFACTOR: Mirror production SecurityConfiguration
│
└── integration/
    ├── AdminEndpointsIntegrationTest.java      # UPDATE: Use new URL patterns
    ├── DualAuthenticationIntegrationTest.java  # UPDATE: Test JWT-only behavior
    ├── JwtOnlyWriteIntegrationTest.java        # UPDATE: Use /api/dfc/** URLs
    └── [other integration tests]               # UPDATE: As needed
```

**Structure Decision**: This is an existing monolithic Spring Boot backend application. The refactoring focuses on the security configuration layer (`shared/config`) and controller URL mappings. No new modules or packages are required - only modifications to existing presentation and infrastructure layers.

## Complexity Tracking

*No violations requiring justification - Constitution Check passes with documented breaking change rationale.*

## Phase 0: Outline & Research

**Status**: ✅ Complete

All research decisions finalized:
1. ✅ Spring Security filter chain architecture: Separate SecurityFilterChain beans (not AuthenticationManagerResolver)
2. ✅ URL pattern migration strategy: Direct cutover with breaking change (no dual URL support)
3. ✅ Test environment security configuration: Refactor TestSecurityConfig to mirror production
4. ✅ Dual token detection removal: Remove DualAuthenticationFilter entirely
5. ✅ URL pattern conventions: Use `/api/dfc/**` for Data Forge Client endpoints

See [research.md](./research.md) for detailed findings and rationale.

## Phase 1: Design & Contracts

**Status**: ✅ Complete
**Prerequisites**: Phase 0 research complete ✓

Generated artifacts:
1. ✅ **data-model.md**: Security configuration entities (SecurityFilterChain, AuthenticationContext, APIEndpoint)
2. ✅ **contracts/jwt-endpoints.yaml**: OpenAPI 3.0 spec for 8 JWT endpoints
3. ✅ **contracts/keycloak-endpoints.yaml**: OpenAPI 3.0 spec for 6 admin endpoints
4. ✅ **quickstart.md**: Complete TDD workflow guide with testing checklist and rollback plan
5. ✅ **Agent context updated**: CLAUDE.md updated with feature-specific technologies

## Phase 2: Task Generation

**Status**: Not started (requires /speckit.tasks command)

Task breakdown will be generated after Phase 1 completion using contract tests derived from the OpenAPI specifications.
