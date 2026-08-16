package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.CheckpointScheduler;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #137 — prune-then-tick. With the audit window at 0 a site keeps no segment row once its
 * changelog is below the checkpoint, so {@code changelog_segments} alone stops naming it and the
 * nightly tick used to walk past a table that still had no snapshot. The tick now also visits the
 * sites named by an unmaterialized checkpoint row — and stops visiting them the moment every table
 * is materialized again.
 */
@TestPropertySource(properties = {
        "delta.retention.audit-window-segments=0",
        "delta.checkpoint.max-materialize-attempts=" + CheckpointSchedulerRematerializeIntegrationTest.MAX_ATTEMPTS
})
class CheckpointSchedulerRematerializeIntegrationTest extends BaseIntegrationTest {

    /** Low enough to exhaust in a test without a loop that hides what is being asserted. */
    static final String MAX_ATTEMPTS = "2";

    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654"); // store-01
    private static final UUID BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    /**
     * Shared Testcontainers DB: many classes persist store-01 at first_seq=1. A leftover
     * that lands between this class's DELETE and persist still trips
     * {@code uk_segment_site_first_seq} (CI on #164, PR run 31954480363). This range is
     * this class's only.
     */
    private static final long FIRST_SEQ = 8_000_001L;

    @Autowired
    private CheckpointScheduler scheduler;

    @Autowired
    private ChangelogSegmentService changelogSegmentService;

    @Autowired
    private ChangelogSegmentRepository segmentRepository;

    @Autowired
    private CheckpointRepository checkpointRepository;

    @Autowired
    private SiteSyncStateRepository syncStateRepository;

    @Autowired
    private S3CheckpointStorage checkpointStorage;

    @Autowired
    private com.bitbi.dfm.delta.application.DeltaCheckpointRebuildService rebuildService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void purgeCheckpointObjects() {
        purgeCheckpointPrefix(SITE);
        // Both methods persist (store-01, first_seq=1). @Sql is per-class, so a leftover
        // from the sibling method — or from another class sharing the Testcontainers DB —
        // trips uk_segment_site_first_seq. Wipe the site's segments per method.
        jdbc.update("DELETE FROM changelog_segments WHERE site_id = ?", SITE);
    }

    @Test
    void rematerializesOnTheNextTickAfterEverySegmentWasPruned() {
        // A first tick with no declared schema: the table is recorded without an artifact, the
        // pointer advances, and retention (window 0) then removes the only segment row.
        changelogSegmentService.persist(SITE, BATCH, "FULL_SNAPSHOT", FIRST_SEQ, List.of(
                rec("customers", Op.INSERT, FIRST_SEQ, key("id", intVal(1)),
                        data("id", intVal(1), "name", strVal("Ann"))),
                rec("customers", Op.INSERT, FIRST_SEQ + 1, key("id", intVal(2)),
                        data("id", intVal(2), "name", strVal("Bob")))));

        scheduler.buildCheckpoints();

        Checkpoint detached = checkpointRepository.findBySiteIdAndTableName(SITE, "customers").orElseThrow();
        assertNull(detached.getS3KeyParquet(), "no declared schema → no artifact on the first tick");
        assertTrue(segmentRepository.findBySiteIdOrderByFirstSeq(SITE).isEmpty(),
                "the audit window is 0, so the below-checkpoint segment is pruned");
        assertFalse(segmentRepository.findDistinctSiteIds().contains(SITE),
                "the segment table no longer names the site — the hole this issue is about");
        assertTrue(checkpointRepository.findSiteIdsWithUnmaterializedCheckpoints(Integer.parseInt(MAX_ATTEMPTS)).contains(SITE),
                "the unmaterialized checkpoint row must keep the site on the tick's work list");
        long pointer = syncStateRepository.findBySiteId(SITE).orElseThrow().getLastCheckpointSeq();
        assertEquals(FIRST_SEQ + 1, pointer);

        // The cause is gone: the schema arrives, and the next tick must find the site again.
        seedCustomersSchema();

        scheduler.buildCheckpoints();

        Checkpoint recovered = checkpointRepository.findBySiteIdAndTableName(SITE, "customers").orElseThrow();
        assertNotNull(recovered.getS3KeyParquet(), "the tick must rematerialize from the frame");
        assertTrue(checkpointStorage.exists(recovered.getS3KeyParquet()), "the snapshot object must exist");
        assertEquals(FIRST_SEQ + 1, recovered.getSeq());
        assertEquals(2L, recovered.getRowCount());
        assertEquals(pointer, syncStateRepository.findBySiteId(SITE).orElseThrow().getLastCheckpointSeq(),
                "a rematerialize must not move the checkpoint pointer");

        // …and now nothing names the site any more: a fully materialized site with no leftover
        // segments is not visited by the following ticks.
        assertFalse(segmentRepository.findDistinctSiteIds().contains(SITE));
        assertFalse(checkpointRepository.findSiteIdsWithUnmaterializedCheckpoints(Integer.parseInt(MAX_ATTEMPTS)).contains(SITE));
    }

