package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.delta.application.ChangelogCodec;
import com.bitbi.dfm.delta.application.ChangelogFold;
import com.bitbi.dfm.delta.application.ChangelogFold.FoldedRow;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.CheckpointService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.ClientEvent;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.ServerEvent;
import com.bitbi.dfm.delta.grpc.v2.SessionEnd;
import com.bitbi.dfm.delta.grpc.v2.SessionMode;
import com.bitbi.dfm.delta.grpc.v2.SessionStart;
import com.bitbi.dfm.delta.grpc.v2.TableStats;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.presentation.DeltaAuthInterceptor;
import com.bitbi.dfm.delta.presentation.DeltaIngestionService;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 033/T07 — issue #82 end to end: a re-baseline larger than the session record cap.
 *
 * <p>Before 033 this was impossible. A {@code FULL_SNAPSHOT} never sealed, so the whole snapshot
 * buffered on-heap; past {@code delta.ingestion.max-session-records} the server answered
 * {@code INTERNAL} + {@code NEED_REBASELINE}, which sent the client straight back into
 * {@code FULL_SNAPSHOT}. A site above the cap was bricked by one click on "Full re-baseline".</p>
 *
 * <p>The cap is lowered to 150 here (seals fire every 100 records) so a 250-record snapshot is
 * genuinely over the limit that used to reject it.</p>
 */
@TestPropertySource(properties = {
        "delta.ingestion.max-session-records=150",
        // Production seals a snapshot every 25,000 records; pinned low here so a 250-record snapshot
        // actually exercises the seal path.
        "delta.ingestion.snapshot-seal-records=100"
})
class SegmentedRebaselineIntegrationTest extends BaseIntegrationTest {

    /** store-01.example.com — test-data.sql clears its segments before every test method. */
    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    private static final UUID ACCOUNT = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID OLD_BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    private static final long SNAPSHOT_RECORDS = 250L;

    @Autowired
    private DeltaIngestionService ingestionService;
    @Autowired
    private ChangelogSegmentService changelogSegmentService;
    @Autowired
    private CheckpointService checkpointService;
    @Autowired
    private DeltaSyncStateService syncStateService;
    @Autowired
    private ChangelogSegmentRepository segmentRepository;
    @Autowired
    private CheckpointRepository checkpointRepository;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void snapshotLargerThanTheRecordCapCompletesAndReplacesTheBaseline() {
        seedSchema();
        releaseActiveBatch();
        seedOldBaseline();
        requestRebaseline();

        List<ServerEvent> events = runSession(req -> {
            req.onNext(start(SessionMode.FULL_SNAPSHOT, 100L));
            for (long i = 0; i < SNAPSHOT_RECORDS; i++) {
                req.onNext(customer(100L + i, 1000L + i));
            }
            req.onNext(ClientEvent.newBuilder().setEnd(SessionEnd.newBuilder()
                    .setLastSeq(100L + SNAPSHOT_RECORDS - 1)
                    .putPerTable("customers", TableStats.newBuilder().setInserts(SNAPSHOT_RECORDS).build())
                    .build()).build());
        });

        assertTrue(events.stream().noneMatch(ServerEvent::hasError),
                "a snapshot above the record cap must commit, not overflow");
        List<ServerEvent> committed = events.stream().filter(ServerEvent::hasCommitted).toList();
        assertEquals(1, committed.size(), "one terminal SessionCommitted, seals stay silent");
        assertEquals(100L + SNAPSHOT_RECORDS - 1, committed.get(0).getCommitted().getCommittedSeq());

        // The rebaseline request is consumed and the watermark lands on the snapshot's last record.
        assertFalse(syncStateService.findSyncState(SITE).orElseThrow().isRebaselineRequested(),
                "rebaseline_requested must be cleared by the completed snapshot");
        assertEquals(100L + SNAPSHOT_RECORDS - 1, syncStateService.getSyncState(SITE).lastAppliedSeq());

        // The old baseline is gone exactly once: only this snapshot's segments survive, all visible.
        assertTrue(segmentRepository.findProvisionalBySiteId(SITE).isEmpty(),
                "every sealed segment is published by the flip");
        List<Long> firstSeqs = segmentRepository.findBySiteIdOrderByFirstSeq(SITE).stream()
                .map(s -> s.getFirstSeq()).toList();
        assertEquals(List.of(100L, 200L, 300L), firstSeqs,
                "three contiguous segments: two silent seals at 100 records plus the tail");

        // The fold sees only the snapshot — the pre-existing row is gone, not merged.
        checkpointService.buildCheckpoint(SITE);
        assertEquals(SNAPSHOT_RECORDS, publishedFrame().get("customers").size(),
                "the checkpoint contains the snapshot's rows and nothing from the old baseline");
    }

