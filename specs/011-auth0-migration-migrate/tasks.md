# Tasks: Auth0 Migration

**Input**: Design documents from `/specs/011-auth0-migration-migrate/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/admin-api-auth0.openapi.yaml

**Tests**: TDD approach required per Constitution Principle III (NON-NEGOTIABLE). Tests written FIRST, implementation follows Red-Green-Refactor cycle.

**Organization**: Tasks grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions
- Backend: `src/main/java/com/bitbi/dfm/`, `src/main/resources/`, `src/test/java/`
- Frontend: `frontend/src/`, `frontend/tests/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and Auth0 dependency configuration

- [x] **T001** [P] Add Auth0 dependencies to `build.gradle.kts`: `com.auth0:auth0:2.26.0`, `com.auth0:java-jwt:4.4.0`
- [x] **T002** [P] Add Auth0 React SDK to `frontend/package.json`: `@auth0/auth0-react:^2.8.0`
- [x] **T003** [P] Remove Keycloak dependencies from `build.gradle.kts`: `org.keycloak:keycloak-admin-client`
- [x] **T004** [P] Remove Keycloak OIDC client from `frontend/package.json`: `oidc-client-ts`
- [x] **T005** Update `.gitignore` to exclude Auth0 credentials (`.env.local`, `application-dev.yml` with secrets)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Database Migration

- [x] **T006** Create Flyway migration `V017__rename_keycloak_to_identity_provider.sql` in `src/main/resources/db/migration/` to:
  - Rename column `keycloak_user_id` → `identity_provider_user_id`
  - Expand column from VARCHAR(36) → VARCHAR(64)
  - Drop old index `idx_accounts_keycloak_user_id`
  - Create new unique index `idx_accounts_identity_provider_user_id`
  - Add column comment

- [ ] **T007** Run `./gradlew flywayMigrate` to apply database migration
- [ ] **T008** Verify migration with `./gradlew flywayInfo` (confirm V017 applied)

### Domain Entities & Value Objects

- [x] **T009** [P] Update `Account.java` entity in `src/main/java/com/bitbi/dfm/account/domain/Account.java`:
  - Rename field `keycloakUserId` → `identityProviderUserId`
  - Update `@Column(name="identity_provider_user_id", length=64)`
  - Add `linkIdentityProvider(String)` method with validation
  - Add `hasIdentityProviderIntegration()` boolean method

- [x] **T010** [P] Create `Auth0UserId.java` value object in `src/main/java/com/bitbi/dfm/auth/domain/Auth0UserId.java`:
  - Java record with validation (format: `{provider}|{alphanumeric}`, max 64 chars)
  - Pattern validation: `^[a-zA-Z0-9-]+\\|[a-zA-Z0-9]+$`
  - Static factory method `of(String)`

- [x] **T011** [P] Create `AccountAuth0LinkedEvent.java` domain event in `src/main/java/com/bitbi/dfm/account/domain/AccountAuth0LinkedEvent.java`:
  - Record with accountId, email, auth0UserId, linkedAt
  - Static factory method `of(Account)`

### Auth0 Configuration & Infrastructure

- [x] **T012** Create `Auth0Properties.java` in `src/main/java/com/bitbi/dfm/auth/config/Auth0Properties.java`:
  - `@ConfigurationProperties(prefix = "auth0")` record
  - Nested records: Management (clientId, clientSecret, audience), Api (audience, issuer)
  - Utility methods: getManagementApiBaseUrl(), getIssuerUrl(), getTokenEndpoint()

- [x] **T013** Update `application.yml` in `src/main/resources/application.yml`:
  - Add `auth0:` section with domain, management-client-id, management-client-secret, api-audience, database-connection, password-reset-ttl-seconds, roles-namespace
  - Update `spring.security.oauth2.resourceserver.jwt` with `issuer-uri` and `audiences`
  - Remove `keycloak:` section (kept JWT section for backward compatibility)

- [x] **T014** Update `application-dev.yml` with Auth0 dev tenant configuration and `logging.level.com.auth0: DEBUG`

- [x] **T015** Create `Auth0TokenProvider.java` in `src/main/java/com/bitbi/dfm/auth/application/Auth0TokenProvider.java`:
  - Service with `String getAccessToken()` method
  - Implementation with 24-hour token caching (Instant expiry tracking)
  - Uses `AuthAPI.newBuilder().requestToken()` to fetch Management API token
  - 5-minute expiry buffer with ReentrantLock for thread-safe refresh

