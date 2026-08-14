package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Database-backed gauge for changelog segments still waiting for delta-Parquet egress. */
@Component
public class EgressPendingMetrics implements MeterBinder {

    private static final String METER_NAME = "delta.egress.pending";
    private static final String APP_TAG_KEY = "application";
    private static final String APP_TAG_VALUE = "data-forge-middleware";
    private static final Duration SNAPSHOT_TTL = Duration.ofSeconds(5);

    private final ChangelogSegmentRepository repository;
    private final LongSupplier nanoTime;
    private final long snapshotTtlNanos;
    private volatile Snapshot snapshot = Snapshot.uninitialized();

    @Autowired
    public EgressPendingMetrics(ChangelogSegmentRepository repository) {
        this(repository, System::nanoTime, SNAPSHOT_TTL);
    }

    EgressPendingMetrics(ChangelogSegmentRepository repository, LongSupplier nanoTime,
                         Duration snapshotTtl) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.snapshotTtlNanos = Objects.requireNonNull(snapshotTtl, "snapshotTtl").toNanos();
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        MeterRegistry requiredRegistry = Objects.requireNonNull(registry, "registry");
        Gauge.builder(METER_NAME, this, EgressPendingMetrics::pendingCount)
                .description("Changelog segments waiting for delta Parquet egress")
                .tag(APP_TAG_KEY, APP_TAG_VALUE)
                .register(requiredRegistry);
    }

    private long pendingCount() {
        long now = nanoTime.getAsLong();
        Snapshot current = snapshot;
        if (!isFresh(current, now)) {
            current = refreshSnapshot(now);
        }
        return current.count();
    }

    private synchronized Snapshot refreshSnapshot(long now) {
        Snapshot current = snapshot;
        if (isFresh(current, now)) {
            return current;
        }
        Snapshot refreshed = new Snapshot(repository.countPendingEgress(), nanoTime.getAsLong(), true);
        snapshot = refreshed;
        return refreshed;
    }

    private boolean isFresh(Snapshot candidate, long now) {
        return candidate.initialized()
                && now - candidate.refreshedAtNanos() < snapshotTtlNanos;
    }

    private record Snapshot(long count, long refreshedAtNanos, boolean initialized) {
        private static Snapshot uninitialized() {
            return new Snapshot(0, 0, false);
        }
    }
}
