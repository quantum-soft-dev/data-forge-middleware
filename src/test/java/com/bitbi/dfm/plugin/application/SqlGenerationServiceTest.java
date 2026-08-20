package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.plugin.domain.*;
import com.bitbi.dfm.plugin.infrastructure.storage.S3SqlFileStorageService;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.site.domain.SiteType;
import com.bitbi.dfm.upload.domain.UploadedFile;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SqlGenerationService.
 * Tests BOM stripping in DbfSqlGenerationStrategy and per-file error handling.
 */
@DisplayName("SqlGenerationService")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SqlGenerationServiceTest {

    @Mock
    private BatchRepository batchRepository;

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private AccountPluginRepository accountPluginRepository;

    @Mock
    private PluginSqlGenerationRepository sqlGenerationRepository;

    @Mock
    private CsvDiffService csvDiffService;

    @Mock
    private SqlStatementGenerator sqlStatementGenerator;

    @Mock
    private S3SqlFileStorageService s3SqlFileStorageService;

    @Mock
    private S3Client s3Client;

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private PluginAuditService pluginAuditService;

    @Mock
    private SiteSchemaService siteSchemaService;

    @Mock
    private CdcSqlGenerationStrategy cdcStrategy;

    @Mock
    private DeltaSqlGenerationStrategy deltaStrategy;

    @Mock
    private com.bitbi.dfm.delta.domain.ChangelogSegmentRepository changelogSegmentRepository;

    @Mock
    private PluginDeltaBaselineRepository pluginDeltaBaselineRepository;

    @Mock
    private Counter counter;

    @Mock
    private Timer timer;

    private SqlGenerationService service;
    private DbfSqlGenerationStrategy dbfStrategy;

    private static final String BUCKET_NAME = "test-bucket";

    @BeforeEach
    void setUp() {
        when(meterRegistry.counter(anyString())).thenReturn(counter);
        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(counter);
        when(meterRegistry.timer(anyString())).thenReturn(timer);

        dbfStrategy = new DbfSqlGenerationStrategy(
                csvDiffService, sqlStatementGenerator, s3Client, BUCKET_NAME, meterRegistry);

        service = newService(meterRegistry, dbfStrategy, 2, 120, 80);
        service.init();
    }

    /**
     * The one construction of {@link SqlGenerationService} in this file. The dependencies that
     * vary between tests (the registry, because {@code Timer.start(mockRegistry)} NPEs and some
     * tests need a real {@link SimpleMeterRegistry}; the DBF strategy) are parameters; everything
     * else is the class's shared mocks.
     */
    private SqlGenerationService newService(MeterRegistry registry, DbfSqlGenerationStrategy dbf,
                                            int maxConcurrent, int semaphoreTimeoutSeconds,
                                            int heapThresholdPercent) {
        return new SqlGenerationService(
                accountPluginRepository,
                new SqlGenerationPersistence(batchRepository, siteRepository,
                        sqlGenerationRepository, changelogSegmentRepository),
                s3SqlFileStorageService,
                registry,
                pluginAuditService,
                dbf,
                cdcStrategy,
                siteSchemaService,
                deltaStrategy,
                pluginDeltaBaselineRepository,
                maxConcurrent,
                semaphoreTimeoutSeconds,
                heapThresholdPercent);
    }

    @Nested
    @DisplayName("BOM Stripping (DbfSqlGenerationStrategy)")
    class BomStripping {

        @Test
        @DisplayName("should strip UTF-8 BOM from beginning of CSV content")
        void shouldStripBomFromCsvContent() throws Exception {
            // Given - CSV content with BOM prefix
            String csvWithBom = "\uFEFFCAR_NO,PAY_DT,AMOUNT\n1,2024-01-01,100";
            byte[] bytes = csvWithBom.getBytes(StandardCharsets.UTF_8);

            ResponseInputStream<GetObjectResponse> responseStream = new ResponseInputStream<>(
                    GetObjectResponse.builder().build(),
                    new ByteArrayInputStream(bytes)
            );
            when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream);

            // When - invoke private method on DbfSqlGenerationStrategy via reflection
            Method readMethod = DbfSqlGenerationStrategy.class.getDeclaredMethod(
                    "readCsvContentFromS3", String.class);
            readMethod.setAccessible(true);
            String result = (String) readMethod.invoke(dbfStrategy, "test/file.csv");

            // Then - BOM should be stripped
            assertThat(result).isEqualTo("CAR_NO,PAY_DT,AMOUNT\n1,2024-01-01,100");
            assertThat(result.charAt(0)).isNotEqualTo('\uFEFF');
            assertThat(result.charAt(0)).isEqualTo('C');
        }

        @Test
        @DisplayName("should not modify CSV content without BOM")
        void shouldNotModifyCsvContentWithoutBom() throws Exception {
            // Given - CSV content without BOM
            String csvWithoutBom = "CAR_NO,PAY_DT,AMOUNT\n1,2024-01-01,100";
            byte[] bytes = csvWithoutBom.getBytes(StandardCharsets.UTF_8);

            ResponseInputStream<GetObjectResponse> responseStream = new ResponseInputStream<>(
                    GetObjectResponse.builder().build(),
                    new ByteArrayInputStream(bytes)
            );
            when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream);

            // When
            Method readMethod = DbfSqlGenerationStrategy.class.getDeclaredMethod(
                    "readCsvContentFromS3", String.class);
            readMethod.setAccessible(true);
            String result = (String) readMethod.invoke(dbfStrategy, "test/file.csv");

            // Then - content should remain unchanged
            assertThat(result).isEqualTo(csvWithoutBom);
        }

        @Test
        @DisplayName("should handle empty content from S3")
        void shouldHandleEmptyContent() throws Exception {
            // Given - empty content
            byte[] bytes = "".getBytes(StandardCharsets.UTF_8);

            ResponseInputStream<GetObjectResponse> responseStream = new ResponseInputStream<>(
                    GetObjectResponse.builder().build(),
                    new ByteArrayInputStream(bytes)
            );
            when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream);

            // When
            Method readMethod = DbfSqlGenerationStrategy.class.getDeclaredMethod(
                    "readCsvContentFromS3", String.class);
            readMethod.setAccessible(true);
            String result = (String) readMethod.invoke(dbfStrategy, "test/file.csv");

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should not strip BOM character in middle of content")
        void shouldNotStripBomInMiddleOfContent() throws Exception {
            // Given - BOM character in middle (not at start)
            String csvWithMiddleBom = "CAR_NO,PAY\uFEFF_DT\n1,2024-01-01";
            byte[] bytes = csvWithMiddleBom.getBytes(StandardCharsets.UTF_8);

            ResponseInputStream<GetObjectResponse> responseStream = new ResponseInputStream<>(
                    GetObjectResponse.builder().build(),
                    new ByteArrayInputStream(bytes)
            );
            when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream);

            // When
            Method readMethod = DbfSqlGenerationStrategy.class.getDeclaredMethod(
                    "readCsvContentFromS3", String.class);
            readMethod.setAccessible(true);
            String result = (String) readMethod.invoke(dbfStrategy, "test/file.csv");

            // Then - middle BOM should remain (only strip from start)
            assertThat(result).isEqualTo(csvWithMiddleBom);
        }
    }

    @Nested
    @DisplayName("Per-file Error Handling")
    class PerFileErrorHandling {

        @Test
        @DisplayName("should continue processing other files when one has invalid headers")
        void shouldContinueWhenOneFileHasInvalidHeaders() throws Exception {
            // Given - use SimpleMeterRegistry to avoid NPE from Timer.start(mockRegistry)
            SimpleMeterRegistry realRegistry = new SimpleMeterRegistry();
            DbfSqlGenerationStrategy realDbfStrategy = new DbfSqlGenerationStrategy(
                    csvDiffService, sqlStatementGenerator, s3Client, BUCKET_NAME, realRegistry);
            SqlGenerationService serviceWithRealMetrics = newService(realRegistry, realDbfStrategy,
                    2, 120,
                    100  // disables the memory-pressure abort: it needs heap usage strictly
                         // above the threshold, and 100% is unreachable (#174)
            );
            serviceWithRealMetrics.init();

            UUID batchId = UUID.randomUUID();
            UUID siteId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            Long accountPluginId = 1L;

            // Set up AccountPlugin (not baseline)
            AccountPlugin accountPlugin = mock(AccountPlugin.class);
            when(accountPlugin.isBaselineBatch(batchId)).thenReturn(false);
            when(accountPlugin.hasBaselineBatch()).thenReturn(true);
            when(accountPluginRepository.findById(accountPluginId)).thenReturn(Optional.of(accountPlugin));

            // Set up batch with 2 CSV files
            Batch batch = mock(Batch.class);
            when(batch.getId()).thenReturn(batchId);
            when(batch.getSiteId()).thenReturn(siteId);
            when(batch.getAccountId()).thenReturn(accountId);

            UploadedFile badFile = mock(UploadedFile.class);
            when(badFile.getOriginalFileName()).thenReturn("bad_headers.csv");
            when(badFile.getS3Key()).thenReturn("account/site/bad_headers.csv");

            UploadedFile goodFile = mock(UploadedFile.class);
            when(goodFile.getOriginalFileName()).thenReturn("good_data.csv");
            when(goodFile.getS3Key()).thenReturn("account/site/good_data.csv");

            when(batch.getUploadedFiles()).thenReturn(List.of(badFile, goodFile));
            when(batchRepository.findByIdWithFiles(batchId)).thenReturn(Optional.of(batch));
            when(sqlGenerationRepository.existsBySourceBatchId(batchId)).thenReturn(false);

            Site site = mock(Site.class);
            when(site.getDomain()).thenReturn("test-site.com");
            when(site.getSiteType()).thenReturn(SiteType.DBF);
            when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));

            // Previous batch is empty (first batch)
            when(batchRepository.findPreviousBatchForSiteWithFiles(siteId, batchId))
                    .thenReturn(Optional.empty());

            // S3 returns content for both files
            String badCsv = "\uFEFFINVALID\u0000COL,DATA\n1,test";
            String goodCsv = "id,name\n1,Alice\n2,Bob";

            ResponseInputStream<GetObjectResponse> badStream = new ResponseInputStream<>(
                    GetObjectResponse.builder().build(),
                    new ByteArrayInputStream(badCsv.getBytes(StandardCharsets.UTF_8))
            );
            ResponseInputStream<GetObjectResponse> goodStream = new ResponseInputStream<>(
                    GetObjectResponse.builder().build(),
                    new ByteArrayInputStream(goodCsv.getBytes(StandardCharsets.UTF_8))
            );

            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenReturn(badStream)
                    .thenReturn(goodStream);

            // Bad file throws InvalidCsvHeaderException during compare
            when(csvDiffService.compare(anyString(), anyString(), anyList()))
                    .thenThrow(new CsvDiffService.InvalidCsvHeaderException("Invalid column names"))
                    .thenReturn(List.of(
                            CsvRowDiff.added(1, new LinkedHashMap<>(Map.of("id", "1", "name", "Alice"))),
                            CsvRowDiff.added(2, new LinkedHashMap<>(Map.of("id", "2", "name", "Bob")))
                    ));

            when(sqlStatementGenerator.generate(any(), anyString(), any()))
                    .thenReturn("INSERT INTO good_data (id, name) VALUES ('1', 'Alice');\n");

            when(s3SqlFileStorageService.storeSqlFile(any(), any(), anyString()))
                    .thenReturn("plugins/bit-bi/test.sql");
            when(s3SqlFileStorageService.getFileSize(anyString())).thenReturn(100L);

            PluginSqlGeneration generation = mock(PluginSqlGeneration.class);
            when(generation.getId()).thenReturn(UUID.randomUUID());
            when(sqlGenerationRepository.save(any())).thenReturn(generation);

            // When
            Optional<PluginSqlGeneration> result = serviceWithRealMetrics.generateSqlForBatch(batchId, accountPluginId);

            // Then - should succeed with the good file, skipping the bad one
            assertThat(result).isPresent();
            // The good file should still be processed
            verify(s3SqlFileStorageService).storeSqlFile(any(), any(), anyString());
            // Verify the skipped file metric was incremented
            assertThat(realRegistry.counter("sql.generation.files.skipped.invalid_headers").count()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Delta v2 routing (026-bitbi-delta-sql)")
    class DeltaV2Routing {

        private final UUID batchId = UUID.randomUUID();
        private final UUID siteId = UUID.randomUUID();
        private final UUID accountId = UUID.randomUUID();
        private final Long accountPluginId = 7L;

        private SqlGenerationService deltaService;
        /** The registry {@link #deltaService} was built on, so the counters can be read back. */
        private SimpleMeterRegistry deltaRegistry;
        private Batch batch;
        private Site site;
        private AccountPlugin accountPlugin;
        private com.bitbi.dfm.delta.domain.ChangelogSegment segment;

        @BeforeEach
        void setUpDelta() {
            deltaRegistry = new SimpleMeterRegistry();
            deltaService = newService(deltaRegistry, dbfStrategy, 2, 120, 100);
            deltaService.init();

            batch = mock(Batch.class);
            when(batch.getId()).thenReturn(batchId);
            when(batch.getSiteId()).thenReturn(siteId);
            when(batch.getAccountId()).thenReturn(accountId);
            when(batch.getUploadedFiles()).thenReturn(List.of());

            site = mock(Site.class);
            when(site.getId()).thenReturn(siteId);
            when(site.getSiteType()).thenReturn(SiteType.DBF);

            accountPlugin = mock(AccountPlugin.class);
            when(accountPlugin.isBaselineBatch(any())).thenReturn(false);
            when(accountPlugin.hasBaselineBatch()).thenReturn(false);

            segment = com.bitbi.dfm.delta.domain.ChangelogSegment.create(
                    siteId, batchId, 11L, 20L, 10L, "hash", "delta/x", "DELTA", Map.of());

            when(accountPluginRepository.findById(accountPluginId)).thenReturn(Optional.of(accountPlugin));
            when(batchRepository.findById(batchId)).thenReturn(Optional.of(batch));
            when(batchRepository.findByIdWithFiles(batchId)).thenReturn(Optional.of(batch));
            when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));
            when(sqlGenerationRepository.existsBySourceBatchId(batchId)).thenReturn(false);
            when(changelogSegmentRepository.existsByBatchId(batchId)).thenReturn(true);
            when(changelogSegmentRepository.findByBatchId(batchId)).thenReturn(List.of(segment));
            when(siteSchemaService.getTableSchemas(siteId)).thenReturn(Map.of());
            when(pluginDeltaBaselineRepository.baselineSeqsBySiteId(siteId)).thenReturn(Map.of());
            when(s3SqlFileStorageService.storeSqlFile(any(), any(), anyString())).thenReturn("plugins/bit-bi/x.sql");
            when(s3SqlFileStorageService.getFileSize(anyString())).thenReturn(42L);
            when(sqlGenerationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("should bypass batch-baseline cases and generate from segments for V2 sites")
        void shouldGenerateFromSegmentsBypassingBatchBaseline() throws Exception {
            when(deltaStrategy.generate(eq(batchId), eq(siteId), eq(List.of(segment)), any(), any()))
                    .thenReturn(new SqlGenerationResult("INSERT INTO t (id) VALUES (1);\n",
                            new SqlGenerationStats(1, 0, 0, 1)));

            Optional<PluginSqlGeneration> result = deltaService.generateSqlForBatch(batchId, accountPluginId);

            assertThat(result).isPresent();
            // batch-level baseline must not be touched for V2 sites
            verify(accountPlugin, never()).setBaselineBatchId(any());
            verify(accountPluginRepository, never()).save(any());
            // record carries the segment seq range, no comparison batch
            assertThat(result.get().getFirstSeq()).isEqualTo(11L);
            assertThat(result.get().getLastSeq()).isEqualTo(20L);
            assertThat(result.get().getComparisonBatchId()).isNull();
            verify(changelogSegmentRepository).existsByBatchId(batchId);
            verify(changelogSegmentRepository).findByBatchId(batchId);
        }

        @Test
        @DisplayName("should adopt the existing row when another worker wins the unique claim")
        void shouldAdoptExistingGenerationOnUniqueViolation() throws Exception {
            when(deltaStrategy.generate(eq(batchId), eq(siteId), eq(List.of(segment)), any(), any()))
                    .thenReturn(new SqlGenerationResult("INSERT INTO t (id) VALUES (1);\n",
                            new SqlGenerationStats(1, 0, 0, 1)));
            PluginSqlGeneration existing = PluginSqlGeneration.create(
                    accountPluginId, siteId, batchId, null, "plugins/bit-bi/winner.sql", 10L,
                    new SqlGenerationStats(1, 0, 0, 1), 1L);
            when(sqlGenerationRepository.save(any())).thenThrow(
                    new org.springframework.dao.DataIntegrityViolationException("uk_sql_gen_source_batch"));
            when(sqlGenerationRepository.findBySourceBatchId(batchId)).thenReturn(Optional.of(existing));

            Optional<PluginSqlGeneration> result = deltaService.generateSqlForBatch(batchId, accountPluginId);

            assertThat(result).contains(existing);
            verify(s3SqlFileStorageService).deleteFile("plugins/bit-bi/x.sql");
        }

        @Test
        @DisplayName("should not audit or count the generation it only adopted (#246)")
        void shouldNotAuditOrCountTheAdoptedGeneration() throws Exception {
            when(deltaStrategy.generate(eq(batchId), eq(siteId), eq(List.of(segment)), any(), any()))
                    .thenReturn(new SqlGenerationResult("INSERT INTO t (id) VALUES (1);\n",
                            new SqlGenerationStats(1, 0, 0, 1)));
            PluginSqlGeneration existing = PluginSqlGeneration.create(
                    accountPluginId, siteId, batchId, null, "plugins/bit-bi/winner.sql", 10L,
                    new SqlGenerationStats(1, 0, 0, 1), 1L);
            when(sqlGenerationRepository.save(any())).thenThrow(
                    new org.springframework.dao.DataIntegrityViolationException("uk_sql_gen_source_batch"));
            when(sqlGenerationRepository.findBySourceBatchId(batchId)).thenReturn(Optional.of(existing));

            Optional<PluginSqlGeneration> result = deltaService.generateSqlForBatch(batchId, accountPluginId);

            assertThat(result).contains(existing);
            // The winner already wrote the completion entry, and this caller's own key was just
            // deleted — a second entry would name a dead object on the account's plugin log.
            verify(pluginAuditService, never()).logSqlGenerationCompleted(
                    anyString(), any(), any(), any(), any(), anyString(), anyLong());
            // Statement counters describe the batch, not the attempt: the winner counted them.
            assertThat(deltaRegistry.counter("sql.generation.statements.inserts").count()).isZero();
            assertThat(deltaRegistry.counter("sql.generation.statements.updates").count()).isZero();
            assertThat(deltaRegistry.counter("sql.generation.statements.deletes").count()).isZero();
            // The lost race is reported as itself rather than as a second success.
            assertThat(deltaRegistry.counter("sql.generation.claims.lost").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should audit and count the winner of the unique claim exactly once (#246)")
        void shouldAuditAndCountTheWinnerOfTheUniqueClaim() throws Exception {
            when(deltaStrategy.generate(eq(batchId), eq(siteId), eq(List.of(segment)), any(), any()))
                    .thenReturn(new SqlGenerationResult("INSERT INTO t (id) VALUES (1);\n",
                            new SqlGenerationStats(3, 2, 1, 1)));

            Optional<PluginSqlGeneration> result = deltaService.generateSqlForBatch(batchId, accountPluginId);

            assertThat(result).isPresent();
            verify(pluginAuditService).logSqlGenerationCompleted(
                    eq("bit-bi"), eq(accountId), eq(batchId), eq(siteId), any(),
                    eq("plugins/bit-bi/x.sql"), anyLong());
            assertThat(deltaRegistry.counter("sql.generation.statements.inserts").count()).isEqualTo(3.0);
            assertThat(deltaRegistry.counter("sql.generation.statements.updates").count()).isEqualTo(2.0);
            assertThat(deltaRegistry.counter("sql.generation.statements.deletes").count()).isEqualTo(1.0);
            assertThat(deltaRegistry.counter("sql.generation.claims.lost").count()).isZero();
        }

        @Test
        @DisplayName("should keep the idempotency guard for V2 batches")
        void shouldKeepIdempotencyGuard() throws Exception {
            when(sqlGenerationRepository.existsBySourceBatchId(batchId)).thenReturn(true);

            Optional<PluginSqlGeneration> result = deltaService.generateSqlForBatch(batchId, accountPluginId);

            assertThat(result).isEmpty();
            verify(deltaStrategy, never()).generate(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("should skip when the V2 batch has no segments")
        void shouldSkipWhenNoSegments() throws Exception {
            when(changelogSegmentRepository.existsByBatchId(batchId)).thenReturn(false);

            Optional<PluginSqlGeneration> result = deltaService.generateSqlForBatch(batchId, accountPluginId);

            assertThat(result).isEmpty();
            verify(deltaStrategy, never()).generate(any(), any(), any(), any(), any());
        }
    }

    /**
     * The three keys this service consumes fail fast (issue #185): an out-of-range value refuses
     * to construct the service, which fails the Spring context at startup. Both ways of getting a
     * key wrong used to fail silently — {@code heap-threshold-percent: 800} disabled the heap
     * guard, a non-positive value refused every generation for ever (an unbounded queue stall
     * since #181, silent data loss once retention passes over the pending segments, #212).
     *
     * <p>The cases pin both boundaries of each agreed range: {@code heap-threshold-percent}
     * ∈ [1..100] — 0 is refused because the ceiling-rounded reading of a live JVM is never 0, so
     * a strict {@code > 0} refuses every generation exactly like the negative it neighbours,
     * while 100 stays the documented off-switch of #174 (pinned by
     * {@code SqlGenerationStreamingTest}, "should not abort at a threshold of 100 even when the
     * heap reports full") — {@code max-concurrent} >= 1, {@code semaphore-timeout-seconds} >= 1.
     * The refusal must name the key <em>and the value</em> ("but was N"): the crash-loop log line
     * is the whole of what an operator gets to diagnose a failed rollout with, and asserting the
     * value through the shared "but was" prefix keeps the assertion from being satisfied by a
     * digit in the static text. The key strings are deliberately literals rather than the
     * {@code *_KEY} constants: the key name is a published contract, and a test that read it
     * through the constant would certify whatever the constant drifted to. The limits of the
     * named-key promise live in {@code docs/020-sql-generation-optimization.md}.</p>
     *
     * <p>The sibling keys {@code delta-max-concurrent} / {@code delta-sweep-ms} are validated the
     * same way by their own consumer — see {@code DeltaSqlSweepWorkerTest}.</p>
     */
    @Nested
    @DisplayName("Configuration validation (issue #185)")
    class ConfigurationValidation {

        static Stream<Arguments> outOfRangeConfigurations() {
            return Stream.of(
                    arguments("plugin.sql-generation.heap-threshold-percent", 2, 120, -1, -1),
                    arguments("plugin.sql-generation.heap-threshold-percent", 2, 120, 0, 0),
                    arguments("plugin.sql-generation.heap-threshold-percent", 2, 120, 101, 101),
                    arguments("plugin.sql-generation.heap-threshold-percent", 2, 120, 800, 800),
                    arguments("plugin.sql-generation.max-concurrent", 0, 120, 80, 0),
                    arguments("plugin.sql-generation.max-concurrent", -1, 120, 80, -1),
                    arguments("plugin.sql-generation.semaphore-timeout-seconds", 2, 0, 80, 0),
                    arguments("plugin.sql-generation.semaphore-timeout-seconds", 2, -5, 80, -5));
        }

        @ParameterizedTest(name = "should refuse {0} = {4} at startup, naming the key and the value")
        @MethodSource("outOfRangeConfigurations")
        void shouldRefuseOutOfRangeValue(String key, int maxConcurrent, int semaphoreTimeoutSeconds,
                                         int heapThresholdPercent, int offendingValue) {
            assertThatThrownBy(() ->
                    newService(meterRegistry, dbfStrategy,
                            maxConcurrent, semaphoreTimeoutSeconds, heapThresholdPercent))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(key)
                    .hasMessageContaining("but was " + offendingValue);
        }

        static Stream<Arguments> acceptedBoundaries() {
            return Stream.of(
                    arguments("heap-threshold-percent floor", 2, 120, 1),
                    arguments("heap-threshold-percent off-switch (#174)", 2, 120, 100),
                    arguments("max-concurrent floor", 1, 120, 80),
                    arguments("semaphore-timeout-seconds floor", 2, 1, 80));
        }

        @ParameterizedTest(name = "should accept the {0}")
        @MethodSource("acceptedBoundaries")
        void shouldAcceptBoundary(String boundary, int maxConcurrent, int semaphoreTimeoutSeconds,
                                  int heapThresholdPercent) {
            assertThatCode(() ->
                    newService(meterRegistry, dbfStrategy,
                            maxConcurrent, semaphoreTimeoutSeconds, heapThresholdPercent))
                    .doesNotThrowAnyException();
        }
    }
}
