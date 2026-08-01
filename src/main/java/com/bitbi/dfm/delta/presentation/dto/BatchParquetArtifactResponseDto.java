package com.bitbi.dfm.delta.presentation.dto;

import com.bitbi.dfm.delta.domain.BatchParquetArtifact;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/** Safe operator projection of one durable completed-batch Parquet queue row. */
public record BatchParquetArtifactResponseDto(
        UUID id,
        String tableName,
        BatchParquetArtifactStatus status,
        int attemptCount,
        String lastError,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime readyAt
) {
    /** Map the manifest without exposing storage keys, checksums, versions, or claim tokens. */
    public static BatchParquetArtifactResponseDto fromEntity(BatchParquetArtifact artifact) {
        return new BatchParquetArtifactResponseDto(
                artifact.getId(), artifact.getTableName(), artifact.getStatus(),
                artifact.getAttemptCount(), artifact.getLastError(), artifact.getCreatedAt(),
                artifact.getUpdatedAt(), artifact.getReadyAt());
    }
}
