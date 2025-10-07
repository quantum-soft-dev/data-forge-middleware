package com.bitbi.dfm.error.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ErrorLog entity.
 */
@DisplayName("ErrorLog Entity Unit Tests")
class ErrorLogTest {

    private final UUID testSiteId = UUID.randomUUID();
    private final UUID testBatchId = UUID.randomUUID();

    @Test
    @DisplayName("Should create ErrorLog with all fields")
    void shouldCreateErrorLogWithAllFields() {
        // Given
        String type = "ValidationError";
        String title = "Invalid Input";
        String message = "Field 'email' is required";
        String stackTrace = "at com.example.Validator.validate(Validator.java:42)";
        String clientVersion = "1.2.3";
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("field", "email");
        metadata.put("userId", 12345);

        // When
        ErrorLog errorLog = ErrorLog.create(testSiteId, testBatchId, type, title, message,
                stackTrace, clientVersion, metadata);

        // Then
        assertNotNull(errorLog);
        assertNotNull(errorLog.getId());
        assertEquals(testSiteId, errorLog.getSiteId());
        assertEquals(testBatchId, errorLog.getBatchId());
        assertEquals(type, errorLog.getType());
        assertEquals(title, errorLog.getTitle());
        assertEquals(message, errorLog.getMessage());
        assertEquals(stackTrace, errorLog.getStackTrace());
        assertEquals(clientVersion, errorLog.getClientVersion());
        assertEquals(metadata, errorLog.getMetadata());
        assertNotNull(errorLog.getOccurredAt());
        assertNotNull(errorLog.getCreatedAt());
    }

    @Test
    @DisplayName("Should create ErrorLog with minimal fields")
    void shouldCreateErrorLogWithMinimalFields() {
        // Given
        String type = "RuntimeError";
        String title = "Unexpected Error";
        String message = "Something went wrong";

        // When
        ErrorLog errorLog = ErrorLog.create(testSiteId, null, type, title, message,
                null, null, null);

        // Then
        assertNotNull(errorLog);
        assertNotNull(errorLog.getId());
        assertEquals(testSiteId, errorLog.getSiteId());
        assertNull(errorLog.getBatchId());
        assertEquals(type, errorLog.getType());
        assertEquals(title, errorLog.getTitle());
        assertEquals(message, errorLog.getMessage());
        assertNull(errorLog.getStackTrace());
        assertNull(errorLog.getClientVersion());
        assertNull(errorLog.getMetadata());
        assertNotNull(errorLog.getOccurredAt());
        assertNotNull(errorLog.getCreatedAt());
    }

    @Test
    @DisplayName("Should throw exception when siteId is null")
    void shouldThrowExceptionWhenSiteIdIsNull() {
        // When & Then
        assertThrows(NullPointerException.class, () ->
                ErrorLog.create(null, testBatchId, "Error", "Title", "Message",
                        null, null, null)
        );
    }

    @Test
    @DisplayName("Should throw exception when type is null")
    void shouldThrowExceptionWhenTypeIsNull() {
        // When & Then
        assertThrows(NullPointerException.class, () ->
                ErrorLog.create(testSiteId, testBatchId, null, "Title", "Message",
                        null, null, null)
        );
    }

    @Test
    @DisplayName("Should throw exception when title is null")
    void shouldThrowExceptionWhenTitleIsNull() {
        // When & Then
        assertThrows(NullPointerException.class, () ->
                ErrorLog.create(testSiteId, testBatchId, "Error", null, "Message",
                        null, null, null)
        );
    }

    @Test
    @DisplayName("Should throw exception when message is null")
    void shouldThrowExceptionWhenMessageIsNull() {
        // When & Then
        assertThrows(NullPointerException.class, () ->
                ErrorLog.create(testSiteId, testBatchId, "Error", "Title", null,
                        null, null, null)
        );
    }

    @Test
    @DisplayName("Should generate unique IDs for each ErrorLog")
    void shouldGenerateUniqueIdsForEachErrorLog() {
        // When
        ErrorLog errorLog1 = ErrorLog.create(testSiteId, testBatchId, "Error", "Title", "Message",
                null, null, null);
        ErrorLog errorLog2 = ErrorLog.create(testSiteId, testBatchId, "Error", "Title", "Message",
                null, null, null);

        // Then
        assertNotEquals(errorLog1.getId(), errorLog2.getId());
    }

    @Test
    @DisplayName("Should set occurredAt and createdAt to current time")
    void shouldSetTimestampsToCurrentTime() {
        // Given
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        // When
        ErrorLog errorLog = ErrorLog.create(testSiteId, testBatchId, "Error", "Title", "Message",
                null, null, null);

        // Then
        LocalDateTime after = LocalDateTime.now().plusSeconds(1);
        assertTrue(errorLog.getOccurredAt().isAfter(before));
        assertTrue(errorLog.getOccurredAt().isBefore(after));
        assertTrue(errorLog.getCreatedAt().isAfter(before));
        assertTrue(errorLog.getCreatedAt().isBefore(after));
    }

