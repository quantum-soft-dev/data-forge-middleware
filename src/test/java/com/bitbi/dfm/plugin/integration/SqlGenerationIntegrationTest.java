package com.bitbi.dfm.plugin.integration;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.integration.AbstractIntegrationTest;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginSqlGeneration;
import com.bitbi.dfm.plugin.domain.PluginSqlGenerationRepository;
import com.bitbi.dfm.shared.domain.events.BatchCompletedEvent;
import com.bitbi.dfm.upload.domain.FileChecksum;
import com.bitbi.dfm.upload.domain.UploadedFile;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.jdbc.Sql;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for SQL generation triggered by batch completion events.
 *
 * <p>Tests the end-to-end flow:</p>
 * <ul>
 *   <li>BatchCompletedEvent triggers SqlGenerationService</li>
 *   <li>CSV files are compared between batches</li>
 *   <li>SQL statements are generated and stored</li>
 *   <li>PluginSqlGeneration records are created</li>
 * </ul>
 *
 * <p>TDD: Written FIRST, expected to FAIL until implementation.</p>
 */
@DisplayName("SQL Generation Integration Tests")
@Sql("/test-data.sql")
class SqlGenerationIntegrationTest extends AbstractIntegrationTest {

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

    // Use account from test-data.sql that owns admin-site.example.com
    private static final UUID TEST_ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    // Use existing site from test-data.sql: admin-site.example.com (has pre-existing batch)
    private static final UUID TEST_SITE_ID = UUID.fromString("b2c3d4e5-f6a7-8901-bcde-f12345678901");
    private static final String TEST_SITE_DOMAIN = "admin-site.example.com";
    // Site without pre-existing batches (for "first batch" tests)
    private static final UUID FRESH_SITE_ID = UUID.fromString("0199bab0-1111-1111-1111-111111111111");
    private static final String FRESH_SITE_DOMAIN = "test-store.example.com";

    /**
     * Reference to the AccountPlugin activation, used by tests that need to set baseline.
     */
    private AccountPlugin accountPlugin;

    @BeforeEach
    void setUp() {
        // Activate Bit BI plugin for test account
        accountPlugin = AccountPlugin.activate(TEST_ACCOUNT_ID, "bit-bi",
                Map.of("tenantId", "test-tenant"));
        accountPluginRepository.save(accountPlugin);
    }

    /**
     * Sets a baseline batch on the account plugin.
     * After baseline is set, subsequent batches will trigger SQL generation.
     *
     * @param baselineBatchId the batch ID to set as baseline
     */
    private void setBaselineBatch(UUID baselineBatchId) {
        accountPlugin.setBaselineBatchId(baselineBatchId);
        accountPlugin = accountPluginRepository.save(accountPlugin);
    }

    @Nested
    @DisplayName("Batch Completion Trigger")
    class BatchCompletionTrigger {

