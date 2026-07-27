package com.bitbi.dfm.plugin.presentation.dto;

import com.bitbi.dfm.plugin.application.ParquetExportFileService.ParquetFileItem;
import com.bitbi.dfm.plugin.domain.DownloadLink;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One Parquet file in the Parquet Export listing (028): metadata plus its registered
 * one-time download URL.
 *
 * @param firstSeq delta files: first sequence of the segment; null for checkpoints
 * @param lastSeq  delta files: last sequence of the segment; null for checkpoints
 * @param seq      checkpoint files: materialized sequence; null for deltas
 */
public record ParquetFileResponseDto(
        UUID siteId,
        String siteDomain,
        String table,
        String type,
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
                item.firstSeq(), item.lastSeq(), item.seq(),
                item.producedAt(), item.fileName(),
                downloadUrl, link.getExpiresAt());
    }
}
