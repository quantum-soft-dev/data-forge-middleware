package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.plugin.domain.*;
import com.bitbi.dfm.plugin.infrastructure.storage.S3SqlFileStorageService;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.site.domain.SiteType;
import com.bitbi.dfm.site.domain.TableSchema;
import com.bitbi.dfm.upload.domain.UploadedFile;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

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
    private static final int MAX_FILES_PER_BATCH = 100;

    private static final String PLUGIN_ID = "bit-bi";

    private final BatchRepository batchRepository;
    private final SiteRepository siteRepository;
    private final AccountPluginRepository accountPluginRepository;
    private final PluginSqlGenerationRepository sqlGenerationRepository;
    private final S3SqlFileStorageService s3SqlFileStorageService;
    private final MeterRegistry meterRegistry;
    private final PluginAuditService pluginAuditService;
    private final DbfSqlGenerationStrategy dbfStrategy;
    private final CdcSqlGenerationStrategy cdcStrategy;
    private final SiteSchemaService siteSchemaService;

    public SqlGenerationService(
            BatchRepository batchRepository,
            SiteRepository siteRepository,
            AccountPluginRepository accountPluginRepository,
            PluginSqlGenerationRepository sqlGenerationRepository,
            S3SqlFileStorageService s3SqlFileStorageService,
            MeterRegistry meterRegistry,
            PluginAuditService pluginAuditService,
            DbfSqlGenerationStrategy dbfStrategy,
            CdcSqlGenerationStrategy cdcStrategy,
            SiteSchemaService siteSchemaService) {
        this.batchRepository = batchRepository;
        this.siteRepository = siteRepository;
        this.accountPluginRepository = accountPluginRepository;
        this.sqlGenerationRepository = sqlGenerationRepository;
        this.s3SqlFileStorageService = s3SqlFileStorageService;
        this.meterRegistry = meterRegistry;
        this.pluginAuditService = pluginAuditService;
        this.dbfStrategy = dbfStrategy;
        this.cdcStrategy = cdcStrategy;
        this.siteSchemaService = siteSchemaService;
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
        Timer.Sample timer = Timer.start(meterRegistry);
        String s3Key = null;
        BatchData batchData = null;
        long startTimeMs = System.currentTimeMillis();

        try {
            // Check if this is a baseline batch (should use CSV files, not SQL generation)
            AccountPlugin accountPlugin = accountPluginRepository.findById(accountPluginId)
                    .orElseThrow(() -> new IllegalArgumentException("AccountPlugin not found: " + accountPluginId));

            // Case 1: This batch is the baseline - skip SQL generation
            if (accountPlugin.isBaselineBatch(batchId)) {
                log.info("Skipping SQL generation for baseline batch {}. " +
                        "Client should download CSV files via /sites/{{siteId}}/files endpoint.",
                        batchId);
                return Optional.empty();
            }

            // Case 2: No baseline set - this is the first batch, make it baseline
            if (!accountPlugin.hasBaselineBatch()) {
                log.info("No baseline batch set. Setting batch {} as baseline. " +
                        "Client should download CSV files via /sites/{{siteId}}/files endpoint.",
                        batchId);
                accountPlugin.setBaselineBatchId(batchId);
                accountPluginRepository.save(accountPlugin);
                return Optional.empty();
            }

            // Case 3: Regular batch - generate SQL delta
            // Phase 1: Load all required data (uses JOIN FETCH to prevent N+1)
            batchData = loadBatchData(batchId, forceFullGeneration);
            if (batchData == null) {
                return Optional.empty();
            }

            // Set MDC for structured logging
            MDC.put("batchId", batchId.toString());
            MDC.put("siteId", batchData.batch().getSiteId().toString());
            MDC.put("accountId", batchData.batch().getAccountId().toString());

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

            // Phase 3: Save generation record (separate transaction)
            long durationMs = timer.stop(meterRegistry.timer("sql.generation.duration"));
            PluginSqlGeneration generation = saveGenerationRecord(
                    accountPluginId,
                    batchData,
                    s3Key,
                    fileSize,
                    result.stats(),
                    durationMs
            );

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
            log.error("SQL generation failed for batch: batchId={}", batchId, e);
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
     * Phase 1: Loads all required batch data using JOIN FETCH.
     * Returns null if generation should be skipped.
     * Site-type-aware: filters CSV files for DBF, JSONL files for POSTGRES_CDC.
     *
     * @param batchId The batch ID to load
     * @param forceFullGeneration If true, skips previous batch lookup (generates all INSERTs; DBF only)
     */
    @Transactional(readOnly = true)
    protected BatchData loadBatchData(UUID batchId, boolean forceFullGeneration) {
        // Get batch with files using JOIN FETCH (prevents N+1)
        Batch batch = batchRepository.findByIdWithFiles(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));

        // Check if already generated
        if (sqlGenerationRepository.existsBySourceBatchId(batchId)) {
            log.warn("SQL already generated for batch: batchId={}", batchId);
            return null;
        }

        // Get site for name and type
        Site site = siteRepository.findById(batch.getSiteId())
                .orElseThrow(() -> new IllegalArgumentException("Site not found: " + batch.getSiteId()));

        List<UploadedFile> currentFiles = batch.getUploadedFiles();
        if (currentFiles.isEmpty()) {
            log.warn("No files in batch, skipping SQL generation: batchId={}", batchId);
            return null;
        }

        boolean isCdc = site.getSiteType() == SiteType.POSTGRES_CDC;
        List<UploadedFile> relevantFiles = filterRelevantFiles(currentFiles, isCdc);

        if (relevantFiles.isEmpty()) {
            log.warn("No {} files in batch, skipping SQL generation: batchId={}",
                    isCdc ? "JSONL" : "CSV", batchId);
            return null;
        }

        if (relevantFiles.size() > MAX_FILES_PER_BATCH) {
            log.error("Too many files in batch: count={}, max={}, batchId={}",
                    relevantFiles.size(), MAX_FILES_PER_BATCH, batchId);
            throw new IllegalArgumentException(
                    "Batch contains " + relevantFiles.size() + " files, exceeding limit of " + MAX_FILES_PER_BATCH);
        }

        // For CDC sites: no previous batch comparison (deltas are self-contained)
        // For DBF sites: load previous batch for diff comparison
        Optional<Batch> previousBatchOpt;
        Map<String, UploadedFile> previousFilesMap = new HashMap<>();

        if (isCdc) {
            previousBatchOpt = Optional.empty();
        } else if (forceFullGeneration) {
            log.info("Force full generation enabled - skipping previous batch comparison (will generate all INSERTs)");
            previousBatchOpt = Optional.empty();
        } else {
            previousBatchOpt = batchRepository
                    .findPreviousBatchForSiteWithFiles(batch.getSiteId(), batchId);
            if (previousBatchOpt.isPresent()) {
                for (UploadedFile file : previousBatchOpt.get().getUploadedFiles()) {
                    previousFilesMap.put(normalizeFileName(file.getOriginalFileName()), file);
                }
            }
        }

        log.debug("Previous batch for comparison: {}",
                previousBatchOpt.map(b -> b.getId().toString()).orElse("none (first batch, force full, or CDC)"));

        return new BatchData(batch, site, relevantFiles, previousBatchOpt, previousFilesMap);
    }

    /**
     * Phase 2: Generates SQL content by dispatching to the appropriate strategy.
     * Runs outside transaction to avoid connection starvation during S3 I/O.
     */
    private SqlGenerationResult generateSqlContent(BatchData data) throws IOException {
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
     * Phase 3: Saves the generation record in a separate transaction.
     */
    @Transactional
    protected PluginSqlGeneration saveGenerationRecord(
            Long accountPluginId,
            BatchData data,
            String s3Key,
            long fileSize,
            SqlGenerationStats stats,
            long durationNanos) {

        PluginSqlGeneration generation = PluginSqlGeneration.create(
                accountPluginId,
                data.batch().getSiteId(),
                data.batch().getId(),
                data.previousBatchOpt().map(Batch::getId).orElse(null),
                s3Key,
                fileSize,
                stats,
                durationNanos / 1_000_000 // Convert nanos to ms
        );

        return sqlGenerationRepository.save(generation);
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
     * Internal record to hold batch data loaded in Phase 1.
     */
    private record BatchData(
            Batch batch,
            Site site,
            List<UploadedFile> relevantFiles,
            Optional<Batch> previousBatchOpt,
            Map<String, UploadedFile> previousFilesMap
    ) {}

    /**
     * Filters uploaded files based on site type.
     * DBF sites use CSV/CSV.GZ; CDC sites use JSONL/JSONL.GZ.
     */
    private List<UploadedFile> filterRelevantFiles(List<UploadedFile> files, boolean isCdc) {
        if (isCdc) {
            return files.stream()
                    .filter(f -> {
                        String name = f.getOriginalFileName().toLowerCase();
                        return name.endsWith(".jsonl") || name.endsWith(".jsonl.gz");
                    })
                    .collect(Collectors.toList());
        }
        return files.stream()
                .filter(f -> {
                    String name = f.getOriginalFileName().toLowerCase();
                    return name.endsWith(".csv") || name.endsWith(".csv.gz");
                })
                .collect(Collectors.toList());
    }

    /**
     * Normalizes file name for comparison (removes .gz extension).
     */
    private String normalizeFileName(String fileName) {
        if (fileName.toLowerCase().endsWith(".gz")) {
            return fileName.substring(0, fileName.length() - 3);
        }
        return fileName;
    }

    /**
     * Regenerates SQL for a batch, creating a new generation record.
     * Used when admin wants to re-run SQL generation for a specific batch.
     * <p>
     * Unlike {@link #generateSqlForBatch}, this method:
     * <ul>
     *   <li>Does not check for existing generation (allows regeneration)</li>
     *   <li>Returns the new generation (caller handles marking original as superseded)</li>
     * </ul>
     * </p>
     * <p>
     * Note: CDC batch regeneration is not yet supported.
     * </p>
     *
     * @param batchId The batch ID to regenerate SQL for
     * @param accountPluginId The ID of the active account plugin
     * @return The new generation record
     * @throws IllegalArgumentException if batch not found or has no processable files
     * @throws SqlGenerationException if generation fails
     */
    public PluginSqlGeneration regenerateForBatch(UUID batchId, Long accountPluginId) {
        Timer.Sample timer = Timer.start(meterRegistry);
        String s3Key = null;
        BatchData batchData = null;
        long startTimeMs = System.currentTimeMillis();

        try {
            // Load batch data (without checking for existing generation)
            batchData = loadBatchDataForRegeneration(batchId);
            if (batchData == null) {
                throw new IllegalArgumentException("Cannot regenerate: no processable files in batch " + batchId);
            }

            MDC.put("batchId", batchId.toString());
            MDC.put("siteId", batchData.batch().getSiteId().toString());
            MDC.put("accountId", batchData.batch().getAccountId().toString());

            log.info("Starting SQL regeneration for batch: batchId={}", batchId);

            // Audit: Log regeneration started
            pluginAuditService.logSqlRegenerationStarted(
                    PLUGIN_ID,
                    batchData.batch().getAccountId(),
                    batchId,
                    null  // Original generation ID is tracked by caller
            );

            // Generate SQL content
            SqlGenerationResult result = generateSqlContent(batchData);
            if (result == null) {
                log.info("No changes detected during regeneration, creating empty generation record");
                // For regeneration, we still create a record even if no changes
                result = new SqlGenerationResult("-- No changes detected\n",
                        new SqlGenerationStats(0, 0, 0, 0));
            }

            // Store SQL file in S3
            s3Key = s3SqlFileStorageService.storeSqlFile(
                    batchData.batch().getAccountId(),
                    batchData.site().getId(),
                    result.sqlContent()
            );
            long fileSize = s3SqlFileStorageService.getFileSize(s3Key);

            // Save generation record
            long durationMs = timer.stop(meterRegistry.timer("sql.regeneration.duration"));
            PluginSqlGeneration generation = saveGenerationRecord(
                    accountPluginId,
                    batchData,
                    s3Key,
                    fileSize,
                    result.stats(),
                    durationMs
            );

            log.info("SQL regeneration completed: batchId={}, statements={}, duration={}ms",
                    batchId, result.stats().total(), durationMs / 1_000_000);

            // Audit: Log regeneration completed
            pluginAuditService.logSqlRegenerationCompleted(
                    PLUGIN_ID,
                    batchData.batch().getAccountId(),
                    batchId,
                    null,  // originalGenerationId - tracked by caller
                    generation.getId(),
                    result.stats(),
                    durationMs / 1_000_000
            );

            return generation;

        } catch (IOException e) {
            log.error("SQL regeneration failed for batch (I/O error): batchId={}", batchId, e);
            meterRegistry.counter("sql.regeneration.errors").increment();
            cleanupOrphanedS3File(s3Key);

            if (batchData != null) {
                long durationMs = System.currentTimeMillis() - startTimeMs;
                pluginAuditService.logSqlRegenerationFailed(
                        PLUGIN_ID,
                        batchData.batch().getAccountId(),
                        batchId,
                        null,
                        "I/O error: " + e.getMessage(),
                        durationMs
                );
            }

            throw new SqlGenerationException("Failed to regenerate SQL for batch", e);
        } catch (RuntimeException e) {
            log.error("SQL regeneration failed for batch: batchId={}", batchId, e);
            meterRegistry.counter("sql.regeneration.errors").increment();
            cleanupOrphanedS3File(s3Key);

            if (batchData != null) {
                long durationMs = System.currentTimeMillis() - startTimeMs;
                pluginAuditService.logSqlRegenerationFailed(
                        PLUGIN_ID,
                        batchData.batch().getAccountId(),
                        batchId,
                        null,
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
     * Loads batch data for regeneration (skips existing generation check).
     * Always filters to CSV files — CDC regeneration is not yet supported.
     */
    @Transactional(readOnly = true)
    protected BatchData loadBatchDataForRegeneration(UUID batchId) {
        Batch batch = batchRepository.findByIdWithFiles(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));

        Site site = siteRepository.findById(batch.getSiteId())
                .orElseThrow(() -> new IllegalArgumentException("Site not found: " + batch.getSiteId()));

        List<UploadedFile> currentFiles = batch.getUploadedFiles();
        if (currentFiles.isEmpty()) {
            return null;
        }

        List<UploadedFile> csvFiles = currentFiles.stream()
                .filter(f -> f.getOriginalFileName().toLowerCase().endsWith(".csv") ||
                             f.getOriginalFileName().toLowerCase().endsWith(".csv.gz"))
                .collect(Collectors.toList());

        if (csvFiles.isEmpty()) {
            return null;
        }

        if (csvFiles.size() > MAX_FILES_PER_BATCH) {
            throw new IllegalArgumentException(
                    "Batch contains " + csvFiles.size() + " CSV files, exceeding limit of " + MAX_FILES_PER_BATCH);
        }

        Optional<Batch> previousBatchOpt = batchRepository
                .findPreviousBatchForSiteWithFiles(batch.getSiteId(), batchId);

        Map<String, UploadedFile> previousFilesMap = new HashMap<>();
        if (previousBatchOpt.isPresent()) {
            for (UploadedFile file : previousBatchOpt.get().getUploadedFiles()) {
                previousFilesMap.put(normalizeFileName(file.getOriginalFileName()), file);
            }
        }

        return new BatchData(batch, site, csvFiles, previousBatchOpt, previousFilesMap);
    }

    /**
     * Exception thrown when SQL generation fails.
     */
    public static class SqlGenerationException extends RuntimeException {
        public SqlGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
