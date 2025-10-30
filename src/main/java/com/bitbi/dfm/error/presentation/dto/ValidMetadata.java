package com.bitbi.dfm.error.presentation.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * Validates metadata map to prevent injection attacks and resource exhaustion.
 * <p>
 * Constraints:
 * - Maximum 20 key-value pairs
 * - Each key must be non-empty and max 100 characters
 * - Total serialized size must not exceed 10KB
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MetadataValidator.class)
@Documented
public @interface ValidMetadata {

    String message() default "Invalid metadata: exceeds size limits or contains invalid keys";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /**
     * Maximum number of key-value pairs allowed in metadata.
     */
    int maxEntries() default 20;

    /**
     * Maximum length for each metadata key.
     */
    int maxKeyLength() default 100;

    /**
     * Maximum total size of serialized metadata in bytes.
     */
    int maxTotalSize() default 10240; // 10KB
}
