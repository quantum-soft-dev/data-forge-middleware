package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.*;
import com.bitbi.dfm.plugin.infrastructure.storage.S3SqlFileStorageService;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.SiteType;
import com.bitbi.dfm.site.domain.TableSchema;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.PostConstruct;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Service orchestrating SQL file generation from batch data.
 *
 * <p>Dispatches to the appropriate {@link SqlGenerationStrategy} based on site type:</p>
 * <ul>
 *   <li>{@link DbfSqlGenerationStrategy} for {@code DBF} sites — CSV snapshot diff</li>
 *   <li>{@link CdcSqlGenerationStrategy} for {@code POSTGRES_CDC} sites — JSONL delta conversion</li>
 * </ul>
 *
 * <p>Performance considerations:</p>
 * <ul>
 *   <li>Uses JOIN FETCH to prevent N+1 queries when loading batch files</li>
 *   <li>Limits batch size to prevent memory issues</li>
 *   <li>S3 operations run outside transactions to avoid connection starvation</li>
 * </ul>
 */
@Service
public class SqlGenerationService {

    private static final Logger log = LoggerFactory.getLogger(SqlGenerationService.class);

    /**
     * Maximum number of files allowed per batch for SQL generation.
     * Prevents memory exhaustion from processing too many files.
     */
    private static final String PLUGIN_ID = "bit-bi";

    private final AccountPluginRepository accountPluginRepository;
    private final SqlGenerationPersistence persistence;
    private final S3SqlFileStorageService s3SqlFileStorageService;
    private final MeterRegistry meterRegistry;
    private final PluginAuditService pluginAuditService;
    private final DbfSqlGenerationStrategy dbfStrategy;
    private final CdcSqlGenerationStrategy cdcStrategy;
    private final SiteSchemaService siteSchemaService;
    private final DeltaSqlGenerationStrategy deltaStrategy;
    private final PluginDeltaBaselineRepository pluginDeltaBaselineRepository;
    private final int maxConcurrent;
    private final int semaphoreTimeoutSeconds;
    private final int heapThresholdPercent;

    private Semaphore sqlGenerationSemaphore;

    public SqlGenerationService(
            AccountPluginRepository accountPluginRepository,
            SqlGenerationPersistence persistence,
            S3SqlFileStorageService s3SqlFileStorageService,
            MeterRegistry meterRegistry,
            PluginAuditService pluginAuditService,
            DbfSqlGenerationStrategy dbfStrategy,
            CdcSqlGenerationStrategy cdcStrategy,
            SiteSchemaService siteSchemaService,
            DeltaSqlGenerationStrategy deltaStrategy,
            PluginDeltaBaselineRepository pluginDeltaBaselineRepository,
            @Value("${plugin.sql-generation.max-concurrent:2}") int maxConcurrent,
            @Value("${plugin.sql-generation.semaphore-timeout-seconds:120}") int semaphoreTimeoutSeconds,
            @Value("${plugin.sql-generation.heap-threshold-percent:80}") int heapThresholdPercent) {
        this.accountPluginRepository = accountPluginRepository;
        this.persistence = persistence;
        this.s3SqlFileStorageService = s3SqlFileStorageService;
        this.meterRegistry = meterRegistry;
        this.pluginAuditService = pluginAuditService;
        this.dbfStrategy = dbfStrategy;
        this.cdcStrategy = cdcStrategy;
        this.siteSchemaService = siteSchemaService;
        this.deltaStrategy = deltaStrategy;
        this.pluginDeltaBaselineRepository = pluginDeltaBaselineRepository;
        this.maxConcurrent = maxConcurrent;
        this.semaphoreTimeoutSeconds = semaphoreTimeoutSeconds;
        this.heapThresholdPercent = heapThresholdPercent;
    }

