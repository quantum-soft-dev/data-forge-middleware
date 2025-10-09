# Tasks: Additions to BACKEND

**Branch**: `002-additions-to-backend` | **Date**: 2025-10-09
**Input**: Design documents from `/specs/002-additions-to-backend/`
**Prerequisites**: plan.md ✓, data-model.md ✓, contracts/ ✓, quickstart.md ✓

## Execution Flow (main)
```
1. Load plan.md from feature directory ✓
   → Tech stack: Java 21, Spring Boot 3.5.6, Spring Security, Lombok, Jackson
   → Structure: Single backend API (monolithic Spring Boot)
2. Load design documents ✓
   → data-model.md: 8 DTO entities (BatchResponseDto, ErrorLogResponseDto, FileUploadResponseDto, AccountResponseDto, SiteResponseDto, TokenResponseDto, ErrorResponseDto, PageResponseDto<T>)
   → contracts/: 6 OpenAPI YAML files (batch, error-log, file-upload, account, site, auth-security)
   → quickstart.md: 7 end-to-end test scenarios
3. Generate tasks by category ✓
   → Foundation: Shared DTOs and security infrastructure (6 tasks)
   → Feature DTOs: Per-domain DTO creation (48 tasks for 6 domains)
   → Authentication: Dual auth integration tests (5 tasks)
   → E2E Integration: Quickstart scenario tests (7 tasks)
   → Documentation: OpenAPI and validation (4 tasks)
4. Apply task rules ✓
   → [P] for independent files (different DTO packages, different test files)
   → Sequential for shared files (SecurityConfiguration, GlobalExceptionHandler)
   → TDD ordering: Contract tests → Unit tests → Implementation → Integration tests
5. Number tasks sequentially (T001-T070) ✓
6. Dependencies validated ✓
7. Parallel execution examples included ✓
8. Validation: All contracts tested, all entities modeled, TDD enforced ✓
```

## Format: `[ID] [P?] Description`
- **[P]**: Can run in parallel (different files, no shared dependencies)
- All file paths are absolute from repository root

## Path Conventions
- **Single backend project** (monolithic Spring Boot)
- **Source**: `src/main/java/com/bitbi/dfm/`
- **Tests**: `src/test/java/`
- **Resources**: `src/main/resources/`

---

## Phase 3.1: Foundation (Shared Infrastructure)

**CRITICAL**: These tasks create shared components required by all feature DTOs.

### T001: Create ErrorResponseDto record

**Type**: [CODE]
**Dependencies**: None
**Files**:
- `src/main/java/com/bitbi/dfm/shared/presentation/dto/ErrorResponseDto.java`

**Description**:
Create immutable ErrorResponseDto record with fields: timestamp (Instant), status (Integer), error (String), message (String), path (String).

All fields are required (no nullable fields).

**Acceptance Criteria**:
- [X] Record compiles with all 5 fields
- [X] All fields use correct types (Instant, Integer, String)
- [X] No fromEntity() method needed (constructed directly from exceptions)
- [X] Record is in `shared/presentation/dto` package

**FR Mapping**: FR-004, FR-014

---

### T002: Create PageResponseDto<T> generic record

**Type**: [CODE]
**Dependencies**: None
**Files**:
- `src/main/java/com/bitbi/dfm/shared/presentation/dto/PageResponseDto.java`

**Description**:
Create immutable generic PageResponseDto<T> record with fields: content (List<T>), page (Integer), size (Integer), totalElements (Long), totalPages (Integer).

Add static method `of(Page<E> page, Function<E, T> mapper)` to convert Spring Data Page to DTO.

**Acceptance Criteria**:
- [X] Generic record compiles with type parameter T
- [X] All 5 fields present with correct types
- [X] Static `of()` method converts Page<Entity> to PageResponseDto<DTO>
- [X] Method applies mapper function to transform entities

**FR Mapping**: FR-001, FR-002

---

### T003: Update GlobalExceptionHandler to return ErrorResponseDto

**Type**: [CODE]
**Dependencies**: T001
**Files**:
- `src/main/java/com/bitbi/dfm/shared/exception/GlobalExceptionHandler.java`

**Description**:
Update all exception handler methods in GlobalExceptionHandler to return ErrorResponseDto instead of Map<String, Object>.

Update methods:
- handleIllegalArgumentException → 400 Bad Request
- handleAccessDeniedException → 403 Forbidden
- handleNoHandlerFoundException → 404 Not Found
- handleMaxUploadSizeExceededException → 413 Payload Too Large
- handleGenericException → 500 Internal Server Error

Construct ErrorResponseDto with timestamp (Instant.now()), status, error (status reason phrase), message, path (from HttpServletRequest).

**Acceptance Criteria**:
- [X] All handler methods return ResponseEntity<ErrorResponseDto>
- [X] ErrorResponseDto constructed with all 5 fields
- [X] Timestamp uses Instant.now()
- [X] Path extracted from HttpServletRequest.getRequestURI()
- [X] Tests updated to assert ErrorResponseDto structure

**FR Mapping**: FR-004, FR-014

---

### T004: Create DualAuthenticationFilter

**Type**: [CODE]
**Dependencies**: T001
**Files**:
- `src/main/java/com/bitbi/dfm/shared/auth/DualAuthenticationFilter.java`

**Description**:
Create OncePerRequestFilter that detects dual token scenarios before authentication.

Check for both:
1. Authorization header with "Bearer " prefix
2. X-Keycloak-Token header (or second token pattern)

If both present:
- Return 400 Bad Request
- Write ErrorResponseDto to response with message "Ambiguous authentication: multiple tokens provided"
- Set Content-Type: application/json

Otherwise, continue filter chain.

**Acceptance Criteria**:
- [X] Extends OncePerRequestFilter
- [X] Checks for both Authorization and X-Keycloak-Token headers
- [X] Returns 400 when both tokens present
- [X] Writes ErrorResponseDto JSON to response
- [X] Filter registered before Spring Security authentication filters

