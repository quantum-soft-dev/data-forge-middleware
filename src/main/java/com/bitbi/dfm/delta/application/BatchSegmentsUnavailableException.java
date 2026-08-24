package com.bitbi.dfm.delta.application;

import java.util.UUID;

/**
 * The batch's raw changelog segments are gone, so its completed-batch Parquet can no longer be
 * produced (issue #244).
 *
 * <p>The 036/038 finalization replays a batch's raw segments on every attempt. Since #244 changelog
 * retention holds those segments back while an artifact row is
 * {@link com.bitbi.dfm.delta.domain.BatchParquetArtifactStatus#UNFINISHED}, so a claimed build
 * normally finds them — this is the ending of the two windows that hold-back cannot cover: an
 * {@code ABANDONED} row requeued long afterwards (039) and the legacy lazy backfill (037), where no
 * unfinished row existed while retention ran. Batch retention (the site's {@code retentionDays})
 * and a history wipe or re-baseline take the segments regardless.</p>
 *
 * <p>It is a <b>permanent</b> failure: nothing re-creates a pruned segment, so retrying it to
 * {@code delta.batch-parquet.max-attempts} would reach the same {@code ABANDONED} an hour later
 * with the reason buried under the retries. The records themselves are not lost — they are in the
 * site's checkpoint — only this per-batch artifact is.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public class BatchSegmentsUnavailableException extends RuntimeException {

    public BatchSegmentsUnavailableException(UUID batchId) {
        super("Batch " + batchId + " has no published changelog segments: they were pruned "
                + "(changelog or batch retention, a history wipe or a re-baseline), so this "
                + "artifact can no longer be produced — the records remain available through the "
                + "site's checkpoint");
    }
}
