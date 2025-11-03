package com.bitbi.dfm.shared.config;

import com.bitbi.dfm.auth.infrastructure.JwtAuthenticationFilter;
import com.bitbi.dfm.shared.auth.AuthenticationAuditLogger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Security configuration with path-based separated authentication systems (FR-005).
 *
 * <p>Implements four separate SecurityFilterChain beans with @Order precedence:</p>
 * <ul>
 *   <li><b>Order 1:</b> /api/dfc/** → JWT authentication only (Data Forge Client endpoints)</li>
 *   <li><b>Order 2:</b> /api/admin/** → Keycloak OAuth2 authentication only (Admin UI endpoints, ROLE_ADMIN)</li>
 *   <li><b>Order 3:</b> /api/sites/**, /api/account/** → Keycloak OAuth2 (User endpoints, authenticated)</li>
 *   <li><b>Order 4:</b> Default → Public endpoints + deny all others</li>
 * </ul>
 *
 * <p><b>Architecture:</b> Each filter chain operates independently. Requests are routed to the first
 * matching SecurityFilterChain based on path patterns. There is no dual authentication or token type
 * mixing - each endpoint accepts only its designated authentication mechanism.</p>
 *
 * <p><b>Audit Logging:</b> AuthenticationAuditLogger logs authentication failures with contextual
 * information (IP, endpoint, method, status, tokenType) for security monitoring.</p>
 *
 * @author Data Forge Team
 * @version 3.1.0
 * @see com.bitbi.dfm.auth.infrastructure.JwtAuthenticationFilter Custom JWT authentication
 * @see com.bitbi.dfm.shared.auth.AuthenticationAuditLogger Authentication failure logging
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Profile("!test")
public class SecurityConfiguration {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AuthenticationAuditLogger authenticationAuditLogger;

    public SecurityConfiguration(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            AuthenticationAuditLogger authenticationAuditLogger) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationAuditLogger = authenticationAuditLogger;
    }

    /**
     * JWT filter chain for Data Forge Client endpoints.
     * <p>
     * Order 1: Highest priority.
     * Matches: /api/dfc/**
     * Authentication: JWT Bearer tokens only (custom JwtAuthenticationFilter).
     * </p>
     */
    @Bean
    @Order(1)
    public SecurityFilterChain jwtFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/dfc/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    authenticationAuditLogger.onAuthenticationFailure(request, response, authException);
                    response.sendError(401, "Unauthorized - JWT authentication required");
                })
            );

        return http.build();
    }

    /**
     * Keycloak filter chain for Admin UI endpoints.
     * <p>
     * Order 2: Second priority.
     * Matches: /api/admin/**
     * Authentication: Keycloak OAuth2 Resource Server (requires ROLE_ADMIN).
     * </p>
     */
    @Bean
    @Order(2)
    public SecurityFilterChain keycloakFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/admin/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .anyRequest().hasRole("ADMIN")
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    authenticationAuditLogger.onAuthenticationFailure(request, response, authException);
                    response.sendError(401, "Unauthorized - Keycloak authentication required");
                })
            );

        return http.build();
    }

    /**
     * User filter chain for authenticated user endpoints.
     * <p>
     * Order 3: Third priority.
     * Matches: /api/sites/**, /api/account/**, /api/user/**, /api/v1/** (except /api/v1/auth/token)
     * Authentication: Keycloak OAuth2 Resource Server (any authenticated user).
     * </p>
     */
    @Bean
    @Order(3)
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
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    authenticationAuditLogger.onAuthenticationFailure(request, response, authException);
                    response.sendError(401, "Unauthorized - Authentication required");
                })
            );

        return http.build();
    }

    /**
     * Default filter chain for public and remaining endpoints.
     * <p>
     * Order 4: Lowest priority (catches all remaining requests).
     * Public access: /api/v1/auth/token, /actuator/health, /actuator/info, /swagger-ui/**, /v3/api-docs/**
     * All other requests: Denied (403).
     * </p>
     */
    @Bean
    @Order(4)
    public SecurityFilterChain defaultFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/token").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html", "/api-docs/**").permitAll()
                .anyRequest().denyAll()
            );

        return http.build();
    }

    /**
     * Convert Keycloak JWT roles to Spring Security authorities.
     * <p>
     * Custom converter to extract roles from nested 'realm_access.roles' claim.
     * Keycloak stores roles in: { "realm_access": { "roles": ["ROLE_ADMIN", ...] } }
     * </p>
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(new KeycloakRoleConverter());
        return jwtAuthenticationConverter;
    }

    /**
     * Custom converter to extract roles from Keycloak's realm_access.roles claim.
     * <p>
     * Automatically adds ROLE_ prefix if not present to ensure compatibility with
     * Spring Security's hasRole() method. Handles both formats:
     * - "ADMIN" → "ROLE_ADMIN"
     * - "ROLE_ADMIN" → "ROLE_ADMIN" (unchanged)
     * </p>
     */
    static class KeycloakRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
        @Override
        @SuppressWarnings("unchecked")
        public Collection<GrantedAuthority> convert(Jwt jwt) {
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            if (realmAccess == null || !realmAccess.containsKey("roles")) {
                return List.of();
            }

            List<String> roles = (List<String>) realmAccess.get("roles");
            if (roles == null || roles.isEmpty()) {
                return List.of();
            }

            return roles.stream()
                .map(role -> {
                    // Ensure ROLE_ prefix for Spring Security hasRole() compatibility
                    String roleName = role.startsWith("ROLE_") ? role : "ROLE_" + role;
                    return new SimpleGrantedAuthority(roleName);
                })
                .collect(Collectors.toList());
        }
    }

}
