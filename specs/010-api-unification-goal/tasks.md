# Implementation Tasks: API Unification

**Feature Branch**: `010-api-unification-goal`
**Created**: 2025-11-05
**Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

## Overview

This document provides a TDD-driven, user-story-organized task breakdown for implementing the API Unification feature. Tasks follow the constitution's TDD requirement: Contract Tests → Integration Tests → Implementation → Unit Tests.

**Implementation Strategy**: Incremental delivery by user story priority (P1 stories first, then P2). Each user story is independently testable and deliverable.

**Total Estimated Effort**: 2-3 days (16-24 hours)
**Risk Level**: Low (refactoring only, no business logic changes)

---

## Task Organization

Tasks are organized into phases:
- **Phase 1**: Setup & Infrastructure (shared foundation)
- **Phase 2**: Foundational Prerequisites (security, path constants - BLOCKS all user stories)
- **Phase 3**: User Story 4 (Security Filter Chains) - CRITICAL PATH
- **Phase 4**: User Story 1 (Device API) - P1
- **Phase 5**: User Story 2 (UI/Admin API) - P1
- **Phase 6**: User Story 3 (Migration Guide) - P2
- **Phase 7**: Polish & Integration

**Parallelization**: Tasks marked `[P]` can run in parallel within the same phase.

---

## Phase 1: Setup & Infrastructure

**Goal**: Create shared infrastructure needed by all user stories.

**Duration**: 30 minutes

### T001: Create ApiRoutes Constants File [Story: Foundation]
**File**: `src/main/java/com/bitbi/dfm/shared/api/ApiRoutes.java`
**Type**: Implementation
**Priority**: BLOCKING (all subsequent tasks depend on this)
**Description**:
- Create `ApiRoutes.java` in `shared/api` package
- Define all Device API path constants (`DEVICE_AUTH_TOKEN`, `DEVICE_BATCHES_START`, etc.)
- Define all UI/Admin API path constants (`ACCOUNTS`, `SITES`, `BATCHES_ADMIN`, `HISTORY_BATCHES`, etc.)
- Make class `final` with private constructor (utility class pattern)
- Use hierarchical naming: `DEVICE_API_BASE` → `DEVICE_BATCHES` → `DEVICE_BATCHES_START`

**Acceptance**:
- All 40+ API paths defined as `public static final String` constants
- Constants follow naming convention: `{API_TYPE}_{RESOURCE}_{ACTION}`
- Class compiles without errors

**Reference**: See `research.md` Decision 2 for implementation pattern

---

### T002: Update OpenAPI Configuration for API Grouping [Story: Foundation]
**File**: `src/main/java/com/bitbi/dfm/config/OpenApiConfiguration.java`
**Type**: Implementation
**Priority**: BLOCKING
**Description**:
- Update `OpenApiConfiguration` to define two API groups:
  - Device API: `GroupedOpenApi.builder().group("device-api").pathsToMatch("/api/v1/device/**")`
  - Admin API: `GroupedOpenApi.builder().group("admin-api").pathsToMatch("/api/v1/**").pathsToExclude("/api/v1/device/**")`
- Ensure security schemes defined: `bearerAuth` (Custom JWT), `oauth2` (Keycloak)
- Update API info title to "Data Forge Middleware API - Unified Structure"

**Acceptance**:
- Swagger UI shows two distinct API groups when accessed at `/swagger-ui.html`
- Security schemes documented for each group
- Configuration compiles without errors

**Reference**: See `research.md` Decision 3 for implementation pattern

---

## Phase 2: Foundational Prerequisites (BLOCKS All User Stories)

**Goal**: Implement security filter chains that MUST work before any API endpoint can function.

**Duration**: 2 hours

**Critical Path**: User Story 4 tasks MUST complete before any other user story can be implemented or tested.

---

## Phase 3: User Story 4 - Security Filter Chains (P1 - CRITICAL PATH)

**Story Goal**: Automatically route Device API requests to Custom JWT filter and UI/Admin API requests to Keycloak OAuth2 filter, rejecting mismatched tokens.

**Independent Test**: Attempting to access Device API with Keycloak tokens returns 403. Attempting to access Admin API with Custom JWT returns 403. Correct tokens are accepted.

**Story Completion Criteria**:
- ✅ Dual security filter chains configured with `@Order` precedence
- ✅ Device API (`/api/v1/device/**`) routes to Custom JWT only
- ✅ UI/Admin API (`/api/v1/**`) routes to Keycloak OAuth2 only
- ✅ Mismatched tokens rejected with 403 Forbidden
- ✅ Security integration tests pass

**Duration**: 2 hours

---

### T003: [US4] Write Security Filter Chain Contract Tests
**File**: `src/test/java/com/bitbi/dfm/security/SecurityFilterChainTest.java`
**Type**: Contract Test (TDD - RED phase)
**Priority**: CRITICAL
**Description**:
Write MockMvc tests for security filter routing:

**Test Cases**:
1. **TC01**: Device API with Custom JWT → 200 OK (authorized)
2. **TC02**: Device API with Keycloak token → 403 Forbidden
3. **TC03**: Device API with no token → 401 Unauthorized
4. **TC04**: Admin API with Keycloak token → 200 OK (authorized)
5. **TC05**: Admin API with Custom JWT → 403 Forbidden
6. **TC06**: Admin API with no token → 401 Unauthorized

**Acceptance**:
- All 6 tests written and FAILING (RED phase)
- Tests use `@WebMvcTest` with `@AutoConfigureMockMvc`
- Mock JWT and Keycloak token generation logic
- Clear assertions on HTTP status codes

