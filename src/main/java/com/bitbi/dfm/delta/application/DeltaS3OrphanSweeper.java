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
import com.bitbi.dfm.shared.storage.S3PrefixLister;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
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
 * <h2>A page at a time, not a site's history at a time</h2>
 *
 * <p>Those four things happen per <b>page</b> of the listing (issue #199), because this sweep takes
 * the largest listing this application ever takes: its own premise is that months of superseded
 * generations accumulated with nothing reclaiming them, and it runs on a scheduler thread beside
 * the checkpoint fold budget (#152, #178) and the Parquet scratch budget (#150), neither of which
 * knows about it. {@code S3PrefixLister.forEachPage} hands one page over at a time and nothing
 * here accumulates but counters and a ten-key sample for the dry-run line, so the peak is a page
 * rather than a site. The row set is the one per-site read that stays whole, and
 * {@link SiteSweep} says why that is the right asymmetry.</p>
 *
 * <p>The sites come from the <b>bucket</b>, not from the database, and that is not an optimization:
 * {@code SiteService.deleteSite} hard-deletes the site row and never touches these prefixes, so a
 * deleted site's objects outlive every row that could have named them and no database-driven walk
 * would ever visit them.</p>
 *
 * <h2>What proves the bucket belongs to this database</h2>
 *
 * <p>Nothing does, so the sweep does not assume it (raised in review). Every deleter before this one
 * worked forward from a row and was therefore harmless to a stranger's objects; this one reads "no
 * rows for this site" as "dead", which is only true where the bucket is this instance's alone. Two
 * deployments sharing one bucket keep separate databases and therefore separate site ids, so each
 * would read the other's live prefixes as unreferenced and delete the changelog and the checkpoint
 * seed of every one of them.</p>
 *
 * <p>A site's own {@code sites} row is the closest available proof: a prefix whose site this
 * database has never heard of is either a site hard-deleted here or a live site of another
 * deployment, and <b>nothing in the bucket distinguishes them</b>, so it is left alone unless
 * {@code delta.s3-orphan.reclaim-unknown-sites} says the bucket is exclusive. Default off: the
 * populations that made this ticket (a failed commit's segment, every advancing build's superseded
 * generation) all belong to sites that still exist, so the default reclaims them and only the
 * hard-deleted-site case waits for the acknowledgement.</p>
 *
 * <p><b>It proves site-id knowledge, not bucket exclusivity</b> (raised in review), and the
 * difference is a database restored from another environment's dump: the site ids then match, the
 * guard passes, and the sweep deletes the other deployment's live objects. The precondition an
 * operator has to check is "no other deployment writes this bucket", which no code here can verify
 * — which is also why the first pass reports instead of deleting
 * ({@code delta.s3-orphan.dry-run}, default true).</p>
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
 *   <li><b>Rows.</b> The rows are read <em>after</em> the page they judge, so a row written in
 *       between is seen and protects its object; and a row set that could not be read skips the
 *       site entirely — every remaining page of it included — rather than reading "no rows" as "no
 *       references".</li>
 *   <li><b>Truncation.</b> A walk that failed mid-way hands over fewer pages, never more, so the
 *       worst it costs is one interval. What earlier pages already reclaimed stands: they were
 *       judged against the rows, not against the walk finishing.</li>
 *   <li><b>Ownership.</b> A prefix whose site has no {@code sites} row is left alone unless the
 *       bucket has been declared exclusive, because "no rows" and "not ours" look identical.</li>
 * </ul>
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

    /**
     * Default answer to "may this sweep delete?" — <b>no</b>, it reports instead.
     *
     * <p>The population on a deployment that has been running for months is unknown and cannot be
     * inspected after the fact: it includes the last good {@code snapshot.parquet} of every table
     * whose key {@code abandonStaleSnapshot} has ever detached, which an operator can still
     * re-attach by hand today and cannot once it is deleted. A first pass that names what it would
     * take is the only way to see that set before it is gone, and the deployment that ships this
     * happens before anyone reads a guide — so the acknowledgement has to be the default rather
     * than the documentation. Clearing the flag is one ConfigMap line; the startup log says so.</p>
     */
    public static final String DEFAULT_DRY_RUN = "true";

    /** How many keys a dry-run line names before it stops being readable. */
    private static final int DRY_RUN_SAMPLE = 10;

    /** Keys per {@code DeleteObjects} round trip, matching {@code S3FileStorageService}. */
    private static final int DELETE_CHUNK = 1000;

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

    /** The {@code seq=} of a checkpoint key, which is what makes that key rewritable. */
    private static final Pattern KEY_SEQUENCE = Pattern.compile("/seq=(\\d+)/");

    private final S3ChangelogSegmentStorage segmentStorage;
    private final S3CheckpointStorage checkpointStorage;
    private final S3FileStorageService objectDeleter;
    private final ChangelogSegmentRepository segmentRepository;
    private final CheckpointRepository checkpointRepository;
    private final SiteSyncStateRepository syncStateRepository;
    private final SiteRepository siteRepository;
    private final DeltaMetrics metrics;
    private final boolean enabled;
    private final boolean dryRun;
    private final boolean reclaimUnknownSites;
    private final long minAgeSeconds;

    public DeltaS3OrphanSweeper(
            S3ChangelogSegmentStorage segmentStorage,
            S3CheckpointStorage checkpointStorage,
            S3FileStorageService objectDeleter,
            ChangelogSegmentRepository segmentRepository,
            CheckpointRepository checkpointRepository,
            SiteSyncStateRepository syncStateRepository,
            SiteRepository siteRepository,
            DeltaMetrics metrics,
            @Value("${delta.s3-orphan.enabled:true}") boolean enabled,
            @Value("${delta.s3-orphan.dry-run:" + DEFAULT_DRY_RUN + "}") boolean dryRun,
            @Value("${delta.s3-orphan.reclaim-unknown-sites:false}") boolean reclaimUnknownSites,
            @Value("${delta.s3-orphan.min-age-seconds:" + DEFAULT_MIN_AGE_SECONDS + "}")
            long minAgeSeconds) {
        // Only when the sweep can actually run (raised in review): enabled=false is documented as
        // the rollback, and a rollback that still crash-loops the pod on the value it is rolling
        // back is not one.
        if (enabled && minAgeSeconds <= 0) {
            throw new IllegalArgumentException(
                    "delta.s3-orphan.min-age-seconds must be positive, got " + minAgeSeconds);
        }
        this.segmentStorage = segmentStorage;
        this.checkpointStorage = checkpointStorage;
        this.objectDeleter = objectDeleter;
        this.segmentRepository = segmentRepository;
        this.checkpointRepository = checkpointRepository;
        this.syncStateRepository = syncStateRepository;
        this.siteRepository = siteRepository;
        this.metrics = metrics;
        this.enabled = enabled;
        this.dryRun = dryRun;
        this.reclaimUnknownSites = reclaimUnknownSites;
        this.minAgeSeconds = minAgeSeconds;
        log.info("Delta S3 orphan sweep is {}{}", enabled ? (dryRun ? "on, in DRY RUN" : "on") : "off",
                enabled ? (dryRun
                                ? ": it will report what it would reclaim and delete nothing. Read a "
                                        + "pass, then set delta.s3-orphan.dry-run=false. Unreferenced "
                                        + "objects older than " + minAgeSeconds + "s are the candidates"
                                : ", reclaiming unreferenced objects older than " + minAgeSeconds + "s")
                        + (reclaimUnknownSites
                                ? ", including prefixes of sites this database has never heard of "
                                        + "(delta.s3-orphan.reclaim-unknown-sites=true: this bucket "
                                        + "is declared exclusive to this deployment)"
                                : ", except prefixes of sites this database has never heard of")
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
        S3ChildPrefixListing sites;
        try {
            sites = scope.listSites();
        } catch (RuntimeException e) {
            // The lister turns an S3Exception into a truncated result, but not every failure is one
            // (a credentials provider, an interceptor). Losing this prefix must not lose the other
            // one as well (raised in review).
            log.warn("Could not list the sites under the {} prefix; the other prefix is unaffected",
                    scope.label(), e);
            return;
        }
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
            try {
                sweepSite(scope, siteId, cutoff);
            } catch (RuntimeException e) {
                // Per-site isolation is the stated design, and the inner catches only cover the
                // reads they wrap. Anything else — a malformed key, an SDK exception the lister
                // does not catch — must cost this site and not the rest of the pass, nor the
                // second prefix (raised in review).
                log.warn("Sweep of {} failed; the remaining sites are unaffected", sitePrefix, e);
            }
        }
    }

    private void sweepSite(ReclaimScope scope, UUID siteId, Instant cutoff) {
        String prefix = scope.prefixOf(siteId);
        SiteSweep sweep = new SiteSweep(scope, siteId, prefix, cutoff);
        try {
            S3PrefixLister.S3PrefixWalk walk = scope.walkObjects(siteId, sweep::judge);
            if (walk.truncated()) {
                log.warn("Listing of {} stopped after {} object(s); this pass sweeps only those",
                        prefix, walk.objectsRead());
            }
        } finally {
            // What the pages already decided is worth saying however the walk ended: a dry run
            // that reports nothing because the last page threw is a dry run an operator cannot act
            // on, and the per-site catch above would otherwise swallow the summary with the throw.
            sweep.report();
        }
    }

    /**
     * One site's half of one prefix, judged <b>page by page</b> as the walk hands the pages over
     * (issue #199).
     *
     * <p>What is bounded and what is not: the listing is bounded by nothing — its whole premise is
     * that unreferenced objects accumulated for months — while the row set is bounded by what still
     * exists, which retention prunes for segments and which is one row per table for checkpoints.
     * So the listing is consumed a page at a time and the row set is read whole, once.</p>
     *
     * <p>Reading it <em>once</em> is what keeps this a heap change and nothing else: it is the same
     * single query per site #158 made, so no S3 call and no database call is added or removed. It
     * is read lazily, on the first page that produces a candidate, which for the overwhelming
     * majority of sites — one page — is exactly #158's "rows after the listing". Beyond the first
     * page the ordering guard is weaker and the age window is what carries it: a row can only
     * appear for an object whose write is still in flight, and the window is a day past the longest
     * such gap. The checkpoint pointer read with those rows is <b>older</b> than the pages that
     * follow, and an older pointer protects strictly more keys, so that half only ever errs towards
     * keeping the object.</p>
     */
    private final class SiteSweep {

        private final ReclaimScope scope;
        private final UUID siteId;
        private final String prefix;
        private final Instant cutoff;

        /** Null until the first page produces a candidate; never re-read after that. */
        private Predicate<String> protectedKeys;
        private boolean known;
        private boolean rowsUnreadable;
        private long heldBack;
        private long wouldReclaim;
        private final List<String> wouldReclaimSample = new ArrayList<>();

        private SiteSweep(ReclaimScope scope, UUID siteId, String prefix, Instant cutoff) {
            this.scope = scope;
            this.siteId = siteId;
            this.prefix = prefix;
            this.cutoff = cutoff;
        }

        /**
         * Judge one page and act on it. Nothing survives the call but counters and a bounded
         * sample, which is what makes the peak a page rather than a site's history.
         *
         * @param page one {@code ListObjectsV2} page of this site's prefix
         */
        private void judge(List<S3ListedObject> page) {
            if (rowsUnreadable) {
                // One failed read is the site's answer for this pass; the remaining pages must not
                // ask again and must not be deleted from on a row set that could not be read. The
                // walk itself runs on — it cannot be cancelled from here, and before #199 the whole
                // listing was taken before the rows were read anyway, so no S3 call is added.
                return;
            }
            List<String> candidates = page.stream()
                    .filter(object -> olderThan(object, cutoff))
                    .map(S3ListedObject::key)
                    .filter(key -> scope.isReclaimable(prefix, key))
                    .toList();
            if (candidates.isEmpty()) {
                return;
            }
            if (!loadRows()) {
                return;
            }
            List<String> orphans = candidates.stream()
                    .filter(key -> !protectedKeys.test(key))
                    .toList();
            if (orphans.isEmpty()) {
                return;
            }
            // Counted before either gate, so a dry run sizes the whole population — including the
            // one reclaim-unknown-sites governs, which is otherwise invisible until the flag
            // asserting its precondition is already set (raised in review).
            metrics.s3OrphanCandidates(scope.label(), orphans.size());
            if (!known && !reclaimUnknownSites) {
                heldBack += orphans.size();
                return;
            }
            if (dryRun) {
                wouldReclaim += orphans.size();
                if (wouldReclaimSample.size() < DRY_RUN_SAMPLE) {
                    orphans.stream().limit(DRY_RUN_SAMPLE - wouldReclaimSample.size())
                            .forEach(wouldReclaimSample::add);
                }
                return;
            }
            delete(orphans);
        }

        /**
         * The rows this site's objects are judged against, read on the first page that needs them.
         *
         * @return {@code false} when they could not be read, which ends the site
         */
        private boolean loadRows() {
            if (protectedKeys != null) {
                return true;
            }
            try {
                // A site this database has never heard of may be a hard-deleted one of ours or a
                // live one of another deployment sharing the bucket, and nothing in S3 tells the
                // two apart. Its own `sites` row is the closest thing to a proof of ownership
                // there is. Both reads are inside the catch — including the ownership one (raised
                // in review), whose failure would otherwise end the whole pass.
                known = siteRepository.findById(siteId).isPresent();
                protectedKeys = scope.protectedKeys(siteId);
                return true;
            } catch (RuntimeException e) {
                log.warn("Could not read the rows for {}; nothing is deleted for this site until "
                        + "they can be read", prefix, e);
                rowsUnreadable = true;
                return false;
            }
        }

        private void delete(List<String> orphans) {
            // Chunked as well as inside deleteObjects (raised in review): that method catches
            // S3Exception but not SdkClientException, so a network failure part-way through would
            // otherwise throw out after some chunks had already been deleted and report every key
            // as left behind. One chunk per try keeps both counters true. A page is already at
            // most one chunk; the loop stays for the day a page is not.
            for (int from = 0; from < orphans.size(); from += DELETE_CHUNK) {
                deleteChunk(scope, prefix,
                        orphans.subList(from, Math.min(from + DELETE_CHUNK, orphans.size())));
            }
        }

        /**
         * One line per site, not per page: the pages are an implementation detail of the walk, and
         * an operator reading a dry run wants the site's total and a sample of its keys.
         */
        private void report() {
            if (heldBack > 0) {
                log.info("Holding back {} unreferenced object(s) under {}: no site row, so this "
                                + "prefix cannot be tied to this database. Set "
                                + "delta.s3-orphan.reclaim-unknown-sites=true if this bucket is "
                                + "exclusive to this deployment and the site was hard-deleted",
                        heldBack, prefix);
            }
            if (wouldReclaim > 0) {
                log.info("Dry run (delta.s3-orphan.dry-run): {} unreferenced object(s) under {} "
                                + "would be reclaimed, e.g. {}. Clear the flag to delete them",
                        wouldReclaim, prefix, wouldReclaimSample);
            }
        }
    }

    private void deleteChunk(ReclaimScope scope, String prefix, List<String> chunk) {
        int deleted = 0;
        List<String> errors = List.of();
        try {
            S3FileStorageService.DeleteObjectsResult result = objectDeleter.deleteObjects(chunk);
            deleted = result.deletedCount();
            errors = result.errors();
        } catch (RuntimeException e) {
            log.warn("Could not delete {} unreferenced object(s) under {}", chunk.size(), prefix, e);
        }
        metrics.s3OrphansReclaimed(scope.label(), deleted);
        // Objects, not error entries (raised in review): deleteObjects records one entry per failed
        // 1000-key chunk, so counting entries would report a bucket-wide denial as a trickle. The
        // subtraction is truthful in every branch and makes the two counters sum to the candidates.
        long leftBehind = chunk.size() - deleted;
        if (leftBehind > 0) {
            metrics.s3OrphanDeletesFailed(scope.label(), leftBehind);
            // The empty list of the throw path would read as "no reason given" where the reason was
            // logged one line above, so say which it is (raised in review).
            log.warn("{} unreferenced object(s) under {} could not be deleted ({}); they are still "
                            + "unreferenced and the next sweep tries again",
                    leftBehind, prefix, errors.isEmpty() ? "see the failure logged above" : errors);
        }
        if (deleted > 0) {
            log.info("Reclaimed {} unreferenced object(s) under {}", deleted, prefix);
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

        /**
         * Everything under one site's prefix, handed over a page at a time (issue #199).
         *
         * @param siteId the site
         * @param page   receives each page as it arrives
         * @return how many objects the walk handed over, and whether it stopped early
         */
        S3PrefixLister.S3PrefixWalk walkObjects(UUID siteId, Consumer<List<S3ListedObject>> page);

        /** Whether this key has a shape one of this application's writers produces. */
        boolean isReclaimable(String sitePrefix, String key);

        /**
         * The keys of this site that must not be deleted, whatever their age — the rows' answer to
         * "is this object live?", plus anything a build could still adopt.
         */
        Predicate<String> protectedKeys(UUID siteId);
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
        public S3PrefixLister.S3PrefixWalk walkObjects(UUID siteId, Consumer<List<S3ListedObject>> page) {
            return segmentStorage.walkPrefix(prefixOf(siteId), page);
        }

        @Override
        public boolean isReclaimable(String sitePrefix, String key) {
            return matches(SEGMENT_OBJECT, sitePrefix, key);
        }

        @Override
        public Predicate<String> protectedKeys(UUID siteId) {
            // Provisional segments included: their rows exist for the whole of a segmented
            // re-baseline (033), so the objects are live even though nothing reads them yet.
            // A segment key carries a freshly minted segment id and can never be written twice, so
            // the rows are the whole answer here.
            Set<String> referenced = new HashSet<>(segmentRepository.findAllS3KeysBySiteId(siteId));
            return referenced::contains;
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
        public S3PrefixLister.S3PrefixWalk walkObjects(UUID siteId, Consumer<List<S3ListedObject>> page) {
            return checkpointStorage.walkPrefix(prefixOf(siteId), page);
        }

        @Override
        public boolean isReclaimable(String sitePrefix, String key) {
            return matches(CHECKPOINT_FRAME, sitePrefix, key)
                    || matches(CHECKPOINT_SNAPSHOT, sitePrefix, key);
        }

        @Override
        public Predicate<String> protectedKeys(UUID siteId) {
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
            // Zero is "no checkpoint yet", not a sequence: CheckpointService seeds from a frame
            // only when last_checkpoint_seq > 0, and a wipe or a re-baseline resets the pointer to
            // zero. Treating it as a sequence would make every key of such a site immune for as
            // long as the client's restarted counters took to climb back — for a site wiped at
            // five million, never (raised in review).
            long pointer = syncStateRepository.findBySiteId(siteId)
                    .map(SiteSyncState::getLastCheckpointSeq)
                    .orElse(0L);
            if (pointer > 0) {
                referenced.add(S3CheckpointStorage.frameKey(siteId, pointer));
            }
            return key -> referenced.contains(key) || couldStillBeAdopted(key, pointer);
        }

        /**
         * Checkpoint keys are addressed by {@code seq}, so unlike a segment they can be
         * <em>rewritten</em>: a build always uploads at a sequence above the pointer and adopts it
         * a moment later. Between this sweep's listing and its delete, a key that was weeks old and
         * unreferenced can therefore become the live seed — the one place where the listing's
         * {@code lastModified} is not the whole story (raised in review). Anything at or above the
         * pointer is left alone for that reason; it becomes reclaimable as soon as the pointer
         * passes it, which a live site does nightly.
         *
         * <p>A site with no pointer — no sync-state row, or one still at zero after a wipe or a
         * re-baseline — gets no protection from this rule, and needs none: a build seeds from a
         * frame only above zero, and the age window still covers whatever a live build is writing.
         * That is also what keeps a hard-deleted site reclaimable.</p>
         */
        private boolean couldStillBeAdopted(String key, long pointer) {
            if (pointer <= 0) {
                return false;
            }
            Matcher seq = KEY_SEQUENCE.matcher(key);
            if (!seq.find()) {
                return true;
            }
            try {
                return Long.parseLong(seq.group(1)) >= pointer;
            } catch (NumberFormatException e) {
                // The shape filter bounds the digits by nothing, so a key above Long.MAX_VALUE
                // reaches here. Keep it: an unreadable sequence is not a licence to delete.
                log.warn("Keeping {}: its sequence cannot be read as a number", key);
                return true;
            }
        }
    }

    private static boolean matches(Pattern pattern, String sitePrefix, String key) {
        return key.startsWith(sitePrefix)
                && pattern.matcher(key.substring(sitePrefix.length())).matches();
    }
}
