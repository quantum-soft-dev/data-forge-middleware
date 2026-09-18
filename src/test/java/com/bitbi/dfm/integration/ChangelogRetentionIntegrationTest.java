package com.bitbi.dfm.integration;

import com.bitbi.dfm.batch.application.BatchHistoryService;
import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.presentation.dto.BatchDetailDto;
import com.bitbi.dfm.batch.presentation.dto.BatchSummaryDto;
import com.bitbi.dfm.batch.presentation.dto.DeltaSeqRangeDto;
import com.bitbi.dfm.batch.presentation.dto.DeltaTableStatsDto;
import com.bitbi.dfm.delta.application.ChangelogRetentionService;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.CheckpointService;
import com.bitbi.dfm.delta.application.DeltaSessionCommitService;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactStatus;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3ChangelogSegmentStorage;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

/**
 * T3.5b — changelog retention prunes segments at/below the durable checkpoint (DB row + S3 object),
 * honoring the audit window. With the window set to 0, every below-checkpoint segment is pruned; a
 * subsequent build still reconstructs correctly because it seeds from the checkpoint frame (T3.5a).
 *
 * <p>Since issue #212 "prunable" additionally requires the segment's queue work to be done:
 * {@code plugin_sql_at} and {@code egress_at} both set. The fixture path
 * ({@code ChangelogSegmentService.persist}) leaves both {@code NULL} — pending — so each method
 * marks its segments processed before expecting a prune, and the hold-back method relies on
 * exactly that pending state surviving.</p>
 */
@TestPropertySource(properties = "delta.retention.audit-window-segments=0")
class ChangelogRetentionIntegrationTest extends BaseIntegrationTest {

    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654"); // store-01
    private static final UUID BATCH1 = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID BATCH2 = UUID.fromString("0199bab2-ca1c-3d0e-441d-adb776a62579");

    @Autowired
    private CheckpointService checkpointService;

    @Autowired
    private ChangelogSegmentService changelogSegmentService;

    @Autowired
    private ChangelogRetentionService retentionService;

    @Autowired
    private ChangelogSegmentRepository segmentRepository;

    @Autowired
    private S3ChangelogSegmentStorage segmentStorage;

    @Autowired
    private CheckpointRepository checkpointRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private BatchParquetArtifactRepository artifactRepository;

    @Autowired
    private DeltaSessionCommitService commitService;

    @Autowired
    private BatchLifecycleService batchLifecycleService;

    @Autowired
    private BatchHistoryService batchHistoryService;

    @Autowired
    private JdbcTemplate jdbc;

    private static final UUID ACCOUNT = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @MockitoSpyBean
    private S3FileStorageService objectDeleter;

    /** Every observed {@code deleteObjects}: was a transaction open, and which keys did it carry. */
    private record ObjectDelete(boolean insideTransaction, List<String> keys) {
    }

    private final List<ObjectDelete> objectDeletes = Collections.synchronizedList(new ArrayList<>());

    /**
     * Installed before the test body runs (issue #234, review round 3), and what that does and does
     * not buy is worth stating precisely (review round 5). It removes the window in which
     * <em>this</em> test's own work — the persist, the checkpoint build — could invoke the spy
     * while it is being stubbed. It does <b>not</b> quiesce a background caller of this
     * context-wide bean (batch retention's cron pass, a wipe in flight); the test profile keeps
     * those sweeps at an hour (#159 / #167), which is what makes the residual improbable, exactly
     * as #175 argued for its counter. Recording is unconditional and thread-safe; the assertion
     * filters by this test's own key, so another caller's round trip is not its business.
     */
    @BeforeEach
    void recordTransactionStateAtObjectDelete() {
        objectDeletes.clear();
        doAnswer(invocation -> {
            List<String> keys = List.copyOf(invocation.getArgument(0));
            objectDeletes.add(new ObjectDelete(
                    TransactionSynchronizationManager.isActualTransactionActive(), keys));
            return invocation.callRealMethod();
        }).when(objectDeleter).deleteObjects(anyList());
    }

