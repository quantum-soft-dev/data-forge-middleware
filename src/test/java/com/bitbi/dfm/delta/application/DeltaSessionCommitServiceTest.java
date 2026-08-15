package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * #9 — the commit service runs persist + advance-watermark + complete-batch as one ordered unit
 * (atomic via {@link DeltaSessionCommitTransaction}), writes no segment for an empty session, and
 * wakes the egress worker after a segment-producing commit (T8.4; outside a transaction the wake is
 * immediate).
 *
 * <p>Issue #147 adds the ordering that matters for locks: the segment's {@code PutObject} runs
 * <em>before</em> the transaction is opened, so no database lock — least of all the
 * {@code site_sync_state} row {@code DeltaRebaselineService.reset} takes first — is held across
 * the upload.</p>
 */
class DeltaSessionCommitServiceTest {

    private static final UUID SITE = UUID.randomUUID();
    private static final UUID BATCH = UUID.randomUUID();

    private final ChangelogSegmentService segmentService = mock(ChangelogSegmentService.class);
    private final DeltaSyncStateService syncStateService = mock(DeltaSyncStateService.class);
    private final BatchLifecycleService batchLifecycleService = mock(BatchLifecycleService.class);
    private final DeltaEgressWorker egressWorker = mock(DeltaEgressWorker.class);
    private final DeltaRebaselineService rebaselineService = mock(DeltaRebaselineService.class);
    private final DeltaSessionCommitTransaction transaction =
            new DeltaSessionCommitTransaction(segmentService, syncStateService, batchLifecycleService,
                    egressWorker, rebaselineService);
    private final DeltaSessionCommitService commit =
            new DeltaSessionCommitService(segmentService, transaction);

    @Test
    void persistsThenAdvancesThenCompletes() {
        PreparedSegment prepared = prepared(1L, 1L);
        stubUpload(prepared);
        stubRow("delta/site/segments/b.pb.gz");
        List<ChangeRecord> records = List.of(ChangeRecord.newBuilder().setSeq(1L).build());

        String key = commit.commit(SITE, BATCH, "DELTA", 1L, 1L, records);

        assertEquals("delta/site/segments/b.pb.gz", key);
        InOrder order = inOrder(segmentService, syncStateService, batchLifecycleService);
        order.verify(segmentService).prepare(SITE, BATCH, "DELTA", 1L, records);
        order.verify(segmentService).persistPrepared(prepared);
        order.verify(syncStateService).advanceWatermark(SITE, 1L);
        order.verify(batchLifecycleService).completeBatch(BATCH);
    }

    @Test
    void theSegmentIsUploadedBeforeAnyDatabaseWorkBegins() {
        // Issue #147. The upload is the slow part of the commit; running it first means the
        // transaction that follows only ever waits on statements.
        PreparedSegment prepared = prepared(1L, 1L);
        stubUpload(prepared);
        stubRow("delta/site/segments/b.pb.gz");

        commit.commit(SITE, BATCH, "DELTA", 1L, 1L,
                List.of(ChangeRecord.newBuilder().setSeq(1L).build()));

        InOrder order = inOrder(segmentService, syncStateService, batchLifecycleService);
        order.verify(segmentService).prepare(any(), any(), any(), anyLong(), any());
        order.verify(segmentService).persistPrepared(any());
        order.verify(syncStateService).advanceWatermark(any(), anyLong());
        order.verify(batchLifecycleService).completeBatch(any());
    }

    @Test
    void theSnapshotTailIsUploadedBeforeTheRebaselineTakesTheSiteRowLock() {
        // Issue #147, the case that motivated it: reset() takes the site_sync_state row lock as its
        // first statement (issue #142) and holds it for the rest of the transaction. With the
        // PutObject ahead of it, that mutex is no longer held across a network upload.
        PreparedSegment prepared = prepared(200L, 200L);
        stubUpload(prepared);
        stubRow("delta/site/segments/new.pb.gz");

        commit.commit(SITE, BATCH, "FULL_SNAPSHOT", 200L, 200L,
                List.of(ChangeRecord.newBuilder().setSeq(200L).build()), true);

        InOrder order = inOrder(segmentService, rebaselineService);
        order.verify(segmentService).prepare(SITE, BATCH, "FULL_SNAPSHOT", 200L,
                List.of(ChangeRecord.newBuilder().setSeq(200L).build()));
        order.verify(rebaselineService).reset(SITE, 200L);
        order.verify(segmentService).persistPrepared(prepared);
    }

