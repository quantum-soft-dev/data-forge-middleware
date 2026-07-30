package com.bitbi.dfm.delta.presentation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
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
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * #83 — the Delta v2 ingestion path is the only way a batch can be created since client API v1 was
 * retired, and it used to carry no logger at all. Every session rejection was invisible: an incident
 * had to be investigated by proving the <em>absence</em> of log lines over 22 hours.
 *
 * <p>These tests pin the observability contract itself: a session leaves a trace when it opens,
 * seals, and commits; every rejection names its {@code ErrorCode} and {@code RecoveryAction} at
 * WARN; an unexpected fault is logged with its stack trace. And the whole thing stays
 * <em>bounded</em> — per session and per seal, never per record.</p>
 */
class DeltaIngestionSessionLoggingContractTest {

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
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() throws IOException {
        when(syncRepo.findBySiteId(SITE)).thenReturn(Optional.empty());
        ChangelogSegment segment = mock(ChangelogSegment.class);
        when(segment.getS3Key()).thenReturn("delta/site/segments/batch.pb.gz");
        when(changelogSegmentService.persist(any(), any(), any(), anyLong(), any())).thenReturn(segment);
        DeltaSyncStateService syncStateService = new DeltaSyncStateService(syncRepo);
        DeltaSessionCommitService commitService = new DeltaSessionCommitService(
                changelogSegmentService, syncStateService, batchLifecycle,
                mock(com.bitbi.dfm.delta.application.DeltaEgressWorker.class), rebaselineService);
        DeltaIngestionService service = new DeltaIngestionService(
                syncStateService, batchLifecycle, siteSchemaService, commitService,
                new DeltaMetrics(new SimpleMeterRegistry()),
                2000000, Long.MAX_VALUE, 3900000L, 300000L, 16777216L);

        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(ServerInterceptors.intercept(service, authContext(SITE, ACCOUNT)))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        asyncStub = DeltaIngestionGrpc.newStub(channel);

        logger = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(DeltaIngestionService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void openingAndCommittingASessionIsTraceable() throws Exception {
        UUID batchId = UUID.randomUUID();
        openableBatch(batchId);

        runSession(req -> {
            req.onNext(start(SessionMode.FULL_SNAPSHOT, 1L));
            req.onNext(change(1L));
            req.onNext(ClientEvent.newBuilder().setEnd(SessionEnd.newBuilder()
                    .setLastSeq(1L)
                    .putPerTable("t", TableStats.newBuilder().setInserts(1).build())
                    .build()).build());
        });

        assertTrue(atLevel(Level.INFO).stream().anyMatch(line ->
                        line.contains("session start") && line.contains(SITE.toString())
                                && line.contains("FULL_SNAPSHOT") && line.contains("firstSeq=1")),
                "SessionStart must record the site, mode and first_seq it was asked for: " + atLevel(Level.INFO));
        assertTrue(atLevel(Level.INFO).stream().anyMatch(line ->
                        line.contains("session opened") && line.contains(batchId.toString())),
                "the opened session must name the batch it created: " + atLevel(Level.INFO));
        assertTrue(atLevel(Level.INFO).stream().anyMatch(line ->
                        line.contains("session committed") && line.contains("committedSeq=1")),
                "the commit must record the sequence it committed: " + atLevel(Level.INFO));
    }

    @Test
    void everyContinuousSealIsTraceable() throws Exception {
        openableBatch(UUID.randomUUID());

        runSession(req -> {
            req.onNext(start(SessionMode.CONTINUOUS, 1L));
            for (long seq = 1; seq <= 200; seq++) {
                req.onNext(change(seq));
            }
        });

        List<String> seals = atLevel(Level.INFO).stream().filter(line -> line.contains("segment sealed")).toList();
        assertEquals(2, seals.size(), "one line per seal at the 100-record threshold: " + atLevel(Level.INFO));
        assertTrue(seals.getFirst().contains("records=100"), "a seal must record its size: " + seals.getFirst());
        assertTrue(seals.getFirst().contains("delta/site/segments/batch.pb.gz"),
                "a seal must name the segment it wrote: " + seals.getFirst());
    }

    @Test
    void everyRejectionNamesItsCodeAndRecoveryActionAtWarn() throws Exception {
        when(batchLifecycle.startBatch(ACCOUNT, SITE))
                .thenThrow(new BatchLifecycleService.SiteInactiveException("Cannot start batch for inactive site."));

        runSession(req -> req.onNext(start(SessionMode.DELTA, 1L)));

        List<String> warnings = atLevel(Level.WARN);
        assertEquals(1, warnings.size(), "a rejected session must leave exactly one WARN: " + warnings);
        assertTrue(warnings.getFirst().contains("UNAUTHORIZED"), "the WARN must name the code: " + warnings);
        assertTrue(warnings.getFirst().contains("PROCEED"), "the WARN must name the action: " + warnings);
        assertTrue(warnings.getFirst().contains(SITE.toString()), "the WARN must name the site: " + warnings);
    }

    @Test
    void anUnexpectedFaultIsLoggedWithItsStackTraceInsteadOfVanishingIntoStatusInternal() throws Exception {
        when(batchLifecycle.startBatch(ACCOUNT, SITE))
                .thenThrow(new IllegalStateException("site row vanished"));

        runSession(req -> req.onNext(start(SessionMode.DELTA, 1L)));

        List<ILoggingEvent> errors = appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR).toList();
        assertEquals(1, errors.size(), "an unhandled fault must leave exactly one ERROR");
        assertTrue(errors.getFirst().getFormattedMessage().contains(SITE.toString()),
                "the ERROR must name the site: " + errors.getFirst().getFormattedMessage());
        assertNotNull(errors.getFirst().getThrowableProxy(),
                "a Status.INTERNAL with no stack trace is what made #83 uninvestigable");
    }

    @Test
    void loggingIsBoundedPerSessionNotPerRecord() throws Exception {
        openableBatch(UUID.randomUUID());

        runSession(req -> {
            req.onNext(start(SessionMode.DELTA, 1L));
            for (long seq = 1; seq <= 500; seq++) {
                req.onNext(change(seq));
            }
            req.onNext(ClientEvent.newBuilder().setEnd(SessionEnd.newBuilder()
                    .setLastSeq(500L)
                    .putPerTable("t", TableStats.newBuilder().setInserts(500).build())
                    .build()).build());
        });

        assertTrue(appender.list.size() <= 5,
                "500 records must not produce per-record logging, got " + appender.list.size()
                        + " lines: " + atLevel(Level.INFO));
    }

    private void openableBatch(UUID batchId) {
        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(batchId);
        when(batchLifecycle.startBatch(ACCOUNT, SITE)).thenReturn(batch);
    }

    private List<String> atLevel(Level level) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private void runSession(Consumer<StreamObserver<ClientEvent>> client) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        StreamObserver<ServerEvent> responses = new StreamObserver<>() {
            @Override
            public void onNext(ServerEvent event) {
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
        request.onCompleted();
        assertTrue(done.await(5, TimeUnit.SECONDS), "stream did not complete");
    }

    private static ClientEvent start(SessionMode mode, long firstSeq) {
        return ClientEvent.newBuilder()
                .setStart(SessionStart.newBuilder().setMode(mode).setFirstSeq(firstSeq).build())
                .build();
    }

    private static ClientEvent change(long seq) {
        return ClientEvent.newBuilder()
                .setChange(ChangeRecord.newBuilder().setTable("t").setOp(Op.INSERT).setSeq(seq).build())
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
