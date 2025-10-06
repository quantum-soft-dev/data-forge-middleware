package com.bitbi.dfm.config;

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
            // Use mocked JWT decoder with authentication converter
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder())
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())
                )
            );

        return http.build();
    }
}
