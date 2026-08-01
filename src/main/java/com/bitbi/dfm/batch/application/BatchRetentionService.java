package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.domain.BatchParquetArtifact;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.plugin.domain.PluginSqlGenerationRepository;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService.DeleteObjectsResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class BatchRetentionService {

    private static final Logger logger = LoggerFactory.getLogger(BatchRetentionService.class);
    private static final int DEFAULT_LIMIT = 1000;

    private final BatchRepository batchRepository;
    private final com.bitbi.dfm.site.domain.SiteRepository siteRepository;
    private final UploadedFileRepository uploadedFileRepository;
    private final PluginSqlGenerationRepository sqlGenerationRepository;
    private final S3FileStorageService s3FileStorageService;
    private final ChangelogSegmentService changelogSegmentService;
    private final BatchParquetArtifactRepository artifactRepository;

    public BatchRetentionService(
            BatchRepository batchRepository,
            com.bitbi.dfm.site.domain.SiteRepository siteRepository,
            UploadedFileRepository uploadedFileRepository,
            PluginSqlGenerationRepository sqlGenerationRepository,
            S3FileStorageService s3FileStorageService,
            ChangelogSegmentService changelogSegmentService,
            BatchParquetArtifactRepository artifactRepository) {
        this.batchRepository = batchRepository;
        this.siteRepository = siteRepository;
        this.uploadedFileRepository = uploadedFileRepository;
        this.sqlGenerationRepository = sqlGenerationRepository;
        this.s3FileStorageService = s3FileStorageService;
        this.changelogSegmentService = changelogSegmentService;
        this.artifactRepository = artifactRepository;
    }

    public BatchCleanupSummary runCleanup(BatchCleanupRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        int limit = request.limit() != null && request.limit() > 0 ? request.limit() : DEFAULT_LIMIT;
        boolean dryRun = request.dryRun() != null && request.dryRun();

        List<Site> sites = resolveSites(request.siteId(), request.accountId());
        if (sites.isEmpty()) {
            return BatchCleanupSummary.empty();
        }

        BatchCleanupSummary summary = new BatchCleanupSummary();
        int remaining = limit;

        for (Site site : sites) {
            if (remaining <= 0) {
                break;
            }
            int retentionDays = request.retentionDays() != null ? request.retentionDays() : site.getRetentionDays();
            LocalDateTime cutoff = request.olderThan() != null
                    ? request.olderThan()
                    : LocalDateTime.now().minusDays(retentionDays);

            BatchCleanupSummary siteSummary = cleanupSite(site.getId(), cutoff, remaining, dryRun);
            summary.merge(siteSummary);
            remaining -= siteSummary.candidates;
        }

        return summary;
    }

    protected BatchCleanupSummary cleanupSite(UUID siteId, LocalDateTime cutoff, int limit, boolean dryRun) {
        CleanupDbResult dbResult = cleanupSiteInDb(siteId, cutoff, limit, dryRun);

        List<String> dedupedKeys = deduplicate(dbResult.s3Keys);
        dbResult.summary.deletedFiles += dedupedKeys.size();

        if (!dryRun) {
            // We delete from the DB first, then best-effort delete from S3.
            // Tradeoff: if S3 deletion fails, it can leave orphaned objects (no DB references),
            // but avoids the more harmful "DB references missing objects" state.
            DeleteObjectsResult deleteResult = s3FileStorageService.deleteObjects(dedupedKeys);
            if (!deleteResult.errors().isEmpty()) {
                dbResult.summary.errors.add("S3 delete errors: " + deleteResult.errors());
            }
        }

        return dbResult.summary;
    }

    @Transactional
    protected CleanupDbResult cleanupSiteInDb(UUID siteId, LocalDateTime cutoff, int limit, boolean dryRun) {
        List<Batch> candidates = batchRepository.findCleanupCandidatesForSite(siteId, cutoff, limit);
        BatchCleanupSummary summary = new BatchCleanupSummary();
        summary.candidates = candidates.size();

        List<String> s3Keys = new ArrayList<>();

        for (Batch batch : candidates) {
            UUID batchId = batch.getId();
            try {
                // Collect keys/sizes first, but only add them to the global delete list after DB deletion succeeds.
                // This avoids a "DB still has records but files are gone" state if the DB stage fails.
                List<UploadedFileRepository.FileKeySize> uploadedKeys = uploadedFileRepository.findS3KeysByBatchId(batchId);
                List<PluginSqlGenerationRepository.S3KeySize> sqlKeys = sqlGenerationRepository.findS3KeysByBatchId(batchId);
                List<BatchParquetArtifact> artifacts = artifactRepository.findByBatchId(batchId);

                List<String> batchS3Keys = new ArrayList<>();
                long batchBytes = 0L;

                for (UploadedFileRepository.FileKeySize fileKey : uploadedKeys) {
                    if (fileKey.getS3Key() != null) {
                        batchS3Keys.add(fileKey.getS3Key());
                    }
                    if (fileKey.getFileSize() != null) {
                        batchBytes += fileKey.getFileSize();
                    }
                }

                for (PluginSqlGenerationRepository.S3KeySize sqlKey : sqlKeys) {
                    if (sqlKey.getS3Key() != null) {
                        batchS3Keys.add(sqlKey.getS3Key());
                    }
                    if (sqlKey.getFileSizeBytes() != null) {
                        batchBytes += sqlKey.getFileSizeBytes();
                    }
                }

                for (BatchParquetArtifact artifact : artifacts) {
                    if (artifact.getS3Key() != null) {
                        batchS3Keys.add(artifact.getS3Key());
                    }
                    if (artifact.getFileSize() != null) {
                        batchBytes += artifact.getFileSize();
                    }
                }

                if (dryRun) {
                    // Dry run reports what would be removed, but doesn't perform any deletions.
                    summary.deletedBytes += batchBytes;
                    s3Keys.addAll(batchS3Keys);
                    continue;
                }

                // Prevent leaving plugin SQL generations referencing a deleted comparison batch.
                sqlGenerationRepository.deleteByComparisonBatchId(batchId);
                sqlGenerationRepository.deleteBySourceBatchId(batchId);

                // Unified completed-batch artifacts name their S3 objects in the manifest; collect
                // above, then remove rows before their parent batch.
                artifactRepository.deleteByBatchId(batchId);

                // Remove Delta v2 changelog segments (DB + S3) so the batch_id FK does not block delete.
                changelogSegmentService.deleteByBatchId(batchId);

                batchRepository.deleteById(batchId);
                summary.deletedBatches++;
                summary.deletedBytes += batchBytes;
                s3Keys.addAll(batchS3Keys);
            } catch (Exception e) {
                logger.error("Failed to cleanup batch in DB: batchId={}, error={}", batchId, e.getMessage(), e);
                summary.errors.add("Batch " + batchId + " cleanup failed: " + e.getMessage());
            }
        }

        return new CleanupDbResult(summary, s3Keys);
    }

    private List<Site> resolveSites(UUID siteId, UUID accountId) {
        if (siteId != null) {
            return siteRepository.findById(siteId)
                    .map(List::of)
                    .orElse(List.of());
        }
        if (accountId != null) {
            return siteRepository.findByAccountId(accountId);
        }
        return siteRepository.findAll();
    }

    private List<String> deduplicate(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        return keys.stream().distinct().toList();
    }

    protected record CleanupDbResult(BatchCleanupSummary summary, List<String> s3Keys) {}

    public record BatchCleanupRequest(
            UUID siteId,
            UUID accountId,
            Integer retentionDays,
            LocalDateTime olderThan,
            Integer limit,
            Boolean dryRun
    ) {}

    public static class BatchCleanupSummary {
        private int candidates;
        private int deletedBatches;
        private int deletedFiles;
        private long deletedBytes;
        private final List<String> errors = new ArrayList<>();

        public static BatchCleanupSummary empty() {
            return new BatchCleanupSummary();
        }

        public void merge(BatchCleanupSummary other) {
            this.candidates += other.candidates;
            this.deletedBatches += other.deletedBatches;
            this.deletedFiles += other.deletedFiles;
            this.deletedBytes += other.deletedBytes;
            this.errors.addAll(other.errors);
        }

        public int candidates() {
            return candidates;
        }

        public int deletedBatches() {
            return deletedBatches;
        }

        public int deletedFiles() {
            return deletedFiles;
        }

        public long deletedBytes() {
            return deletedBytes;
        }

        public List<String> errors() {
            return errors;
        }
    }
}
