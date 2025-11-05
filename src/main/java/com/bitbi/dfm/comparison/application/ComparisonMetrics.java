package com.bitbi.dfm.comparison.application;

import com.bitbi.dfm.comparison.domain.ComparisonRepository;
import com.bitbi.dfm.comparison.domain.ComparisonStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Registers Micrometer gauges for tracking file comparison metrics.
 *
 * Provides real-time visibility into:
 * - Number of active IN_PROGRESS comparisons
 * - Comparison workload monitoring
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComparisonMetrics {

    private final MeterRegistry meterRegistry;
    private final ComparisonRepository comparisonRepository;

    /**
     * Registers comparison metrics gauges at application startup.
     *
     * Gauges are evaluated lazily on each scrape/query.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerMetrics() {
        log.info("Registering Micrometer gauges for file comparison metrics");

        // Gauge for number of IN_PROGRESS comparisons
        Gauge.builder("comparison.in_progress.count", comparisonRepository, repo -> {
                    try {
                        return repo.countByStatus(ComparisonStatus.IN_PROGRESS);
                    } catch (Exception e) {
                        log.error("Failed to query IN_PROGRESS comparison count", e);
                        return 0;
                    }
                })
                .description("Number of file comparisons currently in progress")
                .tag("status", "in_progress")
                .register(meterRegistry);

        // Gauge for number of PENDING comparisons
        Gauge.builder("comparison.pending.count", comparisonRepository, repo -> {
                    try {
                        return repo.countByStatus(ComparisonStatus.PENDING);
                    } catch (Exception e) {
                        log.error("Failed to query PENDING comparison count", e);
                        return 0;
                    }
                })
                .description("Number of file comparisons waiting to be processed")
                .tag("status", "pending")
                .register(meterRegistry);

        // Gauge for number of FAILED comparisons (last hour)
        Gauge.builder("comparison.failed.recent.count", comparisonRepository, repo -> {
                    try {
                        return repo.countFailedSince(java.time.Instant.now().minus(1, java.time.temporal.ChronoUnit.HOURS));
                    } catch (Exception e) {
                        log.error("Failed to query recent FAILED comparison count", e);
                        return 0;
                    }
                })
                .description("Number of file comparisons that failed in the last hour")
                .tag("status", "failed")
                .tag("window", "1h")
                .register(meterRegistry);

        log.info("✓ File comparison metrics registered: comparison.in_progress.count, comparison.pending.count, comparison.failed.recent.count");
    }
}
