package com.bitbi.dfm.plugin.presentation.dto;

import com.bitbi.dfm.plugin.application.BitBiV2QueryService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * {@code GET /api/v1/plugins/bit-bi/v2/sites/{siteId}/state} (issue #386): where the site's history
 * stands, the position a v2 consumer compares its watermark {@code (epoch, seq)} against.
 *
 * @param siteId         the site
 * @param epoch          the site's baseline epoch; it moves on a history wipe and on a re-baseline,
 *                       and a changed value means the consumer's mirror belongs to a discarded
 *                       history and must be reloaded
 * @param checkpointSeq  the seq the current checkpoint snapshots are built at; 0 = no checkpoint yet
 * @param lastAppliedSeq the highest seq the server has committed
 * @param schemaVersion  the version of the schema {@code /schema} serves; 0 = none submitted
 */
@Schema(description = "Bit BI v2: a site's epoch, checkpoint pointer, applied watermark and schema version")
public record BitBiV2SiteStateDto(
        @Schema(description = "Site identifier", example = "0199baac-f852-753f-6fc3-7c994fc38654")
        UUID siteId,

        @Schema(description = "Baseline epoch; moves on a history wipe and on a re-baseline", example = "3")
        long epoch,

        @Schema(description = "Seq of the current checkpoint snapshots; 0 = no checkpoint yet", example = "5020646")
        long checkpointSeq,

        @Schema(description = "Highest seq the server has committed", example = "5023845")
        long lastAppliedSeq,

        @Schema(description = "Version of the schema /schema serves; 0 = none submitted", example = "2")
        int schemaVersion
) {

    /**
     * @param state the read model
     * @return its wire form
     */
    public static BitBiV2SiteStateDto from(BitBiV2QueryService.SiteState state) {
        return new BitBiV2SiteStateDto(state.siteId(), state.epoch(), state.checkpointSeq(),
                state.lastAppliedSeq(), state.schemaVersion());
    }
}
