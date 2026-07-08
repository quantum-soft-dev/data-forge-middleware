package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.delta.application.ChangeRecordValidator;
import com.bitbi.dfm.delta.application.ChangelogContentHash;
import com.bitbi.dfm.delta.application.DeltaMetrics;
import com.bitbi.dfm.delta.application.DeltaSessionCommitService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService.SyncStateView;
import com.bitbi.dfm.delta.application.SessionReconciler;
import com.bitbi.dfm.delta.grpc.v2.*;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.SiteSchema;
import com.bitbi.dfm.site.domain.TableSchema;
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
    private final DeltaSessionCommitService commitService;
    private final DeltaMetrics metrics;
    /** Max records one session may buffer before the server rejects it (OOM guard). */
    private final int maxSessionRecords;
    /** Staged sessions older than this are evicted by the sweep (defends against a leak). */
    private final long stagedTtlMillis;

    /**
     * Sessions that dropped mid-stream (before {@code SessionEnd}), retained by site so a reconnect
     * can resume from the staged data (T5.1). In-memory only: lost on server restart, in which case
     * the client falls back to gap detection / re-baseline. Cleared on commit or on re-baseline, and
     * evicted by {@link #evictStaleStagedSessions()} so a client that drops and never resumes cannot
     * leak its buffer for the life of the process.
     */
    private final Map<UUID, StagedSession> stagedSessions = new ConcurrentHashMap<>();

    public DeltaIngestionService(DeltaSyncStateService syncStateService,
                                 BatchLifecycleService batchLifecycleService,
                                 SiteSchemaService siteSchemaService,
                                 DeltaSessionCommitService commitService,
                                 DeltaMetrics metrics,
                                 @org.springframework.beans.factory.annotation.Value(
                                         "${delta.ingestion.max-session-records:2000000}") int maxSessionRecords,
                                 @org.springframework.beans.factory.annotation.Value(
                                         "${delta.ingestion.staged-ttl-millis:3900000}") long stagedTtlMillis) {
        this.syncStateService = syncStateService;
        this.batchLifecycleService = batchLifecycleService;
        this.siteSchemaService = siteSchemaService;
        this.commitService = commitService;
        this.metrics = metrics;
        this.maxSessionRecords = maxSessionRecords;
        this.stagedTtlMillis = stagedTtlMillis;
    }

    /**
     * Evict staged sessions older than the TTL (default just over the 60-min batch timeout) and fail
     * their orphaned batches, so a client that drops mid-session and never resumes cannot leak its
     * buffer (and the still-active batch) indefinitely (review r4).
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString =
            "${delta.ingestion.staged-sweep-millis:300000}")
    public void evictStaleStagedSessions() {
        long cutoff = System.currentTimeMillis() - stagedTtlMillis;
        stagedSessions.forEach((site, staged) -> {
            if (staged.stagedAtMillis() < cutoff && stagedSessions.remove(site, staged)) {
                try {
                    batchLifecycleService.failBatch(staged.batchId());
                } catch (RuntimeException ignored) {
                    // batch may already be terminal (timed out) — best-effort
                }
            }
        });
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
            // Mirror the schema version into the sync state so GetSyncState and SessionStart
            // validation see the version the server actually holds.
            syncStateService.recordSchemaVersion(siteId, saved.getSchemaVersion());
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
            private boolean rebaseline;
            private int sinceAck;
            /** Per-table key model for this site, lazily loaded on the first change record. */
            private Map<String, TableSchema> schemas;

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
                    abortBatch();
                    closed = true;
                    responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
                }
                if (flow != null && !closed) {
                    flow.request(1); // pull the next record now that this one is staged
                }
            }

            private void onChange(ChangeRecord change) {
                if (buffer == null) {
                    return; // no open session
                }
                try {
                    // Keyless tables (no declared PK/unique key) may only INSERT/DELETE: an UPDATE
                    // would re-key the row (CR §6). Reject before staging so it never enters a segment.
                    ChangeRecordValidator.validate(change, tableHasKey(change.getTable()));
                } catch (ChangeRecordValidator.InvalidChangeException e) {
                    emitError(ErrorCode.SCHEMA_MISMATCH, e.getMessage(), RecoveryAction.NEED_REBASELINE);
                    return;
                }
                switch (buffer.accept(change)) {
                    case DUPLICATE -> {
                        return; // replay / already-applied seq, ignore
                    }
                    case GAP -> {
                        // A sequence was skipped mid-stream; rejecting forces the client to re-baseline
                        // rather than silently committing a hole in the changelog.
                        emitError(ErrorCode.SEQUENCE_GAP,
                                "Expected seq=" + (buffer.lastSeq() + 1) + " but got " + change.getSeq(),
                                RecoveryAction.NEED_REBASELINE);
                        return;
                    }
                    case OVERFLOW -> {
                        // The session exceeded the per-session record cap: reject rather than buffer
                        // the whole dataset in heap (OOM guard). A very large snapshot should stream in
                        // CONTINUOUS mode, which seals bounded segments as it goes.
                        emitError(ErrorCode.INTERNAL,
                                "Session exceeded the " + maxSessionRecords + "-record limit; stream large "
                                        + "datasets in CONTINUOUS mode",
                                RecoveryAction.NEED_REBASELINE);
                        return;
                    }
                    case ACCEPTED -> { /* fall through to ack / seal */ }
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

            /**
             * Whether the record's table has a declared primary/unique key. A table with no schema on
             * file is treated as keyed so this guard never over-rejects (unknown tables fail elsewhere).
             */
            private boolean tableHasKey(String table) {
                if (schemas == null) {
                    schemas = siteSchemaService.getTableSchemas(siteId);
                }
                TableSchema schema = schemas.get(table);
                return schema == null || !schema.primaryKey().isEmpty() || !schema.uniqueKeys().isEmpty();
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

                SyncStateView state = syncStateService.getSyncState(siteId);
                long serverLastSeq = state.lastAppliedSeq();

                // Schema-version guard: when both sides declare a version, the session's schema must
                // match the one the server holds, otherwise the records may not parse against the
                // current schema. A 0 on either side (not provided / no schema yet) skips the check.
                int clientSchemaVersion = start.getSchemaVersion();
                if (clientSchemaVersion != 0 && state.schemaVersion() != 0
                        && clientSchemaVersion != state.schemaVersion()) {
                    emitError(ErrorCode.SCHEMA_MISMATCH,
                            "Session schema_version=" + clientSchemaVersion
                                    + " does not match server schema_version=" + state.schemaVersion(),
                            RecoveryAction.NEED_REBASELINE);
                    return;
                }

                // Gap detection: a DELTA / CONTINUOUS session must continue contiguously from the
                // server watermark. Only a FORWARD gap (first_seq beyond watermark+1) is a real hole
                // that needs re-baseline. A first_seq at-or-below the watermark is a replay of an
                // already-committed session (e.g. a lost SessionCommitted ack) — accept it and let the
                // buffer swallow the duplicate seqs, rather than forcing an expensive full snapshot (D).
                // FULL_SNAPSHOT (bootstrap / re-baseline) resets and is exempt.
                boolean delta = start.getMode() == SessionMode.DELTA || start.getMode() == SessionMode.CONTINUOUS;
                if (delta && start.getFirstSeq() > serverLastSeq + 1) {
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
                if (start.getMode() == SessionMode.FULL_SNAPSHOT) {
                    // Re-baseline: the prior changelog and checkpoints are discarded so the snapshot
                    // becomes the new baseline. The destruction is DEFERRED to commit (done in the
                    // same transaction as the new snapshot segment), so a snapshot that drops before
                    // it commits leaves the old baseline intact (review r4). Buffer records from just
                    // before the snapshot's first record.
                    rebaseline = true;
                    serverLastSeq = start.getFirstSeq() - 1;
                }
                buffer = new SessionChangeBuffer(serverLastSeq, maxSessionRecords);
                sessionMode = start.getMode().name();
                // For a delta replay (first_seq below the watermark) the segment's first_seq is the
                // first genuinely-new sequence, not the replayed start, so the persisted segment row
                // never claims a range it did not accept.
                firstSeq = delta ? Math.max(start.getFirstSeq(), serverLastSeq + 1) : start.getFirstSeq();
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
                    // The declared last_seq must equal the highest accepted seq (proto: seq of the
                    // last ChangeRecord). A non-zero mismatch betrays a client watermark bug that
                    // counts/hash would not catch (P2). last_seq=0 means the client opted out.
                    if (end.getLastSeq() != 0 && end.getLastSeq() != buffer.lastSeq()) {
                        metrics.reconciliationFailed();
                        emitError(ErrorCode.RECONCILIATION_FAILED,
                                "Declared last_seq=" + end.getLastSeq() + " does not match highest accepted seq="
                                        + buffer.lastSeq(),
                                RecoveryAction.NEED_REBASELINE);
                        return; // do not complete the batch
                    }
                }
                long committed = buffer != null ? buffer.lastSeq() : end.getLastSeq();
                emitSealed(committed, buffer != null ? buffer.accepted() : List.of(), rebaseline);
                this.committed = true;
                closed = true;
                responseObserver.onCompleted();
            }

            /**
             * Commit orchestration for one segment: persist it, advance the watermark, complete the
             * batch, record metrics, and emit {@code SessionCommitted}. Shared by {@code SessionEnd}
             * and continuous sealing.
             */
            private void emitSealed(long committedSeq, List<ChangeRecord> records, boolean rebaselineCommit) {
                long checkpointSeq = syncStateService.getSyncState(siteId).lastCheckpointSeq();
                // Persist the segment, advance the watermark, and complete the batch atomically so a
                // failure can never leave the watermark ahead of a still-active batch. A rebaseline
                // commit additionally wipes the old baseline in the same transaction.
                String segmentKey = commitService.commit(
                        siteId, batchId, sessionMode, firstSeq, committedSeq, records, rebaselineCommit);
                metrics.sessionCommitted();
                metrics.recordSeqLag(committedSeq - checkpointSeq);
                responseObserver.onNext(ServerEvent.newBuilder()
                        .setCommitted(SessionCommitted.newBuilder()
                                .setCommittedSeq(committedSeq)
                                .setSegmentS3Key(segmentKey)
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
                emitSealed(committedSeq, buffer.accepted(), false); // CONTINUOUS is never a rebaseline
                if (continueStream) {
                    batchId = batchLifecycleService.startBatch(accountId, siteId).getId();
                    firstSeq = committedSeq + 1;
                    buffer = new SessionChangeBuffer(committedSeq, maxSessionRecords);
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
                    stagedSessions.put(siteId, new StagedSession(
                            batchId, sessionMode, firstSeq, buffer, System.currentTimeMillis()));
                    closed = true;
                }
            }

            private void emitError(ErrorCode code, String message, RecoveryAction action) {
                abortBatch();
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

            /**
             * Fail the in-progress batch when a session is rejected, so it is not left {@code IN_PROGRESS}
             * (which would block the site with {@code ACTIVE_SESSION_EXISTS} until the timeout sweeper).
             * Best-effort and idempotent: no-op when no batch is open or it already committed.
             */
            private void abortBatch() {
                if (batchId != null && !committed) {
                    UUID toFail = batchId;
                    batchId = null;
                    try {
                        batchLifecycleService.failBatch(toFail);
                    } catch (RuntimeException ignored) {
                        // Batch may already be terminal (e.g. timed out); failing it is best-effort.
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                if (continuous) {
                    // A continuous session has no SessionEnd, so a transport drop must not leave its
                    // batch IN_PROGRESS (which would block reconnect with ACTIVE_SESSION_EXISTS).
                    // Durably seal whatever was received since the last seal; the client recovers via
                    // GetSyncState and resumes from the advanced watermark.
                    sealOnContinuousDrop();
                } else {
                    // Periodic session dropped mid-stream — stage for resume (T5.1); the batch is left
                    // active on purpose so the reconnect re-attaches to it.
                    stageForResume();
                }
            }

            /**
             * Durably seal the in-progress continuous batch after a transport drop, without notifying
             * the (broken) response stream. Best-effort: if the seal fails, fail the batch so it is not
             * orphaned.
             */
            private void sealOnContinuousDrop() {
                if (closed || committed || batchId == null || buffer == null) {
                    return;
                }
                closed = true;
                try {
                    commitService.commit(siteId, batchId, sessionMode, firstSeq,
                            buffer.lastSeq(), buffer.accepted());
                    metrics.sessionCommitted();
                } catch (RuntimeException e) {
                    abortBatch();
                }
            }

            @Override
            public void onCompleted() {
                if (closed) {
                    return;
                }
                try {
                    if (continuous) {
                        // Flush the final segment and complete its batch, then close (T5.4). Even an
                        // empty buffer must be sealed: after a threshold seal opened a fresh batch, a
                        // clean close with no further records would otherwise leave that batch
                        // IN_PROGRESS, blocking the site with ACTIVE_SESSION_EXISTS until the sweeper (P1).
                        if (buffer != null) {
                            sealContinuous(false);
                        }
                    } else {
                        // Half-close without SessionEnd = abandoned periodic session — stage for resume (T5.1).
                        stageForResume();
                    }
                } catch (RuntimeException e) {
                    // A commit failure on the final seal must not escape the gRPC callback and strand
                    // the batch IN_PROGRESS — fail the batch and surface the error, like onNext/onError.
                    abortBatch();
                    closed = true;
                    responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
                    return;
                }
                closed = true;
                responseObserver.onCompleted();
            }
        };
    }

    /**
     * A session retained for resume after a mid-session drop (T5.1): the active batch it opened, its
     * mode and first sequence, the buffer of records staged so far, and when it was staged (for TTL
     * eviction).
     */
    private record StagedSession(UUID batchId, String mode, long firstSeq, SessionChangeBuffer buffer,
                                 long stagedAtMillis) {
    }
}
