package com.bitbi.dfm.comparison.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Paginated response wrapper for comparison result listings.
 *
 * <p>This DTO wraps a list of comparison results (individual file diffs)
 * with pagination metadata.
 *
 * @param content list of comparison results in the current page
 * @param page current page number (0-indexed)
 * @param size page size (number of items per page)
 * @param totalElements total number of results across all pages
 * @param totalPages total number of pages
 */
@Schema(description = "Paginated list of file comparison results")
public record PagedComparisonResultResponse(
    @Schema(description = "List of comparison results in the current page")
    List<ComparisonResultDto> content,

    @Schema(description = "Current page number (0-indexed)", example = "0")
    int page,

    @Schema(description = "Page size", example = "50")
    int size,

    @Schema(description = "Total number of results", example = "150")
    long totalElements,

    @Schema(description = "Total number of pages", example = "3")
    int totalPages
) {
    /**
     * Converts a Spring Data Page to a paginated response DTO.
     *
     * @param page the Spring Data Page object
     * @return the corresponding paginated response
     */
    public static PagedComparisonResultResponse fromPage(Page<ComparisonResultDto> page) {
        return new PagedComparisonResultResponse(
            page.getContent(),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements(),
            page.getTotalPages()
        );
    }
}
