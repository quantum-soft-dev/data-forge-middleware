# Tasks: Admin User Management (Keycloak-First Architecture)

**Input**: Design documents from `/specs/006-task-the-admin/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/account-management-api.yaml

**Tests**: Tests are included following constitution-mandated TDD workflow (Contract → Integration → Implementation → Unit)

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions
- Backend: `src/main/java/com/bitbi/dfm/`
- Frontend: `frontend/src/`
- Backend Tests: `src/test/java/com/bitbi/dfm/`
- Frontend Tests: `frontend/tests/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and dependencies

- [X] **T001** [P] Add Keycloak Admin Client dependency to `build.gradle.kts`: `implementation("org.keycloak:keycloak-admin-client:23.0.1")`
- [X] **T002** [P] Add Keycloak admin config to `src/main/resources/application-dev.yml` and `application-prod.yml`
- [X] **T003** [P] Create frontend directory structure for FSD architecture: `frontend/src/entities/user/`, `frontend/src/features/user-management/`, `frontend/src/pages/admin/users/`, `frontend/src/widgets/user-management/`

**Checkpoint**: Dependencies and structure ready

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Database Schema

- [X] **T004** Create Flyway migration `src/main/resources/db/migration/V006__extend_accounts_with_keycloak.sql`:
  - ALTER TABLE accounts ADD COLUMN keycloak_user_id VARCHAR(36) UNIQUE
  - CREATE INDEX idx_accounts_keycloak_user_id
  - CREATE TABLE admin_action_logs (all fields from data-model.md)
  - CREATE indexes for admin_action_logs
- [X] **T005** Run migration: `./gradlew flywayMigrate` and verify schema with `\d accounts` in psql

### Domain Layer Extensions

- [X] **T006** [P] Extend `src/main/java/com/bitbi/dfm/account/domain/Account.java`:
  - Add `keycloakUserId` field (String, nullable)
  - Add `createWithKeycloak()` factory method
  - Add `linkToKeycloak()` method
  - Add `hasKeycloakIntegration()` method
  - Write unit tests for new methods
- [X] **T007** [P] Create `src/main/java/com/bitbi/dfm/account/domain/AdminActionLog.java`:
  - Entity with all fields from data-model.md
  - Factory methods: `success()` and `failure()`
  - Write unit tests for factory methods
- [X] **T008** [P] Create `src/main/java/com/bitbi/dfm/account/domain/AdminActionType.java` enum
- [X] **T009** [P] Create `src/main/java/com/bitbi/dfm/account/domain/ActionStatus.java` enum

### Infrastructure Layer

- [X] **T010** Create `src/main/java/com/bitbi/dfm/shared/config/KeycloakAdminConfig.java`:
  - @Bean method for Keycloak admin client
  - Load config from application.yml
  - Use CLIENT_CREDENTIALS grant type
- [X] **T011** Create `src/main/java/com/bitbi/dfm/account/infrastructure/KeycloakAdminClient.java`:
  - Wrapper for Keycloak admin operations
  - Methods: createUser(), enableUser(), disableUser(), resetPassword(), deleteUser()
  - Exception translation to domain exceptions
- [X] **T012** [P] Create `src/main/java/com/bitbi/dfm/account/application/TemporaryPasswordGenerator.java`:
  - SecureRandom-based generation
  - 12 characters: uppercase + lowercase + digits + special
  - Shuffle to avoid predictable patterns
  - Write unit tests for password requirements
- [X] **T013** [P] Create `src/main/java/com/bitbi/dfm/account/infrastructure/AdminActionLogRepository.java`:
  - Spring Data JPA repository interface
  - Query methods: findByTargetAccountId(), findByAdminAccountId()
- [X] **T014** Extend `src/main/java/com/bitbi/dfm/account/infrastructure/JpaAccountRepository.java`:
  - Add `findByKeycloakUserId(String)` query method
  - Add `existsByKeycloakUserId(String)` query method

### Frontend Foundation

- [X] **T015** [P] Create `frontend/src/entities/account/model/types.ts`:
  - Interface: Account (with keycloakUserId field)
  - Interface: AccountWithKeycloakStatus (extends Account)
  - Interface: AdminActionLog
  - Export from `frontend/src/entities/account/index.ts`