**FR Mapping**: FR-015

---

### T005: Create AuthenticationAuditLogger

**Type**: [CODE]
**Dependencies**: None
**Files**:
- `src/main/java/com/bitbi/dfm/shared/auth/AuthenticationAuditLogger.java`

**Description**:
Create AuthenticationFailureHandler implementation that logs auth failures with MDC context.

Log structure (JSON in production):
- event: "auth_failure"
- timestamp: ISO-8601
- ip: Client IP from HttpServletRequest
- endpoint: Request URI
- method: HTTP method
- status: 401 or 403
- tokenType: "jwt" or "keycloak" (detect from header)
- message: "Authentication failed"

Use SLF4J with MDC.put() for structured fields.

**Acceptance Criteria**:
- [X] Implements AuthenticationFailureHandler
- [X] Logs with SLF4J logger
- [X] Uses MDC.put() for: ip, endpoint, method, status, tokenType
- [X] Log message contains "auth_failure"
- [X] Token type detected from Authorization header (JWT vs Keycloak)
- [X] MDC context cleared after logging

**FR Mapping**: FR-013

---

### T006: Update SecurityConfiguration with AuthenticationManagerResolver

**Type**: [CODE]
**Dependencies**: T004, T005
**Files**:
- `src/main/java/com/bitbi/dfm/shared/config/SecurityConfiguration.java`

**Description**:
Update Spring Security configuration to use AuthenticationManagerResolver for path/method-based authentication.

Authentication rules:
1. `/api/v1/batch/**`, `/api/v1/error/**`, `/api/v1/upload/**` + GET method → Accept both JWT and Keycloak
2. `/api/v1/batch/**`, `/api/v1/error/**`, `/api/v1/upload/**` + POST/PUT/DELETE/PATCH → JWT only (reject Keycloak with 403)
3. `/api/v1/admin/**` → Keycloak only (reject JWT with 403)
4. `/api/v1/auth/**` → Allow both (unchanged)

Register DualAuthenticationFilter before authentication.
Register AuthenticationAuditLogger as failure handler.

**Acceptance Criteria**:
- [X] AuthenticationManagerResolver resolves based on path + method
- [X] DualAuthenticationFilter registered first
- [X] AuthenticationAuditLogger wired as failure handler
- [X] GET requests on client endpoints accept both tokens
- [X] Write requests on client endpoints accept JWT only
- [X] Admin endpoints accept Keycloak only
- [X] Configuration compiles and Spring context starts

**FR Mapping**: FR-005, FR-006, FR-007, FR-008, FR-009, FR-015

---

## Phase 3.2: Feature DTOs - Batch Domain

### T007: [P] Create BatchResponseDto record

**Type**: [CODE]
**Dependencies**: None
**Files**:
- `src/main/java/com/bitbi/dfm/batch/presentation/dto/BatchResponseDto.java`

**Description**:
Create immutable BatchResponseDto record with fields:
- id (UUID)
- batchId (UUID) - alias for backward compatibility
- siteId (UUID)
- status (String)
- s3Path (String)
- uploadedFilesCount (Integer)
- totalSize (Long)
- hasErrors (Boolean)
- startedAt (Instant)
- completedAt (Instant) - nullable

Add static method `fromEntity(Batch batch)` that:
- Copies all fields
- Converts batch.getStatus() enum to string using .name()
- Returns completedAt as null if batch still active

**Acceptance Criteria**:
- [X] Record compiles with all 10 fields
- [X] completedAt marked as nullable (@Nullable annotation)
- [X] fromEntity() method present and handles null completedAt
- [X] Status converted from enum to String
- [X] Package is `com.bitbi.dfm.batch.presentation.dto`

**FR Mapping**: FR-001, FR-002, FR-003

---

### T008: [P] Write BatchResponseDto unit test

**Type**: [TEST]
**Dependencies**: T007
**Files**:
- `src/test/java/com/bitbi/dfm/batch/presentation/dto/BatchResponseDtoTest.java`

**Description**:
Write JUnit 5 unit test for BatchResponseDto.fromEntity() mapping.

Test cases:
1. fromEntity_shouldMapAllFields() - active batch (completedAt null)
2. fromEntity_shouldMapCompletedBatch() - completed batch (completedAt present)
3. fromEntity_shouldConvertStatusEnumToString()

Mock Batch entity with all fields, call fromEntity(), assert all DTO fields match.

**Acceptance Criteria**:
- [X] Test class in correct package
- [X] Uses JUnit 5 (@Test annotation)
- [X] 3 test methods covering all scenarios
- [X] Asserts all 10 DTO fields
- [X] Verifies completedAt null handling
- [X] Verifies status enum→string conversion

**FR Mapping**: FR-001, FR-002, FR-003

---

### T009: [P] Update BatchController to use BatchResponseDto

**Type**: [CODE]
**Dependencies**: T007
**Files**:
- `src/main/java/com/bitbi/dfm/batch/presentation/BatchController.java`

**Description**:
Update all methods in BatchController to return `ResponseEntity<BatchResponseDto>` instead of `ResponseEntity<Map<String, Object>>`.

Methods to update:
- startBatch() → return BatchResponseDto.fromEntity(batch)
- completeBatch() → return BatchResponseDto.fromEntity(batch)
- failBatch() → return BatchResponseDto.fromEntity(batch)
- cancelBatch() → return BatchResponseDto.fromEntity(batch)
- getBatch() → return BatchResponseDto.fromEntity(batch)

Remove createBatchResponse() and createErrorResponse() helper methods (ErrorResponseDto handled by GlobalExceptionHandler now).

