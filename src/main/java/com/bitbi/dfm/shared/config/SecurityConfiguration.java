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
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security configuration with separated authentication systems.
 *
 * <p>Implements path-based authentication with complete separation:</p>
 * <ul>
 *   <li>/api/dfc/** → JWT authentication only (Data Forge Client endpoints)</li>
 *   <li>/api/admin/** → Keycloak OAuth2 authentication only (Admin UI endpoints)</li>
 *   <li>/api/v1/auth/token → Public endpoint (Basic Auth for token generation)</li>
 *   <li>Actuator, Swagger → Public endpoints</li>
 * </ul>
 *
 * <p>Breaking change from version 2.0.0: No longer supports dual authentication or mixed token types.</p>
 * <p>Audit logging: AuthenticationAuditLogger logs auth failures with IP, endpoint, method, status, tokenType.</p>
 *
 * @author Data Forge Team
 * @version 3.0.0
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
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    authenticationAuditLogger.onAuthenticationFailure(request, response, authException);
                    response.sendError(401, "Unauthorized - Keycloak authentication required");
                })
            );

        return http.build();
    }

    /**
     * Default filter chain for public and remaining endpoints.
     * <p>
     * Order 3: Lowest priority (catches all remaining requests).
     * Public access: /api/v1/auth/token, /actuator/health, /actuator/info, /swagger-ui/**, /v3/api-docs/**
     * All other requests: Denied (403).
     * </p>
     */
    @Bean
    @Order(3)
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
