package com.bitbi.dfm.plugin.presentation.dto;

import com.bitbi.dfm.plugin.application.BitBiV2FileService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/v1/plugins/bit-bi/v2/sites/{siteId}/snapshot} (issue #387): one checkpoint Parquet
 * per table, every one at {@code checkpointSeq}. The consumer loads them and stores
 * {@code (epoch, checkpointSeq)} as its watermark.
 *
 * @param siteId        the site
 * @param epoch         the site's baseline epoch the files belong to
 * @param checkpointSeq the seq every {@code ready} file is at; 0 = no checkpoint yet (no tables)
 * @param tables        one entry per table, sorted by name
 */
@Schema(description = "Bit BI v2: the site's checkpoint snapshot, one Parquet per table at one seq")
public record BitBiV2SnapshotDto(
        @Schema(description = "Site identifier", example = "0199baac-f852-753f-6fc3-7c994fc38654")
        UUID siteId,

        @Schema(description = "Baseline epoch the files belong to", example = "3")
        long epoch,

        @Schema(description = "Seq every ready file is at; 0 = no checkpoint yet", example = "5020646")
        long checkpointSeq,

        @Schema(description = "Tables, sorted by name")
        List<Table> tables
) {

    /**
     * One table.
     *
     * @param table  the table name
     * @param status {@code ready} — a file exists at {@code checkpointSeq}; {@code missing} — none does
     * @param fileId the id to pass to {@code /files/{fileId}}; null unless {@code ready}
     */
    @Schema(description = "One table of the snapshot")
    public record Table(
            @Schema(description = "Table name", example = "nsftrns")
            String table,

            @Schema(description = "ready: a file exists at checkpointSeq; missing: none does",
                    allowableValues = {"ready", "missing"}, example = "ready")
            String status,

            @Schema(description = "Opaque id for /files/{fileId}, pinned to this version of the file; "
                    + "null unless ready", nullable = true)
            String fileId
    ) {
    }

    /**
     * @param snapshot the read model
     * @return its wire form
     */
    public static BitBiV2SnapshotDto from(BitBiV2FileService.Snapshot snapshot) {
        return new BitBiV2SnapshotDto(snapshot.siteId(), snapshot.epoch(), snapshot.checkpointSeq(),
                snapshot.tables().stream()
                        .map(t -> new Table(t.table(), t.status().wireValue(), t.fileId()))
                        .toList());
    }
}
