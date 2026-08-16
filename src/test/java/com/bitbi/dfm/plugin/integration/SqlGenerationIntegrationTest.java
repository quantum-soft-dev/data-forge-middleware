package com.bitbi.dfm.plugin.integration;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.integration.AbstractIntegrationTest;
import com.bitbi.dfm.plugin.application.SqlGenerationService;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginSqlGeneration;
import com.bitbi.dfm.plugin.domain.PluginSqlGenerationRepository;
import com.bitbi.dfm.shared.domain.events.BatchCompletedEvent;
import com.bitbi.dfm.upload.domain.FileChecksum;
import com.bitbi.dfm.upload.domain.UploadedFile;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.jdbc.Sql;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for SQL generation from historical file-backed batches.
 *
 * <p>Tests the end-to-end flow:</p>
 * <ul>
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

    @Autowired
    private SqlGenerationService sqlGenerationService;

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
        // test-data.sql empties plugin_sql_generations only through the cascades of the rows it
        // deletes, and only at the instant it runs; a generation written afterwards by an async
        // dispatch or by the delta-SQL sweep of another cached context outlives it (issue #159).
        clearPluginSqlGenerations();

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

    /**
     * The generation this test produced, named by its source batch and waited for.
     *
     * <p>Both halves matter (issue #159). <b>Named</b>: {@code source_batch_id} is unique, so this
     * cannot see a generation another test method, another cached Spring context's sweep worker or
     * an in-flight {@code @Async} dispatch wrote for the same site — which is what
     * {@code findBySiteId(...)} + {@code hasSize(1)} + {@code get(0)} counted, and why the same
     * assertion was seen failing with "was 2". <b>Waited for</b>: a narrower query on its own would
     * still return empty if the row is not committed yet, which is how the same line was also seen
     * failing with "was 0"; polling turns that into a pass rather than a coin flip.</p>
     *
     * @param sourceBatchId the batch whose generation is expected
     * @return the generation for that batch
     */
    private PluginSqlGeneration awaitGenerationFor(UUID sourceBatchId) {
        return Awaitility.await("SQL generation for batch " + sourceBatchId)
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> pluginSqlGenerationRepository.findBySourceBatchId(sourceBatchId),
                        Optional::isPresent)
                .orElseThrow();
    }

    /**
     * Assert that a batch produced no generation, and keep asserting it for half a second so a
     * late writer is caught rather than missed. Scoped by source batch for the same reason as
     * {@link #awaitGenerationFor(UUID)}: whether the <em>site</em> has generations is not this
     * test's business and is not isolated from the rest of the suite.
     *
     * @param sourceBatchId the batch expected to produce nothing
     */
    private void assertNoGenerationFor(UUID sourceBatchId) {
        Awaitility.await("no SQL generation for batch " + sourceBatchId)
                .during(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> pluginSqlGenerationRepository.findBySourceBatchId(sourceBatchId).isEmpty());
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

            // When - Generate from historical file-backed batch data directly.
            sqlGenerationService.generateSqlForBatch(secondBatch.getId(), accountPlugin.getId());

            // Then - Verify SQL generation record was created
            PluginSqlGeneration generation = awaitGenerationFor(secondBatch.getId());
            assertThat(generation.getSiteId()).isEqualTo(TEST_SITE_ID);
            assertThat(generation.getComparisonBatchId()).isEqualTo(firstBatch.getId());
            assertThat(generation.getS3Key()).isNotBlank();
            assertThat(generation.getInsertCount()).isGreaterThan(0);

            // ...and that the baseline batch produced none — the scoped form of what the old
            // site-wide hasSize(1) was really claiming (issue #159).
            assertNoGenerationFor(firstBatch.getId());
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

            // When
            sqlGenerationService.generateSqlForBatch(batch.getId(), accountPlugin.getId());

            // Then - Verify all rows become INSERT statements (fresh site has no previous batch)
            PluginSqlGeneration generation = awaitGenerationFor(batch.getId());
            assertThat(generation.getSiteId()).isEqualTo(FRESH_SITE_ID);
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

            // Then - the dispatch produces no generation for this batch, and keeps producing none
            // for long enough that a late async write is caught. Scoped to the batch: findAll() was
            // asserting that the whole shared database held no generation at all, which no test
            // owns (issue #159).
            assertNoGenerationFor(batchId);
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
            sqlGenerationService.generateSqlForBatch(secondBatch.getId(), accountPlugin.getId());

            // Then
            PluginSqlGeneration generation = awaitGenerationFor(secondBatch.getId());
            assertThat(generation.getUpdateCount()).isEqualTo(1); // Bob was modified
            assertThat(generation.getInsertCount()).isEqualTo(0);
            assertThat(generation.getDeleteCount()).isEqualTo(0);
            assertNoGenerationFor(firstBatch.getId());
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
            sqlGenerationService.generateSqlForBatch(secondBatch.getId(), accountPlugin.getId());

            // Then
            PluginSqlGeneration generation = awaitGenerationFor(secondBatch.getId());
            assertThat(generation.getDeleteCount()).isEqualTo(1); // Item3 was deleted
            assertThat(generation.getInsertCount()).isEqualTo(0);
            assertThat(generation.getUpdateCount()).isEqualTo(0);
            assertNoGenerationFor(firstBatch.getId());
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
            sqlGenerationService.generateSqlForBatch(secondBatch.getId(), accountPlugin.getId());

            // Then
            PluginSqlGeneration generation = awaitGenerationFor(secondBatch.getId());
            assertThat(generation.getInsertCount()).isEqualTo(1); // 4,D added
            assertThat(generation.getUpdateCount()).isEqualTo(1); // 1,A→A-UPDATED
            assertThat(generation.getDeleteCount()).isEqualTo(1); // 3,C deleted
            assertNoGenerationFor(firstBatch.getId());
        }

        @Test
        @DisplayName("Should read its own generation past a leftover one, and clear leftovers between methods")
        void shouldIsolateItsOwnGenerationFromLeftovers() {
            // Given - a first delta on the site, standing in for the generation another test
            // method, an async dispatch or another context's sweep worker leaves behind
            Batch baselineBatch = createBatchWithCsvFile(null, "stock.csv", "id,qty\n1,1\n2,2");
            setBaselineBatch(baselineBatch.getId());
            Batch leftoverBatch = createBatchWithCsvFile(null, "stock.csv", "id,qty\n1,1\n2,9");
            sqlGenerationService.generateSqlForBatch(leftoverBatch.getId(), accountPlugin.getId());
            assertThat(awaitGenerationFor(leftoverBatch.getId()).getUpdateCount()).isEqualTo(1);

            // When - this test produces its own generation while that one is still on the site
            Batch ownBatch = createBatchWithCsvFile(null, "stock.csv", "id,qty\n1,1\n2,9\n3,3");
            sqlGenerationService.generateSqlForBatch(ownBatch.getId(), accountPlugin.getId());

            // Then - naming the source batch reads the right one; the site now carries two, which
            // is exactly what the retired findBySiteId(...).hasSize(1).get(0) shape could not
            // survive (issue #159)
            PluginSqlGeneration own = awaitGenerationFor(ownBatch.getId());
            assertThat(own.getInsertCount()).isEqualTo(1); // 3,3 added
            assertThat(own.getUpdateCount()).isEqualTo(0);
            assertThat(own.getComparisonBatchId()).isEqualTo(leftoverBatch.getId());
            assertThat(pluginSqlGenerationRepository.findBySiteId(TEST_SITE_ID)).hasSize(2);

            // And the @BeforeEach clear is what stops those two reaching the next method without
            // depending on test-data.sql's cascades — which only fire for accounts and sites its
            // LIKE '%@example.com' / '%.example.com' filters happen to match, and only at the
            // instant the script runs
            clearPluginSqlGenerations();
            assertThat(pluginSqlGenerationRepository.findBySiteId(TEST_SITE_ID)).isEmpty();
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

            // When
            sqlGenerationService.generateSqlForBatch(batch.getId(), accountPlugin.getId());

            // Then - SQL generation should succeed (table name prefixed with _)
            PluginSqlGeneration generation = awaitGenerationFor(batch.getId());
            assertThat(generation.getSiteId()).isEqualTo(FRESH_SITE_ID);
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
            sqlGenerationService.generateSqlForBatch(batch.getId(), accountPlugin.getId());

            // Then - Verify S3 key follows expected pattern
            String s3Key = awaitGenerationFor(batch.getId()).getS3Key();
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
        Batch batch = Batch.start(TEST_ACCOUNT_ID, siteId);

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
