package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService.SyncStateView;
import com.bitbi.dfm.delta.grpc.v2.DeltaIngestionGrpc;
import com.bitbi.dfm.delta.grpc.v2.RecoveryAction;
import com.bitbi.dfm.delta.grpc.v2.SyncStateRequest;
import com.bitbi.dfm.delta.grpc.v2.SyncStateResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * gRPC service implementation for Delta Client v2 ingestion (feature 022).
 *
 * <p>The authenticated site is taken from the gRPC context ({@link DeltaAuthInterceptor#SITE_ID}),
 * populated by {@link DeltaAuthInterceptor}. This class only maps between the application layer
 * and the Protobuf contract.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class DeltaIngestionService extends DeltaIngestionGrpc.DeltaIngestionImplBase {

    private final DeltaSyncStateService syncStateService;

    public DeltaIngestionService(DeltaSyncStateService syncStateService) {
        this.syncStateService = syncStateService;
    }

    @Override
    public void getSyncState(SyncStateRequest request, StreamObserver<SyncStateResponse> responseObserver) {
        UUID siteId = DeltaAuthInterceptor.SITE_ID.get();
        if (siteId == null) {
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("No authenticated site on context").asRuntimeException());
            return;
        }

        SyncStateView view = syncStateService.getSyncState(siteId);

        SyncStateResponse response = SyncStateResponse.newBuilder()
                .setLastAppliedSeq(view.lastAppliedSeq())
                .setLastCheckpointSeq(view.lastCheckpointSeq())
                .setSchemaVersion(view.schemaVersion())
                .setAction(view.needRebaseline() ? RecoveryAction.NEED_REBASELINE : RecoveryAction.PROCEED)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
