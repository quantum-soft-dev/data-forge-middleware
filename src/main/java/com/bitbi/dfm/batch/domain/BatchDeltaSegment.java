package com.bitbi.dfm.batch.domain;

import java.util.Map;

/**
 * What one committed changelog segment adds to its batch's totals (issue #346).
 * <p>
 * A segment is a working unit of the delta queues and of retention, and
 * {@code ChangelogRetentionService.prune} deletes it once a checkpoint covers it; the batch's
 * history must outlive that, so each segment's contribution is added to the batch in the
 * transaction that commits the segment.
 * </p>
 *
 * @param recordCount records in the segment
 * @param firstSeq    first sequence number in the segment
 * @param lastSeq     last sequence number in the segment
 * @param stats       per-table counts, or {@code null} when the segment carries none
 * @author Data Forge Team
 * @version 1.0.0
 */
public record BatchDeltaSegment(long recordCount, long firstSeq, long lastSeq,
                                Map<String, BatchTableStats> stats) {
}
