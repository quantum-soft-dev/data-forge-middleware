# Quickstart Guide: DTO Refactoring for Admin Controllers

**Feature**: Admin Controllers DTO Refactoring and Error Handling Standardization
**Date**: 2025-10-11
**Audience**: Developers implementing the refactoring

This guide provides practical examples for implementing type-safe DTOs, centralized exception handling, and OpenAPI documentation.

## Table of Contents

1. [Creating Request DTOs](#creating-request-dtos)
2. [Creating Response DTOs](#creating-response-dtos)
3. [Refactoring Controllers](#refactoring-controllers)
4. [Adding GlobalExceptionHandler Handlers](#adding-globalexceptionhandler-handlers)
5. [Adding OpenAPI Annotations](#adding-openapi-annotations)
6. [Writing Tests](#writing-tests)
7. [Common Patterns](#common-patterns)

---

## Creating Request DTOs

Request DTOs replace `Map<String, String>` parameters in POST/PUT endpoints.

### Step 1: Create the DTO Record

```java
package com.bitbi.dfm.account.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for creating a new account.
 */
public record CreateAccountRequestDto(
    @Schema(
        description = "Account email address",
        example = "admin@example.com",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid format")
    String email,

    @Schema(
        description = "Account display name",
        example = "John Doe",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100, message = "Name must be 2-100 characters")
    String name
) {}
```

### Step 2: Update Controller Method

**Before**:
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
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AccountResponseDto.fromEntity(account));

    } catch (AccountService.AccountAlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(createErrorResponse(e.getMessage()));
    }
}
```

**After**:
```java
@PostMapping
public ResponseEntity<AccountResponseDto> createAccount(
        @Valid @RequestBody CreateAccountRequestDto request) {

    // Validation is automatic via @Valid annotation
    // Exceptions are caught by GlobalExceptionHandler
    Account account = accountService.createAccount(request.email(), request.name());
    return ResponseEntity.status(HttpStatus.CREATED)
            .body(AccountResponseDto.fromEntity(account));
}
```

**Key Changes**:
- Replace `Map<String, String>` with `CreateAccountRequestDto`
- Add `@Valid` annotation to trigger validation
- Remove manual validation checks (handled by Jakarta Validation)
- Remove try-catch blocks (handled by GlobalExceptionHandler)
- Return type is now `ResponseEntity<AccountResponseDto>` instead of `ResponseEntity<?>`

---

## Creating Response DTOs

Response DTOs replace `Map<String, Object>` returns and custom Map building logic.

### Step 1: Create the DTO Record

```java
package com.bitbi.dfm.account.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for account details with statistics.
 */
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
) {
    /**
     * Factory method to create DTO from Account entity and statistics map.
     */
    public static AccountWithStatsResponseDto fromEntityAndStats(
            Account account, Map<String, Object> stats) {
        return new AccountWithStatsResponseDto(
            account.getId(),
            account.getEmail(),
            account.getName(),
            account.getIsActive(),
            account.getCreatedAt(),
            (Integer) stats.get("totalSites"),
            (Integer) stats.get("totalBatches"),
            (Integer) stats.get("totalFiles")
        );
    }
}
```

### Step 2: Update Controller Method

**Before**:
```java
@GetMapping("/{id}")
public ResponseEntity<?> getAccount(@PathVariable("id") UUID accountId) {
    try {
        Account account = accountService.getAccount(accountId);
        Map<String, Object> statistics = accountStatisticsService.getAccountStatistics(accountId);

        // Manually build response Map
        Map<String, Object> response = new HashMap<>();
        response.put("id", account.getId());
        response.put("email", account.getEmail());
        response.put("name", account.getName());
        response.put("isActive", account.getIsActive());
        response.put("createdAt", account.getCreatedAt());
        response.put("sitesCount", statistics.get("totalSites"));
        response.put("totalBatches", statistics.get("totalBatches"));
        response.put("totalUploadedFiles", statistics.get("totalFiles"));

        return ResponseEntity.ok(response);

    } catch (AccountService.AccountNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(createErrorResponse("Account not found"));
    }
}
```

**After**:
```java
@GetMapping("/{id}")
public ResponseEntity<AccountWithStatsResponseDto> getAccount(
        @PathVariable("id") UUID accountId) {

    Account account = accountService.getAccount(accountId);
    Map<String, Object> statistics = accountStatisticsService.getAccountStatistics(accountId);

    AccountWithStatsResponseDto response =
        AccountWithStatsResponseDto.fromEntityAndStats(account, statistics);

    return ResponseEntity.ok(response);
}
```

**Key Changes**:
- Replace manual Map construction with `AccountWithStatsResponseDto.fromEntityAndStats()`
- Remove try-catch block (exception handled by GlobalExceptionHandler)
- Return type is now `ResponseEntity<AccountWithStatsResponseDto>` instead of `ResponseEntity<?>`

---

## Refactoring Controllers

Follow this systematic approach for each controller.

### Controller Refactoring Checklist

For each endpoint in the controller:

1. ✅ **Identify input structure**: What fields does the endpoint accept?
2. ✅ **Create request DTO** (if POST/PUT): Define record with validation annotations
3. ✅ **Identify output structure**: What fields does the endpoint return?
4. ✅ **Create response DTO**: Define record with @Schema annotations
5. ✅ **Update controller method signature**: Replace Map with DTOs
6. ✅ **Remove try-catch blocks**: Let exceptions propagate to GlobalExceptionHandler
7. ✅ **Add OpenAPI annotations**: @Operation, @ApiResponses
8. ✅ **Update tests**: Contract tests, integration tests, unit tests

### Example: Complete Endpoint Refactoring

**Original Endpoint** (`SiteAdminController.createSite()`):
```java
@PostMapping("/api/admin/accounts/{accountId}/sites")
public ResponseEntity<Map<String, Object>> createSite(
        @PathVariable("accountId") UUID accountId,
        @RequestBody Map<String, String> request) {

    try {
        String domain = request.get("domain");
        String displayName = request.get("displayName");

        if (domain == null || domain.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(createErrorResponse("Domain is required"));
        }

        SiteService.SiteCreationResult result = siteService.createSite(accountId, domain, displayName);

        // Build response Map
        Map<String, Object> response = new HashMap<>();
        response.put("id", result.site().getId());
        response.put("accountId", result.site().getAccountId());
        response.put("domain", result.site().getDomain());
        response.put("name", result.site().getName());
        response.put("isActive", result.site().getIsActive());
        response.put("createdAt", result.site().getCreatedAt());
        response.put("clientSecret", result.plaintextSecret());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);

    } catch (SiteService.SiteAlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(createErrorResponse(e.getMessage()));
    }
}
```

**Refactored Endpoint**:
```java
@Operation(
    summary = "Create new site",
    description = "Creates a new site under an account. Returns site details including client secret (one-time only).",
    tags = {"Admin - Sites"}
)
@ApiResponses(value = {
    @ApiResponse(
        responseCode = "201",
        description = "Site created successfully",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = SiteCreationResponseDto.class))
    ),
    @ApiResponse(
        responseCode = "400",
        description = "Invalid input (validation errors)",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))
    ),
    @ApiResponse(
        responseCode = "409",
        description = "Site with domain already exists for account",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))
    )
})
@PostMapping("/api/admin/accounts/{accountId}/sites")
public ResponseEntity<SiteCreationResponseDto> createSite(
        @PathVariable("accountId") UUID accountId,
        @Valid @RequestBody CreateSiteRequestDto request) {

    SiteService.SiteCreationResult result = siteService.createSite(
        accountId,
        request.domain(),
        request.displayName()
    );

    SiteCreationResponseDto response = SiteCreationResponseDto.fromCreationResult(
        result.site(),
        result.plaintextSecret()
    );

    return ResponseEntity.status(HttpStatus.CREATED).body(response);
}
```

---

## Adding GlobalExceptionHandler Handlers

Add exception handlers for custom service exceptions.

### Step 1: Identify Service Exceptions

Find all custom exceptions thrown by services:
- `AccountService.AccountNotFoundException`
- `AccountService.AccountAlreadyExistsException`
- `SiteService.SiteNotFoundException`
- `SiteService.SiteAlreadyExistsException`
- `ErrorLoggingService.ErrorLogNotFoundException`
- `BatchLifecycleService.BatchNotFoundException`

### Step 2: Add @ExceptionHandler Methods

```java
package com.bitbi.dfm.shared.exception;

