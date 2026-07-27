package com.bitbi.dfm.integration;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.batch.domain.BatchStatus;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.CheckpointService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.grpc.v2.*;
import com.bitbi.dfm.delta.presentation.DeltaAuthInterceptor;
import com.bitbi.dfm.delta.presentation.DeltaIngestionService;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 030 (T01) — the Delta v2 liveness touch must never collide with a concurrent batch transition.
 *
 * <p>{@code touchActivity} used to be a {@code findById} + {@code save()} on a {@code @Version}ed
 * aggregate, so a timeout sweeper (or a segment commit) racing the touch made one side throw
 * {@link org.springframework.dao.OptimisticLockingFailureException} — and when the loser was the
 * touch, the exception surfaced in the gRPC ingest path and killed a healthy live session. The
 * touch is now a targeted {@code UPDATE ... SET last_activity_at} that does not take part in
 * optimistic locking.</p>
 */
class DeltaSessionLivenessIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private BatchLifecycleService batchLifecycleService;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeltaIngestionService ingestionService;

    @Autowired
    private ChangelogSegmentService changelogSegmentService;

    @Autowired
    private ChangelogSegmentRepository segmentRepository;

    @Autowired
    private CheckpointService checkpointService;

    @Autowired
    private CheckpointRepository checkpointRepository;

    @Autowired
    private DeltaSyncStateService syncStateService;

    @Test
    void concurrentTouchesDoNotBreakBatchCompletion() throws Exception {
        assertTerminalTransitionSurvivesTouchStorm(
                batchLifecycleService::completeBatch, BatchStatus.COMPLETED, "complete");
    }

    @Test
    void concurrentTouchesDoNotBreakBatchFailure() throws Exception {
        assertTerminalTransitionSurvivesTouchStorm(
                batchLifecycleService::failBatch, BatchStatus.FAILED, "fail");
    }

    @Test
    void concurrentTouchesDoNotBreakTimeoutSweep() throws Exception {
        // The sweeper is the transition that actually races a live session in production.
        assertTerminalTransitionSurvivesTouchStorm(
                batchLifecycleService::markBatchNotCompleted, BatchStatus.NOT_COMPLETED, "sweep");
    }

    private void assertTerminalTransitionSurvivesTouchStorm(
            Consumer<UUID> transition, BatchStatus expected, String tag) throws Exception {

        UUID accountId = freshAccount(tag);
        UUID siteId = freshV2Site(accountId, tag);
        UUID batchId = batchLifecycleService.startBatch(accountId, siteId).getId();

        int touchers = 6;
        int touchesPerThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(touchers + 1);
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> escaped = new CopyOnWriteArrayList<>();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < touchers; t++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    for (int n = 0; n < touchesPerThread; n++) {
                        // This is the ingest path's call: it must never throw, before or after the
                        // batch turns terminal underneath it.
                        batchLifecycleService.touchActivity(batchId);
                    }
                    return null;
                }));
            }
            futures.add(pool.submit(() -> {
                go.await();
                Thread.sleep(10); // let the touch storm get going, then transition mid-flight
                transition.accept(batchId);
                return null;
            }));
            go.countDown();

            for (Future<?> future : futures) {
                try {
                    future.get(60, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    escaped.add(e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(escaped.isEmpty(),
                "neither the liveness touch nor the " + tag + " transition may throw: " + escaped);

        Batch reloaded = batchRepository.findById(batchId).orElseThrow();
        assertEquals(expected, reloaded.getStatus(), "the terminal transition wins the race");
        assertNotNull(reloaded.getLastActivityAt(), "liveness was recorded");
    }

    @Test
    void resumedFullSnapshotActuallyWipesThePriorBaseline() throws Exception {
        // 030/T05, observable result: a FULL_SNAPSHOT that drops and resumes must still destroy the
        // prior baseline. Losing the intent across the drop committed it as an ordinary delta, so
        // the stale segments and checkpoints survived and the snapshot folded on top of them.
        UUID accountId = freshAccount("rebase");
        UUID siteId = freshV2Site(accountId, "rebase");
        seedBaseline(accountId, siteId);

        UUID oldSegmentId = segmentRepository.findBySiteIdOrderByFirstSeq(siteId).get(0).getId();
        assertFalse(checkpointRepository.findBySiteId(siteId).isEmpty(), "checkpoint seeded");

        dropThenResume(siteId, accountId, SessionMode.FULL_SNAPSHOT, 100L);

        List<ChangelogSegment> segments = segmentRepository.findBySiteIdOrderByFirstSeq(siteId);
        assertEquals(1, segments.size(), "only the new snapshot segment survives the re-baseline");
        assertNotEquals(oldSegmentId, segments.get(0).getId(), "the pre-baseline segment is gone");
        assertEquals(100L, segments.get(0).getFirstSeq(), "the survivor is the resumed snapshot");
        assertTrue(checkpointRepository.findBySiteId(siteId).isEmpty(),
                "prior checkpoints are wiped, so the next fold sees only the snapshot");
    }

    @Test
    void resumedDeltaSessionLeavesThePriorBaselineIntact() throws Exception {
        // The other half of the sentinel, end-to-end: an ordinary delta session that drops and
        // resumes must never destroy anything. An inverted condition would wipe live baselines.
        UUID accountId = freshAccount("keep");
        UUID siteId = freshV2Site(accountId, "keep");
        seedBaseline(accountId, siteId);

        UUID oldSegmentId = segmentRepository.findBySiteIdOrderByFirstSeq(siteId).get(0).getId();
        int checkpointsBefore = checkpointRepository.findBySiteId(siteId).size();
        syncStateService.advanceWatermark(siteId, 50L); // a delta continues from the watermark

        dropThenResume(siteId, accountId, SessionMode.DELTA, 51L);

        List<ChangelogSegment> segments = segmentRepository.findBySiteIdOrderByFirstSeq(siteId);
        assertEquals(2, segments.size(), "the delta session adds a segment, destroys none");
        assertTrue(segments.stream().anyMatch(s -> s.getId().equals(oldSegmentId)),
                "the pre-existing baseline segment is untouched");
        assertEquals(checkpointsBefore, checkpointRepository.findBySiteId(siteId).size(),
                "checkpoints are untouched by an ordinary delta session");
    }

    /**
     * Open a session in {@code mode} at {@code firstSeq}, stage one record, drop the transport, then
     * reconnect (the resume handshake is always DELTA), replay one more record and end cleanly.
     */
    private void dropThenResume(UUID siteId, UUID accountId, SessionMode mode, long firstSeq)
            throws Exception {
        withIngestion(siteId, accountId, stub -> {
            StreamObserver<ClientEvent> s1 = stub.streamChanges(sink(new CountDownLatch(1)));
            s1.onNext(start(mode, firstSeq));
            s1.onNext(change("customers", firstSeq));
            s1.onError(new RuntimeException("transport drop"));

            CountDownLatch done = new CountDownLatch(1);
            StreamObserver<ClientEvent> s2 = stub.streamChanges(sink(done));
            s2.onNext(start(SessionMode.DELTA, firstSeq));
            s2.onNext(change("customers", firstSeq + 1));
            s2.onNext(ClientEvent.newBuilder().setEnd(SessionEnd.newBuilder()
                    .setLastSeq(firstSeq + 1)
                    .putPerTable("customers", TableStats.newBuilder().setInserts(2).build())
                    .build()).build());
            s2.onCompleted();
            assertTrue(done.await(15, TimeUnit.SECONDS), "resumed stream did not terminate");
        });
    }

    /** Seed a prior baseline for the site: one committed segment plus a built checkpoint. */
    private void seedBaseline(UUID accountId, UUID siteId) {
        jdbc.update("INSERT INTO site_schemas (id, site_id, schema_data, schema_version, created_at, updated_at) "
                        + "VALUES (?, ?, ?::jsonb, 1, now(), now())",
                UUID.randomUUID(), siteId, """
                        {"tables": {"customers": {
                          "columns": [{"name": "id", "type": "bigint", "nullable": false}],
                          "primaryKey": ["id"], "uniqueKeys": []}}}
                        """);
        UUID oldBatchId = batchLifecycleService.startBatch(accountId, siteId).getId();
        changelogSegmentService.persist(siteId, oldBatchId, "FULL_SNAPSHOT", 1L, List.of(
                ChangeRecord.newBuilder().setTable("customers").setOp(Op.INSERT).setSeq(1L)
                        .putKey("id", Value.newBuilder().setIntValue(1).build())
                        .putData("id", Value.newBuilder().setIntValue(1).build())
                        .build()));
        checkpointService.buildCheckpoint(siteId);
        batchLifecycleService.completeBatch(oldBatchId);
    }

    // ---------------------------------------------------------------- helpers

    private void withIngestion(UUID siteId, UUID accountId, GrpcBody body) throws Exception {
        String name = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(ServerInterceptors.intercept(ingestionService, authContext(siteId, accountId)))
                .build()
                .start();
        ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        try {
            body.accept(DeltaIngestionGrpc.newStub(channel));
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface GrpcBody {
        void accept(DeltaIngestionGrpc.DeltaIngestionStub stub) throws Exception;
    }

    private static StreamObserver<ServerEvent> sink(CountDownLatch done) {
        return new StreamObserver<>() {
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
    }

    private static ClientEvent start(SessionMode mode, long firstSeq) {
        return ClientEvent.newBuilder()
                .setStart(SessionStart.newBuilder().setMode(mode).setFirstSeq(firstSeq).build())
                .build();
    }

    private static ClientEvent change(String table, long seq) {
        return ClientEvent.newBuilder().setChange(
                ChangeRecord.newBuilder().setTable(table).setOp(Op.INSERT).setSeq(seq)
                        .putKey("id", Value.newBuilder().setIntValue(seq).build())
                        .putData("id", Value.newBuilder().setIntValue(seq).build())
                        .build()).build();
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

    private UUID freshAccount(String tag) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO accounts (id, email, name, is_active, created_at, updated_at)
                VALUES (?, ?, ?, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, "030-" + tag + "-" + id + "@test.local", "030 " + tag);
        return id;
    }

    private UUID freshV2Site(UUID accountId, String tag) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sites (id, account_id, domain, client_secret_hash, display_name,
                                   is_active, created_at, updated_at, site_name, client_api_version)
                VALUES (?, ?, ?, 'x', ?, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, 'V2')
                """, id, accountId, "030-" + tag + "-" + id + ".test.local", "030 " + tag,
                "030-" + tag + "-" + id);
        return id;
    }
}
