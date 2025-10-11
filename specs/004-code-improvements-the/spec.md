# Feature Specification: Admin Controllers DTO Refactoring and Error Handling Standardization

**Feature Branch**: `004-code-improvements-the`
**Created**: 2025-10-11
**Status**: Draft
**Input**: User description: "# Code improvements:
- The AccountAdminController, SiteAdminController and ErrorLogController should not accept Map<String, Object> structures as input. Instead, they should operate on special DTO objects.
All web controllers should return special DTO objects, not Map<String, Object>.
- Controllers should no longer handle errors in a 'try-catch' block, but should instead throw them to the global error handler.
The error handler should also return an Error DTO object.
- Update the Swagger API automatic generation.
- Fix the tests to ensure 100% validity."

## User Scenarios & Testing

### User Story 1 - Replace Input Maps with Request DTOs (Priority: P1)

Admin users interact with admin API endpoints to create and update accounts, sites, and log errors. Currently, controllers accept unstructured `Map<String, Object>` inputs, which lack type safety and clear API contracts. Admin users need strongly-typed request structures that provide clear validation and documentation.

**Why this priority**: This is the foundation for all other improvements. Type-safe request DTOs enable proper validation, clear API contracts, and better IDE support for API consumers. Without this, the API remains fragile and hard to document.

**Independent Test**: Can be fully tested by sending POST/PUT requests to admin endpoints with request DTOs and verifying that controllers properly deserialize, validate, and process the data. Delivers immediate value through type safety and validation.

**Acceptance Scenarios**:

1. **Given** an admin wants to create an account, **When** they POST to `/api/admin/accounts` with `CreateAccountRequestDto` (email, name), **Then** the system validates the DTO and creates the account
2. **Given** an admin wants to update an account, **When** they PUT to `/api/admin/accounts/{id}` with `UpdateAccountRequestDto` (name), **Then** the system validates the DTO and updates the account
3. **Given** an admin wants to create a site, **When** they POST to `/api/admin/accounts/{accountId}/sites` with `CreateSiteRequestDto` (domain, displayName), **Then** the system validates the DTO and creates the site
4. **Given** an admin wants to update a site, **When** they PUT to `/api/admin/sites/{id}` with `UpdateSiteRequestDto` (displayName), **Then** the system validates the DTO and updates the site
5. **Given** a client wants to log an error, **When** they POST to `/api/dfc/error` or `/api/dfc/error/{batchId}` with `LogErrorRequestDto` (type, message, metadata), **Then** the system validates the DTO and logs the error

---

### User Story 2 - Replace Output Maps with Response DTOs (Priority: P2)

Admin users and API consumers receive responses from admin endpoints. Currently, many endpoints return unstructured `Map<String, Object>` responses, which make it difficult to understand the API contract and lead to runtime errors when expected fields are missing.

**Why this priority**: This builds on P1 and completes the DTO refactoring. While less critical than input validation, consistent response structures improve API reliability and documentation quality. Must be done after P1 to maintain consistency.

**Independent Test**: Can be fully tested by calling GET endpoints and verifying that responses match documented DTO structures. Delivers value through predictable API contracts and improved OpenAPI documentation.

**Acceptance Scenarios**:

1. **Given** an admin requests account details with statistics, **When** they GET `/api/admin/accounts/{id}`, **Then** the system returns `AccountWithStatsResponseDto` with all statistics fields
2. **Given** an admin lists accounts, **When** they GET `/api/admin/accounts`, **Then** the system returns `PageResponseDto<AccountResponseDto>` with paginated results
3. **Given** an admin requests site creation, **When** they POST to `/api/admin/accounts/{accountId}/sites`, **Then** the system returns `SiteCreationResponseDto` including the client secret
4. **Given** an admin lists batches, **When** they GET `/api/admin/batches`, **Then** the system returns `PageResponseDto<BatchSummaryDto>` with paginated results
5. **Given** an admin requests batch details, **When** they GET `/api/admin/batches/{id}`, **Then** the system returns `BatchDetailResponseDto` with all files included
6. **Given** an admin lists error logs, **When** they GET `/api/admin/errors`, **Then** the system returns `PageResponseDto<ErrorLogSummaryDto>` with paginated results
7. **Given** an admin requests account statistics, **When** they GET `/api/admin/accounts/{id}/stats`, **Then** the system returns `AccountStatisticsDto` with all metrics
8. **Given** an admin requests site statistics, **When** they GET `/api/admin/sites/{id}/statistics`, **Then** the system returns `SiteStatisticsDto` with all metrics

---

### User Story 3 - Remove Try-Catch Blocks and Standardize Error Handling (Priority: P3)

API consumers (admin users and client applications) receive error responses when operations fail. Currently, controllers handle exceptions locally with try-catch blocks, leading to inconsistent error responses and duplicated error handling logic.

**Why this priority**: This is a refactoring that improves code quality without changing external behavior. Must be done after P1 and P2 to ensure all custom exceptions are properly defined for the global handler to catch.