- [x] **T016** Create `Auth0ManagementApiClient.java` wrapper in `src/main/java/com/bitbi/dfm/auth/infrastructure/Auth0ManagementApiClient.java`:
  - Injects `Auth0TokenProvider` and `Auth0Properties`
  - Creates `ManagementAPI` instance with token provider
  - Provides methods: createUser, getUser, getUsersByEmail, blockUser, unblockUser, createPasswordChangeTicket, updateUserMetadata

- [x] **T017** Create Spring Security configuration `Auth0SecurityConfig.java` in `src/main/java/com/bitbi/dfm/auth/config/Auth0SecurityConfig.java`:
  - `@Bean JwtDecoder` with Auth0 JWKS endpoint
  - Custom `JwtAuthenticationConverter` to extract roles from `https://api.dataforge.com/roles` custom claim
  - Authority prefix `ROLE_`
  - Includes AudienceValidator as inner class

- [x] **T018** Create `AudienceValidator.java` in `src/main/java/com/bitbi/dfm/auth/config/Auth0SecurityConfig.java`:
  - Implements `OAuth2TokenValidator<Jwt>` as inner class
  - Validates JWT audience matches `https://api.dataforge.com`

### Repository Updates

- [x] **T019** Update `JpaAccountRepository.java` in `src/main/java/com/bitbi/dfm/account/infrastructure/JpaAccountRepository.java`:
  - Rename query method `findByKeycloakUserId` → `findByIdentityProviderUserId`
  - Add `@Query` for `findAccountsWithIdentityProvider()` (WHERE identity_provider_user_id IS NOT NULL)
  - Add `@Query` for `findAccountsBySearch()` with pageable (search by email/name, filter by identity provider)

### Exception Handling

- [x] **T020** [P] Create `Auth0ServiceUnavailableException.java` in `src/main/java/com/bitbi/dfm/shared/exception/Auth0ServiceUnavailableException.java` (extends RuntimeException)

- [x] **T021** [P] Create `Auth0RateLimitException.java` in `src/main/java/com/bitbi/dfm/shared/exception/Auth0RateLimitException.java` (extends RuntimeException)

- [x] **T022** Update `GlobalExceptionHandler.java` in `src/main/java/com/bitbi/dfm/shared/config/GlobalExceptionHandler.java`:
  - Add handler for `Auth0ServiceUnavailableException` → 503 Service Unavailable
  - Add handler for `Auth0RateLimitException` → 503 Service Unavailable with Retry-After header
  - Add handler for Auth0 `APIException` with duplicate email → 409 Conflict

### Frontend Auth0 Provider Setup

- [x] **T023** Create `Auth0Provider.tsx` wrapper in `frontend/src/app/providers/Auth0Provider.tsx`:
  - Import `Auth0Provider` from `@auth0/auth0-react`
  - Configure with domain, clientId, authorizationParams (redirect_uri, audience, scope)
  - Enable `useRefreshTokens={true}`, `cacheLocation="memory"`
  - Add `onRedirectCallback` to handle navigation

- [x] **T024** Update `frontend/src/app/main.tsx` to wrap app in Auth0Provider

- [x] **T025** [P] Create `AuthenticationGuard.tsx` HOC in `frontend/src/shared/lib/auth/AuthenticationGuard.tsx`:
  - Uses `withAuthenticationRequired` from Auth0 SDK
  - Shows `LoadingSpinner` during redirect
  - Passes `returnTo` for post-login navigation

- [x] **T026** [P] Create `RoleGuard.tsx` HOC in `frontend/src/shared/lib/auth/RoleGuard.tsx`:
  - Uses `useAuth0()` hook
  - Extracts roles from `user['https://api.dataforge.com/roles']`
  - Returns 403 if role not present

- [x] **T027** [P] Create `useAuth0Roles.ts` hook in `frontend/src/shared/lib/auth/useAuth0Roles.ts`:
  - Custom hook to extract roles from Auth0 user
  - Returns `hasRole(role: string)`, `hasAnyRole()`, `hasAllRoles()` helpers

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Admin Creates User Account via Auth0 (Priority: P1) 🎯 MVP

**Goal**: Enable admins to create new user accounts in Auth0 with automatic role assignment and password reset link generation

**Independent Test**: Create user via POST `/api/v1/admin/accounts`, verify user exists in Auth0 with correct role, verify password reset link returned

### Tests for User Story 1

**NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [x] **T028** [P] [US1] Contract test for `POST /api/v1/admin/accounts` in `src/test/java/com/bitbi/dfm/contract/Auth0AdminContractTest.java`:
  - Test valid request returns 201 with temporaryPassword
  - Test invalid email returns 400
  - Test duplicate email returns 409
  - Mock Auth0 Management API calls

