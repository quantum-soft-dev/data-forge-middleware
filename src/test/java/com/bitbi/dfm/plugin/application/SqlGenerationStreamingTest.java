package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.plugin.application.SqlGenerationService.ParsedCsvData;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.CsvRowDiff;
import com.bitbi.dfm.plugin.domain.PluginSqlGeneration;
import com.bitbi.dfm.plugin.domain.PluginSqlGenerationRepository;
import com.bitbi.dfm.plugin.infrastructure.storage.S3SqlFileStorageService;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.upload.domain.UploadedFile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SqlGenerationService streaming S3 parsing and memory backpressure.
 * Tests Tasks 3 (eager GC), 5 (memory backpressure), and 8 (streaming S3).
 */
@DisplayName("SqlGenerationService Streaming & Memory")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SqlGenerationStreamingTest {

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
    private PluginAuditService pluginAuditService;

    private SimpleMeterRegistry meterRegistry;

    private static final String BUCKET_NAME = "test-bucket";

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    private SqlGenerationService createService(int heapThreshold) {
        SqlGenerationService service = new SqlGenerationService(
                batchRepository,
                siteRepository,
                accountPluginRepository,
                sqlGenerationRepository,
                csvDiffService,
                sqlStatementGenerator,
                s3SqlFileStorageService,
                s3Client,
                BUCKET_NAME,
                meterRegistry,
                pluginAuditService,
                2,
                120,
                heapThreshold
        );
        service.init();
        return service;
    }

    private ResponseInputStream<GetObjectResponse> createS3Stream(String content) {
        return new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))
        );
    }

    private ResponseInputStream<GetObjectResponse> createGzipS3Stream(String content) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                new ByteArrayInputStream(baos.toByteArray())
        );
    }

    @Nested
    @DisplayName("Streaming S3 Parsing")
    class StreamingS3Parsing {

        @Test
        @DisplayName("should parse CSV directly from S3 stream into headers and rows")
        void shouldParseCsvDirectlyFromS3Stream() throws Exception {
            // Given
            SqlGenerationService service = createService(80);
            String csvContent = "id,name,email\n1,Alice,alice@test.com\n2,Bob,bob@test.com";

            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenReturn(createS3Stream(csvContent));

            // When - invoke streamCsvFromS3 via reflection
            Method streamMethod = SqlGenerationService.class.getDeclaredMethod(
                    "streamCsvFromS3", String.class);
            streamMethod.setAccessible(true);
            ParsedCsvData result = (ParsedCsvData) streamMethod.invoke(service, "test/file.csv");

            // Then
            assertThat(result.headers()).containsExactly("id", "name", "email");
            assertThat(result.rows()).hasSize(2);
            assertThat(result.rows().get(0)).containsExactly("1", "Alice", "alice@test.com");
            assertThat(result.rows().get(1)).containsExactly("2", "Bob", "bob@test.com");
        }

        @Test
        @DisplayName("should strip BOM from first header when streaming")
        void shouldStripBomFromFirstHeaderWhenStreaming() throws Exception {
            // Given
            SqlGenerationService service = createService(80);
            String csvWithBom = "\uFEFFid,name\n1,Alice";

            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenReturn(createS3Stream(csvWithBom));

            // When
            Method streamMethod = SqlGenerationService.class.getDeclaredMethod(
                    "streamCsvFromS3", String.class);
            streamMethod.setAccessible(true);
            ParsedCsvData result = (ParsedCsvData) streamMethod.invoke(service, "test/file.csv");

            // Then - BOM should be stripped from first header
            assertThat(result.headers().get(0)).isEqualTo("id");
            assertThat(result.headers().get(0).charAt(0)).isNotEqualTo('\uFEFF');
        }

        @Test
        @DisplayName("should handle gzipped CSV files when streaming")
        void shouldHandleGzippedCsvFilesWhenStreaming() throws Exception {
            // Given
            SqlGenerationService service = createService(80);
            String csvContent = "id,name\n1,Alice\n2,Bob";

            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenReturn(createGzipS3Stream(csvContent));

            // When
            Method streamMethod = SqlGenerationService.class.getDeclaredMethod(
                    "streamCsvFromS3", String.class);
            streamMethod.setAccessible(true);
            ParsedCsvData result = (ParsedCsvData) streamMethod.invoke(service, "test/file.csv.gz");

            // Then
            assertThat(result.headers()).containsExactly("id", "name");
            assertThat(result.rows()).hasSize(2);
        }

        @Test
        @DisplayName("should normalize embedded newlines in streamed values")
        void shouldNormalizeEmbeddedNewlinesInStreamedValues() throws Exception {
            // Given - CSV with embedded newline in quoted field
            SqlGenerationService service = createService(80);
            String csvContent = "id,name,memo\n1,Alice,\"Line1\nLine2\"";

            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenReturn(createS3Stream(csvContent));

            // When
            Method streamMethod = SqlGenerationService.class.getDeclaredMethod(
                    "streamCsvFromS3", String.class);
            streamMethod.setAccessible(true);
            ParsedCsvData result = (ParsedCsvData) streamMethod.invoke(service, "test/file.csv");

            // Then - embedded newline should be normalized to space
            assertThat(result.rows()).hasSize(1);
            assertThat(result.rows().get(0).get(2)).isEqualTo("Line1 Line2");
        }

        @Test
        @DisplayName("should return empty data for empty S3 content")
        void shouldReturnEmptyDataForEmptyS3Content() throws Exception {
            // Given
            SqlGenerationService service = createService(80);

            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenReturn(createS3Stream(""));

            // When
            Method streamMethod = SqlGenerationService.class.getDeclaredMethod(
                    "streamCsvFromS3", String.class);
            streamMethod.setAccessible(true);
            ParsedCsvData result = (ParsedCsvData) streamMethod.invoke(service, "test/file.csv");

            // Then
            assertThat(result.headers()).isEmpty();
            assertThat(result.rows()).isEmpty();
        }

        @Test
        @DisplayName("should handle CSV with only headers and no data rows")
        void shouldHandleCsvWithOnlyHeaders() throws Exception {
            // Given
            SqlGenerationService service = createService(80);
            String csvContent = "id,name,email";

            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenReturn(createS3Stream(csvContent));

            // When
            Method streamMethod = SqlGenerationService.class.getDeclaredMethod(
                    "streamCsvFromS3", String.class);
            streamMethod.setAccessible(true);
            ParsedCsvData result = (ParsedCsvData) streamMethod.invoke(service, "test/file.csv");

            // Then
            assertThat(result.headers()).containsExactly("id", "name", "email");
            assertThat(result.rows()).isEmpty();
        }

        @Test
        @DisplayName("should use streaming compare in generateSqlForBatch flow")
        void shouldUseStreamingCompareInGenerateFlow() {
            // Given - set up a complete generation flow to verify streaming integration
            SqlGenerationService service = createService(80);

            UUID batchId = UUID.randomUUID();
            UUID siteId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            Long accountPluginId = 1L;

            AccountPlugin accountPlugin = mock(AccountPlugin.class);
            when(accountPlugin.isBaselineBatch(batchId)).thenReturn(false);
            when(accountPlugin.hasBaselineBatch()).thenReturn(true);
            when(accountPluginRepository.findById(accountPluginId)).thenReturn(Optional.of(accountPlugin));

            Batch batch = mock(Batch.class);
            when(batch.getId()).thenReturn(batchId);
            when(batch.getSiteId()).thenReturn(siteId);
            when(batch.getAccountId()).thenReturn(accountId);

            UploadedFile file = mock(UploadedFile.class);
            when(file.getOriginalFileName()).thenReturn("data.csv");
            when(file.getS3Key()).thenReturn("account/site/data.csv");

            when(batch.getUploadedFiles()).thenReturn(List.of(file));
            when(batchRepository.findByIdWithFiles(batchId)).thenReturn(Optional.of(batch));
            when(sqlGenerationRepository.existsBySourceBatchId(batchId)).thenReturn(false);

            Site site = mock(Site.class);
            when(site.getId()).thenReturn(siteId);
            when(site.getDomain()).thenReturn("test.com");
            when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));

            when(batchRepository.findPreviousBatchForSiteWithFiles(siteId, batchId))
                    .thenReturn(Optional.empty());

            // S3 returns CSV content for streaming
            String csv = "id,name\n1,Alice\n2,Bob";
            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenReturn(createS3Stream(csv));

            // Mock the list-based compare overload (streaming path)
            when(csvDiffService.compare(anyList(), anyList(), anyList()))
                    .thenReturn(List.of(
                            CsvRowDiff.added(1, new LinkedHashMap<>(Map.of("id", "1", "name", "Alice")))
                    ));

            when(sqlStatementGenerator.generate(any(), anyString(), any()))
                    .thenReturn("INSERT INTO data (id, name) VALUES ('1', 'Alice');\n");

            when(s3SqlFileStorageService.storeSqlFile(any(), any(), anyString()))
                    .thenReturn("plugins/bit-bi/test.sql");
            when(s3SqlFileStorageService.getFileSize(anyString())).thenReturn(100L);

            PluginSqlGeneration generation = mock(PluginSqlGeneration.class);
            when(generation.getId()).thenReturn(UUID.randomUUID());
            when(sqlGenerationRepository.save(any())).thenReturn(generation);

            // When
            Optional<PluginSqlGeneration> result = service.generateSqlForBatch(batchId, accountPluginId);

            // Then - verify the list-based compare was called (not string-based)
            assertThat(result).isPresent();
            verify(csvDiffService).compare(anyList(), anyList(), anyList());
            verify(csvDiffService, never()).compare(anyString(), anyString(), anyList());
        }
    }

    @Nested
    @DisplayName("Memory Backpressure")
    class MemoryBackpressure {

        @Test
        @DisplayName("should report heap usage percentage")
        void shouldReportHeapUsagePercentage() {
            // Given
            SqlGenerationService service = createService(80);

            // When
            int heapPercent = service.getHeapUsagePercent();

            // Then - should be between 0 and 100
            assertThat(heapPercent).isBetween(0, 100);
        }

        @Test
        @DisplayName("should abort remaining files when memory pressure is high")
        void shouldAbortRemainingFilesWhenMemoryPressureHigh() {
            // Given - threshold set to 0% so memory is always "high"
            SqlGenerationService service = spy(createService(0));

            UUID batchId = UUID.randomUUID();
            UUID siteId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            Long accountPluginId = 1L;

            AccountPlugin accountPlugin = mock(AccountPlugin.class);
            when(accountPlugin.isBaselineBatch(batchId)).thenReturn(false);
            when(accountPlugin.hasBaselineBatch()).thenReturn(true);
            when(accountPluginRepository.findById(accountPluginId)).thenReturn(Optional.of(accountPlugin));

            Batch batch = mock(Batch.class);
            when(batch.getId()).thenReturn(batchId);
            when(batch.getSiteId()).thenReturn(siteId);
            when(batch.getAccountId()).thenReturn(accountId);

            UploadedFile file1 = mock(UploadedFile.class);
            when(file1.getOriginalFileName()).thenReturn("file1.csv");
            when(file1.getS3Key()).thenReturn("account/site/file1.csv");

            UploadedFile file2 = mock(UploadedFile.class);
            when(file2.getOriginalFileName()).thenReturn("file2.csv");
            when(file2.getS3Key()).thenReturn("account/site/file2.csv");

            when(batch.getUploadedFiles()).thenReturn(List.of(file1, file2));
            when(batchRepository.findByIdWithFiles(batchId)).thenReturn(Optional.of(batch));
            when(sqlGenerationRepository.existsBySourceBatchId(batchId)).thenReturn(false);

            Site site = mock(Site.class);
            when(site.getId()).thenReturn(siteId);
            when(site.getDomain()).thenReturn("test.com");
            when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));

            when(batchRepository.findPreviousBatchForSiteWithFiles(siteId, batchId))
                    .thenReturn(Optional.empty());

            // When
            Optional<PluginSqlGeneration> result = service.generateSqlForBatch(batchId, accountPluginId);

            // Then - should return empty (no files processed due to memory pressure)
            assertThat(result).isEmpty();
            // Should NOT have read from S3 (aborted before processing)
            verify(s3Client, never()).getObject(any(GetObjectRequest.class));
            // Should have recorded memory pressure metric
            assertThat(meterRegistry.counter("sql.generation.aborted.memory_pressure").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("should process files normally when memory pressure is low")
        void shouldProcessFilesNormallyWhenMemoryPressureLow() {
            // Given - threshold set to 100% so memory is never "high"
            SqlGenerationService service = createService(100);

            UUID batchId = UUID.randomUUID();
            UUID siteId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            Long accountPluginId = 1L;

            AccountPlugin accountPlugin = mock(AccountPlugin.class);
            when(accountPlugin.isBaselineBatch(batchId)).thenReturn(false);
            when(accountPlugin.hasBaselineBatch()).thenReturn(true);
            when(accountPluginRepository.findById(accountPluginId)).thenReturn(Optional.of(accountPlugin));

            Batch batch = mock(Batch.class);
            when(batch.getId()).thenReturn(batchId);
            when(batch.getSiteId()).thenReturn(siteId);
            when(batch.getAccountId()).thenReturn(accountId);

            UploadedFile file = mock(UploadedFile.class);
            when(file.getOriginalFileName()).thenReturn("data.csv");
            when(file.getS3Key()).thenReturn("account/site/data.csv");

            when(batch.getUploadedFiles()).thenReturn(List.of(file));
            when(batchRepository.findByIdWithFiles(batchId)).thenReturn(Optional.of(batch));
            when(sqlGenerationRepository.existsBySourceBatchId(batchId)).thenReturn(false);

            Site site = mock(Site.class);
            when(site.getId()).thenReturn(siteId);
            when(site.getDomain()).thenReturn("test.com");
            when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));

            when(batchRepository.findPreviousBatchForSiteWithFiles(siteId, batchId))
                    .thenReturn(Optional.empty());

            String csv = "id,name\n1,Alice";
            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenReturn(createS3Stream(csv));

            when(csvDiffService.compare(anyList(), anyList(), anyList()))
                    .thenReturn(List.of());

            // When
            Optional<PluginSqlGeneration> result = service.generateSqlForBatch(batchId, accountPluginId);

            // Then - file should have been processed (S3 was read)
            verify(s3Client).getObject(any(GetObjectRequest.class));
            // No memory pressure abort
            assertThat(meterRegistry.counter("sql.generation.aborted.memory_pressure").count())
                    .isEqualTo(0.0);
        }
    }
}