    @Test
    void theOrchestratorOpensNoTransactionOfItsOwn() {
        // Structural guard for the above: an @Transactional on this class would put the upload back
        // inside the transaction, and only an integration test would notice.
        assertFalse(DeltaSessionCommitService.class.isAnnotationPresent(Transactional.class),
                "DeltaSessionCommitService must stay non-transactional (issue #147)");
        for (Method method : DeltaSessionCommitService.class.getDeclaredMethods()) {
            assertNull(method.getAnnotation(Transactional.class),
                    "no transaction may wrap the segment upload: " + method.getName());
        }
    }

    @Test
    void wakesEgressWorkerAfterSegmentProducingCommit() {
        stubUpload(prepared(1L, 1L));
        stubRow("delta/site/segments/b.pb.gz");

        commit.commit(SITE, BATCH, "DELTA", 1L, 1L,
                List.of(ChangeRecord.newBuilder().setSeq(1L).build()));

        verify(egressWorker).wake();
    }

    @Test
    void emptySessionWritesNoSegmentButStillCompletesBatch() {
        String key = commit.commit(SITE, BATCH, "DELTA", 1L, 0L, List.of());

        assertEquals("", key);
        verify(segmentService, never()).prepare(any(), any(), any(), anyLong(), any());
        verify(segmentService, never()).persistPrepared(any());
        verify(syncStateService).advanceWatermark(SITE, 0L);
        verify(batchLifecycleService).completeBatch(BATCH);
        verify(egressWorker, never()).wake();
    }

    @Test
    void rebaselineCommitResetsOldBaselineBeforePersistingNewSegment() {
        // The FULL_SNAPSHOT reset must run inside the commit transaction, BEFORE the new snapshot
        // segment row is written (reset deletes all prior segments) — so the old baseline is only
        // destroyed once the new one durably commits (review r4).
        PreparedSegment prepared = prepared(200L, 200L);
        stubUpload(prepared);
        stubRow("delta/site/segments/new.pb.gz");
        List<ChangeRecord> records = List.of(ChangeRecord.newBuilder().setSeq(200L).build());

        commit.commit(SITE, BATCH, "FULL_SNAPSHOT", 200L, 200L, records, true);

        InOrder order = inOrder(rebaselineService, segmentService, syncStateService, batchLifecycleService);
        order.verify(rebaselineService).reset(SITE, 200L);
        order.verify(segmentService).persistPrepared(prepared);
        order.verify(syncStateService).advanceWatermark(SITE, 200L);
        order.verify(batchLifecycleService).completeBatch(BATCH);
    }

    @Test
    void commitSegmentPersistsAndAdvancesWatermarkWithoutCompletingBatch() {
        // 029: a mid-session seal is a durability event, not a batch boundary — the session's
        // single batch must stay IN_PROGRESS across N seals.
        PreparedSegment prepared = prepared(1L, 100L);
        stubUpload(prepared);
        stubRow("delta/site/segments/s1.pb.gz");
        List<ChangeRecord> records = List.of(ChangeRecord.newBuilder().setSeq(100L).build());

        String key = commit.commitSegment(SITE, BATCH, "CONTINUOUS", 1L, 100L, records);

        assertEquals("delta/site/segments/s1.pb.gz", key);
        InOrder order = inOrder(segmentService, syncStateService);
        order.verify(segmentService).prepare(SITE, BATCH, "CONTINUOUS", 1L, records);
        order.verify(segmentService).persistPrepared(prepared);
        order.verify(syncStateService).advanceWatermark(SITE, 100L);
        verify(batchLifecycleService, never()).completeBatch(any());
        verify(batchLifecycleService, never()).startBatch(any(), any(), any());
        verify(egressWorker).wake();
    }

    @Test
    void commitSegmentWithEmptyRecordsIsNoop() {
        String key = commit.commitSegment(SITE, BATCH, "CONTINUOUS", 5L, 4L, List.of());

        assertEquals("", key);
        verify(segmentService, never()).prepare(any(), any(), any(), anyLong(), any());
        verify(syncStateService, never()).advanceWatermark(any(), anyLong());
        verify(batchLifecycleService, never()).completeBatch(any());
        verify(egressWorker, never()).wake();
    }

