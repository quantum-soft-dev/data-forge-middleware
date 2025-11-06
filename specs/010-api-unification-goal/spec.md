# Feature Specification: API Unification

**Feature Branch**: `010-api-unification-goal`
**Created**: 2025-11-05
**Status**: Draft
**Input**: User description: "Unify API endpoints into a uniform structure with Device API (/api/v1/device/*) for client devices using Custom JWT and UI/Admin API (/api/v1/*) for web interface using Keycloak OAuth2"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Device Client Can Access All Operations via Unified Device API (Priority: P1)

A client device (data collection device, mobile app, IoT device) needs to authenticate and perform batch operations (start batch, upload files, log errors, complete/fail/cancel batch) through a consistent, predictable API structure using Custom JWT authentication.

**Why this priority**: This is the core functionality - without device access to batch operations, the entire data collection system cannot function. Device clients are the primary data source and must be able to operate reliably.

**Independent Test**: Can be fully tested by authenticating a device client with Custom JWT, starting a batch, uploading files, and completing the batch via the new `/api/v1/device/*` endpoints. Success means all operations complete without errors using the new endpoint structure.

**Acceptance Scenarios**:

1. **Given** a device client with valid site credentials, **When** the client requests a token from `/api/v1/device/auth/token`, **Then** the system returns a valid JWT token for subsequent API calls
2. **Given** an authenticated device client, **When** the client starts a batch at `/api/v1/device/batches/start`, **Then** the system creates a new batch and returns batch details
3. **Given** an active batch, **When** the client uploads a file to `/api/v1/device/files/batches/{batchId}/upload`, **Then** the file is stored and metadata is recorded
4. **Given** an active batch with uploaded files, **When** the client completes the batch at `/api/v1/device/batches/{id}/complete`, **Then** the batch status changes to COMPLETED
5. **Given** an active batch, **When** the client logs an error at `/api/v1/device/errors/batches/{batchId}`, **Then** the error is recorded with batch association

---

### User Story 2 - Web UI Can Manage Resources via Unified Admin API (Priority: P1)

An administrator using the web interface needs to manage accounts, sites, batches, view upload history, analyze errors, and perform comparisons through a consistent `/api/v1/*` API structure authenticated via Keycloak OAuth2.

**Why this priority**: Web UI access is equally critical - administrators must be able to manage the system, troubleshoot issues, and view data. Without this, the system cannot be administered or monitored.

**Independent Test**: Can be fully tested by authenticating via Keycloak, then performing CRUD operations on accounts at `/api/v1/accounts`, sites at `/api/v1/sites`, viewing batch history at `/api/v1/history/batches`, and accessing error logs at `/api/v1/errors`. Success means all admin operations work with Keycloak tokens via the new endpoint structure.

**Acceptance Scenarios**:

1. **Given** an authenticated admin user with Keycloak token, **When** the user creates an account at `/api/v1/accounts`, **Then** the system creates the account and returns account details
2. **Given** an authenticated admin user, **When** the user lists sites at `/api/v1/sites`, **Then** the system returns all sites the admin can access
3. **Given** an authenticated user (non-admin), **When** the user views upload history at `/api/v1/history/batches`, **Then** the system returns only batches associated with the user's account
4. **Given** an authenticated admin user, **When** the user exports errors at `/api/v1/errors/export`, **Then** the system generates an error report
5. **Given** an authenticated user, **When** the user creates a comparison at `/api/v1/comparisons`, **Then** the system generates file diffs between specified batches

---

### User Story 3 - Existing Integrations Migrate to New Endpoints (Priority: P2)

Existing client devices and frontend applications need to transition from old endpoint paths to new unified paths with clear migration guidance and minimal disruption.

**Why this priority**: While new systems will use the unified API from the start, existing integrations must be migrated smoothly. This is P2 because it's about transition, not core functionality - the system works if we only focus on new endpoints.

