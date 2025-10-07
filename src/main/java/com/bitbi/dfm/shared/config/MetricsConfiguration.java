package com.bitbi.dfm.shared.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Micrometer metrics configuration.
 * <p>
 * Defines custom counters for tracking batch operations and file uploads.
 * Metrics are exposed via /actuator/metrics endpoint.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Configuration
public class MetricsConfiguration {

    @Bean
    public Counter batchStartedCounter(MeterRegistry registry) {
        return Counter.builder("batch.started")
                .description("Total number of batches started")
                .tag("application", "data-forge-middleware")
                .register(registry);
    }

    @Bean
    public Counter batchCompletedCounter(MeterRegistry registry) {
        return Counter.builder("batch.completed")
                .description("Total number of batches completed successfully")
                .tag("application", "data-forge-middleware")
                .register(registry);
    }

    @Bean
    public Counter batchFailedCounter(MeterRegistry registry) {
        return Counter.builder("batch.failed")
                .description("Total number of batches that failed")
                .tag("application", "data-forge-middleware")
                .register(registry);
    }

    @Bean
    public Counter filesUploadedCounter(MeterRegistry registry) {
        return Counter.builder("files.uploaded")
                .description("Total number of files uploaded")
                .tag("application", "data-forge-middleware")
                .register(registry);
    }

    @Bean
    public Counter errorLoggedCounter(MeterRegistry registry) {
        return Counter.builder("error.logged")
                .description("Total number of errors logged")
                .tag("application", "data-forge-middleware")
                .register(registry);
    }
}
