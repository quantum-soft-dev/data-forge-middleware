# Research: API Unification

**Feature**: API Unification
**Date**: 2025-11-05
**Status**: Complete

## Overview

This document contains technical research and architectural decisions for unifying API endpoints into Device API (`/api/v1/device/*`) and UI/Admin API (`/api/v1/*`) with separate security filter chains.

---

## Decision 1: Spring Security Filter Chain Ordering

**Context**: Need to route `/api/v1/device/**` to Custom JWT filter and `/api/v1/**` to Keycloak OAuth2 filter without conflicts.

**Decision**: Use Spring Security 6's multiple `SecurityFilterChain` beans with `@Order` annotations.

**Rationale**:
- Spring Security 6 evaluates filter chains in order defined by `@Order` annotation
- First matching filter chain processes the request
- More specific patterns (`/api/v1/device/**`) must be evaluated before general patterns (`/api/v1/**`)

**Implementation Pattern**:
```java
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    @Order(1) // Evaluated FIRST
    public SecurityFilterChain deviceApiFilterChain(HttpSecurity http) throws Exception {
        return http
            .securityMatcher("/api/v1/device/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .addFilterBefore(customJwtFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    @Bean
    @Order(2) // Evaluated SECOND
    public SecurityFilterChain adminApiFilterChain(HttpSecurity http) throws Exception {
        return http
            .securityMatcher("/api/v1/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt())
            .build();
    }
}
```

**Alternatives Considered**:
- Single filter chain with conditional logic → Rejected: Complex, error-prone, harder to test
- RequestMatcher-based switching → Rejected: Less explicit, harder to debug routing issues
- Separate Spring Boot applications → Rejected: Overkill, deployment complexity, shared services needed

**Validation**: Integration tests verify correct filter selection by checking 403 Forbidden for mismatched tokens.

---

## Decision 2: Path Constants Centralization

**Context**: 40+ endpoints across multiple controllers need consistent path definitions for both controllers and tests.

**Decision**: Create `ApiRoutes.java` in `shared/api` package with public static final String constants.

**Rationale**:
- Single source of truth for all API paths
- Compile-time safety (typos caught at build time)
- Easy refactoring (change path in one place)
- Test constants match production constants (no path drift)

