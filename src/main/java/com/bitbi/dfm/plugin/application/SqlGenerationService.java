package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.plugin.domain.*;
import com.bitbi.dfm.plugin.infrastructure.storage.S3SqlFileStorageService;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.upload.domain.UploadedFile;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * Service orchestrating SQL file generation from CSV batch comparisons.
 * Compares CSV files between consecutive batches and generates SQL statements.
 * <p>
 * Performance considerations:
 * <ul>
 *   <li>Uses JOIN FETCH to prevent N+1 queries when loading batch files</li>
 *   <li>Limits batch size to prevent memory issues</li>
 *   <li>S3 operations run outside transactions to avoid connection starvation</li>
 * </ul>
 * </p>
 */
@Service
public class SqlGenerationService {

    private static final Logger log = LoggerFactory.getLogger(SqlGenerationService.class);

    /**
     * Maximum number of CSV files allowed per batch for SQL generation.
     * Prevents memory exhaustion from processing too many files.
     */
    private static final int MAX_CSV_FILES_PER_BATCH = 100;

    private static final String PLUGIN_ID = "bit-bi";

    private final BatchRepository batchRepository;
    private final SiteRepository siteRepository;
    private final AccountPluginRepository accountPluginRepository;
    private final PluginSqlGenerationRepository sqlGenerationRepository;
    private final CsvDiffService csvDiffService;
    private final SqlStatementGenerator sqlStatementGenerator;
    private final S3SqlFileStorageService s3SqlFileStorageService;
    private final S3Client s3Client;
    private final String bucketName;
    private final MeterRegistry meterRegistry;
    private final PluginAuditService pluginAuditService;

