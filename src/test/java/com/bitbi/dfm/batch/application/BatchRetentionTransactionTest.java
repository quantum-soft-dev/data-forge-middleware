package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.batch.application.BatchRetentionTransaction.BatchContents;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.domain.BatchParquetArtifact;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.plugin.domain.PluginSqlGenerationRepository;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The database phase of batch retention (issue #344): one batch per transaction, re-locked with the
 * candidate predicate, and refusing to run without the transaction its proxy opens — the failure
 * that #344 was, made loud instead of a nightly {@code TransactionRequiredException}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BatchRetentionTransaction")
class BatchRetentionTransactionTest {

    @Mock
    private BatchRepository batchRepository;
    @Mock
    private UploadedFileRepository uploadedFileRepository;
    @Mock
    private PluginSqlGenerationRepository sqlGenerationRepository;
    @Mock
    private ChangelogSegmentService changelogSegmentService;
    @Mock
    private ChangelogSegmentRepository segmentRepository;
    @Mock
    private BatchParquetArtifactRepository artifactRepository;

    private BatchRetentionTransaction transaction;
    private UUID siteId;
    private UUID batchId;
    private LocalDateTime cutoff;

    @BeforeEach
    void setUp() {
        transaction = new BatchRetentionTransaction(batchRepository, uploadedFileRepository,
                sqlGenerationRepository, changelogSegmentService, segmentRepository, artifactRepository);
        siteId = UUID.randomUUID();
        batchId = UUID.randomUUID();
        cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(45);
        lenient().when(segmentRepository.countPendingQueueWorkByBatchId(any()))
                .thenReturn(QueueWorkStubs.pendingWork(0, 0));
        // Stands in for the transaction the Spring proxy opens; the refusal tests clear it.
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void clearAmbientTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    @DisplayName("deleting without an active transaction fails before touching the database")
    void deleteRefusesWithoutATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);

        assertThatThrownBy(() -> transaction.deleteBatch(siteId, batchId, cutoff))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");
        verifyNoInteractions(batchRepository, uploadedFileRepository, sqlGenerationRepository,
                changelogSegmentService, segmentRepository, artifactRepository);
    }

    @Test
    @DisplayName("describing without an active transaction fails too")
    void describeRefusesWithoutATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);

        assertThatThrownBy(() -> transaction.describeBatch(siteId, batchId))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(batchRepository, uploadedFileRepository, sqlGenerationRepository,
                changelogSegmentService, segmentRepository, artifactRepository);
    }

    @Test
    @DisplayName("the transactional methods are public and proxied, the shape #344 lacked")
    void transactionalMethodsAreProxyable() throws Exception {
        // cleanupSiteInDb was protected and self-invoked: annotated, and inert. Both entry points
        // here are public, called from another bean, and open (or require) a real transaction.
        Method delete = BatchRetentionTransaction.class.getMethod(
                "deleteBatch", UUID.class, UUID.class, LocalDateTime.class);
        Method describe = BatchRetentionTransaction.class.getMethod("describeBatch", UUID.class, UUID.class);
        for (Method method : List.of(delete, describe)) {
            assertThat(Modifier.isPublic(method.getModifiers())).as(method.getName()).isTrue();
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as(method.getName()).isNotNull();
            assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        }
        assertThat(delete.getAnnotation(Transactional.class).readOnly()).isFalse();
        assertThat(describe.getAnnotation(Transactional.class).readOnly()).isTrue();
    }

    @Test
    @DisplayName("a batch that is no longer a candidate under its lock is skipped and nothing is deleted")
    void skipsABatchThatIsNoLongerACandidate() {
        when(batchRepository.lockCleanupCandidate(batchId, siteId, cutoff)).thenReturn(Optional.empty());

        assertThat(transaction.deleteBatch(siteId, batchId, cutoff)).isEmpty();

        verify(batchRepository, never()).deleteById(any());
        verifyNoInteractions(sqlGenerationRepository, changelogSegmentService, artifactRepository);
    }

    @Test
    @DisplayName("deletes the batch's dependants and the batch, returning every object key for after the commit")
    void deletesRowsAndReturnsObjectKeys() {
        when(batchRepository.lockCleanupCandidate(batchId, siteId, cutoff))
                .thenReturn(Optional.of(mock(Batch.class)));
        givenRecordedObjects();
        when(changelogSegmentService.deleteMetadataByBatchId(batchId)).thenReturn(List.of("delta/s1.pb.gz"));
        when(segmentRepository.countPendingQueueWorkByBatchId(batchId))
                .thenReturn(QueueWorkStubs.pendingWork(2, 1));

        BatchContents contents = transaction.deleteBatch(siteId, batchId, cutoff).orElseThrow();

        assertThat(contents.s3Keys()).containsExactly("batch/file.csv", "plugins/sql.sql",
                S3CheckpointStorage.batchParquetKey(siteId, batchId, "items"), "delta/s1.pb.gz");
        assertThat(contents.bytes()).isEqualTo(300L);
        assertThat(contents.batchParquetPrefix())
                .isEqualTo(S3CheckpointStorage.batchParquetPrefix(siteId, batchId));
        assertThat(contents.pendingPluginSql()).isEqualTo(2);
        assertThat(contents.pendingEgress()).isEqualTo(1);
        assertThat(contents.destroyedPendingWork()).isTrue();

        InOrder order = inOrder(batchRepository, sqlGenerationRepository, artifactRepository,
                segmentRepository, changelogSegmentService);
        order.verify(batchRepository).lockCleanupCandidate(batchId, siteId, cutoff);
        order.verify(sqlGenerationRepository).deleteByComparisonBatchId(batchId);
        order.verify(sqlGenerationRepository).deleteBySourceBatchId(batchId);
        order.verify(artifactRepository).deleteByBatchId(batchId);
        order.verify(segmentRepository).countPendingQueueWorkByBatchId(batchId);
        // Segment rows only — their objects go with the rest, after the commit.
        order.verify(changelogSegmentService).deleteMetadataByBatchId(batchId);
        order.verify(batchRepository).deleteById(batchId);
        verify(changelogSegmentService, never()).deleteByBatchId(any());
    }

    @Test
    @DisplayName("describing a batch reads its objects, segments included, and deletes nothing")
    void describeReadsWithoutDeleting() {
        givenRecordedObjects();
        ChangelogSegment segment = mock(ChangelogSegment.class);
        when(segment.getS3Key()).thenReturn("delta/s1.pb.gz");
        when(segmentRepository.findByBatchId(batchId)).thenReturn(List.of(segment));

        BatchContents contents = transaction.describeBatch(siteId, batchId);

        assertThat(contents.s3Keys()).contains("batch/file.csv", "plugins/sql.sql", "delta/s1.pb.gz");
        assertThat(contents.bytes()).isEqualTo(300L);
        assertThat(contents.destroyedPendingWork()).isFalse();
        verify(batchRepository, never()).deleteById(any());
        verify(sqlGenerationRepository, never()).deleteBySourceBatchId(any());
        verify(artifactRepository, never()).deleteByBatchId(any());
        verifyNoInteractions(changelogSegmentService);
    }

    private void givenRecordedObjects() {
        UploadedFileRepository.FileKeySize fileKey = mock(UploadedFileRepository.FileKeySize.class);
        when(fileKey.getS3Key()).thenReturn("batch/file.csv");
        when(fileKey.getFileSize()).thenReturn(100L);
        when(uploadedFileRepository.findS3KeysByBatchId(batchId)).thenReturn(List.of(fileKey));
        PluginSqlGenerationRepository.S3KeySize sqlKey = mock(PluginSqlGenerationRepository.S3KeySize.class);
        when(sqlKey.getS3Key()).thenReturn("plugins/sql.sql");
        when(sqlKey.getFileSizeBytes()).thenReturn(200L);
        when(sqlGenerationRepository.findS3KeysByBatchId(batchId)).thenReturn(List.of(sqlKey));
        // Mid-build: no recorded key yet, so the key is derived from the row's identity.
        BatchParquetArtifact building = BatchParquetArtifact.pending(batchId, siteId, "items");
        building.markBuilding();
        when(artifactRepository.findByBatchId(batchId)).thenReturn(List.of(building));
    }
}
