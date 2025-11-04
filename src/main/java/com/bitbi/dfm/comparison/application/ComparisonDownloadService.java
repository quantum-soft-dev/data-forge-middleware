package com.bitbi.dfm.comparison.application;

import com.bitbi.dfm.comparison.domain.FileComparison;
import com.bitbi.dfm.comparison.domain.ComparisonRepository;
import com.bitbi.dfm.comparison.infrastructure.persistence.ComparisonResultEntity;
import com.bitbi.dfm.comparison.infrastructure.persistence.SpringDataComparisonRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Service for generating downloadable ZIP archives of comparison results.
 * <p>
 * Responsibilities:
 * - Generate ZIP archive containing all diff files and summary report
 * - Convert JSONB unified diff to text format
 * - Stream ZIP content efficiently (no large memory footprint)
 * - Include summary.txt with comparison statistics
 * <p>
 * Implementation notes:
 * - Uses Apache Commons Compress for ZIP streaming
 * - Converts JSONB diff structure to unified diff text format
 * - Includes Micrometer metrics for download tracking
 *
 * @see com.bitbi.dfm.comparison.presentation.ComparisonController#downloadComparison(Long)
 */
@Service
public class ComparisonDownloadService {

    private static final Logger logger = LoggerFactory.getLogger(ComparisonDownloadService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ComparisonRepository comparisonRepository;
    private final SpringDataComparisonRepository springDataComparisonRepository;
    private final ObjectMapper objectMapper;
    private final Counter downloadCounter;

    public ComparisonDownloadService(
            ComparisonRepository comparisonRepository,
            SpringDataComparisonRepository springDataComparisonRepository,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry
    ) {
        this.comparisonRepository = comparisonRepository;
        this.springDataComparisonRepository = springDataComparisonRepository;
        this.objectMapper = objectMapper;
        this.downloadCounter = Counter.builder("downloads.zip.files")
                .description("Count of comparison ZIP downloads")
                .register(meterRegistry);
    }

    /**
     * Generate ZIP archive for comparison results.
     * <p>
     * ZIP structure:
     * <pre>
     * comparison-{id}.zip
     * ├── summary.txt           # Comparison statistics and metadata
     * ├── file-1-vs-file-2.diff # Diff for first file pair
     * ├── file-3-vs-file-4.diff # Diff for second file pair
     * └── ...
     * </pre>
     *
     * @param comparisonId Comparison ID
     * @param accountId    Account ID (for authorization)
     * @return ZIP archive as byte array
     * @throws IllegalArgumentException if comparison not found or access denied
     * @throws IOException              if ZIP generation fails
     */
    @Transactional(readOnly = true)
    public byte[] generateZipArchive(Long comparisonId, UUID accountId) throws IOException {
        MDC.put("comparisonId", comparisonId.toString());
        logger.info("Generating ZIP archive for comparison {}", comparisonId);

        try {
            // Load comparison with authorization check
            FileComparison comparison = comparisonRepository.findById(comparisonId)
                    .orElseThrow(() -> new IllegalArgumentException("Comparison not found: " + comparisonId));

            if (!comparison.getAccountId().equals(accountId)) {
                logger.warn("Access denied for user {} attempting to download comparison {}", accountId, comparisonId);
                throw new IllegalArgumentException("Access denied to comparison: " + comparisonId);
            }

            // Load comparison results (diff data)
            List<ComparisonResultEntity> results = springDataComparisonRepository
                    .findByComparisonIdWithFiles(comparisonId);

            if (results.isEmpty()) {
                logger.warn("No comparison results found for comparison {}", comparisonId);
            }

            // Generate ZIP with streaming
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipArchiveOutputStream zipOut = new ZipArchiveOutputStream(baos)) {
                zipOut.setEncoding(StandardCharsets.UTF_8.name());

                // Add summary.txt
                addSummaryFile(zipOut, comparison);

                // Add diff files
                for (ComparisonResultEntity result : results) {
                    addDiffFile(zipOut, result);
                }

                zipOut.finish();
            }

            byte[] zipBytes = baos.toByteArray();

            // Track metrics
            downloadCounter.increment();
            logger.info("Generated ZIP archive for comparison {} ({} files, {} bytes)",
                    comparisonId, results.size(), zipBytes.length);

            return zipBytes;

        } finally {
            MDC.remove("comparisonId");
        }
    }

