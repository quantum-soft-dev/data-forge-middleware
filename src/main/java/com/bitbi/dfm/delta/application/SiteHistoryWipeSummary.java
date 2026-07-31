package com.bitbi.dfm.delta.application;

/**
 * What a site history wipe destroyed (035 — issue #89).
 *
 * @param generation            the site's new epoch; the client resets its local journal and seq
 *                              counter the moment it sees a value it has not seen before
 * @param deletedBatches        batches removed (their uploaded files and comparisons follow through
 *                              the database cascade)
 * @param deletedSegments       changelog segments removed, published and provisional alike
 * @param deletedCheckpoints    checkpoint rows removed
 * @param deletedFiles          uploaded files whose S3 objects were collected for deletion
 * @param deletedSqlGenerations plugin SQL generations removed
 * @param deletedErrorLogs      error log rows removed
 * @param deletedBytes          bytes accounted for by the uploaded files and plugin SQL files
 *                              (segments and checkpoints carry no recorded size)
 * @param s3DeleteErrors        objects the bucket refused to delete — orphans, not data loss: the
 *                              rows naming them are already gone
 * @param baselineBatchDetached whether a plugin activation's {@code baseline_batch_id} pointed at a
 *                              destroyed batch and had to be nulled
 */
public record SiteHistoryWipeSummary(
        long generation,
        int deletedBatches,
        int deletedSegments,
        int deletedCheckpoints,
        int deletedFiles,
        int deletedSqlGenerations,
        int deletedErrorLogs,
        long deletedBytes,
        int s3DeleteErrors,
        boolean baselineBatchDetached) {
}