import com.bitbi.dfm.account.application.AccountService;
import com.bitbi.dfm.site.application.SiteService;
import com.bitbi.dfm.error.application.ErrorLoggingService;
import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.shared.presentation.dto.ErrorResponseDto;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ========== 404 NOT FOUND HANDLERS ==========

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

    @ExceptionHandler(SiteService.SiteNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handleSiteNotFound(
            SiteService.SiteNotFoundException ex,
            HttpServletRequest request) {

        logger.warn("Site not found: {}", ex.getMessage());

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(BatchLifecycleService.BatchNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handleBatchNotFound(
            BatchLifecycleService.BatchNotFoundException ex,
            HttpServletRequest request) {

        logger.warn("Batch not found: {}", ex.getMessage());

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(ErrorLoggingService.ErrorLogNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handleErrorLogNotFound(
            ErrorLoggingService.ErrorLogNotFoundException ex,
            HttpServletRequest request) {

        logger.warn("Error log not found: {}", ex.getMessage());

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    // ========== 409 CONFLICT HANDLERS ==========

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

    @ExceptionHandler(SiteService.SiteAlreadyExistsException.class)
    public ResponseEntity<ErrorResponseDto> handleSiteAlreadyExists(
            SiteService.SiteAlreadyExistsException ex,
            HttpServletRequest request) {

        logger.warn("Site already exists: {}", ex.getMessage());

        ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                HttpStatus.CONFLICT.value(),
                "Conflict",
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    // ========== 400 BAD REQUEST HANDLERS ==========

    /**
     * Handle DTO validation errors (triggered by @Valid annotation).
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

    // ... existing handlers for IllegalArgumentException, AccessDeniedException, etc.
}
```

---

## Adding OpenAPI Annotations

Annotate controllers to generate proper Swagger documentation.

### Controller-Level Annotation

```java
@RestController
@RequestMapping("/api/admin/accounts")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Accounts", description = "Account administration endpoints")
public class AccountAdminController {
    // ...
}
```

### Method-Level Annotations

```java
@Operation(
    summary = "Create new account",
    description = "Creates a new account with email and name. Email must be unique. Returns account details.",
    tags = {"Admin - Accounts"}
)
@ApiResponses(value = {
    @ApiResponse(
        responseCode = "201",
        description = "Account created successfully",
        content = @Content(
            mediaType = "application/json",
            schema = @Schema(implementation = AccountResponseDto.class)
        )
    ),
    @ApiResponse(
        responseCode = "400",
        description = "Invalid input (validation errors)",
        content = @Content(
            mediaType = "application/json",
            schema = @Schema(implementation = ErrorResponseDto.class)
        )
    ),
    @ApiResponse(
        responseCode = "409",
        description = "Account with email already exists",
        content = @Content(
            mediaType = "application/json",
            schema = @Schema(implementation = ErrorResponseDto.class)
        )
    )
})
@PostMapping
public ResponseEntity<AccountResponseDto> createAccount(
        @Valid @RequestBody CreateAccountRequestDto request) {
    // ...
}
```

---

## Writing Tests

Update tests to use DTOs and verify GlobalExceptionHandler behavior.

### Contract Tests (MockMvc)

```java
@Test
void shouldCreateAccountWithValidInput() throws Exception {
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
            .andExpect(jsonPath("$.isActive").value(true))
            .andExpect(jsonPath("$.createdAt").exists());
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
            .andExpect(jsonPath("$.message").value(containsString("email")))
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.path").value("/api/admin/accounts"));
}

@Test
void shouldReturn409WhenEmailAlreadyExists() throws Exception {
    // Create first account
    accountService.createAccount("admin@example.com", "John Doe");

    // Try to create duplicate
    String requestJson = """
        {
            "email": "admin@example.com",
            "name": "Jane Smith"
        }
        """;

    mockMvc.perform(post("/api/admin/accounts")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestJson)
            .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.error").value("Conflict"))
            .andExpect(jsonPath("$.message").value(containsString("already exists")));
}
```

### Integration Tests (Testcontainers)

```java
@Test
void shouldCreateAccountEndToEnd() {
    CreateAccountRequestDto request = new CreateAccountRequestDto(
        "admin@example.com",
        "John Doe"
    );

    ResponseEntity<AccountResponseDto> response = restTemplate
            .exchange("/api/admin/accounts",
                      HttpMethod.POST,
                      new HttpEntity<>(request, headers),
                      AccountResponseDto.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().email()).isEqualTo("admin@example.com");
    assertThat(response.getBody().name()).isEqualTo("John Doe");
    assertThat(response.getBody().isActive()).isTrue();
}
```

### Unit Tests (DTO Validation)

```java
@ExtendWith(MockitoExtension.class)
class CreateAccountRequestDtoTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void shouldValidateSuccessfully() {
        CreateAccountRequestDto dto = new CreateAccountRequestDto(
            "admin@example.com",
            "John Doe"
        );

        Set<ConstraintViolation<CreateAccountRequestDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void shouldFailWhenEmailInvalid() {
        CreateAccountRequestDto dto = new CreateAccountRequestDto(
            "invalid-email",
            "John Doe"
        );

        Set<ConstraintViolation<CreateAccountRequestDto>> violations = validator.validate(dto);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .isEqualTo("Email must be valid format");
    }

    @Test
    void shouldFailWhenNameTooShort() {
        CreateAccountRequestDto dto = new CreateAccountRequestDto(
            "admin@example.com",
            "J"
        );

        Set<ConstraintViolation<CreateAccountRequestDto>> violations = validator.validate(dto);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .isEqualTo("Name must be 2-100 characters");
    }
}
```

---

## Common Patterns

### Pattern 1: DTO with Nested Objects

For complex responses with nested structures (e.g., `BatchDetailResponseDto` with `List<UploadedFileDto>`):

```java
public record BatchDetailResponseDto(
    UUID id,
    // ... other fields
    List<UploadedFileDto> files
) {
    public static BatchDetailResponseDto fromBatchAndFiles(
            Batch batch,
            String siteDomain,
            List<UploadedFile> uploadedFiles) {

        List<UploadedFileDto> fileDtos = uploadedFiles.stream()
                .map(UploadedFileDto::fromEntity)
                .toList();

        return new BatchDetailResponseDto(
            batch.getId(),
            // ... map other fields
            fileDtos
        );
    }
}
```

### Pattern 2: Optional Fields in Request DTOs

For optional fields (like `metadata` in `LogErrorRequestDto`), do not add validation annotations:

```java
public record LogErrorRequestDto(
    @NotBlank String type,
    @NotBlank String message,
    Map<String, Object> metadata  // No @NotNull - this field is optional
) {}
```

### Pattern 3: Paginated Responses

Reuse existing `PageResponseDto<T>`:

```java
@GetMapping
public ResponseEntity<PageResponseDto<AccountResponseDto>> listAccounts(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {

    Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
    Page<Account> accountPage = accountService.listAccounts(pageable);

    List<AccountResponseDto> content = accountPage.getContent().stream()
            .map(AccountResponseDto::fromEntity)
            .toList();

    PageResponseDto<AccountResponseDto> response = new PageResponseDto<>(
        content,
        accountPage.getNumber(),
        accountPage.getSize(),
        accountPage.getTotalElements(),
        accountPage.getTotalPages()
    );

    return ResponseEntity.ok(response);
}
```

### Pattern 4: Mapping Service Statistics to DTOs

For endpoints returning statistics (maps from service layer):

```java
@GetMapping("/{id}/stats")
public ResponseEntity<AccountStatisticsDto> getAccountStats(
        @PathVariable("id") UUID accountId) {

    Map<String, Object> statistics = accountStatisticsService.getAccountStatistics(accountId);

    AccountStatisticsDto response = AccountStatisticsDto.fromStatsMap(statistics);

    return ResponseEntity.ok(response);
}
```

---

## Checklist for Each Controller

Use this checklist when refactoring each controller:

- [ ] All request DTOs created with validation annotations
- [ ] All response DTOs created with @Schema annotations
- [ ] All controller methods updated to use DTOs
- [ ] All try-catch blocks removed (except resource cleanup)
- [ ] All @Operation and @ApiResponses annotations added
- [ ] GlobalExceptionHandler extended with custom exception handlers
- [ ] Contract tests updated and passing
- [ ] Integration tests updated and passing
- [ ] Unit tests for DTO validation written and passing
- [ ] Code coverage ≥80% verified
- [ ] Swagger UI documentation verified at /swagger-ui.html

---

## Resources

- **Specification**: `specs/004-code-improvements-the/spec.md`
- **Data Model**: `specs/004-code-improvements-the/data-model.md`
- **Research**: `specs/004-code-improvements-the/research.md`
- **OpenAPI Contracts**: `specs/004-code-improvements-the/contracts/`
- **Jakarta Validation Docs**: https://jakarta.ee/specifications/bean-validation/3.0/
- **SpringDoc OpenAPI Docs**: https://springdoc.org/

---

**Next Step**: Run `/speckit.tasks` to generate granular implementation tasks organized by user story priority.