**Implementation Pattern**:
```java
package com.bitbi.dfm.shared.api;

public final class ApiRoutes {

    // Device API Base
    public static final String DEVICE_API_BASE = "/api/v1/device";

    // Device Auth
    public static final String DEVICE_AUTH = DEVICE_API_BASE + "/auth";
    public static final String DEVICE_AUTH_TOKEN = DEVICE_AUTH + "/token";

    // Device Batches
    public static final String DEVICE_BATCHES = DEVICE_API_BASE + "/batches";
    public static final String DEVICE_BATCHES_START = DEVICE_BATCHES + "/start";
    public static final String DEVICE_BATCHES_COMPLETE = DEVICE_BATCHES + "/{id}/complete";
    public static final String DEVICE_BATCHES_FAIL = DEVICE_BATCHES + "/{id}/fail";
    public static final String DEVICE_BATCHES_CANCEL = DEVICE_BATCHES + "/{id}/cancel";
    public static final String DEVICE_BATCHES_GET = DEVICE_BATCHES + "/{id}";

    // Device Files
    public static final String DEVICE_FILES = DEVICE_API_BASE + "/files";
    public static final String DEVICE_FILES_UPLOAD = DEVICE_FILES + "/batches/{batchId}/upload";
    public static final String DEVICE_FILES_GET = DEVICE_FILES + "/batches/{batchId}/files/{fileId}";

    // Device Errors
    public static final String DEVICE_ERRORS = DEVICE_API_BASE + "/errors";
    public static final String DEVICE_ERRORS_LOG = DEVICE_ERRORS;
    public static final String DEVICE_ERRORS_LOG_BATCH = DEVICE_ERRORS + "/batches/{batchId}";
    public static final String DEVICE_ERRORS_GET = DEVICE_ERRORS + "/{errorId}";

    // UI/Admin API Base
    public static final String ADMIN_API_BASE = "/api/v1";

    // Accounts
    public static final String ACCOUNTS = ADMIN_API_BASE + "/accounts";
    public static final String ACCOUNTS_WITH_KEYCLOAK = ACCOUNTS + "/with-keycloak";
    public static final String ACCOUNTS_ID = ACCOUNTS + "/{id}";
    public static final String ACCOUNTS_LOCK = ACCOUNTS + "/{id}/lock";
    public static final String ACCOUNTS_UNLOCK = ACCOUNTS + "/{id}/unlock";
    public static final String ACCOUNTS_RESET_PASSWORD = ACCOUNTS + "/{id}/reset-password";
    public static final String ACCOUNTS_AUDIT_LOGS = ACCOUNTS + "/{id}/audit-logs";

    // Sites
    public static final String SITES = ADMIN_API_BASE + "/sites";
    public static final String SITES_ID = SITES + "/{id}";
    public static final String SITES_STATISTICS = SITES + "/{id}/statistics";
    public static final String SITES_BY_ACCOUNT = ACCOUNTS + "/{accountId}/sites";
    public static final String SITES_ACTIVATE = ACCOUNTS + "/{accountId}/sites/{siteId}/activate";
    public static final String SITES_DEACTIVATE = ACCOUNTS + "/{accountId}/sites/{siteId}/deactivate";

    // Batches (Admin)
    public static final String BATCHES_ADMIN = ADMIN_API_BASE + "/batches";
    public static final String BATCHES_ADMIN_ID = BATCHES_ADMIN + "/{id}";

    // History (User-facing)
    public static final String HISTORY = ADMIN_API_BASE + "/history";
    public static final String HISTORY_BATCHES = HISTORY + "/batches";
    public static final String HISTORY_BATCH_ID = HISTORY_BATCHES + "/{batchId}";
    public static final String HISTORY_FILE_DOWNLOAD = HISTORY_BATCHES + "/{batchId}/files/{fileId}/download";
    public static final String HISTORY_ZIP_DOWNLOAD = HISTORY_BATCHES + "/{batchId}/download-zip";
    public static final String HISTORY_EXCEL_EXPORT = HISTORY_BATCHES + "/{batchId}/export-excel";
    public static final String HISTORY_ERRORS = HISTORY_BATCHES + "/{batchId}/errors";

    // Errors (Admin)
    public static final String ERRORS_ADMIN = ADMIN_API_BASE + "/errors";
    public static final String ERRORS_EXPORT = ERRORS_ADMIN + "/export";

    // Comparisons
    public static final String COMPARISONS = ADMIN_API_BASE + "/comparisons";
    public static final String COMPARISONS_ID = COMPARISONS + "/{id}";
    public static final String COMPARISONS_RESULTS = COMPARISONS + "/{id}/results";
    public static final String COMPARISONS_SUMMARY = COMPARISONS + "/{id}/summary";
    public static final String COMPARISONS_BY_BATCH = COMPARISONS + "/by-batch/{batchId}";
    public static final String COMPARISONS_DOWNLOAD = COMPARISONS + "/{id}/download";
    public static final String COMPARISONS_SUMMARY_DOWNLOAD = COMPARISONS + "/{id}/summary/download";

    private ApiRoutes() {
        // Utility class - no instantiation
    }
}
```

**Alternatives Considered**:
- Spring @Value properties → Rejected: Runtime only, no compile-time checking, harder to refactor
- Enum-based constants → Rejected: Unnecessary complexity, constants are simple strings
- Per-controller constants → Rejected: Duplication, path drift between controllers and tests

**Validation**: All controllers and tests import from `ApiRoutes`, compilation fails if path reference invalid.

---

## Decision 3: OpenAPI Documentation Grouping

**Context**: Swagger UI needs clear visual separation between Device API (Custom JWT) and UI/Admin API (Keycloak OAuth2).

**Decision**: Use SpringDoc OpenAPI `@Tag` annotations with distinct names and descriptions on controllers.

**Rationale**:
- Tags group endpoints logically in Swagger UI
- Security schemes already defined in OpenApiConfiguration (bearerAuth for JWT, oauth2 for Keycloak)
- Each controller maps to one API group via @Tag annotation

**Implementation Pattern**:
```java
// Device API Controller Example
@RestController
@RequestMapping(ApiRoutes.DEVICE_BATCHES)
@Tag(name = "Device API - Batches", description = "Batch management operations for client devices (Custom JWT authentication)")
@SecurityRequirement(name = "bearerAuth")
public class DeviceBatchController {
    // endpoints
}

// UI/Admin API Controller Example
@RestController
@RequestMapping(ApiRoutes.ACCOUNTS)
@Tag(name = "UI/Admin API - Accounts", description = "Account management operations for web interface (Keycloak OAuth2 authentication)")
@SecurityRequirement(name = "oauth2")
public class AccountAdminController {
    // endpoints
}
```