    /**
     * Add summary.txt to ZIP archive.
     * <p>
     * Summary format:
     * <pre>
     * File Comparison Summary
     * ======================
     *
     * Comparison ID: 123
     * Status: COMPLETED
     * Created At: 2025-11-04 10:30:00
     * Completed At: 2025-11-04 10:30:15
     *
     * Batches Compared:
     * - Current Batch ID: a1b2c3d4-e5f6-7890-abcd-ef1234567890
     * - Target Batch ID: c3d4e5f6-a7b8-9012-cdef-123456789012
     *
     * Results:
     * - Total Files Compared: 10
     * - Files Changed: 5
     * - Files Added: 2
     * - Files Unchanged: 3
     * - Total Change Size: 15.2 KB
     * </pre>
     */
    /**
     * T102: Generate human-readable summary report as text.
     * <p>
     * Generates a formatted summary report for download as a standalone file.
     * Report format follows the same structure as summary.txt in ZIP archives.
     * <p>
     * Report sections:
     * - Title and separator
     * - Comparison metadata (ID, status, timestamps)
     * - Session information (current and target batch IDs)
     * - Statistics (file counts, change size)
     * - Error message (if applicable)
     *
     * @param comparisonId Comparison ID
     * @param accountId    Account ID (for authorization)
     * @return Human-readable summary report as string
     * @throws IllegalArgumentException if comparison not found or access denied
     */
    @Transactional(readOnly = true)
    public String generateSummaryReport(Long comparisonId, UUID accountId) {
        logger.info("Generating summary report for comparison {} (account={})", comparisonId, accountId);

        // Retrieve comparison (includes authorization check)
        FileComparison comparison = comparisonRepository.findById(comparisonId)
                .orElseThrow(() -> {
                    logger.warn("Comparison {} not found", comparisonId);
                    return new IllegalArgumentException("Comparison not found");
                });

        // Verify ownership
        if (!comparison.getAccountId().equals(accountId)) {
            logger.warn("Access denied: account {} attempted to access comparison {} owned by account {}",
                    accountId, comparisonId, comparison.getAccountId());
            throw new IllegalArgumentException("Access denied: you do not own this comparison");
        }

        // Set MDC context for logging
        MDC.put("comparisonId", comparisonId.toString());
        MDC.put("accountId", accountId.toString());

        try {
            // Generate summary text (reuse formatting logic)
            String summaryText = formatSummaryReport(comparison);

            logger.info("Successfully generated summary report for comparison {} ({} bytes)",
                    comparisonId, summaryText.length());

            return summaryText;
        } finally {
            MDC.remove("comparisonId");
            MDC.remove("accountId");
        }
    }

    private void addSummaryFile(ZipArchiveOutputStream zipOut, FileComparison comparison) throws IOException {
        String summaryText = formatSummaryReport(comparison);

        ZipArchiveEntry summaryEntry = new ZipArchiveEntry("summary.txt");
        summaryEntry.setSize(summaryText.length());
        zipOut.putArchiveEntry(summaryEntry);
        zipOut.write(summaryText.getBytes(StandardCharsets.UTF_8));
        zipOut.closeArchiveEntry();
    }

    /**
     * Format summary report as human-readable text.
     * <p>
     * This method generates the text content for summary reports, used by both:
     * - ZIP archive summary.txt files
     * - Standalone summary report downloads
     */
    private String formatSummaryReport(FileComparison comparison) {
        StringBuilder summary = new StringBuilder();
        summary.append("FILE COMPARISON SUMMARY REPORT\n");
        summary.append("==============================\n\n");

        summary.append("Comparison ID: ").append(comparison.getId()).append("\n");
        summary.append("Status: ").append(comparison.getStatus()).append("\n");
        summary.append("Created At: ").append(comparison.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).format(DATE_FORMATTER)).append("\n");
        if (comparison.getCompletedAt() != null) {
            summary.append("Completed At: ").append(comparison.getCompletedAt().atZone(java.time.ZoneId.systemDefault()).format(DATE_FORMATTER)).append("\n");
        }
        summary.append("\n");

        summary.append("Session Information:\n");
        summary.append("- Current Upload Session: ").append(comparison.getCurrentBatchId()).append("\n");
        summary.append("- Target Upload Session: ").append(comparison.getTargetBatchId()).append("\n");
        summary.append("\n");

        summary.append("Statistics:\n");
        summary.append("- Total Files Compared: ").append(comparison.getTotalFilesCompared()).append("\n");
        summary.append("- Files Changed: ").append(comparison.getFilesChanged()).append("\n");
        summary.append("- Files Added: ").append(comparison.getFilesAdded()).append("\n");
        summary.append("- Files Unchanged: ").append(comparison.getFilesUnchanged()).append("\n");
        summary.append("- Total Change Size: ").append(formatBytes(comparison.getTotalChangeSize())).append("\n");

        if (comparison.getErrorMessage() != null) {
            summary.append("\nError: ").append(comparison.getErrorMessage()).append("\n");
        }

