package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService.SyncStateView;
import com.bitbi.dfm.delta.application.SessionReconciler;
import com.bitbi.dfm.delta.grpc.v2.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * gRPC service implementation for Delta Client v2 ingestion (feature 022).
 *
 * <p>The authenticated site/account are taken from the gRPC context
 * ({@link DeltaAuthInterceptor#SITE_ID} / {@link DeltaAuthInterceptor#ACCOUNT_ID}), populated by
 * {@link DeltaAuthInterceptor}. This class maps between the application layer and the Protobuf
 * contract.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class DeltaIngestionService extends DeltaIngestionGrpc.DeltaIngestionImplBase {

    private final DeltaSyncStateService syncStateService;
    private final BatchLifecycleService batchLifecycleService;

    public DeltaIngestionService(DeltaSyncStateService syncStateService,
                                 BatchLifecycleService batchLifecycleService) {
        this.syncStateService = syncStateService;
        this.batchLifecycleService = batchLifecycleService;
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

    /**
     * Bidirectional ingestion session (skeleton — T1.4).
     *
     * <p>{@code SessionStart} opens a batch (one session = one batch); {@code ChangeRecord}s are
     * tracked for the committed-seq report (persistence is added in Task 2); {@code SessionEnd}
     * completes the batch and emits {@code SessionCommitted}.</p>
     */
    @Override
    public StreamObserver<ClientEvent> streamChanges(StreamObserver<ServerEvent> responseObserver) {
        UUID siteId = DeltaAuthInterceptor.SITE_ID.get();
        UUID accountId = DeltaAuthInterceptor.ACCOUNT_ID.get();

        return new StreamObserver<>() {
            private UUID batchId;
            private SessionChangeBuffer buffer;
            private boolean closed;

            @Override
            public void onNext(ClientEvent event) {
                if (closed) {
                    return;
                }
                try {
                    switch (event.getEventCase()) {
                        case START -> onSessionStart(event.getStart());
                        case CHANGE -> { if (buffer != null) buffer.accept(event.getChange()); }
                        case END -> onSessionEnd(event.getEnd());
                        case EVENT_NOT_SET -> { /* ignore empty frame */ }
                    }
                } catch (RuntimeException e) {
                    closed = true;
                    responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
                }
            }

            private void onSessionStart(SessionStart start) {
                long serverLastSeq = syncStateService.getSyncState(siteId).lastAppliedSeq();

                // Gap detection: a DELTA session must continue contiguously from the server
                // watermark. FULL_SNAPSHOT (bootstrap / re-baseline) resets and is exempt.
                if (start.getMode() == SessionMode.DELTA && start.getFirstSeq() != serverLastSeq + 1) {
                    emitError(ErrorCode.SEQUENCE_GAP,
                            "Expected first_seq=" + (serverLastSeq + 1) + " but got " + start.getFirstSeq(),
                            RecoveryAction.NEED_REBASELINE);
                    return;
                }

                Batch batch;
                try {
                    batch = batchLifecycleService.startBatch(accountId, siteId);
                } catch (BatchLifecycleService.ActiveBatchExistsException e) {
                    // One active session per site (mirrors one-active-batch).
                    emitError(ErrorCode.ACTIVE_SESSION_EXISTS, e.getMessage(), RecoveryAction.PROCEED);
                    return;
                }
                batchId = batch.getId();
                buffer = new SessionChangeBuffer(serverLastSeq);
                responseObserver.onNext(ServerEvent.newBuilder()
                        .setOpened(SessionOpened.newBuilder()
                                .setServerSessionId(batchId.toString())
                                .setServerLastSeq(serverLastSeq)
                                .setAction(RecoveryAction.PROCEED)
                                .build())
                        .build());
            }

            private void onSessionEnd(SessionEnd end) {
                if (buffer != null && !SessionReconciler.reconcile(buffer.accepted(), end.getPerTableMap())) {
                    // Hard-fail: declared counts must match accepted records (CR §10).
                    emitError(ErrorCode.RECONCILIATION_FAILED,
                            "Declared per-table counts do not match accepted records",
                            RecoveryAction.NEED_REBASELINE);
                    return; // do not complete the batch
                }
                batchLifecycleService.completeBatch(batchId);
                long committed = buffer != null ? buffer.lastSeq() : end.getLastSeq();
                responseObserver.onNext(ServerEvent.newBuilder()
                        .setCommitted(SessionCommitted.newBuilder()
                                .setCommittedSeq(committed)
                                .setSegmentS3Key("")
                                .build())
                        .build());
                closed = true;
                responseObserver.onCompleted();
            }

            private void emitError(ErrorCode code, String message, RecoveryAction action) {
                responseObserver.onNext(ServerEvent.newBuilder()
                        .setError(ServerError.newBuilder()
                                .setCode(code)
                                .setMessage(message == null ? "" : message)
                                .setAction(action)
                                .build())
                        .build());
                closed = true;
                responseObserver.onCompleted();
            }

            @Override
            public void onError(Throwable t) {
                // client cancelled / transport error — nothing persisted in the skeleton to roll back
            }

            @Override
            public void onCompleted() {
                if (!closed) {
                    responseObserver.onCompleted();
                }
            }
        };
    }
}
