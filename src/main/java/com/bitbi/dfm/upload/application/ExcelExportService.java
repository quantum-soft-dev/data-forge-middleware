package com.bitbi.dfm.upload.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.batch.domain.BatchStatus;
import com.bitbi.dfm.batch.domain.exception.UnauthorizedBatchAccessException;
import com.bitbi.dfm.upload.domain.UploadedFile;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.*;

/**
 * Service for exporting CSV files from S3 to Excel (.xlsx) format.
 *
 * Features:
 * - Streaming Excel generation with Apache POI SXSSF (100-row window)
 * - Automatic encoding detection (UTF-8, Windows-1252, ISO-8859-1)
 * - Gzip decompression for .csv.gz files
 * - Sheet name deduplication with 31-character limit
 * - Memory-efficient processing (~100MB for 20 files with 10K rows each)
 * - Micrometer metrics for monitoring
 *
 * @see EncodingDetectionService
 * @see CsvDecompressionService
 */
@Service
@Transactional(readOnly = true)
public class ExcelExportService {

    private static final Logger logger = LoggerFactory.getLogger(ExcelExportService.class);

    private static final int SXSSF_WINDOW_SIZE = 100; // Rows kept in memory
    private static final int MAX_SHEET_NAME_LENGTH = 31; // Excel limitation
    private static final String INVALID_SHEET_NAME_CHARS = "[\\[\\]\\*\\?\\/\\\\:]"; // Excel forbidden chars

    // Memory safety limits
    private static final int MAX_FILES_PER_EXPORT = 20; // Max files to prevent memory exhaustion
    private static final long MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024; // 50MB per file
    private static final long MAX_TOTAL_SIZE_BYTES = 200 * 1024 * 1024; // 200MB total

    private final BatchRepository batchRepository;
    private final UploadedFileRepository uploadedFileRepository;
    private final S3Client s3Client;
    private final EncodingDetectionService encodingDetectionService;
    private final CsvDecompressionService csvDecompressionService;
    private final String bucketName;

    // Micrometer metrics
    private final Counter exportSheetCounter;
    private final Timer exportDurationTimer;

    public ExcelExportService(
        BatchRepository batchRepository,
        UploadedFileRepository uploadedFileRepository,
        S3Client s3Client,
        EncodingDetectionService encodingDetectionService,
        CsvDecompressionService csvDecompressionService,
        @Value("${s3.bucket.name}") String bucketName,
        MeterRegistry meterRegistry
    ) {
        this.batchRepository = batchRepository;
        this.uploadedFileRepository = uploadedFileRepository;
        this.s3Client = s3Client;
        this.encodingDetectionService = encodingDetectionService;
        this.csvDecompressionService = csvDecompressionService;
        this.bucketName = bucketName;

        // Initialize metrics
        this.exportSheetCounter = Counter.builder("exports.excel.sheets")
            .description("Total number of sheets exported to Excel")
            .register(meterRegistry);
        this.exportDurationTimer = Timer.builder("exports.excel.duration")
            .description("Time taken to generate Excel exports")
            .register(meterRegistry);
    }

    /**
     * Exports selected files from a batch as an Excel workbook (.xlsx).
     *
     * Each CSV file becomes a separate sheet in the workbook.
     * Sheet names are derived from filenames with automatic deduplication.
     *
     * <p><strong>Memory Safety Limits:</strong></p>
     * <ul>
     *   <li>Max 20 files per export (prevents memory exhaustion)</li>
     *   <li>Max 50MB per file (prevents individual large files)</li>
     *   <li>Max 200MB total size (prevents excessive memory usage)</li>
     *   <li>SXSSF with 100-row window (streaming, ~100MB footprint for typical files)</li>
     * </ul>
     *
     * <p><strong>Memory Estimate:</strong></p>
     * With SXSSF 100-row window, memory usage is approximately:
     * - 100 rows × number of columns × 8 bytes + sheet overhead
     * - For 20 files with 10K rows and 10 columns: ~100-150MB
     *
     * @param batchId The batch ID containing the files
     * @param fileIds List of file IDs to export (must belong to batch)
     * @param accountId The account ID for authorization
     * @param response HTTP response to stream Excel output
     * @throws UnauthorizedAccessException If batch doesn't belong to account
     * @throws IllegalStateException If batch is not completed
     * @throws IllegalArgumentException If fileIds is empty, files don't belong to batch, or size limits exceeded
     * @throws ExcelExportException If Excel generation fails
     */
    public void exportToExcel(
        UUID batchId,
        List<UUID> fileIds,
        UUID accountId,
        HttpServletResponse response
    ) {
        Timer.Sample timerSample = Timer.start();

        try {
            // Validate inputs
            if (fileIds == null || fileIds.isEmpty()) {
                throw new IllegalArgumentException("fileIds cannot be empty");
            }

            // Validate file count limit
            if (fileIds.size() > MAX_FILES_PER_EXPORT) {
                throw new IllegalArgumentException(
                    String.format("Cannot export more than %d files at once (requested: %d). " +
                        "Please split into multiple exports.", MAX_FILES_PER_EXPORT, fileIds.size())
                );
            }

            // Load and authorize batch
            Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));