**Independent Test**: Can be tested by maintaining a mapping document (old → new endpoints), verifying that each old endpoint has a direct equivalent in the new structure, and confirming that all functionality remains available after migration. Success means 100% feature parity between old and new endpoints.

**Acceptance Scenarios**:

1. **Given** a device client using `/api/dfc/batch/start`, **When** the client switches to `/api/v1/device/batches/start`, **Then** the batch creation behavior is identical
2. **Given** a web UI calling `/api/admin/accounts`, **When** the UI updates to use `/api/v1/accounts`, **Then** all account management operations work identically
3. **Given** a frontend calling `/api/user/batches`, **When** the frontend switches to `/api/v1/history/batches`, **Then** upload history displays with identical data
4. **Given** a migration guide document, **When** a developer follows the path mapping instructions, **Then** they can successfully update all API calls in their codebase

---

### User Story 4 - Security Filter Chains Properly Route Requests (Priority: P1)

The system needs to automatically route Device API requests (`/api/v1/device/**`) to Custom JWT authentication and UI/Admin API requests (`/api/v1/**`) to Keycloak OAuth2 authentication, rejecting mismatched token types.

**Why this priority**: Security is foundational - without proper authentication routing, the system is vulnerable. This is P1 because incorrect authentication can lead to security breaches or system downtime.

**Independent Test**: Can be tested by attempting to access Device API endpoints with Keycloak tokens (should fail with 403), attempting to access UI/Admin API endpoints with Custom JWT tokens (should fail with 403), and verifying that correct token types are accepted. Success means 100% rejection of mismatched tokens.

**Acceptance Scenarios**:

1. **Given** a request to `/api/v1/device/batches/start` with a Keycloak token, **When** the security filter processes the request, **Then** the request is rejected with 403 Forbidden
2. **Given** a request to `/api/v1/accounts` with a Custom JWT token, **When** the security filter processes the request, **Then** the request is rejected with 403 Forbidden
3. **Given** a request to `/api/v1/device/auth/token` with valid Basic Auth credentials, **When** the endpoint generates a token, **Then** the response contains a Custom JWT token (not Keycloak)
4. **Given** a request to `/api/v1/accounts` with a valid Keycloak token, **When** the security filter processes the request, **Then** the request is authorized and processed
5. **Given** a request to `/api/v1/device/batches/{id}` with a valid Custom JWT token, **When** the security filter processes the request, **Then** the request is authorized and processed

---

### Edge Cases

- **What happens when a client attempts to access a Device API endpoint with no authentication?** System should return 401 Unauthorized with appropriate error message
- **What happens when a Device API endpoint is called with an expired Custom JWT?** System should return 401 Unauthorized with token expiration message
- **What happens when a UI/Admin API endpoint is called with an expired Keycloak token?** System should return 401 Unauthorized, prompting user to re-authenticate
- **What happens when obsolete endpoint paths are called after migration?** System returns 410 Gone with a message indicating the endpoint has been removed and directing clients to the new endpoint structure. This ensures clean removal without maintaining duplicate controllers.
- **How does system handle requests to `/api/v1/comparisons` (already using correct path)?** No changes needed - endpoint already follows the unified structure
- **What happens when OpenAPI documentation is accessed?** Documentation should clearly separate Device API and UI/Admin API with appropriate security scheme indicators
- **What happens to existing tests when endpoints change?** All contract and integration tests must be updated to use new endpoint paths while maintaining identical test coverage

## Requirements *(mandatory)*

### Functional Requirements

#### Device API Requirements

- **FR-001**: System MUST expose all device authentication operations under `/api/v1/device/auth/*` path prefix
- **FR-002**: System MUST expose all batch management operations for devices under `/api/v1/device/batches/*` path prefix
- **FR-003**: System MUST expose all file upload operations under `/api/v1/device/files/batches/{batchId}/*` path prefix
- **FR-004**: System MUST expose all error logging operations for devices under `/api/v1/device/errors/*` path prefix
- **FR-005**: System MUST authenticate all Device API requests (`/api/v1/device/**`) using Custom JWT tokens only
- **FR-006**: System MUST reject Device API requests that present Keycloak OAuth2 tokens with 403 Forbidden
- **FR-007**: Device API token endpoint MUST accept Basic Authentication (site credentials) and return Custom JWT tokens
- **FR-008**: Device API MUST support batch lifecycle operations: start, complete, fail, cancel
- **FR-009**: Device API MUST support file upload with multipart/form-data
- **FR-010**: Device API MUST support error logging both standalone and batch-associated

