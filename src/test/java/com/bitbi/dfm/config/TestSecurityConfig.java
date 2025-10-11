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
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Test security configuration that mocks OAuth2/Keycloak authentication.
 * <p>
 * This configuration is used in contract and integration tests to avoid
 * the need for a running Keycloak instance.
 * </p>
 * <p>
 * Mock JWT tokens are decoded to provide ROLE_ADMIN or ROLE_USER authorities
 * based on the token content.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
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

            if ("mock.admin.jwt.token".equals(token)) {
                roles = List.of("ADMIN");
                subject = "admin-user";
                email = "admin@test.com";
                username = "admin";
            } else if ("mock.user.jwt.token".equals(token)) {
                roles = List.of("USER");
                subject = "regular-user";
                email = "user@test.com";
                username = "user";
            } else {
                // Invalid token - no roles
                roles = List.of();
                subject = "unknown";
                email = "unknown@test.com";
                username = "unknown";
            }

            return Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .header("typ", "JWT")
                    .subject(subject)
                    .claim("email", email)
                    .claim("preferred_username", username)
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
            .addFilterBefore(jwtAuthenticationFilter(), BearerTokenAuthenticationFilter.class);

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
     * Default security filter chain for remaining endpoints.
     * <p>
     * Order 3: Lowest priority - catches all remaining requests.
     * Allows public access to actuator, swagger, and auth token endpoint.
     * </p>
     */
    @Bean
    @org.springframework.core.annotation.Order(3)
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
