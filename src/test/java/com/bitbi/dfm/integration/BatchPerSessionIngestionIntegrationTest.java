package com.bitbi.dfm.integration;

import com.bitbi.dfm.batch.application.BatchHistoryService;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.batch.presentation.dto.BatchSummaryDto;
import com.bitbi.dfm.batch.presentation.dto.CursorPageResponseDto;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.grpc.v2.*;
import com.bitbi.dfm.delta.presentation.DeltaAuthInterceptor;
import com.bitbi.dfm.delta.presentation.DeltaIngestionService;
import com.bitbi.dfm.shared.domain.events.BatchCompletedEvent;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 029 (T008) — end-to-end: one client session = one batch, whatever the record count.
 * Real Spring beans (PostgreSQL + LocalStack), in-process gRPC transport.
 */
@RecordApplicationEvents
class BatchPerSessionIngestionIntegrationTest extends BaseIntegrationTest {

    private static final UUID ACCOUNT = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @Autowired
    private DeltaIngestionService ingestionService;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private ChangelogSegmentRepository segmentRepository;

    @Autowired
    private BatchHistoryService batchHistoryService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationEvents applicationEvents;

    @Test
    void continuousMultiSegmentSessionProducesOneBatch() throws Exception {
        UUID accountId = freshAccount("one-batch");
        UUID siteId = freshV2Site(accountId, "one-batch");

        runSession(siteId, accountId, req -> {
            req.onNext(start(SessionMode.CONTINUOUS, 1L));
            for (long seq = 1; seq <= 200; seq++) {
                req.onNext(change("orders", seq));
            }
            // Mid-session durability: two segments are already committed while the session's
            // batch is still IN_PROGRESS (SC-005 — no waiting for session end).
            List<UUID> batchIds = jdbc.queryForList(
                    "SELECT id FROM batches WHERE site_id = ?", UUID.class, siteId);
            assertEquals(1, batchIds.size(), "one batch for the open session");
            assertEquals("IN_PROGRESS", jdbc.queryForObject(
                    "SELECT status FROM batches WHERE id = ?", String.class, batchIds.get(0)));
            assertEquals(2, countSegments(batchIds.get(0)), "two 100-record segments sealed mid-session");
            for (long seq = 201; seq <= 250; seq++) {
                req.onNext(change("customers", seq));
            }
        });

        List<Batch> batches = allBatches(siteId);
        assertEquals(1, batches.size(), "one upload attempt = one batch row");
        Batch batch = batches.get(0);
        assertEquals("COMPLETED", batch.getStatus().name());

        List<ChangelogSegment> segments = segmentRepository.findByBatchId(batch.getId());
        assertEquals(3, segments.size(), "segments: [1..100], [101..200], [201..250]");
        long totalRecords = segments.stream().mapToLong(ChangelogSegment::getRecordCount).sum();
        assertEquals(250L, totalRecords);
        for (ChangelogSegment segment : segments) {
            assertEquals("delta/" + siteId + "/segments/" + segment.getId() + ".pb.gz",
                    segment.getS3Key(), "segment keyed by its own id, not the batch id");
        }

        // Upload History: one row with session totals aggregated across the three segments.
        CursorPageResponseDto<BatchSummaryDto> page =
                batchHistoryService.listBatchHistory(accountId, null, 20);
        List<BatchSummaryDto> rows = page.items();
        assertEquals(1, rows.size(), "no 100-record slices, no empty tail row");
        assertEquals(batch.getId(), rows.get(0).id());
        assertEquals(250L, rows.get(0).deltaRecordCount());
        assertEquals(2, rows.get(0).deltaTableCount());

        // Exactly one completion event for the whole session (plugins see one upload attempt).
        long completions = applicationEvents.stream(BatchCompletedEvent.class)
                .filter(e -> e.batchId().equals(batch.getId()))
                .count();
        assertEquals(1L, completions);
    }

    @Test
    void zeroRecordSessionCompletesItsSingleBatchHonestly() throws Exception {
        UUID accountId = freshAccount("empty");
        UUID siteId = freshV2Site(accountId, "empty");

        runSession(siteId, accountId, req -> req.onNext(start(SessionMode.CONTINUOUS, 1L)));

        List<Batch> batches = allBatches(siteId);
        assertEquals(1, batches.size(), "an empty attempt is still one honest batch row");
        assertEquals("COMPLETED", batches.get(0).getStatus().name());
        assertEquals(0, countSegments(batches.get(0).getId()), "no degenerate segment row");
    }

