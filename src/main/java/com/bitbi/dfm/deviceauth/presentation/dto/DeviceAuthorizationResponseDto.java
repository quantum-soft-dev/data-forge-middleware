package com.bitbi.dfm.deviceauth.presentation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response DTO for device authorization initiation.
 *
 * @param deviceCode              secret code for polling (keep private)
 * @param userCode                code to display to user
 * @param verificationUri         URL where user should go
 * @param verificationUriComplete URL with code pre-filled
 * @param expiresIn               seconds until codes expire
 * @param interval                minimum seconds between polling requests
 * @author Data Forge Team
 * @version 1.0.0
 */
@Schema(description = "Response with device authorization codes")
public record DeviceAuthorizationResponseDto(

        @Schema(description = "Secret device code for polling", example = "GmRhmhcxhwAzkoEqiMEg_DnyEysNkuNhszIySk9eS")
        @JsonProperty("deviceCode")
        String deviceCode,

        @Schema(description = "Human-readable code for user", example = "WDJB-MJHT")
        @JsonProperty("userCode")
        String userCode,

        @Schema(description = "Verification URL", example = "https://app.dataforge.com/device-verify")
        @JsonProperty("verificationUri")
        String verificationUri,

        @Schema(description = "Verification URL with code", example = "https://app.dataforge.com/device-verify?code=WDJB-MJHT")
        @JsonProperty("verificationUriComplete")
        String verificationUriComplete,

        @Schema(description = "Seconds until expiration", example = "900")
        @JsonProperty("expiresIn")
        int expiresIn,

        @Schema(description = "Minimum polling interval in seconds", example = "5")
        @JsonProperty("interval")
        int interval
) {
    /**
     * Create response from service result.
     */
    public static DeviceAuthorizationResponseDto fromResult(
            com.bitbi.dfm.deviceauth.application.DeviceAuthorizationService.AuthorizationResult result) {
        return new DeviceAuthorizationResponseDto(
                result.deviceCode(),
                result.userCode(),
                result.verificationUri(),
                result.verificationUriComplete(),
                result.expiresIn(),
                result.interval()
        );
    }
}
