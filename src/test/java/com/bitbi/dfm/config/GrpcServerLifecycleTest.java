package com.bitbi.dfm.config;

import io.grpc.Server;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * #1 — the gRPC server lifecycle binds the server on start and shuts it down on stop, and is a no-op
 * when disabled (no server), so tests and gRPC-less deployments do not bind a port.
 */
class GrpcServerLifecycleTest {

    @Test
    void startsAndStopsTheServer() throws Exception {
        Server server = mock(Server.class);
        when(server.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
        GrpcServerLifecycle lifecycle = new GrpcServerLifecycle(server);

        lifecycle.start();
        assertTrue(lifecycle.isRunning());
        verify(server).start();

        lifecycle.stop();
        assertFalse(lifecycle.isRunning());
        verify(server).shutdown();
    }

    @Test
    void disabledLifecycleNeverBinds() {
        GrpcServerLifecycle lifecycle = GrpcServerLifecycle.disabled();

        lifecycle.start();

        assertFalse(lifecycle.isRunning(), "disabled lifecycle must not run");
    }

    @Test
    void stopBeforeStartIsNoop() {
        Server server = mock(Server.class);
        GrpcServerLifecycle lifecycle = new GrpcServerLifecycle(server);

        lifecycle.stop();

        verify(server, never()).shutdown();
    }
}