    /**
     * Initializes the concurrency semaphore and registers metrics.
     * Package-private for testability. The double-initialization guard protects
     * against test scenarios that call {@code init()} manually after construction
     * (Spring's {@code @PostConstruct} itself is only invoked once per bean).
     */
    @PostConstruct
    void init() {
        if (this.sqlGenerationSemaphore != null) {
            log.warn("init() called more than once — skipping re-initialization");
            return;
        }
        this.sqlGenerationSemaphore = new Semaphore(maxConcurrent, true);
        meterRegistry.gauge("sql.generation.semaphore.queue.size", sqlGenerationSemaphore,
                Semaphore::getQueueLength);
        // Registered at zero from startup, the DeltaMetrics treatment of
        // delta.checkpoint.builds.aborted: an alert written on it must be able to predate the
        // first occurrence instead of appearing together with it. Since #181 it counts
        // *refusals*, not batches lost: the refused batch is retried, so one batch under a long
        // pressure episode increments this once per queue wake. Read it as a rate ("the pod is
        // refusing work") and the SQL_GENERATION_FAILED audit entries for which batches.
        meterRegistry.counter("sql.generation.aborted.memory_pressure");
        log.info("SQL generation semaphore initialized: maxConcurrent={}, timeoutSeconds={}, "
                        + "heapThresholdPercent={} ({})",
                maxConcurrent, semaphoreTimeoutSeconds, heapThresholdPercent,
                heapThresholdPercent >= 100 ? "memory-pressure abort disabled" : "aborts above threshold");
    }

    /**
     * Generates SQL file from batch comparison.
     * Called when a batch completes for an account with active Bit BI plugin.
     * <p>
     * Transaction strategy:
     * <ul>
     *   <li>Phase 1: Load data (read-only transaction)</li>
     *   <li>Phase 2: S3 operations (outside transaction to avoid connection starvation)</li>
     *   <li>Phase 3: Save result (separate write transaction)</li>
     * </ul>
     * </p>
     *
     * @param batchId The completed batch ID
     * @param accountPluginId The ID of the active account plugin
     * @return Optional containing the generation record, or empty if no changes
     */
    public Optional<PluginSqlGeneration> generateSqlForBatch(UUID batchId, Long accountPluginId) {
        return generateSqlForBatch(batchId, accountPluginId, false);
    }

    /**
     * Generates SQL file from batch comparison with optional full generation mode.
     * <p>
     * Baseline batch handling:
     * <ul>
     *   <li>If batchId equals account_plugin.baseline_batch_id - skips generation (use CSV files)</li>
     *   <li>If baseline_batch_id is null - this batch becomes the baseline, sets it and skips generation</li>
     *   <li>Otherwise - generates SQL delta compared to previous batch</li>
     * </ul>
     * </p>
     * <p>
     * When forceFullGeneration is true, skips previous batch comparison and generates
     * INSERT statements for all data. Used by reinit and plugin activation.
     * </p>
     *
     * @param batchId The completed batch ID
     * @param accountPluginId The ID of the active account plugin
     * @param forceFullGeneration If true, skips previous batch comparison (generates all INSERTs)
     * @return Optional containing the generation record, or empty if baseline batch (use CSV) or no changes
     */
    public Optional<PluginSqlGeneration> generateSqlForBatch(UUID batchId, Long accountPluginId, boolean forceFullGeneration) {
        refuseIfTransactionActive();
        acquireSemaphore(batchId);
        try {
            return doGenerateSqlForBatch(batchId, accountPluginId, forceFullGeneration);
        } finally {
            sqlGenerationSemaphore.release();
        }
    }

