package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * #9 — the commit service runs persist + advance-watermark + complete-batch as one ordered unit
 * (atomic via @Transactional), writes no segment for an empty session, and wakes the egress
 * worker after a segment-producing commit (T8.4; outside a transaction the wake is immediate).
 */
class DeltaSessionCommitServiceTest {

    private static final UUID SITE = UUID.randomUUID();
    private static final UUID BATCH = UUID.randomUUID();

    private final ChangelogSegmentService segmentService = mock(ChangelogSegmentService.class);
    private final DeltaSyncStateService syncStateService = mock(DeltaSyncStateService.class);
    private final BatchLifecycleService batchLifecycleService = mock(BatchLifecycleService.class);
    private final DeltaEgressWorker egressWorker = mock(DeltaEgressWorker.class);
    private final DeltaRebaselineService rebaselineService = mock(DeltaRebaselineService.class);
    private final DeltaSessionCommitService commit =
            new DeltaSessionCommitService(segmentService, syncStateService, batchLifecycleService,
                    egressWorker, rebaselineService);

    @Test
    void persistsThenAdvancesThenCompletes() {
        ChangelogSegment segment = mock(ChangelogSegment.class);
        when(segment.getS3Key()).thenReturn("delta/site/segments/b.pb.gz");
        when(segmentService.persist(any(), any(), any(), anyLong(), any())).thenReturn(segment);
        List<ChangeRecord> records = List.of(ChangeRecord.newBuilder().setSeq(1L).build());

        String key = commit.commit(SITE, BATCH, "DELTA", 1L, 1L, records);

        assertEquals("delta/site/segments/b.pb.gz", key);
        InOrder order = inOrder(segmentService, syncStateService, batchLifecycleService);
        order.verify(segmentService).persist(SITE, BATCH, "DELTA", 1L, records);
        order.verify(syncStateService).advanceWatermark(SITE, 1L);
        order.verify(batchLifecycleService).completeBatch(BATCH);
    }

    @Test
    void wakesEgressWorkerAfterSegmentProducingCommit() {
        ChangelogSegment segment = mock(ChangelogSegment.class);
        when(segment.getS3Key()).thenReturn("delta/site/segments/b.pb.gz");
        when(segmentService.persist(any(), any(), any(), anyLong(), any())).thenReturn(segment);

        commit.commit(SITE, BATCH, "DELTA", 1L, 1L,
                List.of(ChangeRecord.newBuilder().setSeq(1L).build()));

        verify(egressWorker).wake();
    }

    @Test
    void emptySessionWritesNoSegmentButStillCompletesBatch() {
        String key = commit.commit(SITE, BATCH, "DELTA", 1L, 0L, List.of());

        assertEquals("", key);
        verify(segmentService, never()).persist(any(), any(), any(), anyLong(), any());
        verify(syncStateService).advanceWatermark(SITE, 0L);
        verify(batchLifecycleService).completeBatch(BATCH);
        verify(egressWorker, never()).wake();
    }

    @Test
    void rebaselineCommitResetsOldBaselineBeforePersistingNewSegment() {
        // The FULL_SNAPSHOT reset must run inside the commit transaction, BEFORE the new snapshot
        // segment is persisted (reset deletes all prior segments) — so the old baseline is only
        // destroyed once the new one durably commits (review r4).
        ChangelogSegment segment = mock(ChangelogSegment.class);
        when(segment.getS3Key()).thenReturn("delta/site/segments/new.pb.gz");
        when(segmentService.persist(any(), any(), any(), anyLong(), any())).thenReturn(segment);
        List<ChangeRecord> records = List.of(ChangeRecord.newBuilder().setSeq(200L).build());

        commit.commit(SITE, BATCH, "FULL_SNAPSHOT", 200L, 200L, records, true);

        InOrder order = inOrder(rebaselineService, segmentService, syncStateService, batchLifecycleService);
        order.verify(rebaselineService).reset(SITE, 200L);
        order.verify(segmentService).persist(SITE, BATCH, "FULL_SNAPSHOT", 200L, records);
        order.verify(syncStateService).advanceWatermark(SITE, 200L);
        order.verify(batchLifecycleService).completeBatch(BATCH);
    }

    @Test
    void commitSegmentPersistsAndAdvancesWatermarkWithoutCompletingBatch() {
        // 029: a mid-session seal is a durability event, not a batch boundary — the session's
        // single batch must stay IN_PROGRESS across N seals.
        ChangelogSegment segment = mock(ChangelogSegment.class);
        when(segment.getS3Key()).thenReturn("delta/site/segments/s1.pb.gz");
        when(segmentService.persist(any(), any(), any(), anyLong(), any())).thenReturn(segment);
        List<ChangeRecord> records = List.of(ChangeRecord.newBuilder().setSeq(100L).build());

        String key = commit.commitSegment(SITE, BATCH, "CONTINUOUS", 1L, 100L, records);

        assertEquals("delta/site/segments/s1.pb.gz", key);
        InOrder order = inOrder(segmentService, syncStateService);
        order.verify(segmentService).persist(SITE, BATCH, "CONTINUOUS", 1L, records);
        order.verify(syncStateService).advanceWatermark(SITE, 100L);
        verify(batchLifecycleService, never()).completeBatch(any());
        verify(batchLifecycleService, never()).startBatch(any(), any());
        verify(egressWorker).wake();
    }

    @Test
    void commitSegmentWithEmptyRecordsIsNoop() {
        String key = commit.commitSegment(SITE, BATCH, "CONTINUOUS", 5L, 4L, List.of());

        assertEquals("", key);
        verify(segmentService, never()).persist(any(), any(), any(), anyLong(), any());
        verify(syncStateService, never()).advanceWatermark(any(), anyLong());
        verify(batchLifecycleService, never()).completeBatch(any());
        verify(egressWorker, never()).wake();
    }

