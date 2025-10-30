# Implementation Tasks: Admin Controllers DTO Refactoring and Error Handling Standardization

**Feature Branch**: `004-code-improvements-the`
**Created**: 2025-10-11
**Status**: Ready for Implementation

This document provides granular, dependency-ordered tasks for implementing type-safe DTOs, centralized exception handling, and OpenAPI documentation across admin controllers.

## Overview

**Total Tasks**: 62 tasks
**Controllers Refactored**: 5 (AccountAdminController, SiteAdminController, BatchAdminController, ErrorAdminController, ErrorLogController)
**New DTOs**: 13 (5 request, 8 response)
**Exception Handlers**: 8 new handlers in GlobalExceptionHandler
**Test Strategy**: TDD (Contract → Integration → Implementation → Unit)

## Task Organization

Tasks are organized by user story to enable **incremental delivery** and **independent testing**:

- **Phase 1: Setup** (1 task) - Global exception handler foundation
- **Phase 2: Foundational** (No tasks) - No blocking prerequisites
- **Phase 3: User Story 1 (P1)** (22 tasks) - Request DTOs + validation
- **Phase 4: User Story 2 (P2)** (18 tasks) - Response DTOs + API contracts
- **Phase 5: User Story 3 (P3)** (15 tasks) - Remove try-catch, add exception handlers
- **Phase 6: User Story 4 (P4)** (5 tasks) - OpenAPI documentation
- **Phase 7: Polish** (1 task) - Verification and cleanup

**Checkpoint Gates**: After each user story phase, verify all tests pass before proceeding.

---

## Phase 1: Setup (Global Infrastructure)

### T001: [Setup] Add MethodArgumentNotValidException Handler to GlobalExceptionHandler [US3 prerequisite]

**Story**: Setup (required by US1)
**File**: `src/main/java/com/bitbi/dfm/shared/exception/GlobalExceptionHandler.java`
**Description**: Add exception handler for Jakarta Validation errors triggered by @Valid annotation on request DTOs.

**Implementation**:
```java
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
```

**Success Criteria**:
- Handler catches MethodArgumentNotValidException
- Returns 400 Bad Request with ErrorResponseDto
- Logs validation failure with error message
- Extracts field-level error details from BindingResult

---

## Phase 3: User Story 1 - Replace Input Maps with Request DTOs (Priority: P1)

**Goal**: Replace all `Map<String, Object>` request parameters with type-safe DTO records that provide validation and clear API contracts.

**Independent Test**: Send POST/PUT requests with request DTOs and verify validation works correctly.

### Account Admin Controller (Request DTOs)

#### T002: [US1] [P] Write contract test for CreateAccountRequestDto validation

**Story**: User Story 1 (P1)
**File**: `src/test/java/com/bitbi/dfm/contract/AdminContractTest.java`
**Description**: Write MockMvc test verifying CreateAccountRequestDto validation (email format, name length).

**Test Cases**:
1. Valid request → 201 Created
2. Missing email → 400 Bad Request (validation error)
3. Invalid email format → 400 Bad Request
4. Missing name → 400 Bad Request
5. Name too short (1 char) → 400 Bad Request

**Example**:
```java
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
            .andExpect(jsonPath("$.message").value(containsString("email")));
}
```

**Success Criteria**: All 5 test cases written and initially failing

---

#### T003: [US1] Create CreateAccountRequestDto record

**Story**: User Story 1 (P1)
**File**: `src/main/java/com/bitbi/dfm/account/presentation/dto/CreateAccountRequestDto.java`
**Description**: Create Java record with validation annotations for account creation.

**Implementation**:
```java
package com.bitbi.dfm.account.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAccountRequestDto(
    @Schema(description = "Account email address", example = "admin@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid format")
    String email,

    @Schema(description = "Account display name", example = "John Doe", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100, message = "Name must be 2-100 characters")
    String name
) {}
```

**Success Criteria**:
- Record defined with email and name fields
- Validation annotations present: @NotBlank, @Email, @Size
- @Schema annotations for OpenAPI documentation

---

#### T004: [US1] Refactor AccountAdminController.createAccount() to use CreateAccountRequestDto

**Story**: User Story 1 (P1)
**File**: `src/main/java/com/bitbi/dfm/account/presentation/AccountAdminController.java`
**Description**: Replace Map<String, String> parameter with CreateAccountRequestDto, add @Valid annotation, remove manual validation.

**Before**:
```java
public ResponseEntity<?> createAccount(@RequestBody Map<String, String> request) {
    String email = request.get("email");
    String name = request.get("name");
    if (email == null || email.isBlank()) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(createErrorResponse("Email is required"));
    }
    // ...
}
```

**After**:
```java
public ResponseEntity<AccountResponseDto> createAccount(@Valid @RequestBody CreateAccountRequestDto request) {
    Account account = accountService.createAccount(request.email(), request.name());
    return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponseDto.fromEntity(account));
}
```

**Success Criteria**:
- Method parameter is `CreateAccountRequestDto` with @Valid annotation
- Return type is `ResponseEntity<AccountResponseDto>`
- Manual validation removed (handled by @Valid)
- Try-catch remains (will be removed in US3)

