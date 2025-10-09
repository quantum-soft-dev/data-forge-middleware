# Implementation Plan: Additions to BACKEND

**Branch**: `002-additions-to-backend` | **Date**: 2025-10-09 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/002-additions-to-backend/spec.md`

## Execution Flow (/plan command scope)
```
1. Load feature spec from Input path
   → If not found: ERROR "No feature spec at {path}"
2. Fill Technical Context (scan for NEEDS CLARIFICATION)
   → Detect Project Type from file system structure or context (web=frontend+backend, mobile=app+api)
   → Set Structure Decision based on project type
3. Fill the Constitution Check section based on the content of the constitution document.
4. Evaluate Constitution Check section below
   → If violations exist: Document in Complexity Tracking
   → If no justification possible: ERROR "Simplify approach first"
   → Update Progress Tracking: Initial Constitution Check
5. Execute Phase 0 → research.md
   → If NEEDS CLARIFICATION remain: ERROR "Resolve unknowns"
6. Execute Phase 1 → contracts, data-model.md, quickstart.md, agent-specific template file
7. Re-evaluate Constitution Check section
   → If new violations: Refactor design, return to Phase 1
   → Update Progress Tracking: Post-Design Constitution Check
8. Plan Phase 2 → Describe task generation approach (DO NOT create tasks.md)
9. STOP - Ready for /tasks command
```

**IMPORTANT**: The /plan command STOPS at step 8. Phases 2-4 are executed by other commands:
- Phase 2: /tasks command creates tasks.md
- Phase 3-4: Implementation execution (manual or via tools)

## Summary
This feature adds two major improvements to the Data Forge Middleware backend:

1. **DTO Response Standardization**: Replace all `Map<String, Object>` responses with strongly-typed DTO objects using Lombok @Builder pattern for type safety, better documentation, and improved client integration
2. **Dual Authentication Support**: Configure fine-grained authentication rules where error-log, batch, and file-upload controllers support both internal JWT and Keycloak OAuth2 tokens for GET operations, while restricting write operations to internal JWT only, and maintaining Keycloak-only authentication for admin controllers

The primary goal is to improve API contract clarity and enable administrative read-only access to operational endpoints while maintaining strict write permissions for client applications.

## Technical Context
**Language/Version**: Java 21 (LTS)
**Primary Dependencies**: Spring Boot 3.5.6, Spring Security (JWT + OAuth2 Resource Server), Lombok, Jackson
**Storage**: PostgreSQL 16 (via Spring Data JPA)
**Testing**: JUnit 5, Mockito, Testcontainers, Spring MockMvc
**Target Platform**: Linux server (containerized deployment)
**Project Type**: Single backend API (monolithic Spring Boot application)
**Performance Goals**: Maintain current <1000ms p95 latency for API endpoints
**Constraints**: Zero breaking changes to response data content; immediate deployment (breaking change for authentication rules)
**Scale/Scope**: 8 controllers affected (ErrorLogController, BatchController, FileUploadController, plus 5 admin controllers); ~40 endpoint methods requiring DTO conversion

## Constitution Check
*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### ✅ Domain-Driven Design (DDD)
- **Status**: PASS
- **Rationale**: DTOs are presentation-layer concerns; no domain logic changes. Authentication rules are infrastructure/security configuration.

### ✅ Package by Layered Feature (PbLF)
- **Status**: PASS
- **Rationale**: DTOs will be added to existing `presentation` packages within each feature module (batch, error, upload, account, site). Security configuration remains in `shared/config`.

### ✅ Test-Driven Development
- **Status**: PASS
- **Rationale**: Contract tests will verify DTO structure before implementation. Integration tests will validate authentication rules. TDD workflow maintained.

### ✅ API-First Design
- **Status**: PASS
- **Rationale**: OpenAPI spec will be updated to reflect new DTO schemas. Contract tests ensure backward compatibility of response data.

### ✅ Security by Default
- **Status**: PASS (with enhancement)
- **Rationale**: Feature strengthens security by:
  - Adding dual token validation with priority rules
  - Preventing ambiguous authentication (400 on dual tokens)
  - Implementing standard audit logging (FR-013)
  - Using generic error messages to prevent information disclosure (FR-014)

### ✅ Database Optimization
- **Status**: PASS (no impact)
- **Rationale**: No database schema changes; purely application-layer refactoring.

### ✅ Observability & Monitoring
- **Status**: PASS (with enhancement)
- **Rationale**: Adds structured authentication failure logging with IP, endpoint, method, status, token type (FR-013).

### Code Quality Requirements
- **Status**: PASS
- **Details**:
  - Java 21 records for immutable DTOs
  - Lombok @Builder for conversion utilities
  - No circular dependencies (DTOs reference domain entities one-way)
  - Explicit response DTOs separate from domain entities

### Testing Requirements
- **Status**: PASS
- **Details**:
  - MockMvc contract tests for DTO schema validation
  - Spring Security Test for authentication rule verification
  - Integration tests with Testcontainers for end-to-end flows
  - Unit tests for DTO builder classes

### Security Requirements
- **Status**: PASS
- **Details**:
  - No credentials exposed
  - Token validation logic enhanced (dual token detection)
  - Audit logging includes IP and token type
  - Generic error messages prevent token type disclosure

## Project Structure

### Documentation (this feature)
```
specs/002-additions-to-backend/
├── plan.md              # This file (/plan command output)
├── research.md          # Phase 0 output (/plan command)
├── data-model.md        # Phase 1 output (/plan command)
├── quickstart.md        # Phase 1 output (/plan command)
├── contracts/           # Phase 1 output (/plan command)
│   ├── batch-dtos.yaml
│   ├── error-log-dtos.yaml
│   ├── file-upload-dtos.yaml
│   ├── account-dtos.yaml
│   ├── site-dtos.yaml
│   └── auth-security.yaml
└── tasks.md             # Phase 2 output (/tasks command - NOT created by /plan)
```

### Source Code (repository root)
```
src/main/java/com/bitbi/dfm/
├── account/
│   └── presentation/
│       ├── dto/                    # NEW: AccountResponseDto, AccountListDto
│       └── AccountAdminController.java  # MODIFIED: Use DTOs
├── site/
│   └── presentation/
│       ├── dto/                    # NEW: SiteResponseDto, SiteListDto
│       └── SiteAdminController.java     # MODIFIED: Use DTOs
├── batch/
│   └── presentation/
│       ├── dto/                    # NEW: BatchResponseDto, BatchStatusDto
│       ├── BatchController.java         # MODIFIED: Use DTOs, dual auth on GET
│       └── BatchAdminController.java    # MODIFIED: Use DTOs
├── error/
│   └── presentation/
│       ├── dto/                    # NEW: ErrorLogResponseDto, ErrorLogListDto
│       ├── ErrorLogController.java      # MODIFIED: Use DTOs, dual auth on GET
│       └── ErrorAdminController.java    # MODIFIED: Use DTOs
├── upload/
│   └── presentation/
│       ├── dto/                    # NEW: FileUploadResponseDto
│       └── FileUploadController.java    # MODIFIED: Use DTOs, dual auth on GET
├── auth/
│   └── presentation/
│       ├── dto/                    # NEW: TokenResponseDto, AuthErrorDto
│       └── AuthController.java          # MODIFIED: Use DTOs
└── shared/
    ├── config/
    │   └── SecurityConfiguration.java   # MODIFIED: Dual authentication rules
    ├── auth/
    │   ├── DualAuthenticationFilter.java  # NEW: Validates token type per endpoint
    │   └── AuthenticationAuditLogger.java # NEW: FR-013 logging
    ├── presentation/
    │   └── dto/
    │       ├── ErrorResponseDto.java    # NEW: Standardized error DTO
    │       └── PageResponseDto.java     # NEW: Paginated response wrapper
    └── exception/
        └── GlobalExceptionHandler.java  # MODIFIED: Return ErrorResponseDto

