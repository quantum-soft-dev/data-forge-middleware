# Research: DTO Refactoring and Error Handling Standardization

**Feature**: Admin Controllers DTO Refactoring
**Date**: 2025-10-11
**Status**: Complete

This document consolidates research findings for implementing type-safe DTOs, centralized exception handling, and OpenAPI documentation for admin controllers.

## 1. Jakarta Validation Annotations Best Practices

### Decision

Use **Jakarta Bean Validation 3.0** (provided by `spring-boot-starter-validation`) with standard annotations for request DTO validation.

### Rationale

- Jakarta Validation is the Java standard for declarative validation
- Spring Boot automatically enables validation when `@Valid` annotation is present
- Validation failures automatically trigger `MethodArgumentNotValidException`, which can be handled globally
- Annotations provide self-documenting code and OpenAPI schema generation
- Zero additional dependencies required (already in build.gradle.kts line 37)

### Validation Strategy

**Request DTOs** (user input):
```java
public record CreateAccountRequestDto(
    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid format")
    String email,

    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100, message = "Name must be 2-100 characters")
    String name
) {}
```

**Controller Method**:
```java
@PostMapping
public ResponseEntity<AccountResponseDto> createAccount(@Valid @RequestBody CreateAccountRequestDto request) {
    // Spring automatically validates request before method executes
    Account account = accountService.createAccount(request.email(), request.name());
    return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponseDto.fromEntity(account));
}
```

**Validation Annotations to Use**:

| Annotation | Use Case | Example |
|------------|----------|---------|
| `@NotNull` | Required field (primitives, objects) | `@NotNull UUID accountId` |
| `@NotBlank` | Required string (not null, not empty, not whitespace) | `@NotBlank String email` |
| `@Email` | Email format validation | `@Email String email` |
| `@Size` | String/collection length constraints | `@Size(min=2, max=100) String name` |
| `@Valid` | Cascade validation to nested objects | `@Valid AddressDto address` |

**Response DTOs** (system output):
- No validation annotations needed (system-generated data is trusted)
- Use `@Schema` annotations for OpenAPI documentation only

### Alternatives Considered

**Alternative 1: Custom validators**
- **Rejected**: Unnecessary complexity for simple field validation
- **When to use**: Only for complex cross-field validation (e.g., "start date must be before end date")

**Alternative 2: Manual validation in controllers**
- **Rejected**: Violates DRY principle, bypasses global error handling
- **Issue**: Would require manual try-catch blocks (contradicts refactoring goal)

### Implementation Notes

1. Add `@Valid` to all controller method parameters accepting request DTOs
2. Add validation annotations to request DTO record components
3. GlobalExceptionHandler will automatically catch `MethodArgumentNotValidException`
4. Return 400 Bad Request with field-level error details via ErrorResponseDto

---

## 2. OpenAPI Schema Generation for Java Records

### Decision

Use **SpringDoc OpenAPI 3** (springdoc-openapi-starter-webmvc-ui:2.8.3) with `@Schema` annotations on Java record components.

### Rationale

- SpringDoc natively supports Java records (since v1.6.0)
- Records automatically map to OpenAPI schemas without configuration
- `@Schema` annotations enrich documentation with descriptions, examples, constraints
- Validation annotations (@NotBlank, @Email) automatically populate schema constraints
- Zero configuration required beyond annotations

### OpenAPI Annotation Patterns

**Request DTO Example**:
```java
public record CreateAccountRequestDto(
    @Schema(description = "Account email address", example = "admin@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid format")
    String email,

    @Schema(description = "Account display name", example = "John Doe", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100)
    String name
) {}
```

**Response DTO Example**:
```java
public record AccountWithStatsResponseDto(
    @Schema(description = "Account unique identifier", example = "123e4567-e89b-12d3-a456-426614174000")
    UUID id,

    @Schema(description = "Account email address", example = "admin@example.com")
    String email,

    @Schema(description = "Account display name", example = "John Doe")
    String name,

    @Schema(description = "Whether account is active", example = "true")
    Boolean isActive,

    @Schema(description = "Account creation timestamp", example = "2025-01-15T10:30:00Z")
    Instant createdAt,

    @Schema(description = "Total number of sites", example = "5")
    Integer sitesCount,

    @Schema(description = "Total number of batches", example = "120")
    Integer totalBatches,

    @Schema(description = "Total number of uploaded files", example = "1500")
    Integer totalUploadedFiles
) {}
```

