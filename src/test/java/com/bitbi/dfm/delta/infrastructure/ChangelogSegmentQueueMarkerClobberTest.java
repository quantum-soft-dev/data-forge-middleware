package com.bitbi.dfm.delta.infrastructure;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.integration.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A queue's success mark cannot un-mark the other queue (issue #245).
 *
 * <p>Both workers used to finish with {@code segment.markX(); save(segment)} on the entity
 * captured at claim. Since #164 that claim's {@code FOR UPDATE SKIP LOCKED} lock is released
 * before S3, so a merge of the stale snapshot wrote the other marker back to {@code NULL}.
 * After #212 those columns are also retention's predicate, so the clobber held the segment
 * back from pruning until the re-run stamped it again.</p>
 *
 * <p>The production write is a targeted {@code UPDATE ... SET <this marker> WHERE id = ?},
 * the same shape {@code deferPluginSql}/{@code deferEgress} already use. This class drives
 * the real statements against PostgreSQL — a mock cannot prove two column writes compose.
 * It lives outside {@code integration/} so the per-task gate runs it (the
 * {@link ChangelogSegmentRequeueContractTest} precedent).</p>
 */
@DisplayName("Queue marker writes (issue #245)")
class ChangelogSegmentQueueMarkerClobberTest extends BaseIntegrationTest {

    /** store-01.example.com. */
    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    /** A COMPLETED batch of that site, seeded by test-data.sql. */
    private static final UUID BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @Autowired
    private ChangelogSegmentRepository segmentRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearSiteSegments() {
        jdbc.update("DELETE FROM changelog_segments WHERE site_id = ?", SITE);
    }

    @Test
    @DisplayName("a plugin-SQL mark after a concurrent egress mark leaves both set")
    void sqlMarkAfterEgressMarkLeavesBothSet() {
        UUID id = seedPending().getId();

        assertThat(segmentRepository.markEgressed(id)).isEqualTo(1);
        assertThat(segmentRepository.markPluginSqlProcessed(id)).isEqualTo(1);

        Row row = load(id);
        assertThat(row.pluginSqlAt()).as("this queue's mark landed").isNotNull();
        assertThat(row.egressAt()).as("the other queue's mark survived").isNotNull();
    }

    @Test
    @DisplayName("an egress mark after a concurrent plugin-SQL mark leaves both set")
    void egressMarkAfterSqlMarkLeavesBothSet() {
        UUID id = seedPending().getId();

        assertThat(segmentRepository.markPluginSqlProcessed(id)).isEqualTo(1);
        assertThat(segmentRepository.markEgressed(id)).isEqualTo(1);

        Row row = load(id);
        assertThat(row.egressAt()).as("this queue's mark landed").isNotNull();
        assertThat(row.pluginSqlAt()).as("the other queue's mark survived").isNotNull();
    }

    @Test
    @DisplayName("a plugin-SQL mark leaves the egress retry columns intact")
    void sqlMarkLeavesEgressRetryStateIntact() {
        UUID id = seedPending().getId();
        LocalDateTime retryAt = LocalDateTime.now().plusMinutes(5);
        assertThat(segmentRepository.deferEgress(id, retryAt, 0)).isEqualTo(1);

        assertThat(segmentRepository.markPluginSqlProcessed(id)).isEqualTo(1);

        Row row = load(id);
        assertThat(row.pluginSqlAt()).isNotNull();
        assertThat(row.egressAt()).as("egress is still owed").isNull();
        assertThat(row.egressAttempts()).isEqualTo(1);
        assertThat(row.egressRetryAt()).isEqualToIgnoringNanos(asStored(retryAt));
        assertThat(row.pluginSqlAttempts()).isZero();
        assertThat(row.pluginSqlRetryAt()).isNull();
    }

    @Test
    @DisplayName("an egress mark leaves the plugin-SQL retry columns intact")
    void egressMarkLeavesSqlRetryStateIntact() {
        UUID id = seedPending().getId();
        LocalDateTime retryAt = LocalDateTime.now().plusMinutes(5);
        assertThat(segmentRepository.deferPluginSql(id, retryAt, 0)).isEqualTo(1);

        assertThat(segmentRepository.markEgressed(id)).isEqualTo(1);

        Row row = load(id);
        assertThat(row.egressAt()).isNotNull();
        assertThat(row.pluginSqlAt()).as("plugin SQL is still owed").isNull();
        assertThat(row.pluginSqlAttempts()).isEqualTo(1);
        assertThat(row.pluginSqlRetryAt()).isEqualToIgnoringNanos(asStored(retryAt));
        assertThat(row.egressAttempts()).isZero();
        assertThat(row.egressRetryAt()).isNull();
    }

    @Test
    @DisplayName("two threads marking opposite columns leave both set")
    void concurrentMarksLeaveBothSet() throws Exception {
        UUID id = seedPending().getId();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> sql = pool.submit(() -> {
                barrier.await();
                return segmentRepository.markPluginSqlProcessed(id);
            });
            Future<Integer> egress = pool.submit(() -> {
                barrier.await();
                return segmentRepository.markEgressed(id);
            });
            assertThat(sql.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(egress.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        Row row = load(id);
        assertThat(row.pluginSqlAt()).isNotNull();
        assertThat(row.egressAt()).isNotNull();
    }

    private ChangelogSegment seedPending() {
        ChangelogSegment segment = ChangelogSegment.create(
                SITE, BATCH, 1L, 5L, 5L, "hash-245",
                "delta/" + SITE + "/segments/245.pb.gz", "DELTA", Map.of());
        return segmentRepository.save(segment);
    }

    /**
     * The wall clock a {@link LocalDateTime} parameter actually lands as in the row.
     *
     * <p>These columns are {@code TIMESTAMP} without a zone (V55) and the entity holds them as
     * {@link LocalDateTime}, but {@code hibernate.jdbc.time_zone: UTC} (application.yml) makes
     * Hibernate read a bound {@code LocalDateTime} as JVM-local wall clock and store the same
     * instant in UTC. Production is symmetric — it writes and reads through Hibernate, so the
     * conversion cancels — while this class deliberately reads the <em>row</em> through
     * {@link JdbcTemplate}, where it does not. Writing a UTC wall clock and comparing it with the
     * raw column therefore differed by the JVM's offset: green in CI (UTC) and deterministically
     * red anywhere else, failing the mandatory per-task gate on commits that touch nothing
     * (issue #278, part B; the offset is zero in UTC, so this conversion is the identity there
     * and the assertion holds in every zone).</p>
     */
    private static LocalDateTime asStored(LocalDateTime bound) {
        return bound.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private Row load(UUID id) {
        return jdbc.queryForObject("""
                SELECT plugin_sql_at, egress_at,
                       plugin_sql_attempts, plugin_sql_retry_at,
                       egress_attempts, egress_retry_at
                FROM changelog_segments WHERE id = ?
                """, (rs, i) -> new Row(
                rs.getObject("plugin_sql_at", LocalDateTime.class),
                rs.getObject("egress_at", LocalDateTime.class),
                rs.getInt("plugin_sql_attempts"),
                rs.getObject("plugin_sql_retry_at", LocalDateTime.class),
                rs.getInt("egress_attempts"),
                rs.getObject("egress_retry_at", LocalDateTime.class)), id);
    }

    private record Row(LocalDateTime pluginSqlAt, LocalDateTime egressAt,
                       int pluginSqlAttempts, LocalDateTime pluginSqlRetryAt,
                       int egressAttempts, LocalDateTime egressRetryAt) {
    }
}