- [x] **T029** [P] [US1] Integration test for user creation in `src/test/java/com/bitbi/dfm/integration/Auth0UserCreationIntegrationTest.java`:
  - Use Testcontainers PostgreSQL
  - Mock Auth0 Management API
  - Verify bidirectional linkage (PostgreSQL ← Auth0 user ID, Auth0 user_metadata → accountId)
  - Verify rollback on Auth0 failure (no orphaned PostgreSQL record)

- [x] **T030** [P] [US1] Unit test for `Auth0UserId` value object in `src/test/java/com/bitbi/dfm/auth/Auth0UserIdTest.java`:
  - Test valid format accepted
  - Test invalid format throws exception
  - Test null/blank throws exception
  - Test >64 chars throws exception

### DTOs for User Story 1

- [x] **T031** [P] [US1] Create `CreateAccountRequestDto.java` in `src/main/java/com/bitbi/dfm/account/presentation/dto/`:
  - Fields: email, name, phone, company (all with validation)
  - Jakarta Bean Validation annotations

- [x] **T032** [P] [US1] Create `AccountResponseDto.java` in `src/main/java/com/bitbi/dfm/account/presentation/dto/`:
  - Fields: id, email, name, phone, company, isActive, identityProviderUserId, createdAt
  - Factory methods: fromEntity(), fromEntityWithPassword(), fromEntityWithResetUrl()

### Implementation for User Story 1

- [x] **T033** [US1] Create `AccountSyncService.java` in `src/main/java/com/bitbi/dfm/account/application/`:
  - Method `createAccount(email, name, phone, company)` with two-phase commit:
    - Phase 1: Create user in Auth0 via Management API with temporary password
    - Phase 2: Create Account entity in PostgreSQL with identityProviderUserId
    - Phase 3: Update Auth0 user_metadata with accountId (bidirectional mapping)
    - Compensating transaction: Delete Auth0 user if PostgreSQL fails
  - Implements retry logic with `@Retryable` (maxAttempts=3, exponential backoff)
  - Publishes `AccountLinkedEvent` on success
  - Converts Auth0Exception → Auth0RateLimitException (429) or Auth0ServiceUnavailableException (5xx)

- [x] **T034** [US1] Update `AccountAdminController.java`:
  - Add endpoint `POST /api/v1/accounts`
  - `@PreAuthorize("hasRole('ADMIN')")`
  - Validate `@Valid CreateAccountRequestDto`
  - Call `AccountSyncService.createAccount()`
  - Return `ResponseEntity.status(201).body(AccountResponseDto)`
  - OpenAPI annotations for Swagger documentation

- [x] **T035** [US1] Create `Auth0Configuration.java` in `src/main/java/com/bitbi/dfm/auth/config/`:
  - `@Bean ManagementAPI` with CachedAuth0TokenProvider
  - Token caching (24h with 5min buffer)
  - Automatic token refresh using AuthAPI

- [x] **T036** [US1] Create unit tests `AccountSyncServiceTest.java`:
  - TC01: Successful account creation
  - TC02: Rollback Auth0 user on PostgreSQL failure
  - TC03: Account already exists (409)
  - TC04: Auth0 rate limit (503)
  - TC05: Auth0 service unavailable (503)
  - TC06: Metadata update failure handled gracefully
  - Mockito mocks for ManagementAPI, AccountRepository, EventPublisher

- [x] **T037** [US1] Add structured logging with MDC in `AccountSyncService.createAccount`:
  - MDC.put("accountId", account.getId())
  - MDC.put("identityProviderUserId", identityProviderUserId)
  - Log creation success/failure at INFO/ERROR level

**Checkpoint**: User Story 1 complete - admins can create accounts with Auth0 integration

---

## Phase 4: User Story 2 - Admin Locks/Unlocks User Accounts (Priority: P1)

**Goal**: Enable admins to lock (block) and unlock (unblock) user accounts in Auth0

**Independent Test**: Lock account via POST `/api/v1/admin/accounts/{id}/lock`, verify user cannot authenticate, unlock via POST `/api/v1/admin/accounts/{id}/unlock`, verify user can authenticate

### Tests for User Story 2

- [x] **T038** [P] [US2] Contract test for `POST /api/v1/admin/accounts/{id}/lock` in `Auth0AdminContractTest.java`:
  - Test successful lock returns 204
  - Test locking own account returns 403
  - Test non-existent account returns 404
  - Mock Auth0 Management API

- [x] **T039** [P] [US2] Contract test for `POST /api/v1/admin/accounts/{id}/unlock` in `Auth0AdminContractTest.java`:
  - Test successful unlock returns 204
  - Test non-existent account returns 404

