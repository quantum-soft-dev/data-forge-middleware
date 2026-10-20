package com.bitbi.dfm.security;

import com.bitbi.dfm.batch.infrastructure.S3PresignedUrlService;
import com.bitbi.dfm.config.JacksonConfiguration;
import com.bitbi.dfm.contract.WireJson;
import com.bitbi.dfm.delta.application.BatchParquetFinalizationService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService.SyncStateView;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage.ObjectPresence;
import com.bitbi.dfm.plugin.application.BitBiV2ChangesService;
import com.bitbi.dfm.plugin.application.BitBiV2FileService;
import com.bitbi.dfm.plugin.application.BitBiV2QueryService;
import com.bitbi.dfm.plugin.domain.BitBiChangesCursor;
import com.bitbi.dfm.plugin.domain.BitBiFileId;
import com.bitbi.dfm.plugin.infrastructure.BitBiChangesFeedDao;
import com.bitbi.dfm.plugin.infrastructure.BitBiChangesFeedDao.FeedBatch;
import com.bitbi.dfm.plugin.infrastructure.BitBiChangesFeedDao.FeedTable;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Bit BI v2 read surface (issues #386, #387 and #388), on the <b>production</b> security chain and a
 * real embedded Tomcat — the harness of {@link ProductionSecurityChainTest}, because the contract
 * suite's MockMvc and {@code TestSecurityConfig} could not see #384's 403.
 *
 * <p>The controller, {@link BitBiV2QueryService}, {@link BitBiV2FileService} and
 * {@link SiteSchemaService} are the real ones; only what they read is stubbed (the site, schema and
 * checkpoint repositories, the sync state, object storage and the presigner), so the
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
@DisplayName("Bit BI v2 read surface on the production chain (#386, #387, #388)")
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

    @MockitoBean
    private CheckpointRepository checkpointRepository;

    @MockitoBean
    private S3CheckpointStorage checkpointStorage;

    @MockitoBean
    private S3PresignedUrlService presignedUrlService;

    @MockitoBean
    private BitBiChangesFeedDao feedDao;

    @MockitoBean
    private BatchParquetFinalizationService finalizationService;

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

    private static Checkpoint checkpointRow(String table, long seq, boolean materialized) {
        Checkpoint checkpoint = Checkpoint.create(SITE, table, seq, 10L);
        if (materialized) {
            checkpoint.attachParquet(S3CheckpointStorage.snapshotKey(SITE, table, seq));
        }
        return checkpoint;
    }

    @Nested
    @DisplayName("GET /v2/sites/{siteId}/snapshot")
    class Snapshot {

        @Test
        @DisplayName("200: every table at checkpointSeq, a missing one listed with a null fileId")
        void answersTheSnapshot() {
            when(syncStateService.getSyncState(SITE))
                    .thenReturn(new SyncStateView(5_023_845L, 5_020_646L, 2, false, false, 1L, 3L));
            when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of(
                    checkpointRow("orders", 5_020_646L, true),
                    checkpointRow("nsfcard", 5_020_646L, false),
                    checkpointRow("nsftrns", 5_020_646L, true)));

            HttpResponse<String> response = get("/api/v1/plugins/bit-bi/v2/sites/" + SITE + "/snapshot", KEY);

            assertThat(response.statusCode()).isEqualTo(200);
            String nsftrns = new BitBiFileId.Checkpoint(SITE, 3L, 5_020_646L, "nsftrns").encode();
            String orders = new BitBiFileId.Checkpoint(SITE, 3L, 5_020_646L, "orders").encode();
            WireJson.assertMatches("{\"siteId\":\"" + SITE + "\",\"epoch\":3,\"checkpointSeq\":5020646,"
                    + "\"tables\":["
                    + "{\"table\":\"nsfcard\",\"status\":\"missing\",\"fileId\":null},"
                    + "{\"table\":\"nsftrns\",\"status\":\"ready\",\"fileId\":\"" + nsftrns + "\"},"
                    + "{\"table\":\"orders\",\"status\":\"ready\",\"fileId\":\"" + orders + "\"}]}",
                    response.body());
        }

        @Test
        @DisplayName("No checkpoint yet: checkpointSeq 0 and no tables")
        void noCheckpointYet() {
            when(syncStateService.getSyncState(SITE))
                    .thenReturn(new SyncStateView(12L, 0L, 0, false, false, 0L, 0L));

            HttpResponse<String> response = get("/api/v1/plugins/bit-bi/v2/sites/" + SITE + "/snapshot", KEY);

            assertThat(response.statusCode()).isEqualTo(200);
            WireJson.assertMatches("{\"siteId\":\"" + SITE + "\",\"epoch\":0,\"checkpointSeq\":0,\"tables\":[]}",
                    response.body());
        }

        @Test
        @DisplayName("503 with the standard body when storage cannot say whether a table's file exists")
        void storageUndecidableIs503() {
            when(syncStateService.getSyncState(SITE))
                    .thenReturn(new SyncStateView(5_023_845L, 5_020_646L, 2, false, false, 1L, 3L));
            when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of(checkpointRow("orders", 5_021_000L, true)));
            when(checkpointStorage.presence(anyString())).thenReturn(ObjectPresence.UNKNOWN);

            HttpResponse<String> response = get("/api/v1/plugins/bit-bi/v2/sites/" + SITE + "/snapshot", KEY);

            assertThat(response.statusCode()).isEqualTo(503);
            WireJson.assertMatches("{\"timestamp\":\"<instant>\",\"status\":503,\"error\":\"Service Unavailable\","
                    + "\"message\":\"Object storage is temporarily unavailable. Please try again.\","
                    + "\"path\":\"/api/v1/plugins/bit-bi/v2/sites/" + SITE + "/snapshot\"}", response.body());
        }
    }

    @Nested
    @DisplayName("GET /v2/files/{fileId}")
    class Files {

        private final String fileId = new BitBiFileId.Checkpoint(SITE, 3L, 5_020_646L, "orders").encode();
        private final String key = S3CheckpointStorage.snapshotKey(SITE, "orders", 5_020_646L);

        @BeforeEach
        void aListedFile() {
            when(syncStateService.getSyncState(SITE))
                    .thenReturn(new SyncStateView(5_023_845L, 5_020_646L, 2, false, false, 1L, 3L));
            when(checkpointRepository.findBySiteIdAndTableName(SITE, "orders"))
                    .thenReturn(Optional.of(checkpointRow("orders", 5_020_646L, true)));
            when(checkpointStorage.presence(key)).thenReturn(ObjectPresence.PRESENT);
            when(presignedUrlService.generatePresignedUrl(eq(key), anyString(), any(Duration.class)))
                    .thenReturn(new S3PresignedUrlService.PresignedUrlResult(
                            "https://storage.googleapis.com/dfm-dev-uploads/" + key + "?X-Amz-Signature=abc",
                            Instant.now().plusSeconds(60)));
        }

        @Test
        @DisplayName("302 to the presigned URL of that object, not cached, with no body")
        void redirects() {
            HttpResponse<String> response = get("/api/v1/plugins/bit-bi/v2/files/" + fileId, KEY);

            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(response.headers().firstValue("Location"))
                    .hasValue("https://storage.googleapis.com/dfm-dev-uploads/" + key + "?X-Amz-Signature=abc");
            assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
            assertThat(response.body()).isEmpty();
        }

        @Test
        @DisplayName("Another account's file is 404 with the body of an id never issued")
        void foreignFileIs404() {
            String foreign = new BitBiFileId.Checkpoint(FOREIGN_SITE, 3L, 5_020_646L, "orders").encode();

            HttpResponse<String> response = get("/api/v1/plugins/bit-bi/v2/files/" + foreign, KEY);
            HttpResponse<String> garbage = get("/api/v1/plugins/bit-bi/v2/files/garbage", KEY);

            assertThat(response.statusCode()).isEqualTo(404);
            WireJson.assertMatches("{\"timestamp\":\"<instant>\",\"status\":404,\"error\":\"Not Found\","
                    + "\"message\":\"File not found: " + foreign + "\","
                    + "\"path\":\"/api/v1/plugins/bit-bi/v2/files/" + foreign + "\"}", response.body());
            assertThat(garbage.statusCode()).isEqualTo(404);
            WireJson.assertMatches("{\"timestamp\":\"<instant>\",\"status\":404,\"error\":\"Not Found\","
                    + "\"message\":\"File not found: garbage\","
                    + "\"path\":\"/api/v1/plugins/bit-bi/v2/files/garbage\"}", garbage.body());
            verify(checkpointStorage, never()).presence(S3CheckpointStorage.snapshotKey(FOREIGN_SITE, "orders", 5_020_646L));
        }

        @Test
        @DisplayName("An object that is gone is 404")
        void goneIs404() {
            when(checkpointStorage.presence(key)).thenReturn(ObjectPresence.ABSENT);

            assertThat(get("/api/v1/plugins/bit-bi/v2/files/" + fileId, KEY).statusCode()).isEqualTo(404);
            verify(presignedUrlService, never()).generatePresignedUrl(anyString(), anyString(), any(Duration.class));
        }
    }

    @Nested
    @DisplayName("GET /v2/sites/{siteId}/changes")
    class Changes {

        private final UUID batch1 = UUID.fromString("0199bb0f-1a2b-7c3d-8e4f-5a6b7c8d9e01");
        private final UUID batch2 = UUID.fromString("0199bb10-4c2e-7a1d-9f00-3b5e1a2c7d41");
        private final UUID batch3 = UUID.fromString("0199bb11-7d3f-7b2e-8a01-4c6f2b3d8e52");
        private final LocalDateTime started2 = LocalDateTime.of(2026, 10, 20, 6, 0);

        @BeforeEach
        void atEpoch3() {
            when(syncStateService.getSyncState(SITE))
                    .thenReturn(new SyncStateView(5_024_100L, 5_020_646L, 2, false, false, 1L, 3L));
            when(finalizationService.enqueueHorizon()).thenReturn(Duration.ofHours(168));
        }

        @Test
        @DisplayName("200: batches in order with their manifests, and the cursor of the next page")
        void answersAPage() {
            when(feedDao.findPage(eq(SITE), eq(3L), eq(5_020_646L), eq(null), eq(null), any(), eq(3))).thenReturn(List.of(
                    new FeedBatch(batch1, "COMPLETED", LocalDateTime.of(2026, 10, 19, 22, 0),
                            LocalDateTime.of(2026, 10, 20, 1, 10), "CONTINUOUS", 5_019_800L, 5_023_011L, List.of(
                            new FeedTable("nsftrns", "READY", 3212L, 3212L,
                                    LocalDateTime.of(2026, 10, 20, 1, 14, 7, 311_000_000), null))),
                    new FeedBatch(batch2, "COMPLETED", started2, LocalDateTime.of(2026, 10, 20, 6, 2),
                            "DELTA", 5_023_012L, 5_023_845L, List.of(
                            new FeedTable("nsfcard", "ABANDONED", null, 25L, null,
                                    LocalDateTime.of(2026, 10, 20, 6, 3, 12, 500_000_000)),
                            new FeedTable("nsftrns", "READY", 834L, 834L,
                                    LocalDateTime.of(2026, 10, 20, 6, 2, 41), null),
                            new FeedTable("tmp_x", null, null, 4L, null, null))),
                    new FeedBatch(batch3, "COMPLETED", LocalDateTime.of(2026, 10, 20, 7, 0),
                            LocalDateTime.of(2026, 10, 20, 7, 1), "DELTA", 5_023_846L, 5_024_100L, List.of())));

            HttpResponse<String> response = get("/api/v1/plugins/bit-bi/v2/sites/" + SITE
                    + "/changes?epoch=3&afterSeq=5020646&size=2", KEY);

            assertThat(response.statusCode()).isEqualTo(200);
            String nsftrns1 = new BitBiFileId.BatchFile(SITE, batch1, "nsftrns").encode();
            String nsftrns2 = new BitBiFileId.BatchFile(SITE, batch2, "nsftrns").encode();
            String next = new BitBiChangesCursor(SITE, started2, batch2).encode();
            WireJson.assertMatches("{\"siteId\":\"" + SITE + "\",\"epoch\":3,\"batches\":["
                    + "{\"epoch\":3,\"batchId\":\"" + batch1 + "\",\"sessionMode\":\"continuous\","
                    + "\"firstSeq\":5019800,\"lastSeq\":5023011,\"readyAt\":\"2026-10-20T01:14:07.311Z\","
                    + "\"tables\":[{\"table\":\"nsftrns\",\"status\":\"ready\",\"fileId\":\"" + nsftrns1
                    + "\",\"rows\":3212}]},"
                    + "{\"epoch\":3,\"batchId\":\"" + batch2 + "\",\"sessionMode\":\"delta\","
                    + "\"firstSeq\":5023012,\"lastSeq\":5023845,\"readyAt\":\"2026-10-20T06:03:12.500Z\","
                    + "\"tables\":[{\"table\":\"nsfcard\",\"status\":\"abandoned\",\"fileId\":null,\"rows\":25},"
                    + "{\"table\":\"nsftrns\",\"status\":\"ready\",\"fileId\":\"" + nsftrns2 + "\",\"rows\":834},"
                    + "{\"table\":\"tmp_x\",\"status\":\"missing\",\"fileId\":null,\"rows\":4}]}],"
                    + "\"nextCursor\":\"" + next + "\"}", response.body());
        }

        @Test
        @DisplayName("The last page has no cursor; the cursor walks on from where the previous page ended")
        void theLastPage() {
            String cursor = new BitBiChangesCursor(SITE, started2, batch2).encode();
            when(feedDao.findPage(eq(SITE), eq(3L), eq(5_020_646L), eq(started2), eq(batch2), any(), eq(3)))
                    .thenReturn(List.of());

            HttpResponse<String> response = get("/api/v1/plugins/bit-bi/v2/sites/" + SITE
                    + "/changes?epoch=3&afterSeq=5020646&size=2&cursor=" + cursor, KEY);

            assertThat(response.statusCode()).isEqualTo(200);
            WireJson.assertMatches("{\"siteId\":\"" + SITE + "\",\"epoch\":3,\"batches\":[],\"nextCursor\":null}",
                    response.body());
        }

        @Test
        @DisplayName("A watermark of another epoch reads the current epoch from its start")
        void anotherEpochIgnoresAfterSeq() {
            when(feedDao.findPage(eq(SITE), eq(3L), eq(0L), eq(null), eq(null), any(), eq(51))).thenReturn(List.of());

            assertThat(get("/api/v1/plugins/bit-bi/v2/sites/" + SITE + "/changes?epoch=2&afterSeq=9000000", KEY)
                    .statusCode()).isEqualTo(200);
            verify(feedDao).findPage(eq(SITE), eq(3L), eq(0L), eq(null), eq(null), any(), eq(51));
        }

        @ParameterizedTest(name = "{0} → 400")
        @ValueSource(strings = {"afterSeq=10", "epoch=3&afterSeq=-1", "epoch=-1", "size=0", "size=201",
                "cursor=garbage", "afterSeq=abc"})
        void badParametersAre400(String query) {
            HttpResponse<String> response = get("/api/v1/plugins/bit-bi/v2/sites/" + SITE + "/changes?" + query, KEY);

            assertThat(response.statusCode()).isEqualTo(400);
            WireJson.assertMatches("{\"timestamp\":\"<instant>\",\"status\":400,\"error\":\"Bad Request\","
                    + "\"message\":\"<string>\",\"path\":\"/api/v1/plugins/bit-bi/v2/sites/" + SITE + "/changes\"}",
                    response.body());
            verify(feedDao, never()).findPage(any(), anyLong(), anyLong(), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("A cursor issued for another site is 400")
        void anotherSitesCursorIs400() {
            String foreign = new BitBiChangesCursor(FOREIGN_SITE, started2, batch2).encode();

            HttpResponse<String> response = get("/api/v1/plugins/bit-bi/v2/sites/" + SITE + "/changes?cursor=" + foreign,
                    KEY);

            assertThat(response.statusCode()).isEqualTo(400);
            WireJson.assertMatches("{\"timestamp\":\"<instant>\",\"status\":400,\"error\":\"Bad Request\","
                    + "\"message\":\"Invalid cursor\",\"path\":\"/api/v1/plugins/bit-bi/v2/sites/" + SITE
                    + "/changes\"}", response.body());
        }

        @Test
        @DisplayName("A batch fileId redirects to its artifact")
        void aBatchFileRedirects() {
            String key = "egress/" + SITE + "/batches/" + batch2 + "/attempts/c1/nsftrns.parquet";
            when(feedDao.findReadyKey(ACCOUNT, SITE, batch2, "nsftrns")).thenReturn(Optional.of(key));
            when(presignedUrlService.generatePresignedUrl(eq(key), anyString(), any(Duration.class)))
                    .thenReturn(new S3PresignedUrlService.PresignedUrlResult(
                            "https://storage.googleapis.com/dfm-dev-uploads/" + key + "?X-Amz-Signature=abc",
                            Instant.now().plusSeconds(60)));

            HttpResponse<String> response = get("/api/v1/plugins/bit-bi/v2/files/"
                    + new BitBiFileId.BatchFile(SITE, batch2, "nsftrns").encode(), KEY);

            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(response.headers().firstValue("Location"))
                    .hasValue("https://storage.googleapis.com/dfm-dev-uploads/" + key + "?X-Amz-Signature=abc");
            verify(checkpointStorage, never()).presence(anyString());
        }
    }

    @Nested
    @DisplayName("Refusals")
    class Refusals {

        @ParameterizedTest(name = "{0} without a key → 401")
        @ValueSource(strings = {"/sites", "/sites/" + "0199baac-f852-753f-6fc3-7c994fc38654" + "/state",
                "/sites/" + "0199baac-f852-753f-6fc3-7c994fc38654" + "/schema",
                "/sites/" + "0199baac-f852-753f-6fc3-7c994fc38654" + "/snapshot",
                "/sites/" + "0199baac-f852-753f-6fc3-7c994fc38654" + "/changes", "/files/some-file-id"})
        void noKeyIs401(String route) {
            assertThat(get("/api/v1/plugins/bit-bi/v2" + route, null).statusCode()).isEqualTo(401);
        }

        @ParameterizedTest(name = "{0} with a revoked or unknown key → 401")
        @ValueSource(strings = {"/sites", "/sites/" + "0199baac-f852-753f-6fc3-7c994fc38654" + "/state",
                "/sites/" + "0199baac-f852-753f-6fc3-7c994fc38654" + "/schema",
                "/sites/" + "0199baac-f852-753f-6fc3-7c994fc38654" + "/snapshot",
                "/sites/" + "0199baac-f852-753f-6fc3-7c994fc38654" + "/changes", "/files/some-file-id"})
        void revokedKeyIs401(String route) {
            HttpResponse<String> response = get("/api/v1/plugins/bit-bi/v2" + route, "plk_revoked");

            assertThat(response.statusCode()).isEqualTo(401);
            verify(syncStateService, never()).getSyncState(any());
        }

        @ParameterizedTest(name = "{0} of another account's site → 404, the body of an absent one")
        @ValueSource(strings = {"state", "schema", "snapshot", "changes"})
        void aForeignSiteIs404(String route) {
            HttpResponse<String> foreign = get("/api/v1/plugins/bit-bi/v2/sites/" + FOREIGN_SITE + "/" + route, KEY);

            assertThat(foreign.statusCode()).isEqualTo(404);
            WireJson.assertMatches("{\"timestamp\":\"<instant>\",\"status\":404,\"error\":\"Not Found\","
                    + "\"message\":\"Site not found: " + FOREIGN_SITE + "\","
                    + "\"path\":\"/api/v1/plugins/bit-bi/v2/sites/" + FOREIGN_SITE + "/" + route + "\"}",
                    foreign.body());
            verify(syncStateService, never()).getSyncState(FOREIGN_SITE);
            verify(siteSchemaRepository, never()).findBySiteId(FOREIGN_SITE);
            verify(checkpointRepository, never()).findBySiteId(FOREIGN_SITE);
            verify(feedDao, never()).findPage(eq(FOREIGN_SITE), anyLong(), anyLong(), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("An unknown path under /v2 is 404 with a key and 401 without (#384)")
        void unknownPathUnderV2() {
            assertThat(get("/api/v1/plugins/bit-bi/v2/unknown", KEY).statusCode()).isEqualTo(404);
            assertThat(get("/api/v1/plugins/bit-bi/v2/unknown", null).statusCode()).isEqualTo(401);
        }

        @ParameterizedTest(name = "{0} over the limit → 429 with Retry-After")
        @ValueSource(strings = {"/sites", "/sites/" + "0199baac-f852-753f-6fc3-7c994fc38654" + "/state",
                "/sites/" + "0199baac-f852-753f-6fc3-7c994fc38654" + "/schema",
                "/sites/" + "0199baac-f852-753f-6fc3-7c994fc38654" + "/snapshot",
                "/sites/" + "0199baac-f852-753f-6fc3-7c994fc38654" + "/changes", "/files/some-file-id"})
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
            verify(checkpointStorage, never()).presence(anyString());
            verify(presignedUrlService, never()).generatePresignedUrl(anyString(), anyString(), any(Duration.class));
        }
    }

    /** The real v2 controller and the real services behind it. */
    @Configuration(proxyBeanMethods = false)
    @Import({
            JacksonConfiguration.class,
            BitBiV2ApiController.class,
            BitBiV2QueryService.class,
            BitBiV2FileService.class,
            BitBiV2ChangesService.class,
            SiteSchemaService.class
    })
    static class V2Wiring {
    }
}
