package com.bitbi.dfm.plugin.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response DTO for listing sites in the Plugin API.
 *
 * @param sites List of sites belonging to the account
 */
@Schema(description = "List of sites response")
public record SiteListResponseDto(
    @Schema(description = "List of sites belonging to the account")
    List<SiteDto> sites
) {
    /**
     * Creates a response with the given sites.
     */
    public static SiteListResponseDto of(List<SiteDto> sites) {
        return new SiteListResponseDto(sites != null ? List.copyOf(sites) : List.of());
    }

    /**
     * Creates an empty response (no sites).
     */
    public static SiteListResponseDto empty() {
        return new SiteListResponseDto(List.of());
    }

    /**
     * Returns true if there are no sites.
     */
    public boolean isEmpty() {
        return sites == null || sites.isEmpty();
    }

    /**
     * Returns the number of sites.
     */
    public int size() {
        return sites != null ? sites.size() : 0;
    }
}
