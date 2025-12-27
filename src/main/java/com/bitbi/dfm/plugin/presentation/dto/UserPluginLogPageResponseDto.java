package com.bitbi.dfm.plugin.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Paginated response DTO for user plugin logs.
 *
 * @param content       list of log entries on current page
 * @param page          current page number (0-indexed)
 * @param size          page size
 * @param totalElements total number of log entries
 * @param totalPages    total number of pages
 */
@Schema(description = "Paginated list of plugin log entries")
public record UserPluginLogPageResponseDto(
        @Schema(description = "List of log entries")
        List<UserPluginLogDto> content,

        @Schema(description = "Current page number (0-indexed)", example = "0")
        int page,

        @Schema(description = "Page size", example = "20")
        int size,

        @Schema(description = "Total number of log entries", example = "150")
        long totalElements,

        @Schema(description = "Total number of pages", example = "8")
        int totalPages
) {
    /**
     * Creates a response DTO from a Spring Data Page.
     */
    public static UserPluginLogPageResponseDto fromPage(Page<UserPluginLogDto> page) {
        return new UserPluginLogPageResponseDto(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
