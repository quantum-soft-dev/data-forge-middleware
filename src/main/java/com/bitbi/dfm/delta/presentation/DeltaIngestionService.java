package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.delta.application.ChangeRecordValidator;
import com.bitbi.dfm.delta.application.DeltaMetrics;
import com.bitbi.dfm.delta.application.DeltaRebaselineService;
import com.bitbi.dfm.delta.application.DeltaSessionCommitService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.application.SessionTotals;
import com.bitbi.dfm.delta.application.DeltaSyncStateService.SyncStateView;
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
    private final DeltaRebaselineService rebaselineService;
    private final DeltaMetrics metrics;
    /** Max records one session may buffer before the server rejects it (OOM guard). */
    private final int maxSessionRecords;
    /** Max cumulative serialized bytes one session may buffer before the server rejects it (OOM guard). */
    private final long maxSessionBytes;
    /** Staged sessions older than this are evicted by the sweep (defends against a leak). */
    private final long stagedTtlMillis;
    /** In CONTINUOUS mode, also seal a non-empty segment this long after the last seal (time trigger). */
    private final long continuousSealMillis;
    /** In CONTINUOUS mode, also seal once this many serialized bytes have accumulated (byte trigger). */
    private final long continuousSealBytes;

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
                                 DeltaRebaselineService rebaselineService,
                                 DeltaMetrics metrics,
                                 @org.springframework.beans.factory.annotation.Value(
                                         "${delta.ingestion.max-session-records:2000000}") int maxSessionRecords,
                                 @org.springframework.beans.factory.annotation.Value(
                                         "${delta.ingestion.max-session-bytes:0}") long maxSessionBytes,
                                 @org.springframework.beans.factory.annotation.Value(
                                         "${delta.ingestion.staged-ttl-millis:3000000}") long stagedTtlMillis,
                                 @org.springframework.beans.factory.annotation.Value(
                                         "${delta.ingestion.continuous-seal-millis:300000}") long continuousSealMillis,
                                 @org.springframework.beans.factory.annotation.Value(
                                         "${delta.ingestion.continuous-seal-bytes:16777216}") long continuousSealBytes) {
        this.syncStateService = syncStateService;
        this.batchLifecycleService = batchLifecycleService;
        this.siteSchemaService = siteSchemaService;
        this.commitService = commitService;
        this.rebaselineService = rebaselineService;
        this.metrics = metrics;
        this.maxSessionRecords = maxSessionRecords;
        this.maxSessionBytes = resolveMaxSessionBytes(maxSessionBytes);
        this.stagedTtlMillis = stagedTtlMillis;
        this.continuousSealMillis = continuousSealMillis;
        this.continuousSealBytes = continuousSealBytes;
        // Fail fast: with the seal threshold at or above the budget, a fat CONTINUOUS stream would
        // hit OVERFLOW_BYTES before the byte seal ever fires. Reachable when the auto budget
        // (maxHeap/8) drops to <= 16MiB on a heap of <= 128MiB.
        if (continuousSealBytes >= this.maxSessionBytes) {
            throw new IllegalArgumentException(
                    "delta.ingestion.continuous-seal-bytes (" + continuousSealBytes
                            + ") must be below the session byte budget (delta.ingestion.max-session-bytes, "
                            + "resolved to " + this.maxSessionBytes
                            + "), or the budget rejects a continuous stream before the seal fires");
        }
    }

    /**
     * Resolve the per-session byte budget: a non-positive value (the config default) means auto —
     * an eighth of the max heap, so the guard scales with the pod. The budget counts serialized
     * proto bytes; the retained heap of buffered records is roughly 3–5x that, so an eighth keeps
     * the worst-case buffer well under half the heap.
     */
    static long resolveMaxSessionBytes(long configured) {
        return configured > 0 ? configured : Runtime.getRuntime().maxMemory() / 8;
    }

    /**
     * Evict staged sessions older than the TTL and fail their orphaned batches, so a client that
     * drops mid-session and never resumes cannot leak its buffer (and the still-active batch)
     * indefinitely (review r4).
     * <p>
     * 030: the TTL sits <em>below</em> {@code batch.timeout.minutes} (50 min against 60, leaving
     * room for this sweep's 5-minute granularity). It used to sit just above it, which left a
     * window where a staged session was still resumable onto a batch the timeout sweeper had
     * already reaped — a failure that only surfaced at the final commit.
     * </p>
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
            /**
             * First sequence of the whole session, unlike {@link #firstSeq} which tracks the segment
             * currently accumulating. They diverge once a session seals mid-stream; a re-baseline
             * resets the baseline in terms of where the snapshot started, not where its tail did.
             */
            private long sessionFirstSeq;
            /**
             * Whole-session counts + content hash (033). A sealed session no longer holds its earlier
             * records, so {@code SessionEnd} reconciliation cannot read them back off the buffer.
             */
            private SessionTotals totals;
            private boolean closed;
            private boolean committed;
            private boolean continuous;
            private boolean rebaseline;
            private int sinceAck;
            /** Wall-clock of the last continuous seal, for the time-based seal trigger. */
            private long lastSealMillis = System.currentTimeMillis();
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
                        metrics.sessionOverflowedRecords();
                        emitError(ErrorCode.INTERNAL,
                                "Session exceeded the " + maxSessionRecords + "-record limit; stream large "
                                        + "datasets in CONTINUOUS mode",
                                RecoveryAction.NEED_REBASELINE);
                        return;
                    }
                    case OVERFLOW_BYTES -> {
                        // The record cap counts rows, not bytes: a fat-but-few-rows snapshot could
                        // still buffer gigabytes on-heap (a 439k-row snapshot OOMed a 1536Mi pod).
                        // Reject with a clean session error instead of killing the JVM.
                        metrics.sessionOverflowedBytes();
                        // "Use CONTINUOUS mode" is nonsense advice to a session that already is —
                        // reachable only when budget minus seal threshold is smaller than one record.
                        emitError(ErrorCode.INTERNAL,
                                "Session exceeded the " + maxSessionBytes + "-byte buffer budget; "
                                        + (continuous
                                        ? "raise delta.ingestion.max-session-bytes or lower "
                                                + "delta.ingestion.continuous-seal-bytes"
                                        : "stream large datasets in CONTINUOUS mode"),
                                RecoveryAction.NEED_REBASELINE);
                        return;
                    }
                    case ACCEPTED -> totals.add(List.of(change));
                }
                if (++sinceAck >= ACK_INTERVAL) {
                    sinceAck = 0;
                    // Liveness signal for the activity-based timeout (029/T006): touch at the same
                    // bounded cadence as the ack watermark, never per record.
                    batchLifecycleService.touchActivity(batchId);
                    responseObserver.onNext(ServerEvent.newBuilder()
                            .setAck(Ack.newBuilder().setAckedSeq(buffer.lastSeq()).build())
                            .build());
                }
                // Continuous mode: seal a segment on the size threshold OR after the time threshold
                // since the last seal (T5.4). The time trigger bounds staleness for low-throughput
                // streams that would never reach the record count — otherwise a trickle client's
                // watermark/egress/UI could sit stale for hours (review r4).
                boolean sizeReached = buffer.acceptedCount() >= CONTINUOUS_SEAL_RECORDS;
                boolean timeReached = buffer.acceptedCount() > 0
                        && System.currentTimeMillis() - lastSealMillis >= continuousSealMillis;
                // Byte trigger: 100 fat records can weigh as much as thousands of thin ones (each may
                // approach the 4MB gRPC message cap), so a fat stream seals into more, smaller
                // segments instead of a bloated buffer. Keeps the buffer far below the session byte
                // budget, which then only backstops misconfiguration.
                boolean bytesReached = buffer.acceptedBytes() >= continuousSealBytes;
                // 033: a re-baseline seals on the same thresholds. Without this the whole snapshot
                // buffered on-heap, so any site above max-session-records was rejected with
                // NEED_REBASELINE — which sent the client straight back into FULL_SNAPSHOT (#82).
                // Its seals are silent and provisional: see sealSegment.
                if ((continuous || rebaseline) && (sizeReached || timeReached || bytesReached)) {
                    sealSegment();
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
                        // 030: a staged session can outlive its batch — the timeout sweeper reaps
                        // an IN_PROGRESS batch after batch.timeout.minutes, while the staged entry
                        // survives until its own TTL. Re-attaching blindly to a reaped batch only
                        // blew up much later, at the final commit, after the client had replayed.
                        // Detect it here and run the resumed session under a fresh batch instead,
                        // carrying the staged buffer across so nothing staged before the drop is
                        // lost and the client still replays only from the staged watermark.
                        UUID resumeBatchId = resume.batchId();
                        if (!batchLifecycleService.isBatchInProgress(resumeBatchId)) {
                            Batch replacement;
                            try {
                                replacement = batchLifecycleService.startBatch(accountId, siteId);
                            } catch (BatchLifecycleService.ActiveBatchExistsException e) {
                                // Same rule as a fresh session start: one active session per site.
                                emitError(ErrorCode.ACTIVE_SESSION_EXISTS, e.getMessage(),
                                        RecoveryAction.PROCEED);
                                return;
                            }
                            resumeBatchId = replacement.getId();
                        }
                        batchId = resumeBatchId;
                        buffer = resume.buffer();
                        sessionMode = resume.mode();
                        firstSeq = resume.firstSeq();
                        // 033: the session's own origin and its running reconciliation totals travel
                        // with the staged session. Re-deriving them here would reconcile the resumed
                        // session against only the records that arrived after the drop.
                        sessionFirstSeq = resume.sessionFirstSeq();
                        totals = resume.totals();
                        // 030/T05: restore the re-baseline intent. A FULL_SNAPSHOT is not CONTINUOUS,
                        // so a mid-stream drop stages it here — and this branch returns before the
                        // fresh-start path would set the flag. Losing it committed a re-baseline as
                        // an ordinary delta: the old segments/checkpoints were never wiped and the
                        // new snapshot folded on top of stale data. Carried explicitly rather than
                        // re-derived from the mode, so the staged session records what it decided.
                        rebaseline = resume.rebaseline();
                        // `continuous` is deliberately NOT restored: onError routes continuous
                        // sessions to sealOnContinuousDrop(), so only non-continuous sessions ever
                        // reach stageForResume(). A staged session was never continuous, and
                        // setting the flag here would enable mid-stream seals that must not happen.
                        // The re-attached batch is live again — refresh its activity (029).
                        batchLifecycleService.touchActivity(batchId);
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
                    // A non-DELTA start deliberately abandons any staged session. Its batch was left
                    // IN_PROGRESS so a DELTA reconnect could re-attach; nothing will now, and leaving
                    // it active would reject this session with ACTIVE_SESSION_EXISTS until the staged
                    // sweeper runs (~50 min). That made a re-baseline that dropped mid-snapshot
                    // unretryable for the best part of an hour — precisely the case 033 exists to fix.
                    StagedSession superseded = stagedSessions.remove(siteId);
                    if (superseded != null) {
                        try {
                            batchLifecycleService.failBatch(superseded.batchId());
                        } catch (RuntimeException ignored) {
                            // Already terminal (e.g. timed out) — best-effort.
                        }
                    }
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
                // First liveness touch: the activity-based timeout (029) measures from last session
                // activity, and a session that opens and stalls must still be reclaimable.
                batchLifecycleService.touchActivity(batchId);
                if (start.getMode() == SessionMode.FULL_SNAPSHOT) {
                    // Re-baseline: the prior changelog and checkpoints are discarded so the snapshot
                    // becomes the new baseline. The destruction is DEFERRED to commit (done in the
                    // same transaction as the new snapshot segment), so a snapshot that drops before
                    // it commits leaves the old baseline intact (review r4). Buffer records from just
                    // before the snapshot's first record.
                    rebaseline = true;
                    serverLastSeq = start.getFirstSeq() - 1;
                    // 033: collect the invisible remains of an earlier snapshot that never reached
                    // SessionEnd. They are harmless to readers but hold UNIQUE (site_id, first_seq)
                    // slots this attempt may need.
                    rebaselineService.deleteProvisional(siteId);
                }
                buffer = new SessionChangeBuffer(serverLastSeq, maxSessionRecords, maxSessionBytes);
                totals = new SessionTotals();
                sessionMode = start.getMode().name();
                // For a delta replay (first_seq below the watermark) the segment's first_seq is the
                // first genuinely-new sequence, not the replayed start, so the persisted segment row
                // never claims a range it did not accept.
                firstSeq = delta ? Math.max(start.getFirstSeq(), serverLastSeq + 1) : start.getFirstSeq();
                sessionFirstSeq = firstSeq;
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
                    // 033: verified against the session-scoped totals, not the buffer — a sealed
                    // session has already discarded everything but its tail.
                    if (!totals.reconcile(end.getPerTableMap())) {
                        metrics.reconciliationFailed();
                        emitError(ErrorCode.RECONCILIATION_FAILED,
                                "Declared per-table counts do not match accepted records",
                                RecoveryAction.NEED_REBASELINE);
                        return; // do not complete the batch
                    }
                    if (!totals.hashMatches(end.getContentHash())) {
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
             * Session-completing commit: persist the final segment (when non-empty), advance the
             * watermark, complete the session's batch, record metrics, and emit
             * {@code SessionCommitted}. Used by {@code SessionEnd} and the final continuous flush;
             * mid-stream seals go through {@link #sealSegment()} instead and never touch the batch.
             */
            private void emitSealed(long committedSeq, List<ChangeRecord> records, boolean rebaselineCommit) {
                long checkpointSeq = syncStateService.getSyncState(siteId).lastCheckpointSeq();
                // Persist the segment, advance the watermark, and complete the batch atomically so a
                // failure can never leave the watermark ahead of a still-active batch. A rebaseline
                // commit additionally wipes the old baseline in the same transaction.
                String segmentKey = commitService.commit(
                        siteId, batchId, sessionMode, firstSeq, committedSeq, records, rebaselineCommit,
                        sessionFirstSeq);
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
             * Seal the current continuous-mode segment mid-stream (T5.4, reshaped by 029): the
             * segment commits durably under the session's single batch and the buffer restarts —
             * the batch itself stays {@code IN_PROGRESS} until the session closes. A seal is a
             * storage-durability event, not a batch boundary.
             */
            private void sealSegment() {
                long committedSeq = buffer.lastSeq();
                long checkpointSeq = syncStateService.getSyncState(siteId).lastCheckpointSeq();
                if (rebaseline) {
                    // 033: a snapshot seal is durable but invisible — no watermark move and no
                    // SessionCommitted. Announcing it would break the periodic-session contract
                    // ("the server replies SessionCommitted and closes its side"): a conforming
                    // client would treat the first seal as end-of-session and stop mid-snapshot.
                    // The progressive Acks above remain the client's only progress signal, exactly
                    // as before 033, so no client change is needed.
                    commitService.commitProvisionalSegment(
                            siteId, batchId, sessionMode, firstSeq, buffer.accepted());
                } else {
                    String segmentKey = commitService.commitSegment(
                            siteId, batchId, sessionMode, firstSeq, committedSeq, buffer.accepted());
                    metrics.recordSeqLag(committedSeq - checkpointSeq);
                    responseObserver.onNext(ServerEvent.newBuilder()
                            .setCommitted(SessionCommitted.newBuilder()
                                    .setCommittedSeq(committedSeq)
                                    .setSegmentS3Key(segmentKey)
                                    .build())
                            .build());
                }
                batchLifecycleService.touchActivity(batchId);
                lastSealMillis = System.currentTimeMillis();
                firstSeq = committedSeq + 1;
                buffer = new SessionChangeBuffer(committedSeq, maxSessionRecords, maxSessionBytes);
                sinceAck = 0;
            }

            /**
             * Retain an open, uncommitted session for resume (T5.1). Called on a transport drop or a
             * half-close without {@code SessionEnd}; the batch is left active so the reconnect
             * re-attaches to it. No-op once the session has committed or otherwise closed.
             */
            private void stageForResume() {
                if (!closed && !committed && batchId != null && buffer != null) {
                    // 030/T05: the re-baseline intent is staged with the session — it decides
                    // whether the eventual commit wipes the prior baseline, and a drop must not
                    // silently downgrade a FULL_SNAPSHOT to an ordinary delta commit.
                    stagedSessions.put(siteId, new StagedSession(
                            batchId, sessionMode, firstSeq, sessionFirstSeq, buffer, totals, rebaseline,
                            System.currentTimeMillis()));
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
                    if (rebaseline) {
                        // 033: drop this snapshot's provisional segments now rather than leaving them
                        // for the next attempt's start-of-session sweep. They were never published,
                        // so nothing observable changes — this only shortens how long the storage and
                        // the UNIQUE (site_id, first_seq) slots stay occupied.
                        try {
                            rebaselineService.deleteProvisional(siteId);
                        } catch (RuntimeException ignored) {
                            // Best-effort: the next FULL_SNAPSHOT start collects whatever is left.
                        }
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
                        // Flush the final segment (possibly empty — no segment row is written then)
                        // and complete the session's single batch, then close (T5.4/029). Even a
                        // zero-record close must complete the batch: it is one honest upload attempt,
                        // and an IN_PROGRESS leftover would block the site with ACTIVE_SESSION_EXISTS
                        // until the sweeper (P1).
                        if (buffer != null) {
                            emitSealed(buffer.lastSeq(), buffer.accepted(), false);
                            committed = true;
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
     * mode, the first sequence of the segment being accumulated and of the session as a whole (033 —
     * they differ once a re-baseline has sealed), the buffer of records staged so far, the
     * whole-session reconciliation totals (033 — records already sealed are no longer in the buffer),
     * whether it is a re-baseline (030 — a FULL_SNAPSHOT must still wipe the prior baseline when it
     * commits after a resume), and when it was staged (for TTL eviction).
     */
    private record StagedSession(UUID batchId, String mode, long firstSeq, long sessionFirstSeq,
                                 SessionChangeBuffer buffer, SessionTotals totals,
                                 boolean rebaseline, long stagedAtMillis) {
    }
}
