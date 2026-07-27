package com.bitbi.dfm.plugin.presentation.dto;

import java.util.List;

/**
 * Paginated Parquet Export file listing response (028).
 */
public record ParquetFileListResponseDto(
        List<ParquetFileResponseDto> files,
        int page,
        int size,
        boolean hasMore) {
}
