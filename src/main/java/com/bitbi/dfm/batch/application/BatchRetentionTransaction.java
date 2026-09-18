package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.domain.BatchParquetArtifact;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactKey;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository.PendingQueueWork;
import com.bitbi.dfm.plugin.domain.PluginSqlGenerationRepository;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The database half of batch retention: one transaction per batch (issue #344).
 * <p>
 * A bean of its own for the reason {@code DeltaSessionCommitTransaction} (#147) and
 * {@code SqlGenerationPersistence} (#164) are: a {@code @Transactional} method invoked on
 * {@code this} is not proxied. {@code BatchRetentionService.cleanupSiteInDb} was exactly that — a
 * {@code protected}, self-invoked {@code @Transactional} — so the nightly pass ran its bulk deletes
 * with no transaction, threw {@code TransactionRequiredException} on the first one, and deleted
 * nothing, every night.
 * </p>
 * <p>
 * <b>Per batch, not per site.</b> The caller records a failing batch in the summary and moves on to
 * the next; inside one site-wide transaction that cannot work, because PostgreSQL refuses every
 * statement after the first error until the transaction ends, so one bad batch would roll the whole
 * site back. Each transaction first re-locks its batch with the shared candidate predicate
 * ({@code lockCleanupCandidate}), so the lock is held until the rows are gone and a batch that
 * stopped being a candidate since the listing — or that another pass is deleting — is skipped.
 * </p>
 * <p>
 * No S3 here: every object key is returned, and the caller deletes the objects after this
 * transaction has committed — which also takes the changelog segments' object deletes out of the
 * transaction they used to run in.
 * </p>
 */
@Component
class BatchRetentionTransaction {

    private final BatchRepository batchRepository;
    private final UploadedFileRepository uploadedFileRepository;
    private final PluginSqlGenerationRepository sqlGenerationRepository;
    private final ChangelogSegmentService changelogSegmentService;
    private final ChangelogSegmentRepository segmentRepository;
    private final BatchParquetArtifactRepository artifactRepository;

    BatchRetentionTransaction(BatchRepository batchRepository,
                              UploadedFileRepository uploadedFileRepository,
                              PluginSqlGenerationRepository sqlGenerationRepository,
                              ChangelogSegmentService changelogSegmentService,
                              ChangelogSegmentRepository segmentRepository,
                              BatchParquetArtifactRepository artifactRepository) {
        this.batchRepository = batchRepository;
        this.uploadedFileRepository = uploadedFileRepository;
        this.sqlGenerationRepository = sqlGenerationRepository;
        this.changelogSegmentService = changelogSegmentService;
        this.segmentRepository = segmentRepository;
        this.artifactRepository = artifactRepository;
    }

    /**
     * Delete one batch's rows if it is still a cleanup candidate.
     *
     * @return what was deleted and which objects to remove after the commit, or empty when the
     *         batch is gone, locked by another pass, or no longer a candidate
     */
    @Transactional
    public Optional<BatchContents> deleteBatch(UUID siteId, UUID batchId, LocalDateTime cutoff) {
        requireTransaction();
        if (batchRepository.lockCleanupCandidate(batchId, siteId, cutoff).isEmpty()) {
            return Optional.empty();
        }
        List<String> keys = new ArrayList<>();
        long bytes = collectKeys(batchId, keys);

        // Prevent leaving plugin SQL generations referencing a deleted comparison batch.
        sqlGenerationRepository.deleteByComparisonBatchId(batchId);
        sqlGenerationRepository.deleteBySourceBatchId(batchId);
        artifactRepository.deleteByBatchId(batchId);

        // #212: read before the delete (the rows are gone after it); the caller counts and WARNs
        // only once this transaction has committed, so a rolled-back batch is no phantom loss.
        PendingQueueWork pending = segmentRepository.countPendingQueueWorkByBatchId(batchId);
        long pendingPluginSql = pending.getPendingPluginSql();
        long pendingEgress = pending.getPendingEgress();

        // Segment rows here (their batch_id FK does not cascade), their objects after the commit.
        keys.addAll(changelogSegmentService.deleteMetadataByBatchId(batchId));
        batchRepository.deleteById(batchId);

        return Optional.of(new BatchContents(bytes, List.copyOf(keys),
                BatchParquetArtifactKey.batchPrefix(siteId, batchId), pendingPluginSql, pendingEgress));
    }

    /** What deleting one batch would remove — the dry run's read, deleting nothing. */
    @Transactional(readOnly = true)
    public BatchContents describeBatch(UUID siteId, UUID batchId) {
        requireTransaction();
        List<String> keys = new ArrayList<>();
        long bytes = collectKeys(batchId, keys);
        for (ChangelogSegment segment : segmentRepository.findByBatchId(batchId)) {
            keys.add(segment.getS3Key());
        }
        return new BatchContents(bytes, List.copyOf(keys),
                BatchParquetArtifactKey.batchPrefix(siteId, batchId), 0L, 0L);
    }

    private long collectKeys(UUID batchId, List<String> keys) {
        long bytes = 0L;
        for (UploadedFileRepository.FileKeySize fileKey : uploadedFileRepository.findS3KeysByBatchId(batchId)) {
            if (fileKey.getS3Key() != null) {
                keys.add(fileKey.getS3Key());
            }
            if (fileKey.getFileSize() != null) {
                bytes += fileKey.getFileSize();
            }
        }
        for (PluginSqlGenerationRepository.S3KeySize sqlKey : sqlGenerationRepository.findS3KeysByBatchId(batchId)) {
            if (sqlKey.getS3Key() != null) {
                keys.add(sqlKey.getS3Key());
            }
            if (sqlKey.getFileSizeBytes() != null) {
                bytes += sqlKey.getFileSizeBytes();
            }
        }
        for (BatchParquetArtifact artifact : artifactRepository.findByBatchId(batchId)) {
            // READY rows name both new attempt keys and legacy stable keys exactly. A row without
            // published metadata retains the legacy derived-key fallback; prefix enumeration after
            // the commit discovers attempt orphans.
            keys.add(artifact.getS3Key() != null ? artifact.getS3Key() : artifact.expectedS3Key());
            if (artifact.getFileSize() != null) {
                bytes += artifact.getFileSize();
            }
        }
        return bytes;
    }

    /**
     * The inverse of {@code refuseInsideTransaction}: this bean's work is only correct inside the
     * transaction its proxy opens. Reached without one — a self-invocation, a {@code new}, a future
     * {@code protected} copy — it fails here, naming the wiring, instead of on the first bulk delete
     * as {@code TransactionRequiredException} (issue #344).
     */
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Batch retention's database phase must run inside a "
                    + "transaction — call it through the Spring proxy (issue #344)");
        }
    }

    /**
     * One batch's objects and bytes, plus the queue work its deletion destroyed.
     *
     * @param bytes               bytes of the recorded objects
     * @param s3Keys              exact object keys, deleted by the caller after the commit
     * @param batchParquetPrefix  the batch's Parquet prefix, enumerated by the caller for orphans
     * @param pendingPluginSql    segments deleted while still awaiting plugin SQL (#212)
     * @param pendingEgress       segments deleted while still awaiting egress (#212)
     */
    record BatchContents(long bytes, List<String> s3Keys, String batchParquetPrefix,
                         long pendingPluginSql, long pendingEgress) {

        boolean destroyedPendingWork() {
            return pendingPluginSql > 0 || pendingEgress > 0;
        }
    }
}