**OpenAPI Configuration Update**:
```java
@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Data Forge Middleware API")
                .version("v1")
                .description("Unified API with separate Device and UI/Admin endpoints"))
            .components(new Components()
                .addSecuritySchemes("bearerAuth", new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Custom JWT for device clients"))
                .addSecuritySchemes("oauth2", new SecurityScheme()
                    .type(SecurityScheme.Type.OAUTH2)
                    .flows(new OAuthFlows()
                        .authorizationCode(new OAuthFlow()
                            .authorizationUrl("/realms/dataforge/protocol/openid-connect/auth")
                            .tokenUrl("/realms/dataforge/protocol/openid-connect/token")))
                    .description("Keycloak OAuth2 for web UI/admin")));
    }

    @Bean
    public GroupedOpenApi deviceApi() {
        return GroupedOpenApi.builder()
            .group("device-api")
            .pathsToMatch("/api/v1/device/**")
            .build();
    }

    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
            .group("admin-api")
            .pathsToMatch("/api/v1/**")
            .pathsToExclude("/api/v1/device/**")
            .build();
    }
}
```

**Alternatives Considered**:
- Separate Swagger instances → Rejected: Overkill, users want unified view with clear grouping
- Custom Swagger UI theme → Rejected: Maintenance burden, tags provide sufficient separation
- No grouping → Rejected: Confusing to developers which auth method to use

**Validation**: Manual review of Swagger UI confirms Device API and UI/Admin API sections clearly separated.

---

## Decision 4: 410 Gone Response Handling

**Context**: Old endpoint paths (`/api/dfc/*`, `/api/admin/*`, `/api/user/*`) must return 410 Gone with migration guidance.

**Decision**: Create a `DeprecatedEndpointFilter` that intercepts requests to old paths and returns 410 Gone with JSON error response.

**Rationale**:
- Filter runs before security filters, preventing unnecessary authentication attempts
- Consistent error format matching existing `ErrorResponseDto`
- Easy to enable/disable via configuration property
- Can be removed entirely after migration period

**Implementation Pattern**:
```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // Run before security filters
public class DeprecatedEndpointFilter extends OncePerRequestFilter {

    private static final Map<String, String> DEPRECATED_PATHS = Map.of(
        "/api/dfc/batch/start", "/api/v1/device/batches/start",
        "/api/dfc/batch/{id}/complete", "/api/v1/device/batches/{id}/complete",
        "/api/admin/accounts", "/api/v1/accounts",
        "/api/user/batches", "/api/v1/history/batches"
        // ... all old → new mappings
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String requestPath = request.getRequestURI();

        String newPath = DEPRECATED_PATHS.entrySet().stream()
            .filter(entry -> matchesPattern(requestPath, entry.getKey()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);

        if (newPath != null) {
            response.setStatus(HttpStatus.GONE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);

            ErrorResponseDto error = new ErrorResponseDto(
                Instant.now(),
                410,
                "Gone",
                String.format("This endpoint has been removed. Please use: %s", newPath),
                requestPath
            );

            response.getWriter().write(new ObjectMapper().writeValueAsString(error));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean matchesPattern(String requestPath, String pattern) {
        // Simple pattern matching for path variables
        String regex = pattern.replaceAll("\\{[^}]+\\}", "[^/]+");
        return requestPath.matches(regex);
    }
}
```

**Alternatives Considered**:
- Controller methods for old paths → Rejected: Pollutes new controllers with deprecated code
- Global exception handler → Rejected: Requires controllers to exist, defeats purpose of deletion
- No 410 Gone support → Rejected: Spec requires migration guidance

**Validation**: Contract tests verify 410 Gone response with correct JSON structure and new path guidance.

---

## Decision 5: Test Update Strategy

**Context**: 40+ endpoints migrated means dozens of test files need path updates.

**Decision**: Three-phase test update approach using ApiRoutes constants:

**Phase 1: Contract Tests**
- Update all MockMvc `.perform(get(...))` calls to use `ApiRoutes` constants
- Verify response contracts unchanged (same DTOs, same validation)
- Example: `.perform(get(ApiRoutes.ACCOUNTS))` instead of `"/api/admin/accounts"`

**Phase 2: Integration Tests**
- Update Testcontainers-based tests to use new paths
- Verify end-to-end flows work with new security filter routing
- Test both Device API (Custom JWT) and UI/Admin API (Keycloak) auth flows