**Dependencies**: T001 (ApiRoutes constants)

---

### T004: [US4] Implement Dual Security Filter Chains [P]
**File**: `src/main/java/com/bitbi/dfm/security/SecurityConfiguration.java`
**Type**: Implementation (TDD - GREEN phase)
**Priority**: CRITICAL
**Description**:
- Create `deviceApiFilterChain` bean with `@Order(1)` and `.securityMatcher("/api/v1/device/**")`
- Configure Custom JWT filter for Device API
- Create `adminApiFilterChain` bean with `@Order(2)` and `.securityMatcher("/api/v1/**")`
- Configure Keycloak OAuth2 resource server for Admin API
- Ensure explicit precedence with @Order annotations

**Acceptance**:
- T003 security tests turn GREEN
- Both filter chains defined as separate `@Bean` methods
- Device API filter evaluated before Admin API filter
- No compilation errors

**Dependencies**: T003 (tests written)
**Reference**: See `research.md` Decision 1 for implementation pattern

---

### T005: [US4] Write Security Integration Tests
**File**: `src/test/java/com/bitbi/dfm/integration/SecurityIntegrationTest.java`
**Type**: Integration Test
**Priority**: CRITICAL
**Description**:
Write Testcontainers-based integration tests:

**Test Cases**:
1. **IT01**: Full Device API flow (token generation → authenticated request)
2. **IT02**: Full Admin API flow (Keycloak token → authenticated request)
3. **IT03**: Device API rejects Keycloak token at filter level (before controller)
4. **IT04**: Admin API rejects Custom JWT at filter level (before controller)

**Acceptance**:
- All 4 integration tests pass
- Uses `@SpringBootTest(webEnvironment = RANDOM_PORT)`
- Actual HTTP requests with RestTemplate
- Verifies filter-level rejection (not controller-level)

**Dependencies**: T004 (security implementation)

---

### T006: [US4] Refactor Security Filter Chains (TDD - REFACTOR phase) [P]
**File**: `src/main/java/com/bitbi/dfm/security/SecurityConfiguration.java`
**Type**: Refactoring
**Priority**: Medium
**Description**:
- Extract common filter configuration to private methods if duplication exists
- Add MDC logging for authentication attempts (log token type: JWT vs Keycloak)
- Add JavaDoc comments explaining filter chain precedence
- Ensure code follows Spring Boot 3.5.6 best practices

**Acceptance**:
- T003 and T005 tests still GREEN after refactoring
- Code quality improved (reduced duplication, better readability)
- MDC logging adds `tokenType` to log context

**Dependencies**: T005 (integration tests passing)

---

**✅ CHECKPOINT**: Security filter chains complete. All subsequent user stories can now safely delegate to these security configurations.

---

## Phase 4: User Story 1 - Device API (P1)

**Story Goal**: Device clients can authenticate and perform batch operations (start, upload, complete, error logging) via `/api/v1/device/*` endpoints with Custom JWT.

**Independent Test**: Authenticate device → start batch → upload file → complete batch → log error. All operations succeed via new endpoints.

**Story Completion Criteria**:
- ✅ 4 Device API controllers implemented (Auth, Batch, File, Error)
- ✅ All 11 Device API endpoints functional
- ✅ Custom JWT authentication enforced
- ✅ Contract tests pass for all Device API endpoints
- ✅ Integration tests verify end-to-end device workflows

**Duration**: 4 hours

---

### T007: [US1] Write Device Auth Controller Contract Tests
**File**: `src/test/java/com/bitbi/dfm/device/presentation/DeviceAuthContractTest.java`
**Type**: Contract Test (TDD - RED)
**Priority**: P1
**Description**:
Write MockMvc tests for Device Auth endpoints:

**Test Cases**:
1. **TC07**: `POST /api/v1/device/auth/token` with valid Basic Auth → 200 OK + JWT token
2. **TC08**: `POST /api/v1/device/auth/token` with invalid credentials → 401 Unauthorized
3. **TC09**: `POST /api/v1/device/auth/token` with missing Authorization header → 401 Unauthorized

**Acceptance**:
- All 3 tests written and FAILING
- Tests verify `TokenResponseDto` structure (token, expiresAt, siteId, domain)
- Uses `MockMvc.perform(post(ApiRoutes.DEVICE_AUTH_TOKEN))`

**Dependencies**: T001 (ApiRoutes), T004 (security filter chains)

---

### T008: [US1] Implement DeviceAuthController [P]
**File**: `src/main/java/com/bitbi/dfm/device/presentation/DeviceAuthController.java`
**Type**: Implementation (TDD - GREEN)
**Priority**: P1
**Description**:
- Create `DeviceAuthController` with `@RestController` and `@RequestMapping(ApiRoutes.DEVICE_AUTH)`
- Add `@Tag(name = "Device API - Authentication", description = "...")` for OpenAPI grouping
- Add `@SecurityRequirement(name = "basicAuth")` for OpenAPI
- Implement `POST /token` endpoint delegating to existing `AuthService.generateToken()`
- Return `TokenResponseDto`

**Acceptance**:
- T007 tests turn GREEN
- Controller delegates to existing service (zero business logic duplication)
- OpenAPI shows endpoint in "Device API" group
- No compilation errors

**Dependencies**: T007 (tests written)

---

### T009: [US1] Write Device Batch Controller Contract Tests
**File**: `src/test/java/com/bitbi/dfm/device/presentation/DeviceBatchContractTest.java`
**Type**: Contract Test (TDD - RED)
**Priority**: P1
**Description**:
Write MockMvc tests for Device Batch endpoints:

**Test Cases**:
1. **TC10**: `POST /api/v1/device/batches/start` with valid JWT → 201 Created + BatchResponseDto
2. **TC11**: `POST /api/v1/device/batches/{id}/complete` with valid JWT → 200 OK
3. **TC12**: `POST /api/v1/device/batches/{id}/fail` with valid JWT → 200 OK
4. **TC13**: `POST /api/v1/device/batches/{id}/cancel` with valid JWT → 200 OK
5. **TC14**: `GET /api/v1/device/batches/{id}` with valid JWT → 200 OK + BatchResponseDto
6. **TC15**: `POST /api/v1/device/batches/start` with Keycloak token → 403 Forbidden (verify filter rejection)

**Acceptance**:
- All 6 tests written and FAILING
- Tests use valid Custom JWT mock
- Verify `BatchResponseDto` structure unchanged

**Dependencies**: T001 (ApiRoutes), T004 (security filter chains)

---

### T010: [US1] Implement DeviceBatchController [P]
**File**: `src/main/java/com/bitbi/dfm/device/presentation/DeviceBatchController.java`
**Type**: Implementation (TDD - GREEN)
**Priority**: P1
**Description**:
- Create `DeviceBatchController` with `@RestController` and `@RequestMapping(ApiRoutes.DEVICE_BATCHES)`
- Add `@Tag(name = "Device API - Batches", description = "...")` and `@SecurityRequirement(name = "bearerAuth")`
- Implement 5 endpoints: start, complete, fail, cancel, get
- Delegate to existing `BatchService` methods
- Return `BatchResponseDto` (unchanged from old endpoints)

**Acceptance**:
- T009 tests turn GREEN
- All endpoints delegate to existing service
- OpenAPI shows endpoints in "Device API - Batches" group
- No business logic duplication

**Dependencies**: T009 (tests written)

---

### T011: [US1] Write Device File Controller Contract Tests
**File**: `src/test/java/com/bitbi/dfm/device/presentation/DeviceFileContractTest.java`
**Type**: Contract Test (TDD - RED)
**Priority**: P1
**Description**:
Write MockMvc tests for Device File endpoints:

**Test Cases**:
1. **TC16**: `POST /api/v1/device/files/batches/{batchId}/upload` with multipart file + valid JWT → 201 Created + FileUploadResponseDto
2. **TC17**: `POST /api/v1/device/files/batches/{batchId}/upload` with missing file → 400 Bad Request
3. **TC18**: `GET /api/v1/device/files/batches/{batchId}/files/{fileId}` with valid JWT → 200 OK + FileMetadataDto

**Acceptance**:
- All 3 tests written and FAILING
- Tests use `MockMultipartFile` for upload test
- Verify `FileUploadResponseDto` structure unchanged

**Dependencies**: T001 (ApiRoutes), T004 (security filter chains)

---

### T012: [US1] Implement DeviceFileController [P]
**File**: `src/main/java/com/bitbi/dfm/device/presentation/DeviceFileController.java`
**Type**: Implementation (TDD - GREEN)
**Priority**: P1
**Description**:
- Create `DeviceFileController` with `@RestController` and `@RequestMapping(ApiRoutes.DEVICE_FILES)`
- Add `@Tag(name = "Device API - Files", description = "...")` and `@SecurityRequirement(name = "bearerAuth")`
- Implement 2 endpoints: upload (multipart), getFile
- Delegate to existing `FileUploadService` methods
- Return `FileUploadResponseDto` and `FileMetadataDto`

**Acceptance**:
- T011 tests turn GREEN
- Multipart file handling works identically to old endpoint
- S3 upload logic unchanged (delegated to service)
- OpenAPI shows endpoints in "Device API - Files" group

**Dependencies**: T011 (tests written)

---

### T013: [US1] Write Device Error Controller Contract Tests
**File**: `src/test/java/com/bitbi/dfm/device/presentation/DeviceErrorContractTest.java`
**Type**: Contract Test (TDD - RED)
**Priority**: P1
**Description**:
Write MockMvc tests for Device Error endpoints:

**Test Cases**:
1. **TC19**: `POST /api/v1/device/errors` with valid error payload + JWT → 201 Created + ErrorLogResponseDto
2. **TC20**: `POST /api/v1/device/errors/batches/{batchId}` with valid error payload + JWT → 201 Created + ErrorLogResponseDto
3. **TC21**: `GET /api/v1/device/errors/{errorId}` with valid JWT → 200 OK + ErrorLogResponseDto
4. **TC22**: `POST /api/v1/device/errors` with invalid payload → 400 Bad Request

**Acceptance**:
- All 4 tests written and FAILING
- Tests verify `ErrorLogResponseDto` structure unchanged
- Validation errors return appropriate 400 responses

**Dependencies**: T001 (ApiRoutes), T004 (security filter chains)

---

### T014: [US1] Implement DeviceErrorController [P]
**File**: `src/main/java/com/bitbi/dfm/device/presentation/DeviceErrorController.java`
**Type**: Implementation (TDD - GREEN)
**Priority**: P1
**Description**:
- Create `DeviceErrorController` with `@RestController` and `@RequestMapping(ApiRoutes.DEVICE_ERRORS)`
- Add `@Tag(name = "Device API - Errors", description = "...")` and `@SecurityRequirement(name = "bearerAuth")`
- Implement 3 endpoints: logError (standalone), logError (with batchId), getError
- Delegate to existing `ErrorLogService` methods
- Return `ErrorLogResponseDto`

