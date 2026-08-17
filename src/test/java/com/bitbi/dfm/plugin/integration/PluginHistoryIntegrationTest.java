package com.bitbi.dfm.plugin.integration;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.bitbi.dfm.plugin.domain.*;
import com.bitbi.dfm.shared.domain.events.BatchCompletedEvent;
import com.bitbi.dfm.upload.domain.FileChecksum;
import com.bitbi.dfm.upload.domain.UploadedFile;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.bitbi.dfm.plugin.application.PluginAuditService;
import com.bitbi.dfm.plugin.application.PluginHistoryService;
import com.bitbi.dfm.plugin.application.SqlGenerationService;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Plugin History Management feature.
 *
 * <p>Tests the end-to-end flows for:</p>
 * <ul>
 *   <li>US1: Viewing SQL generation history with pagination</li>
 *   <li>US2: Clearing history (DB records + S3 files)</li>
 *   <li>US3: Regenerating SQL for a batch</li>
 * </ul>
 *
 * <p>Feature 014: Plugin History Management</p>
 */
@DisplayName("Plugin History Integration Tests")
class PluginHistoryIntegrationTest extends BaseIntegrationTest {

    /**
     * Mock admin token for Auth0 authentication.
     * TestSecurityConfig accepts this token for admin endpoints.
     */
    private static final String ADMIN_TOKEN = "Bearer mock.admin.jwt.token";

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private AccountPluginRepository accountPluginRepository;

    @Autowired
    private PluginSqlGenerationRepository pluginSqlGenerationRepository;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private UploadedFileRepository uploadedFileRepository;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private PluginHistoryService pluginHistoryService;

    @Autowired
    private SqlGenerationService sqlGenerationService;

    @Autowired
    private PluginAuditService pluginAuditService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${aws.s3.bucket-name:data-forge-test-bucket}")
    private String bucketName;

    private static final String PLUGIN_ID = "bit-bi";
    private static final UUID TEST_ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID TEST_SITE_ID = UUID.fromString("b2c3d4e5-f6a7-8901-bcde-f12345678901");
    private static final String TEST_SITE_DOMAIN = "admin-site.example.com";

    private AccountPlugin testAccountPlugin;

    /**
     * Set by the methods that audit against an account of their own, so their rows can be removed
     * even when the assertion in between them fails. {@code plugin_audit_logs} is partitioned and
     * {@code test-data.sql} never touches it.
     */
    private UUID ownAuditAccountId;

    @BeforeEach
    void setUp() {
        // Activate Bit BI plugin for test account
        testAccountPlugin = AccountPlugin.activate(TEST_ACCOUNT_ID, PLUGIN_ID,
                Map.of("tenantId", "test-tenant"));
        accountPluginRepository.save(testAccountPlugin);
    }

    @AfterEach
    void removeOwnAuditRows() {
        if (ownAuditAccountId != null) {
            jdbcTemplate.update("DELETE FROM plugin_audit_logs WHERE account_id = ?", ownAuditAccountId);
        }
    }

    @Test
    @DisplayName("Should not audit a regeneration that rolled back")
    void shouldNotAuditRolledBackRegeneration() {
        // The invariant: SQL_REGENERATION_COMPLETED is a deferred entry, so a caller whose
        // transaction rolls back must leave nothing claiming the regeneration completed.
        //
        // Published through PluginAuditService — the production writer of this action type — into
        // a transaction that then fails, rather than through PluginHistoryService.regenerateSql().
        // Two reasons, both from issue #172:
        //
        //  1. The old vehicle asserts nothing. Since #164 SqlGenerationService.regenerateForBatch
        //     refuses to run with a transaction active, and regenerateSql() is @Transactional, so
        //     it throws before it regenerates anything — an IllegalStateException the old
        //     assertThatThrownBy(...).isInstanceOf(IllegalStateException.class) accepted as the
        //     caller's own failure. Zero rows then meant "nothing ran", not "the rollback took the
        //     audit with it". That regression is #190; when it is fixed the regeneration will run
        //     outside the caller's transaction, so no rollback will span it and this test could
        //     never be routed back through it.
        //  2. The old fixture is what actually went red in CI. Both recorded failures were
        //     AssertionError at the `orElseThrow` of generateSqlForBatch — the #174 memory-pressure
        //     abort returning Optional.empty(), not the audit assertion. Nothing about this
        //     invariant needs a batch, a CSV or S3, so none is built.
        //
        // The account is this method's own, so the count names what this method produced rather
        // than every row the shared account has (the second candidate cause on #172), and no
        // DELETE-then-count window remains for a neighbour to land in.
        ownAuditAccountId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            logRegenerationCompleted(batchId);
            throw new IllegalStateException("caller fails after regenerating");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("caller fails after regenerating");

        // during() so a late async write cannot be mistaken for absence.
        await().during(ofSeconds(2)).atMost(ofSeconds(6)).untilAsserted(() ->
                assertThat(regenerationCompletedEntries())
                        .as("the regeneration was rolled back, so nothing may claim it completed")
                        .isZero());

        // The same publication under a committing caller, so "zero" above can never be zero
        // because the write is broken, dropped or never wired — which is exactly how this test
        // came to assert nothing at all.
        transactionTemplate.executeWithoutResult(status -> logRegenerationCompleted(batchId));

        await().atMost(ofSeconds(10)).untilAsserted(() ->
                assertThat(regenerationCompletedEntries())
                        .as("a committed regeneration must be on record")
                        .isOne());
    }