**Controller Annotation Example**:
```java
@Operation(
    summary = "Create new account",
    description = "Creates a new account with email and name. Returns account details.",
    tags = {"Admin - Accounts"}
)
@ApiResponses(value = {
    @ApiResponse(
        responseCode = "201",
        description = "Account created successfully",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = AccountResponseDto.class))
    ),
    @ApiResponse(
        responseCode = "400",
        description = "Invalid input (validation errors)",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))
    ),
    @ApiResponse(
        responseCode = "409",
        description = "Account with email already exists",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))
    )
})
@PostMapping
public ResponseEntity<AccountResponseDto> createAccount(@Valid @RequestBody CreateAccountRequestDto request) {
    // ...
}
```

### Generated OpenAPI Schema

SpringDoc automatically generates:
```yaml
components:
  schemas:
    CreateAccountRequestDto:
      type: object
      required:
        - email
        - name
      properties:
        email:
          type: string
          format: email
          description: Account email address
          example: admin@example.com
        name:
          type: string
          minLength: 2
          maxLength: 100
          description: Account display name
          example: John Doe
```

### Alternatives Considered

**Alternative 1: Separate OpenAPI YAML files**
- **Rejected**: Requires manual synchronization between code and spec
- **Issue**: Code-first approach is faster and safer for refactoring

**Alternative 2: Swagger v2 annotations (@ApiModel, @ApiModelProperty)**
- **Rejected**: Deprecated, SpringDoc uses OpenAPI 3 annotations
- **Issue**: Incompatible with modern OpenAPI tooling

### Implementation Notes

1. Add `@Schema` to all DTO record components (both request and response)
2. Add `@Operation` and `@ApiResponses` to controller methods
3. Use `requiredMode = Schema.RequiredMode.REQUIRED` for mandatory fields
4. Provide examples for all fields to improve API documentation
5. Swagger UI will be available at `/swagger-ui.html`

---

## 3. GlobalExceptionHandler Custom Exception Mapping

### Decision

Map service-layer custom exceptions to appropriate HTTP status codes using `@ExceptionHandler` methods in `GlobalExceptionHandler`.

### Rationale

- Centralized error handling improves consistency and reduces controller boilerplate
- Spring's `@RestControllerAdvice` intercepts exceptions before response is sent
- Custom exceptions encode business logic failures (not found, conflict, etc.)
- ErrorResponseDto provides consistent error format across all endpoints
- Existing GlobalExceptionHandler already handles common exceptions (IllegalArgumentException, AccessDeniedException)

### Exception-to-HTTP-Status Mapping

| Service Exception | HTTP Status | Status Code | When Thrown |
|-------------------|-------------|-------------|-------------|
| `AccountNotFoundException` | 404 Not Found | 404 | Account ID not found in database |
| `AccountAlreadyExistsException` | 409 Conflict | 409 | Email already registered |
| `SiteNotFoundException` | 404 Not Found | 404 | Site ID not found in database |
| `SiteAlreadyExistsException` | 409 Conflict | 409 | Domain already registered for account |
| `ErrorLogNotFoundException` | 404 Not Found | 404 | Error log ID not found in database |
| `BatchNotFoundException` | 404 Not Found | 404 | Batch ID not found in database |
| `MethodArgumentNotValidException` | 400 Bad Request | 400 | DTO validation failure (Jakarta Validation) |
| `IllegalArgumentException` | 400 Bad Request | 400 | Invalid input format or business rule violation |
| `IllegalStateException` | 500 Internal Server Error | 500 | System invariant violated |
| `Exception` (generic) | 500 Internal Server Error | 500 | Unexpected errors |