- [x] **T040** [P] [US2] Integration test for lock/unlock in `Auth0LockUnlockIntegrationTest.java`:
  - Create user with Auth0
  - Lock user
  - Verify Auth0 user.blocked = true
  - Unlock user
  - Verify Auth0 user.blocked = false

### Implementation for User Story 2

- [x] **T041** [US2] Add methods to `Auth0UserManagementService.java`:
  - `blockUser(String auth0UserId)`: Update user with blocked=true
  - `unblockUser(String auth0UserId)`: Update user with blocked=false
  - Both use `mgmt.users().update(auth0UserId, userUpdate).execute()`

- [x] **T042** [US2] Update `AccountService.java`:
  - Add `lockAccount(UUID accountId, UUID adminAccountId)`:
    - Verify account exists
    - Verify admin is not locking themselves (accountId != adminAccountId)
    - Get Auth0 user ID from Account.identityProviderUserId
    - Call `Auth0UserManagementService.blockUser()`
  - Add `unlockAccount(UUID accountId)`:
    - Verify account exists
    - Get Auth0 user ID
    - Call `Auth0UserManagementService.unblockUser()`

- [x] **T043** [US2] Update `AccountAdminController.java`:
  - Add endpoint `POST /api/v1/admin/accounts/{id}/lock`
  - Add endpoint `POST /api/v1/admin/accounts/{id}/unlock`
  - Both require `@PreAuthorize("hasRole('ADMIN')")`
  - Extract admin account ID from JWT (`@AuthenticationPrincipal Jwt jwt`) for self-lock prevention
  - Return `ResponseEntity.noContent()`

- [x] **T044** [US2] Add MDC logging for lock/unlock operations (accountId, auth0UserId, adminAccountId)

**Checkpoint**: User Story 2 complete - admins can lock/unlock accounts

---

## Phase 5: User Story 3 - Admin Resets User Password (Priority: P1)

**Goal**: Enable admins to generate Auth0 password reset links for users

**Independent Test**: Reset password via POST `/api/v1/admin/accounts/{id}/reset-password`, verify password reset link returned, verify link works on Auth0

### Tests for User Story 3

- [x] **T045** [P] [US3] Contract test for `POST /api/v1/admin/accounts/{id}/reset-password` in `Auth0AdminContractTest.java`:
  - Test successful reset returns 200 with passwordResetLink
  - Test non-existent account returns 404
  - Mock Auth0 ticket creation

