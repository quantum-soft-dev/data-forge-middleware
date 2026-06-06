package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.delta.application.ChangelogContentHash;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.DeltaMetrics;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService.SyncStateView;
import com.bitbi.dfm.delta.application.SessionReconciler;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.grpc.v2.*;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.SiteSchema;
import com.bitbi.dfm.upload.presentation.dto.SchemaUploadRequestDto;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    /** Emit a progressive {@link Ack} every this many accepted records (backpressure watermark). */
    private static final int ACK_INTERVAL = 100;

    /** In CONTINUOUS mode, seal a segment once this many records have accumulated (T5.4, size trigger). */
    private static final int CONTINUOUS_SEAL_RECORDS = 100;

    private final DeltaSyncStateService syncStateService;
    private final BatchLifecycleService batchLifecycleService;
    private final SiteSchemaService siteSchemaService;
    private final ChangelogSegmentService changelogSegmentService;
    private final DeltaMetrics metrics;

    /**
     * Sessions that dropped mid-stream (before {@code SessionEnd}), retained by site so a reconnect
     * can resume from the staged data (T5.1). In-memory only: lost on server restart, in which case
     * the client falls back to gap detection / re-baseline. Cleared on commit or on re-baseline.
     */
    private final Map<UUID, StagedSession> stagedSessions = new ConcurrentHashMap<>();

    public DeltaIngestionService(DeltaSyncStateService syncStateService,
                                 BatchLifecycleService batchLifecycleService,
                                 SiteSchemaService siteSchemaService,
                                 ChangelogSegmentService changelogSegmentService,
                                 DeltaMetrics metrics) {
        this.syncStateService = syncStateService;
        this.batchLifecycleService = batchLifecycleService;
        this.siteSchemaService = siteSchemaService;
        this.changelogSegmentService = changelogSegmentService;
        this.metrics = metrics;
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

    @Override
    public void submitSchema(SchemaRequest request, StreamObserver<SchemaResponse> responseObserver) {
        UUID siteId = DeltaAuthInterceptor.SITE_ID.get();
        if (siteId == null) {
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("No authenticated site on context").asRuntimeException());
            return;
        }
        try {
            SiteSchema saved = siteSchemaService.upsertSchema(siteId, toDto(request));
            Instant updated = saved.getUpdatedAt().toInstant(ZoneOffset.UTC);
            responseObserver.onNext(SchemaResponse.newBuilder()
                    .setSchemaVersion(saved.getSchemaVersion())
                    .setUpdatedAt(Timestamp.newBuilder()
                            .setSeconds(updated.getEpochSecond())
                            .setNanos(updated.getNano())
                            .build())
                    .build());
            responseObserver.onCompleted();
        } catch (SiteSchemaService.InvalidSchemaException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage()).asRuntimeException());
        }
    }

    private static SchemaUploadRequestDto toDto(SchemaRequest request) {
        Map<String, SchemaUploadRequestDto.TableSchemaDto> tables = new LinkedHashMap<>();
        request.getTablesMap().forEach((name, table) -> {
            List<SchemaUploadRequestDto.ColumnDto> columns = new ArrayList<>();
            for (Column column : table.getColumnsList()) {
                columns.add(new SchemaUploadRequestDto.ColumnDto(
                        column.getName(), column.getType(), column.getNullable()));
            }
            List<SchemaUploadRequestDto.UniqueKeyDto> uniqueKeys = new ArrayList<>();
            for (UniqueKey uk : table.getUniqueKeysList()) {
                uniqueKeys.add(new SchemaUploadRequestDto.UniqueKeyDto(
                        uk.getName(), new ArrayList<>(uk.getColumnsList())));
            }
            tables.put(name, new SchemaUploadRequestDto.TableSchemaDto(
                    columns, new ArrayList<>(table.getPrimaryKeyList()), uniqueKeys));
        });
        return new SchemaUploadRequestDto(tables);
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

        // Inbound flow control: pull one record at a time so a fast producer cannot outrun the server
        // (backpressure). Combined with the progressive Acks below, the client bounds its in-flight
        // window to the server's pace.
        ServerCallStreamObserver<ServerEvent> flow =
                responseObserver instanceof ServerCallStreamObserver<ServerEvent> s ? s : null;
        if (flow != null) {
            flow.disableAutoRequest();
            flow.request(1);
        }

        return new StreamObserver<>() {
            private UUID batchId;
            private SessionChangeBuffer buffer;
            private String sessionMode;
            private long firstSeq;
            private boolean closed;
            private boolean committed;
            private boolean continuous;
            private int sinceAck;

            @Override
            public void onNext(ClientEvent event) {
                if (closed) {
                    return;
                }
                try {
                    switch (event.getEventCase()) {
                        case START -> onSessionStart(event.getStart());
                        case CHANGE -> onChange(event.getChange());
                        case END -> onSessionEnd(event.getEnd());
                        case EVENT_NOT_SET -> { /* ignore empty frame */ }
                    }
                } catch (RuntimeException e) {
                    closed = true;
                    responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
                }
                if (flow != null && !closed) {
                    flow.request(1); // pull the next record now that this one is staged
                }
            }

            private void onChange(ChangeRecord change) {
                if (buffer == null || !buffer.accept(change)) {
                    return; // no open session, or a duplicate / replayed seq
                }
                if (++sinceAck >= ACK_INTERVAL) {
                    sinceAck = 0;
                    responseObserver.onNext(ServerEvent.newBuilder()
                            .setAck(Ack.newBuilder().setAckedSeq(buffer.lastSeq()).build())
                            .build());
                }
                // Continuous mode: seal a segment once it reaches the size threshold and keep the
                // stream open for the next one (T5.4).
                if (continuous && buffer.acceptedCount() >= CONTINUOUS_SEAL_RECORDS) {
                    sealContinuous(true);
                }
            }

            private void onSessionStart(SessionStart start) {
                // Resume: a DELTA reconnect re-attaches to a session that dropped mid-stream and
                // replays from the staged watermark, rather than re-sending everything (T5.1). A
                // FULL_SNAPSHOT re-baseline instead discards any staged data.
                if (start.getMode() == SessionMode.DELTA) {
                    StagedSession resume = stagedSessions.remove(siteId);
                    if (resume != null) {
                        batchId = resume.batchId();
                        buffer = resume.buffer();
                        sessionMode = resume.mode();
                        firstSeq = resume.firstSeq();
                        responseObserver.onNext(ServerEvent.newBuilder()
                                .setOpened(SessionOpened.newBuilder()
                                        .setServerSessionId(batchId.toString())
                                        .setServerLastSeq(syncStateService.getSyncState(siteId).lastAppliedSeq())
                                        .setAction(RecoveryAction.RESUME_FROM)
                                        .setResumeFromSeq(buffer.lastSeq() + 1)
                                        .build())
                                .build());
                        return;
                    }
                } else {
                    stagedSessions.remove(siteId);
                }

                long serverLastSeq = syncStateService.getSyncState(siteId).lastAppliedSeq();

                // Gap detection: a DELTA / CONTINUOUS session must continue contiguously from the
                // server watermark. FULL_SNAPSHOT (bootstrap / re-baseline) resets and is exempt.
                boolean delta = start.getMode() == SessionMode.DELTA || start.getMode() == SessionMode.CONTINUOUS;
                if (delta && start.getFirstSeq() != serverLastSeq + 1) {
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
                sessionMode = start.getMode().name();
                firstSeq = start.getFirstSeq();
                continuous = start.getMode() == SessionMode.CONTINUOUS;
                metrics.sessionStarted();
                responseObserver.onNext(ServerEvent.newBuilder()
                        .setOpened(SessionOpened.newBuilder()
                                .setServerSessionId(batchId.toString())
                                .setServerLastSeq(serverLastSeq)
                                .setAction(RecoveryAction.PROCEED)
                                .build())
                        .build());
            }

            private void onSessionEnd(SessionEnd end) {
                if (buffer != null) {
                    // Hard-fail: declared counts and (when provided) the integrity hash must match the
                    // accepted records (CR §10). A blank content_hash means the client opted out.
                    if (!SessionReconciler.reconcile(buffer.accepted(), end.getPerTableMap())) {
                        metrics.reconciliationFailed();
                        emitError(ErrorCode.RECONCILIATION_FAILED,
                                "Declared per-table counts do not match accepted records",
                                RecoveryAction.NEED_REBASELINE);
                        return; // do not complete the batch
                    }
                    if (!ChangelogContentHash.matches(buffer.accepted(), end.getContentHash())) {
                        metrics.reconciliationFailed();
                        emitError(ErrorCode.RECONCILIATION_FAILED,
                                "Declared content_hash does not match accepted records",
                                RecoveryAction.NEED_REBASELINE);
                        return; // do not complete the batch
                    }
                }
                long committed = buffer != null ? buffer.lastSeq() : end.getLastSeq();
                emitSealed(committed, buffer != null ? buffer.accepted() : List.of());
                this.committed = true;
                closed = true;
                responseObserver.onCompleted();
            }

            /**
             * Commit orchestration for one segment: persist it, advance the watermark, complete the
             * batch, record metrics, and emit {@code SessionCommitted}. Shared by {@code SessionEnd}
             * and continuous sealing.
             */
            private void emitSealed(long committedSeq, List<ChangeRecord> records) {
                long checkpointSeq = syncStateService.getSyncState(siteId).lastCheckpointSeq();
                ChangelogSegment segment = changelogSegmentService.persist(
                        siteId, batchId, sessionMode, firstSeq, records);
                syncStateService.advanceWatermark(siteId, committedSeq);
                batchLifecycleService.completeBatch(batchId);
                metrics.sessionCommitted();
                metrics.recordSeqLag(committedSeq - checkpointSeq);
                responseObserver.onNext(ServerEvent.newBuilder()
                        .setCommitted(SessionCommitted.newBuilder()
                                .setCommittedSeq(committedSeq)
                                .setSegmentS3Key(segment.getS3Key())
                                .build())
                        .build());
            }

            /**
             * Seal the current continuous-mode segment (T5.4). When {@code continueStream} is true,
             * open the next segment under a fresh batch and keep the stream open; otherwise this is
             * the final flush on stream close.
             */
            private void sealContinuous(boolean continueStream) {
                long committedSeq = buffer.lastSeq();
                emitSealed(committedSeq, buffer.accepted());
                if (continueStream) {
                    batchId = batchLifecycleService.startBatch(accountId, siteId).getId();
                    firstSeq = committedSeq + 1;
                    buffer = new SessionChangeBuffer(committedSeq);
                    sinceAck = 0;
                    metrics.sessionStarted();
                }
            }

            /**
             * Retain an open, uncommitted session for resume (T5.1). Called on a transport drop or a
             * half-close without {@code SessionEnd}; the batch is left active so the reconnect
             * re-attaches to it. No-op once the session has committed or otherwise closed.
             */
            private void stageForResume() {
                if (!closed && !committed && batchId != null && buffer != null) {
                    stagedSessions.put(siteId, new StagedSession(batchId, sessionMode, firstSeq, buffer));
                    closed = true;
                }
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
                // Periodic session dropped mid-stream — stage for resume (T5.1). A continuous session
                // drops its unsealed tail; the client reconnects and continues from the watermark.
                if (!continuous) {
                    stageForResume();
                }
            }

            @Override
            public void onCompleted() {
                if (closed) {
                    return;
                }
                if (continuous) {
                    // Flush the final partial segment, then close (T5.4).
                    if (buffer != null && buffer.acceptedCount() > 0) {
                        sealContinuous(false);
                    }
                } else {
                    // Half-close without SessionEnd = abandoned periodic session — stage for resume (T5.1).
                    stageForResume();
                }
                closed = true;
                responseObserver.onCompleted();
            }
        };
    }

    /**
     * A session retained for resume after a mid-session drop (T5.1): the active batch it opened, its
     * mode and first sequence, and the buffer of records staged so far.
     */
    private record StagedSession(UUID batchId, String mode, long firstSeq, SessionChangeBuffer buffer) {
    }
}
