package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.delta.application.DeltaMetrics;
import com.bitbi.dfm.delta.application.DeltaSessionCommitService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.site.domain.TableSchema;
import com.bitbi.dfm.delta.grpc.v2.*;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * T1.4 — contract test for the bidirectional {@code StreamChanges} session: SessionStart opens a
 * batch and emits SessionOpened; SessionEnd completes the batch and emits SessionCommitted.
 */
class DeltaIngestionStreamChangesContractTest extends DeltaIngestionContractTestSupport {

    @Test
    void happyPathSessionOpensAndCommitsBatch() throws Exception {
        UUID batchId = UUID.randomUUID();
        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(batchId);
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(batch);

        List<ServerEvent> received = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        StreamObserver<ServerEvent> responses = new StreamObserver<>() {
            @Override
            public void onNext(ServerEvent event) {
                received.add(event);
            }

            @Override
            public void onError(Throwable t) {
                done.countDown();
            }

            @Override
            public void onCompleted() {
                done.countDown();
            }
        };

        StreamObserver<ClientEvent> request = asyncStub.streamChanges(responses);
        request.onNext(ClientEvent.newBuilder().setStart(
                SessionStart.newBuilder().setMode(SessionMode.FULL_SNAPSHOT).setFirstSeq(1).build()).build());
        request.onNext(ClientEvent.newBuilder().setChange(
                ChangeRecord.newBuilder().setTable("t").setOp(Op.INSERT).setSeq(1).build()).build());
        request.onNext(ClientEvent.newBuilder().setEnd(
                SessionEnd.newBuilder().setLastSeq(1)
                        .putPerTable("t", TableStats.newBuilder().setInserts(1).build())
                        .build()).build());
        request.onCompleted();

        assertTrue(done.await(5, TimeUnit.SECONDS), "stream did not complete");

        verify(batchLifecycle).startBatch(ACCOUNT, SITE);
        verify(batchLifecycle).completeBatch(batchId);

        assertEquals(2, received.size(), "expected SessionOpened then SessionCommitted");
        assertTrue(received.get(0).hasOpened());
        assertEquals(batchId.toString(), received.get(0).getOpened().getServerSessionId());
        assertEquals(0L, received.get(0).getOpened().getServerLastSeq());
        assertEquals(RecoveryAction.PROCEED, received.get(0).getOpened().getAction());
        assertTrue(received.get(1).hasCommitted());
        assertEquals(1L, received.get(1).getCommitted().getCommittedSeq());
    }

    @Test
    void secondConcurrentSessionRejectedWithActiveSessionExists() throws Exception {
        when(batchLifecycle.startBatch(ACCOUNT, SITE))
                .thenThrow(new BatchLifecycleService.ActiveBatchExistsException(
                        "Site already has an active batch"));

        List<ServerEvent> received = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        StreamObserver<ServerEvent> responses = new StreamObserver<>() {
            @Override
            public void onNext(ServerEvent event) {
                received.add(event);
            }

            @Override
            public void onError(Throwable t) {
                done.countDown();
            }

            @Override
            public void onCompleted() {
                done.countDown();
            }
        };

        StreamObserver<ClientEvent> request = asyncStub.streamChanges(responses);
        request.onNext(ClientEvent.newBuilder().setStart(
                SessionStart.newBuilder().setMode(SessionMode.DELTA).setFirstSeq(1).build()).build());

        assertTrue(done.await(5, TimeUnit.SECONDS), "stream did not complete");
        assertEquals(1, received.size());
        assertTrue(received.get(0).hasError());
        assertEquals(ErrorCode.ACTIVE_SESSION_EXISTS, received.get(0).getError().getCode());
        verify(batchLifecycle, never()).completeBatch(any());
    }

    /**
     * #83: the other three documented {@code startBatch} rejections used to escape the typed
     * protocol entirely — the generic {@code catch (RuntimeException)} in {@code onNext} turned them
     * into a bare {@code Status.INTERNAL} with no {@code ErrorCode}, so the client could not tell an
     * inactive site from a missing schema from a server crash.
     */
    @Test
    void inactiveSiteIsRejectedWithTypedUnauthorizedInsteadOfStatusInternal() throws Exception {
        when(batchLifecycle.startBatch(ACCOUNT, SITE))
                .thenThrow(new BatchLifecycleService.SiteInactiveException(
                        "Cannot start batch for inactive site. Please activate the site first."));

        List<ServerEvent> received = runSession(req -> req.onNext(start(SessionMode.DELTA, 1L)));

        assertEquals(1, received.size());
        assertTrue(received.get(0).hasError(), "an inactive site must produce a typed ServerError");
        assertEquals(ErrorCode.UNAUTHORIZED, received.get(0).getError().getCode());
        assertEquals(RecoveryAction.PROCEED, received.get(0).getError().getAction());
        assertTrue(received.get(0).getError().getMessage().contains("inactive site"),
                "the rejection must carry the reason the client can act on");
    }

    @Test
    void cdcSiteWithoutSchemaIsRejectedWithTypedSchemaMismatch() throws Exception {
        when(batchLifecycle.startBatch(ACCOUNT, SITE))
                .thenThrow(new BatchLifecycleService.SchemaRequiredException(
                        "Schema required for POSTGRES_CDC sites. Submit it through Delta gRPC SubmitSchema first."));

        List<ServerEvent> received = runSession(req -> req.onNext(start(SessionMode.DELTA, 1L)));

        assertEquals(1, received.size());
        assertTrue(received.get(0).hasError(), "a missing schema must produce a typed ServerError");
        assertEquals(ErrorCode.SCHEMA_MISMATCH, received.get(0).getError().getCode());
        assertEquals(RecoveryAction.PROCEED, received.get(0).getError().getAction(),
                "SubmitSchema then retry — a re-baseline would hit the same wall");
    }

    /**
     * A site can be hard-deleted while a stream is open — the token was validated once, at stream
     * open. {@code startBatch} rejects that before any of the other four, so leaving it unmapped
     * would have made the typed set inconsistent: the same mid-stream window is typed for a
     * deactivated site but not for a deleted one.
     */
    @Test
    void deletedSiteIsRejectedWithTypedUnauthorizedInsteadOfStatusInternal() throws Exception {
        when(batchLifecycle.startBatch(ACCOUNT, SITE))
                .thenThrow(new BatchLifecycleService.SiteNotFoundException("Site not found: " + SITE));

        List<ServerEvent> received = runSession(req -> req.onNext(start(SessionMode.DELTA, 1L)));

        assertEquals(1, received.size());
        assertTrue(received.get(0).hasError(), "a deleted site must produce a typed ServerError");
        assertEquals(ErrorCode.UNAUTHORIZED, received.get(0).getError().getCode());
        assertEquals(RecoveryAction.PROCEED, received.get(0).getError().getAction());
    }

    @Test
    void accountConcurrencyLimitIsRejectedWithTypedActiveSessionExists() throws Exception {
        when(batchLifecycle.startBatch(ACCOUNT, SITE))
                .thenThrow(new BatchLifecycleService.ConcurrentBatchLimitException(
                        "Account exceeded concurrent batch limit: 5/5"));

        List<ServerEvent> received = runSession(req -> req.onNext(start(SessionMode.DELTA, 1L)));

        assertEquals(1, received.size());
        assertTrue(received.get(0).hasError(), "the concurrency limit must produce a typed ServerError");
        assertEquals(ErrorCode.ACTIVE_SESSION_EXISTS, received.get(0).getError().getCode());
        assertEquals(RecoveryAction.PROCEED, received.get(0).getError().getAction());
    }

    @Test
    void deltaSessionWithSequenceGapRejected() throws Exception {
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.advanceWatermark(120L);
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.of(state));

