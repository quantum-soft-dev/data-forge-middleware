package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.plugin.domain.PluginSqlGeneration;
import com.bitbi.dfm.plugin.domain.PluginSqlGenerationRepository;
import com.bitbi.dfm.plugin.domain.SqlGenerationStats;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.site.domain.SiteType;
import com.bitbi.dfm.upload.domain.UploadedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Transactional halves of SQL generation (issue #164).
 *
 * <p>{@link SqlGenerationService} used to declare {@code loadBatchData} and
 * {@code saveGenerationRecord} as {@code @Transactional} on itself, then call them from
 * {@code doGenerateSqlForBatch}. Those annotations were inert — a self-invocation bypasses
 * the Spring proxy — so the methods simply joined whatever transaction the caller had
 * opened. The delta-SQL worker opened one around the whole generate call, which is how
 * a connection came to be held across the semaphore wait and the S3 I/O.</p>
 *
 * <p>The methods live here so the proxy is real. The orchestrator acquires the semaphore
 * and talks to S3 with nothing open, then enters these short transactions for the
 * database work only.</p>
 */
@Service
public class SqlGenerationPersistence {

    private static final Logger log = LoggerFactory.getLogger(SqlGenerationPersistence.class);

    static final int MAX_FILES_PER_BATCH = 100;

    /** Batch data loaded in Phase 1. */
    public record BatchData(
            Batch batch,
            Site site,
            List<UploadedFile> relevantFiles,
            Optional<Batch> previousBatchOpt,
            Map<String, UploadedFile> previousFilesMap,
            List<ChangelogSegment> segments
    ) {
    }

    private final BatchRepository batchRepository;
    private final SiteRepository siteRepository;
    private final PluginSqlGenerationRepository sqlGenerationRepository;
    private final ChangelogSegmentRepository changelogSegmentRepository;

    public SqlGenerationPersistence(BatchRepository batchRepository,
                                    SiteRepository siteRepository,
                                    PluginSqlGenerationRepository sqlGenerationRepository,
                                    ChangelogSegmentRepository changelogSegmentRepository) {
        this.batchRepository = batchRepository;
        this.siteRepository = siteRepository;
        this.sqlGenerationRepository = sqlGenerationRepository;
        this.changelogSegmentRepository = changelogSegmentRepository;
    }

    /**
     * Phase 1: load the batch, site and files (or changelog segments) needed to generate.
     * Returns {@code null} if generation should be skipped.
     */
    @Transactional(readOnly = true)
    public BatchData loadBatchData(UUID batchId, boolean forceFullGeneration) {
        Batch batch = batchRepository.findByIdWithFiles(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));

        if (sqlGenerationRepository.existsBySourceBatchId(batchId)) {
            log.warn("SQL already generated for batch: batchId={}", batchId);
            return null;
        }

        Site site = siteRepository.findById(batch.getSiteId())
                .orElseThrow(() -> new IllegalArgumentException("Site not found: " + batch.getSiteId()));

        List<ChangelogSegment> segments = changelogSegmentRepository.findByBatchId(batchId);
        if (!segments.isEmpty()) {
            return new BatchData(batch, site, List.of(), Optional.empty(), Map.of(), segments);
        }

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

        return new BatchData(batch, site, relevantFiles, previousBatchOpt, previousFilesMap, List.of());
    }

    /**
     * Load batch data for regeneration (skips the existing-generation check).
     * Always filters to CSV files — CDC regeneration is not yet supported.
     */
    @Transactional(readOnly = true)
    public BatchData loadBatchDataForRegeneration(UUID batchId) {
        Batch batch = batchRepository.findByIdWithFiles(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));

        Site site = siteRepository.findById(batch.getSiteId())
                .orElseThrow(() -> new IllegalArgumentException("Site not found: " + batch.getSiteId()));

        if (changelogSegmentRepository.existsByBatchId(batchId)) {
            throw new IllegalArgumentException(
                    "SQL regeneration is not supported for segment-backed batches: siteId=" + site.getId());
        }

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

        return new BatchData(batch, site, csvFiles, previousBatchOpt, previousFilesMap, List.of());
    }

    /** Phase 3: persist the generation record. */
    @Transactional
    public PluginSqlGeneration saveGenerationRecord(
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
                durationNanos / 1_000_000
        );

        if (!data.segments().isEmpty()) {
            long firstSeq = data.segments().stream()
                    .mapToLong(ChangelogSegment::getFirstSeq).min().orElseThrow();
            long lastSeq = data.segments().stream()
                    .mapToLong(ChangelogSegment::getLastSeq).max().orElseThrow();
            generation.recordSegmentRange(firstSeq, lastSeq);
        }

        return sqlGenerationRepository.save(generation);
    }

    boolean existsByBatchId(UUID batchId) {
        return changelogSegmentRepository.existsByBatchId(batchId);
    }

    private static List<UploadedFile> filterRelevantFiles(List<UploadedFile> files, boolean isCdc) {
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

    private static String normalizeFileName(String fileName) {
        if (fileName.toLowerCase().endsWith(".gz")) {
            return fileName.substring(0, fileName.length() - 3);
        }
        return fileName;
    }
}
