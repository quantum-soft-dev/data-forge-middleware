package com.bitbi.dfm.delta.domain;

/**
 * How a forced out-of-schedule checkpoint rebuild ended (issue #186).
 *
 * <p>Before this existed, {@code site_sync_state.rebuild_requested} was the only record of an
 * operator's click on <em>Rebuild now</em>: raised by the request, released when the attempt
 * settled. Three of the four settling endings ran nothing at all, and from outside the pod they
 * were indistinguishable from the one that worked — the "Rebuild queued" chip vanished and the
 * checkpoints did not change. This is the verdict that tells them apart.</p>
 *
 * <p>A rebuild cut short because the application is shutting down has deliberately <b>no value
 * here</b>: it keeps {@code rebuild_requested} so the next process re-drives it
 * ({@code DeltaCheckpointRebuildService#resumePendingRebuilds}, issue #162), which means the
 * request has not finished and a verdict would contradict the flag that is still up.</p>
 *
 * <p>The name is persisted ({@code EnumType.STRING}) and is published on the sync-state REST
 * projection, so these constants are a wire contract: rename one and the frontend stops
 * recognising it.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public enum CheckpointRebuildOutcome {

    /** The rebuild ran to the end. Nothing to explain, so it carries no message. */
    COMPLETED,

    /**
     * The rebuild threw, or could not be queued at all. The message is the failure's own text; the
     * remedy is to fix what it names and ask again.
     */
    FAILED,

    /**
     * S3 would not say whether the site's seed frame exists, so the build did nothing (issue #157).
     * Nothing durable changed and no attempt was spent; the remedy is a bucket policy or IAM grant,
     * not a retry — see the {@code delta.s3.read-denied} counter.
     */
    FRAME_UNAVAILABLE,

    /**
     * Another checkpoint build held the process's fold budget for longer than
     * {@code delta.checkpoint.fold-wait-seconds}, so this one never folded anything (issue #178).
     * It repairs itself: ask again once the neighbouring build has finished.
     */
    DEFERRED,

    /**
     * The site's baseline was replaced while the rebuild was running — a history wipe or a
     * FULL_SNAPSHOT re-baseline (issues #136, #142) — so {@code CheckpointEpochGuard} refused every
     * write and nothing was published. Not a failure: the new baseline is what the next build
     * starts from. Ask again if the rebuild is still wanted.
     */
    DISCARDED,

    /**
     * The site has neither a checkpoint frame nor a changelog segment, so there was nothing to
     * rebuild its checkpoints from. Reported instead of {@code COMPLETED} because a rebuild that
     * had no source did not rebuild anything (issue #186).
     */
    NOTHING_TO_REBUILD
}