**Acceptance Criteria**:
- [ ] All 5 methods return ResponseEntity<BatchResponseDto>
- [ ] All success responses use BatchResponseDto.fromEntity()
- [ ] Error responses removed (GlobalExceptionHandler handles them)
- [ ] Controller compiles without errors
- [ ] No Map<String, Object> references remain

**FR Mapping**: FR-001, FR-002, FR-003

---

### T010: [P] Write BatchController contract test

**Type**: [TEST]
**Dependencies**: T009
**Files**:
- `src/test/java/contract/BatchContractTest.java`

**Description**:
Update existing BatchContractTest to assert BatchResponseDto schema.

Add/update tests:
1. startBatch_shouldReturnBatchResponseDto() - verify all 10 fields present, correct types
2. getBatch_withJwt_shouldReturnBatchResponseDto() - JWT auth on GET
3. getBatch_withKeycloak_shouldReturnBatchResponseDto() - Keycloak auth on GET
4. startBatch_withKeycloakToken_shouldReturn403() - Keycloak rejected on POST

Use MockMvc with @WebMvcTest(BatchController.class).
Assert JSON fields with jsonPath():
- $.id isString()
- $.uploadedFilesCount isNumber()
- $.hasErrors isBoolean()
- etc.

**Acceptance Criteria**:
- [ ] 4 test methods asserting DTO schema
- [ ] Uses Spring Security Test (jwt(), opaqueToken())
- [ ] Verifies all field types match OpenAPI schema
- [ ] Tests both JWT and Keycloak authentication
- [ ] Tests fail before implementation (RED phase)

**FR Mapping**: FR-001, FR-002, FR-005, FR-006, FR-007

---

## Phase 3.3: Feature DTOs - Error Log Domain

### T011: [P] Create ErrorLogResponseDto record

**Type**: [CODE]
**Dependencies**: None
**Files**:
- `src/main/java/com/bitbi/dfm/error/presentation/dto/ErrorLogResponseDto.java`

**Description**:
Create immutable ErrorLogResponseDto record with fields:
- id (UUID)
- batchId (UUID)
- severity (String)
- message (String)
- source (String)
- metadata (Map<String, Object>) - nullable
- occurredAt (Instant)

Add static method `fromEntity(ErrorLog errorLog)` that copies all fields.

**Acceptance Criteria**:
- [X] Record compiles with all 7 fields
- [X] metadata marked as nullable
- [X] fromEntity() method present
- [X] Package is `com.bitbi.dfm.error.presentation.dto`

**FR Mapping**: FR-001, FR-002, FR-003

---

### T012: [P] Write ErrorLogResponseDto unit test

**Type**: [TEST]
**Dependencies**: T011
**Files**:
- `src/test/java/com/bitbi/dfm/error/presentation/dto/ErrorLogResponseDtoTest.java`

**Description**:
Write JUnit 5 unit test for ErrorLogResponseDto.fromEntity().

Test cases:
1. fromEntity_shouldMapAllFields()
2. fromEntity_shouldHandleNullMetadata()
3. fromEntity_shouldIncludeJsonbMetadata()

**Acceptance Criteria**:
- [X] 3 test methods
- [X] Asserts all 7 fields
- [X] Verifies metadata Map handling
- [X] Tests pass after T011 implementation

**FR Mapping**: FR-001, FR-002, FR-003

---

### T013: [P] Update ErrorLogController to use ErrorLogResponseDto

**Type**: [CODE]
**Dependencies**: T011
**Files**:
- `src/main/java/com/bitbi/dfm/error/presentation/ErrorLogController.java`

**Description**:
Update ErrorLogController methods to return ErrorLogResponseDto instead of Map<String, Object>.

Methods to update:
- logError() → return ErrorLogResponseDto.fromEntity(errorLog)
- getErrorLogs() → return List<ErrorLogResponseDto> or PageResponseDto<ErrorLogResponseDto>

**Acceptance Criteria**:
- [ ] All methods return ErrorLogResponseDto or List/Page thereof
- [ ] Uses ErrorLogResponseDto.fromEntity()
- [ ] No Map<String, Object> references
- [ ] Controller compiles

**FR Mapping**: FR-001, FR-002, FR-003

---

### T014: [P] Write ErrorLogController contract test

**Type**: [TEST]
**Dependencies**: T013
**Files**:
- `src/test/java/contract/ErrorLogContractTest.java`

**Description**:
Create/update contract tests for ErrorLogController asserting ErrorLogResponseDto schema.

Tests:
1. logError_shouldReturnErrorLogResponseDto()
2. getErrorLogs_withJwt_shouldReturnList()
3. getErrorLogs_withKeycloak_shouldReturnList()

Assert JSON fields match OpenAPI schema (error-log-dtos.yaml).

**Acceptance Criteria**:
- [ ] 3 test methods
- [ ] Uses MockMvc
- [ ] Asserts all ErrorLogResponseDto fields
- [ ] Tests both JWT and Keycloak on GET

**FR Mapping**: FR-001, FR-005

---

## Phase 3.4: Feature DTOs - File Upload Domain

### T015: [P] Create FileUploadResponseDto record

**Type**: [CODE]
**Dependencies**: None
**Files**:
- `src/main/java/com/bitbi/dfm/upload/presentation/dto/FileUploadResponseDto.java`

**Description**:
Create immutable FileUploadResponseDto record with fields:
- id (UUID)
- batchId (UUID)
- filename (String)
- s3Key (String)
- fileSize (Long)
- checksum (String)
- uploadedAt (Instant)

Add static method `fromEntity(FileUpload fileUpload)`.

**Acceptance Criteria**:
- [X] Record compiles with all 7 fields
- [X] fromEntity() method present
- [X] Package is `com.bitbi.dfm.upload.presentation.dto`

**FR Mapping**: FR-001, FR-002, FR-003

---

### T016: [P] Write FileUploadResponseDto unit test

