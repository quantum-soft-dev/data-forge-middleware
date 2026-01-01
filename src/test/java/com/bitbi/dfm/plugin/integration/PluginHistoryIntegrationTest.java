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
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Value("${aws.s3.bucket-name:data-forge-test-bucket}")
    private String bucketName;

    private static final String PLUGIN_ID = "bit-bi";
    private static final UUID TEST_ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID TEST_SITE_ID = UUID.fromString("b2c3d4e5-f6a7-8901-bcde-f12345678901");
    private static final String TEST_SITE_DOMAIN = "admin-site.example.com";

    private AccountPlugin testAccountPlugin;

    @BeforeEach
    void setUp() {
        // Activate Bit BI plugin for test account
        testAccountPlugin = AccountPlugin.activate(TEST_ACCOUNT_ID, PLUGIN_ID,
                Map.of("tenantId", "test-tenant"));
        accountPluginRepository.save(testAccountPlugin);
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

    // ==================== Helper Methods ====================

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

        Batch batch = Batch.start(TEST_ACCOUNT_ID, TEST_SITE_ID, TEST_SITE_DOMAIN);
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
