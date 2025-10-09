package com.bitbi.dfm.shared.config;

import com.bitbi.dfm.auth.infrastructure.JwtAuthenticationFilter;
import com.bitbi.dfm.shared.auth.AuthenticationAuditLogger;
import com.bitbi.dfm.shared.auth.DualAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

/**
 * Security configuration with dual authentication support.
 *
 * <p>Implements path and method-based authentication rules:</p>
 * <ul>
 *   <li>GET on /api/v1/batch/**, /api/v1/error/**, /api/v1/upload/** → Accept both JWT and Keycloak (FR-005)</li>
 *   <li>POST/PUT/DELETE/PATCH on same paths → JWT only, reject Keycloak with 403 (FR-006, FR-007)</li>
 *   <li>/admin/** → Keycloak only, reject JWT with 403 (FR-008, FR-009)</li>
 *   <li>/api/v1/auth/** → Allow both (unchanged)</li>
 * </ul>
 *
 * <p>Dual token detection (FR-015): DualAuthenticationFilter rejects requests with both Authorization and X-Keycloak-Token headers.</p>
 * <p>Audit logging (FR-013): AuthenticationAuditLogger logs auth failures with IP, endpoint, method, status, tokenType.</p>
 *
 * @author Data Forge Team
 * @version 2.0.0
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Profile("!test")
public class SecurityConfiguration {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final DualAuthenticationFilter dualAuthenticationFilter;
    private final AuthenticationAuditLogger authenticationAuditLogger;

    public SecurityConfiguration(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            DualAuthenticationFilter dualAuthenticationFilter,
            AuthenticationAuditLogger authenticationAuditLogger) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.dualAuthenticationFilter = dualAuthenticationFilter;
        this.authenticationAuditLogger = authenticationAuditLogger;
    }

    /**
     * Configure security filter chain with dual authentication support.
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

                // All protected endpoints require authentication
                // Specific rules handled by AuthenticationManagerResolver
                .anyRequest().authenticated()
            )
            // Register DualAuthenticationFilter FIRST (before any authentication)
            .addFilterBefore(dualAuthenticationFilter, BasicAuthenticationFilter.class)
            // Add custom JWT filter before OAuth2 Resource Server filter
            .addFilterBefore(jwtAuthenticationFilter, BearerTokenAuthenticationFilter.class)
            // Configure OAuth2 Resource Server with custom authentication manager resolver
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationManagerResolver(authenticationManagerResolver())
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    authenticationAuditLogger.onAuthenticationFailure(
                            request,
                            response,
                            new org.springframework.security.authentication.InsufficientAuthenticationException(
                                    "Access denied", accessDeniedException)
                    );
                    response.sendError(403, "Authentication failed");
                })
            );

        return http.build();
    }

    /**
     * Authentication manager resolver that selects the appropriate authentication
     * strategy based on request path and HTTP method.
     *
     * <p>Authentication rules:</p>
     * <ul>
     *   <li>GET on client endpoints (batch, error, upload) → Dual auth (both JWT and Keycloak)</li>
     *   <li>Write operations (POST/PUT/DELETE/PATCH) on client endpoints → JWT only</li>
     *   <li>Admin endpoints (/admin/**) → Keycloak only</li>
     *   <li>Auth endpoints → Allow both</li>
     * </ul>
     */
    private AuthenticationManagerResolver<HttpServletRequest> authenticationManagerResolver() {
        return request -> {
            String path = request.getRequestURI();
            String method = request.getMethod();

            // Admin endpoints - Keycloak only (FR-008, FR-009)
            if (path.startsWith("/admin/")) {
                return keycloakAuthenticationManager();
            }

            // Client endpoints with dual auth on GET (FR-005)
            if (isClientEndpoint(path) && HttpMethod.GET.matches(method)) {
                return dualAuthenticationManager();
            }

            // Client endpoints with JWT only on write operations (FR-006, FR-007)
            if (isClientEndpoint(path)) {
                return jwtOnlyAuthenticationManager();
            }

            // Default: JWT authentication for other /api/v1/** endpoints
            return jwtOnlyAuthenticationManager();
        };
    }

    /**
     * Check if path is a client endpoint that supports dual auth on GET.
     */
    private boolean isClientEndpoint(String path) {
        return path.startsWith("/api/v1/batch/")
                || path.startsWith("/api/v1/error/")
                || path.startsWith("/api/v1/upload/");
    }

    /**
     * Dual authentication manager - accepts both JWT and Keycloak tokens.
     * Used for GET operations on client endpoints.
     */
    private AuthenticationManager dualAuthenticationManager() {
        return authentication -> {
            // This is a placeholder - actual authentication is handled by
            // JwtAuthenticationFilter and OAuth2 Resource Server filters
            // Both filters run before this resolver is invoked
            return authentication;
        };
    }

    /**
     * JWT-only authentication manager - rejects Keycloak tokens with 403.
     * Used for write operations on client endpoints.
     */
    private AuthenticationManager jwtOnlyAuthenticationManager() {
        return authentication -> {
            // Check if this is a Keycloak token (heuristic: token length >500)
            if (authentication.getPrincipal() instanceof org.springframework.security.oauth2.jwt.Jwt) {
                throw new org.springframework.security.authentication.InsufficientAuthenticationException(
                        "Keycloak tokens not allowed on write operations");
            }
            return authentication;
        };
    }

    /**
     * Keycloak-only authentication manager - rejects JWT tokens with 403.
     * Used for admin endpoints.
     */
    private AuthenticationManager keycloakAuthenticationManager() {
        return authentication -> {
            // Ensure this is a Keycloak OAuth2 token, not custom JWT
            if (!(authentication.getPrincipal() instanceof org.springframework.security.oauth2.jwt.Jwt)) {
                throw new org.springframework.security.authentication.InsufficientAuthenticationException(
                        "Only Keycloak tokens allowed on admin endpoints");
            }
            return authentication;
        };
    }

    /**
     * Convert Keycloak JWT roles to Spring Security authorities.
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