---

#### T005: [US1] Verify T002 contract tests pass

**Story**: User Story 1 (P1)
**File**: `src/test/java/com/bitbi/dfm/contract/AdminContractTest.java`
**Description**: Run contract tests from T002 and verify all pass with CreateAccountRequestDto.

**Success Criteria**:
- All 5 test cases pass
- Validation errors return 400 with ErrorResponseDto
- Valid requests return 201 with AccountResponseDto

---

#### T006: [US1] [P] Write contract test for UpdateAccountRequestDto validation

**Story**: User Story 1 (P1)
**File**: `src/test/java/com/bitbi/dfm/contract/AdminContractTest.java`
**Description**: Write MockMvc test verifying UpdateAccountRequestDto validation (name length).

**Test Cases**:
1. Valid request → 200 OK
2. Missing name → 400 Bad Request
3. Name too short → 400 Bad Request

**Success Criteria**: All 3 test cases written and initially failing

---

#### T007: [US1] Create UpdateAccountRequestDto record

**Story**: User Story 1 (P1)
**File**: `src/main/java/com/bitbi/dfm/account/presentation/dto/UpdateAccountRequestDto.java`
**Description**: Create Java record with validation annotations for account updates.

**Implementation**:
```java
public record UpdateAccountRequestDto(
    @Schema(description = "New account display name", example = "Jane Smith", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100, message = "Name must be 2-100 characters")
    String name
) {}
```

**Success Criteria**: Record defined with name field and validation annotations

---

#### T008: [US1] Refactor AccountAdminController.updateAccount() to use UpdateAccountRequestDto

**Story**: User Story 1 (P1)
**File**: `src/main/java/com/bitbi/dfm/account/presentation/AccountAdminController.java`
**Description**: Replace Map<String, String> parameter with UpdateAccountRequestDto, add @Valid annotation.

**Success Criteria**: Method uses UpdateAccountRequestDto with @Valid, manual validation removed

---

#### T009: [US1] Verify T006 contract tests pass

**Story**: User Story 1 (P1)
**File**: `src/test/java/com/bitbi/dfm/contract/AdminContractTest.java`
**Description**: Run contract tests from T006 and verify all pass.

**Success Criteria**: All 3 test cases pass

---

### Site Admin Controller (Request DTOs)

#### T010: [US1] [P] Write contract test for CreateSiteRequestDto validation

**Story**: User Story 1 (P1)
**File**: `src/test/java/com/bitbi/dfm/contract/AdminContractTest.java`
**Description**: Write MockMvc test verifying CreateSiteRequestDto validation (domain format, displayName length).

**Test Cases**:
1. Valid request → 201 Created
2. Missing domain → 400 Bad Request
3. Invalid domain (uppercase/special chars) → 400 Bad Request
4. Missing displayName → 400 Bad Request
5. DisplayName too short → 400 Bad Request

**Success Criteria**: All 5 test cases written and initially failing

---

#### T011: [US1] Create CreateSiteRequestDto record

**Story**: User Story 1 (P1)
**File**: `src/main/java/com/bitbi/dfm/site/presentation/dto/CreateSiteRequestDto.java`
**Description**: Create Java record with validation annotations for site creation.

**Implementation**:
```java
public record CreateSiteRequestDto(
    @Schema(description = "Site domain (unique within account)", example = "example.com", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Domain is required")
    @Pattern(regexp = "^[a-z0-9.-]+$", message = "Domain must contain only lowercase letters, numbers, dots, and hyphens")
    @Size(min = 3, max = 255, message = "Domain must be 3-255 characters")
    String domain,

    @Schema(description = "Site display name", example = "Example Website", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Display name is required")
    @Size(min = 2, max = 100, message = "Display name must be 2-100 characters")
    String displayName
) {}
```

**Success Criteria**: Record defined with domain and displayName fields, validation annotations including @Pattern

---

#### T012: [US1] Refactor SiteAdminController.createSite() to use CreateSiteRequestDto

**Story**: User Story 1 (P1)
**File**: `src/main/java/com/bitbi/dfm/site/presentation/SiteAdminController.java`
**Description**: Replace Map<String, String> parameter with CreateSiteRequestDto, add @Valid annotation.

**Success Criteria**: Method uses CreateSiteRequestDto with @Valid, manual validation removed

---

#### T013: [US1] Verify T010 contract tests pass

**Story**: User Story 1 (P1)
**File**: `src/test/java/com/bitbi/dfm/contract/AdminContractTest.java`
**Description**: Run contract tests from T010 and verify all pass.

**Success Criteria**: All 5 test cases pass

---

#### T014: [US1] [P] Write contract test for UpdateSiteRequestDto validation

**Story**: User Story 1 (P1)
**File**: `src/test/java/com/bitbi/dfm/contract/AdminContractTest.java`
**Description**: Write MockMvc test verifying UpdateSiteRequestDto validation.

**Test Cases**:
1. Valid request → 200 OK
2. Missing displayName → 400 Bad Request
3. DisplayName too short → 400 Bad Request

**Success Criteria**: All 3 test cases written and initially failing

---

