package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
     * existing best-effort S3 cleanup while the manifest identities are still available.
     *
     * @return false when the batch does not exist
     */
    @Transactional
    public boolean deleteBatch(UUID batchId) {
        if (!batchRepository.existsById(batchId)) {
            return false;
        }

        List<String> artifactKeys = artifactRepository.findByBatchId(batchId).stream()
                .map(artifact -> artifact.expectedS3Key())
                .toList();
        artifactRepository.deleteByBatchId(batchId);
        segmentService.deleteByBatchId(batchId);
        batchRepository.deleteById(batchId);

        if (!artifactKeys.isEmpty()) {
            S3FileStorageService.DeleteObjectsResult deleted = storage.deleteObjects(artifactKeys);
            if (!deleted.errors().isEmpty()) {
                log.warn("Batch {} deleted but {} unified artifact object(s) remain orphaned: {}",
                        batchId, deleted.errors().size(), deleted.errors());
            }
        }
        return true;
    }
}