        List<ServerEvent> received = runSession(req ->
                req.onNext(start(SessionMode.DELTA, 130L))); // expected first_seq=121

        assertEquals(1, received.size());
        assertTrue(received.get(0).hasError());
        assertEquals(ErrorCode.SEQUENCE_GAP, received.get(0).getError().getCode());
        assertEquals(RecoveryAction.NEED_REBASELINE, received.get(0).getError().getAction());
        verify(batchLifecycle, never()).startBatch(any(), any());
    }

    @Test
    void deltaReplayBelowWatermarkProceedsInsteadOfForcingRebaseline() throws Exception {
        // Lost SessionCommitted ack: the client retries a session whose records the server already
        // committed (first_seq <= watermark+1). A strict != check answered SEQUENCE_GAP/
        // NEED_REBASELINE — an expensive full snapshot for a lost ack. A replay must PROCEED; the
        // buffer swallows the duplicate seqs (review r4/D).
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.advanceWatermark(120L);
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.of(state));
        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(UUID.randomUUID());
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(batch);

        List<ServerEvent> received = runSession(req -> req.onNext(start(SessionMode.DELTA, 100L)));

        assertTrue(received.get(0).hasOpened(), "a replay below the watermark must open, not reject");
        assertEquals(RecoveryAction.PROCEED, received.get(0).getOpened().getAction());
        assertEquals(120L, received.get(0).getOpened().getServerLastSeq());
        assertTrue(received.stream().noneMatch(ServerEvent::hasError));
    }

    @Test
    void deltaSessionContiguousProceeds() throws Exception {
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.advanceWatermark(120L);
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.of(state));
        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(UUID.randomUUID());
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(batch);

        List<ServerEvent> received = runSession(req ->
                req.onNext(start(SessionMode.DELTA, 121L)));

        assertTrue(received.get(0).hasOpened());
        assertEquals(120L, received.get(0).getOpened().getServerLastSeq());
        verify(batchLifecycle).startBatch(ACCOUNT, SITE);
    }

    @Test
    void fullSnapshotStartsFromFirstSeqMinusOneButDefersResetUntilCommit() throws Exception {
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.advanceWatermark(120L); // stale watermark from a previous baseline
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.of(state));
        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(UUID.randomUUID());
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(batch);

        List<ServerEvent> received = runSession(req ->
                req.onNext(start(SessionMode.FULL_SNAPSHOT, 200L)));

        // The snapshot session reports firstSeq-1, but the destructive reset must NOT run at
        // SessionStart — it is deferred to commit so a mid-stream drop keeps the old baseline (r4).
        verify(rebaselineService, never()).reset(any(), anyLong());
        assertTrue(received.get(0).hasOpened());
        assertEquals(199L, received.get(0).getOpened().getServerLastSeq(),
                "snapshot session reports the reset watermark (firstSeq-1)");
    }

    @Test
    void fullSnapshotResetsOldBaselineOnlyWhenItCommits() throws Exception {
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.advanceWatermark(120L);
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.of(state));
        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(UUID.randomUUID());
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(batch);

        runSession(req -> {
            req.onNext(start(SessionMode.FULL_SNAPSHOT, 200L));
            req.onNext(change("t", Op.INSERT, 200L));
            req.onNext(ClientEvent.newBuilder().setEnd(SessionEnd.newBuilder().setLastSeq(200L)
                    .putPerTable("t", TableStats.newBuilder().setInserts(1).build()).build()).build());
        });

        // Reset runs as part of the commit (DeltaSessionCommitService wipes the old baseline in the
        // same transaction as the new snapshot segment persist).
        verify(rebaselineService).reset(SITE, 200L);
        verify(batchLifecycle).completeBatch(any());
    }

    @Test
    void fullSnapshotIgnoresSequenceGap() throws Exception {
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.advanceWatermark(120L);
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.of(state));
        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(UUID.randomUUID());
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(batch);

        List<ServerEvent> received = runSession(req ->
                req.onNext(start(SessionMode.FULL_SNAPSHOT, 1L))); // not contiguous, but allowed

        assertTrue(received.get(0).hasOpened());
        verify(batchLifecycle).startBatch(ACCOUNT, SITE);
    }

    @Test
    void reconciliationMismatchRejectsAndDoesNotCompleteBatch() throws Exception {
        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(UUID.randomUUID());
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(batch);

        List<ServerEvent> received = runSession(req -> {
            req.onNext(start(SessionMode.FULL_SNAPSHOT, 1L));
            req.onNext(ClientEvent.newBuilder().setChange(
                    ChangeRecord.newBuilder().setTable("t").setOp(Op.INSERT).setSeq(1L).build()).build());
            req.onNext(ClientEvent.newBuilder().setEnd(
                    SessionEnd.newBuilder().setLastSeq(1L)
                            .putPerTable("t", TableStats.newBuilder().setInserts(2).build()) // actual = 1
                            .build()).build());
        });

        ServerEvent last = received.get(received.size() - 1);
        assertTrue(last.hasError());
        assertEquals(ErrorCode.RECONCILIATION_FAILED, last.getError().getCode());
        verify(batchLifecycle, never()).completeBatch(any());
    }

    @Test
    void sessionSchemaVersionMismatchRejected() throws Exception {
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.recordSchemaVersion(2); // server holds schema v2
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.of(state));

        List<ServerEvent> received = runSession(req -> req.onNext(ClientEvent.newBuilder()
                .setStart(SessionStart.newBuilder()
                        .setMode(SessionMode.FULL_SNAPSHOT).setFirstSeq(1L).setSchemaVersion(1) // stale
                        .build())
                .build()));

        assertEquals(1, received.size());
        assertTrue(received.get(0).hasError());
        assertEquals(ErrorCode.SCHEMA_MISMATCH, received.get(0).getError().getCode());
        assertEquals(RecoveryAction.NEED_REBASELINE, received.get(0).getError().getAction());
        verify(batchLifecycle, never()).startBatch(any(), any());
    }

    @Test
    void emptySessionCompletesBatchWithoutPersistingSegment() throws Exception {
        // A no-op session must not persist a segment: doing so at first_seq=watermark+1 without
        // advancing the watermark would collide with the next session on UNIQUE(site_id,first_seq).
        UUID batchId = UUID.randomUUID();
        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(batchId);
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(batch);

        List<ServerEvent> received = runSession(req -> {
            req.onNext(start(SessionMode.DELTA, 1L));
            req.onNext(ClientEvent.newBuilder().setEnd(SessionEnd.newBuilder().setLastSeq(0L).build()).build());
        });

        ServerEvent last = received.get(received.size() - 1);
        assertTrue(last.hasCommitted(), "empty session still commits its batch");
        assertTrue(last.getCommitted().getSegmentS3Key().isEmpty(), "no segment for an empty session");
        verify(changelogSegmentService, never()).persist(any(), any(), any(), anyLong(), any());
        verify(batchLifecycle).completeBatch(batchId);
    }

    @Test
    void keylessTableUpdateRejectedWithSchemaMismatch() throws Exception {
        UUID batchId = UUID.randomUUID();
        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(batchId);
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(batch);
        // Table "t" has no primary/unique key -> keyless: UPDATE is not allowed.
        when(siteSchemaService.getTableSchemas(SITE)).thenReturn(Map.of(
                "t", new TableSchema(List.of(
                        new TableSchema.ColumnDefinition("a", "integer", false)),
                        List.of(), List.of())));

        List<ServerEvent> received = runSession(req -> {
            req.onNext(start(SessionMode.FULL_SNAPSHOT, 1L));
            req.onNext(change("t", Op.UPDATE, 1L)); // keyless UPDATE
        });

        ServerEvent last = received.get(received.size() - 1);
        assertTrue(last.hasError());
        assertEquals(ErrorCode.SCHEMA_MISMATCH, last.getError().getCode());
        verify(batchLifecycle).failBatch(batchId);
        verify(batchLifecycle, never()).completeBatch(any());
    }

    @Test
    void inStreamSequenceGapRejectsAndFailsBatch() throws Exception {
        UUID batchId = UUID.randomUUID();
        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(batchId);
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(batch);

        List<ServerEvent> received = runSession(req -> {
            req.onNext(start(SessionMode.FULL_SNAPSHOT, 1L));
            req.onNext(change("t", Op.INSERT, 1L));
            req.onNext(change("t", Op.INSERT, 3L)); // seq 2 skipped
            req.onNext(ClientEvent.newBuilder().setEnd(SessionEnd.newBuilder().setLastSeq(3L).build()).build());
        });

        ServerEvent last = received.get(received.size() - 1);
        assertTrue(last.hasError());
        assertEquals(ErrorCode.SEQUENCE_GAP, last.getError().getCode());
        assertEquals(RecoveryAction.NEED_REBASELINE, last.getError().getAction());
        // The batch must be failed, not left active (which would block the site).
        verify(batchLifecycle).failBatch(batchId);
        verify(batchLifecycle, never()).completeBatch(any());
    }

    @Test
    void contentHashMismatchRejectsAndDoesNotCompleteBatch() throws Exception {
        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(UUID.randomUUID());
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(batch);

        List<ServerEvent> received = runSession(req -> {
            req.onNext(start(SessionMode.FULL_SNAPSHOT, 1L));
            req.onNext(change("t", Op.INSERT, 1L));
            req.onNext(ClientEvent.newBuilder().setEnd(
                    SessionEnd.newBuilder().setLastSeq(1L)
                            .putPerTable("t", TableStats.newBuilder().setInserts(1).build()) // counts OK
                            .setContentHash("deadbeef") // but integrity hash is wrong
                            .build()).build());
        });

        ServerEvent last = received.get(received.size() - 1);
        assertTrue(last.hasError());
        assertEquals(ErrorCode.RECONCILIATION_FAILED, last.getError().getCode());
        verify(batchLifecycle, never()).completeBatch(any());
    }

    @Test
    void sessionEndWithMismatchedLastSeqRejected() throws Exception {
        // SessionEnd.last_seq must equal the highest accepted seq. Counts and hash can match while a
        // wrong last_seq betrays a client watermark bug — reject rather than silently commit (P2, r4).
        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(UUID.randomUUID());
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(batch);

        List<ServerEvent> received = runSession(req -> {
            req.onNext(start(SessionMode.FULL_SNAPSHOT, 1L));
            req.onNext(change("t", Op.INSERT, 1L));
            req.onNext(ClientEvent.newBuilder().setEnd(
                    SessionEnd.newBuilder().setLastSeq(999L) // declared, but highest accepted is 1
                            .putPerTable("t", TableStats.newBuilder().setInserts(1).build())
                            .build()).build());
        });

        ServerEvent last = received.get(received.size() - 1);
        assertTrue(last.hasError());
        assertEquals(ErrorCode.RECONCILIATION_FAILED, last.getError().getCode());
        verify(batchLifecycle, never()).completeBatch(any());
    }

    @Test
    void resumesAfterMidSessionDropWithResumeFrom() throws Exception {
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.advanceWatermark(120L);
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.of(state));
        UUID batchId = UUID.randomUUID();
        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(batchId);
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(batch);
        // 030/T02: the batch is still live, so the resume re-attaches to it.
        when(batchLifecycle.isBatchInProgress(batchId)).thenReturn(true);

        // --- Session 1: open, stage 121 & 122, then drop mid-stream (no SessionEnd). ---
        List<ServerEvent> first = new CopyOnWriteArrayList<>();
        StreamObserver<ClientEvent> s1 = asyncStub.streamChanges(collect(first, new CountDownLatch(1)));
        s1.onNext(start(SessionMode.DELTA, 121L));
        s1.onNext(change("t", Op.INSERT, 121L));
        s1.onNext(change("t", Op.INSERT, 122L));
        s1.onError(new RuntimeException("transport drop"));

        assertTrue(first.get(0).hasOpened(), "session 1 opened");
        assertEquals(RecoveryAction.PROCEED, first.get(0).getOpened().getAction());

        // --- Session 2: reconnect from the client watermark → server replies RESUME_FROM 123. ---
        List<ServerEvent> second = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        StreamObserver<ClientEvent> s2 = asyncStub.streamChanges(collect(second, done));
        s2.onNext(start(SessionMode.DELTA, 121L));

        assertTrue(second.get(0).hasOpened(), "resume opens the session");
        assertEquals(RecoveryAction.RESUME_FROM, second.get(0).getOpened().getAction());
        assertEquals(123L, second.get(0).getOpened().getResumeFromSeq(), "resume from highest staged seq + 1");
        assertEquals(batchId.toString(), second.get(0).getOpened().getServerSessionId(),
                "re-attaches to the dropped session's batch");

        // Client replays from the resume point and ends the session.
        s2.onNext(change("t", Op.INSERT, 123L));
        s2.onNext(ClientEvent.newBuilder().setEnd(SessionEnd.newBuilder().setLastSeq(123L)
                .putPerTable("t", TableStats.newBuilder().setInserts(3).build()).build()).build());
        s2.onCompleted();

        assertTrue(done.await(5, TimeUnit.SECONDS), "resumed stream did not complete");

        ServerEvent committed = second.get(second.size() - 1);
        assertTrue(committed.hasCommitted(), "resumed session commits");
        assertEquals(123L, committed.getCommitted().getCommittedSeq());

        // One batch overall (started by session 1), completed once on resume; the committed segment
        // spans the staged + replayed records [121..123].
        verify(batchLifecycle, times(1)).startBatch(ACCOUNT, SITE);
        verify(batchLifecycle).completeBatch(batchId);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChangeRecord>> records = ArgumentCaptor.forClass(List.class);
        verify(changelogSegmentService).persist(eq(SITE), eq(batchId), eq("DELTA"), eq(121L), records.capture());
        assertEquals(3, records.getValue().size(), "segment spans staged + replayed records");
    }

    @Test
    void resumeOntoReapedBatchOpensFreshBatchAndKeepsStagedRecords() throws Exception {
        // 030/T02: a staged session can outlive its batch — the timeout sweeper reaps an
        // IN_PROGRESS batch into NOT_COMPLETED while the client is away. Re-attaching blindly used
        // to fail late, at the final commit. The resume now notices and opens a fresh batch,
        // carrying the staged buffer into it so nothing staged before the drop is lost.
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.advanceWatermark(120L);
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.of(state));
        UUID reapedBatchId = UUID.randomUUID();
        UUID freshBatchId = UUID.randomUUID();
        Batch reapedBatch = mockBatch(reapedBatchId);
        Batch freshBatch = mockBatch(freshBatchId);
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(reapedBatch, freshBatch);
        when(batchLifecycle.isBatchInProgress(any())).thenReturn(true);

        // --- Session 1: open, stage 121 & 122, then drop mid-stream (no SessionEnd). ---
        List<ServerEvent> first = new CopyOnWriteArrayList<>();
        StreamObserver<ClientEvent> s1 = asyncStub.streamChanges(collect(first, new CountDownLatch(1)));
        s1.onNext(start(SessionMode.DELTA, 121L));
        s1.onNext(change("t", Op.INSERT, 121L));
        s1.onNext(change("t", Op.INSERT, 122L));
        s1.onError(new RuntimeException("transport drop"));

        // --- The sweeper reaps the staged batch while the client is away. ---
        when(batchLifecycle.isBatchInProgress(reapedBatchId)).thenReturn(false);

        // --- Session 2: reconnect. The staged batch is terminal, so a fresh one is opened. ---
        List<ServerEvent> second = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        StreamObserver<ClientEvent> s2 = asyncStub.streamChanges(collect(second, done));
        s2.onNext(start(SessionMode.DELTA, 121L));

        assertTrue(second.get(0).hasOpened(), "resume still opens the session");
        assertEquals(RecoveryAction.RESUME_FROM, second.get(0).getOpened().getAction());
        assertEquals(123L, second.get(0).getOpened().getResumeFromSeq(),
                "staged records survive the batch swap — resume from highest staged seq + 1");
        assertEquals(freshBatchId.toString(), second.get(0).getOpened().getServerSessionId(),
                "the session runs under the fresh batch, not the reaped one");

        s2.onNext(change("t", Op.INSERT, 123L));
        s2.onNext(ClientEvent.newBuilder().setEnd(SessionEnd.newBuilder().setLastSeq(123L)
                .putPerTable("t", TableStats.newBuilder().setInserts(3).build()).build()).build());
        s2.onCompleted();

        assertTrue(done.await(5, TimeUnit.SECONDS), "resumed stream did not complete");
        ServerEvent committed = second.get(second.size() - 1);
        assertTrue(committed.hasCommitted(), "resumed session commits under the fresh batch");
        assertEquals(123L, committed.getCommitted().getCommittedSeq());

        verify(batchLifecycle, times(2)).startBatch(ACCOUNT, SITE);
        verify(batchLifecycle).completeBatch(freshBatchId);
        verify(batchLifecycle, never()).completeBatch(reapedBatchId);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChangeRecord>> records = ArgumentCaptor.forClass(List.class);
        verify(changelogSegmentService)
                .persist(eq(SITE), eq(freshBatchId), eq("DELTA"), eq(121L), records.capture());
        assertEquals(3, records.getValue().size(),
                "segment spans the staged + replayed records — no data lost to the batch swap");
    }

    @Test
    void resumeOntoReapedBatchSurfacesActiveSessionExistsWhenNoBatchCanOpen() throws Exception {
        // 030/T02: the fresh batch obeys "one active batch per site" like any other session start.
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.advanceWatermark(120L);
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.of(state));
        UUID reapedBatchId = UUID.randomUUID();
        Batch reapedBatch = mockBatch(reapedBatchId);
        when(batchLifecycle.startBatch(ACCOUNT, SITE))
                .thenReturn(reapedBatch)
                .thenThrow(new BatchLifecycleService.ActiveBatchExistsException(
                        "Site already has an active batch: " + SITE));
        when(batchLifecycle.isBatchInProgress(any())).thenReturn(true);

        List<ServerEvent> first = new CopyOnWriteArrayList<>();
        StreamObserver<ClientEvent> s1 = asyncStub.streamChanges(collect(first, new CountDownLatch(1)));
        s1.onNext(start(SessionMode.DELTA, 121L));
        s1.onNext(change("t", Op.INSERT, 121L));
        s1.onError(new RuntimeException("transport drop"));

        when(batchLifecycle.isBatchInProgress(reapedBatchId)).thenReturn(false);

        List<ServerEvent> second = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        StreamObserver<ClientEvent> s2 = asyncStub.streamChanges(collect(second, done));
        s2.onNext(start(SessionMode.DELTA, 121L));

        assertTrue(done.await(5, TimeUnit.SECONDS), "rejected resume did not complete");
        ServerEvent last = second.get(second.size() - 1);
        assertTrue(last.hasError(), "resume is rejected, not silently re-attached");
        assertEquals(ErrorCode.ACTIVE_SESSION_EXISTS, last.getError().getCode());
    }

    @Test
    void resumeOntoReapedBatchAlsoSurfacesTheSiteBecomingInactive() throws Exception {
        // #83: the resume path opens a replacement batch too, so it must map the same rejections.
        // A site deactivated while its session was parked used to blow up as Status.INTERNAL.
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.advanceWatermark(120L);
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.of(state));
        UUID reapedBatchId = UUID.randomUUID();
        Batch reapedBatch = mockBatch(reapedBatchId);
        when(batchLifecycle.startBatch(ACCOUNT, SITE))
                .thenReturn(reapedBatch)
                .thenThrow(new BatchLifecycleService.SiteInactiveException(
                        "Cannot start batch for inactive site. Please activate the site first."));
        when(batchLifecycle.isBatchInProgress(any())).thenReturn(true);

        StreamObserver<ClientEvent> s1 = asyncStub.streamChanges(
                collect(new CopyOnWriteArrayList<>(), new CountDownLatch(1)));
        s1.onNext(start(SessionMode.DELTA, 121L));
        s1.onNext(change("t", Op.INSERT, 121L));
        s1.onError(new RuntimeException("transport drop"));

        when(batchLifecycle.isBatchInProgress(reapedBatchId)).thenReturn(false);

        List<ServerEvent> second = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        StreamObserver<ClientEvent> s2 = asyncStub.streamChanges(collect(second, done));
        s2.onNext(start(SessionMode.DELTA, 121L));

        assertTrue(done.await(5, TimeUnit.SECONDS), "rejected resume did not complete");
        ServerEvent last = second.get(second.size() - 1);
        assertTrue(last.hasError(), "resume onto an inactive site is rejected, not re-attached");
        assertEquals(ErrorCode.UNAUTHORIZED, last.getError().getCode());
    }

    @Test
    void resumedDeltaSessionNeverResetsTheBaseline() throws Exception {
        // 030/T05 REGRESSION SENTINEL. Re-baseline wipes every prior segment and checkpoint for the
        // site. If the resume path ever restores that intent on the wrong condition, ordinary delta
        // sessions — the overwhelming majority — would start destroying live baselines. This must
        // fail on any inversion of the rebaseline condition.
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.advanceWatermark(120L);
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.of(state));
        UUID batchId = UUID.randomUUID();
        Batch batch = mockBatch(batchId);
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(batch);
        when(batchLifecycle.isBatchInProgress(any())).thenReturn(true);

        StreamObserver<ClientEvent> s1 = asyncStub.streamChanges(
                collect(new CopyOnWriteArrayList<>(), new CountDownLatch(1)));
        s1.onNext(start(SessionMode.DELTA, 121L));
        s1.onNext(change("t", Op.INSERT, 121L));
        s1.onError(new RuntimeException("transport drop"));

        List<ServerEvent> second = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        StreamObserver<ClientEvent> s2 = asyncStub.streamChanges(collect(second, done));
        s2.onNext(start(SessionMode.DELTA, 121L));
        s2.onNext(change("t", Op.INSERT, 122L));
        s2.onNext(ClientEvent.newBuilder().setEnd(SessionEnd.newBuilder().setLastSeq(122L)
                .putPerTable("t", TableStats.newBuilder().setInserts(2).build()).build()).build());
        s2.onCompleted();

        assertTrue(done.await(5, TimeUnit.SECONDS), "resumed stream did not complete");
        assertTrue(second.get(second.size() - 1).hasCommitted(), "resumed delta session commits");
        verify(rebaselineService, never()).reset(any(), anyLong());
    }

    @Test
    void resumedFullSnapshotStillResetsTheBaseline() throws Exception {
        // 030/T05: a FULL_SNAPSHOT is not CONTINUOUS, so a mid-stream drop stages it for resume —
        // and the resume path used to return before the rebaseline flag was ever set, committing a
        // re-baseline as an ordinary delta and folding the new snapshot on top of stale data.
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.advanceWatermark(120L);
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.of(state));
        UUID batchId = UUID.randomUUID();
        Batch batch = mockBatch(batchId);
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(batch);
        when(batchLifecycle.isBatchInProgress(any())).thenReturn(true);

        StreamObserver<ClientEvent> s1 = asyncStub.streamChanges(
                collect(new CopyOnWriteArrayList<>(), new CountDownLatch(1)));
        s1.onNext(start(SessionMode.FULL_SNAPSHOT, 200L));
        s1.onNext(change("t", Op.INSERT, 200L));
        s1.onError(new RuntimeException("transport drop"));

        List<ServerEvent> second = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        StreamObserver<ClientEvent> s2 = asyncStub.streamChanges(collect(second, done));
        s2.onNext(start(SessionMode.DELTA, 200L)); // the resume handshake is always DELTA
        s2.onNext(change("t", Op.INSERT, 201L));
        s2.onNext(ClientEvent.newBuilder().setEnd(SessionEnd.newBuilder().setLastSeq(201L)
                .putPerTable("t", TableStats.newBuilder().setInserts(2).build()).build()).build());
        s2.onCompleted();

        assertTrue(done.await(5, TimeUnit.SECONDS), "resumed snapshot did not complete");
        assertTrue(second.get(second.size() - 1).hasCommitted(), "resumed snapshot commits");
        // Same reset the session would have performed had it never dropped: at the snapshot's own
        // first_seq, which survives the drop inside the staged session.
        verify(rebaselineService).reset(SITE, 200L);
        // ...and the segment carries the snapshot's mode and range, not a delta's.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChangeRecord>> records = ArgumentCaptor.forClass(List.class);
        verify(changelogSegmentService)
                .persist(eq(SITE), eq(batchId), eq("FULL_SNAPSHOT"), eq(200L), records.capture());
        assertEquals(2, records.getValue().size(), "staged + replayed snapshot records");
    }

    @Test
    void rebaselineIntentSurvivesRepeatedDrops() throws Exception {
        // 030/T05: a re-baseline is the longest, most drop-prone session there is — one drop must
        // not be the only case that works.
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.advanceWatermark(120L);
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.of(state));
        UUID batchId = UUID.randomUUID();
        Batch batch = mockBatch(batchId);
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(batch);
        when(batchLifecycle.isBatchInProgress(any())).thenReturn(true);

        StreamObserver<ClientEvent> s1 = asyncStub.streamChanges(
                collect(new CopyOnWriteArrayList<>(), new CountDownLatch(1)));
        s1.onNext(start(SessionMode.FULL_SNAPSHOT, 200L));
        s1.onNext(change("t", Op.INSERT, 200L));
        s1.onError(new RuntimeException("first drop"));

        StreamObserver<ClientEvent> s2 = asyncStub.streamChanges(
                collect(new CopyOnWriteArrayList<>(), new CountDownLatch(1)));
        s2.onNext(start(SessionMode.DELTA, 200L));
        s2.onNext(change("t", Op.INSERT, 201L));
        s2.onError(new RuntimeException("second drop"));

        List<ServerEvent> third = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        StreamObserver<ClientEvent> s3 = asyncStub.streamChanges(collect(third, done));
        s3.onNext(start(SessionMode.DELTA, 200L));
        s3.onNext(change("t", Op.INSERT, 202L));
        s3.onNext(ClientEvent.newBuilder().setEnd(SessionEnd.newBuilder().setLastSeq(202L)
                .putPerTable("t", TableStats.newBuilder().setInserts(3).build()).build()).build());
        s3.onCompleted();

        assertTrue(done.await(5, TimeUnit.SECONDS), "twice-resumed snapshot did not complete");
        verify(rebaselineService).reset(SITE, 200L);
        verify(changelogSegmentService)
                .persist(eq(SITE), eq(batchId), eq("FULL_SNAPSHOT"), eq(200L), any());
    }

    @Test
    void resumedFullSnapshotOntoReapedBatchStillResetsTheBaseline() throws Exception {
        // 030/T05 x T02: the two resume repairs must compose — a snapshot whose batch the sweeper
        // reaped while it was parked still re-baselines, now under the fresh batch.
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.advanceWatermark(120L);
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.of(state));
        UUID reapedBatchId = UUID.randomUUID();
        UUID freshBatchId = UUID.randomUUID();
        Batch reapedBatch = mockBatch(reapedBatchId);
        Batch freshBatch = mockBatch(freshBatchId);
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(reapedBatch, freshBatch);
        when(batchLifecycle.isBatchInProgress(any())).thenReturn(true);

        StreamObserver<ClientEvent> s1 = asyncStub.streamChanges(
                collect(new CopyOnWriteArrayList<>(), new CountDownLatch(1)));
        s1.onNext(start(SessionMode.FULL_SNAPSHOT, 200L));
        s1.onNext(change("t", Op.INSERT, 200L));
        s1.onError(new RuntimeException("transport drop"));

        when(batchLifecycle.isBatchInProgress(reapedBatchId)).thenReturn(false);

        List<ServerEvent> second = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        StreamObserver<ClientEvent> s2 = asyncStub.streamChanges(collect(second, done));
        s2.onNext(start(SessionMode.DELTA, 200L));
        s2.onNext(change("t", Op.INSERT, 201L));
        s2.onNext(ClientEvent.newBuilder().setEnd(SessionEnd.newBuilder().setLastSeq(201L)
                .putPerTable("t", TableStats.newBuilder().setInserts(2).build()).build()).build());
        s2.onCompleted();

        assertTrue(done.await(5, TimeUnit.SECONDS), "resumed snapshot did not complete");
        verify(rebaselineService).reset(SITE, 200L);
        verify(batchLifecycle).completeBatch(freshBatchId);
        verify(changelogSegmentService)
                .persist(eq(SITE), eq(freshBatchId), eq("FULL_SNAPSHOT"), eq(200L), any());
    }

    @Test
    void emitsProgressiveAcksOverLargeSession() throws Exception {
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.empty()); // watermark 0
        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(UUID.randomUUID());
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(batch);

        long total = 250L;
        List<ServerEvent> received = runSession(req -> {
            req.onNext(start(SessionMode.FULL_SNAPSHOT, 1L));
            for (long seq = 1; seq <= total; seq++) {
                req.onNext(change("t", Op.INSERT, seq));
            }
            req.onNext(ClientEvent.newBuilder().setEnd(SessionEnd.newBuilder().setLastSeq(total)
                    .putPerTable("t", TableStats.newBuilder().setInserts(total).build()).build()).build());
        });

        List<ServerEvent> acks = received.stream().filter(ServerEvent::hasAck).toList();
        assertEquals(2, acks.size(), "an ack every 100 records over a 250-record session");
        assertEquals(100L, acks.get(0).getAck().getAckedSeq());
        assertEquals(200L, acks.get(1).getAck().getAckedSeq());

        long prev = 0;
        for (ServerEvent ack : acks) {
            assertTrue(ack.getAck().getAckedSeq() > prev, "acked_seq advances monotonically");
            prev = ack.getAck().getAckedSeq();
        }

        ServerEvent last = received.get(received.size() - 1);
        assertTrue(last.hasCommitted(), "session commits after the acks");
        assertEquals(total, last.getCommitted().getCommittedSeq());
        assertTrue(prev <= last.getCommitted().getCommittedSeq(), "acks never exceed the committed seq");
    }

    @Test
    void continuousModeSealsSegmentsUnderOneSessionBatch() throws Exception {
        // 029: seals are durability events, not batch boundaries — one client session = one batch,
        // however many ~100-record segments it seals.
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.empty()); // watermark 0
        UUID batchId = UUID.randomUUID();
        Batch sessionBatch = mockBatch(batchId);
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(sessionBatch);

        long total = 250L;
        List<ServerEvent> received = runSession(req -> {
            req.onNext(start(SessionMode.CONTINUOUS, 1L));
            for (long seq = 1; seq <= total; seq++) {
                req.onNext(change("t", Op.INSERT, seq));
            }
            // No SessionEnd — continuous mode; runSession half-closes the stream to flush the tail.
        });

        List<ServerEvent> committed = received.stream().filter(ServerEvent::hasCommitted).toList();
        assertEquals(3, committed.size(), "two seals at the 100-record threshold + a final seal on close");
        assertEquals(100L, committed.get(0).getCommitted().getCommittedSeq());
        assertEquals(200L, committed.get(1).getCommitted().getCommittedSeq());
        assertEquals(250L, committed.get(2).getCommitted().getCommittedSeq());
        committed.forEach(e -> assertFalse(e.getCommitted().getSegmentS3Key().isEmpty(),
                "each sealed segment reports an s3 key"));
        assertTrue(received.stream().noneMatch(ServerEvent::hasError), "no error in continuous mode");

        // One batch for the whole session, completed exactly once at close.
        verify(batchLifecycle, times(1)).startBatch(ACCOUNT, SITE);
        verify(batchLifecycle, times(1)).completeBatch(batchId);
        verify(batchLifecycle, never()).failBatch(any());

        // Three segments, contiguous and complete, all under the session's batch: [1..100],
        // [101..200], [201..250].
        verify(changelogSegmentService).persist(eq(SITE), eq(batchId), eq("CONTINUOUS"), eq(1L),
                argThat((List<ChangeRecord> r) -> r.size() == 100));
        verify(changelogSegmentService).persist(eq(SITE), eq(batchId), eq("CONTINUOUS"), eq(101L),
                argThat((List<ChangeRecord> r) -> r.size() == 100));
        verify(changelogSegmentService).persist(eq(SITE), eq(batchId), eq("CONTINUOUS"), eq(201L),
                argThat((List<ChangeRecord> r) -> r.size() == 50));
    }

    @Test
    void continuousExactThresholdCloseCompletesTheSessionBatchOnce() throws Exception {
        // Exactly 100 records: the threshold seal commits a segment but keeps the session's batch
        // open; the clean half-close completes that same batch (029). No successor batch is ever
        // pre-opened, so nothing can be stranded and no empty trailing batch row appears.
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.empty()); // watermark 0
        UUID batchId = UUID.randomUUID();
        Batch sessionBatch = mockBatch(batchId);
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(sessionBatch);

        runSession(req -> {
            req.onNext(start(SessionMode.CONTINUOUS, 1L));
            for (long seq = 1; seq <= 100L; seq++) {
                req.onNext(change("t", Op.INSERT, seq));
            }
            // No more records; runSession half-closes the stream.
        });

        verify(batchLifecycle, times(1)).startBatch(ACCOUNT, SITE);
        verify(batchLifecycle, times(1)).completeBatch(batchId);
        verify(batchLifecycle, never()).failBatch(any());
        // The threshold seal wrote the one and only segment; the close flushed no second one.
        verify(changelogSegmentService, times(1)).persist(eq(SITE), eq(batchId), eq("CONTINUOUS"),
                eq(1L), argThat((List<ChangeRecord> r) -> r.size() == 100));
    }

    @Test
    void streamingTouchesBatchActivityAtStartAckAndSeal() throws Exception {
        // 029/T006: the activity-based timeout needs a liveness signal — the session touches its
        // batch at start, at each Ack watermark (>=100 accepted records), and at each seal.
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.empty());
        UUID batchId = UUID.randomUUID();
        Batch sessionBatch = mockBatch(batchId);
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(sessionBatch);

        runSession(req -> {
            req.onNext(start(SessionMode.CONTINUOUS, 1L));
            for (long seq = 1; seq <= 100L; seq++) {
                req.onNext(change("t", Op.INSERT, seq));
            }
        });

        // At least: one touch at session start, one at the 100-record ack, one at the seal.
        verify(batchLifecycle, atLeast(3)).touchActivity(batchId);
    }

    @Test
    void shortDeltaSessionTouchesActivityOnlyAtStart() throws Exception {
        // Below the ack watermark no per-record touching happens — the touch cadence is bounded,
        // not per record.
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.empty());
        UUID batchId = UUID.randomUUID();
        Batch sessionBatch = mockBatch(batchId);
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(sessionBatch);

        runSession(req -> {
            req.onNext(start(SessionMode.FULL_SNAPSHOT, 1L));
            req.onNext(change("t", Op.INSERT, 1L));
            req.onNext(ClientEvent.newBuilder().setEnd(
                    SessionEnd.newBuilder().setLastSeq(1L)
                            .putPerTable("t", TableStats.newBuilder().setInserts(1).build())
                            .build()).build());
        });

        verify(batchLifecycle, times(1)).touchActivity(batchId);
    }

    @Test
    void continuousZeroRecordSessionCompletesItsBatchWithNoSegment() throws Exception {
        // A session that opens and closes without records is still one honest upload attempt:
        // its single batch completes with zero changes and no segment row (029, FR-004/FR-005).
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.empty());
        UUID batchId = UUID.randomUUID();
        Batch sessionBatch = mockBatch(batchId);
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(sessionBatch);

        runSession(req -> req.onNext(start(SessionMode.CONTINUOUS, 1L)));

        verify(batchLifecycle, times(1)).startBatch(ACCOUNT, SITE);
        verify(batchLifecycle, times(1)).completeBatch(batchId);
        verify(batchLifecycle, never()).failBatch(any());
        verify(changelogSegmentService, never()).persist(any(), any(), any(), anyLong(), any());
    }

    @Test
    void continuousDropSealsTailAndCompletesBatchInsteadOfOrphaning() throws Exception {
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.empty()); // watermark 0
        UUID batchId = UUID.randomUUID();
        Batch batch = mockBatch(batchId);
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(batch);

        List<ServerEvent> received = new CopyOnWriteArrayList<>();
        StreamObserver<ClientEvent> s = asyncStub.streamChanges(collect(received, new CountDownLatch(1)));
        s.onNext(start(SessionMode.CONTINUOUS, 1L));
        s.onNext(change("t", Op.INSERT, 1L));
        s.onNext(change("t", Op.INSERT, 2L)); // < seal threshold, so still unsealed
        s.onError(new RuntimeException("transport drop"));

        // The unsealed tail is durably sealed and the batch completed (not left IN_PROGRESS).
        verify(batchLifecycle).startBatch(ACCOUNT, SITE);
        verify(changelogSegmentService).persist(eq(SITE), eq(batchId), eq("CONTINUOUS"), eq(1L),
                argThat((List<ChangeRecord> r) -> r.size() == 2));
        verify(batchLifecycle).completeBatch(batchId);
        verify(batchLifecycle, never()).failBatch(batchId);
    }

    @Test
    void continuousCloseCommitFailureFailsBatchInsteadOfEscaping() throws Exception {
        // onCompleted was the only commit path without a try/catch: a commit failure escaped the
        // gRPC callback, left the batch IN_PROGRESS, and never notified the client. It must now
        // fail the batch and surface an error, like onNext/onError already do.
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.empty());
        UUID batchId = UUID.randomUUID();
        Batch batch = mockBatch(batchId);
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(batch);
        when(changelogSegmentService.persist(any(), any(), any(), anyLong(), any()))
                .thenThrow(new RuntimeException("s3 down"));

        List<ServerEvent> received = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        StreamObserver<ClientEvent> s = asyncStub.streamChanges(collect(received, done));
        s.onNext(start(SessionMode.CONTINUOUS, 1L));
        s.onNext(change("t", Op.INSERT, 1L));
        s.onCompleted(); // clean close flushes the tail -> commit throws

        assertTrue(done.await(5, TimeUnit.SECONDS), "stream must terminate (not hang on an escaped throw)");
        verify(batchLifecycle).failBatch(batchId);
        verify(batchLifecycle, never()).completeBatch(batchId);
    }

    @Test
    void sessionExceedingRecordCapRejectedInsteadOfBufferingUnbounded() throws Exception {
        // A tiny cap makes the OOM guard observable: past the cap, the session is rejected rather
        // than accumulating the whole dataset in heap (review r4).
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.empty());
        Batch batch = mockBatch(UUID.randomUUID());
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(batch);
        DeltaSyncStateService syncStateService = new DeltaSyncStateService(syncRepo);
        DeltaSessionCommitService commitService = new DeltaSessionCommitService(
                changelogSegmentService, syncStateService, batchLifecycle,
                mock(com.bitbi.dfm.delta.application.DeltaEgressWorker.class), rebaselineService);
        DeltaIngestionService capped = new DeltaIngestionService(
                syncStateService, batchLifecycle, siteSchemaService, commitService,
                new DeltaMetrics(new SimpleMeterRegistry()), 2, Long.MAX_VALUE, 3900000L, 300000L, 16777216L); // cap = 2 records
        String name = InProcessServerBuilder.generateName();
        Server capServer = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(capped, authContext(SITE, ACCOUNT))).build().start();
        ManagedChannel capChannel = InProcessChannelBuilder.forName(name).directExecutor().build();
        try {
            DeltaIngestionGrpc.DeltaIngestionStub stub = DeltaIngestionGrpc.newStub(capChannel);
            List<ServerEvent> received = new CopyOnWriteArrayList<>();
            StreamObserver<ClientEvent> s = stub.streamChanges(collect(received, new CountDownLatch(1)));
            s.onNext(start(SessionMode.FULL_SNAPSHOT, 1L));
            s.onNext(change("t", Op.INSERT, 1L));
            s.onNext(change("t", Op.INSERT, 2L));
            s.onNext(change("t", Op.INSERT, 3L)); // past the cap

            ServerEvent last = received.get(received.size() - 1);
            assertTrue(last.hasError());
            assertEquals(ErrorCode.INTERNAL, last.getError().getCode());
            verify(batchLifecycle).failBatch(any());
        } finally {
            capChannel.shutdownNow();
            capServer.shutdownNow();
        }
    }

    @Test
    void sessionExceedingByteBudgetRejectedInsteadOfBufferingUnbounded() throws Exception {
        // The record cap counts rows, not bytes: a 439k-row full snapshot OOMed a 1536Mi pod while
        // far under the 2M-row limit. Past the byte budget the session must degrade into a clean
        // SESSION-level error (and a failed batch) instead of killing the JVM.
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.empty());
        Batch batch = mockBatch(UUID.randomUUID());
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(batch);
        long budget = ChangeRecord.newBuilder().setTable("t").setOp(Op.INSERT).setSeq(1L).build()
                .getSerializedSize() * 2L; // room for two records, not three
        DeltaSyncStateService syncStateService = new DeltaSyncStateService(syncRepo);
        DeltaSessionCommitService commitService = new DeltaSessionCommitService(
                changelogSegmentService, syncStateService, batchLifecycle,
                mock(com.bitbi.dfm.delta.application.DeltaEgressWorker.class), rebaselineService);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeltaIngestionService capped = new DeltaIngestionService(
                syncStateService, batchLifecycle, siteSchemaService, commitService,
                new DeltaMetrics(registry), 2000000, budget, 3900000L, 300000L, 1L); // seal < tiny budget
        String name = InProcessServerBuilder.generateName();
        Server capServer = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(capped, authContext(SITE, ACCOUNT))).build().start();
        ManagedChannel capChannel = InProcessChannelBuilder.forName(name).directExecutor().build();
        try {
            DeltaIngestionGrpc.DeltaIngestionStub stub = DeltaIngestionGrpc.newStub(capChannel);
            List<ServerEvent> received = new CopyOnWriteArrayList<>();
            StreamObserver<ClientEvent> s = stub.streamChanges(collect(received, new CountDownLatch(1)));
            s.onNext(start(SessionMode.FULL_SNAPSHOT, 1L));
            s.onNext(change("t", Op.INSERT, 1L));
            s.onNext(change("t", Op.INSERT, 2L));
            s.onNext(change("t", Op.INSERT, 3L)); // past the byte budget

            ServerEvent last = received.get(received.size() - 1);
            assertTrue(last.hasError());
            assertEquals(ErrorCode.INTERNAL, last.getError().getCode());
            assertTrue(last.getError().getMessage().contains("byte"),
                    "the error must name the byte budget, not the record cap: " + last.getError().getMessage());
            assertEquals(RecoveryAction.NEED_REBASELINE, last.getError().getAction());
            verify(batchLifecycle).failBatch(any());
            assertEquals(1.0, registry.get("delta.sessions.overflow").tag("reason", "bytes").counter().count(),
                    "a byte-budget rejection is counted under reason=bytes");
        } finally {
            capChannel.shutdownNow();
            capServer.shutdownNow();
        }
    }

    @Test
    void sealBytesAtOrAboveTheSessionByteBudgetFailsFastAtStartup() {
        // With continuous-seal-bytes >= max-session-bytes the budget would reject a fat CONTINUOUS
        // stream before the byte seal ever fires (reachable when auto = maxHeap/8 <= 16MiB on a tiny
        // heap). A misconfiguration must fail the boot with a clear message, not lurk as a comment.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new DeltaIngestionService(
                        mock(DeltaSyncStateService.class), batchLifecycle, siteSchemaService,
                        mock(DeltaSessionCommitService.class), new DeltaMetrics(new SimpleMeterRegistry()),
                        2000000, 100L, 3900000L, 300000L, 100L)); // seal == budget
        assertTrue(e.getMessage().contains("continuous-seal-bytes"), e.getMessage());
        assertTrue(e.getMessage().contains("max-session-bytes"), e.getMessage());
    }

    @Test
    void continuousByteOverflowDoesNotAdviseContinuousMode() throws Exception {
        // A CONTINUOUS session can still trip the budget when budget - seal threshold is smaller
        // than one record: "stream in CONTINUOUS mode" would be nonsense advice there — the message
        // must point at the config knobs instead.
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.empty());
        UUID batchId = UUID.randomUUID();
        Batch batch = mockBatch(batchId);
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(batch);
        long recordSize = ChangeRecord.newBuilder().setTable("t").setOp(Op.INSERT).setSeq(1L).build()
                .getSerializedSize();
        long sealBytes = recordSize + 1;  // first record does not seal...
        long budget = recordSize + 2;     // ...and the second does not fit (valid: seal < budget)
        DeltaSyncStateService syncStateService = new DeltaSyncStateService(syncRepo);
        DeltaSessionCommitService commitService = new DeltaSessionCommitService(
                changelogSegmentService, syncStateService, batchLifecycle,
                mock(com.bitbi.dfm.delta.application.DeltaEgressWorker.class), rebaselineService);
        DeltaIngestionService tight = new DeltaIngestionService(
                syncStateService, batchLifecycle, siteSchemaService, commitService,
                new DeltaMetrics(new SimpleMeterRegistry()), 2000000, budget, 3900000L, 300000L, sealBytes);
        String name = InProcessServerBuilder.generateName();
        Server srv = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(tight, authContext(SITE, ACCOUNT))).build().start();
        ManagedChannel ch = InProcessChannelBuilder.forName(name).directExecutor().build();
        try {
            DeltaIngestionGrpc.DeltaIngestionStub stub = DeltaIngestionGrpc.newStub(ch);
            List<ServerEvent> received = new CopyOnWriteArrayList<>();
            StreamObserver<ClientEvent> s = stub.streamChanges(collect(received, new CountDownLatch(1)));
            s.onNext(start(SessionMode.CONTINUOUS, 1L));
            s.onNext(change("t", Op.INSERT, 1L));
            s.onNext(change("t", Op.INSERT, 2L)); // budget exceeded before the seal threshold

            ServerEvent last = received.get(received.size() - 1);
            assertTrue(last.hasError());
            assertEquals(ErrorCode.INTERNAL, last.getError().getCode());
            assertTrue(last.getError().getMessage().contains("byte"), last.getError().getMessage());
            assertFalse(last.getError().getMessage().contains("CONTINUOUS"),
                    "a continuous session must not be advised to use CONTINUOUS mode: "
                            + last.getError().getMessage());
            assertTrue(last.getError().getMessage().contains("max-session-bytes"),
                    "the message must point at the config knobs: " + last.getError().getMessage());
            verify(batchLifecycle).failBatch(batchId);
        } finally {
            ch.shutdownNow();
            srv.shutdownNow();
        }
    }

    @Test
    void byteBudgetAutoDefaultScalesWithMaxHeap() {
        // 0 (the config default) resolves to maxHeap/8 of serialized bytes, so the guard scales with
        // the pod instead of a fixed number that is either too big for small pods or rejects legit
        // snapshots on big ones. An explicit value is taken as-is.
        assertEquals(Runtime.getRuntime().maxMemory() / 8, DeltaIngestionService.resolveMaxSessionBytes(0L));
        assertEquals(Runtime.getRuntime().maxMemory() / 8, DeltaIngestionService.resolveMaxSessionBytes(-1L));
        assertEquals(123456789L, DeltaIngestionService.resolveMaxSessionBytes(123456789L));
    }

    @Test
    void continuousTimeTriggerSealsBelowTheRecordThreshold() throws Exception {
        // With a 0 ms time trigger, a low-throughput continuous stream seals each record instead of
        // waiting for the 100-record count — bounding staleness for trickle clients (review r4).
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.empty());
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        Batch b1 = mockBatch(id1);
        Batch b2 = mockBatch(id2);
        Batch b3 = mockBatch(id3);
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(b1, b2, b3);
        DeltaSyncStateService syncStateService = new DeltaSyncStateService(syncRepo);
        DeltaSessionCommitService commitService = new DeltaSessionCommitService(
                changelogSegmentService, syncStateService, batchLifecycle,
                mock(com.bitbi.dfm.delta.application.DeltaEgressWorker.class), rebaselineService);
        DeltaIngestionService timed = new DeltaIngestionService(
                syncStateService, batchLifecycle, siteSchemaService, commitService,
                new DeltaMetrics(new SimpleMeterRegistry()), 2000000, Long.MAX_VALUE, 3900000L, 0L, 16777216L); // seal on every record
        String name = InProcessServerBuilder.generateName();
        Server srv = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(timed, authContext(SITE, ACCOUNT))).build().start();
        ManagedChannel ch = InProcessChannelBuilder.forName(name).directExecutor().build();
        try {
            DeltaIngestionGrpc.DeltaIngestionStub stub = DeltaIngestionGrpc.newStub(ch);
            List<ServerEvent> received = new CopyOnWriteArrayList<>();
            StreamObserver<ClientEvent> s = stub.streamChanges(collect(received, new CountDownLatch(1)));
            s.onNext(start(SessionMode.CONTINUOUS, 1L));
            s.onNext(change("t", Op.INSERT, 1L));
            s.onNext(change("t", Op.INSERT, 2L));

            long seals = received.stream().filter(ServerEvent::hasCommitted).count();
            assertTrue(seals >= 2, "each record seals via the time trigger (got " + seals + ")");
        } finally {
            ch.shutdownNow();
            srv.shutdownNow();
        }
    }

    @Test
    void continuousByteTriggerSealsBelowTheRecordThreshold() throws Exception {
        // 100 fat records can weigh as much as thousands of thin ones (each may approach the 4MB
        // gRPC message cap): CONTINUOUS mode must also seal on buffered bytes, not just count/time,
        // so a fat stream degrades into more, smaller segments instead of a bloated buffer.
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.empty());
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        Batch b1 = mockBatch(id1);
        Batch b2 = mockBatch(id2);
        Batch b3 = mockBatch(id3);
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(b1, b2, b3);
        long sealBytes = ChangeRecord.newBuilder().setTable("t").setOp(Op.INSERT).setSeq(1L).build()
                .getSerializedSize() * 2L; // seal after every two records
        DeltaSyncStateService syncStateService = new DeltaSyncStateService(syncRepo);
        DeltaSessionCommitService commitService = new DeltaSessionCommitService(
                changelogSegmentService, syncStateService, batchLifecycle,
                mock(com.bitbi.dfm.delta.application.DeltaEgressWorker.class), rebaselineService);
        DeltaIngestionService sized = new DeltaIngestionService(
                syncStateService, batchLifecycle, siteSchemaService, commitService,
                new DeltaMetrics(new SimpleMeterRegistry()), 2000000, Long.MAX_VALUE, 3900000L, 300000L,
                sealBytes);
        String name = InProcessServerBuilder.generateName();
        Server srv = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(sized, authContext(SITE, ACCOUNT))).build().start();
        ManagedChannel ch = InProcessChannelBuilder.forName(name).directExecutor().build();
        try {
            DeltaIngestionGrpc.DeltaIngestionStub stub = DeltaIngestionGrpc.newStub(ch);
            List<ServerEvent> received = new CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(1);
            StreamObserver<ClientEvent> s = stub.streamChanges(collect(received, done));
            s.onNext(start(SessionMode.CONTINUOUS, 1L));
            for (long seq = 1; seq <= 5; seq++) {
                s.onNext(change("t", Op.INSERT, seq));
            }
            s.onCompleted(); // half-close flushes the tail
            assertTrue(done.await(5, TimeUnit.SECONDS), "stream did not complete");

            List<ServerEvent> committed = received.stream().filter(ServerEvent::hasCommitted).toList();
            assertEquals(3, committed.size(), "two byte-trigger seals + a final seal on close");
            assertEquals(2L, committed.get(0).getCommitted().getCommittedSeq());
            assertEquals(4L, committed.get(1).getCommitted().getCommittedSeq());
            assertEquals(5L, committed.get(2).getCommitted().getCommittedSeq());
            assertTrue(received.stream().noneMatch(ServerEvent::hasError), "byte seals are not errors");
        } finally {
            ch.shutdownNow();
            srv.shutdownNow();
        }
    }

    private List<ServerEvent> runSession(Consumer<StreamObserver<ClientEvent>> client) throws InterruptedException {
        List<ServerEvent> received = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        StreamObserver<ServerEvent> responses = new StreamObserver<>() {
            @Override
            public void onNext(ServerEvent event) {
                received.add(event);
            }

            @Override
            public void onError(Throwable t) {
                done.countDown();
            }

            @Override
            public void onCompleted() {
                done.countDown();
            }
        };
        StreamObserver<ClientEvent> request = asyncStub.streamChanges(responses);
        client.accept(request);
        request.onCompleted(); // half-close so a still-open session completes for assertion
        assertTrue(done.await(5, TimeUnit.SECONDS), "stream did not complete");
        return received;
    }
}
