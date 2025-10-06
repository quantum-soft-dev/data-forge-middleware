
# Implementation Plan: Data Forge Middleware - Backend System

**Branch**: `001-technical-specification-data` | **Date**: 2025-10-06 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-technical-specification-data/spec.md`

## Execution Flow (/plan command scope)
```
1. Load feature spec from Input path
   → If not found: ERROR "No feature spec at {path}"
2. Fill Technical Context (scan for NEEDS CLARIFICATION)
   → Detect Project Type from file system structure or context (web=frontend+backend, mobile=app+api)
   → Set Structure Decision based on project type
3. Fill the Constitution Check section based on the content of the constitution document.
4. Evaluate Constitution Check section below
   → If violations exist: Document in Complexity Tracking
   → If no justification possible: ERROR "Simplify approach first"
   → Update Progress Tracking: Initial Constitution Check
5. Execute Phase 0 → research.md
   → If NEEDS CLARIFICATION remain: ERROR "Resolve unknowns"
6. Execute Phase 1 → contracts, data-model.md, quickstart.md, agent-specific template file (e.g., `CLAUDE.md` for Claude Code, `.github/copilot-instructions.md` for GitHub Copilot, `GEMINI.md` for Gemini CLI, `QWEN.md` for Qwen Code, or `AGENTS.md` for all other agents).
7. Re-evaluate Constitution Check section
   → If new violations: Refactor design, return to Phase 1
   → Update Progress Tracking: Post-Design Constitution Check