- [x] **T046** [P] [US3] Integration test for password reset in `Auth0PasswordResetIntegrationTest.java`:
  - Create user with Auth0
  - Trigger password reset
  - Verify Auth0 ticket URL format (https://{domain}/lo/reset?ticket=xxx)
  - Verify ticket expires in 24 hours

### DTOs for User Story 3

- [x] **T047** [P] [US3] Update `ResetPasswordResponseDto.java` in `src/main/java/com/bitbi/dfm/account/presentation/dto/ResetPasswordResponseDto.java`:
  - Replace `String temporaryPassword` with `String passwordResetLink`
  - Static factory method `of(UUID accountId, String email, String resetLink)`

### Implementation for User Story 3

- [x] **T048** [US3] Update `AccountService.java`:
  - Add `resetPassword(UUID accountId)`:
    - Verify account exists and has Auth0 integration
    - Get Auth0 user ID
    - Call `Auth0UserManagementService.generatePasswordResetLink()`
    - Return `ResetPasswordResponseDto`

- [x] **T049** [US3] Update `AccountAdminController.java`:
  - Add endpoint `POST /api/v1/admin/accounts/{id}/reset-password`
  - Require `@PreAuthorize("hasRole('ADMIN')")`
  - Call `AccountService.resetPassword()`
  - Return `ResponseEntity.ok(response)`

- [x] **T050** [US3] Add Micrometer counter `auth0.password_reset.tickets.generated`

**Checkpoint**: User Story 3 complete - admins can generate password reset links

---

## Phase 6: User Story 4 - User Authenticates via Auth0 for Admin Panel (Priority: P1)

**Goal**: Replace Keycloak login with Auth0 Universal Login on frontend

**Independent Test**: Access admin panel unauthenticated, verify redirect to Auth0, login, verify redirect back with valid JWT

### Tests for User Story 4

- [x] **T051** [P] [US4] Frontend unit test for `AuthenticationGuard.test.tsx` in `frontend/tests/unit/AuthenticationGuard.test.tsx`:
  - Test unauthenticated user sees loading spinner
  - Test authenticated user renders protected component
  - Mock `useAuth0()` hook

- [x] **T052** [P] [US4] Frontend integration test for auth flow in `frontend/tests/integration/auth-flow.test.tsx`:
  - Mock Auth0 redirect
  - Simulate login callback
  - Verify protected route accessible post-login

### Frontend Implementation for User Story 4

- [x] **T053** [P] [US4] Create `LoginButton.tsx` in `frontend/src/features/auth/ui/LoginButton.tsx`:
  - Uses `useAuth0()` hook
  - Calls `loginWithRedirect({ appState: { returnTo: '/dashboard' } })`

- [x] **T054** [P] [US4] Create `LogoutButton.tsx` in `frontend/src/features/auth/ui/LogoutButton.tsx`:
  - Uses `useAuth0()` hook
  - Calls `logout({ logoutParams: { returnTo: window.location.origin } })`

- [x] **T055** [P] [US4] Create `UserProfile.tsx` in `frontend/src/features/auth/ui/UserProfile.tsx`:
  - Displays user.name, user.email, user.picture from `useAuth0()`
  - Shows roles from custom claim

- [x] **T056** [US4] Update `frontend/src/app/routes.tsx`:
  - Wrap protected routes with `<AuthenticationGuard component={...} />`
  - Remove Keycloak login route

- [x] **T057** [US4] Delete Keycloak login page `frontend/src/pages/login/` directory

- [x] **T058** [US4] Update API client `frontend/src/entities/user/api/userApi.ts`:
  - Use `getAccessTokenSilently()` from `useAuth0()` to get token
  - Add Authorization header: `Bearer ${token}`

**Checkpoint**: User Story 4 complete - frontend uses Auth0 Universal Login

---

## Phase 7: User Story 5 - System Validates Auth0 JWT Tokens (Priority: P1)

**Goal**: Validate Auth0 JWT tokens with custom claims extraction

**Independent Test**: Make API request with valid Auth0 token containing roles, verify Spring Security grants access based on role

### Tests for User Story 5

- [x] **T059** [P] [US5] Integration test for JWT validation in `Auth0JwtValidationIntegrationTest.java`:
  - Generate mock Auth0 JWT with RS256 signature
  - Include custom claim `https://api.dataforge.com/roles: ["ROLE_ADMIN"]`
  - Make request to `/api/v1/batches`
  - Verify 200 response (token accepted)

- [x] **T060** [P] [US5] Integration test for invalid JWT in `Auth0JwtValidationIntegrationTest.java`:
  - Test expired token → 401
  - Test invalid signature → 401
  - Test missing audience → 401
  - Test missing roles claim → 403

### Implementation for User Story 5

- [x] **T061** [US5] Verify `Auth0SecurityConfig.java` (from Phase 2) correctly extracts roles:
  - Custom `JwtAuthenticationConverter` uses `authoritiesClaimName="https://api.dataforge.com/roles"`
  - Authority prefix `ROLE_`
  - Run tests T059, T060 to confirm

- [x] **T062** [US5] Add logging in `Auth0SecurityConfig`:
  - Log successful JWT validation (user, roles)
  - Log JWT validation failures (reason)

**Checkpoint**: User Story 5 complete - backend validates Auth0 JWTs with custom claims

---

## Phase 8: User Story 6 - Admin Views List of Users with Auth0 Integration (Priority: P2)

**Goal**: Display paginated list of Auth0-integrated users with Auth0-specific fields (blocked status, last login)

**Independent Test**: Create multiple users with Auth0, call GET `/api/v1/admin/accounts`, verify list includes Auth0 fields

### Tests for User Story 6

- [ ] **T063** [P] [US6] Contract test for `GET /api/v1/admin/accounts` in `Auth0AdminContractTest.java`:
  - Test pagination (page, size parameters)
  - Test search by email/name
  - Test invalid search pattern returns 400
  - Mock Auth0 Management API `getUser()` calls for blocked status and last_login

- [ ] **T064** [P] [US6] Integration test for user listing in `Auth0UserListIntegrationTest.java`:
  - Create 3 users with Auth0
  - Fetch page 0, size 2
  - Verify totalElements = 3, totalPages = 2
  - Verify Auth0 fields populated (auth0UserId, isBlocked, lastLogin)

### DTOs for User Story 6

- [ ] **T065** [P] [US6] Create `AccountDetailDto.java` in `src/main/java/com/bitbi/dfm/account/presentation/dto/AccountDetailDto.java`:
  - Fields: id, email, name, phone, company, isActive, identityProviderUserId, isBlocked, lastLogin, createdAt
  - Static factory `fromEntity(Account, boolean isBlocked, Instant lastLogin)`

- [ ] **T066** [P] [US6] Create `AccountPageDto.java` DTO:
  - Fields: content (List<AccountDetailDto>), page, size, totalElements, totalPages

### Implementation for User Story 6

- [ ] **T067** [US6] Create `AccountQueryService.java` in `src/main/java/com/bitbi/dfm/account/application/AccountQueryService.java`:
  - Method `listAccounts(String search, Pageable pageable)`:
    - Query PostgreSQL using `findAccountsBySearch()` if search provided
    - Otherwise use `findAccountsWithIdentityProvider()`
    - For each account, call Auth0 Management API to get blocked status and last_login
    - Map to `AccountDetailDto`
    - Return `PageResponseDto<AccountDetailDto>`

- [ ] **T068** [US6] Update `AccountAdminController.java`:
  - Add endpoint `GET /api/v1/accounts`
  - Query params: `@RequestParam(required=false) String search`, `@RequestParam(defaultValue="0") int page`, `@RequestParam(defaultValue="20") int size`
  - Validate search with `@Size(max=100) @Pattern(regexp="^[a-zA-Z0-9@.\\s\\-]+$")`
  - Call `AccountQueryService.listAccounts()`
  - Return `ResponseEntity.ok(page)`

- [ ] **T069** [US6] Add caching for Auth0 user details (optional optimization):
  - Spring Cache with 5-minute TTL for blocked status and last_login
  - Cache key: auth0UserId

**Checkpoint**: User Story 6 complete - admins can view Auth0-integrated users with Auth0 fields

---

## Phase 9: User Story 7 - Migration Script Transfers Existing Users from Keycloak to Auth0 (Priority: P2)

**Goal**: Bulk migrate existing Keycloak users to Auth0 with role preservation and password reset email generation

**Independent Test**: Export Keycloak users, run migration script, verify all users exist in Auth0 with correct roles and PostgreSQL updated

### Tests for User Story 7

- [ ] **T070** [P] [US7] Integration test for migration in `KeycloakToAuth0MigrationIntegrationTest.java`:
  - Mock Keycloak user export (3 users: 1 USER, 2 ADMIN)
  - Run migration
  - Verify 3 users created in Auth0 with correct roles
  - Verify PostgreSQL updated with Auth0 user IDs
  - Verify 3 password reset emails generated

- [ ] **T071** [P] [US7] Unit test for migration error handling:
  - Test Auth0 duplicate email error → logs error, continues
  - Test Auth0 rate limit → retries with backoff
  - Test Auth0 service unavailable → logs critical error

### Implementation for User Story 7

- [ ] **T072** [US7] Create `KeycloakUserExportService.java` in `src/main/java/com/bitbi/dfm/migration/KeycloakUserExportService.java`:
  - Method `exportUsers()`:
    - Call Keycloak Admin API `GET /admin/realms/{realm}/users?max=10000`
    - Map to `KeycloakUser` DTO (email, firstName, lastName, roles, accountId from attributes)
    - Return `List<KeycloakUser>`

- [ ] **T073** [US7] Create `KeycloakToAuth0MigrationService.java` in `src/main/java/com/bitbi/dfm/migration/KeycloakToAuth0MigrationService.java`:
  - Method `migrateUsers()`:
    - Export Keycloak users
    - For each user:
      - Create in Auth0 via `Auth0UserManagementService.createUserWithRole()`
      - Update PostgreSQL account with `linkIdentityProvider(auth0UserId)`
      - Generate password reset ticket
      - Log success/failure
    - Return `MigrationReport` (total, succeeded, failed, errors)

- [ ] **T074** [US7] Create `MigrationController.java` (admin-only endpoint):
  - `POST /api/v1/admin/migration/keycloak-to-auth0`
  - Requires `ROLE_ADMIN`
  - Calls `KeycloakToAuth0MigrationService.migrateUsers()`
  - Returns `MigrationReport`

- [ ] **T075** [US7] Add migration metrics:
  - Counter `migration.users.total`
  - Counter `migration.users.succeeded`
  - Counter `migration.users.failed`
  - Timer `migration.duration`

- [ ] **T076** [US7] Create migration logging (separate log file `logs/migration.log`):
  - Log each user migration attempt
  - Log errors with full stack trace
  - Log final report summary

**Checkpoint**: User Story 7 complete - migration script transfers Keycloak users to Auth0

---

## Phase 10: User Story 8 - Developer Runs Tests Against Auth0 Mock Service (Priority: P2)

**Goal**: Enable offline testing with mocked Auth0 API responses

**Independent Test**: Run full test suite with Auth0 mocked, verify all tests pass

### Implementation for User Story 8

- [ ] **T077** [P] [US8] Create `TestAuth0Config.java` in `src/test/java/com/bitbi/dfm/config/TestAuth0Config.java`:
  - `@TestConfiguration` class
  - `@Bean @Primary ManagementAPI mockManagementAPI()`:
    - Returns Mockito mock of `ManagementAPI`
    - Stub `users().create()` to return mock User with auth0|test123
    - Stub `users().update()` to return updated User
    - Stub `tickets().createPasswordChange()` to return mock Ticket with URL
    - Stub `roles().assignUsers()` to succeed

- [ ] **T078** [P] [US8] Create `MockAuth0JwtDecoder.java` in `src/test/java/com/bitbi/dfm/config/MockAuth0JwtDecoder.java`:
  - `@Bean @Primary JwtDecoder` for test profile
  - Returns JWT with claims: sub, iss, aud, exp, iat, custom roles claim
  - Used in integration tests without real Auth0 connection

- [ ] **T079** [US8] Update `application-test.yml`:
  - Set `auth0.enabled=false` flag
  - Use `TestAuth0Config` when flag is false

- [ ] **T080** [US8] Verify all existing integration tests use `@Import(TestAuth0Config.class)`

- [ ] **T081** [US8] Run full backend test suite: `./gradlew test` (verify 0 failures)

- [ ] **T082** [US8] Create frontend mock for `useAuth0()` in `frontend/tests/mocks/mockAuth0.ts`:
  - Mock implementation returning:
    - `isAuthenticated: true`
    - `user: { email, name, roles }`
    - `getAccessTokenSilently: () => Promise.resolve('mock-token')`
    - `loginWithRedirect: jest.fn()`
    - `logout: jest.fn()`

- [ ] **T083** [US8] Update frontend test setup to use mock Auth0: `frontend/tests/setup.ts`

- [ ] **T084** [US8] Run full frontend test suite: `npm test` (verify 0 failures)

**Checkpoint**: User Story 8 complete - tests run offline with mocked Auth0

---

## Phase 11: Polish & Cross-Cutting Concerns

**Purpose**: Final improvements affecting multiple user stories

- [ ] **T085** [P] Remove deprecated Keycloak code:
  - Delete `KeycloakSecurityConfig.java`
  - Delete `KeycloakAdminClient.java`
  - Delete all Keycloak test configurations
  - Verify `./gradlew build` succeeds

- [ ] **T086** [P] Remove Keycloak Docker Compose service from `docker-compose.yml`

- [ ] **T087** [P] Update OpenAPI documentation in `contracts/admin-api-auth0.openapi.yaml`:
  - Verify all endpoints documented
  - Add examples for error responses
  - Generate Swagger UI: `./gradlew generateOpenApiDocs`

- [ ] **T088** [P] Update `CLAUDE.md` with Auth0 technology stack:
  - Add Auth0 section under "Active Technologies"
  - Remove Keycloak references
  - Document Auth0 custom claims pattern

- [ ] **T089** Security audit:
  - Verify Auth0 credentials not in Git (`.env.local`, `application-dev.yml`)
  - Verify Auth0 Management API token has minimal required permissions
  - Verify HTTPS required in production (`application-prod.yml`)

- [ ] **T090** [P] Performance testing:
  - Verify Auth0 Management API calls <5s (use metrics)
  - Verify JWT validation <50ms (use metrics)
  - Verify frontend auth flow <5s (manual test)

- [ ] **T091** Run quickstart validation:
  - Follow `quickstart.md` setup instructions
  - Verify Auth0 tenant configured correctly
  - Verify backend starts without errors
  - Verify frontend authenticates successfully

- [ ] **T092** Final test run:
  - Backend: `./gradlew test` (target: >80% coverage)
  - Frontend: `npm test` (target: >80% coverage)
  - All tests must pass

- [ ] **T093** Code coverage report:
  - `./gradlew jacocoTestReport`
  - Verify coverage ≥80% (per Constitution Principle III)
  - If <80%, add missing unit tests

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3-10)**: All depend on Foundational phase completion
  - User stories can proceed in parallel (if staffed)
  - Or sequentially in priority order (P1 → P2)