    @Test
    void abortMidStreamFailsTheSingleBatchKeepingSealedSegments() throws Exception {
        UUID accountId = freshAccount("abort");
        UUID siteId = freshV2Site(accountId, "abort");

        List<ServerEvent> received = runSession(siteId, accountId, req -> {
            req.onNext(start(SessionMode.CONTINUOUS, 1L));
            for (long seq = 1; seq <= 100; seq++) {
                req.onNext(change("orders", seq));
            }
            req.onNext(change("orders", 300L)); // sequence gap -> session rejected
        });

        assertTrue(received.stream().anyMatch(ServerEvent::hasError), "gap must reject the session");

        List<Batch> batches = allBatches(siteId);
        assertEquals(1, batches.size(), "still exactly one batch for the failed attempt");
        assertEquals("FAILED", batches.get(0).getStatus().name());
        assertEquals(1, countSegments(batches.get(0).getId()),
                "the segment sealed before the failure stays durable");
    }

    @Test
    void expiredBatchQueryUsesLastActivityWithStartedAtFallback() {
        // One site per batch — "one active batch per site" is enforced by a partial unique index.
        // The UTC wall clock is what every write path stores since #282 — this raw-JDBC seed, the
        // JPA-bound cutoff below, and the production stamps alike — so seeding and querying agree
        // in any JVM zone rather than only in UTC.
        UUID accountId = freshAccount("sweeper");
        LocalDateTime dbNow = LocalDateTime.now(java.time.ZoneOffset.UTC);

        UUID liveOld = insertInProgressBatch(accountId, freshV2Site(accountId, "sw-a"),
                dbNow.minusMinutes(90), dbNow.minusMinutes(5));
        UUID silent = insertInProgressBatch(accountId, freshV2Site(accountId, "sw-b"),
                dbNow.minusMinutes(90), dbNow.minusMinutes(70));
        UUID legacySilent = insertInProgressBatch(accountId, freshV2Site(accountId, "sw-c"),
                dbNow.minusMinutes(90), null);
        UUID legacyFresh = insertInProgressBatch(accountId, freshV2Site(accountId, "sw-d"),
                dbNow.minusMinutes(30), null);

        List<UUID> expired = batchRepository.findExpiredBatches(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(60)).stream()
                .map(Batch::getId)
                .filter(id -> List.of(liveOld, silent, legacySilent, legacyFresh).contains(id))
                .toList();

        assertFalse(expired.contains(liveOld), "old-started but recently active session survives");
        assertTrue(expired.contains(silent), "silent streaming session is reclaimed");
        assertTrue(expired.contains(legacySilent), "legacy batch keeps started_at-based expiry");
        assertFalse(expired.contains(legacyFresh), "fresh legacy batch untouched");
    }

    // ---------------------------------------------------------------- helpers

    private UUID freshAccount(String tag) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO accounts (id, email, name, is_active, created_at, updated_at)
                VALUES (?, ?, ?, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, "029-" + tag + "-" + id + "@test.local", "029 " + tag);
        return id;
    }

    private UUID freshV2Site(UUID accountId, String tag) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sites (id, account_id, domain, client_secret_hash, display_name,
                                   is_active, created_at, updated_at, site_name, client_api_version)
                VALUES (?, ?, ?, 'x', ?, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, 'V2')
                """, id, accountId, "029-" + tag + "-" + id + ".test.local", "029 " + tag,
                "029-" + tag + "-" + id);
        return id;
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

    private List<Batch> allBatches(UUID siteId) {
        return jdbc.queryForList("SELECT id FROM batches WHERE site_id = ?", UUID.class, siteId)
                .stream()
                .map(id -> batchRepository.findById(id).orElseThrow())
                .toList();
    }

    private int countSegments(UUID batchId) {
        return segmentRepository.findByBatchId(batchId).size();
    }

    private List<ServerEvent> runSession(UUID siteId, UUID accountId,
                                         Consumer<StreamObserver<ClientEvent>> client)
            throws IOException, InterruptedException {
        String name = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(ServerInterceptors.intercept(ingestionService, authContext(siteId, accountId)))
                .build()
                .start();
        ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        try {
            List<ServerEvent> received = new CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(1);
            StreamObserver<ClientEvent> request = DeltaIngestionGrpc.newStub(channel)
                    .streamChanges(new StreamObserver<>() {
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
                    });
            client.accept(request);
            request.onCompleted(); // half-close: a still-open continuous session flushes and completes
            assertTrue(done.await(15, TimeUnit.SECONDS), "stream did not terminate");
            return received;
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    private static ClientEvent start(SessionMode mode, long firstSeq) {
        return ClientEvent.newBuilder()
                .setStart(SessionStart.newBuilder().setMode(mode).setFirstSeq(firstSeq).build())
                .build();
    }

    private static ClientEvent change(String table, long seq) {
        return ClientEvent.newBuilder()
                .setChange(ChangeRecord.newBuilder().setTable(table).setOp(Op.INSERT).setSeq(seq).build())
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