**Type**: [TEST]
**Dependencies**: T015
**Files**:
- `src/test/java/com/bitbi/dfm/upload/presentation/dto/FileUploadResponseDtoTest.java`

**Description**:
Write JUnit 5 unit test for FileUploadResponseDto.fromEntity().

Test case:
1. fromEntity_shouldMapAllFields()

**Acceptance Criteria**:
- [ ] 1 test method
- [ ] Asserts all 7 fields
- [ ] Tests pass

**FR Mapping**: FR-001, FR-002, FR-003

---

### T017: [P] Update FileUploadController to use FileUploadResponseDto

**Type**: [CODE]
**Dependencies**: T015
**Files**:
- `src/main/java/com/bitbi/dfm/upload/presentation/FileUploadController.java`

**Description**:
Update FileUploadController methods to return FileUploadResponseDto.

Methods:
- uploadFile() → return FileUploadResponseDto.fromEntity(fileUpload)

**Acceptance Criteria**:
- [ ] Method returns ResponseEntity<FileUploadResponseDto>
- [ ] Uses fromEntity()
- [ ] Controller compiles

**FR Mapping**: FR-001, FR-002, FR-003

---

### T018: [P] Write FileUploadController contract test

**Type**: [TEST]
**Dependencies**: T017
**Files**:
- `src/test/java/contract/FileUploadContractTest.java`

**Description**:
Create contract tests for FileUploadController asserting FileUploadResponseDto schema.

Tests:
1. uploadFile_shouldReturnFileUploadResponseDto()
2. uploadFile_withKeycloakToken_shouldReturn403() - Keycloak rejected on POST

**Acceptance Criteria**:
- [ ] 2 test methods
- [ ] Asserts FileUploadResponseDto fields
- [ ] Tests authentication rules

**FR Mapping**: FR-001, FR-006, FR-007

---

## Phase 3.5: Feature DTOs - Account Domain

### T019: [P] Create AccountResponseDto record

**Type**: [CODE]
**Dependencies**: None
**Files**:
- `src/main/java/com/bitbi/dfm/account/presentation/dto/AccountResponseDto.java`

**Description**:
Create immutable AccountResponseDto record with fields:
- id (UUID)
- email (String)
- name (String)
- isActive (Boolean)
- createdAt (Instant)
- maxConcurrentBatches (Integer)

Add static method `fromEntity(Account account)` that excludes sensitive fields (passwords, secrets).

**Acceptance Criteria**:
- [X] Record compiles with all 6 fields
- [X] fromEntity() excludes sensitive data
- [X] Package is `com.bitbi.dfm.account.presentation.dto`

**FR Mapping**: FR-001, FR-002, FR-003

---

### T020: [P] Write AccountResponseDto unit test

**Type**: [TEST]
**Dependencies**: T019
**Files**:
- `src/test/java/com/bitbi/dfm/account/presentation/dto/AccountResponseDtoTest.java`

**Description**:
Write JUnit 5 unit test for AccountResponseDto.fromEntity().

Test case:
1. fromEntity_shouldMapAllFields()
2. fromEntity_shouldExcludeSensitiveFields() - verify no password/secrets in DTO

**Acceptance Criteria**:
- [ ] 2 test methods
- [ ] Asserts all 6 fields
- [ ] Verifies sensitive data exclusion

**FR Mapping**: FR-001, FR-002, FR-003

---

### T021: [P] Update AccountAdminController to use AccountResponseDto

**Type**: [CODE]
**Dependencies**: T019, T002
**Files**:
- `src/main/java/com/bitbi/dfm/account/presentation/AccountAdminController.java`

**Description**:
Update AccountAdminController methods to return AccountResponseDto.

Methods:
- getAccount() → return AccountResponseDto.fromEntity(account)
- listAccounts() → return PageResponseDto<AccountResponseDto> using PageResponseDto.of()
- createAccount() → return AccountResponseDto.fromEntity(account)
- updateAccount() → return AccountResponseDto.fromEntity(account)

**Acceptance Criteria**:
- [ ] All methods return AccountResponseDto or PageResponseDto<AccountResponseDto>
- [ ] Uses fromEntity()
- [ ] Paginated endpoints use PageResponseDto.of()
- [ ] Controller compiles

**FR Mapping**: FR-001, FR-002, FR-003

---

### T022: [P] Write AccountAdminController contract test

**Type**: [TEST]
**Dependencies**: T021
**Files**:
- `src/test/java/contract/AccountContractTest.java`

**Description**:
Update contract tests for AccountAdminController asserting AccountResponseDto schema.

Tests:
1. getAccount_withKeycloak_shouldReturnAccountResponseDto()
2. listAccounts_withKeycloak_shouldReturnPagedResponse()
3. createAccount_withJwtToken_shouldReturn403() - JWT rejected on admin endpoint

**Acceptance Criteria**:
- [ ] 3 test methods
- [ ] Asserts AccountResponseDto fields
- [ ] Tests Keycloak-only authentication

**FR Mapping**: FR-001, FR-008, FR-009

---

## Phase 3.6: Feature DTOs - Site Domain

### T023: [P] Create SiteResponseDto record

**Type**: [CODE]
**Dependencies**: None
**Files**:
- `src/main/java/com/bitbi/dfm/site/presentation/dto/SiteResponseDto.java`

**Description**:
Create immutable SiteResponseDto record with fields:
- id (UUID)
- accountId (UUID)
- domain (String)
- name (String)
- isActive (Boolean)
- createdAt (Instant)

Add static method `fromEntity(Site site)` that excludes clientSecret.

**Acceptance Criteria**:
- [X] Record compiles with all 6 fields
- [X] fromEntity() excludes clientSecret
- [X] Package is `com.bitbi.dfm.site.presentation.dto`

**FR Mapping**: FR-001, FR-002, FR-003

---

### T024: [P] Write SiteResponseDto unit test

