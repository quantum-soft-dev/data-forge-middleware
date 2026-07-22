package com.bitbi.dfm.delta.presentation.dto;

import com.bitbi.dfm.delta.domain.Checkpoint;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Response DTO for one per-table checkpoint row (feature 023 — Delta Sync UI, B5).
 *
 * <p>Carries file-presence flags instead of S3 keys or URLs: presigned download URLs are issued
 * by a separate endpoint per click, never in the (polled) list response. {@code hasParquet=false}
 * while {@code hasCsv=true} renders as "Parquet pending" in the UI.</p>
 *
 * @param table      table name
 * @param seq        sequence the checkpoint represents
 * @param rowCount   number of rows in the materialized snapshot
 * @param updatedAt  when the checkpoint was last (re)built
 * @param hasCsv     whether the legacy gzipped CSV snapshot exists
 * @param hasParquet whether the full typed Parquet snapshot exists
 */
public record DeltaCheckpointResponseDto(
        String table,
        long seq,
        long rowCount,
        Instant updatedAt,
        boolean hasCsv,
        boolean hasParquet
) {

    /**
     * Convert the Checkpoint entity to its REST projection.
     *
     * @param checkpoint the checkpoint entity
     * @return response DTO
     */
    public static DeltaCheckpointResponseDto fromEntity(Checkpoint checkpoint) {
        return new DeltaCheckpointResponseDto(
                checkpoint.getTableName(),
                checkpoint.getSeq(),
                checkpoint.getRowCount(),
                checkpoint.getUpdatedAt().toInstant(ZoneOffset.UTC),
                checkpoint.getS3KeyCsv() != null,
                checkpoint.getS3KeyParquet() != null
        );
    }
}
