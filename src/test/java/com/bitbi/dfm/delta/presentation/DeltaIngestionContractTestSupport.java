package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.DeltaEgressWorker;
import com.bitbi.dfm.delta.application.DeltaMetrics;
import com.bitbi.dfm.delta.application.DeltaRebaselineService;
import com.bitbi.dfm.delta.application.DeltaSessionCommitService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import com.bitbi.dfm.delta.grpc.v2.*;
import com.bitbi.dfm.site.application.SiteSchemaService;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared in-process gRPC harness for the {@link DeltaIngestionService} contract tests: the same
 * five mocked collaborators, the same service tuning, an in-process server behind an interceptor
 * that binds a fixed authenticated site, and the {@code ClientEvent} builders every session test
 * needs.
 *
 * <p>Extracted because each contract test used to carry its own byte-identical copy — including
 * the tuning constants below, which decide when a session overflows or seals and so must stay the
 * same across tests that assert on those boundaries.</p>
 *
 * <p>A test that needs different tuning (e.g. a smaller seal threshold) builds its own server
 * inside the test method instead of using this one.</p>
 */
abstract class DeltaIngestionContractTestSupport {

    protected static final UUID SITE = UUID.randomUUID();
    protected static final UUID ACCOUNT = UUID.randomUUID();

    /** The S3 key the mocked segment storage reports for every persisted segment. */
    protected static final String SEGMENT_KEY = "delta/site/segments/batch.pb.gz";

    protected final SiteSyncStateRepository syncRepo = mock(SiteSyncStateRepository.class);
    protected final BatchLifecycleService batchLifecycle = mock(BatchLifecycleService.class);
    protected final ChangelogSegmentService changelogSegmentService = mock(ChangelogSegmentService.class);
    protected final SiteSchemaService siteSchemaService = mock(SiteSchemaService.class);
    protected final DeltaRebaselineService rebaselineService = mock(DeltaRebaselineService.class);

    private Server server;
    private ManagedChannel channel;
    protected DeltaIngestionGrpc.DeltaIngestionStub asyncStub;

    @BeforeEach
    void startInProcessServer() throws IOException {
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.empty());
        ChangelogSegment segment = mock(ChangelogSegment.class);
        when(segment.getS3Key()).thenReturn(SEGMENT_KEY);
        when(changelogSegmentService.persist(any(), any(), any(), anyLong(), any())).thenReturn(segment);
        when(changelogSegmentService.persistProvisional(any(), any(), any(), anyLong(), any())).thenReturn(segment);
        DeltaSyncStateService syncStateService = new DeltaSyncStateService(syncRepo);
        DeltaSessionCommitService commitService = new DeltaSessionCommitService(
                changelogSegmentService, syncStateService, batchLifecycle,
                mock(DeltaEgressWorker.class), rebaselineService);
        DeltaIngestionService service = new DeltaIngestionService(
                syncStateService, batchLifecycle, siteSchemaService, commitService, rebaselineService,
                new DeltaMetrics(new SimpleMeterRegistry()),
                2000000, Long.MAX_VALUE, 3900000L, 300000L, 16777216L,
                // 033: snapshot seal pinned to 100 so seal behaviour is observable in a 250-record
                // test; the shipped default is far higher (DeltaIngestionSnapshotSealConfigTest).
                500, 100);

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
    void stopInProcessServer() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    protected static Batch mockBatch(UUID id) {
        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(id);
        return batch;
    }

    /**
     * Make {@code startBatch} hand out a batch with the given id. The stub is built before the
     * outer {@code when(...)} — nesting one stubbing inside another's argument is what Mockito
     * reports as {@code UnfinishedStubbingException}.
     */
    protected void openableBatch(UUID batchId) {
        Batch batch = mockBatch(batchId);
        when(batchLifecycle.startBatch(eq(ACCOUNT), eq(SITE), any())).thenReturn(batch);
    }

    protected static ClientEvent start(SessionMode mode, long firstSeq) {
        return ClientEvent.newBuilder()
                .setStart(SessionStart.newBuilder().setMode(mode).setFirstSeq(firstSeq).build())
                .build();
    }

    protected static ClientEvent change(long seq) {
        return change("t", Op.INSERT, seq);
    }

    protected static ClientEvent change(String table, Op op, long seq) {
        return ClientEvent.newBuilder().setChange(
                ChangeRecord.newBuilder().setTable(table).setOp(op).setSeq(seq).build()).build();
    }

    protected static StreamObserver<ServerEvent> collect(List<ServerEvent> sink, CountDownLatch done) {
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

    /**
     * Stand-in for {@link DeltaAuthInterceptor}: binds a fixed site/account to the gRPC context so
     * handlers see an authenticated call without a real token.
     */
    protected static ServerInterceptor authContext(UUID siteId, UUID accountId) {
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
