package com.bitbi.dfm.upload.presentation.dto;

import com.bitbi.dfm.site.domain.SiteSchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Response DTO for schema upload and retrieval.
 *
 * @param siteId        site identifier
 * @param schemaVersion current schema version number
 * @param updatedAt     timestamp of last schema update
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Schema(description = "Schema upload result")
public record SchemaResponseDto(

        @Schema(description = "Site identifier")
        UUID siteId,

        @Schema(description = "Schema version number (increments on each update)")
        Integer schemaVersion,

        @Schema(description = "Timestamp of last schema update")
        Instant updatedAt

) {

    /**
     * Convert SiteSchema entity to response DTO.
     *
     * @param schema the site schema entity
     * @return response DTO
     */
    public static SchemaResponseDto fromEntity(SiteSchema schema) {
        return new SchemaResponseDto(
                schema.getSiteId(),
                schema.getSchemaVersion(),
                schema.getUpdatedAt().toInstant(ZoneOffset.UTC)
        );
    }
}
