package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.delta.domain.BatchParquetArtifact;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactStatus;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.TableChangeStats;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.TableSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Durable batch-level fan-out into one completed-batch Parquet per table (036/038, issues #93/#97). */
@Service
public class BatchParquetFinalizationService {

    private static final Logger log = LoggerFactory.getLogger(BatchParquetFinalizationService.class);

    private final BatchParquetArtifactRepository artifactRepository;
    private final ChangelogSegmentRepository segmentRepository;
    private final ChangelogSegmentService segmentService;
    private final SiteSchemaService schemaService;
    private final BatchRepository batchRepository;
    private final S3CheckpointStorage storage;
    private final DeltaMetrics metrics;
    private final TransactionTemplate transactions;
    private final ScheduledExecutorService leaseRenewals;
    private final Path tempDirectory;
    private final long maxTempBytes;
    private final int retryDelaySeconds;
    private final int maxAttempts;
    private final int leaseSeconds;

    public BatchParquetFinalizationService(
            BatchParquetArtifactRepository artifactRepository,
            ChangelogSegmentRepository segmentRepository,
            ChangelogSegmentService segmentService,
            SiteSchemaService schemaService,
            BatchRepository batchRepository,
            S3CheckpointStorage storage,
            DeltaMetrics metrics,
            PlatformTransactionManager transactionManager,
            @Value("${delta.batch-parquet.temp-dir:${java.io.tmpdir}}") String tempDirectory,
            @Value("${delta.batch-parquet.max-temp-bytes:10737418240}") long maxTempBytes,
            @Value("${delta.batch-parquet.retry-delay-seconds:60}") int retryDelaySeconds,
            @Value("${delta.batch-parquet.max-attempts:7}") int maxAttempts,
            @Value("${delta.batch-parquet.lease-seconds:1800}") int leaseSeconds) {
        this.artifactRepository = artifactRepository;
        this.segmentRepository = segmentRepository;
        this.segmentService = segmentService;
        this.schemaService = schemaService;
        this.batchRepository = batchRepository;
        this.storage = storage;
        this.metrics = metrics;
        // Explicit template rather than @Transactional on the steps below: they are called from
        // finalizeNext() on this same bean, and a self-invocation never reaches the proxy — the
        // annotation would silently do nothing, which is exactly what the claim must not do.
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions = template;
        this.leaseRenewals = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "batch-parquet-lease");
            thread.setDaemon(true);
            return thread;
        });
        this.tempDirectory = Path.of(tempDirectory);
        this.maxTempBytes = maxTempBytes;
        this.retryDelaySeconds = retryDelaySeconds;
        this.maxAttempts = maxAttempts;
        this.leaseSeconds = leaseSeconds;
    }

    /**
     * Create the durable per-table work rows after the batch-completion transaction. Existing rows
     * make replayed completion events harmless, and lazy download backfill covers a failed callback.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int enqueueBatch(UUID batchId) {
        List<ChangelogSegment> segments = segmentRepository.findByBatchIdOrderByFirstSeq(batchId);
        if (segments.isEmpty()) {
            return 0;
        }
        return enqueue(batchId, segments.get(0).getSiteId(), segments);
    }

    /**
     * Enqueue a batch on demand, scoped to the site that is asking. Manifest rows exist only for
     * batches completed since 036 shipped, so a download of an older batch would answer 404 for an
     * artifact that is still perfectly buildable — its raw segments are untouched. Backfilling here
     * rebuilds lazily, only for the batches someone actually opens, instead of queueing every
     * historical batch at migration time and pushing fresh sessions behind them.
     *
     * <p>Only a batch in a terminal status is enqueued. A continuous session publishes
     * non-provisional segments while its batch is still {@code IN_PROGRESS}, and enqueueing then
     * would build an artifact from the seals so far, publish it as READY, and never rebuild it —
     * {@link #enqueueBatch} skips tables that already have a row — leaving the finished session
     * serving a silently truncated download.</p>
     *
     * @param siteId  the site the caller was authorized for
     * @param batchId batch (= Delta session) identifier
     * @return rows created; 0 when the batch is unfinished, has no published segments, or belongs
     *         to another site
     */
    @Transactional
    public int enqueueBatchForSite(UUID siteId, UUID batchId) {
        boolean finished = batchRepository.findById(batchId)
                .map(Batch::getStatus)
                .filter(status -> status.isTerminal())
                .isPresent();
        if (!finished) {
            return 0;
        }
        List<ChangelogSegment> segments = segmentRepository.findByBatchIdOrderByFirstSeq(batchId);
        if (segments.isEmpty() || !segments.get(0).getSiteId().equals(siteId)) {
            return 0;
        }
        return enqueue(batchId, siteId, segments);
    }

    private int enqueue(UUID batchId, UUID siteId, List<ChangelogSegment> segments) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int created = 0;
        for (String tableName : tableNames(segments)) {
            // Insert-if-absent rather than read-then-insert: two enqueues of the same batch can run
            // at once (two download clicks, two replicas), and losing that race on the unique index
            // would turn a 409 into a 500 on either the completion callback or lazy backfill path.
            created += artifactRepository.insertPendingIfAbsent(
                    UUID.randomUUID(), batchId, siteId, tableName, now);
        }
        return created;
    }

    private Set<String> tableNames(List<ChangelogSegment> segments) {
        Set<String> tables = new TreeSet<>(aggregateStats(segments).keySet());
        segments.stream().filter(segment -> segment.getStats() == null)
                .forEach(segment -> segmentService.forEachRecord(
                        segment.getS3Key(), change -> tables.add(change.getTable())));
        return tables;
    }

    /**
     * Claim and finalize all currently retryable rows of one batch.
     *
     * <p>The claim commits <em>before</em> the build starts, in its own transaction. That is what
     * makes the attempt ceiling real: if the process dies mid-build (an OOM on a large batch, a pod
     * eviction) the incremented {@code attemptCount} is already durable, so the failure class that
     * costs the most cannot loop forever. It also keeps the long S3 work off a database connection.
     * {@code BUILDING} — not a held row lock — is what stops another replica from picking the row
     * up; a claim whose owner vanished is reclaimed once {@code lease-seconds} elapse.</p>
     *
     * <p>Retries are capped: a row that uses up {@code max-attempts} becomes {@code ABANDONED} and
     * is never claimed again, so a deterministic failure (no declared schema, data the schema
     * cannot render, a batch whose segments a re-baseline removed) settles instead of rebuilding
     * the whole changelog forever.</p>
     *
     * @return true when a batch was claimed, or when lock contention proves work remains and the
     *         caller should keep draining
     */
    public boolean finalizeNext() {
        settleExpiredClaims();
        List<Claim> claims = transactions.execute(status -> claimNext());
        if (claims == null) {
            return false;
        }
        if (claims.isEmpty()) {
            // Another worker has selected this batch but has not committed its short claim
            // transaction yet. Reporting an empty queue here would stop this worker before it can
            // select an unrelated batch after that commit releases the candidate row lock.
            return true;
        }
        // Scheduled inside the try: once the claim is committed the attempt is spent, so every path
        // out of here must reach publish(). A rejected schedule (the renewal executor is shutting
        // down) would otherwise abandon the row in BUILDING for a whole lease having done no work.
        ScheduledFuture<?> lease = null;
        try {
            Map<UUID, BuildOutcome> built;
            try {
                lease = renewLeaseWhileBuilding(claims);
                built = metrics.timeBatchParquetBuild(() -> buildAndUpload(claims));
            } catch (RuntimeException e) {
                String error = Objects.toString(e.getMessage(), e.getClass().getSimpleName());
                built = new LinkedHashMap<>();
                for (Claim claim : claims) {
                    built.put(claim.artifactId(), BuildOutcome.failed(error));
                }
            }
            for (Claim claim : claims) {
                BuildOutcome outcome = built.getOrDefault(claim.artifactId(),
                        BuildOutcome.failed("Build produced no outcome for claimed artifact"));
                try {
                    transactions.execute(status -> {
                        publish(claim, outcome);
                        return null;
                    });
                } catch (RuntimeException e) {
                    // A commit error is ambiguous: the row may already be READY with this attempt
                    // key, so eager deletion could break a valid manifest. Retention owns cleanup.
                    log.error("Could not publish unified batch Parquet outcome: batchId={}, table={}",
                            claim.batchId(), claim.tableName(), e);
                }
            }
            return true;
        } finally {
            // Publication is part of the live attempt: later siblings must not become reclaimable
            // while earlier sibling transactions are still settling.
            if (lease != null) {
                lease.cancel(false);
            }
        }
    }

    /**
     * Settle spent expired claims in their own short transaction. Catalog visibility for
     * {@code ABANDONED} uses {@code updated_at}, so the timestamp must be the DB watermark
     * taken under the same lock {@link #publish} uses.
     *
     * <p>The lock and the watermark are the expensive half of that guarantee — one cluster-wide
     * advisory lock and one write to a single-row table — and a dead claim is rare while this
     * runs on every drain iteration of every worker on every replica. So the predicate is read
     * first, outside any lock: only a non-empty answer is worth a settle transaction. Losing that
     * race (a peer settles the row in between) costs one wasted watermark tick, never a missed
     * settle — the row stays claimable until some transaction actually stamps it.</p>
     */
    private void settleExpiredClaims() {
        // Templated like every other step here, and for the same reason: nothing in this method may
        // join a caller's transaction. A native query that did would flush that caller's whole
        // persistence context and hold its connection for the rest of the drain.
        Boolean expired = transactions.execute(status -> artifactRepository.hasSpentExpiredClaims(
                LocalDateTime.now(ZoneOffset.UTC), leaseSeconds, maxAttempts));
        if (!Boolean.TRUE.equals(expired)) {
            return;
        }
        Integer abandoned = transactions.execute(status -> {
            artifactRepository.lockCatalogPublish();
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            LocalDateTime publishedAt = artifactRepository.nextCatalogWatermark();
            String expiredError = "Build lease expired without an outcome after the configured attempt limit";
            return artifactRepository.abandonExpiredClaims(
                    now, publishedAt, leaseSeconds, maxAttempts, expiredError);
        });
        int abandonedCount = abandoned == null ? 0 : abandoned;
        for (int index = 0; index < abandonedCount; index++) {
            metrics.batchParquetAbandoned();
        }
        if (abandonedCount > 0) {
            log.error("Abandoned {} unified batch Parquet claim(s) whose owners never returned",
                    abandonedCount);
        }
    }

    private List<Claim> claimNext() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<BatchParquetArtifact> next = artifactRepository.findNextRetryable(
                now, retryDelaySeconds, leaseSeconds, maxAttempts, 1);
        if (next.isEmpty()) {
            return null;
        }
        UUID batchId = next.get(0).getBatchId();
        if (!artifactRepository.tryLockBatch(batchId)) {
            return List.of();
        }
        List<BatchParquetArtifact> artifacts = artifactRepository.findRetryableByBatchId(
                batchId, now, retryDelaySeconds, leaseSeconds, maxAttempts);
        List<Claim> claims = new java.util.ArrayList<>(artifacts.size());
        for (BatchParquetArtifact artifact : artifacts) {
            if (artifact.getStatus() == BatchParquetArtifactStatus.BUILDING) {
                metrics.batchParquetReclaimed();
                log.warn("Reclaiming unified batch Parquet whose build lease expired: batchId={}, table={}",
                        artifact.getBatchId(), artifact.getTableName());
            }
            artifact.markBuilding();
            artifactRepository.save(artifact);
            claims.add(new Claim(artifact.getId(), artifact.getClaimToken(), artifact.getBatchId(),
                    artifact.getSiteId(), artifact.getTableName()));
        }
        return claims;
    }

    /**
     * Keep touching every sibling while the build runs, so the lease measures liveness instead of
     * capping how long a build may take. A large batch replayed per table can legitimately outrun
     * {@code lease-seconds}; without this it would be reclaimed mid-flight and rebuilt from scratch
     * by the next worker, spending an attempt and a pool slot on every lapse.
     *
     * <p>The batch advisory lock and every token touch share one transaction. This makes renewal
     * all-or-nothing relative to a competing batch claim: a claimant sees either every sibling's
     * old lease or every sibling's renewed lease, never a partially renewed batch.</p>
     */
    private ScheduledFuture<?> renewLeaseWhileBuilding(List<Claim> claims) {
        long period = Math.max(1L, leaseSeconds / 3L);
        return leaseRenewals.scheduleAtFixedRate(() -> {
            UUID batchId = claims.get(0).batchId();
            try {
                transactions.execute(status -> {
                    if (!artifactRepository.tryLockBatch(batchId)) {
                        log.warn("Could not acquire batch lock to renew Parquet build lease: batchId={}",
                                batchId);
                        return null;
                    }
                    LocalDateTime renewedAt = LocalDateTime.now(ZoneOffset.UTC);
                    for (Claim claim : claims) {
                        int renewed = artifactRepository.touchClaim(
                                claim.artifactId(), claim.token(), renewedAt);
                        if (renewed == 0) {
                            log.warn("Build lease was taken over while still building: batchId={}, table={}",
                                    claim.batchId(), claim.tableName());
                        }
                    }
                    return null;
                });
            } catch (RuntimeException e) {
                log.warn("Could not atomically renew the batch build lease: batchId={}", batchId, e);
            }
        }, period, period, TimeUnit.SECONDS);
    }

    /**
     * Record the attempt's outcome. The row is re-read because the build ran outside any
     * transaction: retention, a site wipe or a lease takeover may have moved on without us, and a
     * late writer must not clobber any of them.
     */
    private void publish(Claim claim, BuildOutcome outcome) {
        // Assign ready_at / updated_at only after the previous publisher committed. A wall-clock
        // stamp taken before commit can land earlier than a sibling that committed first, and a
        // client that advanced since= to that sibling would never see this row.
        artifactRepository.lockCatalogPublish();
        Optional<BatchParquetArtifact> found = artifactRepository.findById(claim.artifactId());
        if (found.isEmpty()) {
            log.warn("Unified batch Parquet finished for an artifact that no longer exists: batchId={}, table={}",
                    claim.batchId(), claim.tableName());
            if (outcome.file() != null) {
                // Retention or an admin delete removed the row while we were building — and it
                // collected keys from that row, which was BUILDING and named none. We are the last
                // reference to the object we just wrote, so it goes with us.
                storage.deleteBatchParquet(outcome.file().s3Key());
            }
            return;
        }
        BatchParquetArtifact artifact = found.get();
        if (!artifact.isHeldBy(claim.token())) {
            // Another worker reclaimed the row after our lease lapsed. Attempt keys are immutable,
            // so our upload cannot overwrite its object and is safe to remove without touching the
            // winner. If deletion fails, batch/site prefix cleanup can still discover it.
            log.warn("Unified batch Parquet finished under a claim that was taken over (status {}); "
                            + "discarding this attempt's outcome: batchId={}, table={}",
                    artifact.getStatus(), claim.batchId(), claim.tableName());
            if (outcome.file() != null) {
                storage.deleteBatchParquet(outcome.file().s3Key());
            }
            return;
        }
        if (outcome.file() != null) {
            FinalizedFile finalized = outcome.file();
            LocalDateTime publishedAt = artifactRepository.nextCatalogWatermark();
            artifact.markReady(finalized.s3Key(), finalized.rowCount(), finalized.fileSize(),
                    finalized.checksum(), finalized.firstSeq(), finalized.lastSeq(), publishedAt);
            metrics.batchParquetReady();
            log.info("Unified batch Parquet ready: batchId={}, table={}, rows={}, bytes={}",
                    claim.batchId(), claim.tableName(), finalized.rowCount(), finalized.fileSize());
        } else if (outcome.permanentFailure() || artifact.getAttemptCount() >= maxAttempts) {
            LocalDateTime publishedAt = artifactRepository.nextCatalogWatermark();
            artifact.markAbandoned(outcome.error(), outcome.firstSeq(), outcome.lastSeq(), publishedAt);
            metrics.batchParquetAbandoned();
            log.error("Unified batch Parquet abandoned after {} attempt(s): batchId={}, table={}, "
                            + "error={} — the table's download answers 404 until the row is reset",
                    artifact.getAttemptCount(), claim.batchId(), claim.tableName(), outcome.error());
        } else {
            artifact.markFailed(outcome.error());
            metrics.batchParquetFailed();
            log.warn("Unified batch Parquet failed (attempt {}/{}): batchId={}, table={}, error={}",
                    artifact.getAttemptCount(), maxAttempts, claim.batchId(), claim.tableName(),
                    outcome.error());
        }
        artifactRepository.save(artifact);
    }

    private Map<UUID, BuildOutcome> buildAndUpload(List<Claim> claims) {
        Claim firstClaim = claims.get(0);
        List<ChangelogSegment> segments = segmentRepository
                .findByBatchIdOrderByFirstSeq(firstClaim.batchId());
        if (segments.isEmpty()) {
            throw new IllegalStateException("Batch has no published changelog segments");
        }
        Map<String, TableSchema> schemas = schemaService.getTableSchemas(firstClaim.siteId());
        Map<UUID, BuildOutcome> outcomes = new LinkedHashMap<>();
        Map<String, Claim> claimsByTable = new LinkedHashMap<>();
        Map<String, Path> tempFiles = new LinkedHashMap<>();
        Map<String, DeltaParquetWriter.TableWriteRequest> requests = new LinkedHashMap<>();
        try {
            Files.createDirectories(tempDirectory);
            for (Claim claim : claims) {
                TableSchema schema = schemas.get(claim.tableName());
                if (schema == null) {
                    SeqRange range = seqRange(segments, claim.tableName());
                    outcomes.put(claim.artifactId(),
                            BuildOutcome.failed("No declared schema for table " + claim.tableName(),
                                    range.firstSeq(), range.lastSeq()));
                    continue;
                }
                Path output = Files.createTempFile(tempDirectory,
                        "batch-parquet-" + claim.artifactId() + "-", ".parquet");
                claimsByTable.put(claim.tableName(), claim);
                tempFiles.put(claim.tableName(), output);
                requests.put(claim.tableName(), new DeltaParquetWriter.TableWriteRequest(output, schema));
            }
            PhaseClock clock = new PhaseClock();
            DeltaParquetWriter.BatchWriteResult written;
            try {
                written = DeltaParquetWriter.writeBatchDeltaParquet(
                        requests,
                        consumer -> segments.forEach(segment ->
                                segmentService.forEachRecord(segment.getS3Key(), consumer, clock)),
                        maxTempBytes,
                        clock);
            } finally {
                if (clock.hasSamples()) {
                    metrics.recordBatchParquetPhase("download", clock.downloadNanos());
                    metrics.recordBatchParquetPhase("decode", clock.decodeNanos());
                    if (clock.decimalScanAttempted()) {
                        metrics.recordBatchParquetPhase("decimal_scan", clock.decimalScanNanos());
                    }
                    if (clock.writeAttempted()) {
                        metrics.recordBatchParquetPhase("write", clock.writeNanos());
                    }
                }
            }
            for (Map.Entry<String, DeltaParquetWriter.FileWriteFailure> entry
                    : written.failures().entrySet()) {
                Claim claim = claimsByTable.get(entry.getKey());
                DeltaParquetWriter.FileWriteFailure failure = entry.getValue();
                SeqRange range = seqRange(segments, claim.tableName());
                outcomes.put(claim.artifactId(), failure.permanent()
                        ? BuildOutcome.permanentlyFailed(failure.error(), range.firstSeq(), range.lastSeq())
                        : BuildOutcome.failed(failure.error(), range.firstSeq(), range.lastSeq()));
            }
            for (Map.Entry<String, DeltaParquetWriter.FileWriteResult> entry : written.files().entrySet()) {
                String tableName = entry.getKey();
                Claim claim = claimsByTable.get(tableName);
                DeltaParquetWriter.FileWriteResult result = entry.getValue();
                try {
                    OptionalLong expectedRows = expectedRowCount(segments, tableName);
                    if (expectedRows.isPresent() && result.rowCount() != expectedRows.getAsLong()) {
                        throw new IllegalStateException("Artifact row count " + result.rowCount()
                                + " does not match segment stats " + expectedRows.getAsLong());
                    }
                    String s3Key = metrics.timeBatchParquetPhase("upload", () ->
                            storage.uploadBatchParquet(claim.siteId(), claim.batchId(),
                                    tableName, claim.token(), tempFiles.get(tableName)));
                    SeqRange range = seqRange(segments, tableName);
                    outcomes.put(claim.artifactId(), BuildOutcome.succeeded(new FinalizedFile(
                            s3Key, result.rowCount(), result.fileSize(), result.checksum(),
                            range.firstSeq(), range.lastSeq())));
                } catch (RuntimeException e) {
                    SeqRange range = seqRange(segments, tableName);
                    outcomes.put(claim.artifactId(), BuildOutcome.failed(
                            Objects.toString(e.getMessage(), e.getClass().getSimpleName()),
                            range.firstSeq(), range.lastSeq()));
                }
            }
            return outcomes;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create unified Parquet temp file", e);
        } finally {
            for (Path tempFile : tempFiles.values()) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("Could not delete unified Parquet temp file {}", tempFile, e);
                }
            }
        }
    }

    private static Map<String, TableChangeStats> aggregateStats(List<ChangelogSegment> segments) {
        Map<String, TableChangeStats> totals = new TreeMap<>();
        segments.stream().map(ChangelogSegment::getStats).filter(Objects::nonNull)
                .flatMap(stats -> stats.entrySet().stream())
                .forEach(entry -> totals.merge(entry.getKey(), entry.getValue(), (left, right) ->
                        new TableChangeStats(left.inserts() + right.inserts(),
                                left.updates() + right.updates(), left.deletes() + right.deletes())));
        return totals;
    }

    private static OptionalLong expectedRowCount(List<ChangelogSegment> segments, String tableName) {
        if (segments.stream().anyMatch(segment -> segment.getStats() == null)) {
            return OptionalLong.empty();
        }
        long total = segments.stream()
                .map(ChangelogSegment::getStats)
                .map(stats -> stats.get(tableName))
                .filter(Objects::nonNull)
                .mapToLong(TableChangeStats::total)
                .sum();
        return OptionalLong.of(total);
    }

    @PreDestroy
    void shutdown() {
        leaseRenewals.shutdownNow();
    }

    /** The facts a build needs, plus the token identifying this attempt's claim. */
    private record Claim(UUID artifactId, UUID token, UUID batchId, UUID siteId, String tableName) {
    }

    private record FinalizedFile(String s3Key, long rowCount, long fileSize, String checksum,
                                 Long firstSeq, Long lastSeq) {
    }

    private record SeqRange(Long firstSeq, Long lastSeq) {
    }

    /**
     * Inclusive seq range of published segments that mention {@code tableName} in stats.
     * Falls back to the batch-wide published range when any segment has null stats (the
     * file still contains those records) or when no segment names the table.
     */
    static SeqRange seqRange(List<ChangelogSegment> segments, String tableName) {
        long tableFirst = Long.MAX_VALUE;
        long tableLast = Long.MIN_VALUE;
        long batchFirst = Long.MAX_VALUE;
        long batchLast = Long.MIN_VALUE;
        boolean anyNullStats = false;
        for (ChangelogSegment segment : segments) {
            batchFirst = Math.min(batchFirst, segment.getFirstSeq());
            batchLast = Math.max(batchLast, segment.getLastSeq());
            if (segment.getStats() == null) {
                anyNullStats = true;
                continue;
            }
            if (segment.getStats().containsKey(tableName)) {
                tableFirst = Math.min(tableFirst, segment.getFirstSeq());
                tableLast = Math.max(tableLast, segment.getLastSeq());
            }
        }
        if (!anyNullStats && tableFirst != Long.MAX_VALUE) {
            return new SeqRange(tableFirst, tableLast);
        }
        if (batchFirst != Long.MAX_VALUE) {
            return new SeqRange(batchFirst, batchLast);
        }
        return new SeqRange(null, null);
    }

    /** Either the finished file or the error that stopped this attempt. */
    private record BuildOutcome(FinalizedFile file, String error, boolean permanentFailure,
                                Long firstSeq, Long lastSeq) {
        static BuildOutcome succeeded(FinalizedFile file) {
            return new BuildOutcome(file, null, false, file.firstSeq(), file.lastSeq());
        }

        static BuildOutcome failed(String error) {
            return failed(error, null, null);
        }

        static BuildOutcome failed(String error, Long firstSeq, Long lastSeq) {
            return new BuildOutcome(null, error, false, firstSeq, lastSeq);
        }

        static BuildOutcome permanentlyFailed(String error) {
            return permanentlyFailed(error, null, null);
        }

        static BuildOutcome permanentlyFailed(String error, Long firstSeq, Long lastSeq) {
            return new BuildOutcome(null, error, true, firstSeq, lastSeq);
        }
    }
}
