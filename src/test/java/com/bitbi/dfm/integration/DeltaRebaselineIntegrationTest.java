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
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Autowired
    private SiteSyncStateRepository syncStateRepository;

    @Autowired
    private S3CheckpointStorage checkpointStorage;

    @Autowired
    private TransactionTemplate transactionTemplate;

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

    @Test
    void concurrentCheckpointBuildCannotOutliveTheRebaseline() throws Exception {
        // Issue #142. The wipe half of this race was closed in #136, but a re-baseline leaves the
        // generation alone by design (035 — moving it would tell the client to reset its counters),
        // so a build that overlapped one used to pass the guard and restore the pointer of the
        // baseline that had just been discarded. Unlike the wipe case it is silent: reset leaves the
        // old frame in S3, so the *next* build would find frameExists true at the restored seq and
        // seed the fold from the discarded baseline — rows deleted at the source back in every
        // checkpoint Parquet, with no pruning alarm and no refused refold.
        seedCustomersSchema();
        purgeCheckpointPrefix(SITE);
        changelogSegmentService.persist(SITE, BATCH, "FULL_SNAPSHOT", 1L, List.of(
                rec("customers", Op.INSERT, 1L, key("id", intVal(1)), data("id", intVal(1), "name", strVal("Ann"))),
                rec("customers", Op.INSERT, 2L, key("id", intVal(2)), data("id", intVal(2), "name", strVal("Bob")))));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch locked = new CountDownLatch(1);
        Future<?> rebaseline = executor.submit(() -> transactionTemplate.execute(status -> {
            // The mutex reset itself takes first; taken here explicitly so the test can synchronize
            // on it (inside reset the row lock would only be observable at flush time).
            syncStateRepository.findBySiteIdForUpdate(SITE);
            locked.countDown();
            holdTheRowLockWhileTheBuildRuns();
            rebaselineService.reset(SITE, 10L);
            return null;
        }));
        assertTrue(locked.await(10, TimeUnit.SECONDS));

        try {
            // Reads baseline epoch 0; every row it writes waits for the lock above and is refused.
            checkpointService.buildCheckpoint(SITE);
            rebaseline.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        // Self-verifying: the build must actually have reached the write it was refused at. Its
        // uploads survive (reset deletes segment objects only), so the snapshot object proves it ran
        // rather than having started after the commit with nothing left to fold.
        assertTrue(checkpointStorage.listKeys(S3CheckpointStorage.checkpointPrefix(SITE)).stream()
                        .anyMatch(k -> k.endsWith("seq=2/snapshot.parquet")),
                "the racing build must have uploaded its snapshot before the guard refused it");

        Map<String, Object> state = jdbc.queryForMap("SELECT * FROM site_sync_state WHERE site_id = ?", SITE);
        assertEquals(0L, state.get("generation"),
                "an ordinary re-baseline must not move the wire epoch (035)");
        assertEquals(1L, state.get("baseline_epoch"), "the guard's epoch is what moved");
        assertEquals(0L, state.get("last_checkpoint_seq"),
                "a restored pointer would name the discarded baseline's frame");
        assertEquals(0L, count("SELECT COUNT(*) FROM checkpoints WHERE site_id = ?"),
                "a resurrected checkpoint row would serve pre-re-baseline bytes");

        // And the snapshot that replaces the baseline folds alone: id=2 is gone at the source and
        // must not come back through frame@2, which is still sitting in the bucket.
        changelogSegmentService.persist(SITE, BATCH, "FULL_SNAPSHOT", 10L, List.of(
                rec("customers", Op.INSERT, 10L, key("id", intVal(1)), data("id", intVal(1), "name", strVal("Ann")))));

        Map<String, Map<String, FoldedRow>> folded = checkpointService.buildCheckpoint(SITE);

        assertEquals(1, folded.get("customers").size(),
                "the discarded baseline's frame must not seed the new baseline's fold");
        assertEquals(10L, checkpointRepository.findBySiteIdAndTableName(SITE, "customers").orElseThrow().getSeq());
    }

    /** Hold the row long enough for the racing build to reach its first guarded write. */
    private static void holdTheRowLockWhileTheBuildRuns() {
        try {
            Thread.sleep(1500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class, SITE);
        return value == null ? 0L : value;
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
