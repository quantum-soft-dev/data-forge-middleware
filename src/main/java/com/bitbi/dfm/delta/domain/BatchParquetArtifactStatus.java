package com.bitbi.dfm.delta.domain;

import java.util.EnumSet;
import java.util.Set;

/** Durable lifecycle of one unified batch/table Parquet artifact (036, issue #93). */
public enum BatchParquetArtifactStatus {
    /** Queued, never claimed. */
    PENDING,
    /** Claimed by a worker. The claim is committed before the build starts, so it survives a crash
     *  and is only reclaimed once the build lease expires. */
    BUILDING,
    /** The S3 object is complete and its metadata published. */
    READY,
    /** Attempt failed, attempts remain — the worker will rebuild it. */
    FAILED,
    /** Attempts exhausted. Terminal: no worker claims it again and the download answers 404. */
    ABANDONED;

    /**
     * The statuses in which a build attempt is still owed, so the batch's raw changelog segments
     * are still needed (issue #244).
     *
     * <p>Changelog retention holds a below-checkpoint segment back while its batch has a row in one
     * of these: the 036/038 finalization replays the batch's raw segments on every retry, and a
     * pruned segment set makes that replay fail permanently — or, worse, silently truncate, since
     * the row-count guard derives its expectation from the segments actually loaded. Unlike the two
     * queue markers of #212 this hold-back is <b>bounded by construction</b>: a row leaves this set
     * after {@code delta.batch-parquet.max-attempts} attempts (~1 h), so it cannot pin storage
     * indefinitely. {@code READY} and {@code ABANDONED} are terminal and prunable — an
     * {@code ABANDONED} row requeued later (039) and the legacy backfill (037) are the two windows
     * this predicate deliberately does not cover, and both report the unproducible artifact
     * explicitly instead of retrying it into the same ending.</p>
     */
    public static final Set<BatchParquetArtifactStatus> UNFINISHED =
            Set.copyOf(EnumSet.of(PENDING, BUILDING, FAILED));
}
