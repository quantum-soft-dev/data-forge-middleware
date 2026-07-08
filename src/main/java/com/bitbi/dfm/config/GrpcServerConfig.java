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

import java.io.File;
import java.util.concurrent.TimeUnit;

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
            @Value("${delta.grpc.port:9090}") int port,
            @Value("${delta.grpc.tls.enabled:false}") boolean tlsEnabled,
            @Value("${delta.grpc.tls.cert-path:}") String certPath,
            @Value("${delta.grpc.tls.key-path:}") String keyPath,
            @Value("${delta.grpc.keepalive-seconds:300}") long keepAliveSeconds,
            @Value("${delta.grpc.max-connection-age-seconds:3600}") long maxConnectionAgeSeconds) {

        if (!enabled) {
            log.info("Delta gRPC server disabled (delta.grpc.enabled=false)");
            return GrpcServerLifecycle.disabled();
        }
        ServerBuilder<?> builder = ServerBuilder.forPort(port)
                .addService(ServerInterceptors.intercept(deltaIngestionService, deltaAuthInterceptor))
                // Keepalive + a max connection age bound a half-open/hung stream: the server closes it,
                // which triggers onError (drop-seal / staged eviction) so a wedged session cannot pin a
                // batch forever — the safety net that lets delta batches skip the upload timeout.
                .keepAliveTime(keepAliveSeconds, TimeUnit.SECONDS)
                .keepAliveTimeout(20, TimeUnit.SECONDS)
                .permitKeepAliveWithoutCalls(true)
                .maxConnectionAge(maxConnectionAgeSeconds, TimeUnit.SECONDS)
                .maxConnectionAgeGrace(30, TimeUnit.SECONDS);

        // The site Bearer token travels in gRPC metadata; enable TLS (or terminate it at an ingress/
        // sidecar) so it is not sent in cleartext. Off by default for local/in-cluster plaintext.
        if (tlsEnabled) {
            builder.useTransportSecurity(new File(certPath), new File(keyPath));
            log.info("Delta gRPC server: TLS enabled (cert={})", certPath);
        } else {
            log.warn("Delta gRPC server: TLS DISABLED (plaintext h2c) on port {} — terminate TLS "
                    + "upstream (ingress/sidecar) or set delta.grpc.tls.enabled=true in production", port);
        }
        return new GrpcServerLifecycle(builder.build());
    }
}
