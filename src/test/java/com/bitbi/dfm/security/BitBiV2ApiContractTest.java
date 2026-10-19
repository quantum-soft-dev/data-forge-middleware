package com.bitbi.dfm.security;

import com.bitbi.dfm.config.JacksonConfiguration;
import com.bitbi.dfm.contract.WireJson;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService.SyncStateView;
import com.bitbi.dfm.plugin.application.BitBiV2QueryService;
import com.bitbi.dfm.plugin.application.PluginRateLimiterService;
import com.bitbi.dfm.plugin.presentation.BitBiV2ApiController;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.site.domain.SiteSchema;
import com.bitbi.dfm.site.domain.SiteSchemaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Bit BI v2 read surface, part 1 (issue #386), on the <b>production</b> security chain and a
 * real embedded Tomcat — the harness of {@link ProductionSecurityChainTest}, because the contract
 * suite's MockMvc and {@code TestSecurityConfig} could not see #384's 403.
 *
 * <p>The controller, {@link BitBiV2QueryService} and {@link SiteSchemaService} are the real ones;
 * only what they read is stubbed (the site and schema repositories and the sync state), so the
 * bodies below are what the real mapping writes from a stored {@code site_schemas} document. The
 * bodies are pinned whole through {@link WireJson} and are the examples in
 * {@code docs/bitbi-integration.md} ("Bit BI API v2").</p>
 */
@SpringBootTest(
        classes = {ProductionSecurityChainTest.Harness.class, BitBiV2ApiContractTest.V2Wiring.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=production-security-harness",
                "dfm.observability.metrics-scrape.allowed-cidrs=",
                "spring.main.banner-mode=off"
        })
@DisplayName("Bit BI v2 read surface on the production chain (#386)")
class BitBiV2ApiContractTest {

    /** The key the harness's filter accepts, and the account it belongs to. */
    private static final String KEY = "plk_valid";
    private static final UUID ACCOUNT = UUID.fromString("0199bab2-1111-1111-1111-111111111111");
    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    private static final UUID FOREIGN_SITE = UUID.fromString("0199bab0-ca3b-e41c-5521-2f4b33fda8b6");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @LocalServerPort
    private int port;

    @MockitoBean
    private SiteRepository siteRepository;

    @MockitoBean
    private SiteSchemaRepository siteSchemaRepository;

    @MockitoBean
    private DeltaSyncStateService syncStateService;

    @MockitoBean
    private PluginRateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        when(rateLimiterService.tryConsume(any())).thenReturn(true);
        Site site = mock(Site.class);
        when(site.getId()).thenReturn(SITE);
        when(site.getSiteName()).thenReturn("fyt-new");
        when(site.getDisplayName()).thenReturn("FYT");
        when(site.getAccountId()).thenReturn(ACCOUNT);
        when(siteRepository.findActiveByAccountId(ACCOUNT)).thenReturn(List.of(site));
        when(siteRepository.findByIdAndAccountId(SITE, ACCOUNT)).thenReturn(Optional.of(site));
        // FOREIGN_SITE exists, under another account: the lookup scoped to this account finds nothing.
        when(siteRepository.findByIdAndAccountId(FOREIGN_SITE, ACCOUNT)).thenReturn(Optional.empty());
    }

    private HttpResponse<String> get(String path, String key) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (key != null) {
            builder.header("X-Plugin-Api-Key", key);
        }
        try {
            return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> column(String name, String type, boolean nullable) {
        return Map.of("name", name, "type", type, "nullable", nullable);
    }

    @Nested
    @DisplayName("GET /v2/sites")
    class Sites {

        @Test
        @DisplayName("200 with the account's sites")
        void listsTheSites() {
            HttpResponse<String> response = get("/api/v1/plugins/bit-bi/v2/sites", KEY);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                    ct -> assertThat(ct).startsWith("application/json"));
            WireJson.assertMatches("{\"sites\":[{\"id\":\"" + SITE + "\",\"siteName\":\"fyt-new\","
                    + "\"displayName\":\"FYT\"}]}", response.body());
        }
    }

    @Nested
    @DisplayName("GET /v2/sites/{siteId}/state")
    class State {

        @Test
        @DisplayName("200 with epoch = baseline epoch, the checkpoint pointer, the watermark and schemaVersion")
        void answersTheState() {
            when(syncStateService.getSyncState(SITE))
                    .thenReturn(new SyncStateView(5_023_845L, 5_020_646L, 2, false, false, 1L, 3L));
            SiteSchema schema = SiteSchema.create(SITE, Map.of("tables", Map.of()));
            schema.update(Map.of("tables", Map.of()));
            when(siteSchemaRepository.findBySiteId(SITE)).thenReturn(Optional.of(schema));

            HttpResponse<String> response = get("/api/v1/plugins/bit-bi/v2/sites/" + SITE + "/state", KEY);

            assertThat(response.statusCode()).isEqualTo(200);
            WireJson.assertMatches("{\"siteId\":\"" + SITE + "\",\"epoch\":3,\"checkpointSeq\":5020646,"
                    + "\"lastAppliedSeq\":5023845,\"schemaVersion\":2}", response.body());
        }

        @Test
        @DisplayName("A site that never synced answers zeros")
        void aSiteThatNeverSyncedAnswersZeros() {
            when(syncStateService.getSyncState(SITE))
                    .thenReturn(new SyncStateView(0L, 0L, 0, false, false, 0L, 0L));

            HttpResponse<String> response = get("/api/v1/plugins/bit-bi/v2/sites/" + SITE + "/state", KEY);

            WireJson.assertMatches("{\"siteId\":\"" + SITE + "\",\"epoch\":0,\"checkpointSeq\":0,"
                    + "\"lastAppliedSeq\":0,\"schemaVersion\":0}", response.body());
        }
    }

    @Nested
    @DisplayName("GET /v2/sites/{siteId}/schema")
    class Schema {

        @Test
        @DisplayName("200 with identity per table: row_hash without a primary key, primary_key with one")
        void answersTheSchema() {
            SiteSchema schema = SiteSchema.create(SITE, Map.of("tables", Map.of(
                    "orders", Map.of(
                            "columns", List.of(column("id", "bigint", false), column("total", "numeric(12,2)", true)),
                            "primaryKey", List.of("id"),
                            "uniqueKeys", List.of(Map.of("name", "uk_orders_total", "columns", List.of("total")))),
                    "nsftrns", Map.of(
                            "columns", List.of(column("TR_NUM", "integer", true), column("TR_DATE", "date", true)),
                            "primaryKey", List.of(),
                            "uniqueKeys", List.of()))));
            when(siteSchemaRepository.findBySiteId(SITE)).thenReturn(Optional.of(schema));

            HttpResponse<String> response = get("/api/v1/plugins/bit-bi/v2/sites/" + SITE + "/schema", KEY);

            assertThat(response.statusCode()).isEqualTo(200);
            WireJson.assertMatches("{\"siteId\":\"" + SITE + "\",\"schemaVersion\":1,\"tables\":["
                    + "{\"table\":\"nsftrns\",\"identity\":\"row_hash\",\"primaryKey\":[],\"uniqueKeys\":[],"
                    + "\"columns\":[{\"name\":\"TR_NUM\",\"type\":\"integer\",\"nullable\":true},"
                    + "{\"name\":\"TR_DATE\",\"type\":\"date\",\"nullable\":true}]},"
                    + "{\"table\":\"orders\",\"identity\":\"primary_key\",\"primaryKey\":[\"id\"],"
                    + "\"uniqueKeys\":[{\"name\":\"uk_orders_total\",\"columns\":[\"total\"]}],"
                    + "\"columns\":[{\"name\":\"id\",\"type\":\"bigint\",\"nullable\":false},"
                    + "{\"name\":\"total\",\"type\":\"numeric(12,2)\",\"nullable\":true}]}]}", response.body());
        }

        @Test
        @DisplayName("A site with no submitted schema answers version 0 and no tables")
        void noSchema() {
            HttpResponse<String> response = get("/api/v1/plugins/bit-bi/v2/sites/" + SITE + "/schema", KEY);

            assertThat(response.statusCode()).isEqualTo(200);
            WireJson.assertMatches("{\"siteId\":\"" + SITE + "\",\"schemaVersion\":0,\"tables\":[]}",
                    response.body());
        }
    }

    @Nested
    @DisplayName("Refusals")
    class Refusals {

        @ParameterizedTest(name = "{0} without a key → 401")
        @ValueSource(strings = {"/sites", "/sites/" + "0199baac-f852-753f-6fc3-7c994fc38654" + "/state",
                "/sites/" + "0199baac-f852-753f-6fc3-7c994fc38654" + "/schema"})
        void noKeyIs401(String route) {
            assertThat(get("/api/v1/plugins/bit-bi/v2" + route, null).statusCode()).isEqualTo(401);
        }

        @ParameterizedTest(name = "{0} with a revoked or unknown key → 401")
        @ValueSource(strings = {"/sites", "/sites/" + "0199baac-f852-753f-6fc3-7c994fc38654" + "/state",
                "/sites/" + "0199baac-f852-753f-6fc3-7c994fc38654" + "/schema"})
        void revokedKeyIs401(String route) {
            HttpResponse<String> response = get("/api/v1/plugins/bit-bi/v2" + route, "plk_revoked");

            assertThat(response.statusCode()).isEqualTo(401);
            verify(syncStateService, never()).getSyncState(any());
        }

        @ParameterizedTest(name = "{0} of another account's site → 404, the body of an absent one")
        @ValueSource(strings = {"state", "schema"})
        void aForeignSiteIs404(String route) {
            HttpResponse<String> foreign = get("/api/v1/plugins/bit-bi/v2/sites/" + FOREIGN_SITE + "/" + route, KEY);

            assertThat(foreign.statusCode()).isEqualTo(404);
            WireJson.assertMatches("{\"timestamp\":\"<instant>\",\"status\":404,\"error\":\"Not Found\","
                    + "\"message\":\"Site not found: " + FOREIGN_SITE + "\","
                    + "\"path\":\"/api/v1/plugins/bit-bi/v2/sites/" + FOREIGN_SITE + "/" + route + "\"}",
                    foreign.body());
            verify(syncStateService, never()).getSyncState(FOREIGN_SITE);
            verify(siteSchemaRepository, never()).findBySiteId(FOREIGN_SITE);
        }

        @Test
        @DisplayName("An unknown path under /v2 is 404 with a key and 401 without (#384)")
        void unknownPathUnderV2() {
            assertThat(get("/api/v1/plugins/bit-bi/v2/unknown", KEY).statusCode()).isEqualTo(404);
            assertThat(get("/api/v1/plugins/bit-bi/v2/unknown", null).statusCode()).isEqualTo(401);
        }

        @ParameterizedTest(name = "{0} over the limit → 429 with Retry-After")
        @ValueSource(strings = {"/sites", "/sites/" + "0199baac-f852-753f-6fc3-7c994fc38654" + "/state",
                "/sites/" + "0199baac-f852-753f-6fc3-7c994fc38654" + "/schema"})
        void overTheLimitIs429(String route) {
            when(rateLimiterService.tryConsume(ACCOUNT)).thenReturn(false);
            when(rateLimiterService.getRetryAfterSeconds(ACCOUNT)).thenReturn(17L);

            HttpResponse<String> response = get("/api/v1/plugins/bit-bi/v2" + route, KEY);

            assertThat(response.statusCode()).isEqualTo(429);
            assertThat(response.headers().firstValue("Retry-After")).hasValue("17");
            WireJson.assertMatches("{\"timestamp\":\"<instant>\",\"status\":429,\"error\":\"Too Many Requests\","
                    + "\"message\":\"Rate limit exceeded. Retry after 17 seconds.\","
                    + "\"path\":\"/api/v1/plugins/bit-bi/v2" + route + "\"}", response.body());
            verify(syncStateService, never()).getSyncState(any());
            verify(siteSchemaRepository, never()).findBySiteId(any());
        }
    }

    /** The real v2 controller and the real services behind it. */
    @Configuration(proxyBeanMethods = false)
    @Import({
            JacksonConfiguration.class,
            BitBiV2ApiController.class,
            BitBiV2QueryService.class,
            SiteSchemaService.class
    })
    static class V2Wiring {
    }
}