src/test/java/
├── contract/
│   ├── BatchContractTest.java          # MODIFIED: Assert DTO schemas
│   ├── ErrorLogContractTest.java       # MODIFIED: Assert DTO schemas
│   ├── FileUploadContractTest.java     # MODIFIED: Assert DTO schemas
│   ├── AccountContractTest.java        # MODIFIED: Assert DTO schemas
│   └── AuthSecurityContractTest.java   # NEW: Test dual auth rules
└── integration/
    ├── DualAuthenticationIntegrationTest.java  # NEW: End-to-end auth tests
    └── [existing tests]                        # MODIFIED: Use DTO assertions
```

**Structure Decision**: Single project (monolithic Spring Boot backend) with Package by Layered Feature organization. DTOs are added within each feature's `presentation/dto` package. Shared DTOs (ErrorResponseDto, PageResponseDto) live in `shared/presentation/dto`. Security enhancements centralized in `shared/config` and `shared/auth`.

## Phase 0: Outline & Research

### Unknowns from Technical Context
No critical unknowns remain - all technologies are already in use:
- ✅ Java 21 with Lombok @Builder
- ✅ Spring Security with dual authentication sources
- ✅ Jackson for DTO serialization
- ✅ MockMvc for contract testing
- ✅ Spring Security Test for authentication testing

### Research Tasks

1. **DTO Design Patterns with Lombok**
   - **Decision**: Use Java records for immutable DTOs with Lombok @Builder on separate builder classes
   - **Rationale**: Records provide immutability and automatic equals/hashCode/toString. Separate builder classes avoid Lombok conflicts with record constructors.
   - **Alternatives considered**:
     - Lombok @Data classes: Mutable, violates immutability principle
     - Manual builders: More boilerplate, error-prone
   - **Pattern**:
     ```java
     public record BatchResponseDto(UUID id, UUID siteId, String status, ...) {
         @Builder
         public static class BatchResponseDtoBuilder {
             public static BatchResponseDto fromEntity(Batch batch) { ... }
         }
     }
     ```

2. **Spring Security Dual Authentication Strategy**
   - **Decision**: Custom `AuthenticationManagerResolver` that inspects request method and path to select JWT vs OAuth2 authentication
   - **Rationale**: Spring Security 6+ supports method-level authentication manager selection. Avoids filter chain duplication.
   - **Alternatives considered**:
     - Dual filter chains: Complex configuration, harder to maintain
     - Custom @PreAuthorize expressions: Less type-safe, harder to test
   - **Pattern**:
     ```java
     http.authenticationManagerResolver(context -> {
         String path = context.getRequest().getRequestURI();
         String method = context.getRequest().getMethod();
         if (isClientEndpoint(path) && "GET".equals(method)) {
             return dualAuthManager; // Accept both token types
         } else if (isClientEndpoint(path)) {
             return jwtAuthManager; // JWT only
         } else {
             return oauth2AuthManager; // Keycloak only
         }
     });
     ```

3. **Dual Token Detection Strategy**
   - **Decision**: Custom `OncePerRequestFilter` that runs before authentication, checks for both `Authorization: Bearer` and `Authorization: OAuth2` headers (or single header with two token patterns)
   - **Rationale**: Early detection prevents ambiguous authentication reaching business logic
   - **Alternatives considered**:
     - Post-authentication check: Too late, one auth would already succeed
     - Custom AuthenticationProvider: Harder to integrate with existing providers
   - **Pattern**: Filter returns 400 Bad Request if both JWT and OAuth2 tokens detected

4. **Authentication Audit Logging**
   - **Decision**: Custom `AuthenticationFailureHandler` with structured SLF4J logging using MDC context
   - **Rationale**: Integrates with existing Logback/Logstash JSON logging infrastructure
   - **Alternatives considered**:
     - Spring Security event listeners: Less control over log format
     - AOP around controllers: Misses authentication layer failures
   - **Log Format**:
     ```json
     {
       "event": "auth_failure",
       "timestamp": "2025-10-09T...",
       "ip": "192.168.1.1",
       "endpoint": "/api/v1/batch/start",
       "method": "POST",
       "status": 403,
       "tokenType": "keycloak",
       "message": "Authentication failed"
     }
     ```

5. **DTO-Entity Mapping Strategy**
   - **Decision**: Static builder methods `fromEntity(Entity)` on DTO record builder classes
   - **Rationale**: Keeps mapping logic close to DTO definition, testable in isolation, no runtime mapping framework overhead
   - **Alternatives considered**:
     - MapStruct: Overkill for simple mappings, adds dependency
     - ModelMapper: Runtime reflection, performance cost
     - Service-layer mapping: Spreads DTO knowledge across layers

**Output**: All research decisions documented above (consolidated into plan.md; separate research.md not required as no external research needed)

## Phase 1: Design & Contracts

### 1. Data Model (data-model.md)

#### DTO Entities

**BatchResponseDto**
- Fields: `id` (UUID), `batchId` (UUID, alias), `siteId` (UUID), `status` (String), `s3Path` (String), `uploadedFilesCount` (Integer), `totalSize` (Long), `hasErrors` (Boolean), `startedAt` (Instant), `completedAt` (Instant, nullable)
- Validation: None (read-only response)
- Mapping: `BatchResponseDto.fromEntity(Batch)` → copies all fields, converts `BatchStatus` enum to string
- Relationships: None (flat structure)

**ErrorLogResponseDto**
- Fields: `id` (UUID), `batchId` (UUID), `severity` (String), `message` (String), `source` (String), `metadata` (Map<String, Object>), `occurredAt` (Instant)
- Validation: None (read-only response)
- Mapping: `ErrorLogResponseDto.fromEntity(ErrorLog)` → includes JSONB metadata as Map
- Relationships: References batchId but no nested object

**FileUploadResponseDto**
- Fields: `id` (UUID), `batchId` (UUID), `filename` (String), `s3Key` (String), `fileSize` (Long), `checksum` (String), `uploadedAt` (Instant)
- Validation: None (read-only response)
- Mapping: `FileUploadResponseDto.fromEntity(FileUpload)`
- Relationships: References batchId

**AccountResponseDto**
- Fields: `id` (UUID), `email` (String), `name` (String), `isActive` (Boolean), `createdAt` (Instant), `maxConcurrentBatches` (Integer)
- Validation: None (read-only response)
- Mapping: `AccountResponseDto.fromEntity(Account)` → excludes sensitive fields
- Relationships: None (admin endpoints return flat structure)

**SiteResponseDto**
- Fields: `id` (UUID), `accountId` (UUID), `domain` (String), `name` (String), `isActive` (Boolean), `createdAt` (Instant)
- Validation: None (read-only response)
- Mapping: `SiteResponseDto.fromEntity(Site)` → excludes `clientSecret`
- Relationships: References accountId

**TokenResponseDto**
- Fields: `token` (String), `expiresAt` (Instant), `siteId` (UUID), `domain` (String)
- Validation: None (read-only response)
- Mapping: `TokenResponseDto.fromToken(JwtToken)` → extracts claims
- Relationships: None

**ErrorResponseDto** (Shared)
- Fields: `timestamp` (Instant), `status` (Integer), `error` (String), `message` (String), `path` (String)
- Validation: None (error response)
- Mapping: Constructed from exception and HttpServletRequest context
- Relationships: None

**PageResponseDto<T>** (Shared Generic)
- Fields: `content` (List<T>), `page` (Integer), `size` (Integer), `totalElements` (Long), `totalPages` (Integer)
- Validation: None (wrapper)
- Mapping: `PageResponseDto.of(Page<Entity>, Function<Entity, T>)` → maps Spring Data Page
- Relationships: Generic container for paginated responses

### 2. API Contracts (contracts/)

#### batch-dtos.yaml (OpenAPI 3.0 components)
```yaml
components:
  schemas:
    BatchResponseDto:
      type: object
      required: [id, batchId, siteId, status, s3Path, uploadedFilesCount, totalSize, hasErrors, startedAt]
      properties:
        id:
          type: string
          format: uuid
        batchId:
          type: string
          format: uuid
        siteId:
          type: string
          format: uuid
        status:
          type: string
          enum: [ACTIVE, COMPLETED, FAILED, CANCELLED, EXPIRED]
        s3Path:
          type: string
        uploadedFilesCount:
          type: integer
        totalSize:
          type: integer
          format: int64
        hasErrors:
          type: boolean
        startedAt:
          type: string
          format: date-time
        completedAt:
          type: string
          format: date-time
          nullable: true
