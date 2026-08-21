package com.bitbi.dfm.delta.application;

/**
 * Raised when a writer's next bytes would take the shared scratch <em>directory</em> past
 * {@code delta.parquet.max-scratch-bytes} (issue #150), or — for a batch writer — past that
 * budget minus the checkpoint reserved share (issue #193).
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
        this(writer, neededBytes, budgetBytes, budgetBytes, 0L, liveBytes);
    }

    ScratchBudgetExceededException(String writer, long neededBytes, long writerCeiling,
                                   long budgetBytes, long reservedBytes, long liveBytes) {
        super(message(writer, neededBytes, writerCeiling, budgetBytes, reservedBytes, liveBytes));
    }

    private static String message(String writer, long neededBytes, long writerCeiling,
                                  long budgetBytes, long reservedBytes, long liveBytes) {
        long free = Math.max(0L, writerCeiling - liveBytes);
        StringBuilder text = new StringBuilder();
        text.append("The Parquet scratch directory is full: writer ").append(writer)
                .append(" needed ").append(neededBytes)
                .append(" more bytes and only ").append(free).append(" of the ")
                .append(writerCeiling).append(" bytes this writer may use were free (live writers hold ")
                .append(liveBytes).append("; delta.parquet.max-scratch-bytes is ").append(budgetBytes);
        if (reservedBytes > 0L) {
            text.append(", ").append(reservedBytes)
                    .append(" of which is reserved for a checkpoint frame via "
                            + "delta.checkpoint.max-frame-temp-bytes");
        }
        text.append("). Nothing is wrong with this artifact — raise that key "
                + "together with the volume behind it, or lower "
                + "delta.batch-parquet.max-concurrent");
        return text.toString();
    }
}