**Type**: [TEST]
**Dependencies**: T023
**Files**:
- `src/test/java/com/bitbi/dfm/site/presentation/dto/SiteResponseDtoTest.java`

**Description**:
Write JUnit 5 unit test for SiteResponseDto.fromEntity().

Test cases:
1. fromEntity_shouldMapAllFields()
2. fromEntity_shouldExcludeClientSecret()

**Acceptance Criteria**:
- [ ] 2 test methods
- [ ] Asserts all 6 fields
- [ ] Verifies clientSecret exclusion

**FR Mapping**: FR-001, FR-002, FR-003

---

### T025: [P] Update SiteAdminController to use SiteResponseDto

**Type**: [CODE]
**Dependencies**: T023, T002
**Files**:
- `src/main/java/com/bitbi/dfm/site/presentation/SiteAdminController.java`

**Description**:
Update SiteAdminController methods to return SiteResponseDto.

Methods:
- getSite() → return SiteResponseDto.fromEntity(site)
- listSites() → return PageResponseDto<SiteResponseDto>
- createSite() → return SiteResponseDto.fromEntity(site)
- updateSite() → return SiteResponseDto.fromEntity(site)

**Acceptance Criteria**:
- [ ] All methods return SiteResponseDto or PageResponseDto<SiteResponseDto>
- [ ] Uses fromEntity()
- [ ] Controller compiles

**FR Mapping**: FR-001, FR-002, FR-003

---

### T026: [P] Write SiteAdminController contract test

**Type**: [TEST]
**Dependencies**: T025
**Files**:
- `src/test/java/contract/SiteContractTest.java`

**Description**:
Update contract tests for SiteAdminController asserting SiteResponseDto schema.

Tests:
1. getSite_withKeycloak_shouldReturnSiteResponseDto()
2. listSites_withKeycloak_shouldReturnPagedResponse()
3. createSite_withJwtToken_shouldReturn403()

**Acceptance Criteria**:
- [ ] 3 test methods
- [ ] Asserts SiteResponseDto fields
- [ ] Tests Keycloak-only authentication

**FR Mapping**: FR-001, FR-008, FR-009

---

## Phase 3.7: Feature DTOs - Auth Domain

### T027: [P] Create TokenResponseDto record

**Type**: [CODE]
**Dependencies**: None
**Files**:
- `src/main/java/com/bitbi/dfm/auth/presentation/dto/TokenResponseDto.java`

**Description**:
Create immutable TokenResponseDto record with fields:
- token (String)
- expiresAt (Instant)
- siteId (UUID)
- domain (String)

Add static method `fromToken(JwtToken jwtToken)` that extracts fields from custom JwtToken value object.

**Acceptance Criteria**:
- [X] Record compiles with all 4 fields
- [X] fromToken() method present
- [X] Extracts claims from JwtToken
- [X] Package is `com.bitbi.dfm.auth.presentation.dto`

**FR Mapping**: FR-001, FR-002, FR-003

---

### T028: [P] Write TokenResponseDto unit test

**Type**: [TEST]
**Dependencies**: T027
**Files**:
- `src/test/java/com/bitbi/dfm/auth/presentation/dto/TokenResponseDtoTest.java`

**Description**:
Write JUnit 5 unit test for TokenResponseDto.fromToken().

Test case:
1. fromToken_shouldExtractAllClaims()

**Acceptance Criteria**:
- [ ] 1 test method
- [ ] Asserts all 4 fields
- [ ] Mocks JwtToken value object

**FR Mapping**: FR-001, FR-002, FR-003

---

### T029: [P] Update AuthController to use TokenResponseDto

**Type**: [CODE]
**Dependencies**: T027
**Files**:
- `src/main/java/com/bitbi/dfm/auth/presentation/AuthController.java`

**Description**:
Update AuthController token generation method to return TokenResponseDto.

Method:
- generateToken() → return TokenResponseDto.fromToken(jwtToken)

**Acceptance Criteria**:
- [ ] Method returns ResponseEntity<TokenResponseDto>
- [ ] Uses fromToken()
- [ ] Controller compiles

**FR Mapping**: FR-001, FR-002, FR-003

---

### T030: [P] Write AuthController contract test

**Type**: [TEST]
**Dependencies**: T029
**Files**:
- `src/test/java/contract/AuthContractTest.java`

**Description**:
Update contract tests for AuthController asserting TokenResponseDto schema.

Test:
1. generateToken_shouldReturnTokenResponseDto()

**Acceptance Criteria**:
- [ ] 1 test method
- [ ] Asserts TokenResponseDto fields
- [ ] Uses Basic Auth for token generation endpoint

**FR Mapping**: FR-001

---

## Phase 3.8: Authentication Integration Tests

**NOTE**: These tests require T006 (SecurityConfiguration) to be complete.

### T031: Write DualAuthenticationIntegrationTest

**Type**: [TEST]
**Dependencies**: T006, T007, T011, T015
**Files**:
- `src/test/java/integration/DualAuthenticationIntegrationTest.java`

**Description**:
Write Spring Boot integration test with @SpringBootTest and @AutoConfigureMockMvc.

Test cases:
1. getBatch_withJwtToken_shouldReturn200() - JWT accepted on GET
2. getBatch_withKeycloakToken_shouldReturn200() - Keycloak accepted on GET
3. getErrorLog_withJwtToken_shouldReturn200()
4. getErrorLog_withKeycloakToken_shouldReturn200()
5. getFileUpload_withBothTokenTypes_shouldWork()

Use real Spring Security context (not mocked).
Use Testcontainers for PostgreSQL if needed.

**Acceptance Criteria**:
- [ ] 5 test methods
- [ ] Uses @SpringBootTest
- [ ] Tests dual authentication on GET endpoints
- [ ] Verifies both JWT and Keycloak work on client endpoints

**FR Mapping**: FR-005

