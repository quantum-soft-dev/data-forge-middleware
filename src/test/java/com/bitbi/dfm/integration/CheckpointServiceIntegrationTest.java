package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogCodec;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.CheckpointService;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3.2 — building a checkpoint folds the site's changelog segments into per-table state, writes a
 * checkpoint row per table, and advances the site checkpoint pointer.
 */
class CheckpointServiceIntegrationTest extends BaseIntegrationTest {

    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    private static final UUID BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @Autowired
    private CheckpointService checkpointService;

    @Autowired
    private ChangelogSegmentService changelogSegmentService;

    @Autowired
    private CheckpointRepository checkpointRepository;

    @Autowired
    private SiteSyncStateRepository syncStateRepository;

    @Autowired
    private S3CheckpointStorage checkpointStorage;

    @Test
    void buildsCheckpointRowsAndAdvancesPointer() {
        List<ChangeRecord> records = List.of(
                rec("customers", Op.INSERT, 1L, key("id", 1L), data("id", 1L, "name", "Ann")),
                rec("customers", Op.INSERT, 2L, key("id", 2L), data("id", 2L, "name", "Bob")),
                rec("customers", Op.DELETE, 3L, key("id", 2L), Map.of()));
        changelogSegmentService.persist(SITE, BATCH, "FULL_SNAPSHOT", 1L, records);

        checkpointService.buildCheckpoint(SITE);

        Checkpoint checkpoint = checkpointRepository.findBySiteIdAndTableName(SITE, "customers").orElseThrow();
        assertEquals(1L, checkpoint.getRowCount(), "2 inserts - 1 delete = 1 row");
        assertEquals(3L, checkpoint.getSeq());

        long lastCheckpointSeq =
                syncStateRepository.findBySiteId(SITE).orElseThrow().getLastCheckpointSeq();
        assertEquals(3L, lastCheckpointSeq);
    }

    @Test
    void streamsALargeTableFrameThatTheExistingParseCanRead() {
        // Issue #126: a site big enough that collecting the frame used to blow the heap must
        // still complete, and the file-backed bytes must be the same form parse() already reads.
        final int rows = 3_000;
        List<ChangeRecord> records = new ArrayList<>(rows);
        for (int i = 1; i <= rows; i++) {
            records.add(rec("customers", Op.INSERT, i, key("id", (long) i),
                    data("id", (long) i, "name", "n" + i)));
        }
        changelogSegmentService.persist(SITE, BATCH, "FULL_SNAPSHOT", 1L, records);

        checkpointService.buildCheckpoint(SITE);

        Checkpoint checkpoint = checkpointRepository.findBySiteIdAndTableName(SITE, "customers").orElseThrow();
        assertEquals(rows, checkpoint.getRowCount());
        assertEquals(rows, checkpoint.getSeq());

        List<ChangeRecord> frame = ChangelogCodec.parse(checkpointStorage.downloadFrame(SITE, rows));
        assertEquals(rows, frame.size(), "the streamed frame must contain every surviving row");
        assertTrue(frame.stream().allMatch(r -> r.getOp() == Op.INSERT), "frame is all-INSERT");

        changelogSegmentService.persist(SITE, BATCH, "DELTA", rows + 1L, List.of(
                rec("customers", Op.INSERT, rows + 1L, key("id", rows + 1L),
                        data("id", rows + 1L, "name", "tail"))));
        checkpointService.buildCheckpoint(SITE);

        Checkpoint after = checkpointRepository.findBySiteIdAndTableName(SITE, "customers").orElseThrow();
        assertEquals(rows + 1L, after.getRowCount(), "the next build must seed from the streamed frame");
        assertEquals(rows + 1L, after.getSeq());
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
