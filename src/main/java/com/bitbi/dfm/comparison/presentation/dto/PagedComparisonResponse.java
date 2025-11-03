package com.bitbi.dfm.comparison.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Paginated response wrapper for comparison listings.
 *
 * <p>This DTO wraps a list of comparisons with pagination metadata,
 * following the standard pagination pattern used across the application.
 *
 * @param content list of comparisons in the current page
 * @param page current page number (0-indexed)
 * @param size page size (number of items per page)
 * @param totalElements total number of comparisons across all pages
 * @param totalPages total number of pages
 */
@Schema(description = "Paginated list of file comparisons")
public record PagedComparisonResponse(
    @Schema(description = "List of comparisons in the current page")
    List<ComparisonResponseDto> content,

    @Schema(description = "Current page number (0-indexed)", example = "0")
    int page,

    @Schema(description = "Page size", example = "20")
    int size,

    @Schema(description = "Total number of comparisons", example = "125")
    long totalElements,

    @Schema(description = "Total number of pages", example = "7")
    int totalPages
) {
    /**
     * Converts a Spring Data Page to a paginated response DTO.
     *
     * @param page the Spring Data Page object
     * @return the corresponding paginated response
     */
    public static PagedComparisonResponse fromPage(Page<ComparisonResponseDto> page) {
        return new PagedComparisonResponse(
            page.getContent(),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements(),
            page.getTotalPages()
        );
    }
}
