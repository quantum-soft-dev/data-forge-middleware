package com.bitbi.dfm.plugin.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * {@code GET /api/v1/plugins/bit-bi/v2/sites} (issue #386): the account's sites, the same selection
 * as v1's {@code /sites}. Unlike v1's {@code SiteListResponseDto} it carries no derived
 * {@code empty} member.
 *
 * @param sites the account's active sites
 */
@Schema(description = "Bit BI v2: the account's sites")
public record BitBiV2SiteListDto(
        @Schema(description = "The account's active sites")
        List<SiteDto> sites
) {
}
