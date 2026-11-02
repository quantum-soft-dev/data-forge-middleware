package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Database-backed census of segments still pending in each work queue past its poison threshold
 * (issue #404, review round 1).
 *
 * <p>{@code delta.egress.segments.poisoned} and {@code sql.generation.delta.segments.poisoned} are
 * counters moved by a <em>deferral</em> — a failure the queue caught. A segment that kills its
 * process (out of heap, a liveness kill — the incident #404 is about) never reaches one: since #404
 * its attempt is spent at the claim and it backs off by the lease, but no counter moves and no line
 * is logged by the failure itself. These gauges count rows instead, so they see both kinds:</p>
 *
 * <ul>
 *   <li>{@code delta.egress.segments.poisoned.pending} — {@code egress_at IS NULL} and
 *       {@code egress_attempts >= delta.egress.poison-after-attempts}</li>
 *   <li>{@code sql.generation.delta.segments.poisoned.pending} — {@code plugin_sql_at IS NULL} and
 *       {@code plugin_sql_attempts >= plugin.sql-generation.delta-poison-after-attempts}</li>
 * </ul>
 *
 * <p>A census, not a rate: a segment counts for as long as it stays pending past the threshold, and
 * drops out when its work lands, its batch is deleted, or a reinit resets the SQL queue's attempts.
 * Both are queried against the queues' pending partial indexes and cached for a few seconds, the
 * {@link EgressPendingMetrics} shape.</p>
 */
@Component
public class PoisonedSegmentMetrics implements MeterBinder {

    static final String EGRESS_METER = "delta.egress.segments.poisoned.pending";
    static final String PLUGIN_SQL_METER = "sql.generation.delta.segments.poisoned.pending";

    private static final Duration SNAPSHOT_TTL = Duration.ofSeconds(5);

    private final CachedCount egress;
    private final CachedCount pluginSql;

    @Autowired
    public PoisonedSegmentMetrics(ChangelogSegmentRepository repository,
                                  @Value("${delta.egress.poison-after-attempts:7}") int egressThreshold,
                                  @Value("${plugin.sql-generation.delta-poison-after-attempts:7}")
                                  int pluginSqlThreshold) {
        this(repository, egressThreshold, pluginSqlThreshold, System::nanoTime, SNAPSHOT_TTL);
    }

    PoisonedSegmentMetrics(ChangelogSegmentRepository repository, int egressThreshold,
                           int pluginSqlThreshold, LongSupplier nanoTime, Duration snapshotTtl) {
        Objects.requireNonNull(repository, "repository");
        int egressAt = atLeastOne("delta.egress.poison-after-attempts", egressThreshold);
        int pluginSqlAt = atLeastOne("plugin.sql-generation.delta-poison-after-attempts", pluginSqlThreshold);
        long ttlNanos = Objects.requireNonNull(snapshotTtl, "snapshotTtl").toNanos();
        this.egress = new CachedCount(() -> repository.countPendingEgressAttemptedAtLeast(egressAt),
                nanoTime, ttlNanos);
        this.pluginSql = new CachedCount(() -> repository.countPendingPluginSqlAttemptedAtLeast(pluginSqlAt),
                nanoTime, ttlNanos);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        MeterRegistry requiredRegistry = Objects.requireNonNull(registry, "registry");
        Gauge.builder(EGRESS_METER, this, metrics -> metrics.egress.get())
                .description("Segments pending delta-Parquet egress at or past "
                        + "delta.egress.poison-after-attempts attempts, a killed process included")
                .tag("application", "data-forge-middleware")
                .register(requiredRegistry);
        Gauge.builder(PLUGIN_SQL_METER, this, metrics -> metrics.pluginSql.get())
                .description("Segments pending Bit BI delta SQL at or past "
                        + "plugin.sql-generation.delta-poison-after-attempts attempts, a killed process included")
                .tag("application", "data-forge-middleware")
                .register(requiredRegistry);
    }

    private static int atLeastOne(String key, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(key + " must be at least 1, got " + value);
        }
        return value;
    }

    /** One query's result, reused for a few seconds so a scrape storm costs one count. */
    private static final class CachedCount {

        private final LongSupplier query;
        private final LongSupplier nanoTime;
        private final long ttlNanos;
        private long value;
        private long refreshedAt;
        private boolean initialized;

        private CachedCount(LongSupplier query, LongSupplier nanoTime, long ttlNanos) {
            this.query = query;
            this.nanoTime = nanoTime;
            this.ttlNanos = ttlNanos;
        }

        private synchronized long get() {
            long now = nanoTime.getAsLong();
            if (!initialized || now - refreshedAt >= ttlNanos) {
                value = query.getAsLong();
                refreshedAt = nanoTime.getAsLong();
                initialized = true;
            }
            return value;
        }
    }
}