```

#### error-log-dtos.yaml
```yaml
components:
  schemas:
    ErrorLogResponseDto:
      type: object
      required: [id, batchId, severity, message, source, occurredAt]
      properties:
        id:
          type: string
          format: uuid
        batchId:
          type: string
          format: uuid
        severity:
          type: string
          enum: [INFO, WARN, ERROR, FATAL]
        message:
          type: string
        source:
          type: string
        metadata:
          type: object
          additionalProperties: true
        occurredAt:
          type: string
          format: date-time
```

#### file-upload-dtos.yaml
```yaml
components:
  schemas:
    FileUploadResponseDto:
      type: object
      required: [id, batchId, filename, s3Key, fileSize, checksum, uploadedAt]
      properties:
        id:
          type: string
          format: uuid
        batchId:
          type: string
          format: uuid
        filename:
          type: string
        s3Key:
          type: string
        fileSize:
          type: integer
          format: int64
        checksum:
          type: string
        uploadedAt:
          type: string
          format: date-time
```

#### account-dtos.yaml
```yaml
components:
  schemas:
    AccountResponseDto:
      type: object
      required: [id, email, name, isActive, createdAt, maxConcurrentBatches]
      properties:
        id:
          type: string
          format: uuid
        email:
          type: string
          format: email
        name:
          type: string
        isActive:
          type: boolean
        createdAt:
          type: string
          format: date-time
        maxConcurrentBatches:
          type: integer
