package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginSqlGeneration;
import com.bitbi.dfm.plugin.domain.PluginSqlGenerationRepository;
import com.bitbi.dfm.plugin.infrastructure.storage.S3SqlFileStorageService;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.site.domain.SiteType;
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
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SqlGenerationService memory backpressure and init guard.
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

    @Mock
    private CdcSqlGenerationStrategy cdcStrategy;

    @Mock
    private SiteSchemaService siteSchemaService;

    private SimpleMeterRegistry meterRegistry;

    private static final String BUCKET_NAME = "test-bucket";

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    private SqlGenerationService createService(int heapThreshold) {
        DbfSqlGenerationStrategy dbfStrategy = new DbfSqlGenerationStrategy(
                csvDiffService, sqlStatementGenerator, s3Client, BUCKET_NAME, meterRegistry);
        SqlGenerationService service = new SqlGenerationService(
                batchRepository,
                siteRepository,
                accountPluginRepository,
                sqlGenerationRepository,
                s3SqlFileStorageService,
                meterRegistry,
                pluginAuditService,
                dbfStrategy,
                cdcStrategy,
                siteSchemaService,
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

    @Nested
    @DisplayName("Init Guard")
    class InitGuard {

        @Test
        @DisplayName("should not replace semaphore on double init")
        void shouldNotReplaceSemaphoreOnDoubleInit() {
            // Given
            SqlGenerationService service = createService(80);

            // When - call init() again
            service.init();

            // Then - should still work (no duplicate gauge exception, semaphore intact)
            assertThat(meterRegistry.find("sql.generation.semaphore.queue.size").gauge())
                    .isNotNull();
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
        @DisplayName("should abort SQL generation when memory pressure is high")
        void shouldAbortWhenMemoryPressureHigh() {
            // Given - threshold set to 0% so memory is always "high"
            SqlGenerationService service = createService(0);

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
            when(site.getSiteType()).thenReturn(SiteType.DBF);
            when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));

            when(batchRepository.findPreviousBatchForSiteWithFiles(siteId, batchId))
                    .thenReturn(Optional.empty());

            // When
            Optional<PluginSqlGeneration> result = service.generateSqlForBatch(batchId, accountPluginId);

            // Then - should return empty (aborted due to memory pressure before strategy runs)
            assertThat(result).isEmpty();
            // Should NOT have read from S3 (aborted before strategy invocation)
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
            when(site.getSiteType()).thenReturn(SiteType.DBF);
            when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));

            when(batchRepository.findPreviousBatchForSiteWithFiles(siteId, batchId))
                    .thenReturn(Optional.empty());

            String csv = "id,name\n1,Alice";
            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenReturn(createS3Stream(csv));

            when(csvDiffService.compare(anyString(), anyString(), anyList()))
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
