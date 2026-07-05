package com.bitbi.dfm.batch.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Sequence range covered by a Delta v2 session (feature 023 — Delta Sync UI, B9).
 *
 * @param first lowest first_seq across the session's changelog segments
 * @param last  highest last_seq across the session's changelog segments
 */
@Schema(description = "Sequence range covered by a Delta v2 session")
public record DeltaSeqRangeDto(
        @Schema(description = "First change sequence of the session", example = "4801")
        long first,

        @Schema(description = "Last change sequence of the session", example = "5100")
        long last
) {
}
