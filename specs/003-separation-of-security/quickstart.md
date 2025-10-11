# Quickstart: Security System Separation Refactoring

**Feature**: 003-separation-of-security
**Date**: 2025-10-10
**Target Audience**: Backend developers implementing the security refactoring

## Overview

This guide provides step-by-step instructions for implementing the security separation refactoring. Follow the TDD (Test-Driven Development) workflow mandated by the constitution.

**⚠️ Breaking Change Alert**: This refactoring requires coordinated client updates. See [Client Communication](#client-communication) section.

## Prerequisites

- Java 21 JDK installed
- Access to `003-separation-of-security` branch
- Familiarity with Spring Security 6 and Spring Boot 3.5.6
- Understanding of JWT and OAuth2/Keycloak authentication

## Development Workflow (TDD)

### Phase 1: Update Tests First (RED)

1. **Update integration test URLs** from `/api/v1/**` to `/api/dfc/**`:

   ```bash
   # Files to update:
   src/test/java/com/bitbi/dfm/integration/JwtOnlyWriteIntegrationTest.java
   src/test/java/com/bitbi/dfm/integration/DualAuthenticationIntegrationTest.java
   src/test/java/com/bitbi/dfm/integration/AdminEndpointsIntegrationTest.java
   ```

   **Example change**:
   ```java
   // OLD
   mockMvc.perform(post("/api/v1/batch/start")

   // NEW
   mockMvc.perform(post("/api/dfc/batch/start")
   ```

2. **Refactor TestSecurityConfig** to mirror production:

   ```java
   // src/test/java/com/bitbi/dfm/config/TestSecurityConfig.java

   @Configuration
   @EnableWebSecurity
   public class TestSecurityConfig {

       @Bean
       @Order(1)
       public SecurityFilterChain jwtFilterChain(HttpSecurity http) throws Exception {
           http
               .securityMatcher("/api/dfc/**")
               .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
               .sessionManagement(session -> session.sessionCreationPolicy(STATELESS))
               .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);
           return http.build();
       }

       @Bean
       @Order(2)
       public SecurityFilterChain keycloakFilterChain(HttpSecurity http) throws Exception {
           http
               .securityMatcher("/api/admin/**")
               .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
               .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
           return http.build();
       }

       // Preserve existing JWT filter and OAuth2 mock configuration
   }
   ```

3. **Run tests to verify they fail** (RED phase):

   ```bash
   ./gradlew test
   # Expected: Failures due to 404s (endpoints not yet migrated)
   ```

### Phase 2: Implement Controller Changes (GREEN)

4. **Update controller @RequestMapping annotations**:

   ```java
   // src/main/java/com/bitbi/dfm/batch/presentation/BatchController.java

   @RestController
   @RequestMapping("/api/dfc/batch")  // Changed from /api/v1/batch
   public class BatchController {
       // ... existing methods unchanged
   }
   ```

   **Controllers to update**:
   - `BatchController.java`: `/api/v1/batch` → `/api/dfc/batch`
   - `ErrorLogController.java`: `/api/v1/error` → `/api/dfc/error`
   - `FileUploadController.java`: `/api/v1/upload` → `/api/dfc/upload`

   **Controllers to verify (should already use `/admin/**`)**:
   - `AccountAdminController.java`: Verify uses `/admin/accounts`
   - `SiteAdminController.java`: Verify uses `/admin/sites`

5. **Refactor production SecurityConfiguration**:

   ```java
   // src/main/java/com/bitbi/dfm/shared/config/SecurityConfiguration.java

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
               .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
               .exceptionHandling(ex -> ex.authenticationEntryPoint(jwtAuthenticationEntryPoint))
               .csrf(csrf -> csrf.disable());
           return http.build();
       }

       @Bean
       @Order(2)
       public SecurityFilterChain keycloakFilterChain(HttpSecurity http) throws Exception {
           http
               .securityMatcher("/api/admin/**")
               .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
               .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
               .exceptionHandling(ex -> ex.authenticationEntryPoint(keycloakAuthenticationEntryPoint))
               .csrf(csrf -> csrf.disable());
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

       // Remove: AuthenticationManagerResolver bean
       // Remove: DualAuthenticationFilter registration
       // Preserve: JwtAuthenticationFilter bean
       // Preserve: Authentication entry points
   }
   ```

6. **Delete obsolete classes**:

   ```bash
   rm src/main/java/com/bitbi/dfm/shared/auth/DualAuthenticationFilter.java
   # Note: AuthenticationManagerResolver may be embedded in SecurityConfiguration - remove inline
   ```

7. **Run tests to verify they pass** (GREEN phase):

   ```bash
   ./gradlew test
   # Expected: 100% pass rate (389/389 tests)
   ```

### Phase 3: Refactor and Verify (REFACTOR)

8. **Enhance AuthenticationAuditLogger** to log rejected token types:

   ```java
   // src/main/java/com/bitbi/dfm/shared/auth/AuthenticationAuditLogger.java

   public void logAuthenticationFailure(HttpServletRequest request, AuthenticationException ex) {
       String tokenType = detectTokenType(request);  // "JWT" or "KEYCLOAK" or "UNKNOWN"
       String endpoint = request.getRequestURI();

       logger.warn("Authentication failure: tokenType={}, endpoint={}, method={}, status=401, reason={}",
           tokenType, endpoint, request.getMethod(), ex.getMessage());
   }

   private String detectTokenType(HttpServletRequest request) {
       String authHeader = request.getHeader("Authorization");
       if (authHeader != null && authHeader.startsWith("Bearer ")) {
           String token = authHeader.substring(7);
           // Simple heuristic: JWT has 3 parts (header.payload.signature)
           return token.split("\\.").length == 3 ? "JWT" : "KEYCLOAK";
       }
       return "UNKNOWN";
   }
   ```

9. **Run full test suite and verify coverage**:

   ```bash
   ./gradlew test jacocoTestReport
   # Check: build/reports/jacoco/test/html/index.html
   # Expected: Coverage unchanged or improved
   ```

10. **Run integration tests specifically**:

    ```bash
    ./gradlew test --tests "*IntegrationTest"
    # Expected: All integration tests pass
    ```

## Configuration Files

### application.yml (No changes required)

The existing JWT and Keycloak configuration remains unchanged:

```yaml
# JWT configuration (existing)
app:
  security:
    jwt:
      secret: ${JWT_SECRET}
      expiration: 86400000  # 24 hours

# Keycloak configuration (existing)
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI}
```

### application-test.yml (Verify test configuration)

Ensure test configuration uses same JWT secret:

```yaml
app:
  security:
    jwt:
      secret: test-secret-at-least-256-bits-long-for-hmac-sha256-algorithm
```

## Testing Checklist

Before committing, verify:

- [ ] All 389 tests pass (`./gradlew test`)
- [ ] JWT authentication works on `/api/dfc/**` endpoints
- [ ] Keycloak authentication works on `/api/admin/**` endpoints
- [ ] JWT tokens are rejected on `/api/admin/**` (403 Forbidden)
- [ ] Keycloak tokens are rejected on `/api/dfc/**` (403 Forbidden)
- [ ] Authentication failures are logged with token type
- [ ] TestSecurityConfig mirrors production SecurityConfiguration
- [ ] No dual token detection logic remains
- [ ] Code coverage ≥80% maintained

## Manual Testing

### Test JWT Authentication

```bash
# 1. Get JWT token
curl -X POST http://localhost:8080/api/v1/auth/token \
  -u "store-01.example.com:client-secret" \
  -H "Content-Type: application/json"

# 2. Test JWT on /api/dfc/** (should succeed)
curl -X POST http://localhost:8080/api/dfc/batch/start \
  -H "Authorization: Bearer <JWT_TOKEN>"

# 3. Test JWT on /api/admin/** (should return 403)
curl -X GET http://localhost:8080/api/admin/accounts \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

### Test Keycloak Authentication

```bash
# 1. Get Keycloak token (use Keycloak admin console or Postman OAuth2 flow)
KEYCLOAK_TOKEN="<YOUR_KEYCLOAK_TOKEN>"

# 2. Test Keycloak on /api/admin/** (should succeed)
curl -X GET http://localhost:8080/api/admin/accounts \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN"

# 3. Test Keycloak on /api/dfc/** (should return 403)
curl -X POST http://localhost:8080/api/dfc/batch/start \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN"
```

## Common Issues and Solutions

### Issue: Tests fail with 404 Not Found

**Cause**: Controller @RequestMapping not updated to `/api/dfc/**`

**Solution**: Verify all controllers use new URL patterns (see Step 4)

### Issue: Tests fail with 403 Forbidden

**Cause**: SecurityFilterChain order incorrect or URL pattern overlap

**Solution**: Verify @Order annotations (JWT=1, Keycloak=2, Default=3)

### Issue: Keycloak tests fail in test environment

**Cause**: TestSecurityConfig doesn't mirror production

**Solution**: Copy SecurityFilterChain structure from production to test config (see Step 2)

### Issue: "No AuthenticationProvider" error

**Cause**: Missing JwtAuthenticationFilter or OAuth2ResourceServerFilter

**Solution**: Verify filter beans are registered in SecurityConfiguration

## Client Communication

### Pre-Deployment (2 weeks before)

Send notification to all client application teams:

**Subject**: BREAKING CHANGE - API URL Migration Required

**Body**:
```
Action Required: Security Refactoring Deployment on [DATE]

All file uploader clients must update API base URLs:
- OLD: /api/v1/batch/** → NEW: /api/dfc/batch/**
- OLD: /api/v1/error/** → NEW: /api/dfc/error/**
- OLD: /api/v1/upload/** → NEW: /api/dfc/upload/**

Authentication: JWT tokens only (Keycloak tokens will return 403)

Admin UI endpoints unchanged (/api/admin/** still uses Keycloak).

Contact: support@example.com
```

### Post-Deployment Monitoring

Monitor for 24 hours:

```bash
# Check for 404 errors on old URLs
grep "404" /var/log/application.log | grep "/api/v1/"

# Check for 403 errors (wrong token type)
grep "403" /var/log/application.log | grep "AuthenticationException"

# Monitor authentication failure rate
curl http://localhost:8080/actuator/metrics/http.server.requests \
  | jq '.measurements[] | select(.statistic == "COUNT" and .tags[].value == "403")'
```

## Performance Validation

Verify authentication processing time:

```bash
# Check Micrometer metrics
curl http://localhost:8080/actuator/metrics/security.authentication.duration

# Expected: p95 < 100ms for both JWT and Keycloak
```

## Rollback Plan

If critical issues arise post-deployment:

1. Revert git commit: `git revert <commit-sha>`
2. Redeploy previous version
3. Notify clients: "Deployment postponed, continue using /api/v1/**"
4. Fix issues in `003-separation-of-security` branch
5. Re-test and re-deploy

## Next Steps

After completing this refactoring:

1. Run `/speckit.tasks` to generate detailed task breakdown
2. Create pull request for code review
3. Schedule deployment with client coordination
4. Monitor post-deployment for 24 hours

## References

- [Architecture Research](./research.md)
- [Data Model](./data-model.md)
- [JWT API Contract](./contracts/jwt-endpoints.yaml)
- [Keycloak API Contract](./contracts/keycloak-endpoints.yaml)
- [Spring Security 6 Documentation](https://docs.spring.io/spring-security/reference/)

---

**Questions?** Contact the Data Forge Team or create an issue in the project repository.
