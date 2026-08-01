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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Durable, idempotent finalization of one completed-batch Parquet per table (036, issue #93). */
@Service
public class BatchParquetFinalizationService {

    private static final Logger log = LoggerFactory.getLogger(BatchParquetFinalizationService.class);

    private final BatchParquetArtifactRepository artifactRepository;
    private final ChangelogSegmentRepository segmentRepository;
    private final ChangelogSegmentService segmentService;
    private final SiteSchemaService schemaService;
    private final BatchRepository batchRepository;
    private final S3CheckpointStorage storage;
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
     * Create the durable per-table work rows inside the batch-completion transaction. Existing rows
     * make replayed completion events harmless.
     */
    @Transactional
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
        Map<String, TableChangeStats> totals = aggregateStats(segments);
        int created = 0;
        for (String tableName : totals.keySet()) {
            Optional<BatchParquetArtifact> existing = artifactRepository
                    .findBySiteIdAndBatchIdAndTableName(siteId, batchId, tableName);
            if (existing.isEmpty()) {
                artifactRepository.save(BatchParquetArtifact.pending(batchId, siteId, tableName));
                created++;
            }
        }
        return created;
    }

    /**
     * Claim and finalize one row.
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
     * @return true when a row was claimed and its attempt recorded
     */
    public boolean finalizeNext() {
        Claim claim = transactions.execute(status -> claimNext());
        if (claim == null) {
            return false;
        }
        ScheduledFuture<?> lease = renewLeaseWhileBuilding(claim);
        BuildOutcome built;
        try {
            built = BuildOutcome.succeeded(buildAndUpload(claim));
        } catch (RuntimeException e) {
            built = BuildOutcome.failed(Objects.toString(e.getMessage(), e.getClass().getSimpleName()));
        } finally {
            lease.cancel(false);
        }
        BuildOutcome outcome = built;
        transactions.execute(status -> {
            publish(claim, outcome);
            return null;
        });
        return true;
    }

    /** Take one row and commit its {@code BUILDING} claim, so the attempt outlives this process. */
    private Claim claimNext() {
        List<BatchParquetArtifact> next = artifactRepository.findNextRetryable(
                LocalDateTime.now(ZoneOffset.UTC), retryDelaySeconds, leaseSeconds, 1);
        if (next.isEmpty()) {
            return null;
        }
        BatchParquetArtifact artifact = next.get(0);
        if (artifact.getStatus() == BatchParquetArtifactStatus.BUILDING) {
            log.warn("Reclaiming unified batch Parquet whose build lease expired: batchId={}, table={}",
                    artifact.getBatchId(), artifact.getTableName());
        }
        artifact.markBuilding();
        artifactRepository.save(artifact);
        return new Claim(artifact.getId(), artifact.getClaimToken(), artifact.getBatchId(),
                artifact.getSiteId(), artifact.getTableName());
    }

    /**
     * Keep touching the row while the build runs, so the lease measures liveness instead of
     * capping how long a build may take. A large batch replayed per table can legitimately outrun
     * {@code lease-seconds}; without this it would be reclaimed mid-flight and rebuilt from scratch
     * by the next worker, spending an attempt and a pool slot on every lapse.
     */
    private ScheduledFuture<?> renewLeaseWhileBuilding(Claim claim) {
        long period = Math.max(1L, leaseSeconds / 3L);
        return leaseRenewals.scheduleAtFixedRate(() -> {
            try {
                Integer renewed = transactions.execute(status -> artifactRepository.touchClaim(
                        claim.artifactId(), claim.token(), LocalDateTime.now(ZoneOffset.UTC)));
                if (renewed != null && renewed == 0) {
                    log.warn("Build lease was taken over while still building: batchId={}, table={}",
                            claim.batchId(), claim.tableName());
                }
            } catch (RuntimeException e) {
                log.warn("Could not renew the build lease for batchId={}, table={}",
                        claim.batchId(), claim.tableName(), e);
            }
        }, period, period, TimeUnit.SECONDS);
    }

    /**
     * Record the attempt's outcome. The row is re-read because the build ran outside any
     * transaction: retention, a site wipe or a lease takeover may have moved on without us, and a
     * late writer must not clobber any of them.
     */
    private void publish(Claim claim, BuildOutcome outcome) {
        Optional<BatchParquetArtifact> found = artifactRepository.findById(claim.artifactId());
        if (found.isEmpty()) {
            log.warn("Unified batch Parquet finished for an artifact that no longer exists: batchId={}, table={}",
                    claim.batchId(), claim.tableName());
            return;
        }
        BatchParquetArtifact artifact = found.get();
        if (!artifact.isHeldBy(claim.token())) {
            // Another worker reclaimed the row after our lease lapsed. Its attempt owns the outcome
            // now: writing ours here would either overwrite a live claim or bury a build that is
            // still running under a stale failure.
            log.warn("Unified batch Parquet finished under a claim that was taken over (status {}); "
                            + "discarding this attempt's outcome: batchId={}, table={}",
                    artifact.getStatus(), claim.batchId(), claim.tableName());
            return;
        }
        if (outcome.file() != null) {
            FinalizedFile finalized = outcome.file();
            artifact.markReady(finalized.s3Key(), finalized.rowCount(), finalized.fileSize(),
                    finalized.checksum());
            log.info("Unified batch Parquet ready: batchId={}, table={}, rows={}, bytes={}",
                    claim.batchId(), claim.tableName(), finalized.rowCount(), finalized.fileSize());
        } else if (artifact.getAttemptCount() >= maxAttempts) {
            artifact.markAbandoned(outcome.error());
            log.error("Unified batch Parquet abandoned after {} attempt(s): batchId={}, table={}, "
                            + "error={} — the table's download answers 404 until the row is reset",
                    artifact.getAttemptCount(), claim.batchId(), claim.tableName(), outcome.error());
        } else {
            artifact.markFailed(outcome.error());
            log.warn("Unified batch Parquet failed (attempt {}/{}): batchId={}, table={}, error={}",
                    artifact.getAttemptCount(), maxAttempts, claim.batchId(), claim.tableName(),
                    outcome.error());
        }
        artifactRepository.save(artifact);
    }

    private FinalizedFile buildAndUpload(Claim claim) {
        List<ChangelogSegment> segments = segmentRepository
                .findByBatchIdOrderByFirstSeq(claim.batchId());
        if (segments.isEmpty()) {
            throw new IllegalStateException("Batch has no published changelog segments");
        }
        TableSchema schema = schemaService.getTableSchemas(claim.siteId()).get(claim.tableName());
        if (schema == null) {
            throw new IllegalStateException("No declared schema for table " + claim.tableName());
        }

        Path tempFile = null;
        try {
            Files.createDirectories(tempDirectory);
            tempFile = Files.createTempFile(tempDirectory,
                    "batch-parquet-" + claim.artifactId() + "-", ".parquet");
            Path output = tempFile;
            DeltaParquetWriter.FileWriteResult result = DeltaParquetWriter.writeDeltaParquet(
                    output, claim.tableName(), schema,
                    consumer -> segments.forEach(segment ->
                            segmentService.forEachRecord(segment.getS3Key(), consumer)));
            long expectedRows = aggregateStats(segments).getOrDefault(
                    claim.tableName(), new TableChangeStats(0, 0, 0)).total();
            if (result.rowCount() != expectedRows) {
                throw new IllegalStateException("Artifact row count " + result.rowCount()
                        + " does not match segment stats " + expectedRows);
            }
            if (result.fileSize() > maxTempBytes) {
                throw new IllegalStateException("Artifact exceeds temp-file limit of " + maxTempBytes + " bytes");
            }
            String s3Key = storage.uploadBatchParquet(claim.siteId(), claim.batchId(),
                    claim.tableName(), output);
            return new FinalizedFile(s3Key, result.rowCount(), result.fileSize(), result.checksum());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create unified Parquet temp file", e);
        } finally {
            if (tempFile != null) {
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

    @PreDestroy
    void shutdown() {
        leaseRenewals.shutdownNow();
    }

    /** The facts a build needs, plus the token identifying this attempt's claim. */
    private record Claim(UUID artifactId, UUID token, UUID batchId, UUID siteId, String tableName) {
    }

    private record FinalizedFile(String s3Key, long rowCount, long fileSize, String checksum) {
    }

    /** Either the finished file or the error that stopped this attempt. */
    private record BuildOutcome(FinalizedFile file, String error) {
        static BuildOutcome succeeded(FinalizedFile file) {
            return new BuildOutcome(file, null);
        }

        static BuildOutcome failed(String error) {
            return new BuildOutcome(null, error);
        }
    }
}
