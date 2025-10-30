# Tasks: Site Management for Users and Admins

**Input**: Design documents from `/specs/007-adding-a-site/`
**Prerequisites**: plan.md, spec.md (5 user stories with priorities), research.md, data-model.md, contracts/

**Tests**: Included (TDD mandated by Constitution Principle III - NON-NEGOTIABLE)

**Organization**: Tasks grouped by user story to enable independent implementation and testing

## Format: `[ID] [P?] [Story] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3, US4, US5)
- Include exact file paths in descriptions

## Path Conventions
- Backend: `src/main/java/com/bitbi/dfm/`, `src/test/java/com/bitbi/dfm/`
- Frontend: `frontend/src/`, `frontend/tests/`
- Database: `src/main/resources/db/migration/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and basic structure

- [X] T001 [P] Create frontend FSD directory structure: `frontend/src/{app,pages,widgets,features,entities,shared}`
- [X] T002 [P] Verify backend structure exists: `src/main/java/com/bitbi/dfm/{site,adminactionlog}/`
- [X] T003 [P] Configure TypeScript strict mode in `frontend/tsconfig.json`
- [X] T004 [P] Verify test directories exist: `src/test/java/`, `frontend/tests/`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Database Foundation

- [X] T005 Create Flyway migration `src/main/resources/db/migration/V014__extend_admin_action_logs_for_sites.sql` with admin_action_logs table extensions, indexes, and sites table constraints (unique on account_id+domain, index on is_active)
- [X] T006 Run migration and verify schema: Migration V14 successfully applied, schema at version v14

### Backend Foundation - Domain Layer

- [X] T007 [P] Create PasswordGenerator domain service in `src/main/java/com/bitbi/dfm/site/domain/PasswordGenerator.java` (generates 8-12 char alphanumeric passwords)
- [X] T008 [P] Extend AdminActionLog entity in `src/main/java/com/bitbi/dfm/account/domain/AdminActionLog.java` with targetSiteId field and AdminActionType enum with site actions
- [X] T009 [P] AdminActionLogRepository interface exists in `src/main/java/com/bitbi/dfm/account/infrastructure/AdminActionLogRepository.java`

### Backend Foundation - Infrastructure Layer

- [X] T010 JpaAdminActionLogRepository exists in `src/main/java/com/bitbi/dfm/account/infrastructure/AdminActionLogRepository.java`
- [X] T011 Update SiteRepository interface in `src/main/java/com/bitbi/dfm/site/domain/SiteRepository.java` with new methods: `findByAccountIdAndIsActiveTrueOrderByCreatedAtDesc`, `findByIdAndAccountId`

### Backend Foundation - Application Layer

- [X] T012 Extend SiteService in `src/main/java/com/bitbi/dfm/site/application/SiteService.java` with methods: `listAccountSites`, `deactivateSite`, `activateSite` (reactivateSite), `deleteSite`, `createSite` with custom password

### Backend Foundation - Presentation Layer (DTOs)

- [X] T013 [P] CreateSiteRequestDto exists in `src/main/java/com/bitbi/dfm/site/presentation/dto/CreateSiteRequestDto.java` with validation annotations and optional password field
- [X] T014 [P] SiteResponseDto exists in `src/main/java/com/bitbi/dfm/site/presentation/dto/SiteResponseDto.java` with all required fields
- [X] T015 [P] AdminActionLogResponseDto exists in `src/main/java/com/bitbi/dfm/account/presentation/dto/AdminActionLogResponseDto.java`

### Backend Foundation - Unit Tests

- [X] T016 [P] Write unit test for PasswordGenerator in `src/test/java/com/bitbi/dfm/site/domain/PasswordGeneratorTest.java` (length, format, uniqueness) - 7 tests passing
- [X] T017 [P] Write unit tests for SiteService methods in `src/test/java/com/bitbi/dfm/site/application/SiteServiceTest.java` - 10 tests passing

### Frontend Foundation - Shared Layer

- [X] T018 [P] Create password generator utility in `frontend/src/shared/lib/password-generator.ts`
- [X] T019 [P] Write unit test for password generator in `frontend/src/shared/lib/password-generator.test.ts` - 15 tests passing

### Frontend Foundation - Entities Layer

- [X] T020 [P] Create Site types in `frontend/src/entities/site/model/types.ts` (Site, CreateSiteRequest interfaces)
- [X] T021 [P] Create Site API client in `frontend/src/entities/site/api/siteApi.ts` with all CRUD methods (user + admin endpoints)
- [X] T022 [P] Create public API export in `frontend/src/entities/site/index.ts`

### Frontend Foundation - Features Layer

- [X] T023 [P] Create Zod validation schemas in `frontend/src/features/site-crud/model/schemas.ts` (CreateSiteFormSchema)
- [X] T024 Create TanStack Query hooks in `frontend/src/features/site-crud/model/queries.ts` (useSites, useCreateSite, useUpdateSiteStatus, useDeleteSite, useAdminSites, admin mutations)

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - User Creates Own Site with Generated Credentials (Priority: P1) 🎯 MVP

**Goal**: Enable logged-in users to create sites with manual or generated passwords, view site list sorted by creation date

**Independent Test**: Login as regular user → Navigate to Account Management > Site Management → Click "Add Site" → Enter domain + password (or generate) → Verify site appears in list with Active status

### Tests for User Story 1 (TDD - Write First, Ensure FAIL)

- [X] T025 [P] [US1] Contract test for POST /api/sites in `src/test/java/com/bitbi/dfm/site/contract/SiteContractTest.java` (create site endpoint validation) - 4 test cases written
- [X] T026 [P] [US1] Contract test for GET /api/sites in `src/test/java/com/bitbi/dfm/site/contract/SiteContractTest.java` (list sites endpoint) - 2 test cases written
- [X] T027 [P] [US1] Integration test for user site creation flow in `src/test/java/com/bitbi/dfm/site/integration/SiteManagementIntegrationTest.java` - 6 test cases written
- [X] T028 [P] [US1] Frontend unit test for CreateSiteForm in `frontend/src/features/site-crud/ui/CreateSiteForm.test.tsx` - 20+ test cases covering rendering, validation, password generation, submission
- [X] T029 [P] [US1] Frontend integration test for SiteManagementPage in `frontend/src/pages/site-management/SiteManagementPage.test.tsx`

**⚠️ BLOCKER**: SQL migration issues preventing test execution. V14/V15 migrations need debugging.

**Verify tests FAIL before proceeding to implementation**

### Backend Implementation for User Story 1

- [X] T030 [US1] Implement POST /api/sites endpoint in `src/main/java/com/bitbi/dfm/site/presentation/SiteController.java` (createSite method with optional password)
- [X] T031 [US1] Implement GET /api/sites endpoint in `src/main/java/com/bitbi/dfm/site/presentation/SiteController.java` (listUserSites method)
- [X] T032 [US1] Duplicate domain validation exists in SiteService (SiteAlreadyExistsException)
- [X] T033 [US1] Password hashing implemented via SiteCredentials.generateWithHash()

### Frontend Implementation for User Story 1

- [X] T034 [P] [US1] Create CreateSiteForm component in `frontend/src/features/site-crud/ui/CreateSiteForm.tsx` with password generation button, copy functionality, Zod validation
- [X] T035 [P] [US1] Create SiteList widget in `frontend/src/widgets/site-list/SiteList.tsx` (display sites with domain, status, actions, loading/error states)
- [X] T036 [P] [US1] Create SiteListItem component in `frontend/src/widgets/site-list/ui/SiteListItem.tsx` with activate/deactivate/delete actions and confirmation dialog
- [X] T037 [US1] Create SiteManagementPage in `frontend/src/pages/site-management/SiteManagementPage.tsx` (integrate CreateSiteForm + SiteList) - Added Header component for consistent navigation
- [X] T038 [US1] Add route for /account/sites in `frontend/src/app/router.tsx`
- [X] T039 [US1] Add navigation link to Site Management in Account Management section - Implemented via "Manage Sites" button in Account Details page

### Verification for User Story 1

- [X] T040 [US1] Run backend tests and verify all pass: `./gradlew test --tests *Site*` - ✅ ALL PASSING (SiteContractTest: 6 tests, SiteManagementIntegrationTest: 6 tests, AdminContractTest: 10 site-related tests)
- [ ] T041 [US1] Run frontend tests and verify all pass: `npm test CreateSiteForm SiteList SiteManagementPage`
- [ ] T042 [US1] Manual test: Create site with manual password, verify appears in list
- [ ] T043 [US1] Manual test: Create site with generated password, verify password strength
- [ ] T044 [US1] Manual test: Verify sites sorted by creation date (newest first)

**Checkpoint**: User Story 1 complete - users can create and view sites

---

## Phase 4: User Story 2 - User Deactivates and Reactivates Site (Priority: P1)

**Goal**: Enable users to temporarily suspend and resume sites without losing configuration

**Independent Test**: Create a site → Click "Deactivate" → Verify status changes to Inactive → Click "Activate" → Verify status changes back to Active

### Tests for User Story 2 (TDD - Write First, Ensure FAIL)

- [ ] T045 [P] [US2] Contract test for POST /api/sites/{siteId}/deactivate in `src/test/java/com/bitbi/dfm/site/contract/SiteContractTest.java`
- [ ] T046 [P] [US2] Contract test for POST /api/sites/{siteId}/activate in `src/test/java/com/bitbi/dfm/site/contract/SiteContractTest.java`
- [ ] T047 [P] [US2] Integration test for deactivate/activate flow in `src/test/java/com/bitbi/dfm/site/integration/SiteManagementIntegrationTest.java`
- [ ] T048 [P] [US2] Frontend test for status update in `frontend/src/features/site-crud/model/queries.test.ts` (useUpdateSiteStatus hook)

**Verify tests FAIL before proceeding to implementation**

### Backend Implementation for User Story 2

- [ ] T049 [US2] Implement POST /api/sites/{siteId}/deactivate endpoint in `src/main/java/com/bitbi/dfm/site/presentation/SiteController.java`
- [ ] T050 [US2] Implement POST /api/sites/{siteId}/activate endpoint in `src/main/java/com/bitbi/dfm/site/presentation/SiteController.java`
- [ ] T051 [US2] Add validation: prevent deactivating already inactive site
- [ ] T052 [US2] Add validation: prevent activating already active site
- [ ] T053 [US2] Update error message for deactivated site uploads in existing upload endpoints (FR-022: "Site is inactive" + domain + account contact)

### Frontend Implementation for User Story 2

- [ ] T054 [US2] Add activate/deactivate buttons to SiteListItem component in `frontend/src/widgets/site-list/ui/SiteListItem.tsx`
- [ ] T055 [US2] Implement optimistic updates in useUpdateSiteStatus hook (already in T024, verify implementation)
- [ ] T056 [US2] Add status badge (Active/Inactive) to SiteListItem

### Verification for User Story 2

- [ ] T057 [US2] Run backend tests: `./gradlew test --tests *Site*`
- [ ] T058 [US2] Run frontend tests: `npm test queries SiteList`
- [ ] T059 [US2] Manual test: Deactivate site, verify status changes immediately (<1 second per SC-002)
- [ ] T060 [US2] Manual test: Activate site, verify status changes back
- [ ] T061 [US2] Manual test: Try uploading to deactivated site, verify error message includes domain and account contact

**Checkpoint**: User Stories 1 and 2 complete - users can create, view, activate, and deactivate sites

---

## Phase 5: User Story 3 - User Deletes Site (Priority: P2)

**Goal**: Enable users to permanently remove sites (soft delete) with confirmation dialog

**Independent Test**: Create test site → Click "Delete" → Confirm deletion in dialog → Verify site removed from list

### Tests for User Story 3 (TDD - Write First, Ensure FAIL)

- [ ] T062 [P] [US3] Contract test for DELETE /api/sites/{siteId} in `src/test/java/com/bitbi/dfm/site/contract/SiteContractTest.java`
- [ ] T063 [P] [US3] Integration test for delete flow (verify soft delete) in `src/test/java/com/bitbi/dfm/site/integration/SiteManagementIntegrationTest.java`
- [ ] T064 [P] [US3] Frontend test for DeleteSiteDialog in `frontend/src/features/site-crud/ui/DeleteSiteDialog.test.tsx`

**Verify tests FAIL before proceeding to implementation**

### Backend Implementation for User Story 3

- [ ] T065 [US3] Implement DELETE /api/sites/{siteId} endpoint in `src/main/java/com/bitbi/dfm/site/presentation/SiteController.java` (soft delete via isActive=false)
- [ ] T066 [US3] Verify Site entity deactivate() method exists or add it
- [ ] T067 [US3] Verify batch and upload history preserved after soft delete (FR-024)

### Frontend Implementation for User Story 3

- [ ] T068 [P] [US3] Create DeleteSiteDialog component in `frontend/src/features/site-crud/ui/DeleteSiteDialog.tsx` with confirmation message
- [ ] T069 [US3] Add Delete button to SiteListItem in `frontend/src/widgets/site-list/ui/SiteListItem.tsx`
- [ ] T070 [US3] Wire up delete action in SiteManagementPage (open dialog, handle confirmation)

### Verification for User Story 3

- [ ] T071 [US3] Run backend tests: `./gradlew test --tests *Site*`
- [ ] T072 [US3] Run frontend tests: `npm test DeleteSiteDialog SiteList`
- [ ] T073 [US3] Manual test: Delete site, verify confirmation dialog appears
- [ ] T074 [US3] Manual test: Confirm deletion, verify site removed from list
- [ ] T075 [US3] Manual test: Verify deleted site is soft deleted in database (isActive=false, data preserved)

**Checkpoint**: User Stories 1, 2, and 3 complete - full CRUD for user sites

---

## Phase 6: User Story 4 - Admin Creates Site for User (Priority: P1)

**Goal**: Enable admins to create sites on behalf of users through User Management section with audit logging

**Independent Test**: Login as admin → Navigate to User Management → Select user → Click "Manage Sites" → Add site → Verify site appears in user's account and audit log created

### Tests for User Story 4 (TDD - Write First, Ensure FAIL)

- [ ] T076 [P] [US4] Contract test for GET /api/admin/accounts/{accountId}/sites in `src/test/java/com/bitbi/dfm/site/contract/SiteAdminContractTest.java`
- [ ] T077 [P] [US4] Contract test for POST /api/admin/accounts/{accountId}/sites in `src/test/java/com/bitbi/dfm/site/contract/SiteAdminContractTest.java` (with ROLE_ADMIN check)
- [ ] T078 [P] [US4] Integration test for admin site creation with audit logging in `src/test/java/com/bitbi/dfm/site/integration/SiteManagementIntegrationTest.java`
- [ ] T079 [P] [US4] Frontend test for UserSitesPage (admin view) in `frontend/src/pages/admin/user-sites/UserSitesPage.test.tsx`

**Verify tests FAIL before proceeding to implementation**

### Backend Implementation for User Story 4

- [ ] T080 [US4] Implement GET /api/admin/accounts/{accountId}/sites endpoint in `src/main/java/com/bitbi/dfm/site/presentation/SiteAdminController.java` with @PreAuthorize("hasRole('ADMIN')")
- [ ] T081 [US4] Implement POST /api/admin/accounts/{accountId}/sites endpoint in `src/main/java/com/bitbi/dfm/site/presentation/SiteAdminController.java`
- [ ] T082 [US4] Add audit logging for CREATE_SITE action (write to AdminActionLog table with IP, user agent, timestamp)
- [ ] T083 [US4] Add helper method `logAdminAction()` in SiteAdminController
- [ ] T084 [US4] Add helper method `extractAccountIdFromJwt()` in SiteAdminController

### Frontend Implementation for User Story 4

- [X] T085 [P] [US4] Create UserSitesPage in `frontend/src/pages/admin/user-sites/UserSitesPage.tsx` (admin view of user's sites)
- [X] T086 [P] [US4] Create useAdminSites hook wrapper (already in T024, verify implementation)
- [X] T087 [P] [US4] Reuse CreateSiteForm component with accountId prop for admin context
- [X] T088 [US4] Reuse SiteList widget in admin context
- [X] T089 [US4] Add route for /admin/users/:accountId/sites in `frontend/src/app/router.tsx` with ROLE_ADMIN guard
- [X] T090 [US4] Add "Manage Sites" button in Account Details page with navigation to user's sites

### Verification for User Story 4

- [ ] T091 [US4] Run backend tests: `./gradlew test --tests *SiteAdmin*`
- [ ] T092 [US4] Run frontend tests: `npm test UserSitesPage`
- [ ] T093 [US4] Manual test: Login as admin, create site for user, verify appears in user's list
- [ ] T094 [US4] Manual test: Verify audit log entry created in admin_action_logs table (actionType=CREATE_SITE)
- [ ] T095 [US4] Manual test: Try accessing admin endpoint as regular user, verify 403 Forbidden

**Checkpoint**: User Stories 1, 2, 3, and 4 complete - users and admins can create sites

---

## Phase 7: User Story 5 - Admin Manages User's Sites (Priority: P2)

**Goal**: Enable admins to deactivate, activate, and delete sites for users with audit logging

**Independent Test**: Login as admin → Navigate to user's site management → Perform deactivate/activate/delete → Verify changes and audit logs

### Tests for User Story 5 (TDD - Write First, Ensure FAIL)

- [ ] T096 [P] [US5] Contract test for POST /api/admin/accounts/{accountId}/sites/{siteId}/deactivate in `src/test/java/com/bitbi/dfm/site/contract/SiteAdminContractTest.java`
- [ ] T097 [P] [US5] Contract test for POST /api/admin/accounts/{accountId}/sites/{siteId}/activate in `src/test/java/com/bitbi/dfm/site/contract/SiteAdminContractTest.java`
- [ ] T098 [P] [US5] Contract test for DELETE /api/admin/accounts/{accountId}/sites/{siteId} in `src/test/java/com/bitbi/dfm/site/contract/SiteAdminContractTest.java`
- [ ] T099 [P] [US5] Integration test for admin site operations with audit logging in `src/test/java/com/bitbi/dfm/site/integration/SiteManagementIntegrationTest.java`

**Verify tests FAIL before proceeding to implementation**

### Backend Implementation for User Story 5

- [ ] T100 [US5] Implement POST /api/admin/accounts/{accountId}/sites/{siteId}/deactivate in `src/main/java/com/bitbi/dfm/site/presentation/SiteAdminController.java` with audit logging (DEACTIVATE_SITE)
- [ ] T101 [US5] Implement POST /api/admin/accounts/{accountId}/sites/{siteId}/activate in `src/main/java/com/bitbi/dfm/site/presentation/SiteAdminController.java` with audit logging (ACTIVATE_SITE)
- [ ] T102 [US5] Implement DELETE /api/admin/accounts/{accountId}/sites/{siteId} in `src/main/java/com/bitbi/dfm/site/presentation/SiteAdminController.java` with audit logging (DELETE_SITE)

### Frontend Implementation for User Story 5

- [ ] T103 [US5] Add admin activate/deactivate/delete mutations in `frontend/src/features/site-crud/model/queries.ts` (useAdminUpdateSiteStatus, useAdminDeleteSite)
- [ ] T104 [US5] Wire up admin actions in UserSitesPage (reuse SiteList widget with admin mutation hooks)

### Verification for User Story 5

- [ ] T105 [US5] Run backend tests: `./gradlew test --tests *SiteAdmin*`
- [ ] T106 [US5] Run frontend tests: `npm test queries UserSitesPage`
- [ ] T107 [US5] Manual test: Admin deactivates user's site, verify status changes and audit log entry (DEACTIVATE_SITE)
- [ ] T108 [US5] Manual test: Admin activates user's site, verify status changes and audit log entry (ACTIVATE_SITE)
- [ ] T109 [US5] Manual test: Admin deletes user's site, verify soft delete and audit log entry (DELETE_SITE)
- [ ] T110 [US5] Manual test: Verify all admin actions logged with IP address and user agent

**Checkpoint**: All user stories complete - full site management for users and admins

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Improvements affecting multiple user stories, E2E tests, documentation

### End-to-End Tests

- [ ] T111 [P] E2E test for user site creation flow in `frontend/tests/e2e/site-management.spec.ts` (Playwright)
- [ ] T112 [P] E2E test for user site activation/deactivation in `frontend/tests/e2e/site-management.spec.ts`
- [ ] T113 [P] E2E test for admin site management in `frontend/tests/e2e/admin-site-management.spec.ts`

### Performance & Accessibility

- [ ] T114 [P] Run Lighthouse audit on SiteManagementPage, verify ≥90 score
- [ ] T115 [P] Test keyboard navigation on all site management forms and dialogs
- [ ] T116 [P] Verify ARIA labels on all interactive elements (buttons, inputs, dialogs)
- [ ] T117 Optimize site list query (add database indexes if needed, verify <2s load time for 50 sites)

### Code Quality & Documentation

- [ ] T118 [P] Run backend code coverage: `./gradlew jacocoTestReport`, verify ≥80%
- [ ] T119 [P] Run frontend code coverage: `npm run test:coverage`, verify ≥80%
- [ ] T120 [P] Update OpenAPI documentation (verify generated from SpringDoc matches contracts/)
- [ ] T121 [P] Update CLAUDE.md with new endpoints and audit logging patterns
- [ ] T122 [P] Update README.md with Site Management feature documentation

### Security Hardening

- [ ] T123 [P] Security audit: Verify JWT validation on all endpoints
- [ ] T124 [P] Security audit: Verify ROLE_ADMIN checks on all admin endpoints
- [ ] T125 [P] Security audit: Verify input validation on all DTOs (domain regex, password length)
- [ ] T126 [P] Security audit: Verify no sensitive data (passwords) in logs or responses

### Final Validation

- [ ] T127 Run full test suite: `./gradlew test integrationTest && npm test && npm run test:e2e`
- [ ] T128 Run quickstart.md validation checklist (all manual tests pass)
- [ ] T129 Verify bundle size <500KB gzipped: `npm run build:analyze`
- [ ] T130 Performance test: Verify site deactivation <1 second, API p95 <1000ms

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - start immediately
- **Foundational (Phase 2)**: Depends on Setup → **BLOCKS all user stories**
- **User Stories (Phases 3-7)**: All depend on Foundational completion
  - US1 (P1): Create sites → Independent, MVP
  - US2 (P1): Activate/Deactivate → Requires US1 (needs sites to exist)
  - US3 (P2): Delete sites → Requires US1 (needs sites to exist)
  - US4 (P1): Admin creates sites → Independent from user stories, MVP admin feature
  - US5 (P2): Admin manages sites → Requires US4 (needs admin site list view)
- **Polish (Phase 8)**: Depends on all desired user stories complete

### User Story Dependencies

- **US1**: No dependencies (after Foundational) - **Start here for MVP**
- **US2**: Soft dependency on US1 (needs sites to activate/deactivate) - Can implement in parallel with separate test data
- **US3**: Soft dependency on US1 (needs sites to delete) - Can implement in parallel with separate test data
- **US4**: No dependencies (after Foundational) - **Admin MVP**, can start in parallel with US1
- **US5**: Soft dependency on US4 (reuses admin site list view) - Can implement in parallel with separate test data

### Within Each User Story

1. Tests MUST be written first and FAIL
2. Backend: DTOs → Controllers → Service logic → Validation
3. Frontend: Components → Hooks → Pages → Routes
4. Verification tests after implementation
5. Manual testing after automated tests pass

### Parallel Opportunities

**Setup Phase**: All tasks can run in parallel (T001-T004)

**Foundational Phase**:
- Database: T005-T006 (sequential)
- Backend layers: T007-T009 (parallel), T010-T011 (sequential), T012 (after T010-T011), T013-T015 (parallel), T016-T017 (parallel after implementation)
- Frontend: T018-T019 (parallel), T020-T022 (parallel), T023-T024 (sequential)

**User Stories**:
- US1 and US4 can start in parallel (independent MVPs)
- US2 and US3 can start in parallel after US1
- US5 can start after US4

**Within Each User Story**:
- All test tasks marked [P] can run in parallel
- Backend and frontend implementation can proceed in parallel
- Verification tasks after implementation complete

---

## Parallel Example: Phase 2 (Foundational)

```bash
# Batch 1: Domain layer (after migration complete)
Task: T007 [P] PasswordGenerator domain service
Task: T008 [P] AdminActionLog entity
Task: T009 [P] AdminActionLogRepository interface