    private Optional<PluginSqlGeneration> doGenerateSqlForBatch(UUID batchId, Long accountPluginId, boolean forceFullGeneration) {
        Timer.Sample timer = Timer.start(meterRegistry);
        String s3Key = null;
        SqlGenerationPersistence.BatchData batchData = null;
        long startTimeMs = System.currentTimeMillis();

        try {
            // Check if this is a baseline batch (should use CSV files, not SQL generation)
            AccountPlugin accountPlugin = accountPluginRepository.findById(accountPluginId)
                    .orElseThrow(() -> new IllegalArgumentException("AccountPlugin not found: " + accountPluginId));

            // Segment-backed batches use per-table seq baselines (plugin_delta_baselines, 026)
            // captured at activation/reinit. Historical file-backed batches keep their original
            // batch-level baseline semantics without depending on a retired client API version.
            boolean segmentBacked = persistence.existsByBatchId(batchId);

            // Case 1: This batch is the baseline - skip SQL generation
            if (!segmentBacked && accountPlugin.isBaselineBatch(batchId)) {
                log.info("Skipping SQL generation for baseline batch {}. " +
                        "Client should download CSV files via /sites/{{siteId}}/files endpoint.",
                        batchId);
                return Optional.empty();
            }

            // Case 2: No baseline set - this is the first batch, make it baseline
            if (!segmentBacked && !accountPlugin.hasBaselineBatch()) {
                log.info("No baseline batch set. Setting batch {} as baseline. " +
                        "Client should download CSV files via /sites/{{siteId}}/files endpoint.",
                        batchId);
                accountPlugin.setBaselineBatchId(batchId);
                accountPluginRepository.save(accountPlugin);
                return Optional.empty();
            }

            // Case 3: Regular batch - generate SQL delta
            // Phase 1: Load all required data (uses JOIN FETCH to prevent N+1)
            batchData = persistence.loadBatchData(batchId, forceFullGeneration);
            if (batchData == null) {
                return Optional.empty();
            }

            // Set MDC for structured logging
            MDC.put("batchId", batchId.toString());
            MDC.put("siteId", batchData.batch().getSiteId().toString());
            MDC.put("accountId", batchData.batch().getAccountId().toString());

            // Before anything is announced: a refused attempt must not leave a
            // SQL_GENERATION_STARTED entry for a generation that never started. Under a pressure
            // episode this path is re-entered once per queue wake — which is once per completed
            // batch across the fleet, not once per sweep tick — so a pair of audit rows per
            // attempt would bury the account's log in the very entries #181 added to be read.
            refuseUnderMemoryPressure(batchId);

            log.info("Starting SQL generation for batch: batchId={}, siteType={}",
                    batchId, batchData.site().getSiteType());

            // Audit: Log SQL generation started
            pluginAuditService.logSqlGenerationStarted(
                    PLUGIN_ID,
                    batchData.batch().getAccountId(),
                    batchId,
                    batchData.batch().getSiteId()
            );

            // Phase 2: Generate SQL content (S3 reads outside transaction)
            SqlGenerationResult result = generateSqlContent(batchData);
            if (result == null) {
                long durationMs = System.currentTimeMillis() - startTimeMs;
                log.info("No changes detected between batches, skipping SQL file creation: batchId={}, duration={}ms",
                        batchId, durationMs);

                // Audit: Log SQL generation completed with zero changes
                pluginAuditService.logSqlGenerationCompletedNoChanges(
                        PLUGIN_ID,
                        batchData.batch().getAccountId(),
                        batchId,
                        batchData.batch().getSiteId(),
                        durationMs
                );

                return Optional.empty();
            }

            // Phase 2b: Store SQL file in S3 (outside transaction)
            s3Key = s3SqlFileStorageService.storeSqlFile(
                    batchData.batch().getAccountId(),
                    batchData.site().getId(),
                    result.sqlContent()
            );
            long fileSize = s3SqlFileStorageService.getFileSize(s3Key);

            // Phase 3: Save generation record (separate transaction). Two workers can now
            // race here (#164 dropped the queue TX that used to serialize them); the unique
            // on source_batch_id is the durable claim.
            long durationMs = timer.stop(meterRegistry.timer("sql.generation.duration"));
            PluginSqlGeneration generation = persistOrAdoptExisting(
                    accountPluginId, batchData, s3Key, fileSize, result.stats(), durationMs);

            log.info("SQL generation completed: batchId={}, statements={}, duration={}ms",
                    batchId, result.stats().total(), durationMs / 1_000_000);

            // Audit: Log SQL generation completed
            pluginAuditService.logSqlGenerationCompleted(
                    PLUGIN_ID,
                    batchData.batch().getAccountId(),
                    batchId,
                    batchData.batch().getSiteId(),
                    result.stats(),
                    s3Key,
                    durationMs / 1_000_000  // Convert nanos to ms
            );

            // Record metrics
            meterRegistry.counter("sql.generation.statements.inserts").increment(result.stats().inserts());
            meterRegistry.counter("sql.generation.statements.updates").increment(result.stats().updates());
            meterRegistry.counter("sql.generation.statements.deletes").increment(result.stats().deletes());

            return Optional.of(generation);

        } catch (IOException e) {
            log.error("SQL generation failed for batch (I/O error): batchId={}", batchId, e);
            meterRegistry.counter("sql.generation.errors").increment();
            cleanupOrphanedS3File(s3Key);

            // Audit: Log SQL generation failed (if we have batch data)
            if (batchData != null) {
                long durationMs = System.currentTimeMillis() - startTimeMs;
                pluginAuditService.logSqlGenerationFailed(
                        PLUGIN_ID,
                        batchData.batch().getAccountId(),
                        batchId,
                        batchData.batch().getSiteId(),
                        "I/O error: " + e.getMessage(),
                        durationMs
                );
            }

            throw new SqlGenerationException("Failed to read files for SQL generation", e);
        } catch (RuntimeException e) {
            if (!(e instanceof MemoryPressureAbortedException)) {
                // A refusal is already logged with its reading at the raise site and counted on
                // sql.generation.aborted.memory_pressure. It is deliberately kept off
                // sql.generation.errors, which is for generations that are broken: this one
                // repairs itself when the heap does, and an alert on that series must not be
                // spent on a condition that clears (the rule #162 applied to
                // delta.checkpoint.builds.aborted). It still audits, below.
                log.error("SQL generation failed for batch: batchId={}", batchId, e);
                meterRegistry.counter("sql.generation.errors").increment();
            }
            cleanupOrphanedS3File(s3Key);

            // Audit: Log SQL generation failed (if we have batch data)
            if (batchData != null) {
                long durationMs = System.currentTimeMillis() - startTimeMs;
                pluginAuditService.logSqlGenerationFailed(
                        PLUGIN_ID,
                        batchData.batch().getAccountId(),
                        batchId,
                        batchData.batch().getSiteId(),
                        e.getMessage(),
                        durationMs
                );
            }

            throw e;
        } finally {
            MDC.clear();
        }
    }

