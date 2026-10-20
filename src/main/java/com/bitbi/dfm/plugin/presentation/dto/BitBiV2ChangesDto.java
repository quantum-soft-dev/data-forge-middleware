package com.bitbi.dfm.plugin.presentation.dto;

import com.bitbi.dfm.plugin.application.BitBiV2ChangesService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/v1/plugins/bit-bi/v2/sites/{siteId}/changes} (issue #388): the site's completed
 * batches of its current epoch, in batch order, each with the manifest of the tables it touched.
 *
 * @param siteId     the site
 * @param epoch      the site's current epoch; every listed batch belongs to it
 * @param batches    in batch order
 * @param nextCursor pass it as {@code cursor} for the next page; null when the feed ends here for now
 */
@Schema(description = "Bit BI v2: completed batches of the site's current epoch, in batch order")
public record BitBiV2ChangesDto(
        @Schema(description = "Site identifier", example = "0199baac-f852-753f-6fc3-7c994fc38654")
        UUID siteId,

        @Schema(description = "The site's current baseline epoch; every listed batch belongs to it", example = "3")
        long epoch,

        @Schema(description = "Batches in batch order")
        List<Batch> batches,

        @Schema(description = "Cursor of the next page; null when the feed ends here for now", nullable = true)
        String nextCursor
) {

    /**
     * One batch (= one Delta session).
     */
    @Schema(name = "BitBiV2ChangesBatch", description = "One batch: a Delta session and the manifest of the tables it touched")
    public record Batch(
            @Schema(description = "Baseline epoch the batch belongs to", example = "3")
            long epoch,

            @Schema(description = "Batch identifier", example = "0199bb10-4c2e-7a1d-9f00-3b5e1a2c7d41")
            UUID batchId,

            @Schema(description = "full_snapshot replaces every table of the site; delta and continuous apply on top",
                    allowableValues = {"full_snapshot", "delta", "continuous"}, nullable = true, example = "delta")
            String sessionMode,

            @Schema(description = "Lowest seq of the batch", example = "5023012")
            long firstSeq,

            @Schema(description = "Highest seq of the batch; the watermark to store once it is applied",
                    example = "5023845")
            long lastSeq,

            @Schema(description = "When the batch's last table settled (published or abandoned)", nullable = true)
            Instant readyAt,

            @Schema(description = "Every table the batch touched, sorted by name")
            List<Table> tables
    ) {
    }

    /**
     * One table of a batch.
     */
    @Schema(name = "BitBiV2ChangesTable", description = "One table of a batch")
    public record Table(
            @Schema(description = "Table name", example = "nsftrns")
            String table,

            @Schema(description = "ready: the file exists; abandoned: the build gave up; missing: no file and "
                    + "no build (no declared schema, or the batch was never queued)",
                    allowableValues = {"ready", "abandoned", "missing"}, example = "ready")
            String status,

            @Schema(description = "Opaque id for /files/{fileId}; null unless ready", nullable = true)
            String fileId,

            @Schema(description = "Rows of the file when ready, otherwise the batch's records of the table",
                    nullable = true, example = "834")
            Long rows
    ) {
    }

    /**
     * @param page the read model
     * @return its wire form
     */
    public static BitBiV2ChangesDto from(BitBiV2ChangesService.ChangesPage page) {
        return new BitBiV2ChangesDto(page.siteId(), page.epoch(), page.batches().stream()
                .map(b -> new Batch(b.epoch(), b.batchId(), b.sessionMode(), b.firstSeq(), b.lastSeq(), b.readyAt(),
                        b.tables().stream()
                                .map(t -> new Table(t.table(), t.status().wireValue(), t.fileId(), t.rows()))
                                .toList()))
                .toList(), page.nextCursor());
    }
}
