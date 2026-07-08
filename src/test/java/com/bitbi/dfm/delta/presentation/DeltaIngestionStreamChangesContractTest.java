package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.DeltaMetrics;
import com.bitbi.dfm.delta.application.DeltaRebaselineService;
import com.bitbi.dfm.delta.application.DeltaSessionCommitService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.TableSchema;
import com.bitbi.dfm.delta.grpc.v2.*;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
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
class DeltaIngestionStreamChangesContractTest {

    private static final UUID SITE = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();

    private final SiteSyncStateRepository syncRepo = mock(SiteSyncStateRepository.class);
    private final BatchLifecycleService batchLifecycle = mock(BatchLifecycleService.class);
    private final ChangelogSegmentService changelogSegmentService = mock(ChangelogSegmentService.class);
    private final SiteSchemaService siteSchemaService = mock(SiteSchemaService.class);
    private final DeltaRebaselineService rebaselineService = mock(DeltaRebaselineService.class);
    private Server server;
    private ManagedChannel channel;
    private DeltaIngestionGrpc.DeltaIngestionStub asyncStub;

    @BeforeEach
    void setUp() throws IOException {
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.empty());
        ChangelogSegment segment = mock(ChangelogSegment.class);
        when(segment.getS3Key()).thenReturn("delta/site/segments/batch.pb.gz");
        when(changelogSegmentService.persist(any(), any(), any(), anyLong(), any())).thenReturn(segment);
        DeltaSyncStateService syncStateService = new DeltaSyncStateService(syncRepo);
        DeltaSessionCommitService commitService = new DeltaSessionCommitService(
                changelogSegmentService, syncStateService, batchLifecycle,
                mock(com.bitbi.dfm.delta.application.DeltaEgressWorker.class));
        DeltaIngestionService service = new DeltaIngestionService(
                syncStateService, batchLifecycle,
                siteSchemaService, commitService, rebaselineService,
                new DeltaMetrics(new SimpleMeterRegistry()));
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(ServerInterceptors.intercept(service, authContext(SITE, ACCOUNT)))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        asyncStub = DeltaIngestionGrpc.newStub(channel);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

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
    void fullSnapshotResetsPriorStateAndStartsFromFirstSeqMinusOne() throws Exception {
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.advanceWatermark(120L); // stale watermark from a previous baseline
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.of(state));
        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(UUID.randomUUID());
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(batch);

        List<ServerEvent> received = runSession(req ->
                req.onNext(start(SessionMode.FULL_SNAPSHOT, 200L)));

        // The prior changelog/checkpoints are wiped and the watermark resets to firstSeq-1.
        verify(rebaselineService).reset(SITE, 200L);
        assertTrue(received.get(0).hasOpened());
        assertEquals(199L, received.get(0).getOpened().getServerLastSeq(),
                "snapshot session reports the reset watermark (firstSeq-1)");
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
    void continuousModeSealsSegmentsWithoutSessionEnd() throws Exception {
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.empty()); // watermark 0
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        Batch b1 = mockBatch(id1);
        Batch b2 = mockBatch(id2);
        Batch b3 = mockBatch(id3);
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(b1, b2, b3);

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

        // One batch per sealed segment.
        verify(batchLifecycle, times(3)).startBatch(ACCOUNT, SITE);
        verify(batchLifecycle).completeBatch(id1);
        verify(batchLifecycle).completeBatch(id2);
        verify(batchLifecycle).completeBatch(id3);

        // Three segments, contiguous and complete: [1..100], [101..200], [201..250].
        verify(changelogSegmentService).persist(eq(SITE), eq(id1), eq("CONTINUOUS"), eq(1L),
                argThat((List<ChangeRecord> r) -> r.size() == 100));
        verify(changelogSegmentService).persist(eq(SITE), eq(id2), eq("CONTINUOUS"), eq(101L),
                argThat((List<ChangeRecord> r) -> r.size() == 100));
        verify(changelogSegmentService).persist(eq(SITE), eq(id3), eq("CONTINUOUS"), eq(201L),
                argThat((List<ChangeRecord> r) -> r.size() == 50));
    }

    @Test
    void continuousExactThresholdCloseDoesNotStrandTheFreshBatch() throws Exception {
        // Exactly 100 records: the threshold seal commits batch 1 and opens a fresh batch 2 with an
        // empty buffer. A clean half-close must complete batch 2 too, or it stays IN_PROGRESS and
        // blocks the site with ACTIVE_SESSION_EXISTS until the 60-min sweeper (P1).
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.empty()); // watermark 0
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Batch b1 = mockBatch(id1);
        Batch b2 = mockBatch(id2);
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(b1, b2);

        runSession(req -> {
            req.onNext(start(SessionMode.CONTINUOUS, 1L));
            for (long seq = 1; seq <= 100L; seq++) {
                req.onNext(change("t", Op.INSERT, seq));
            }
            // No more records; runSession half-closes the stream.
        });

        verify(batchLifecycle, times(2)).startBatch(ACCOUNT, SITE);
        verify(batchLifecycle).completeBatch(id1);
        verify(batchLifecycle).completeBatch(id2); // the fresh empty batch must not be stranded
        verify(batchLifecycle, never()).failBatch(any());
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

    private static Batch mockBatch(UUID id) {
        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(id);
        return batch;
    }

    private static StreamObserver<ServerEvent> collect(List<ServerEvent> sink, CountDownLatch done) {
        return new StreamObserver<>() {
            @Override
            public void onNext(ServerEvent event) {
                sink.add(event);
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
    }

    private static ClientEvent change(String table, Op op, long seq) {
        return ClientEvent.newBuilder().setChange(
                ChangeRecord.newBuilder().setTable(table).setOp(op).setSeq(seq).build()).build();
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

    private static ClientEvent start(SessionMode mode, long firstSeq) {
        return ClientEvent.newBuilder()
                .setStart(SessionStart.newBuilder().setMode(mode).setFirstSeq(firstSeq).build())
                .build();
    }

    private static ServerInterceptor authContext(UUID siteId, UUID accountId) {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                Context context = Context.current()
                        .withValue(DeltaAuthInterceptor.SITE_ID, siteId)
                        .withValue(DeltaAuthInterceptor.ACCOUNT_ID, accountId);
                return Contexts.interceptCall(context, call, headers, next);
            }
        };
    }
}
