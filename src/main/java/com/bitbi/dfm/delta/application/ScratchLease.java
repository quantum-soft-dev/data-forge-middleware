package com.bitbi.dfm.delta.application;

/**
 * One writer's share of the shared Parquet scratch directory, held for the life of its file
 * (issue #150).
 *
 * <p>Handed out by {@link ParquetScratchBudget} and charged by the two counting output streams —
 * {@link CappedOutputStream} for the checkpoint frame and {@code FileOutputFile} for every Parquet
 * artifact — so the directory's total is maintained by the same code that already maintains each
 * file's own.</p>
 *
 * <p>The lease's life is the <b>file's</b>, not the stream's: a completed-batch build closes every
 * writer and only then uploads them one at a time, so the bytes are still on disk long after the
 * last write. Close it in the same {@code finally} that deletes the file.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public interface ScratchLease extends AutoCloseable {

    /**
     * Account for {@code delta} more bytes landing in the scratch directory.
     *
     * @param delta bytes about to be written; non-positive values are ignored
     * @throws ScratchBudgetExceededException when the directory has no room for them
     */
    void charge(long delta);

    /** Release everything this lease reserved. Idempotent, and never throws. */
    @Override
    void close();
}
