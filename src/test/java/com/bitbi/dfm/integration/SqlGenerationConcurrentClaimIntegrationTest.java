package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.plugin.application.DeltaSqlQueueService;
import com.bitbi.dfm.plugin.application.SqlGenerationService;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginSqlGeneration;
import com.bitbi.dfm.plugin.infrastructure.storage.S3SqlFileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

/**
 * Two concurrent {@link SqlGenerationService#generateSqlForBatch} calls for one batch end with
 * exactly one generation — the DoD checkbox of issue #190 that outlives the retired regeneration
 * path.
 *
 * <p>The property under test is the <em>durable claim</em> of issue #164:
 * {@code uk_sql_gen_source_batch UNIQUE (source_batch_id)} (V11) is what serializes two workers
 * racing to persist SQL for one batch now that the delta-SQL queue no longer holds
 * {@code SKIP LOCKED} across S3. The loser's {@code DataIntegrityViolationException} is caught by
 * {@code persistOrAdoptExisting}, its orphaned S3 object is deleted, and the winner's row is
 * adopted — so both callers succeed and agree on the same generation. A mock-level twin exists in
 * {@code SqlGenerationServiceTest} (it stubs the violation); only this test drives the real
 * constraint in PostgreSQL, which is the half a mock cannot prove.</p>
 *
 * <p><strong>Why the race is deterministic rather than hoped for.</strong> The idempotency guard
 * ({@code existsBySourceBatchId}) runs in phase 1, before any S3 write; the INSERT runs in
 * phase 3, after it. The {@link MockitoSpyBean} on {@link S3SqlFileStorageService} holds both
 * threads at {@code storeSqlFile} — between the two phases — until both have arrived, so both
 * have passed the guard before either persists and exactly one INSERT can win. Without the
 * barrier the second thread could see the winner's committed row at the guard and return through
 * a branch this test is not about. The barrier is scoped to this test's site, so a drain of
 * another class's leftover queue rows in this context passes through untouched.</p>
 *
 * <p><strong>Proven by mutation</strong>, since the claim already holds on {@code develop} and no
 * new test can start red against it: with the {@code DataIntegrityViolationException} catch in
 * {@code persistOrAdoptExisting} removed, the losing thread throws instead of adopting and this
 * test fails on the future's exception; with the adopt lookup removed, it fails on the callers
 * disagreeing.</p>
 *
 * <p>The seeded segment is immediately marked {@code plugin_sql_at = now()} (and
 * {@code egress_at}), so the <em>global</em> delta-SQL queue — which has no site predicate
 * (issue #175) — can never hand this batch to a sweep worker of this or another cached context:
 * the only two calls that can generate for it are the two this test races. The spy makes this
 * class's Spring context its own, which is the usual price of a bean override here
 * (the {@code S3ChangelogSegmentStorage} spy of issue #147 is the precedent).</p>
 */
@DisplayName("generateSqlForBatch — the unique claim under two concurrent callers (#190)")
class SqlGenerationConcurrentClaimIntegrationTest extends BaseIntegrationTest {

    /** Seeded account (test-data.sql) owning {@link #SITE_ID}. */
    private static final UUID ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    /** store-01.example.com — seeded site, flipped to V2 in setup. */
    private static final UUID SITE_ID = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    /** Bounded like BitBiDeltaSqlIntegrationTest.drainQueue — a claimed-but-unmarked row loops for ever. */
    private static final Duration MAX_DRAIN_TIME = Duration.ofMinutes(3);

    @MockitoSpyBean
    private S3SqlFileStorageService s3SqlFileStorageService;

    @Autowired
    private ChangelogSegmentService changelogSegmentService;

    @Autowired
    private SqlGenerationService sqlGenerationService;

    @Autowired
    private DeltaSqlQueueService queueService;

    @Autowired
    private AccountPluginRepository accountPluginRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private AccountPlugin activation;

    @BeforeEach
    void setUp() {
        clearPluginSqlGenerations(SITE_ID);
        jdbc.update("UPDATE sites SET client_api_version = 'V2' WHERE id = ?", SITE_ID);
        declareCustomersSchema();
        activation = accountPluginRepository.save(
                AccountPlugin.activate(ACCOUNT_ID, "bit-bi", Map.of("tenantId", "t1")));
    }

