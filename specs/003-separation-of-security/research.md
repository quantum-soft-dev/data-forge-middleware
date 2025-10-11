# Research: Security System Separation

**Feature**: 003-separation-of-security
**Date**: 2025-10-10
**Status**: Complete

## Overview

This document consolidates research findings for separating JWT and Keycloak authentication into independent security domains. The research focuses on Spring Security 6 filter chain patterns, URL migration strategies, and test environment parity.

## Decision 1: Security Filter Chain Architecture

**Question**: Should we use AuthenticationManagerResolver (current) or separate SecurityFilterChain beans for JWT vs Keycloak?

**Decision**: Use **separate SecurityFilterChain beans** with explicit order precedence

**Rationale**:
1. **Simpler Configuration**: Each filter chain is independently configurable without conditional logic
2. **Testability**: Test environment can mirror production by using identical filter chain structure
3. **Maintenance**: Removing `DualAuthenticationFilter` and `AuthenticationManagerResolver` reduces custom code
4. **Spring Security Best Practice**: Official Spring Security 6 documentation recommends multiple `SecurityFilterChain` beans for path-based security separation
5. **Order Control**: `@Order` annotation provides explicit precedence (JWT chain first, Keycloak second, default last)

**Alternatives Considered**:
- **AuthenticationManagerResolver (current approach)**: Requires complex conditional logic and custom filter, difficult to test
  - Rejected because: Test environment cannot easily replicate the resolver behavior, leading to 9/11 test failures
- **Single SecurityFilterChain with custom filter**: Would still require dual token detection logic
  - Rejected because: Doesn't achieve complete separation, maintains complexity

**Implementation Pattern**:
```java
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    @Order(1)
    public SecurityFilterChain jwtFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/dfc/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .sessionManagement(session -> session.sessionCreationPolicy(STATELESS))
            .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex.authenticationEntryPoint(jwtAuthenticationEntryPoint()));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain keycloakFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/admin/**")
            .authorizeHttpRequests(auth -> auth
                .anyRequest().hasRole("ADMIN"))
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(keycloakAuthenticationEntryPoint()));
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain defaultFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                .anyRequest().denyAll())
            .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
```

**References**:
- Spring Security 6.1 Documentation: Multiple SecurityFilterChain Beans
- Baeldung: Spring Security - Multiple Entry Points

---

## Decision 2: URL Pattern Migration Strategy

**Question**: How should we migrate controllers from `/api/v1/**` to `/api/dfc/**` without causing 404s?

**Decision**: **Direct cutover with breaking change** (no dual URL support)

**Rationale**:
1. **Specification Requirement**: FR-010 mandates immediate client updates with no migration period
2. **Simplified Deployment**: Single deployment eliminates the need for feature flags or dual routing
3. **Test Clarity**: Tests target only the new URLs, no conditional test logic required
4. **Reduced Risk**: Shorter migration window reduces the time dual authentication complexity exists in codebase

**Alternatives Considered**:
- **Dual URL Support (6-month migration)**: Support both `/api/v1/**` and `/api/dfc/**` simultaneously
  - Rejected because: FR-010 specifies immediate cutover, and dual support would require maintaining three security zones
- **Gradual Controller Migration**: Move controllers one-by-one over multiple releases
  - Rejected because: Increases deployment complexity and extends the period of mixed authentication patterns

**Migration Steps**:
1. Update controller `@RequestMapping` annotations from `/api/v1/batch` → `/api/dfc/batch`
2. Update test fixtures and integration tests to use new URLs
3. Deploy with coordinated client updates
4. Monitor for 404 errors in first 24 hours post-deployment

**Client Communication Template**:
```
BREAKING CHANGE: Security Refactoring - Action Required

Effective: [DEPLOYMENT_DATE]

All client applications must update API base URLs:
- OLD: https://api.example.com/api/v1/batch/start
- NEW: https://api.example.com/api/dfc/batch/start

Updated paths:
- /api/v1/batch/** → /api/dfc/batch/**
- /api/v1/error/** → /api/dfc/error/**
- /api/v1/upload/** → /api/dfc/upload/**

Authentication: JWT tokens only (Keycloak tokens will return 403)
```

---

## Decision 3: Test Environment Security Configuration

**Question**: How can TestSecurityConfig mirror production SecurityConfiguration to ensure test reliability?

**Decision**: **Refactor TestSecurityConfig to use identical SecurityFilterChain structure as production**

**Rationale**:
1. **Test Parity**: Tests validate production behavior, not a simplified test-only configuration
2. **Failure Detection**: Integration tests catch security misconfigurations before production deployment
3. **Maintainability**: Single source of truth for security configuration reduces drift over time
4. **Current Pain Point**: 9/11 test failures are directly caused by TestSecurityConfig divergence from production

**Alternatives Considered**:
- **Mock-based Security (current approach)**: Use mock authentication tokens and simplified filter chains
  - Rejected because: Does not test actual Spring Security filter chain behavior, leading to false positives
- **@WithMockUser annotations**: Spring Security testing utilities for mocking authentication
  - Rejected because: Still doesn't test actual JWT parsing, Keycloak OAuth2 validation, or filter ordering

**Implementation Approach**:
1. Remove custom `TestSecurityConfig` filter chains
2. Import production `SecurityConfiguration` into test context
3. Use Testcontainers for Keycloak mock server (or WireMock for OAuth2 JWKS endpoint)
4. Generate valid JWT tokens in test fixtures using same secret as production configuration
5. Verify filter chain order with `@Autowired FilterChainProxy` in tests

