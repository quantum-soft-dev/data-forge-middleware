package com.bitbi.dfm.plugin.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * DTO for the response of listing CSV files for a site.
 *
 * @param files List of CSV files with their metadata
 */
@Schema(description = "Response containing list of CSV files for a site")
public record FileListResponseDto(
    @Schema(description = "List of CSV files")
    List<FileDto> files
) {
    /**
     * Creates a FileListResponseDto from a list of files.
     */
    public static FileListResponseDto of(List<FileDto> files) {
        return new FileListResponseDto(files);
    }
}