    @Test
    void prunesBelowCheckpointSegmentsAndKeepsReconstructionCorrect() {
        // Segment 1 (seq 1..2), then a checkpoint@2 (frame@2 written) — segment 1 is now below checkpoint.
        changelogSegmentService.persist(SITE, BATCH1, "FULL_SNAPSHOT", 1L, List.of(
                rec("customers", Op.INSERT, 1L, key("id", 1L), data("id", 1L, "name", "Ann")),
                rec("customers", Op.INSERT, 2L, key("id", 2L), data("id", 2L, "name", "Bob"))));
        checkpointService.buildCheckpoint(SITE);

        ChangelogSegment seg1 = segmentRepository.findBySiteIdAndFirstSeq(SITE, 1L).orElseThrow();
        String seg1Key = seg1.getS3Key();
        assertTrue(segmentStorage.exists(seg1Key), "segment object exists before prune");
        markSegmentsProcessed(SITE);

        int pruned = retentionService.prune(SITE);

        assertEquals(1, pruned, "the single below-checkpoint segment is pruned (window=0)");
        assertTrue(segmentRepository.findBySiteIdAndFirstSeq(SITE, 1L).isEmpty(), "segment row pruned");
        assertFalse(segmentStorage.exists(seg1Key), "segment S3 object pruned");

        // Reconstruction still correct: a later delta builds on the frame, not the pruned segment.
        changelogSegmentService.persist(SITE, BATCH2, "DELTA", 3L, List.of(
                rec("customers", Op.DELETE, 3L, key("id", 2L), Map.of()),
                rec("customers", Op.INSERT, 4L, key("id", 3L), data("id", 3L, "name", "Cleo"))));
        checkpointService.buildCheckpoint(SITE);

        Checkpoint cp = checkpointRepository.findBySiteIdAndTableName(SITE, "customers").orElseThrow();
        assertEquals(4L, cp.getSeq());
        assertEquals(2L, cp.getRowCount(), "Ann (frame) + Cleo (delta); Bob deleted");
    }

    /**
     * Issue #212 — a below-checkpoint segment whose plugin SQL or egress was never generated
     * survives the prune (and is counted), until its queues drain it; then it is pruned as before.
     *
     * <p>The counters are read as deltas: the registry is shared with every other class of this
     * cached context, so absolute values belong to nobody (#175's discipline).</p>
     */
    @Test
    void holdsBackAPendingSegmentPastTheAuditWindowUntilItsWorkIsDone() {
        changelogSegmentService.persist(SITE, BATCH1, "FULL_SNAPSHOT", 1L, List.of(
                rec("customers", Op.INSERT, 1L, key("id", 1L), data("id", 1L, "name", "Ann"))));
        checkpointService.buildCheckpoint(SITE);

        ChangelogSegment pending = segmentRepository.findBySiteIdAndFirstSeq(SITE, 1L).orElseThrow();
        String pendingKey = pending.getS3Key();
        double sqlBefore = heldBack("pending_plugin_sql");
        double egressBefore = heldBack("pending_egress");

        int held = retentionService.prune(SITE);

        // Review round 1, A4b: both queues are global, so a background drain of any cached
        // context could stamp this row mid-test and flip the outcome. Assessed improbable
        // (#159/#167/#175 keep the sweeps at an hour), but a steal must diagnose itself in one
        // shot — every message re-reads the markers, so a red run says which queue took the row.
        assertEquals(0, held, () -> "a segment with pending queue work is not prunable "
                + "(issue #212); markers now: " + describeMarkers());
        assertTrue(segmentRepository.findBySiteIdAndFirstSeq(SITE, 1L).isPresent(),
                () -> "the pending segment's row survives the prune; markers now: " + describeMarkers());
        assertTrue(segmentStorage.exists(pendingKey),
                () -> "the pending segment's S3 object survives the prune; markers now: " + describeMarkers());
        assertEquals(1.0, heldBack("pending_plugin_sql") - sqlBefore,
                () -> "the hold-back is counted for the pending plugin SQL; markers now: " + describeMarkers());
        assertEquals(1.0, heldBack("pending_egress") - egressBefore,
                () -> "the hold-back is counted for the pending egress; markers now: " + describeMarkers());

        // Once both queues have drained the segment, retention reclaims it exactly as before.
        markSegmentsProcessed(SITE);
        int pruned = retentionService.prune(SITE);

        assertEquals(1, pruned, "the same segment is pruned once its work is done");
        assertTrue(segmentRepository.findBySiteIdAndFirstSeq(SITE, 1L).isEmpty(), "segment row pruned");
        assertFalse(segmentStorage.exists(pendingKey), "segment S3 object pruned");
    }

