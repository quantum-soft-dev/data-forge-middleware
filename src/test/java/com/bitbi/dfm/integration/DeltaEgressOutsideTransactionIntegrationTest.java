package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.DeltaEgressService;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3ChangelogSegmentStorage;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * Issue #164 — delta egress must not perform S3 I/O while a database transaction is open.
 *
 * <p>The unit tests pin the refusal and the call order; only the wired application can show
 * that the download and the per-table {@code PutObject} genuinely run with no transaction
 * open, because the worker used to start one via a Spring proxy that unit tests do not have.
 * The spies record {@link TransactionSynchronizationManager#isActualTransactionActive()} at
 * the moment of each S3 call and then perform the real one.</p>
 */
class DeltaEgressOutsideTransactionIntegrationTest extends BaseIntegrationTest {

    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    private static final UUID BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @MockitoSpyBean
    private S3ChangelogSegmentStorage segmentStorage;

    @MockitoSpyBean
    private S3CheckpointStorage checkpointStorage;

    @Autowired
    private DeltaEgressService egressService;

    @Autowired
    private ChangelogSegmentService changelogSegmentService;

    @Autowired
    private ChangelogSegmentRepository segmentRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Boolean> downloadInsideTransaction = new ArrayList<>();
    private final List<Boolean> uploadInsideTransaction = new ArrayList<>();

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

    @BeforeEach
    void recordTransactionStateAtS3() {
        downloadInsideTransaction.clear();
        uploadInsideTransaction.clear();
        doAnswer(invocation -> {
            downloadInsideTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(segmentStorage).download(anyString());
        doAnswer(invocation -> {
            uploadInsideTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(checkpointStorage).uploadDelta(any(), anyString(), anyLong(), anyLong(), any(byte[].class));
    }

    @Test
    void egressNextPendingDownloadsAndUploadsWithNoTransactionOpen() {
        ChangelogSegment segment = changelogSegmentService.persist(SITE, BATCH, "DELTA", 9_000_002L, List.of(
                rec("customers", Op.INSERT, 9_000_002L, Map.of("id", intVal(1)),
                        data("id", intVal(1), "name", strVal("Ann")))));

        egressService.egressSegment(segment);

        assertEquals(List.of(false), downloadInsideTransaction,
                "the segment GetObject must not run inside the worker transaction");
        assertEquals(List.of(false), uploadInsideTransaction,
                "the delta PutObject must not run inside the worker transaction");
        ChangelogSegment reloaded = segmentRepository.findBySiteIdAndFirstSeq(SITE, 9_000_002L).orElseThrow();
        assertNotNull(reloaded.getEgressAt(), "the row half still marked the segment egressed");
    }

    private static ChangeRecord rec(String table, Op op, long seq, Map<String, Value> key, Map<String, Value> data) {
        return ChangeRecord.newBuilder().setTable(table).setOp(op).setSeq(seq)
                .putAllKey(key).putAllData(data).build();
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
