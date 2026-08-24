package com.bitbi.dfm.delta.domain;

/**
 * How a scheduled checkpoint build aborted before writing anything (issue #224).
 *
 * <p>A forced rebuild already leaves {@link CheckpointRebuildOutcome}. The nightly tick did not:
 * every whole-site abort ({@code frame_too_large}, a fold over the heap budget, a deferral behind
 * the process fold budget, an S3 read denial) writes no {@code checkpoints} row and leaves
 * {@code last_checkpoint_seq} at zero, so a first build that has failed thirty nights is
 * indistinguishable from one that is not due yet. This is the abort that tells them apart, and
 * it is written only on the abort path — a healthy build advances the pointer and does not
 * touch these columns.</p>
 *
 * <p>The name is persisted ({@code EnumType.STRING}) and is published on the sync-state REST
 * projection, so these constants are a wire contract: rename one and the frontend stops
 * recognising it.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public enum CheckpointBuildAbort {

    /**
     * The build threw for a reason that is not one of the named aborts below. The message is the
     * failure's own text.
     */
    FAILED,

    /**
     * The site's folded state grew past {@code delta.checkpoint.max-fold-bytes} (issue #152). The
     * next tick fails identically, because the site's history has not shrunk.
     */
    FOLD_TOO_LARGE,

    /**
     * The reload frame crossed {@code delta.checkpoint.max-frame-temp-bytes} (issue #153). The
     * next tick fails identically, because the fold has not changed.
     */
    FRAME_TOO_LARGE,

    /**
     * The shared Parquet scratch directory was full (issue #150). Contention, not a fact about
     * the site — the next tick tries again once neighbouring writers finish.
     */
    SCRATCH_FULL,

    /**
     * S3 would not say whether the site's seed frame exists, so the build did nothing
     * (issue #157). Nothing durable changed; the remedy is a bucket policy or IAM grant.
     */
    FRAME_UNAVAILABLE,

    /**
     * Another checkpoint build held the process's fold budget for longer than
     * {@code delta.checkpoint.fold-wait-seconds}, so this one never folded anything
     * (issue #178). It repairs itself on the next tick.
     */
    DEFERRED
}
