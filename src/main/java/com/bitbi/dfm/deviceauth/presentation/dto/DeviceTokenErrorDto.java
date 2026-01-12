package com.bitbi.dfm.deviceauth.presentation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Error response DTO for device token polling.
 * Follows OAuth 2.0 Device Authorization Grant error format.
 *
 * @param error            error code
 * @param errorDescription human-readable error description
 * @param interval         new polling interval (for slow_down error)
 * @author Data Forge Team
 * @version 1.0.0
 */
@Schema(description = "Device token error response")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceTokenErrorDto(

        @Schema(description = "Error code", example = "authorization_pending")
        @JsonProperty("error")
        String error,

        @Schema(description = "Error description", example = "The user has not yet completed authorization")
        @JsonProperty("error_description")
        String errorDescription,

        @Schema(description = "New polling interval (for slow_down)", example = "10")
        @JsonProperty("interval")
        Integer interval
) {
    /**
     * Authorization is still pending.
     */
    public static DeviceTokenErrorDto authorizationPending() {
        return new DeviceTokenErrorDto(
                "authorization_pending",
                "The user has not yet completed authorization",
                null
        );
    }

    /**
     * Client is polling too frequently.
     */
    public static DeviceTokenErrorDto slowDown(int newInterval) {
        return new DeviceTokenErrorDto(
                "slow_down",
                "Polling too frequently",
                newInterval
        );
    }

    /**
     * User denied the authorization.
     */
    public static DeviceTokenErrorDto accessDenied() {
        return new DeviceTokenErrorDto(
                "access_denied",
                "The user denied the authorization request",
                null
        );
    }

    /**
     * Device code has expired.
     */
    public static DeviceTokenErrorDto expiredToken() {
        return new DeviceTokenErrorDto(
                "expired_token",
                "The device code has expired",
                null
        );
    }

    /**
     * Invalid device code.
     */
    public static DeviceTokenErrorDto invalidGrant() {
        return new DeviceTokenErrorDto(
                "invalid_grant",
                "Invalid device code",
                null
        );
    }
}
