package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogRetentionService;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.CheckpointService;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3ChangelogSegmentStorage;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
