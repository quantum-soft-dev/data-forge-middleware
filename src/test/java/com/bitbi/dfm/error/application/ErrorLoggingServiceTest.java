package com.bitbi.dfm.error.application;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.error.domain.ErrorLog;
import com.bitbi.dfm.error.domain.ErrorLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ErrorLoggingService.
 */
@DisplayName("ErrorLoggingService Unit Tests")
class ErrorLoggingServiceTest {

    private ErrorLoggingService errorLoggingService;
    private ErrorLogRepository errorLogRepository;
    private BatchLifecycleService batchLifecycleService;

    private UUID testSiteId;
    private UUID testBatchId;
    private UUID testErrorId;

    @BeforeEach
    void setUp() {
        errorLogRepository = mock(ErrorLogRepository.class);
        batchLifecycleService = mock(BatchLifecycleService.class);
        errorLoggingService = new ErrorLoggingService(errorLogRepository, batchLifecycleService);

        testSiteId = UUID.randomUUID();
        testBatchId = UUID.randomUUID();
        testErrorId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Should log error with batch and update hasErrors flag")
    void shouldLogErrorWithBatchAndUpdateHasErrorsFlag() {
        // Given
        String type = "ValidationError";
        String message = "Invalid data format";
        Map<String, Object> metadata = Map.of("field", "amount", "value", "abc");

        ErrorLog savedErrorLog = ErrorLog.create(testSiteId, testBatchId, type, type, message, null, null, metadata);
        when(errorLogRepository.save(any(ErrorLog.class))).thenReturn(savedErrorLog);

        // When
        ErrorLog result = errorLoggingService.logError(testBatchId, testSiteId, type, message, metadata);

        // Then
        assertNotNull(result);
        verify(errorLogRepository, times(1)).save(any(ErrorLog.class));
        verify(batchLifecycleService, times(1)).markBatchHasErrors(testBatchId);
    }

    @Test
    @DisplayName("Should log error without metadata")
    void shouldLogErrorWithoutMetadata() {
        // Given
        String type = "NetworkError";
        String message = "Connection timeout";

        ErrorLog savedErrorLog = ErrorLog.create(testSiteId, testBatchId, type, type, message, null, null, null);
        when(errorLogRepository.save(any(ErrorLog.class))).thenReturn(savedErrorLog);

        // When
        ErrorLog result = errorLoggingService.logError(testBatchId, testSiteId, type, message);

        // Then
        assertNotNull(result);
        verify(errorLogRepository, times(1)).save(any(ErrorLog.class));
        verify(batchLifecycleService, times(1)).markBatchHasErrors(testBatchId);
    }

    @Test
    @DisplayName("Should log standalone error without batch association")
    void shouldLogStandaloneErrorWithoutBatchAssociation() {
        // Given
        String type = "ConfigurationError";
        String message = "Missing configuration file";
        Map<String, Object> metadata = Map.of("file", "config.ini");

        ErrorLog savedErrorLog = ErrorLog.create(testSiteId, null, type, type, message, null, null, metadata);
        when(errorLogRepository.save(any(ErrorLog.class))).thenReturn(savedErrorLog);

        // When
        ErrorLog result = errorLoggingService.logStandaloneError(testSiteId, type, message, metadata);

        // Then
        assertNotNull(result);
        ArgumentCaptor<ErrorLog> captor = ArgumentCaptor.forClass(ErrorLog.class);
        verify(errorLogRepository, times(1)).save(captor.capture());
        assertNull(captor.getValue().getBatchId());
        verify(batchLifecycleService, never()).markBatchHasErrors(any());
    }

    @Test
    @DisplayName("Should log standalone error without metadata")
    void shouldLogStandaloneErrorWithoutMetadata() {
        // Given
        String type = "AuthenticationError";
        String message = "Invalid credentials";

        ErrorLog savedErrorLog = ErrorLog.create(testSiteId, null, type, type, message, null, null, null);
        when(errorLogRepository.save(any(ErrorLog.class))).thenReturn(savedErrorLog);

        // When
        ErrorLog result = errorLoggingService.logStandaloneError(testSiteId, type, message);

        // Then
        assertNotNull(result);
        verify(errorLogRepository, times(1)).save(any(ErrorLog.class));
        verify(batchLifecycleService, never()).markBatchHasErrors(any());
    }

    @Test
    @DisplayName("Should get error log by ID")
    void shouldGetErrorLogById() {
        // Given
        ErrorLog errorLog = ErrorLog.create(testSiteId, testBatchId, "TestError", "TestError", "Test message", null, null, null);
        when(errorLogRepository.findById(testErrorId)).thenReturn(Optional.of(errorLog));

        // When
        ErrorLog result = errorLoggingService.getErrorLog(testErrorId);

        // Then
        assertNotNull(result);
        assertEquals(errorLog, result);
        verify(errorLogRepository, times(1)).findById(testErrorId);
    }

    @Test
    @DisplayName("Should throw exception when error log not found")
    void shouldThrowExceptionWhenErrorLogNotFound() {
        // Given
        when(errorLogRepository.findById(testErrorId)).thenReturn(Optional.empty());

        // When & Then
        assertThrows(ErrorLoggingService.ErrorLogNotFoundException.class, () -> {
            errorLoggingService.getErrorLog(testErrorId);
        });
        verify(errorLogRepository, times(1)).findById(testErrorId);
    }

    @Test
    @DisplayName("Should list errors by batch")
    void shouldListErrorsByBatch() {
        // Given
        List<ErrorLog> expectedErrors = Arrays.asList(
                ErrorLog.create(testSiteId, testBatchId, "Error1", "Error1", "Message 1", null, null, null),
                ErrorLog.create(testSiteId, testBatchId, "Error2", "Error2", "Message 2", null, null, null)
        );
        when(errorLogRepository.findByBatchId(testBatchId)).thenReturn(expectedErrors);

        // When
        List<ErrorLog> result = errorLoggingService.listErrorsByBatch(testBatchId);

        // Then
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(expectedErrors, result);
        verify(errorLogRepository, times(1)).findByBatchId(testBatchId);
    }

    @Test
    @DisplayName("Should list errors by site")
    void shouldListErrorsBySite() {
        // Given
        List<ErrorLog> expectedErrors = Arrays.asList(
                ErrorLog.create(testSiteId, testBatchId, "Error1", "Error1", "Message 1", null, null, null),
                ErrorLog.create(testSiteId, null, "Error2", "Error2", "Message 2", null, null, null)
        );
        when(errorLogRepository.findBySiteId(testSiteId)).thenReturn(expectedErrors);

        // When
        List<ErrorLog> result = errorLoggingService.listErrorsBySite(testSiteId);

        // Then
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(expectedErrors, result);
        verify(errorLogRepository, times(1)).findBySiteId(testSiteId);
    }

    @Test
    @DisplayName("Should count errors by batch")
    void shouldCountErrorsByBatch() {
        // Given
        long expectedCount = 5L;
        when(errorLogRepository.countByBatchId(testBatchId)).thenReturn(expectedCount);

        // When
        long result = errorLoggingService.countErrorsByBatch(testBatchId);

        // Then
        assertEquals(expectedCount, result);
        verify(errorLogRepository, times(1)).countByBatchId(testBatchId);
    }

    @Test
    @DisplayName("Should return empty list when no errors for batch")
    void shouldReturnEmptyListWhenNoErrorsForBatch() {
        // Given
        when(errorLogRepository.findByBatchId(testBatchId)).thenReturn(Collections.emptyList());

        // When
        List<ErrorLog> result = errorLoggingService.listErrorsByBatch(testBatchId);

        // Then
        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(errorLogRepository, times(1)).findByBatchId(testBatchId);
    }

    @Test
    @DisplayName("Should return zero count when no errors for batch")
    void shouldReturnZeroCountWhenNoErrorsForBatch() {
        // Given
        when(errorLogRepository.countByBatchId(testBatchId)).thenReturn(0L);

        // When
        long result = errorLoggingService.countErrorsByBatch(testBatchId);

        // Then
        assertEquals(0L, result);
        verify(errorLogRepository, times(1)).countByBatchId(testBatchId);
    }

    @Test
    @DisplayName("ErrorLogNotFoundException should have correct message")
    void errorLogNotFoundExceptionShouldHaveCorrectMessage() {
        // Given
        String expectedMessage = "Error log not found: " + testErrorId;

        // When
        ErrorLoggingService.ErrorLogNotFoundException exception =
                new ErrorLoggingService.ErrorLogNotFoundException(expectedMessage);

        // Then
        assertEquals(expectedMessage, exception.getMessage());
    }
}
