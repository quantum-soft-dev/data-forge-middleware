package com.bitbi.dfm.plugin.presentation.dto;

import java.util.List;

/**
 * Paginated Parquet Export file listing response (028).
 * <p>
 * Keyset pagination: pass {@code nextCursor} back as the {@code cursor} query parameter and
 * iterate until {@code hasMore} is {@code false}. Drive iteration by {@code hasMore}, not by an
 * empty {@code files} list — a page can be shorter than {@code size} (even empty) when delta
 * candidates were dropped by the S3 existence probe.
 * </p>
 */
public record ParquetFileListResponseDto(
        List<ParquetFileResponseDto> files,
        int size,
        boolean hasMore,
        String nextCursor) {
}
