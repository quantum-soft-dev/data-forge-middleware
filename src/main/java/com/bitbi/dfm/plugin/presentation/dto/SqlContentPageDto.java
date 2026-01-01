package com.bitbi.dfm.plugin.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * DTO for paginated SQL content response.
 * Contains SQL statements split by delimiter for preview display.
 */
@Schema(description = "Paginated SQL content for preview")
public record SqlContentPageDto(
        @Schema(description = "Generation ID")
        UUID generationId,

        @Schema(description = "Current page number (0-based)")
        int page,

        @Schema(description = "Number of statements per page")
        int pageSize,

        @Schema(description = "Total number of pages")
        int totalPages,

        @Schema(description = "Total number of SQL statements")
        int totalStatements,

        @Schema(description = "SQL statements for the current page")
        List<String> statements,

        @Schema(description = "Whether there is a next page")
        boolean hasNext,

        @Schema(description = "Whether there is a previous page")
        boolean hasPrevious
) {
    /**
     * Creates a SqlContentPageDto from parsed SQL statements.
     *
     * @param generationId the ID of the SQL generation
     * @param allStatements all SQL statements from the file
     * @param page the current page number (0-based)
     * @param pageSize the number of statements per page
     * @return the DTO with paginated statements
     */
    public static SqlContentPageDto create(
            UUID generationId,
            List<String> allStatements,
            int page,
            int pageSize
    ) {
        int totalStatements = allStatements.size();
        int totalPages = (int) Math.ceil((double) totalStatements / pageSize);

        // Handle empty case
        if (totalStatements == 0) {
            return new SqlContentPageDto(
                    generationId, page, pageSize, 0, 0,
                    List.of(), false, false
            );
        }

        // Calculate page boundaries
        int startIndex = page * pageSize;
        int endIndex = Math.min(startIndex + pageSize, totalStatements);

        // Handle out of bounds
        if (startIndex >= totalStatements) {
            return new SqlContentPageDto(
                    generationId, page, pageSize, totalPages, totalStatements,
                    List.of(), false, page > 0
            );
        }

        List<String> pageStatements = allStatements.subList(startIndex, endIndex);

        return new SqlContentPageDto(
                generationId,
                page,
                pageSize,
                totalPages,
                totalStatements,
                pageStatements,
                endIndex < totalStatements,
                page > 0
        );
    }
}
