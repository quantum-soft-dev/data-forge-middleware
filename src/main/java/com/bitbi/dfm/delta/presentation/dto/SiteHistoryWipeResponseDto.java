package com.bitbi.dfm.delta.presentation.dto;

import com.bitbi.dfm.delta.application.SiteHistoryWipeSummary;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a site history wipe destroyed (035 — issue #89).
 *
 * @param generation            the site's new epoch — the client resets its local journal and seq
 *                              counter as soon as it sees this value
 * @param deletedBatches        batches removed
 * @param deletedSegments       changelog segments removed
 * @param deletedCheckpoints    checkpoint rows removed
 * @param deletedFiles          uploaded files removed
 * @param deletedSqlGenerations plugin SQL generations removed
 * @param deletedErrorLogs      error log rows removed
 * @param deletedBytes          bytes accounted for by the removed files
 * @param s3DeleteErrors        objects known to have been left behind — the ones the bucket refused,
 *                              or every object handed to a delete phase that failed outright
 *                              (orphans, not data loss; a floor rather than a census, see #123)
 * @param prefixesNotSwept      prefixes that could not be listed or were listed only partially.
 *                              Distinct from {@code s3DeleteErrors}: do not quote this as an
 *                              object count — repeat the wipe (issue #122)
 * @param baselineBatchDetached whether a plugin activation's baseline batch had to be nulled
 */
@Schema(description = "Summary of a completed site history wipe")
public record SiteHistoryWipeResponseDto(
        long generation,
        int deletedBatches,
        int deletedSegments,
        int deletedCheckpoints,
        int deletedFiles,
        int deletedSqlGenerations,
        int deletedErrorLogs,
        long deletedBytes,
        int s3DeleteErrors,
        int prefixesNotSwept,
        boolean baselineBatchDetached) {

    /**
     * Map an application-layer summary onto the wire shape.
     *
     * @param summary what the wipe reported
     * @return response DTO
     */
    public static SiteHistoryWipeResponseDto fromSummary(SiteHistoryWipeSummary summary) {
        return new SiteHistoryWipeResponseDto(
                summary.generation(),
                summary.deletedBatches(),
                summary.deletedSegments(),
                summary.deletedCheckpoints(),
                summary.deletedFiles(),
                summary.deletedSqlGenerations(),
                summary.deletedErrorLogs(),
                summary.deletedBytes(),
                summary.s3DeleteErrors(),
                summary.prefixesNotSwept(),
                summary.baselineBatchDetached());
    }
}
