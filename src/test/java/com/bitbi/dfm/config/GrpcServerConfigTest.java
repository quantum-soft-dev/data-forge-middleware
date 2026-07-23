package com.bitbi.dfm.config;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.delta.application.DeltaMetrics;
import com.bitbi.dfm.delta.application.DeltaSessionCommitService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.presentation.DeltaAuthInterceptor;
import com.bitbi.dfm.delta.presentation.DeltaIngestionService;
import com.bitbi.dfm.site.application.SiteSchemaService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * Review r4 — the gRPC server bean builds with keepalive + connection-age bounds, and TLS is
 * optional/off by default (plaintext for local, TLS or upstream termination in production).
 */
class GrpcServerConfigTest {

    private final GrpcServerConfig config = new GrpcServerConfig();
    // A real service (its bindService() is invoked when the interceptor wraps it).
    private final DeltaIngestionService ingestion = new DeltaIngestionService(
            mock(DeltaSyncStateService.class), mock(BatchLifecycleService.class),
            mock(SiteSchemaService.class), mock(DeltaSessionCommitService.class),
            new DeltaMetrics(new SimpleMeterRegistry()), 2000000, Long.MAX_VALUE, 3900000L, 300000L, 16777216L);
    private final DeltaAuthInterceptor interceptor = mock(DeltaAuthInterceptor.class);

    @Test
    void disabledFlagYieldsANonRunningLifecycle() {
        GrpcServerLifecycle lifecycle = config.deltaGrpcServerLifecycle(
                ingestion, interceptor, false, 0, false, "", "", 300, 3600);
        assertNotNull(lifecycle);
        assertFalse(lifecycle.isRunning(), "disabled server must not be running");
    }

    @Test
    void enabledPlaintextBuildsWithKeepaliveWithoutThrowing() {
        // Build (not start) the server with keepalive + max-connection-age applied; TLS off.
        assertDoesNotThrow(() -> config.deltaGrpcServerLifecycle(
                ingestion, interceptor, true, 0, false, "", "", 300, 3600));
    }
}
