package com.bitbi.dfm.plugin.unit;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.plugin.application.CsvDiffService;
import com.bitbi.dfm.plugin.application.PluginAuditService;
import com.bitbi.dfm.plugin.application.SqlGenerationService;
import com.bitbi.dfm.plugin.application.SqlGenerationService.SqlGenerationException;
import com.bitbi.dfm.plugin.application.SqlStatementGenerator;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
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
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SqlGenerationService concurrency limiting via Semaphore.
 * Verifies that concurrent SQL generation operations are bounded to prevent OOM.
 */
@DisplayName("SqlGenerationService Concurrency")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SqlGenerationConcurrencyTest {

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

    /**
     * Creates a SqlGenerationService with the specified concurrency settings.
     */
    private SqlGenerationService createService(int maxConcurrent, int semaphoreTimeoutSeconds) {
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
                maxConcurrent,
                semaphoreTimeoutSeconds,
                100  // 100% threshold — disable memory pressure check in concurrency tests
        );
        try {
            java.lang.reflect.Method initMethod = SqlGenerationService.class.getDeclaredMethod("init");
            initMethod.setAccessible(true);
            initMethod.invoke(service);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return service;
    }

    /**
     * Sets up mocks so that generateSqlForBatch reaches the semaphore-protected section
     * and blocks on the provided latch before completing.
     */
    private void setupMocksForBlockingGeneration(UUID batchId, Long accountPluginId, CountDownLatch blockLatch) {
        UUID siteId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

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

        // S3 read blocks on the latch to simulate slow processing
        when(s3Client.getObject(any(GetObjectRequest.class))).thenAnswer(invocation -> {
            if (blockLatch != null) {
                blockLatch.await();
            }
            String csv = "id,name\n1,Alice";
            return new ResponseInputStream<>(
                    GetObjectResponse.builder().build(),
                    new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))
            );
        });

        when(csvDiffService.compare(anyList(), anyList(), anyList()))
                .thenReturn(List.of());
    }

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    @Nested
    @DisplayName("Semaphore Concurrency Limiting")
    class SemaphoreConcurrencyLimiting {

        @Test
        @DisplayName("should limit concurrent generations to configured maximum")
        void shouldLimitConcurrentGenerations() throws Exception {
            // Given - max 2 concurrent generations
            int maxConcurrent = 2;
            SqlGenerationService service = createService(maxConcurrent, 120);

            CountDownLatch blockLatch = new CountDownLatch(1);
            AtomicInteger concurrentCount = new AtomicInteger(0);
            AtomicInteger maxObservedConcurrent = new AtomicInteger(0);
            CountDownLatch allStarted = new CountDownLatch(maxConcurrent);

            // Set up N+1 batches to exceed the limit
            int totalBatches = maxConcurrent + 1;
            UUID[] batchIds = new UUID[totalBatches];
            Long[] pluginIds = new Long[totalBatches];

            for (int i = 0; i < totalBatches; i++) {
                batchIds[i] = UUID.randomUUID();
                pluginIds[i] = (long) (i + 1);

                UUID siteId = UUID.randomUUID();
                UUID accountId = UUID.randomUUID();

                AccountPlugin accountPlugin = mock(AccountPlugin.class);
                when(accountPlugin.isBaselineBatch(batchIds[i])).thenReturn(false);
                when(accountPlugin.hasBaselineBatch()).thenReturn(true);
                when(accountPluginRepository.findById(pluginIds[i])).thenReturn(Optional.of(accountPlugin));

                Batch batch = mock(Batch.class);
                when(batch.getId()).thenReturn(batchIds[i]);
                when(batch.getSiteId()).thenReturn(siteId);
                when(batch.getAccountId()).thenReturn(accountId);

                UploadedFile file = mock(UploadedFile.class);
                when(file.getOriginalFileName()).thenReturn("data.csv");
                when(file.getS3Key()).thenReturn("account/site/data_" + i + ".csv");
                when(batch.getUploadedFiles()).thenReturn(List.of(file));

                when(batchRepository.findByIdWithFiles(batchIds[i])).thenReturn(Optional.of(batch));
                when(sqlGenerationRepository.existsBySourceBatchId(batchIds[i])).thenReturn(false);

                Site site = mock(Site.class);
                when(site.getId()).thenReturn(siteId);
                when(site.getDomain()).thenReturn("test" + i + ".com");
                when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));

                when(batchRepository.findPreviousBatchForSiteWithFiles(siteId, batchIds[i]))
                        .thenReturn(Optional.empty());
            }

            // S3 read tracks concurrency and blocks
            when(s3Client.getObject(any(GetObjectRequest.class))).thenAnswer(invocation -> {
                int current = concurrentCount.incrementAndGet();
                maxObservedConcurrent.updateAndGet(prev -> Math.max(prev, current));
                allStarted.countDown();
                blockLatch.await();
                concurrentCount.decrementAndGet();
                String csv = "id,name\n1,Alice";
                return new ResponseInputStream<>(
                        GetObjectResponse.builder().build(),
                        new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))
                );
            });

            when(csvDiffService.compare(anyList(), anyList(), anyList()))
                    .thenReturn(List.of());

            ExecutorService executor = Executors.newFixedThreadPool(totalBatches);
            try {
                // When - submit all batches concurrently
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < totalBatches; i++) {
                    int idx = i;
                    futures.add(executor.submit(() ->
                            service.generateSqlForBatch(batchIds[idx], pluginIds[idx])));
                }

                // Wait for the maxConcurrent tasks to actually start processing
                boolean started = allStarted.await(5, TimeUnit.SECONDS);
                assertThat(started).as("Expected %d tasks to start processing", maxConcurrent).isTrue();

                // Give a small window for the third task to potentially start (it shouldn't)
                Thread.sleep(200);

                // Then - only maxConcurrent should be running simultaneously
                assertThat(maxObservedConcurrent.get()).isLessThanOrEqualTo(maxConcurrent);

                // Release blocked tasks
                blockLatch.countDown();

                // Wait for all to complete
                for (Future<?> future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("should timeout when semaphore is not available within configured timeout")
        void shouldTimeoutWhenSemaphoreNotAvailable() throws Exception {
            // Given - max 1 concurrent, very short timeout (1 second)
            SqlGenerationService service = createService(1, 1);

            CountDownLatch blockLatch = new CountDownLatch(1);
            CountDownLatch firstStarted = new CountDownLatch(1);

            UUID batchId1 = UUID.randomUUID();
            UUID batchId2 = UUID.randomUUID();
            Long pluginId1 = 1L;
            Long pluginId2 = 2L;

            // Setup first batch - will block and hold the semaphore
            setupMocksForBlockingGeneration(batchId1, pluginId1, blockLatch);

            // Setup second batch - same setup but different IDs
            UUID siteId2 = UUID.randomUUID();
            UUID accountId2 = UUID.randomUUID();
            AccountPlugin accountPlugin2 = mock(AccountPlugin.class);
            when(accountPlugin2.isBaselineBatch(batchId2)).thenReturn(false);
            when(accountPlugin2.hasBaselineBatch()).thenReturn(true);
            when(accountPluginRepository.findById(pluginId2)).thenReturn(Optional.of(accountPlugin2));

            Batch batch2 = mock(Batch.class);
            when(batch2.getId()).thenReturn(batchId2);
            when(batch2.getSiteId()).thenReturn(siteId2);
            when(batch2.getAccountId()).thenReturn(accountId2);

            UploadedFile file2 = mock(UploadedFile.class);
            when(file2.getOriginalFileName()).thenReturn("data2.csv");
            when(file2.getS3Key()).thenReturn("account/site/data2.csv");
            when(batch2.getUploadedFiles()).thenReturn(List.of(file2));

            when(batchRepository.findByIdWithFiles(batchId2)).thenReturn(Optional.of(batch2));
            when(sqlGenerationRepository.existsBySourceBatchId(batchId2)).thenReturn(false);

            Site site2 = mock(Site.class);
            when(site2.getId()).thenReturn(siteId2);
            when(site2.getDomain()).thenReturn("test2.com");
            when(siteRepository.findById(siteId2)).thenReturn(Optional.of(site2));

            when(batchRepository.findPreviousBatchForSiteWithFiles(siteId2, batchId2))
                    .thenReturn(Optional.empty());

            // Override S3 mock to signal when first task starts, then block
            when(s3Client.getObject(any(GetObjectRequest.class))).thenAnswer(invocation -> {
                firstStarted.countDown();
                blockLatch.await();
                String csv = "id,name\n1,Alice";
                return new ResponseInputStream<>(
                        GetObjectResponse.builder().build(),
                        new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))
                );
            });

            when(csvDiffService.compare(anyList(), anyList(), anyList()))
                    .thenReturn(List.of());

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                // When - first task acquires semaphore
                executor.submit(() -> service.generateSqlForBatch(batchId1, pluginId1));

                // Wait for first task to actually start processing (holding semaphore)
                boolean started = firstStarted.await(5, TimeUnit.SECONDS);
                assertThat(started).as("First task should have started").isTrue();

                // Then - second task should timeout waiting for semaphore
                assertThatThrownBy(() -> service.generateSqlForBatch(batchId2, pluginId2))
                        .isInstanceOf(SqlGenerationException.class)
                        .hasMessageContaining("timed out");

                // Release the blocked first task
                blockLatch.countDown();
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("should release semaphore after successful generation")
        void shouldReleaseSemaphoreOnSuccess() {
            // Given - max 1 concurrent generation
            SqlGenerationService service = createService(1, 120);

            UUID batchId1 = UUID.randomUUID();
            UUID batchId2 = UUID.randomUUID();
            Long pluginId = 1L;

            // Setup both batches to complete immediately (no blocking)
            // First batch returns baseline (empty result, fast path)
            AccountPlugin accountPlugin = mock(AccountPlugin.class);
            when(accountPlugin.isBaselineBatch(any())).thenReturn(false);
            when(accountPlugin.hasBaselineBatch()).thenReturn(false);
            when(accountPluginRepository.findById(pluginId)).thenReturn(Optional.of(accountPlugin));

            // When - first call succeeds (baseline path, no CSV processing)
            service.generateSqlForBatch(batchId1, pluginId);

            // Reset to allow second call to also succeed
            when(accountPlugin.hasBaselineBatch()).thenReturn(false);

            // Then - second call should also succeed (semaphore was released)
            service.generateSqlForBatch(batchId2, pluginId);

            // Both should have completed without SqlGenerationException timeout
            verify(accountPluginRepository, times(2)).findById(pluginId);
        }

        @Test
        @DisplayName("should release semaphore even when generation throws exception")
        void shouldReleaseSemaphoreOnException() {
            // Given - max 1 concurrent generation
            SqlGenerationService service = createService(1, 120);

            UUID batchId1 = UUID.randomUUID();
            UUID batchId2 = UUID.randomUUID();
            Long pluginId1 = 1L;
            Long pluginId2 = 2L;

            // First call throws exception
            when(accountPluginRepository.findById(pluginId1))
                    .thenThrow(new RuntimeException("Database error"));

            // Second call uses baseline path (succeeds)
            AccountPlugin accountPlugin2 = mock(AccountPlugin.class);
            when(accountPlugin2.isBaselineBatch(batchId2)).thenReturn(false);
            when(accountPlugin2.hasBaselineBatch()).thenReturn(false);
            when(accountPluginRepository.findById(pluginId2)).thenReturn(Optional.of(accountPlugin2));

            // When - first call fails
            try {
                service.generateSqlForBatch(batchId1, pluginId1);
            } catch (RuntimeException e) {
                // Expected
            }

            // Then - second call should succeed (semaphore was released despite exception)
            service.generateSqlForBatch(batchId2, pluginId2);

            // Verify both calls were attempted
            verify(accountPluginRepository).findById(pluginId1);
            verify(accountPluginRepository).findById(pluginId2);
        }

        @Test
        @DisplayName("should record timeout metric when semaphore acquisition times out")
        void shouldRecordTimeoutMetric() throws Exception {
            // Given - max 1 concurrent, very short timeout
            SqlGenerationService service = createService(1, 1);

            CountDownLatch blockLatch = new CountDownLatch(1);
            CountDownLatch firstStarted = new CountDownLatch(1);

            UUID batchId1 = UUID.randomUUID();
            UUID batchId2 = UUID.randomUUID();
            Long pluginId1 = 1L;
            Long pluginId2 = 2L;

            setupMocksForBlockingGeneration(batchId1, pluginId1, blockLatch);

            // Setup second batch
            UUID siteId2 = UUID.randomUUID();
            UUID accountId2 = UUID.randomUUID();
            AccountPlugin accountPlugin2 = mock(AccountPlugin.class);
            when(accountPlugin2.isBaselineBatch(batchId2)).thenReturn(false);
            when(accountPlugin2.hasBaselineBatch()).thenReturn(true);
            when(accountPluginRepository.findById(pluginId2)).thenReturn(Optional.of(accountPlugin2));

            Batch batch2 = mock(Batch.class);
            when(batch2.getId()).thenReturn(batchId2);
            when(batch2.getSiteId()).thenReturn(siteId2);
            when(batch2.getAccountId()).thenReturn(accountId2);

            UploadedFile file2 = mock(UploadedFile.class);
            when(file2.getOriginalFileName()).thenReturn("data2.csv");
            when(file2.getS3Key()).thenReturn("account/site/data2.csv");
            when(batch2.getUploadedFiles()).thenReturn(List.of(file2));

            when(batchRepository.findByIdWithFiles(batchId2)).thenReturn(Optional.of(batch2));
            when(sqlGenerationRepository.existsBySourceBatchId(batchId2)).thenReturn(false);

            Site site2 = mock(Site.class);
            when(site2.getId()).thenReturn(siteId2);
            when(site2.getDomain()).thenReturn("test2.com");
            when(siteRepository.findById(siteId2)).thenReturn(Optional.of(site2));

            when(batchRepository.findPreviousBatchForSiteWithFiles(siteId2, batchId2))
                    .thenReturn(Optional.empty());

            // Override S3 mock to signal and block
            when(s3Client.getObject(any(GetObjectRequest.class))).thenAnswer(invocation -> {
                firstStarted.countDown();
                blockLatch.await();
                String csv = "id,name\n1,Alice";
                return new ResponseInputStream<>(
                        GetObjectResponse.builder().build(),
                        new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))
                );
            });

            when(csvDiffService.compare(anyList(), anyList(), anyList()))
                    .thenReturn(List.of());

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                // When - first task holds semaphore
                executor.submit(() -> service.generateSqlForBatch(batchId1, pluginId1));
                boolean started = firstStarted.await(5, TimeUnit.SECONDS);
                assertThat(started).isTrue();

                // Second task times out
                try {
                    service.generateSqlForBatch(batchId2, pluginId2);
                } catch (SqlGenerationException e) {
                    // Expected
                }

                // Then - timeout counter should be incremented
                assertThat(meterRegistry.counter("sql.generation.semaphore.timeouts").count())
                        .isEqualTo(1.0);

                blockLatch.countDown();
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("regenerateForBatch Concurrency")
    class RegenerateForBatchConcurrency {

        @Test
        @DisplayName("should also protect regenerateForBatch with semaphore")
        void shouldProtectRegenerateForBatchWithSemaphore() throws Exception {
            // Given - max 1 concurrent, short timeout
            SqlGenerationService service = createService(1, 1);

            CountDownLatch blockLatch = new CountDownLatch(1);
            CountDownLatch firstStarted = new CountDownLatch(1);

            UUID batchId1 = UUID.randomUUID();
            UUID batchId2 = UUID.randomUUID();
            Long pluginId1 = 1L;
            Long pluginId2 = 2L;

            // First batch setup for generateSqlForBatch (holds semaphore)
            setupMocksForBlockingGeneration(batchId1, pluginId1, blockLatch);

            // Override S3 to signal first started
            when(s3Client.getObject(any(GetObjectRequest.class))).thenAnswer(invocation -> {
                firstStarted.countDown();
                blockLatch.await();
                String csv = "id,name\n1,Alice";
                return new ResponseInputStream<>(
                        GetObjectResponse.builder().build(),
                        new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))
                );
            });

            when(csvDiffService.compare(anyList(), anyList(), anyList()))
                    .thenReturn(List.of());

            // Second batch setup for regenerateForBatch
            UUID siteId2 = UUID.randomUUID();
            UUID accountId2 = UUID.randomUUID();

            Batch batch2 = mock(Batch.class);
            when(batch2.getId()).thenReturn(batchId2);
            when(batch2.getSiteId()).thenReturn(siteId2);
            when(batch2.getAccountId()).thenReturn(accountId2);

            UploadedFile file2 = mock(UploadedFile.class);
            when(file2.getOriginalFileName()).thenReturn("data2.csv");
            when(file2.getS3Key()).thenReturn("account/site/data2.csv");
            when(batch2.getUploadedFiles()).thenReturn(List.of(file2));

            when(batchRepository.findByIdWithFiles(batchId2)).thenReturn(Optional.of(batch2));

            Site site2 = mock(Site.class);
            when(site2.getId()).thenReturn(siteId2);
            when(site2.getDomain()).thenReturn("test2.com");
            when(siteRepository.findById(siteId2)).thenReturn(Optional.of(site2));

            when(batchRepository.findPreviousBatchForSiteWithFiles(siteId2, batchId2))
                    .thenReturn(Optional.empty());

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                // When - first task holds semaphore via generateSqlForBatch
                executor.submit(() -> service.generateSqlForBatch(batchId1, pluginId1));
                boolean started = firstStarted.await(5, TimeUnit.SECONDS);
                assertThat(started).isTrue();

                // Then - regenerateForBatch should also timeout
                assertThatThrownBy(() -> service.regenerateForBatch(batchId2, pluginId2))
                        .isInstanceOf(SqlGenerationException.class)
                        .hasMessageContaining("timed out");

                blockLatch.countDown();
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("Semaphore Metrics")
    class SemaphoreMetrics {

        @Test
        @DisplayName("should register queue size gauge")
        void shouldRegisterQueueSizeGauge() {
            // Given / When
            SqlGenerationService service = createService(2, 120);

            // Then - gauge should be registered
            assertThat(meterRegistry.find("sql.generation.semaphore.queue.size").gauge())
                    .isNotNull();
        }

        @Test
        @DisplayName("should show zero queue size when no waiters")
        void shouldShowZeroQueueSizeWhenNoWaiters() {
            // Given
            SqlGenerationService service = createService(2, 120);

            // Then
            assertThat(meterRegistry.get("sql.generation.semaphore.queue.size").gauge().value())
                    .isEqualTo(0.0);
        }
    }
}
