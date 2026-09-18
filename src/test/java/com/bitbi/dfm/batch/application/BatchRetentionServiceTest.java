package com.bitbi.dfm.batch.application;

import ch.qos.logback.classic.Level;
import com.bitbi.dfm.batch.application.BatchRetentionTransaction.BatchContents;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.delta.application.DeltaMetrics;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService.DeleteObjectsResult;
import com.bitbi.dfm.util.LogCapture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The retention orchestrator: which batch transaction it runs, what it does with the result, and
 * that objects go only after the rows (issue #344). The database phase itself is
 * {@link BatchRetentionTransactionTest}; the wiring — that the phase really is a transaction — is
 * {@code BatchRetentionIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BatchRetentionService")
class BatchRetentionServiceTest {

    @Mock
    private BatchRepository batchRepository;

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private BatchRetentionTransaction retentionTransaction;

    @Mock
    private S3FileStorageService s3FileStorageService;

    private SimpleMeterRegistry meterRegistry;
    private BatchRetentionService service;

    private UUID siteId;
    private UUID batchId;
    private String batchPrefix;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new BatchRetentionService(batchRepository, siteRepository, retentionTransaction,
                s3FileStorageService, new DeltaMetrics(meterRegistry));
        siteId = UUID.randomUUID();
        batchId = UUID.randomUUID();
        batchPrefix = S3CheckpointStorage.batchParquetPrefix(siteId, batchId);

        Site site = mock(Site.class);
        lenient().when(site.getId()).thenReturn(siteId);
        lenient().when(site.getRetentionDays()).thenReturn(45);
        lenient().when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));
    }

    @AfterEach
    void clearAmbientTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    @DisplayName("refuses to run inside a caller's transaction, before reading anything")
    void refusesInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> service.runCleanup(request(false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");
        verifyNoInteractions(siteRepository, batchRepository, retentionTransaction, s3FileStorageService);
    }

    @Test
    @DisplayName("dry run describes every candidate and deletes neither rows nor objects")
    void dryRunDescribesWithoutDeleting() {
        givenCandidates(batchId);
        when(retentionTransaction.describeBatch(siteId, batchId)).thenReturn(
                contents(300L, List.of("batch/file.csv", "plugins/sql.sql"), 0, 0));
        when(s3FileStorageService.listAllKeys(batchPrefix)).thenReturn(List.of());

        BatchRetentionService.BatchCleanupSummary summary = service.runCleanup(request(true));

        assertThat(summary.candidates()).isEqualTo(1);
        assertThat(summary.deletedBatches()).isZero();
        assertThat(summary.deletedFiles()).isEqualTo(2);
        assertThat(summary.deletedBytes()).isEqualTo(300L);
        verify(retentionTransaction, never()).deleteBatch(any(), any(), any());
        verify(s3FileStorageService, never()).deleteObjects(any());
        assertThat(counter("pending_plugin_sql")).isZero();
    }

    @Test
    @DisplayName("deletes a batch's rows through its transaction, then its objects and prefix orphans")
    void deletesRowsThenObjects() {
        givenCandidates(batchId);
        String winnerKey = batchPrefix + "attempts/winner/orders.parquet";
        String orphanKey = batchPrefix + "attempts/dead/items.parquet";
        when(retentionTransaction.deleteBatch(eq(siteId), eq(batchId), any())).thenReturn(Optional.of(
                contents(500L, List.of("batch/file.csv", winnerKey, "delta/segments/s.pb.gz"), 0, 0)));
        when(s3FileStorageService.listAllKeys(batchPrefix)).thenReturn(List.of(winnerKey, orphanKey));
        when(s3FileStorageService.deleteObjects(any())).thenReturn(new DeleteObjectsResult(4, List.of()));

        BatchRetentionService.BatchCleanupSummary summary = service.runCleanup(request(false));

        assertThat(summary.errors()).isEmpty();
        assertThat(summary.deletedBatches()).isEqualTo(1);
        assertThat(summary.deletedBytes()).isEqualTo(500L);
        assertThat(summary.deletedFiles()).isEqualTo(4);
        InOrder order = inOrder(retentionTransaction, s3FileStorageService);
        order.verify(retentionTransaction).deleteBatch(eq(siteId), eq(batchId), any());
        order.verify(s3FileStorageService).deleteObjects(
                List.of("batch/file.csv", winnerKey, "delta/segments/s.pb.gz", orphanKey));
    }

    @Test
    @DisplayName("a batch its transaction skips (gone, locked, no longer a candidate) is neither deleted nor an error")
    void skippedBatchIsNotCounted() {
        givenCandidates(batchId);
        when(retentionTransaction.deleteBatch(eq(siteId), eq(batchId), any())).thenReturn(Optional.empty());
        when(s3FileStorageService.deleteObjects(List.of())).thenReturn(new DeleteObjectsResult(0, List.of()));

        BatchRetentionService.BatchCleanupSummary summary = service.runCleanup(request(false));

        assertThat(summary.candidates()).isEqualTo(1);
        assertThat(summary.deletedBatches()).isZero();
        assertThat(summary.errors()).isEmpty();
        verify(s3FileStorageService, never()).listAllKeys(any());
    }

    @Test
    @DisplayName("one failing batch is recorded and the next batch is still deleted")
    void failingBatchDoesNotStopTheSite() {
        UUID failing = UUID.randomUUID();
        givenCandidates(failing, batchId);
        when(retentionTransaction.deleteBatch(eq(siteId), eq(failing), any()))
                .thenThrow(new RuntimeException("FK contention"));
        when(retentionTransaction.deleteBatch(eq(siteId), eq(batchId), any()))
                .thenReturn(Optional.of(contents(10L, List.of("k"), 0, 0)));
        when(s3FileStorageService.listAllKeys(batchPrefix)).thenReturn(List.of());
        when(s3FileStorageService.deleteObjects(List.of("k"))).thenReturn(new DeleteObjectsResult(1, List.of()));

        BatchRetentionService.BatchCleanupSummary summary = service.runCleanup(request(false));

        assertThat(summary.deletedBatches()).isEqualTo(1);
        assertThat(summary.errors()).singleElement().asString()
                .contains(failing.toString()).contains("FK contention");
        verify(s3FileStorageService, never()).listAllKeys(S3CheckpointStorage.batchParquetPrefix(siteId, failing));
    }

    @Test
    @DisplayName("prefix listing failure preserves exact-key cleanup and the committed row deletion")
    void listingFailureFallsBackToRecordedKeys() {
        givenCandidates(batchId);
        when(retentionTransaction.deleteBatch(eq(siteId), eq(batchId), any()))
                .thenReturn(Optional.of(contents(4L, List.of("legacy.parquet"), 0, 0)));
        when(s3FileStorageService.listAllKeys(batchPrefix))
                .thenThrow(new S3FileStorageService.FileStorageException("list denied"));
        when(s3FileStorageService.deleteObjects(List.of("legacy.parquet")))
                .thenReturn(new DeleteObjectsResult(1, List.of()));

        BatchRetentionService.BatchCleanupSummary summary = service.runCleanup(request(false));

        assertThat(summary.deletedBatches()).isEqualTo(1);
        assertThat(summary.errors()).anyMatch(error -> error.contains("list denied"));
        verify(s3FileStorageService).deleteObjects(List.of("legacy.parquet"));
    }

    @Test
    @DisplayName("S3 delete errors are reported, the committed rows stay deleted (best effort)")
    void s3ErrorsAreBestEffort() {
        givenCandidates(batchId);
        when(retentionTransaction.deleteBatch(eq(siteId), eq(batchId), any()))
                .thenReturn(Optional.of(contents(100L, List.of("batch/file.csv"), 0, 0)));
        when(s3FileStorageService.listAllKeys(any())).thenReturn(List.of());
        when(s3FileStorageService.deleteObjects(any())).thenReturn(new DeleteObjectsResult(0, List.of("error")));

        BatchRetentionService.BatchCleanupSummary summary = service.runCleanup(request(false));

        assertThat(summary.deletedBatches()).isEqualTo(1);
        assertThat(summary.errors()).anyMatch(error -> error.contains("S3 delete errors"));
    }

    @Test
    @DisplayName("deleting a batch that still carried pending queue work is counted and warned (issue #212)")
    void countsAndWarnsDestroyedPendingWork() throws Exception {
        givenCandidates(batchId);
        when(retentionTransaction.deleteBatch(eq(siteId), eq(batchId), any()))
                .thenReturn(Optional.of(contents(0L, List.of(), 2, 1)));
        when(s3FileStorageService.listAllKeys(any())).thenReturn(List.of());
        when(s3FileStorageService.deleteObjects(any())).thenReturn(new DeleteObjectsResult(0, List.of()));

        try (LogCapture capture = LogCapture.attachTo(BatchRetentionService.class)) {
            service.runCleanup(request(false));

            assertThat(capture.messagesAt(Level.WARN))
                    .anyMatch(message -> message.contains("pending queue work")
                            && message.contains(batchId.toString()));
        }
        assertThat(counter("pending_plugin_sql")).isEqualTo(2.0);
        assertThat(counter("pending_egress")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a batch whose transaction fails moves no deleted-pending counter (issue #212, R2-4)")
    void failedBatchCountsNoDestroyedWork() {
        givenCandidates(batchId);
        when(retentionTransaction.deleteBatch(eq(siteId), eq(batchId), any()))
                .thenThrow(new RuntimeException("rolled back"));
        when(s3FileStorageService.deleteObjects(List.of())).thenReturn(new DeleteObjectsResult(0, List.of()));

        BatchRetentionService.BatchCleanupSummary summary = service.runCleanup(request(false));

        assertThat(summary.errors()).isNotEmpty();
        assertThat(counter("pending_plugin_sql")).isZero();
        assertThat(counter("pending_egress")).isZero();
    }

    private void givenCandidates(UUID... ids) {
        List<Batch> batches = new java.util.ArrayList<>();
        for (UUID id : ids) {
            Batch batch = mock(Batch.class);
            when(batch.getId()).thenReturn(id);
            batches.add(batch);
        }
        when(batchRepository.findCleanupCandidatesForSite(eq(siteId), any(), anyInt())).thenReturn(batches);
    }

    private BatchContents contents(long bytes, List<String> keys, long pendingSql, long pendingEgress) {
        return new BatchContents(bytes, keys, batchPrefix, pendingSql, pendingEgress);
    }

    private BatchRetentionService.BatchCleanupRequest request(boolean dryRun) {
        return new BatchRetentionService.BatchCleanupRequest(
                siteId, null, null, LocalDateTime.now(ZoneOffset.UTC).minusDays(1), 10, dryRun);
    }

    private double counter(String reason) {
        return meterRegistry.get("delta.retention.segments.deleted-pending")
                .tag("reason", reason).counter().count();
    }
}