### GlobalExceptionHandler Implementation Pattern

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // EXISTING HANDLERS (already implemented)
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponseDto> handleIllegalArgument(...) { /* existing */ }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponseDto> handleAccessDenied(...) { /* existing */ }

    // NEW HANDLERS (to be added)

    /**
     * Handle AccountNotFoundException (404 Not Found).
     */
    @ExceptionHandler(AccountService.AccountNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handleAccountNotFound(
            AccountService.AccountNotFoundException ex,
            HttpServletRequest request) {

        logger.warn("Account not found: {}", ex.getMessage());

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * Handle AccountAlreadyExistsException (409 Conflict).
     */
    @ExceptionHandler(AccountService.AccountAlreadyExistsException.class)
    public ResponseEntity<ErrorResponseDto> handleAccountAlreadyExists(
            AccountService.AccountAlreadyExistsException ex,
            HttpServletRequest request) {

        logger.warn("Account already exists: {}", ex.getMessage());

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.CONFLICT.value(),
                "Conflict",
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    /**
     * Handle MethodArgumentNotValidException (400 Bad Request).
     * Triggered by @Valid annotation on request DTOs.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDto> handleValidationErrors(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        // Extract first validation error message
        String errorMessage = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");

        logger.warn("Validation failed: {}", errorMessage);

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                errorMessage,
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }
}
```

### Controller Changes

**Before** (with try-catch):
```java
@PostMapping
public ResponseEntity<?> createAccount(@RequestBody Map<String, String> request) {
    try {
        String email = request.get("email");
        String name = request.get("name");

        if (email == null || email.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(createErrorResponse("Email is required"));
        }

        Account account = accountService.createAccount(email, name);
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponseDto.fromEntity(account));

    } catch (AccountService.AccountAlreadyExistsException e) {
        logger.warn("Account already exists: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(createErrorResponse(e.getMessage()));
    } catch (Exception e) {
        logger.error("Error creating account", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Failed to create account"));
    }
}
```

**After** (without try-catch, using DTOs):
```java
@PostMapping
public ResponseEntity<AccountResponseDto> createAccount(@Valid @RequestBody CreateAccountRequestDto request) {
    // Validation handled by @Valid annotation
    // Exceptions automatically caught by GlobalExceptionHandler
    Account account = accountService.createAccount(request.email(), request.name());
    return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponseDto.fromEntity(account));
}
```

### Alternatives Considered

**Alternative 1: Keep try-catch in controllers**
- **Rejected**: Violates DRY principle, duplicates error handling logic
- **Issue**: Inconsistent error responses across controllers

**Alternative 2: Return Optional from services**
- **Rejected**: Forces null checks in controllers, verbose code
- **Issue**: Doesn't handle exceptional cases (conflicts, validation failures)

**Alternative 3: Use Spring's ResponseEntityExceptionHandler**
- **Considered**: Provides default handlers for Spring exceptions
- **Decision**: Extend it if needed, but custom handlers are sufficient

### Implementation Notes

1. Add 8 new `@ExceptionHandler` methods to GlobalExceptionHandler
2. Remove all try-catch blocks from controllers (except resource cleanup)
3. Let exceptions propagate naturally from service layer to GlobalExceptionHandler
4. All handlers return ErrorResponseDto for consistency
5. Log warnings for client errors (4xx), errors for server failures (5xx)

---

## 4. Backward Compatibility Strategy for DTO Migration

### Decision

Ensure **JSON field name compatibility** between old Map responses and new DTO responses to avoid breaking API consumers.

### Rationale

- Admin API consumers may be calling endpoints expecting Map<String, Object> responses
- JSON serialization is field-name based, not type-based
- DTOs that produce the same JSON structure as Maps are backward-compatible
- Zero downtime deployment requires response format stability

### Compatibility Analysis

**Example: AccountResponseDto (existing, already DTO)**
```java
public record AccountResponseDto(
    UUID id,
    String email,
    String name,
    Boolean isActive,
    Instant createdAt,
    Integer maxConcurrentBatches
) {
    public static AccountResponseDto fromEntity(Account account) { /* ... */ }
}
```

**Old Map Response**:
```json
{
  "id": "123e4567-e89b-12d3-a456-426614174000",
  "email": "admin@example.com",
  "name": "John Doe",
  "isActive": true,
  "createdAt": "2025-01-15T10:30:00Z",
  "maxConcurrentBatches": 5
}
```

**New DTO Response** (identical JSON):
```json
{
  "id": "123e4567-e89b-12d3-a456-426614174000",
  "email": "admin@example.com",
  "name": "John Doe",
  "isActive": true,
  "createdAt": "2025-01-15T10:30:00Z",
  "maxConcurrentBatches": 5
}
```

✅ **Fully compatible** - same field names, same types

### New DTOs Requiring Compatibility Verification

**AccountWithStatsResponseDto** (new):
- Must match existing Map structure from `AccountAdminController.getAccount()` (lines 114-127)
- Field names: `id`, `email`, `name`, `isActive`, `createdAt`, `sitesCount`, `totalBatches`, `totalUploadedFiles`
- All fields already present in Map response → compatible

**BatchSummaryDto** (new):
- Must match existing Map structure from `BatchAdminController.createBatchSummary()` (lines 174-187)
- Field names: `id`, `siteId`, `status`, `s3Path`, `uploadedFilesCount`, `totalSize`, `hasErrors`, `startedAt`, `completedAt`, `createdAt`
- All fields already present in Map response → compatible

**SiteCreationResponseDto** (new):
- Must match existing Map structure from `SiteAdminController.createSite()` (lines 84-92)
- Field names: `id`, `accountId`, `domain`, `name`, `isActive`, `createdAt`, `clientSecret`
- All fields already present in Map response → compatible

### Jackson Serialization Behavior

Spring Boot's default Jackson configuration:
- **Field names**: Uses record component names as JSON keys
- **Null handling**: Excludes null fields by default (can configure if needed)
- **Date format**: ISO-8601 format for Instant/LocalDateTime
- **Enums**: Serialized as strings (e.g., `"ACTIVE"`)
- **Collections**: Arrays for List, objects for Map

**No custom Jackson configuration required** - defaults are sufficient.

### Request DTO Compatibility

Request DTOs are **new** (replacing Map<String, String> parameters):
- Old: `@RequestBody Map<String, String> request` → extract `request.get("email")`
- New: `@RequestBody CreateAccountRequestDto request` → access `request.email()`

**Client impact**: Clients sending JSON will not notice any difference (same JSON structure expected).

**Example**:
```json
POST /api/admin/accounts
Content-Type: application/json

{
  "email": "admin@example.com",
  "name": "John Doe"
}
```

This JSON works with both old Map and new CreateAccountRequestDto (field names match).

### Deployment Strategy

**Single-phase deployment** (safe):
1. Deploy DTO refactoring in one release
2. JSON responses remain structurally identical
3. Clients continue working without changes
4. No API version bump required

**Risk mitigation**:
- Contract tests verify JSON response structure before/after
- Integration tests validate full request-response flows
- Manual testing with Swagger UI confirms API contracts

### Alternatives Considered

**Alternative 1: API versioning (e.g., /api/v2/admin/accounts)**
- **Rejected**: Unnecessary complexity for backward-compatible changes
- **When to use**: Only when response structure changes materially

**Alternative 2: @JsonProperty annotations to rename fields**
- **Rejected**: Not needed if record component names match existing Map keys
- **When to use**: Only if DTO field names must differ from JSON keys

### Implementation Notes

1. Keep DTO record component names identical to existing Map keys
2. Verify JSON responses in contract tests before/after refactoring
3. Document any intentional response format changes in release notes
4. Monitor API consumers after deployment for unexpected errors

---

## 5. Test Strategy for DTO Refactoring

### Decision

Update tests **incrementally by controller** following TDD process: contract tests → integration tests → implementation → unit tests.

### Rationale

- TDD is non-negotiable per constitution (Principle III)
- Contract tests define API behavior expectations
- Integration tests validate full request-response flows
- Unit tests verify DTO validation logic
- Incremental approach reduces risk and allows early detection of issues

### Test Update Strategy

**Phase 1: Contract Tests** (MockMvc, no database):
```java
@Test
void shouldCreateAccount() throws Exception {
    String requestJson = """
        {
            "email": "admin@example.com",
            "name": "John Doe"
        }
        """;

    mockMvc.perform(post("/api/admin/accounts")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestJson)
            .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.email").value("admin@example.com"))
            .andExpect(jsonPath("$.name").value("John Doe"))
            .andExpect(jsonPath("$.isActive").value(true));
}

@Test
void shouldReturn400WhenEmailMissing() throws Exception {
    String requestJson = """
        {
            "name": "John Doe"
        }
        """;

    mockMvc.perform(post("/api/admin/accounts")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestJson)
            .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Bad Request"))
            .andExpect(jsonPath("$.message").value(containsString("email")));
}
```

**Phase 2: Integration Tests** (Testcontainers, full stack):
```java
@Test
void shouldCreateAccountEndToEnd() {
    CreateAccountRequestDto request = new CreateAccountRequestDto("admin@example.com", "John Doe");

    ResponseEntity<AccountResponseDto> response = restTemplate
            .exchange("/api/admin/accounts", HttpMethod.POST,
                      new HttpEntity<>(request, headers),
                      AccountResponseDto.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().email()).isEqualTo("admin@example.com");
    assertThat(response.getBody().name()).isEqualTo("John Doe");
}
```

**Phase 3: Implementation** (Controller + DTOs):
- Create request/response DTOs
- Refactor controller method to use DTOs
- Remove try-catch blocks
- Add validation annotations
- Add OpenAPI annotations

**Phase 4: Unit Tests** (DTO validation logic):
```java
@Test
void shouldValidateEmailFormat() {
    CreateAccountRequestDto dto = new CreateAccountRequestDto("invalid-email", "John Doe");

    Set<ConstraintViolation<CreateAccountRequestDto>> violations = validator.validate(dto);

    assertThat(violations).hasSize(1);
    assertThat(violations.iterator().next().getMessage()).contains("Email must be valid format");
}
```

### Test Data Builders

Create reusable test data builders to reduce boilerplate:

```java
public class TestDataBuilders {

    public static CreateAccountRequestDto createAccountRequest(String email, String name) {
        return new CreateAccountRequestDto(email, name);
    }

    public static CreateAccountRequestDto validAccountRequest() {
        return new CreateAccountRequestDto("admin@example.com", "John Doe");
    }

    public static String toJson(Object obj) throws JsonProcessingException {
        return new ObjectMapper().writeValueAsString(obj);
    }
}
```

### Test Coverage Requirements

Per constitution (Principle III):
- **Minimum 80% overall coverage** (enforced by Jacoco)
- **Contract tests**: All controller endpoints (happy path + error cases)
- **Integration tests**: Full request-response flows with database
- **Unit tests**: DTO validation rules

### Test Execution Order

1. **AccountAdminController** (4 endpoints: create, get, list, update, delete)
2. **SiteAdminController** (5 endpoints: create, get, list by account, update, delete)
3. **ErrorLogController** (3 endpoints: log standalone error, log batch error, get error)
4. **BatchAdminController** (3 endpoints: list, get details, delete)
5. **ErrorAdminController** (2 endpoints: list, export CSV)

Total: ~17 endpoints to refactor with tests.

### Alternatives Considered

**Alternative 1: Update all tests at once**
- **Rejected**: High risk, difficult to debug failures
- **Issue**: Violates incremental development principle

**Alternative 2: Skip contract tests, only integration tests**
- **Rejected**: Violates TDD process (contract tests define API behavior)
- **Issue**: Integration tests are slower and less focused

**Alternative 3: Generate tests automatically**
- **Rejected**: Generated tests lack business context and edge cases
- **Issue**: Low-quality tests that provide false confidence

### Implementation Notes

1. Use test data builders to reduce JSON boilerplate
2. Test validation errors explicitly (missing fields, wrong format)
3. Test exception handling via GlobalExceptionHandler
4. Verify JSON response structure matches spec
5. Run full test suite after each controller refactored
6. Check coverage report to ensure ≥80% threshold maintained

---

## Summary

| Research Area | Decision | Key Takeaway |
|---------------|----------|--------------|
| Validation | Jakarta Bean Validation 3.0 with @Valid, @NotBlank, @Email | Declarative, automatic, OpenAPI-integrated |
| OpenAPI | SpringDoc with @Schema on record components | Code-first, automatic schema generation |
| Exception Handling | GlobalExceptionHandler with @ExceptionHandler | Centralized, consistent ErrorResponseDto |
| Compatibility | JSON field name matching between Maps and DTOs | Zero downtime, backward-compatible |
| Testing | TDD: contract → integration → implementation → unit | Incremental, safe, 80% coverage enforced |

**All research questions resolved** - ready for Phase 1 (Design & Contracts).