    /**
     * Issue #244 — a segment whose batch still owes its completed-batch Parquet build survives the
     * prune even with every queue marker set, because the 036/038 finalization replays these raw
     * segments on its next attempt. Once the artifact row is terminal the same segment is pruned.
     */
    @Test
    void holdsBackASegmentWhoseBatchStillOwesItsCompletedBatchParquet() {
        changelogSegmentService.persist(SITE, BATCH1, "FULL_SNAPSHOT", 1L, List.of(
                rec("customers", Op.INSERT, 1L, key("id", 1L), data("id", 1L, "name", "Ann"))));
        checkpointService.buildCheckpoint(SITE);
        markSegmentsProcessed(SITE); // both queues done — only the artifact row holds it now
        UUID artifactId = UUID.randomUUID();
        artifactRepository.insertPendingIfAbsent(artifactId, BATCH1, SITE, "customers",
                LocalDateTime.now(ZoneOffset.UTC));
        double before = heldBack("pending_batch_parquet");

        int held = retentionService.prune(SITE);

        assertEquals(0, held, "a batch that still owes its Parquet build keeps its segments");
        ChangelogSegment survivor = segmentRepository.findBySiteIdAndFirstSeq(SITE, 1L)
                .orElseThrow(() -> new AssertionError("the segment row must survive the prune"));
        assertTrue(segmentStorage.exists(survivor.getS3Key()), "its S3 object survives too");
        assertEquals(1.0, heldBack("pending_batch_parquet") - before,
                "the hold-back is counted under its own reason");

        // The artifact reaches a terminal status (here: the row is gone with its batch) and the
        // same segment is prunable again.
        artifactRepository.deleteByBatchId(BATCH1);
        String key = survivor.getS3Key();
        assertEquals(1, retentionService.prune(SITE), "terminal artifact rows do not hold segments");
        assertTrue(segmentRepository.findBySiteIdAndFirstSeq(SITE, 1L).isEmpty(), "segment row pruned");
        assertFalse(segmentStorage.exists(key), "segment S3 object pruned");
    }

    /**
     * Issue #244 — the artifact half of the conditional DELETE's predicate, against the real
     * statement. A lazy backfill (037) or an admin requeue (039) committing between retention's
     * census read and this delete creates exactly the work row that needs these segments, and SQL
     * inside {@code @Query} is a contract neither the compiler nor CI catches.
     */
    @Test
    void theConditionalDeleteRefusesASegmentWhoseBatchOwesItsParquetAtTheSqlLevel() {
        changelogSegmentService.persist(SITE, BATCH1, "FULL_SNAPSHOT", 1L, List.of(
                rec("customers", Op.INSERT, 1L, key("id", 1L), data("id", 1L, "name", "Ann"))));
        markSegmentsProcessed(SITE);
        ChangelogSegment segment = segmentRepository.findBySiteIdAndFirstSeq(SITE, 1L).orElseThrow();
        String key = segment.getS3Key();
        artifactRepository.insertPendingIfAbsent(UUID.randomUUID(), BATCH1, SITE, "customers",
                LocalDateTime.now(ZoneOffset.UTC));

        assertEquals(0, segmentRepository.deleteByIdIfProcessed(segment.getId(),
                        BatchParquetArtifactStatus.UNFINISHED),
                "an UNFINISHED artifact row must make the statement refuse");
        assertTrue(segmentRepository.findBySiteIdAndFirstSeq(SITE, 1L).isPresent());

        artifactRepository.deleteByBatchId(BATCH1);
        assertEquals(1, segmentRepository.deleteByIdIfProcessed(segment.getId(),
                        BatchParquetArtifactStatus.UNFINISHED),
                "with no unfinished row left the statement deletes");
        assertTrue(segmentRepository.findBySiteIdAndFirstSeq(SITE, 1L).isEmpty());
        segmentStorage.delete(key); // the statement deletes rows only; keep the shared bucket clean
    }