**Independent Test**: Can be fully tested by triggering error conditions (invalid input, missing resources, authorization failures) and verifying that `GlobalExceptionHandler` returns consistent `ErrorResponseDto` responses. Delivers value through consistent error contracts.

**Acceptance Scenarios**:

1. **Given** an admin provides invalid input, **When** the controller throws `IllegalArgumentException`, **Then** `GlobalExceptionHandler` returns 400 with `ErrorResponseDto`
2. **Given** an admin requests a non-existent account, **When** the service throws `AccountNotFoundException`, **Then** `GlobalExceptionHandler` returns 404 with `ErrorResponseDto`
3. **Given** an admin tries to create a duplicate account, **When** the service throws `AccountAlreadyExistsException`, **Then** `GlobalExceptionHandler` returns 409 with `ErrorResponseDto`
4. **Given** an admin requests a non-existent site, **When** the service throws `SiteNotFoundException`, **Then** `GlobalExceptionHandler` returns 404 with `ErrorResponseDto`
5. **Given** an admin tries to create a duplicate site, **When** the service throws `SiteAlreadyExistsException`, **Then** `GlobalExceptionHandler` returns 409 with `ErrorResponseDto`
6. **Given** a client requests a non-existent error log, **When** the service throws `ErrorLogNotFoundException`, **Then** `GlobalExceptionHandler` returns 404 with `ErrorResponseDto`
7. **Given** any unexpected error occurs, **When** an `Exception` is thrown, **Then** `GlobalExceptionHandler` returns 500 with `ErrorResponseDto`

---

### User Story 4 - Update OpenAPI Documentation (Priority: P4)

API consumers (developers integrating with the API) need accurate, up-to-date API documentation that reflects the new DTO-based contracts. Currently, OpenAPI documentation may show generic Object types instead of structured DTO schemas.

**Why this priority**: This is the final polish that makes all previous work discoverable. Must be done last after all DTOs are in place, so the documentation accurately reflects the implementation.

**Independent Test**: Can be fully tested by accessing `/swagger-ui.html` and verifying that all endpoints show proper request/response schemas with DTO structures. Delivers value through improved developer experience.

**Acceptance Scenarios**:

1. **Given** a developer opens Swagger UI, **When** they view `/api/admin/accounts` POST endpoint, **Then** they see `CreateAccountRequestDto` schema with email and name fields
2. **Given** a developer opens Swagger UI, **When** they view `/api/admin/accounts` GET endpoint, **Then** they see `PageResponseDto<AccountResponseDto>` response schema
3. **Given** a developer opens Swagger UI, **When** they view `/api/admin/accounts/{id}` GET endpoint, **Then** they see `AccountWithStatsResponseDto` response schema
4. **Given** a developer opens Swagger UI, **When** they view any error response, **Then** they see `ErrorResponseDto` schema with timestamp, status, error, message, and path fields
5. **Given** a developer opens Swagger UI, **When** they view `/api/dfc/error` POST endpoint, **Then** they see `LogErrorRequestDto` schema with type, message, and metadata fields

---

### Edge Cases

- What happens when a request DTO has missing required fields? (Validation should trigger 400 Bad Request)
- What happens when a request DTO has fields with wrong types? (Deserialization should trigger 400 Bad Request)
- What happens when a controller throws a custom exception not handled by GlobalExceptionHandler? (Should be caught by generic Exception handler and return 500)
- What happens when statistics services return Map<String, Object> that need to be converted to DTOs? (Controllers should map to proper DTO structures)
- What happens when paginated responses need to include DTOs? (Use `PageResponseDto<T>` generic wrapper)
- What happens when batch details include nested file lists? (Use nested DTO structures within `BatchDetailResponseDto`)

## Requirements

### Functional Requirements

