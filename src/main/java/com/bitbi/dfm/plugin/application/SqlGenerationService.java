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
 */
@Service
public class SqlGenerationService {

    private static final Logger log = LoggerFactory.getLogger(SqlGenerationService.class);

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
            MeterRegistry meterRegistry) {
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
    }

    /**
     * Generates SQL file from batch comparison.
     * Called when a batch completes for an account with active Bit BI plugin.
     *
     * @param batchId The completed batch ID
     * @param accountPluginId The ID of the active account plugin
     * @return Optional containing the generation record, or empty if no changes
     */
    @Transactional
    public Optional<PluginSqlGeneration> generateSqlForBatch(UUID batchId, Long accountPluginId) {
        Timer.Sample timer = Timer.start(meterRegistry);

        try {
            // Get batch with files
            Batch batch = batchRepository.findById(batchId)
                    .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));

            // Set MDC for structured logging
            MDC.put("batchId", batchId.toString());
            MDC.put("siteId", batch.getSiteId().toString());
            MDC.put("accountId", batch.getAccountId().toString());

            log.info("Starting SQL generation for batch: batchId={}", batchId);

            // Check if already generated
            if (sqlGenerationRepository.existsBySourceBatchId(batchId)) {
                log.warn("SQL already generated for batch: batchId={}", batchId);
                return Optional.empty();
            }

            // Get site for name
            Site site = siteRepository.findById(batch.getSiteId())
                    .orElseThrow(() -> new IllegalArgumentException("Site not found: " + batch.getSiteId()));

            // Find previous batch for comparison
            Optional<Batch> previousBatchOpt = batchRepository
                    .findPreviousBatchForSite(batch.getSiteId(), batchId);

            log.debug("Previous batch for comparison: {}",
                    previousBatchOpt.map(b -> b.getId().toString()).orElse("none (first batch)"));

            // Get current batch files
            Batch batchWithFiles = batchRepository.findById(batchId)
                    .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));

            List<UploadedFile> currentFiles = batchWithFiles.getUploadedFiles();
            if (currentFiles.isEmpty()) {
                log.warn("No files in batch, skipping SQL generation: batchId={}", batchId);
                return Optional.empty();
            }

            // Filter to CSV files only
            List<UploadedFile> csvFiles = currentFiles.stream()
                    .filter(f -> f.getOriginalFileName().toLowerCase().endsWith(".csv") ||
                                 f.getOriginalFileName().toLowerCase().endsWith(".csv.gz"))
                    .collect(Collectors.toList());

            if (csvFiles.isEmpty()) {
                log.warn("No CSV files in batch, skipping SQL generation: batchId={}", batchId);
                return Optional.empty();
            }

            // Get previous batch files (if exists)
            Map<String, UploadedFile> previousFilesMap = new HashMap<>();
            if (previousBatchOpt.isPresent()) {
                Batch previousBatchWithFiles = batchRepository.findById(previousBatchOpt.get().getId())
                        .orElseThrow();
                for (UploadedFile file : previousBatchWithFiles.getUploadedFiles()) {
                    previousFilesMap.put(normalizeFileName(file.getOriginalFileName()), file);
                }
            }

            // Generate SQL for each CSV file
            StringBuilder sqlContent = new StringBuilder();
            int totalInserts = 0;
            int totalUpdates = 0;
            int totalDeletes = 0;
            int filesProcessed = 0;

            for (UploadedFile currentFile : csvFiles) {
                String normalizedName = normalizeFileName(currentFile.getOriginalFileName());
                String tableName = deriveTableName(normalizedName);

                log.debug("Processing CSV file: {} -> table {}", currentFile.getOriginalFileName(), tableName);

                // Read current file content
                List<Map<String, String>> currentRows = readCsvFromS3(currentFile.getS3Key());

                // Read previous file content (empty if first batch)
                List<Map<String, String>> previousRows = new ArrayList<>();
                UploadedFile previousFile = previousFilesMap.get(normalizedName);
                if (previousFile != null) {
                    previousRows = readCsvFromS3(previousFile.getS3Key());
                }

                // Generate diffs
                List<CsvRowDiff> diffs = csvDiffService.compare(previousRows, currentRows, Map.of());

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

            // Handle case where no changes were detected
            if (sqlContent.isEmpty()) {
                log.info("No changes detected between batches, skipping SQL file creation");
                return Optional.empty();
            }

            // Store SQL file in S3
            String s3Key = s3SqlFileStorageService.storeSqlFile(
                    batch.getAccountId(),
                    site.getDomain(),
                    sqlContent.toString()
            );

            // Get file size
            long fileSize = s3SqlFileStorageService.getFileSize(s3Key);

            // Record generation
            long durationMs = timer.stop(meterRegistry.timer("sql.generation.duration"));

            SqlGenerationStats stats = new SqlGenerationStats(
                    totalInserts, totalUpdates, totalDeletes, filesProcessed
            );

            PluginSqlGeneration generation = PluginSqlGeneration.create(
                    accountPluginId,
                    batch.getSiteId(),
                    batchId,
                    previousBatchOpt.map(Batch::getId).orElse(null),
                    s3Key,
                    fileSize,
                    stats,
                    durationMs / 1_000_000 // Convert nanos to ms
            );

            generation = sqlGenerationRepository.save(generation);

            log.info("SQL generation completed: batchId={}, statements={}, duration={}ms",
                    batchId, stats.total(), durationMs / 1_000_000);

            // Record metrics
            meterRegistry.counter("sql.generation.statements.inserts").increment(totalInserts);
            meterRegistry.counter("sql.generation.statements.updates").increment(totalUpdates);
            meterRegistry.counter("sql.generation.statements.deletes").increment(totalDeletes);

            return Optional.of(generation);

        } catch (IOException e) {
            log.error("SQL generation failed for batch (I/O error): batchId={}", batchId, e);
            meterRegistry.counter("sql.generation.errors").increment();
            throw new SqlGenerationException("Failed to read CSV files for SQL generation", e);
        } catch (RuntimeException e) {
            log.error("SQL generation failed for batch: batchId={}", batchId, e);
            meterRegistry.counter("sql.generation.errors").increment();
            throw e;
        } finally {
            MDC.clear();
        }
    }

    /**
     * Reads CSV content from S3 and returns rows as list of maps.
     */
    private List<Map<String, String>> readCsvFromS3(String s3Key) throws IOException {
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

            try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
                 CSVParser parser = CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(reader)) {

                List<Map<String, String>> rows = new ArrayList<>();
                for (CSVRecord record : parser) {
                    // Use LinkedHashMap to preserve column order
                    Map<String, String> row = new LinkedHashMap<>();
                    for (String header : parser.getHeaderNames()) {
                        row.put(header, record.get(header));
                    }
                    rows.add(row);
                }
                return rows;
            }
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
     */
    private String deriveTableName(String fileName) {
        String name = fileName;
        if (name.toLowerCase().endsWith(".csv")) {
            name = name.substring(0, name.length() - 4);
        }
        // Sanitize: replace invalid characters with underscore
        return name.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
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