    @Test
    void stopsNamingASiteWhoseTableHasSpentItsMaterializeAttempts() {
        // Issue #149. The schema never arrives, so every tick records the same verdict: without a
        // bound the site is visited — frame download, whole-site fold, per-table attempt — every
        // night for the life of the site, behind the single lock the sweep with real work shares.
        changelogSegmentService.persist(SITE, BATCH, "FULL_SNAPSHOT", FIRST_SEQ, List.of(
                rec("customers", Op.INSERT, FIRST_SEQ, key("id", intVal(1)),
                        data("id", intVal(1), "name", strVal("Ann")))));

        int cap = Integer.parseInt(MAX_ATTEMPTS);
        for (int tick = 1; tick <= cap; tick++) {
            scheduler.buildCheckpoints();
            assertEquals(tick,
                    checkpointRepository.findBySiteIdAndTableName(SITE, "customers")
                            .orElseThrow().materializeAttempts(),
                    "every tick that leaves the table without a snapshot spends one attempt");
        }

        assertFalse(checkpointRepository.findSiteIdsWithUnmaterializedCheckpoints(cap).contains(SITE),
                "a row with no attempts left must stop naming its site");
        assertTrue(checkpointRepository.findBySiteIdAndTableName(SITE, "customers")
                        .orElseThrow().hasGivenUpMaterializing(cap),
                "and must be counted instead, so giving up is visible rather than silent");
        // The gauge is a whole-database level and the tick visits every seeded site, so this asserts
        // that the row reached it, not that it is alone there.
        assertTrue(checkpointRepository.countGivenUpMaterializing(cap) >= 1);

        scheduler.buildCheckpoints();

        assertEquals(cap, checkpointRepository.findBySiteIdAndTableName(SITE, "customers")
                        .orElseThrow().materializeAttempts(),
                "a further tick does not reach the table at all");

        // The exit is not manual SQL: the cause is fixed and a forced rebuild re-arms the row.
        seedCustomersSchema();
        rebuildService.requestRebuild(SITE);
        awaitMaterialized();

        Checkpoint recovered = checkpointRepository.findBySiteIdAndTableName(SITE, "customers").orElseThrow();
        assertNotNull(recovered.getS3KeyParquet(), "the forced rebuild materializes from the frame");
        assertEquals(0, recovered.materializeAttempts(), "a snapshot clears the attempt count");
        assertFalse(recovered.hasGivenUpMaterializing(cap), "and puts the row back in the population");
    }

    private void awaitMaterialized() {
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(20))
                .pollInterval(java.time.Duration.ofMillis(200))
                .until(() -> checkpointRepository.findBySiteIdAndTableName(SITE, "customers")
                        .map(checkpoint -> checkpoint.getS3KeyParquet() != null)
                        .orElse(false));
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