```

#### site-dtos.yaml
```yaml
components:
  schemas:
    SiteResponseDto:
      type: object
      required: [id, accountId, domain, name, isActive, createdAt]
      properties:
        id:
          type: string
          format: uuid
        accountId:
          type: string
          format: uuid
        domain:
          type: string
        name:
          type: string
        isActive:
          type: boolean
        createdAt:
          type: string
          format: date-time
```

#### auth-security.yaml
```yaml
components:
  schemas:
    TokenResponseDto:
      type: object
      required: [token, expiresAt, siteId, domain]
      properties:
        token:
          type: string
        expiresAt:
          type: string
          format: date-time
        siteId:
          type: string
          format: uuid
        domain:
          type: string

    ErrorResponseDto:
      type: object
      required: [timestamp, status, error, message, path]
      properties:
        timestamp:
          type: string
          format: date-time
        status:
          type: integer
        error:
          type: string
        message:
          type: string
        path:
          type: string

  securitySchemes:
    JwtAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT
      description: Internal JWT for client applications

    KeycloakAuth:
      type: oauth2
      flows:
        clientCredentials:
          tokenUrl: https://keycloak.example.com/realms/dataforge/protocol/openid-connect/token
          scopes: {}
      description: Keycloak OAuth2 for admin access

paths:
  /api/v1/batch/{id}:
    get:
      summary: Get batch status
      security:
        - JwtAuth: []
        - KeycloakAuth: []  # Dual authentication on GET
      responses:
        '200':
          description: Batch details
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/BatchResponseDto'
        '403':
          description: Wrong token type or unauthorized
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponseDto'

  /api/v1/batch/start:
    post:
      summary: Start new batch
      security:
        - JwtAuth: []  # JWT only on POST
      responses:
        '201':
          description: Batch created
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/BatchResponseDto'
        '400':
          description: Dual tokens detected
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponseDto'
        '403':
          description: Keycloak token not allowed
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponseDto'
```

### 3. Contract Tests

#### BatchContractTest.java (Modified)
```java
@WebMvcTest(BatchController.class)
class BatchContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BatchLifecycleService batchService;

    @MockBean
    private AuthorizationHelper authHelper;

    @Test
    void startBatch_shouldReturnBatchResponseDto() throws Exception {
        // Given
        Batch mockBatch = createMockBatch();
        when(batchService.startBatch(any(), any(), any())).thenReturn(mockBatch);

        // When/Then
        mockMvc.perform(post("/api/v1/batch/start")
                .with(jwt()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isString())
            .andExpect(jsonPath("$.batchId").isString())
            .andExpect(jsonPath("$.siteId").isString())
            .andExpect(jsonPath("$.status").isString())
            .andExpect(jsonPath("$.s3Path").isString())
            .andExpect(jsonPath("$.uploadedFilesCount").isNumber())
            .andExpect(jsonPath("$.totalSize").isNumber())
            .andExpect(jsonPath("$.hasErrors").isBoolean())
            .andExpect(jsonPath("$.startedAt").isString())
            .andExpect(jsonPath("$.completedAt").doesNotExist()); // Nullable field
    }

    @Test
    void getBatch_withJwt_shouldReturnBatchResponseDto() throws Exception {
        // Given
        UUID batchId = UUID.randomUUID();
        Batch mockBatch = createMockBatch();
        when(batchService.getBatch(batchId)).thenReturn(mockBatch);

        // When/Then - JWT auth accepted on GET
        mockMvc.perform(get("/api/v1/batch/{id}", batchId)
                .with(jwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(mockBatch.getId().toString()));
    }

    @Test
    void getBatch_withKeycloak_shouldReturnBatchResponseDto() throws Exception {
        // Given
        UUID batchId = UUID.randomUUID();
        Batch mockBatch = createMockBatch();
        when(batchService.getBatch(batchId)).thenReturn(mockBatch);

        // When/Then - Keycloak auth accepted on GET
        mockMvc.perform(get("/api/v1/batch/{id}", batchId)
                .with(opaqueToken().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(mockBatch.getId().toString()));
    }

    @Test
    void startBatch_withKeycloakToken_shouldReturn403() throws Exception {
        // When/Then - Keycloak NOT accepted on POST
        mockMvc.perform(post("/api/v1/batch/start")
                .with(opaqueToken().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value(403))
            .andExpect(jsonPath("$.error").value("Forbidden"))
            .andExpect(jsonPath("$.message").value("Authentication failed")); // Generic message
    }
}
```

#### AuthSecurityContractTest.java (New)
```java
@SpringBootTest
@AutoConfigureMockMvc
class AuthSecurityContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void dualTokens_shouldReturn400() throws Exception {
        // When/Then - Both JWT and Keycloak token present
        mockMvc.perform(get("/api/v1/batch/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer jwt-token-here")
                .header("X-Keycloak-Token", "Bearer keycloak-token-here"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.message").value("Ambiguous authentication: multiple tokens provided"));
    }

    @Test
    void authFailure_shouldLogAuditEntry() throws Exception {
        // Given - capture logs
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(AuthenticationAuditLogger.class)).addAppender(logAppender);

        // When - invalid token
        mockMvc.perform(post("/api/v1/batch/start")
                .header("Authorization", "Bearer invalid-token"))
            .andExpect(status().isUnauthorized());

        // Then - verify audit log
        List<ILoggingEvent> logs = logAppender.list;
        assertThat(logs).anyMatch(log ->
            log.getMessage().contains("auth_failure") &&
            log.getMDCPropertyMap().containsKey("ip") &&
            log.getMDCPropertyMap().containsKey("endpoint") &&
            log.getMDCPropertyMap().containsKey("tokenType")
        );
    }
}
```

### 4. Test Scenarios from User Stories

**Scenario 1**: Client application with JWT retrieves batch status
- **Test**: `BatchIntegrationTest.clientJwt_getBatch_success()`
- **Steps**: Authenticate with JWT → Call GET /api/v1/batch/{id} → Assert BatchResponseDto structure → Verify all fields present

**Scenario 2**: Admin with Keycloak token retrieves batch status (read-only)
- **Test**: `BatchIntegrationTest.adminKeycloak_getBatch_success()`
- **Steps**: Authenticate with Keycloak → Call GET /api/v1/batch/{id} → Assert BatchResponseDto structure

**Scenario 3**: Admin with Keycloak token attempts to start batch (should fail)
- **Test**: `BatchIntegrationTest.adminKeycloak_startBatch_forbidden()`
- **Steps**: Authenticate with Keycloak → Call POST /api/v1/batch/start → Assert 403 Forbidden → Verify ErrorResponseDto structure

**Scenario 4**: Client with JWT uploads file
- **Test**: `FileUploadIntegrationTest.clientJwt_uploadFile_success()`
- **Steps**: Start batch with JWT → Upload file → Assert FileUploadResponseDto structure

**Scenario 5**: Request with both JWT and Keycloak tokens
- **Test**: `AuthSecurityIntegrationTest.dualTokens_rejected()`
- **Steps**: Send request with both tokens → Assert 400 Bad Request → Verify ErrorResponseDto

**Scenario 6**: Authentication failure logging
- **Test**: `AuthSecurityIntegrationTest.authFailure_logsAudit()`
- **Steps**: Send invalid token → Verify audit log entry with IP, endpoint, method, status, tokenType

### 5. Update CLAUDE.md (Agent-Specific File)

*Note: Will run `.specify/scripts/bash/update-agent-context.sh claude` after all artifacts created*

**Output**:
- ✅ data-model.md created (consolidated into plan.md above)
- ✅ contracts/ OpenAPI YAML schemas defined above
- ✅ Contract test patterns defined above
- ✅ Test scenarios mapped to user stories
- ⏳ CLAUDE.md update (deferred to script execution)

## Phase 1 Execution: Artifacts Created ✓

All Phase 1 artifacts have been successfully created:

**Created Files:**
- ✅ `data-model.md` - Complete DTO entity definitions with mappings
- ✅ `contracts/batch-dtos.yaml` - OpenAPI schema for BatchResponseDto
- ✅ `contracts/error-log-dtos.yaml` - OpenAPI schema for ErrorLogResponseDto
- ✅ `contracts/file-upload-dtos.yaml` - OpenAPI schema for FileUploadResponseDto
- ✅ `contracts/account-dtos.yaml` - OpenAPI schema for AccountResponseDto
- ✅ `contracts/site-dtos.yaml` - OpenAPI schema for SiteResponseDto
- ✅ `contracts/auth-security.yaml` - OpenAPI schemas for auth DTOs + security rules
- ✅ `quickstart.md` - End-to-end testing guide with 7 scenarios
- ✅ `CLAUDE.md` - Updated with feature context (Java 21, Spring Boot 3.5.6, PostgreSQL 16)

**Phase 1 Output Summary:**
- 6 OpenAPI contract files with complete DTO schemas
- 8 DTO entity definitions documented
- 6 test scenario specifications
- Contract test patterns defined (BatchContractTest, AuthSecurityContractTest)
- Integration test scenarios mapped to functional requirements
- Quickstart validation guide with curl examples

---

## Constitution Re-Evaluation (Post-Phase 1)

**Gate Check**: No constitutional violations introduced by Phase 1 design.

### ✅ All Constitutional Requirements Met

1. **DDD**: DTOs remain in presentation layer, domain logic unchanged
2. **PbLF**: DTOs organized within feature packages (`batch/presentation/dto`, etc.)
3. **TDD**: Contract tests defined before implementation (RED phase ready)
4. **API-First**: OpenAPI contracts created before code
5. **Security**: Dual auth design strengthens security posture
6. **Database**: No schema changes, zero impact
7. **Observability**: Audit logging specification complete

**Complexity Tracking**: No deviations to document. Design follows established patterns.

---

## Phase 2: Task Planning Approach

*This section describes what the /tasks command will do - DO NOT execute during /plan*

### Task Generation Strategy

The `/tasks` command will generate a comprehensive `tasks.md` file using the following approach:

#### 1. Load Design Artifacts
- Parse `data-model.md` for DTO entities
- Parse `contracts/*.yaml` for API schemas
- Parse `quickstart.md` for test scenarios
- Reference feature specification for functional requirements

#### 2. Generate Task Categories

**Category A: Shared Infrastructure (Foundation)**
1. Create `ErrorResponseDto` record and builder (shared/presentation/dto)
2. Create `PageResponseDto<T>` generic record (shared/presentation/dto)
3. Update `GlobalExceptionHandler` to return `ErrorResponseDto`
4. Create `DualAuthenticationFilter` for dual token detection (FR-015)
5. Create `AuthenticationAuditLogger` with MDC logging (FR-013)
6. Update `SecurityConfiguration` with `AuthenticationManagerResolver` (FR-005-009)

**Category B: Feature DTO Creation (Per Domain)**

For each domain (batch, error, upload, account, site, auth):
7-14. Create DTO record (e.g., `BatchResponseDto`)
15-22. Write DTO unit tests (`fromEntity()` mapping verification)
23-30. Update controller to return DTO instead of `Map<String, Object>`
31-38. Write contract tests for DTO schema validation

**Category C: Authentication Integration Tests**
39. Write `DualAuthenticationIntegrationTest` (GET with both tokens)
40. Write `AuthSecurityIntegrationTest` - JWT only on POST (FR-006, FR-007)
41. Write `AuthSecurityIntegrationTest` - Keycloak only on admin (FR-008, FR-009)
42. Write `AuthSecurityIntegrationTest` - Dual token detection (FR-015)
43. Write `AuthSecurityIntegrationTest` - Audit logging verification (FR-013)

**Category D: End-to-End Integration Tests**
44-50. One integration test per quickstart scenario (7 tests)

**Category E: Contract Test Updates**
51-58. Update existing contract tests to assert DTO schemas (8 controllers × various methods)

**Category F: Documentation & Validation**
59. Update Swagger OpenAPI spec with DTO schemas
60. Run quickstart.md manual validation
61. Verify code coverage ≥80%
62. Update CLAUDE.md if needed (final sync)

#### 3. Task Ordering Strategy

**Dependency-Ordered Execution:**
1. **Foundation First** (Tasks 1-6): Shared infrastructure must exist before feature DTOs
2. **DTO Creation in Parallel** (Tasks 7-38): Each domain can be implemented independently [P]
3. **Auth Tests After Security Config** (Tasks 39-43): Requires SecurityConfiguration complete
4. **Integration Tests Last** (Tasks 44-50): Require all DTOs and security complete
5. **Contract Test Updates Parallel** (Tasks 51-58): Can run alongside integration tests [P]
6. **Validation Final** (Tasks 59-62): After all implementation complete

**Parallelization Markers:**
- `[P]` tags indicate independent tasks (no shared file modifications)
- Example: All 8 DTO creations can run in parallel (different packages)
- Example: All contract test updates can run in parallel (different test files)

**TDD Compliance:**
- Every implementation task is preceded by its test task
- Order: Contract test → DTO unit test → Implementation → Integration test
- Example sequence: Task 23 (contract test) → Task 15 (unit test) → Task 7 (DTO) → Task 31 (controller) → Task 44 (integration)

#### 4. Estimated Task Count

**Total Tasks**: ~62 tasks
- Foundation: 6 tasks
- DTO Creation: 32 tasks (8 DTOs × 4 tasks each: record, unit test, controller update, contract test)
- Auth Integration: 5 tasks
- E2E Integration: 7 tasks
- Contract Updates: 8 tasks
- Documentation: 4 tasks

**Estimated Completion Time**: 8-12 hours (assuming 10-15 min per task average)

#### 5. Success Criteria Per Task

Each task in `tasks.md` will include:
- **Title**: Clear, actionable description
- **Type**: [TEST] or [CODE] or [DOC]
- **Dependencies**: List of task numbers that must complete first
- **Files**: Specific file paths to create or modify
- **Acceptance**: How to verify task completion
- **FR Mapping**: Which functional requirements (FR-XXX) this task satisfies

**Example Task Format:**
```markdown
### Task 7: Create BatchResponseDto record [P]

**Type**: [CODE]
**Dependencies**: None
**Files**:
- `src/main/java/com/bitbi/dfm/batch/presentation/dto/BatchResponseDto.java`

**Description**:
Create immutable BatchResponseDto record with fields: id, batchId, siteId, status, s3Path, uploadedFilesCount, totalSize, hasErrors, startedAt, completedAt (nullable).

Add static method `fromEntity(Batch batch)` for entity-to-DTO conversion.

**Acceptance Criteria**:
- [ ] Record compiles with all 10 fields
- [ ] `fromEntity()` method present
- [ ] CompletedAt is nullable, all others required
- [ ] Status converted to string (enum.name())

**FR Mapping**: FR-001, FR-002, FR-003
```

#### 6. Task Generation Process (Automated by /tasks command)

1. Load `.specify/templates/tasks-template.md`
2. Insert feature metadata (branch, spec path, date)
3. Generate tasks following the strategy above
4. Order by dependencies (topological sort)
5. Add parallelization markers `[P]`
6. Write to `specs/002-additions-to-backend/tasks.md`
7. Validate task count and dependency graph
8. Report completion

**IMPORTANT**: This is a planning description only. The actual `tasks.md` file will be created by the `/tasks` command, not by `/plan`.

---

## Phase 3+: Future Implementation

*These phases are beyond the scope of the /plan command*

**Phase 3**: Task execution (/implement command or manual)
- Execute tasks.md in dependency order
- Follow TDD workflow (test-first)
- Maintain 80% code coverage threshold
- Update CLAUDE.md with implementation notes

**Phase 4**: Validation & Deployment
- Run full test suite (`./gradlew test`)
- Execute quickstart.md scenarios manually
- Verify performance (p95 <1000ms maintained)
- Coordinate with API consumers (breaking change deployment)

---

## Complexity Tracking

*No complexity deviations to document. All design decisions follow constitutional principles.*

---

## Progress Tracking

**Phase Status**:
- [x] Phase 0: Research complete (/plan command)
- [x] Phase 1: Design complete (/plan command)
- [x] Phase 2: Task planning approach described (/plan command)
- [ ] Phase 3: Tasks generated (/tasks command - not yet run)
- [ ] Phase 4: Implementation complete
- [ ] Phase 5: Validation passed

**Gate Status**:
- [x] Initial Constitution Check: PASS
- [x] Post-Design Constitution Check: PASS
- [x] All NEEDS CLARIFICATION resolved
- [x] Complexity deviations documented (none)

**Artifacts Created**:
- [x] plan.md (this file)
- [x] research.md (consolidated into plan.md)
- [x] data-model.md
- [x] contracts/ (6 YAML files)
- [x] quickstart.md
- [x] CLAUDE.md (updated)
- [ ] tasks.md (created by /tasks command)

---

## Next Steps

✅ **Planning Phase Complete!**

Run `/tasks` to generate the implementation task list from these design artifacts.

The `/tasks` command will create `tasks.md` with ~62 dependency-ordered tasks following the strategy described in Phase 2 above.

---

*Based on Constitution v1.0.0 - See `.specify/memory/constitution.md`*
*Feature Specification: `specs/002-additions-to-backend/spec.md`*
*Branch: `002-additions-to-backend`*
*Date: 2025-10-09*
