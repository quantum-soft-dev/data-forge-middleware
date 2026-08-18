package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import com.bitbi.dfm.delta.infrastructure.S3ChangelogSegmentStorage;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.shared.storage.S3ChildPrefixListing;
import com.bitbi.dfm.shared.storage.S3ListedObject;
import com.bitbi.dfm.shared.storage.S3PrefixLister;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Issue #158 — objects that no row references are reclaimed from both delta prefixes, and objects
 * that a row does reference, or that are too young to have had their row written yet, are not.
 *
 * <p>Everything here is one mechanism seen twice: list the prefix, keep only keys of a shape this
 * application writes, drop everything younger than the age window, then subtract the keys the rows
 * still name. The two prefixes differ only in which rows answer that last question.</p>
 *
 * <p>The direction of every failure matters more than the reclaim itself. Wasted storage costs
 * money; deleting the frame at {@code last_checkpoint_seq} costs a site its checkpoint history
 * (#149 retires those rows after five nights). So the guards are tested individually — a young
 * object survives with no row at all, an unreadable row set stops the site outright, and an
 * unrecognized key shape is never a candidate however old it is.</p>
 */
@DisplayName("Delta S3 orphan sweeper (#158)")
class DeltaS3OrphanSweeperTest {

    private static final UUID SITE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final long AGE_SECONDS = 86_400L;
    private static final Instant NOW = Instant.parse("2026-08-17T02:00:00Z");
    private static final Instant OLD = NOW.minusSeconds(AGE_SECONDS + 60);
    private static final Instant FRESH = NOW.minusSeconds(60);

    private static final String SEGMENT_PREFIX = "delta/" + SITE + "/segments/";
    private static final String CHECKPOINT_PREFIX = "checkpoints/" + SITE + "/";

    private S3ChangelogSegmentStorage segmentStorage;
    private S3CheckpointStorage checkpointStorage;
    private S3FileStorageService objectDeleter;
    private ChangelogSegmentRepository segmentRepository;
    private CheckpointRepository checkpointRepository;
    private SiteSyncStateRepository syncStateRepository;
    private SiteRepository siteRepository;
    private MeterRegistry registry;
    private DeltaMetrics metrics;

    @BeforeEach
    void setUp() {
        segmentStorage = mock(S3ChangelogSegmentStorage.class);
        checkpointStorage = mock(S3CheckpointStorage.class);
        objectDeleter = mock(S3FileStorageService.class);
        segmentRepository = mock(ChangelogSegmentRepository.class);
        checkpointRepository = mock(CheckpointRepository.class);
        syncStateRepository = mock(SiteSyncStateRepository.class);
        siteRepository = mock(SiteRepository.class);
        // Known by default: ownership is its own test below.
        when(siteRepository.findById(SITE)).thenReturn(Optional.of(mock(Site.class)));
        registry = new SimpleMeterRegistry();
        metrics = new DeltaMetrics(registry);

        // Default: neither prefix has any site. Each test opens the one it is about.
        when(segmentStorage.listSitePrefixes()).thenReturn(S3ChildPrefixListing.complete(List.of()));
        when(checkpointStorage.listSitePrefixes()).thenReturn(S3ChildPrefixListing.complete(List.of()));
        when(objectDeleter.deleteObjects(anyList()))
                .thenAnswer(call -> new S3FileStorageService.DeleteObjectsResult(
                        ((List<?>) call.getArgument(0)).size(), List.of()));
    }

    private DeltaS3OrphanSweeper sweeper() {
        return sweeper(true);
    }

    private DeltaS3OrphanSweeper sweeper(boolean enabled) {
        return sweeper(enabled, false);
    }

    private DeltaS3OrphanSweeper sweeper(boolean enabled, boolean reclaimUnknownSites) {
        return new DeltaS3OrphanSweeper(segmentStorage, checkpointStorage, objectDeleter,
                segmentRepository, checkpointRepository, syncStateRepository, siteRepository,
                metrics, enabled, false, reclaimUnknownSites, AGE_SECONDS);
    }

    /** The shipped default: reports and deletes nothing. */
    private DeltaS3OrphanSweeper dryRunSweeper() {
        return new DeltaS3OrphanSweeper(segmentStorage, checkpointStorage, objectDeleter,
                segmentRepository, checkpointRepository, syncStateRepository, siteRepository,
                metrics, true, true, false, AGE_SECONDS);
    }

    private void segmentSite(S3ListedObject... objects) {
        segmentSitePages(false, List.of(objects));
    }

    /**
     * A segment prefix whose walk hands over {@code pages} in order — the shape the sweeper sees
     * from a real prefix bigger than one {@code ListObjectsV2} page (#199).
     */
    @SafeVarargs
    private void segmentSitePages(boolean truncated, List<S3ListedObject>... pages) {
        when(segmentStorage.listSitePrefixes())
                .thenReturn(S3ChildPrefixListing.complete(List.of("delta/" + SITE + "/")));
        when(segmentStorage.walkPrefix(eq(SEGMENT_PREFIX), any()))
                .thenAnswer(call -> deliver(call, truncated, pages));
    }

    private void checkpointSite(S3ListedObject... objects) {
        when(checkpointStorage.listSitePrefixes())
                .thenReturn(S3ChildPrefixListing.complete(List.of(CHECKPOINT_PREFIX)));
        when(checkpointStorage.walkPrefix(eq(CHECKPOINT_PREFIX), any()))
                .thenAnswer(call -> deliver(call, false, List.of(objects)));
        when(syncStateRepository.findBySiteId(SITE)).thenReturn(Optional.empty());
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of());
    }

    /** Hand each page to the sweeper's consumer, then report what the walk did. */
    @SafeVarargs
    private static S3PrefixLister.S3PrefixWalk deliver(
            InvocationOnMock call, boolean truncated, List<S3ListedObject>... pages) {
        @SuppressWarnings("unchecked")
        Consumer<List<S3ListedObject>> consumer = call.getArgument(1, Consumer.class);
        long read = 0L;
        for (List<S3ListedObject> page : pages) {
            read += page.size();
            consumer.accept(page);
        }
        return new S3PrefixLister.S3PrefixWalk(read, truncated);
    }

    private static S3ListedObject object(String key, Instant lastModified) {
        return new S3ListedObject(key, lastModified);
    }

    private List<String> deletedKeys() {
        var keys = forClass(List.class);
        verify(objectDeleter).deleteObjects(keys.capture());
        @SuppressWarnings("unchecked")
        List<String> captured = (List<String>) keys.getValue();
        return captured;
    }

    private double counter(String name, String prefix) {
        return registry.find(name).tag("prefix", prefix).counter().count();
    }

    // ---------------------------------------------------------------- delta/{site}/segments/

    @Test
    @DisplayName("a segment object old enough and named by no row is reclaimed")
    void reclaimsAnUnreferencedSegment() {
        String orphan = SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz";
        segmentSite(object(orphan, OLD));
        when(segmentRepository.findAllS3KeysBySiteId(SITE)).thenReturn(List.of());

        sweeper().sweep(NOW);

        assertThat(deletedKeys()).containsExactly(orphan);
        assertThat(counter("delta.s3-orphan.reclaimed", "segments")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a segment object a changelog row still names is left alone")
    void keepsAReferencedSegment() {
        String live = SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz";
        segmentSite(object(live, OLD));
        when(segmentRepository.findAllS3KeysBySiteId(SITE)).thenReturn(List.of(live));

        sweeper().sweep(NOW);

        verify(objectDeleter, never()).deleteObjects(anyList());
    }

    @Test
    @DisplayName("an object younger than the age window survives even with no row at all")
    void keepsAnObjectYoungerThanTheAgeWindow() {
        segmentSite(object(SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz", FRESH));
        when(segmentRepository.findAllS3KeysBySiteId(SITE)).thenReturn(List.of());

        sweeper().sweep(NOW);

        verify(objectDeleter, never()).deleteObjects(anyList());
        // The row set is not even asked for: nothing under the prefix is a candidate.
        verify(segmentRepository, never()).findAllS3KeysBySiteId(SITE);
    }

    @Test
    @DisplayName("an object whose lastModified S3 did not report counts as too young")
    void keepsAnObjectWithoutALastModified() {
        segmentSite(object(SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz", null));

        sweeper().sweep(NOW);

        verify(objectDeleter, never()).deleteObjects(anyList());
    }

    @Test
    @DisplayName("a key under the segment prefix that this application never writes is not a candidate")
    void ignoresAnUnknownShapeUnderTheSegmentPrefix() {
        segmentSite(object(SEGMENT_PREFIX + "2026/07/rollup.parquet", OLD));

        sweeper().sweep(NOW);

        verify(objectDeleter, never()).deleteObjects(anyList());
        verify(segmentRepository, never()).findAllS3KeysBySiteId(SITE);
    }

    // ---------------------------------------------------------------- checkpoints/{site}/

    @Test
    @DisplayName("the frame the pointer names is kept and every earlier one is reclaimed")
    void keepsTheFrameThePointerNamesAndReclaimsTheRest() {
        String live = CHECKPOINT_PREFIX + "_frame/seq=7/frame.pb.gz";
        String superseded = CHECKPOINT_PREFIX + "_frame/seq=6/frame.pb.gz";
        checkpointSite(object(live, OLD), object(superseded, OLD));
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.recordCheckpoint(7L);
        when(syncStateRepository.findBySiteId(SITE)).thenReturn(Optional.of(state));

        sweeper().sweep(NOW);

        assertThat(deletedKeys()).containsExactly(superseded);
        assertThat(counter("delta.s3-orphan.reclaimed", "checkpoints")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a frame a build never adopted is reclaimed once the pointer has passed it")
    void reclaimsAStrandedFrameOnceThePointerHasPassedIt() {
        String stranded = CHECKPOINT_PREFIX + "_frame/seq=9/frame.pb.gz";
        checkpointSite(object(stranded, OLD));
        SiteSyncState state = SiteSyncState.initial(SITE);
        // Still ahead of the pointer while the site is at 7: a later build can end at 9 and adopt
        // that very key, so it waits. A live site passes it on the next nightly build.
        state.recordCheckpoint(12L);
        when(syncStateRepository.findBySiteId(SITE)).thenReturn(Optional.of(state));

        sweeper().sweep(NOW);

        assertThat(deletedKeys()).containsExactly(stranded);
    }

    @Test
    @DisplayName("the snapshot a checkpoint row names is kept and the superseded generation is reclaimed")
    void keepsTheCurrentSnapshotAndReclaimsTheSuperseded() {
        String current = CHECKPOINT_PREFIX + "orders/seq=7/snapshot.parquet";
        String previous = CHECKPOINT_PREFIX + "orders/seq=6/snapshot.parquet";
        checkpointSite(object(current, OLD), object(previous, OLD));
        Checkpoint checkpoint = Checkpoint.create(SITE, "orders", 7L, 10L);
        checkpoint.attachParquet(current);
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of(checkpoint));

        sweeper().sweep(NOW);

        assertThat(deletedKeys()).containsExactly(previous);
    }

    @Test
    @DisplayName("a key under the checkpoint prefix of an unrecognized shape is never a candidate")
    void ignoresAnUnknownShapeUnderTheCheckpointPrefix() {
        checkpointSite(object(CHECKPOINT_PREFIX + "manifest.json", OLD),
                object(CHECKPOINT_PREFIX + "orders/seq=6/notes.txt", OLD));

        sweeper().sweep(NOW);

        verify(objectDeleter, never()).deleteObjects(anyList());
        verify(checkpointRepository, never()).findBySiteId(SITE);
    }

    @Test
    @DisplayName("a site whose rows are all gone has its whole prefix reclaimed")
    void reclaimsEverythingOfASiteWithNoRowsLeft() {
        String frame = CHECKPOINT_PREFIX + "_frame/seq=3/frame.pb.gz";
        String snapshot = CHECKPOINT_PREFIX + "orders/seq=3/snapshot.parquet";
        String legacyCsv = CHECKPOINT_PREFIX + "orders/seq=2/snapshot.csv.gz";
        checkpointSite(object(frame, OLD), object(snapshot, OLD), object(legacyCsv, OLD));

        sweeper().sweep(NOW);

        assertThat(deletedKeys()).containsExactlyInAnyOrder(frame, snapshot, legacyCsv);
    }

    // ---------------------------------------------------------------- the guards

    @Test
    @DisplayName("a checkpoint key at or above the pointer is kept: a build could still adopt it")
    void keepsACheckpointKeyThePointerHasNotPassed() {
        String atPointer = CHECKPOINT_PREFIX + "orders/seq=7/snapshot.parquet";
        String above = CHECKPOINT_PREFIX + "orders/seq=8/snapshot.parquet";
        String below = CHECKPOINT_PREFIX + "orders/seq=6/snapshot.parquet";
        checkpointSite(object(atPointer, OLD), object(above, OLD), object(below, OLD));
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.recordCheckpoint(7L);
        when(syncStateRepository.findBySiteId(SITE)).thenReturn(Optional.of(state));

        sweeper().sweep(NOW);

        // seq= is rewritable, unlike a segment id: between this listing and the delete a build can
        // upload at that sequence and adopt it, so only what the pointer has passed may go.
        assertThat(deletedKeys()).containsExactly(below);
    }

    @Test
    @DisplayName("the shipped default reports what it would take and deletes nothing")
    void dryRunDeletesNothing() {
        segmentSite(object(SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz", OLD));
        when(segmentRepository.findAllS3KeysBySiteId(SITE)).thenReturn(List.of());

        dryRunSweeper().sweep(NOW);

        verify(objectDeleter, never()).deleteObjects(anyList());
        assertThat(counter("delta.s3-orphan.reclaimed", "segments")).isZero();
        // The population still has to be visible, or the shipped default is unobservable in
        // Prometheus and an alert on a flat `reclaimed` pages on a healthy deployment.
        assertThat(counter("delta.s3-orphan.candidates", "segments")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a pointer of zero protects nothing: a wiped site is swept, not made immune")
    void aZeroPointerIsNotASequence() {
        String frame = CHECKPOINT_PREFIX + "_frame/seq=5000000/frame.pb.gz";
        checkpointSite(object(frame, OLD));
        // What a wipe or a re-baseline leaves: the pointer back at zero while the objects of the
        // old epoch sit at a sequence the restarted client will not reach for months, or ever.
        when(syncStateRepository.findBySiteId(SITE))
                .thenReturn(Optional.of(SiteSyncState.initial(SITE)));

        sweeper().sweep(NOW);

        assertThat(deletedKeys()).containsExactly(frame);
    }

    @Test
    @DisplayName("a sequence too large to be a number keeps its object instead of throwing")
    void keepsAKeyWhoseSequenceCannotBeRead() {
        String unreadable = CHECKPOINT_PREFIX + "orders/seq=99999999999999999999/snapshot.parquet";
        String below = CHECKPOINT_PREFIX + "orders/seq=1/snapshot.parquet";
        checkpointSite(object(unreadable, OLD), object(below, OLD));
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.recordCheckpoint(7L);
        when(syncStateRepository.findBySiteId(SITE)).thenReturn(Optional.of(state));

        sweeper().sweep(NOW);

        assertThat(deletedKeys()).containsExactly(below);
    }

    @Test
    @DisplayName("a site that throws outside a guarded read costs that site, not the pass")
    void survivesASiteThatThrowsOutsideAGuardedRead() {
        String orphan = CHECKPOINT_PREFIX + "_frame/seq=1/frame.pb.gz";
        segmentSite(object(SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz", OLD));
        checkpointSite(object(orphan, OLD));
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.recordCheckpoint(7L);
        when(syncStateRepository.findBySiteId(SITE)).thenReturn(Optional.of(state));
        // Not one of the wrapped reads: the listing itself blows up for the segment scope.
        when(segmentStorage.walkPrefix(eq(SEGMENT_PREFIX), any()))
                .thenThrow(new IllegalStateException("SDK exception the lister does not catch"));

        sweeper().sweep(NOW);

        assertThat(deletedKeys()).containsExactly(orphan);
    }

    @Test
    @DisplayName("a site whose rows could not be read is skipped rather than swept")
    void skipsASiteWhoseRowsCouldNotBeRead() {
        segmentSite(object(SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz", OLD));
        when(segmentRepository.findAllS3KeysBySiteId(SITE))
                .thenThrow(new IllegalStateException("connection timed out"));

        sweeper().sweep(NOW);

        verify(objectDeleter, never()).deleteObjects(anyList());
    }

    @Test
    @DisplayName("a failed ownership read costs that site, not the rest of the pass")
    void survivesAFailedOwnershipRead() {
        String orphan = CHECKPOINT_PREFIX + "_frame/seq=1/frame.pb.gz";
        segmentSite(object(SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz", OLD));
        checkpointSite(object(orphan, OLD));
        when(siteRepository.findById(SITE))
                .thenThrow(new IllegalStateException("pool exhausted"))
                .thenReturn(Optional.of(mock(Site.class)));

        sweeper().sweep(NOW);

        // The segment scope lost its site to the throw; the checkpoint scope still ran.
        assertThat(deletedKeys()).containsExactly(orphan);
    }

    @Test
    @DisplayName("the rows are read after the page they judge, and while the walk is still running")
    void readsTheRowsAfterThePageAndDuringTheWalk() {
        String key = SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz";
        List<String> events = new ArrayList<>();
        when(segmentStorage.listSitePrefixes())
                .thenReturn(S3ChildPrefixListing.complete(List.of("delta/" + SITE + "/")));
        when(segmentStorage.walkPrefix(eq(SEGMENT_PREFIX), any())).thenAnswer(call -> {
            @SuppressWarnings("unchecked")
            Consumer<List<S3ListedObject>> consumer = call.getArgument(1, Consumer.class);
            events.add("page");
            consumer.accept(List.of(object(key, OLD)));
            events.add("walk-finished");
            return new S3PrefixLister.S3PrefixWalk(1L, false);
        });
        when(segmentRepository.findAllS3KeysBySiteId(SITE)).thenAnswer(call -> {
            events.add("rows");
            return List.of(key);
        });

        sweeper().sweep(NOW);

        // "rows" before "walk-finished" is the whole point of #199: the site's history is judged
        // while the walk runs, so nothing accumulates. "page" before "rows" keeps #158's guard —
        // a row written during the walk is in the answer and its object is spared.
        assertThat(events).containsExactly("page", "rows", "walk-finished");
    }

    @Test
    @DisplayName("a multi-page prefix is judged and deleted page by page, never as one listing")
    void judgesEachPageAsItArrives() {
        String first = SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz";
        String second = SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz";
        segmentSitePages(false, List.of(object(first, OLD)), List.of(object(second, OLD)));
        when(segmentRepository.findAllS3KeysBySiteId(SITE)).thenReturn(List.of());

        sweeper().sweep(NOW);

        var chunks = forClass(List.class);
        verify(objectDeleter, times(2)).deleteObjects(chunks.capture());
        assertThat(chunks.getAllValues()).containsExactly(List.of(first), List.of(second));
        assertThat(counter("delta.s3-orphan.reclaimed", "segments")).isEqualTo(2.0);
        assertThat(counter("delta.s3-orphan.candidates", "segments")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("the row set is read once per site, however many pages the prefix takes")
    void readsTheRowSetOncePerSite() {
        segmentSitePages(false,
                List.of(object(SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz", OLD)),
                List.of(object(SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz", OLD)),
                List.of(object(SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz", OLD)));
        when(segmentRepository.findAllS3KeysBySiteId(SITE)).thenReturn(List.of());

        sweeper().sweep(NOW);

        // Once, not once per page: the row set is bounded by what retention left, so re-reading it
        // per page would trade the heap this ticket bounds for database work proportional to
        // pages x rows.
        verify(segmentRepository, times(1)).findAllS3KeysBySiteId(SITE);
        verify(siteRepository, times(1)).findById(SITE);
    }

    @Test
    @DisplayName("rows that could not be read stop the whole site, not just the page that asked")
    void unreadableRowsStopTheRemainingPages() {
        segmentSitePages(false,
                List.of(object(SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz", OLD)),
                List.of(object(SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz", OLD)));
        when(segmentRepository.findAllS3KeysBySiteId(SITE))
                .thenThrow(new IllegalStateException("connection timed out"));

        sweeper().sweep(NOW);

        verify(objectDeleter, never()).deleteObjects(anyList());
        // The second page must not ask again: one failed read is the site's answer for this pass.
        verify(segmentRepository, times(1)).findAllS3KeysBySiteId(SITE);
    }

    @Test
    @DisplayName("a truncated listing sweeps what it did read and never more")
    void sweepsWhatATruncatedListingReturned() {
        String orphan = SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz";
        segmentSitePages(true, List.of(object(orphan, OLD)));
        when(segmentRepository.findAllS3KeysBySiteId(SITE)).thenReturn(List.of());

        sweeper().sweep(NOW);

        assertThat(deletedKeys()).containsExactly(orphan);
    }

    @Test
    @DisplayName("a child prefix that is not a site id is left untouched")
    void ignoresAChildPrefixThatIsNotASiteId() {
        when(checkpointStorage.listSitePrefixes())
                .thenReturn(S3ChildPrefixListing.complete(List.of("checkpoints/not-a-uuid/")));

        sweeper().sweep(NOW);

        verify(checkpointStorage, never()).walkPrefix(anyString(), any());
        verify(objectDeleter, never()).deleteObjects(anyList());
    }

    @Test
    @DisplayName("objects the bucket refused are counted as objects, not as error entries")
    void countsKeysTheBucketRefusedToDelete() {
        List<String> orphans = List.of(
                SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz",
                SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz",
                SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz");
        segmentSite(orphans.stream().map(key -> object(key, OLD)).toArray(S3ListedObject[]::new));
        when(segmentRepository.findAllS3KeysBySiteId(SITE)).thenReturn(List.of());
        // What a whole failed chunk looks like: one error entry for any number of objects.
        when(objectDeleter.deleteObjects(anyList()))
                .thenReturn(new S3FileStorageService.DeleteObjectsResult(0, List.of("S3Exception: denied")));

        sweeper().sweep(NOW);

        assertThat(counter("delta.s3-orphan.reclaimed", "segments")).isZero();
        assertThat(counter("delta.s3-orphan.delete-failed", "segments")).isEqualTo(3.0);
    }

    @Test
    @DisplayName("a delete that throws costs that site's pass, not the sweep")
    void survivesADeleteThatThrows() {
        String orphan = SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz";
        segmentSite(object(orphan, OLD));
        when(segmentRepository.findAllS3KeysBySiteId(SITE)).thenReturn(List.of());
        when(objectDeleter.deleteObjects(anyList()))
                .thenThrow(new IllegalStateException("connection reset"));

        sweeper().sweep(NOW);

        assertThat(counter("delta.s3-orphan.delete-failed", "segments")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a prefix whose site this database has never heard of is counted, then held back")
    void holdsBackAPrefixOfAnUnknownSite() {
        segmentSite(object(SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz", OLD));
        when(siteRepository.findById(SITE)).thenReturn(Optional.empty());
        when(segmentRepository.findAllS3KeysBySiteId(SITE)).thenReturn(List.of());

        sweeper().sweep(NOW);

        verify(objectDeleter, never()).deleteObjects(anyList());
        // Counted all the same, or the population reclaim-unknown-sites governs stays invisible
        // until the flag asserting its precondition is already set.
        assertThat(counter("delta.s3-orphan.candidates", "segments")).isEqualTo(1.0);
        assertThat(counter("delta.s3-orphan.reclaimed", "segments")).isZero();
    }

    @Test
    @DisplayName("declaring the bucket exclusive reclaims a hard-deleted site's objects")
    void reclaimsAnUnknownSiteWhenTheBucketIsDeclaredExclusive() {
        String orphan = SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz";
        segmentSite(object(orphan, OLD));
        when(siteRepository.findById(SITE)).thenReturn(Optional.empty());
        when(segmentRepository.findAllS3KeysBySiteId(SITE)).thenReturn(List.of());

        sweeper(true, true).sweep(NOW);

        assertThat(deletedKeys()).containsExactly(orphan);
    }

    @Test
    @DisplayName("disabled, it does not even ask the bucket")
    void doesNothingWhenDisabled() {
        sweeper(false).sweep(NOW);

        verifyNoInteractions(segmentStorage, checkpointStorage, objectDeleter,
                segmentRepository, checkpointRepository, syncStateRepository);
    }

    @Test
    @DisplayName("disabled, a bad age window is not a crash-loop — the rollback has to work")
    void acceptsABadAgeWindowWhileDisabled() {
        DeltaS3OrphanSweeper disabled = new DeltaS3OrphanSweeper(segmentStorage, checkpointStorage,
                objectDeleter, segmentRepository, checkpointRepository, syncStateRepository,
                siteRepository, metrics, false, false, false, 0L);

        disabled.sweep(NOW);

        verifyNoInteractions(objectDeleter);
    }

    @Test
    @DisplayName("every reclaim counter exists from startup, so an alert can predate the first sweep")
    void registersEveryCounterAtZero() {
        assertThat(counter("delta.s3-orphan.reclaimed", "segments")).isZero();
        assertThat(counter("delta.s3-orphan.reclaimed", "checkpoints")).isZero();
        assertThat(counter("delta.s3-orphan.delete-failed", "segments")).isZero();
        assertThat(counter("delta.s3-orphan.delete-failed", "checkpoints")).isZero();
    }

    @Test
    @DisplayName("an age window of zero or less is refused at startup, not silently accepted")
    void refusesANonPositiveAgeWindow() {
        assertThatThrownBy(() -> new DeltaS3OrphanSweeper(segmentStorage, checkpointStorage,
                objectDeleter, segmentRepository, checkpointRepository, syncStateRepository,
                siteRepository, metrics, true, false, false, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delta.s3-orphan.min-age-seconds");
    }
}
