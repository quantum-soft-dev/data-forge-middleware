package com.bitbi.dfm.config;

import com.auth0.client.mgmt.ManagementAPI;
import com.bitbi.dfm.account.application.AccountSyncService;
import com.bitbi.dfm.auth.application.TokenService;
import com.bitbi.dfm.auth.infrastructure.JwtAuthenticationFilter;
import com.bitbi.dfm.shared.config.SecurityConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import com.bitbi.dfm.plugin.presentation.ParquetExportBasicAuthFilter;
import com.bitbi.dfm.plugin.presentation.PluginApiKeyAuthenticationFilter;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Test security configuration that mocks OAuth2/Auth0 authentication.
 *
 * <p>This configuration mirrors the production SecurityConfiguration architecture with
 * unified API structure, but uses mocked authentication for testing without requiring
 * a running Auth0 instance.</p>
 *
 * <p><b>Unified Filter Chain Architecture</b> (mirrors production orders):</p>
 * <ul>
 *   <li><b>Order 1:</b> /api/v1/device/** → Custom JWT authentication (Device API)</li>
 *   <li><b>Order 2:</b> /api/dfc/** → Custom JWT (legacy, deprecated)</li>
 *   <li><b>Order 3:</b> /api/v1/plugins/bit-bi/** → Plugin API key</li>
 *   <li><b>Order 4:</b> /api/v1/plugins/parquet-export/** → Basic Auth + anonymous download</li>
 *   <li><b>Order 5:</b> /api/v1/** → Mocked OAuth2 Resource Server (UI/Admin API)</li>
 *   <li><b>Order 8:</b> Default → Public endpoints (actuator, swagger) + denyAll</li>
 * </ul>
 * <p>The legacy {@code /api/admin/**} and {@code /api/sites/**}, {@code /api/account/**},
 * {@code /api/user/**} chains (Orders 6 and 7) were removed along with their production
 * counterparts — no controller ever mapped those prefixes. Orders are intentionally left with a
 * gap so the surviving chains keep their production numbering.</p>
 *
 * <p><b>Mock Token Behavior:</b></p>
 * <ul>
 *   <li>"mock.admin.jwt.token" → Grants ROLE_ADMIN for admin endpoint testing</li>
 *   <li>"mock.user.jwt.token" → Grants ROLE_USER for user endpoint testing</li>
 *   <li>Any other token → No roles (authorization will fail)</li>
 * </ul>
 *
 * <p><b>AccountSyncService:</b></p>
 * <ul>
 *   <li>Mocked by default here (required for Spring context to load)</li>
 *   <li>Integration tests needing real AccountSyncService: @Import(Auth0TestConfig.class) which provides @Primary bean</li>
 *   <li>Contract tests: configure mock behavior as needed in test setup</li>
 * </ul>
 *
 * @author Data Forge Team
 * @version 2.0.0
 * @see com.bitbi.dfm.shared.config.SecurityConfiguration Production security configuration
 * @see Auth0TestConfig Configuration for real AccountSyncService in integration tests
 */
@TestConfiguration
@EnableWebSecurity
@EnableMethodSecurity
public class TestSecurityConfig {

    /** Mirrors auth0.api.claims-namespace, the same knob production derives its roles claim from. */
    private final String claimsNamespace;

    public TestSecurityConfig(
            @org.springframework.beans.factory.annotation.Value("${auth0.api.claims-namespace}")
            String claimsNamespace) {
        this.claimsNamespace = claimsNamespace;
    }

    @Autowired(required = false)
    private TokenService tokenService;

    @Autowired(required = false)
    private PluginApiKeyAuthenticationFilter pluginApiKeyAuthenticationFilter;

    @Autowired(required = false)
    private ParquetExportBasicAuthFilter parquetExportBasicAuthFilter;

    /**
     * Mock Auth0 ManagementAPI bean for tests.
     * <p>
     * Required by Auth0TestConfig to create AccountSyncService.
     * Individual tests should configure mock behavior as needed.
     * </p>
     */
    @Bean
    public ManagementAPI managementAPI() {
        return org.mockito.Mockito.mock(ManagementAPI.class);
    }