    @Test
    void dropMidSnapshotLeavesThePreviousBaselineUsable() {
        seedSchema();
        releaseActiveBatch();
        seedOldBaseline();
        requestRebaseline();

        long watermarkBefore = syncStateService.getSyncState(SITE).lastAppliedSeq();

        // Stream past the first seal, then drop the transport without SessionEnd.
        Context ctx = Context.current()
                .withValue(DeltaAuthInterceptor.SITE_ID, SITE)
                .withValue(DeltaAuthInterceptor.ACCOUNT_ID, ACCOUNT);
        ctx.run(() -> {
            StreamObserver<ClientEvent> req = ingestionService.streamChanges(sink(new ArrayList<>()));
            req.onNext(start(SessionMode.FULL_SNAPSHOT, 100L));
            for (long i = 0; i < 150L; i++) {
                req.onNext(customer(100L + i, 1000L + i));
            }
            req.onError(new RuntimeException("transport drop"));
        });

        // The old baseline is untouched and still the only thing anyone can see.
        assertEquals(watermarkBefore, syncStateService.getSyncState(SITE).lastAppliedSeq(),
                "a provisional seal must not advance the durable watermark");
        assertTrue(syncStateService.findSyncState(SITE).orElseThrow().isRebaselineRequested(),
                "the request survives so the client retries the whole snapshot");
        assertEquals(List.of(1L), segmentRepository.findBySiteIdOrderByFirstSeq(SITE).stream()
                        .map(s -> s.getFirstSeq()).toList(),
                "only the original baseline segment is visible");
        assertFalse(segmentRepository.findProvisionalBySiteId(SITE).isEmpty(),
                "the half-streamed snapshot is retained, but invisible");

        // A forced rebuild rather than the scheduled build, because since issue #149 an idle visit
        // with nothing to rematerialize answers without folding at all — the assertion here is
        // about what a fold *sees*, so it has to ask for one.
        // Read the row the rebuild wrote rather than a fold it returns: since issue #293 a build
        // seeded from a frame streams the site past an in-heap delta and has no fold to hand back.
        checkpointService.rebuildFromFrame(SITE);
        assertEquals(1L,
                checkpointRepository.findBySiteIdAndTableName(SITE, "customers").orElseThrow().getRowCount(),
                "the rebuild still sees the old baseline, not a half-replaced one");
    }

    @Test
    void retryAfterADropCollectsTheOrphansAndSucceeds() {
        seedSchema();
        releaseActiveBatch();
        seedOldBaseline();
        requestRebaseline();

        Context ctx = Context.current()
                .withValue(DeltaAuthInterceptor.SITE_ID, SITE)
                .withValue(DeltaAuthInterceptor.ACCOUNT_ID, ACCOUNT);
        ctx.run(() -> {
            StreamObserver<ClientEvent> req = ingestionService.streamChanges(sink(new ArrayList<>()));
            req.onNext(start(SessionMode.FULL_SNAPSHOT, 100L));
            for (long i = 0; i < 150L; i++) {
                req.onNext(customer(100L + i, 1000L + i));
            }
            req.onError(new RuntimeException("transport drop"));
        });
        assertFalse(segmentRepository.findProvisionalBySiteId(SITE).isEmpty());

        // The retry reuses the same sequence range the abandoned attempt already claimed, which only
        // works because its segments (and their UNIQUE (site_id, first_seq) slots) are collected.
        List<ServerEvent> events = runSession(req -> {
            req.onNext(start(SessionMode.FULL_SNAPSHOT, 100L));
            for (long i = 0; i < SNAPSHOT_RECORDS; i++) {
                req.onNext(customer(100L + i, 1000L + i));
            }
            req.onNext(ClientEvent.newBuilder().setEnd(SessionEnd.newBuilder()
                    .setLastSeq(100L + SNAPSHOT_RECORDS - 1)
                    .putPerTable("customers", TableStats.newBuilder().setInserts(SNAPSHOT_RECORDS).build())
                    .build()).build());
        });

        assertTrue(events.stream().noneMatch(ServerEvent::hasError),
                "the retry must not collide with the abandoned attempt's segments");
        assertTrue(events.get(events.size() - 1).hasCommitted());
        assertFalse(syncStateService.findSyncState(SITE).orElseThrow().isRebaselineRequested());
        assertTrue(segmentRepository.findProvisionalBySiteId(SITE).isEmpty());
        checkpointService.buildCheckpoint(SITE);
        assertEquals(SNAPSHOT_RECORDS, publishedFrame().get("customers").size());
    }