**Test Configuration Pattern**:
```java
@SpringBootTest
@ActiveProfiles("test")
@Import(SecurityConfiguration.class)  // Use production config
public class AdminEndpointsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private String generateKeycloakToken() {
        // Use testcontainers Keycloak or WireMock JWKS endpoint
        return OAuth2TestUtils.createToken()
            .claim("realm_access", Map.of("roles", List.of("ROLE_ADMIN")))
            .build();
    }

    @Test
    void listAccounts_withKeycloak_shouldReturn200() throws Exception {
        mockMvc.perform(get("/api/admin/accounts")
                .header("Authorization", "Bearer " + generateKeycloakToken()))
            .andExpect(status().isOk());
    }
}
```

**References**:
- Spring Security Testing Documentation: OAuth2 Resource Server
- Testcontainers Keycloak Module

---

## Decision 4: Dual Token Detection Removal

**Question**: Should we remove `DualAuthenticationFilter` or preserve it for backward compatibility?

**Decision**: **Remove DualAuthenticationFilter entirely**

**Rationale**:
1. **Separation Principle**: JWT and Keycloak authentication domains are now mutually exclusive
2. **Simplified Logic**: No need to detect and reject dual tokens when filter chains are path-based
3. **Performance**: Eliminates extra filter processing for every request
4. **Error Clarity**: Path-based rejection (403 Forbidden) is clearer than dual token detection (400 Bad Request)

**Alternatives Considered**:
- **Preserve Dual Token Detection**: Keep filter for requests with both JWT and Keycloak tokens
  - Rejected because: Path-based filter chains make dual tokens impossible (only one filter chain executes per request)
- **Move Logic to AuthenticationEntryPoint**: Handle dual token detection in error handler
  - Rejected because: Unnecessary complexity when filter chains already enforce single authentication type

**Removal Steps**:
1. Delete `DualAuthenticationFilter.java`
2. Remove filter registration from `SecurityConfiguration`
3. Update integration tests to remove dual token test cases (or replace with path-based tests)
4. Update `AuthenticationAuditLogger` to log rejected token types (JWT on Keycloak endpoint, vice versa)

---

## Decision 5: URL Pattern Conventions

**Question**: Why `/api/dfc/**` for data forge client endpoints?

**Decision**: Use `/api/dfc/**` prefix for JWT-authenticated client endpoints

**Rationale**:
1. **Namespace Clarity**: "dfc" = "Data Forge Client" explicitly identifies endpoints for file uploader clients
2. **Security Boundary**: Clear separation from `/api/admin/**` (Keycloak) and `/api/v1/**` (legacy dual-auth)
3. **Future Extensibility**: Additional client types (e.g., analytics clients) could use `/api/analytics/**` without confusion
4. **URL Readability**: `/api/dfc/batch/start` clearly indicates client API, not admin API

**Alternatives Considered**:
- **`/api/client/**`**: Generic "client" term is ambiguous (admin UI is also a client)
  - Rejected because: Less specific than "dfc"
- **`/api/v2/**`**: Version-based namespace
  - Rejected because: This is a refactoring, not a new API version (behavior unchanged, only authentication)
- **`/client/api/**`**: Alternative prefix order
  - Rejected because: Inconsistent with existing `/api/admin/**` pattern

---

## Technology Stack Confirmation

All required technologies are already present in the codebase:

- **Spring Boot**: 3.5.6 ✅
- **Spring Security**: 6.x (via spring-boot-starter-security) ✅
- **Spring OAuth2 Resource Server**: (via spring-boot-starter-oauth2-resource-server) ✅
- **JUnit 5**: For testing ✅
- **Mockito**: For unit tests ✅
- **Testcontainers**: For integration tests ✅

**No new dependencies required.**

---

## Performance Considerations

**Authentication Processing Time Target**: <100ms (SC-006)

**Current Baseline** (from existing dual-auth implementation):
- JWT validation: ~5-10ms (in-memory HMAC signature verification)
- Keycloak validation: ~20-50ms (JWKS fetch cached, signature verification)

**Expected Impact of Refactoring**:
- **Faster**: Removing `DualAuthenticationFilter` and `AuthenticationManagerResolver` reduces filter chain length
- **No Regression**: Separate filter chains still use same JWT/OAuth2 validation logic
- **Monitoring**: Add Micrometer timers for authentication duration per filter chain

**Optimization Notes**:
- JWT secret loaded once at startup (no per-request overhead)
- Keycloak JWKS cache TTL: 5 minutes (default Spring Security behavior)
- No database queries during authentication (all claims in token)

---

## Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| 404 errors post-deployment | High | High | Pre-deployment client notification 2 weeks in advance, monitoring dashboard |
| Test environment config drift | Medium | High | Import production SecurityConfiguration, enforce identical filter chains |
| Performance regression | Low | Medium | Load testing pre-deployment, Micrometer metrics monitoring |
| Keycloak JWKS endpoint downtime | Low | High | Circuit breaker pattern, fallback to cached JWKS |

---

## Open Questions

**None remaining** - all architecture decisions finalized.

---

## References

1. Spring Security 6 - Multiple SecurityFilterChain: https://docs.spring.io/spring-security/reference/servlet/architecture.html#servlet-securityfilterchain
2. Spring Security Testing - OAuth2: https://docs.spring.io/spring-security/reference/servlet/test/method.html#test-method-withoauth2login
3. Testcontainers Keycloak: https://java.testcontainers.org/modules/keycloak/
4. Baeldung - Spring Security Multiple Entry Points: https://www.baeldung.com/spring-security-multiple-entry-points
5. Spring Boot 3.5.6 Release Notes: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.5-Release-Notes

---

**Phase 0 Status**: ✅ Complete - All architecture decisions finalized, no NEEDS CLARIFICATION items remaining