#### T015: [US1] Create UpdateSiteRequestDto record

**Story**: User Story 1 (P1)
**File**: `src/main/java/com/bitbi/dfm/site/presentation/dto/UpdateSiteRequestDto.java`
**Description**: Create Java record with validation annotations for site updates.

**Success Criteria**: Record defined with displayName field and validation annotations

---

#### T016: [US1] Refactor SiteAdminController.updateSite() to use UpdateSiteRequestDto

**Story**: User Story 1 (P1)
**File**: `src/main/java/com/bitbi/dfm/site/presentation/SiteAdminController.java`
**Description**: Replace Map<String, String> parameter with UpdateSiteRequestDto, add @Valid annotation.

**Success Criteria**: Method uses UpdateSiteRequestDto with @Valid, manual validation removed

---

#### T017: [US1] Verify T014 contract tests pass

**Story**: User Story 1 (P1)
**File**: `src/test/java/com/bitbi/dfm/contract/AdminContractTest.java`
**Description**: Run contract tests from T014 and verify all pass.

**Success Criteria**: All 3 test cases pass

---

### Error Log Controller (Request DTOs)

#### T018: [US1] [P] Write contract test for LogErrorRequestDto validation

**Story**: User Story 1 (P1)
**File**: `src/test/java/com/bitbi/dfm/contract/ErrorLogContractTest.java`
**Description**: Write MockMvc test verifying LogErrorRequestDto validation (type, message, metadata optional).

**Test Cases**:
1. Valid request with metadata → 201 Created
2. Valid request without metadata → 201 Created
3. Missing type → 400 Bad Request
4. Missing message → 400 Bad Request
5. Type too long (>100 chars) → 400 Bad Request
6. Message too long (>1000 chars) → 400 Bad Request

**Success Criteria**: All 6 test cases written and initially failing

---

#### T019: [US1] Create LogErrorRequestDto record

**Story**: User Story 1 (P1)
**File**: `src/main/java/com/bitbi/dfm/error/presentation/dto/LogErrorRequestDto.java`
**Description**: Create Java record with validation annotations for error logging.

**Implementation**:
```java
public record LogErrorRequestDto(
    @Schema(description = "Error type/category", example = "UPLOAD_FAILED", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Error type is required")
    @Size(max = 100, message = "Error type must not exceed 100 characters")
    String type,

    @Schema(description = "Error message description", example = "File upload failed: connection timeout", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Error message is required")
    @Size(max = 1000, message = "Error message must not exceed 1000 characters")
    String message,

    @Schema(description = "Optional additional error context", example = "{\"fileName\":\"data.csv\",\"fileSize\":1024}", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    Map<String, Object> metadata
) {}
```

**Success Criteria**: Record defined with type, message, metadata fields, validation annotations

---

#### T020: [US1] Refactor ErrorLogController.logStandaloneError() to use LogErrorRequestDto

**Story**: User Story 1 (P1)
**File**: `src/main/java/com/bitbi/dfm/error/presentation/ErrorLogController.java`
**Description**: Replace Map<String, Object> parameter with LogErrorRequestDto, add @Valid annotation.

**Success Criteria**: Method uses LogErrorRequestDto with @Valid, manual validation removed

---

#### T021: [US1] Refactor ErrorLogController.logError() to use LogErrorRequestDto

**Story**: User Story 1 (P1)
**File**: `src/main/java/com/bitbi/dfm/error/presentation/ErrorLogController.java`
**Description**: Replace Map<String, Object> parameter with LogErrorRequestDto, add @Valid annotation.

**Success Criteria**: Method uses LogErrorRequestDto with @Valid, manual validation removed

---

#### T022: [US1] Verify T018 contract tests pass

**Story**: User Story 1 (P1)
**File**: `src/test/java/com/bitbi/dfm/contract/ErrorLogContractTest.java`
**Description**: Run contract tests from T018 and verify all pass.

**Success Criteria**: All 6 test cases pass

---

#### T023: [US1] [P] Write unit tests for request DTO validation logic

**Story**: User Story 1 (P1)
**File**: `src/test/java/com/bitbi/dfm/account/presentation/dto/CreateAccountRequestDtoTest.java` (and others)
**Description**: Write unit tests using Validator to verify DTO validation constraints work correctly.

**Test Coverage**:
- CreateAccountRequestDto: email validation, name length
- UpdateAccountRequestDto: name length
- CreateSiteRequestDto: domain pattern, displayName length
- UpdateSiteRequestDto: displayName length
- LogErrorRequestDto: type length, message length, optional metadata

**Example**:
```java
@Test
void shouldFailWhenEmailInvalid() {
    CreateAccountRequestDto dto = new CreateAccountRequestDto("invalid-email", "John Doe");
    Set<ConstraintViolation<CreateAccountRequestDto>> violations = validator.validate(dto);
    assertThat(violations).hasSize(1);
    assertThat(violations.iterator().next().getMessage()).isEqualTo("Email must be valid format");
}
```

**Success Criteria**: All validation constraints tested, 100% coverage on DTO records

---

### **Checkpoint: User Story 1 Complete**

