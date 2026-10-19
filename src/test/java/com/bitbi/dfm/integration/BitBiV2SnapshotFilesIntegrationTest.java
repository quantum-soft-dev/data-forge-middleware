package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.DeltaSiteWipeService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.plugin.application.PluginApiKeyService;
import com.bitbi.dfm.plugin.application.PluginRateLimiterService;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.site.application.SiteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /snapshot} and {@code /files/{fileId}} against the real database, the real API key and
 * real objects on LocalStack (issue #387).
 *
 * <p>The bodies are pinned on the production chain by {@code BitBiV2ApiContractTest}; this class
 * holds what only real storage can show: that the redirect leads to the bytes of the object the
 * listing named, that the listing stays at the checkpoint pointer while a build has moved the rows
 * on, that an id keeps leading to its own object after the next build, and that it answers 404 once
 * that object is gone, after a wipe, or for another account.</p>
 *
 * <p>The builds are staged by hand — an object uploaded through the build's own
 * {@link S3CheckpointStorage#uploadParquet}, the row saved, the pointer recorded — in the order
 * {@code CheckpointSnapshotMaterializer} follows: rows first, pointer last. The bytes are not
 * Parquet; nothing here reads them as such, and distinct bytes per build are what make "the same
 * object" checkable.</p>
 */
@DisplayName("Bit BI v2 snapshot and file redirect, wired (#387)")
class BitBiV2SnapshotFilesIntegrationTest extends BaseIntegrationTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    /** store-01 — seeded site under ACCOUNT_ID. */
    private static final UUID SITE_ID = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    /** "Test Account 2" — another account, owner of no site used here. */
    private static final UUID OTHER_ACCOUNT_ID = UUID.fromString("0199bab1-fad2-bf76-c478-eae1f61e1c17");
    private static final String KEY_HEADER = "X-Plugin-Api-Key";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Autowired
    private AccountPluginRepository accountPluginRepository;

    @Autowired
    private PluginApiKeyService pluginApiKeyService;

    @Autowired
    private PluginRateLimiterService rateLimiterService;

    @Autowired
    private DeltaSyncStateService syncStateService;

    @Autowired
    private DeltaSiteWipeService wipeService;

    @Autowired
    private SiteService siteService;

    @Autowired
    private CheckpointRepository checkpointRepository;

    @Autowired
    private S3CheckpointStorage checkpointStorage;

    @Autowired
    private S3Client s3Client;

    @Value("${s3.bucket.name}")
    private String bucket;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    Path scratch;

    private String apiKey;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE sites SET client_api_version = 'V2' WHERE id = ?", SITE_ID);
        // A live session refuses a wipe by design; the seed leaves one open.
        jdbc.update("""
                UPDATE batches SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP AT TIME ZONE 'UTC'
                WHERE site_id = ? AND status = 'IN_PROGRESS'
                """, SITE_ID);
        jdbc.update("DELETE FROM checkpoints WHERE site_id = ?", SITE_ID);
        jdbc.update("DELETE FROM site_sync_state WHERE site_id = ?", SITE_ID);
        jdbc.update("DELETE FROM account_plugins WHERE plugin_id = 'bit-bi' AND account_id IN (?, ?)",
                ACCOUNT_ID, OTHER_ACCOUNT_ID);
        purgeCheckpointPrefix(SITE_ID);
        accountPluginRepository.save(AccountPlugin.activate(ACCOUNT_ID, "bit-bi", Map.of()));
        apiKey = pluginApiKeyService.rotateApiKey(ACCOUNT_ID).value();
        rateLimiterService.resetBucket(ACCOUNT_ID);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM checkpoints WHERE site_id = ?", SITE_ID);
        jdbc.update("DELETE FROM account_plugins WHERE plugin_id = 'bit-bi' AND account_id IN (?, ?)",
                ACCOUNT_ID, OTHER_ACCOUNT_ID);
        purgeCheckpointPrefix(SITE_ID);
        rateLimiterService.resetBucket(ACCOUNT_ID);
        rateLimiterService.resetBucket(OTHER_ACCOUNT_ID);
    }

    /** One table's snapshot written the way a build writes it: object, then row (pointer untouched). */
    private void writeTable(String table, long seq, String content) throws IOException {
        Path file = Files.writeString(scratch.resolve(table + "-" + seq + ".parquet"), content);
        String key = checkpointStorage.uploadParquet(SITE_ID, table, seq, file);
        Checkpoint row = checkpointRepository.findBySiteIdAndTableName(SITE_ID, table)
                .orElseGet(() -> Checkpoint.create(SITE_ID, table, seq, 1L));
        row.update(seq, 1L);
        row.attachParquet(key);
        checkpointRepository.save(row);
    }

    private void movePointer(long seq) {
        syncStateService.advanceWatermark(SITE_ID, seq);
        syncStateService.recordCheckpoint(SITE_ID, seq);
    }

    private JsonNode snapshot() throws Exception {
        String body = mockMvc.perform(get("/api/v1/plugins/bit-bi/v2/sites/" + SITE_ID + "/snapshot")
                        .header(KEY_HEADER, apiKey))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private Map<String, String> fileIds(JsonNode snapshot) {
        Map<String, String> ids = new HashMap<>();
        for (JsonNode table : snapshot.get("tables")) {
            assertThat(table.get("status").asString()).as(table.toString()).isEqualTo("ready");
            ids.put(table.get("table").asString(), table.get("fileId").asString());
        }
        return ids;
    }

    private MvcResult file(String fileId, String key) throws Exception {
        return mockMvc.perform(get("/api/v1/plugins/bit-bi/v2/files/" + fileId).header(KEY_HEADER, key))
                .andReturn();
    }

    /** Follow the redirect the way a consumer does — without the API key — and read the object. */
    private String download(String fileId) throws Exception {
        MvcResult result = file(fileId, apiKey);
        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        String location = Optional.ofNullable(result.getResponse().getHeader("Location")).orElseThrow();
        HttpResponse<String> object = HTTP.send(HttpRequest.newBuilder(URI.create(location)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(object.statusCode()).as("the presigned URL opens the object").isEqualTo(200);
        return object.body();
    }

    @Test
    @DisplayName("The redirect leads to the listed object, at the pointer through a build and after it, then 404 once it is gone")
    void aFileIdStaysPinnedToItsObject() throws Exception {
        writeTable("orders", 100L, "orders@100");
        writeTable("nsftrns", 100L, "nsftrns@100");
        movePointer(100L);

        JsonNode first = snapshot();
        assertThat(first.get("checkpointSeq").asLong()).isEqualTo(100L);
        Map<String, String> ids = fileIds(first);
        assertThat(download(ids.get("orders"))).isEqualTo("orders@100");
        assertThat(download(ids.get("nsftrns"))).isEqualTo("nsftrns@100");

        // The next build has written one table at its seq and not yet moved the pointer.
        writeTable("orders", 200L, "orders@200");
        JsonNode during = snapshot();
        assertThat(during.get("checkpointSeq").asLong()).as("still the pointer").isEqualTo(100L);
        assertThat(fileIds(during)).as("the same set as before the build began").isEqualTo(ids);
        assertThat(download(ids.get("orders"))).as("not the row's new object").isEqualTo("orders@100");

        // The build finishes.
        writeTable("nsftrns", 200L, "nsftrns@200");
        movePointer(200L);
        Map<String, String> next = fileIds(snapshot());
        assertThat(next.get("orders")).isNotEqualTo(ids.get("orders"));
        assertThat(download(next.get("orders"))).isEqualTo("orders@200");
        assertThat(download(ids.get("orders"))).as("the old id still names the old object").isEqualTo("orders@100");

        // The orphan sweep (or retention) removes the superseded object.
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket)
                .key(S3CheckpointStorage.snapshotKey(SITE_ID, "orders", 100L)).build());
        assertThat(file(ids.get("orders"), apiKey).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("A table whose build left nothing at the pointer is listed as missing, not skipped")
    void aTableWithNoObjectAtThePointerIsMissing() throws Exception {
        writeTable("orders", 100L, "orders@100");
        movePointer(100L);
        // A table first seen by the running build: its row is above the pointer, nothing is at it.
        writeTable("tmp_x", 200L, "tmp_x@200");

        JsonNode tables = snapshot().get("tables");

        assertThat(tables).hasSize(2);
        assertThat(tables.get(1).get("table").asString()).isEqualTo("tmp_x");
        assertThat(tables.get(1).get("status").asString()).isEqualTo("missing");
        assertThat(tables.get(1).get("fileId").isNull()).isTrue();
    }

    @Test
    @DisplayName("Another account's key gets 404 for a valid fileId, and so does every id after a wipe")
    void foreignAccountAndWipe() throws Exception {
        writeTable("orders", 100L, "orders@100");
        movePointer(100L);
        String fileId = fileIds(snapshot()).get("orders");

        accountPluginRepository.save(AccountPlugin.activate(OTHER_ACCOUNT_ID, "bit-bi", Map.of()));
        String otherKey = pluginApiKeyService.rotateApiKey(OTHER_ACCOUNT_ID).value();
        rateLimiterService.resetBucket(OTHER_ACCOUNT_ID);
        assertThat(file(fileId, otherKey).getResponse().getStatus()).isEqualTo(404);
        assertThat(file(fileId, apiKey).getResponse().getStatus()).isEqualTo(302);

        wipeService.wipe(siteService.getSite(SITE_ID), DeltaSiteWipeService.Initiator.ADMIN);
        // The new epoch starts again from seq 1 and could reach 100 with other data: the old id
        // must not name it.
        writeTable("orders", 100L, "orders@100-new-epoch");
        movePointer(100L);

        assertThat(file(fileId, apiKey).getResponse().getStatus()).isEqualTo(404);
        assertThat(download(fileIds(snapshot()).get("orders"))).isEqualTo("orders@100-new-epoch");
    }
}