**Acceptance**:
- T013 tests turn GREEN
- Error logging behavior identical to old endpoints
- Partitioned table inserts work correctly (delegated to service)
- OpenAPI shows endpoints in "Device API - Errors" group

**Dependencies**: T013 (tests written)

---

### T015: [US1] Write Device API Integration Test
**File**: `src/test/java/com/bitbi/dfm/integration/DeviceApiIntegrationTest.java`
**Type**: Integration Test
**Priority**: P1
**Description**:
Write end-to-end device workflow integration test:

**Test Scenario**:
1. Generate token via `/api/v1/device/auth/token` with Basic Auth
2. Start batch via `/api/v1/device/batches/start` with JWT
3. Upload file via `/api/v1/device/files/batches/{batchId}/upload` with JWT
4. Log error via `/api/v1/device/errors/batches/{batchId}` with JWT
5. Complete batch via `/api/v1/device/batches/{id}/complete` with JWT
6. Verify batch status = COMPLETED

**Acceptance**:
- Full workflow test passes
- Uses `@SpringBootTest` with Testcontainers (PostgreSQL + LocalStack S3)
- Verifies S3 file upload occurred
- Verifies error log persisted to database

**Dependencies**: T008, T010, T012, T014 (all Device API controllers implemented)

---

### T016: [US1] Refactor Device API Controllers (TDD - REFACTOR) [P]
**File**: All Device API controllers
**Type**: Refactoring
**Priority**: Low
**Description**:
- Extract common error handling to base controller if duplication exists
- Add JavaDoc comments for all public methods
- Ensure consistent exception handling (GlobalExceptionHandler integration)
- Verify OpenAPI annotations complete

**Acceptance**:
- All contract tests (T007, T009, T011, T013) still GREEN
- Integration test (T015) still GREEN
- Code quality improved

**Dependencies**: T015 (integration test passing)

---

**✅ CHECKPOINT**: User Story 1 complete. Device API fully functional and tested. Can be deployed independently.

---

## Phase 5: User Story 2 - UI/Admin API (P1)

**Story Goal**: Web UI can manage accounts, sites, batches, view upload history, analyze errors, and perform comparisons via `/api/v1/*` endpoints with Keycloak OAuth2.

**Independent Test**: Authenticate via Keycloak → CRUD accounts → CRUD sites → view batch history → view errors. All operations succeed via new endpoints.

**Story Completion Criteria**:
- ✅ 6 admin controller classes updated with new paths
- ✅ All 30+ UI/Admin API endpoints functional
- ✅ Keycloak OAuth2 authentication enforced
- ✅ Contract tests updated for all admin endpoints
- ✅ Integration tests verify admin workflows

**Duration**: 4 hours

---

### T017: [US2] Update AccountAdminController to New Path
**File**: `src/main/java/com/bitbi/dfm/account/presentation/AccountAdminController.java`
**Type**: Implementation
**Priority**: P1
**Description**:
- Update `@RequestMapping` from `/api/admin/accounts` to `ApiRoutes.ACCOUNTS`
- Update `@Tag(name = "UI/Admin API - Accounts", description = "...")`
- Verify `@SecurityRequirement(name = "oauth2")` present
- Update all endpoint paths to use ApiRoutes constants
- No business logic changes (delegate to existing services)

**Acceptance**:
- Controller compiles without errors
- All endpoints now under `/api/v1/accounts`
- OpenAPI shows endpoints in "UI/Admin API - Accounts" group
- Service delegation unchanged

**Dependencies**: T001 (ApiRoutes), T004 (security filter chains)

---

### T018: [US2] Update AccountAdminController Contract Tests [P]
**File**: `src/test/java/com/bitbi/dfm/account/presentation/AccountAdminControllerTest.java`
**Type**: Contract Test Update
**Priority**: P1
**Description**:
- Update all MockMvc `.perform(get(...))` calls to use `ApiRoutes.ACCOUNTS` constants
- Update test to verify Keycloak OAuth2 token required
- Add test verifying Custom JWT token rejected (403 Forbidden)
- Ensure all existing test assertions remain unchanged

**Test Cases to Update/Add**:
1. All existing tests (update paths only)
2. **TC23**: Request with Custom JWT → 403 Forbidden (new test)

**Acceptance**:
- All existing tests GREEN with new paths
- New security test (TC23) passes
- Test coverage ≥80% maintained

**Dependencies**: T017 (controller updated)

---

### T019: [US2] Update SiteAdminController to New Path
**File**: `src/main/java/com/bitbi/dfm/site/presentation/SiteAdminController.java`
**Type**: Implementation
**Priority**: P1
**Description**:
- Update `@RequestMapping` from `/api/admin/sites` to `ApiRoutes.SITES`
- Update `@Tag(name = "UI/Admin API - Sites", description = "...")`
- Verify `@SecurityRequirement(name = "oauth2")` present
- Update all endpoint paths to use ApiRoutes constants
- No business logic changes

**Acceptance**:
- Controller compiles without errors
- All endpoints now under `/api/v1/sites`
- OpenAPI shows endpoints in "UI/Admin API - Sites" group

**Dependencies**: T001 (ApiRoutes), T004 (security filter chains)

---

### T020: [US2] Update SiteAdminController Contract Tests [P]
**File**: `src/test/java/com/bitbi/dfm/site/presentation/SiteAdminControllerTest.java`
**Type**: Contract Test Update
**Priority**: P1
**Description**:
- Update all paths to use `ApiRoutes.SITES` constants
- Add test verifying Custom JWT token rejected (403 Forbidden)
- Ensure all existing test assertions unchanged

