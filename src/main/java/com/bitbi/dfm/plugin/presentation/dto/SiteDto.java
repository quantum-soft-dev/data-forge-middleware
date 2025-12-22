package com.bitbi.dfm.plugin.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * DTO representing a site in the Plugin API response.
 *
 * @param id Unique site identifier
 * @param domain Site domain name
 * @param displayName Human-readable site name
 */
@Schema(description = "Site information")
public record SiteDto(
    @Schema(description = "Unique site identifier", example = "550e8400-e29b-41d4-a716-446655440000")
    UUID id,

    @Schema(description = "Site domain name", example = "example.com")
    String domain,

    @Schema(description = "Human-readable site name", example = "Example Site")
    String displayName
) {
    /**
     * Creates a SiteDto from individual values.
     */
    public static SiteDto of(UUID id, String domain, String displayName) {
        return new SiteDto(id, domain, displayName);
    }
}