**Verification**:
- ✅ All contract tests pass (T002, T006, T010, T014, T018)
- ✅ All unit tests pass (T023)
- ✅ 5 request DTOs created and integrated
- ✅ 5 controller methods refactored to use DTOs
- ✅ Manual validation removed from controllers
- ✅ GlobalExceptionHandler catches validation errors

**Independent Test**: Send POST/PUT requests with valid/invalid DTOs and verify validation works correctly.

---

## Phase 4: User Story 2 - Replace Output Maps with Response DTOs (Priority: P2)

**Goal**: Replace all `Map<String, Object>` responses with type-safe DTO records that provide clear API contracts.

**Independent Test**: Call GET endpoints and verify responses match documented DTO structures.

### Account Admin Controller (Response DTOs)

#### T024: [US2] [P] Write contract test for AccountWithStatsResponseDto structure

**Story**: User Story 2 (P2)
**File**: `src/test/java/com/bitbi/dfm/contract/AdminContractTest.java`
**Description**: Write MockMvc test verifying GET /api/admin/accounts/{id} returns AccountWithStatsResponseDto with all fields.

**Test Cases**:
1. GET existing account → 200 OK with all stats fields (id, email, name, isActive, createdAt, sitesCount, totalBatches, totalUploadedFiles)
2. Verify field types match DTO definition

**Success Criteria**: Test written and initially failing

---

#### T025: [US2] Create AccountWithStatsResponseDto record

**Story**: User Story 2 (P2)
**File**: `src/main/java/com/bitbi/dfm/account/presentation/dto/AccountWithStatsResponseDto.java`
**Description**: Create Java record for account details with statistics.

**Implementation**: See data-model.md for full definition

**Success Criteria**:
- Record defined with all 8 fields
- Factory method `fromEntityAndStats()` implemented
- @Schema annotations for OpenAPI documentation

---

#### T026: [US2] Refactor AccountAdminController.getAccount() to return AccountWithStatsResponseDto

**Story**: User Story 2 (P2)
**File**: `src/main/java/com/bitbi/dfm/account/presentation/AccountAdminController.java`
**Description**: Replace manual Map construction with AccountWithStatsResponseDto.fromEntityAndStats().

**Success Criteria**: Method returns `ResponseEntity<AccountWithStatsResponseDto>`, no Map construction

---

#### T027: [US2] Verify T024 contract test passes

**Story**: User Story 2 (P2)
**File**: `src/test/java/com/bitbi/dfm/contract/AdminContractTest.java`
**Description**: Run contract test from T024 and verify it passes.

**Success Criteria**: Test passes with AccountWithStatsResponseDto

---

#### T028: [US2] [P] Write contract test for AccountStatisticsDto structure

**Story**: User Story 2 (P2)
**File**: `src/test/java/com/bitbi/dfm/contract/AdminContractTest.java`
**Description**: Write MockMvc test verifying GET /api/admin/accounts/{id}/stats returns AccountStatisticsDto.

**Test Cases**:
1. GET account stats → 200 OK with all stats fields (accountId, sitesCount, activeSites, totalBatches, completedBatches, failedBatches, totalFiles, totalStorageSize)

**Success Criteria**: Test written and initially failing

---

#### T029: [US2] Create AccountStatisticsDto record

**Story**: User Story 2 (P2)
**File**: `src/main/java/com/bitbi/dfm/account/presentation/dto/AccountStatisticsDto.java`
**Description**: Create Java record for account statistics.

**Implementation**: See data-model.md for full definition

**Success Criteria**: Record defined with all 8 fields, factory method `fromStatsMap()` implemented

---

#### T030: [US2] Refactor AccountAdminController.getAccountStats() and getAccountStatistics() to return AccountStatisticsDto

**Story**: User Story 2 (P2)
**File**: `src/main/java/com/bitbi/dfm/account/presentation/AccountAdminController.java`
**Description**: Replace Map return with AccountStatisticsDto.fromStatsMap().

**Success Criteria**: Both methods return `ResponseEntity<AccountStatisticsDto>`

---

#### T031: [US2] Verify T028 contract test passes

**Story**: User Story 2 (P2)
**File**: `src/test/java/com/bitbi/dfm/contract/AdminContractTest.java`
**Description**: Run contract test from T028 and verify it passes.

**Success Criteria**: Test passes with AccountStatisticsDto

---

### Site Admin Controller (Response DTOs)

#### T032: [US2] [P] Write contract test for SiteCreationResponseDto structure

**Story**: User Story 2 (P2)
**File**: `src/test/java/com/bitbi/dfm/contract/AdminContractTest.java`
**Description**: Write MockMvc test verifying POST /api/admin/accounts/{accountId}/sites returns SiteCreationResponseDto with clientSecret.

**Test Cases**:
1. POST create site → 201 Created with all site fields + clientSecret

**Success Criteria**: Test written and initially failing

---

#### T033: [US2] Create SiteCreationResponseDto record

**Story**: User Story 2 (P2)
**File**: `src/main/java/com/bitbi/dfm/site/presentation/dto/SiteCreationResponseDto.java`
**Description**: Create Java record for site creation response including plaintext secret.