- **Polish (Phase 11)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational - No dependencies on other stories
- **User Story 2 (P1)**: Can start after Foundational - Uses `Auth0UserManagementService` from US1
- **User Story 3 (P1)**: Can start after Foundational - Uses `Auth0UserManagementService` from US1
- **User Story 4 (P1)**: Can start after Foundational - Independent (frontend only)
- **User Story 5 (P1)**: Can start after Foundational - Uses Spring Security config from Foundational
- **User Story 6 (P2)**: Can start after Foundational - Uses `Auth0UserManagementService` from US1
- **User Story 7 (P2)**: Can start after US1 complete - Uses `Auth0UserManagementService` from US1
- **User Story 8 (P2)**: Can start after Foundational - Independent (testing infrastructure)

### Within Each User Story

- Tests MUST be written and FAIL before implementation (TDD cycle)
- DTOs before services
- Services before controllers
- Core implementation before integration
- Story complete before moving to next priority

### Parallel Opportunities

- **Phase 1 (Setup)**: All tasks marked [P] can run in parallel
- **Phase 2 (Foundational)**:
  - T009, T010, T011 (domain entities) can run in parallel
  - T020, T021 (exceptions) can run in parallel
  - T023, T024, T025, T026, T027 (frontend Auth0 setup) can run in parallel