    public SqlGenerationService(
            BatchRepository batchRepository,
            SiteRepository siteRepository,
            AccountPluginRepository accountPluginRepository,
            PluginSqlGenerationRepository sqlGenerationRepository,
            CsvDiffService csvDiffService,
            SqlStatementGenerator sqlStatementGenerator,
            S3SqlFileStorageService s3SqlFileStorageService,
            S3Client s3Client,
            @org.springframework.beans.factory.annotation.Value("${s3.bucket.name}") String bucketName,
            MeterRegistry meterRegistry,
            PluginAuditService pluginAuditService) {
        this.batchRepository = batchRepository;
        this.siteRepository = siteRepository;
        this.accountPluginRepository = accountPluginRepository;
        this.sqlGenerationRepository = sqlGenerationRepository;
        this.csvDiffService = csvDiffService;
        this.sqlStatementGenerator = sqlStatementGenerator;
        this.s3SqlFileStorageService = s3SqlFileStorageService;
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.meterRegistry = meterRegistry;
        this.pluginAuditService = pluginAuditService;
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
     * When forceFullGeneration is true, skips previous batch comparison and generates
     * INSERT statements for all data. Used by reinit and plugin activation.
     * </p>
     *
     * @param batchId The completed batch ID
     * @param accountPluginId The ID of the active account plugin
     * @param forceFullGeneration If true, skips previous batch comparison (generates all INSERTs)
     * @return Optional containing the generation record, or empty if no changes
     */
    public Optional<PluginSqlGeneration> generateSqlForBatch(UUID batchId, Long accountPluginId, boolean forceFullGeneration) {
        Timer.Sample timer = Timer.start(meterRegistry);
        String s3Key = null;
        BatchData batchData = null;
        long startTimeMs = System.currentTimeMillis();

        try {
            // Phase 1: Load all required data (uses JOIN FETCH to prevent N+1)
            batchData = loadBatchData(batchId, forceFullGeneration);
            if (batchData == null) {
                return Optional.empty();
            }

            // Set MDC for structured logging
            MDC.put("batchId", batchId.toString());
            MDC.put("siteId", batchData.batch.getSiteId().toString());
            MDC.put("accountId", batchData.batch.getAccountId().toString());

            log.info("Starting SQL generation for batch: batchId={}", batchId);

            // Audit: Log SQL generation started
            pluginAuditService.logSqlGenerationStarted(
                    PLUGIN_ID,
                    batchData.batch.getAccountId(),
                    batchId,
                    batchData.batch.getSiteId()
            );

            // Phase 2: Generate SQL content (S3 reads outside transaction)
            SqlGenerationResult result = generateSqlContent(batchData);
            if (result == null) {
                log.info("No changes detected between batches, skipping SQL file creation");
                return Optional.empty();
            }

            // Phase 2b: Store SQL file in S3 (outside transaction)
            s3Key = s3SqlFileStorageService.storeSqlFile(
                    batchData.batch.getAccountId(),
                    batchData.site.getDomain(),
                    result.sqlContent
            );
            long fileSize = s3SqlFileStorageService.getFileSize(s3Key);

            // Phase 3: Save generation record (separate transaction)
            long durationMs = timer.stop(meterRegistry.timer("sql.generation.duration"));
            PluginSqlGeneration generation = saveGenerationRecord(
                    accountPluginId,
                    batchData,
                    s3Key,
                    fileSize,
                    result.stats,
                    durationMs
            );

            log.info("SQL generation completed: batchId={}, statements={}, duration={}ms",
                    batchId, result.stats.total(), durationMs / 1_000_000);

            // Audit: Log SQL generation completed
            pluginAuditService.logSqlGenerationCompleted(
                    PLUGIN_ID,
                    batchData.batch.getAccountId(),
                    batchId,
                    batchData.batch.getSiteId(),
                    result.stats,
                    s3Key,
                    durationMs / 1_000_000  // Convert nanos to ms
            );

            // Record metrics
            meterRegistry.counter("sql.generation.statements.inserts").increment(result.stats.inserts());
            meterRegistry.counter("sql.generation.statements.updates").increment(result.stats.updates());
            meterRegistry.counter("sql.generation.statements.deletes").increment(result.stats.deletes());

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
                        batchData.batch.getAccountId(),
                        batchId,
                        batchData.batch.getSiteId(),
                        "I/O error: " + e.getMessage(),
                        durationMs
                );
            }

            throw new SqlGenerationException("Failed to read CSV files for SQL generation", e);
        } catch (RuntimeException e) {
            log.error("SQL generation failed for batch: batchId={}", batchId, e);
            meterRegistry.counter("sql.generation.errors").increment();
            cleanupOrphanedS3File(s3Key);

            // Audit: Log SQL generation failed (if we have batch data)
            if (batchData != null) {
                long durationMs = System.currentTimeMillis() - startTimeMs;
                pluginAuditService.logSqlGenerationFailed(
                        PLUGIN_ID,
                        batchData.batch.getAccountId(),
                        batchId,
                        batchData.batch.getSiteId(),
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
     *
     * @param batchId The batch ID to load
     * @param forceFullGeneration If true, skips previous batch lookup (generates all INSERTs)
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

        // Get site for name
        Site site = siteRepository.findById(batch.getSiteId())
                .orElseThrow(() -> new IllegalArgumentException("Site not found: " + batch.getSiteId()));

        List<UploadedFile> currentFiles = batch.getUploadedFiles();
        if (currentFiles.isEmpty()) {
            log.warn("No files in batch, skipping SQL generation: batchId={}", batchId);
            return null;
        }

        // Filter to CSV files only
        List<UploadedFile> csvFiles = currentFiles.stream()
                .filter(f -> f.getOriginalFileName().toLowerCase().endsWith(".csv") ||
                             f.getOriginalFileName().toLowerCase().endsWith(".csv.gz"))
                .collect(Collectors.toList());

        if (csvFiles.isEmpty()) {
            log.warn("No CSV files in batch, skipping SQL generation: batchId={}", batchId);
            return null;
        }

        // Check batch file limit to prevent memory issues
        if (csvFiles.size() > MAX_CSV_FILES_PER_BATCH) {
            log.error("Too many CSV files in batch: count={}, max={}, batchId={}",
                    csvFiles.size(), MAX_CSV_FILES_PER_BATCH, batchId);
            throw new IllegalArgumentException(
                    "Batch contains " + csvFiles.size() + " CSV files, exceeding limit of " + MAX_CSV_FILES_PER_BATCH);
        }

        // Find previous batch for comparison using JOIN FETCH
        // When forceFullGeneration=true, skip previous batch lookup (generate all INSERTs)
        Optional<Batch> previousBatchOpt;
        if (forceFullGeneration) {
            log.info("Force full generation enabled - skipping previous batch comparison (will generate all INSERTs)");
            previousBatchOpt = Optional.empty();
        } else {
            previousBatchOpt = batchRepository
                    .findPreviousBatchForSiteWithFiles(batch.getSiteId(), batchId);
        }

        log.debug("Previous batch for comparison: {}",
                previousBatchOpt.map(b -> b.getId().toString()).orElse("none (first batch or force full)"));

        // Build previous files map
        Map<String, UploadedFile> previousFilesMap = new HashMap<>();
        if (previousBatchOpt.isPresent()) {
            for (UploadedFile file : previousBatchOpt.get().getUploadedFiles()) {
                previousFilesMap.put(normalizeFileName(file.getOriginalFileName()), file);
            }
        }

        return new BatchData(batch, site, csvFiles, previousBatchOpt, previousFilesMap);
    }

    /**
     * Phase 2: Generates SQL content by reading files from S3 and computing diffs.
     * Runs outside transaction to avoid connection starvation during S3 I/O.
     */
    private SqlGenerationResult generateSqlContent(BatchData data) throws IOException {
        StringBuilder sqlContent = new StringBuilder();
        int totalInserts = 0;
        int totalUpdates = 0;
        int totalDeletes = 0;
        int filesProcessed = 0;

        for (UploadedFile currentFile : data.csvFiles) {
            String normalizedName = normalizeFileName(currentFile.getOriginalFileName());
            String tableName = deriveTableName(normalizedName);

            log.debug("Processing CSV file: {} -> table {}", currentFile.getOriginalFileName(), tableName);

            // Read current file content from S3 as raw CSV string
            String currentCsvContent = readCsvContentFromS3(currentFile.getS3Key());

            // Extract headers from current CSV
            List<String> headers = extractHeaders(currentCsvContent);
            if (headers.isEmpty()) {
                log.warn("Empty headers in CSV file: {}", currentFile.getOriginalFileName());
                continue;
            }

            // Read previous file content (empty if first batch)
            String previousCsvContent = "";
            UploadedFile previousFile = data.previousFilesMap.get(normalizedName);
            if (previousFile != null) {
                previousCsvContent = readCsvContentFromS3(previousFile.getS3Key());
            }

            // Generate diffs using the new compare method (accepts raw CSV content)
            List<CsvRowDiff> diffs = csvDiffService.compare(previousCsvContent, currentCsvContent, headers);

            // Generate SQL statements
            for (CsvRowDiff diff : diffs) {
                String sql = sqlStatementGenerator.generate(diff, tableName, Map.of());
                sqlContent.append(sql);

                switch (diff.type()) {
                    case ADDED -> totalInserts++;
                    case MODIFIED -> totalUpdates++;
                    case DELETED -> totalDeletes++;
                }
            }

            filesProcessed++;
            meterRegistry.counter("sql.generation.files.processed").increment();
        }

        // Return null if no changes detected
        if (sqlContent.isEmpty()) {
            return null;
        }

        return new SqlGenerationResult(
                sqlContent.toString(),
                new SqlGenerationStats(totalInserts, totalUpdates, totalDeletes, filesProcessed)
        );
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
                data.batch.getSiteId(),
                data.batch.getId(),
                data.previousBatchOpt.map(Batch::getId).orElse(null),
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
            List<UploadedFile> csvFiles,
            Optional<Batch> previousBatchOpt,
            Map<String, UploadedFile> previousFilesMap
    ) {}

    /**
     * Internal record to hold SQL generation result from Phase 2.
     */
    private record SqlGenerationResult(
            String sqlContent,
            SqlGenerationStats stats
    ) {}

    /**
     * Reads CSV content from S3 and returns it as a raw string.
     */
    private String readCsvContentFromS3(String s3Key) throws IOException {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .build();

        try (ResponseInputStream<GetObjectResponse> s3Response = s3Client.getObject(request)) {
            InputStream inputStream = s3Response;

            // Handle gzipped files
            if (s3Key.toLowerCase().endsWith(".gz")) {
                inputStream = new GZIPInputStream(s3Response);
            }

            try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                StringBuilder content = new StringBuilder();
                char[] buffer = new char[8192];
                int bytesRead;
                while ((bytesRead = reader.read(buffer)) != -1) {
                    content.append(buffer, 0, bytesRead);
                }
                return content.toString();
            }
        }
    }

    /**
     * Extracts headers (column names) from CSV content.
     */
    private List<String> extractHeaders(String csvContent) throws IOException {
        if (csvContent == null || csvContent.isBlank()) {
            return Collections.emptyList();
        }

        try (CSVParser parser = CSVFormat.DEFAULT
                .withFirstRecordAsHeader()
                .parse(new StringReader(csvContent))) {
            return new ArrayList<>(parser.getHeaderNames());
        }
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
     * Derives table name from CSV filename.
     * Example: customers.csv -> customers
     * Example: 77nsfsfira.csv -> _77nsfsfira (prefixed because starts with digit)
     */
    private String deriveTableName(String fileName) {
        String name = fileName;
        if (name.toLowerCase().endsWith(".csv")) {
            name = name.substring(0, name.length() - 4);
        }
        // Sanitize: replace invalid characters with underscore
        name = name.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();

        // PostgreSQL identifiers must start with letter or underscore
        // If starts with digit, prefix with underscore
        if (!name.isEmpty() && Character.isDigit(name.charAt(0))) {
            name = "_" + name;
        }

        return name;
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
     *
     * @param batchId The batch ID to regenerate SQL for
     * @param accountPluginId The ID of the active account plugin
     * @return The new generation record
     * @throws IllegalArgumentException if batch not found
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
                throw new IllegalArgumentException("Cannot regenerate: no CSV files in batch " + batchId);
            }

            MDC.put("batchId", batchId.toString());
            MDC.put("siteId", batchData.batch.getSiteId().toString());
            MDC.put("accountId", batchData.batch.getAccountId().toString());

            log.info("Starting SQL regeneration for batch: batchId={}", batchId);

            // Audit: Log regeneration started
            pluginAuditService.logSqlRegenerationStarted(
                    PLUGIN_ID,
                    batchData.batch.getAccountId(),
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
                    batchData.batch.getAccountId(),
                    batchData.site.getDomain(),
                    result.sqlContent
            );
            long fileSize = s3SqlFileStorageService.getFileSize(s3Key);

            // Save generation record
            long durationMs = timer.stop(meterRegistry.timer("sql.regeneration.duration"));
            PluginSqlGeneration generation = saveGenerationRecord(
                    accountPluginId,
                    batchData,
                    s3Key,
                    fileSize,
                    result.stats,
                    durationMs
            );

            log.info("SQL regeneration completed: batchId={}, statements={}, duration={}ms",
                    batchId, result.stats.total(), durationMs / 1_000_000);

            // Audit: Log regeneration completed
            // Note: originalGenerationId is null here as we don't have it in this context
            // The caller (PluginHistoryService) tracks the original generation
            pluginAuditService.logSqlRegenerationCompleted(
                    PLUGIN_ID,
                    batchData.batch.getAccountId(),
                    batchId,
                    null,  // originalGenerationId - tracked by caller
                    generation.getId(),
                    result.stats,
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
                        batchData.batch.getAccountId(),
                        batchId,
                        null,  // originalGenerationId - tracked by caller
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
                        batchData.batch.getAccountId(),
                        batchId,
                        null,  // originalGenerationId - tracked by caller
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

        if (csvFiles.size() > MAX_CSV_FILES_PER_BATCH) {
            throw new IllegalArgumentException(
                    "Batch contains " + csvFiles.size() + " CSV files, exceeding limit of " + MAX_CSV_FILES_PER_BATCH);
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