- [X] **T016** [P] Create `frontend/src/features/user-management/model/userSchemas.ts`:
  - Zod schema: createAccountSchema (email, name, phone, company, role)
  - Export types via `z.infer`
  - Export from `frontend/src/features/user-management/index.ts`

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Create New User with Temporary Password (Priority: P1) 🎯 MVP

**Goal**: Allow administrators to create new accounts with Keycloak integration and temporary passwords

**Independent Test**: Create a new user through admin console → verify user exists in database (with keycloak_user_id) and Keycloak → login as user → confirm password change requirement enforced

### Tests for User Story 1 (TDD - Write FIRST, ensure FAIL)

- [ ] **T017** [P] [US1] Contract test in `src/test/java/com/bitbi/dfm/account/contract/AccountAdminControllerContractTest.java`:
  - Test POST /api/admin/accounts → 201 with account + temporary password
  - Test POST /api/admin/accounts with duplicate email → 409 Conflict
  - Test POST /api/admin/accounts with invalid email → 400 Bad Request
  - Mock KeycloakAccountSyncService
  - Verify contract matches account-management-api.yaml
- [ ] **T018** [P] [US1] Integration test in `src/test/java/com/bitbi/dfm/account/integration/KeycloakAccountSyncIntegrationTest.java`:
  - Setup: Testcontainers for PostgreSQL + Keycloak
  - Test: Create account → verify in both database AND Keycloak
  - Test: Create duplicate email → verify rollback (neither database nor Keycloak)
  - Test: Keycloak failure → verify database rollback
  - Test: Database failure → verify Keycloak rollback

### Implementation for User Story 1

- [X] **T019** [P] [US1] Create `src/main/java/com/bitbi/dfm/account/presentation/dto/CreateAccountRequest.java`:
  - Fields: email, name, phone (optional), company (optional), role
  - Jakarta validation annotations (@NotBlank, @Email, @Size)
- [X] **T020** [P] [US1] Create `src/main/java/com/bitbi/dfm/account/presentation/dto/AccountWithKeycloakResponse.java`:
  - Extends AccountResponseDto (or includes all Account fields)
  - Additional fields: keycloakEnabled, passwordTemporary, passwordExpiresAt, lastLogin
  - Static method: `fromEntity(Account, UserRepresentation)`
- [X] **T021** [P] [US1] Create `src/main/java/com/bitbi/dfm/account/presentation/dto/CreateAccountResponse.java`:
  - Fields: account (AccountWithKeycloakResponse), temporaryPassword (String)
- [X] **T022** [US1] Implement `src/main/java/com/bitbi/dfm/account/application/KeycloakAccountSyncService.java`:
  - Constructor: inject Keycloak, AccountRepository, TemporaryPasswordGenerator, AdminActionLogRepository
  - Method: `createAccount(CreateAccountRequest)` with two-phase commit:
    1. Create in Keycloak (enabled=true, temporary=true, generate password)
    2. Extract keycloakUserId from response
    3. Create Account with `Account.createWithKeycloak()`
    4. Save bidirectional reference (Keycloak user attributes.accountId)
    5. Save Account to database
    6. Log success to AdminActionLog
    7. Catch exceptions → rollback Keycloak user → log failure → rethrow
  - @Transactional annotation
  - Write unit tests with Mockito (mock all dependencies)
- [X] **T023** [US1] Extend `src/main/java/com/bitbi/dfm/account/presentation/AccountAdminController.java`:
  - Add endpoint: POST /api/admin/accounts (@PostMapping)
  - Parameter: @Valid @RequestBody CreateAccountRequest
  - Return: ResponseEntity<CreateAccountResponse> with @ResponseStatus(201)
  - OpenAPI annotations: @Operation, @ApiResponse
  - SecurityRequirement: keycloakAuth with admin scope
- [X] **T024** [US1] Add Micrometer metrics in KeycloakAccountSyncService:
  - Counter: account.created.success
  - Counter: account.created.failure
  - Timer: account.creation.duration

### Frontend Implementation for User Story 1

- [X] **T025** [P] [US1] Create `frontend/src/features/user-management/api/userQueries.ts`:
  - Query key factory: `accountKeys` with lists(), list(filters), detail(id), auditLogs(id)
  - Hook: `useAccountsQuery(page, size, filters)` → GET /api/admin/accounts
  - Hook: `useAccountQuery(accountId)` → GET /api/admin/accounts/{accountId}