---

### T032: Write AuthSecurityIntegrationTest - JWT only on POST

**Type**: [TEST]
**Dependencies**: T006, T007
**Files**:
- `src/test/java/integration/AuthSecurityIntegrationTest.java`

**Description**:
Write integration tests for JWT-only write operations.

Test cases:
1. startBatch_withJwtToken_shouldReturn201() - JWT works on POST
2. startBatch_withKeycloakToken_shouldReturn403() - Keycloak rejected on POST
3. completeBatch_withKeycloakToken_shouldReturn403()
4. uploadFile_withKeycloakToken_shouldReturn403()

**Acceptance Criteria**:
- [ ] 4 test methods
- [ ] Verifies JWT works on write operations
- [ ] Verifies Keycloak returns 403 on write operations
- [ ] Asserts ErrorResponseDto structure in 403 responses

**FR Mapping**: FR-006, FR-007

---

### T033: Write AuthSecurityIntegrationTest - Keycloak only on admin

**Type**: [TEST]
**Dependencies**: T006, T019, T023
**Files**:
- `src/test/java/integration/AuthSecurityIntegrationTest.java` (add to existing file)

**Description**:
Add integration tests for Keycloak-only admin endpoints.

Test cases:
1. listAccounts_withKeycloakToken_shouldReturn200() - Keycloak works on admin
2. listAccounts_withJwtToken_shouldReturn403() - JWT rejected on admin
3. listSites_withJwtToken_shouldReturn403()

**Acceptance Criteria**:
- [ ] 3 test methods in AuthSecurityIntegrationTest
- [ ] Verifies Keycloak works on admin endpoints
- [ ] Verifies JWT returns 403 on admin endpoints

**FR Mapping**: FR-008, FR-009

---

### T034: Write AuthSecurityIntegrationTest - Dual token detection

**Type**: [TEST]
**Dependencies**: T006, T004
**Files**:
- `src/test/java/integration/AuthSecurityIntegrationTest.java` (add to existing file)

**Description**:
Add integration test for dual token rejection.

Test case:
1. request_withBothTokens_shouldReturn400()

Send request with both Authorization and X-Keycloak-Token headers.
Assert 400 Bad Request.
Assert ErrorResponseDto with message containing "Ambiguous authentication".

**Acceptance Criteria**:
- [ ] 1 test method
- [ ] Sends both token headers
- [ ] Asserts 400 status
- [ ] Asserts ErrorResponseDto structure
- [ ] Verifies message mentions ambiguous authentication

**FR Mapping**: FR-015

---

### T035: Write AuthSecurityIntegrationTest - Audit logging

**Type**: [TEST]
**Dependencies**: T006, T005
**Files**:
- `src/test/java/integration/AuthSecurityIntegrationTest.java` (add to existing file)

**Description**:
Add integration test for authentication failure logging.

Test case:
1. authFailure_shouldLogWithMdcContext()

Capture Logback logs with ListAppender.
Send invalid token.
Assert log entry contains:
- "auth_failure" in message
- MDC fields: ip, endpoint, method, status, tokenType

**Acceptance Criteria**:
- [ ] 1 test method
- [ ] Captures logs with ListAppender
- [ ] Sends invalid token to trigger auth failure
- [ ] Asserts MDC context fields present
- [ ] Verifies tokenType field

**FR Mapping**: FR-013

---

## Phase 3.9: End-to-End Integration Tests (Quickstart Scenarios)

**NOTE**: These tests validate the complete feature end-to-end.

### T036: [P] Write Scenario 1 integration test - DTO structure validation

**Type**: [TEST]
**Dependencies**: T007, T009
**Files**:
- `src/test/java/integration/DtoStructureIntegrationTest.java`

**Description**:
Implement quickstart Scenario 1: Verify all endpoints return structured DTOs.

Test:
1. fullBatchLifecycle_shouldReturnStructuredDtos()

Steps:
- Generate JWT token
- Start batch → assert BatchResponseDto with all 10 fields
- Upload file → assert FileUploadResponseDto
- Log error → assert ErrorLogResponseDto
- Complete batch → assert BatchResponseDto with completedAt populated

**Acceptance Criteria**:
- [ ] End-to-end test with real Spring context
- [ ] Asserts all DTO field types
- [ ] Verifies no Map<String, Object> in responses
- [ ] Uses Testcontainers for PostgreSQL

**FR Mapping**: FR-001, FR-002, FR-003

---

### T037: [P] Write Scenario 2 integration test - Dual auth on GET

**Type**: [TEST]
**Dependencies**: T006, T007
**Files**:
- `src/test/java/integration/DualAuthGetIntegrationTest.java`

**Description**:
Implement quickstart Scenario 2: Verify GET endpoints accept both JWT and Keycloak.

Tests:
1. getBatch_withJwt_shouldReturn200AndBatchDto()
2. getBatch_withKeycloak_shouldReturn200AndBatchDto()

**Acceptance Criteria**:
- [ ] 2 test methods
- [ ] Tests both authentication types on GET
- [ ] Asserts BatchResponseDto structure
- [ ] Both tests pass

**FR Mapping**: FR-005

---

### T038: [P] Write Scenario 3 integration test - JWT only on write

**Type**: [TEST]
**Dependencies**: T006, T007
**Files**:
- `src/test/java/integration/JwtOnlyWriteIntegrationTest.java`

**Description**:
Implement quickstart Scenario 3: Verify POST/PUT/DELETE reject Keycloak with 403.

Tests:
1. startBatch_withJwt_shouldReturn201()
2. startBatch_withKeycloak_shouldReturn403()

**Acceptance Criteria**:
- [ ] 2 test methods
- [ ] JWT succeeds on POST
- [ ] Keycloak returns 403 on POST
- [ ] Asserts ErrorResponseDto on 403

**FR Mapping**: FR-006, FR-007

