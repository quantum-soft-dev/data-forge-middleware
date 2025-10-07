package com.bitbi.dfm.auth.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration with Keycloak OAuth2 integration.
 * <p>
 * Configures two authentication mechanisms:
 * 1. JWT tokens for client API endpoints (/api/v1/*)
 * 2. Keycloak OAuth2 for admin endpoints (/admin/*)
 * </p>
 *
 * <p>Admin endpoints require ROLE_ADMIN granted by Keycloak.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Profile("!test")
public class KeycloakSecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public KeycloakSecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    /**
     * Configure security filter chain.
     * <p>
     * Public endpoints:
     * - POST /api/v1/auth/token (Basic Auth)
     * - /actuator/health, /actuator/info
     * - /swagger-ui/**, /v3/api-docs/**
     * </p>
     * <p>
     * Protected endpoints:
     * - /api/v1/** (JWT token - custom authentication via JwtAuthenticationFilter)
     * - /admin/** (Keycloak OAuth2 with ROLE_ADMIN)
     * </p>
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public endpoints
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/token").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html", "/api-docs/**").permitAll()

                // Admin endpoints - require Keycloak ROLE_ADMIN
                .requestMatchers("/admin/**").hasRole("ADMIN")

                // Client API endpoints - require custom JWT authentication
                .requestMatchers("/api/v1/**").authenticated()

                // All other endpoints require authentication
                .anyRequest().authenticated()
            )
            // Add custom JWT filter BEFORE OAuth2 Resource Server filter
            // This ensures custom JWT tokens are validated for /api/v1/** endpoints
            .addFilterBefore(jwtAuthenticationFilter, BearerTokenAuthenticationFilter.class)
            // Keycloak OAuth2 Resource Server for admin endpoints
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            );

        return http.build();
    }

    /**
     * Convert Keycloak JWT roles to Spring Security authorities.
     * <p>
     * Maps Keycloak realm roles to ROLE_* authorities.
     * Example: "admin" role becomes "ROLE_ADMIN"
     * </p>
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");
        grantedAuthoritiesConverter.setAuthoritiesClaimName("realm_access.roles");

        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);

        return jwtAuthenticationConverter;
    }
}
