package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.application.DeltaSyncStateService.SyncStateView;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.infrastructure.S3ChangelogSegmentStorage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #212 — retention must not delete unprocessed work silently.
 *
 * <p>A below-checkpoint segment whose {@code plugin_sql_at} or {@code egress_at} is still
 * {@code NULL} is the durable entry of a work queue ({@code DeltaSqlQueueService},
 * {@code DeltaEgressService}); pruning it loses that batch's SQL or delta Parquet permanently,
 * with no audit row marking the moment of loss. The prune therefore skips such a segment and makes
 * the hold-back visible: {@code delta.retention.segments.held-back{reason=...}} plus one WARN per
 * site per pass. Processed segments are pruned exactly as before, and segments inside the audit
 * window are retained by the window — not counted as held back.</p>
 */
@ExtendWith(MockitoExtension.class)
class ChangelogRetentionServiceTest {

    private static final UUID SITE = UUID.randomUUID();
    private static final UUID BATCH = UUID.randomUUID();
    private static final String HELD_BACK_METER = "delta.retention.segments.held-back";

    @Mock
    private ChangelogSegmentRepository segmentRepository;
    @Mock
    private S3ChangelogSegmentStorage segmentStorage;
    @Mock
    private DeltaSyncStateService syncStateService;

    private SimpleMeterRegistry registry;
    private DeltaMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new DeltaMetrics(registry);
    }

    private ChangelogRetentionService service(int auditWindowSegments) {
        return new ChangelogRetentionService(segmentRepository, segmentStorage, syncStateService,
                metrics, auditWindowSegments);
    }

    private void checkpointAt(long seq) {
        when(syncStateService.getSyncState(SITE))
                .thenReturn(new SyncStateView(seq, seq, 1, false, false, 0L, 0L));
    }

    private static ChangelogSegment segment(long firstSeq, long lastSeq) {
        return ChangelogSegment.create(SITE, BATCH, firstSeq, lastSeq, lastSeq - firstSeq + 1,
                "hash-" + firstSeq, "delta/" + SITE + "/segments/" + firstSeq + ".pb.gz",
                "DELTA", null);
    }

    private static ChangelogSegment processedSegment(long firstSeq, long lastSeq) {
        ChangelogSegment segment = segment(firstSeq, lastSeq);
        segment.markEgressed();
        segment.markPluginSqlProcessed();
        return segment;
    }

    private double heldBack(String reason) {
        return registry.get(HELD_BACK_METER).tag("reason", reason).counter().count();
    }

    @Test
    void prunesProcessedSegmentsBelowCheckpointAsBefore() {
        checkpointAt(10L);
        ChangelogSegment processed = processedSegment(1L, 5L);
        when(segmentRepository.findBySiteIdOrderByFirstSeq(SITE)).thenReturn(List.of(processed));

        int pruned = service(0).prune(SITE);

        assertEquals(1, pruned, "a fully processed below-checkpoint segment is pruned");
        verify(segmentStorage).delete(processed.getS3Key());
        verify(segmentRepository).deleteById(processed.getId());
        assertEquals(0.0, heldBack("pending_plugin_sql"), "nothing was held back");
        assertEquals(0.0, heldBack("pending_egress"), "nothing was held back");
    }

    @Test
    void holdsBackSegmentWhosePluginSqlIsPending() {
        checkpointAt(10L);
        ChangelogSegment pendingSql = segment(1L, 5L);
        pendingSql.markEgressed(); // egress done, plugin SQL still owed
        when(segmentRepository.findBySiteIdOrderByFirstSeq(SITE)).thenReturn(List.of(pendingSql));

        int pruned = service(0).prune(SITE);

        assertEquals(0, pruned, "pending work is not prunable");
        verify(segmentStorage, never()).delete(anyString());
        verify(segmentRepository, never()).deleteById(any());
        assertEquals(1.0, heldBack("pending_plugin_sql"));
        assertEquals(0.0, heldBack("pending_egress"));
    }

    @Test
    void holdsBackSegmentWhoseEgressIsPending() {
        checkpointAt(10L);
        ChangelogSegment pendingEgress = segment(1L, 5L);
        pendingEgress.markPluginSqlProcessed(); // SQL done, egress still owed
        when(segmentRepository.findBySiteIdOrderByFirstSeq(SITE)).thenReturn(List.of(pendingEgress));

        int pruned = service(0).prune(SITE);

        assertEquals(0, pruned, "pending work is not prunable");
        verify(segmentStorage, never()).delete(anyString());
        verify(segmentRepository, never()).deleteById(any());
        assertEquals(0.0, heldBack("pending_plugin_sql"));
        assertEquals(1.0, heldBack("pending_egress"));
    }

    @Test
    void countsASegmentPendingBothOnBothSeries() {
        // Each series independently answers "is this queue stalling retention", so a segment owing
        // both moves both — the sum over reasons can exceed the number of held-back segments, the
        // same per-consumer honesty as delta.parquet.unrepresentable-decimals (#215).
        checkpointAt(10L);
        ChangelogSegment pendingBoth = segment(1L, 5L);
        when(segmentRepository.findBySiteIdOrderByFirstSeq(SITE)).thenReturn(List.of(pendingBoth));

        int pruned = service(0).prune(SITE);

        assertEquals(0, pruned);
        verify(segmentRepository, never()).deleteById(any());
        assertEquals(1.0, heldBack("pending_plugin_sql"));
        assertEquals(1.0, heldBack("pending_egress"));
    }

    @Test
    void pendingSegmentsStillCountTowardTheAuditWindow() {
        // The window's meaning is unchanged: the most recent N below-checkpoint segments are
        // retained, whatever their state. A pending segment does not shield an older processed one
        // from being pruned — held-back segments are retained on top of the window, they do not
        // re-shape it.
        checkpointAt(20L);
        ChangelogSegment oldestProcessed = processedSegment(1L, 5L);
        ChangelogSegment pendingSql = segment(6L, 10L);
        pendingSql.markEgressed();
        ChangelogSegment newestPending = segment(11L, 15L); // inside the window of 1
        when(segmentRepository.findBySiteIdOrderByFirstSeq(SITE))
                .thenReturn(List.of(oldestProcessed, pendingSql, newestPending));

        int pruned = service(1).prune(SITE);

        assertEquals(1, pruned, "the oldest processed segment is pruned as before");
        verify(segmentStorage).delete(oldestProcessed.getS3Key());
        verify(segmentRepository).deleteById(oldestProcessed.getId());
        verify(segmentRepository, never()).deleteById(pendingSql.getId());
        // Only the segment the window would have pruned counts as held back; the newest one is
        // retained by the window itself, so it is not part of the backlog signal.
        assertEquals(1.0, heldBack("pending_plugin_sql"));
        assertEquals(0.0, heldBack("pending_egress"));
    }

    @Test
    void registersHeldBackSeriesAtZeroSoAnAlertCanPredateTheFirstOccurrence() {
        // The DeltaMetrics constructor registers both reason series; nothing has to be held back
        // first — the delta.checkpoint.builds.aborted treatment (#153).
        assertEquals(0.0, heldBack("pending_plugin_sql"));
        assertEquals(0.0, heldBack("pending_egress"));
    }

    @Test
    void doesNothingForASiteWithoutACheckpoint() {
        checkpointAt(0L);

        int pruned = service(0).prune(SITE);

        assertEquals(0, pruned);
        verify(segmentRepository, never()).findBySiteIdOrderByFirstSeq(any());
    }
}