    /**
     * Asynchronously generates SQL for a batch during reinit operation.
     * <p>
     * This method runs in a separate thread to avoid blocking the HTTP request.
     * The reinit endpoint returns immediately (202 Accepted) while SQL generation
     * continues in the background.
     * </p>
     * <p>
     * Error handling: If SQL generation fails, it's logged but does NOT fail the
     * reinit operation (which has already completed and returned).
     * </p>
     *
     * @param batchId The batch ID to generate SQL from
     * @param accountPluginId The account plugin ID
     * @param accountId The account ID (for logging)
     */
    @Async("pluginExecutor")
    public void generateSqlForBatchAsync(UUID batchId, Long accountPluginId, UUID accountId) {
        log.info("Starting async SQL generation for reinit: batchId={}, accountId={}", batchId, accountId);
        try {
            // forceFullGeneration=true: generate all INSERTs since history was cleared
            generateSqlForBatch(batchId, accountPluginId, true);
            log.info("Async SQL generation completed successfully: batchId={}, accountId={}", batchId, accountId);
        } catch (Exception e) {
            // Log error but don't propagate - reinit has already returned successfully
            log.error("Async SQL generation failed for reinit: batchId={}, accountId={}, error={}",
                    batchId, accountId, e.getMessage(), e);
        }
    }

    /**
     * Pre-flight heap check: refuses the batch outright when the pod is above
     * {@code plugin.sql-generation.heap-threshold-percent}, before any S3 read and before the
     * attempt is announced.
     *
     * <p>It <em>throws</em> rather than returning "no changes" (issue #181): a null result is the
     * answer for an empty diff, and no caller could tell the two apart — the delta-SQL queue
     * marked the segment processed and dropped the batch's SQL for good (and the regeneration
     * path, retired by #190, wrote an empty artifact over a good one). Throwing puts the refusal
     * on the failure path every caller already handles: the segment stays pending for the sweep,
     * and the audit entry names the batch.</p>
     *
     * <p>The reading is taken once, so the value logged is the value that tripped the check. The
     * numbers stay in this log line and deliberately not in the exception message, which reaches
     * the owner endpoint's response body and the account-visible audit entry, where a tenant can
     * act on neither the pod's heap nor the name of a server configuration key.</p>
     *
     * @param batchId the batch being refused, for the log line and the exception message
     * @throws MemoryPressureAbortedException if the heap is strictly above the threshold
     */
    private void refuseUnderMemoryPressure(UUID batchId) {
        int heapUsagePercent = getHeapUsagePercent();
        if (isMemoryPressureHigh(heapUsagePercent)) {
            // WARN and not ERROR, and logged only here: a refusal that clears when the heap does
            // must not read like a broken generation on an ERROR-rate alert, which is the same
            // reason it is kept off sql.generation.errors. The catch below deliberately adds no
            // second line for this type.
            log.warn("High memory pressure ({}% of heap, threshold {}%), refusing SQL generation "
                            + "for batch: {}", heapUsagePercent, heapThresholdPercent, batchId);
            meterRegistry.counter("sql.generation.aborted.memory_pressure").increment();
            throw new MemoryPressureAbortedException(batchId);
        }
    }

