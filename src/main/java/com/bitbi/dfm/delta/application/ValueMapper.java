package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.Value;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps the Protobuf {@link Value} wire type to typed Java values (Delta Client v2 — 022).
 *
 * <p>SQL NULL is represented by {@code is_null = true} (or an unset value) and maps to a Java
 * {@code null}. The <em>absent</em> case (an unchanged column in an UPDATE) is represented by the
 * key being missing from the {@code data} map and is therefore handled at the map level, not here:
 * {@link #toMap(Map)} keeps present-null columns in the result (with a {@code null} value) while
 * absent columns never appear, so {@code containsKey} distinguishes the two.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public final class ValueMapper {

    private ValueMapper() {
    }

    /**
     * Convert a single Protobuf {@link Value} to its typed Java representation.
     *
     * <p>A {@code decimal_value} that {@link BigDecimal} cannot parse yields {@code null} rather
     * than throwing (issue #215). Two different clients produce one: PostgreSQL {@code numeric}
     * legitimately holds {@code NaN} and {@code ±Infinity} and the extractor sends them as those
     * tokens, and a malformed token is possible from any client. Parquet DECIMAL is a scaled
     * integer with no representation for a non-finite value, so the pipeline cannot store it under
     * the column's declared type — but throwing here cost far more than the value: every consumer
     * of this mapper catches per table, so one such cell discarded the whole table's delta file,
     * failed its checkpoint snapshot (spending a {@code materialize_attempts} towards #149's
     * permanent give-up) and stopped the batch's Bit BI SQL.</p>
     *
     * <p>The degradation is deliberately <em>not</em> silent: {@link #isNonFiniteDecimal(Value)}
     * lets a caller that has the site/table context tell this apart from a real SQL NULL and report
     * it. A caller that does not ask still gets a row instead of an exception.</p>
     *
     * @param value the wire value
     * @return typed Java value, or {@code null} for SQL NULL / unset / an unrepresentable decimal
     */
    public static Object toJava(Value value) {
        return switch (value.getVCase()) {
            case INT_VALUE -> value.getIntValue();
            case DOUBLE_VALUE -> value.getDoubleValue();
            case STRING_VALUE -> value.getStringValue();
            case BOOL_VALUE -> value.getBoolValue();
            case DECIMAL_VALUE -> parseDecimal(value.getDecimalValue());
            case BYTES_VALUE -> value.getBytesValue().toByteArray();
            case IS_NULL, V_NOT_SET -> null;
        };
    }

    /**
     * Whether this value is a decimal carrying one of PostgreSQL's three non-finite {@code numeric}
     * values, which {@link #toJava(Value)} degrades to {@code null}.
     *
     * <p>Deliberately narrower than "{@code toJava} returned null": a real SQL NULL, an unset value
     * and a <em>malformed</em> decimal all answer {@code false}. The three cases want different
     * responses from an operator — nothing to do, nothing to do, and a client sending nonsense —
     * so a counter keyed on this must not conflate them.</p>
     *
     * <p>The three <em>values</em> are matched through more spellings than PostgreSQL itself emits,
     * because the token is whatever the client chose to format: any casing, {@code inf} as well as
     * {@code infinity}, and an optional sign on both — see {@link #isNonFiniteToken(String)}.</p>
     *
     * @param value the wire value
     * @return {@code true} for {@code NaN}, {@code Infinity} or {@code -Infinity} in any spelling
     *         this pipeline recognises, the signed {@code NaN} included (issue #238)
     */
    public static boolean isNonFiniteDecimal(Value value) {
        return value.getVCase() == Value.VCase.DECIMAL_VALUE
                && isNonFiniteToken(value.getDecimalValue());
    }

    /**
     * PostgreSQL emits these as {@code NaN} / {@code Infinity} / {@code -Infinity} and accepts them
     * case-insensitively, with an optional sign on the infinities only — {@code '-NaN'::numeric} is
     * a syntax error, so a signed NaN never comes from a faithful {@code numeric} round trip.
     * It is matched anyway, because the token reaching us is whatever the client chose to format,
     * which is the same premise that makes the sign stripped for infinity. The sign is therefore
     * stripped before the NaN test too: {@code -NaN} used to fall through to
     * {@link #isMalformedDecimal(Value)} and be counted as a client defect that does not exist
     * (issue #238).
     */
    private static boolean isNonFiniteToken(String token) {
        return canonicalNonFinite(token) != null;
    }

    /**
     * The canonical spelling of a non-finite token, or {@code null} when the token is not one.
     *
     * <p>Package-private because {@link ChangelogFold} needs the same vocabulary to canonicalise a
     * decimal key's fold identity, and a second copy of it is what issue #238 was: the identical
     * sign-handling slip existed in both, and nothing made them agree. A future spelling added
     * here — or a change to the trim or sign rule — would otherwise leave the fold returning the raw
     * token as identity for a value this class calls non-finite, folding one source row into two.</p>
     *
     * <p>PostgreSQL has a single NaN and rejects the signed input, so the sign is dropped rather
     * than preserved; for the infinities it decides the answer.</p>
     *
     * @param token the wire token
     * @return {@code "NaN"}, {@code "Infinity"} or {@code "-Infinity"}, or {@code null}
     */
    static String canonicalNonFinite(String token) {
        String trimmed = token.trim();
        boolean negative = trimmed.startsWith("-");
        String unsigned = negative || trimmed.startsWith("+") ? trimmed.substring(1) : trimmed;
        if (unsigned.equalsIgnoreCase("nan")) {
            return "NaN";
        }
        if (unsigned.equalsIgnoreCase("infinity") || unsigned.equalsIgnoreCase("inf")) {
            return negative ? "-Infinity" : "Infinity";
        }
        return null;
    }

    /**
     * Whether this value is a decimal token {@link BigDecimal} cannot parse and which is <em>not</em>
     * one of PostgreSQL's non-finite spellings — i.e. a client sending nonsense (issue #215, review
     * round 1).
     *
     * <p>Split from {@link #isNonFiniteDecimal(Value)} because the two want opposite responses: a
     * non-finite value is legal at the source and this pipeline simply cannot store it, while a
     * malformed one is a client defect somebody has to fix. Before #215 a malformed token threw and
     * was therefore loud; degrading it to NULL without a signal of its own would have traded one
     * defect for a quieter one.</p>
     *
     * @param value the wire value
     * @return {@code true} for a decimal token that is neither parseable nor non-finite
     */
    public static boolean isMalformedDecimal(Value value) {
        return value.getVCase() == Value.VCase.DECIMAL_VALUE
                && !isNonFiniteToken(value.getDecimalValue())
                && parseDecimal(value.getDecimalValue()) == null;
    }

    /**
     * Whether this value was present on the wire but has no representation this pipeline can store,
     * so {@link #toJava(Value)} degraded it to {@code null}.
     *
     * <p>The union of {@link #isNonFiniteDecimal(Value)} and {@link #isMalformedDecimal(Value)}, and
     * the predicate a caller wants when the distinction between "absent", "SQL NULL" and "we lost
     * it" decides correctness rather than reporting — the key columns of a CDC statement, where a
     * degraded value silently produces a {@code WHERE col = NULL} that matches no row.</p>
     *
     * @param value the wire value
     * @return {@code true} for a present decimal this pipeline cannot store
     */
    public static boolean isUnrepresentable(Value value) {
        return value.getVCase() == Value.VCase.DECIMAL_VALUE
                && parseDecimal(value.getDecimalValue()) == null;
    }

    private static BigDecimal parseDecimal(String token) {
        try {
            return new BigDecimal(token);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Convert a Protobuf value map to a typed Java map. Present columns (including present-null)
     * are retained; absent columns are not in the input and stay absent in the output.
     *
     * @param values proto column → value map
     * @return ordered map of column → typed Java value (values may be {@code null})
     */
    public static Map<String, Object> toMap(Map<String, Value> values) {
        Map<String, Object> result = new LinkedHashMap<>(values.size());
        for (Map.Entry<String, Value> entry : values.entrySet()) {
            result.put(entry.getKey(), toJava(entry.getValue()));
        }
        return result;
    }
}
