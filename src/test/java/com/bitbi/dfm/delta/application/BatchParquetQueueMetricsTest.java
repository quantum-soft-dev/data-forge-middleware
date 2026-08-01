package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactStatus;
import com.bitbi.dfm.delta.domain.BatchParquetQueueDepth;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchParquetQueueMetricsTest {

    @Test
    void sharesOneGroupedSnapshotAcrossAllStatusGaugesAndRefreshesAfterItsTtl() {
        BatchParquetArtifactRepository repository = mock(BatchParquetArtifactRepository.class);
        AtomicLong clock = new AtomicLong(-Duration.ofSeconds(100).toNanos());
        when(repository.countByStatusGrouped())
                .thenAnswer(ignored -> {
                    clock.addAndGet(Duration.ofSeconds(6).toNanos());
                    return List.of(
                            new BatchParquetQueueDepth(BatchParquetArtifactStatus.PENDING, 2),
                            new BatchParquetQueueDepth(BatchParquetArtifactStatus.BUILDING, 1));
                })
                .thenReturn(List.of(
                        new BatchParquetQueueDepth(BatchParquetArtifactStatus.PENDING, 42)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new BatchParquetQueueMetrics(repository, clock::get, Duration.ofSeconds(5))
                .bindTo(registry);

        for (BatchParquetArtifactStatus status : BatchParquetArtifactStatus.values()) {
            double expected = switch (status) {
                case PENDING -> 2.0;
                case BUILDING -> 1.0;
                default -> 0.0;
            };
            assertEquals(expected, registry.get("delta.batch-parquet.queue")
                    .tag("status", status.name().toLowerCase()).gauge().value());
        }
        verify(repository, times(1)).countByStatusGrouped();

        clock.addAndGet(Duration.ofSeconds(6).toNanos());
        assertEquals(42.0, registry.get("delta.batch-parquet.queue")
                .tag("status", "pending").gauge().value(),
                "the grouped snapshot must refresh after its bounded reuse window");
        verify(repository, times(2)).countByStatusGrouped();
    }
}
