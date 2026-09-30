package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.infrastructure.S3ChangelogSegmentStorage;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.error.domain.ErrorLogRepository;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaselineRepository;
import com.bitbi.dfm.plugin.domain.PluginSqlGenerationRepository;
import com.bitbi.dfm.shared.storage.S3ListedObject;
import com.bitbi.dfm.shared.storage.S3PrefixLister.S3PrefixWalk;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService.DeleteObjectsResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Removes every history row of one site, in the order the foreign keys dictate, and the objects
 * those rows named once the caller's transaction has committed (issue #367).
 *
 * <p>Two operations need exactly this and nothing more: the site history wipe (035 — #89), which
 * then resets the site's epoch and keeps the site, and the site hard delete
 * ({@code SiteService.deleteSite}), which then removes the site itself. The delete used to carry a
 * pre-Delta list of its own — uploaded files, error logs, batches one by one — and so could not
 * delete a site with a single committed segment: {@code changelog_segments.batch_id} (V30) has no
 * {@code ON DELETE} action and {@code account_plugins.baseline_batch_id} (V25) is
 * {@code ON DELETE RESTRICT}. One list, shared, is what keeps the two from drifting apart again.</p>
 *
 * <p>{@link #purgeRows} joins the caller's transaction and refuses to run without one
 * ({@link Propagation#MANDATORY}): a purge is only ever half of an operation, and committing the
 * rows before the caller has done its own half would leave the site in a state neither operation
 * describes. It collects every S3 key before the row naming it disappears — after the deletes
 * nothing remembers them — and deletes no object itself, because a rollback must leave every
 * object in place: rows pointing at files that no longer exist is the one genuinely harmful
 * ordering.</p>
 */
@Component
public class SiteHistoryPurge {

    private static final Logger log = LoggerFactory.getLogger(SiteHistoryPurge.class);

    private final BatchRepository batchRepository;
    private final UploadedFileRepository uploadedFileRepository;
    private final PluginSqlGenerationRepository sqlGenerationRepository;
    private final PluginDeltaBaselineRepository baselineRepository;
    private final AccountPluginRepository accountPluginRepository;
    private final ChangelogSegmentRepository segmentRepository;
    private final CheckpointRepository checkpointRepository;
    private final BatchParquetArtifactRepository artifactRepository;
    private final ErrorLogRepository errorLogRepository;
    private final S3FileStorageService s3FileStorageService;
    private final S3CheckpointStorage checkpointStorage;
    private final S3ChangelogSegmentStorage segmentStorage;

    public SiteHistoryPurge(BatchRepository batchRepository,
                            UploadedFileRepository uploadedFileRepository,
                            PluginSqlGenerationRepository sqlGenerationRepository,
                            PluginDeltaBaselineRepository baselineRepository,
                            AccountPluginRepository accountPluginRepository,
                            ChangelogSegmentRepository segmentRepository,
                            CheckpointRepository checkpointRepository,
                            BatchParquetArtifactRepository artifactRepository,
                            ErrorLogRepository errorLogRepository,
                            S3FileStorageService s3FileStorageService,
                            S3CheckpointStorage checkpointStorage,
                            S3ChangelogSegmentStorage segmentStorage) {
        this.batchRepository = batchRepository;
        this.uploadedFileRepository = uploadedFileRepository;
        this.sqlGenerationRepository = sqlGenerationRepository;
        this.baselineRepository = baselineRepository;
        this.accountPluginRepository = accountPluginRepository;
        this.segmentRepository = segmentRepository;
        this.checkpointRepository = checkpointRepository;
        this.artifactRepository = artifactRepository;
        this.errorLogRepository = errorLogRepository;
        this.s3FileStorageService = s3FileStorageService;
        this.checkpointStorage = checkpointStorage;
        this.segmentStorage = segmentStorage;
    }

    /**
     * Delete the site's history rows — plugin SQL, plugin delta baselines, changelog segments,
     * checkpoints, completed-batch Parquet artifacts, error logs and batches (uploaded files and
     * file comparisons follow the batches by cascade) — and detach any plugin activation whose
     * baseline batch is one of them. The site row, its schema and its sync state are left to the
     * caller.
     *
     * @param siteId the site whose history to delete
     * @return what was deleted, and the S3 keys the rows named
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PurgedHistory purgeRows(UUID siteId) {
        // Collect first: after the deletes nothing remembers these keys.
        List<String> s3Keys = new ArrayList<>();
        long deletedBytes = 0L;
        int deletedFiles = 0;
        for (UploadedFileRepository.FileKeySize file : uploadedFileRepository.findS3KeysBySiteId(siteId)) {
            deletedFiles++;
            if (file.getS3Key() != null) {
                s3Keys.add(file.getS3Key());
            }
            if (file.getFileSize() != null) {
                deletedBytes += file.getFileSize();
            }
        }
        for (PluginSqlGenerationRepository.S3KeySize sql : sqlGenerationRepository.findS3KeysBySiteId(siteId)) {
            if (sql.getS3Key() != null) {
                s3Keys.add(sql.getS3Key());
            }
            if (sql.getFileSizeBytes() != null) {
                deletedBytes += sql.getFileSizeBytes();
            }
        }

        // Plugin SQL generations, both sides of the batch reference.
        int deletedSqlGenerations = sqlGenerationRepository.deleteBySiteId(siteId);

        // Plugin delta baselines. A wipe keeps the site row, so no cascade fires for it.
        baselineRepository.deleteBySiteId(siteId);

        // Changelog segments, provisional ones included — a half-uploaded snapshot is history too,
        // and its rows block the batch delete either way (batch_id has no ON DELETE action).
        s3Keys.addAll(segmentRepository.findAllS3KeysBySiteId(siteId));
        int deletedSegments = segmentRepository.deleteBySiteId(siteId);

        // Checkpoints.
        for (Checkpoint checkpoint : checkpointRepository.findBySiteId(siteId)) {
            if (checkpoint.getS3KeyCsv() != null) {
                s3Keys.add(checkpoint.getS3KeyCsv());
            }
            if (checkpoint.getS3KeyParquet() != null) {
                s3Keys.add(checkpoint.getS3KeyParquet());
            }
        }
        int deletedCheckpoints = checkpointRepository.deleteBySiteId(siteId);

        // Unified artifact objects are also covered by the post-commit egress-prefix walk, but the
        // manifest gives exact keys even if that listing later fails.
        s3Keys.addAll(artifactRepository.findS3KeysBySiteId(siteId));
        artifactRepository.deleteBySiteId(siteId);

        // Error logs.
        int deletedErrorLogs = errorLogRepository.deleteBySiteId(siteId);

        // Detach plugin baselines pointing at the site's batches — the FK is ON DELETE RESTRICT,
        // so without this the batch delete fails outright.
        boolean baselineBatchDetached = accountPluginRepository.detachBaselineBatchesOfSite(siteId) > 0;

        // Batches. Uploaded files and file comparisons follow through the database cascade.
        int deletedBatches = batchRepository.deleteBySiteId(siteId);

        return new PurgedHistory(deletedBatches, deletedSegments, deletedCheckpoints, deletedFiles,
                deletedSqlGenerations, deletedErrorLogs, deletedBytes, baselineBatchDetached,
                s3Keys.stream().distinct().toList());
    }

    /**
     * Delete the objects of a site whose row is gone: the exact keys its rows named, then every
     * object under its three prefixes — {@code delta/{siteId}/segments/},
     * {@code checkpoints/{siteId}/} and {@code egress/{siteId}/} — which also carry objects no row
     * ever named (checkpoint frames, delta Parquet keyed by seq range, a segment whose commit
     * failed after its upload).
     *
     * <p>Called after the delete has committed, so every outcome here is "orphans left behind, no
     * data lost" and none may surface as a failure of a delete that happened: nothing is thrown.
     * No cut-off by time, unlike the wipe's walk: with the site row gone no row can name an object
     * under these prefixes, so nothing there is live. An object written after the walk by a build
     * that was already running stays behind, and so does anything a failed or truncated listing did
     * not reach — {@code DeltaS3OrphanSweeper} takes such a prefix only when
     * {@code delta.s3-orphan.reclaim-unknown-sites} declares the bucket this database's alone.</p>
     *
     * @param siteId    the deleted site
     * @param exactKeys the keys {@link #purgeRows} collected, plus any other the caller holds
     */
    public void deleteObjectsOfDeletedSite(UUID siteId, List<String> exactKeys) {
        int[] leftBehind = {deleteQuietly(siteId, exactKeys)};
        Consumer<List<S3ListedObject>> deletePage = page ->
                leftBehind[0] += deleteQuietly(siteId, page.stream().map(S3ListedObject::key).toList());
        boolean incomplete = !walkQuietly(siteId, S3ChangelogSegmentStorage.segmentPrefix(siteId),
                prefix -> segmentStorage.walkPrefix(prefix, deletePage));
        incomplete |= !walkQuietly(siteId, S3CheckpointStorage.checkpointPrefix(siteId),
                prefix -> checkpointStorage.walkPrefix(prefix, deletePage));
        incomplete |= !walkQuietly(siteId, S3CheckpointStorage.egressPrefix(siteId),
                prefix -> checkpointStorage.walkPrefix(prefix, deletePage));
        if (leftBehind[0] > 0 || incomplete) {
            log.warn("Deleted site {}: at least {} S3 object(s) left behind{} — its rows are gone, so they "
                            + "are reclaimed only by the orphan sweep with reclaim-unknown-sites or by hand",
                    siteId, leftBehind[0], incomplete ? ", and a prefix could not be walked to its end" : "");
        } else {
            log.info("Deleted site {}: its S3 objects are gone", siteId);
        }
    }

    /**
     * @return whether the walk reached the end of the prefix
     */
    private static boolean walkQuietly(UUID siteId, String prefix, Function<String, S3PrefixWalk> walk) {
        try {
            return !walk.apply(prefix).truncated();
        } catch (RuntimeException e) {
            log.warn("Deleted site {}: the walk of {} failed; what it did not reach is left behind",
                    siteId, prefix, e);
            return false;
        }
    }

    private int deleteQuietly(UUID siteId, List<String> keys) {
        if (keys.isEmpty()) {
            return 0;
        }
        try {
            DeleteObjectsResult deleted = s3FileStorageService.deleteObjects(keys);
            return deleted.errors().size();
        } catch (RuntimeException e) {
            log.warn("Deleted site {}: {} object(s) could not be deleted", siteId, keys.size(), e);
            return keys.size();
        }
    }

    /**
     * What {@link #purgeRows} deleted, and the S3 keys the rows named.
     */
    public record PurgedHistory(int deletedBatches, int deletedSegments, int deletedCheckpoints,
                                int deletedFiles, int deletedSqlGenerations, int deletedErrorLogs,
                                long deletedBytes, boolean baselineBatchDetached, List<String> s3Keys) {
    }
}