**Implementation**: See data-model.md for full definition

**Success Criteria**: Record defined with 7 fields including clientSecret, factory method implemented

---

#### T034: [US2] Refactor SiteAdminController.createSite() to return SiteCreationResponseDto

**Story**: User Story 2 (P2)
**File**: `src/main/java/com/bitbi/dfm/site/presentation/SiteAdminController.java`
**Description**: Replace manual Map construction with SiteCreationResponseDto.fromCreationResult().

**Success Criteria**: Method returns `ResponseEntity<SiteCreationResponseDto>`, no Map construction

---

#### T035: [US2] Verify T032 contract test passes

**Story**: User Story 2 (P2)
**File**: `src/test/java/com/bitbi/dfm/contract/AdminContractTest.java`
**Description**: Run contract test from T032 and verify it passes.

**Success Criteria**: Test passes with SiteCreationResponseDto

---

#### T036: [US2] [P] Write contract test for SiteStatisticsDto structure

**Story**: User Story 2 (P2)
**File**: `src/test/java/com/bitbi/dfm/contract/AdminContractTest.java`
**Description**: Write MockMvc test verifying GET /api/admin/sites/{id}/statistics returns SiteStatisticsDto.

**Success Criteria**: Test written and initially failing

---

#### T037: [US2] Create SiteStatisticsDto record

**Story**: User Story 2 (P2)
**File**: `src/main/java/com/bitbi/dfm/site/presentation/dto/SiteStatisticsDto.java`
**Description**: Create Java record for site statistics.

**Success Criteria**: Record defined with 6 fields, factory method implemented

---

#### T038: [US2] Refactor SiteAdminController.getSiteStatistics() to return SiteStatisticsDto

**Story**: User Story 2 (P2)
**File**: `src/main/java/com/bitbi/dfm/site/presentation/SiteAdminController.java`
**Description**: Replace Map return with SiteStatisticsDto.fromStatsMap().

**Success Criteria**: Method returns `ResponseEntity<SiteStatisticsDto>`

---

#### T039: [US2] Verify T036 contract test passes

**Story**: User Story 2 (P2)
**File**: `src/test/java/com/bitbi/dfm/contract/AdminContractTest.java`
**Description**: Run contract test from T036 and verify it passes.

**Success Criteria**: Test passes with SiteStatisticsDto

---

### Batch Admin Controller (Response DTOs)

#### T040: [US2] [P] Create BatchSummaryDto, BatchDetailResponseDto, UploadedFileDto records

**Story**: User Story 2 (P2)
**Files**:
- `src/main/java/com/bitbi/dfm/batch/presentation/dto/BatchSummaryDto.java`
- `src/main/java/com/bitbi/dfm/batch/presentation/dto/BatchDetailResponseDto.java`
- `src/main/java/com/bitbi/dfm/batch/presentation/dto/UploadedFileDto.java`

**Description**: Create Java records for batch responses (summary for list, detail with files).

**Success Criteria**: All 3 records defined with factory methods, @Schema annotations

---

#### T041: [US2] Refactor BatchAdminController.listBatches() to return PageResponseDto<BatchSummaryDto>

**Story**: User Story 2 (P2)
**File**: `src/main/java/com/bitbi/dfm/batch/presentation/BatchAdminController.java`
**Description**: Replace manual Map construction with BatchSummaryDto.fromEntity() in stream.

**Success Criteria**: Method returns `ResponseEntity<PageResponseDto<BatchSummaryDto>>`

---

#### T042: [US2] Refactor BatchAdminController.getBatchDetails() to return BatchDetailResponseDto

**Story**: User Story 2 (P2)
**File**: `src/main/java/com/bitbi/dfm/batch/presentation/BatchAdminController.java`
**Description**: Replace manual Map construction with BatchDetailResponseDto.fromBatchAndFiles().

**Success Criteria**: Method returns `ResponseEntity<BatchDetailResponseDto>` with nested files

---

### Error Admin Controller (Response DTOs)

#### T043: [US2] [P] Create ErrorLogSummaryDto record

**Story**: User Story 2 (P2)
**File**: `src/main/java/com/bitbi/dfm/error/presentation/dto/ErrorLogSummaryDto.java`
**Description**: Create Java record for error log list items.

**Success Criteria**: Record defined with 7 fields, factory method implemented

---

#### T044: [US2] Refactor ErrorAdminController.listErrors() to return PageResponseDto<ErrorLogSummaryDto>

**Story**: User Story 2 (P2)
**File**: `src/main/java/com/bitbi/dfm/error/presentation/ErrorAdminController.java`
**Description**: Replace manual Map construction with ErrorLogSummaryDto.fromEntity() in stream.

**Success Criteria**: Method returns `ResponseEntity<PageResponseDto<ErrorLogSummaryDto>>`

---

### **Checkpoint: User Story 2 Complete**

**Verification**:
- ✅ All contract tests pass (T024, T028, T032, T036, T040-T044 tests)
- ✅ 8 response DTOs created and integrated
- ✅ All GET endpoints return response DTOs
- ✅ All POST endpoints return response DTOs (SiteCreationResponseDto)
- ✅ No Map<String, Object> returns remain

