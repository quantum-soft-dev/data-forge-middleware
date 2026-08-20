package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.application.DeltaSyncStateService.SyncStateView;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository.PrunableSegmentView;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
 * site per pass. Processed segments are pruned exactly as before — through a single-statement
 * conditional delete, so a reinit re-pending a row between the read and the delete cannot have its
 * fresh queue entry destroyed (the TOCTOU the #212 review closed) — and segments inside the audit
 * window are retained by the window, not counted as held back.</p>
 */
@ExtendWith(MockitoExtension.class)
class ChangelogRetentionServiceTest {

    private static final UUID SITE = UUID.randomUUID();
    private static final String HELD_BACK_METER = "delta.retention.segments.held-back";
    private static final LocalDateTime DONE = LocalDateTime.of(2026, 8, 1, 0, 0);

    @Mock
    private ChangelogSegmentRepository segmentRepository;
    @Mock
    private S3FileStorageService objectDeleter;
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
        return new ChangelogRetentionService(segmentRepository, objectDeleter, syncStateService,
                metrics, auditWindowSegments);
    }

    private void checkpointAt(long seq) {
        when(syncStateService.getSyncState(SITE))
                .thenReturn(new SyncStateView(seq, seq, 1, false, false, 0L, 0L));
    }

    private record View(UUID id, String key, LocalDateTime pluginSqlAt, LocalDateTime egressAt)
            implements PrunableSegmentView {
        @Override
        public UUID getId() {
            return id;
        }

        @Override
        public String getS3Key() {
            return key;
        }

        @Override
        public LocalDateTime getPluginSqlAt() {
            return pluginSqlAt;
        }

        @Override
        public LocalDateTime getEgressAt() {
            return egressAt;
        }
    }

    private static View processed(String key) {
        return new View(UUID.randomUUID(), key, DONE, DONE);
    }

    private static View pendingPluginSql(String key) {
        return new View(UUID.randomUUID(), key, null, DONE);
    }

    private static View pendingEgress(String key) {
        return new View(UUID.randomUUID(), key, DONE, null);
    }

    private static View pendingBoth(String key) {
        return new View(UUID.randomUUID(), key, null, null);
    }

    private void belowCheckpoint(long checkpointSeq, View... views) {
        when(segmentRepository.findBelowCheckpointBySiteId(SITE, checkpointSeq))
                .thenReturn(List.of(views));
    }

    private void deleteSucceeds() {
        when(segmentRepository.deleteByIdIfProcessed(any())).thenReturn(1);
        when(objectDeleter.deleteObjects(anyList()))
                .thenReturn(new S3FileStorageService.DeleteObjectsResult(1, List.of()));
    }

    private double heldBack(String reason) {
        return registry.get(HELD_BACK_METER).tag("reason", reason).counter().count();
    }

    @Test
    void prunesProcessedSegmentsBelowCheckpointAsBefore() {
        checkpointAt(10L);
        View processed = processed("delta/s/1.pb.gz");
        belowCheckpoint(10L, processed);
        deleteSucceeds();

        int pruned = service(0).prune(SITE);

        assertEquals(1, pruned, "a fully processed below-checkpoint segment is pruned");
        verify(segmentRepository).deleteByIdIfProcessed(processed.id());
        // One batched DeleteObjects call carrying exactly the keys whose row delete succeeded —
        // not one round trip per object (the #212 review's efficiency finding).
        verify(objectDeleter).deleteObjects(List.of(processed.key()));
        assertEquals(0.0, heldBack("pending_plugin_sql"), "nothing was held back");
        assertEquals(0.0, heldBack("pending_egress"), "nothing was held back");
    }

    @Test
    void holdsBackSegmentWhosePluginSqlIsPending() {
        checkpointAt(10L);
        belowCheckpoint(10L, pendingPluginSql("delta/s/1.pb.gz"));

        int pruned = service(0).prune(SITE);

        assertEquals(0, pruned, "pending work is not prunable");
        verify(segmentRepository, never()).deleteByIdIfProcessed(any());
        verify(objectDeleter, never()).deleteObjects(anyList());
        assertEquals(1.0, heldBack("pending_plugin_sql"));
        assertEquals(0.0, heldBack("pending_egress"));
    }

    @Test
    void holdsBackSegmentWhoseEgressIsPending() {
        checkpointAt(10L);
        belowCheckpoint(10L, pendingEgress("delta/s/1.pb.gz"));

        int pruned = service(0).prune(SITE);

        assertEquals(0, pruned, "pending work is not prunable");
        verify(segmentRepository, never()).deleteByIdIfProcessed(any());
        verify(objectDeleter, never()).deleteObjects(anyList());
        assertEquals(0.0, heldBack("pending_plugin_sql"));
        assertEquals(1.0, heldBack("pending_egress"));
    }

    @Test
    void countsASegmentPendingBothOnBothSeries() {
        // Each series independently answers "is this queue stalling retention", so a segment owing
        // both moves both — the sum over reasons can exceed the number of held-back segments, the
        // same per-consumer honesty as delta.parquet.unrepresentable-decimals (#215).
        checkpointAt(10L);
        belowCheckpoint(10L, pendingBoth("delta/s/1.pb.gz"));

        int pruned = service(0).prune(SITE);

        assertEquals(0, pruned);
        verify(segmentRepository, never()).deleteByIdIfProcessed(any());
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
        View oldestProcessed = processed("delta/s/1.pb.gz");
        View pendingSql = pendingPluginSql("delta/s/6.pb.gz");
        View newestPending = pendingBoth("delta/s/11.pb.gz"); // inside the window of 1
        belowCheckpoint(20L, oldestProcessed, pendingSql, newestPending);
        deleteSucceeds();

        int pruned = service(1).prune(SITE);

        assertEquals(1, pruned, "the oldest processed segment is pruned as before");
        verify(segmentRepository).deleteByIdIfProcessed(oldestProcessed.id());
        verify(segmentRepository, never()).deleteByIdIfProcessed(pendingSql.id());
        verify(objectDeleter).deleteObjects(List.of(oldestProcessed.key()));
        // Only the segment the window would have pruned counts as held back; the newest one is
        // retained by the window itself, so it is not part of the backlog signal.
        assertEquals(1.0, heldBack("pending_plugin_sql"));
        assertEquals(0.0, heldBack("pending_egress"));
    }

    @Test
    void refusedConditionalDeleteKeepsTheObjectAndCountsTheRePendedRow() {
        // The TOCTOU the review closed: the projection read the row as processed, then a reinit
        // committed and re-NULLed plugin_sql_at (clearPluginSqlBySiteId is site-wide). The
        // conditional DELETE carries the marker predicate, so it refuses — and the S3 object must
        // then survive too, because it belongs to a row that is a live queue entry again.
        checkpointAt(10L);
        View racedOver = processed("delta/s/1.pb.gz");
        belowCheckpoint(10L, racedOver);
        when(segmentRepository.deleteByIdIfProcessed(racedOver.id())).thenReturn(0);
        ChangelogSegment rePended = ChangelogSegment.create(racedOver.id(), SITE, UUID.randomUUID(),
                1L, 5L, 5L, "hash", racedOver.key(), "DELTA", null);
        rePended.markEgressed(); // plugin SQL re-pended, egress still done
        when(segmentRepository.findById(racedOver.id())).thenReturn(Optional.of(rePended));

        int pruned = service(0).prune(SITE);

        assertEquals(0, pruned, "a row the conditional delete refused is not pruned");
        verify(objectDeleter, never()).deleteObjects(anyList());
        assertEquals(1.0, heldBack("pending_plugin_sql"),
                "the re-pended row is counted by what it says now");
        assertEquals(0.0, heldBack("pending_egress"));
    }

    @Test
    void aRowThatVanishedBetweenReadAndDeleteCountsNowhere() {
        checkpointAt(10L);
        View vanished = processed("delta/s/1.pb.gz");
        belowCheckpoint(10L, vanished);
        when(segmentRepository.deleteByIdIfProcessed(vanished.id())).thenReturn(0);
        when(segmentRepository.findById(vanished.id())).thenReturn(Optional.empty());

        int pruned = service(0).prune(SITE);

        assertEquals(0, pruned);
        verify(objectDeleter, never()).deleteObjects(anyList());
        assertEquals(0.0, heldBack("pending_plugin_sql"));
        assertEquals(0.0, heldBack("pending_egress"));
    }

    @Test
    void doesNothingForASiteWithoutACheckpoint() {
        checkpointAt(0L);

        int pruned = service(0).prune(SITE);

        assertEquals(0, pruned);
        verify(segmentRepository, never()).findBelowCheckpointBySiteId(any(), org.mockito.ArgumentMatchers.anyLong());
    }
}
