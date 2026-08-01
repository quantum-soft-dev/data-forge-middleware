package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;

/** Database-backed gauges for the durable completed-batch Parquet work queue. */
@Component
public class BatchParquetQueueMetrics implements MeterBinder {

    private static final String METER_NAME = "delta.batch-parquet.queue";
    private static final String APP_TAG_KEY = "application";
    private static final String APP_TAG_VALUE = "data-forge-middleware";

    private final BatchParquetArtifactRepository repository;

    public BatchParquetQueueMetrics(BatchParquetArtifactRepository repository) {
        this.repository = repository;
    }

    /** Register one live queue-depth gauge for every durable artifact status. */
    @Override
    public void bindTo(MeterRegistry registry) {
        for (BatchParquetArtifactStatus status : BatchParquetArtifactStatus.values()) {
            Gauge.builder(METER_NAME, repository, ignored -> repository.countByStatus(status))
                    .description("Completed-batch Parquet queue depth by durable status")
                    .tag(APP_TAG_KEY, APP_TAG_VALUE)
                    .tag("status", status.name().toLowerCase(Locale.ROOT))
                    .register(Objects.requireNonNull(registry, "registry"));
        }
    }
}
