package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.infrastructure.S3ChangelogSegmentStorage;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import com.bitbi.dfm.shared.storage.S3ChildPrefixListing;
import com.bitbi.dfm.shared.storage.S3ListedObject;
import com.bitbi.dfm.shared.storage.S3PrefixListing;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Deletes S3 objects under the two delta prefixes that no database row references (issue #158,
 * which folded #160).
 *
 * <p>Every object here is written <em>before</em>, or independently of, the row that names it, and
 * nothing else reclaims one that ends up with no row:
 * {@code ChangelogSegmentService}/{@code DeltaRebaselineService} collect keys from rows, the site
 * history wipe walks these prefixes only for the site being wiped (#118, #122),
 * {@code ChangelogRetentionService} prunes segments and never touches checkpoints,
 * {@link ParquetScratchOrphanSweeper} sweeps <em>local</em> scratch, and there is no bucket
 * lifecycle rule for either prefix.</p>
 *
 * <h2>Why not a compensating delete in the caller</h2>
 *
 * <p>Because an exception can surface <em>after</em> the transaction committed — an
 * {@code afterCommit} synchronization or an {@code AFTER_COMMIT} listener throwing, and
 * {@code BatchEventListener} and {@code BatchParquetFinalizationListener} both run there. A delete
 * on that path destroys a live, referenced object. Any compensation would have to prove the
 * transaction did not commit; a sweep proves the stronger thing directly, by asking the rows.</p>
 *
 * <h2>One mechanism, two prefixes</h2>
 *
 * <p>Per site, per prefix: list the objects, keep only the key shapes this application writes, drop
 * everything younger than {@code delta.s3-orphan.min-age-seconds}, subtract the keys the rows still
 * name, delete the remainder. The prefixes differ only in which rows answer the last question —
 * {@code changelog_segments.s3_key} for one, the {@code checkpoints} keys plus the frame implied by
 * {@code site_sync_state.last_checkpoint_seq} for the other.</p>
 *
 * <p>The sites come from the <b>bucket</b>, not from the database, and that is not an optimization:
 * {@code SiteService.deleteSite} hard-deletes the site row and never touches these prefixes, so a
 * deleted site's objects outlive every row that could have named them and no database-driven walk
 * would ever visit them.</p>
 *
 * <h2>Every guard fails towards keeping the object</h2>
 *
 * <ul>
 *   <li><b>Age.</b> An object is a candidate only when S3 reports it strictly older than the window,
 *       whose default (24 h) is far beyond the longest in-flight commit or checkpoint build. A
 *       missing {@code lastModified} counts as new. This is what protects the frame a running build
 *       has uploaded but not yet adopted, and the segment of a commit still in its transaction.</li>
 *   <li><b>Shape.</b> Only keys matching what the writers produce are candidates, so an artifact
 *       kind added later accumulates — as it does today — instead of being deleted by a sweeper
 *       that has never heard of it.</li>
 *   <li><b>Rows.</b> The rows are read <em>after</em> the listing, so a row written in between is
 *       seen and protects its object; and a row set that could not be read skips the site entirely
 *       rather than reading "no rows" as "no references".</li>
 *   <li><b>Truncation.</b> A listing that failed mid-walk returns fewer objects, never more, so the
 *       worst it costs is one interval.</li>
 * </ul>
 *
 * <p>A site with no rows at all is therefore swept whole — that is the deleted-site case, and the
 * only one where the answer "nothing references any of this" is the literal truth.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class DeltaS3OrphanSweeper {

    private static final Logger log = LoggerFactory.getLogger(DeltaS3OrphanSweeper.class);

    /**
     * Default age window (24 hours). It has to exceed the longest interval between an object's
     * {@code PutObject} and the commit of the row that names it: a large {@code FULL_SNAPSHOT} tail
     * upload plus the ingestion transaction, and — the longer of the two by far — a whole-site
     * checkpoint build between {@code uploadFrame(N)} and the {@code recordCheckpoint(N)} that
     * adopts it. A day is not a measurement; it is a margin nothing observed comes close to.
     */
    public static final String DEFAULT_MIN_AGE_SECONDS = "86400";

    /** Default interval between sweeps (24 hours): the population changes once per nightly build. */
    public static final String DEFAULT_SWEEP_MS = "86400000";

    /**
     * Default delay before the first sweep (10 minutes). Unlike the queue workers, nothing here is
     * crash recovery — an orphan waits days by definition — so the first pass stays out of the
     * startup window a restart is already busy with.
     */
    public static final String DEFAULT_INITIAL_DELAY_MS = "600000";

    /** {@code {segmentId}.pb.gz} directly under {@code delta/{siteId}/segments/}. */
    private static final Pattern SEGMENT_OBJECT = Pattern.compile("^[^/]+\\.pb\\.gz$");

    /** {@code _frame/seq={n}/frame.pb.gz} under {@code checkpoints/{siteId}/}. */
    private static final Pattern CHECKPOINT_FRAME = Pattern.compile("^_frame/seq=\\d+/frame\\.pb\\.gz$");

    /**
     * {@code {table}/seq={n}/snapshot.parquet} under {@code checkpoints/{siteId}/}. The
     * {@code .csv.gz} alternative is the format builds wrote before #113 — still deleted by the
     * site wipe through the keys on the rows, so still reclaimable here.
     */
    private static final Pattern CHECKPOINT_SNAPSHOT =
            Pattern.compile("^[^/]+/seq=\\d+/snapshot\\.(parquet|csv\\.gz)$");

    private final S3ChangelogSegmentStorage segmentStorage;
    private final S3CheckpointStorage checkpointStorage;
    private final S3FileStorageService objectDeleter;
    private final ChangelogSegmentRepository segmentRepository;
    private final CheckpointRepository checkpointRepository;
    private final SiteSyncStateRepository syncStateRepository;
    private final DeltaMetrics metrics;
    private final boolean enabled;
    private final long minAgeSeconds;

    public DeltaS3OrphanSweeper(
            S3ChangelogSegmentStorage segmentStorage,
            S3CheckpointStorage checkpointStorage,
            S3FileStorageService objectDeleter,
            ChangelogSegmentRepository segmentRepository,
            CheckpointRepository checkpointRepository,
            SiteSyncStateRepository syncStateRepository,
            DeltaMetrics metrics,
            @Value("${delta.s3-orphan.enabled:true}") boolean enabled,
            @Value("${delta.s3-orphan.min-age-seconds:" + DEFAULT_MIN_AGE_SECONDS + "}")
            long minAgeSeconds) {
        if (minAgeSeconds <= 0) {
            throw new IllegalArgumentException(
                    "delta.s3-orphan.min-age-seconds must be positive, got " + minAgeSeconds);
        }
        this.segmentStorage = segmentStorage;
        this.checkpointStorage = checkpointStorage;
        this.objectDeleter = objectDeleter;
        this.segmentRepository = segmentRepository;
        this.checkpointRepository = checkpointRepository;
        this.syncStateRepository = syncStateRepository;
        this.metrics = metrics;
        this.enabled = enabled;
        this.minAgeSeconds = minAgeSeconds;
        log.info("Delta S3 orphan sweep is {}{}", enabled ? "on" : "off",
                enabled ? ", reclaiming unreferenced objects older than " + minAgeSeconds + "s"
                        : " (delta.s3-orphan.enabled=false): unreferenced objects accumulate");
    }

    /**
     * Reclaim what neither prefix's rows reference any more.
     *
     * <p>The tick opens no transaction of its own: each repository call is its own short one and
     * every S3 round trip runs with nothing held, the rule #164 established for the queue
     * workers.</p>
     */
    @Scheduled(
            initialDelayString = "${delta.s3-orphan.initial-delay-ms:" + DEFAULT_INITIAL_DELAY_MS + "}",
            fixedDelayString = "${delta.s3-orphan.sweep-ms:" + DEFAULT_SWEEP_MS + "}")
    public void sweep() {
        sweep(Instant.now());
    }

    /**
     * One pass, with the instant the age window counts back from supplied.
     *
     * <p>Public because it is the only seam a test can use: the age window is what makes an object
     * a candidate, and no test can wait a day or backdate an S3 {@code LastModified}, so it moves
     * {@code now} instead.</p>
     *
     * @param now the instant the age window counts back from
     */
    public void sweep(Instant now) {
        if (!enabled) {
            return;
        }
        Instant cutoff = now.minusSeconds(minAgeSeconds);
        sweepScope(new SegmentScope(), cutoff);
        sweepScope(new CheckpointScope(), cutoff);
    }

    private void sweepScope(ReclaimScope scope, Instant cutoff) {
        S3ChildPrefixListing sites = scope.listSites();
        if (sites.truncated()) {
            log.warn("Listing of the sites under the {} prefix stopped after {}; the ones already "
                            + "read are swept and the rest wait for the next pass",
                    scope.label(), sites.prefixes().size());
        }
        for (String sitePrefix : sites.prefixes()) {
            UUID siteId = siteIdOf(sitePrefix);
            if (siteId == null) {
                log.warn("Skipping {}: not a site prefix this application writes", sitePrefix);
                continue;
            }
            sweepSite(scope, siteId, cutoff);
        }
    }

    private void sweepSite(ReclaimScope scope, UUID siteId, Instant cutoff) {
        String prefix = scope.prefixOf(siteId);
        S3PrefixListing listing = scope.listObjects(siteId);
        if (listing.truncated()) {
            log.warn("Listing of {} stopped after {} object(s); this pass sweeps only those",
                    prefix, listing.objects().size());
        }

        List<String> candidates = listing.objects().stream()
                .filter(object -> olderThan(object, cutoff))
                .map(S3ListedObject::key)
                .filter(key -> scope.isReclaimable(prefix, key))
                .toList();
        if (candidates.isEmpty()) {
            return;
        }

        // Rows are read after the listing on purpose: a row committed in between is then part of
        // the answer, and its object is spared. The other order could see a key before its row and
        // a row set from before that row existed.
        Set<String> referenced;
        try {
            referenced = scope.referencedKeys(siteId);
        } catch (RuntimeException e) {
            log.warn("Could not read the rows referencing {}; nothing is deleted for this site "
                    + "until they can be read", prefix, e);
            return;
        }

        List<String> orphans = candidates.stream().filter(key -> !referenced.contains(key)).toList();
        if (orphans.isEmpty()) {
            return;
        }
        delete(scope, prefix, orphans);
    }

    private void delete(ReclaimScope scope, String prefix, List<String> orphans) {
        try {
            S3FileStorageService.DeleteObjectsResult result = objectDeleter.deleteObjects(orphans);
            metrics.s3OrphansReclaimed(scope.label(), result.deletedCount());
            if (!result.errors().isEmpty()) {
                metrics.s3OrphanDeletesFailed(scope.label(), result.errors().size());
                log.warn("{} unreferenced object(s) under {} could not be deleted; they are still "
                        + "unreferenced and the next sweep tries again", result.errors().size(), prefix);
            }
            log.info("Reclaimed {} unreferenced object(s) under {}", result.deletedCount(), prefix);
        } catch (RuntimeException e) {
            // Nothing about how far the phase got is known, so every key counts as left behind —
            // which is true either way, since the next sweep will list them again.
            metrics.s3OrphanDeletesFailed(scope.label(), orphans.size());
            log.warn("Could not delete {} unreferenced object(s) under {}", orphans.size(), prefix, e);
        }
    }

    /**
     * S3 {@code LastModified} is second-resolution, and an absent one is not a young object — it is
     * an unknown one. Both round towards keeping the object.
     */
    private static boolean olderThan(S3ListedObject object, Instant cutoff) {
        return object.lastModified() != null && object.lastModified().isBefore(cutoff);
    }

    /** {@code <root>/{siteId}/} → the site id, or {@code null} when the prefix is not one of ours. */
    private static UUID siteIdOf(String sitePrefix) {
        String[] parts = sitePrefix.split("/");
        if (parts.length != 2) {
            return null;
        }
        try {
            return UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * One prefix's half of the sweep: where its objects are, which of its keys this application
     * wrote, and which rows can still name one.
     */
    private interface ReclaimScope {

        /** Metric tag and log name for this prefix. */
        String label();

        /** The sites that still have objects here. */
        S3ChildPrefixListing listSites();

        /** The prefix holding one site's objects. */
        String prefixOf(UUID siteId);

        /** Everything under one site's prefix. */
        S3PrefixListing listObjects(UUID siteId);

        /** Whether this key has a shape one of this application's writers produces. */
        boolean isReclaimable(String sitePrefix, String key);

        /** Every key of this site that a row still names. */
        Set<String> referencedKeys(UUID siteId);
    }

    /** {@code delta/{siteId}/segments/} — one object per changelog segment row. */
    private final class SegmentScope implements ReclaimScope {

        @Override
        public String label() {
            return DeltaMetrics.ORPHAN_PREFIX_SEGMENTS;
        }

        @Override
        public S3ChildPrefixListing listSites() {
            return segmentStorage.listSitePrefixes();
        }

        @Override
        public String prefixOf(UUID siteId) {
            return S3ChangelogSegmentStorage.segmentPrefix(siteId);
        }

        @Override
        public S3PrefixListing listObjects(UUID siteId) {
            return segmentStorage.listPrefix(prefixOf(siteId));
        }

        @Override
        public boolean isReclaimable(String sitePrefix, String key) {
            return matches(SEGMENT_OBJECT, sitePrefix, key);
        }

        @Override
        public Set<String> referencedKeys(UUID siteId) {
            // Provisional segments included: their rows exist for the whole of a segmented
            // re-baseline (033), so the objects are live even though nothing reads them yet.
            return new HashSet<>(segmentRepository.findAllS3KeysBySiteId(siteId));
        }
    }

    /** {@code checkpoints/{siteId}/} — per-table snapshots plus the reload frames no row names. */
    private final class CheckpointScope implements ReclaimScope {

        @Override
        public String label() {
            return DeltaMetrics.ORPHAN_PREFIX_CHECKPOINTS;
        }

        @Override
        public S3ChildPrefixListing listSites() {
            return checkpointStorage.listSitePrefixes();
        }

        @Override
        public String prefixOf(UUID siteId) {
            return S3CheckpointStorage.checkpointPrefix(siteId);
        }

        @Override
        public S3PrefixListing listObjects(UUID siteId) {
            return checkpointStorage.listPrefix(prefixOf(siteId));
        }

        @Override
        public boolean isReclaimable(String sitePrefix, String key) {
            return matches(CHECKPOINT_FRAME, sitePrefix, key)
                    || matches(CHECKPOINT_SNAPSHOT, sitePrefix, key);
        }

        @Override
        public Set<String> referencedKeys(UUID siteId) {
            Set<String> referenced = new HashSet<>();
            for (Checkpoint checkpoint : checkpointRepository.findBySiteId(siteId)) {
                if (checkpoint.getS3KeyParquet() != null) {
                    referenced.add(checkpoint.getS3KeyParquet());
                }
                if (checkpoint.getS3KeyCsv() != null) {
                    referenced.add(checkpoint.getS3KeyCsv());
                }
            }
            // The one artifact no row names: the next incremental build seeds from the frame at
            // last_checkpoint_seq, so that key is live even though nothing stores it.
            syncStateRepository.findBySiteId(siteId)
                    .map(SiteSyncState::getLastCheckpointSeq)
                    .ifPresent(seq -> referenced.add(S3CheckpointStorage.frameKey(siteId, seq)));
            return referenced;
        }
    }

    private static boolean matches(Pattern pattern, String sitePrefix, String key) {
        return key.startsWith(sitePrefix)
                && pattern.matcher(key.substring(sitePrefix.length())).matches();
    }
}