**Phase 3: Security Tests**
- New: `SecurityFilterChainTest` - Unit tests for filter chain selection logic
- New: `SecurityIntegrationTest` - Integration tests for token rejection (403 Forbidden for mismatched tokens)

**Rationale**:
- Contract tests catch API breakage first (fastest feedback)
- Integration tests catch security/routing issues (most critical)
- Security-specific tests ensure authentication routing correctness

**Alternatives Considered**:
- Update all tests simultaneously → Rejected: High risk, hard to debug failures
- No test updates (create new tests) → Rejected: Old tests would fail, doesn't verify parity
- Manual path strings in tests → Rejected: Path drift risk, refactoring difficulty

**Validation**: Full test suite passes with `./gradlew test integrationTest`, coverage ≥80%.

---

## Decision 6: Controller Delegation Pattern

**Context**: New Device API controllers must delegate to existing service layer without duplicating business logic.

**Decision**: Direct service injection and method delegation in new controllers.

**Implementation Pattern**:
```java
@RestController
@RequestMapping(ApiRoutes.DEVICE_BATCHES)
@Tag(name = "Device API - Batches", description = "...")
@SecurityRequirement(name = "bearerAuth")
public class DeviceBatchController {

    private final BatchService batchService; // Existing service

    public DeviceBatchController(BatchService batchService) {
        this.batchService = batchService;
    }

    @PostMapping(ApiRoutes.DEVICE_BATCHES_START)
    public ResponseEntity<BatchResponseDto> startBatch(@RequestBody @Valid StartBatchRequestDto request) {
        // Direct delegation - NO new business logic
        Batch batch = batchService.startBatch(request.siteId());
        return ResponseEntity.status(HttpStatus.CREATED).body(BatchResponseDto.fromEntity(batch));
    }
}
```

**Rationale**:
- Zero business logic duplication
- Services already tested via old controllers
- New controllers are thin adapters (presentation layer only)
- Easy to verify functional parity

**Alternatives Considered**:
- New service layer for Device API → Rejected: Unnecessary duplication, violates DRY
- Shared base controller → Rejected: Premature abstraction, complicates testing
- Facade service wrapping existing services → Rejected: Extra layer without benefit

**Validation**: New controllers covered by contract tests verifying identical behavior to old endpoints.

---

## Summary of Architectural Decisions

| Decision | Approach | Rationale | Validation |
|----------|----------|-----------|------------|
| **Security Filter Chains** | Dual `SecurityFilterChain` beans with `@Order(1)` and `@Order(2)` | Explicit routing, testable, Spring Security 6 best practice | Integration tests verify 403 for mismatched tokens |
| **Path Constants** | `ApiRoutes.java` with `public static final String` | Single source of truth, compile-time safety, easy refactoring | Compilation fails on invalid references |
| **OpenAPI Grouping** | `@Tag` annotations + `GroupedOpenApi` beans | Clear Swagger UI separation, existing security schemes | Manual Swagger UI review |
| **410 Gone Handling** | `DeprecatedEndpointFilter` with path mapping | Runs before security, consistent error format, removable | Contract tests for 410 responses |
| **Test Updates** | 3-phase: Contract → Integration → Security | Prioritize fastest feedback, catch auth issues early | Full test suite passes with ≥80% coverage |
| **Controller Delegation** | Direct service injection in new controllers | Zero duplication, thin adapter pattern, DDD compliance | Contract tests verify parity |

---

## Implementation Risks & Mitigations

### Risk 1: Filter Chain Order Misconfiguration
**Mitigation**: Use explicit `@Order` annotations, write integration tests that verify correct filter selection before implementing controllers.

### Risk 2: Path Constant Drift
**Mitigation**: All controllers and tests import from `ApiRoutes`, compilation enforces consistency.

### Risk 3: Test Update Errors
**Mitigation**: Update tests in phases (contract → integration → security), verify each phase passes before proceeding.

### Risk 4: Business Logic Duplication
**Mitigation**: New controllers delegate to existing services only, no new business logic allowed in controllers. Code review enforces this.

---

## Next Steps (Phase 1: Design & Contracts)

1. Create `data-model.md` - Document that no new entities are introduced (refactoring only)
2. Generate OpenAPI contract specifications in `/contracts` directory
3. Create `quickstart.md` - Migration guide with old → new endpoint mappings
4. Update agent context with new architectural decisions

**Status**: Research Complete ✅