- [X] **T026** [P] [US1] Create `frontend/src/features/user-management/api/userMutations.ts`:
  - Hook: `useCreateAccountMutation()` → POST /api/admin/accounts
  - onSuccess: invalidate accountKeys.lists()
  - onError: show error toast
- [X] **T027** [US1] Create `frontend/src/features/user-management/ui/CreateAccountForm.tsx`:
  - Use React Hook Form + Zod resolver (createAccountSchema)
  - Form fields: email, name, phone (optional), company (optional), role
  - Submit: call useCreateAccountMutation
  - On success: show temporary password in modal/alert (ONCE only)
  - Error handling: display field errors from Zod + API errors
  - Accessibility: ARIA labels, keyboard navigation
- [ ] **T028** [US1] Create `frontend/src/pages/admin/users/CreateAccountPage.tsx`:
  - Layout: page header + CreateAccountForm
  - Navigation: back button to accounts list
  - Export from `frontend/src/pages/admin/users/index.ts`
- [ ] **T029** [P] [US1] Create `frontend/src/entities/account/ui/AccountCard.tsx`:
  - Display: email, name, status badges, keycloak integration status
  - Props: account (AccountWithKeycloakStatus)
- [ ] **T030** [P] [US1] Create `frontend/src/entities/account/ui/AccountStatusBadge.tsx`:
  - Display: isActive (green "Active" / red "Inactive")
  - Display: keycloakEnabled (green "Enabled" / red "Disabled")
  - Display: passwordTemporary (yellow "Temporary" / gray "Permanent")
- [ ] **T031** [US1] Create `frontend/src/widgets/user-management/UserListTable.tsx`:
  - Use TanStack Table with pagination, sorting, filtering
  - Columns: email, name, status badges, actions (link to details)
  - Integration: useAccountsQuery for data fetching
  - Virtualization: if list >100 users
- [ ] **T032** [US1] Create `frontend/src/pages/admin/users/AccountsListPage.tsx`:
  - Layout: page header + filters + UserListTable + "Create Account" button
  - Filters: isActive, hasKeycloak Integration, sort by
  - Navigation: click row → AccountDetailsPage, button → CreateAccountPage
- [ ] **T033** [US1] Update `frontend/src/app/router/` to add routes:
  - /admin/accounts → AccountsListPage
  - /admin/accounts/create → CreateAccountPage
  - /admin/accounts/:id → AccountDetailsPage (placeholder for US2/US3)
- [ ] **T034** [US1] Add navigation link to admin sidebar/menu for "User Management"

### Tests for Frontend US1 (Write after implementation, per constitution)

- [ ] **T035** [P] [US1] Unit tests in `frontend/tests/unit/user-management/CreateAccountForm.test.tsx`:
  - Test: Form renders with all fields
  - Test: Validation errors display (invalid email, missing required fields)
  - Test: Successful submission calls mutation with correct data
  - Test: Temporary password displayed once after success
  - Use Vitest + Testing Library
- [ ] **T036** [P] [US1] Integration test in `frontend/tests/integration/user-management/create-account-flow.test.tsx`:
  - Mock API: POST /api/admin/accounts → 201
  - Test: Fill form → submit → see success message with temp password → navigate to list
  - Test: Duplicate email error → shows error toast
- [ ] **T037** [US1] E2E test in `frontend/tests/e2e/user-management.spec.ts`:
  - Test: Login as admin → navigate to User Management → Create Account → fill form → submit → verify temp password shown → navigate to list → verify new account appears
  - Use Playwright

**Checkpoint**: At this point, User Story 1 (Create Account) should be fully functional and testable independently. This is the MVP!

---

## Phase 4: User Story 2 - Lock and Unlock User Accounts (Priority: P2)

**Goal**: Allow administrators to lock (disable Keycloak user) and unlock (enable Keycloak user) accounts for security control

**Independent Test**: Create test account → lock via admin console → verify user cannot login → unlock → verify user can login

### Tests for User Story 2 (TDD - Write FIRST, ensure FAIL)

- [ ] **T038** [P] [US2] Contract test in `src/test/java/com/bitbi/dfm/account/contract/AccountAdminControllerContractTest.java`:
  - Test POST /api/admin/accounts/{id}/lock → 200 with updated account (keycloakEnabled=false)
  - Test POST /api/admin/accounts/{id}/lock on already locked account → 400 Bad Request
  - Test POST /api/admin/accounts/{id}/unlock → 200 with updated account (keycloakEnabled=true)
  - Test POST /api/admin/accounts/{id}/lock on account without Keycloak → 400 Bad Request