**Acceptance**:
- All tests GREEN with new paths
- Security test passes
- Coverage maintained

**Dependencies**: T019 (controller updated)

---

### T021: [US2] Delete Obsolete SiteController
**File**: `src/main/java/com/bitbi/dfm/site/presentation/SiteController.java`
**Type**: Deletion
**Priority**: P1
**Description**:
- Delete `SiteController.java` (duplicate, no longer needed)
- Verify no references to this controller in codebase (search imports)
- Ensure tests do not reference deleted controller

**Acceptance**:
- File deleted
- No compilation errors
- No broken imports

**Dependencies**: T019, T020 (SiteAdminController fully migrated)

---

### T022: [US2] Update BatchAdminController to New Path
**File**: `src/main/java/com/bitbi/dfm/batch/presentation/BatchAdminController.java`
**Type**: Implementation
**Priority**: P1
**Description**:
- Update `@RequestMapping` from `/api/admin/batches` to `ApiRoutes.BATCHES_ADMIN`
- Update `@Tag(name = "UI/Admin API - Batches", description = "...")`
- Update paths to use ApiRoutes constants
- No business logic changes

**Acceptance**:
- Controller compiles
- Endpoints under `/api/v1/batches`
- OpenAPI grouping correct

**Dependencies**: T001 (ApiRoutes), T004 (security filter chains)

---

### T023: [US2] Update BatchAdminController Contract Tests [P]
**File**: `src/test/java/com/bitbi/dfm/batch/presentation/BatchAdminControllerTest.java`
**Type**: Contract Test Update
**Priority**: P1
**Description**:
- Update all paths to use `ApiRoutes.BATCHES_ADMIN` constants
- Add security test for Custom JWT rejection (403)
- Ensure assertions unchanged

**Acceptance**:
- All tests GREEN with new paths
- Security test passes

**Dependencies**: T022 (controller updated)

---

### T024: [US2] Update BatchHistoryController and BatchHistoryAdminController to New Paths
**Files**:
- `src/main/java/com/bitbi/dfm/batch/presentation/BatchHistoryController.java`
- `src/main/java/com/bitbi/dfm/batch/presentation/BatchHistoryAdminController.java`
**Type**: Implementation
**Priority**: P1
**Description**:
- Update both controllers from `/api/user/batches` to `ApiRoutes.HISTORY_BATCHES`
- Update `@Tag(name = "UI/Admin API - Upload History", description = "...")`
- Update all endpoint paths to use ApiRoutes constants (`HISTORY_FILE_DOWNLOAD`, `HISTORY_ZIP_DOWNLOAD`, `HISTORY_EXCEL_EXPORT`, `HISTORY_ERRORS`)
- No business logic changes

**Acceptance**:
- Both controllers compile
- Endpoints under `/api/v1/history/batches`
- OpenAPI grouping correct

**Dependencies**: T001 (ApiRoutes), T004 (security filter chains)

---

### T025: [US2] Update BatchHistory Contract Tests [P]
**File**: `src/test/java/com/bitbi/dfm/batch/presentation/BatchHistoryContractTest.java`
**Type**: Contract Test Update
**Priority**: P1
**Description**:
- Update all paths to use `ApiRoutes.HISTORY_BATCHES` and related constants
- Add security test for Custom JWT rejection
- Test file download, ZIP download, Excel export with new paths
- Ensure assertions unchanged

**Acceptance**:
- All tests GREEN with new paths
- Security test passes
- Download/export tests work

**Dependencies**: T024 (controllers updated)

---

### T026: [US2] Update ErrorAdminController to New Path
**File**: `src/main/java/com/bitbi/dfm/error/presentation/ErrorAdminController.java`
**Type**: Implementation
**Priority**: P1
**Description**:
- Update `@RequestMapping` from `/api/admin/errors` to `ApiRoutes.ERRORS_ADMIN`
- Update `@Tag(name = "UI/Admin API - Errors", description = "...")`
- Update paths to use ApiRoutes constants (`ERRORS_EXPORT`)
- No business logic changes

**Acceptance**:
- Controller compiles
- Endpoints under `/api/v1/errors`
- OpenAPI grouping correct

**Dependencies**: T001 (ApiRoutes), T004 (security filter chains)

---

### T027: [US2] Update ErrorAdminController Contract Tests [P]
**File**: `src/test/java/com/bitbi/dfm/error/presentation/ErrorAdminControllerTest.java`
**Type**: Contract Test Update
**Priority**: P1
**Description**:
- Update all paths to use `ApiRoutes.ERRORS_ADMIN` constants
- Add security test for Custom JWT rejection
- Test error export with new path
- Ensure assertions unchanged

**Acceptance**:
- All tests GREEN with new paths
- Security test passes

**Dependencies**: T026 (controller updated)

---

### T028: [US2] Verify ComparisonController Already Compliant
**File**: `src/main/java/com/bitbi/dfm/comparison/presentation/ComparisonController.java`
**Type**: Verification (No Code Changes)
**Priority**: P1
**Description**:
- Verify `ComparisonController` already uses `/api/v1/comparisons` (compliant)
- Verify `@Tag` name includes "UI/Admin API" grouping
- Verify `@SecurityRequirement(name = "oauth2")` present
- Update `@RequestMapping` to use `ApiRoutes.COMPARISONS` constant for consistency

**Acceptance**:
- Controller verified compliant or minor constant reference added
- No functional changes
- OpenAPI grouping correct

**Dependencies**: T001 (ApiRoutes)

---

