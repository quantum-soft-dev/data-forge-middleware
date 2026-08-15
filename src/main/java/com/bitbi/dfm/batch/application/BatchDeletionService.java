package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.domain.BatchParquetArtifact;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactKey;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.shared.storage.S3PrefixListing;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Application boundary for deleting one batch and its Delta-owned dependants. */
@Service
public class BatchDeletionService {

    private static final Logger log = LoggerFactory.getLogger(BatchDeletionService.class);

    private final BatchRepository batchRepository;
    private final ChangelogSegmentService segmentService;
    private final BatchParquetArtifactRepository artifactRepository;
    private final S3FileStorageService storage;

    public BatchDeletionService(BatchRepository batchRepository,
                                ChangelogSegmentService segmentService,
                                BatchParquetArtifactRepository artifactRepository,
                                S3FileStorageService storage) {
        this.batchRepository = batchRepository;
        this.segmentService = segmentService;
        this.artifactRepository = artifactRepository;
        this.storage = storage;
    }

    /**
     * Delete artifact rows, changelog segments, and the batch in one transaction, then issue the
     * existing best-effort S3 cleanup only after that transaction commits.
     *
     * @return false when the batch does not exist
     */
    @Transactional
    public boolean deleteBatch(UUID batchId) {
        Optional<Batch> found = batchRepository.findById(batchId);
        if (found.isEmpty()) {
            return false;
        }
        String batchParquetPrefix = BatchParquetArtifactKey.batchPrefix(
                found.get().getSiteId(), batchId);

        List<String> objectKeys = new ArrayList<>(artifactRepository.findByBatchId(batchId).stream()
                .map(BatchDeletionService::cleanupKey)
                .toList());
        artifactRepository.deleteByBatchId(batchId);
        objectKeys.addAll(segmentService.deleteMetadataByBatchId(batchId));
        batchRepository.deleteById(batchId);

        deleteObjectsAfterCommit(List.copyOf(objectKeys), batchParquetPrefix, batchId);
        return true;
    }

    private static String cleanupKey(BatchParquetArtifact artifact) {
        return artifact.getS3Key() != null ? artifact.getS3Key() : artifact.expectedS3Key();
    }

    private void deleteObjectsAfterCommit(List<String> objectKeys, String batchParquetPrefix,
                                          UUID batchId) {
        Runnable cleanup = () -> deleteObjects(objectKeys, batchParquetPrefix, batchId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup.run();
                }
            });
        } else {
            // Keeps explicit non-proxied callers and unit tests useful; production enters through
            // the transactional Spring proxy and therefore registers the callback above.
            cleanup.run();
        }
    }

    private void deleteObjects(List<String> objectKeys, String batchParquetPrefix, UUID batchId) {
        LinkedHashSet<String> discovered = new LinkedHashSet<>(objectKeys);
        try {
            S3PrefixListing listing = storage.listAllKeys(batchParquetPrefix);
            if (listing.truncated()) {
                // Same fallback as a thrown listing: the recorded keys are the complete set we
                // trust. A partial prefix walk is not used (issue #122).
                log.warn("Batch {} deleted but its Parquet prefix listing was truncated; "
                        + "exact-key cleanup continues", batchId);
            } else {
                discovered.addAll(listing.keys());
            }
        } catch (RuntimeException e) {
            log.warn("Batch {} deleted but its Parquet prefix could not be enumerated; "
                    + "exact-key cleanup continues", batchId, e);
        }
        if (discovered.isEmpty()) {
            return;
        }
        try {
            S3FileStorageService.DeleteObjectsResult deleted = storage.deleteObjects(List.copyOf(discovered));
            if (!deleted.errors().isEmpty()) {
                log.warn("Batch {} deleted but {} object(s) remain orphaned: {}",
                        batchId, deleted.errors().size(), deleted.errors());
            }
        } catch (RuntimeException e) {
            // DB deletion is already committed. Object cleanup is deliberately best-effort; an
            // orphan is safer than reporting that the successfully deleted batch still exists.
            log.warn("Batch {} deleted but object cleanup failed", batchId, e);
        }
    }
}
