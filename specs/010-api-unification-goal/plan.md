# Implementation Plan: API Unification

**Branch**: `010-api-unification-goal` | **Date**: 2025-11-05 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/010-api-unification-goal/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

This feature unifies the API endpoint structure into two distinct, consistently-versioned APIs:
- **Device API** (`/api/v1/device/*`): For client devices (IoT devices, mobile apps, data collection clients) using Custom JWT authentication
- **UI/Admin API** (`/api/v1/*`): For web interface (admin dashboard, user portal) using Keycloak OAuth2 authentication

**Primary Requirement**: Restructure existing API endpoints without changing business logic, ensuring proper security filter chain routing, and providing 100% functional parity with old endpoints.

**Technical Approach**:
1. Create new controller classes under `/device/presentation` and update existing admin controllers to new paths
2. Define `ApiRoutes.java` constant file for centralized path management
3. Configure dual security filter chains with explicit precedence (`@Order`) for correct authentication routing
4. Update OpenAPI documentation to separate Device API and UI/Admin API with distinct tags
5. Delete obsolete controllers after migration verification
6. Implement 410 Gone responses for old endpoint paths with migration guidance

## Technical Context

**Language/Version**: Java 21 (LTS)
**Primary Dependencies**: Spring Boot 3.5.6, Spring Security 6, SpringDoc OpenAPI 3
**Storage**: N/A (refactoring only - no schema changes)
**Testing**: JUnit 5, Mockito, Testcontainers (PostgreSQL + LocalStack S3), MockMvc
**Target Platform**: Linux server (Spring Boot microservice)
**Project Type**: Web application (backend API refactoring)
**Performance Goals**: Maintain existing performance (<1000ms p95 latency), response times within ±5% of current
**Constraints**: Zero downtime deployment requirement, coordinated migration across all client systems, 100% functional parity with old endpoints
**Scale/Scope**: 5 obsolete controllers to remove, 4 new Device API controllers to create, 6 existing admin controllers to update, ~40 endpoints to migrate

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Backend Core Principles

- ✅ **I. Domain-Driven Design (DDD)**: PASS - No domain logic changes, only presentation layer refactoring
- ✅ **II. Package by Layered Feature (PbLF)**: PASS - New `device/presentation` package follows PbLF, existing packages updated
- ✅ **III. Test-Driven Development (NON-NEGOTIABLE)**: REQUIRED - Contract tests → Integration tests → Implementation → Unit tests
  - Contract tests for all 4 new Device API controllers
  - Integration tests for security filter chain routing
  - Unit tests for ApiRoutes constants and controller delegation
  - Update all existing contract/integration tests to use new endpoint paths
- ✅ **IV. API-First Design**: PASS - OpenAPI spec updated with new endpoint structure, contracts defined before implementation
- ✅ **V. Security by Default**: CRITICAL - Dual security filter chains must be correctly configured
  - Device API: Custom JWT authentication only
  - UI/Admin API: Keycloak OAuth2 authentication only
  - Explicit @Order annotations to control filter precedence
  - Test rejection of mismatched token types (403 Forbidden)
- ✅ **VI. Database Optimization**: N/A - No database changes
- ✅ **VII. Observability & Monitoring**: PASS - Existing MDC logging preserved, audit logging for authentication attempts maintained

### Git Workflow Standards

- ⚠️ **Branch Naming**: PARTIAL COMPLIANCE - Current branch `010-api-unification-goal` does not follow `feature/**` convention
  - **Justification**: Speckit framework uses `###-feature-name` pattern for feature branches, which pre-dates the constitution's Git Workflow Standards (added in v1.1.1)
  - **Impact**: Low - Branch naming is clear and follows project-specific tooling convention
  - **Mitigation**: Document this exception in constitution or update speckit to generate `feature/###-feature-name` branches

### Code Quality Requirements

- ✅ **Backend Code Quality**: PASS - Java 21, Spring Boot 3.5.6, no circular dependencies, DTOs preserved
- ✅ **Testing Requirements**: REQUIRED - Testcontainers, MockMvc, Mockito for all new and updated controllers
- ✅ **Security Requirements**: CRITICAL - No credential changes, authentication routing correctness verified

### Implementation Workflow

- ✅ **Backend Task Execution Process**: FOLLOWED - Contract tests first, implementation follows, 80% coverage enforced

### Pull Request Requirements

- ✅ **All tests passing**: REQUIRED
- ✅ **Coverage ≥80%**: REQUIRED
- ✅ **Flyway migration if schema changed**: N/A - No schema changes
- ✅ **API contract updated**: REQUIRED - OpenAPI spec must reflect new endpoint structure
- ✅ **Constitution compliance**: IN PROGRESS - This check

