package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.batch.application.BatchRetentionTransaction.BatchContents;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.delta.application.DeltaMetrics;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService.DeleteObjectsResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Batch retention: the nightly {@code BatchRetentionScheduler} pass and the admin cleanup endpoint.
 * <p>
 * Deliberately not transactional (issue #344). The candidates are listed in a short read, each
 * batch's rows are deleted in a transaction of its own ({@link BatchRetentionTransaction}), and a
 * site's objects go in one batched delete after all of that site's batch transactions have
 * committed — rows first, objects after, so a failure in between leaves an unreferenced object
 * rather than a row naming a missing one. {@link #runCleanup} refuses to run inside a caller's
 * transaction: the batch transactions would join it instead of committing on their own, and it would
 * hold its connection and locks across the object deletes.
 * </p>
 */
@Service
public class BatchRetentionService {

    private static final Logger logger = LoggerFactory.getLogger(BatchRetentionService.class);
    private static final int DEFAULT_LIMIT = 1000;

    private final BatchRepository batchRepository;
    private final SiteRepository siteRepository;
    private final BatchRetentionTransaction retentionTransaction;
    private final S3FileStorageService s3FileStorageService;
    private final DeltaMetrics metrics;

    BatchRetentionService(
            BatchRepository batchRepository,
            SiteRepository siteRepository,
            BatchRetentionTransaction retentionTransaction,
            S3FileStorageService s3FileStorageService,
            DeltaMetrics metrics) {
        this.batchRepository = batchRepository;
        this.siteRepository = siteRepository;
        this.retentionTransaction = retentionTransaction;
        this.s3FileStorageService = s3FileStorageService;
        this.metrics = metrics;
    }

    public BatchCleanupSummary runCleanup(BatchCleanupRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        refuseInsideTransaction();
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
                    : LocalDateTime.now(ZoneOffset.UTC).minusDays(retentionDays);

            BatchCleanupSummary siteSummary = cleanupSite(site.getId(), cutoff, remaining, dryRun);
            summary.merge(siteSummary);
            remaining -= siteSummary.candidates;
        }

        return summary;
    }

    private BatchCleanupSummary cleanupSite(UUID siteId, LocalDateTime cutoff, int limit, boolean dryRun) {
        // One candidate query for a real pass and a dry run, so the dry run reports what the real
        // pass would take (#344, owner decision 1: a v1 upload batch of a site with no checkpoint is
        // not a candidate). A real pass re-checks each batch under its lock before deleting it.
        List<Batch> candidates = batchRepository.findCleanupCandidatesForSite(siteId, cutoff, limit);
        BatchCleanupSummary summary = new BatchCleanupSummary();
        summary.candidates = candidates.size();

        List<String> keys = new ArrayList<>();
        List<String> batchParquetPrefixes = new ArrayList<>();

        for (Batch batch : candidates) {
            UUID batchId = batch.getId();
            try {
                if (dryRun) {
                    BatchContents contents = retentionTransaction.describeBatch(siteId, batchId);
                    summary.deletedBytes += contents.bytes();
                    keys.addAll(contents.s3Keys());
                    batchParquetPrefixes.add(contents.batchParquetPrefix());
                    continue;
                }
                Optional<BatchContents> deleted = retentionTransaction.deleteBatch(siteId, batchId, cutoff);
                if (deleted.isEmpty()) {
                    logger.debug("Batch {} of site {} is no longer a retention candidate or is being "
                            + "deleted by another pass; skipped", batchId, siteId);
                    continue;
                }
                BatchContents contents = deleted.get();
                summary.deletedBatches++;
                summary.deletedBytes += contents.bytes();
                keys.addAll(contents.s3Keys());
                batchParquetPrefixes.add(contents.batchParquetPrefix());
                if (contents.destroyedPendingWork()) {
                    reportDestroyedPendingWork(siteId, batchId, contents);
                }
            } catch (RuntimeException e) {
                logger.error("Failed to cleanup batch in DB: batchId={}, error={}", batchId, e.getMessage(), e);
                summary.errors.add("Batch " + batchId + " cleanup failed: " + e.getMessage());
            }
        }

        for (String prefix : batchParquetPrefixes) {
            try {
                keys.addAll(s3FileStorageService.listAllKeys(prefix));
            } catch (RuntimeException e) {
                logger.warn("Could not enumerate batch Parquet objects under {}; exact-key cleanup continues",
                        prefix, e);
                summary.errors.add("S3 list failed for " + prefix + ": " + e.getMessage());
            }
        }
        List<String> dedupedKeys = deduplicate(keys);
        summary.deletedFiles += dedupedKeys.size();

        if (!dryRun) {
            // Every batch transaction above has committed. Tradeoff: if S3 deletion fails, it can
            // leave orphaned objects (no DB references), but avoids the more harmful "DB references
            // missing objects" state.
            DeleteObjectsResult deleteResult = s3FileStorageService.deleteObjects(dedupedKeys);
            if (!deleteResult.errors().isEmpty()) {
                summary.errors.add("S3 delete errors: " + deleteResult.errors());
            }
        }

        return summary;
    }

    /**
     * #212: batch retention is the deliberate outer horizon of the queues' retry — the one scheduled
     * deleter allowed to take pending work. Reported only once the batch's transaction has committed,
     * so a batch whose deletion keeps failing is no phantom loss on the series.
     */
    private void reportDestroyedPendingWork(UUID siteId, UUID batchId, BatchContents contents) {
        metrics.retentionPendingSegmentsDeleted(
                DeltaMetrics.RETENTION_PENDING_PLUGIN_SQL, contents.pendingPluginSql());
        metrics.retentionPendingSegmentsDeleted(
                DeltaMetrics.RETENTION_PENDING_EGRESS, contents.pendingEgress());
        logger.warn("Batch retention deleted batch {} of site {} with pending queue "
                        + "work — {} segment(s) awaiting plugin SQL, {} awaiting egress: "
                        + "their SQL/delta Parquet is now permanently unproducible "
                        + "(issue #212, the deliberate outer horizon of the queues' retry)",
                batchId, siteId, contents.pendingPluginSql(), contents.pendingEgress());
    }

    private static void refuseInsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Refusing to run batch retention inside an active "
                    + "transaction: each batch is deleted in a transaction of its own and its objects "
                    + "after that commit, which a caller's transaction would swallow and hold across "
                    + "the object deletes (issue #344)");
        }
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
