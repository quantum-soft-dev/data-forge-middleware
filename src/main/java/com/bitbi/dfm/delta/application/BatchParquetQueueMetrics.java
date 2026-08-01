package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactStatus;
import com.bitbi.dfm.delta.domain.BatchParquetQueueDepth;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Database-backed gauges for the durable completed-batch Parquet work queue. */
@Component
public class BatchParquetQueueMetrics implements MeterBinder {

    private static final String METER_NAME = "delta.batch-parquet.queue";
    private static final String APP_TAG_KEY = "application";
    private static final String APP_TAG_VALUE = "data-forge-middleware";
    private static final Duration SNAPSHOT_TTL = Duration.ofSeconds(5);

    private final BatchParquetArtifactRepository repository;
    private final LongSupplier nanoTime;
    private final long snapshotTtlNanos;
    private volatile Snapshot snapshot = new Snapshot(Map.of(), -1);

    @Autowired
    public BatchParquetQueueMetrics(BatchParquetArtifactRepository repository) {
        this(repository, System::nanoTime, SNAPSHOT_TTL);
    }

    BatchParquetQueueMetrics(BatchParquetArtifactRepository repository, LongSupplier nanoTime,
                             Duration snapshotTtl) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.snapshotTtlNanos = Objects.requireNonNull(snapshotTtl, "snapshotTtl").toNanos();
    }

    /** Register one queue-depth gauge for every durable artifact status. */
    @Override
    public void bindTo(MeterRegistry registry) {
        MeterRegistry requiredRegistry = Objects.requireNonNull(registry, "registry");
        for (BatchParquetArtifactStatus status : BatchParquetArtifactStatus.values()) {
            Gauge.builder(METER_NAME, this, metrics -> metrics.queueDepth(status))
                    .description("Completed-batch Parquet queue depth by durable status")
                    .tag(APP_TAG_KEY, APP_TAG_VALUE)
                    .tag("status", status.name().toLowerCase(Locale.ROOT))
                    .register(requiredRegistry);
        }
    }

    private long queueDepth(BatchParquetArtifactStatus status) {
        long now = nanoTime.getAsLong();
        Snapshot current = snapshot;
        if (now >= current.expiresAtNanos()) {
            current = refreshSnapshot(now);
        }
        return current.depths().getOrDefault(status, 0L);
    }

    private synchronized Snapshot refreshSnapshot(long now) {
        Snapshot current = snapshot;
        if (now < current.expiresAtNanos()) {
            return current;
        }
        Map<BatchParquetArtifactStatus, Long> depths =
                new EnumMap<>(BatchParquetArtifactStatus.class);
        for (BatchParquetQueueDepth depth : repository.countByStatusGrouped()) {
            depths.put(depth.status(), depth.count());
        }
        Snapshot refreshed = new Snapshot(Map.copyOf(depths), now + snapshotTtlNanos);
        snapshot = refreshed;
        return refreshed;
    }

    private record Snapshot(Map<BatchParquetArtifactStatus, Long> depths, long expiresAtNanos) {
    }
}
