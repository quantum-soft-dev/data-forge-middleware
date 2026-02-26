package com.bitbi.dfm.deviceauth.presentation.dto;

import com.bitbi.dfm.site.domain.SiteType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for initiating device authorization.
 *
 * @param siteName        requested site name (alphanumeric + hyphens)
 * @param siteDescription optional site description
 * @param siteType        optional site type (DBF | POSTGRES_CDC | MSSQL_CDC | DBF_CDC); defaults to DBF if omitted
 * @author Data Forge Team
 * @version 1.1.0
 */
@Schema(description = "Request to initiate device authorization")
public record DeviceAuthorizationRequestDto(

        @Schema(
                description = "Site name (Unicode allowed, no path traversal characters)",
                example = "warehouse-01",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "Site name is required")
        @Size(max = 255, message = "Site name must be at most 255 characters")
        String siteName,

        @Schema(
                description = "Optional site description",
                example = "Main warehouse terminal",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        @Size(max = 500, message = "Site description must be at most 500 characters")
        String siteDescription,

        @Schema(
                description = "Site type. DBF = full CSV snapshots (default). POSTGRES_CDC / MSSQL_CDC / DBF_CDC = first batch CSV baseline, subsequent batches JSONL deltas.",
                example = "DBF",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                allowableValues = {"DBF", "POSTGRES_CDC", "MSSQL_CDC", "DBF_CDC"}
        )
        SiteType siteType
) {
}