    /**
     * Phase 2: Generates SQL content by dispatching to the appropriate strategy.
     * Runs outside transaction to avoid connection starvation during S3 I/O.
     *
     * @return the generated SQL, or {@code null} when the batch genuinely produced no changes
     */
    private SqlGenerationResult generateSqlContent(SqlGenerationPersistence.BatchData data) throws IOException {

        // Segment-sourced generation with per-table baseline filtering (026).
        if (!data.segments().isEmpty()) {
            return deltaStrategy.generate(
                    data.batch().getId(),
                    data.site().getId(),
                    data.segments(),
                    siteSchemaService.getTableSchemas(data.site().getId()),
                    pluginDeltaBaselineRepository.baselineSeqsBySiteId(data.site().getId())
            );
        }

        Map<String, TableSchema> tableSchemas = Map.of();
        if (data.site().getSiteType() == SiteType.POSTGRES_CDC) {
            tableSchemas = siteSchemaService.getTableSchemas(data.site().getId());
        }

        SqlGenerationContext context = new SqlGenerationContext(
                data.batch().getId(),
                data.site().getId(),
                data.relevantFiles(),
                data.previousFilesMap(),
                tableSchemas
        );

        SqlGenerationStrategy strategy = (data.site().getSiteType() == SiteType.POSTGRES_CDC)
                ? cdcStrategy
                : dbfStrategy;

        return strategy.generate(context);
    }

    /**
     * Cleans up orphaned S3 file if database save fails.
     */
    private void cleanupOrphanedS3File(String s3Key) {
        if (s3Key != null) {
            try {
                s3SqlFileStorageService.deleteFile(s3Key);
                log.info("Cleaned up orphaned S3 file: {}", s3Key);
            } catch (Exception e) {
                log.error("Failed to cleanup orphaned S3 file: {}. Manual cleanup required.", s3Key, e);
                meterRegistry.counter("sql.generation.orphaned.files").increment();
            }
        }
    }

    /**
     * Checks whether JVM heap usage <em>exceeds</em> the configured threshold.
     * <p>
     * The comparison is strict on purpose. {@link #getHeapUsagePercent()} rounds up, and for an
     * integer threshold {@code T} that makes {@code ceil(x) > T} equivalent to {@code x > T} —
     * so the predicate is exactly "used/max is strictly above {@code T}%", with no rounding
     * artifact in either direction. It also gives the key a way to say "disabled":
     * {@link #getHeapUsagePercent()} never reports above 100, so {@code heap-threshold-percent: 100}
     * switches the abort off. A non-strict comparison made 100 trip for any usage above 99%
     * (issue #174), which is why the three call sites that set 100 "to disable the check" did not —
     * {@code application-test.yml} above all, which put the abort into every Spring integration test
     * that generates SQL.
     *
     * @param heapUsagePercent the reading to judge, as produced by {@link #getHeapUsagePercent()}
     * @return true if heap usage is strictly above the threshold percentage
     */
    private boolean isMemoryPressureHigh(int heapUsagePercent) {
        return heapUsagePercent > heapThresholdPercent;
    }

    /**
     * Returns current JVM heap usage as a percentage (0-100).
     * Uses {@link MemoryMXBean} instead of {@link Runtime} for a more accurate
     * post-GC view of heap usage (accounts for unreachable but uncollected objects).
     * Uses ceiling division so that any non-zero usage above a whole percent is visible to the
     * strict comparison in {@link #isMemoryPressureHigh(int)}.
     * Package-private for testing.
     */
    int getHeapUsagePercent() {
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memBean.getHeapMemoryUsage();
        return heapUsagePercent(heap.getUsed(), heap.getMax());
    }

    /**
     * Converts a heap reading into a whole percentage in {@code [0, 100]}.
     * <p>
     * Rounded up, so that for an integer threshold {@code T} the strict comparison in
     * {@link #isMemoryPressureHigh(int)} trips exactly when {@code used/max} is above {@code T}%.
     * Clamped at 100, so that "a threshold of 100 disables the check" is a property of this
     * arithmetic and not an assumption about the collector: a reading above 100 would abort while
     * startup had logged the check as disabled. A non-positive {@code max} means the JVM declares
     * no heap maximum, and there is no percentage to report.
     * Package-private for testing.
     *
     * @param used bytes of heap in use
     * @param max  heap maximum in bytes, or a non-positive value when undefined
     * @return heap usage as a whole percentage, 0 when the maximum is undefined
     */
    int heapUsagePercent(long used, long max) {
        if (max <= 0) {
            return 0;
        }
        return (int) Math.min(100L, (long) Math.ceil(used * 100.0 / max));
    }

