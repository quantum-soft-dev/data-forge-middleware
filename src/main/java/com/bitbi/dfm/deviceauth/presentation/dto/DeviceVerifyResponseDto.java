package com.bitbi.dfm.deviceauth.presentation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Response DTO after device verification action.
 *
 * @param success  whether the operation succeeded
 * @param siteId   created site ID (for approve)
 * @param siteName created site name (for approve)
 * @author Data Forge Team
 * @version 1.0.0
 */
@Schema(description = "Response after device verification")
public record DeviceVerifyResponseDto(

        @Schema(description = "Operation success", example = "true")
        @JsonProperty("success")
        boolean success,

        @Schema(description = "Created site ID", example = "550e8400-e29b-41d4-a716-446655440000")
        @JsonProperty("siteId")
        UUID siteId,

        @Schema(description = "Created site name", example = "warehouse-01")
        @JsonProperty("siteName")
        String siteName
) {
    /**
     * Create success response with site info.
     */
    public static DeviceVerifyResponseDto success(UUID siteId, String siteName) {
        return new DeviceVerifyResponseDto(true, siteId, siteName);
    }

    /**
     * Create success response without site info (for deny).
     */
    public static DeviceVerifyResponseDto successDenied() {
        return new DeviceVerifyResponseDto(true, null, null);
    }

    /**
     * Create success response for denied authorization.
     */
    public static DeviceVerifyResponseDto denied() {
        return new DeviceVerifyResponseDto(true, null, null);
    }
}
