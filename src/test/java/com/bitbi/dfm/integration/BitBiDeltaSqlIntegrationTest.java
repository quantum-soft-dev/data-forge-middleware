package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.plugin.application.DeltaSqlQueueService;
import com.bitbi.dfm.plugin.application.PluginApiKeyService;
import com.bitbi.dfm.plugin.application.PluginHistoryService;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaselineRepository;
import com.bitbi.dfm.plugin.domain.PluginSqlGeneration;
import com.bitbi.dfm.plugin.domain.PluginSqlGenerationRepository;
import com.bitbi.dfm.shared.api.ApiRoutes;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T8 (026-bitbi-delta-sql) — end-to-end: changelog segments of a V2 site are rendered into plugin
 * SQL by the queue, served in seq order via {@code /sql-changes}; reinit recaptures baselines and
 * regenerates; a FULL_SNAPSHOT suspends SQL until reinit; a site whose account has no active
 * bit-bi activation gets no SQL and still leaves the queue (issue #175).
 */
@DisplayName("Bit BI delta SQL generation (026, T8)")
class BitBiDeltaSqlIntegrationTest extends BaseIntegrationTest {

    private static final String API_KEY_HEADER = "X-Plugin-Api-Key";
    /** Published contract — the operator-visible signal that a segment was skipped, not lost. */
    private static final String SKIPPED_INACTIVE_COUNTER = "sql.generation.delta.segments.skipped.inactive";
    /** Well above what any method here seeds; see {@link #drainQueue()}. */
    private static final int MAX_DRAIN_ITERATIONS = 100;
    private static final String VALID_API_KEY = "plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6";
    private static final UUID ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    /** store-01 — seeded site under ACCOUNT_ID; flipped to V2 in setup. */
    private static final UUID SITE_ID = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");

    @MockitoBean
    private PluginApiKeyService pluginApiKeyService;

    @Autowired
    private ChangelogSegmentService changelogSegmentService;

    @Autowired
    private DeltaSqlQueueService queueService;

    @Autowired
    private AccountPluginRepository accountPluginRepository;

    @Autowired
    private PluginSqlGenerationRepository sqlGenerationRepository;

    @Autowired
    private PluginDeltaBaselineRepository baselineRepository;

    @Autowired
    private PluginHistoryService pluginHistoryService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    private AccountPlugin activation;

    /** Batches seeded by the running test method — the scope of every generation assertion below. */
    private final List<UUID> seededBatchIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // test-data.sql empties plugin_sql_generations only through the cascades of the rows it
        // deletes, and only at the instant it runs; a generation written afterwards by an async
        // dispatch or by the delta-SQL sweep of another cached context outlives it (issue #159).
        clearPluginSqlGenerations(SITE_ID);
        seededBatchIds.clear();
        jdbc.update("UPDATE sites SET client_api_version = 'V2' WHERE id = ?", SITE_ID);
        declareCustomersSchema();

        activation = accountPluginRepository.save(
                AccountPlugin.activate(ACCOUNT_ID, "bit-bi", Map.of("tenantId", "t1")));
        when(pluginApiKeyService.validateApiKey(VALID_API_KEY)).thenReturn(Optional.of(activation));
    }

    private void declareCustomersSchema() {
        String schemaJson = """
                {
                  "tables": {
                    "customers": {
                      "columns": [
                        {"name": "id", "type": "bigint", "nullable": false},
                        {"name": "name", "type": "varchar(255)", "nullable": true},
                        {"name": "balance", "type": "numeric(12,4)", "nullable": true},
                        {"name": "avatar", "type": "bytea", "nullable": true}
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

    private UUID seedBatch() {
        UUID batchId = UUID.randomUUID();
        jdbc.update("INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count, "
                        + "total_size, has_errors, started_at, created_at, completed_at) "
                        + "VALUES (?, ?, ?, 'COMPLETED', ?, 0, 0, false, now(), now(), now())",
                batchId, ACCOUNT_ID, SITE_ID, "delta/" + batchId + "/");
        seededBatchIds.add(batchId);
        return batchId;
    }

    private void drainQueue() {
        drainQueueWatching(null);
    }

    /**
     * Drain deterministically — the same loop the sweep worker runs, but bounded, and reporting
     * whether <em>this</em> context was the one that took the watched segment.
     *
     * <p>The bound is what a segment the queue claims and never marks processed looks like from
     * here: {@code findNextPendingPluginSql} hands the same row back on the next iteration and the
     * loop never ends. A hung suite says nothing about which property broke, so the drain fails
     * with the pending rows named instead (issue #175, whose branch exists precisely so that a
     * segment with nothing to generate still leaves the queue). The queue this loop drains is
     * global — {@code findNextPendingPluginSql} has no site predicate — so both the bound and the
     * diagnostic are about every site, not this one.</p>
     *
     * @param watched segment whose claim to attribute, or {@code null} to just drain
     * @return {@code true} if one of this loop's own calls marked {@code watched} processed
     */
    private boolean drainQueueWatching(UUID watched) {
        boolean claimedHere = false;
        for (int processed = 0; processed < MAX_DRAIN_ITERATIONS; processed++) {
            boolean pendingBefore = watched != null && !claimedHere && !isProcessed(watched);
            if (!queueService.processNextPending()) {
                return claimedHere;
            }
            if (pendingBefore && isProcessed(watched)) {
                claimedHere = true;
            }
        }
        // The loop above never asks whether the queue is now empty, so a drain of exactly
        // MAX_DRAIN_ITERATIONS live segments would be reported as a runaway.
        if (!queueService.processNextPending()) {
            return claimedHere;
        }
        throw new IllegalStateException("The delta-SQL queue did not drain in " + MAX_DRAIN_ITERATIONS
                + " iterations — a claimed segment is not being marked processed. Still pending: "
                + jdbc.queryForList("SELECT id, site_id FROM changelog_segments "
                        + "WHERE plugin_sql_at IS NULL ORDER BY created_at LIMIT 20"));
    }

    private boolean isProcessed(UUID segmentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT plugin_sql_at IS NOT NULL FROM changelog_segments WHERE id = ?",
                Boolean.class, segmentId));
    }

    /**
     * The generations this test produced, in the order {@code /sql-changes} serves them.
     *
     * <p>The repository call is the one the endpoint makes — a day-wide window on the site, which
     * is what the ordering assertions are about and must not change. What is added is the scope:
     * only the batches this method seeded count, so a generation written for the same site by
     * another test method, by an in-flight {@code @Async} dispatch or by
     * {@link com.bitbi.dfm.plugin.application.DeltaSqlSweepWorker} draining in another cached
     * Spring context cannot be counted into a {@code hasSize(n)} here (issue #159, folding
     * #163).</p>
     *
     * @return this method's generations, oldest first
     */
    private List<PluginSqlGeneration> generationsInSqlChangesOrder() {
        return sqlGenerationRepository.findBySiteIdAndCreatedAtAfter(
                        SITE_ID, LocalDateTime.now().minusDays(1))
                .stream()
                .filter(generation -> seededBatchIds.contains(generation.getSourceBatchId()))
                .toList();
    }

    @Test
    @DisplayName("segments render into SQL in seq order; /sql-changes serves the concatenation")
    void segmentsRenderIntoOrderedSql() throws Exception {
        changelogSegmentService.persist(SITE_ID, seedBatch(), "DELTA", 1L, List.of(
                rec("customers", Op.INSERT, 1L, key(1L),
                        data("name", str("Ann"), "balance", decimal("1E-2"),
                                "avatar", bytes(new byte[]{(byte) 0xAB}))),
                rec("customers", Op.UPDATE, 2L, key(1L), data("name", str("Ann Lee")))));
        changelogSegmentService.persist(SITE_ID, seedBatch(), "DELTA", 3L, List.of(
                rec("customers", Op.DELETE, 3L, key(1L), Map.of())));

        drainQueue();

        List<PluginSqlGeneration> generations = awaitGenerations(2);
        assertThat(generations).hasSize(2);
        assertThat(generations.get(0).getFirstSeq()).isEqualTo(1L);
        assertThat(generations.get(0).getLastSeq()).isEqualTo(2L);
        assertThat(generations.get(1).getFirstSeq()).isEqualTo(3L);
        assertThat(generations.get(0).getComparisonBatchId()).isNull();

        // `since` is derived from this method's own oldest generation rather than left at an
        // epoch-wide 2020 date: the endpoint concatenates every generation the site has after that
        // instant, so the body assertions below would otherwise be exposed to exactly the leftover
        // the counts above were scoped against (issue #159). One second of slack, and the same
        // LocalDateTime/UTC conversion SqlChangesQueryService applies, so the bound does not move
        // with the machine's zone.
        String since = generations.get(0).getCreatedAt().minusSeconds(1).toInstant(ZoneOffset.UTC).toString();
        String sql = mockMvc.perform(get(ApiRoutes.BITBI_PLUGIN_API + "/sql-changes")
                        .param("siteId", SITE_ID.toString())
                        .param("since", since)
                        .header(API_KEY_HEADER, VALID_API_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(sql).contains("INSERT INTO customers");
        assertThat(sql).contains("'Ann'");
        assertThat(sql).contains("0.01");         // BigDecimal via toPlainString
        assertThat(sql).contains("'\\xab'");      // bytea hex literal
        assertThat(sql).contains("UPDATE customers SET name = 'Ann Lee' WHERE id = 1");
        assertThat(sql).contains("DELETE FROM customers WHERE id = 1");
        // seq order: INSERT before UPDATE before DELETE
        assertThat(sql.indexOf("INSERT INTO customers")).isLessThan(sql.indexOf("UPDATE customers"));
        assertThat(sql.indexOf("UPDATE customers")).isLessThan(sql.indexOf("DELETE FROM customers"));
    }

    @Test
    @DisplayName("reinit clears generations, recaptures baselines and regenerates the segments")
    void reinitRegeneratesUnderNewBaselines() {
        changelogSegmentService.persist(SITE_ID, seedBatch(), "DELTA", 1L, List.of(
                rec("customers", Op.INSERT, 1L, key(1L), data("name", str("Ann")))));
        drainQueue();
        assertThat(awaitGenerations(1)).hasSize(1);

        pluginHistoryService.reinit("bit-bi", ACCOUNT_ID);

        // old generations gone, segments re-enqueued (no checkpoints → baseline 0 → regenerate)
        drainQueue();
        List<PluginSqlGeneration> regenerated = awaitGenerations(1);
        assertThat(regenerated).hasSize(1);
        assertThat(regenerated.get(0).getFirstSeq()).isEqualTo(1L);
    }

    @Test
    @DisplayName("FULL_SNAPSHOT emits no SQL and suspends the site's tables until reinit")
    void fullSnapshotSuspendsSql() {
        changelogSegmentService.persist(SITE_ID, seedBatch(), "FULL_SNAPSHOT", 1L, List.of(
                rec("customers", Op.INSERT, 1L, key(1L), data("name", str("Ann")))));
        drainQueue();

        assertNoGenerationsAppear();
        assertThat(baselineRepository.baselineSeqsBySiteId(SITE_ID))
                .containsEntry("customers", Long.MAX_VALUE);

        // subsequent delta for the suspended table produces no SQL
        changelogSegmentService.persist(SITE_ID, seedBatch(), "DELTA", 2L, List.of(
                rec("customers", Op.UPDATE, 2L, key(1L), data("name", str("Bob")))));
        drainQueue();
        assertNoGenerationsAppear();

        // queue fully drained — nothing left pending
        Integer pending = jdbc.queryForObject(
                "SELECT count(*) FROM changelog_segments WHERE site_id = ? AND plugin_sql_at IS NULL",
                Integer.class, SITE_ID);
        assertThat(pending).isZero();
    }

    @Test
    @DisplayName("a deactivated bit-bi activation: no SQL, the segment still leaves the queue, the skip is counted")
    void inactiveActivationSkipsGenerationAndMarksSegmentProcessed() {
        activation.deactivate();
        accountPluginRepository.save(activation);

        assertSegmentIsSkippedButProcessed();
    }

    @Test
    @DisplayName("no bit-bi activation at all: same outcome as a deactivated one")
    void absentActivationSkipsGenerationAndMarksSegmentProcessed() {
        // The queue reaches this through the empty Optional rather than through the isActive
        // filter, so it is the second entry into the same branch and not a repeat of the first.
        accountPluginRepository.delete(activation);

        assertSegmentIsSkippedButProcessed();
    }

    /**
     * The three properties of {@code DeltaSqlQueueService}'s inactive-activation branch, asserted
     * on a segment this method seeded.
     *
     * <p>Marking the segment processed is the property that makes the branch more than a
     * {@code return}: a segment left pending is claimed again on every sweep, for ever, since
     * nothing about the account's activation is going to change on its own.</p>
     *
     * <p>The counter is read as a delta around the drain rather than as an absolute: the registry
     * belongs to the shared Spring context, so a sibling class draining a segment of some other
     * account without an activation contributes to the same series. For the same reason it is
     * asserted only when this context's own drain was what marked the segment: a
     * {@link com.bitbi.dfm.plugin.application.DeltaSqlSweepWorker} of <em>another</em> cached
     * context can win the {@code FOR UPDATE SKIP LOCKED} claim (its once-per-context drain at
     * refresh, or a `wake()` from a sibling class), and it would then increment its own registry
     * while every database-observable property here still holds. The two assertions above are
     * unconditional because they are read from the shared database; this one cannot be. A build in
     * which the branch stopped counting still fails, because the drain that reaches the branch is
     * then this one.</p>
     */
    private void assertSegmentIsSkippedButProcessed() {
        double skippedBefore = skippedInactiveCount();
        ChangelogSegment segment = changelogSegmentService.persist(SITE_ID, seedBatch(), "DELTA", 1L, List.of(
                rec("customers", Op.INSERT, 1L, key(1L), data("name", str("Ann")))));

        boolean claimedHere = drainQueueWatching(segment.getId());

        awaitSegmentProcessed(segment.getId());
        assertNoGenerationsAppear();
        if (claimedHere) {
            assertThat(skippedInactiveCount()).isGreaterThanOrEqualTo(skippedBefore + 1);
        }
    }

    /**
     * Wait until the segment has left the queue ({@code plugin_sql_at} set).
     *
     * <p>Waited for rather than sampled for the reason {@link #awaitGenerations(int)} gives: the
     * manual {@link #drainQueue()} is not the only claimant of a pending segment.</p>
     *
     * @param segmentId the segment this method seeded
     */
    private void awaitSegmentProcessed(UUID segmentId) {
        Awaitility.await("segment " + segmentId + " marked processed")
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> isProcessed(segmentId));
    }

    /**
     * @return the current value of {@link #SKIPPED_INACTIVE_COUNTER}, 0 before its first increment
     */
    private double skippedInactiveCount() {
        Counter counter = meterRegistry.find(SKIPPED_INACTIVE_COUNTER).counter();
        return counter == null ? 0d : counter.count();
    }

    /**
     * Wait until this method's generations are all visible, then return them.
     *
     * <p>The manual {@link #drainQueue()} is not the only writer: a reinit wakes the async sweep
     * pool, and the pool of another cached Spring context can claim the same segments (SKIP LOCKED
     * keeps that safe but not synchronous). Sampling right after the drain is therefore a coin
     * flip — the "Expected size: 1 but was: 0" half of issue #159. Every count assertion in this
     * class goes through here.</p>
     *
     * @param expected number of generations this method should end up with
     * @return the generations, oldest first
     */
    private List<PluginSqlGeneration> awaitGenerations(int expected) {
        return Awaitility.await(expected + " delta SQL generation(s) for site " + SITE_ID)
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .until(this::generationsInSqlChangesOrder, found -> found.size() >= expected);
    }

    /**
     * Assert this method produced no generation, and keep asserting it long enough that a late
     * writer fails the test instead of slipping past a single sample.
     */
    private void assertNoGenerationsAppear() {
        Awaitility.await("no delta SQL generation for site " + SITE_ID)
                .during(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> generationsInSqlChangesOrder().isEmpty());
    }

    // --- proto helpers ---

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

    private static Value decimal(String v) {
        return Value.newBuilder().setDecimalValue(v).build();
    }

    private static Value bytes(byte[] v) {
        return Value.newBuilder().setBytesValue(ByteString.copyFrom(v)).build();
    }
}