**Independent Test**: Call GET endpoints and verify JSON responses match DTO structures.

---

## Phase 5: User Story 3 - Remove Try-Catch Blocks and Standardize Error Handling (Priority: P3)

**Goal**: Remove all try-catch blocks from controllers and delegate exception handling to GlobalExceptionHandler.

**Independent Test**: Trigger error conditions and verify GlobalExceptionHandler returns consistent ErrorResponseDto responses.

### Add Exception Handlers to GlobalExceptionHandler

#### T045: [US3] [P] Write integration test for AccountNotFoundException handling

**Story**: User Story 3 (P3)
**File**: `src/test/java/com/bitbi/dfm/integration/AdminEndpointsIntegrationTest.java`
**Description**: Write integration test verifying AccountNotFoundException returns 404 with ErrorResponseDto.

**Test Cases**:
1. GET non-existent account → 404 Not Found with ErrorResponseDto

**Success Criteria**: Test written and initially failing

---

#### T046: [US3] Add AccountNotFoundException handler to GlobalExceptionHandler

**Story**: User Story 3 (P3)
**File**: `src/main/java/com/bitbi/dfm/shared/exception/GlobalExceptionHandler.java`
**Description**: Add @ExceptionHandler for AccountService.AccountNotFoundException returning 404.

**Implementation**: See quickstart.md for pattern

**Success Criteria**: Handler catches AccountNotFoundException, returns 404 with ErrorResponseDto

---

#### T047: [US3] Verify T045 integration test passes

**Story**: User Story 3 (P3)
**File**: `src/test/java/com/bitbi/dfm/integration/AdminEndpointsIntegrationTest.java`
**Description**: Run integration test from T045 and verify it passes.

**Success Criteria**: Test passes with 404 ErrorResponseDto

---

#### T048: [US3] [P] Write integration test for AccountAlreadyExistsException handling

**Story**: User Story 3 (P3)
**File**: `src/test/java/com/bitbi/dfm/integration/AdminEndpointsIntegrationTest.java`
**Description**: Write integration test verifying AccountAlreadyExistsException returns 409.

**Success Criteria**: Test written and initially failing

---

#### T049: [US3] Add AccountAlreadyExistsException handler to GlobalExceptionHandler

**Story**: User Story 3 (P3)
**File**: `src/main/java/com/bitbi/dfm/shared/exception/GlobalExceptionHandler.java`
**Description**: Add @ExceptionHandler for AccountService.AccountAlreadyExistsException returning 409.

**Success Criteria**: Handler catches exception, returns 409 with ErrorResponseDto

---

#### T050: [US3] Verify T048 integration test passes

**Story**: User Story 3 (P3)
**File**: `src/test/java/com/bitbi/dfm/integration/AdminEndpointsIntegrationTest.java`
**Description**: Run integration test from T048 and verify it passes.

**Success Criteria**: Test passes with 409 ErrorResponseDto

---

#### T051: [US3] [P] Add SiteNotFoundException and SiteAlreadyExistsException handlers

**Story**: User Story 3 (P3)
**File**: `src/main/java/com/bitbi/dfm/shared/exception/GlobalExceptionHandler.java`
**Description**: Add @ExceptionHandler for SiteService exceptions (404 and 409).

**Success Criteria**: Both handlers added following same pattern as account exceptions

---

#### T052: [US3] [P] Add BatchNotFoundException and ErrorLogNotFoundException handlers

**Story**: User Story 3 (P3)
**File**: `src/main/java/com/bitbi/dfm/shared/exception/GlobalExceptionHandler.java`
**Description**: Add @ExceptionHandler for batch and error log not found exceptions (404).

**Success Criteria**: Both handlers added returning 404 with ErrorResponseDto

---

### Remove Try-Catch Blocks from Controllers

#### T053: [US3] [P] Remove all try-catch blocks from AccountAdminController

**Story**: User Story 3 (P3)
**File**: `src/main/java/com/bitbi/dfm/account/presentation/AccountAdminController.java`
**Description**: Remove all try-catch blocks, let exceptions propagate to GlobalExceptionHandler. Keep only service method calls.

**Before**:
```java
try {
    Account account = accountService.getAccount(accountId);
    // ...
} catch (AccountService.AccountNotFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(createErrorResponse("Account not found"));
}
```

**After**:
```java
Account account = accountService.getAccount(accountId);
// Exception automatically caught by GlobalExceptionHandler
```

**Success Criteria**:
- All try-catch blocks removed (except resource cleanup if any)
- All methods throw exceptions naturally
- `createErrorResponse()` helper method deleted

---

#### T054: [US3] [P] Remove all try-catch blocks from SiteAdminController

**Story**: User Story 3 (P3)
**File**: `src/main/java/com/bitbi/dfm/site/presentation/SiteAdminController.java`
**Description**: Remove all try-catch blocks, let exceptions propagate.

**Success Criteria**: All try-catch blocks removed, exceptions propagate to GlobalExceptionHandler

---

#### T055: [US3] [P] Remove all try-catch blocks from BatchAdminController

