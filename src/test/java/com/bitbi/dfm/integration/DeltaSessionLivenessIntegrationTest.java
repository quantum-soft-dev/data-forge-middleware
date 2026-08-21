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
import com.bitbi.dfm.delta.infrastructure.SeededSiteTeardown;
import com.bitbi.dfm.delta.presentation.DeltaAuthInterceptor;
import com.bitbi.dfm.delta.presentation.DeltaIngestionService;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

    /** Sites/accounts seeded by a test, torn down in {@link #cleanUpSeededData()}. */
    private final List<UUID> createdSites = new ArrayList<>();
    private final List<UUID> createdAccounts = new ArrayList<>();

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
    void timeoutSweepSkipsABatchRevivedAfterItsSelect() {
        // 030/T06 — THE regression this whole feature exists to prevent. The sweeper SELECTs expired
        // batches, then transitions them one by one. A live session that touches in between must
        // survive: its transition is evaluated against the same cutoff the SELECT used, so a fresh
        // last_activity_at makes the conditional UPDATE match nothing.
        //
        // Deterministic by construction rather than by thread timing: seed an expired batch, take
        // the cutoff the sweeper's SELECT would have used, THEN touch (the session woke up), then
        // run the transition with that stale cutoff.
        UUID accountId = freshAccount("revived");
        UUID siteId = freshV2Site(accountId, "revived");
        LocalDateTime dbNow = LocalDateTime.now(ZoneOffset.UTC);
        UUID batchId = insertInProgressBatch(accountId, siteId,
                dbNow.minusMinutes(90), dbNow.minusMinutes(70));

        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(60);
        assertTrue(batchRepository.findExpiredBatches(cutoff).stream()
                        .anyMatch(b -> b.getId().equals(batchId)),
                "precondition: the sweeper's SELECT sees this batch as expired");

        batchLifecycleService.touchActivity(batchId); // the live session speaks up

        boolean reaped = batchLifecycleService.markBatchNotCompletedIfStillExpired(batchId, cutoff);

        assertFalse(reaped, "the sweeper must skip a batch that came back to life after its SELECT");
        assertEquals(BatchStatus.IN_PROGRESS, batchRepository.findById(batchId).orElseThrow().getStatus(),
                "a live streaming session must not be killed by the timeout sweeper");
    }

    @Test
    void timeoutSweepStillReapsASilentBatch() {
        // The guard in the other direction: the T06 fix must not make the sweeper impotent. A batch
        // whose last activity predates the cutoff is genuinely abandoned and must be reclaimed,
        // otherwise it blocks its site with ACTIVE_SESSION_EXISTS forever.
        UUID accountId = freshAccount("silent");
        UUID siteId = freshV2Site(accountId, "silent");
        LocalDateTime dbNow = LocalDateTime.now(ZoneOffset.UTC);
        UUID batchId = insertInProgressBatch(accountId, siteId,
                dbNow.minusMinutes(90), dbNow.minusMinutes(70));

        boolean reaped = batchLifecycleService.markBatchNotCompletedIfStillExpired(
                batchId, LocalDateTime.now().minusMinutes(60));

        assertTrue(reaped, "a silent session is still reclaimed");
        assertEquals(BatchStatus.NOT_COMPLETED,
                batchRepository.findById(batchId).orElseThrow().getStatus());
    }

    @Test
    void timeoutSweepReapsLegacyBatchByStartedAt() {
        // v1 batches never touch activity: last_activity_at is NULL and expiry falls back to
        // started_at, exactly as before 029/030.
        UUID accountId = freshAccount("legacy");
        UUID siteId = freshV2Site(accountId, "legacy");
        LocalDateTime dbNow = LocalDateTime.now(ZoneOffset.UTC);
        UUID expired = insertInProgressBatch(accountId, siteId, dbNow.minusMinutes(90), null);
        UUID fresh = insertInProgressBatch(accountId, freshV2Site(accountId, "legacy-b"),
                dbNow.minusMinutes(30), null);

        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(60);

        assertTrue(batchLifecycleService.markBatchNotCompletedIfStillExpired(expired, cutoff),
                "legacy batch older than the timeout is reaped by started_at");
        assertFalse(batchLifecycleService.markBatchNotCompletedIfStillExpired(fresh, cutoff),
                "legacy batch inside the timeout is left alone");
        assertEquals(BatchStatus.NOT_COMPLETED,
                batchRepository.findById(expired).orElseThrow().getStatus());
        assertEquals(BatchStatus.IN_PROGRESS,
                batchRepository.findById(fresh).orElseThrow().getStatus());
    }

    @Test
    void liveSessionSurvivesAConcurrentTimeoutSweep() throws Exception {
        // The same regression under real thread contention: a touch storm against the sweeper's
        // conditional transition. In READ COMMITTED the UPDATE that blocks on a touch re-evaluates
        // its WHERE clause against the committed row, so a live session always wins.
        UUID accountId = freshAccount("sweeprace");
        UUID siteId = freshV2Site(accountId, "sweeprace");
        LocalDateTime dbNow = LocalDateTime.now(ZoneOffset.UTC);
        UUID batchId = insertInProgressBatch(accountId, siteId,
                dbNow.minusMinutes(90), dbNow.minusMinutes(70));
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(60);

        int touchers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(touchers + 1);
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> escaped = new CopyOnWriteArrayList<>();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < touchers; t++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    for (int n = 0; n < 25; n++) {
                        batchLifecycleService.touchActivity(batchId);
                    }
                    return null;
                }));
            }
            futures.add(pool.submit(() -> {
                go.await();
                Thread.sleep(10);
                batchLifecycleService.markBatchNotCompletedIfStillExpired(batchId, cutoff);
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

        assertTrue(escaped.isEmpty(), "nothing may throw: " + escaped);
        assertEquals(BatchStatus.IN_PROGRESS, batchRepository.findById(batchId).orElseThrow().getStatus(),
                "the live session outlives the sweeper");
    }

    private UUID insertInProgressBatch(UUID accountId, UUID siteId,
                                       LocalDateTime startedAt, LocalDateTime lastActivityAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count,
                                     total_size, has_errors, started_at, created_at, last_activity_at)
                VALUES (?, ?, ?, 'IN_PROGRESS', 'p/', 0, 0, false, ?, ?, ?)
                """, id, accountId, siteId, startedAt, startedAt, lastActivityAt);
        return id;
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
        Exception thrown = null;
        try {
            body.accept(DeltaIngestionGrpc.newStub(channel));
        } catch (Exception e) {
            thrown = e;
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
            boolean channelDown = channel.awaitTermination(5, TimeUnit.SECONDS);
            boolean serverDown = server.awaitTermination(5, TimeUnit.SECONDS);
            if (!channelDown || !serverDown) {
                IllegalStateException quiesce = new IllegalStateException(
                        "ingestion gRPC did not quiesce before teardown: channelTerminated="
                                + channel.isTerminated() + " serverTerminated=" + server.isTerminated());
                if (thrown != null) {
                    thrown.addSuppressed(quiesce);
                } else {
                    thrown = quiesce;
                }
            }
        }
        if (thrown != null) {
            throw thrown;
        }
    }

    @FunctionalInterface
    private interface GrpcBody {
        void accept(DeltaIngestionGrpc.DeltaIngestionStub stub) throws Exception;
    }

    /**
     * Remove everything this class created. The suite shares one database, and some queries are
     * genuinely global — {@code findNextPendingPluginSql} claims one pending head <em>per site</em>
     * across all sites, so leaking committed segments here makes an unrelated test see extra rows.
     * Tests that seed their own sites must take them away again.
     *
     * <p>The child sweeps of #226/#228 are necessary and not sufficient: a gRPC commit still in
     * flight (or a sibling context's worker) can insert a new referencing row between those
     * statements, and {@code DELETE FROM batches} then fails as an opaque
     * {@code DataIntegrityViolationException} (issue #265). {@link SeededSiteTeardown} retries
     * once after re-sweeping, and a remaining failure names {@code SQLSTATE} and the constraint.
     */
    @AfterEach
    void cleanUpSeededData() {
        for (UUID siteId : createdSites) {
            SeededSiteTeardown.cleanSite(jdbc, siteId);
        }
        for (UUID accountId : createdAccounts) {
            SeededSiteTeardown.cleanAccount(jdbc, accountId);
        }
        createdSites.clear();
        createdAccounts.clear();
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
        createdAccounts.add(id);
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
        createdSites.add(id);
        return id;
    }
}
