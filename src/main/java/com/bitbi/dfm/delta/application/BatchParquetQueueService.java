package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.account.domain.AdminActionLog;
import com.bitbi.dfm.account.domain.AdminActionType;
import com.bitbi.dfm.account.infrastructure.AdminActionLogRepository;
import com.bitbi.dfm.delta.domain.BatchParquetArtifact;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactStatus;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.site.domain.Site;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Admin operations for observing and recovering the completed-batch Parquet queue. */
@Service
public class BatchParquetQueueService {

    private final BatchParquetArtifactRepository artifactRepository;
    private final ChangelogSegmentRepository segmentRepository;
    private final AdminActionLogRepository auditRepository;
    private final int leaseSeconds;

    public BatchParquetQueueService(BatchParquetArtifactRepository artifactRepository,
                                    ChangelogSegmentRepository segmentRepository,
                                    AdminActionLogRepository auditRepository,
                                    @Value("${delta.batch-parquet.lease-seconds:1800}")
                                    int leaseSeconds) {
        this.artifactRepository = artifactRepository;
        this.segmentRepository = segmentRepository;
        this.auditRepository = auditRepository;
        this.leaseSeconds = leaseSeconds;
    }

    /** Return a stable, route-constrained view of one batch's queue rows. */
    @Transactional(readOnly = true)
    public List<BatchParquetArtifact> list(UUID siteId, UUID batchId) {
        return artifactRepository.findBySiteIdAndBatchIdOrderByTableName(siteId, batchId);
    }

    /**
     * Requeue an abandoned artifact or a build whose claim lease has expired, and audit the reset
     * in the same transaction.
     */
    @Transactional
    public BatchParquetArtifact requeue(Site site, UUID batchId, UUID artifactId,
                                        String ipAddress, String userAgent) {
        BatchParquetArtifact artifact = artifactRepository
                .findForUpdate(artifactId, site.getId(), batchId)
                .orElseThrow(() -> new ArtifactNotFoundException(artifactId));
        BatchParquetArtifactStatus previousStatus = artifact.getStatus();
        if (!isRequeueable(artifact, previousStatus)) {
            throw new ArtifactNotRequeueableException(artifactId, previousStatus);
        }
        // Issue #244: the replay reads the batch's raw segments, and retention holds them back only
        // while an artifact row is UNFINISHED — an ABANDONED row requeued long afterwards is
        // precisely the window where they may be gone. Refuse here rather than queue an attempt
        // whose only possible ending is the same ABANDONED with a different error.
        if (!segmentRepository.existsCommittedByBatchId(batchId)) {
            throw new ArtifactUnproducibleException(artifactId, batchId);
        }

        artifact.requeue();
        BatchParquetArtifact saved = artifactRepository.save(artifact);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("artifactId", artifactId.toString());
        details.put("batchId", batchId.toString());
        details.put("tableName", artifact.getTableName());
        details.put("previousStatus", previousStatus.name());
        auditRepository.save(AdminActionLog
                .successForSite(AdminActionType.BATCH_PARQUET_REQUEUE, site.getAccountId(),
                        site.getId(), null, ipAddress, userAgent)
                .withDetails(details));
        return saved;
    }

    private boolean isRequeueable(BatchParquetArtifact artifact,
                                  BatchParquetArtifactStatus status) {
        if (status == BatchParquetArtifactStatus.ABANDONED) {
            return true;
        }
        if (status != BatchParquetArtifactStatus.BUILDING) {
            return false;
        }
        LocalDateTime leaseDeadline = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(leaseSeconds);
        return artifact.getUpdatedAt().isBefore(leaseDeadline);
    }

    /** The route tuple does not identify an artifact. */
    public static class ArtifactNotFoundException extends RuntimeException {
        public ArtifactNotFoundException(UUID artifactId) {
            super("Batch Parquet artifact not found: " + artifactId);
        }
    }

    /** The artifact is already healthy, retryable, published, or still has a live build lease. */
    public static class ArtifactNotRequeueableException extends RuntimeException {
        public ArtifactNotRequeueableException(UUID artifactId, BatchParquetArtifactStatus status) {
            this("Batch Parquet artifact " + artifactId + " cannot be requeued in status " + status);
        }

        protected ArtifactNotRequeueableException(String message) {
            super(message);
        }
    }

    /**
     * The artifact is recoverable in principle but its batch's raw changelog segments are gone,
     * so no attempt can produce it (issue #244). A subclass, so the route keeps answering 409 —
     * the state is not recoverable, which is what that status already means here.
     */
    public static class ArtifactUnproducibleException extends ArtifactNotRequeueableException {
        public ArtifactUnproducibleException(UUID artifactId, UUID batchId) {
            super("Batch Parquet artifact " + artifactId + " cannot be requeued: batch " + batchId
                    + " has no published changelog segments left (retention, a history wipe or a "
                    + "re-baseline took them), so no attempt can produce it — the records remain "
                    + "available through the site's checkpoint");
        }
    }
}
