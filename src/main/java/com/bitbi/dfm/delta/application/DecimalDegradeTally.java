package com.bitbi.dfm.delta.application;

/**
 * Counts, for one rendered table, the decimal cells written as NULL because the value has no
 * Parquet DECIMAL representation (issue #215).
 *
 * <p>Two counters rather than one, because the two conditions have opposite remedies and an
 * operator alerting on them wants them apart: {@code nonFinite} is PostgreSQL {@code numeric}
 * holding {@code NaN} or {@code +/-Infinity} — legal at the source, and nothing to repair in this
 * pipeline — while {@code malformed} is a client sending a token {@link java.math.BigDecimal}
 * cannot parse, which somebody has to fix. Before #215 both threw and were therefore loud;
 * degrading them to NULL without keeping them distinguishable would have replaced one defect with
 * a quieter one.</p>
 *
 * <p>A third counter since issue #382, {@code overflow}: a finite value that needs more digits than
 * the widest decimal column a file may carry ({@link ParquetSchemaMapper#MAX_DECIMAL_PRECISION}).
 * Its remedy is a third one — the source data or the declared schema, typically a DBF writer
 * storing a number its field could not hold in exponent form.</p>
 *
 * <p>Not thread-safe and deliberately so: one instance belongs to one writer rendering one table,
 * which is single-threaded, and a shared instance would blur the per-table WARN this exists to
 * produce.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
final class DecimalDegradeTally {

    private long nonFinite;
    private long malformed;
    private long overflow;

    /** A value legal at the source that DECIMAL cannot encode. */
    void nonFinite() {
        nonFinite++;
    }

    /** A token {@code BigDecimal} cannot parse. */
    void malformed() {
        malformed++;
    }

    /** A value that needs more digits than the widest decimal column a file may carry (issue #382). */
    void overflow() {
        overflow++;
    }

    long nonFiniteCount() {
        return nonFinite;
    }

    long malformedCount() {
        return malformed;
    }

    long overflowCount() {
        return overflow;
    }

    /** Whether anything was degraded at all — the test for whether to log or report. */
    boolean any() {
        return nonFinite > 0 || malformed > 0 || overflow > 0;
    }
}
