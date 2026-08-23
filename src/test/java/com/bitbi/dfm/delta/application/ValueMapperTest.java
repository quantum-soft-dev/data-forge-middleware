package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.Value;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T2.3 — typed mapping of the Protobuf {@link Value} to Java, distinguishing present-null from
 * absent and preserving decimal exactness.
 */
class ValueMapperTest {

    @Test
    void mapsScalarTypes() {
        assertEquals(42L, ValueMapper.toJava(Value.newBuilder().setIntValue(42L).build()));
        assertEquals(3.5d, ValueMapper.toJava(Value.newBuilder().setDoubleValue(3.5d).build()));
        assertEquals("hi", ValueMapper.toJava(Value.newBuilder().setStringValue("hi").build()));
        assertEquals(Boolean.TRUE, ValueMapper.toJava(Value.newBuilder().setBoolValue(true).build()));
    }

    @Test
    void mapsExplicitNull() {
        assertNull(ValueMapper.toJava(Value.newBuilder().setIsNull(true).build()));
    }

    @Test
    void emptyValueMapsToNull() {
        assertNull(ValueMapper.toJava(Value.getDefaultInstance()));
    }

    @Test
    void decimalIsExact() {
        String exact = "12345678901234567890.123456789";
        Object mapped = ValueMapper.toJava(Value.newBuilder().setDecimalValue(exact).build());
        assertEquals(new BigDecimal(exact), mapped);
    }

    /**
     * PostgreSQL {@code numeric} holds {@code NaN} and {@code ±Infinity}, and since
     * PostgreSQL-data-extractor#86 the client sends them as {@code decimal_value} tokens. Parquet
     * DECIMAL is a scaled integer and has no representation for any of them, so the pipeline cannot
     * store the value — but it must not lose the row, the table's file or the whole checkpoint over
     * it, which is what `new BigDecimal("Infinity")` throwing used to cost (issue #215).
     */
    @Test
    void nonFiniteDecimalDegradesToNullInsteadOfThrowing() {
        for (String token : new String[]{"Infinity", "-Infinity", "NaN", "+Infinity", "infinity", "nan",
                "+NaN", "-NaN"}) {
            Value value = Value.newBuilder().setDecimalValue(token).build();
            assertNull(ValueMapper.toJava(value), token + " must map to null, not throw");
            assertTrue(ValueMapper.isNonFiniteDecimal(value), token + " must be reported as non-finite");
        }
    }

    /**
     * The degraded null has to be distinguishable from a real SQL NULL, otherwise a caller cannot
     * count what it lost and "loud" is impossible.
     */
    @Test
    void aRealNullIsNotReportedAsNonFinite() {
        assertFalse(ValueMapper.isNonFiniteDecimal(Value.newBuilder().setIsNull(true).build()));
        assertFalse(ValueMapper.isNonFiniteDecimal(Value.getDefaultInstance()));
        assertFalse(ValueMapper.isNonFiniteDecimal(Value.newBuilder().setDecimalValue("1.5").build()));
        assertFalse(ValueMapper.isNonFiniteDecimal(Value.newBuilder().setStringValue("NaN").build()));
    }

    /**
     * A signed NaN is a legal source value this pipeline cannot store, not a client defect. The two
     * counters are read for opposite reasons — {@code non_finite} says the pipeline lost a value it
     * cannot hold, {@code malformed} is defined as "a client defect somebody has to fix" — so
     * classifying {@code -NaN} as malformed pages someone to chase a bug that does not exist, which
     * is the outcome the split was added to prevent (issue #238).
     */
    @Test
    void aSignedNanIsNonFiniteRatherThanMalformed() {
        for (String token : new String[]{"+NaN", "-NaN", "+nan", "-NAN", " -NaN "}) {
            Value value = Value.newBuilder().setDecimalValue(token).build();
            assertTrue(ValueMapper.isNonFiniteDecimal(value), token + " must be reported as non-finite");
            assertFalse(ValueMapper.isMalformedDecimal(value), token + " is not a client defect");
            assertTrue(ValueMapper.isUnrepresentable(value), token + " is still unrepresentable");
        }
    }

    /**
     * A token that is neither finite nor one of the three non-finite spellings is a malformed
     * decimal, not a representable-value problem: it still must not throw out of the mapper (that is
     * what cost the table its file), but it must not be counted as non-finite either, since the
     * remedy differs -- one is a client sending a legal value we cannot store, the other is a client
     * sending nonsense.
     */
    @Test
    void malformedDecimalDegradesToNullButIsNotCalledNonFinite() {
        Value value = Value.newBuilder().setDecimalValue("not-a-number").build();
        assertNull(ValueMapper.toJava(value));
        assertFalse(ValueMapper.isNonFiniteDecimal(value));
    }

    /**
     * {@link #isNonFiniteToken} trims; {@code parseDecimal} used to be handed the token raw, and
     * {@link BigDecimal} rejects surrounding whitespace. So {@code " 1.5 "} — a perfectly legal
     * number, and a shape {@link ChangelogFold} already retries trimmed so it folds with {@code "1.5"}
     * as one row — was unparseable, degraded to NULL, and counted
     * {@code reason=malformed}: silent loss of a value this pipeline can represent exactly, in the
     * counter defined as "a client defect somebody has to fix" (issue #240, evidence from #238).
     */
    @Test
    void aPaddedFiniteDecimalIsKeptRatherThanCountedMalformed() {
        for (String token : new String[]{" 1.5 ", "1.5 ", " 1.5", "\t12.50\n"}) {
            Value value = Value.newBuilder().setDecimalValue(token).build();
            assertEquals(new BigDecimal(token.trim()), ValueMapper.toJava(value),
                    token + " is a finite number this pipeline can store");
            assertFalse(ValueMapper.isMalformedDecimal(value),
                    token + " is not a client defect");
            assertFalse(ValueMapper.isNonFiniteDecimal(value));
            assertFalse(ValueMapper.isUnrepresentable(value));
        }
    }

    /**
     * The bulk path must degrade the single column and keep every other column of the row.
     */
    @Test
    void toMapKeepsSiblingColumnsWhenOneDecimalIsNonFinite() {
        Map<String, Value> proto = new LinkedHashMap<>();
        proto.put("id", Value.newBuilder().setIntValue(7L).build());
        proto.put("numeric_edge", Value.newBuilder().setDecimalValue("Infinity").build());
        proto.put("label", Value.newBuilder().setStringValue("kept").build());

        Map<String, Object> mapped = ValueMapper.toMap(proto);

        assertEquals(3, mapped.size(), "the row keeps all three columns");
        assertEquals(7L, mapped.get("id"));
        assertNull(mapped.get("numeric_edge"));
        assertTrue(mapped.containsKey("numeric_edge"), "present-but-degraded stays present");
        assertEquals("kept", mapped.get("label"));
    }

    @Test
    void mapDistinguishesPresentNullFromAbsent() {
        Map<String, Value> proto = new LinkedHashMap<>();
        proto.put("a", Value.newBuilder().setIntValue(1L).build());
        proto.put("b", Value.newBuilder().setIsNull(true).build());

        Map<String, Object> result = ValueMapper.toMap(proto);

        assertEquals(1L, result.get("a"));
        assertTrue(result.containsKey("b"), "present column must stay in the map");
        assertNull(result.get("b"), "present-null column maps to null");
        assertFalse(result.containsKey("c"), "absent column must not appear");
    }
}
