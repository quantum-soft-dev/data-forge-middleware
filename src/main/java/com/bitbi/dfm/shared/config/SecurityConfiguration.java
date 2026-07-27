package com.bitbi.dfm.shared.config;

import com.bitbi.dfm.auth.config.Auth0Properties;
import com.bitbi.dfm.auth.infrastructure.JwtAuthenticationFilter;
import com.bitbi.dfm.plugin.presentation.ParquetExportBasicAuthFilter;
import com.bitbi.dfm.plugin.presentation.PluginApiKeyAuthenticationFilter;
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
 * Security configuration with unified API structure (API Unification - Spec 010).
 *
 * <p><b>Unified API Structure (Post-Migration):</b></p>
 * <ul>
 *   <li><b>Order 1:</b> /api/v1/device/** → Custom JWT authentication only (Device API)</li>
 *   <li><b>Order 2:</b> /api/v1/** → Keycloak OAuth2 authentication only (UI/Admin API)</li>
 *   <li><b>Order 3:</b> Default → Public endpoints + deny all others</li>
 * </ul>
 *
 * <p><b>Legacy API Support (Pre-Migration):</b></p>
 * <ul>
 *   <li>/api/dfc/** → Custom JWT (legacy Device API paths)</li>
 * </ul>
 * <p>The {@code /api/admin/**} and {@code /api/user/**}, {@code /api/sites/**},
 * {@code /api/account/**} chains were removed: no controller ever mapped those prefixes, so they
 * now fall through to the default chain's denyAll. Their {@code /api/v1/**} successors
 * ({@code /api/v1/admin/**}, {@code /api/v1/account/**}, {@code /api/v1/sites/**}) are served by
 * the Order 5 chain and are unaffected.</p>
 *
 * <p><b>Architecture:</b> Each filter chain operates independently. Requests are routed to the first
 * matching SecurityFilterChain based on path patterns. There is no dual authentication or token type
 * mixing - each endpoint accepts only its designated authentication mechanism.</p>
 *
 * <p><b>Audit Logging:</b> AuthenticationAuditLogger logs authentication failures with contextual
 * information (IP, endpoint, method, status, tokenType) for security monitoring.</p>
 *
 * @author Data Forge Team
 * @version 4.0.0
 * @see com.bitbi.dfm.auth.infrastructure.JwtAuthenticationFilter Custom JWT authentication
 * @see com.bitbi.dfm.shared.auth.AuthenticationAuditLogger Authentication failure logging
 * @see <a href="specs/010-api-unification-goal/spec.md">API Unification Specification</a>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Profile("!test")
public class SecurityConfiguration {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final PluginApiKeyAuthenticationFilter pluginApiKeyAuthenticationFilter;
    private final ParquetExportBasicAuthFilter parquetExportBasicAuthFilter;
    private final AuthenticationAuditLogger authenticationAuditLogger;
    private final Auth0Properties auth0Properties;

    public SecurityConfiguration(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            PluginApiKeyAuthenticationFilter pluginApiKeyAuthenticationFilter,
            ParquetExportBasicAuthFilter parquetExportBasicAuthFilter,
            AuthenticationAuditLogger authenticationAuditLogger,
            Auth0Properties auth0Properties) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.pluginApiKeyAuthenticationFilter = pluginApiKeyAuthenticationFilter;
        this.parquetExportBasicAuthFilter = parquetExportBasicAuthFilter;
        this.authenticationAuditLogger = authenticationAuditLogger;
        this.auth0Properties = auth0Properties;
    }

    /**
     * Device Authorization Flow filter chain (OAuth2 protected).
     * <p>
     * <b>Order 0</b>: Highest priority - evaluated FIRST<br>
     * <b>Matches</b>: /api/v1/device/verify<br>
     * <b>Authentication</b>: Auth0 OAuth2 (user must be logged in)
     * </p>
     * <p>
     * This filter chain handles the Device Authorization Flow verification endpoint.
     * User must be authenticated via Auth0 OAuth2 to approve/deny device authorization.
     * </p>
     *
     * @since 5.0.0 (Device Authorization Flow)
     */
    @Bean
    @Order(0)
    public SecurityFilterChain deviceAuthorizationVerifyFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/v1/device/verify")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    authenticationAuditLogger.onAuthenticationFailure(request, response, authException);
                    response.sendError(401, "Unauthorized - Auth0 OAuth2 authentication required for device verification");
                })
            );

        return http.build();
    }

    /**
     * Device API filter chain (NEW unified structure).
     * <p>
     * <b>Order 1</b>: Second priority - evaluated after device authorization verify<br>
     * <b>Matches</b>: /api/v1/device/**<br>
     * <b>Authentication</b>: Custom JWT Bearer tokens only (JwtAuthenticationFilter)
     * </p>
     * <p>
     * This filter chain routes all Device API requests (IoT devices, mobile apps, data
     * collection clients) to Custom JWT authentication. Keycloak tokens will be rejected.
     * </p>
     * <p>
     * <b>Public endpoints</b>:
     * </p>
     * <ul>
     *   <li>/api/v1/device/auth/refresh - Auth V2 refresh token rotation</li>
     *   <li>/api/v1/device/authorize - Device Authorization Flow initiation</li>
     *   <li>/api/v1/device/token - Device Authorization Flow polling</li>
     * </ul>
     *
     * @since 4.0.0 (API Unification)
     */
    @Bean
    @Order(1)
    public SecurityFilterChain deviceApiFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/v1/device/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/device/auth/refresh").permitAll() // Auth V2: refresh token endpoint
                .requestMatchers(HttpMethod.POST, "/api/v1/device/authorize").permitAll() // Device Authorization Flow - initiate
                .requestMatchers(HttpMethod.POST, "/api/v1/device/token").permitAll() // Device Authorization Flow - poll
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    authenticationAuditLogger.onAuthenticationFailure(request, response, authException);
                    response.sendError(401, "Unauthorized - Custom JWT authentication required for Device API");
                })
            );

        return http.build();
    }

    /**
     * Legacy JWT filter chain for old Data Forge Client endpoints.
     * <p>
     * <b>Order 2</b>: Second priority<br>
     * <b>Matches</b>: /api/dfc/**<br>
     * <b>Authentication</b>: JWT Bearer tokens only (custom JwtAuthenticationFilter)
     * </p>
     * <p>
     * <b>DEPRECATED</b>: This filter chain supports legacy /api/dfc/** paths during
     * migration period. Will return 410 Gone after migration via DeprecatedEndpointFilter.
     * </p>
     *
     * @deprecated Use {@link #deviceApiFilterChain(HttpSecurity)} instead (Order 1)
     */
    @Bean
    @Order(2)
    @Deprecated(since = "4.0.0", forRemoval = true)
    public SecurityFilterChain legacyJwtFilterChain(HttpSecurity http) throws Exception {
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
     * Bit BI Plugin API filter chain.
     * <p>
     * <b>Order 3</b>: Third priority - evaluated AFTER legacy JWT filter chain<br>
     * <b>Matches</b>: /api/v1/plugins/bit-bi/sql-changes, /api/v1/plugins/bit-bi/sites, /api/v1/plugins/bit-bi/tables<br>
     * <b>Authentication</b>: Plugin API Key (X-Plugin-Api-Key header)
     * </p>
     * <p>
     * This filter chain handles the Bit BI Plugin API endpoints which use
     * a custom API Key authentication mechanism instead of OAuth2 or JWT.
     * API Keys are generated during plugin activation and stored in account_plugins.plugin_data.
     * </p>
     * <p>
     * <b>IMPORTANT:</b> Only specific Bit BI API endpoints use Plugin API Key auth.
     * The /api/v1/plugins/bit-bi/activate and /api/v1/plugins/bit-bi/deactivate
     * endpoints use OAuth2 (handled by Order 4 filter chain).
     * </p>
     *
     * @since 5.0.0 (Plugin SQL Generation)
     * @see PluginApiKeyAuthenticationFilter
     */
    @Bean
    @Order(3)
    public SecurityFilterChain bitBiPluginApiFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher(
                "/api/v1/plugins/bit-bi/sql-changes",
                "/api/v1/plugins/bit-bi/sites",
                "/api/v1/plugins/bit-bi/sites/**",
                "/api/v1/plugins/bit-bi/tables"
            )
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .anyRequest().authenticated()
            )
            .addFilterBefore(pluginApiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    authenticationAuditLogger.onAuthenticationFailure(request, response, authException);
                    response.sendError(401, "Unauthorized - Plugin API Key authentication required");
                })
            );

        return http.build();
    }

    /**
     * Parquet Export Plugin API filter chain (028-parquet-export-plugin).
     * <p>
     * <b>Order 4</b>: Fourth priority - evaluated AFTER Bit BI Plugin API filter chain<br>
     * <b>Matches</b>: /api/v1/plugins/parquet-export/**<br>
     * <b>Authentication</b>: HTTP Basic (/files) via ParquetExportBasicAuthFilter;
     * /download/** is anonymous — the one-time token is the credential
     * </p>
     * <p>
     * The plugin's /activate and /deactivate endpoints stay on OAuth2 (catch-all chain), same
     * split as Bit BI.
     * </p>
     *
     * @see com.bitbi.dfm.plugin.presentation.ParquetExportBasicAuthFilter
     */
    @Bean
    @Order(4)
    public SecurityFilterChain parquetExportPluginApiFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/v1/plugins/parquet-export/files", "/api/v1/plugins/parquet-export/download/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/plugins/parquet-export/download/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(parquetExportBasicAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    authenticationAuditLogger.onAuthenticationFailure(request, response, authException);
                    response.setHeader("WWW-Authenticate", "Basic realm=\"parquet-export\"");
                    response.sendError(401, "Unauthorized - Basic authentication required");
                })
            );

        return http.build();
    }

    /**
     * UI/Admin API filter chain (NEW unified structure).
     * <p>
     * <b>Order 5</b>: Fifth priority - evaluated AFTER the plugin API filter chains<br>
     * <b>Matches</b>: /api/v1/** (excluding /api/v1/device/**, /api/v1/plugins/bit-bi/** and /api/v1/plugins/parquet-export/**)<br>
     * <b>Authentication</b>: Keycloak OAuth2 Resource Server only
     * </p>
     * <p>
     * This filter chain routes all UI/Admin API requests (web dashboard, user portal,
     * admin operations) to Keycloak OAuth2 authentication. Custom JWT tokens will be rejected.
     * </p>
     * <p>
     * <b>Authorization</b>:
     * </p>
     * <ul>
     *   <li>/api/v1/accounts/** → Requires ROLE_ADMIN</li>
     *   <li>/api/v1/sites/** → Requires ROLE_ADMIN</li>
     *   <li>/api/v1/batches/** → Requires ROLE_ADMIN</li>
     *   <li>/api/v1/errors/** → Requires ROLE_ADMIN</li>
     *   <li>/api/v1/history/** → Requires authenticated user (any role)</li>
     *   <li>/api/v1/comparisons/** → Requires authenticated user (any role)</li>
     * </ul>
     *
     * @since 4.0.0 (API Unification)
     */
    @Bean
    @Order(5)
    public SecurityFilterChain adminApiFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/v1/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/device/**").denyAll() // Explicitly deny (already handled by Order 1)
                .requestMatchers("/api/v1/plugins/bit-bi/sql-changes", "/api/v1/plugins/bit-bi/sites", "/api/v1/plugins/bit-bi/sites/**", "/api/v1/plugins/bit-bi/tables").denyAll() // Explicitly deny (already handled by Order 3)
                .requestMatchers("/api/v1/plugins/parquet-export/files", "/api/v1/plugins/parquet-export/download/**").denyAll() // Explicitly deny (already handled by Order 4, 028)
                .requestMatchers("/api/v1/accounts/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/sites/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/batches/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/errors/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/admin/plugins/**").hasRole("ADMIN") // Plugin admin endpoints
                .requestMatchers("/api/v1/plugins/**").authenticated() // Plugin activation endpoints (any authenticated user)
                .requestMatchers("/api/v1/account/plugins/**").authenticated() // Account plugins list
                .requestMatchers("/api/v1/account/errors/**").authenticated() // User global errors (Dashboard)
                .requestMatchers("/api/v1/history/**").authenticated() // Any authenticated user
                .requestMatchers("/api/v1/comparisons/**").authenticated() // Any authenticated user
                .anyRequest().authenticated() // Default: require authentication
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    authenticationAuditLogger.onAuthenticationFailure(request, response, authException);
                    response.sendError(401, "Unauthorized - Keycloak OAuth2 authentication required for UI/Admin API");
                })
            );

        return http.build();
    }

    /**
     * Default filter chain for public and remaining endpoints.
     * <p>
     * <b>Order 8</b>: Lowest priority (catches all remaining requests)<br>
     * <b>Public access</b>:
     * </p>
     * <ul>
     *   <li>/api/v1/auth/token (POST) - Legacy token endpoint (deprecated; in practice shadowed by
     *       the Order 5 /api/v1/** chain, so this rule never matches)</li>
     *   <li>/actuator/health, /actuator/health/** (liveness/readiness probes), /actuator/info - Health checks</li>
     *   <li>/swagger-ui/**, /v3/api-docs/** - API documentation</li>
     * </ul>
     * <p>
     * All other requests: Denied (403 Forbidden)
     * </p>
     */
    @Bean
    @Order(8)
    public SecurityFilterChain defaultFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/token").permitAll() // Legacy token endpoint (shadowed by the Order 5 /api/v1/** chain)
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html", "/api-docs/**").permitAll()
                .anyRequest().denyAll()
            );

        return http.build();
    }

    /**
     * Convert Auth0 JWT roles to Spring Security authorities.
     * <p>
     * Custom converter to extract roles from configurable Auth0 custom claim.
     * The claim namespace is configured via AUTH0_CLAIMS_NAMESPACE environment variable.
     * Auth0 stores roles in custom claims via Auth0 Actions.
     * </p>
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(
            new Auth0RoleConverter(auth0Properties.api().rolesClaim())
        );
        return jwtAuthenticationConverter;
    }

    /**
     * Custom converter to extract roles from Auth0's custom roles claim.
     * <p>
     * Extracts roles from configurable custom claim (e.g., 'https://dev.dfm.bitbi.io/roles').
     * Automatically adds ROLE_ prefix if not present to ensure compatibility with
     * Spring Security's hasRole() method. Handles both formats:
     * - "ADMIN" → "ROLE_ADMIN"
     * - "ROLE_ADMIN" → "ROLE_ADMIN" (unchanged)
     * </p>
     * <p>
     * Falls back to Keycloak's realm_access.roles for backward compatibility during migration.
     * </p>
     */
    static class Auth0RoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
        private final String rolesClaim;

        Auth0RoleConverter(String rolesClaim) {
            this.rolesClaim = rolesClaim;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Collection<GrantedAuthority> convert(Jwt jwt) {
            // Strategy 1: Try Auth0 custom claim first (using configurable namespace)
            List<String> roles = jwt.getClaimAsStringList(rolesClaim);

            // Strategy 2: Fall back to Keycloak realm_access.roles (backward compatibility)
            if (roles == null || roles.isEmpty()) {
                Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
                if (realmAccess != null && realmAccess.containsKey("roles")) {
                    roles = (List<String>) realmAccess.get("roles");
                }
            }

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