    @Test
    void provisionalSealPersistsWithoutPublishingAnything() {
        // 033: a mid-snapshot seal of a re-baseline is durable but invisible — no watermark move
        // (GetSyncState must keep reporting the pre-snapshot position so a reconnect restarts the
        // snapshot cleanly), no egress wake, no batch completion.
        ChangelogSegment segment = mock(ChangelogSegment.class);
        when(segment.getS3Key()).thenReturn("delta/site/segments/snap1.pb.gz");
        when(segmentService.persistProvisional(any(), any(), any(), anyLong(), any())).thenReturn(segment);
        List<ChangeRecord> records = List.of(ChangeRecord.newBuilder().setSeq(500L).build());

        String key = commit.commitProvisionalSegment(SITE, BATCH, "FULL_SNAPSHOT", 1L, records);

        assertEquals("delta/site/segments/snap1.pb.gz", key);
        verify(segmentService).persistProvisional(SITE, BATCH, "FULL_SNAPSHOT", 1L, records);
        verify(segmentService, never()).persist(any(), any(), any(), anyLong(), any());
        verify(syncStateService, never()).advanceWatermark(any(), anyLong());
        verify(egressWorker, never()).wake();
        verify(batchLifecycleService, never()).completeBatch(any());
    }

    @Test
    void provisionalSealWithEmptyRecordsIsNoop() {
        assertEquals("", commit.commitProvisionalSegment(SITE, BATCH, "FULL_SNAPSHOT", 5L, List.of()));

        verify(segmentService, never()).persistProvisional(any(), any(), any(), anyLong(), any());
        verify(syncStateService, never()).advanceWatermark(any(), anyLong());
    }

    @Test
    void segmentedRebaselineResetsThenPersistsTailThenPublishesTheWholeSnapshot() {
        // The flip must happen after the reset (which discards the old baseline) and before the
        // watermark advance, all in one transaction: readers go from the entire old baseline to the
        // entire new one with nothing in between.
        ChangelogSegment tail = mock(ChangelogSegment.class);
        when(tail.getS3Key()).thenReturn("delta/site/segments/tail.pb.gz");
        when(segmentService.persist(any(), any(), any(), anyLong(), any())).thenReturn(tail);
        List<ChangeRecord> records = List.of(ChangeRecord.newBuilder().setSeq(900L).build());

        commit.commit(SITE, BATCH, "FULL_SNAPSHOT", 900L, 900L, records, true, 100L);

        InOrder order = inOrder(rebaselineService, segmentService, syncStateService, batchLifecycleService);
        order.verify(rebaselineService).reset(SITE, 100L); // the session's first seq, not the tail's
        order.verify(segmentService).persist(SITE, BATCH, "FULL_SNAPSHOT", 900L, records);
        order.verify(segmentService).publishProvisional(BATCH);
        order.verify(syncStateService).advanceWatermark(SITE, 900L);
        order.verify(batchLifecycleService).completeBatch(BATCH);
    }

    @Test
    void segmentedRebaselinePublishesEvenWhenTheTailBufferIsEmpty() {
        // The snapshot's last records may land exactly on a seal boundary: SessionEnd then has
        // nothing left to persist, but the already-sealed segments still have to be published.
        commit.commit(SITE, BATCH, "FULL_SNAPSHOT", 901L, 900L, List.of(), true, 100L);

        verify(rebaselineService).reset(SITE, 100L);
        verify(segmentService, never()).persist(any(), any(), any(), anyLong(), any());
        verify(segmentService).publishProvisional(BATCH);
        verify(batchLifecycleService).completeBatch(BATCH);
    }

    @Test
    void nonRebaselineCommitNeverPublishesProvisionalSegments() {
        when(segmentService.persist(any(), any(), any(), anyLong(), any())).thenReturn(mock(ChangelogSegment.class));

        commit.commit(SITE, BATCH, "CONTINUOUS", 1L, 1L,
                List.of(ChangeRecord.newBuilder().setSeq(1L).build()));

        verify(segmentService, never()).publishProvisional(any());
    }

    @Test
    void resumeOntoAReplacementBatchMovesTheAlreadySealedSegments() {
        // 033 review: publication is batch-keyed and reset() cannot see provisional rows, so
        // segments sealed under a reaped batch would be neither published nor deleted — the snapshot
        // would commit a baseline missing everything streamed before the drop, with the watermark
        // advanced and the client told it succeeded.
        UUID reaped = UUID.randomUUID();
        when(segmentService.reassignProvisionalBatch(reaped, BATCH)).thenReturn(3);

        assertEquals(3, commit.reassignProvisionalSegments(reaped, BATCH));

        verify(segmentService).reassignProvisionalBatch(reaped, BATCH);
    }

    @Test
    void reassignIsANoOpWhenTheSessionKeptItsBatch() {
        assertEquals(0, commit.reassignProvisionalSegments(BATCH, BATCH));
        assertEquals(0, commit.reassignProvisionalSegments(null, BATCH));

        verify(segmentService, never()).reassignProvisionalBatch(any(), any());
    }

    @Test
    void nonRebaselineCommitDoesNotTouchTheBaseline() {
        when(segmentService.persist(any(), any(), any(), anyLong(), any())).thenReturn(mock(ChangelogSegment.class));
        commit.commit(SITE, BATCH, "DELTA", 1L, 1L, List.of(ChangeRecord.newBuilder().setSeq(1L).build()));
        verify(rebaselineService, never()).reset(any(), anyLong());
    }
}
