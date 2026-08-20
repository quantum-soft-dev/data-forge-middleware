package com.bitbi.dfm.plugin.application;

/**
 * Fail-fast range checks for the {@code plugin.sql-generation.*} block (issue #185): a consuming
 * bean validates its keys in its constructor, so an out-of-range value throws and the Spring
 * context refuses to start. Fail fast rather than a startup WARN is an owner decision recorded on
 * the ticket — the reasoning lives in {@code docs/020-sql-generation-optimization.md} ("One caveat
 * on 'unbounded retry is safe'"), not here, so it has one home.
 *
 * <p>The message names the configuration key and the offending value ("but was N"): the crash-loop
 * log line is the whole of what an operator gets to diagnose a failed rollout with. That promise
 * holds for a well-formed integer out of range; a value Spring cannot convert at all ({@code "80%"},
 * or an env var present but empty — {@code ${VAR:80}} does not default for {@code ""}) dies earlier,
 * during {@code @Value} conversion, naming the constructor parameter rather than the key.</p>
 *
 * <p>Package-private on purpose: the block's consumers ({@link SqlGenerationService},
 * {@link DeltaSqlSweepWorker}) live in this package, and the delta packages keep their own
 * constructor checks ({@code CheckpointRetryProperties}, {@code DeltaS3OrphanSweeper},
 * {@code ParquetScratchOrphanSweeper}, {@code DeltaParquetProperties}) — sharing across aggregates
 * would couple them for one {@code if}.</p>
 */
final class PluginConfigValidation {

    private PluginConfigValidation() {
    }

    /**
     * Returns {@code value} when it lies in {@code [min, max]}, otherwise throws an
     * {@link IllegalArgumentException} naming the key, the value and the consequence.
     */
    static int requireInRange(String key, int value, int min, int max, String consequence) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max
                    + ", but was " + value + ". Refusing to start: " + consequence + " (issue #185).");
        }
        return value;
    }

    /**
     * The {@code int} form of {@link #requireAtLeast(String, long, long, String)}.
     */
    static int requireAtLeast(String key, int value, int min, String consequence) {
        requireAtLeast(key, (long) value, (long) min, consequence);
        return value;
    }

    /**
     * The one-sided form of {@link #requireInRange(String, int, int, int, String)}.
     */
    static long requireAtLeast(String key, long value, long min, String consequence) {
        if (value < min) {
            throw new IllegalArgumentException(key + " must be at least " + min + ", but was "
                    + value + ". Refusing to start: " + consequence + " (issue #185).");
        }
        return value;
    }
}