    @Test
    @DisplayName("two concurrent calls for one batch end with exactly one generation, adopted by both")
    void twoConcurrentCallsEndWithExactlyOneGeneration() throws Exception {
        // Empty the shared queue first so its rows cannot occupy the two semaphore permits
        // (max-concurrent 2) while this test's threads wait at the barrier.
        drainQueue();

        UUID batchId = seedBatch();
        changelogSegmentService.persist(SITE_ID, batchId, "DELTA", 1L, List.of(
                rec("customers", Op.INSERT, 1L, key(1L), data("name", str("Ann")))));
        // Take the segment out of both background queues — see the class Javadoc.
        jdbc.update("UPDATE changelog_segments SET plugin_sql_at = now(), egress_at = now() WHERE batch_id = ?",
                batchId);

        CyclicBarrier bothPastTheGuard = new CyclicBarrier(2);
        doAnswer(invocation -> {
            if (SITE_ID.equals(invocation.getArgument(1))) {
                // Generous bound: a permit can be held briefly by an unrelated in-context drain.
                bothPastTheGuard.await(120, TimeUnit.SECONDS);
            }
            return invocation.callRealMethod();
        }).when(s3SqlFileStorageService).storeSqlFile(any(), any(), anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<PluginSqlGeneration>> first = executor.submit(
                    () -> sqlGenerationService.generateSqlForBatch(batchId, activation.getId()));
            Future<Optional<PluginSqlGeneration>> second = executor.submit(
                    () -> sqlGenerationService.generateSqlForBatch(batchId, activation.getId()));

            Optional<PluginSqlGeneration> firstResult = first.get(180, TimeUnit.SECONDS);
            Optional<PluginSqlGeneration> secondResult = second.get(180, TimeUnit.SECONDS);

            // Both callers succeed — the loser adopts the winner's row rather than failing.
            assertThat(firstResult).as("first caller must end with a generation").isPresent();
            assertThat(secondResult).as("second caller must end with a generation").isPresent();
            assertThat(firstResult.get().getId())
                    .as("both callers must agree on the one surviving generation")
                    .isEqualTo(secondResult.get().getId());

            // The claim held: exactly one row for the batch.
            Integer rows = jdbc.queryForObject(
                    "SELECT count(*) FROM plugin_sql_generations WHERE source_batch_id = ?",
                    Integer.class, batchId);
            assertThat(rows).as("uk_sql_gen_source_batch must leave exactly one generation").isEqualTo(1);

            // Both rendered and uploaded; the loser's orphaned object was removed, and it was
            // the loser's — the surviving row still names the winner's key.
            verify(s3SqlFileStorageService, org.mockito.Mockito.times(2))
                    .storeSqlFile(any(), any(), anyString());
            String survivingKey = firstResult.get().getS3Key();
            verify(s3SqlFileStorageService)
                    .deleteFile(argThat(key -> key != null && !key.equals(survivingKey)));
        } finally {
            executor.shutdownNow();
        }
    }

    private UUID seedBatch() {
        UUID batchId = UUID.randomUUID();
        jdbc.update("INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count, "
                        + "total_size, has_errors, started_at, created_at, completed_at) "
                        + "VALUES (?, ?, ?, 'COMPLETED', ?, 0, 0, false, now(), now(), now())",
                batchId, ACCOUNT_ID, SITE_ID, "delta/" + batchId + "/");
        return batchId;
    }

    private void declareCustomersSchema() {
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
    }

    /** The queue is global (no site predicate), so the bound is a deadline, not an iteration count. */
    private void drainQueue() {
        Instant deadline = Instant.now().plus(MAX_DRAIN_TIME);
        while (queueService.processNextPending()) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("The delta-SQL queue did not drain within " + MAX_DRAIN_TIME
                        + "; still pending (any site): "
                        + jdbc.queryForList("SELECT id, site_id FROM changelog_segments "
                        + "WHERE plugin_sql_at IS NULL ORDER BY created_at LIMIT 20"));
            }
        }
    }

    // --- proto helpers (the BitBiDeltaSqlIntegrationTest shape) ---

    private static ChangeRecord rec(String table, Op op, long seq, Map<String, Value> key, Map<String, Value> data) {
        return ChangeRecord.newBuilder().setTable(table).setOp(op).setSeq(seq)
                .putAllKey(key).putAllData(data).build();
    }

    private static Map<String, Value> key(long id) {
        return Map.of("id", Value.newBuilder().setIntValue(id).build());
    }

    private static Map<String, Value> data(Object... kv) {
        Map<String, Value> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Value) kv[i + 1]);
        }
        return m;
    }

    private static Value str(String v) {
        return Value.newBuilder().setStringValue(v).build();
    }
}
