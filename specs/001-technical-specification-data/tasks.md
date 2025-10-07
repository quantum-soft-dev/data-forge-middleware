# Tasks: Data Forge Middleware - Backend System

**Input**: Design documents from `/specs/001-technical-specification-data/`
**Prerequisites**: plan.md, research.md, data-model.md, contracts/, quickstart.md

## Execution Flow
```
1. Load plan.md from feature directory → Tech stack: Spring Boot 3.5.6, Java 21, PostgreSQL 16, S3
2. Load design documents:
   → data-model.md: 5 entities (Account, Site, Batch, UploadedFile, ErrorLog)
   → contracts/: 3 API contracts (auth, batch, admin)
   → quickstart.md: 6 acceptance scenarios
3. Generate tasks by category: Setup → Tests → Domain → Infrastructure → Application → Presentation
4. Apply TDD ordering: Contract tests → Integration tests → Implementation
5. Mark [P] for parallel execution (different files, no dependencies)
6. Total estimated: 64 tasks
```

## Format: `[ID] [P?] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- Include exact file paths in descriptions

## Phase 3.1: Project Setup (5 tasks)

- [X] T001 Create project structure with Gradle 9.0 (Kotlin DSL) and Spring Boot 3.5.6 parent
- [X] T002 Configure build.gradle.kts with all dependencies (Spring Boot, JPA, Flyway, AWS SDK, Keycloak, Testcontainers, Lombok)
- [X] T003 [P] Create docker-compose.yml with PostgreSQL 16, LocalStack S3, Keycloak
- [X] T004 [P] Configure application.yml profiles (dev, test, prod) with datasource, S3, Keycloak settings
- [X] T005 [P] Create package structure: account/, site/, batch/, upload/, error/, auth/, shared/

## Phase 3.2: Database Migrations (6 tasks)

- [X] T006 [P] Write Flyway migration V1__create_accounts_table.sql with indexes
- [X] T007 [P] Write Flyway migration V2__create_sites_table.sql with foreign keys and unique constraints
- [X] T008 [P] Write Flyway migration V3__create_batches_table.sql with status check constraint and partial unique index
- [X] T009 [P] Write Flyway migration V4__create_uploaded_files_table.sql with composite unique constraint
- [X] T010 Write Flyway migration V5__create_error_logs_partitioned_table.sql with range partitioning and initial partition
- [X] T011 Verify migrations run successfully with Flyway in dev environment

## Phase 3.3: Contract Tests First (TDD - MUST FAIL) (3 tasks)

**CRITICAL: These tests MUST be written and MUST FAIL before ANY implementation**

- [X] T012 [P] Write AuthContractTest.java in src/test/java/com/bitbi/dfm/contract/ for POST /api/v1/auth/token (3 test cases: valid credentials, invalid secret, inactive site)
- [X] T013 [P] Write BatchContractTest.java in src/test/java/com/bitbi/dfm/contract/ for batch lifecycle endpoints (start, upload, complete, fail, cancel)
- [X] T014 [P] Write AdminContractTest.java in src/test/java/com/bitbi/dfm/contract/ for admin CRUD operations (accounts, sites, batches, errors with Keycloak auth)

## Phase 3.4: Integration Tests First (TDD - MUST FAIL) (6 tasks)

- [X] T015 [P] Write AuthenticationIntegrationTest.java with Testcontainers for Scenario 1 (client authentication and token acquisition)
- [X] T016 [P] Write BatchLifecycleIntegrationTest.java with Testcontainers for Scenario 2 (batch start, IN_PROGRESS status, S3 path validation, duplicate prevention)
- [X] T017 [P] Write FileUploadIntegrationTest.java with LocalStack S3 for Scenario 3 (multipart upload, checksum, duplicate filename rejection)
- [X] T018 [P] Write BatchCompletionIntegrationTest.java for Scenario 4 (batch completion, status transition, upload prevention after completion)
- [X] T019 [P] Write ErrorLoggingIntegrationTest.java for Scenario 5 (error recording, batch hasErrors flag, JSONB metadata)
- [X] T020 [P] Write BatchTimeoutIntegrationTest.java for Scenario 6 (timeout scheduler, NOT_COMPLETED status transition)

## Phase 3.5: Domain Layer - Entities & Aggregates (10 tasks)

