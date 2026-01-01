package com.bitbi.dfm.plugin.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Paginated response for admin account-plugin listing.
 *
 * <p>Feature 014: Plugin History Management</p>
 */
@Schema(description = "Paginated list of account-plugin activations")
public record AdminAccountPluginPageDto(
    @Schema(description = "List of account-plugin activations")
    List<AdminAccountPluginDto> content,

    @Schema(description = "Current page number (0-indexed)")
    int page,

    @Schema(description = "Number of elements on this page")
    int size,

    @Schema(description = "Total number of elements")
    long totalElements,

    @Schema(description = "Total number of pages")
    int totalPages,

    @Schema(description = "Whether this is the first page")
    boolean first,

    @Schema(description = "Whether this is the last page")
    boolean last
) {
    /**
     * Creates from a Spring Data Page.
     */
    public static AdminAccountPluginPageDto fromPage(Page<AdminAccountPluginDto> page) {
        return new AdminAccountPluginPageDto(
            page.getContent(),
            page.getNumber(),
            page.getNumberOfElements(),
            page.getTotalElements(),
            page.getTotalPages(),
            page.isFirst(),
            page.isLast()
        );
    }
}
