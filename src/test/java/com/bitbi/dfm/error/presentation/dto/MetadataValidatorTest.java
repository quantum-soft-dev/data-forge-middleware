package com.bitbi.dfm.error.presentation.dto;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MetadataValidator.
 */
@DisplayName("MetadataValidator Unit Tests")
class MetadataValidatorTest {

    private MetadataValidator validator;
    private ConstraintValidatorContext context;
    private ConstraintValidatorContext.ConstraintViolationBuilder violationBuilder;

    @BeforeEach
    void setUp() {
        validator = new MetadataValidator();
        context = mock(ConstraintValidatorContext.class);
        violationBuilder = mock(ConstraintValidatorContext.ConstraintViolationBuilder.class);

        when(context.buildConstraintViolationWithTemplate(anyString())).thenReturn(violationBuilder);
        when(violationBuilder.addConstraintViolation()).thenReturn(context);

        // Initialize with default values
        ValidMetadata annotation = mock(ValidMetadata.class);
        when(annotation.maxEntries()).thenReturn(20);
        when(annotation.maxKeyLength()).thenReturn(100);
        when(annotation.maxTotalSize()).thenReturn(10240); // 10KB

        validator.initialize(annotation);
    }

    @Test
    @DisplayName("Should accept null metadata")
    void shouldAcceptNullMetadata() {
        // When
        boolean result = validator.isValid(null, context);

        // Then
        assertTrue(result);
        verify(context, never()).disableDefaultConstraintViolation();
    }

    @Test
    @DisplayName("Should accept empty metadata")
    void shouldAcceptEmptyMetadata() {
        // Given
        Map<String, Object> metadata = new HashMap<>();

        // When
        boolean result = validator.isValid(metadata, context);

        // Then
        assertTrue(result);
        verify(context, never()).disableDefaultConstraintViolation();
    }

    @Test
    @DisplayName("Should accept valid metadata")
    void shouldAcceptValidMetadata() {
        // Given
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fileName", "test.csv");
        metadata.put("fileSize", 1024);
        metadata.put("lineNumber", 42);

        // When
        boolean result = validator.isValid(metadata, context);

        // Then
        assertTrue(result);
        verify(context, never()).disableDefaultConstraintViolation();
    }

    @Test
    @DisplayName("Should reject metadata with too many entries")
    void shouldRejectMetadataWithTooManyEntries() {
        // Given: 21 entries (exceeds max of 20)
        Map<String, Object> metadata = new HashMap<>();
        for (int i = 1; i <= 21; i++) {
            metadata.put("key" + i, "value" + i);
        }

        // When
        boolean result = validator.isValid(metadata, context);

        // Then
        assertFalse(result);
        verify(context).disableDefaultConstraintViolation();
        verify(context).buildConstraintViolationWithTemplate(
                argThat(msg -> msg.contains("cannot contain more than 20 entries"))
        );
    }

    @Test
    @DisplayName("Should reject metadata with null key")
    void shouldRejectMetadataWithNullKey() {
        // Given
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(null, "value");

        // When
        boolean result = validator.isValid(metadata, context);

        // Then
        assertFalse(result);
        verify(context).disableDefaultConstraintViolation();
        verify(context).buildConstraintViolationWithTemplate(
                argThat(msg -> msg.contains("cannot be null or empty"))
        );
    }

    @Test
    @DisplayName("Should reject metadata with empty key")
    void shouldRejectMetadataWithEmptyKey() {
        // Given
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("", "value");

        // When
        boolean result = validator.isValid(metadata, context);

        // Then
        assertFalse(result);
        verify(context).disableDefaultConstraintViolation();
        verify(context).buildConstraintViolationWithTemplate(
                argThat(msg -> msg.contains("cannot be null or empty"))
        );
    }

    @Test
    @DisplayName("Should reject metadata with key exceeding max length")
    void shouldRejectMetadataWithKeyExceedingMaxLength() {
        // Given: Key with 101 characters (exceeds max of 100)
        String longKey = "a".repeat(101);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(longKey, "value");

        // When
        boolean result = validator.isValid(metadata, context);

        // Then
        assertFalse(result);
        verify(context).disableDefaultConstraintViolation();
        verify(context).buildConstraintViolationWithTemplate(
                argThat(msg -> msg.contains("exceeds maximum length of 100 characters"))
        );
    }

    @Test
    @DisplayName("Should reject metadata exceeding total size limit")
    void shouldRejectMetadataExceedingTotalSizeLimit() {
        // Given: Large metadata exceeding 10KB
        Map<String, Object> metadata = new HashMap<>();
        String largeValue = "x".repeat(5000);
        metadata.put("key1", largeValue);
        metadata.put("key2", largeValue);
        metadata.put("key3", largeValue); // Total > 10KB

        // When
        boolean result = validator.isValid(metadata, context);

        // Then
        assertFalse(result);
        verify(context).disableDefaultConstraintViolation();
        verify(context).buildConstraintViolationWithTemplate(
                argThat(msg -> msg.contains("exceeds maximum allowed size"))
        );
    }

    @Test
    @DisplayName("Should reject metadata with non-serializable values")
    void shouldRejectMetadataWithNonSerializableValues() {
        // Given: Metadata with circular reference (non-serializable)
        Map<String, Object> metadata = new HashMap<>();
        Map<String, Object> circular = new HashMap<>();
        circular.put("self", circular); // Circular reference
        metadata.put("circular", circular);

        // When
        boolean result = validator.isValid(metadata, context);

        // Then
        assertFalse(result);
        verify(context).disableDefaultConstraintViolation();
        verify(context).buildConstraintViolationWithTemplate(
                argThat(msg -> msg.contains("invalid or non-serializable"))
        );
    }

    @Test
    @DisplayName("Should accept metadata at exactly max entries limit")
    void shouldAcceptMetadataAtExactlyMaxEntriesLimit() {
        // Given: Exactly 20 entries
        Map<String, Object> metadata = new HashMap<>();
        for (int i = 1; i <= 20; i++) {
            metadata.put("key" + i, "value" + i);
        }

        // When
        boolean result = validator.isValid(metadata, context);

        // Then
        assertTrue(result);
        verify(context, never()).disableDefaultConstraintViolation();
    }

    @Test
    @DisplayName("Should accept metadata with nested objects")
    void shouldAcceptMetadataWithNestedObjects() {
        // Given
        Map<String, Object> metadata = new HashMap<>();
        Map<String, Object> nested = new HashMap<>();
        nested.put("nested1", "value1");
        nested.put("nested2", 123);
        metadata.put("parent", nested);

        // When
        boolean result = validator.isValid(metadata, context);

        // Then
        assertTrue(result);
        verify(context, never()).disableDefaultConstraintViolation();
    }
}
