package com.bitbi.dfm.batch.presentation.dto;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.infrastructure.BatchWithFileCountProjection;
import com.bitbi.dfm.delta.domain.SegmentBatchAggregate;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Summary DTO for batch lists (Admin UI API).
 * <p>
 * Contains basic batch information without files list.
 * Used for paginated batch list endpoints.
 * </p>
 *
 * @param id                  Batch unique identifier
 * @param siteId              Site identifier
 * @param status              Batch status (IN_PROGRESS, COMPLETED, FAILED, etc.)
 * @param s3Path              S3 base path for batch files
 * @param uploadedFilesCount  Number of files uploaded
 * @param totalSize           Total size of all files in bytes
 * @param hasErrors           Whether batch has any errors
 * @param startedAt           Batch start timestamp
 * @param completedAt         Batch completion timestamp (null if in progress)
 * @param createdAt           Batch creation timestamp
 * @param deltaRecordCount    Total records changed in the Delta v2 session (null for v1 batches)
 * @param deltaTableCount     Number of tables touched in the Delta v2 session (null for v1 batches)
 * @author Data Forge Team
 * @version 1.0.0
 * @see com.bitbi.dfm.batch.presentation.BatchAdminController
 */
@Schema(description = "Batch summary for list view")
public record BatchSummaryDto(
        @Schema(description = "Batch unique identifier", example = "123e4567-e89b-12d3-a456-426614174000")
        UUID id,

        @Schema(description = "Site identifier", example = "987fcdeb-51a2-43f7-9c3d-123456789abc")
        UUID siteId,

        @Schema(description = "Batch status", example = "IN_PROGRESS")
        String status,

        @Schema(description = "S3 base path for batch files", example = "987fcdeb-51a2-43f7-9c3d-123456789abc/example.com/2025-01-15/10-30/")
        String s3Path,

        @Schema(description = "Number of files uploaded", example = "15")
        Integer uploadedFilesCount,

        @Schema(description = "Total size of all files in bytes", example = "20971520")
        Long totalSize,

        @Schema(description = "Whether batch has any errors", example = "false")
        Boolean hasErrors,

        @Schema(description = "Batch start timestamp (ISO-8601)", example = "2025-01-15T10:30:00Z")
        Instant startedAt,

        @Schema(description = "Batch completion timestamp (ISO-8601, null if in progress)", example = "2025-01-15T11:15:00Z")
        Instant completedAt,

        @Schema(description = "Batch creation timestamp (ISO-8601)", example = "2025-01-15T10:30:00Z")
        Instant createdAt,

        @Schema(description = "Total records changed in the Delta v2 session (null for v1 batches)", example = "34")
        Long deltaRecordCount,

        @Schema(description = "Number of tables touched in the Delta v2 session (null for v1 batches)", example = "6")
        Integer deltaTableCount
) {
    /**
     * Create DTO from Batch entity.
     *
     * @param batch Batch entity
     * @return BatchSummaryDto
     */
    public static BatchSummaryDto fromEntity(Batch batch) {
        return new BatchSummaryDto(
                batch.getId(),
                batch.getSiteId(),
                batch.getStatus().toString(),
                batch.getS3Path(),
                batch.getUploadedFilesCount(),
                batch.getTotalSize(),
                batch.getHasErrors(),
                batch.getStartedAt().toInstant(ZoneOffset.UTC),
                batch.getCompletedAt() != null ? batch.getCompletedAt().toInstant(ZoneOffset.UTC) : null,
                batch.getCreatedAt().toInstant(ZoneOffset.UTC),
                null,
                null
        );
    }

    /**
     * T026: Create DTO from BatchWithFileCountProjection (optimized for list view), with no
     * Delta v2 signal (v1 file-based batch, or caller doesn't have a segment to look up).
     *
     * @param projection Batch projection with file count
     * @return BatchSummaryDto
     */
    public static BatchSummaryDto fromProjection(BatchWithFileCountProjection projection) {
        return fromProjection(projection, null);
    }

    /**
     * T6.5 / 029: Create DTO from BatchWithFileCountProjection and the batch's segment aggregate
     * (session totals across N segments), surfacing the Delta v2 signal in the list view.
     * <p>
     * Used by cursor-based pagination queries to avoid N+1 queries.
     * S3Path is set to empty string as it's not needed for list view. A 0 distinct-table count
     * (segments persisted before per-table stats existed) maps to {@code null} so those batches
     * keep their pre-stats rendering.
     * </p>
     *
     * @param projection Batch projection with file count
     * @param aggregate  the batch's segment aggregate, or {@code null} for a v1 batch
     * @return BatchSummaryDto
     */
    public static BatchSummaryDto fromProjection(BatchWithFileCountProjection projection,
                                                 SegmentBatchAggregate aggregate) {
        Long tableCount = aggregate != null ? aggregate.getTableCount() : null;
        return new BatchSummaryDto(
                projection.getId(),
                projection.getSiteId(),
                projection.getStatus(),
                "",  // S3Path not included in projection (not needed for list view)
                projection.getFileCount(),
                projection.getTotalSize(),
                projection.getHasErrors(),
                projection.getStartedAt().toInstant(ZoneOffset.UTC),
                projection.getCompletedAt() != null ? projection.getCompletedAt().toInstant(ZoneOffset.UTC) : null,
                projection.getStartedAt().toInstant(ZoneOffset.UTC),  // Use startedAt as createdAt approximation
                aggregate != null ? aggregate.getTotalRecords() : null,
                tableCount != null && tableCount > 0 ? tableCount.intValue() : null
        );
    }
}
