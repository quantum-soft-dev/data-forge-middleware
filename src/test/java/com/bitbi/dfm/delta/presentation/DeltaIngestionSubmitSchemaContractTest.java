package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.delta.application.DeltaMetrics;
import com.bitbi.dfm.delta.application.DeltaSessionCommitService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import com.bitbi.dfm.delta.grpc.v2.Column;
import com.bitbi.dfm.delta.grpc.v2.DeltaIngestionGrpc;
import com.bitbi.dfm.delta.grpc.v2.SchemaRequest;
import com.bitbi.dfm.delta.grpc.v2.SchemaResponse;
import com.bitbi.dfm.delta.grpc.v2.TableSchema;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.SiteSchema;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T2.7 — contract test for the {@code SubmitSchema} RPC: a valid schema returns the (bumped)
 * version; an invalid schema is rejected with INVALID_ARGUMENT.
 */
class DeltaIngestionSubmitSchemaContractTest {

    private static final UUID SITE = UUID.randomUUID();

    private final SiteSchemaService siteSchemaService = mock(SiteSchemaService.class);
    private Server server;
    private ManagedChannel channel;
    private DeltaIngestionGrpc.DeltaIngestionBlockingStub stub;

    @BeforeEach
    void setUp() throws IOException {
        DeltaIngestionService service = new DeltaIngestionService(
                new DeltaSyncStateService(mock(SiteSyncStateRepository.class)),
                mock(BatchLifecycleService.class),
                siteSchemaService,
                mock(DeltaSessionCommitService.class),
                new DeltaMetrics(new SimpleMeterRegistry()), 2000000, 3900000L);
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(ServerInterceptors.intercept(service, siteInterceptor(SITE)))
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
    void validSchemaReturnsBumpedVersion() {
        SiteSchema saved = mock(SiteSchema.class);
        when(saved.getSchemaVersion()).thenReturn(3);
        when(saved.getUpdatedAt()).thenReturn(LocalDateTime.now());
        when(siteSchemaService.upsertSchema(eq(SITE), any())).thenReturn(saved);

        SchemaResponse response = stub.submitSchema(schemaRequest());

        assertEquals(3, response.getSchemaVersion());
    }

    @Test
    void invalidSchemaRejectedWithInvalidArgument() {
        when(siteSchemaService.upsertSchema(eq(SITE), any()))
                .thenThrow(new SiteSchemaService.InvalidSchemaException("bad schema"));

        StatusRuntimeException ex =
                assertThrows(StatusRuntimeException.class, () -> stub.submitSchema(schemaRequest()));

        assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
    }

    private static SchemaRequest schemaRequest() {
        return SchemaRequest.newBuilder()
                .putTables("customers", TableSchema.newBuilder()
                        .addColumns(Column.newBuilder().setName("id").setType("integer").setNullable(false).build())
                        .addPrimaryKey("id")
                        .build())
                .build();
    }

    private static ServerInterceptor siteInterceptor(UUID siteId) {
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