**Gate Status: ⚠️ CONDITIONAL PASS** - Proceed with minor branch naming deviation (project-specific tooling constraint). All other gates satisfied.

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

This is a **backend-only refactoring** of existing Spring Boot API controllers. The project follows Package by Layered Feature (PbLF) architecture.

```
src/main/java/com/bitbi/dfm/
├── shared/api/
│   └── ApiRoutes.java                    [NEW] - Centralized API path constants
│
├── device/presentation/                   [NEW PACKAGE] - Device API controllers
│   ├── DeviceAuthController.java         [NEW] - /api/v1/device/auth/*
│   ├── DeviceBatchController.java        [NEW] - /api/v1/device/batches/*
│   ├── DeviceFileController.java         [NEW] - /api/v1/device/files/*
│   └── DeviceErrorController.java        [NEW] - /api/v1/device/errors/*
│
├── account/presentation/
│   └── AccountAdminController.java       [UPDATED] - /api/admin/accounts → /api/v1/accounts
│
├── site/presentation/
│   ├── SiteAdminController.java          [UPDATED] - /api/admin/sites → /api/v1/sites
│   └── SiteController.java               [DELETED] - Duplicate, no longer needed
│
├── batch/presentation/
│   ├── BatchAdminController.java         [UPDATED] - /api/admin/batches → /api/v1/batches
│   ├── BatchHistoryController.java       [UPDATED] - /api/user/batches → /api/v1/history/batches
│   ├── BatchHistoryAdminController.java  [UPDATED] - /api/user/batches → /api/v1/history/batches
│   └── BatchController.java              [DELETED] - Replaced by DeviceBatchController
│
├── error/presentation/
│   ├── ErrorAdminController.java         [UPDATED] - /api/admin/errors → /api/v1/errors
│   └── ErrorLogController.java           [DELETED] - Replaced by DeviceErrorController
│
├── comparison/presentation/
│   └── ComparisonController.java         [NO CHANGE] - Already uses /api/v1/comparisons
│
├── auth/presentation/
│   └── AuthController.java               [DELETED] - Replaced by DeviceAuthController
│
├── upload/presentation/
│   └── FileUploadController.java         [DELETED] - Replaced by DeviceFileController
│
├── security/
│   └── SecurityConfiguration.java        [UPDATED] - Dual security filter chains with @Order
│
└── config/
    └── OpenApiConfiguration.java         [UPDATED] - Separate Device API and UI/Admin API tags

src/test/java/com/bitbi/dfm/
├── device/presentation/
│   └── DeviceApiContractTest.java        [NEW] - Contract tests for all Device API endpoints
│
├── security/
│   └── SecurityFilterChainTest.java      [NEW] - Filter routing and token rejection tests
│
├── account/presentation/
│   └── AccountAdminControllerTest.java   [UPDATED] - New endpoint paths
│
├── site/presentation/
│   └── SiteAdminControllerTest.java      [UPDATED] - New endpoint paths
│
├── batch/presentation/
│   ├── BatchAdminControllerTest.java     [UPDATED] - New endpoint paths
│   └── BatchHistoryContractTest.java     [UPDATED] - New endpoint paths
│
├── error/presentation/
│   └── ErrorAdminControllerTest.java     [UPDATED] - New endpoint paths
│
├── comparison/presentation/
│   └── ComparisonContractTest.java       [UPDATED] - Verify no changes needed
│
└── integration/
    ├── DeviceApiIntegrationTest.java     [NEW] - End-to-end Device API tests
    └── SecurityIntegrationTest.java      [NEW] - Security filter chain integration tests
```

**Structure Decision**: Backend-only refactoring using Spring Boot Package by Layered Feature architecture. New `device/presentation` package created for Device API controllers. Existing admin controllers updated in place to new paths. Five obsolete controllers deleted after verification.

## Complexity Tracking

*Fill ONLY if Constitution Check has violations that must be justified*

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Branch naming `010-api-unification-goal` instead of `feature/010-api-unification-goal` | Speckit framework generates branches in `###-feature-name` format as part of automated workflow | Manually renaming branch would break speckit tooling that references branch name in generated files (spec.md, plan.md, tasks.md). Framework predates constitution v1.1.1 Git Workflow Standards addition. |

**Impact Assessment**: Low - Branch naming is descriptive and follows project-specific tooling convention. No functional impact on code quality or compliance with core architectural principles.