        @Test
        @DisplayName("Should trigger SQL generation on BATCH_COMPLETED event")
        void shouldTriggerSqlGenerationOnBatchCompletedEvent() throws Exception {
            // Given - First batch is the baseline (set explicitly)
            Batch firstBatch = createBatchWithCsvFile(null, "customers.csv",
                    "id,name,email\n1,Alice,alice@example.com\n2,Bob,bob@example.com");
            setBaselineBatch(firstBatch.getId());

            // Second batch with modified data (will trigger SQL generation)
            Batch secondBatch = createBatchWithCsvFile(null, "customers.csv",
                    "id,name,email\n1,Alice,alice@new.com\n3,Charlie,charlie@example.com");

            // When - Publish batch completed event for second batch
            BatchCompletedEvent event = new BatchCompletedEvent(
                    secondBatch.getId(), TEST_ACCOUNT_ID, 1, 1024L
            );
            eventPublisher.publishEvent(event);

            // Wait for async processing
            Thread.sleep(1000);

            // Then - Verify SQL generation record was created
            List<PluginSqlGeneration> generations = pluginSqlGenerationRepository.findBySiteId(TEST_SITE_ID);
            assertThat(generations).isNotEmpty();

            PluginSqlGeneration generation = generations.get(0);
            assertThat(generation.getSourceBatchId()).isEqualTo(secondBatch.getId());
            assertThat(generation.getComparisonBatchId()).isEqualTo(firstBatch.getId());
            assertThat(generation.getS3Key()).isNotBlank();
            assertThat(generation.getInsertCount()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should generate INSERT statements for first batch on fresh site (no previous batch)")
        void shouldGenerateInsertStatementsForFirstBatch() throws Exception {
            // Given - Set baseline to any batch (required for SQL generation to proceed)
            // Create a dummy baseline batch on the main test site
            Batch baselineBatch = createBatchWithCsvFile(null, "dummy.csv", "id\n1");
            setBaselineBatch(baselineBatch.getId());

            // Create first batch for FRESH_SITE_ID (has no previous batch for this site)
            Batch batch = createBatchWithCsvFileForSite(FRESH_SITE_ID, FRESH_SITE_DOMAIN, "products.csv",
                    "id,name,price\n1,Widget,9.99\n2,Gadget,19.99");

            // When - Publish batch completed event
            BatchCompletedEvent event = new BatchCompletedEvent(
                    batch.getId(), TEST_ACCOUNT_ID, 1, 512L
            );
            eventPublisher.publishEvent(event);

            // Wait for async processing
            Thread.sleep(1000);

            // Then - Verify all rows become INSERT statements (fresh site has no previous batch)
            List<PluginSqlGeneration> generations = pluginSqlGenerationRepository.findBySiteId(FRESH_SITE_ID);
            assertThat(generations).isNotEmpty();

            PluginSqlGeneration generation = generations.get(0);
            assertThat(generation.getComparisonBatchId()).isNull(); // No previous batch for this site
            assertThat(generation.getInsertCount()).isEqualTo(2); // 2 rows
            assertThat(generation.getUpdateCount()).isEqualTo(0);
            assertThat(generation.getDeleteCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should not trigger SQL generation for accounts without Bit BI plugin")
        void shouldNotTriggerSqlGenerationForAccountsWithoutPlugin() throws Exception {
            // Given - Different account without Bit BI plugin
            UUID otherAccountId = UUID.randomUUID();
            UUID batchId = UUID.randomUUID();

            // When - Publish batch completed event
            BatchCompletedEvent event = new BatchCompletedEvent(
                    batchId, otherAccountId, 1, 512L
            );
            eventPublisher.publishEvent(event);

            // Wait for async processing
            Thread.sleep(500);

            // Then - No SQL generation records
            List<PluginSqlGeneration> generations = pluginSqlGenerationRepository.findAll();
            assertThat(generations).isEmpty();
        }
    }

    @Nested
    @DisplayName("Diff Detection")
    class DiffDetection {

        @Test
        @DisplayName("Should detect modified rows between batches")
        void shouldDetectModifiedRowsBetweenBatches() throws Exception {
            // Given - First batch is the baseline
            Batch firstBatch = createBatchWithCsvFile(null, "users.csv",
                    "id,name,status\n1,Alice,active\n2,Bob,active");
            setBaselineBatch(firstBatch.getId());

            // Second batch with Bob's status changed
            Batch secondBatch = createBatchWithCsvFile(null, "users.csv",
                    "id,name,status\n1,Alice,active\n2,Bob,inactive");

            // When
            BatchCompletedEvent event = new BatchCompletedEvent(
                    secondBatch.getId(), TEST_ACCOUNT_ID, 1, 512L
            );
            eventPublisher.publishEvent(event);
            Thread.sleep(1000);

            // Then
            List<PluginSqlGeneration> generations = pluginSqlGenerationRepository.findBySiteId(TEST_SITE_ID);
            assertThat(generations).hasSize(1);

            PluginSqlGeneration generation = generations.get(0);
            assertThat(generation.getUpdateCount()).isEqualTo(1); // Bob was modified
            assertThat(generation.getInsertCount()).isEqualTo(0);
            assertThat(generation.getDeleteCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should detect deleted rows between batches")
        void shouldDetectDeletedRowsBetweenBatches() throws Exception {
            // Given - First batch with 3 rows is the baseline
            Batch firstBatch = createBatchWithCsvFile(null, "items.csv",
                    "id,name\n1,Item1\n2,Item2\n3,Item3");
            setBaselineBatch(firstBatch.getId());

            // Second batch with row 3 removed
            Batch secondBatch = createBatchWithCsvFile(null, "items.csv",
                    "id,name\n1,Item1\n2,Item2");

            // When
            BatchCompletedEvent event = new BatchCompletedEvent(
                    secondBatch.getId(), TEST_ACCOUNT_ID, 1, 256L
            );
            eventPublisher.publishEvent(event);
            Thread.sleep(1000);

            // Then
            List<PluginSqlGeneration> generations = pluginSqlGenerationRepository.findBySiteId(TEST_SITE_ID);
            assertThat(generations).hasSize(1);

            PluginSqlGeneration generation = generations.get(0);
            assertThat(generation.getDeleteCount()).isEqualTo(1); // Item3 was deleted
            assertThat(generation.getInsertCount()).isEqualTo(0);
            assertThat(generation.getUpdateCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should handle multiple changes in single batch")
        void shouldHandleMultipleChangesInSingleBatch() throws Exception {
            // Given - First batch is the baseline
            Batch firstBatch = createBatchWithCsvFile(null, "data.csv",
                    "id,value\n1,A\n2,B\n3,C");
            setBaselineBatch(firstBatch.getId());

            // Second batch: 1 modified, 3 deleted, 4 added
            Batch secondBatch = createBatchWithCsvFile(null, "data.csv",
                    "id,value\n1,A-UPDATED\n2,B\n4,D");

            // When
            BatchCompletedEvent event = new BatchCompletedEvent(
                    secondBatch.getId(), TEST_ACCOUNT_ID, 1, 256L
            );
            eventPublisher.publishEvent(event);
            Thread.sleep(1000);

            // Then
            List<PluginSqlGeneration> generations = pluginSqlGenerationRepository.findBySiteId(TEST_SITE_ID);
            assertThat(generations).hasSize(1);

            PluginSqlGeneration generation = generations.get(0);
            assertThat(generation.getInsertCount()).isEqualTo(1); // 4,D added
            assertThat(generation.getUpdateCount()).isEqualTo(1); // 1,A→A-UPDATED
            assertThat(generation.getDeleteCount()).isEqualTo(1); // 3,C deleted
        }
    }

    @Nested
    @DisplayName("Table Name Derivation")
    class TableNameDerivation {

        @Test
        @DisplayName("Should handle CSV filenames starting with digits by prefixing with underscore")
        void shouldHandleCsvFilenamesStartingWithDigits() throws Exception {
            // Given - Set baseline to any batch (required for SQL generation to proceed)
            Batch baselineBatch = createBatchWithCsvFile(null, "dummy.csv", "id\n1");
            setBaselineBatch(baselineBatch.getId());

            // Create batch with CSV file with name starting with digit (like 77nsfsfira.csv)
            Batch batch = createBatchWithCsvFileForSite(FRESH_SITE_ID, FRESH_SITE_DOMAIN, "77nsfsfira.csv",
                    "id,name\n1,Test\n2,Data");

            // When - Publish batch completed event
            BatchCompletedEvent event = new BatchCompletedEvent(
                    batch.getId(), TEST_ACCOUNT_ID, 1, 256L
            );
            eventPublisher.publishEvent(event);
            Thread.sleep(1000);

            // Then - SQL generation should succeed (table name prefixed with _)
            List<PluginSqlGeneration> generations = pluginSqlGenerationRepository.findBySiteId(FRESH_SITE_ID);
            assertThat(generations).hasSize(1);

            PluginSqlGeneration generation = generations.get(0);
            assertThat(generation.getInsertCount()).isEqualTo(2);
            assertThat(generation.getS3Key()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("S3 Storage")
    class S3Storage {

        @Test
        @DisplayName("Should store generated SQL in S3 with correct path structure")
        void shouldStoreGeneratedSqlInS3WithCorrectPathStructure() throws Exception {
            // Given - Set up baseline batch first
            Batch baselineBatch = createBatchWithCsvFile(null, "orders.csv",
                    "id,customer,total\n0,Dummy,0.00");
            setBaselineBatch(baselineBatch.getId());

            // Create second batch that will trigger SQL generation
            Batch batch = createBatchWithCsvFile(null, "orders.csv",
                    "id,customer,total\n1,Alice,100.00");

            // When
            BatchCompletedEvent event = new BatchCompletedEvent(
                    batch.getId(), TEST_ACCOUNT_ID, 1, 256L
            );
            eventPublisher.publishEvent(event);
            Thread.sleep(1000);

            // Then - Verify S3 key follows expected pattern
            List<PluginSqlGeneration> generations = pluginSqlGenerationRepository.findBySiteId(TEST_SITE_ID);
            assertThat(generations).hasSize(1);

            String s3Key = generations.get(0).getS3Key();
            // Path format: plugins/bit-bi/{accountId}/{siteName}/{datetime}.sql
            assertThat(s3Key).startsWith("plugins/bit-bi/");
            assertThat(s3Key).contains(TEST_ACCOUNT_ID.toString());
            assertThat(s3Key).endsWith(".sql");
        }
    }

    /**
     * Helper method to create a batch with CSV file uploaded to S3.
     * Creates both the Batch entity in the database and uploads the file to S3.
     * Uses batch ID in path to ensure uniqueness. Uses default TEST_SITE_ID.
     *
     * @param ignored unused parameter (kept for API compatibility)
     * @param filename the CSV filename
     * @param content the CSV content
     * @return the created and saved Batch entity
     */
    @SuppressWarnings("unused")
    private Batch createBatchWithCsvFile(UUID ignored, String filename, String content) {
        return createBatchWithCsvFileForSite(TEST_SITE_ID, TEST_SITE_DOMAIN, filename, content);
    }

    /**
     * Helper method to create a batch with CSV file uploaded to S3 for a specific site.
     * Creates both the Batch entity in the database and uploads the file to S3.
     * Uses batch ID in path to ensure uniqueness.
     *
     * @param siteId the site ID to create batch for
     * @param siteDomain the site domain
     * @param filename the CSV filename
     * @param content the CSV content
     * @return the created and saved Batch entity
     */
    private Batch createBatchWithCsvFileForSite(UUID siteId, String siteDomain, String filename, String content) {
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);

        // Create Batch entity using the factory method
        Batch batch = Batch.start(TEST_ACCOUNT_ID, siteId, siteDomain);

        // Save batch first to get its ID
        batch = batchRepository.save(batch);

        // Create unique s3Key using batch ID to avoid conflicts
        String s3Path = batch.getS3Path();
        // Include batch ID in the s3Key to ensure uniqueness
        String s3Key = s3Path + batch.getId() + "/" + filename;

        // Upload file to S3
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket("data-forge-test-bucket")
                        .key(s3Key)
                        .contentType("text/csv")
                        .build(),
                RequestBody.fromBytes(contentBytes)
        );

        // Create UploadedFile entity with unique s3Key
        FileChecksum checksum = FileChecksum.calculateMD5(contentBytes);
        UploadedFile uploadedFile = UploadedFile.create(
                batch.getId(),
                filename,
                s3Path + batch.getId() + "/",  // Include batch ID in path
                (long) contentBytes.length,
                "text/csv",
                checksum
        );
        uploadedFileRepository.save(uploadedFile);

        // Mark batch as completed
        batch.complete();

        // Save updated batch
        return batchRepository.save(batch);
    }
}