#### UI/Admin API Requirements

- **FR-011**: System MUST expose all account management operations under `/api/v1/accounts/*` path prefix
- **FR-012**: System MUST expose all site management operations under `/api/v1/sites/*` path prefix
- **FR-013**: System MUST expose all admin batch operations under `/api/v1/batches/*` path prefix
- **FR-014**: System MUST expose all user upload history operations under `/api/v1/history/batches/*` path prefix (renamed from `/api/user/batches`)
- **FR-015**: System MUST expose all error management operations under `/api/v1/errors/*` path prefix
- **FR-016**: System MUST expose all comparison operations under `/api/v1/comparisons/*` path prefix (already compliant)
- **FR-017**: System MUST authenticate all UI/Admin API requests (`/api/v1/**` excluding `/api/v1/device/**`) using Keycloak OAuth2 tokens only
- **FR-018**: System MUST reject UI/Admin API requests that present Custom JWT tokens with 403 Forbidden
- **FR-019**: UI/Admin API MUST enforce role-based access control for admin operations (ROLE_ADMIN required)
- **FR-020**: UI/Admin API MUST support all existing CRUD operations for accounts, sites, batches, errors, and comparisons

#### Security & Routing Requirements

- **FR-021**: System MUST define two separate security filter chains with correct precedence: Device API first, then UI/Admin API
- **FR-022**: System MUST ensure Device API filter chain matches `/api/v1/device/**` pattern exclusively
- **FR-023**: System MUST ensure UI/Admin API filter chain matches `/api/v1/**` pattern (excluding device paths)
- **FR-024**: System MUST log authentication failures with token type information (Custom JWT vs Keycloak)
- **FR-025**: System MUST log all authentication attempts to device and admin endpoints for audit purposes

#### API Structure & Documentation Requirements

- **FR-026**: System MUST define a central constants file with all Device API and UI/Admin API path constants
- **FR-027**: System MUST update OpenAPI documentation to clearly separate Device API and UI/Admin API endpoint groups
- **FR-028**: System MUST document security schemes for both Custom JWT and Keycloak OAuth2 in OpenAPI spec
- **FR-029**: System MUST maintain backward compatibility with existing response DTO structures (no breaking changes to response formats)
- **FR-030**: System MUST maintain identical request/response validation rules for migrated endpoints

#### Migration & Deprecation Requirements

- **FR-031**: System MUST provide a complete endpoint mapping document (old path → new path) covering all affected endpoints
- **FR-032**: System MUST include migration examples for each endpoint type (authentication, batch operations, file uploads, admin operations)
- **FR-033**: System MUST update all contract tests to use new endpoint paths
- **FR-034**: System MUST update all integration tests to use new endpoint paths
- **FR-035**: System MUST remove obsolete controller files after successful migration (AuthController, BatchController, FileUploadController, ErrorLogController, SiteController)
- **FR-036**: System MUST return 410 Gone with descriptive error message for requests to obsolete endpoint paths after migration (old paths like `/api/dfc/*`, `/api/admin/*`, `/api/user/*`)
- **FR-037**: Error messages for obsolete endpoints MUST include the new endpoint path for client migration guidance

#### Data Integrity Requirements

- **FR-038**: System MUST ensure no data loss during endpoint migration (all operations must function identically)
- **FR-039**: System MUST maintain identical business logic for all migrated endpoints
- **FR-040**: System MUST preserve existing error handling behavior for all operations
- **FR-041**: System MUST maintain existing rate limiting and throttling behavior (if any)
- **FR-042**: System MUST preserve existing audit logging for all operations

