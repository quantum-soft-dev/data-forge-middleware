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
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T3.4 / issue #113 — the Bit BI {@code /sites/{siteId}/files} endpoint serves reconstructed
 * checkpoint snapshots for the sole supported Delta ingestion path, as Parquet: the checkpoint
 * build stopped materializing the gzipped CSV this endpoint used to expose.
 */
@DisplayName("Bit BI Plugin API — checkpoint Parquet files (T3.4)")
class BitBiCheckpointFilesIntegrationTest extends BaseIntegrationTest {

    private static final String API_KEY_HEADER = "X-Plugin-Api-Key";
    private static final String VALID_API_KEY = "plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6";
    private static final UUID ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    /** store-01 — seeded site under ACCOUNT_ID, owns a seeded COMPLETED batch. */
    private static final UUID SITE_ID = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    private static final UUID BATCH_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final String HISTORICAL_FILE = "mock-file1.csv";
    private static final String HISTORICAL_KEY =
            "a1b2c3d4-e5f6-7890-abcd-ef1234567890/store-01.example.com/2025-10-06/10-00/mock-file1.csv";

    @MockitoBean
    private PluginApiKeyService pluginApiKeyService;

    @Autowired
    private CheckpointService checkpointService;

    @Autowired
    private ChangelogSegmentService changelogSegmentService;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Value("${s3.bucket.name}")
    private String bucketName;

    private final String filesPath = ApiRoutes.BITBI_SITES + "/" + SITE_ID + "/files";

    @BeforeEach
    void setUp() {
        AccountPlugin plugin = mock(AccountPlugin.class);
        when(plugin.getId()).thenReturn(1L);
        when(plugin.getAccountId()).thenReturn(ACCOUNT_ID);
        when(plugin.isActive()).thenReturn(true);
        when(pluginApiKeyService.validateApiKey(VALID_API_KEY)).thenReturn(Optional.of(plugin));
    }

    private void buildCustomersCheckpoint() {
        seedCustomersSchema();
        List<ChangeRecord> records = List.of(
                rec("customers", Op.INSERT, 1L, key("id", 1L), data("id", 1L, "name", "Ann")),
                rec("customers", Op.INSERT, 2L, key("id", 2L), data("id", 2L, "name", "Bob")));
        changelogSegmentService.persist(SITE_ID, BATCH_ID, "FULL_SNAPSHOT", 1L, records);
        checkpointService.buildCheckpoint(SITE_ID);
    }

    /** Parquet is typed from the declared schema, so the site needs one to materialize at all. */
    private void seedCustomersSchema() {
        String schemaJson = """
                {
                  "tables": {
                    "customers": {
                      "columns": [
                        {"name": "id", "type": "bigint", "nullable": false},
                        {"name": "name", "type": "varchar(255)", "nullable": true}
                      ],
                      "primaryKey": ["id"],
                      "uniqueKeys": []
                    }
                  }
                }
                """;
        jdbc.update("DELETE FROM site_schemas WHERE site_id = ?", SITE_ID);
        jdbc.update("INSERT INTO site_schemas (id, site_id, schema_data, schema_version, created_at, updated_at) "
                        + "VALUES (?, ?, ?::jsonb, 1, now() AT TIME ZONE 'UTC', now() AT TIME ZONE 'UTC')",
                UUID.randomUUID(), SITE_ID, schemaJson);
    }

    @Test
    @DisplayName("V2 site lists the reconstructed table as <table>.parquet")
    void v2SiteListsReconstructedCheckpointParquet() throws Exception {
        buildCustomersCheckpoint();

        mockMvc.perform(get(filesPath).header(API_KEY_HEADER, VALID_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[*].fileName", hasItem("customers.parquet")));
    }

    @Test
    @DisplayName("V2 site download streams the reconstructed Parquet")
    void v2SiteDownloadsReconstructedCheckpointParquet() throws Exception {
        buildCustomersCheckpoint();

        byte[] body = mockMvc.perform(get(filesPath + "/customers.parquet").header(API_KEY_HEADER, VALID_API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/vnd.apache.parquet"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("customers.parquet")))
                .andReturn().getResponse().getContentAsByteArray();

        assertTrue(body.length > 4, "a Parquet file was streamed");
        assertEquals("PAR1", new String(body, 0, 4, StandardCharsets.US_ASCII), "Parquet magic header");
    }

    @Test
    @DisplayName("The retired <table>.csv.gz name is no longer served")
    void v2SiteNoLongerServesTheCheckpointAsCsv() throws Exception {
        buildCustomersCheckpoint();

        mockMvc.perform(get(filesPath).header(API_KEY_HEADER, VALID_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[*].fileName", not(hasItem("customers.csv.gz"))));

        mockMvc.perform(get(filesPath + "/customers.csv.gz").header(API_KEY_HEADER, VALID_API_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Site without checkpoints lists historical uploaded CSV files")
    void siteWithoutCheckpointsListsHistoricalUploadedFiles() throws Exception {
        mockMvc.perform(get(filesPath).header(API_KEY_HEADER, VALID_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[*].fileName", hasItem(HISTORICAL_FILE)));
    }

    @Test
    @DisplayName("Site without a matching checkpoint downloads a historical uploaded CSV")
    void siteWithoutMatchingCheckpointDownloadsHistoricalUploadedFile() throws Exception {
        byte[] body = "id,name\n1,Historical\n".getBytes(StandardCharsets.UTF_8);
        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucketName).key(HISTORICAL_KEY).build(),
                RequestBody.fromBytes(body));

        mockMvc.perform(get(filesPath + "/" + HISTORICAL_FILE).header(API_KEY_HEADER, VALID_API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().bytes(body));
    }

    // --- helpers (mirror CheckpointParquetIntegrationTest) ---

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
}
