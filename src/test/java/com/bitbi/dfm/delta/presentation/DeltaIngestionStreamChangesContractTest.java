package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.delta.grpc.v2.*;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
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
    private Server server;
    private ManagedChannel channel;
    private DeltaIngestionGrpc.DeltaIngestionStub asyncStub;

    @BeforeEach
    void setUp() throws IOException {
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.empty());
        ChangelogSegment segment = mock(ChangelogSegment.class);
        when(segment.getS3Key()).thenReturn("delta/site/segments/batch.pb.gz");
        when(changelogSegmentService.persist(any(), any(), any(), anyLong(), any())).thenReturn(segment);
        DeltaIngestionService service = new DeltaIngestionService(
                new DeltaSyncStateService(syncRepo), batchLifecycle,
                mock(SiteSchemaService.class), changelogSegmentService);
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
                ChangeRecord.newBuilder().setTable("t").setOp(Op.INSERT).setSeq(7).build()).build());
        request.onNext(ClientEvent.newBuilder().setEnd(
                SessionEnd.newBuilder().setLastSeq(7)
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
        assertEquals(7L, received.get(1).getCommitted().getCommittedSeq());
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
