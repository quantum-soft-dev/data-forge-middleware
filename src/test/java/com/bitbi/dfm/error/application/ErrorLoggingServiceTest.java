package com.bitbi.dfm.error.application;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.error.domain.ErrorLog;
import com.bitbi.dfm.error.domain.ErrorLogRepository;
import com.bitbi.dfm.error.domain.ErrorSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Modifier;
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
    private BatchRepository batchRepository;

    private UUID testSiteId;
    private UUID testBatchId;
    private UUID testErrorId;

    @BeforeEach
    void setUp() {
        errorLogRepository = mock(ErrorLogRepository.class);
        batchLifecycleService = mock(BatchLifecycleService.class);
        batchRepository = mock(BatchRepository.class);
        errorLoggingService = new ErrorLoggingService(errorLogRepository, batchLifecycleService, batchRepository);

        testSiteId = UUID.randomUUID();
        testBatchId = UUID.randomUUID();
        testErrorId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Should log error with batch, store its severity and update hasErrors flag")
    void shouldLogErrorWithBatchAndUpdateHasErrorsFlag() {
        // Given
        String type = "ValidationError";
        String message = "Invalid data format";
        Map<String, Object> metadata = Map.of("field", "amount", "value", "abc");

        when(errorLogRepository.save(any(ErrorLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        ErrorLog result = errorLoggingService.logError(testBatchId, testSiteId, type, message, metadata,
                ErrorSeverity.WARNING);

        // Then
        assertNotNull(result);
        ArgumentCaptor<ErrorLog> captor = ArgumentCaptor.forClass(ErrorLog.class);
        verify(errorLogRepository, times(1)).save(captor.capture());
        assertEquals(testBatchId, captor.getValue().getBatchId());
        assertEquals(ErrorSeverity.WARNING, captor.getValue().getSeverity());
        verify(batchLifecycleService, times(1)).markBatchHasErrors(testBatchId);
    }

    @Test
    @DisplayName("Should log error without metadata")
    void shouldLogErrorWithoutMetadata() {
        // Given
        String type = "NetworkError";
        String message = "Connection timeout";

        when(errorLogRepository.save(any(ErrorLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        ErrorLog result = errorLoggingService.logError(testBatchId, testSiteId, type, message, null,
                ErrorSeverity.CRITICAL);

        // Then
        assertNotNull(result);
        ArgumentCaptor<ErrorLog> captor = ArgumentCaptor.forClass(ErrorLog.class);
        verify(errorLogRepository, times(1)).save(captor.capture());
        assertNull(captor.getValue().getMetadata());
        assertEquals(ErrorSeverity.CRITICAL, captor.getValue().getSeverity());
        verify(batchLifecycleService, times(1)).markBatchHasErrors(testBatchId);
    }

    @Test
    @DisplayName("Should log standalone error without batch association")
    void shouldLogStandaloneErrorWithoutBatchAssociation() {
        // Given
        String type = "ConfigurationError";
        String message = "Missing configuration file";
        Map<String, Object> metadata = Map.of("file", "config.ini");

        when(errorLogRepository.save(any(ErrorLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        ErrorLog result = errorLoggingService.logStandaloneError(testSiteId, type, message, metadata,
                ErrorSeverity.INFO);

        // Then
        assertNotNull(result);
        ArgumentCaptor<ErrorLog> captor = ArgumentCaptor.forClass(ErrorLog.class);
        verify(errorLogRepository, times(1)).save(captor.capture());
        assertNull(captor.getValue().getBatchId());
        assertEquals(ErrorSeverity.INFO, captor.getValue().getSeverity());
        verify(batchLifecycleService, never()).markBatchHasErrors(any());
    }

    @Test
    @DisplayName("Should log standalone error without metadata or severity as ERROR")
    void shouldLogStandaloneErrorWithoutMetadata() {
        // Given
        String type = "AuthenticationError";
        String message = "Invalid credentials";

        when(errorLogRepository.save(any(ErrorLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When: a caller with no severity to pass says so with null, and gets ERROR from ErrorLog.create
        ErrorLog result = errorLoggingService.logStandaloneError(testSiteId, type, message, null, null);

        // Then
        assertNotNull(result);
        ArgumentCaptor<ErrorLog> captor = ArgumentCaptor.forClass(ErrorLog.class);
        verify(errorLogRepository, times(1)).save(captor.capture());
        assertNull(captor.getValue().getMetadata());
        assertEquals(ErrorSeverity.ERROR, captor.getValue().getSeverity());
        verify(batchLifecycleService, never()).markBatchHasErrors(any());
    }

    /**
     * Every public method that writes an error log takes the severity as a parameter (issue #326).
     * <p>
     * An overload without it, whose body substitutes {@link ErrorSeverity#ERROR}, was how #321
     * happened: the device controller picked the shorter signature, both compiled, and the
     * client's severity was dropped silently. A caller that does not care passes {@code null},
     * which {@link ErrorLog#create} reads as ERROR — at the call site, not behind it.
     * </p>
     */
    @Test
    @DisplayName("Every public log method takes the severity rather than substituting one")
    void everyPublicLogMethodTakesTheSeverity() {
        List<String> withoutSeverity = Arrays.stream(ErrorLoggingService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getName().startsWith("log"))
                .filter(method -> !Arrays.asList(method.getParameterTypes()).contains(ErrorSeverity.class))
                .map(method -> method.getName() + Arrays.toString(method.getParameterTypes()))
                .sorted()
                .toList();

        assertEquals(List.of(), withoutSeverity,
                "These ErrorLoggingService methods write an error log without taking its severity, "
                        + "so a caller can drop the client's severity without noticing (#321)");
    }

    @Test
    @DisplayName("Should get error log by ID")
    void shouldGetErrorLogById() {
        // Given
        ErrorLog errorLog = ErrorLog.create(testSiteId, testBatchId, "TestError", "TestError", "Test message",
                null, null, null, ErrorSeverity.ERROR);
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
                ErrorLog.create(testSiteId, testBatchId, "Error1", "Error1", "Message 1",
                        null, null, null, ErrorSeverity.ERROR),
                ErrorLog.create(testSiteId, testBatchId, "Error2", "Error2", "Message 2",
                        null, null, null, ErrorSeverity.ERROR)
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
                ErrorLog.create(testSiteId, testBatchId, "Error1", "Error1", "Message 1",
                        null, null, null, ErrorSeverity.ERROR),
                ErrorLog.create(testSiteId, null, "Error2", "Error2", "Message 2",
                        null, null, null, ErrorSeverity.ERROR)
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