- [ ] **T039** [P] [US2] Integration test in `src/test/java/com/bitbi/dfm/account/integration/KeycloakAccountSyncIntegrationTest.java`:
  - Test: Lock account → verify Keycloak user.enabled = false
  - Test: Unlock account → verify Keycloak user.enabled = true
  - Test: Lock already locked → throws exception
  - Test: Unlock account without Keycloak integration → throws exception

### Implementation for User Story 2

- [ ] **T040** [US2] Extend `src/main/java/com/bitbi/dfm/account/application/KeycloakAccountSyncService.java`:
  - Method: `lockAccount(UUID accountId)`:
    1. Load Account, check hasKeycloakIntegration()
    2. Get Keycloak user by keycloakUserId
    3. Check user.isEnabled() (if false, throw AlreadyLockedException)
    4. Set user.setEnabled(false)
    5. Update Keycloak user
    6. Update Account timestamp
    7. Log success to AdminActionLog
    8. Handle errors, log failure
  - Method: `unlockAccount(UUID accountId)` (inverse logic)
  - Write unit tests with Mockito
- [ ] **T041** [US2] Extend `src/main/java/com/bitbi/dfm/account/presentation/AccountAdminController.java`:
  - Add endpoint: POST /api/admin/accounts/{accountId}/lock
  - Add endpoint: POST /api/admin/accounts/{accountId}/unlock
  - Both return AccountWithKeycloakResponse
  - OpenAPI annotations
- [ ] **T042** [US2] Add Micrometer metrics in KeycloakAccountSyncService:
  - Counter: account.locked.success / failure
  - Counter: account.unlocked.success / failure

### Frontend Implementation for User Story 2

- [ ] **T043** [P] [US2] Extend `frontend/src/features/user-management/api/userMutations.ts`:
  - Hook: `useLockAccountMutation()` → POST /api/admin/accounts/{id}/lock
  - Hook: `useUnlockAccountMutation()` → POST /api/admin/accounts/{id}/unlock
  - Both with optimistic updates (update cache before server response)
  - onSuccess: invalidate accountKeys.detail(accountId)
- [ ] **T044** [P] [US2] Create `frontend/src/features/user-management/ui/LockAccountButton.tsx`:
  - Button with confirmation dialog: "Are you sure you want to lock this account?"
  - Calls useLockAccountMutation
  - Disabled if account already locked or no Keycloak integration
  - Accessibility: ARIA labels
- [ ] **T045** [P] [US2] Create `frontend/src/features/user-management/ui/UnlockAccountButton.tsx`:
  - Button with confirmation dialog: "Are you sure you want to unlock this account?"
  - Calls useUnlockAccountMutation
  - Disabled if account already unlocked
  - Accessibility: ARIA labels
- [ ] **T046** [US2] Create `frontend/src/pages/admin/users/AccountDetailsPage.tsx`:
  - Display: AccountCard with full details
  - Actions: LockAccountButton, UnlockAccountButton (conditionally shown)
  - Display: AdminActionLog list for this account (use useAccountAuditLogsQuery)
  - Layout: details + actions + audit log
- [ ] **T047** [US2] Update UserListTable with inline lock/unlock actions (optional quick actions)

### Tests for Frontend US2

- [ ] **T048** [P] [US2] Unit tests in `frontend/tests/unit/user-management/LockAccountButton.test.tsx`:
  - Test: Button enabled for unlocked account
  - Test: Button disabled for already locked account
  - Test: Confirmation dialog appears on click
  - Test: Mutation called after confirmation
- [ ] **T049** [P] [US2] Integration test in `frontend/tests/integration/user-management/lock-unlock-flow.test.tsx`:
  - Mock API: POST lock → 200, POST unlock → 200
  - Test: Navigate to account details → lock → see status change → unlock → see status revert
- [ ] **T050** [US2] E2E test in `frontend/tests/e2e/user-management.spec.ts`:
  - Test: Create account → lock → attempt login (expect failure) → unlock → login (expect success)

**Checkpoint**: At this point, User Stories 1 AND 2 should both work independently. Lock/unlock functionality complete.

---