    @Test
    void provisionalSealPersistsWithoutPublishingAnything() {
        // 033: a mid-snapshot seal of a re-baseline is durable but invisible — no watermark move
        // (GetSyncState must keep reporting the pre-snapshot position so a reconnect restarts the
        // snapshot cleanly), no egress wake, no batch completion.
        PreparedSegment prepared = prepared(1L, 500L);
        stubUpload(prepared);
        ChangelogSegment segment = mock(ChangelogSegment.class);
        when(segment.getS3Key()).thenReturn("delta/site/segments/snap1.pb.gz");
        when(segmentService.persistPreparedProvisional(any())).thenReturn(segment);
        List<ChangeRecord> records = List.of(ChangeRecord.newBuilder().setSeq(500L).build());

        String key = commit.commitProvisionalSegment(SITE, BATCH, "FULL_SNAPSHOT", 1L, records);

        assertEquals("delta/site/segments/snap1.pb.gz", key);
        InOrder order = inOrder(segmentService);
        order.verify(segmentService).prepare(SITE, BATCH, "FULL_SNAPSHOT", 1L, records);
        order.verify(segmentService).persistPreparedProvisional(prepared);
        verify(segmentService, never()).persistPrepared(any());
        verify(syncStateService, never()).advanceWatermark(any(), anyLong());
        verify(egressWorker, never()).wake();
        verify(batchLifecycleService, never()).completeBatch(any());
    }

    @Test
    void provisionalSealWithEmptyRecordsIsNoop() {
        assertEquals("", commit.commitProvisionalSegment(SITE, BATCH, "FULL_SNAPSHOT", 5L, List.of()));

        verify(segmentService, never()).prepare(any(), any(), any(), anyLong(), any());
        verify(segmentService, never()).persistPreparedProvisional(any());
        verify(syncStateService, never()).advanceWatermark(any(), anyLong());
    }

    @Test
    void segmentedRebaselineResetsThenPersistsTailThenPublishesTheWholeSnapshot() {
        // The flip must happen after the reset (which discards the old baseline) and before the
        // watermark advance, all in one transaction: readers go from the entire old baseline to the
        // entire new one with nothing in between.
        PreparedSegment prepared = prepared(900L, 900L);
        stubUpload(prepared);
        stubRow("delta/site/segments/tail.pb.gz");
        List<ChangeRecord> records = List.of(ChangeRecord.newBuilder().setSeq(900L).build());

        commit.commit(SITE, BATCH, "FULL_SNAPSHOT", 900L, 900L, records, true, 100L);

        InOrder order = inOrder(rebaselineService, segmentService, syncStateService, batchLifecycleService);
        order.verify(rebaselineService).reset(SITE, 100L); // the session's first seq, not the tail's
        order.verify(segmentService).persistPrepared(prepared);
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
        verify(segmentService, never()).prepare(any(), any(), any(), anyLong(), any());
        verify(segmentService, never()).persistPrepared(any());
        verify(segmentService).publishProvisional(BATCH);
        verify(batchLifecycleService).completeBatch(BATCH);
    }

    @Test
    void nonRebaselineCommitNeverPublishesProvisionalSegments() {
        stubUpload(prepared(1L, 1L));
        stubRow("delta/site/segments/b.pb.gz");

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
        stubUpload(prepared(1L, 1L));
        stubRow("delta/site/segments/b.pb.gz");

        commit.commit(SITE, BATCH, "DELTA", 1L, 1L, List.of(ChangeRecord.newBuilder().setSeq(1L).build()));

        verify(rebaselineService, never()).reset(any(), anyLong());
    }

    private static PreparedSegment prepared(long firstSeq, long lastSeq) {
        return new PreparedSegment(UUID.randomUUID(), SITE, BATCH, "DELTA", firstSeq, lastSeq, 1, "hash",
                "delta/site/segments/uploaded.pb.gz", Map.of());
    }

    private void stubUpload(PreparedSegment prepared) {
        when(segmentService.prepare(any(), any(), any(), anyLong(), any())).thenReturn(prepared);
    }

    private void stubRow(String s3Key) {
        ChangelogSegment segment = mock(ChangelogSegment.class);
        when(segment.getS3Key()).thenReturn(s3Key);
        when(segmentService.persistPrepared(any())).thenReturn(segment);
    }
}
