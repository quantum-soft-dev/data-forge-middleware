package com.bitbi.dfm.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
            Map<String, Object> claims;
            List<String> roles;

            if ("mock.admin.jwt.token".equals(token)) {
                roles = List.of("ADMIN");
                claims = Map.of(
                    "sub", "admin-user",
                    "realm_access", Map.of("roles", roles),
                    "email", "admin@test.com",
                    "preferred_username", "admin"
                );
            } else if ("mock.user.jwt.token".equals(token)) {
                roles = List.of("USER");
                claims = Map.of(
                    "sub", "regular-user",
                    "realm_access", Map.of("roles", roles),
                    "email", "user@test.com",
                    "preferred_username", "user"
                );
            } else {
                // Invalid token - no roles
                claims = Map.of(
                    "sub", "unknown",
                    "realm_access", Map.of("roles", List.<String>of())
                );
            }

            return Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .header("typ", "JWT")
                    .claims(c -> c.putAll(claims))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();
        };
    }

    /**
     * Security filter chain for tests.
     * <p>
     * Simplified configuration that allows:
     * - Public access to /api/v1/auth/token, actuator, swagger
     * - ROLE_ADMIN required for /admin/**
     * - Authentication required for /api/v1/**
     * </p>
     */
    @Bean
    @Primary
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public endpoints
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/token").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()

                // Admin endpoints - require ROLE_ADMIN
                .requestMatchers("/admin/**").hasRole("ADMIN")

                // Client API endpoints - require authentication
                .requestMatchers("/api/v1/**").authenticated()

                // All other endpoints require authentication
                .anyRequest().authenticated()
            )
            // Use mocked JWT decoder
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder())));

        return http.build();
    }
}