    /**
     * Persist the generation, or adopt the row another worker already wrote for this batch.
     * The unique on {@code source_batch_id} is the durable claim now that the queue no longer
     * holds {@code SKIP LOCKED} across S3 (issue #164).
     */
    private PluginSqlGeneration persistOrAdoptExisting(
            Long accountPluginId,
            SqlGenerationPersistence.BatchData batchData,
            String s3Key,
            long fileSize,
            SqlGenerationStats stats,
            long durationNanos) {
        try {
            return persistence.saveGenerationRecord(
                    accountPluginId, batchData, s3Key, fileSize, stats, durationNanos);
        } catch (DataIntegrityViolationException e) {
            cleanupOrphanedS3File(s3Key);
            return persistence.findBySourceBatchId(batchData.batch().getId()).orElseThrow(() -> e);
        }
    }

    private static void refuseIfTransactionActive() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Refusing to generate SQL inside an active transaction: the semaphore wait "
                            + "and S3 I/O would hold that transaction's connection (issue #164). "
                            + "Acquire the semaphore first, then open a short transaction only "
                            + "around the database work.");
        }
    }

    /**
     * Acquires the SQL generation semaphore with the configured timeout.
     * Throws SqlGenerationException if the semaphore cannot be acquired in time.
     *
     * @param batchId The batch ID (for logging context)
     */
    private void acquireSemaphore(UUID batchId) {
        try {
            boolean acquired = sqlGenerationSemaphore.tryAcquire(semaphoreTimeoutSeconds, TimeUnit.SECONDS);
            if (acquired) {
                meterRegistry.counter("sql.generation.semaphore.acquired").increment();
            }
            if (!acquired) {
                log.warn("SQL generation semaphore acquisition timed out after {}s: batchId={}, queueLength={}",
                        semaphoreTimeoutSeconds, batchId, sqlGenerationSemaphore.getQueueLength());
                meterRegistry.counter("sql.generation.semaphore.timeouts").increment();
                throw new SqlGenerationException(
                        "SQL generation timed out waiting for available slot after " + semaphoreTimeoutSeconds +
                        "s. Current queue length: " + sqlGenerationSemaphore.getQueueLength());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SqlGenerationException("SQL generation interrupted while waiting for semaphore", e);
        }
    }

    /**
     * Exception thrown when SQL generation fails.
     */
    public static class SqlGenerationException extends RuntimeException {
        public SqlGenerationException(String message) {
            super(message);
        }

        public SqlGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Thrown when a generation is refused because the pod's heap is above
     * {@code plugin.sql-generation.heap-threshold-percent} (issue #181).
     *
     * <p>It is a failure and not an outcome: the batch's records are untouched and the same batch
     * will generate normally once the heap recovers, which is why the delta-SQL queue is expected
     * to leave the segment pending and offer it again on the next sweep. The condition is a
     * property of the <em>pod at that instant</em>, not of the batch, so retrying is not a spin —
     * unlike the deterministic Parquet size ceilings, whose refusal repeats for ever.</p>
     *
     * <p>A subclass of {@link SqlGenerationException} so it lands on the failure paths that
     * already exist: a {@code SQL_GENERATION_FAILED} audit entry naming the batch, and a 500 from
     * the two manual generation endpoints rather than a 200 reporting "no changes detected". It is
     * kept off {@code sql.generation.errors} — it has a counter of its own and it repairs
     * itself.</p>
     *
     * <p>The message is written for the reader it reaches: the owner endpoint copies it into a 500
     * body and the audit entry into an account-visible {@code errorMessage}, so it names neither
     * the pod's heap usage nor the configuration key. Both are in the ERROR logged where the
     * refusal is raised.</p>
     */
    public static class MemoryPressureAbortedException extends SqlGenerationException {

        /**
         * @param batchId the batch that was refused; it is the only detail the message carries,
         *                since the message reaches a tenant on two surfaces
         */
        public MemoryPressureAbortedException(UUID batchId) {
            // No promise of a retry: it is true for a segment claimed by the delta-SQL queue and
            // false for a manual generation, and this type cannot tell which caller raised it.
            super("Generating SQL for batch " + batchId + " was refused because the server is under "
                    + "memory pressure. No SQL was produced for this batch and its records are intact.");
        }
    }
}
