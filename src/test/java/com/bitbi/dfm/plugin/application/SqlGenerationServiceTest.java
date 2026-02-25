package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.plugin.domain.*;
import com.bitbi.dfm.plugin.infrastructure.storage.S3SqlFileStorageService;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SqlGenerationService.
 * Tests BOM stripping from CSV content and per-file error handling.
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
    private Counter counter;

    @Mock
    private Timer timer;

    private SqlGenerationService service;

    private static final String BUCKET_NAME = "test-bucket";

    @BeforeEach
    void setUp() {
        when(meterRegistry.counter(anyString())).thenReturn(counter);
        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(counter);
        when(meterRegistry.timer(anyString())).thenReturn(timer);

        service = new SqlGenerationService(
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
                80
        );
        service.init();
    }

    @Nested
    @DisplayName("BOM Stripping")
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

            // When - invoke private method via reflection
            Method readMethod = SqlGenerationService.class.getDeclaredMethod(
                    "readCsvContentFromS3", String.class);
            readMethod.setAccessible(true);
            String result = (String) readMethod.invoke(service, "test/file.csv");

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
            Method readMethod = SqlGenerationService.class.getDeclaredMethod(
                    "readCsvContentFromS3", String.class);
            readMethod.setAccessible(true);
            String result = (String) readMethod.invoke(service, "test/file.csv");

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
            Method readMethod = SqlGenerationService.class.getDeclaredMethod(
                    "readCsvContentFromS3", String.class);
            readMethod.setAccessible(true);
            String result = (String) readMethod.invoke(service, "test/file.csv");

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
            Method readMethod = SqlGenerationService.class.getDeclaredMethod(
                    "readCsvContentFromS3", String.class);
            readMethod.setAccessible(true);
            String result = (String) readMethod.invoke(service, "test/file.csv");

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
            SqlGenerationService serviceWithRealMetrics = new SqlGenerationService(
                    batchRepository,
                    siteRepository,
                    accountPluginRepository,
                    sqlGenerationRepository,
                    csvDiffService,
                    sqlStatementGenerator,
                    s3SqlFileStorageService,
                    s3Client,
                    BUCKET_NAME,
                    realRegistry,
                    pluginAuditService,
                    2,
                    120,
                    100  // 100% threshold — disable memory pressure check in this test
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

            // Bad file throws InvalidCsvHeaderException during compare (list-based overload)
            when(csvDiffService.compare(anyList(), anyList(), anyList()))
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
}