- **Phase 3 (US1)**:
  - T028, T029, T030 (tests) can run in parallel
  - T031, T032 (DTOs) can run in parallel
- **Phase 4 (US2)**:
  - T038, T039, T040 (tests) can run in parallel
- **Phase 5 (US3)**:
  - T045, T046 (tests) can run in parallel
  - T047 (DTO) can run in parallel with tests
- **Phase 6 (US4)**:
  - T051, T052 (tests) can run in parallel
  - T053, T054, T055 (frontend components) can run in parallel
- **Phase 7 (US6)**:
  - T063, T064 (tests) can run in parallel
  - T065, T066 (DTOs) can run in parallel
- **Phase 8 (US7)**:
  - T070, T071 (tests) can run in parallel
- **Phase 9 (US8)**:
  - T077, T078, T082 (mocks) can run in parallel
- **Phase 11 (Polish)**:
  - T085, T086, T087, T088, T090 can run in parallel

---

## Parallel Example: User Story 1 (Backend)

```bash
# Launch all tests for User Story 1 together:
Task: "Contract test for POST /api/v1/admin/accounts"
Task: "Integration test for user creation with Auth0"
Task: "Unit test for Auth0UserId value object"

# After tests fail, implement in parallel:
Task: "Update CreateAccountRequestDto (add role field)"
Task: "Update CreateAccountResponseDto (replace temporaryPassword)"

# Then implement services sequentially:
Task: "Create Auth0UserManagementService"
Task: "Update AccountSyncService.createAccount"
Task: "Update AccountAdminController"
```