**Story**: User Story 3 (P3)
**File**: `src/main/java/com/bitbi/dfm/batch/presentation/BatchAdminController.java`
**Description**: Remove all try-catch blocks, let exceptions propagate.

**Success Criteria**: All try-catch blocks removed

---

#### T056: [US3] [P] Remove all try-catch blocks from ErrorAdminController

**Story**: User Story 3 (P3)
**File**: `src/main/java/com/bitbi/dfm/error/presentation/ErrorAdminController.java`
**Description**: Remove all try-catch blocks, let exceptions propagate.

**Success Criteria**: All try-catch blocks removed

---

#### T057: [US3] [P] Remove all try-catch blocks from ErrorLogController

**Story**: User Story 3 (P3)
**File**: `src/main/java/com/bitbi/dfm/error/presentation/ErrorLogController.java`
**Description**: Remove all try-catch blocks, let exceptions propagate.

**Success Criteria**: All try-catch blocks removed

---

#### T058: [US3] Run full integration test suite and verify all tests pass

**Story**: User Story 3 (P3)
**File**: `src/test/java/com/bitbi/dfm/integration/`
**Description**: Run all integration tests and verify GlobalExceptionHandler correctly handles all exceptions.

**Success Criteria**: All integration tests pass, consistent ErrorResponseDto format across all error scenarios

---

### **Checkpoint: User Story 3 Complete**

**Verification**:
- ✅ All integration tests pass (T045, T048, T058)
- ✅ 8 exception handlers added to GlobalExceptionHandler
- ✅ All try-catch blocks removed from 5 controllers
- ✅ Consistent ErrorResponseDto format across all endpoints
- ✅ No controller error handling logic remains

**Independent Test**: Trigger various error conditions and verify consistent 4xx/5xx responses.

---

## Phase 6: User Story 4 - Update OpenAPI Documentation (Priority: P4)

**Goal**: Add OpenAPI annotations to controllers and DTOs to ensure accurate Swagger documentation.

**Independent Test**: Access /swagger-ui.html and verify all endpoints show proper DTO schemas.

#### T059: [US4] [P] Add @Operation and @ApiResponses annotations to all AccountAdminController methods

**Story**: User Story 4 (P4)
**File**: `src/main/java/com/bitbi/dfm/account/presentation/AccountAdminController.java`
**Description**: Add OpenAPI annotations to document each endpoint's purpose, request/response schemas, and error codes.

**Example**:
```java
@Operation(
    summary = "Create new account",
    description = "Creates a new account with email and name. Returns account details.",
    tags = {"Admin - Accounts"}
)
@ApiResponses(value = {
    @ApiResponse(responseCode = "201", description = "Account created successfully",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = AccountResponseDto.class))),
    @ApiResponse(responseCode = "400", description = "Invalid input",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class))),
    @ApiResponse(responseCode = "409", description = "Account already exists",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponseDto.class)))
})
@PostMapping
public ResponseEntity<AccountResponseDto> createAccount(...) {
```

**Success Criteria**: All 5 methods annotated with @Operation and @ApiResponses

---

#### T060: [US4] [P] Add @Operation and @ApiResponses annotations to SiteAdminController

**Story**: User Story 4 (P4)
**File**: `src/main/java/com/bitbi/dfm/site/presentation/SiteAdminController.java`
**Description**: Add OpenAPI annotations to all site admin endpoints.

**Success Criteria**: All methods annotated following same pattern as T059

---

#### T061: [US4] [P] Add @Operation and @ApiResponses annotations to BatchAdminController

**Story**: User Story 4 (P4)
**File**: `src/main/java/com/bitbi/dfm/batch/presentation/BatchAdminController.java`
**Description**: Add OpenAPI annotations to all batch admin endpoints.

**Success Criteria**: All methods annotated

---

#### T062: [US4] [P] Add @Operation and @ApiResponses annotations to ErrorAdminController and ErrorLogController

**Story**: User Story 4 (P4)
**Files**:
- `src/main/java/com/bitbi/dfm/error/presentation/ErrorAdminController.java`
- `src/main/java/com/bitbi/dfm/error/presentation/ErrorLogController.java`

**Description**: Add OpenAPI annotations to all error logging endpoints.

**Success Criteria**: All methods annotated

---

#### T063: [US4] Manual verification of Swagger UI documentation

**Story**: User Story 4 (P4)
**Description**: Start application and access /swagger-ui.html. Verify all endpoints show:
1. Request DTO schemas with field descriptions and examples
2. Response DTO schemas with field descriptions
3. Error response schemas (ErrorResponseDto)
4. Validation constraints (email format, size limits)
5. Proper HTTP status codes (201, 400, 404, 409)

**Success Criteria**: All endpoints documented correctly in Swagger UI

---

### **Checkpoint: User Story 4 Complete**

**Verification**:
- ✅ All controller methods annotated with @Operation and @ApiResponses
- ✅ Swagger UI shows proper DTO schemas (not generic Object types)
- ✅ Request/response examples visible in documentation
- ✅ Error responses documented with ErrorResponseDto schema

**Independent Test**: Browse Swagger UI and verify developers can understand API contracts.

---

## Phase 7: Polish & Final Verification

