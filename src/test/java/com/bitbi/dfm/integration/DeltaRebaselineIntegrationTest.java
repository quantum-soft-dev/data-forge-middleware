package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogFold.FoldedRow;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.CheckpointService;
import com.bitbi.dfm.delta.application.DeltaRebaselineService;
import com.bitbi.dfm.delta.application.ValueMapper;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #3 — a FULL_SNAPSHOT re-baseline must replace prior state, not merge onto it: rows that existed
 * before but are absent from the snapshot must not survive the next checkpoint.
 */
class DeltaRebaselineIntegrationTest extends BaseIntegrationTest {

    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654"); // store-01
    private static final UUID BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @Autowired
    private CheckpointService checkpointService;

    @Autowired
    private ChangelogSegmentService changelogSegmentService;

    @Autowired
    private DeltaRebaselineService rebaselineService;

    @Autowired
    private CheckpointRepository checkpointRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void rebaselineDropsRowsAbsentFromTheSnapshot() {
        seedCustomersSchema();

        // Original baseline: two rows.
        changelogSegmentService.persist(SITE, BATCH, "FULL_SNAPSHOT", 1L, List.of(
                rec("customers", Op.INSERT, 1L, key("id", intVal(1)), data("id", intVal(1), "name", strVal("Ann"))),
                rec("customers", Op.INSERT, 2L, key("id", intVal(2)), data("id", intVal(2), "name", strVal("Bob")))));
        checkpointService.buildCheckpoint(SITE);
        assertEquals(2L, checkpointRepository.findBySiteIdAndTableName(SITE, "customers").orElseThrow().getRowCount());

        // Re-baseline: discard prior state, then a snapshot that contains only id=1 (id=2 disappeared).
        rebaselineService.reset(SITE, 10L);
        changelogSegmentService.persist(SITE, BATCH, "FULL_SNAPSHOT", 10L, List.of(
                rec("customers", Op.INSERT, 10L, key("id", intVal(1)), data("id", intVal(1), "name", strVal("Ann")))));

        Map<String, Map<String, FoldedRow>> state = checkpointService.buildCheckpoint(SITE);

        assertEquals(1, state.get("customers").size(), "id=2 must not survive the re-baseline");
        FoldedRow only = state.get("customers").values().iterator().next();
        assertEquals(1L, ValueMapper.toJava(only.key().get("id")), "the surviving row is the snapshot's id=1");
        Checkpoint cp = checkpointRepository.findBySiteIdAndTableName(SITE, "customers").orElseThrow();
        assertEquals(1L, cp.getRowCount(), "checkpoint reflects only the snapshot rows");
        assertEquals(10L, cp.getSeq());
    }

    private void seedCustomersSchema() {
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
                        + "VALUES (?, ?, ?::jsonb, 1, now(), now())",
                UUID.randomUUID(), SITE, schemaJson);
    }

    private static ChangeRecord rec(String table, Op op, long seq, Map<String, Value> key, Map<String, Value> data) {
        return ChangeRecord.newBuilder().setTable(table).setOp(op).setSeq(seq)
                .putAllKey(key).putAllData(data).build();
    }

    private static Map<String, Value> key(String col, Value v) {
        return Map.of(col, v);
    }

    private static Map<String, Value> data(Object... kv) {
        Map<String, Value> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Value) kv[i + 1]);
        }
        return m;
    }

    private static Value intVal(long v) {
        return Value.newBuilder().setIntValue(v).build();
    }

    private static Value strVal(String v) {
        return Value.newBuilder().setStringValue(v).build();
    }
}