- [X] T021 [P] Implement Account aggregate root in account/domain/Account.java with soft delete and email validation invariants
- [X] T022 [P] Implement Site aggregate root in site/domain/Site.java with credentials generation and domain uniqueness invariants
- [X] T023 [P] Implement Batch aggregate root in batch/domain/Batch.java with status transitions and one-active-per-site invariant
- [X] T024 [P] Implement UploadedFile entity in upload/domain/UploadedFile.java with checksum and filename uniqueness logic
- [X] T025 [P] Implement ErrorLog entity in error/domain/ErrorLog.java with JSONB metadata support
- [X] T026 [P] Create BatchStatus enum in batch/domain/BatchStatus.java (IN_PROGRESS, COMPLETED, NOT_COMPLETED, FAILED, CANCELLED)
- [X] T027 [P] Create SiteCredentials value object in site/domain/SiteCredentials.java
- [X] T028 [P] Create FileChecksum value object in upload/domain/FileChecksum.java with MD5 algorithm
- [X] T029 [P] Create JwtToken value object in auth/domain/JwtToken.java with expiration logic
- [X] T030 [P] Define all repository interfaces (AccountRepository, SiteRepository, BatchRepository, UploadedFileRepository, ErrorLogRepository)

## Phase 3.6: Domain Services & Events (4 tasks)

- [X] T031 [P] Implement BatchTimeoutPolicy domain service in batch/domain/BatchTimeoutPolicy.java for timeout calculation
- [X] T032 [P] Implement PartitionManager domain service in error/domain/PartitionManager.java for partition creation
- [X] T033 [P] Implement AuthenticationService domain service in auth/domain/AuthenticationService.java for credential validation
- [X] T034 [P] Create domain events (AccountDeactivatedEvent, BatchStartedEvent, BatchCompletedEvent, BatchExpiredEvent, ErrorLoggedEvent) in shared/domain/events/

## Phase 3.7: Infrastructure Layer - JPA & Persistence (6 tasks)

- [X] T035 [P] Implement JpaAccountRepository in account/infrastructure/JpaAccountRepository.java with custom queries
- [X] T036 [P] Implement JpaSiteRepository in site/infrastructure/JpaSiteRepository.java with @EntityGraph for account fetching
- [X] T037 Implement JpaBatchRepository in batch/infrastructure/JpaBatchRepository.java with findActiveBySiteId and findExpiredBatches queries
- [X] T038 [P] Implement JpaUploadedFileRepository in upload/infrastructure/JpaUploadedFileRepository.java with duplicate filename check
- [X] T039 [P] Implement JpaErrorLogRepository in error/infrastructure/JpaErrorLogRepository.java with partitioned table support and JSONB queries
- [X] T040 Run integration tests to validate repository implementations with Testcontainers

## Phase 3.8: Infrastructure Layer - S3 & Security (4 tasks)

- [X] T041 [P] Implement S3FileStorageService in upload/infrastructure/S3FileStorageService.java with 3-retry logic and multipart upload support
- [X] T042 [P] Create S3Configuration in upload/infrastructure/S3Configuration.java with LocalStack support for dev/test
- [X] T043 Implement JwtTokenProvider in auth/infrastructure/JwtTokenProvider.java with HMAC-SHA256 signing and claims (siteId, accountId, domain)
- [X] T044 Implement KeycloakSecurityConfig in auth/infrastructure/KeycloakSecurityConfig.java with SecurityFilterChain for admin endpoints (ROLE_ADMIN)

## Phase 3.9: Application Layer - Use Cases & Services (8 tasks)

- [X] T045 [P] Implement AccountService in account/application/AccountService.java with CRUD operations and AccountDeactivatedEvent publishing
- [X] T046 [P] Implement SiteService in site/application/SiteService.java with clientSecret generation and cascade deactivation listener
- [X] T047 Implement BatchLifecycleService in batch/application/BatchLifecycleService.java (start, complete, fail, cancel) with status transition validation
- [X] T048 Implement FileUploadService in upload/application/FileUploadService.java with multipart handling, S3 upload, and checksum calculation
- [X] T049 [P] Implement ErrorLoggingService in error/application/ErrorLoggingService.java with batch hasErrors flag update
- [X] T050 [P] Implement TokenService in auth/application/TokenService.java for JWT generation and validation
- [X] T051 [P] Implement AccountStatisticsService in account/application/AccountStatisticsService.java for admin stats queries
- [X] T052 [P] Implement ErrorLogExportService in error/application/ErrorLogExportService.java for CSV export

## Phase 3.10: Application Layer - Scheduled Tasks (2 tasks)

- [X] T053 [P] Implement BatchTimeoutScheduler in batch/application/BatchTimeoutScheduler.java with @Scheduled cron (every 5 minutes) to mark expired batches as NOT_COMPLETED
- [X] T054 [P] Implement PartitionScheduler in error/application/PartitionScheduler.java with @Scheduled monthly task to create error_logs partitions one month in advance

