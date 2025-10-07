package com.bitbi.dfm.error.application;

import com.bitbi.dfm.error.domain.ErrorLog;
import com.bitbi.dfm.error.domain.ErrorLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ErrorLogExportService.
 */
@DisplayName("ErrorLogExportService Unit Tests")
class ErrorLogExportServiceTest {

    private ErrorLogExportService exportService;
    private ErrorLogRepository errorLogRepository;

    private UUID testSiteId;
    private UUID testBatchId;

    @BeforeEach
    void setUp() {
        errorLogRepository = mock(ErrorLogRepository.class);
        exportService = new ErrorLogExportService(errorLogRepository);

        testSiteId = UUID.randomUUID();
        testBatchId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Should export error logs to CSV with filters")
    void shouldExportErrorLogsToCsvWithFilters() {
        // Given
        LocalDateTime start = LocalDateTime.of(2025, 10, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2025, 10, 7, 23, 59);
        String errorType = "ValidationError";

        List<ErrorLog> errorLogs = Arrays.asList(
                ErrorLog.create(testSiteId, testBatchId, errorType, errorType, "Error 1", null, null, null),
                ErrorLog.create(testSiteId, testBatchId, errorType, errorType, "Error 2", null, null, null)
        );

        when(errorLogRepository.exportByFilters(testSiteId, errorType, start, end))
                .thenReturn(errorLogs);

        // When
        byte[] csv = exportService.exportToCsv(testSiteId, errorType, start, end);

        // Then
        assertNotNull(csv);
        assertTrue(csv.length > 0);

        String csvContent = new String(csv, StandardCharsets.UTF_8);
        assertTrue(csvContent.contains("id,batchId,siteId,type,message,occurredAt,metadata"));
        assertTrue(csvContent.contains("Error 1"));
        assertTrue(csvContent.contains("Error 2"));

        verify(errorLogRepository, times(1)).exportByFilters(testSiteId, errorType, start, end);
    }

    @Test
    @DisplayName("Should export batch errors to CSV")
    void shouldExportBatchErrorsToCsv() {
        // Given
        List<ErrorLog> errorLogs = Collections.singletonList(
                ErrorLog.create(testSiteId, testBatchId, "FileReadError", "FileReadError",
                        "Failed to read file", null, null, Map.of("fileName", "data.csv"))
        );

        when(errorLogRepository.findByBatchId(testBatchId)).thenReturn(errorLogs);

        // When
        byte[] csv = exportService.exportBatchErrors(testBatchId);

        // Then
        assertNotNull(csv);
        assertTrue(csv.length > 0);

        String csvContent = new String(csv, StandardCharsets.UTF_8);
        assertTrue(csvContent.contains("id,batchId,siteId,type,message,occurredAt,metadata"));
        assertTrue(csvContent.contains("Failed to read file"));
        assertTrue(csvContent.contains("FileReadError"));

        verify(errorLogRepository, times(1)).findByBatchId(testBatchId);
    }

    @Test
    @DisplayName("Should export site errors to CSV")
    void shouldExportSiteErrorsToCsv() {
        // Given
        UUID anotherBatchId = UUID.randomUUID();
        List<ErrorLog> errorLogs = Arrays.asList(
                ErrorLog.create(testSiteId, testBatchId, "NetworkError", "NetworkError",
                        "Connection timeout", null, null, null),
                ErrorLog.create(testSiteId, anotherBatchId, "ConfigError", "ConfigError",
                        "Missing config", null, null, null)
        );

        when(errorLogRepository.findBySiteId(testSiteId)).thenReturn(errorLogs);

        // When
        byte[] csv = exportService.exportSiteErrors(testSiteId);

        // Then
        assertNotNull(csv);
        assertTrue(csv.length > 0);

        String csvContent = new String(csv, StandardCharsets.UTF_8);
        assertTrue(csvContent.contains("Connection timeout"));
        assertTrue(csvContent.contains("Missing config"));

        verify(errorLogRepository, times(1)).findBySiteId(testSiteId);
    }

    @Test
    @DisplayName("Should export empty CSV when no errors found")
    void shouldExportEmptyCsvWhenNoErrorsFound() {
        // Given
        when(errorLogRepository.findByBatchId(testBatchId)).thenReturn(Collections.emptyList());

        // When
        byte[] csv = exportService.exportBatchErrors(testBatchId);

        // Then
        assertNotNull(csv);

        String csvContent = new String(csv, StandardCharsets.UTF_8);
        assertTrue(csvContent.contains("id,batchId,siteId,type,message,occurredAt,metadata"));
        // Should only have header line when no errors found
        assertEquals(1, csvContent.split("\n").length);
    }

    @Test
    @DisplayName("Should escape CSV special characters")
    void shouldEscapeCsvSpecialCharacters() {
        // Given
        ErrorLog errorLog = ErrorLog.create(testSiteId, testBatchId, "QuoteError", "QuoteError",
                "Message with \"quotes\" and, comma", null, null, null);

        when(errorLogRepository.findByBatchId(testBatchId))
                .thenReturn(Collections.singletonList(errorLog));

        // When
        byte[] csv = exportService.exportBatchErrors(testBatchId);

        // Then
        String csvContent = new String(csv, StandardCharsets.UTF_8);
        assertTrue(csvContent.contains("\"Message with \"\"quotes\"\" and, comma\""));
    }

    @Test
    @DisplayName("Should handle null metadata gracefully")
    void shouldHandleNullMetadataGracefully() {
        // Given
        ErrorLog errorLog = ErrorLog.create(testSiteId, testBatchId, "TestError", "TestError",
                "Test message", null, null, null);

        when(errorLogRepository.findByBatchId(testBatchId))
                .thenReturn(Collections.singletonList(errorLog));

        // When
        byte[] csv = exportService.exportBatchErrors(testBatchId);

        // Then
        assertNotNull(csv);
        String csvContent = new String(csv, StandardCharsets.UTF_8);
        assertFalse(csvContent.contains("null"));
    }

    @Test
    @DisplayName("Should include metadata in CSV export")
    void shouldIncludeMetadataInCsvExport() {
        // Given
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fileName", "data.csv");
        metadata.put("lineNumber", 42);

        ErrorLog errorLog = ErrorLog.create(testSiteId, testBatchId, "ParseError", "ParseError",
                "Parse failed", null, null, metadata);

        when(errorLogRepository.findBySiteId(testSiteId))
                .thenReturn(Collections.singletonList(errorLog));

        // When
        byte[] csv = exportService.exportSiteErrors(testSiteId);

        // Then
        String csvContent = new String(csv, StandardCharsets.UTF_8);
        assertTrue(csvContent.contains("ParseError"));
        assertTrue(csvContent.contains("Parse failed"));
    }

    @Test
    @DisplayName("Should format timestamps correctly in CSV")
    void shouldFormatTimestampsCorrectlyInCsv() {
        // Given
        ErrorLog errorLog = ErrorLog.create(testSiteId, testBatchId, "TimeError", "TimeError",
                "Time test", null, null, null);

        when(errorLogRepository.findByBatchId(testBatchId))
                .thenReturn(Collections.singletonList(errorLog));

        // When
        byte[] csv = exportService.exportBatchErrors(testBatchId);

        // Then
        String csvContent = new String(csv, StandardCharsets.UTF_8);
        // Should contain ISO formatted timestamp
        assertTrue(csvContent.matches("(?s).*\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*"));
    }

    @Test
    @DisplayName("ExportException should have message and cause")
    void exportExceptionShouldHaveMessageAndCause() {
        // Given
        IOException cause = new IOException("IO error");

        // When
        ErrorLogExportService.ExportException exception =
                new ErrorLogExportService.ExportException("Export failed", cause);

        // Then
        assertEquals("Export failed", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }
}
