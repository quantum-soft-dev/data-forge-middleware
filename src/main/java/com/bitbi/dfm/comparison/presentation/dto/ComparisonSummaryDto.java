package com.bitbi.dfm.comparison.presentation.dto;

import com.bitbi.dfm.comparison.domain.ComparisonSummary;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Response DTO for comparison summary statistics.
 *
 * <p>This DTO provides a high-level overview of comparison results without
 * including detailed diff information for individual files.
 *
 * @param totalFilesCompared total number of files compared
 * @param filesChanged number of files with modifications
 * @param filesAdded number of new files
 * @param filesUnchanged number of unchanged files
 * @param totalChangeSize total size of changes in bytes
 * @param comparisonTimestamp timestamp when comparison completed
 * @param currentBatchId current batch ID
 * @param targetBatchId target batch ID
 */
@Schema(description = "Summary statistics for a file comparison")
public record ComparisonSummaryDto(
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

    @Schema(description = "Comparison completion timestamp", example = "2025-11-03T10:31:25Z")
    Instant comparisonTimestamp,

    @Schema(description = "Current batch ID", example = "123")
    Long currentBatchId,

    @Schema(description = "Target batch ID", example = "120")
    Long targetBatchId
) {
    /**
     * Converts a ComparisonSummary value object to a response DTO.
     *
     * @param summary the value object
     * @return the corresponding DTO
     */
    public static ComparisonSummaryDto fromValueObject(ComparisonSummary summary) {
        if (summary == null) {
            return null;
        }

        return new ComparisonSummaryDto(
            summary.totalFilesCompared(),
            summary.filesChanged(),
            summary.filesAdded(),
            summary.filesUnchanged(),
            summary.totalChangeSize(),
            summary.comparisonTimestamp(),
            summary.currentBatchId(),
            summary.targetBatchId()
        );
    }

    /**
     * Checks if there are any changes in this comparison.
     *
     * @return true if there are any changed or added files
     */
    public boolean hasChanges() {
        return filesChanged > 0 || filesAdded > 0;
    }

    /**
     * Gets the percentage of files that were changed or added.
     *
     * @return percentage of changed files (0-100), or 0 if no files were compared
     */
    public double getChangePercentage() {
        if (totalFilesCompared == 0) {
            return 0.0;
        }
        return ((double) (filesChanged + filesAdded) / totalFilesCompared) * 100.0;
    }
}
