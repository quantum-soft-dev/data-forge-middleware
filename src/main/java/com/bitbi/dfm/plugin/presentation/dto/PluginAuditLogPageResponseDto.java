package com.bitbi.dfm.plugin.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Paginated response DTO for plugin audit log queries.
 *
 * <p>Wraps a page of PluginAuditLogEntryDto for REST API responses.</p>
 *
 * <p>User Story 6 (Phase 8) - Admin Views Plugin Audit Trail</p>
 */
@Schema(description = "Paginated plugin audit log response")
public record PluginAuditLogPageResponseDto(
        @Schema(description = "List of audit log entries")
        List<PluginAuditLogEntryDto> content,

        @Schema(description = "Current page number (0-indexed)", example = "0")
        int page,

        @Schema(description = "Page size", example = "50")
        int size,

        @Schema(description = "Total number of elements", example = "1234")
        long totalElements,

        @Schema(description = "Total number of pages", example = "25")
        int totalPages
) {

    /**
     * Creates a paginated response from a Spring Data Page.
     *
     * @param page the page of audit log entities
     * @return the paginated response DTO
     */
    public static PluginAuditLogPageResponseDto fromPage(Page<PluginAuditLogEntryDto> page) {
        return new PluginAuditLogPageResponseDto(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
