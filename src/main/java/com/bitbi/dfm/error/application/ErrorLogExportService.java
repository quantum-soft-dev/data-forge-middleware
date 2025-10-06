package com.bitbi.dfm.error.application;

import com.bitbi.dfm.error.domain.ErrorLog;
import com.bitbi.dfm.error.domain.ErrorLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Application service for error log export operations.
 * <p>
 * Provides CSV export functionality for admin error log queries.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
@Transactional(readOnly = true)
public class ErrorLogExportService {

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String CSV_HEADER = "id,batchId,siteId,type,message,occurredAt,metadata";

    private final ErrorLogRepository errorLogRepository;

    public ErrorLogExportService(ErrorLogRepository errorLogRepository) {
        this.errorLogRepository = errorLogRepository;
    }

    /**
     * Export error logs to CSV format.
     *
     * @param siteId site identifier (nullable)
     * @param type   error type (nullable)
     * @param start  start timestamp (nullable)
     * @param end    end timestamp (nullable)
     * @return CSV content as byte array
     * @throws ExportException if CSV generation fails
     */
    public byte[] exportToCsv(UUID siteId, String type, LocalDateTime start, LocalDateTime end) {
        List<ErrorLog> errorLogs = errorLogRepository.exportByFilters(siteId, type, start, end);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             OutputStreamWriter osw = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
             PrintWriter writer = new PrintWriter(osw)) {

            // Write CSV header
            writer.println(CSV_HEADER);

            // Write CSV rows
            for (ErrorLog errorLog : errorLogs) {
                writeCsvRow(writer, errorLog);
            }

            writer.flush();
            return baos.toByteArray();

        } catch (IOException e) {
            throw new ExportException("Failed to generate CSV export", e);
        }
    }

    /**
     * Export error logs for batch.
     *
     * @param batchId batch identifier
     * @return CSV content as byte array
     */
    public byte[] exportBatchErrors(UUID batchId) {
        List<ErrorLog> errorLogs = errorLogRepository.findByBatchId(batchId);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             OutputStreamWriter osw = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
             PrintWriter writer = new PrintWriter(osw)) {

            writer.println(CSV_HEADER);

            for (ErrorLog errorLog : errorLogs) {
                writeCsvRow(writer, errorLog);
            }

            writer.flush();
            return baos.toByteArray();

        } catch (IOException e) {
            throw new ExportException("Failed to generate CSV export for batch", e);
        }
    }

    /**
     * Export error logs for site.
     *
     * @param siteId site identifier
     * @return CSV content as byte array
     */
    public byte[] exportSiteErrors(UUID siteId) {
        List<ErrorLog> errorLogs = errorLogRepository.findBySiteId(siteId);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             OutputStreamWriter osw = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
             PrintWriter writer = new PrintWriter(osw)) {

            writer.println(CSV_HEADER);

            for (ErrorLog errorLog : errorLogs) {
                writeCsvRow(writer, errorLog);
            }

            writer.flush();
            return baos.toByteArray();

        } catch (IOException e) {
            throw new ExportException("Failed to generate CSV export for site", e);
        }
    }

    /**
     * Write single error log as CSV row.
     *
     * @param writer   print writer
     * @param errorLog error log entry
     */
    private void writeCsvRow(PrintWriter writer, ErrorLog errorLog) {
        StringBuilder row = new StringBuilder();

        row.append(escapeCsv(errorLog.getId().toString())).append(",");
        row.append(escapeCsv(errorLog.getBatchId().toString())).append(",");
        row.append(escapeCsv(errorLog.getSiteId().toString())).append(",");
        row.append(escapeCsv(errorLog.getType())).append(",");
        row.append(escapeCsv(errorLog.getMessage())).append(",");
        row.append(escapeCsv(errorLog.getOccurredAt().format(ISO_FORMATTER))).append(",");

        // Metadata as JSON string
        String metadata = errorLog.getMetadata() != null ? errorLog.getMetadata().toString() : "";
        row.append(escapeCsv(metadata));

        writer.println(row);
    }

    /**
     * Escape CSV field value.
     *
     * @param value field value
     * @return escaped value
     */
    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }

        // Quote if contains comma, newline, or quote
        if (value.contains(",") || value.contains("\n") || value.contains("\"")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }

        return value;
    }

    /**
     * Exception thrown when CSV export fails.
     */
    public static class ExportException extends RuntimeException {
        public ExportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
