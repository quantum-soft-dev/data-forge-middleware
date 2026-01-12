package com.bitbi.dfm.deviceauth.presentation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Response DTO with authorization info for verification page.
 *
 * @param userCode        the user code
 * @param siteName        requested site name
 * @param siteDescription optional site description
 * @param expiresAt       when authorization expires
 * @author Data Forge Team
 * @version 1.0.0
 */
@Schema(description = "Device authorization info for verification")
public record DeviceVerifyInfoResponseDto(

        @Schema(description = "User code", example = "WDJB-MJHT")
        @JsonProperty("userCode")
        String userCode,

        @Schema(description = "Requested site name", example = "warehouse-01")
        @JsonProperty("siteName")
        String siteName,

        @Schema(description = "Site description", example = "Main warehouse terminal")
        @JsonProperty("siteDescription")
        String siteDescription,

        @Schema(description = "Expiration time", example = "2025-01-12T15:30:00Z")
        @JsonProperty("expiresAt")
        Instant expiresAt
) {
    /**
     * Create response from service result.
     */
    public static DeviceVerifyInfoResponseDto fromInfo(
            com.bitbi.dfm.deviceauth.application.DeviceAuthorizationService.AuthorizationInfo info) {
        return new DeviceVerifyInfoResponseDto(
                info.userCode(),
                info.siteName(),
                info.siteDescription(),
                info.expiresAt()
        );
    }
}
