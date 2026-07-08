package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.DeltaEgressWorker;
import com.bitbi.dfm.delta.application.DeltaSessionCommitService;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T8.4 — delta Parquet egress is <b>event-driven</b>: a session commit wakes the bounded worker
 * pool, which drains pending segments (per-site head first) without waiting for any cron. A
 * backlog of several segments drains from a single wake (the drain loops until empty).
 */
class DeltaEgressWorkerIntegrationTest extends BaseIntegrationTest {

    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654"); // store-01
    /** store-01's seeded IN_PROGRESS batch — commit() completes it. */
    private static final UUID IN_PROGRESS_BATCH = UUID.fromString("b1c2d3e4-f5a6-7890-bcde-f12345678903");
    private static final UUID COMPLETED_BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @Autowired
    private DeltaSessionCommitService commitService;

    @Autowired
    private DeltaEgressWorker egressWorker;

    @Autowired
    private ChangelogSegmentService changelogSegmentService;

    @Autowired
    private ChangelogSegmentRepository segmentRepository;

    @Autowired
    private S3CheckpointStorage storage;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void purgeEgress() {
        // The exists() assertions below must be satisfied by THIS test's egress run,
        // not by a file another test class left under the same site prefix (review r3).
        purgeEgressPrefix(SITE);
    }

    @BeforeEach
    void seedCustomersSchema() {
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

    @Test
    void sessionCommitTriggersDeltaEgressWithoutCron() {
        List<ChangeRecord> records = List.of(
                rec(Op.INSERT, 1L, 1, "Ann"),
                rec(Op.INSERT, 2L, 2, "Bob"));

        commitService.commit(SITE, IN_PROGRESS_BATCH, "DELTA", 1L, 2L, records);

        String key = "egress/" + SITE + "/customers/delta/" + seqRange(1L, 2L) + ".parquet";
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertTrue(storage.exists(key), "delta parquet materialized by the worker: " + key));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertNotNull(segmentRepository.findBySiteIdAndFirstSeq(SITE, 1L).orElseThrow().getEgressAt(),
                        "segment marked egressed"));
    }

    @Test
    void singleWakeDrainsBacklogInSeqOrder() {
        // Backlog of two committed-but-not-egressed segments (e.g. accumulated while workers were down).
        changelogSegmentService.persist(SITE, COMPLETED_BATCH, "DELTA", 1L,
                List.of(rec(Op.INSERT, 1L, 1, "Ann"), rec(Op.INSERT, 2L, 2, "Bob")));
        changelogSegmentService.persist(SITE, COMPLETED_BATCH, "DELTA", 3L,
                List.of(rec(Op.DELETE, 3L, 1, null)));

        egressWorker.wake();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertTrue(storage.exists("egress/" + SITE + "/customers/delta/" + seqRange(1L, 2L) + ".parquet"));
            assertTrue(storage.exists("egress/" + SITE + "/customers/delta/" + seqRange(3L, 3L) + ".parquet"));
        });
    }

    private static String seqRange(long first, long last) {
        return String.format("seq=%019d-%019d", first, last);
    }

    private static ChangeRecord rec(Op op, long seq, long id, String name) {
        ChangeRecord.Builder builder = ChangeRecord.newBuilder()
                .setTable("customers").setOp(op).setSeq(seq)
                .putAllKey(Map.of("id", intVal(id)));
        if (name != null) {
            builder.putAllData(Map.of("id", intVal(id), "name", strVal(name)));
        }
        return builder.build();
    }

    private static Value intVal(long v) {
        return Value.newBuilder().setIntValue(v).build();
    }

    private static Value strVal(String v) {
        return Value.newBuilder().setStringValue(v).build();
    }
}
