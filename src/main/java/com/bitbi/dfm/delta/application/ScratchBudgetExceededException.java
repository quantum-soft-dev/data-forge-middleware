package com.bitbi.dfm.delta.application;

/**
 * Raised when a writer's next bytes would take the shared scratch <em>directory</em> past
 * {@code delta.parquet.max-scratch-bytes} (issue #150).
 *
 * <p><b>Deliberately not an {@link ArtifactSizeLimitExceededException}</b>, and not a subclass of
 * one either. That exception is a verdict on the artifact: it is deterministically too large for its
 * own per-file ceiling, so every retry fails identically — which is why the completed-batch writer
 * abandons such an artifact on the first attempt and the checkpoint frame's abort is registered on
 * {@code delta.checkpoint.builds.aborted}, a meter whose contract (#153) is refusals that never
 * repair themselves. This one is the opposite in every respect: the artifact may be perfectly
 * ordinary and the cause is entirely outside it — how much scratch the <em>other</em> live writers
 * happen to be holding at that moment — so it clears as soon as they finish. Sharing the type would
 * have put a transient collision on both of those permanent verdicts, which is the rule #178 settled
 * for the heap twin of this budget.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public final class ScratchBudgetExceededException extends RuntimeException {

    ScratchBudgetExceededException(String writer, long neededBytes, long budgetBytes, long liveBytes) {
        super("The Parquet scratch directory is full: writer " + writer + " needed " + neededBytes
                + " more bytes and only " + Math.max(0L, budgetBytes - liveBytes) + " of the "
                + budgetBytes + " bytes of delta.parquet.max-scratch-bytes were free (live writers "
                + "hold " + liveBytes + "). Nothing is wrong with this artifact — raise that key "
                + "together with the volume behind it, or lower "
                + "delta.batch-parquet.max-concurrent");
    }
}
