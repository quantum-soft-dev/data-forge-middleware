package com.bitbi.dfm.security;

import com.bitbi.dfm.auth.application.TokenService;
import com.bitbi.dfm.auth.config.Auth0Properties;
import com.bitbi.dfm.auth.infrastructure.JwtAuthenticationFilter;
import com.bitbi.dfm.plugin.application.ParquetExportCredentialsService;
import com.bitbi.dfm.plugin.application.PluginApiKeyService;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.presentation.ParquetExportBasicAuthFilter;
import com.bitbi.dfm.plugin.presentation.PluginApiKeyAuthenticationFilter;
import com.bitbi.dfm.shared.auth.AuthenticationAuditLogger;
import com.bitbi.dfm.shared.config.SecurityConfiguration;
import com.bitbi.dfm.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The <b>production</b> {@link SecurityConfiguration} on a real embedded Tomcat (issue #384).
 *
 * <p>Every other security test runs the {@code test} profile, where {@code SecurityConfiguration}
 * ({@code @Profile("!test")}) is replaced by {@code TestSecurityConfig}, and every one of them
 * drives MockMvc. MockMvc has <b>no error dispatch</b>: a {@code response.sendError(401)} from an
 * entry point is simply the status it reports. On a servlet container the same call forwards the
 * request to {@code /error}, which no {@code /api/v1/**} chain matches, so it lands on the
 * {@code @Order(8)} default chain — and that chain's {@code anyRequest().denyAll()} answered the
 * anonymous error dispatch with {@code Http403ForbiddenEntryPoint}. The 401 every contract test
 * asserts was therefore a 403 on the deployed server, and no test could see it. A garbage bearer
 * token kept answering 401 only because {@code BearerTokenAuthenticationEntryPoint} uses
 * {@code setStatus}, and the plugin-key chain only because its filter writes its own response.
 * The two device chains end in {@code sendError(401)} as well and had the same 403.</p>
 *
 * <p>So this harness is the opposite of the contract suite on both axes: the production class,
 * and a real HTTP round trip. It needs no database: the context is the web, MVC, error and
 * security auto-configurations the application uses, the production security beans, and stubs for
 * the services behind the authentication filters. {@link SecurityFilterAutoConfiguration} is the
 * load-bearing import — it registers the security filter for the {@code ERROR} dispatch as the
 * application does; without it a plain filter bean is registered for {@code REQUEST} only and this
 * test could not fail.</p>
 *
 * <p>It also pins the decision the ticket asked for: an unknown path under
 * {@code /api/v1/plugins/bit-bi/**} is in the plugin-key namespace — 401 without a valid key, 404
 * with one — while {@code /activate} and {@code /deactivate} stay on Auth0.</p>
 */
@SpringBootTest(
        classes = ProductionSecurityChainTest.Harness.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // CI exports SPRING_PROFILES_ACTIVE=test, under which SecurityConfiguration
                // (@Profile("!test")) does not exist; an inlined test property outranks the
                // environment, so the production class is what this context boots everywhere.
                "spring.profiles.active=production-security-harness",
                "dfm.observability.metrics-scrape.allowed-cidrs=",
                "spring.main.banner-mode=off"
        })
@DisplayName("Production SecurityConfiguration on a real server (#384)")
class ProductionSecurityChainTest {

    private static final String VALID_PLUGIN_KEY = "plk_valid";
    private static final String VALID_AUTH0_TOKEN = "valid.auth0.token";
    private static final String CLAIMS_NAMESPACE = "https://dfm.example";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private SecurityConfiguration securityConfiguration;

    private HttpResponse<String> send(String method, String path, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.noBody());
        headers.forEach(builder::header);
        try {
            return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Nested
    @DisplayName("Anonymous request to the OAuth chain")
    class Anonymous {

        @ParameterizedTest(name = "{0} {1} → 401")
        @CsvSource({
                "GET,    /api/v1/account/plugins",
                "GET,    /api/v1/foo",
                "GET,    /api/v1/plugins/foo",
                "POST,   /api/v1/plugins/bit-bi/activate",
                "DELETE, /api/v1/plugins/bit-bi/deactivate",
                "GET,    /api/v1/sites/0199bab2-0000-0000-0000-000000000000",
                "GET,    /api/v1/admin/plugins"
        })
        void answers401(String method, String path) {
            HttpResponse<String> response = send(method, path, Map.of());

            assertThat(response.statusCode())
                    .as("%s %s without Authorization — body: %s", method, path, response.body())
                    .isEqualTo(401);
        }

        @ParameterizedTest(name = "{0} {1} → 401")
        @CsvSource({
                "GET,  /api/v1/device/batches/0199bab2-0000-0000-0000-000000000000",
                "POST, /api/v1/device/errors",
                "GET,  /api/v1/device/verify"
        })
        @DisplayName("The device chains' sendError(401) reached the client as 403 as well")
        void deviceChainsAnswer401(String method, String path) {
            HttpResponse<String> response = send(method, path, Map.of());

            assertThat(response.statusCode())
                    .as("%s %s without Authorization — body: %s", method, path, response.body())
                    .isEqualTo(401);
        }

        @Test
        @DisplayName("A garbage bearer token is 401 too (BearerTokenAuthenticationEntryPoint)")
        void garbageBearerAnswers401() {
            HttpResponse<String> response = send("GET", "/api/v1/foo", Map.of("Authorization", "Bearer x.y.z"));

            assertThat(response.statusCode()).isEqualTo(401);
        }
    }

    @Nested
    @DisplayName("Authenticated request to the OAuth chain")
    class Authenticated {

        @Test
        @DisplayName("A valid Auth0 token reaches an existing route")
        void validTokenReachesTheRoute() {
            HttpResponse<String> response = send("GET", "/api/v1/account/plugins", bearer());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("account-plugins");
        }

        @Test
        @DisplayName("An unknown path with a valid Auth0 token is 404, not 403")
        void unknownPathIs404() {
            HttpResponse<String> response = send("GET", "/api/v1/foo", bearer());

            assertThat(response.statusCode()).isEqualTo(404);
        }

        @Test
        @DisplayName("bit-bi /activate and /deactivate stay on Auth0, not on the plugin key")
        void activationStaysOnAuth0() {
            assertThat(send("POST", "/api/v1/plugins/bit-bi/activate", bearer()).body()).isEqualTo("activated:bit-bi");
            assertThat(send("DELETE", "/api/v1/plugins/bit-bi/deactivate", bearer()).body()).isEqualTo("deactivated:bit-bi");
            assertThat(send("POST", "/api/v1/plugins/bit-bi/activate", Map.of("X-Plugin-Api-Key", VALID_PLUGIN_KEY))
                    .statusCode())
                    .as("a plugin key does not authenticate an Auth0 route")
                    .isEqualTo(401);
        }

        @Test
        @DisplayName("A ROLE_USER token on an admin route is 403 — the one case that is a 403")
        void wrongRoleIs403() {
            HttpResponse<String> response = send("GET", "/api/v1/sites/0199bab2-0000-0000-0000-000000000000",
                    Map.of("Authorization", "Bearer user." + VALID_AUTH0_TOKEN));

            assertThat(response.statusCode()).isEqualTo(403);
        }

        private Map<String, String> bearer() {
            return Map.of("Authorization", "Bearer " + VALID_AUTH0_TOKEN);
        }
    }

    @Nested
    @DisplayName("Bit BI plugin-key namespace /api/v1/plugins/bit-bi/**")
    class PluginNamespace {

        @ParameterizedTest(name = "{0} with key [{1}] → {2}")
        @CsvSource(value = {
                "/api/v1/plugins/bit-bi/sites,       '',            401",
                "/api/v1/plugins/bit-bi/sites,       plk_invalid,   401",
                "/api/v1/plugins/bit-bi/sites,       plk_valid,     200",
                "/api/v1/plugins/bit-bi/v2/sites,    '',            401",
                "/api/v1/plugins/bit-bi/v2/sites,    plk_invalid,   401",
                "/api/v1/plugins/bit-bi/v2/unknown,  '',            401",
                "/api/v1/plugins/bit-bi/v2/unknown,  plk_valid,     404",
                "/api/v1/plugins/bit-bi/unknown,     plk_valid,     404"
        })
        void answersByKey(String path, String key, int expected) {
            Map<String, String> headers = key.isEmpty() ? Map.of() : Map.of("X-Plugin-Api-Key", key);

            HttpResponse<String> response = send("GET", path, headers);

            assertThat(response.statusCode())
                    .as("GET %s with key [%s] — body: %s", path, key, response.body())
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("An Auth0 token does not open the plugin namespace")
        void auth0TokenIsNotAKey() {
            HttpResponse<String> response = send("GET", "/api/v1/plugins/bit-bi/v2/sites",
                    Map.of("Authorization", "Bearer " + VALID_AUTH0_TOKEN));

            assertThat(response.statusCode()).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("The harness runs the production class, not TestSecurityConfig")
    void harnessUsesProductionConfiguration() {
        assertThat(securityConfiguration).isNotNull();
    }

    /**
     * The smallest application that serves the production security chains: no database, no S3,
     * no gRPC. Only the services behind the three authentication filters are stubbed.
     *
     * <p>A plain {@code @Configuration}, deliberately not {@code @SpringBootConfiguration}: every
     * {@code @SpringBootTest} without {@code classes} in this package searches upwards for the
     * nearest {@code @SpringBootConfiguration} and would boot this harness instead of the
     * application.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            TomcatServletWebServerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            ErrorMvcAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            JacksonAutoConfiguration.class,
            SecurityAutoConfiguration.class,
            ServletWebSecurityAutoConfiguration.class,
            SecurityFilterAutoConfiguration.class
    })
    @Import({
            SecurityConfiguration.class,
            AuthenticationAuditLogger.class,
            GlobalExceptionHandler.class,
            ProbeController.class
    })
    static class Harness {

        @Bean
        Auth0Properties auth0Properties() {
            return new Auth0Properties(
                    "example.auth0.com",
                    new Auth0Properties.Management("client", "secret", "https://example.auth0.com/api/v2/", 3600L),
                    new Auth0Properties.Api("https://api.example", "https://example.auth0.com/", CLAIMS_NAMESPACE));
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                List<String> roles;
                if (VALID_AUTH0_TOKEN.equals(token)) {
                    roles = List.of("ADMIN");
                } else if (("user." + VALID_AUTH0_TOKEN).equals(token)) {
                    roles = List.of("USER");
                } else {
                    throw new BadJwtException("not a token this harness issued");
                }
                return Jwt.withTokenValue(token)
                        .header("alg", "RS256")
                        .subject("auth0|probe")
                        .claim(CLAIMS_NAMESPACE + "/roles", roles)
                        .issuedAt(Instant.now().minusSeconds(5))
                        .expiresAt(Instant.now().plusSeconds(300))
                        .build();
            };
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter() {
            return new JwtAuthenticationFilter(mock(TokenService.class));
        }

        @Bean
        PluginApiKeyAuthenticationFilter pluginApiKeyAuthenticationFilter() {
            PluginApiKeyService keys = mock(PluginApiKeyService.class);
            AccountPlugin plugin = mock(AccountPlugin.class);
            when(plugin.getAccountId()).thenReturn(UUID.fromString("0199bab2-1111-1111-1111-111111111111"));
            when(plugin.getId()).thenReturn(1L);
            when(keys.validateApiKey(anyString())).thenReturn(Optional.empty());
            when(keys.validateApiKey(VALID_PLUGIN_KEY)).thenReturn(Optional.of(plugin));
            return new PluginApiKeyAuthenticationFilter(keys, JsonMapper.builder().build());
        }

        @Bean
        ParquetExportBasicAuthFilter parquetExportBasicAuthFilter() {
            return new ParquetExportBasicAuthFilter(mock(ParquetExportCredentialsService.class), JsonMapper.builder().build());
        }
    }

    /** Stand-ins for the routes whose security this test is about. */
    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/account/plugins")
        String accountPlugins() {
            return "account-plugins";
        }

        @PostMapping("/api/v1/plugins/{pluginId}/activate")
        String activate(@PathVariable String pluginId) {
            return "activated:" + pluginId;
        }

        @DeleteMapping("/api/v1/plugins/{pluginId}/deactivate")
        String deactivate(@PathVariable String pluginId) {
            return "deactivated:" + pluginId;
        }

        @GetMapping("/api/v1/plugins/bit-bi/sites")
        String sites() {
            return "sites";
        }
    }
}
