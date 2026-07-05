package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.CheckpointService;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.plugin.application.PluginApiKeyService;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.shared.api.ApiRoutes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T3.4 — the Bit BI {@code /sites/{siteId}/files} endpoint serves the reconstructed checkpoint CSV
 * for V2 (Delta) sites, while V1 (legacy) sites keep returning their uploaded files unchanged.
 *
 * <p>{@code store-01} is a seeded site under the plugin account; fixtures pin seeded sites to
 * {@code V1} (022 Task 7), so setup flips it to {@code V2} explicitly. The V1 leg flips it back
 * to prove the legacy path is untouched.</p>
 */
@DisplayName("Bit BI Plugin API — checkpoint CSV files (T3.4)")
class BitBiCheckpointFilesIntegrationTest extends BaseIntegrationTest {

    private static final String API_KEY_HEADER = "X-Plugin-Api-Key";
    private static final String VALID_API_KEY = "plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6";
    private static final UUID ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    /** store-01 — seeded site under ACCOUNT_ID; flipped to V2 in setup, owns a seeded COMPLETED batch. */
    private static final UUID SITE_ID = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    private static final UUID BATCH_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @MockitoBean
    private PluginApiKeyService pluginApiKeyService;

    @Autowired
    private CheckpointService checkpointService;

    @Autowired
    private ChangelogSegmentService changelogSegmentService;

    @Autowired
    private JdbcTemplate jdbc;

    private final String filesPath = ApiRoutes.BITBI_SITES + "/" + SITE_ID + "/files";

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE sites SET client_api_version = 'V2' WHERE id = ?", SITE_ID);

        AccountPlugin plugin = mock(AccountPlugin.class);
        when(plugin.getId()).thenReturn(1L);
        when(plugin.getAccountId()).thenReturn(ACCOUNT_ID);
        when(plugin.isActive()).thenReturn(true);
        when(pluginApiKeyService.validateApiKey(VALID_API_KEY)).thenReturn(Optional.of(plugin));
    }

    private void buildCustomersCheckpoint() {
        List<ChangeRecord> records = List.of(
                rec("customers", Op.INSERT, 1L, key("id", 1L), data("id", 1L, "name", "Ann")),
                rec("customers", Op.INSERT, 2L, key("id", 2L), data("id", 2L, "name", "Bob")));
        changelogSegmentService.persist(SITE_ID, BATCH_ID, "FULL_SNAPSHOT", 1L, records);
        checkpointService.buildCheckpoint(SITE_ID);
    }

    @Test
    @DisplayName("V2 site lists the reconstructed table as <table>.csv.gz")
    void v2SiteListsReconstructedCheckpointCsv() throws Exception {
        buildCustomersCheckpoint();

        mockMvc.perform(get(filesPath).header(API_KEY_HEADER, VALID_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[*].fileName", hasItem("customers.csv.gz")));
    }

    @Test
    @DisplayName("V2 site download streams the gzipped reconstructed CSV")
    void v2SiteDownloadsReconstructedCheckpointCsv() throws Exception {
        buildCustomersCheckpoint();

        byte[] body = mockMvc.perform(get(filesPath + "/customers.csv.gz").header(API_KEY_HEADER, VALID_API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/gzip"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("customers.csv.gz")))
                .andReturn().getResponse().getContentAsByteArray();

        String csv = ungzip(body);
        assertTrue(csv.contains("name"), "header present: " + csv);
        assertTrue(csv.contains("Ann"), "row Ann present: " + csv);
        assertTrue(csv.contains("Bob"), "row Bob present: " + csv);
    }

    @Test
    @DisplayName("V1 site still lists its uploaded files and ignores checkpoints")
    void v1SiteStillListsUploadedFiles() throws Exception {
        buildCustomersCheckpoint();
        jdbc.update("UPDATE sites SET client_api_version = 'V1' WHERE id = ?", SITE_ID);

        mockMvc.perform(get(filesPath).header(API_KEY_HEADER, VALID_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[*].fileName", hasItem("mock-file1.csv")))
                .andExpect(jsonPath("$.files[*].fileName", not(hasItem("customers.csv.gz"))));
    }

    // --- helpers (mirror CheckpointCsvIntegrationTest) ---

    private static ChangeRecord rec(String table, Op op, long seq, Map<String, Value> key, Map<String, Value> data) {
        return ChangeRecord.newBuilder().setTable(table).setOp(op).setSeq(seq)
                .putAllKey(key).putAllData(data).build();
    }

    private static Map<String, Value> key(String col, long v) {
        return Map.of(col, Value.newBuilder().setIntValue(v).build());
    }

    private static Map<String, Value> data(Object... kv) {
        Map<String, Value> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object value = kv[i + 1];
            Value v = value instanceof Long l
                    ? Value.newBuilder().setIntValue(l).build()
                    : Value.newBuilder().setStringValue((String) value).build();
            m.put((String) kv[i], v);
        }
        return m;
    }

    private static String ungzip(byte[] gz) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
