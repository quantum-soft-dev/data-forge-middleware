package com.bitbi.dfm.error.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Request DTO for logging an error.
 * <p>
 * Replaces Map<String, Object> input for error logging endpoint.
 * Provides type safety and automatic validation via Jakarta Bean Validation.
 * </p>
 *
 * @param type     Error type/category (max 100 characters, required)
 * @param message  Error message description (max 1000 characters, required)
 * @param metadata Optional additional error context (arbitrary JSON object)
 * @author Data Forge Team
 * @version 1.0.0
 * @see com.bitbi.dfm.error.presentation.ErrorLogController
 */
@Schema(description = "Request body for logging an error")
public record LogErrorRequestDto(

        @Schema(
                description = "Error type/category (e.g., UPLOAD_FAILED, VALIDATION_ERROR)",
                example = "UPLOAD_FAILED",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "Error type is required")
        @Size(max = 100, message = "Error type must be at most 100 characters")
        String type,

        @Schema(
                description = "Error message description",
                example = "File upload failed: connection timeout",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "Error message is required")
        @Size(max = 1000, message = "Error message must be at most 1000 characters")
        String message,

        @Schema(
                description = "Optional additional error context (max 20 entries, 10KB total size)",
                example = "{\"fileName\": \"data.csv\", \"fileSize\": 1024}",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        @ValidMetadata
        Map<String, Object> metadata
) {
}
