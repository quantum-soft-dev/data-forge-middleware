package com.bitbi.dfm.comparison.presentation.dto;

import com.bitbi.dfm.comparison.domain.ComparisonStatus;
import com.bitbi.dfm.comparison.domain.FileComparison;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for file comparison operations.
 *
 * <p>This DTO represents the metadata of a comparison operation, excluding
 * detailed results (use ComparisonResultDto for individual file results).
 *
 * @param id the comparison ID
 * @param currentBatchId the current batch ID
 * @param targetBatchId the target batch ID
 * @param accountId the account owner ID
 * @param status the comparison status
 * @param createdAt timestamp when comparison was created
 * @param startedAt timestamp when processing started
 * @param completedAt timestamp when processing completed
 * @param totalFilesCompared total number of files compared
 * @param filesChanged number of files with changes
 * @param filesAdded number of new files
 * @param filesUnchanged number of unchanged files
 * @param totalChangeSize total size of changes in bytes
 * @param errorMessage error description if status is FAILED
 */
@Schema(description = "File comparison metadata and statistics")
public record ComparisonResponseDto(
    @Schema(description = "Comparison ID", example = "45")
    Long id,

    @Schema(description = "Current batch ID", example = "123")
    UUID currentBatchId,

    @Schema(description = "Target batch ID", example = "120")
    UUID targetBatchId,

    @Schema(description = "Account owner ID", example = "10")
    UUID accountId,

    @Schema(description = "Comparison status", example = "COMPLETED")
    ComparisonStatus status,

    @Schema(description = "Creation timestamp", example = "2025-11-03T10:30:00Z")
    Instant createdAt,

    @Schema(description = "Start timestamp", example = "2025-11-03T10:30:01Z")
    Instant startedAt,

    @Schema(description = "Completion timestamp", example = "2025-11-03T10:31:25Z")
    Instant completedAt,

    @Schema(description = "Total files compared", example = "50")
    Integer totalFilesCompared,

    @Schema(description = "Files with changes", example = "12")
    Integer filesChanged,

    @Schema(description = "New files", example = "3")
    Integer filesAdded,

    @Schema(description = "Unchanged files", example = "35")
    Integer filesUnchanged,

    @Schema(description = "Total change size in bytes", example = "125000")
    Long totalChangeSize,

    @Schema(description = "Error message if status is FAILED")
    String errorMessage
) {
    /**
     * Converts a FileComparison domain entity to a response DTO.
     *
     * @param comparison the domain entity
     * @return the corresponding DTO
     */
    public static ComparisonResponseDto fromEntity(FileComparison comparison) {
        if (comparison == null) {
            return null;
        }

        return new ComparisonResponseDto(
            comparison.getId(),
            comparison.getCurrentBatchId(),
            comparison.getTargetBatchId(),
            comparison.getAccountId(),
            comparison.getStatus(),
            comparison.getCreatedAt(),
            comparison.getStartedAt(),
            comparison.getCompletedAt(),
            comparison.getTotalFilesCompared(),
            comparison.getFilesChanged(),
            comparison.getFilesAdded(),
            comparison.getFilesUnchanged(),
            comparison.getTotalChangeSize(),
            comparison.getErrorMessage()
        );
    }

    /**
     * Alias for fromEntity - converts domain entity to DTO.
     *
     * @param comparison the domain entity
     * @return the corresponding DTO
     */
    public static ComparisonResponseDto fromDomain(FileComparison comparison) {
        return fromEntity(comparison);
    }
}