    @Test
    @DisplayName("Should support complex metadata structure")
    void shouldSupportComplexMetadataStructure() {
        // Given
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("userId", 12345);
        metadata.put("userName", "john.doe");
        metadata.put("ipAddress", "192.168.1.1");
        metadata.put("userAgent", "Mozilla/5.0");
        Map<String, Object> context = new HashMap<>();
        context.put("page", "/checkout");
        context.put("action", "submit-payment");
        metadata.put("context", context);

        // When
        ErrorLog errorLog = ErrorLog.create(testSiteId, testBatchId, "Error", "Title", "Message",
                null, null, metadata);

        // Then
        assertNotNull(errorLog.getMetadata());
        assertEquals(5, errorLog.getMetadata().size());
        assertEquals(12345, errorLog.getMetadata().get("userId"));
        assertTrue(errorLog.getMetadata().get("context") instanceof Map);
    }

    @Test
    @DisplayName("Should implement equals correctly based on ID")
    void shouldImplementEqualsCorrectlyBasedOnId() {
        // Given
        ErrorLog errorLog1 = ErrorLog.create(testSiteId, testBatchId, "Error", "Title", "Message",
                null, null, null);
        ErrorLog errorLog2 = ErrorLog.create(testSiteId, testBatchId, "Error", "Title", "Message",
                null, null, null);

        // Then
        assertEquals(errorLog1, errorLog1);
        assertNotEquals(errorLog1, errorLog2);
        assertNotEquals(errorLog1, null);
        assertNotEquals(errorLog1, new Object());
    }

    @Test
    @DisplayName("Should implement hashCode correctly based on ID")
    void shouldImplementHashCodeCorrectlyBasedOnId() {
        // Given
        ErrorLog errorLog1 = ErrorLog.create(testSiteId, testBatchId, "Error", "Title", "Message",
                null, null, null);
        ErrorLog errorLog2 = ErrorLog.create(testSiteId, testBatchId, "Error", "Title", "Message",
                null, null, null);

        // Then
        assertEquals(errorLog1.hashCode(), errorLog1.hashCode());
        assertNotEquals(errorLog1.hashCode(), errorLog2.hashCode());
    }

    @Test
    @DisplayName("Should allow batch ID to be null for non-batch errors")
    void shouldAllowBatchIdToBeNullForNonBatchErrors() {
        // When
        ErrorLog errorLog = ErrorLog.create(testSiteId, null, "Error", "Title", "Message",
                null, null, null);

        // Then
        assertNull(errorLog.getBatchId());
    }

    @Test
    @DisplayName("Should preserve all optional fields when provided")
    void shouldPreserveAllOptionalFieldsWhenProvided() {
        // Given
        String stackTrace = "java.lang.NullPointerException\n\tat com.example.Service.process(Service.java:123)";
        String clientVersion = "2.0.0-beta";
        Map<String, Object> metadata = Map.of("key", "value");

        // When
        ErrorLog errorLog = ErrorLog.create(testSiteId, testBatchId, "Error", "Title", "Message",
                stackTrace, clientVersion, metadata);

        // Then
        assertEquals(stackTrace, errorLog.getStackTrace());
        assertEquals(clientVersion, errorLog.getClientVersion());
        assertEquals(metadata, errorLog.getMetadata());
    }

    @Test
    @DisplayName("Should handle long messages and stack traces")
    void shouldHandleLongMessagesAndStackTraces() {
        // Given
        String longMessage = "Error: ".repeat(500); // Very long message
        String longStackTrace = "at com.example.Class.method(File.java:1)\n".repeat(100);

        // When
        ErrorLog errorLog = ErrorLog.create(testSiteId, testBatchId, "Error", "Title", longMessage,
                longStackTrace, null, null);

        // Then
        assertEquals(longMessage, errorLog.getMessage());
        assertEquals(longStackTrace, errorLog.getStackTrace());
    }

    @Test
    @DisplayName("Should handle empty metadata map")
    void shouldHandleEmptyMetadataMap() {
        // Given
        Map<String, Object> emptyMetadata = new HashMap<>();

        // When
        ErrorLog errorLog = ErrorLog.create(testSiteId, testBatchId, "Error", "Title", "Message",
                null, null, emptyMetadata);

        // Then
        assertNotNull(errorLog.getMetadata());
        assertTrue(errorLog.getMetadata().isEmpty());
    }
}