# Batch 2: Infrastructure + DTOs (after domain layer)
Task: T010 JpaAdminActionLogRepository (after T009)
Task: T013 [P] CreateSiteRequestDto
Task: T014 [P] SiteResponseDto
Task: T015 [P] AdminActionLogResponseDto

# Batch 3: Frontend foundation (parallel with backend)
Task: T018 [P] Password generator utility
Task: T020 [P] Site types
Task: T021 [P] Site API client
Task: T023 [P] Zod schemas
```

---

## Parallel Example: User Story 1 (MVP)

```bash
# Phase 1: Tests first (all parallel)
Task: T025 [P] [US1] Contract test POST /api/sites
Task: T026 [P] [US1] Contract test GET /api/sites
Task: T027 [P] [US1] Integration test creation flow
Task: T028 [P] [US1] Frontend test CreateSiteForm
Task: T029 [P] [US1] Frontend test SiteManagementPage

# Phase 2: Backend implementation (sequential due to same controller file)
Task: T030 [US1] POST /api/sites endpoint
Task: T031 [US1] GET /api/sites endpoint
Task: T032 [US1] Duplicate domain validation
Task: T033 [US1] Password hashing

# Phase 3: Frontend implementation (all parallel - different files)
Task: T034 [P] [US1] CreateSiteForm component
Task: T035 [P] [US1] SiteList widget
Task: T036 [P] [US1] SiteListItem component
Task: T037 [US1] SiteManagementPage (after T034, T035)
Task: T038 [P] [US1] Add route
Task: T039 [P] [US1] Add navigation link
```

---

## Implementation Strategy

### MVP First (User Stories 1 + 4)

**Goal**: Deliver minimum viable product with user and admin site creation

1. Complete **Phase 1: Setup** (5 minutes)
2. Complete **Phase 2: Foundational** (2-3 hours) → **CRITICAL BLOCKER**
3. Complete **Phase 3: User Story 1** (4-6 hours) → Users can create sites
4. Complete **Phase 6: User Story 4** (3-4 hours) → Admins can create sites for users
5. **STOP and VALIDATE**: Test US1 and US4 independently
6. Deploy/demo if ready → **MVP achieved!**

**MVP delivers**: Site creation for users and admins with password generation and audit logging

### Incremental Delivery

1. **Foundation** → Database + Core services ready
2. **MVP** (US1 + US4) → Test → Deploy → Demo
3. **+US2** (Activate/Deactivate) → Test → Deploy → Demo
4. **+US3** (Delete) → Test → Deploy → Demo
5. **+US5** (Admin manage) → Test → Deploy → Demo
6. **Polish** → E2E tests, performance, docs → Production ready

Each story adds value without breaking previous functionality.

### Parallel Team Strategy

With 3 developers:

1. **Together**: Setup + Foundational (Phase 1-2, 3-4 hours)
2. **Once Foundational complete**:
   - **Dev A**: User Story 1 (user site creation)
   - **Dev B**: User Story 4 (admin site creation)
   - **Dev C**: Start User Story 2 (activate/deactivate)
3. **After MVP (US1+US4)**:
   - **Dev A**: User Story 3 (delete)
   - **Dev B**: User Story 5 (admin manage)
   - **Dev C**: Polish + E2E tests

---

## Notes

- **[P]** tasks = different files, no dependencies, run in parallel
- **[Story]** label maps task to specific user story for traceability
- **TDD is mandatory** (Constitution Principle III): Tests before implementation
- Each user story is independently completable and testable
- Verify tests **FAIL** before implementing (red-green-refactor)
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- **Foundation phase is critical**: Nothing can proceed until T005-T024 complete
- **MVP scope**: US1 (user create) + US4 (admin create) = ~10 hours of work
- **Full feature**: All 5 user stories + polish = ~25-30 hours of work

---

## Test Coverage Targets

- **Backend**: ≥80% overall, ≥95% for SiteService and PasswordGenerator
- **Frontend**: ≥80% overall, 100% for password-generator utility
- **E2E**: Cover all 5 user story critical paths (3 E2E tests minimum)

---

## Success Criteria Validation

After Phase 8 complete, verify:

- [ ] **SC-001**: Users can create site and upload data within 2 minutes
- [ ] **SC-002**: Site deactivation <1 second
- [ ] **SC-003**: 95% of creations succeed first try (test with validation edge cases)
- [ ] **SC-004**: Admin operations within 3 clicks from User Management
- [ ] **SC-005**: Zero data loss (verify soft delete preserves history)
- [ ] **SC-006**: Password generation always produces valid passwords (8-12 chars, alphanumeric)
- [ ] **SC-007**: Site list loads <2 seconds for 50 sites