### T029: [US2] Update ComparisonController Contract Tests [P]
**File**: `src/test/java/com/bitbi/dfm/comparison/presentation/ComparisonContractTest.java`
**Type**: Contract Test Update
**Priority**: P1
**Description**:
- Update paths to use `ApiRoutes.COMPARISONS` constants (if not already)
- Add security test for Custom JWT rejection (if not exists)
- Ensure all comparison endpoints tested with new path references

**Acceptance**:
- All tests GREEN
- Security test passes (if added)
- Constants used throughout

**Dependencies**: T028 (controller verified)

---

### T030: [US2] Write UI/Admin API Integration Test
**File**: `src/test/java/com/bitbi/dfm/integration/AdminApiIntegrationTest.java`
**Type**: Integration Test
**Priority**: P1
**Description**:
Write end-to-end admin workflow integration test:

**Test Scenario**:
1. Authenticate with Keycloak token
2. Create account via `/api/v1/accounts` (ROLE_ADMIN)
3. Create site via `/api/v1/accounts/{accountId}/sites` (ROLE_ADMIN)
4. List batches via `/api/v1/batches` (ROLE_ADMIN)
5. View upload history via `/api/v1/history/batches` (authenticated user)
6. View errors via `/api/v1/errors` (ROLE_ADMIN)
7. Create comparison via `/api/v1/comparisons` (authenticated user)

**Acceptance**:
- Full admin workflow test passes
- Uses `@SpringBootTest` with Testcontainers
- Verifies Keycloak token required
- Verifies ROLE_ADMIN enforcement

**Dependencies**: T017-T029 (all admin controllers updated)

---

**✅ CHECKPOINT**: User Story 2 complete. UI/Admin API fully functional and tested. Can be deployed independently.

---

## Phase 6: User Story 3 - Migration Support (P2)

**Story Goal**: Provide migration guide and 410 Gone responses for old endpoints to help existing integrations migrate smoothly.

**Independent Test**: Calling old endpoint paths returns 410 Gone with migration guidance. Migration guide provides 100% endpoint mapping.

**Story Completion Criteria**:
- ✅ DeprecatedEndpointFilter returns 410 Gone for old paths
- ✅ Error messages include new endpoint path
- ✅ Migration guide (quickstart.md) complete with code examples
- ✅ Contract tests verify 410 Gone responses
- ✅ API_MIGRATION.md created for frontend developers

**Duration**: 2 hours

---

### T031: [US3] Write DeprecatedEndpointFilter Contract Tests
**File**: `src/test/java/com/bitbi/dfm/shared/filter/DeprecatedEndpointFilterTest.java`
**Type**: Contract Test (TDD - RED)
**Priority**: P2
**Description**:
Write MockMvc tests for deprecated endpoint handling:

**Test Cases**:
1. **TC24**: `POST /api/dfc/batch/start` → 410 Gone + error message with `/api/v1/device/batches/start`
2. **TC25**: `GET /api/admin/accounts` → 410 Gone + error message with `/api/v1/accounts`
3. **TC26**: `GET /api/user/batches` → 410 Gone + error message with `/api/v1/history/batches`
4. **TC27**: `POST /api/v1/device/batches/start` → Not intercepted by filter (new endpoint works)

**Acceptance**:
- All 4 tests written and FAILING
- Tests verify `ErrorResponseDto` structure with 410 status
- Tests verify new path included in error message

**Dependencies**: T001 (ApiRoutes)

---

### T032: [US3] Implement DeprecatedEndpointFilter [P]
**File**: `src/main/java/com/bitbi/dfm/shared/filter/DeprecatedEndpointFilter.java`
**Type**: Implementation (TDD - GREEN)
**Priority**: P2
**Description**:
- Create filter extending `OncePerRequestFilter`
- Annotate with `@Component` and `@Order(Ordered.HIGHEST_PRECEDENCE)`
- Define map of old → new endpoint paths (all 40+ mappings)
- Intercept requests matching old patterns
- Return 410 Gone with `ErrorResponseDto` containing new path in message
- Use pattern matching for path variables (`{id}`, `{batchId}`, etc.)

**Acceptance**:
- T031 tests turn GREEN
- Filter runs before security filters
- All old endpoint paths mapped to new paths
- 410 Gone responses include migration guidance

**Dependencies**: T031 (tests written)
**Reference**: See `research.md` Decision 4 for implementation pattern

---

### T033: [US3] Create API Migration Guide Document
**File**: `API_MIGRATION.md` (repository root)
**Type**: Documentation
**Priority**: P2
**Description**:
- Copy content from `specs/010-api-unification-goal/quickstart.md` to root-level `API_MIGRATION.md`
- Ensure endpoint mapping table complete (all 40+ endpoints)
- Include code examples for Java device clients and TypeScript frontend
- Add troubleshooting section for 410 Gone, 403 Forbidden errors
- Add migration checklist for device clients and frontend apps

**Acceptance**:
- `API_MIGRATION.md` exists at repository root
- Contains complete endpoint mapping
- Includes code examples for both client types
- Includes configuration file update examples

**Dependencies**: quickstart.md already exists (Phase 1 planning)

---

### T034: [US3] Update README.md with Migration Notice
**File**: `README.md` (repository root)
**Type**: Documentation
**Priority**: P2
**Description**:
- Add "API Migration" section to README.md
- Link to `API_MIGRATION.md`
- Note coordinated deployment requirement
- Provide migration deadline (if known) or "TBD"

**Acceptance**:
- README.md updated with migration section
- Link to migration guide works
- Clear call-to-action for API consumers

