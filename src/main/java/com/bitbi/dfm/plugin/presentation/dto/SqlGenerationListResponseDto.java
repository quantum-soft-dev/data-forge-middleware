package com.bitbi.dfm.plugin.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * DTO for paginated SQL generation list response.
 */
@Schema(description = "Paginated list of SQL generations")
public record SqlGenerationListResponseDto(
        @Schema(description = "List of SQL generation summaries")
        List<SqlGenerationSummaryDto> content,

        @Schema(description = "Current page number (0-based)")
        int page,

        @Schema(description = "Page size")
        int size,

        @Schema(description = "Total number of elements across all pages")
        long totalElements,

        @Schema(description = "Total number of pages")
        int totalPages
) {
    /**
     * Creates a response DTO from a Spring Data Page.
     *
     * @param page the page of SQL generation summaries
     * @return the DTO
     */
    public static SqlGenerationListResponseDto fromPage(Page<SqlGenerationSummaryDto> page) {
        return new SqlGenerationListResponseDto(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
