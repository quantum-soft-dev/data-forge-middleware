package com.bitbi.dfm.delta.presentation.dto;

import com.bitbi.dfm.delta.application.DeltaCheckpointQueryService.PresignedDownload;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Response DTO for a presigned checkpoint download (feature 023 — Delta Sync UI, B5).
 *
 * @param downloadUrl presigned S3 URL (15-minute expiry)
 * @param fileName    suggested filename for browser download
 * @param expiresAt   URL expiration timestamp
 */
@Schema(description = "Checkpoint download response with presigned URL")
public record DeltaCheckpointDownloadResponseDto(
        @Schema(description = "Presigned S3 URL for download")
        String downloadUrl,

        @Schema(description = "Suggested filename for browser download", example = "orders_seq4821.parquet")
        String fileName,

        @Schema(description = "URL expiration timestamp (ISO-8601)")
        Instant expiresAt
) {

    /**
     * Convert the application-layer presigned download result to its REST projection.
     *
     * @param download presigned download result
     * @return response DTO
     */
    public static DeltaCheckpointDownloadResponseDto of(PresignedDownload download) {
        return new DeltaCheckpointDownloadResponseDto(download.downloadUrl(), download.fileName(), download.expiresAt());
    }
}