---

## Implementation Strategy

### MVP Scope (User Stories 1-5, Priority P1)

The MVP includes all P1 user stories:
1. Admin creates accounts with Auth0 (US1)
2. Admin locks/unlocks accounts (US2)
3. Admin resets passwords (US3)
4. Users authenticate via Auth0 Universal Login (US4)
5. Backend validates Auth0 JWTs (US5)

**Estimated Effort**: 2-3 weeks (1 backend + 1 frontend developer)

### Incremental Delivery

- **Week 1**: Phase 1 (Setup) + Phase 2 (Foundational)
- **Week 2**: Phase 3-7 (User Stories 1-5) - MVP complete
- **Week 3**: Phase 8-10 (User Stories 6-8) - P2 features
- **Week 4**: Phase 11 (Polish) + Production migration

### Rollout Plan

1. **Development**: MVP (US1-US5) deployed to dev environment, tested by team
2. **Staging**: Full feature set (US1-US8) deployed to staging, user acceptance testing
3. **Production**: Execute US7 (migration) during maintenance window, monitor closely
4. **Post-Migration**: Decommission Keycloak after 90 days

---

## Test Summary

**Total Test Tasks**: 28 (covering all 8 user stories)

**Test Coverage Target**: ≥80% (per Constitution Principle III)

**Test Distribution**:
- Contract tests: 8 tasks (MockMvc)
- Integration tests: 12 tasks (Testcontainers + Mock Auth0)
- Unit tests: 5 tasks (domain logic, value objects)
- Frontend tests: 3 tasks (Vitest + React Testing Library)

---

**Total Tasks**: 93
**Task Count by Phase**:
- Phase 1 (Setup): 5 tasks
- Phase 2 (Foundational): 22 tasks
- Phase 3 (US1): 10 tasks
- Phase 4 (US2): 7 tasks
- Phase 5 (US3): 6 tasks
- Phase 6 (US4): 8 tasks
- Phase 7 (US5): 4 tasks
- Phase 8 (US6): 7 tasks
- Phase 9 (US7): 7 tasks
- Phase 10 (US8): 8 tasks
- Phase 11 (Polish): 9 tasks

**Parallel Opportunities**: 42 tasks marked [P] (45% parallelizable)

**Independent Test Criteria**: Each user story has clear acceptance criteria and can be tested independently after its phase completes