    @Test
    void snapshotOverlappingTheOldBaselineSequenceRangeStillCompletes() {
        // Review finding: provisional seals INSERT while the old baseline is still present. With the
        // (site_id, first_seq) uniqueness spanning all rows, a client re-bootstrapping at a sequence
        // the old baseline already owns collided on its first seal and could never re-baseline.
        seedSchema();
        releaseActiveBatch();
        seedOldBaseline();                       // committed segment at first_seq = 1
        requestRebaseline();

        List<ServerEvent> events = runSession(req -> {
            req.onNext(start(SessionMode.FULL_SNAPSHOT, 1L));   // re-bootstrap over the same range
            for (long i = 0; i < SNAPSHOT_RECORDS; i++) {
                req.onNext(customer(1L + i, 2000L + i));
            }
            req.onNext(ClientEvent.newBuilder().setEnd(SessionEnd.newBuilder()
                    .setLastSeq(SNAPSHOT_RECORDS)
                    .putPerTable("customers", TableStats.newBuilder().setInserts(SNAPSHOT_RECORDS).build())
                    .build()).build());
        });

        assertNoTransportError();
        assertTrue(events.stream().noneMatch(ServerEvent::hasError),
                "a snapshot may reuse sequences the baseline it replaces still occupies");
        assertTrue(events.get(events.size() - 1).hasCommitted());
        assertTrue(segmentRepository.findProvisionalBySiteId(SITE).isEmpty());
        checkpointService.buildCheckpoint(SITE);
        assertEquals(SNAPSHOT_RECORDS, publishedFrame().get("customers").size());
    }

    @Test
    void abandonedSnapshotDoesNotBlockAnOrdinaryDeltaSessionAtTheSameSequence() {
        // Review finding: the watermark deliberately does not move during a snapshot, so an
        // abandoned attempt's first provisional segment sits exactly where the next ordinary session
        // starts. While uniqueness spanned all rows that wedged the site on every later session.
        seedSchema();
        releaseActiveBatch();
        // No committed baseline here on purpose: this pins the orphaned PROVISIONAL row as the
        // blocker. A committed segment at the same first_seq would collide legitimately.
        long watermark = syncStateService.getSyncState(SITE).lastAppliedSeq();

        Context ctx = Context.current()
                .withValue(DeltaAuthInterceptor.SITE_ID, SITE)
                .withValue(DeltaAuthInterceptor.ACCOUNT_ID, ACCOUNT);
        ctx.run(() -> {
            StreamObserver<ClientEvent> req = ingestionService.streamChanges(sink(new ArrayList<>()));
            req.onNext(start(SessionMode.FULL_SNAPSHOT, watermark + 1));
            for (long i = 0; i < 150L; i++) {
                req.onNext(customer(watermark + 1 + i, 3000L + i));
            }
            req.onError(new RuntimeException("transport drop"));
        });
        assertFalse(segmentRepository.findProvisionalBySiteId(SITE).isEmpty(),
                "the abandoned attempt left provisional segments at the next free sequence");

        // A plain CONTINUOUS reconnect starts at exactly that sequence.
        List<ServerEvent> events = runSession(req -> {
            req.onNext(start(SessionMode.CONTINUOUS, watermark + 1));
            req.onNext(customer(watermark + 1, 4000L));
            req.onCompleted();   // continuous sends no SessionEnd; the half-close flushes the tail
        });

        assertNoTransportError();
        assertTrue(events.stream().noneMatch(ServerEvent::hasError),
                "an orphaned provisional segment must not block the next ordinary session");
        assertEquals(watermark + 1, syncStateService.getSyncState(SITE).lastAppliedSeq());
    }