### Key Entities

This feature is a refactoring effort and does not introduce new entities. It reorganizes API access to existing entities:

- **Device Client**: Represents client devices that authenticate via Custom JWT and use Device API endpoints
- **Admin User**: Represents web interface users that authenticate via Keycloak OAuth2 and use UI/Admin API endpoints
- **API Endpoint**: Represents the path structure and routing rules for Device API vs UI/Admin API
- **Security Filter Chain**: Represents the authentication routing logic that directs requests to appropriate authentication mechanisms
- **Endpoint Mapping**: Represents the relationship between old endpoint paths and new unified paths

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All device client operations (authentication, batch management, file uploads, error logging) successfully complete via `/api/v1/device/*` endpoints with Custom JWT authentication
- **SC-002**: All web UI operations (account management, site management, batch admin, upload history, error management, comparisons) successfully complete via `/api/v1/*` endpoints with Keycloak OAuth2 authentication
- **SC-003**: 100% of Device API requests with Keycloak tokens are rejected with 403 Forbidden within 50ms
- **SC-004**: 100% of UI/Admin API requests with Custom JWT tokens are rejected with 403 Forbidden within 50ms
- **SC-005**: All existing test suites (contract tests, integration tests, unit tests) pass with new endpoint paths without any functionality regression
- **SC-006**: OpenAPI documentation clearly separates Device API and UI/Admin API with correct security scheme indicators for each endpoint group
- **SC-007**: Migration guide provides 100% coverage of old → new endpoint mappings with code examples for each API category
- **SC-008**: All obsolete controller files are removed from codebase after migration verification (5 files: AuthController, BatchController, FileUploadController, ErrorLogController, SiteController)
- **SC-009**: API response times remain within ±5% of pre-migration performance for all endpoint categories
- **SC-010**: Zero security vulnerabilities introduced by the new routing structure (verified via security filter chain testing)

## Assumptions

1. **Coordinated Migration Deployment**: All client devices and frontend applications will be updated to use new endpoints in a coordinated deployment - old endpoints will be removed immediately after migration (410 Gone)
2. **Same Business Logic**: All business logic, validation rules, and error handling remain identical - only the endpoint paths and routing change
3. **Existing Authentication Mechanisms Unchanged**: Custom JWT and Keycloak OAuth2 authentication implementations remain as-is - only the routing to them changes
4. **Test Environment Available**: A full test environment exists where the migration can be validated before production deployment
5. **No Data Migration Needed**: This is purely an API structure refactoring - no database schema changes or data migration required
6. **OpenAPI Tooling Supports Grouping**: SpringDoc OpenAPI 3 supports grouping endpoints by tags and security schemes for documentation clarity
7. **Security Filter Chain Order**: Spring Security filter chain ordering can be explicitly controlled to ensure correct precedence (Device API filter before UI/Admin API filter)
8. **No Rate Limiting Changes**: Existing rate limiting (if any) applies equally to new endpoint structure
9. **Backward Compatible DTOs**: All existing request and response DTOs remain unchanged - no breaking changes to payload structures

## Out of Scope

1. **New Functionality**: This feature only restructures existing endpoints - no new features or capabilities are added
2. **Authentication Mechanism Changes**: No changes to how Custom JWT or Keycloak OAuth2 authentication works internally
3. **Database Schema Changes**: No changes to database tables, columns, or relationships
4. **Performance Optimization**: While performance must not degrade, this feature does not aim to improve performance
5. **Frontend UI Changes**: Frontend components may need endpoint path updates but no visual or UX changes are in scope
6. **API Versioning Strategy**: This feature implements `/api/v1/*` but does not define a long-term versioning strategy for future v2, v3, etc.
7. **Rate Limiting Implementation**: If rate limiting doesn't currently exist, adding it is out of scope
8. **API Gateway Integration**: This refactoring assumes direct backend API calls - integration with API gateways is out of scope
9. **Multi-Region Deployment**: Endpoint structure changes do not address multi-region deployment concerns
10. **Batch Processing Changes**: No changes to how batches are processed, stored, or managed - only how they're accessed via API