        return summary.toString();
    }

    /**
     * Add diff file to ZIP archive.
     * <p>
     * Filename format: {currentFileName}-vs-{targetFileName}.diff
     * Content: Unified diff in text format
     */
    private void addDiffFile(ZipArchiveOutputStream zipOut, ComparisonResultEntity result) throws IOException {
        // Generate filename
        String filename = generateDiffFilename(result);

        // Convert JSONB diff to unified diff text
        String diffText = convertJsonbToUnifiedDiff(result);

        // Add to ZIP
        ZipArchiveEntry diffEntry = new ZipArchiveEntry(filename);
        diffEntry.setSize(diffText.length());
        zipOut.putArchiveEntry(diffEntry);
        zipOut.write(diffText.getBytes(StandardCharsets.UTF_8));
        zipOut.closeArchiveEntry();

        logger.debug("Added diff file to ZIP: {}", filename);
    }

    /**
     * Generate filename for diff file.
     * <p>
     * Examples:
     * - MODIFIED: file1.csv-vs-file1.csv.diff
     * - ADDED: file2.csv-new.diff
     * - UNCHANGED: file3.csv-unchanged.diff
     */
    private String generateDiffFilename(ComparisonResultEntity result) {
        // For safety, use file ID if filename is not available
        String baseName = "file-" + result.getFileId();

        return switch (result.getChangeType()) {
            case ADDED -> baseName + "-new.diff";
            case UNCHANGED -> baseName + "-unchanged.diff";
            case MODIFIED, REMOVED -> baseName + "-vs-file-" + result.getTargetFileId() + ".diff";
        };
    }

    /**
     * Convert JSONB unified diff structure to text format.
     * <p>
     * JSONB structure (from DiffServiceImpl):
     * <pre>
     * {
     *   "hunks": [
     *     {
     *       "oldStart": 1,
     *       "oldLines": 3,
     *       "newStart": 1,
     *       "newLines": 4,
     *       "changes": [
     *         {"type": "UNCHANGED", "lineNumber": 1, "content": "line 1"},
     *         {"type": "REMOVED", "lineNumber": 2, "content": "line 2"},
     *         {"type": "ADDED", "lineNumber": 2, "content": "line 2 modified"}
     *       ]
     *     }
     *   ],
     *   "oldFileName": "file1.csv",
     *   "newFileName": "file1.csv"
     * }
     * </pre>
     * <p>
     * Unified diff text format:
     * <pre>
     * --- file1.csv
     * +++ file1.csv
     * @@ -1,3 +1,4 @@
     *  line 1
     * -line 2
     * +line 2 modified
     *  line 3
     * </pre>
     */
    private String convertJsonbToUnifiedDiff(ComparisonResultEntity result) throws IOException {
        if (result.getUnifiedDiff() == null || result.getUnifiedDiff().isBlank()) {
            return "No changes (files are identical)\n";
        }

        try {
            JsonNode diffJson = objectMapper.readTree(result.getUnifiedDiff());

            StringBuilder diff = new StringBuilder();

            // Header
            String oldFileName = diffJson.path("oldFileName").asText("file");
            String newFileName = diffJson.path("newFileName").asText("file");
            diff.append("--- ").append(oldFileName).append("\n");
            diff.append("+++ ").append(newFileName).append("\n");

            // Hunks
            JsonNode hunks = diffJson.path("hunks");
            if (hunks.isArray()) {
                for (JsonNode hunk : hunks) {
                    int oldStart = hunk.path("oldStart").asInt();
                    int oldLines = hunk.path("oldLines").asInt();
                    int newStart = hunk.path("newStart").asInt();
                    int newLines = hunk.path("newLines").asInt();

                    diff.append("@@ -").append(oldStart).append(",").append(oldLines)
                            .append(" +").append(newStart).append(",").append(newLines)
                            .append(" @@\n");

                    JsonNode changes = hunk.path("changes");
                    if (changes.isArray()) {
                        for (JsonNode change : changes) {
                            String type = change.path("type").asText();
                            String content = change.path("content").asText();

                            String prefix = switch (type) {
                                case "ADDED" -> "+";
                                case "REMOVED" -> "-";
                                case "UNCHANGED" -> " ";
                                default -> " ";
                            };

                            diff.append(prefix).append(content).append("\n");
                        }
                    }
                }
            }

            return diff.toString();

        } catch (Exception e) {
            logger.error("Failed to parse unified diff JSON for result {}", result.getId(), e);
            return "Error parsing diff: " + e.getMessage() + "\n";
        }
    }

    /**
     * Format bytes to human-readable string.
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
