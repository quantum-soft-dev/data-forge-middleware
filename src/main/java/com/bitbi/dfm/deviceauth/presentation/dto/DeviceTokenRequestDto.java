package com.bitbi.dfm.deviceauth.presentation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for polling device token.
 *
 * @param deviceCode the device code received from authorization
 * @author Data Forge Team
 * @version 1.0.0
 */
@Schema(description = "Request to poll for device token")
public record DeviceTokenRequestDto(

        @Schema(
                description = "Device code from authorization response",
                example = "GmRhmhcxhwAzkoEqiMEg_DnyEysNkuNhszIySk9eS",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @JsonProperty("deviceCode")
        @NotBlank(message = "Device code is required")
        String deviceCode
) {
}
