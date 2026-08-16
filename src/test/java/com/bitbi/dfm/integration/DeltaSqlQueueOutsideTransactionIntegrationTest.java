package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3ChangelogSegmentStorage;
import com.bitbi.dfm.plugin.application.DeltaSqlQueueService;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.infrastructure.storage.S3SqlFileStorageService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * Issue #164 — the delta-SQL worker must not hold a database connection across S3 or the
 * generation semaphore. The unit tests pin the missing {@code @Transactional} and the
 * refusal; only the wired application can show that the segment download and the SQL
 * {@code PutObject} run with no transaction open.
 */
class DeltaSqlQueueOutsideTransactionIntegrationTest extends BaseIntegrationTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID SITE_ID = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");

    @MockitoSpyBean
    private S3ChangelogSegmentStorage segmentStorage;

    @MockitoSpyBean
    private S3SqlFileStorageService sqlFileStorage;

    @Autowired
    private DeltaSqlQueueService queueService;

    @Autowired
    private ChangelogSegmentService changelogSegmentService;

    @Autowired
    private AccountPluginRepository accountPluginRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Boolean> downloadInsideTransaction = new ArrayList<>();
    private final List<Boolean> uploadInsideTransaction = new ArrayList<>();

    @BeforeEach
    void activatePluginAndSchema() {
        jdbc.update("DELETE FROM plugin_sql_generations WHERE site_id = ?", SITE_ID);
        jdbc.update("UPDATE sites SET client_api_version = 'V2' WHERE id = ?", SITE_ID);
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
        jdbc.update("DELETE FROM site_schemas WHERE site_id = ?", SITE_ID);
        jdbc.update("INSERT INTO site_schemas (id, site_id, schema_data, schema_version, created_at, updated_at) "
                        + "VALUES (?, ?, ?::jsonb, 1, now(), now())",
                UUID.randomUUID(), SITE_ID, schemaJson);
        if (accountPluginRepository.findByAccountIdAndPluginId(ACCOUNT_ID, "bit-bi").isEmpty()) {
            accountPluginRepository.save(AccountPlugin.activate(ACCOUNT_ID, "bit-bi", Map.of("tenantId", "t1")));
        }
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
        }).when(sqlFileStorage).storeSqlFile(any(), any(), anyString());
    }

    @Test
    void processNextPendingReadsAndWritesS3WithNoTransactionOpen() {
        UUID batchId = UUID.randomUUID();
        jdbc.update("INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count, "
                        + "total_size, has_errors, started_at, created_at, completed_at) "
                        + "VALUES (?, ?, ?, 'COMPLETED', ?, 0, 0, false, now(), now(), now())",
                batchId, ACCOUNT_ID, SITE_ID, "delta/" + batchId + "/");
        // A high first_seq so this row cannot collide with store-01 fixtures that use seq 1
        // (uk_segment_site_first_seq) when the full CI suite shares one database.
        changelogSegmentService.persist(SITE_ID, batchId, "DELTA", 9_000_001L, List.of(
                ChangeRecord.newBuilder().setTable("customers").setOp(Op.INSERT).setSeq(9_000_001L)
                        .putAllKey(Map.of("id", Value.newBuilder().setIntValue(1).build()))
                        .putAllData(data("id", Value.newBuilder().setIntValue(1).build(),
                                "name", Value.newBuilder().setStringValue("Ann").build()))
                        .build()));

        assertTrue(queueService.processNextPending());

        assertFalse(downloadInsideTransaction.isEmpty(), "generation must download the segment");
        assertEquals(List.of(false), downloadInsideTransaction.stream().distinct().toList(),
                "the segment GetObject must not run inside the worker transaction");
        assertEquals(List.of(false), uploadInsideTransaction,
                "the SQL PutObject must not run inside the worker transaction");
    }

    private static Map<String, Value> data(Object... kv) {
        Map<String, Value> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Value) kv[i + 1]);
        }
        return m;
    }
}
