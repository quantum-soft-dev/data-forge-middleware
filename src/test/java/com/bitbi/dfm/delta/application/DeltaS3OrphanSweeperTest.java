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
import com.bitbi.dfm.shared.storage.S3PrefixListing;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        return new DeltaS3OrphanSweeper(segmentStorage, checkpointStorage, objectDeleter,
                segmentRepository, checkpointRepository, syncStateRepository, metrics,
                enabled, AGE_SECONDS);
    }

    private void segmentSite(S3ListedObject... objects) {
        when(segmentStorage.listSitePrefixes())
                .thenReturn(S3ChildPrefixListing.complete(List.of("delta/" + SITE + "/")));
        when(segmentStorage.listPrefix(SEGMENT_PREFIX))
                .thenReturn(S3PrefixListing.complete(List.of(objects)));
    }

    private void checkpointSite(S3ListedObject... objects) {
        when(checkpointStorage.listSitePrefixes())
                .thenReturn(S3ChildPrefixListing.complete(List.of(CHECKPOINT_PREFIX)));
        when(checkpointStorage.listPrefix(CHECKPOINT_PREFIX))
                .thenReturn(S3PrefixListing.complete(List.of(objects)));
        when(syncStateRepository.findBySiteId(SITE)).thenReturn(Optional.empty());
        when(checkpointRepository.findBySiteId(SITE)).thenReturn(List.of());
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
    @DisplayName("a frame above the pointer is reclaimed once it is older than the age window")
    void reclaimsAFrameLeftByABuildThatNeverAdoptedIt() {
        String stranded = CHECKPOINT_PREFIX + "_frame/seq=9/frame.pb.gz";
        checkpointSite(object(stranded, OLD));
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.recordCheckpoint(7L);
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
    @DisplayName("a site whose rows could not be read is skipped rather than swept")
    void skipsASiteWhoseRowsCouldNotBeRead() {
        segmentSite(object(SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz", OLD));
        when(segmentRepository.findAllS3KeysBySiteId(SITE))
                .thenThrow(new IllegalStateException("connection timed out"));

        sweeper().sweep(NOW);

        verify(objectDeleter, never()).deleteObjects(anyList());
    }

    @Test
    @DisplayName("the rows are read after the listing, so a row written in between still protects")
    void readsTheRowsAfterTheListing() {
        String key = SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz";
        segmentSite(object(key, OLD));
        when(segmentRepository.findAllS3KeysBySiteId(SITE)).thenReturn(List.of(key));

        sweeper().sweep(NOW);

        InOrder order = inOrder(segmentStorage, segmentRepository);
        order.verify(segmentStorage).listPrefix(SEGMENT_PREFIX);
        order.verify(segmentRepository).findAllS3KeysBySiteId(SITE);
    }

    @Test
    @DisplayName("a truncated listing sweeps what it did read and never more")
    void sweepsWhatATruncatedListingReturned() {
        String orphan = SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz";
        when(segmentStorage.listSitePrefixes())
                .thenReturn(S3ChildPrefixListing.complete(List.of("delta/" + SITE + "/")));
        when(segmentStorage.listPrefix(SEGMENT_PREFIX))
                .thenReturn(S3PrefixListing.truncated(List.of(object(orphan, OLD))));
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

        verify(checkpointStorage, never()).listPrefix(anyString());
        verify(objectDeleter, never()).deleteObjects(anyList());
    }

    @Test
    @DisplayName("keys the bucket refused to delete are counted rather than lost")
    void countsKeysTheBucketRefusedToDelete() {
        String orphan = SEGMENT_PREFIX + UUID.randomUUID() + ".pb.gz";
        segmentSite(object(orphan, OLD));
        when(segmentRepository.findAllS3KeysBySiteId(SITE)).thenReturn(List.of());
        when(objectDeleter.deleteObjects(anyList()))
                .thenReturn(new S3FileStorageService.DeleteObjectsResult(0, List.of(orphan)));

        sweeper().sweep(NOW);

        assertThat(counter("delta.s3-orphan.reclaimed", "segments")).isZero();
        assertThat(counter("delta.s3-orphan.delete-failed", "segments")).isEqualTo(1.0);
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
    @DisplayName("disabled, it does not even ask the bucket")
    void doesNothingWhenDisabled() {
        sweeper(false).sweep(NOW);

        verifyNoInteractions(segmentStorage, checkpointStorage, objectDeleter,
                segmentRepository, checkpointRepository, syncStateRepository);
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
                metrics, true, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delta.s3-orphan.min-age-seconds");
    }
}
