package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BatchParquetQueueMetricsTest {

    @Test
    void registersALiveQueueDepthGaugeForEveryStatus() {
        BatchParquetArtifactRepository repository = mock(BatchParquetArtifactRepository.class);
        Map<BatchParquetArtifactStatus, AtomicLong> depths =
                new EnumMap<>(BatchParquetArtifactStatus.class);
        for (BatchParquetArtifactStatus status : BatchParquetArtifactStatus.values()) {
            AtomicLong depth = new AtomicLong(status.ordinal() + 1L);
            depths.put(status, depth);
            when(repository.countByStatus(status)).thenAnswer(ignored -> depth.get());
        }
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new BatchParquetQueueMetrics(repository).bindTo(registry);

        for (BatchParquetArtifactStatus status : BatchParquetArtifactStatus.values()) {
            assertEquals(status.ordinal() + 1.0, registry.get("delta.batch-parquet.queue")
                    .tag("status", status.name().toLowerCase()).gauge().value());
        }

        depths.get(BatchParquetArtifactStatus.PENDING).set(42L);
        assertEquals(42.0, registry.get("delta.batch-parquet.queue")
                .tag("status", "pending").gauge().value(),
                "the gauge must query durable queue state at collection time");
    }
}