---

### T039: [P] Write Scenario 4 integration test - Dual token detection

**Type**: [TEST]
**Dependencies**: T006, T004
**Files**:
- `src/test/java/integration/DualTokenDetectionIntegrationTest.java`

**Description**:
Implement quickstart Scenario 4: Verify dual tokens return 400.

Test:
1. request_withBothTokens_shouldReturn400()

**Acceptance Criteria**:
- [ ] 1 test method
- [ ] Sends both tokens
- [ ] Asserts 400 status
- [ ] Asserts ErrorResponseDto

**FR Mapping**: FR-015

---

### T040: [P] Write Scenario 5 integration test - Admin endpoints

**Type**: [TEST]
**Dependencies**: T006, T019
**Files**:
- `src/test/java/integration/AdminEndpointsIntegrationTest.java`

**Description**:
Implement quickstart Scenario 5: Verify admin endpoints Keycloak-only.

Tests:
1. listAccounts_withKeycloak_shouldReturn200AndPagedDto()
2. listAccounts_withJwt_shouldReturn403()

**Acceptance Criteria**:
- [ ] 2 test methods
- [ ] Keycloak succeeds on admin
- [ ] JWT returns 403 on admin
- [ ] Asserts PageResponseDto<AccountResponseDto>

**FR Mapping**: FR-008, FR-009

---

### T041: [P] Write Scenario 6 integration test - Audit logging

**Type**: [TEST]
**Dependencies**: T006, T005
**Files**:
- `src/test/java/integration/AuditLoggingIntegrationTest.java`

**Description**:
Implement quickstart Scenario 6: Verify auth failures logged with MDC.

Test:
1. authFailure_shouldLogWithIpEndpointMethodStatusTokenType()

**Acceptance Criteria**:
- [ ] 1 test method
- [ ] Captures logs
- [ ] Verifies all MDC fields: ip, endpoint, method, status, tokenType

**FR Mapping**: FR-013

---

### T042: [P] Write Scenario 7 integration test - Error standardization

**Type**: [TEST]
**Dependencies**: T003
**Files**:
- `src/test/java/integration/ErrorStandardizationIntegrationTest.java`

**Description**:
Implement quickstart Scenario 7: Verify all errors use ErrorResponseDto.

Tests:
1. notFound_shouldReturnErrorResponseDto() - 404 Not Found
2. conflict_shouldReturnErrorResponseDto() - 409 Conflict (duplicate batch)
3. payloadTooLarge_shouldReturnErrorResponseDto() - 413 Payload Too Large

**Acceptance Criteria**:
- [ ] 3 test methods
- [ ] Tests various HTTP error codes
- [ ] All assert ErrorResponseDto structure
- [ ] Verifies consistent error format

**FR Mapping**: FR-004

---

## Phase 3.10: Documentation & Validation

### T043: Update Swagger OpenAPI spec with DTO schemas

**Type**: [DOC]
**Dependencies**: T007, T011, T015, T019, T023, T027, T001, T002
**Files**:
- `src/main/resources/openapi/api-docs.yaml` (or SpringDoc auto-generation config)

**Description**:
Update or verify OpenAPI documentation reflects new DTO schemas.

If using SpringDoc auto-generation:
- Add @Schema annotations to DTO records
- Verify Swagger UI at /swagger-ui.html shows correct schemas

If manual OpenAPI file:
- Reference contract YAML files
- Merge batch-dtos.yaml, error-log-dtos.yaml, etc. into main spec

**Acceptance Criteria**:
- [ ] Swagger UI displays all DTO schemas
- [ ] All endpoints show correct request/response types
- [ ] Security schemes documented (JwtAuth, KeycloakAuth)
- [ ] Example responses include DTO structure

**FR Mapping**: FR-001, FR-002

---

### T044: Run quickstart.md manual validation

**Type**: [TEST]
**Dependencies**: T001-T042 (all implementation complete)
**Files**:
- `specs/002-additions-to-backend/quickstart.md`

**Description**:
Manually execute all 7 scenarios in quickstart.md using curl commands.

Scenarios:
1. DTO Response Structure - verify field presence and types
2. Dual Authentication on GET - test both JWT and Keycloak
3. JWT-Only on Write Operations - verify 403 on Keycloak POST
4. Dual Token Detection - verify 400 on both tokens
5. Admin Endpoints - verify Keycloak-only
6. Authentication Audit Logging - verify logs
7. Error Response Standardization - verify ErrorResponseDto

Document results in quickstart.md or separate validation report.

**Acceptance Criteria**:
- [ ] All 7 scenarios executed
- [ ] All curl commands succeed with expected responses
- [ ] JWT token generation works
- [ ] Keycloak token generation works (if Keycloak available)
- [ ] All success criteria from quickstart.md met

**FR Mapping**: All FR-001 through FR-015

---

### T045: Verify code coverage ≥80%

**Type**: [TEST]
**Dependencies**: T001-T042
**Files**:
- N/A (verification task)

**Description**:
Run JaCoCo test coverage report and verify ≥80% coverage threshold.

Commands:
```bash
./gradlew test
./gradlew jacocoTestReport
```

Check report at `build/reports/jacoco/test/html/index.html`.

If coverage <80%:
- Identify uncovered lines
- Add unit tests for uncovered DTO mapping logic
- Add integration tests for uncovered controller paths

**Acceptance Criteria**:
- [ ] `./gradlew test` passes all tests
- [ ] JaCoCo report generated
- [ ] Line coverage ≥80%
- [ ] Branch coverage ≥70%
- [ ] All DTOs have mapping tests

**FR Mapping**: Constitutional requirement (TDD)

---

### T046: Final CLAUDE.md update

**Type**: [DOC]
**Dependencies**: T001-T045
**Files**:
- `CLAUDE.md`

**Description**:
Run agent context update script one final time to sync any implementation notes.

