package com.bitbi.dfm.delta;

import com.bitbi.dfm.delta.grpc.v2.DeltaIngestionGrpc;
import com.bitbi.dfm.delta.grpc.v2.SessionMode;
import com.bitbi.dfm.delta.grpc.v2.SessionStart;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * T0.4 — smoke test that the gRPC/Protobuf code generation is wired into the build:
 * generated message and service stubs from delta-ingestion.proto must be importable and usable.
 */
class DeltaProtoCodegenSmokeTest {

    @Test
    void generatedMessageBuildsAndReadsBack() {
        SessionStart start = SessionStart.newBuilder()
                .setMode(SessionMode.FULL_SNAPSHOT)
                .setFirstSeq(1L)
                .setSchemaVersion(1)
                .setClientSessionId("smoke")
                .build();

        assertEquals(1L, start.getFirstSeq());
        assertEquals(SessionMode.FULL_SNAPSHOT, start.getMode());
        assertEquals("smoke", start.getClientSessionId());
    }

    @Test
    void generatedGrpcServiceDescriptorIsAvailable() {
        assertNotNull(DeltaIngestionGrpc.getServiceDescriptor());
        assertEquals("com.bitbi.dfm.delta.v2.DeltaIngestion",
                DeltaIngestionGrpc.getServiceDescriptor().getName());
    }
}