## Dependencies

1. **Spring Security 6**: Required for security filter chain configuration with correct precedence
2. **SpringDoc OpenAPI 3**: Required for API documentation generation with endpoint grouping
3. **Existing Custom JWT Implementation**: Device API relies on existing Custom JWT authentication logic
4. **Existing Keycloak Integration**: UI/Admin API relies on existing Keycloak OAuth2 resource server configuration
5. **Existing Controller Services**: All business logic services must remain available for new controllers to delegate to
6. **Test Infrastructure**: Testcontainers, MockMvc, and other testing tools must support new endpoint structures
7. **Build System**: Gradle build configuration must compile new controller structure without errors
8. **Logging Framework**: Logback/SLF4J must support MDC context for audit logging of authentication attempts
9. **Frontend Build System**: Frontend applications must be able to update API client configurations
10. **CI/CD Pipeline**: Deployment pipeline must successfully build and deploy refactored codebase

## Risks & Mitigations

### Risk 1: Breaking Existing Client Integrations
**Impact**: High - Device clients or frontend apps may fail if endpoints change unexpectedly
**Likelihood**: Medium
**Mitigation**:
- Provide comprehensive migration guide with before/after examples and 410 Gone error handling
- Coordinate synchronized deployment across all client devices, frontend applications, and backend
- Implement extensive contract testing to verify functional parity before deployment
- Establish rollback plan in case of deployment issues
- Deploy to staging environment first for validation with all integrated systems

### Risk 2: Security Filter Chain Misconfiguration
**Impact**: Critical - Wrong authentication mechanism could lead to security breaches
**Likelihood**: Low
**Mitigation**:
- Implement comprehensive security filter tests verifying token rejection behavior
- Use explicit `@Order` annotations to control filter precedence
- Test both positive cases (correct tokens accepted) and negative cases (wrong tokens rejected)
- Security review of filter chain configuration before deployment

### Risk 3: Test Suite Maintenance Overhead
**Impact**: Medium - Large number of tests need endpoint path updates
**Likelihood**: High
**Mitigation**:
- Use constants (ApiRoutes.java) throughout tests to centralize path management
- Implement a systematic test update process (contract tests first, then integration tests, then unit tests)
- Verify test coverage remains at existing levels after migration
- Run full test suite in CI/CD pipeline before deployment

### Risk 4: OpenAPI Documentation Confusion
**Impact**: Medium - Developers may struggle to understand which endpoints use which authentication
**Likelihood**: Medium
**Mitigation**:
- Clearly separate Device API and UI/Admin API in documentation with distinct tags
- Document security schemes prominently at the top of API docs
- Include authentication examples for each API type in OpenAPI description
- Generate clear, visually separated sections in Swagger UI

### Risk 5: Performance Regression from Routing Changes
**Impact**: Medium - Additional security filter logic could slow request processing
**Likelihood**: Low
**Mitigation**:
- Benchmark response times before and after migration for key endpoints
- Monitor performance metrics in staging environment before production
- Keep filter chain logic minimal and efficient
- Load test with realistic traffic patterns after migration

## Notes

- The `/api/v1/comparisons` endpoints already follow the target structure and require no changes
- Migration requires coordinated deployment: all client devices, frontend applications, and backend must be updated simultaneously
- Old endpoints will return 410 Gone immediately after migration - no deprecation period or dual endpoint support
- All 5 obsolete controller files should be deleted as part of the migration deployment
- The endpoint mapping table provided in the user input is comprehensive and should be included in the migration guide
- Security filter chain order is critical: Device API filter must be evaluated before UI/Admin API filter to prevent incorrect routing
- Consider adding API version headers (e.g., `X-API-Version: v1`) for future extensibility, though implementation is out of scope
- Rollback plan is essential: if migration fails, all systems must be able to revert to old endpoints quickly