## Phase 5: User Story 3 - Reset User Password to Temporary (Priority: P3)

**Goal**: Allow administrators to reset passwords to temporary values that expire in 30 days

**Independent Test**: Select existing account → reset password → verify temporary password works → login as user → confirm password change required

### Tests for User Story 3 (TDD - Write FIRST, ensure FAIL)

- [ ] **T051** [P] [US3] Contract test in `src/test/java/com/bitbi/dfm/account/contract/AccountAdminControllerContractTest.java`:
  - Test POST /api/admin/accounts/{id}/reset-password → 200 with temporaryPassword and expiresAt
  - Test POST /api/admin/accounts/{id}/reset-password on account without Keycloak → 400 Bad Request
  - Test POST /api/admin/accounts/{id}/reset-password on locked account → verify still works
- [ ] **T052** [P] [US3] Integration test in `src/test/java/com/bitbi/dfm/account/integration/KeycloakAccountSyncIntegrationTest.java`:
  - Test: Reset password → verify Keycloak credential.temporary = true
  - Test: Reset password → verify requiredActions contains UPDATE_PASSWORD
  - Test: Verify password expiration set to 30 days from now

### Implementation for User Story 3

- [ ] **T053** [P] [US3] Create `src/main/java/com/bitbi/dfm/account/presentation/dto/ResetPasswordResponse.java`:
  - Fields: accountId (UUID), temporaryPassword (String), expiresAt (Instant)
- [ ] **T054** [US3] Extend `src/main/java/com/bitbi/dfm/account/application/KeycloakAccountSyncService.java`:
  - Method: `resetPassword(UUID accountId)`:
    1. Load Account, check hasKeycloakIntegration()
    2. Generate temporary password
    3. Get Keycloak user
    4. Create CredentialRepresentation (type=PASSWORD, temporary=true)
    5. Call keycloak.users().get(id).resetPassword(credential)
    6. Update Account timestamp
    7. Log success to AdminActionLog
    8. Return ResetPasswordResponse
    9. Handle errors, log failure
  - Write unit tests
- [ ] **T055** [US3] Extend `src/main/java/com/bitbi/dfm/account/presentation/AccountAdminController.java`:
  - Add endpoint: POST /api/admin/accounts/{accountId}/reset-password
  - Return: ResetPasswordResponse
  - OpenAPI annotations
- [ ] **T056** [US3] Add Micrometer metrics:
  - Counter: password.reset.success / failure

### Frontend Implementation for User Story 3

- [ ] **T057** [P] [US3] Extend `frontend/src/features/user-management/api/userMutations.ts`:
  - Hook: `useResetPasswordMutation()` → POST /api/admin/accounts/{id}/reset-password
  - onSuccess: invalidate accountKeys.detail(accountId)
- [ ] **T058** [US3] Create `frontend/src/features/user-management/ui/ResetPasswordDialog.tsx`:
  - Dialog/modal with confirmation: "Reset password for {email}?"
  - Explanation: "A temporary password will be generated. User must change it on next login."
  - On confirm: call useResetPasswordMutation
  - On success: Display temporary password (ONCE only, copy button)
  - Warning: "Save this password now. It will not be shown again."
  - Accessibility: ARIA labels, focus trap
- [ ] **T059** [US3] Update `frontend/src/pages/admin/users/AccountDetailsPage.tsx`:
  - Add "Reset Password" button that opens ResetPasswordDialog
  - Show password temporary indicator if passwordTemporary=true

### Tests for Frontend US3

- [ ] **T060** [P] [US3] Unit tests in `frontend/tests/unit/user-management/ResetPasswordDialog.test.tsx`:
  - Test: Dialog renders with confirmation message
  - Test: Temporary password displayed after successful reset
  - Test: Copy button works
  - Test: Warning message shown
- [ ] **T061** [P] [US3] Integration test in `frontend/tests/integration/user-management/reset-password-flow.test.tsx`:
  - Mock API: POST reset-password → 200 with temp password
  - Test: Open dialog → confirm → see temp password → copy → close
- [ ] **T062** [US3] E2E test in `frontend/tests/e2e/user-management.spec.ts`:
  - Test: Navigate to account details → reset password → see temp password → logout → login as that user with temp password → forced to change password

**Checkpoint**: All user stories should now be independently functional. Complete user management feature delivered!

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories, audit log viewing, final integration