            if (!batch.getAccountId().equals(accountId)) {
                throw new UnauthorizedBatchAccessException(batchId, accountId);
            }

            // Verify batch is completed (can only export completed uploads)
            if (batch.getStatus() != BatchStatus.COMPLETED) {
                throw new IllegalStateException("Batch must be COMPLETED to export. Current status: " + batch.getStatus());
            }

            // Load all files from batch
            List<UploadedFile> allFiles = uploadedFileRepository.findByBatchId(batchId);

            // Filter to requested files
            List<UploadedFile> files = allFiles.stream()
                .filter(file -> fileIds.contains(file.getId()))
                .toList();

            if (files.size() != fileIds.size()) {
                throw new IllegalArgumentException("Some fileIds not found or don't belong to batch");
            }

            // Validate file sizes
            validateFileSizes(files);

            // Set response headers
            String filename = "export-" + batchId + ".xlsx";
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

            // Generate Excel workbook
            generateExcelWorkbook(files, response);

            // Record metrics
            timerSample.stop(exportDurationTimer);
            exportSheetCounter.increment(files.size());

            logger.info("Successfully exported {} files to Excel for batch: {}", files.size(), batchId);

        } catch (IOException e) {
            logger.error("Failed to generate Excel export for batch: {}", batchId, e);
            throw new ExcelExportException("Failed to generate Excel export", e);
        }
    }

    /**
     * Validates file sizes to prevent memory exhaustion during Excel export.
     *
     * Checks:
     * - Individual file size (max 50MB)
     * - Total size of all files (max 200MB)
     *
     * @param files List of files to validate
     * @throws IllegalArgumentException If size limits are exceeded
     */
    private void validateFileSizes(List<UploadedFile> files) {
        long totalSize = 0;

        for (UploadedFile file : files) {
            long fileSize = file.getFileSize();

            // Check individual file size
            if (fileSize > MAX_FILE_SIZE_BYTES) {
                throw new IllegalArgumentException(
                    String.format("File '%s' is too large (%d MB). Max size per file is %d MB.",
                        file.getOriginalFileName(),
                        fileSize / (1024 * 1024),
                        MAX_FILE_SIZE_BYTES / (1024 * 1024))
                );
            }

            totalSize += fileSize;
        }

        // Check total size
        if (totalSize > MAX_TOTAL_SIZE_BYTES) {
            throw new IllegalArgumentException(
                String.format("Total size of selected files (%d MB) exceeds limit of %d MB. " +
                    "Please reduce the number of files or split into multiple exports.",
                    totalSize / (1024 * 1024),
                    MAX_TOTAL_SIZE_BYTES / (1024 * 1024))
            );
        }

        logger.debug("File size validation passed: {} files, total size {} MB",
            files.size(), totalSize / (1024 * 1024));
    }

    /**
     * Generates Excel workbook by streaming CSV files from S3.
     *
     * Uses SXSSF for memory-efficient generation (100-row window).
     *
     * @param files List of files to include as sheets
     * @param response HTTP response to write workbook to
     * @throws IOException If S3 read or Excel write fails
     */
    private void generateExcelWorkbook(List<UploadedFile> files, HttpServletResponse response) throws IOException {
        // Create streaming workbook (100 rows in memory)
        SXSSFWorkbook workbook = new SXSSFWorkbook(SXSSF_WINDOW_SIZE);

        try {
            Set<String> usedSheetNames = new HashSet<>();

            for (UploadedFile file : files) {
                logger.debug("Processing file for Excel export: {}", file.getOriginalFileName());

                // Create unique sheet name
                String sheetName = createUniqueSheetName(file.getOriginalFileName(), usedSheetNames);
                Sheet sheet = workbook.createSheet(sheetName);

                // Download file from S3 and process
                try (ResponseInputStream<GetObjectResponse> s3Stream = downloadFileFromS3(file)) {
                    // Decompress if .gz
                    InputStream decompressedStream = csvDecompressionService.decompressIfNeeded(
                        s3Stream,
                        file.getOriginalFileName()
                    );

                    // Detect encoding and create reader (auto-closed by try-with-resources)
                    try (Reader reader = encodingDetectionService.detectEncodingAndCreateReader(
                            decompressedStream,
                            file.getOriginalFileName()
                    )) {
                        // Parse CSV and write to sheet
                        writeCsvToSheet(reader, sheet, file.getOriginalFileName());
                    }
                }
            }

            // Write workbook to response
            workbook.write(response.getOutputStream());
            response.getOutputStream().flush();

        } finally {
            // Critical: Clean up SXSSF temp files
            workbook.dispose();
            workbook.close();
        }
    }

    /**
     * Downloads a file from S3.
     *
     * @param file The file metadata containing S3 key
     * @return InputStream of file content
     */
    private ResponseInputStream<GetObjectResponse> downloadFileFromS3(UploadedFile file) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
            .bucket(bucketName)
            .key(file.getS3Key())
            .build();

        return s3Client.getObject(getObjectRequest);
    }

    /**
     * Parses CSV content and writes to Excel sheet.
     *
     * @param reader CSV reader with correct encoding
     * @param sheet Target Excel sheet
     * @param filename Filename for logging
     * @throws IOException If CSV parsing fails
     */
    private void writeCsvToSheet(Reader reader, Sheet sheet, String filename) throws IOException {
        try (CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT)) {
            int rowNum = 0;

            for (CSVRecord csvRecord : csvParser) {
                Row row = sheet.createRow(rowNum++);

                for (int colNum = 0; colNum < csvRecord.size(); colNum++) {
                    Cell cell = row.createCell(colNum);
                    String value = csvRecord.get(colNum);
                    cell.setCellValue(value);
                }
            }

            logger.debug("Wrote {} rows from CSV '{}' to Excel sheet", rowNum, filename);

        } catch (Exception e) {
            logger.error("Failed to parse CSV file: {}", filename, e);
            throw new IOException("CSV parsing failed for file: " + filename, e);
        }
    }

    /**
     * Creates a unique sheet name with Excel constraints.
     *
     * Rules:
     * - Max 31 characters
     * - No: \ / ? * [ ] :
     * - Deduplicate with numeric suffix: "name", "name (2)", "name (3)"
     *
     * @param filename Original filename
     * @param usedNames Set of already-used sheet names
     * @return Valid, unique sheet name
     */
    private String createUniqueSheetName(String filename, Set<String> usedNames) {
        // Extract base name (remove extensions)
        String baseName = filename;

        // Remove .gz extension
        baseName = csvDecompressionService.getBaseFilename(baseName);

        // Remove .csv extension
        if (baseName.toLowerCase().endsWith(".csv")) {
            baseName = baseName.substring(0, baseName.length() - 4);
        }

        // Replace invalid characters with underscore
        baseName = baseName.replaceAll(INVALID_SHEET_NAME_CHARS, "_");

        // Truncate to 31 characters (leave room for suffix)
        if (baseName.length() > MAX_SHEET_NAME_LENGTH) {
            baseName = baseName.substring(0, MAX_SHEET_NAME_LENGTH);
        }

        // Handle deduplication
        String sheetName = baseName;
        int counter = 2;

        while (usedNames.contains(sheetName)) {
            String suffix = " (" + counter++ + ")";
            int maxBaseLength = MAX_SHEET_NAME_LENGTH - suffix.length();

            // Truncate base name if needed to fit suffix
            if (baseName.length() > maxBaseLength) {
                sheetName = baseName.substring(0, maxBaseLength) + suffix;
            } else {
                sheetName = baseName + suffix;
            }
        }

        usedNames.add(sheetName);
        return sheetName;
    }

    /**
     * Custom exception for Excel export failures.
     */
    public static class ExcelExportException extends RuntimeException {
        public ExcelExportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