    /**
     * Review round 2, R2-2 — the conditional DELETE's marker predicate, exercised against the
     * real statement. It is the A2 fix's last line of defense (a reinit re-pending the row between
     * retention's read and its delete), and SQL inside {@code @Query} is a contract neither the
     * compiler nor CI catches: with the predicate dropped the whole suite stayed green, because
     * the unit tests stub the return value and the hold-back tests never reach the delete.
     */
    @Test
    void theConditionalDeleteRefusesAPendingRowAtTheSqlLevel() {
        changelogSegmentService.persist(SITE, BATCH1, "FULL_SNAPSHOT", 1L, List.of(
                rec("customers", Op.INSERT, 1L, key("id", 1L), data("id", 1L, "name", "Ann"))));
        ChangelogSegment segment = segmentRepository.findBySiteIdAndFirstSeq(SITE, 1L).orElseThrow();
        String key = segment.getS3Key();

        assertEquals(0, segmentRepository.deleteByIdIfProcessed(segment.getId(),
                        BatchParquetArtifactStatus.UNFINISHED),
                "both markers NULL: the predicate must refuse");
        assertTrue(segmentRepository.findBySiteIdAndFirstSeq(SITE, 1L).isPresent());

        segment.markPluginSqlProcessed(); // egress still owed — the OR must still refuse
        segmentRepository.save(segment);
        assertEquals(0, segmentRepository.deleteByIdIfProcessed(segment.getId(),
                        BatchParquetArtifactStatus.UNFINISHED),
                "one marker NULL: the predicate must still refuse");
        assertTrue(segmentRepository.findBySiteIdAndFirstSeq(SITE, 1L).isPresent());

        markSegmentsProcessed(SITE);
        assertEquals(1, segmentRepository.deleteByIdIfProcessed(segment.getId(),
                        BatchParquetArtifactStatus.UNFINISHED),
                "both markers set: the row is deleted");
        assertTrue(segmentRepository.findBySiteIdAndFirstSeq(SITE, 1L).isEmpty());
        segmentStorage.delete(key); // the statement deletes rows only; keep the shared bucket clean
    }

    /**
     * Issue #234 — the batched {@code DeleteObjects} must run with no transaction open.
     *
     * <p>The unit tests pin the annotation and the call order; only the wired application can show
     * that the repository's own short transactions — the projection read and each conditional row
     * delete, each started by a Spring proxy a unit test does not have — really have committed by
     * the time the objects go. The spy records
     * {@link TransactionSynchronizationManager#isActualTransactionActive()} at the delete and then
     * performs the real one.</p>
     */
    @Test
    void theObjectDeleteRunsWithNoTransactionOpen() {
        changelogSegmentService.persist(SITE, BATCH1, "FULL_SNAPSHOT", 1L, List.of(
                rec("customers", Op.INSERT, 1L, key("id", 1L), data("id", 1L, "name", "Ann"))));
        checkpointService.buildCheckpoint(SITE);
        String prunedKey = segmentRepository.findBySiteIdAndFirstSeq(SITE, 1L).orElseThrow().getS3Key();
        markSegmentsProcessed(SITE);

        assertEquals(1, retentionService.prune(SITE));

        // Scoped to the deletion of this test's own key: a concurrent caller's round trip in the
        // same context is not this assertion's business (review round 1).
        List<Boolean> insideTransaction;
        synchronized (objectDeletes) {
            insideTransaction = objectDeletes.stream()
                    .filter(delete -> delete.keys().contains(prunedKey))
                    .map(ObjectDelete::insideTransaction)
                    .toList();
        }
        assertEquals(List.of(false), insideTransaction,
                "the pruned object must be deleted exactly once, with no transaction open (issue #234)");
        assertTrue(segmentRepository.findBySiteIdAndFirstSeq(SITE, 1L).isEmpty(), "segment row pruned");
        assertFalse(segmentStorage.exists(prunedKey), "segment S3 object pruned");
    }

