package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.account.domain.AdminActionLog;
import com.bitbi.dfm.account.domain.AdminActionType;
import com.bitbi.dfm.account.infrastructure.AdminActionLogRepository;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.error.domain.ErrorLogRepository;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaselineRepository;
import com.bitbi.dfm.plugin.domain.PluginSqlGenerationRepository;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService.DeleteObjectsResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Destroys all server-side history of one site, leaving it as if it had just been created
 * (035 — issue #89).
 *
 * <p>The middle ground between the two operations that existed before. A re-baseline replaces the
 * changelog baseline but keeps the schema, the client's local counters, the upload history and the
 * plugin state; deleting the site destroys the site itself, credentials included. This removes
 * batches, uploaded files, changelog segments, checkpoints, the site schema, plugin SQL and error
 * logs, and keeps the site.</p>
 *
 * <p><b>Rows in the transaction, objects strictly after it</b> — the {@link DeltaRebaselineService}
 * convention. A rollback must leave every object in place, and the reverse order (objects first)
 * would produce the one genuinely harmful state: rows pointing at files that no longer exist.
 * Orphans left by a crash between commit and delete are the accepted cost, as they are for
 * retention and for checkpoint snapshots.</p>
 *
 * <p>The transaction is opened explicitly through a {@link TransactionTemplate} rather than
 * declared with {@code @Transactional}. The wipe has to report how many objects the bucket refused,
 * which is only known after the commit, so the S3 phase cannot live inside the transactional
 * method — and a {@code @Transactional} method called from a sibling method of the same bean is
 * silently un-proxied, i.e. not transactional at all.</p>
 *
 * <p>Like {@code BatchRetentionService}, this is a cross-aggregate cleanup service and reaches into
 * the batch, upload, error and plugin repositories directly. That is deliberate for a destructive
 * one-shot operation whose whole point is a single ordered transaction; the ingestion path's
 * one-way package dependency (plugin → delta, never the reverse) is preserved everywhere it
 * matters — see the event-driven auto-reinit hook on {@code CheckpointService}.</p>
 *
 * <p>Deliberately allowed for legacy DBF sites as well as V2 ones: the delta steps are no-ops, but
 * the sync-state row is still created and bumped, so the epoch contract holds if the site is ever
 * migrated to V2.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class DeltaSiteWipeService {

    private static final Logger log = LoggerFactory.getLogger(DeltaSiteWipeService.class);

    private final TransactionTemplate transactionTemplate;
    private final SiteSyncStateRepository syncStateRepository;
    private final BatchRepository batchRepository;
    private final UploadedFileRepository uploadedFileRepository;
    private final PluginSqlGenerationRepository sqlGenerationRepository;
    private final PluginDeltaBaselineRepository baselineRepository;
    private final AccountPluginRepository accountPluginRepository;
    private final ChangelogSegmentRepository segmentRepository;
    private final CheckpointRepository checkpointRepository;
    private final BatchParquetArtifactRepository artifactRepository;
    private final ErrorLogRepository errorLogRepository;
    private final SiteSchemaService siteSchemaService;
    private final S3FileStorageService s3FileStorageService;
    private final S3CheckpointStorage checkpointStorage;
    private final AdminActionLogRepository adminActionLogRepository;
    private final int batchTimeoutMinutes;

    public DeltaSiteWipeService(TransactionTemplate transactionTemplate,
                                SiteSyncStateRepository syncStateRepository,
                                BatchRepository batchRepository,
                                UploadedFileRepository uploadedFileRepository,
                                PluginSqlGenerationRepository sqlGenerationRepository,
                                PluginDeltaBaselineRepository baselineRepository,
                                AccountPluginRepository accountPluginRepository,
                                ChangelogSegmentRepository segmentRepository,
                                CheckpointRepository checkpointRepository,
                                BatchParquetArtifactRepository artifactRepository,
                                ErrorLogRepository errorLogRepository,
                                SiteSchemaService siteSchemaService,
                                S3FileStorageService s3FileStorageService,
                                S3CheckpointStorage checkpointStorage,
                                AdminActionLogRepository adminActionLogRepository,
                                @Value("${batch.timeout.minutes:60}") int batchTimeoutMinutes) {
        this.transactionTemplate = transactionTemplate;
        this.syncStateRepository = syncStateRepository;
        this.batchRepository = batchRepository;
        this.uploadedFileRepository = uploadedFileRepository;
        this.sqlGenerationRepository = sqlGenerationRepository;
        this.baselineRepository = baselineRepository;
        this.accountPluginRepository = accountPluginRepository;
        this.segmentRepository = segmentRepository;
        this.checkpointRepository = checkpointRepository;
        this.artifactRepository = artifactRepository;
        this.errorLogRepository = errorLogRepository;
        this.siteSchemaService = siteSchemaService;
        this.s3FileStorageService = s3FileStorageService;
        this.checkpointStorage = checkpointStorage;
        this.adminActionLogRepository = adminActionLogRepository;
        this.batchTimeoutMinutes = batchTimeoutMinutes;
    }

    /**
     * Wipe every trace of a site's history and give it a fresh epoch.
     *
     * @param site      the site to wipe (already resolved and authorized by the caller)
     * @param initiator who asked for it — recorded in the audit trail
     * @return what was destroyed
     * @throws SessionInProgressException when an ingestion session is live for the site
     * @throws ConcurrentSessionException when a batch committed while the wipe was running
     */
    public SiteHistoryWipeSummary wipe(Site site, Initiator initiator) {
        return wipe(site, initiator, null, null);
    }

    /**
     * Wipe every trace of a site's history and give it a fresh epoch, recording the caller's request
     * context in the audit trail.
     *
     * <p>The context is not decoration. {@code admin_account_id} is always NULL for this action —
     * admins are Auth0 users with no {@code accounts} row — so the IP and user agent are the only
     * things in {@code admin_action_logs} that can attribute the single most destructive operation
     * in the product to a machine. Every other site-level audited action records them
     * ({@code SiteAdminController}, {@code BatchCleanupAdminController}) and this one must not be
     * the exception.</p>
     *
     * @param site      the site to wipe (already resolved and authorized by the caller)
     * @param initiator who asked for it — recorded in the audit trail
     * @param ipAddress caller's IP address, or {@code null} when there is no request context
     * @param userAgent caller's user agent, or {@code null} when there is no request context
     * @return what was destroyed
     * @throws SessionInProgressException when an ingestion session is live for the site
     * @throws ConcurrentSessionException when a batch committed while the wipe was running
     */
    public SiteHistoryWipeSummary wipe(Site site, Initiator initiator, String ipAddress, String userAgent) {
        WipedRows wiped = transactionTemplate.execute(
                status -> wipeRows(site, initiator, ipAddress, userAgent));

        PrefixWalk egress = unreferencedObjects(site.getId(), S3CheckpointStorage.egressPrefix(site.getId()));
        PrefixWalk checkpoints =
                unreferencedObjects(site.getId(), S3CheckpointStorage.checkpointPrefix(site.getId()));

        List<String> objects = new ArrayList<>(wiped.s3Keys());
        objects.addAll(egress.keys());
        objects.addAll(checkpoints.keys());

        DeleteObjectsResult deleted = s3FileStorageService.deleteObjects(objects.stream().distinct().toList());
        if (!deleted.errors().isEmpty()) {
            log.warn("Site history wipe left {} orphaned S3 object(s) for site {}: {}",
                    deleted.errors().size(), site.getId(), deleted.errors());
        }
        // A prefix that could not be listed is counted alongside the objects the bucket refused: both
        // mean the slate is not clean, and the caller has nothing else to learn it from.
        int notSwept = deleted.errors().size()
                + (egress.enumerated() ? 0 : 1) + (checkpoints.enumerated() ? 0 : 1);

        SiteHistoryWipeSummary summary = new SiteHistoryWipeSummary(
                wiped.generation(), wiped.deletedBatches(), wiped.deletedSegments(),
                wiped.deletedCheckpoints(), wiped.deletedFiles(), wiped.deletedSqlGenerations(),
                wiped.deletedErrorLogs(), wiped.deletedBytes(), notSwept,
                wiped.baselineBatchDetached());
        log.info("Site history wiped by {}: siteId={}, generation={}, batches={}, segments={}, "
                        + "checkpoints={}, files={}, sqlGenerations={}, errorLogs={}, bytes={}, "
                        + "s3Errors={}, baselineDetached={}",
                initiator, site.getId(), summary.generation(), summary.deletedBatches(),
                summary.deletedSegments(), summary.deletedCheckpoints(), summary.deletedFiles(),
                summary.deletedSqlGenerations(), summary.deletedErrorLogs(), summary.deletedBytes(),
                summary.s3DeleteErrors(), summary.baselineBatchDetached());
        return summary;
    }

    /**
     * Everything under one of the site's whole-site prefixes, named or not by a surviving row.
     *
     * <p><b>Egress</b> ({@code egress/{siteId}/{table}/delta/seq={first}-{last}.parquet}) derives
     * its key from sequence numbers alone — and a wipe is the one operation that sends those numbers
     * back to zero. Left in place, a pre-wipe file whose {@code (firstSeq, lastSeq)} pair happens to
     * recur in the new epoch is listed by {@code ParquetExportCatalogDao} as the new epoch's delta
     * unless egress overwrites it, which it does not for a table missing from the new segment or
     * skipped by the per-table coercion guard. (The batch download endpoint is no longer exposed to
     * this: since 036 it resolves an exact {@code batch_parquet_artifacts} row instead of deriving a
     * seq-based key.)</p>
     *
     * <p><b>Checkpoints</b> ({@code checkpoints/{siteId}/…}) have the same problem for a different
     * reason (issue #118). The {@code checkpoints} row is one per {@code (site, table)} and reused
     * across builds: each build writes {@code …/{table}/seq={seq}/snapshot.parquet} under a new
     * {@code seq} and replaces the key on the row, so the previous build's object is unreferenced
     * the moment it is superseded — and {@link Checkpoint#detachParquet()} drops the last reference
     * outright when a build advances the row without materializing a file. Nothing else sweeps them:
     * changelog retention prunes segments, and no lifecycle rule covers this prefix. The
     * {@code _frame/seq={seq}/frame.pb.gz} reload frames are named by no row at all, and a survivor
     * is worse than untidy — the new epoch reaching the same {@code seq} would fold a pre-wipe frame
     * back in as its own state.</p>
     *
     * <p>Enumerated after the commit: a paginated S3 walk has no business inside the transaction,
     * and the objects are only reachable through keys the rows never held. The keys recorded on the
     * rows are collected too and stay the fallback, so one prefix failing to list — swallowed, since
     * the rows are already gone and failing here would report a completed wipe as a 500 — costs
     * neither the other prefix nor the exact keys. It is not silent, though: the caller counts the
     * failure into {@code s3DeleteErrors}, because for the checkpoint prefix the difference between
     * "a few orphaned objects" and "every reload frame survived" is not visible anywhere else.</p>
     *
     * @param siteId the site being wiped, for the log line
     * @param prefix the whole-site prefix to enumerate
     * @return the keys under the prefix, and whether the listing succeeded at all
     */
    private PrefixWalk unreferencedObjects(UUID siteId, String prefix) {
        try {
            return new PrefixWalk(checkpointStorage.listAllKeys(prefix), true);
        } catch (RuntimeException e) {
            log.warn("Could not enumerate {} for site {}; the objects under it are left as orphans, "
                    + "a later build reusing their sequence numbers may resolve to one, and the wipe "
                    + "is worth repeating once listing works again", prefix, siteId, e);
            return new PrefixWalk(List.of(), false);
        }
    }

    /**
     * One post-commit prefix walk: what it found, and whether it got to look at all.
     *
     * @param keys       the keys found under the prefix; empty both when the prefix is empty and when
     *                   the listing failed
     * @param enumerated whether the listing succeeded — a false here means unknown, not empty
     */
    private record PrefixWalk(List<String> keys, boolean enumerated) {
    }

    /**
     * The transactional phase: bulk deletes in FK order, with every S3 key collected before the row
     * naming it disappears.
     */
    private WipedRows wipeRows(Site site, Initiator initiator, String ipAddress, String userAgent) {
        UUID siteId = site.getId();

        // 1. Per-site mutex. Every ingestion commit touches this row, so locking it serializes the
        // wipe against them. A site that never synced has no row: it is created here, which two
        // simultaneous wipes of the same fresh site could race on — one loses on the primary key and
        // the operator retries, which is the same outcome as the lock giving them a turn each.
        SiteSyncState state = syncStateRepository.findBySiteIdForUpdate(siteId)
                .orElseGet(() -> SiteSyncState.initial(siteId));

        // 2. A live session is fatal, whatever its mode. The gRPC stream keeps its session state in
        // the heap of whichever pod owns it, so there is nothing here that could cancel it; deleting
        // its batch would only make it fail confusingly at commit. Stop the client (or let the
        // timeout sweeper reap the batch) and retry. A batch that has been silent past the liveness
        // window is not a session — it is debris, and goes with the rest.
        batchRepository.findActiveBySiteId(siteId)
                .filter(batch -> !batch.isExpired(batchTimeoutMinutes))
                .ifPresent(batch -> {
                    throw new SessionInProgressException(siteId, batch);
                });

        // 3. Collect first: after the deletes nothing remembers these keys.
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

        // 4. Plugin SQL generations, both sides of the batch reference.
        int deletedSqlGenerations = sqlGenerationRepository.deleteBySiteId(siteId);

        // 5. Plugin delta baselines. The site row survives the wipe, so no cascade fires.
        baselineRepository.deleteBySiteId(siteId);

        // 6. Changelog segments, provisional ones included — a half-uploaded snapshot is history
        // too, and its rows would block the batch delete either way.
        s3Keys.addAll(segmentRepository.findAllS3KeysBySiteId(siteId));
        int deletedSegments = segmentRepository.deleteBySiteId(siteId);

        // 7. Checkpoints.
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
        // manifest gives us exact keys even if that listing later fails.
        s3Keys.addAll(artifactRepository.findS3KeysBySiteId(siteId));
        artifactRepository.deleteBySiteId(siteId);

        // 8. Error logs.
        int deletedErrorLogs = errorLogRepository.deleteBySiteId(siteId);

        // 9. Detach plugin baselines pointing at the site's batches — the FK is ON DELETE RESTRICT,
        // so without this the batch delete fails outright.
        boolean baselineBatchDetached = accountPluginRepository.detachBaselineBatchesOfSite(siteId) > 0;

        // 10. Batches. Uploaded files and file comparisons follow through the database cascade.
        int deletedBatches = batchRepository.deleteBySiteId(siteId);

        // 11. The schema: the client re-submits it like a brand-new site.
        siteSchemaService.deleteSchema(siteId);

        // 12. Reset — never delete — the sync state, so the generation stays monotonic.
        state.resetForWipe();
        syncStateRepository.save(state);

        // 13. Audit inside the transaction, so a rollback takes the record with it.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("initiator", initiator.name());
        details.put("generation", state.getGeneration());
        details.put("deletedBatches", deletedBatches);
        details.put("deletedSegments", deletedSegments);
        details.put("deletedCheckpoints", deletedCheckpoints);
        details.put("deletedFiles", deletedFiles);
        details.put("deletedSqlGenerations", deletedSqlGenerations);
        details.put("deletedErrorLogs", deletedErrorLogs);
        details.put("deletedBytes", deletedBytes);
        details.put("baselineBatchDetached", baselineBatchDetached);
        adminActionLogRepository.save(AdminActionLog
                .successForSite(AdminActionType.SITE_HISTORY_WIPE, site.getAccountId(), siteId,
                        null, ipAddress, userAgent)
                .withDetails(details));

        // 14. Last look. A client that reconnected while this ran could have committed a batch after
        // step 10, leaving the operator with a site they believe is empty. Rolling back is cheap and
        // retryable; locking the ingestion path for the duration of a wipe is not.
        long remaining = batchRepository.countBySiteId(siteId);
        if (remaining > 0) {
            throw new ConcurrentSessionException(siteId, remaining);
        }

        return new WipedRows(state.getGeneration(), deletedBatches, deletedSegments, deletedCheckpoints,
                deletedFiles, deletedSqlGenerations, deletedErrorLogs, deletedBytes,
                baselineBatchDetached, s3Keys.stream().distinct().toList());
    }

    /**
     * Who asked for the wipe. Recorded in the audit details; both surfaces are otherwise identical.
     */
    public enum Initiator {
        /** ROLE_ADMIN, through {@code /api/v1/sites/{siteId}/delta/wipe}. */
        ADMIN,
        /** The account owner, through {@code /api/v1/account/sites/{siteId}/delta/wipe}. */
        OWNER
    }

    /**
     * An ingestion session is running for the site, so its history cannot be destroyed yet.
     */
    public static class SessionInProgressException extends RuntimeException {
        public SessionInProgressException(UUID siteId, Batch batch) {
            super("Site " + siteId + " has a live ingestion session (batch " + batch.getId()
                    + "); stop the client and retry");
        }
    }

    /**
     * A batch appeared while the wipe was running: the whole wipe is rolled back and the caller
     * should retry.
     */
    public static class ConcurrentSessionException extends RuntimeException {
        public ConcurrentSessionException(UUID siteId, long remaining) {
            super("Site " + siteId + " gained " + remaining + " batch(es) while it was being wiped; "
                    + "the wipe was rolled back — retry");
        }
    }

    /**
     * The transactional phase's output: counts, plus the S3 keys the post-commit phase must delete.
     */
    private record WipedRows(long generation, int deletedBatches, int deletedSegments,
                             int deletedCheckpoints, int deletedFiles, int deletedSqlGenerations,
                             int deletedErrorLogs, long deletedBytes, boolean baselineBatchDetached,
                             List<String> s3Keys) {
    }
}
