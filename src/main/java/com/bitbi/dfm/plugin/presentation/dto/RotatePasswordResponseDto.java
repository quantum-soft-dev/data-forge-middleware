package com.bitbi.dfm.plugin.presentation.dto;

import com.bitbi.dfm.plugin.domain.ParquetExportCredentials;

/**
 * Response for the Parquet Export password rotation endpoint (028).
 * <p>
 * The raw password appears here exactly once — it is stored only as a BCrypt hash and cannot
 * be retrieved again.
 * </p>
 */
public record RotatePasswordResponseDto(String login, String password) {

    public static RotatePasswordResponseDto fromCredentials(ParquetExportCredentials credentials) {
        return new RotatePasswordResponseDto(credentials.login(), credentials.password());
    }
}
