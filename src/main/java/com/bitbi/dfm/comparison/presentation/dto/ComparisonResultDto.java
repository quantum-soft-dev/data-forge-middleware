package com.bitbi.dfm.comparison.presentation.dto;

import com.bitbi.dfm.comparison.domain.ChangeType;
import com.bitbi.dfm.comparison.domain.ComparisonResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Response DTO for individual file comparison results.
 *
 * <p>This DTO represents the diff result for a single file within a comparison operation.
 *
 * @param id the result ID
 * @param comparisonId the parent comparison ID
 * @param fileId the current file ID
 * @param targetFileId the target file ID (null for new files)
 * @param fileName the current file name
 * @param targetFileName the target file name
 * @param changeType the type of change detected
 * @param lineAdditions number of lines added
 * @param lineDeletions number of lines deleted
 * @param changeSize total change size in bytes
 * @param unifiedDiff the diff output in structured JSON format
 * @param createdAt timestamp when result was created
 */
@Schema(description = "Individual file comparison result with diff details")
public record ComparisonResultDto(
    @Schema(description = "Result ID", example = "1001")
    Long id,

    @Schema(description = "Parent comparison ID", example = "45")
    Long comparisonId,

    @Schema(description = "Current file ID", example = "501")
    Long fileId,

    @Schema(description = "Target file ID (null for new files)", example = "450")
    Long targetFileId,

    @Schema(description = "Current file name", example = "data.csv")
    String fileName,

    @Schema(description = "Target file name", example = "data.csv")
    String targetFileName,

    @Schema(description = "Change type", example = "MODIFIED")
    ChangeType changeType,

    @Schema(description = "Lines added", example = "25")
    Integer lineAdditions,

    @Schema(description = "Lines deleted", example = "10")
    Integer lineDeletions,

    @Schema(description = "Change size in bytes", example = "1500")
    Long changeSize,

    @Schema(description = "Unified diff in JSON format")
    String unifiedDiff,

    @Schema(description = "Creation timestamp", example = "2025-11-03T10:31:15Z")
    Instant createdAt
) {
    /**
     * Converts a ComparisonResult domain entity to a response DTO.
     *
     * <p>Note: fileName and targetFileName will need to be fetched from the files table
     * in the actual implementation. This is a placeholder for Phase 2.
     *
     * @param result the domain entity
     * @return the corresponding DTO
     */
    public static ComparisonResultDto fromEntity(ComparisonResult result) {
        if (result == null) {
            return null;
        }

        return new ComparisonResultDto(
            result.getId(),
            result.getComparisonId(),
            result.getFileId(),
            result.getTargetFileId(),
            null,  // TODO: Fetch from files table in Phase 3
            null,  // TODO: Fetch from files table in Phase 3
            result.getChangeType(),
            result.getLineAdditions(),
            result.getLineDeletions(),
            result.getChangeSize(),
            result.getUnifiedDiff(),
            result.getCreatedAt()
        );
    }

    /**
     * Gets a human-readable diff summary (e.g., "+25 -10").
     *
     * @return diff summary string
     */
    public String getDiffSummary() {
        if (changeType == ChangeType.UNCHANGED) {
            return "No changes";
        }
        return String.format("+%d -%d", lineAdditions, lineDeletions);
    }
}