- [ ] **T063** [P] Implement GET /api/admin/accounts/{id}/audit-logs endpoint in AccountAdminController:
  - Return paginated AdminActionLog entries
  - Query AdminActionLogRepository.findByTargetAccountId()
- [ ] **T064** [P] Create `frontend/src/features/user-management/api/userQueries.ts`:
  - Hook: `useAccountAuditLogsQuery(accountId, page, size)`
- [ ] **T065** Create AdminActionLogList component to display audit trail in AccountDetailsPage
- [ ] **T066** [P] Add filtering to UserListTable: filter by isActive, hasKeycloakIntegration, passwordTemporary
- [ ] **T067** [P] Add sorting to UserListTable: sort by createdAt, email, name
- [ ] **T068** [P] Add pagination controls to UserListTable
- [ ] **T069** Handle edge case: Prevent admin from locking their own account (check in KeycloakAccountSyncService)
- [ ] **T070** [P] Error handling improvements: Add specific exception classes (AccountNotFoundException, AccountAlreadyLockedException, KeycloakSyncException)
- [ ] **T071** [P] Add comprehensive logging with MDC context (accountId, adminId, action) in KeycloakAccountSyncService
- [ ] **T072** [P] Frontend: Add loading states and skeletons to all pages/components
- [ ] **T073** [P] Frontend: Add error boundaries for graceful error handling
- [ ] **T074** [P] Frontend: Implement toast notifications for success/error feedback (use Sonner)
- [ ] **T075** Run `./gradlew test` and verify 80% backend coverage threshold met
- [ ] **T076** Run `npm run test:coverage` in frontend and verify 80% coverage threshold met
- [ ] **T077** Run quickstart.md validation: Test all cURL commands
- [ ] **T078** Update CLAUDE.md with Keycloak-first architecture decisions and new patterns
- [ ] **T079** [P] Performance testing: Verify <3s for account creation under load
- [ ] **T080** [P] Security review: Verify no credentials in code, all endpoints require ROLE_ADMIN
- [ ] **T081** Accessibility audit: Run axe-core on all new pages, fix violations
- [ ] **T082** Final integration test: Create → Lock → Unlock → Reset → Delete (full lifecycle)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - **BLOCKS all user stories**
- **User Stories (Phase 3-5)**: All depend on Foundational phase completion
  - User Story 1 (P1): Can start after Foundational - No dependencies on other stories
  - User Story 2 (P2): Can start after Foundational - Independent of US1 (but integrates in UI)
  - User Story 3 (P3): Can start after Foundational - Independent of US1/US2 (but integrates in UI)
- **Polish (Phase 6)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (Create)**: Foundational phase must be complete → Then US1 can proceed independently
- **User Story 2 (Lock/Unlock)**: Foundational phase must be complete → Then US2 can proceed independently (AccountDetailsPage integrates with US1 UI but logic is independent)
- **User Story 3 (Reset)**: Foundational phase must be complete → Then US3 can proceed independently

**Key Insight**: Once Foundational phase completes, all 3 user stories can be developed in parallel by different team members!

### Within Each User Story (TDD Order per Constitution)

1. **Contract Tests** (write FIRST, must FAIL) [P]
2. **Integration Tests** (write FIRST, must FAIL) [P]
3. **Domain Models/DTOs** (implement) [P]
4. **Application Services** (implement)
5. **Presentation Controllers/Components** (implement)
6. **Unit Tests** (write AFTER implementation per constitution)
7. **Frontend Integration/E2E Tests** (write AFTER implementation)

### Parallel Opportunities

**Within Setup (Phase 1)**:
- T001, T002, T003 can all run in parallel

**Within Foundational (Phase 2)**:
- T006, T007, T008, T009 (domain enums/entities) can run in parallel
- T012, T013 (infrastructure utilities) can run in parallel
- T015, T016 (frontend types/schemas) can run in parallel

**Across User Stories (Phase 3-5)**:
- Once Phase 2 completes, ALL user stories can start in parallel:
  - Developer A: User Story 1 (T017-T037)
  - Developer B: User Story 2 (T038-T050)
  - Developer C: User Story 3 (T051-T062)

**Within Each User Story**:
- Contract tests [P] with integration tests [P] (both must fail before implementation)
- All DTOs [P] with all models [P] (different files)
- Frontend: queries [P] with mutations [P] (different files)
- Frontend: all UI components marked [P] (different files)
- All unit tests [P] after implementation complete

