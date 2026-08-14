package com.bitbi.dfm.plugin.presentation.dto;

import com.bitbi.dfm.plugin.application.ParquetExportFileService.ParquetFileItem;
import com.bitbi.dfm.plugin.domain.DownloadLink;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One Parquet file in the Parquet Export listing (028/041): metadata plus its registered
 * one-time download URL. Abandoned batch artifacts have a null download URL.
 *
 * @param batchId  batch files: owning batch; null for delta/checkpoint
 * @param status   batch files: {@code ready} or {@code abandoned}; null otherwise
 * @param firstSeq delta/batch files: first sequence; null for checkpoints
 * @param lastSeq  delta/batch files: last sequence; null for checkpoints
 * @param seq      checkpoint files: materialized sequence; null otherwise
 */
public record ParquetFileResponseDto(
        UUID siteId,
        String siteDomain,
        String table,
        String type,
        UUID batchId,
        String status,
        Long firstSeq,
        Long lastSeq,
        Long seq,
        LocalDateTime producedAt,
        String fileName,
        String downloadUrl,
        LocalDateTime linkExpiresAt) {

    public static ParquetFileResponseDto of(ParquetFileItem item, DownloadLink link, String downloadUrl) {
        return new ParquetFileResponseDto(
                item.siteId(), item.siteDomain(), item.table(),
                item.type().name().toLowerCase(java.util.Locale.ROOT),
                item.batchId(), item.status(),
                item.firstSeq(), item.lastSeq(), item.seq(),
                item.producedAt(), item.fileName(),
                downloadUrl, link == null ? null : link.getExpiresAt());
    }
}
