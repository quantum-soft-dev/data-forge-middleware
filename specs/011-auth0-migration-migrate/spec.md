# Feature Specification: Auth0 Migration

**Feature Branch**: `011-auth0-migration-migrate`
**Created**: 2025-11-06
**Status**: Draft
**Input**: User description: "## Auth0 migration

- Migrate from `keycloak` to `Auth0`
- Update all related tests. Remove deprecated tests.
- On the `frontend`, remove the `sign in with keycloak` page. Instead, if the user is not authenticated, redirect them to `auth0 login`."

## User Scenarios & Testing

### User Story 1 - Admin Creates User Account via Auth0 (Priority: P1)

An administrator needs to create new user accounts for the Data Forge system. The admin accesses the admin panel, enters user details (email, name, phone, company), and the system creates the user in Auth0 instead of Keycloak.

**Why this priority**: This is the core user management functionality that must work for the system to be operational. Without this, no new users can be onboarded.

**Independent Test**: Can be fully tested by creating a new user through the admin API endpoint and verifying the user exists in Auth0 with correct attributes and assigned roles.

**Acceptance Scenarios**:

1. **Given** admin is authenticated with ROLE_ADMIN, **When** admin submits create account form with valid user details (email, name, role=USER), **Then** system creates user in Auth0 and returns password reset link instead of temporary password
2. **Given** admin creates a user account, **When** user creation succeeds, **Then** system stores Auth0 user ID in PostgreSQL account record and sets accountId in Auth0 user metadata
3. **Given** admin creates user with ROLE_ADMIN, **When** user is created, **Then** system assigns ROLE_ADMIN role in Auth0 and includes role in JWT token claims

---

### User Story 2 - Admin Locks/Unlocks User Accounts (Priority: P1)

An administrator needs to lock (disable) or unlock (enable) user accounts for security or administrative purposes. The system updates user status in Auth0 instead of Keycloak.

**Why this priority**: Essential security feature to prevent unauthorized access when accounts are compromised or users violate policies.

**Independent Test**: Can be tested by locking a user account via admin API, verifying user cannot authenticate with Auth0, then unlocking and confirming authentication works again.

**Acceptance Scenarios**:

1. **Given** admin is authenticated with ROLE_ADMIN, **When** admin locks a user account, **Then** system sets blocked=true in Auth0 and user cannot authenticate
2. **Given** user account is locked, **When** admin unlocks the account, **Then** system sets blocked=false in Auth0 and user can authenticate again
3. **Given** admin attempts to lock their own account, **When** lock request is submitted, **Then** system returns error preventing self-lock

---

### User Story 3 - Admin Resets User Password (Priority: P1)

An administrator needs to reset a user's password when the user forgets it or for security reasons. The system generates a password reset link via Auth0 instead of a temporary password.

**Why this priority**: Critical support function that directly impacts user ability to access the system. Without this, locked-out users cannot regain access.

**Independent Test**: Can be tested by triggering password reset via admin API, verifying Auth0 sends email with reset link, and confirming user can set new password through the link.

**Acceptance Scenarios**:

1. **Given** admin is authenticated with ROLE_ADMIN, **When** admin resets user password, **Then** system generates Auth0 password change ticket and returns reset link URL (instead of temporary password)
2. **Given** password reset link is generated, **When** user clicks the link, **Then** Auth0 presents password reset page where user can set new password
3. **Given** password reset is successful, **When** user sets new password, **Then** user can authenticate with Auth0 using the new password

---

### User Story 4 - User Authenticates via Auth0 for Admin Panel (Priority: P1)

An administrator or user accesses the admin panel and needs to authenticate. The frontend redirects to Auth0 login page instead of Keycloak login page.

**Why this priority**: Core authentication flow that must work for any user access. Without this, the entire system is inaccessible.

**Independent Test**: Can be tested by accessing the admin panel while unauthenticated, verifying redirect to Auth0 login, completing login, and confirming successful authentication with JWT token containing roles.

**Acceptance Scenarios**:

1. **Given** user is not authenticated, **When** user accesses admin panel, **Then** frontend redirects to Auth0 Universal Login page (not Keycloak login)
2. **Given** user enters valid credentials on Auth0 login, **When** authentication succeeds, **Then** Auth0 returns JWT token with custom claims (roles, accountId, email)
3. **Given** user is authenticated with ROLE_ADMIN, **When** user accesses /admin/** endpoints, **Then** Spring Security validates Auth0 JWT and grants access based on roles

---

### User Story 5 - System Validates Auth0 JWT Tokens (Priority: P1)

The backend API receives requests with JWT tokens issued by Auth0 and needs to validate them. The system validates tokens using Auth0's public keys and extracts roles from custom claims instead of Keycloak realm_access.roles.

**Why this priority**: Security foundation for all API requests. Invalid token validation creates critical security vulnerabilities.

**Independent Test**: Can be tested by making API requests with valid/invalid Auth0 tokens and verifying access control works correctly based on token validity and roles.

**Acceptance Scenarios**:

1. **Given** user has valid Auth0 JWT token with ROLE_ADMIN, **When** user makes request to /admin/** endpoint, **Then** Spring Security validates token signature using Auth0 JWKS and grants access
2. **Given** JWT token contains custom claim "https://api.dataforge.com/roles", **When** Spring Security processes token, **Then** system extracts roles and maps them to ROLE_* authorities
3. **Given** user has expired or invalid Auth0 token, **When** user makes API request, **Then** system returns 401 Unauthorized

---

### User Story 6 - Admin Views List of Users with Auth0 Integration (Priority: P2)

An administrator needs to view all users that have Auth0 integration. The system displays user information fetched from both PostgreSQL and Auth0 (enabled/blocked status, last login).

**Why this priority**: Important for user management and monitoring, but system can function without detailed user listing initially.

**Independent Test**: Can be tested by creating multiple users with Auth0 integration and verifying admin panel displays correct information including Auth0-specific fields (blocked status, last login time).

**Acceptance Scenarios**:

1. **Given** admin is authenticated with ROLE_ADMIN, **When** admin views user list, **Then** system displays users with Auth0 integration showing email, name, enabled/blocked status, last login timestamp
2. **Given** user account is blocked in Auth0, **When** admin views user list, **Then** system displays user with blocked status indicator
3. **Given** user has never logged in, **When** admin views user list, **Then** system shows last login as null/never

---

### User Story 7 - Migration Script Transfers Existing Users from Keycloak to Auth0 (Priority: P2)

System administrators need to migrate existing users from Keycloak to Auth0 without losing user data or requiring password resets for all users.

**Why this priority**: Essential for production migration but can be executed as a one-time operation during deployment window.

**Independent Test**: Can be tested by exporting Keycloak users, running migration script, and verifying all users exist in Auth0 with correct roles, metadata, and can authenticate successfully.

**Acceptance Scenarios**:

1. **Given** Keycloak contains existing users, **When** migration script runs, **Then** script exports all users from Keycloak and creates them in Auth0 with matching email, roles, and metadata
2. **Given** user exists in both Keycloak and Auth0 after migration, **When** migration completes, **Then** system updates PostgreSQL account records with Auth0 user ID replacing Keycloak user ID
3. **Given** migration script encounters errors, **When** user creation fails in Auth0, **Then** script logs error details and continues with remaining users without stopping entire migration

---

### User Story 8 - Developer Runs Tests Against Auth0 Mock Service (Priority: P2)

Developers need to run integration and unit tests without dependency on live Auth0 service. The test suite uses mock Auth0 service or test configuration.

**Why this priority**: Important for development velocity and CI/CD pipeline, but does not block core functionality.

**Independent Test**: Can be tested by running full test suite (unit + integration tests) with Auth0 disabled or mocked and verifying all tests pass with expected mock behaviors.

**Acceptance Scenarios**:

1. **Given** developer runs tests locally, **When** auth0.enabled=false in test profile, **Then** system uses mock Auth0 client that simulates user creation, role assignment, and token validation
2. **Given** integration tests run in CI/CD, **When** tests execute, **Then** tests use Testcontainers or mock Auth0 API responses for user management operations
3. **Given** contract tests validate admin endpoints, **When** tests run, **Then** tests mock Auth0 Management API calls and verify correct request payloads

---

### Edge Cases

- **What happens when Auth0 service is unavailable during user creation?** System returns 503 Service Unavailable error, logs incident, and does not create partial account in PostgreSQL (no orphaned records)

- **How does system handle Auth0 user already exists (duplicate email)?** System catches Auth0 APIException with duplicate email error, returns 409 Conflict to admin with clear error message

- **What if Auth0 password reset link expires before user clicks it?** User receives error on Auth0 page indicating link expired. Admin must generate new reset link via admin panel.

- **How does system handle Auth0 rate limiting?** System catches 429 Too Many Requests error from Auth0 Management API, implements exponential backoff retry (3 attempts), and returns 503 if all retries fail

- **What happens to JWT tokens issued before migration?** Old Keycloak JWT tokens become invalid immediately after migration (different issuer). All users must re-authenticate with Auth0.

- **How does system handle custom JWT claims missing from Auth0 token?** If Auth0 Action fails to add roles claim, system denies access with 403 Forbidden and logs warning about missing custom claims

- **What if admin account exists in Auth0 but not in PostgreSQL (orphaned Auth0 user)?** Migration script and admin panel ignore orphaned Auth0 users. Admin must manually create PostgreSQL account record or delete Auth0 user.

- **How does system handle concurrent password resets?** Each password reset generates unique Auth0 ticket. Only the most recent ticket works; older tickets are automatically invalidated by Auth0.

## Requirements

### Functional Requirements

- **FR-001**: System MUST replace Keycloak Admin Client with Auth0 Management API client for all user management operations (create, update, delete, lock, unlock, reset password)

- **FR-002**: System MUST replace Keycloak OAuth2 Resource Server configuration with Auth0 OAuth2 configuration, validating JWT tokens issued by Auth0 tenant

- **FR-003**: System MUST extract user roles from Auth0 custom claim (e.g., "https://api.dataforge.com/roles") instead of Keycloak's "realm_access.roles" claim

- **FR-004**: System MUST generate Auth0 password reset ticket/link instead of temporary password when admin resets user password

- **FR-005**: System MUST store Auth0 user ID (format: "auth0|xxxxx") in PostgreSQL by renaming existing keycloak_user_id column to identity_provider_user_id and expanding to VARCHAR(64)

- **FR-006**: System MUST update Auth0 user metadata with accountId from PostgreSQL for bidirectional mapping (same pattern as Keycloak)

- **FR-007**: System MUST use Auth0 blocked field (true/false) for account locking instead of Keycloak enabled field (inverse logic)

- **FR-008**: System MUST retrieve last login timestamp from Auth0 user.last_login field instead of Keycloak getUserSessions() API

- **FR-009**: System MUST create Auth0 Action "Add Roles to Access Token" that injects roles into JWT custom claim during login flow

- **FR-010**: System MUST assign Auth0 roles (ROLE_USER, ROLE_ADMIN) to users via Auth0 Roles API instead of Keycloak realm roles

- **FR-011**: Frontend MUST redirect unauthenticated users to Auth0 Universal Login page instead of Keycloak login page

- **FR-012**: Frontend MUST remove all references to Keycloak login UI components and replace with Auth0 authentication flow

- **FR-013**: System MUST remove deprecated KeycloakSecurityConfig class and all related Keycloak-specific security configuration

- **FR-014**: System MUST update application.yml configuration replacing keycloak.* properties with auth0.* properties (domain, management client ID/secret, database connection)

- **FR-015**: System MUST replace org.keycloak:keycloak-admin-client dependency with com.auth0:auth0 and com.auth0:java-jwt dependencies

- **FR-016**: System MUST provide migration script to export users from Keycloak and import into Auth0 preserving roles, metadata, and account associations

- **FR-017**: System MUST update all integration tests replacing Keycloak test configuration with Auth0 mock configuration or test Auth0 tenant

- **FR-018**: System MUST remove TestKeycloakConfig and create TestAuth0Config for test environments

- **FR-019**: System MUST update Docker Compose configuration removing Keycloak service container

- **FR-020**: Admin API endpoints MUST return passwordResetLink (String URL) instead of temporaryPassword in CreateAccountResponse and ResetPasswordResponse DTOs

- **FR-021**: System MUST implement retry logic (3 attempts with exponential backoff) for Auth0 Management API calls to handle transient failures

- **FR-022**: System MUST validate Auth0 JWT audience claim matches expected API identifier (e.g., "https://api.dataforge.com")

- **FR-023**: System MUST create Auth0 Machine-to-Machine application with Management API permissions (read:users, create:users, update:users, delete:users, read:roles, create:user_tickets)

- **FR-024**: System MUST create Auth0 API application defining API identifier (audience) and enabling RS256 signing algorithm

- **FR-025**: System MUST create Auth0 roles (ROLE_USER, ROLE_ADMIN) via Auth0 Dashboard before migration

- **FR-026**: System MUST execute Flyway migration to rename keycloak_user_id column to identity_provider_user_id and expand from VARCHAR(36) to VARCHAR(64) in accounts table

### Non-Functional Requirements

- **NFR-001**: Auth0 Management API calls MUST complete within 5 seconds under normal conditions (excluding Auth0 service issues)

- **NFR-002**: System MUST log all Auth0 API errors with sufficient detail for troubleshooting (request ID, user ID, error code, error description)

- **NFR-003**: Migration script MUST handle at least 10,000 user accounts within 2 hours execution time

- **NFR-004**: System MUST maintain backward compatibility for existing PostgreSQL account data during migration (no data loss)

- **NFR-005**: Auth0 JWT token validation MUST NOT add more than 50ms latency to API requests (using cached JWKS keys)

### Key Entities

- **Auth0 User**: Represents user account in Auth0 tenant, contains email, username, enabled/blocked status, user_metadata (accountId), last_login timestamp, assigned roles

- **Auth0 Role**: Represents authorization role in Auth0 (ROLE_USER or ROLE_ADMIN), assigned to users and included in JWT token custom claims

- **Auth0 Management API Token**: Short-lived JWT token obtained via Machine-to-Machine client credentials, used to authenticate backend requests to Auth0 Management API

- **Auth0 Password Reset Ticket**: Time-limited URL token generated by Auth0 allowing user to reset password, replaces Keycloak temporary password pattern

- **Account (PostgreSQL)**: Existing entity with identity_provider_user_id column (renamed from keycloak_user_id) storing Auth0 user ID in format "auth0|xxxxx"

## Success Criteria

### Measurable Outcomes

- **SC-001**: Admin can create user accounts through admin panel with password reset link delivered within 3 seconds (95th percentile)

- **SC-002**: Users authenticate via Auth0 Universal Login with authentication flow completing in under 5 seconds from redirect to token receipt

- **SC-003**: System validates Auth0 JWT tokens for API requests with less than 50ms added latency compared to current Keycloak validation

- **SC-004**: Migration script successfully transfers 100% of existing users from Keycloak to Auth0 without data loss or requiring manual intervention

- **SC-005**: Zero Keycloak dependencies remain in production deployment (confirmed by dependency analysis and Docker Compose configuration)

- **SC-006**: All integration tests pass using Auth0 mock configuration or test tenant with zero Keycloak test dependencies

- **SC-007**: Admin panel displays accurate Auth0 user status (blocked/enabled) and last login timestamp synchronized from Auth0 API

- **SC-008**: Password reset workflow completes successfully for 95% of users on first attempt (measured by successful password changes via reset link)

- **SC-009**: System handles Auth0 service unavailability gracefully returning 503 error and detailed logs without crashing or creating partial data

- **SC-010**: Frontend eliminates all Keycloak UI components with zero references to Keycloak login pages in production build

## Assumptions

- Auth0 tenant (dev, staging, production) will be provisioned and configured before migration begins
- Auth0 free tier (7,000 active users/month) is sufficient, or paid plan budget is approved
- Machine-to-Machine application credentials (client ID/secret) will be securely stored in environment variables
- Auth0 Action "Add Roles to Access Token" will be deployed and enabled in Login flow before production migration
- Auth0 Database Connection "Username-Password-Authentication" (or equivalent) will be configured for user storage
- Migration will be executed during planned maintenance window allowing for user re-authentication
- Existing users will accept requirement to re-authenticate after migration (all Keycloak tokens invalidated)
- Password reset links (instead of temporary passwords) are acceptable UX change for admin workflows
- Auth0 API rate limits (standard tier) are sufficient for expected user management operations volume
- Development team has access to Auth0 Dashboard for creating roles, actions, and applications
- PostgreSQL schema changes (if adding auth0_user_id column) can be deployed via Flyway migration
- Frontend framework supports Auth0 SDK integration (React with auth0-react library assumed based on existing stack)
- Auth0 email delivery service is configured for password reset emails (using Auth0 email provider or SMTP)
- Keycloak export functionality is available and functional for user data extraction during migration

## Out of Scope

- Custom Auth0 branding/UI customization beyond default Universal Login (can be added post-migration)
- Multi-factor authentication (MFA) configuration for Auth0 users (separate feature)
- Social login providers (Google, GitHub, etc.) integration with Auth0 (separate feature)
- Migration of Keycloak clients/applications beyond Data Forge backend and admin panel
- Auth0 Organizations or B2B multi-tenancy features (current system uses single tenant)
- Auth0 Log Streams integration with monitoring systems (can be added post-migration)
- Custom Auth0 Rules or Hooks beyond the required "Add Roles to Access Token" Action
- Passwordless authentication flows (email magic links, SMS OTP)
- Auth0 custom domain configuration (using default *.auth0.com domain initially)
- Detailed Auth0 analytics dashboard integration (using Auth0 Dashboard built-in analytics initially)

## Dependencies

- Auth0 tenant must be created and accessible before development starts
- Auth0 Machine-to-Machine application must be authorized with Management API permissions
- Auth0 roles (ROLE_USER, ROLE_ADMIN) must be created in Auth0 Dashboard
- Auth0 Action "Add Roles to Access Token" must be deployed in Login flow
- Auth0 Database Connection must be configured and enabled
- Migration script requires read access to Keycloak Admin API for user export
- Frontend deployment requires updated environment variables (AUTH0_DOMAIN, AUTH0_CLIENT_ID, AUTH0_AUDIENCE)
- Backend deployment requires Auth0 credentials in environment (AUTH0_DOMAIN, AUTH0_MGMT_CLIENT_ID, AUTH0_MGMT_CLIENT_SECRET)

## Security Considerations

- Auth0 Machine-to-Machine credentials (client_secret) must be stored securely in environment variables, never committed to Git
- Auth0 Management API token has extensive permissions (user management) and must be kept server-side only
- Password reset links contain sensitive Auth0 ticket tokens and should be transmitted only via secure channels (HTTPS, email)
- Auth0 JWT signature validation must use cached JWKS keys with appropriate refresh intervals (prevent DDoS via key fetching)
- Custom JWT claims namespace must use HTTPS URL (e.g., "https://api.dataforge.com/roles") to avoid collisions
- Admin self-lock prevention must remain functional during migration (cannot lock own account)
- Audit logs (admin_action_logs table) must continue recording all user management operations with Auth0 user IDs
- Auth0 brute force protection settings should match or exceed current Keycloak protection levels
- Migration script must handle PII (personal identifiable information) securely during Keycloak export and Auth0 import

## Database Schema Decision

**Decision**: Repurpose existing `keycloak_user_id` column to store Auth0 user ID.

**Rationale**:
- Simpler schema with single identity provider ID column
- Keycloak historical reference not needed post-migration (clean cutover)
- Reduces schema complexity and query overhead
- Follows principle of minimal schema changes

**Implementation**:
- **Migration**: Flyway migration to rename `keycloak_user_id` → `identity_provider_user_id` (or `auth_provider_user_id`)
- **Column definition**: Change from `VARCHAR(36)` to `VARCHAR(64)` to accommodate Auth0 ID format ("auth0|xxxxx")
- **Java entity**: Update `Account.java` field name and annotations
- **Backwards compatibility**: No backward compatibility needed (one-time migration with maintenance window)
- **Data preservation**: Not required - Keycloak user IDs have no value after full migration to Auth0
