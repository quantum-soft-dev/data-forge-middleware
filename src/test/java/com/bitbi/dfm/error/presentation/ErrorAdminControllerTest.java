package com.bitbi.dfm.error.presentation;

import com.bitbi.dfm.error.domain.ErrorLog;
import com.bitbi.dfm.error.domain.ErrorLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ErrorAdminController.
 */
@DisplayName("ErrorAdminController Unit Tests")
class ErrorAdminControllerTest {

    private ErrorAdminController controller;
    private ErrorLogRepository errorLogRepository;
    private ObjectMapper objectMapper;

    private UUID testSiteId;
    private UUID testBatchId;

    @BeforeEach
    void setUp() {
        errorLogRepository = mock(ErrorLogRepository.class);
        objectMapper = new ObjectMapper();
        controller = new ErrorAdminController(errorLogRepository, objectMapper);

        testSiteId = UUID.randomUUID();
        testBatchId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Should list all errors with pagination")
    void shouldListAllErrorsWithPagination() {
        // Given
        ErrorLog error1 = ErrorLog.create(testSiteId, testBatchId, "Error", "Type1", "Message 1", null, null, null);
        ErrorLog error2 = ErrorLog.create(testSiteId, testBatchId, "Error", "Type2", "Message 2", null, null, null);
        List<ErrorLog> errors = Arrays.asList(error1, error2);
        Page<ErrorLog> page = new PageImpl<>(errors, PageRequest.of(0, 20), 2);

        when(errorLogRepository.findAll(any(Pageable.class))).thenReturn(page);

        // When
        ResponseEntity<Map<String, Object>> response = controller.listErrors(null, null, 0, 20);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, ((List<?>) response.getBody().get("content")).size());
        assertEquals(0, response.getBody().get("page"));
        assertEquals(20, response.getBody().get("size"));
        assertEquals(2L, response.getBody().get("totalElements"));
        assertEquals(1, response.getBody().get("totalPages"));

        verify(errorLogRepository).findAll(any(Pageable.class));
    }

    @Test
    @DisplayName("Should filter errors by site ID")
    void shouldFilterErrorsBySiteId() {
        // Given
        ErrorLog error = ErrorLog.create(testSiteId, testBatchId, "Error", "Type1", "Message 1", null, null, null);
        Page<ErrorLog> page = new PageImpl<>(Arrays.asList(error), PageRequest.of(0, 20), 1);

        when(errorLogRepository.findBySiteId(eq(testSiteId), any(Pageable.class))).thenReturn(page);

        // When
        ResponseEntity<Map<String, Object>> response = controller.listErrors(testSiteId, null, 0, 20);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, ((List<?>) response.getBody().get("content")).size());

