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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p><b>Observability (#83).</b> Since client API v1 was retired this is the only path that creates
 * a batch, and it used to carry no logger at all — a rejected session left no trace anywhere, so an
 * incident could only be investigated by proving the <em>absence</em> of log lines. Every session
 * now records its start, its opening, each seal, its commit and — when it ends abnormally — the gRPC
 * status that broke its transport, all at INFO; every typed rejection at WARN with its
 * {@code ErrorCode} and {@code RecoveryAction}; every unexpected fault at ERROR with its stack
 * trace. Deliberately bounded — per session and per seal, never per record.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class DeltaIngestionService extends DeltaIngestionGrpc.DeltaIngestionImplBase {

    private static final Logger logger = LoggerFactory.getLogger(DeltaIngestionService.class);

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
    /** Max orphaned provisional segments collected per scheduled sweep pass. */
    private final int provisionalSweepBatch;
    /**
     * Records after which a re-baseline seals (033 review). Deliberately far above
     * {@link #CONTINUOUS_SEAL_RECORDS}: 100 is a staleness threshold for a trickle stream, but for a
     * bulk snapshot it would cut a 5M-row dataset into 50,000 segments — 50,000 transactions, S3
     * objects and downstream queue items, each stalling record intake for a full S3 round trip. A
     * snapshot is bounded by the byte trigger instead; this only caps very narrow rows.
     */
    private final int snapshotSealRecords;

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
                                         "${delta.ingestion.continuous-seal-bytes:16777216}") long continuousSealBytes,
                                 @org.springframework.beans.factory.annotation.Value(
                                         "${delta.ingestion.provisional-sweep-batch:500}") int provisionalSweepBatch,
                                 @org.springframework.beans.factory.annotation.Value(
                                         "${delta.ingestion.snapshot-seal-records:25000}") int snapshotSealRecords) {
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
        this.provisionalSweepBatch = provisionalSweepBatch;
        this.snapshotSealRecords = snapshotSealRecords;
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
        // The same shape on the record axis, and a worse outcome (dbf-data-extractor#130). OVERFLOW
        // tells a DELTA session to retry in CONTINUOUS mode, but a FULL_SNAPSHOT has nowhere to go:
        // a CONTINUOUS snapshot would lose the atomic replace-on-SessionEnd that makes it a snapshot.
        // So a snapshot that can reach the cap before its seal fires is not a session that failed —
        // it is a site that cannot be re-baselined at all, and the misconfiguration would only
        // surface as an unrecoverable OVERFLOW on whichever site first grew past the cap. The
        // shipped pair (25k against 2M) is safe; nothing but this check kept it that way.
        if (snapshotSealRecords >= maxSessionRecords) {
            throw new IllegalArgumentException(
                    "delta.ingestion.snapshot-seal-records (" + snapshotSealRecords
                            + ") must be below delta.ingestion.max-session-records (" + maxSessionRecords
                            + "), or a full snapshot overflows before it seals and the site can never "
                            + "be re-baselined");
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
                logger.warn("Delta staged session evicted after {} ms without a resume — its {} staged "
                                + "records are discarded: siteId={}, batchId={}",
                        stagedTtlMillis, staged.buffer().acceptedCount(), site, staged.batchId());
                try {
                    batchLifecycleService.failBatch(staged.batchId());
                } catch (RuntimeException e) {
                    // Best-effort, but rarely benign here: the staged TTL sits below
                    // batch.timeout.minutes, so at eviction the batch is normally still IN_PROGRESS
                    // and a failure leaves it stranded — blocking the site with ACTIVE_SESSION_EXISTS
                    // until the timeout sweeper. WARN with the stack trace, not DEBUG.
                    logger.warn("Delta staged session eviction could not fail its batch, it may stay "
                            + "IN_PROGRESS until the timeout sweeper: batchId={}", staged.batchId(), e);
                }
                // 033 review: an evicted session will never resume, so its provisional segments are
                // now unreachable garbage. Without this they survive until the scheduled sweep.
                collectProvisional(staged.batchId());
            }
        });
    }

    /**
     * Sweep provisional segments whose owning batch is no longer running (033 review).
     *
     * <p>The batch-scoped collections elsewhere only fire while this server is alive to run them. A
     * pod that is OOM-killed or rescheduled mid-snapshot leaves rows no reader can see, that
     * retention cannot reach (it enumerates published segments only), and that no session will
     * revisit. A staged session keeps its batch {@code IN_PROGRESS} on purpose, so it is never
     * swept here.</p>
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString =
            "${delta.ingestion.provisional-sweep-millis:900000}")
    public void sweepOrphanedProvisionalSegments() {
        try {
            int collected = rebaselineService.sweepOrphanedProvisional(provisionalSweepBatch);
            if (collected > 0) {
                logger.info("Swept {} orphaned provisional segment(s) left by dead sessions", collected);
            }
        } catch (RuntimeException e) {
            logger.warn("Orphaned-provisional sweep failed; will retry on the next tick", e);
        }
    }

    /**
     * Reclaim a site's active batch when it has been silent for longer than the staged-session TTL —
     * i.e. past the point at which the pod holding it would itself have given up on a resume. Being
     * DB-driven this works across replicas, unlike the in-memory staged map.
     *
     * @return true when a batch was reclaimed and the caller may retry startBatch
     */
    private boolean reclaimAbandonedBatch(UUID siteId) {
        try {
            java.time.LocalDateTime cutoff = java.time.LocalDateTime.now()
                    .minus(java.time.Duration.ofMillis(stagedTtlMillis));
            boolean reclaimed = batchLifecycleService.reclaimAbandonedBatch(siteId, cutoff);
            if (reclaimed) {
                logger.info("Reclaimed an abandoned batch for site {} (silent for over {} ms) so a new "
                        + "session could start", siteId, stagedTtlMillis);
            }
            return reclaimed;
        } catch (RuntimeException e) {
            logger.warn("Could not reclaim an abandoned batch for site {}", siteId, e);
            return false;
        }
    }

    /** Best-effort, batch-scoped collection of a dead session's provisional segments. */
    private void collectProvisional(UUID batchId) {
        if (batchId == null) {
            return;
        }
        try {
            rebaselineService.deleteProvisionalByBatch(batchId);
        } catch (RuntimeException e) {
            // The scheduled sweep is the backstop — never let cleanup break the session path.
            logger.warn("Could not collect provisional segments of batch {}", batchId, e);
        }
    }

    @Override
    public void getSyncState(SyncStateRequest request, StreamObserver<SyncStateResponse> responseObserver) {
        UUID siteId = DeltaAuthInterceptor.SITE_ID.get();
        if (siteId == null) {
            logger.warn("auth_failure: GetSyncState reached the handler with no authenticated site on context");
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("No authenticated site on context").asRuntimeException());
            return;
        }

        SyncStateView view = syncStateService.getSyncState(siteId);
        if (view.needRebaseline() && !view.rebaselineNotified()) {
            // The client now knows it must re-baseline, so a cancellation from here on may lose the
            // race against the snapshot it is about to send (issue #84). Recorded once per request:
            // the view already tells us whether the stamp is there, so a poll during the (hours
            // long) snapshot upload costs no extra write.
            syncStateService.markRebaselineNotified(siteId);
        }

        SyncStateResponse response = SyncStateResponse.newBuilder()
                .setLastAppliedSeq(view.lastAppliedSeq())
                .setLastCheckpointSeq(view.lastCheckpointSeq())
                .setSchemaVersion(view.schemaVersion())
                .setAction(view.needRebaseline() ? RecoveryAction.NEED_REBASELINE : RecoveryAction.PROCEED)
                // The epoch (issue #89): a value the client has not seen before means its whole
                // local state — journal, seq counter, cached schema ack — belongs to a history the
                // server no longer has.
                .setGeneration(view.generation())
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void submitSchema(SchemaRequest request, StreamObserver<SchemaResponse> responseObserver) {
        UUID siteId = DeltaAuthInterceptor.SITE_ID.get();
        if (siteId == null) {
            logger.warn("auth_failure: SubmitSchema reached the handler with no authenticated site on context");
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
            logger.info("Delta schema submitted: siteId={}, schemaVersion={}, tables={}",
                    siteId, saved.getSchemaVersion(), request.getTablesMap().keySet());
            responseObserver.onCompleted();
        } catch (SiteSchemaService.InvalidSchemaException e) {
            // A CDC site cannot open a session until its schema lands, so a rejected schema is the
            // upstream cause of every SCHEMA_MISMATCH that follows.
            logger.warn("Delta schema rejected: siteId={}, reason={}", siteId, e.getMessage());
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
            /** Bounds the "records with no open session" warning to one per stream. */
            private boolean reportedRecordsWithNoSession;
            private int sinceAck;
            /** Wall-clock of the last continuous seal, for the time-based seal trigger. */
            private long lastSealMillis = System.currentTimeMillis();
            /** Checkpoint seq cached for this session's lag metric; -1 until first read. */
            private long sessionCheckpointSeq = -1L;
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
                    // #83: this used to close the call with a bare Status.INTERNAL and record
                    // nothing. The stack trace is the whole point — an unexpected fault here is
                    // otherwise indistinguishable from the client simply never having called.
                    logger.error("Delta session failed: siteId={}, batchId={}, event={}",
                            siteId, batchId, event.getEventCase(), e);
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
                    // #83: discarding these silently was the last way a session could end with no
                    // trace at all — no ServerError, no batch, a clean half-close, and a client that
                    // believes it uploaded. Reported once per session, never per record.
                    if (!reportedRecordsWithNoSession) {
                        reportedRecordsWithNoSession = true;
                        logger.warn("Delta change records discarded, no open session on this stream — "
                                        + "SessionStart was never accepted: siteId={}, firstSeq={}, table={}",
                                siteId, change.getSeq(), change.getTable());
                    }
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
                        // Typed since #130 Q5/Q6: INTERNAL may equally be a bug, so the client could
                        // not safely read it as "retry in CONTINUOUS".
                        emitError(ErrorCode.OVERFLOW,
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
                        emitError(ErrorCode.OVERFLOW_BYTES,
                                "Session exceeded the " + maxSessionBytes + "-byte buffer budget; "
                                        + (continuous
                                        ? "raise delta.ingestion.max-session-bytes or lower "
                                                + "delta.ingestion.continuous-seal-bytes"
                                        : "stream large datasets in CONTINUOUS mode"),
                                RecoveryAction.NEED_REBASELINE);
                        return;
                    }
                    case ACCEPTED -> totals.add(change);
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
                int sealRecords = rebaseline ? snapshotSealRecords : CONTINUOUS_SEAL_RECORDS;
                boolean sizeReached = buffer.acceptedCount() >= sealRecords;
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
             * Open the session's batch, mapping every business rejection
             * {@link BatchLifecycleService#startBatch} documents onto the typed stream protocol.
             * <p>
             * #83: only {@code ActiveBatchExistsException} was ever mapped. The other three escaped
             * to the generic {@code catch (RuntimeException)} in {@link #onNext(ClientEvent)}, which
             * closed the call with a bare {@code Status.INTERNAL} carrying no {@code ErrorCode} — so
             * a deactivated site, a CDC site with no schema on file, and a real server fault were
             * indistinguishable to the client, and invisible to us.
             * </p>
             *
             * @param sessionMode the session's mode, recorded on the batch so a running FULL_SNAPSHOT
             *                    stays recognizable while it uploads (issue #84)
             * @return the opened batch, or {@code null} when the session was rejected (the caller
             *         must return; the {@code ServerError} is already on the wire)
             */
            private Batch openBatch(String sessionMode) {
                try {
                    return batchLifecycleService.startBatch(accountId, siteId, sessionMode);
                } catch (BatchLifecycleService.ActiveBatchExistsException e) {
                    // 033 review: before refusing, try to reclaim a batch nobody is driving any more.
                    // A dropped session is staged in the SERVING pod's memory, so with more than one
                    // replica the retry usually lands elsewhere and the in-memory supersede cannot
                    // see it. The reclaim is DB-driven and conditional on the same cutoff the timeout
                    // sweeper uses, so a merely quiet session survives.
                    if (reclaimAbandonedBatch(siteId)) {
                        try {
                            return batchLifecycleService.startBatch(accountId, siteId, sessionMode);
                        } catch (RuntimeException ignored) {
                            // Fall through to the rejection below with the original reason.
                        }
                    }
                    emitError(ErrorCode.ACTIVE_SESSION_EXISTS, e.getMessage(), RecoveryAction.PROCEED);
                } catch (BatchLifecycleService.ConcurrentBatchLimitException e) {
                    // The account-wide concurrency cap — a different rule from the per-site one
                    // above, and now a different code (#130 Q5): the client backs off rather than
                    // waiting on this one site to free up.
                    emitError(ErrorCode.CONCURRENT_BATCH_LIMIT, e.getMessage(), RecoveryAction.PROCEED);
                } catch (BatchLifecycleService.SiteNotFoundException e) {
                    // The site was deleted while this stream was open — the token was validated once,
                    // at stream open. Same answer as a deactivated site: the credentials are fine,
                    // the site is not, and no retry will fix it without an operator.
                    emitError(ErrorCode.SITE_INACTIVE, e.getMessage(), RecoveryAction.PROCEED);
                } catch (BatchLifecycleService.SiteInactiveException e) {
                    // The site's credentials are still valid, the site itself is not. Distinct from
                    // UNAUTHORIZED since #130 Q5: that one tells the client to fix its token, this
                    // one tells it to stop and page an operator.
                    emitError(ErrorCode.SITE_INACTIVE, e.getMessage(), RecoveryAction.PROCEED);
                } catch (BatchLifecycleService.SchemaRequiredException e) {
                    // PROCEED, not NEED_REBASELINE: a FULL_SNAPSHOT would hit the same wall. The
                    // client must SubmitSchema first — which is what this code, unlike the stale-
                    // version SCHEMA_MISMATCH it used to share, says unambiguously (#130 Q5).
                    emitError(ErrorCode.SCHEMA_REQUIRED, e.getMessage(), RecoveryAction.PROCEED);
                }
                return null;
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
                logger.info("Delta session start: siteId={}, mode={}, firstSeq={}, schemaVersion={}, "
                                + "generation={}",
                        siteId, start.getMode(), start.getFirstSeq(), start.getSchemaVersion(),
                        start.getGeneration());

                SyncStateView state = syncStateService.getSyncState(siteId);

                // Epoch guard (issue #89): the client's sequence numbers only mean something within
                // the generation they were produced in. A client that saw a wipe but crashed before
                // resetting would otherwise graft pre-wipe seqs onto a site whose history is gone.
                // Deliberately ahead of the resume branch below: a staged session is heap-local and
                // survives a wipe of its own site, so a resume is exactly the path that can carry
                // stale-epoch records back in.
                //
                // Keyed on field PRESENCE, not on a zero (dbf-data-extractor#130 Q2). A site starts
                // at epoch 0, so the first wipe of any site moves it 0 -> 1 — and while the check
                // read "0 means old client", a new client faithfully echoing epoch 0 was waved
                // through exactly when the guard mattered most. An absent field still skips: such a
                // client cannot decode GENERATION_MISMATCH anyway and recovers through the
                // NEED_REBASELINE the wipe leaves standing.
                if (start.hasGeneration() && start.getGeneration() != state.generation()) {
                    emitError(ErrorCode.GENERATION_MISMATCH,
                            "Session generation=" + start.getGeneration()
                                    + " does not match server generation=" + state.generation()
                                    + " (the site's history was wiped)",
                            RecoveryAction.NEED_REBASELINE);
                    return;
                }

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
                            // Same rules as a fresh session start — including a site deactivated
                            // while this session was parked. The staged session's mode is carried
                            // onto the replacement batch, so a resumed FULL_SNAPSHOT stays
                            // recognizable as one (issue #84).
                            Batch replacement = openBatch(resume.mode());
                            if (replacement == null) {
                                return; // rejected; a typed ServerError was already emitted
                            }
                            resumeBatchId = replacement.getId();
                            // 033 review: publication is keyed on the batch. Segments this session
                            // already sealed carry the reaped batch's id, and reset() cannot see
                            // them (it enumerates published segments only) — so without moving them
                            // the snapshot would commit a baseline missing everything streamed
                            // before the drop, with the watermark advanced and the client told it
                            // succeeded. Reconciliation would not catch it: the totals travel with
                            // the staged session and still match.
                            int moved = commitService.reassignProvisionalSegments(
                                    resume.batchId(), resumeBatchId);
                            if (moved > 0) {
                                logger.info("Resumed session moved {} provisional segment(s) from reaped "
                                        + "batch {} to {}", moved, resume.batchId(), resumeBatchId);
                            }
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
                        logger.info("Delta session opened: siteId={}, batchId={}, mode={}, action=RESUME_FROM, "
                                        + "resumeFromSeq={}, stagedRecords={}, rebaseline={}, freshBatch={}",
                                siteId, batchId, sessionMode, buffer.lastSeq() + 1, buffer.acceptedCount(),
                                rebaseline, !batchId.equals(resume.batchId()));
                        responseObserver.onNext(ServerEvent.newBuilder()
                                .setOpened(SessionOpened.newBuilder()
                                        .setServerSessionId(batchId.toString())
                                        .setServerLastSeq(state.lastAppliedSeq())
                                        .setAction(RecoveryAction.RESUME_FROM)
                                        .setResumeFromSeq(buffer.lastSeq() + 1)
                                        // Echoed on every open (issue #89), so a long-lived client
                                        // that never re-polls GetSyncState still observes a wipe.
                                        .setGeneration(state.generation())
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
                        collectProvisional(superseded.batchId());
                    }
                }

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

                // The mode is persisted on the batch (issue #84): while a FULL_SNAPSHOT is
                // uploading it is the only way to tell it from an ordinary delta session.
                Batch batch = openBatch(start.getMode().name());
                if (batch == null) {
                    return; // rejected; a typed ServerError was already emitted
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
                logger.info("Delta session opened: siteId={}, batchId={}, mode={}, action=PROCEED, "
                                + "firstSeq={}, serverLastSeq={}, rebaseline={}",
                        siteId, batchId, sessionMode, firstSeq, serverLastSeq, rebaseline);
                responseObserver.onNext(ServerEvent.newBuilder()
                        .setOpened(SessionOpened.newBuilder()
                                .setServerSessionId(batchId.toString())
                                .setServerLastSeq(serverLastSeq)
                                .setAction(RecoveryAction.PROCEED)
                                .setGeneration(state.generation())
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
                logger.info("Delta session committed: siteId={}, batchId={}, mode={}, committedSeq={}, "
                                + "records={}, segment={}, rebaseline={}",
                        siteId, batchId, sessionMode, committedSeq, records.size(), segmentKey, rebaselineCommit);
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
                // Read once per session, not once per seal: a checkpoint build is a nightly job, so
                // this cannot move mid-session, and a snapshot seals often enough that the query was
                // pure waste on the ingestion thread.
                if (sessionCheckpointSeq < 0) {
                    sessionCheckpointSeq = syncStateService.getSyncState(siteId).lastCheckpointSeq();
                }
                // Emitted for snapshots too: otherwise the lag gauge goes silent for the whole
                // duration of a large re-baseline — exactly when the site is furthest behind and an
                // operator most needs to tell "streaming" from "stalled".
                metrics.recordSeqLag(committedSeq - sessionCheckpointSeq);
                if (rebaseline) {
                    // 033: a snapshot seal is durable but invisible — no watermark move and no
                    // SessionCommitted. Announcing it would break the periodic-session contract
                    // ("the server replies SessionCommitted and closes its side"): a conforming
                    // client would treat the first seal as end-of-session and stop mid-snapshot.
                    // The progressive Acks above remain the client's only progress signal, exactly
                    // as before 033, so no client change is needed.
                    String segmentKey = commitService.commitProvisionalSegment(
                            siteId, batchId, sessionMode, firstSeq, buffer.accepted());
                    logger.info("Delta snapshot segment sealed (provisional, not yet published): "
                                    + "siteId={}, batchId={}, segment={}, records={}, bytes={}, "
                                    + "firstSeq={}, committedSeq={}",
                            siteId, batchId, segmentKey, buffer.acceptedCount(), buffer.acceptedBytes(),
                            firstSeq, committedSeq);
                } else {
                    String segmentKey = commitService.commitSegment(
                            siteId, batchId, sessionMode, firstSeq, committedSeq, buffer.accepted());
                    logger.info("Delta segment sealed: siteId={}, batchId={}, segment={}, records={}, "
                                    + "bytes={}, firstSeq={}, committedSeq={}",
                            siteId, batchId, segmentKey, buffer.acceptedCount(), buffer.acceptedBytes(),
                            firstSeq, committedSeq);
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
                    logger.info("Delta session staged for resume: siteId={}, batchId={}, mode={}, "
                                    + "stagedRecords={}, resumeFromSeq={}, rebaseline={}",
                            siteId, batchId, sessionMode, buffer.acceptedCount(), buffer.lastSeq() + 1, rebaseline);
                }
            }

            private void emitError(ErrorCode code, String message, RecoveryAction action) {
                // Logged before abortBatch(), which clears batchId — the batch the rejection killed
                // is exactly what an investigation needs to correlate.
                logger.warn("Delta session rejected: siteId={}, batchId={}, code={}, action={}, message={}",
                        siteId, batchId, code, action, message);
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
                    } catch (RuntimeException e) {
                        // Batch may already be terminal (e.g. timed out); failing it is best-effort.
                        logger.debug("Delta batch could not be failed, likely already terminal: batchId={}, error={}",
                                toFail, e.getMessage());
                    }
                    if (rebaseline) {
                        // 033: drop this snapshot's own provisional segments now. Batch-scoped, so a
                        // concurrent session's in-flight snapshot is never touched; the scheduled
                        // sweep is the backstop if this best-effort call does not run.
                        collectProvisional(toFail);
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                // #83: the branches below record what was done about the drop, only this line records
                // what caused it — a client cancel, a deadline, a TLS reset and an oversized message
                // are otherwise the same "staged for resume". It is also the only trace when both
                // branches no-op (a client that connects, sends nothing and drops). INFO and without
                // a stack trace on purpose: the server itself cancels streams at
                // delta.grpc.max-connection-age-seconds, so a drop is an ordinary way for a long
                // CONTINUOUS session to end, not a fault.
                logger.info("Delta session transport drop: siteId={}, batchId={}, status={}, cause={}",
                        siteId, batchId, Status.fromThrowable(t).getCode(), String.valueOf(t));
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
                    logger.info("Delta continuous session dropped, tail sealed: siteId={}, batchId={}, "
                                    + "records={}, committedSeq={}",
                            siteId, batchId, buffer.acceptedCount(), buffer.lastSeq());
                } catch (RuntimeException e) {
                    logger.error("Delta continuous session dropped and its tail could not be sealed — "
                            + "failing the batch: siteId={}, batchId={}", siteId, batchId, e);
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
                    // #83: and like onNext, record it — Status.INTERNAL carries no stack trace, so the
                    // continuous final commit is otherwise the one fault that still closes silently.
                    logger.error("Delta session failed on final close: siteId={}, batchId={}",
                            siteId, batchId, e);
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
