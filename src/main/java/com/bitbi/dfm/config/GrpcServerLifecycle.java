package com.bitbi.dfm.config;

import io.grpc.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Spring-managed lifecycle for the Delta Client v2 gRPC {@link Server} (feature 022).
 *
 * <p>Binds the server on context start and shuts it down gracefully on stop, so the Delta ingestion
 * service is actually reachable in production. A {@code null} server (gRPC disabled by config) makes
 * every operation a no-op, which is how tests and gRPC-less deployments opt out.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public class GrpcServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;

    private final Server server;
    private volatile boolean running;

    public GrpcServerLifecycle(Server server) {
        this.server = server;
    }

    /** A disabled lifecycle that never binds a port (gRPC turned off). */
    public static GrpcServerLifecycle disabled() {
        return new GrpcServerLifecycle(null);
    }

    @Override
    public void start() {
        if (server == null || running) {
            return;
        }
        try {
            server.start();
            running = true;
            log.info("Delta gRPC server started on port {}", server.getPort());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start Delta gRPC server", e);
        }
    }

    @Override
    public void stop() {
        if (server == null || !running) {
            return;
        }
        try {
            server.shutdown();
            if (!server.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                server.shutdownNow();
            }
        } catch (InterruptedException e) {
            server.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            running = false;
            log.info("Delta gRPC server stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