    @Test
    void resumeOntoAReplacementBatchStillPublishesEverythingItSealed() {
        // Review finding: publication is batch-keyed. A resume whose batch was reaped runs under a
        // replacement, and the segments sealed before the drop stayed provisional forever — the
        // snapshot committed a baseline missing them while telling the client it succeeded.
        seedSchema();
        releaseActiveBatch();
        seedOldBaseline();
        requestRebaseline();

        Context ctx = Context.current()
                .withValue(DeltaAuthInterceptor.SITE_ID, SITE)
                .withValue(DeltaAuthInterceptor.ACCOUNT_ID, ACCOUNT);
        ctx.run(() -> {
            StreamObserver<ClientEvent> req = ingestionService.streamChanges(sink(new ArrayList<>()));
            req.onNext(start(SessionMode.FULL_SNAPSHOT, 100L));
            for (long i = 0; i < 150L; i++) {
                req.onNext(customer(100L + i, 1000L + i));
            }
            req.onError(new RuntimeException("transport drop"));
        });
        int sealedBeforeDrop = segmentRepository.findProvisionalBySiteId(SITE).size();
        assertTrue(sealedBeforeDrop > 0);

        // Reap the staged session's batch, exactly as the timeout sweeper would.
        jdbc.update("UPDATE batches SET status = 'NOT_COMPLETED', completed_at = now() AT TIME ZONE 'UTC' "
                + "WHERE site_id = ? AND status = 'IN_PROGRESS'", SITE);

        // The client resumes as DELTA; the server must open a replacement batch and carry the
        // already-sealed segments across to it.
        List<ServerEvent> events = runSession(req -> {
            req.onNext(start(SessionMode.DELTA, 250L));
            for (long i = 0; i < 50L; i++) {
                req.onNext(customer(250L + i, 1150L + i));
            }
            req.onNext(ClientEvent.newBuilder().setEnd(SessionEnd.newBuilder()
                    .setLastSeq(299L)
                    .putPerTable("customers", TableStats.newBuilder().setInserts(200L).build())
                    .build()).build());
        });

        assertNoTransportError();
        assertTrue(events.stream().noneMatch(ServerEvent::hasError), "the resumed snapshot must commit");
        assertTrue(segmentRepository.findProvisionalBySiteId(SITE).isEmpty(),
                "nothing sealed before the drop may be left behind as provisional");
        assertEquals(200L, segmentRepository.findBySiteIdOrderByFirstSeq(SITE).stream()
                        .mapToLong(seg -> seg.getRecordCount()).sum(),
                "the published baseline carries every record the session streamed, not just the tail");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /**
     * test-data.sql seeds an IN_PROGRESS batch for store-01; the one-active-session-per-site rule
     * would reject every session here with ACTIVE_SESSION_EXISTS.
     */
    private void releaseActiveBatch() {
        jdbc.update("UPDATE batches SET status = 'FAILED', completed_at = now() AT TIME ZONE 'UTC' "
                + "WHERE site_id = ? AND status = 'IN_PROGRESS'", SITE);
    }

    /** One pre-existing baseline row + checkpoint, so "the old baseline" is observable. */
    private void seedOldBaseline() {
        changelogSegmentService.persist(SITE, OLD_BATCH, "FULL_SNAPSHOT", 1L, List.of(
                customerRecord(1L, 1L)));
        checkpointService.buildCheckpoint(SITE);
        assertEquals(1L, checkpointRepository.findBySiteIdAndTableName(SITE, "customers")
                .orElseThrow().getRowCount());
    }

    private void requestRebaseline() {
        syncStateService.requestRebaseline(SITE);
    }

    private List<ServerEvent> runSession(Consumer<StreamObserver<ClientEvent>> client) {
        List<ServerEvent> received = new ArrayList<>();
        Context ctx = Context.current()
                .withValue(DeltaAuthInterceptor.SITE_ID, SITE)
                .withValue(DeltaAuthInterceptor.ACCOUNT_ID, ACCOUNT);
        ctx.run(() -> {
            StreamObserver<ClientEvent> req = ingestionService.streamChanges(sink(received));
            client.accept(req);
        });
        return received;
    }

    /** Collected transport errors — a session that dies inside onNext reports here, not as a ServerEvent. */
    private final List<Throwable> transportErrors = new ArrayList<>();

    private StreamObserver<ServerEvent> sink(List<ServerEvent> received) {
        return new StreamObserver<>() {
            @Override
            public void onNext(ServerEvent event) {
                received.add(event);
            }

            @Override
            public void onError(Throwable t) {
                transportErrors.add(t);
            }

            @Override
            public void onCompleted() {
            }
        };
    }

    /** Fail loudly on a stream that died in onNext; otherwise event assertions pass vacuously. */
    private void assertNoTransportError() {
        assertTrue(transportErrors.isEmpty(),
                () -> "stream failed: " + transportErrors.get(0));
    }

    private static ClientEvent start(SessionMode mode, long firstSeq) {
        return ClientEvent.newBuilder()
                .setStart(SessionStart.newBuilder().setMode(mode).setFirstSeq(firstSeq).build())
                .build();
    }

    private static ClientEvent customer(long seq, long id) {
        return ClientEvent.newBuilder().setChange(customerRecord(seq, id)).build();
    }

    private static ChangeRecord customerRecord(long seq, long id) {
        Value idValue = Value.newBuilder().setIntValue(id).build();
        return ChangeRecord.newBuilder()
                .setTable("customers").setOp(Op.INSERT).setSeq(seq)
                .putKey("id", idValue)
                .putData("id", idValue)
                .putData("name", Value.newBuilder().setStringValue("row-" + id).build())
                .build();
    }

    private void seedSchema() {
        String schemaJson = """
                {
                  "tables": {
                    "customers": {
                      "columns": [
                        {"name": "id", "type": "bigint", "nullable": false},
                        {"name": "name", "type": "varchar(255)", "nullable": true}
                      ],
                      "primaryKey": ["id"],
                      "uniqueKeys": []
                    }
                  }
                }
                """;
        jdbc.update("DELETE FROM site_schemas WHERE site_id = ?", SITE);
        jdbc.update("INSERT INTO site_schemas (id, site_id, schema_data, schema_version, created_at, updated_at) "
                        + "VALUES (?, ?, ?::jsonb, 1, now() AT TIME ZONE 'UTC', now() AT TIME ZONE 'UTC')",
                UUID.randomUUID(), SITE, schemaJson);
    }

    /**
     * The state this build published, read back from the reload frame rather than from the value
     * {@code buildCheckpoint} returns (issue #292).
     *
     * <p>A bootstrap or re-baseline build whose whole history is one {@code FULL_SNAPSHOT} session
     * streams straight into the frame and never builds a fold, so there is no in-memory state to
     * return — and the frame is the better subject anyway: it is what the next build seeds from and
     * what a re-baseline is asserted to have replaced.</p>
     */
    @org.springframework.beans.factory.annotation.Autowired
    private S3CheckpointStorage checkpointStorage;

    private Map<String, Map<String, FoldedRow>> publishedFrame() {
        long seq = checkpointRepository.findBySiteIdAndTableName(SITE, "customers").orElseThrow().getSeq();
        Map<String, Map<String, FoldedRow>> state = new java.util.LinkedHashMap<>();
        try (java.io.InputStream frame = checkpointStorage.openFrame(SITE, seq)) {
            ChangelogCodec.forEach(frame, record -> ChangelogFold.apply(state, record));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("could not read the published frame", e);
        }
        return state;
    }
}
