package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.DeltaRebaselineService;
import com.bitbi.dfm.delta.application.DeltaSiteWipeService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.plugin.application.PluginApiKeyService;
import com.bitbi.dfm.plugin.application.PluginRateLimiterService;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.application.SiteService;
import com.bitbi.dfm.upload.presentation.dto.SchemaUploadRequestDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Bit BI v2 read surface against the real database, the real API-key validation and the real
 * operations that move a site's epoch (issue #386).
 *
 * <p>The contract of the bodies is pinned on the production chain by
 * {@code BitBiV2ApiContractTest}; this class holds what only the wired application can show: that
 * {@code epoch} is {@code site_sync_state.baseline_epoch} and moves after a real history wipe
 * <em>and</em> after a real re-baseline reset — where {@code generation}, the wire epoch, would
 * stay put — that a key stops opening the surface the moment its activation is revoked, and that
 * {@code /schema} derives identity from a schema stored through {@code SubmitSchema}'s own path.</p>
 */
@DisplayName("Bit BI v2 state and schema, wired (#386)")
class BitBiV2StateIntegrationTest extends BaseIntegrationTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    /** store-01 — seeded site under ACCOUNT_ID. */
    private static final UUID SITE_ID = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    /** store-03 — seeded site under another account. */
    private static final UUID FOREIGN_SITE_ID = UUID.fromString("0199bab0-ca3b-e41c-5521-2f4b33fda8b6");
    private static final String KEY_HEADER = "X-Plugin-Api-Key";

    @Autowired
    private AccountPluginRepository accountPluginRepository;

    @Autowired
    private PluginApiKeyService pluginApiKeyService;

    @Autowired
    private PluginRateLimiterService rateLimiterService;

    @Autowired
    private DeltaSiteWipeService wipeService;

    @Autowired
    private DeltaRebaselineService rebaselineService;

    @Autowired
    private DeltaSyncStateService syncStateService;

    @Autowired
    private SiteSchemaService siteSchemaService;

    @Autowired
    private SiteService siteService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private String apiKey;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE sites SET client_api_version = 'V2' WHERE id = ?", SITE_ID);
        // A live session refuses a wipe by design; the seed leaves one open.
        jdbc.update("""
                UPDATE batches SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP AT TIME ZONE 'UTC'
                WHERE site_id = ? AND status = 'IN_PROGRESS'
                """, SITE_ID);
        jdbc.update("DELETE FROM site_sync_state WHERE site_id = ?", SITE_ID);
        jdbc.update("DELETE FROM site_schemas WHERE site_id = ?", SITE_ID);
        jdbc.update("DELETE FROM account_plugins WHERE account_id = ? AND plugin_id = 'bit-bi'", ACCOUNT_ID);
        purgeEgressPrefix(SITE_ID);
        purgeCheckpointPrefix(SITE_ID);
        accountPluginRepository.save(AccountPlugin.activate(ACCOUNT_ID, "bit-bi", Map.of()));
        apiKey = pluginApiKeyService.rotateApiKey(ACCOUNT_ID).value();
        rateLimiterService.resetBucket(ACCOUNT_ID);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM account_plugins WHERE account_id = ? AND plugin_id = 'bit-bi'", ACCOUNT_ID);
        rateLimiterService.resetBucket(ACCOUNT_ID);
    }

    private JsonNode getJson(String path) throws Exception {
        String body = mockMvc.perform(get(path).header(KEY_HEADER, apiKey))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode state() throws Exception {
        return getJson("/api/v1/plugins/bit-bi/v2/sites/" + SITE_ID + "/state");
    }

    private long generation() {
        return syncStateService.getSyncState(SITE_ID).generation();
    }

    @Test
    @DisplayName("epoch moves after a history wipe and again after a re-baseline; generation only after the wipe")
    void epochMovesOnWipeAndOnRebaseline() throws Exception {
        syncStateService.advanceWatermark(SITE_ID, 40L);
        syncStateService.recordCheckpoint(SITE_ID, 30L);

        JsonNode before = state();
        long epoch = before.get("epoch").asLong();
        assertThat(before.get("lastAppliedSeq").asLong()).isEqualTo(40L);
        assertThat(before.get("checkpointSeq").asLong()).isEqualTo(30L);

        wipeService.wipe(siteService.getSite(SITE_ID), DeltaSiteWipeService.Initiator.ADMIN);
        JsonNode afterWipe = state();
        assertThat(afterWipe.get("epoch").asLong()).as("a wipe moves the epoch").isEqualTo(epoch + 1);
        assertThat(afterWipe.get("checkpointSeq").asLong()).isZero();
        assertThat(afterWipe.get("lastAppliedSeq").asLong()).isZero();
        long generationAfterWipe = generation();

        syncStateService.advanceWatermark(SITE_ID, 12L);
        syncStateService.recordCheckpoint(SITE_ID, 12L);
        rebaselineService.reset(SITE_ID, 13L);
        JsonNode afterRebaseline = state();
        assertThat(afterRebaseline.get("epoch").asLong()).as("a re-baseline moves the epoch").isEqualTo(epoch + 2);
        assertThat(afterRebaseline.get("checkpointSeq").asLong()).isZero();
        assertThat(generation())
                .as("the wire generation does not move on a re-baseline, which is why it is not the epoch")
                .isEqualTo(generationAfterWipe);
    }

    @Test
    @DisplayName("/state and /schema of another account's site answer 404, as an absent site does")
    void aForeignSiteIsNotFound() throws Exception {
        for (String route : List.of("state", "schema")) {
            mockMvc.perform(get("/api/v1/plugins/bit-bi/v2/sites/" + FOREIGN_SITE_ID + "/" + route)
                            .header(KEY_HEADER, apiKey))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/plugins/bit-bi/v2/sites/" + UUID.randomUUID() + "/" + route)
                            .header(KEY_HEADER, apiKey))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("A key stops opening v2 when it is rotated away and when the activation is deactivated")
    void aRevokedKeyIs401() throws Exception {
        mockMvc.perform(get("/api/v1/plugins/bit-bi/v2/sites").header(KEY_HEADER, apiKey))
                .andExpect(status().isOk());

        String rotated = pluginApiKeyService.rotateApiKey(ACCOUNT_ID).value();
        mockMvc.perform(get("/api/v1/plugins/bit-bi/v2/sites").header(KEY_HEADER, apiKey))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/plugins/bit-bi/v2/sites").header(KEY_HEADER, rotated))
                .andExpect(status().isOk());

        AccountPlugin activation = accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, "bit-bi")
                .orElseThrow();
        activation.deactivate();
        accountPluginRepository.save(activation);
        mockMvc.perform(get("/api/v1/plugins/bit-bi/v2/sites").header(KEY_HEADER, rotated))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("/schema derives identity from the stored schema, and /state reports its version")
    void schemaIdentityFromTheStoredSchema() throws Exception {
        siteSchemaService.upsertSchema(SITE_ID, new SchemaUploadRequestDto(Map.of(
                "nsftrns", new SchemaUploadRequestDto.TableSchemaDto(
                        List.of(new SchemaUploadRequestDto.ColumnDto("TR_NUM", "integer", true)),
                        List.of(), List.of()),
                "orders", new SchemaUploadRequestDto.TableSchemaDto(
                        List.of(new SchemaUploadRequestDto.ColumnDto("id", "bigint", false)),
                        List.of("id"), List.of()))));

        JsonNode schema = getJson("/api/v1/plugins/bit-bi/v2/sites/" + SITE_ID + "/schema");

        assertThat(schema.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(schema.get("tables")).hasSize(2);
        assertThat(schema.get("tables").get(0).get("table").asString()).isEqualTo("nsftrns");
        assertThat(schema.get("tables").get(0).get("identity").asString()).isEqualTo("row_hash");
        assertThat(schema.get("tables").get(1).get("table").asString()).isEqualTo("orders");
        assertThat(schema.get("tables").get(1).get("identity").asString()).isEqualTo("primary_key");
        assertThat(state().get("schemaVersion").asInt()).isEqualTo(1);
    }
}