    @Test
    @DisplayName("Should record a clear that rolled back after its S3 files were already deleted")
    void shouldAuditRolledBackHistoryClear() {
        // clearHistory() deletes the S3 objects before its transaction commits, and no rollback
        // brings them back. Withholding the success entry is right — but staying silent is not:
        // the rows come back pointing at files that no longer exist, and that has to be on record.
        Batch baseline = createBatchWithCsvFile("rollback-clear-baseline.csv",
                "id,name,email\n1,Alice,alice@example.com");
        sqlGenerationService.generateSqlForBatch(baseline.getId(), testAccountPlugin.getId());

        Batch batch = createBatchWithCsvFile("rollback-clear.csv",
                "id,name,email\n1,Alice,alice@example.com\n2,Bob,bob@example.com");
        UUID generationId = sqlGenerationService
                .generateSqlForBatch(batch.getId(), testAccountPlugin.getId())
                .orElseThrow(() -> new AssertionError("fixture did not produce a generation"))
                .getId();

        jdbcTemplate.update(
                "DELETE FROM plugin_audit_logs WHERE account_id = ? AND action_type = ?",
                TEST_ACCOUNT_ID, PluginActionType.PLUGIN_HISTORY_CLEARED.name());

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            pluginHistoryService.clearHistory(PLUGIN_ID, TEST_ACCOUNT_ID);
            throw new IllegalStateException("caller fails after clearing");
        })).isInstanceOf(IllegalStateException.class);

        await().atMost(ofSeconds(10)).untilAsserted(() -> {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT success, error_message FROM plugin_audit_logs " +
                            "WHERE account_id = ? AND action_type = ?",
                    TEST_ACCOUNT_ID, PluginActionType.PLUGIN_HISTORY_CLEARED.name());
            assertThat(rows)
                    .as("the orphaned S3 files must be on record, and nothing may claim success")
                    .hasSize(1);
            assertThat(rows.get(0).get("success")).isEqualTo(false);
            assertThat((String) rows.get(0).get("error_message")).contains("S3");
        });

        assertThat(pluginSqlGenerationRepository.findById(generationId))
                .as("the rollback restored the row the deleted file belonged to")
                .isPresent();
    }

    // ==================== User Story 1: View History ====================

    @Nested
    @DisplayName("View SQL Generation History (US1)")
    @Disabled("Requires async SQL generation to complete - run manually with extended timeout")
    class ViewHistory {

        @Test
        @DisplayName("T016: Should return paginated list of generations via REST endpoint")
        void shouldReturnPaginatedListOfGenerations() throws Exception {
            // Given - Create SQL generations
            createSqlGenerationViaEvent();
            Thread.sleep(1000); // Wait for async processing

            // When - Call API
            MvcResult result = mockMvc.perform(get("/api/v1/admin/plugins/{pluginId}/accounts/{accountId}/generations",
                            PLUGIN_ID, TEST_ACCOUNT_ID)
                            .param("page", "0")
                            .param("size", "20")
                            .header("Authorization", ADMIN_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.totalElements").isNumber())
                    .andReturn();

            // Then - Verify response structure
            String response = result.getResponse().getContentAsString();
            assertThat(response).contains("siteDomain");
            assertThat(response).contains("statementCount");
        }

        @Test
        @DisplayName("Should return SQL content with pagination")
        void shouldReturnSqlContentWithPagination() throws Exception {
            // Given - Create SQL generation with content
            createSqlGenerationViaEvent();
            Thread.sleep(1000);

            List<PluginSqlGeneration> generations = pluginSqlGenerationRepository.findBySiteId(TEST_SITE_ID);
            assertThat(generations).isNotEmpty();
            UUID generationId = generations.get(0).getId();

            // When - Call content API
            mockMvc.perform(get("/api/v1/admin/plugins/{pluginId}/accounts/{accountId}/generations/{id}/content",
                            PLUGIN_ID, TEST_ACCOUNT_ID, generationId)
                            .param("page", "0")
                            .param("size", "50")
                            .header("Authorization", ADMIN_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.generationId").value(generationId.toString()))
                    .andExpect(jsonPath("$.statements").isArray())
                    .andExpect(jsonPath("$.totalStatements").isNumber());
        }

        @Test
        @DisplayName("Should download SQL file")
        void shouldDownloadSqlFile() throws Exception {
            // Given - Create SQL generation
            createSqlGenerationViaEvent();
            Thread.sleep(1000);

            List<PluginSqlGeneration> generations = pluginSqlGenerationRepository.findBySiteId(TEST_SITE_ID);
            assertThat(generations).isNotEmpty();
            UUID generationId = generations.get(0).getId();

            // When - Call download endpoint
            mockMvc.perform(get("/api/v1/admin/plugins/{pluginId}/accounts/{accountId}/generations/{id}/download",
                            PLUGIN_ID, TEST_ACCOUNT_ID, generationId)
                            .header("Authorization", ADMIN_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("Content-Disposition"))
                    .andExpect(content().contentType("application/sql"));
        }
    }

    // ==================== User Story 2: Clear History ====================

    @Nested
    @DisplayName("Clear Plugin History (US2)")
    @Disabled("Requires async SQL generation to complete - run manually with extended timeout")
    class ClearHistory {

        @Test
        @DisplayName("T034: Should delete S3 files and database records when clearing history")
        void shouldDeleteS3FilesAndDatabaseRecords() throws Exception {
            // Given - Create SQL generation with S3 file
            createSqlGenerationViaEvent();
            Thread.sleep(1000);

            List<PluginSqlGeneration> generationsBefore = pluginSqlGenerationRepository.findBySiteId(TEST_SITE_ID);
            assertThat(generationsBefore).isNotEmpty();
            String s3Key = generationsBefore.get(0).getS3Key();

            // Verify S3 file exists
            assertS3ObjectExists(s3Key);

            // When - Clear history
            mockMvc.perform(delete("/api/v1/admin/plugins/{pluginId}/accounts/{accountId}/history",
                            PLUGIN_ID, TEST_ACCOUNT_ID)
                            .header("Authorization", ADMIN_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.deletedGenerations").isNumber())
                    .andExpect(jsonPath("$.deletedFilesCount").isNumber())
                    .andExpect(jsonPath("$.pluginDeactivated").value(true));

            // Then - Verify records deleted
            List<PluginSqlGeneration> generationsAfter = pluginSqlGenerationRepository.findBySiteId(TEST_SITE_ID);
            assertThat(generationsAfter).isEmpty();

            // Verify S3 file deleted
            assertS3ObjectDoesNotExist(s3Key);

            // Verify plugin deactivated
            AccountPlugin updatedPlugin = accountPluginRepository.findByAccountIdAndPluginId(TEST_ACCOUNT_ID, PLUGIN_ID)
                    .orElseThrow();
            assertThat(updatedPlugin.isActive()).isFalse();
        }

        @Test
        @DisplayName("Should return history summary before clearing")
        void shouldReturnHistorySummaryBeforeClearing() throws Exception {
            // Given - Create SQL generation
            createSqlGenerationViaEvent();
            Thread.sleep(1000);

            // When - Get summary
            mockMvc.perform(get("/api/v1/admin/plugins/{pluginId}/accounts/{accountId}/history/summary",
                            PLUGIN_ID, TEST_ACCOUNT_ID)
                            .header("Authorization", ADMIN_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.generationCount").isNumber())
                    .andExpect(jsonPath("$.totalFileSizeBytes").isNumber())
                    .andExpect(jsonPath("$.pluginWillBeDeactivated").value(true));
        }
    }

    // ==================== User Story 3: Regenerate SQL ====================

    @Nested
    @DisplayName("Regenerate SQL (US3)")
    @Disabled("Regeneration not yet implemented - requires SqlGenerationService integration")
    class RegenerateSql {

        @Test
        @DisplayName("T047: Should regenerate SQL and mark original as superseded")
        void shouldRegenerateSqlAndMarkOriginalAsSuperseded() throws Exception {
            // Given - Create SQL generation
            createSqlGenerationViaEvent();
            Thread.sleep(1000);

            List<PluginSqlGeneration> generations = pluginSqlGenerationRepository.findBySiteId(TEST_SITE_ID);
            assertThat(generations).isNotEmpty();
            UUID originalGenerationId = generations.get(0).getId();

            // When - Regenerate
            mockMvc.perform(post("/api/v1/admin/plugins/{pluginId}/accounts/{accountId}/generations/{id}/regenerate",
                            PLUGIN_ID, TEST_ACCOUNT_ID, originalGenerationId)
                            .header("Authorization", ADMIN_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.originalGenerationId").value(originalGenerationId.toString()))
                    .andExpect(jsonPath("$.newGenerationId").exists());

            // Then - Verify original marked as superseded
            PluginSqlGeneration originalGeneration = pluginSqlGenerationRepository.findById(originalGenerationId)
                    .orElseThrow();
            assertThat(originalGeneration.isSuperseded()).isTrue();
            assertThat(originalGeneration.getSupersededBy()).isNotNull();

            // Verify new generation exists and is active
            UUID newGenerationId = originalGeneration.getSupersededBy();
            PluginSqlGeneration newGeneration = pluginSqlGenerationRepository.findById(newGenerationId)
                    .orElseThrow();
            assertThat(newGeneration.isSuperseded()).isFalse();
        }

        @Test
        @DisplayName("Should reject regeneration for already superseded generation")
        void shouldRejectRegenerationForAlreadySupersededGeneration() throws Exception {
            // Given - Create and manually supersede a generation
            createSqlGenerationViaEvent();
            Thread.sleep(1000);

            List<PluginSqlGeneration> generations = pluginSqlGenerationRepository.findBySiteId(TEST_SITE_ID);
            assertThat(generations).isNotEmpty();

            PluginSqlGeneration generation = generations.get(0);
            generation.markAsSuperseded(UUID.randomUUID());
            pluginSqlGenerationRepository.save(generation);

            // When/Then - Attempt regeneration should fail
            mockMvc.perform(post("/api/v1/admin/plugins/{pluginId}/accounts/{accountId}/generations/{id}/regenerate",
                            PLUGIN_ID, TEST_ACCOUNT_ID, generation.getId())
                            .header("Authorization", ADMIN_TOKEN))
                    .andExpect(status().isBadRequest());
        }
    }

    // ==================== Feature 015: Reinit ====================

    @Nested
    @DisplayName("Reinit Plugin SQL State (Feature 015)")
    @Disabled("Requires async SQL generation to complete - run manually with extended timeout")
    class ReinitPlugin {

        private static final String USER_TOKEN = "Bearer mock.user.jwt.token";

        @Test
        @DisplayName("T021: Should reinit and regenerate SQL via REST endpoint")
        void shouldReinitAndRegenerateSqlViaRestEndpoint() throws Exception {
            // Given - Create SQL generation first
            createSqlGenerationViaEvent();
            Thread.sleep(1000);

            List<PluginSqlGeneration> generationsBefore = pluginSqlGenerationRepository.findBySiteId(TEST_SITE_ID);
            assertThat(generationsBefore).isNotEmpty();
            String s3KeyBefore = generationsBefore.get(0).getS3Key();

            // Verify S3 file exists before reinit
            assertS3ObjectExists(s3KeyBefore);

            // When - Call reinit endpoint
            mockMvc.perform(post("/api/v1/account/plugins/{pluginId}/reinit", PLUGIN_ID)
                            .header("Authorization", USER_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.deletedGenerations").value(1))
                    .andExpect(jsonPath("$.sqlGenerationTriggered").value(true));

            // Then - Verify old records deleted
            List<PluginSqlGeneration> generationsAfterReinit = pluginSqlGenerationRepository.findBySiteId(TEST_SITE_ID);
            // May be empty momentarily while async generation runs
            // Old S3 file should be deleted
            assertS3ObjectDoesNotExist(s3KeyBefore);

            // Verify plugin still active (key difference from clearHistory)
            AccountPlugin plugin = accountPluginRepository.findByAccountIdAndPluginId(TEST_ACCOUNT_ID, PLUGIN_ID)
                    .orElseThrow();
            assertThat(plugin.isActive()).isTrue();
        }

        @Test
        @DisplayName("T022: Should reject reinit for inactive plugin")
        void shouldRejectReinitForInactivePlugin() throws Exception {
            // Given - Deactivate the plugin
            testAccountPlugin.deactivate();
            accountPluginRepository.save(testAccountPlugin);

            // When/Then - Reinit should fail with 400
            mockMvc.perform(post("/api/v1/account/plugins/{pluginId}/reinit", PLUGIN_ID)
                            .header("Authorization", USER_TOKEN))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should preserve plugin active state and configuration after reinit")
        void shouldPreservePluginActiveStateAndConfigurationAfterReinit() throws Exception {
            // Given - Create SQL generation
            createSqlGenerationViaEvent();
            Thread.sleep(1000);

            // Store original plugin data
            AccountPlugin pluginBefore = accountPluginRepository.findByAccountIdAndPluginId(TEST_ACCOUNT_ID, PLUGIN_ID)
                    .orElseThrow();
            assertThat(pluginBefore.isActive()).isTrue();
            Object tenantIdBefore = pluginBefore.getPluginData().get("tenantId");

            // When - Reinit
            mockMvc.perform(post("/api/v1/account/plugins/{pluginId}/reinit", PLUGIN_ID)
                            .header("Authorization", USER_TOKEN))
                    .andExpect(status().isOk());

            // Then - Plugin should remain active with same config
            AccountPlugin pluginAfter = accountPluginRepository.findByAccountIdAndPluginId(TEST_ACCOUNT_ID, PLUGIN_ID)
                    .orElseThrow();
            assertThat(pluginAfter.isActive()).isTrue();
            assertThat(pluginAfter.getPluginData().get("tenantId")).isEqualTo(tenantIdBefore);
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Publishes a deferred {@code SQL_REGENERATION_COMPLETED} entry the way production does, for
     * {@link #ownAuditAccountId}.
     */
    private void logRegenerationCompleted(UUID batchId) {
        pluginAuditService.logSqlRegenerationCompleted(PLUGIN_ID, ownAuditAccountId, batchId,
                UUID.randomUUID(), UUID.randomUUID(), new SqlGenerationStats(1, 0, 0, 1), 5L);
    }

    /**
     * Counts the completion entries written for {@link #ownAuditAccountId}.
     */
    private Long regenerationCompletedEntries() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM plugin_audit_logs WHERE account_id = ? AND action_type = ?",
                Long.class, ownAuditAccountId, PluginActionType.SQL_REGENERATION_COMPLETED.name());
    }

    /**
     * Creates a SQL generation by triggering the batch completion event.
     */
    private void createSqlGenerationViaEvent() {
        Batch batch = createBatchWithCsvFile("test-data.csv",
                "id,name,email\n1,Alice,alice@example.com\n2,Bob,bob@example.com");

        BatchCompletedEvent event = new BatchCompletedEvent(
                batch.getId(), TEST_ACCOUNT_ID, 1, 512L
        );
        eventPublisher.publishEvent(event);
    }

    /**
     * Creates a batch with a CSV file uploaded to S3.
     */
    private Batch createBatchWithCsvFile(String filename, String content) {
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);

        Batch batch = Batch.start(TEST_ACCOUNT_ID, TEST_SITE_ID);
        batch = batchRepository.save(batch);

        String s3Path = batch.getS3Path();
        String s3Key = s3Path + batch.getId() + "/" + filename;

        // Upload to S3
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(s3Key)
                        .contentType("text/csv")
                        .build(),
                RequestBody.fromBytes(contentBytes)
        );

        // Create UploadedFile entity
        FileChecksum checksum = FileChecksum.calculateMD5(contentBytes);
        UploadedFile uploadedFile = UploadedFile.create(
                batch.getId(),
                filename,
                s3Path + batch.getId() + "/",
                (long) contentBytes.length,
                "text/csv",
                checksum
        );
        uploadedFileRepository.save(uploadedFile);

        batch.complete();
        return batchRepository.save(batch);
    }

    /**
     * Asserts that an S3 object exists.
     */
    private void assertS3ObjectExists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build());
        } catch (NoSuchKeyException e) {
            throw new AssertionError("Expected S3 object to exist: " + key, e);
        }
    }

    /**
     * Asserts that an S3 object does not exist.
     */
    private void assertS3ObjectDoesNotExist(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build());
            throw new AssertionError("Expected S3 object to be deleted: " + key);
        } catch (NoSuchKeyException e) {
            // Expected - object should not exist
        }
    }
}