---

## Parallel Example: User Story 1

```bash
# Phase 1: Tests (write FIRST, ensure FAIL)
Task: "T017 [US1] Contract test for POST /api/admin/accounts"
Task: "T018 [US1] Integration test for account creation with Keycloak sync"

# Phase 2: Models/DTOs (after tests fail)
Task: "T019 [US1] Create CreateAccountRequest DTO"
Task: "T020 [US1] Create AccountWithKeycloakResponse DTO"
Task: "T021 [US1] Create CreateAccountResponse DTO"

# Phase 3: Frontend queries/mutations (after service implemented)
Task: "T025 [US1] Create userQueries.ts"
Task: "T026 [US1] Create userMutations.ts"

# Phase 4: Frontend UI components (after queries/mutations)
Task: "T029 [US1] Create AccountCard component"
Task: "T030 [US1] Create AccountStatusBadge component"

# Phase 5: Frontend tests (after implementation complete)
Task: "T035 [US1] Unit test CreateAccountForm"
Task: "T036 [US1] Integration test create-account-flow"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T003)
2. Complete Phase 2: Foundational (T004-T016) - **CRITICAL blocker**
3. Complete Phase 3: User Story 1 (T017-T037)
4. **STOP and VALIDATE**: Test User Story 1 end-to-end independently
5. Run ./gradlew test && cd frontend && npm test
6. Deploy/demo if ready → **This is your MVP!**

### Incremental Delivery

1. Setup + Foundational (T001-T016) → Foundation ready
2. Add User Story 1 (T017-T037) → Test independently → Deploy (MVP with account creation!)
3. Add User Story 2 (T038-T050) → Test independently → Deploy (MVP + security controls)
4. Add User Story 3 (T051-T062) → Test independently → Deploy (Full feature)
5. Polish (T063-T082) → Final touches → Production ready
6. Each increment adds value without breaking previous functionality

### Parallel Team Strategy (Recommended)

With 3+ developers:

1. **Everyone together**: Complete Setup + Foundational (T001-T016)
2. **Once Foundational done, split work**:
   - **Developer A**: User Story 1 - Create Account (T017-T037)
   - **Developer B**: User Story 2 - Lock/Unlock (T038-T050)
   - **Developer C**: User Story 3 - Reset Password (T051-T062)
3. **Integrate**: Each story merges independently, UI pages integrate naturally
4. **Final polish**: Everyone collaborates on Phase 6 (T063-T082)

**Timeline Estimate**:
- Setup + Foundational: 2-3 days (sequential, blocks everything)
- Each User Story: 3-4 days (can run in parallel)
- Polish: 2 days
- **Total: 7-9 days sequential OR 5-6 days with 3 developers in parallel**

---

## Notes

- **[P] tasks** = different files, can run in parallel
- **[Story] label** maps task to specific user story for traceability
- **TDD Order**: Tests → Implementation → Unit Tests (per constitution mandate)
- **Each user story** is independently completable and testable
- **Verify tests fail** before implementing (Red → Green → Refactor)
- **Commit** after each task or logical group
- **Stop at checkpoints** to validate story independently
- **Avoid**: Same file edits in parallel, cross-story dependencies that break independence
- **Key Architecture**: Keycloak is source of truth for authentication, Account entity extended (not duplicated)
- **Total Tasks**: 82 tasks (16 setup/foundation, 21 per user story, 10 polish)

---

## Task Count Summary

| Phase | Task Range | Count | Can Parallelize |
|-------|-----------|-------|-----------------|
| Setup | T001-T003 | 3 | Yes (all [P]) |
| Foundational | T004-T016 | 13 | Partially (9 tasks [P]) |
| User Story 1 (P1) | T017-T037 | 21 | Partially (12 tasks [P]) |
| User Story 2 (P2) | T038-T050 | 13 | Partially (8 tasks [P]) |
| User Story 3 (P3) | T051-T062 | 12 | Partially (7 tasks [P]) |
| Polish | T063-T082 | 20 | Partially (13 tasks [P]) |
| **TOTAL** | T001-T082 | **82 tasks** | **49 tasks parallelizable (60%)** |

**MVP Scope**: T001-T037 (Setup + Foundational + User Story 1) = **37 tasks** → Delivers account creation with Keycloak integration