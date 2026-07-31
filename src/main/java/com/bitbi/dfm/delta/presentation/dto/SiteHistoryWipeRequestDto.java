package com.bitbi.dfm.delta.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Confirmation body of a site history wipe (035 — issue #89).
 *
 * <p>The GitHub convention for irreversible destruction: the caller types the name of the thing
 * being destroyed. Nothing about the wipe can be undone, and its damage is invisible until a client
 * next connects, so a single click must not be enough.</p>
 *
 * @param confirm the site's domain, exactly as it appears on the site
 */
@Schema(description = "Confirmation for an irreversible site history wipe")
public record SiteHistoryWipeRequestDto(
        @Schema(description = "The site's domain, typed back to confirm", example = "shop-42.example.com")
        String confirm) {
}