**Dependencies**: T033 (migration guide created)

---

**✅ CHECKPOINT**: User Story 3 complete. Migration support in place. Old endpoints return helpful 410 Gone responses.

---

## Phase 7: Cleanup & Polish

**Goal**: Remove obsolete code, verify all tests pass, ensure documentation complete.

**Duration**: 1 hour

---

### T035: Delete Obsolete Device API Controllers
**Files**:
- `src/main/java/com/bitbi/dfm/auth/presentation/AuthController.java`
- `src/main/java/com/bitbi/dfm/batch/presentation/BatchController.java`
- `src/main/java/com/bitbi/dfm/upload/presentation/FileUploadController.java`
- `src/main/java/com/bitbi/dfm/error/presentation/ErrorLogController.java`
**Type**: Deletion
**Priority**: Low
**Description**:
- Delete all 4 obsolete Device API controllers (replaced by new Device API controllers)
- Verify no references to these controllers in codebase
- Ensure old tests referencing these controllers are deleted or updated

**Acceptance**:
- 4 controller files deleted
- No compilation errors
- No broken imports
- Old contract tests for these controllers deleted

**Dependencies**: T016 (Device API fully implemented), T031-T032 (410 Gone responses ready)

---

### T036: Run Full Test Suite and Verify Coverage [P]
**Files**: All test files
**Type**: Verification
**Priority**: High
**Description**:
- Run `./gradlew test` - all unit and contract tests must pass
- Run `./gradlew integrationTest` - all integration tests must pass
- Run `./gradlew jacocoTestReport` - verify coverage ≥80%
- Review coverage report: `/build/reports/jacoco/test/html/index.html`

**Acceptance**:
- ✅ All tests GREEN (0 failures)
- ✅ Coverage ≥80% overall
- ✅ No compilation warnings
- ✅ No test flakiness

**Dependencies**: All implementation tasks complete

---

### T037: Performance Benchmark - Verify Response Times Within ±5% [P]
**File**: `src/test/java/com/bitbi/dfm/performance/ApiPerformanceTest.java`
**Type**: Performance Test
**Priority**: Medium
**Description**:
- Write JMeter or Gatling performance test (or simple loop test)
- Benchmark key endpoints before and after migration:
  - Device API: token generation, start batch, upload file
  - Admin API: list accounts, list sites, list batches
- Verify response times within ±5% of baseline

**Test Cases**:
1. Device token generation: <200ms p95
2. Start batch: <300ms p95
3. List accounts: <500ms p95
4. List batches: <500ms p95

**Acceptance**:
- All endpoints within ±5% of baseline performance
- No performance regression detected
- Report generated with metrics

**Dependencies**: T036 (all tests passing)

---

### T038: Update CLAUDE.md with Final Context [P]
**File**: `CLAUDE.md` (repository root)
**Type**: Documentation
**Priority**: Low
**Description**:
- Add section documenting API Unification refactoring
- Note new path structure: Device API (`/api/v1/device/*`), UI/Admin API (`/api/v1/*`)
- Document security filter chain precedence (`@Order(1)` vs `@Order(2)`)
- Link to API_MIGRATION.md for endpoint mappings

**Acceptance**:
- CLAUDE.md updated with API structure documentation
- Links to relevant files (ApiRoutes.java, SecurityConfiguration.java)
- Future developers understand API organization

**Dependencies**: T033 (migration guide exists)

---

### T039: Final Code Review Checklist
**Type**: Review
**Priority**: High
**Description**:
Execute final code review checklist:

**Checklist**:
- [ ] All obsolete controllers deleted (5 files)
- [ ] All new Device API controllers implemented (4 files)
- [ ] All admin controllers updated to new paths (6 files)
- [ ] ApiRoutes.java constants used throughout (no hardcoded paths)
- [ ] Security filter chains configured with correct `@Order` precedence
- [ ] OpenAPI documentation separates Device API and UI/Admin API
- [ ] All contract tests updated to new paths
- [ ] All integration tests pass
- [ ] Performance within ±5% of baseline
- [ ] Coverage ≥80%
- [ ] Migration guide complete (API_MIGRATION.md)
- [ ] 410 Gone filter implemented and tested
- [ ] README.md updated with migration notice
- [ ] CLAUDE.md updated with new API structure
- [ ] No compilation warnings
- [ ] No TODO comments left in code

**Acceptance**:
- All checklist items verified
- Code ready for pull request

**Dependencies**: All previous tasks complete

---

**✅ FINAL CHECKPOINT**: All user stories complete. Feature ready for deployment.

---

## Task Dependencies Graph

