package com.bitbi.dfm.config;

import com.bitbi.dfm.delta.presentation.DeltaAuthInterceptor;
import com.bitbi.dfm.delta.presentation.DeltaIngestionService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Delta Client v2 gRPC server into the application (feature 022).
 *
 * <p>Registers {@link DeltaIngestionService} behind {@link DeltaAuthInterceptor} on a dedicated port
 * and hands the server to a {@link GrpcServerLifecycle} so Spring starts/stops it with the context.
 * Without this, the {@code @Component} service beans exist but nothing listens — the feature is only
 * reachable from in-process test servers. Set {@code delta.grpc.enabled=false} to turn it off
 * (e.g. in tests or a gRPC-less deployment).</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Configuration
public class GrpcServerConfig {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerConfig.class);

    @Bean
    public GrpcServerLifecycle deltaGrpcServerLifecycle(
            DeltaIngestionService deltaIngestionService,
            DeltaAuthInterceptor deltaAuthInterceptor,
            @Value("${delta.grpc.enabled:true}") boolean enabled,
            @Value("${delta.grpc.port:9090}") int port) {

        if (!enabled) {
            log.info("Delta gRPC server disabled (delta.grpc.enabled=false)");
            return GrpcServerLifecycle.disabled();
        }
        Server server = ServerBuilder.forPort(port)
                .addService(ServerInterceptors.intercept(deltaIngestionService, deltaAuthInterceptor))
                .build();
        return new GrpcServerLifecycle(server);
    }
}