- **FR-001**: System MUST define request DTO records for account creation (CreateAccountRequestDto: email, name)
- **FR-002**: System MUST define request DTO records for account updates (UpdateAccountRequestDto: name)
- **FR-003**: System MUST define request DTO records for site creation (CreateSiteRequestDto: domain, displayName)
- **FR-004**: System MUST define request DTO records for site updates (UpdateSiteRequestDto: displayName)
- **FR-005**: System MUST define request DTO records for error logging (LogErrorRequestDto: type, message, metadata)
- **FR-006**: System MUST define response DTO records for account details with statistics (AccountWithStatsResponseDto: all account fields + sitesCount, totalBatches, totalUploadedFiles)
- **FR-007**: System MUST define response DTO records for site creation including plaintext secret (SiteCreationResponseDto: all site fields + clientSecret)
- **FR-008**: System MUST define response DTO records for batch summaries (BatchSummaryDto: id, siteId, status, s3Path, uploadedFilesCount, totalSize, hasErrors, startedAt, completedAt, createdAt)
- **FR-009**: System MUST define response DTO records for batch details with files (BatchDetailResponseDto: all batch fields + siteDomain, files list)
- **FR-010**: System MUST define response DTO records for uploaded file information (UploadedFileDto: id, originalFileName, s3Key, fileSize, contentType, checksum, uploadedAt)
- **FR-011**: System MUST define response DTO records for error log summaries (ErrorLogSummaryDto: id, batchId, siteId, type, message, metadata, occurredAt)
- **FR-012**: System MUST define response DTO records for account statistics (AccountStatisticsDto: accountId, sitesCount, activeSites, totalBatches, completedBatches, failedBatches, totalFiles, totalStorageSize)
- **FR-013**: System MUST define response DTO records for site statistics (SiteStatisticsDto: siteId, totalBatches, completedBatches, failedBatches, totalFiles, totalStorageSize)
- **FR-014**: Controllers MUST accept request DTOs instead of Map<String, Object> for POST and PUT endpoints
- **FR-015**: Controllers MUST return response DTOs instead of Map<String, Object> for all endpoints
- **FR-016**: Controllers MUST NOT contain try-catch blocks for business logic exceptions
- **FR-017**: Controllers MUST throw exceptions directly to GlobalExceptionHandler
- **FR-018**: GlobalExceptionHandler MUST handle custom service exceptions (AccountNotFoundException, AccountAlreadyExistsException, SiteNotFoundException, SiteAlreadyExistsException, ErrorLogNotFoundException)
- **FR-019**: GlobalExceptionHandler MUST return ErrorResponseDto for all exception types
- **FR-020**: GlobalExceptionHandler MUST map AccountNotFoundException and SiteNotFoundException to 404 Not Found
- **FR-021**: GlobalExceptionHandler MUST map AccountAlreadyExistsException and SiteAlreadyExistsException to 409 Conflict
- **FR-022**: GlobalExceptionHandler MUST map ErrorLogNotFoundException to 404 Not Found
- **FR-023**: OpenAPI documentation MUST display proper schemas for all request DTOs
- **FR-024**: OpenAPI documentation MUST display proper schemas for all response DTOs
- **FR-025**: OpenAPI documentation MUST display ErrorResponseDto schema for error responses
- **FR-026**: All controller tests MUST pass with updated DTO structures
- **FR-027**: All integration tests MUST pass with updated DTO structures
- **FR-028**: System MUST preserve existing pagination structures using PageResponseDto<T>

### Key Entities

- **CreateAccountRequestDto**: Request structure for account creation (email: String, name: String)
- **UpdateAccountRequestDto**: Request structure for account updates (name: String)
- **CreateSiteRequestDto**: Request structure for site creation (domain: String, displayName: String)
- **UpdateSiteRequestDto**: Request structure for site updates (displayName: String)
- **LogErrorRequestDto**: Request structure for error logging (type: String, message: String, metadata: Map<String, Object>)
- **AccountWithStatsResponseDto**: Response structure for account details with statistics (extends AccountResponseDto with additional stats fields)
- **SiteCreationResponseDto**: Response structure for site creation (extends SiteResponseDto with clientSecret field)
- **BatchSummaryDto**: Response structure for batch list items (basic batch information without files)
- **BatchDetailResponseDto**: Response structure for batch details (includes siteDomain and files list)
- **UploadedFileDto**: Response structure for uploaded file information within batch details
- **ErrorLogSummaryDto**: Response structure for error log list items
- **AccountStatisticsDto**: Response structure for account statistics endpoint
- **SiteStatisticsDto**: Response structure for site statistics endpoint

## Success Criteria

### Measurable Outcomes

- **SC-001**: All admin controller POST/PUT endpoints accept only request DTOs (zero Map<String, Object> parameters remain)
- **SC-002**: All controller GET endpoints return only response DTOs (zero Map<String, Object> returns remain)
- **SC-003**: All controller methods have zero try-catch blocks for business logic (only allow try-catch if absolutely necessary for resource cleanup)
- **SC-004**: All custom service exceptions are handled by GlobalExceptionHandler (100% coverage)
- **SC-005**: OpenAPI documentation shows proper DTO schemas for all endpoints (zero "object" types for request/response bodies)
- **SC-006**: All tests pass with 100% success rate (zero test failures)
- **SC-007**: Code review confirms consistent error response format across all endpoints (all errors return ErrorResponseDto structure)
- **SC-008**: API consumers can deserialize all responses using documented DTO types (zero runtime type errors)

## Assumptions

- Existing `ErrorResponseDto` structure is sufficient for all error scenarios (timestamp, status, error, message, path)
- Existing `PageResponseDto<T>` can be reused for paginated responses
- Existing response DTOs (AccountResponseDto, SiteResponseDto, BatchResponseDto, ErrorLogResponseDto, FileUploadResponseDto) do not need changes
- Statistics services returning Map<String, Object> can be mapped to new statistics DTOs in controllers
- Request validation annotations (@NotNull, @NotBlank, @Email, etc.) from Jakarta Validation can be added to request DTOs
- Spring Boot's automatic DTO deserialization from JSON will work without custom deserializers
- OpenAPI annotations (@Schema, @Operation, @ApiResponse) are already configured in the project
- Existing test data and test fixtures can be updated to use DTOs with minimal effort
- CSV export functionality in ErrorAdminController can remain as-is (returns String, not DTO)
- The batch detail response needs to include site domain for admin UI display purposes
