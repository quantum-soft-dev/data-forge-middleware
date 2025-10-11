package com.bitbi.dfm.error.presentation.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Validator for {@link ValidMetadata} annotation.
 * <p>
 * Validates metadata maps to prevent:
 * - Resource exhaustion (too many entries)
 * - Injection attacks (malicious keys)
 * - Memory issues (oversized values)
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public class MetadataValidator implements ConstraintValidator<ValidMetadata, Map<String, Object>> {

    private static final Logger logger = LoggerFactory.getLogger(MetadataValidator.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private int maxEntries;
    private int maxKeyLength;
    private int maxTotalSize;

    @Override
    public void initialize(ValidMetadata constraintAnnotation) {
        this.maxEntries = constraintAnnotation.maxEntries();
        this.maxKeyLength = constraintAnnotation.maxKeyLength();
        this.maxTotalSize = constraintAnnotation.maxTotalSize();
    }

    @Override
    public boolean isValid(Map<String, Object> metadata, ConstraintValidatorContext context) {
        // Null metadata is valid (optional field)
        if (metadata == null) {
            return true;
        }

        // Empty metadata is valid
        if (metadata.isEmpty()) {
            return true;
        }

        // Check number of entries
        if (metadata.size() > maxEntries) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                    "Metadata cannot contain more than " + maxEntries + " entries (found: " + metadata.size() + ")"
            ).addConstraintViolation();
            return false;
        }

        // Check key lengths and content
        for (String key : metadata.keySet()) {
            if (key == null || key.isEmpty()) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(
                        "Metadata keys cannot be null or empty"
                ).addConstraintViolation();
                return false;
            }

            if (key.length() > maxKeyLength) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(
                        "Metadata key '" + key + "' exceeds maximum length of " + maxKeyLength + " characters"
                ).addConstraintViolation();
                return false;
            }
        }

        // Check total serialized size
        try {
            String serialized = objectMapper.writeValueAsString(metadata);
            int size = serialized.getBytes().length;

            if (size > maxTotalSize) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(
                        "Metadata size (" + size + " bytes) exceeds maximum allowed size of " + maxTotalSize + " bytes"
                ).addConstraintViolation();
                return false;
            }
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize metadata for size validation: {}", e.getMessage());
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                    "Metadata contains invalid or non-serializable values"
            ).addConstraintViolation();
            return false;
        }

        return true;
    }
}
