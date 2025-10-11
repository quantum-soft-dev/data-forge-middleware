# Feature Specification: Security System Separation

**Feature Branch**: `003-separation-of-security`
**Created**: 2025-10-10
**Status**: Draft
**Input**: User description: "Separation of security systems - JWT for /api/dfc/** (file uploader) and Keycloak for /api/** (admin UI)"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - File Uploader Client Authentication (Priority: P1)

As a file uploader client application, I need to authenticate using JWT tokens to access data forge API endpoints so that I can upload files, manage batches, and log errors without mixing authentication mechanisms with the admin UI.

**Why this priority**: This is the core functionality that enables the primary use case of the system - file uploads from client applications. Without this working, the entire file upload pipeline is blocked.

**Independent Test**: Can be fully tested by making API requests to `/api/dfc/batch/**`, `/api/dfc/error/**`, and `/api/dfc/upload/**` with valid JWT tokens and verifying successful authentication and authorization without any Keycloak dependencies.

**Acceptance Scenarios**:

1. **Given** a file uploader client with a valid JWT token, **When** it makes a POST request to `/api/dfc/batch/start`, **Then** the request is authenticated successfully and a new batch is created
2. **Given** a file uploader client with a valid JWT token, **When** it uploads a file to `/api/dfc/upload/**`, **Then** the file is uploaded without requiring Keycloak authentication
3. **Given** a file uploader client with a valid JWT token, **When** it logs an error to `/api/dfc/error/**`, **Then** the error is logged successfully
4. **Given** a file uploader client with an invalid JWT token, **When** it attempts to access any `/api/dfc/**` endpoint, **Then** it receives a 401 Unauthorized response
5. **Given** a file uploader client with a Keycloak token, **When** it attempts to access `/api/dfc/**` endpoints, **Then** it receives a 403 Forbidden response (wrong token type)

---

### User Story 2 - Admin UI Authentication (Priority: P1)

As an admin UI user, I need to authenticate using Keycloak SSO to access administrative endpoints so that I can manage accounts, sites, and monitor system operations through a unified identity management system.

**Why this priority**: Admin operations are critical for system management and user support. This must work independently to ensure administrators can perform their duties without being blocked by client authentication issues.

**Independent Test**: Can be fully tested by making API requests to `/api/admin/**` endpoints with valid Keycloak OAuth2 tokens and verifying successful authentication and ROLE_ADMIN authorization without any JWT dependencies.

**Acceptance Scenarios**:

1. **Given** an admin user with a valid Keycloak token (ROLE_ADMIN), **When** they make a GET request to `/api/admin/accounts`, **Then** they receive a paginated list of accounts
2. **Given** an admin user with a valid Keycloak token (ROLE_ADMIN), **When** they make a POST request to `/api/admin/accounts`, **Then** a new account is created successfully
3. **Given** an admin user with a valid Keycloak token (ROLE_ADMIN), **When** they access any `/api/admin/**` endpoint, **Then** they are authenticated and authorized based on their Keycloak roles
4. **Given** an admin user with an invalid Keycloak token, **When** they attempt to access `/api/admin/**` endpoints, **Then** they receive a 401 Unauthorized response
5. **Given** an admin user with a JWT token, **When** they attempt to access `/api/admin/**` endpoints, **Then** they receive a 403 Forbidden response (wrong token type)

---

### User Story 3 - Test Suite Verification (Priority: P1)

As a QA engineer, I need all existing tests to pass after the security refactoring so that I can verify the changes haven't broken any existing functionality or introduced regressions.

**Why this priority**: This is a refactoring task, not a new feature. The primary success criterion is that all existing functionality continues to work exactly as before, which is validated by the test suite. 100% test pass rate is mandatory.

**Independent Test**: Can be fully tested by running the full test suite (`./gradlew test`) and verifying that all tests pass, including unit tests, integration tests, and contract tests for both authentication mechanisms.

**Acceptance Scenarios**:

1. **Given** the security refactoring is complete, **When** the full test suite is executed, **Then** 100% of tests pass (0 failures, 0 errors)
2. **Given** the test environment configuration, **When** tests are run for JWT endpoints, **Then** all JWT authentication tests pass
3. **Given** the test environment configuration, **When** tests are run for Keycloak endpoints, **Then** all Keycloak authentication tests pass
4. **Given** the refactored security configuration, **When** integration tests for both client and admin flows are executed, **Then** no authentication/authorization regressions are detected

---

### Edge Cases

- What happens when a request is made to `/api/dfc/**` with both JWT and Keycloak tokens (dual token detection)?
- How does the system handle requests to `/api/dfc/**` with no authentication token?
- What happens when a JWT token is used on a `/api/admin/**` endpoint?
- What happens when a Keycloak token is used on a `/api/dfc/**` endpoint?
- How does the system differentiate between authentication failure (401) and authorization failure (403)?
- What happens when the test security configuration (TestSecurityConfig) needs to mirror the production security setup?
- How does the system handle legacy endpoints that don't fit either `/api/dfc/**` or `/api/admin/**` patterns?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST route all requests to `/api/dfc/batch/**`, `/api/dfc/error/**`, and `/api/dfc/upload/**` through JWT-only authentication filter
- **FR-002**: System MUST route all requests to `/api/admin/**` through Keycloak-only authentication filter
- **FR-003**: System MUST reject JWT tokens on `/api/admin/**` endpoints with 403 Forbidden status
- **FR-004**: System MUST reject Keycloak tokens on `/api/dfc/**` endpoints with 403 Forbidden status
- **FR-005**: System MUST maintain separate security filter chains for JWT and Keycloak authentication
- **FR-006**: System MUST preserve all existing authentication behavior for endpoints within each security domain
- **FR-007**: System MUST migrate all existing dual authentication GET endpoints from `/api/v1/batch/**`, `/api/v1/error/**`, `/api/v1/upload/**` to JWT-only authentication under `/api/dfc/batch/**`, `/api/dfc/error/**`, `/api/dfc/upload/**` and deprecate the `/api/v1/**` endpoints
- **FR-008**: Test environment security configuration MUST mirror production security configuration to ensure test reliability
- **FR-009**: System MUST ensure all existing tests pass after refactoring (100% pass rate required)
- **FR-010**: System MUST implement this as a breaking change requiring all clients to update to `/api/dfc/**` immediately (no migration period or backward compatibility for `/api/v1/**` endpoints)
- **FR-011**: System MUST log authentication failures with token type information for troubleshooting

### Key Entities

- **Security Filter Chain**: Defines the authentication mechanism (JWT or Keycloak) and the URL patterns it applies to
- **Authentication Context**: Contains information about the authenticated user/client, including authentication method and granted authorities
- **API Endpoint**: A REST resource with a specific URL pattern that requires authentication

## Clarifications

### Session 2025-10-10

- Q: Should the existing dual-auth GET endpoints be migrated to JWT-only under /api/dfc/**, or should they remain dual-auth under /api/v1/**? → A: Migrate to JWT-only under `/api/dfc/**` and deprecate `/api/v1/**` GET endpoints (Option A)
- Q: Is there a migration period where both /api/v1/** and /api/dfc/** need to work, or is this a breaking change requiring all clients to update immediately? → A: Breaking change - all clients must update to `/api/dfc/**` immediately (Option A)

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of existing tests pass after refactoring with 0 failures and 0 errors
- **SC-002**: All requests to `/api/dfc/**` endpoints are authenticated using JWT tokens only
- **SC-003**: All requests to `/api/admin/**` endpoints are authenticated using Keycloak tokens only
- **SC-004**: Wrong token type on an endpoint results in a 403 Forbidden response in 100% of cases
- **SC-005**: No existing client functionality is broken (verified through contract tests and integration tests)
- **SC-006**: Authentication processing time remains under 100ms for both JWT and Keycloak validations
- **SC-007**: Test environment security configuration produces identical behavior to production for both authentication methods