```
Phase 1: Setup
├── T001 (ApiRoutes.java) ────────────┐
└── T002 (OpenAPI Config)             │
                                      │
Phase 2: Foundational (CRITICAL PATH) │
├── T003 (Security Tests) ◄───────────┤
├── T004 (Security Implementation) ◄──┤
├── T005 (Security Integration Tests) │
└── T006 (Security Refactor)          │
                                      │
Phase 3: User Story 4 (Security) ─────┘
[T003-T006 already listed above]

Phase 4: User Story 1 (Device API)
├── T007 (DeviceAuth Tests) ◄─────────── T001, T004
├── T008 (DeviceAuth Impl)
├── T009 (DeviceBatch Tests) ◄────────── T001, T004
├── T010 (DeviceBatch Impl)
├── T011 (DeviceFile Tests) ◄─────────── T001, T004
├── T012 (DeviceFile Impl)
├── T013 (DeviceError Tests) ◄────────── T001, T004
├── T014 (DeviceError Impl)
├── T015 (Device Integration Test) ◄──── T008, T010, T012, T014
└── T016 (Device Refactor) ◄──────────── T015

Phase 5: User Story 2 (Admin API)
├── T017 (AccountAdmin Update) ◄──────── T001, T004
├── T018 (AccountAdmin Tests) ◄───────── T017
├── T019 (SiteAdmin Update) ◄─────────── T001, T004
├── T020 (SiteAdmin Tests) ◄──────────── T019
├── T021 (Delete SiteController) ◄────── T019, T020
├── T022 (BatchAdmin Update) ◄────────── T001, T004
├── T023 (BatchAdmin Tests) ◄─────────── T022
├── T024 (BatchHistory Update) ◄──────── T001, T004
├── T025 (BatchHistory Tests) ◄───────── T024
├── T026 (ErrorAdmin Update) ◄────────── T001, T004
├── T027 (ErrorAdmin Tests) ◄─────────── T026
├── T028 (Comparison Verify) ◄────────── T001
├── T029 (Comparison Tests) ◄─────────── T028
└── T030 (Admin Integration Test) ◄───── T017-T029

Phase 6: User Story 3 (Migration)
├── T031 (DeprecatedFilter Tests) ◄───── T001
├── T032 (DeprecatedFilter Impl) ◄────── T031
├── T033 (Migration Guide) ◄──────────── quickstart.md
└── T034 (README Update) ◄────────────── T033

Phase 7: Polish
├── T035 (Delete Obsolete) ◄──────────── T016, T032
├── T036 (Full Test Suite) ◄──────────── All tasks
├── T037 (Performance Benchmark) ◄────── T036
├── T038 (CLAUDE.md Update) ◄─────────── T033
└── T039 (Final Review) ◄─────────────── All tasks
```

---

## Parallelization Opportunities

**Phase 1**: T001 and T002 can run in parallel (different files)

**Phase 2**: Sequential (security filter chains must be tested first)

**Phase 4 (US1 - Device API)**:
- T008, T010, T012, T014 can run in parallel AFTER their respective tests pass (different controller files)
- T007, T009, T011, T013 can run in parallel (different test files)

**Phase 5 (US2 - Admin API)**:
- T017, T019, T022, T024, T026, T028 can run in parallel AFTER T001/T004 (different controller files)
- T018, T020, T023, T025, T027, T029 can run in parallel AFTER controllers updated (different test files)

**Phase 6 (US3 - Migration)**:
- T033 and T034 can run in parallel AFTER T032 (different files)

**Phase 7 (Polish)**:
- T036, T037, T038 can run in parallel (different concerns)

**Estimated Parallel Speedup**: 30-40% reduction in total time if tasks parallelized optimally

---

## Implementation Strategy

### MVP Scope (User Story 4 + User Story 1)
**Duration**: 6 hours
**Deliverable**: Device API fully functional with security filter routing

**Tasks**: T001-T016 (Phase 1-4)
**Value**: Device clients can use new unified API immediately

### Full Release (All User Stories)
**Duration**: 16-24 hours total
**Deliverable**: Complete API unification with migration support

**Tasks**: T001-T039 (All phases)
**Value**: Complete system with device API, admin API, and migration support

---

## Testing Strategy

### TDD Workflow (Per Constitution)
1. **RED**: Write failing contract test
2. **GREEN**: Implement minimal code to pass test
3. **REFACTOR**: Improve code quality while keeping tests green

**Example for DeviceAuthController**:
1. T007: Write failing tests → Tests FAIL (RED)
2. T008: Implement controller → Tests PASS (GREEN)
3. T016: Refactor if needed → Tests still PASS (REFACTOR)

### Test Coverage Requirements
- Overall: ≥80% line coverage
- Security filter chains: 100% coverage (critical path)
- Controllers: ≥80% coverage
- Integration tests: Full workflows tested

### Test Execution Order
1. Unit tests (fastest feedback)
2. Contract tests (API contracts verified)
3. Integration tests (end-to-end workflows)
4. Performance tests (non-functional requirements)

---

## Rollback Plan

If deployment fails:
1. Revert backend deployment to previous version
2. Old endpoints become active again
3. Device clients and frontend continue using old paths
4. Fix issues and re-attempt coordinated deployment

**Recovery Time Objective**: <1 hour
**Risk**: Low (refactoring only, no schema changes)

---

## Success Metrics

- ✅ All 39 tasks completed
- ✅ 5 obsolete controllers deleted
- ✅ 4 new Device API controllers created
- ✅ 6 admin controllers updated
- ✅ 40+ endpoints migrated
- ✅ All tests GREEN (0 failures)
- ✅ Coverage ≥80%
- ✅ Performance within ±5%
- ✅ Migration guide complete
- ✅ 410 Gone responses functional
- ✅ Security filter routing correct (403 for mismatched tokens)

---

## Implementation Notes

1. **TDD Discipline**: Follow RED-GREEN-REFACTOR cycle strictly per constitution
2. **No Business Logic Changes**: All controllers delegate to existing services
3. **Path Constants**: Use ApiRoutes.java throughout - no hardcoded paths
4. **Security Testing**: Verify token rejection (403) for every API group
5. **Performance Monitoring**: Benchmark before deploying to production
6. **Documentation**: Keep migration guide and README.md updated

---

**Status**: Ready for implementation via `/speckit.implement` command

**Next Step**: Execute tasks in order, starting with Phase 1 (Setup & Infrastructure)

**Estimated Completion**: 2-3 days (16-24 hours) with parallelization
