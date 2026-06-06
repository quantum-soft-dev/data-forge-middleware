package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.delta.application.DeltaMetrics;
import com.bitbi.dfm.delta.application.DeltaRebaselineService;
import com.bitbi.dfm.delta.application.DeltaSessionCommitService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import com.bitbi.dfm.delta.grpc.v2.DeltaIngestionGrpc;
import com.bitbi.dfm.delta.grpc.v2.RecoveryAction;
import com.bitbi.dfm.delta.grpc.v2.SyncStateRequest;
import com.bitbi.dfm.delta.grpc.v2.SyncStateResponse;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T1.3 — contract test for the {@code GetSyncState} RPC over an in-process gRPC server.
 * A test interceptor injects the authenticated siteId (normally set by {@link DeltaAuthInterceptor}).
 */
class DeltaIngestionGetSyncStateContractTest {

    private static final UUID SITE = UUID.randomUUID();

    private final SiteSyncStateRepository repository = mock(SiteSyncStateRepository.class);
    private Server server;
    private ManagedChannel channel;
    private DeltaIngestionGrpc.DeltaIngestionBlockingStub stub;

    @BeforeEach
    void setUp() throws IOException {
        DeltaIngestionService service = new DeltaIngestionService(
                new DeltaSyncStateService(repository), mock(BatchLifecycleService.class),
                mock(SiteSchemaService.class), mock(DeltaSessionCommitService.class),
                mock(DeltaRebaselineService.class), new DeltaMetrics(new SimpleMeterRegistry()));
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(ServerInterceptors.intercept(service, fixedSiteInterceptor(SITE)))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        stub = DeltaIngestionGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void emptyStateReturnsZeroWatermarkAndProceed() {
        when(repository.findBySiteId(SITE)).thenReturn(Optional.empty());

        SyncStateResponse response = stub.getSyncState(SyncStateRequest.newBuilder().build());

        assertEquals(0L, response.getLastAppliedSeq());
        assertEquals(0L, response.getLastCheckpointSeq());
        assertEquals(0, response.getSchemaVersion());
        assertEquals(RecoveryAction.PROCEED, response.getAction());
    }

    @Test
    void existingWatermarkIsReturned() {
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.advanceWatermark(120L);
        when(repository.findBySiteId(SITE)).thenReturn(Optional.of(state));

        SyncStateResponse response = stub.getSyncState(
                SyncStateRequest.newBuilder().setSiteId(SITE.toString()).build());

        assertEquals(120L, response.getLastAppliedSeq());
        assertEquals(RecoveryAction.PROCEED, response.getAction());
    }

    private static ServerInterceptor fixedSiteInterceptor(UUID siteId) {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                Context context = Context.current().withValue(DeltaAuthInterceptor.SITE_ID, siteId);
                return Contexts.interceptCall(context, call, headers, next);
            }
        };
    }
}