---

## Phase 0: Research & Decisions ✅

**Status**: Complete

**Outputs**:
- ✅ `research.md` - 6 architectural decisions documented with rationale and alternatives
- ✅ All NEEDS CLARIFICATION items resolved

**Key Decisions**:
1. **Security Filter Chains**: Dual `SecurityFilterChain` beans with `@Order` annotations
2. **Path Constants**: Centralized `ApiRoutes.java` for compile-time safety
3. **OpenAPI Grouping**: `@Tag` annotations with `GroupedOpenApi` beans
4. **410 Gone Handling**: `DeprecatedEndpointFilter` with path mapping
5. **Test Update Strategy**: 3-phase approach (Contract → Integration → Security)
6. **Controller Delegation**: Direct service injection, zero business logic duplication

---

## Phase 1: Design & Contracts ✅

**Status**: Complete

**Outputs**:
- ✅ `data-model.md` - Confirmed zero schema changes, no new entities
- ✅ `contracts/README.md` - OpenAPI contract overview with security schemes
- ✅ `quickstart.md` - Complete migration guide with code examples
- ✅ Agent context updated (CLAUDE.md)

**Design Verification**:
- Zero new entities - refactoring only
- All DTOs unchanged - 100% backward compatible
- Security semantics unchanged - only routing changes
- Business logic untouched - thin controller adapters

---

## Constitution Check (Post-Design Re-evaluation)

### Backend Core Principles

- ✅ **I. Domain-Driven Design (DDD)**: PASS - Zero domain logic changes, services delegate identically
- ✅ **II. Package by Layered Feature (PbLF)**: PASS - New `device/presentation` package follows PbLF conventions
- ✅ **III. Test-Driven Development**: READY - Contract test specifications defined in quickstart.md
  - Device API contract tests (11 endpoints)
  - Admin API contract tests (40+ endpoints)
  - Security filter chain tests (4 scenarios)
  - Path constant tests
- ✅ **IV. API-First Design**: PASS - OpenAPI contracts defined before implementation, quickstart.md provides complete specifications
- ✅ **V. Security by Default**: READY - Dual security filter chains designed with explicit @Order precedence
  - Device API: Custom JWT only (filter @Order(1))
  - UI/Admin API: Keycloak OAuth2 only (filter @Order(2))
  - Token rejection tests specified
- ✅ **VI. Database Optimization**: N/A - No database changes
- ✅ **VII. Observability & Monitoring**: PASS - Existing MDC logging preserved, audit logging maintained

### Design Quality

- ✅ **Zero Duplication**: All new controllers delegate to existing services
- ✅ **Single Source of Truth**: ApiRoutes.java for all path definitions
- ✅ **Testability**: Clear test strategy with 3-phase approach
- ✅ **Maintainability**: Thin controller adapters, easy to modify paths
- ✅ **Security**: Explicit filter chain ordering prevents routing errors

### Implementation Readiness

- ✅ **Phase 0 Research**: Complete - All architectural decisions documented
- ✅ **Phase 1 Design**: Complete - Data model, contracts, quickstart ready
- 🔜 **Phase 2 Tasks**: Ready to generate via `/speckit.tasks` command
- 🔜 **Phase 3 Implementation**: Ready to execute after task generation

**Final Gate Status: ✅ PASS** - All design gates satisfied, ready for task generation.

---

## Next Steps

Run `/speckit.tasks` to generate the implementation task list (`tasks.md`) with:
- Contract test specifications for Device API controllers
- Contract test specifications for security filter chains
- Integration test specifications
- Controller implementation tasks
- Test update tasks (path constants)
- Documentation tasks (API migration guide)

**Estimated Effort**: 2-3 days for full implementation and testing
**Risk Level**: Low - Refactoring only, no business logic changes
**Rollback Strategy**: Revert to previous deployment if migration fails

---

## Appendix: File Manifest

### Documentation (This Feature)
```
specs/010-api-unification-goal/
├── spec.md                    ✅ Feature specification
├── plan.md                    ✅ This file
├── research.md                ✅ Architectural decisions
├── data-model.md              ✅ Entity analysis (zero changes)
├── quickstart.md              ✅ Migration guide
├── contracts/
│   └── README.md              ✅ API contracts overview
└── tasks.md                   🔜 Phase 2 output (/speckit.tasks)
```

### Checklists
```
specs/010-api-unification-goal/checklists/
└── requirements.md            ✅ Specification quality checklist
```

**Planning Status**: ✅ **COMPLETE** - Ready for `/speckit.tasks` command

