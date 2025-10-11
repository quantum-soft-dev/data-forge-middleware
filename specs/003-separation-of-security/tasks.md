# Tasks: Security System Separation

**Input**: Design documents from `/specs/003-separation-of-security/`
**Prerequisites**: plan.md ✓, spec.md ✓, research.md ✓, data-model.md ✓, contracts/ ✓

**Tests**: TDD approach mandated by constitution - tests MUST be written before implementation

**Organization**: Tasks grouped by user story to enable independent implementation and testing

## Format: `[ID] [P?] [Story] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions
- Repository root: `/Users/boris/projects/bit-bi/data-forge-middleware`
- Source: `src/main/java/com/bitbi/dfm/`
- Tests: `src/test/java/com/bitbi/dfm/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Verify existing project structure and dependencies

- [ ] T001 Verify Java 21, Spring Boot 3.5.6, Spring Security 6 dependencies in build.gradle
- [ ] T002 Verify existing JWT authentication infrastructure (JwtAuthenticationFilter, TokenService)
- [ ] T003 Verify existing Keycloak OAuth2 Resource Server configuration
- [ ] T004 [P] Review existing controller URL patterns in batch/error/upload/admin packages
- [ ] T005 [P] Review existing integration test structure in src/test/java/com/bitbi/dfm/integration/

**Checkpoint**: Infrastructure verified - ready for test updates

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core refactoring that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Test Configuration Refactoring (Foundation for US3)

- [ ] T006 [US3] **TDD RED**: Update TestSecurityConfig to mirror production (src/test/java/com/bitbi/dfm/config/TestSecurityConfig.java)
  - Replace custom filter chains with separate SecurityFilterChain beans
  - Add @Order(1) jwtFilterChain for `/api/dfc/**`
  - Add @Order(2) keycloakFilterChain for `/api/admin/**`
  - Add @Order(3) defaultFilterChain for public/denied endpoints
  - Preserve existing JwtAuthenticationFilter bean
  - Preserve OAuth2 mock configuration
  - Import production SecurityConfiguration structure

### Production Security Configuration (Foundation for US1 + US2)

- [ ] T007 [US1+US2] **TDD RED**: Refactor SecurityConfiguration with separate filter chains (src/main/java/com/bitbi/dfm/shared/config/SecurityConfiguration.java)
  - Remove AuthenticationManagerResolver bean (if exists)
  - Remove DualAuthenticationFilter registration (if exists)
  - Create @Bean @Order(1) jwtFilterChain(HttpSecurity http) for `/api/dfc/**`
    - securityMatcher("/api/dfc/**")
    - authorizeHttpRequests: authenticated()
    - sessionManagement: STATELESS
    - addFilterBefore: jwtAuthenticationFilter
    - exceptionHandling: jwtAuthenticationEntryPoint
    - csrf: disabled
  - Create @Bean @Order(2) keycloakFilterChain(HttpSecurity http) for `/api/admin/**`
    - securityMatcher("/api/admin/**")
    - authorizeHttpRequests: hasRole("ADMIN")
    - oauth2ResourceServer with JWT
    - exceptionHandling: keycloakAuthenticationEntryPoint
    - csrf: disabled
  - Create @Bean @Order(3) defaultFilterChain(HttpSecurity http)
    - permitAll: /actuator/health, /v3/api-docs/**, /swagger-ui/**
    - denyAll: all others
    - csrf: disabled
  - Preserve: JwtAuthenticationFilter bean, authentication entry points

- [ ] T008 [US1+US2] Delete DualAuthenticationFilter.java (src/main/java/com/bitbi/dfm/shared/auth/DualAuthenticationFilter.java)
  - Remove class file entirely (no longer needed with separate filter chains)

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - File Uploader Client Authentication (Priority: P1) 🎯 MVP

**Goal**: Enable JWT-only authentication for file uploader clients on `/api/dfc/**` endpoints

**Independent Test**: Make API requests to `/api/dfc/batch/start`, `/api/dfc/upload`, `/api/dfc/error` with JWT tokens - all succeed; Keycloak tokens on same endpoints - all return 403

### Tests for User Story 1 ✅ TDD

**NOTE: Write these tests FIRST, ensure they FAIL (RED phase) before implementation**

- [ ] T009 [P] [US1] **TDD RED**: Update JwtOnlyWriteIntegrationTest URLs to `/api/dfc/**` (src/test/java/com/bitbi/dfm/integration/JwtOnlyWriteIntegrationTest.java)
  - Line 50: Change `/api/v1/batch/start` → `/api/dfc/batch/start`
  - Line 107: Change `/api/v1/batch/{batchId}/complete` → `/api/dfc/batch/{batchId}/complete`
  - Line 128: Change `/api/v1/batch/{batchId}/complete` → `/api/dfc/batch/{batchId}/complete`
  - Verify test expects 201 Created for JWT, 403 Forbidden for Keycloak

- [ ] T010 [P] [US1] **TDD RED**: Update DualAuthenticationIntegrationTest for JWT-only behavior (src/test/java/com/bitbi/dfm/integration/DualAuthenticationIntegrationTest.java)
  - Update URLs from `/api/v1/**` → `/api/dfc/**`
  - Remove dual token detection tests (no longer applicable)
  - Update test to verify JWT accepted, Keycloak rejected on `/api/dfc/**`
  - Update test to verify Keycloak accepted, JWT rejected on `/api/admin/**`

- [ ] T011 [P] [US1] **TDD RED**: Create contract test for batch endpoints (src/test/java/com/bitbi/dfm/integration/BatchEndpointsContractTest.java)
  - Test POST `/api/dfc/batch/start` with JWT → 201 Created + BatchResponseDto
  - Test POST `/api/dfc/batch/{id}/complete` with JWT → 200 OK + BatchResponseDto
  - Test GET `/api/dfc/batch` with JWT → 200 OK + BatchPageResponse
  - Test GET `/api/dfc/batch/{id}` with JWT → 200 OK + BatchResponseDto
  - Test all endpoints with Keycloak token → 403 Forbidden
  - Test all endpoints with no token → 401 Unauthorized

- [ ] T012 [P] [US1] **TDD RED**: Create contract test for file upload endpoints (src/test/java/com/bitbi/dfm/integration/FileUploadEndpointsContractTest.java)
  - Test POST `/api/dfc/upload` with JWT + multipart file → 201 Created + FileUploadResponseDto
  - Test POST `/api/dfc/upload` with Keycloak token → 403 Forbidden
  - Test POST `/api/dfc/upload` with file >500MB → 413 Payload Too Large

- [ ] T013 [P] [US1] **TDD RED**: Create contract test for error log endpoints (src/test/java/com/bitbi/dfm/integration/ErrorLogEndpointsContractTest.java)
  - Test POST `/api/dfc/error` with JWT + ErrorLogRequest → 201 Created + ErrorLogResponseDto
  - Test GET `/api/dfc/error` with JWT → 200 OK + ErrorLogPageResponse
  - Test GET `/api/dfc/error?severity=ERROR` with JWT → 200 OK + filtered results
  - Test all endpoints with Keycloak token → 403 Forbidden

- [ ] T014 [US1] **TDD RED**: Run tests to verify they FAIL (expected behavior before implementation)
  - Execute: `./gradlew test --tests "*JwtOnlyWriteIntegrationTest"`
  - Execute: `./gradlew test --tests "*DualAuthenticationIntegrationTest"`
  - Execute: `./gradlew test --tests "*BatchEndpointsContractTest"`
  - Execute: `./gradlew test --tests "*FileUploadEndpointsContractTest"`
  - Execute: `./gradlew test --tests "*ErrorLogEndpointsContractTest"`
  - **Expected**: Failures due to 404 Not Found (endpoints not yet migrated)
  - **Document**: Failure count and types for comparison after implementation

### Implementation for User Story 1 ✅ GREEN

- [ ] T015 [P] [US1] **TDD GREEN**: Migrate BatchController to `/api/dfc/batch` (src/main/java/com/bitbi/dfm/batch/presentation/BatchController.java)
  - Change @RequestMapping from `/api/v1/batch` → `/api/dfc/batch`
  - Verify @PostMapping("/start"), @PostMapping("/{id}/complete"), @GetMapping, @GetMapping("/{id}") paths
  - No service/domain logic changes required

- [ ] T016 [P] [US1] **TDD GREEN**: Migrate ErrorLogController to `/api/dfc/error` (src/main/java/com/bitbi/dfm/error/presentation/ErrorLogController.java)
  - Change @RequestMapping from `/api/v1/error` → `/api/dfc/error`
  - Verify @PostMapping, @GetMapping paths
  - No service/domain logic changes required

- [ ] T017 [P] [US1] **TDD GREEN**: Migrate FileUploadController to `/api/dfc/upload` (src/main/java/com/bitbi/dfm/upload/presentation/FileUploadController.java)
  - Change @RequestMapping from `/api/v1/upload` → `/api/dfc/upload`
  - Verify @PostMapping path for multipart file upload
  - No service/domain logic changes required

- [ ] T018 [US1] **TDD GREEN**: Run User Story 1 tests to verify they PASS
  - Execute: `./gradlew test --tests "*JwtOnlyWriteIntegrationTest"`
  - Execute: `./gradlew test --tests "*DualAuthenticationIntegrationTest"`
  - Execute: `./gradlew test --tests "*BatchEndpointsContractTest"`
  - Execute: `./gradlew test --tests "*FileUploadEndpointsContractTest"`
  - Execute: `./gradlew test --tests "*ErrorLogEndpointsContractTest"`
  - **Expected**: All tests pass (GREEN phase complete)

- [ ] T019 [US1] **TDD REFACTOR**: Enhance AuthenticationAuditLogger with token type detection (src/main/java/com/bitbi/dfm/shared/auth/AuthenticationAuditLogger.java)
  - Add detectTokenType(HttpServletRequest) method
    - Parse Authorization header
    - Heuristic: JWT has 3 parts (header.payload.signature)
    - Return "JWT" or "KEYCLOAK" or "UNKNOWN"
  - Update logAuthenticationFailure to include tokenType, endpoint, method, status
  - Log format: `Authentication failure: tokenType=JWT, endpoint=/api/admin/accounts, method=GET, status=403, reason={exception}`

- [ ] T020 [US1] **TDD REFACTOR**: Run User Story 1 tests again to verify refactoring didn't break anything
  - Execute: `./gradlew test --tests "*JwtOnlyWriteIntegrationTest"`
  - Execute: `./gradlew test --tests "*DualAuthenticationIntegrationTest"`
  - Execute: `./gradlew test --tests "*Batch*ContractTest"`
  - Execute: `./gradlew test --tests "*FileUpload*ContractTest"`
  - Execute: `./gradlew test --tests "*ErrorLog*ContractTest"`
  - **Expected**: All tests still pass (refactoring successful)

**Checkpoint**: User Story 1 complete - JWT authentication works on `/api/dfc/**`, Keycloak rejected with 403

---

## Phase 4: User Story 2 - Admin UI Authentication (Priority: P1)

**Goal**: Ensure Keycloak-only authentication works on `/api/admin/**` endpoints and rejects JWT tokens

**Independent Test**: Make API requests to `/api/admin/accounts`, `/api/admin/sites` with Keycloak tokens - all succeed; JWT tokens on same endpoints - all return 403

### Tests for User Story 2 ✅ TDD

**NOTE: These tests should already PASS from Phase 2 foundation (admin endpoints already use `/admin/**`)**

- [ ] T021 [P] [US2] **TDD VERIFY**: Update AdminEndpointsIntegrationTest if needed (src/test/java/com/bitbi/dfm/integration/AdminEndpointsIntegrationTest.java)
  - Verify tests use `/api/admin/accounts` URLs (should already be correct)
  - Verify tests use `/api/admin/sites` URLs (should already be correct)
  - Verify test expects 200 OK for Keycloak token with ROLE_ADMIN
  - Verify test expects 403 Forbidden for JWT token
  - If URLs already correct and tests pass: mark complete without changes

- [ ] T022 [P] [US2] **TDD RED**: Create contract test for admin account endpoints (src/test/java/com/bitbi/dfm/integration/AdminAccountEndpointsContractTest.java)
  - Test GET `/api/admin/accounts` with Keycloak + ROLE_ADMIN → 200 OK + AccountPageResponse
  - Test GET `/api/admin/accounts/{id}` with Keycloak → 200 OK + AccountDetailsResponse
  - Test POST `/api/admin/accounts` with Keycloak + CreateAccountRequest → 201 Created + AccountResponse
  - Test PUT `/api/admin/accounts/{id}` with Keycloak + UpdateAccountRequest → 200 OK + AccountResponse
  - Test DELETE `/api/admin/accounts/{id}` with Keycloak → 204 No Content
  - Test all endpoints with JWT token → 403 Forbidden
  - Test all endpoints with Keycloak but no ROLE_ADMIN → 403 Forbidden

- [ ] T023 [P] [US2] **TDD RED**: Create contract test for admin site endpoints (src/test/java/com/bitbi/dfm/integration/AdminSiteEndpointsContractTest.java)
  - Test GET `/api/admin/sites` with Keycloak + ROLE_ADMIN → 200 OK + SitePageResponse
  - Test GET `/api/admin/accounts/{accountId}/sites` with Keycloak → 200 OK + List<SiteResponse>
  - Test GET `/api/admin/sites/{id}` with Keycloak → 200 OK + SiteResponse
  - Test POST `/api/admin/accounts/{accountId}/sites` with Keycloak + CreateSiteRequest → 201 Created + SiteCreationResponse (includes clientSecret)
  - Test PUT `/api/admin/sites/{id}` with Keycloak + UpdateSiteRequest → 200 OK + SiteResponse
  - Test DELETE `/api/admin/sites/{id}` with Keycloak → 204 No Content
  - Test all endpoints with JWT token → 403 Forbidden

- [ ] T024 [US2] **TDD RED**: Run User Story 2 tests to verify expected behavior
  - Execute: `./gradlew test --tests "*AdminEndpointsIntegrationTest"`
  - Execute: `./gradlew test --tests "*AdminAccountEndpointsContractTest"`
  - Execute: `./gradlew test --tests "*AdminSiteEndpointsContractTest"`
  - **Expected**: Most tests should pass (admin endpoints already use `/admin/**`)
  - **Expected**: JWT rejection tests (403) should pass due to Phase 2 SecurityConfiguration

### Implementation for User Story 2 ✅ GREEN

- [ ] T025 [P] [US2] **TDD GREEN**: Verify AccountAdminController uses `/admin/accounts` (src/main/java/com/bitbi/dfm/account/presentation/AccountAdminController.java)
  - Confirm @RequestMapping("/admin/accounts") is present
  - Confirm @PreAuthorize("hasRole('ADMIN')") is present
  - If correct: mark complete without changes
  - If incorrect: update @RequestMapping to `/admin/accounts`

- [ ] T026 [P] [US2] **TDD GREEN**: Verify SiteAdminController uses `/admin/sites` (src/main/java/com/bitbi/dfm/site/presentation/SiteAdminController.java)
  - Confirm @RequestMapping patterns include "/admin/sites", "/admin/accounts/{accountId}/sites"
  - Confirm @PreAuthorize("hasRole('ADMIN')") is present
  - If correct: mark complete without changes
  - If incorrect: update @RequestMapping patterns

- [ ] T027 [US2] **TDD GREEN**: Run User Story 2 tests to verify they PASS
  - Execute: `./gradlew test --tests "*AdminEndpointsIntegrationTest"`
  - Execute: `./gradlew test --tests "*AdminAccountEndpointsContractTest"`
  - Execute: `./gradlew test --tests "*AdminSiteEndpointsContractTest"`
  - **Expected**: All tests pass (GREEN phase complete)

**Checkpoint**: User Story 2 complete - Keycloak authentication works on `/api/admin/**`, JWT rejected with 403

---

## Phase 5: User Story 3 - Test Suite Verification (Priority: P1)

**Goal**: Ensure 100% of existing tests pass after security refactoring

**Independent Test**: Run full test suite (`./gradlew test`) and verify 389/389 tests pass with 0 failures

### Tests for User Story 3 ✅ TDD

**NOTE: Test updates were completed in Phase 2 (T006) and Phase 3/4 (T009-T013, T021-T023)**

- [ ] T028 [US3] **TDD VERIFY**: Run full test suite and document results
  - Execute: `./gradlew test`
  - **Expected**: All 389 tests pass (or new total if tests added)
  - **Document**:
    - Total tests: X/X pass
    - Integration tests: X/X pass
    - Unit tests: X/X pass
    - Contract tests: X/X pass
  - If failures: Document specific test names and failure reasons

### Implementation for User Story 3 ✅ GREEN

- [ ] T029 [US3] **TDD GREEN**: Fix any remaining integration test failures
  - Review failure logs from T028
  - Common issues:
    - URL mismatches: Update test URLs to `/api/dfc/**` or `/api/admin/**`
    - Authentication context issues: Verify TestSecurityConfig mirrors production
    - Token type mismatches: Update tests to use correct token for endpoint
  - Fix each failing test individually
  - Re-run after each fix to verify

- [ ] T030 [US3] **TDD GREEN**: Verify code coverage maintained
  - Execute: `./gradlew jacocoTestReport`
  - Review: `build/reports/jacoco/test/html/index.html`
  - **Expected**: Coverage ≥80% overall (constitution requirement)
  - **Expected**: Security configuration classes covered
  - **Expected**: Controller classes covered

- [ ] T031 [US3] **TDD GREEN**: Run full test suite again to verify 100% pass rate
  - Execute: `./gradlew test`
  - **Expected**: 100% of tests pass (0 failures, 0 errors)
  - **Success Criteria Met**: SC-001 verified

**Checkpoint**: User Story 3 complete - All 389 tests pass, refactoring verified successful

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final improvements and validation

- [ ] T032 [P] Update OpenAPI documentation with new URL patterns (src/main/resources/ or @OpenAPIDefinition in controllers)
  - Document JWT authentication required for `/api/dfc/**`
  - Document Keycloak OAuth2 required for `/api/admin/**`
  - Update example requests with correct URLs
  - Regenerate Swagger UI

- [ ] T033 [P] Update CLAUDE.md with security refactoring details
  - Document separate filter chain architecture
  - Document URL pattern conventions (`/api/dfc/**` vs `/api/admin/**`)
  - Update "Known Limitations" section (remove TestSecurityConfig divergence note)
  - Update "Recent Implementation Decisions" with security separation details

- [ ] T034 Performance validation: Verify authentication processing time <100ms
  - Start application: `./gradlew bootRun --args='--spring.profiles.active=dev'`
  - Test JWT auth time: Make 10 requests to `/api/dfc/batch/start`, measure latency
  - Test Keycloak auth time: Make 10 requests to `/api/admin/accounts`, measure latency
  - Verify p95 latency <100ms for both (SC-006)
  - Use Micrometer metrics: `curl http://localhost:8080/actuator/metrics/security.authentication.duration`

- [ ] T035 Manual testing: Validate authentication behavior
  - Follow quickstart.md manual testing section
  - Test JWT on `/api/dfc/**` → Success
  - Test Keycloak on `/api/admin/**` → Success
  - Test JWT on `/api/admin/**` → 403 Forbidden
  - Test Keycloak on `/api/dfc/**` → 403 Forbidden
  - Test no token on both → 401 Unauthorized

- [ ] T036 [P] Prepare client communication materials
  - Draft breaking change announcement (use template from quickstart.md)
  - Document URL migration mapping: `/api/v1/**` → `/api/dfc/**`
  - Create migration timeline (2 weeks notice recommended)
  - Prepare rollback plan documentation

- [ ] T037 Run quickstart.md validation checklist
  - Verify all items in "Testing Checklist" section
  - Confirm 389/389 tests pass ✓
  - Confirm JWT auth works on `/api/dfc/**` ✓
  - Confirm Keycloak auth works on `/api/admin/**` ✓
  - Confirm wrong token type returns 403 ✓
  - Confirm auth failures logged with token type ✓
  - Confirm TestSecurityConfig mirrors production ✓
  - Confirm no dual token detection logic remains ✓
  - Confirm code coverage ≥80% ✓

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup - **BLOCKS all user stories**
  - T006 TestSecurityConfig refactoring (blocking for US3)
  - T007 Production SecurityConfiguration refactoring (blocking for US1 + US2)
  - T008 DualAuthenticationFilter deletion (blocking for US1 + US2)
- **User Story 1 (Phase 3)**: Depends on Foundational completion - Can run parallel to US2
- **User Story 2 (Phase 4)**: Depends on Foundational completion - Can run parallel to US1
- **User Story 3 (Phase 5)**: Depends on US1 + US2 completion
- **Polish (Phase 6)**: Depends on all user stories complete

### User Story Dependencies

- **User Story 1 (JWT endpoints)**: No dependencies on other stories - independently testable
- **User Story 2 (Keycloak endpoints)**: No dependencies on other stories - independently testable
- **User Story 3 (Test verification)**: Depends on US1 + US2 (tests validate both auth systems)

### Within Each User Story

- **Phase 3 (US1)**: Tests (T009-T014) → Implementation (T015-T017) → Verify (T018) → Refactor (T019-T020)
- **Phase 4 (US2)**: Tests (T021-T024) → Implementation (T025-T026) → Verify (T027)
- **Phase 5 (US3)**: Verify (T028) → Fix (T029) → Coverage (T030) → Final Verify (T031)

### Parallel Opportunities

- **Setup Phase (Phase 1)**: T004 and T005 can run in parallel (different file reviews)
- **Foundational Phase (Phase 2)**: T006, T007, T008 are sequential (same files, different sections)
- **User Story 1 Tests**: T009, T010, T011, T012, T013 can run in parallel (different test files)
- **User Story 1 Implementation**: T015, T016, T017 can run in parallel (different controller files)
- **User Story 2 Tests**: T021, T022, T023 can run in parallel (different test files)
- **User Story 2 Implementation**: T025, T026 can run in parallel (different controller files)
- **Polish Phase (Phase 6)**: T032, T033, T036 can run in parallel (different files)

### Critical Path

1. Setup (Phase 1): ~30 minutes
2. **Foundational (Phase 2): ~2-3 hours** ← CRITICAL BLOCKING PHASE
3. User Story 1 Tests + Implementation: ~3-4 hours
4. User Story 2 Tests + Implementation: ~2-3 hours
5. User Story 3 Verification: ~1-2 hours
6. Polish: ~1-2 hours

**Total Estimated Time**: 10-15 hours (single developer, sequential)
**With 2 Developers**: 7-10 hours (US1 and US2 in parallel after Foundational)

---

## Parallel Example: User Story 1 Implementation

```bash
# After Foundational phase (Phase 2) completes, launch User Story 1 tests in parallel:
Task: "T009 [P] Update JwtOnlyWriteIntegrationTest URLs"
Task: "T010 [P] Update DualAuthenticationIntegrationTest"
Task: "T011 [P] Create BatchEndpointsContractTest"
Task: "T012 [P] Create FileUploadEndpointsContractTest"
Task: "T013 [P] Create ErrorLogEndpointsContractTest"

# Then verify all tests FAIL (T014)

# Then launch User Story 1 implementation in parallel:
Task: "T015 [P] Migrate BatchController"
Task: "T016 [P] Migrate ErrorLogController"
Task: "T017 [P] Migrate FileUploadController"

# Then verify all tests PASS (T018)
```

---

## Parallel Example: Both User Stories Together

```bash
# After Foundational phase (Phase 2) completes, assign to 2 developers:

# Developer A: User Story 1 (JWT endpoints)
Task: "T009-T020 [US1] Complete JWT authentication migration"

# Developer B: User Story 2 (Keycloak endpoints)
Task: "T021-T027 [US2] Complete Keycloak authentication verification"

# Both complete independently, then:
# Both developers: User Story 3 (Test verification)
Task: "T028-T031 [US3] Verify 100% test pass rate"
```

---

## Implementation Strategy

### MVP First (All 3 User Stories = MVP for this refactoring)

**Important**: This is a **refactoring task** with a **breaking change**. All 3 user stories are P1 and must complete together to achieve the 100% test pass rate requirement. There is no incremental deployment option.

1. Complete Phase 1: Setup (~30 min)
2. Complete Phase 2: **Foundational (CRITICAL - blocks everything)** (~2-3 hours)
3. Complete Phase 3: User Story 1 - JWT endpoints (~3-4 hours)
4. Complete Phase 4: User Story 2 - Keycloak endpoints (~2-3 hours)
5. Complete Phase 5: User Story 3 - Test verification (~1-2 hours)
6. **STOP and VALIDATE**: Run `./gradlew test` → Expect 389/389 pass
7. Complete Phase 6: Polish & deploy preparation (~1-2 hours)
8. **Deploy with client coordination** (breaking change)

### Validation Checkpoints

- **After Phase 2**: Verify SecurityConfiguration compiles, no syntax errors
- **After Phase 3 (T020)**: Verify JWT tests pass, Keycloak rejection tests pass
- **After Phase 4 (T027)**: Verify Keycloak tests pass, JWT rejection tests pass
- **After Phase 5 (T031)**: Verify 100% test pass rate (SC-001 met)
- **Before Deployment**: Manual testing (T035), performance validation (T034)

### Rollback Plan (if deployment fails)

1. Revert git commit: `git revert <commit-sha>`
2. Redeploy previous version
3. Notify clients: "Deployment postponed, continue using `/api/v1/**`"
4. Fix issues in `003-separation-of-security` branch
5. Re-run full test suite
6. Re-deploy when tests pass

---

## Notes

- **[P] markers**: Indicate tasks that touch different files and can run in parallel
- **[Story] labels**: Map tasks to user stories for traceability (US1, US2, US3)
- **TDD phases**: RED (failing tests) → GREEN (passing tests) → REFACTOR (cleanup)
- **Constitution compliance**: TDD mandatory, tests before implementation, 80% coverage required
- **Breaking change**: All controllers migrate in single deployment, no rollback to `/api/v1/**`
- **Test environment parity**: TestSecurityConfig mirrors production (fixes 9/11 test failures)
- **Success Criteria**: SC-001 verified by T031 (100% test pass rate)
- **Commit strategy**: Commit after each phase checkpoint for granular history
- **Deploy strategy**: Coordinate with client teams 2 weeks in advance (use T036 communication materials)

---

## Success Criteria Verification

| Criteria | Task | Verification Method |
|----------|------|---------------------|
| **SC-001**: 100% tests pass | T031 | `./gradlew test` → 389/389 pass |
| **SC-002**: JWT-only on `/api/dfc/**` | T018 | BatchEndpointsContractTest, FileUploadEndpointsContractTest pass |
| **SC-003**: Keycloak-only on `/api/admin/**` | T027 | AdminAccountEndpointsContractTest, AdminSiteEndpointsContractTest pass |
| **SC-004**: Wrong token type → 403 | T018, T027 | Rejection tests in all contract tests |
| **SC-005**: No broken functionality | T031 | Full test suite pass |
| **SC-006**: Auth <100ms | T034 | Micrometer metrics + manual timing |
| **SC-007**: Test parity | T006 | TestSecurityConfig mirrors production |
