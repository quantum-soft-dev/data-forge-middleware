package com.bitbi.dfm.plugin.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Paginated response wrapper for account plugin summaries.
 *
 * <p>Used in the GET /api/v1/account/plugins endpoint to return a paginated
 * list of plugin integrations for the authenticated account.</p>
 *
 * @see AccountPluginSummaryDto
 */
@Schema(description = "Paginated list of account plugin integrations")
public record AccountPluginListResponseDto(
    @Schema(description = "List of plugin summaries for the current page")
    List<AccountPluginSummaryDto> content,

    @Schema(description = "Current page number (0-indexed)", example = "0")
    Integer page,

    @Schema(description = "Page size", example = "20")
    Integer size,

    @Schema(description = "Total number of elements across all pages", example = "5")
    Long totalElements,

    @Schema(description = "Total number of pages", example = "1")
    Integer totalPages
) {
    /**
     * Creates a response DTO with pagination metadata.
     *
     * @param content the list of plugin summaries for the current page
     * @param page the current page number (0-indexed)
     * @param size the page size
     * @param totalElements the total number of elements across all pages
     * @param totalPages the total number of pages
     * @return paginated response DTO
     */
    public static AccountPluginListResponseDto of(
            List<AccountPluginSummaryDto> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
        return new AccountPluginListResponseDto(content, page, size, totalElements, totalPages);
    }
}