        verify(errorLogRepository).findBySiteId(eq(testSiteId), any(Pageable.class));
    }

    @Test
    @DisplayName("Should filter errors by type")
    void shouldFilterErrorsByType() {
        // Given
        String errorType = "ValidationError";
        ErrorLog error = ErrorLog.create(testSiteId, testBatchId, "Error", errorType, "Message", null, null, null);
        Page<ErrorLog> page = new PageImpl<>(Arrays.asList(error), PageRequest.of(0, 20), 1);

        when(errorLogRepository.findByType(eq(errorType), any(Pageable.class))).thenReturn(page);

        // When
        ResponseEntity<Map<String, Object>> response = controller.listErrors(null, errorType, 0, 20);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, ((List<?>) response.getBody().get("content")).size());

        verify(errorLogRepository).findByType(eq(errorType), any(Pageable.class));
    }

    @Test
    @DisplayName("Should filter errors by site ID and type")
    void shouldFilterErrorsBySiteIdAndType() {
        // Given
        String errorType = "ValidationError";
        ErrorLog error = ErrorLog.create(testSiteId, testBatchId, "Error", errorType, "Message", null, null, null);
        Page<ErrorLog> page = new PageImpl<>(Arrays.asList(error), PageRequest.of(0, 20), 1);

        when(errorLogRepository.findBySiteIdAndType(eq(testSiteId), eq(errorType), any(Pageable.class)))
                .thenReturn(page);

        // When
        ResponseEntity<Map<String, Object>> response = controller.listErrors(testSiteId, errorType, 0, 20);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, ((List<?>) response.getBody().get("content")).size());

        verify(errorLogRepository).findBySiteIdAndType(eq(testSiteId), eq(errorType), any(Pageable.class));
    }

    @Test
    @DisplayName("Should return empty page when no errors found")
    void shouldReturnEmptyPageWhenNoErrorsFound() {
        // Given
        Page<ErrorLog> emptyPage = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 20), 0);
        when(errorLogRepository.findAll(any(Pageable.class))).thenReturn(emptyPage);

        // When
        ResponseEntity<Map<String, Object>> response = controller.listErrors(null, null, 0, 20);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(((List<?>) response.getBody().get("content")).isEmpty());
        assertEquals(0L, response.getBody().get("totalElements"));
    }

    @Test
    @DisplayName("Should handle errors when listing fails")
    void shouldHandleErrorsWhenListingFails() {
        // Given
        when(errorLogRepository.findAll(any(Pageable.class))).thenThrow(new RuntimeException("Database error"));

        // When
        ResponseEntity<Map<String, Object>> response = controller.listErrors(null, null, 0, 20);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("error"));
    }

    @Test
    @DisplayName("Should export errors to CSV")
    void shouldExportErrorsToCsv() {
        // Given
        ErrorLog error1 = ErrorLog.create(testSiteId, testBatchId, "Error", "Type1", "Message 1", null, null, null);
        ErrorLog error2 = ErrorLog.create(testSiteId, testBatchId, "Error", "Type2", "Message 2", null, null, null);
        List<ErrorLog> errors = Arrays.asList(error1, error2);

        when(errorLogRepository.exportByFilters(any(), any(), any(), any())).thenReturn(errors);

        // When
        ResponseEntity<String> response = controller.exportErrors(null, null, null, null);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("ID,Batch ID,Site ID,Type,Message,Metadata,Occurred At"));
        assertTrue(response.getBody().contains(error1.getId().toString()));
        assertTrue(response.getBody().contains(error2.getId().toString()));

        verify(errorLogRepository).exportByFilters(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should export errors with filters")
    void shouldExportErrorsWithFilters() {
        // Given
        String errorType = "ValidationError";
        ErrorLog error = ErrorLog.create(testSiteId, testBatchId, "Error", errorType, "Message", null, null, null);
        List<ErrorLog> errors = Arrays.asList(error);

        when(errorLogRepository.exportByFilters(eq(testSiteId), eq(errorType), any(), any())).thenReturn(errors);

        // When
        ResponseEntity<String> response = controller.exportErrors(testSiteId, errorType, null, null);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains(error.getId().toString()));

        verify(errorLogRepository).exportByFilters(eq(testSiteId), eq(errorType), any(), any());
    }

    @Test
    @DisplayName("Should export errors with date range")
    void shouldExportErrorsWithDateRange() {
        // Given
        String start = "2025-10-01T00:00:00";
        String end = "2025-10-07T23:59:59";
        ErrorLog error = ErrorLog.create(testSiteId, testBatchId, "Error", "Type", "Message", null, null, null);
        List<ErrorLog> errors = Arrays.asList(error);

        when(errorLogRepository.exportByFilters(any(), any(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(errors);

        // When
        ResponseEntity<String> response = controller.exportErrors(null, null, start, end);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());

        verify(errorLogRepository).exportByFilters(any(), any(), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("Should escape CSV special characters")
    void shouldEscapeCsvSpecialCharacters() {
        // Given
        ErrorLog error = ErrorLog.create(testSiteId, testBatchId, "Error", "Type", "Message with, comma", null, null, null);
        List<ErrorLog> errors = Arrays.asList(error);

        when(errorLogRepository.exportByFilters(any(), any(), any(), any())).thenReturn(errors);

        // When
        ResponseEntity<String> response = controller.exportErrors(null, null, null, null);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("\"Message with, comma\""));
    }

    @Test
    @DisplayName("Should handle null batch ID in CSV export")
    void shouldHandleNullBatchIdInCsvExport() {
        // Given
        ErrorLog error = ErrorLog.create(testSiteId, null, "Error", "Type", "Message", null, null, null);
        List<ErrorLog> errors = Arrays.asList(error);

        when(errorLogRepository.exportByFilters(any(), any(), any(), any())).thenReturn(errors);

        // When
        ResponseEntity<String> response = controller.exportErrors(null, null, null, null);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        String[] lines = response.getBody().split("\n");
        assertTrue(lines.length >= 2);
    }

    @Test
    @DisplayName("Should serialize metadata in CSV export")
    void shouldSerializeMetadataInCsvExport() {
        // Given
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key", "value");
        ErrorLog error = ErrorLog.create(testSiteId, testBatchId, "Error", "Type", "Message", null, null, metadata);
        List<ErrorLog> errors = Arrays.asList(error);

        when(errorLogRepository.exportByFilters(any(), any(), any(), any())).thenReturn(errors);

        // When
        ResponseEntity<String> response = controller.exportErrors(null, null, null, null);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("key") && response.getBody().contains("value"));
    }

    @Test
    @DisplayName("Should handle errors when export fails")
    void shouldHandleErrorsWhenExportFails() {
        // Given
        when(errorLogRepository.exportByFilters(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Database error"));

        // When
        ResponseEntity<String> response = controller.exportErrors(null, null, null, null);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Failed to export errors", response.getBody());
    }

    @Test
    @DisplayName("Should export empty CSV when no errors found")
    void shouldExportEmptyCsvWhenNoErrorsFound() {
        // Given
        when(errorLogRepository.exportByFilters(any(), any(), any(), any())).thenReturn(Collections.emptyList());

        // When
        ResponseEntity<String> response = controller.exportErrors(null, null, null, null);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        String csvContent = response.getBody();
        assertEquals(1, csvContent.split("\n").length); // Only header
    }

    @Test
    @DisplayName("Should set correct content type for CSV export")
    void shouldSetCorrectContentTypeForCsvExport() {
        // Given
        when(errorLogRepository.exportByFilters(any(), any(), any(), any())).thenReturn(Collections.emptyList());

        // When
        ResponseEntity<String> response = controller.exportErrors(null, null, null, null);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getHeaders().getContentType());
        assertEquals("text/csv", response.getHeaders().getContentType().toString());
    }

    @Test
    @DisplayName("Should include metadata in error response")
    void shouldIncludeMetadataInErrorResponse() {
        // Given
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sourceFile", "test.csv");
        ErrorLog error = ErrorLog.create(testSiteId, testBatchId, "Error", "Type", "Message", null, null, metadata);
        Page<ErrorLog> page = new PageImpl<>(Arrays.asList(error), PageRequest.of(0, 20), 1);

        when(errorLogRepository.findAll(any(Pageable.class))).thenReturn(page);

        // When
        ResponseEntity<Map<String, Object>> response = controller.listErrors(null, null, 0, 20);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) response.getBody().get("content");
        assertEquals(1, content.size());
        assertTrue(content.get(0).containsKey("metadata"));
    }

    @Test
    @DisplayName("Should handle pagination with custom page size")
    void shouldHandlePaginationWithCustomPageSize() {
        // Given
        List<ErrorLog> errors = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            errors.add(ErrorLog.create(testSiteId, testBatchId, "Error", "Type" + i, "Message " + i, null, null, null));
        }
        Page<ErrorLog> page = new PageImpl<>(errors.subList(0, 50), PageRequest.of(0, 50), 100);

        when(errorLogRepository.findAll(any(Pageable.class))).thenReturn(page);

        // When
        ResponseEntity<Map<String, Object>> response = controller.listErrors(null, null, 0, 50);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(50, ((List<?>) response.getBody().get("content")).size());
        assertEquals(50, response.getBody().get("size"));
        assertEquals(100L, response.getBody().get("totalElements"));
        assertEquals(2, response.getBody().get("totalPages"));
    }

    @Test
    @DisplayName("Should format timestamps in CSV export")
    void shouldFormatTimestampsInCsvExport() {
        // Given
        ErrorLog error = ErrorLog.create(testSiteId, testBatchId, "Error", "Type", "Message", null, null, null);
        List<ErrorLog> errors = Arrays.asList(error);

        when(errorLogRepository.exportByFilters(any(), any(), any(), any())).thenReturn(errors);

        // When
        ResponseEntity<String> response = controller.exportErrors(null, null, null, null);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        // Timestamp format: yyyy-MM-dd HH:mm:ss
        assertTrue(response.getBody().matches("(?s).*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.*"));
    }
}
