package com.bitbi.dfm.deviceauth.presentation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request DTO for approving device authorization.
 *
 * @param userCode the user code to approve
 * @param action   action to take (approve)
 * @author Data Forge Team
 * @version 1.0.0
 */
@Schema(description = "Request to approve device authorization")
public record DeviceVerifyRequestDto(

        @Schema(
                description = "User code to approve",
                example = "WDJB-MJHT",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @JsonProperty("userCode")
        @NotBlank(message = "User code is required")
        String userCode,

        @Schema(
                description = "Action to take",
                example = "approve",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @JsonProperty("action")
        @NotBlank(message = "Action is required")
        @Pattern(regexp = "^(approve|deny)$", message = "Action must be 'approve' or 'deny'")
        String action
) {
}