    // ---- Issue #346: a batch's history survives the pruning of its segments -------------------

    @Test
    void aFinishedSessionShowsTheSameTotalsAfterItsSegmentsArePruned() {
        // The fyt-new report: one session, checkpointed that night, and the next morning Upload
        // History showed only what the audit window kept — with a window of 0, nothing at all.
        UUID batchId = startSession("CONTINUOUS");
        commitService.commitSegment(SITE, batchId, "CONTINUOUS", 1L, 2L, List.of(
                rec("customers", Op.INSERT, 1L, key("id", 1L), data("id", 1L, "name", "Ann")),
                rec("customers", Op.INSERT, 2L, key("id", 2L), data("id", 2L, "name", "Bob"))));
        commitService.commit(SITE, batchId, "CONTINUOUS", 3L, 4L, List.of(
                rec("orders", Op.INSERT, 3L, key("id", 1L), data("id", 1L, "name", "Pen")),
                rec("customers", Op.DELETE, 4L, key("id", 1L), Map.of())));
        BatchSummaryDto listedBefore = listed(batchId);
        BatchDetailDto detailBefore = batchHistoryService.getBatchDetails(batchId, ACCOUNT);
        assertEquals(4L, listedBefore.deltaRecordCount(), "fixture: the session committed four records");

        pruneEverySegmentOf(batchId);

        BatchSummaryDto listedAfter = listed(batchId);
        assertEquals(listedBefore.deltaRecordCount(), listedAfter.deltaRecordCount());
        assertEquals(listedBefore.deltaTableCount(), listedAfter.deltaTableCount());
        assertEquals(2, listedAfter.deltaTableCount());
        BatchDetailDto detailAfter = batchHistoryService.getBatchDetails(batchId, ACCOUNT);
        assertEquals(detailBefore.deltaStats(), detailAfter.deltaStats());
        assertEquals(detailBefore.mode(), detailAfter.mode());
        assertEquals(detailBefore.seqRange(), detailAfter.seqRange());
        assertEquals(List.of(
                        new DeltaTableStatsDto("customers", 2, 0, 1),
                        new DeltaTableStatsDto("orders", 1, 0, 0)),
                detailAfter.deltaStats());
        assertEquals(new DeltaSeqRangeDto(1L, 4L), detailAfter.seqRange());
        assertEquals("CONTINUOUS", detailAfter.mode());
    }

    @Test
    void aSessionWhoseEarlySegmentsArePrunedWhileItRunsStillCountsThem() {
        // Why the totals are added seal by seal rather than computed at SessionEnd: a CONTINUOUS
        // session outlives the nightly checkpoint, and retention deletes its first segments while
        // it is still IN_PROGRESS. Summing the segments at the end would miss them.
        UUID batchId = startSession("CONTINUOUS");
        commitService.commitSegment(SITE, batchId, "CONTINUOUS", 1L, 2L, List.of(
                rec("customers", Op.INSERT, 1L, key("id", 1L), data("id", 1L, "name", "Ann")),
                rec("customers", Op.INSERT, 2L, key("id", 2L), data("id", 2L, "name", "Bob"))));
        checkpointService.buildCheckpoint(SITE);
        markSegmentsProcessed(SITE);
        assertEquals(1, retentionService.prune(SITE), "fixture: the running session's seal is pruned");

        commitService.commit(SITE, batchId, "CONTINUOUS", 3L, 3L, List.of(
                rec("orders", Op.INSERT, 3L, key("id", 1L), data("id", 1L, "name", "Pen"))));

        BatchSummaryDto row = listed(batchId);
        assertEquals(3L, row.deltaRecordCount());
        assertEquals(2, row.deltaTableCount());
        assertEquals(new DeltaSeqRangeDto(1L, 3L),
                batchHistoryService.getBatchDetails(batchId, ACCOUNT).seqRange());
    }

