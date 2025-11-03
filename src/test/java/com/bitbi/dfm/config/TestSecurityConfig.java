package com.bitbi.dfm.config;

import com.bitbi.dfm.auth.application.TokenService;
import com.bitbi.dfm.auth.infrastructure.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Test security configuration that mocks OAuth2/Keycloak authentication.
 *
 * <p>This configuration mirrors the production SecurityConfiguration architecture with
 * three separate SecurityFilterChain beans, but uses mocked authentication for testing
 * without requiring a running Keycloak instance.</p>
 *
 * <p><b>Filter Chain Architecture:</b></p>
 * <ul>
 *   <li><b>Order 1:</b> /api/dfc/** → Custom JWT authentication via JwtAuthenticationFilter</li>
 *   <li><b>Order 2:</b> /api/admin/** → Mocked OAuth2 Resource Server (mock.admin.jwt.token grants ROLE_ADMIN)</li>
 *   <li><b>Order 3:</b> /api/sites**, /api/account/** → Mocked OAuth2 for user endpoints (any authenticated user)</li>
 *   <li><b>Order 4:</b> Default → Public endpoints (token generation, actuator, swagger)</li>
 * </ul>
 *
 * <p><b>Mock Token Behavior:</b></p>
 * <ul>
 *   <li>"mock.admin.jwt.token" → Grants ROLE_ADMIN for admin endpoint testing</li>
 *   <li>"mock.user.jwt.token" → Grants ROLE_USER for user endpoint testing</li>
 *   <li>Any other token → No roles (authorization will fail)</li>
 * </ul>
 *
 * @author Data Forge Team
 * @version 1.1.0
 * @see com.bitbi.dfm.shared.config.SecurityConfiguration Production security configuration
 */
@TestConfiguration
@EnableWebSecurity
@EnableMethodSecurity
public class TestSecurityConfig {

    @Autowired(required = false)
    private TokenService tokenService;

    /**
     * Mock JWT decoder for testing.
     * <p>
     * Accepts any JWT token string and creates a mock Jwt object:
     * - "mock.admin.jwt.token" → grants ROLE_ADMIN
     * - "mock.user.jwt.token" → grants ROLE_USER
     * - Any other token → no roles (will fail authorization)
     * </p>
     */
    @Bean
    @Primary
    public JwtDecoder jwtDecoder() {
        return token -> {
            List<String> roles;
            String subject;
            String email;
            String username;
            String accountId;

            if ("mock.admin.jwt.token".equals(token)) {
                roles = List.of("ADMIN");
                accountId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"; // Test account 1 from test-data.sql
                subject = accountId; // Subject is accountId for extraction
                email = "admin@test.com";
                username = "admin";
            } else if ("mock.user.jwt.token".equals(token) || "mock-jwt-token-account-1".equals(token)) {
                roles = List.of("USER");
                accountId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"; // Test account 1 from test-data.sql
                subject = accountId; // Subject is accountId for extraction
                email = "user@test.com";
                username = "user";
            } else if ("mock-jwt-token-account-2".equals(token)) {
                roles = List.of("USER");
                accountId = "b2c3d4e5-f6a7-8901-bcde-f12345678901"; // Test account 2 (different from account-1)
                subject = accountId; // Subject is accountId for extraction
                email = "user2@test.com";
                username = "user2";
            } else {
                // Invalid token - throw BadJwtException which Spring Security translates to 401
                throw new org.springframework.security.oauth2.jwt.BadJwtException("Invalid JWT token: " + token);
            }

            return Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .header("typ", "JWT")
                    .subject(subject)
                    .claim("email", email)
                    .claim("preferred_username", username)
                    .claim("accountId", accountId)
                    .claim("realm_access", Map.of("roles", roles))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();
        };
    }

    /**
     * Custom JWT authorities converter for Keycloak realm roles.
     * <p>
     * Extracts roles from nested "realm_access.roles" claim and converts them
     * to Spring Security authorities with "ROLE_" prefix.
     * </p>
     */
    private Converter<Jwt, Collection<GrantedAuthority>> jwtGrantedAuthoritiesConverter() {
        return jwt -> {
            Map<String, Object> realmAccess = jwt.getClaim("realm_access");
            if (realmAccess == null) {
                return Collections.emptyList();
            }

            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) realmAccess.get("roles");
            if (roles == null) {
                return Collections.emptyList();
            }

            return roles.stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .collect(Collectors.toList());
        };
    }

    /**
     * JWT authentication converter for tests.
     * <p>
     * Converts JWT claims to Spring Security authorities using custom converter.
     * </p>
     */
    @Bean
    @Primary
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(jwtGrantedAuthoritiesConverter());
        return jwtAuthenticationConverter;
    }

    /**
     * JWT authentication filter for test environment.
     * <p>
     * Uses real TokenService to validate JWT tokens in tests.
     * </p>
     */
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        if (tokenService == null) {
            throw new IllegalStateException("TokenService not available for JwtAuthenticationFilter");
        }
        return new JwtAuthenticationFilter(tokenService);
    }

    /**
     * Security filter chain for JWT-authenticated Data Forge Client endpoints.
     * <p>
     * Order 1: Highest priority to match /api/dfc/** first.
     * JWT tokens only - custom JwtAuthenticationFilter.
     * </p>
     */
    @Bean
    @org.springframework.core.annotation.Order(1)
    public SecurityFilterChain jwtFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/dfc/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Security filter chain for Keycloak-authenticated admin endpoints.
     * <p>
     * Order 2: Second priority to match /api/admin/** (changed from /admin/**).
     * Keycloak OAuth2 Resource Server only - ROLE_ADMIN required.
     * </p>
     */
    @Bean
    @org.springframework.core.annotation.Order(2)
    public SecurityFilterChain keycloakFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/admin/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .anyRequest().hasRole("ADMIN")
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())
                )
            );

        return http.build();
    }

    /**
     * Security filter chain for user-facing authenticated endpoints.
     * <p>
     * Order 3: Third priority to match /api/sites**, /api/account/**, /api/user/**, /api/v1/** (except /api/v1/auth/token).
     * OAuth2 Resource Server - any authenticated user allowed.
     * </p>
     */
    @Bean
    @org.springframework.core.annotation.Order(3)
    public SecurityFilterChain userFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/sites/**", "/api/account/**", "/api/user/**", "/api/v1/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/token").permitAll() // Public token endpoint
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())
                )
            );

        return http.build();
    }

    /**
     * Default security filter chain for remaining endpoints.
     * <p>
     * Order 4: Lowest priority - catches all remaining requests.
     * Allows public access to actuator, swagger, and auth token endpoint.
     * </p>
     */
    @Bean
    @org.springframework.core.annotation.Order(4)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/api-docs/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/token").permitAll()
                .anyRequest().denyAll()
            );

        return http.build();
    }
}
