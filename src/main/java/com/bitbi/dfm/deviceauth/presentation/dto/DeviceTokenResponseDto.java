package com.bitbi.dfm.deviceauth.presentation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Response DTO with site credentials after approval.
 *
 * @param siteId       created site ID
 * @param domain       full domain for authentication
 * @param clientSecret plain client secret (returned only once!)
 * @param apiBaseUrl   API base URL for subsequent requests
 * @author Data Forge Team
 * @version 1.0.0
 */
@Schema(description = "Site credentials after successful authorization")
public record DeviceTokenResponseDto(

        @Schema(description = "Created site ID", example = "550e8400-e29b-41d4-a716-446655440000")
        @JsonProperty("siteId")
        UUID siteId,

        @Schema(description = "Full domain for authentication", example = "acct123_warehouse-01")
        @JsonProperty("domain")
        String domain,

        @Schema(description = "Client secret for Basic Auth (store securely!)", example = "dGhpcyBpcyBhIHNlY3JldCBrZXk=")
        @JsonProperty("clientSecret")
        String clientSecret,

        @Schema(description = "API base URL", example = "https://api.dataforge.com")
        @JsonProperty("apiBaseUrl")
        String apiBaseUrl
) {
    /**
     * Create response from service result.
     */
    public static DeviceTokenResponseDto fromResult(
            com.bitbi.dfm.deviceauth.application.DeviceAuthorizationService.TokenResult.Success success) {
        return new DeviceTokenResponseDto(
                success.siteId(),
                success.domain(),
                success.clientSecret(),
                success.apiBaseUrl()
        );
    }
}