    @Test
    void aBatchStartedBeforeTheTotalsExistedKeepsReadingItsSegments() {
        // total_records NULL: a pre-V58 row, or one a pre-V58 pod started during the rollout.
        // Counting only the segments committed from now on would store a partial total that reads
        // as the whole, so such a batch is left on the segment read.
        UUID batchId = startSession("CONTINUOUS");
        jdbc.update("UPDATE batches SET total_records = NULL, table_count = NULL, table_stats = NULL "
                + "WHERE id = ?", batchId);
        commitService.commit(SITE, batchId, "CONTINUOUS", 1L, 1L, List.of(
                rec("orders", Op.INSERT, 1L, key("id", 1L), data("id", 1L, "name", "Pen"))));

        assertEquals(null, jdbc.queryForObject("SELECT total_records FROM batches WHERE id = ?",
                Long.class, batchId), "an untracked batch must not start tracking half-way");
        assertEquals(1L, listed(batchId).deltaRecordCount(), "read from its segment, as before V58");
    }

    private UUID startSession(String mode) {
        // test-data.sql seeds an IN_PROGRESS batch for store-01; one active batch per site.
        jdbc.update("UPDATE batches SET status = 'FAILED', completed_at = now() AT TIME ZONE 'UTC' "
                + "WHERE site_id = ? AND status = 'IN_PROGRESS'", SITE);
        return batchLifecycleService.startBatch(ACCOUNT, SITE, mode).getId();
    }

    /**
     * What the nightly tick does to a finished session: a checkpoint covering it, then retention
     * (window 0 in this class). The artifact rows its completion enqueued are dropped first —
     * they are #244's hold-back, which is not what this test is about.
     */
    private void pruneEverySegmentOf(UUID batchId) {
        checkpointService.buildCheckpoint(SITE);
        markSegmentsProcessed(SITE);
        jdbc.update("DELETE FROM batch_parquet_artifacts WHERE batch_id = ?", batchId);
        retentionService.prune(SITE);
        assertTrue(segmentRepository.findByBatchId(batchId).isEmpty(),
                "fixture: every segment of the session is pruned");
    }

    private BatchSummaryDto listed(UUID batchId) {
        return batchHistoryService.listBatchHistory(ACCOUNT, null, 100).items().stream()
                .filter(row -> row.id().equals(batchId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("batch " + batchId + " not on the first page"));
    }

    private double heldBack(String reason) {
        return meterRegistry.get("delta.retention.segments.held-back")
                .tag("reason", reason).counter().count();
    }

    private String describeMarkers() {
        return segmentRepository.findBySiteIdAndFirstSeq(SITE, 1L)
                .map(segment -> "plugin_sql_at=" + segment.getPluginSqlAt()
                        + ", egress_at=" + segment.getEgressAt())
                .orElse("row gone");
    }

    private static ChangeRecord rec(String table, Op op, long seq, Map<String, Value> key, Map<String, Value> data) {
        return ChangeRecord.newBuilder().setTable(table).setOp(op).setSeq(seq)
                .putAllKey(key).putAllData(data).build();
    }

    private static Map<String, Value> key(String col, long v) {
        return Map.of(col, Value.newBuilder().setIntValue(v).build());
    }

    private static Map<String, Value> data(Object... kv) {
        Map<String, Value> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object value = kv[i + 1];
            Value v = value instanceof Long l
                    ? Value.newBuilder().setIntValue(l).build()
                    : Value.newBuilder().setStringValue((String) value).build();
            m.put((String) kv[i], v);
        }
        return m;
    }
}