```bash
.specify/scripts/bash/update-agent-context.sh claude
```

Manually add any important implementation notes:
- Security configuration details
- Known limitations
- Future enhancements

**Acceptance Criteria**:
- [ ] Script runs successfully
- [ ] CLAUDE.md reflects completed feature
- [ ] Recent changes section updated
- [ ] File under 150 lines

**FR Mapping**: N/A (documentation)

---

## Dependencies Summary

### Critical Path
```
Foundation (T001-T006) → Feature DTOs (T007-T030) → Integration Tests (T031-T042) → Validation (T043-T046)
```

### Detailed Dependencies
- **T001-T002**: No dependencies (shared DTOs)
- **T003**: Depends on T001 (uses ErrorResponseDto)
- **T004**: Depends on T001 (returns ErrorResponseDto)
- **T005**: No dependencies (logging only)
- **T006**: Depends on T004, T005 (registers filter and logger)
- **T007-T030**: No dependencies (parallel DTO creation)
- **T008, T012, T016, T020, T024, T028**: Depend on respective DTO creation tasks
- **T009, T013, T017, T021, T025, T029**: Depend on respective DTO creation tasks
- **T010, T014, T018, T022, T026, T030**: Depend on respective controller update tasks
- **T031-T035**: Depend on T006 (SecurityConfiguration) + respective DTOs
- **T036-T042**: Depend on all implementation tasks (T001-T035)
- **T043**: Depends on all DTO tasks (T007, T011, T015, T019, T023, T027, T001, T002)
- **T044**: Depends on all implementation (T001-T042)
- **T045**: Depends on all tests (T008, T010, T012, T014, T016, T018, T020, T022, T024, T026, T028, T030, T031-T042)
- **T046**: Depends on everything (final step)

### Parallel Execution Groups

**Group 1: Shared DTOs** (T001-T002) [P]
```
Task T001: Create ErrorResponseDto
Task T002: Create PageResponseDto<T>
```

**Group 2: Feature DTOs - Records** (T007, T011, T015, T019, T023, T027) [P]
```
Task T007: Create BatchResponseDto
Task T011: Create ErrorLogResponseDto
Task T015: Create FileUploadResponseDto
Task T019: Create AccountResponseDto
Task T023: Create SiteResponseDto
Task T027: Create TokenResponseDto
```

**Group 3: DTO Unit Tests** (T008, T012, T016, T020, T024, T028) [P]
```
Task T008: Test BatchResponseDto
Task T012: Test ErrorLogResponseDto
Task T016: Test FileUploadResponseDto
Task T020: Test AccountResponseDto
Task T024: Test SiteResponseDto
Task T028: Test TokenResponseDto
```

**Group 4: Controller Updates** (T009, T013, T017, T021, T025, T029) [P]
```
Task T009: Update BatchController
Task T013: Update ErrorLogController
Task T017: Update FileUploadController
Task T021: Update AccountAdminController
Task T025: Update SiteAdminController
Task T029: Update AuthController
```

**Group 5: Contract Tests** (T010, T014, T018, T022, T026, T030) [P]
```
Task T010: Test BatchController contract
Task T014: Test ErrorLogController contract
Task T018: Test FileUploadController contract
Task T022: Test AccountAdminController contract
Task T026: Test SiteAdminController contract
Task T030: Test AuthController contract
```

**Group 6: E2E Integration Tests** (T036-T042) [P]
```
Task T036: Scenario 1 - DTO structure
Task T037: Scenario 2 - Dual auth GET
Task T038: Scenario 3 - JWT only write
Task T039: Scenario 4 - Dual token detection
Task T040: Scenario 5 - Admin endpoints
Task T041: Scenario 6 - Audit logging
Task T042: Scenario 7 - Error standardization
```

---

## Execution Estimates

**Total Tasks**: 46
**Estimated Time**: 8-10 hours

**Phase Breakdown**:
- Foundation (T001-T006): 2-3 hours (sequential, critical path)
- Feature DTOs (T007-T030): 3-4 hours (highly parallel)
- Auth Integration Tests (T031-T035): 1-2 hours (sequential, depends on T006)
- E2E Tests (T036-T042): 1-2 hours (parallel)
- Documentation & Validation (T043-T046): 1 hour

**Recommended Execution Order**:
1. Foundation first (T001-T006 sequential)
2. All DTO records in parallel (T007, T011, T015, T019, T023, T027)
3. All DTO unit tests in parallel (T008, T012, T016, T020, T024, T028)
4. All controller updates in parallel (T009, T013, T017, T021, T025, T029)
5. All contract tests in parallel (T010, T014, T018, T022, T026, T030)
6. Auth integration tests sequential (T031-T035)
7. E2E tests in parallel (T036-T042)
8. Documentation sequential (T043-T046)

---

## Notes

- **TDD Enforced**: All test tasks (T008, T010, etc.) must be written BEFORE implementation tasks and must initially FAIL
- **Parallel Markers**: Tasks marked [P] can run simultaneously (different files, no shared dependencies)
- **Breaking Change**: This feature introduces breaking authentication changes (FR-011) - coordinate with API consumers before deployment
- **Code Coverage**: Constitutional requirement of ≥80% enforced by T045
- **Security**: Generic error messages enforced (FR-014) - tests verify no sensitive information leaked

---

## Validation Checklist

- [x] All 6 contract files have corresponding test tasks
- [x] All 8 DTO entities have creation tasks (including shared DTOs)
- [x] All tests come before implementation (TDD)
- [x] Parallel tasks [P] are truly independent (different files)
- [x] Each task specifies exact file path
- [x] No task modifies same file as another [P] task
- [x] Foundation tasks complete before feature DTOs
- [x] SecurityConfiguration (T006) complete before auth integration tests
- [x] All implementation complete before validation tasks

✅ **Tasks ready for execution!**
