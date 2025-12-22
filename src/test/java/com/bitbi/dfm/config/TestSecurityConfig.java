package com.bitbi.dfm.config;

import com.auth0.client.mgmt.ManagementAPI;
import com.bitbi.dfm.account.application.AccountSyncService;
import com.bitbi.dfm.auth.application.TokenService;
import com.bitbi.dfm.auth.infrastructure.JwtAuthenticationFilter;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import com.bitbi.dfm.plugin.presentation.PluginApiKeyAuthenticationFilter;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Test security configuration that mocks OAuth2/Auth0 authentication.
 *
 * <p>This configuration mirrors the production SecurityConfiguration architecture with
 * unified API structure, but uses mocked authentication for testing without requiring
 * a running Auth0 instance.</p>
 *
 * <p><b>Unified Filter Chain Architecture:</b></p>
 * <ul>
 *   <li><b>Order 1:</b> /api/v1/device/** → Custom JWT authentication (Device API)</li>
 *   <li><b>Order 2:</b> /api/dfc/** → Custom JWT (legacy, deprecated)</li>
 *   <li><b>Order 3:</b> /api/v1/** → Mocked OAuth2 Resource Server (UI/Admin API)</li>
 *   <li><b>Order 4:</b> /api/admin/** → Mocked OAuth2 (legacy, deprecated)</li>
 *   <li><b>Order 5:</b> /api/sites**, /api/account/**, /api/user/** → Mocked OAuth2 (legacy, deprecated)</li>
 *   <li><b>Order 6:</b> Default → Public endpoints (token generation, actuator, swagger)</li>
 * </ul>
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

    @Autowired(required = false)
    private TokenService tokenService;

    @Autowired(required = false)
    private PluginApiKeyAuthenticationFilter pluginApiKeyAuthenticationFilter;

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
                    .claim("https://api.test.com/accountId", accountId) // Namespaced claim for Auth0 (matches application-test.yml)
                    .claim("https://api.test.com/email", email) // Namespaced email claim
                    .claim("https://api.test.com/roles", roles) // Namespaced roles claim
                    .claim("realm_access", Map.of("roles", roles))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();
        };
    }

    /**
     * Custom JWT authorities converter for Auth0 realm roles.
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
     * Device API filter chain (NEW unified structure).
     * <p>
     * Order 1: Highest priority - matches /api/v1/device/** first.
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
                .requestMatchers("/api/v1/device/auth/token").permitAll() // Public token endpoint
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
     * Order 3: Third priority - matches /api/v1/plugins/bit-bi/**.
     * Plugin API Key authentication via PluginApiKeyAuthenticationFilter.
     * </p>
     */
    @Bean
    @org.springframework.core.annotation.Order(3)
    public SecurityFilterChain bitBiPluginApiFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/v1/plugins/bit-bi/**")
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
     * UI/Admin API filter chain (NEW unified structure).
     * <p>
     * Order 4: Fourth priority - matches /api/v1/** (excluding /api/v1/device/** and /api/v1/plugins/bit-bi/**).
     * Auth0 OAuth2 Resource Server only.
     * </p>
     */
    @Bean
    @org.springframework.core.annotation.Order(4)
    public SecurityFilterChain adminApiFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/v1/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/device/**").denyAll() // Explicitly deny (handled by Order 1)
                .requestMatchers("/api/v1/plugins/bit-bi/**").denyAll() // Explicitly deny (handled by Order 3)
                .requestMatchers("/api/v1/accounts/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/sites/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/batches/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/errors/**").hasRole("ADMIN")
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
     * Legacy Auth0 filter chain for old admin endpoints.
     * <p>
     * Order 5: Fifth priority - matches /api/admin/** (deprecated).
     * Auth0 OAuth2 Resource Server only - ROLE_ADMIN required.
     * </p>
     */
    @Bean
    @org.springframework.core.annotation.Order(5)
    public SecurityFilterChain legacyAuth0FilterChain(HttpSecurity http) throws Exception {
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
     * Legacy user filter chain for authenticated user endpoints.
     * <p>
     * Order 6: Sixth priority - matches /api/sites**, /api/account/**, /api/user/** (deprecated).
     * OAuth2 Resource Server - any authenticated user allowed.
     * </p>
     */
    @Bean
    @org.springframework.core.annotation.Order(6)
    public SecurityFilterChain legacyUserFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/sites/**", "/api/account/**", "/api/user/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
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
     * Order 7: Lowest priority - catches all remaining requests.
     * Allows public access to actuator, swagger, and auth token endpoints.
     * </p>
     */
    @Bean
    @org.springframework.core.annotation.Order(7)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/api-docs/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/device/auth/token").permitAll() // NEW Device API token endpoint
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/token").permitAll() // Legacy token endpoint
                .anyRequest().denyAll()
            );

        return http.build();
    }
}