#### T064: [Polish] Run full test suite and verify 100% pass rate

**Description**: Run all tests (contract + integration + unit) and verify zero failures.

**Command**:
```bash
./gradlew test
```

**Success Criteria**:
- All 378+ tests pass (updated with new DTOs)
- Zero test failures
- Code coverage ≥80%

---

## Task Dependencies and Parallelization

### Dependency Graph (User Story Order)

```
Phase 1 (Setup)
  T001 [Setup exception handler]
    ↓
Phase 3 (US1 - Request DTOs)
  T002 → T003 → T004 → T005 (Account create)
  T006 → T007 → T008 → T009 (Account update)
  T010 → T011 → T012 → T013 (Site create)
  T014 → T015 → T016 → T017 (Site update)
  T018 → T019 → T020 → T021 → T022 (Error logging)
  T023 [Unit tests] (parallel after all DTOs created)
    ↓
Phase 4 (US2 - Response DTOs)
  T024 → T025 → T026 → T027 (Account details)
  T028 → T029 → T030 → T031 (Account stats)
  T032 → T033 → T034 → T035 (Site creation)
  T036 → T037 → T038 → T039 (Site stats)
  T040 → T041 → T042 (Batch DTOs)
  T043 → T044 (Error DTOs)
    ↓
Phase 5 (US3 - Exception handling)
  T045 → T046 → T047 (AccountNotFoundException)
  T048 → T049 → T050 (AccountAlreadyExistsException)
  T051 [Site exceptions] (parallel)
  T052 [Batch/Error exceptions] (parallel)
  T053 [Remove try-catch Account] (parallel)
  T054 [Remove try-catch Site] (parallel)
  T055 [Remove try-catch Batch] (parallel)
  T056 [Remove try-catch ErrorAdmin] (parallel)
  T057 [Remove try-catch ErrorLog] (parallel)
  T058 [Integration tests]
    ↓
Phase 6 (US4 - OpenAPI)
  T059 [Account annotations] (parallel)
  T060 [Site annotations] (parallel)
  T061 [Batch annotations] (parallel)
  T062 [Error annotations] (parallel)
  T063 [Swagger verification]
    ↓
Phase 7 (Polish)
  T064 [Final verification]
```

### Parallel Execution Opportunities

**Phase 3 (US1)**: 5 parallel tracks (one per controller)
- Track 1: T002-T005 (Account create)
- Track 2: T006-T009 (Account update)
- Track 3: T010-T013 (Site create)
- Track 4: T014-T017 (Site update)
- Track 5: T018-T022 (Error logging)
- After all complete: T023 (unit tests)

**Phase 4 (US2)**: 6 parallel tracks
- Track 1: T024-T027 (Account details)
- Track 2: T028-T031 (Account stats)
- Track 3: T032-T035 (Site creation)
- Track 4: T036-T039 (Site stats)
- Track 5: T040-T042 (Batch DTOs)
- Track 6: T043-T044 (Error DTOs)

**Phase 5 (US3)**: 7 parallel tracks after T050
- Tracks: T051, T052, T053, T054, T055, T056, T057
- Then: T058 (integration tests)

**Phase 6 (US4)**: 4 parallel tracks
- Tracks: T059, T060, T061, T062
- Then: T063 (manual verification)

---

## Implementation Strategy

### MVP Scope (Minimum Viable Product)

**Recommended MVP**: User Story 1 only (Request DTOs)

**Rationale**:
- Delivers immediate value: type safety and validation
- Smallest independent increment
- Can be deployed and tested in production
- Foundation for remaining stories

**MVP Tasks**: T001-T023 (23 tasks)
**Estimated Effort**: 2-3 days

### Full Implementation Order

1. **Week 1**: US1 (Request DTOs) + US2 (Response DTOs)
2. **Week 2**: US3 (Exception handling) + US4 (OpenAPI documentation)
3. **Week 2**: Polish and final verification

**Total Estimated Effort**: 2 weeks

---

## Success Criteria Summary

| Criterion | Verification Method |
|-----------|---------------------|
| SC-001: Zero Map parameters in POST/PUT | Code review + grep search |
| SC-002: Zero Map returns in GET | Code review + grep search |
| SC-003: Zero try-catch blocks | Code review + grep search |
| SC-004: All exceptions handled globally | Integration test T058 passes |
| SC-005: OpenAPI shows DTO schemas | Swagger UI verification T063 |
| SC-006: 100% test pass rate | Test suite T064 passes |
| SC-007: Consistent error format | Integration tests verify ErrorResponseDto |
| SC-008: API consumers can deserialize | Contract tests verify JSON structure |

---

## Notes

- **TDD Mandatory**: Per constitution Principle III, tests must be written before implementation
- **Backward Compatibility**: JSON field names match existing Map responses (verified in research.md)
- **Coverage Target**: Maintain ≥80% code coverage (enforced by Jacoco)
- **Zero Downtime**: DTOs are JSON-compatible with existing Map responses
- **Incremental Delivery**: Each user story is independently testable and deployable

---

**Next Step**: Begin implementation with T001 (Setup exception handler), then proceed to Phase 3 (User Story 1).