    /**
     * Mock AccountSyncService bean for tests.
     * <p>
     * AccountSyncService is excluded from test profile (@Profile("!test")) because it requires
     * Auth0 Management API credentials. We provide a mock bean here so that controllers
     * depending on it (like AccountAdminController) can be loaded in test context.
     * Integration tests that need real AccountSyncService should import Auth0TestConfig.
     * </p>
     */
    @Bean
    public AccountSyncService accountSyncService() {
        return org.mockito.Mockito.mock(AccountSyncService.class);
    }

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
            } else if ("mock-jwt-token-no-account".equals(token)) {
                // Authenticated user with NO linked Account record (e.g. pure Auth0 admin):
                // no accountId claims, non-UUID subject, email absent from test-data.sql
                roles = List.of("USER");
                accountId = null;
                subject = "auth0|test-no-account";
                email = "no-account@test.com";
                username = "no-account";
            } else {
                // Invalid token - throw BadJwtException which Spring Security translates to 401
                throw new org.springframework.security.oauth2.jwt.BadJwtException("Invalid JWT token: " + token);
            }

            Jwt.Builder builder = Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .header("typ", "JWT")
                    .subject(subject)
                    .claim("email", email)
                    .claim("preferred_username", username)
                    .claim("https://api.test.com/email", email) // Namespaced email claim
                    .claim("https://api.test.com/roles", roles) // Namespaced roles claim
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600));

            if (accountId != null) {
                builder.claim("accountId", accountId)
                        .claim("https://api.test.com/accountId", accountId); // Namespaced claim for Auth0 (matches application-test.yml)
            }

            return builder.build();
        };
    }

    /**
     * JWT authorities converter — the production one, not a copy.
     * <p>
     * This used to be a second implementation reading Keycloak's {@code realm_access.roles}. Two
     * implementations meant the harness never exercised the real Auth0 path, which is how the
     * Keycloak fallback in {@link SecurityConfiguration.Auth0RoleConverter} survived long past the
     * migration. Reusing the production converter keeps them from drifting again.
     * </p>
     */
    private Converter<Jwt, Collection<GrantedAuthority>> jwtGrantedAuthoritiesConverter() {
        return new SecurityConfiguration.Auth0RoleConverter(claimsNamespace + "/roles");
    }

    /**
     * JWT authentication converter for tests.
     * <p>
     * Converts JWT claims to Spring Security authorities using the production converter.
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
     * Device Authorization Flow verification chain.
     * <p>
     * Order 0: mirrors production - /api/v1/device/verify is the one Device API endpoint driven by
     * the site owner in a browser, so it authenticates with Auth0 OAuth2 rather than a device JWT.
     * Without this chain the Order 1 device chain would swallow it and demand a site token.
     * </p>
     *
     * @see com.bitbi.dfm.shared.config.SecurityConfiguration#deviceAuthorizationVerifyFilterChain
     */
    @Bean
    @org.springframework.core.annotation.Order(0)
    public SecurityFilterChain deviceAuthorizationVerifyFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/v1/device/verify")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())
                )
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.sendError(401, "Unauthorized - Auth0 OAuth2 authentication required for device verification");
                })
            );

        return http.build();
    }

    /**
     * Device API filter chain (NEW unified structure).
     * <p>
     * Order 1: matches the remaining /api/v1/device/** endpoints.
     * Custom JWT tokens only via JwtAuthenticationFilter.
     * </p>
     */
    @Bean
    @org.springframework.core.annotation.Order(1)
    public SecurityFilterChain deviceApiFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/v1/device/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/device/auth/refresh").permitAll() // Auth V2: refresh token endpoint
                .requestMatchers("/api/v1/device/authorize").permitAll() // Device authorization
                .requestMatchers("/api/v1/device/token").permitAll() // Device token polling
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.sendError(401, "Unauthorized - Custom JWT authentication required for Device API");
                })
            );

        return http.build();
    }

    /**
     * Legacy JWT filter chain for old Data Forge Client endpoints.
     * <p>
     * Order 2: Second priority - matches /api/dfc/** (deprecated).
     * Custom JWT tokens only via JwtAuthenticationFilter.
     * </p>
     */
    @Bean
    @org.springframework.core.annotation.Order(2)
    public SecurityFilterChain legacyJwtFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/dfc/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.sendError(401, "Unauthorized - Custom JWT authentication required");
                })
            );

        return http.build();
    }

    /**
     * Bit BI Plugin API filter chain.
     * <p>
     * Order 3: Third priority - matches specific Bit BI Plugin API endpoints.
     * Plugin API Key authentication via PluginApiKeyAuthenticationFilter.
     * </p>
     * <p>
     * IMPORTANT: Only specific Bit BI API endpoints use Plugin API Key auth.
     * The /api/v1/plugins/bit-bi/activate and /api/v1/plugins/bit-bi/deactivate
     * endpoints use OAuth2 (handled by Order 4 filter chain).
     * </p>
     */
    @Bean
    @org.springframework.core.annotation.Order(3)
    public SecurityFilterChain bitBiPluginApiFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/v1/plugins/bit-bi/sql-changes", "/api/v1/plugins/bit-bi/sites", "/api/v1/plugins/bit-bi/sites/**", "/api/v1/plugins/bit-bi/tables")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .anyRequest().authenticated()
            )
            .addFilterBefore(pluginApiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.sendError(401, "Unauthorized - Plugin API Key authentication required");
                })
            );

        return http.build();
    }

    /**
     * Parquet Export Plugin API filter chain (028-parquet-export-plugin).
     * <p>
     * Order 4: mirrors production — Basic Auth on /files via ParquetExportBasicAuthFilter,
     * anonymous /download/** (the one-time token is the credential).
     * </p>
     */
    @Bean
    @org.springframework.core.annotation.Order(4)
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
                    response.setHeader("WWW-Authenticate", "Basic realm=\"parquet-export\"");
                    response.sendError(401, "Unauthorized - Basic authentication required");
                })
            );

        return http.build();
    }

    /**
     * UI/Admin API filter chain (NEW unified structure).
     * <p>
     * Order 5: matches /api/v1/** (excluding /api/v1/device/**, /api/v1/plugins/bit-bi/** and /api/v1/plugins/parquet-export/**).
     * Auth0 OAuth2 Resource Server only.
     * </p>
     */
    @Bean
    @org.springframework.core.annotation.Order(5)
    public SecurityFilterChain adminApiFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/v1/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/device/**").denyAll() // Explicitly deny (handled by Order 1)
                .requestMatchers("/api/v1/plugins/bit-bi/sql-changes", "/api/v1/plugins/bit-bi/sites", "/api/v1/plugins/bit-bi/sites/**", "/api/v1/plugins/bit-bi/tables").denyAll() // Explicitly deny (handled by Order 3)
                .requestMatchers("/api/v1/plugins/parquet-export/files", "/api/v1/plugins/parquet-export/download/**").denyAll() // Explicitly deny (handled by Order 4, 028)
                .requestMatchers("/api/v1/accounts/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/sites/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/batches/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/errors/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/admin/plugins/**").hasRole("ADMIN") // Plugin admin endpoints
                .requestMatchers("/api/v1/plugins/**").authenticated() // Plugin activation endpoints (any authenticated user)
                .requestMatchers("/api/v1/account/plugins/**").authenticated() // Account plugins list
                .requestMatchers("/api/v1/history/**").authenticated() // Any authenticated user
                .requestMatchers("/api/v1/comparisons/**").authenticated() // Any authenticated user
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
     * Order 8: Lowest priority - catches all remaining requests.
     * Allows public access to actuator and swagger; everything else is denied.
     * </p>
     */
    @Bean
    @org.springframework.core.annotation.Order(8)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/api-docs/**").permitAll()
                .anyRequest().denyAll()
            );

        return http.build();
    }
}