8. Plan Phase 2 → Describe task generation approach (DO NOT create tasks.md)
9. STOP - Ready for /tasks command
```

**IMPORTANT**: The /plan command STOPS at step 7. Phases 2-4 are executed by other commands:
- Phase 2: /tasks command creates tasks.md
- Phase 3-4: Implementation execution (manual or via tools)

## Summary
Data Forge Middleware is a backend service for receiving, storing, and managing data files uploaded by client applications (Windows services) at remote locations. The system provides secure JWT-based authentication, batch upload tracking with lifecycle management, S3 file storage with hierarchical organization, error logging with time-partitioned storage, and comprehensive administrative APIs for account/site/batch/error management. Key capabilities include automatic batch timeout handling, storage operation retry logic, health monitoring, and metrics collection.

## Technical Context
**Language/Version**: Java 21
**Primary Dependencies**: Spring Boot 3.5.6, Spring Security, Spring Data JPA, Flyway, HikariCP, AWS SDK for S3, Keycloak Spring Boot Starter
**Storage**: PostgreSQL 16 with table partitioning (error_logs), AWS S3 (or S3-compatible) for file storage
**Testing**: JUnit 5, Mockito, Testcontainers (for PostgreSQL and LocalStack S3)
**Target Platform**: Linux server (containerized deployment)
**Project Type**: single (backend-only REST API service)
**Performance Goals**: <1000ms p95 latency for all non-upload API operations, support ~10,000 batch uploads/day across ~3,000 sites
**Constraints**: <1000ms API response time p95, 3-attempt retry for S3 operations with fixed interval, indefinite data retention (no auto-deletion), 128MB max file size, configurable batch timeout (default 60 min)
**Scale/Scope**: 1,000 accounts, ~3,000 sites (avg 3 per account), ~10,000 daily batch uploads, 1-3MB avg file size, 10-15 files per batch avg

## Constitution Check
*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### I. Domain-Driven Design (DDD)
- [x] **PASS**: Project structure follows DDD with Package by Layered Feature (PbLF)
- [x] Domain layer will contain: Account, Site, Batch, UploadedFile, ErrorLog aggregates with repository interfaces
- [x] Application layer will contain: Use cases for auth, batch lifecycle, file upload, error logging, admin operations
- [x] Infrastructure layer will contain: JPA repositories, S3 client, Keycloak integration, Flyway migrations
- [x] Presentation layer will contain: REST controllers for /auth, /batch, /admin endpoints with OpenAPI docs

### II. Package by Layered Feature (PbLF)
- [x] **PASS**: Source structure organized as:
  ```
  src/main/java/com/bitbi/dfm/
  ├── account/       # Account aggregate + admin operations
  ├── site/          # Site aggregate + admin operations
  ├── batch/         # Batch aggregate + lifecycle management
  ├── upload/        # File upload handling + S3 integration
  ├── error/         # Error logging + partitioned storage
  ├── auth/          # JWT authentication + Keycloak integration
  └── shared/        # Common exceptions, utils, base classes
  ```

### III. Test-Driven Development (TDD)
- [x] **PASS**: All implementation will follow strict TDD red-green-refactor cycle
- [x] Minimum 80% backend coverage enforced via JaCoCo
- [x] Test naming convention: `shouldExpectedBehaviorWhenCondition`
- [x] Contract tests will be written first and MUST fail before implementation
- [x] Integration tests based on acceptance scenarios will guide development

### IV. API-First Design
- [x] **PASS**: REST API follows Richardson Maturity Model Level 2
- [x] Resource-oriented URLs: `/api/v1/auth/token`, `/api/v1/batch/*`, `/admin/*`
- [x] Proper HTTP methods: GET (query), POST (create/actions), PUT (update), DELETE (soft-delete)
- [x] Standard status codes: 200 OK, 201 Created, 204 No Content, 400 Bad Request, 401 Unauthorized, 404 Not Found, 409 Conflict, 413 Payload Too Large
- [x] OpenAPI/Swagger documentation mandatory for all endpoints with examples

### V. Security by Default
- [x] **PASS**: Security integrated from start
- [x] Spring Security + Keycloak for admin endpoints (ADMIN role)
- [x] JWT tokens for client authentication (24-hour expiration, renewal supported)
- [x] Basic Auth only for initial token acquisition (domain:clientSecret)
- [x] Secrets in AWS Secrets Manager (prod) / application-dev.yml (dev)
- [x] No passwords, tokens, or secrets in logs or git

### VI. Database Query Optimization
- [x] **PASS**: Database access optimized from design
- [x] N+1 queries prevented via @EntityGraph and JOIN FETCH for relationships
- [x] Indexes mandatory on all foreign keys (account_id, site_id, batch_id, occurred_at)
- [x] Partitioning strategy for error_logs table by month (occurred_at range partitioning)
- [x] Flyway migrations with V{version}__{description}.sql naming

### VII. Observability and Documentation
- [x] **PASS**: Logging and documentation planned
- [x] JavaDoc mandatory for all public methods/classes/interfaces
- [x] SLF4J + Logback with DEBUG (dev), INFO (prod) levels
- [x] Health check endpoints: /actuator/health, /actuator/health/db, /actuator/health/s3
- [x] Metrics collection for batch operations, file uploads, errors via Micrometer
- [x] README with Prerequisites, Setup, Running, Testing, API Docs sections

### Summary
✅ **ALL CONSTITUTIONAL REQUIREMENTS SATISFIED** - No violations, no complexity deviations needed

## Project Structure

### Documentation (this feature)
```
specs/[###-feature]/
├── plan.md              # This file (/plan command output)
├── research.md          # Phase 0 output (/plan command)
├── data-model.md        # Phase 1 output (/plan command)
├── quickstart.md        # Phase 1 output (/plan command)
├── contracts/           # Phase 1 output (/plan command)
└── tasks.md             # Phase 2 output (/tasks command - NOT created by /plan)
```

### Source Code (repository root)
```
src/main/java/com/bitbi/dfm/
├── account/
│   ├── domain/
│   │   ├── Account.java                    # Aggregate Root
│   │   ├── AccountRepository.java          # Repository interface
│   │   └── AccountId.java                  # Value Object (UUID wrapper)
│   ├── application/
│   │   ├── AccountService.java             # Use cases
│   │   ├── dto/                            # DTOs for queries/commands
│   │   └── AccountStatisticsService.java
│   ├── infrastructure/
│   │   ├── JpaAccountRepository.java       # JPA implementation
│   │   └── AccountEntity.java              # JPA entity
│   └── presentation/
│       ├── AccountAdminController.java     # Admin API endpoints
│       └── model/                          # Request/Response models
├── site/
│   ├── domain/
│   │   ├── Site.java                       # Aggregate Root
│   │   ├── SiteRepository.java
│   │   └── SiteCredentials.java            # Value Object (domain + secret)
│   ├── application/
│   │   └── SiteService.java
│   ├── infrastructure/
│   │   └── JpaSiteRepository.java
│   └── presentation/
│       └── SiteAdminController.java
├── batch/
│   ├── domain/
│   │   ├── Batch.java                      # Aggregate Root
│   │   ├── BatchStatus.java                # Enum (IN_PROGRESS, COMPLETED, etc.)
│   │   ├── BatchRepository.java
│   │   └── BatchTimeoutPolicy.java         # Domain service
│   ├── application/
│   │   ├── BatchLifecycleService.java      # Start, complete, fail, cancel
│   │   ├── BatchTimeoutScheduler.java      # Scheduled task for timeouts
│   │   └── dto/
│   ├── infrastructure/
│   │   └── JpaBatchRepository.java
│   └── presentation/
│       ├── BatchController.java            # Client API (/api/v1/batch/*)
│       └── BatchAdminController.java       # Admin API
├── upload/
│   ├── domain/
│   │   ├── UploadedFile.java               # Entity
│   │   ├── FileChecksum.java               # Value Object
│   │   └── UploadedFileRepository.java
│   ├── application/
│   │   ├── FileUploadService.java          # Multipart upload handling
│   │   └── dto/UploadResponse.java
│   ├── infrastructure/
│   │   ├── S3FileStorageService.java       # S3 client with retry logic
│   │   ├── JpaUploadedFileRepository.java
│   │   └── S3Configuration.java
│   └── presentation/
│       └── FileUploadController.java       # POST /api/v1/batch/{id}/upload
├── error/
│   ├── domain/
│   │   ├── ErrorLog.java                   # Entity
│   │   ├── ErrorLogRepository.java
│   │   └── PartitionManager.java           # Domain service for partition creation
│   ├── application/
│   │   ├── ErrorLoggingService.java
│   │   ├── ErrorLogExportService.java      # CSV export
│   │   └── PartitionScheduler.java         # Scheduled partition creation
│   ├── infrastructure/
│   │   └── JpaErrorLogRepository.java
│   └── presentation/
│       ├── ErrorLogController.java         # POST /api/v1/error
│       └── ErrorLogAdminController.java    # GET /admin/errors, /admin/errors/export
├── auth/
│   ├── domain/
│   │   ├── JwtToken.java                   # Value Object
│   │   └── AuthenticationService.java      # Domain service
│   ├── application/
│   │   └── TokenService.java               # JWT generation/validation
│   ├── infrastructure/
│   │   ├── JwtTokenProvider.java           # JWT implementation
│   │   └── KeycloakSecurityConfig.java     # Keycloak integration
│   └── presentation/
│       └── AuthController.java             # POST /api/v1/auth/token
└── shared/
    ├── exception/
    │   ├── GlobalExceptionHandler.java     # @RestControllerAdvice
    │   ├── DomainException.java            # Base domain exception
    │   └── ErrorResponse.java              # Standard error format
    ├── util/
    │   ├── TimeProvider.java               # Testable time abstraction
    │   └── UuidGenerator.java              # Testable UUID generation
    └── config/
        ├── OpenApiConfiguration.java       # Swagger/OpenAPI setup
        ├── ActuatorConfiguration.java      # Health checks
        └── MetricsConfiguration.java       # Micrometer metrics

src/main/resources/
├── application.yml                         # Default configuration
├── application-dev.yml                     # Development profile
├── application-prod.yml                    # Production profile
└── db/migration/                           # Flyway migrations
    ├── V1__create_accounts_table.sql
    ├── V2__create_sites_table.sql
    ├── V3__create_batches_table.sql
    ├── V4__create_uploaded_files_table.sql
    └── V5__create_error_logs_partitioned_table.sql

src/test/java/com/bitbi/dfm/
├── contract/                               # Contract tests (OpenAPI validation)
│   ├── AuthContractTest.java
│   ├── BatchContractTest.java
│   └── AdminContractTest.java
├── integration/                            # Integration tests (Testcontainers)
│   ├── BatchLifecycleIntegrationTest.java
│   ├── FileUploadIntegrationTest.java
│   └── ErrorLoggingIntegrationTest.java
└── [feature]/                              # Unit tests per feature
    ├── domain/                             # Domain logic tests
    ├── application/                        # Service tests
    └── infrastructure/                     # Repository tests

build.gradle.kts                            # Gradle build with Kotlin DSL
settings.gradle.kts
README.md
docker-compose.yml                          # Local development (Postgres, LocalStack, Keycloak)
```

**Structure Decision**: Single backend REST API project using DDD with Package by Layered Feature (PbLF). Each feature module (account, site, batch, upload, error, auth) contains all four DDD layers. Shared components (exceptions, utilities, configuration) are centralized. Test structure mirrors source structure with additional contract and integration test directories.

## Phase 0: Outline & Research
1. **Extract unknowns from Technical Context** above:
   - For each NEEDS CLARIFICATION → research task
   - For each dependency → best practices task
   - For each integration → patterns task

2. **Generate and dispatch research agents**:
   ```
   For each unknown in Technical Context:
     Task: "Research {unknown} for {feature context}"
   For each technology choice:
     Task: "Find best practices for {tech} in {domain}"
   ```

3. **Consolidate findings** in `research.md` using format:
   - Decision: [what was chosen]
   - Rationale: [why chosen]
   - Alternatives considered: [what else evaluated]

**Output**: research.md with all NEEDS CLARIFICATION resolved

## Phase 1: Design & Contracts
*Prerequisites: research.md complete*

1. **Extract entities from feature spec** → `data-model.md`:
   - Entity name, fields, relationships
   - Validation rules from requirements
   - State transitions if applicable

2. **Generate API contracts** from functional requirements:
   - For each user action → endpoint
   - Use standard REST/GraphQL patterns
   - Output OpenAPI/GraphQL schema to `/contracts/`

3. **Generate contract tests** from contracts:
   - One test file per endpoint
   - Assert request/response schemas
   - Tests must fail (no implementation yet)

4. **Extract test scenarios** from user stories:
   - Each story → integration test scenario
   - Quickstart test = story validation steps

5. **Update agent file incrementally** (O(1) operation):
   - Run `.specify/scripts/bash/update-agent-context.sh claude`
     **IMPORTANT**: Execute it exactly as specified above. Do not add or remove any arguments.
   - If exists: Add only NEW tech from current plan
   - Preserve manual additions between markers
   - Update recent changes (keep last 3)
   - Keep under 150 lines for token efficiency
   - Output to repository root

**Output**: data-model.md, /contracts/*, failing tests, quickstart.md, agent-specific file

## Phase 2: Task Planning Approach
*This section describes what the /tasks command will do - DO NOT execute during /plan*

**Task Generation Strategy**:
1. **Load task template** from `.specify/templates/tasks-template.md`
2. **Extract test tasks from contracts** (Phase 1):
   - Auth API contract → `AuthContractTest.java` (test authentication, token issuance, invalid credentials) [P]
   - Batch API contract → `BatchContractTest.java` (test batch lifecycle, file upload, completion) [P]
   - Admin API contract → `AdminContractTest.java` (test CRUD operations, authorization, pagination) [P]
3. **Extract domain model tasks from data-model.md**:
   - For each aggregate root (Account, Site, Batch) → Domain entity + repository interface tasks [P]
   - For each entity (UploadedFile, ErrorLog) → Entity creation tasks [P]
   - Value objects (SiteCredentials, FileChecksum, JwtToken) → Value object tasks [P]
4. **Extract integration tests from quickstart.md scenarios**:
   - Scenario 1 (Authentication) → Integration test with Testcontainers
   - Scenario 2 (Batch lifecycle) → Integration test
   - Scenario 3 (File upload) → Integration test with LocalStack S3
   - Scenario 4 (Batch completion) → Integration test
   - Scenario 5 (Error logging) → Integration test
   - Scenario 6 (Timeout handling) → Integration test with time manipulation
5. **Generate implementation tasks** (TDD order):
   - **Layer 1: Domain** (no dependencies)
     - Account, Site, Batch aggregates with business logic
     - Repository interfaces
     - Domain events and services
   - **Layer 2: Infrastructure** (depends on domain)
     - JPA entities and repositories
     - S3 client with retry logic
     - Keycloak security configuration
     - Flyway migrations
   - **Layer 3: Application** (depends on domain + infrastructure)
     - Use case services (AccountService, BatchLifecycleService, etc.)
     - Scheduled tasks (BatchTimeoutScheduler, PartitionScheduler)
     - DTOs and mappers
   - **Layer 4: Presentation** (depends on application)
     - REST controllers (AuthController, BatchController, AdminControllers)
     - Request/Response models
     - Global exception handler
     - OpenAPI configuration

**Ordering Strategy**:
- **Strict TDD**: Tests written before implementation (contract tests → integration tests → implementation)
- **Dependency order**: Domain → Infrastructure → Application → Presentation (DDD layers)
- **Parallel markers** [P]: Independent files within same layer can be implemented in parallel
- **Feature grouping**: All tasks for a feature module (e.g., `auth/`) clustered together
- **Migrations first**: Flyway migration tasks precede JPA entity tasks for same table

**Task Categories** (estimated counts):
- Contract tests: 3 files (auth, batch, admin)
- Integration tests: 6 scenarios from quickstart
- Flyway migrations: 5 tables + initial partition
- Domain layer: 15 tasks (5 aggregates/entities + value objects + repositories + domain services)
- Infrastructure layer: 12 tasks (JPA repositories + S3 client + Keycloak + health indicators)
- Application layer: 10 tasks (use case services + DTOs + schedulers)
- Presentation layer: 8 tasks (controllers + exception handler + OpenAPI config)
- Configuration: 3 tasks (application.yml profiles, docker-compose, build.gradle.kts)
- **Total estimated**: 62 tasks

**Output Format** (tasks.md):
```
1. [P] Write AuthContractTest for POST /api/v1/auth/token
2. [P] Write BatchContractTest for batch lifecycle endpoints
3. [P] Write AdminContractTest for admin CRUD operations
4. Write BatchLifecycleIntegrationTest for Scenario 2
...
10. Write Flyway migration V1__create_accounts_table.sql
11. [P] Implement Account aggregate root with invariants
12. [P] Implement Site aggregate root with credential generation
...
35. Implement BatchController with lifecycle endpoints
36. Implement FileUploadController with multipart handling
...
62. ✓ Validate quickstart.md scenarios pass end-to-end
```

**IMPORTANT**: This phase is executed by the /tasks command, NOT by /plan

## Phase 3+: Future Implementation
*These phases are beyond the scope of the /plan command*

**Phase 3**: Task execution (/tasks command creates tasks.md)  
**Phase 4**: Implementation (execute tasks.md following constitutional principles)  
**Phase 5**: Validation (run tests, execute quickstart.md, performance validation)

## Complexity Tracking
*Fill ONLY if Constitution Check has violations that must be justified*

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| [e.g., 4th project] | [current need] | [why 3 projects insufficient] |
| [e.g., Repository pattern] | [specific problem] | [why direct DB access insufficient] |


## Progress Tracking
*This checklist is updated during execution flow*

**Phase Status**:
- [x] Phase 0: Research complete (/plan command) - research.md generated with technology decisions
- [x] Phase 1: Design complete (/plan command) - data-model.md, contracts/, quickstart.md, CLAUDE.md generated
- [x] Phase 2: Task planning complete (/plan command - describe approach only) - 62 tasks estimated, TDD ordering defined
- [ ] Phase 3: Tasks generated (/tasks command) - NEXT STEP
- [ ] Phase 4: Implementation complete
- [ ] Phase 5: Validation passed

**Gate Status**:
- [x] Initial Constitution Check: PASS - All 7 constitutional principles satisfied
- [x] Post-Design Constitution Check: PASS - Design artifacts align with DDD, PbLF, TDD requirements
- [x] All NEEDS CLARIFICATION resolved - No ambiguities in Technical Context
- [x] Complexity deviations documented - None required (no violations)

**Artifacts Generated**:
- ✅ `/specs/001-technical-specification-data/plan.md` (this file)
- ✅ `/specs/001-technical-specification-data/research.md` (10 technology decisions)
- ✅ `/specs/001-technical-specification-data/data-model.md` (5 entities, DB schema, repositories)
- ✅ `/specs/001-technical-specification-data/contracts/auth-api.md` (authentication contract)
- ✅ `/specs/001-technical-specification-data/contracts/batch-api.md` (batch & upload contract)
- ✅ `/specs/001-technical-specification-data/contracts/admin-api-summary.md` (admin endpoints)
- ✅ `/specs/001-technical-specification-data/quickstart.md` (6 acceptance scenario validation tests)
- ✅ `/CLAUDE.md` (agent-specific context for Claude Code)

**Next Command**: `/tasks` to generate tasks.md with 62 ordered, dependency-aware implementation tasks

---
*Based on Constitution v1.0.0 - See `.specify/memory/constitution.md`*
