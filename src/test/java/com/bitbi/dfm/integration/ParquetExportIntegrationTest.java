package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.plugin.application.DownloadLinkPurgeScheduler;
import com.bitbi.dfm.plugin.application.DownloadLinkService;
import com.bitbi.dfm.plugin.application.PluginActivationService;
import com.bitbi.dfm.plugin.application.PluginActivationService.ActivationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration suite for the Parquet Export plugin (028, T014):
 * activation → listing under Basic Auth → one-time download → single-use guarantees →
 * deactivation → purge → cross-account isolation.
 */
@DisplayName("Parquet Export Integration Tests")
class ParquetExportIntegrationTest extends BaseIntegrationTest {

    private static final UUID ACCOUNT_1 = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID ACCOUNT_2 = UUID.fromString("0199bab1-fad2-bf76-c478-eae1f61e1c17");
    private static final UUID SITE_STORE_01 = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    private static final String FILES_PATH = "/api/v1/plugins/parquet-export/files";

    @Autowired
    private PluginActivationService activationService;

    @Autowired
    private DownloadLinkService downloadLinkService;

    @Autowired
    private DownloadLinkPurgeScheduler purgeScheduler;

    @Autowired
    private CheckpointRepository checkpointRepository;

    @Autowired
    private S3CheckpointStorage storage;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private String login;
    private String password;

