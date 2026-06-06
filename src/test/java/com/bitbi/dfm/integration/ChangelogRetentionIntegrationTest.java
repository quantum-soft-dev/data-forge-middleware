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
