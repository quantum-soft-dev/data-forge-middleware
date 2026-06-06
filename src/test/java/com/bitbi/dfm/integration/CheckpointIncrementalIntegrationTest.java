package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.CheckpointService;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3.5a — {@code buildCheckpoint} is incremental: it seeds from the latest all-INSERT checkpoint
 * frame and folds only the segments after the checkpoint pointer. Reconstruction therefore stays
 * correct even after pre-checkpoint segments are pruned (here: the first segment's metadata row is
 * deleted to simulate retention), which is the prerequisite for T3.5b.
 */
class CheckpointIncrementalIntegrationTest extends BaseIntegrationTest {

    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654"); // store-01
    private static final UUID BATCH1 = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890"); // store-01 batch
    private static final UUID BATCH2 = UUID.fromString("0199bab2-ca1c-3d0e-441d-adb776a62579"); // store-01 batch

    @Autowired
    private CheckpointService checkpointService;

    @Autowired
    private ChangelogSegmentService changelogSegmentService;

    @Autowired
    private CheckpointRepository checkpointRepository;

    @Autowired
    private S3CheckpointStorage checkpointStorage;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void seedsFromCheckpointFrameWhenPreCheckpointSegmentsArePruned() throws Exception {
        // Segment 1 (FULL_SNAPSHOT, seq 1..2): Ann + Bob.
        changelogSegmentService.persist(SITE, BATCH1, "FULL_SNAPSHOT", 1L, List.of(
                rec("customers", Op.INSERT, 1L, key("id", 1L), data("id", 1L, "name", "Ann")),
                rec("customers", Op.INSERT, 2L, key("id", 2L), data("id", 2L, "name", "Bob"))));

        checkpointService.buildCheckpoint(SITE);

        Checkpoint cp1 = checkpointRepository.findBySiteIdAndTableName(SITE, "customers").orElseThrow();
        assertEquals(2L, cp1.getRowCount());
        assertEquals(2L, cp1.getSeq());

        // Simulate retention: drop the pre-checkpoint segment's metadata so a full re-fold is impossible.
        jdbc.update("DELETE FROM changelog_segments WHERE site_id = ? AND batch_id = ?", SITE, BATCH1);

        // Segment 2 (DELTA, seq 3..4): delete Bob, insert Cleo.
        changelogSegmentService.persist(SITE, BATCH2, "DELTA", 3L, List.of(
                rec("customers", Op.DELETE, 3L, key("id", 2L), Map.of()),
                rec("customers", Op.INSERT, 4L, key("id", 3L), data("id", 3L, "name", "Cleo"))));

        checkpointService.buildCheckpoint(SITE);

        Checkpoint cp2 = checkpointRepository.findBySiteIdAndTableName(SITE, "customers").orElseThrow();
        assertEquals(4L, cp2.getSeq(), "advanced to the latest segment seq");
        assertEquals(2L, cp2.getRowCount(), "Ann (from frame) + Cleo (from delta); Bob deleted");

        String csv = ungzip(checkpointStorage.download(cp2.getS3KeyCsv()));
        assertTrue(csv.contains("Ann"), "Ann survives from the checkpoint frame: " + csv);
        assertTrue(csv.contains("Cleo"), "Cleo added by the post-checkpoint delta: " + csv);
        assertFalse(csv.contains("Bob"), "Bob deleted by the post-checkpoint delta: " + csv);
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

    private static String ungzip(byte[] gz) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