    @BeforeEach
    void activateAndSeed() {
        jdbc.update("DELETE FROM download_links");
        jdbc.update("DELETE FROM account_plugins WHERE plugin_id = 'parquet-export'");

        ActivationResult activation = activationService.activate(ACCOUNT_1, "parquet-export", Map.of());
        assertNotNull(activation.apiKey(), "first activation must return login:password once");
        String[] credentials = activation.apiKey().split(":", 2);
        login = credentials[0];
        password = credentials[1];

        // Seed one egressed delta segment (with a real S3 object) and one checkpoint parquet.
        jdbc.update("DELETE FROM changelog_segments WHERE site_id = ?", SITE_STORE_01);
        jdbc.update("DELETE FROM checkpoints WHERE site_id = ?", SITE_STORE_01);
        jdbc.update("""
                INSERT INTO changelog_segments
                    (id, site_id, batch_id, first_seq, last_seq, record_count, content_hash, s3_key,
                     mode, created_at, egress_at, plugin_sql_at, stats)
                VALUES (?, ?, ?, 100, 250, 5, 'hash', 'delta/seg.pb.gz', 'DELTA',
                        now(), now(), now(), '{"orders": {"inserts": 5, "updates": 0, "deletes": 0}}'::jsonb)
                """, UUID.randomUUID(), SITE_STORE_01,
                UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")); // COMPLETED batch of store-01 (test-data.sql)
        storage.uploadDelta(SITE_STORE_01, "orders", 100, 250, "parquet-bytes-delta".getBytes(StandardCharsets.UTF_8));

        String parquetKey = storage.uploadParquet(SITE_STORE_01, "orders", 250,
                "parquet-bytes-checkpoint".getBytes(StandardCharsets.UTF_8));
        Checkpoint checkpoint = Checkpoint.create(SITE_STORE_01, "orders", 250, 5);
        checkpoint.attachParquet(parquetKey);
        checkpointRepository.save(checkpoint);
    }

    private String basicAuth() {
        return "Basic " + Base64.getEncoder()
                .encodeToString((login + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Should not re-issue credentials on idempotent re-activation")
    void shouldNotReissueCredentialsOnReactivation() {
        ActivationResult second = activationService.activate(ACCOUNT_1, "parquet-export", Map.of());
        assertNull(second.apiKey(), "already-active activation must not re-show credentials");

        String storedData = jdbc.queryForObject(
                "SELECT plugin_data::text FROM account_plugins WHERE account_id = ? AND plugin_id = 'parquet-export'",
                String.class, ACCOUNT_1);
        assertNotNull(storedData);
        assertTrue(storedData.contains("\"login\""));
        assertTrue(storedData.contains("passwordHash"));
        assertFalse(storedData.contains(password), "raw password must never be persisted");
    }

    @Test
    @DisplayName("Should list delta + checkpoint files and register one link per file")
    void shouldListFilesAndRegisterLinks() throws Exception {
        mockMvc.perform(get(FILES_PATH).header("Authorization", basicAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[?(@.type=='delta')].fileName").value(
                        org.hamcrest.Matchers.hasItem("orders_seq100-250.parquet")))
                .andExpect(jsonPath("$.files[?(@.type=='checkpoint')].fileName").value(
                        org.hamcrest.Matchers.hasItem("orders_seq250.parquet")));

        Integer linkCount = jdbc.queryForObject("SELECT count(*) FROM download_links", Integer.class);
        assertEquals(2, linkCount);
    }

    @Test
    @DisplayName("Should redirect once and answer 410 on the second attempt")
    void shouldServeOneTimeDownload() throws Exception {
        String downloadUrl = firstDownloadUrl();
        String path = downloadUrl.substring(downloadUrl.indexOf("/api/v1/"));

        MvcResult redirect = mockMvc.perform(get(path))
                .andExpect(status().isFound())
                .andReturn();
        String location = redirect.getResponse().getHeader("Location");
        assertNotNull(location);
        assertTrue(location.startsWith("http"), "Location must be a presigned S3 URL: " + location);
        assertTrue(location.contains("X-Amz-"), "Location must be presigned: " + location);

        mockMvc.perform(get(path)).andExpect(status().isGone());
    }

    @Test
    @DisplayName("Should return 404 for an unknown token")
    void shouldReturn404ForUnknownToken() throws Exception {
        mockMvc.perform(get(FILES_PATH.replace("/files", "/download/")
                        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Should let exactly one concurrent consumer win")
    void shouldConsumeAtomicallyUnderConcurrency() throws Exception {
        String downloadUrl = firstDownloadUrl();
        String token = downloadUrl.substring(downloadUrl.lastIndexOf('/') + 1);

        int threads = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger gone = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        start.await();
                        downloadLinkService.consume(token);
                        wins.incrementAndGet();
                    } catch (DownloadLinkService.LinkGoneException e) {
                        gone.incrementAndGet();
                    } catch (Exception e) {
                        // interrupted etc. — counted as neither
                    }
                });
            }
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, wins.get(), "exactly one consumer must win the redirect");
        assertEquals(threads - 1, gone.get(), "all others must observe 410 Gone");
    }

    @Test
    @DisplayName("Should block listing and unconsumed links after deactivation")
    void shouldBlockAfterDeactivation() throws Exception {
        String downloadUrl = firstDownloadUrl();
        String path = downloadUrl.substring(downloadUrl.indexOf("/api/v1/"));

        activationService.deactivate(ACCOUNT_1, "parquet-export");

        mockMvc.perform(get(FILES_PATH).header("Authorization", basicAuth()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Basic realm=\"parquet-export\""));

        mockMvc.perform(get(path)).andExpect(status().isGone());
    }

    @Test
    @DisplayName("Should not show account 1 files to account 2")
    void shouldIsolateAccounts() throws Exception {
        ActivationResult activation2 = activationService.activate(ACCOUNT_2, "parquet-export", Map.of());
        assertNotNull(activation2.apiKey());
        String auth2 = "Basic " + Base64.getEncoder()
                .encodeToString(activation2.apiKey().getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get(FILES_PATH).header("Authorization", auth2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[?(@.siteId=='" + SITE_STORE_01 + "')]").isEmpty());
    }

    @Test
    @DisplayName("Should purge only consumed/expired links older than retention")
    void shouldPurgeStaleLinks() throws Exception {
        firstDownloadUrl(); // registers 2 fresh links
        Long accountPluginId = jdbc.queryForObject(
                "SELECT id FROM account_plugins WHERE account_id = ? AND plugin_id = 'parquet-export'",
                Long.class, ACCOUNT_1);
        jdbc.update("""
                INSERT INTO download_links (id, token, account_plugin_id, s3_key, file_name,
                                            expires_at, consumed_at, created_at)
                VALUES (?, 'old-consumed-token-1234567890123456789012', ?, 'k', 'f.parquet',
                        now() - interval '9 days', now() - interval '9 days', now() - interval '10 days')
                """, UUID.randomUUID(), accountPluginId);

        purgeScheduler.purgeStaleLinks();

        Integer oldRows = jdbc.queryForObject(
                "SELECT count(*) FROM download_links WHERE token = 'old-consumed-token-1234567890123456789012'",
                Integer.class);
        assertEquals(0, oldRows, "aged consumed link must be purged");
        Integer freshRows = jdbc.queryForObject("SELECT count(*) FROM download_links", Integer.class);
        assertTrue(freshRows >= 2, "fresh unconsumed links must be retained");
    }

    /** Perform a listing and return the first file's one-time download URL. */
    private String firstDownloadUrl() throws Exception {
        MvcResult result = mockMvc.perform(get(FILES_PATH).header("Authorization", basicAuth()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode files = body.get("files");
        assertTrue(files.size() >= 1, "expected at least one listed file");
        return files.get(0).get("downloadUrl").asText();
    }
}