## Phase 3.11: Presentation Layer - Controllers (6 tasks)

- [X] T055 Implement AuthController in auth/presentation/AuthController.java for POST /api/v1/auth/token with Basic Auth
- [X] T056 Implement BatchController in batch/presentation/BatchController.java for /api/v1/batch/* endpoints (start, upload, complete, fail, cancel) with JWT auth
- [X] T057 Implement FileUploadController in upload/presentation/FileUploadController.java for POST /api/v1/batch/{id}/upload with multipart file handling
- [X] T058 Implement ErrorLogController in error/presentation/ErrorLogController.java for POST /api/v1/error/{batchId}
- [X] T059 [P] Implement AccountAdminController in account/presentation/AccountAdminController.java for /admin/accounts/* with Keycloak auth
- [X] T060 [P] Implement SiteAdminController in site/presentation/SiteAdminController.java for /admin/accounts/{id}/sites and /admin/sites/{id} with Keycloak auth

## Phase 3.12: Presentation Layer - Shared Components (4 tasks)

- [X] T061 Implement GlobalExceptionHandler in shared/exception/GlobalExceptionHandler.java with @RestControllerAdvice for standardized error responses
- [X] T062 [P] Create ErrorResponse DTO in shared/exception/ErrorResponse.java with timestamp, status, error, message, path fields
- [X] T063 [P] Implement OpenApiConfiguration in shared/config/OpenApiConfiguration.java with Swagger UI and security schemes (Basic Auth, Bearer JWT)
- [X] T064 [P] Implement ActuatorConfiguration in shared/config/ActuatorConfiguration.java with custom health indicators for DB and S3

## Phase 3.13: Observability & Metrics (3 tasks)

- [X] T065 [P] Implement MetricsConfiguration in shared/config/MetricsConfiguration.java with custom Micrometer counters (batch.started, batch.completed, files.uploaded, error.logged)
- [X] T066 [P] Configure Logback with JSON formatting in src/main/resources/logback-spring.xml with MDC support for batchId/siteId context
- [X] T067 Create S3HealthIndicator in shared/health/S3HealthIndicator.java implementing HealthIndicator with bucket connectivity check

## Phase 3.14: Validation & Testing (6 tasks)

- [X] T068 Run all contract tests (T012-T014) and verify they pass with implementations
- [X] T069 Run all integration tests (T015-T020) with Testcontainers and verify quickstart scenarios work end-to-end
- [X] T070 [P] Write unit tests for BatchTimeoutPolicy in batch/domain/BatchTimeoutPolicyTest.java
- [X] T071 [P] Write unit tests for S3FileStorageService retry logic in upload/infrastructure/S3FileStorageServiceTest.java
- [X] T072 Measure JaCoCo code coverage and ensure ≥80% per module
- [ ] T073 Run performance tests with Apache Bench to validate <1000ms p95 latency for non-upload endpoints
- [X] T073.1 Fix JWT authentication filter for /api/v1/** endpoints (add JwtAuthenticationFilter before OAuth2 filter)
- [X] T073.2 Add authorization checks in controllers to verify site ownership (AuthorizationHelper + verifySiteOwnership)
- [X] T073.3 Fix batch counter updates in FileUploadService (call batch.incrementFileCount after upload)
- [X] T073.4 Add startup validation for JWT secret (SecurityConfigValidator with fail-fast)
- [X] T073.5 Align file size limits between Spring multipart (128MB) and service layer
- [X] T073.6 Verify missing index on batches.account_id (already present in V6 migration)
- [X] T073.7 Fix race condition in concurrent batch limit (use countActiveBatchesByAccountIdWithLock with FOR UPDATE)
- [X] T073.8 Separate S3 upload from database transaction (validate first, upload S3, then commit metadata in new transaction)

## Phase 3.15: Documentation & Finalization (4 tasks)

- [X] T074 [P] Create comprehensive README.md at repository root with Prerequisites, Setup, Running, Testing, API Docs, Contributing sections
- [X] T075 [P] Update CLAUDE.md with final tech stack and recent implementation decisions
- [X] T076 [P] Generate OpenAPI spec JSON/YAML from annotations and publish to docs/
- [ ] T077 Execute all 6 quickstart.md scenarios manually and verify success criteria (all health checks pass, metrics collected, S3 files uploaded)

## Dependencies

**Critical TDD Flow:**
- T012-T014 (contract tests) MUST FAIL before T055-T061 (controllers)
- T015-T020 (integration tests) MUST FAIL before T045-T052 (services)
- Flyway migrations (T006-T011) block JPA repositories (T035-T040)

**Layer Dependencies:**
- Domain (T021-T034) blocks Infrastructure (T035-T044)
- Infrastructure (T035-T044) blocks Application (T045-T054)
- Application (T045-T054) blocks Presentation (T055-T064)

**Specific Blocks:**
- T037 (BatchRepository) depends on T023 (Batch aggregate)
- T043 (JwtTokenProvider) blocks T050 (TokenService)
- T044 (KeycloakSecurityConfig) blocks T059-T060 (Admin controllers)
- T053 (BatchTimeoutScheduler) depends on T047 (BatchLifecycleService)

## Parallel Execution Examples

### Contract Tests (T012-T014) - Run together after migrations complete:
```bash
# Launch 3 agents in parallel after T011 completes
Task: "Write AuthContractTest.java for POST /api/v1/auth/token with 3 test cases"
Task: "Write BatchContractTest.java for batch lifecycle endpoints"
Task: "Write AdminContractTest.java for admin CRUD with Keycloak auth"
```

### Integration Tests (T015-T020) - Run together after contract tests:
```bash
# Launch 6 agents in parallel after T014 completes
Task: "Write AuthenticationIntegrationTest.java with Testcontainers for Scenario 1"
Task: "Write BatchLifecycleIntegrationTest.java for Scenario 2"
Task: "Write FileUploadIntegrationTest.java with LocalStack S3 for Scenario 3"
Task: "Write BatchCompletionIntegrationTest.java for Scenario 4"
Task: "Write ErrorLoggingIntegrationTest.java for Scenario 5"
Task: "Write BatchTimeoutIntegrationTest.java for Scenario 6"
```

### Domain Entities (T021-T030) - Run together (no dependencies):
```bash
# Launch 10 agents in parallel after Phase 3.4 completes
Task: "Implement Account aggregate root with soft delete invariants"
Task: "Implement Site aggregate root with credentials generation"
Task: "Implement Batch aggregate root with status transitions"
Task: "Implement UploadedFile entity with checksum logic"
Task: "Implement ErrorLog entity with JSONB metadata"
Task: "Create BatchStatus enum"
Task: "Create SiteCredentials value object"
Task: "Create FileChecksum value object"
Task: "Create JwtToken value object"
Task: "Define all repository interfaces"
```

### JPA Repositories (T035, T036, T038, T039) - Parallel after domain layer:
```bash
# Launch 4 agents in parallel (T037 sequential due to Batch dependency)
Task: "Implement JpaAccountRepository with custom queries"
Task: "Implement JpaSiteRepository with @EntityGraph"
Task: "Implement JpaUploadedFileRepository with duplicate check"
Task: "Implement JpaErrorLogRepository with partitioned table support"
```

### Application Services (T045, T046, T049-T052) - Parallel after infrastructure:
```bash
# Launch 6 agents in parallel (T047-T048 sequential due to batch dependency)
Task: "Implement AccountService with CRUD and event publishing"
Task: "Implement SiteService with clientSecret generation"
Task: "Implement ErrorLoggingService with hasErrors flag update"
Task: "Implement TokenService for JWT generation"
Task: "Implement AccountStatisticsService for admin stats"
Task: "Implement ErrorLogExportService for CSV export"
```

## Notes

- **[P] markers**: Different files, no shared dependencies, safe for parallel execution
- **TDD enforcement**: All tests (T012-T020) must FAIL before implementation begins
- **Constitution compliance**: 80% coverage enforced, JavaDoc mandatory, no secrets in code
- **Commit strategy**: Commit after each green test cycle (red → green → refactor → commit)
- **File conflicts to avoid**: Never mark tasks [P] if they modify the same file (e.g., T047-T048 both touch BatchLifecycleService)

## Validation Checklist
*GATE: Verify before marking tasks.md complete*

- [x] All 3 contract files have corresponding tests (T012-T014)
- [x] All 5 entities have model tasks (T021-T025)
- [x] All 6 quickstart scenarios have integration tests (T015-T020)
- [x] All contract tests come before controller implementation
- [x] All integration tests come before service implementation
- [x] Parallel tasks [P] are truly independent (different files, no shared state)
- [x] Each task specifies exact file path and module
- [x] TDD ordering enforced: Tests → Domain → Infrastructure → Application → Presentation
- [x] Total 77 tasks generated (5 setup + 6 migrations + 3 contract + 6 integration + 10 domain + 4 domain services + 6 JPA + 4 S3/Security + 8 services + 2 schedulers + 6 controllers + 4 shared + 3 observability + 6 validation + 4 docs)
