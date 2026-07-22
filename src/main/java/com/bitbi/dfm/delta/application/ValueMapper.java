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
     * @param value the wire value
     * @return typed Java value, or {@code null} for SQL NULL / unset
     */
    public static Object toJava(Value value) {
        return switch (value.getVCase()) {
            case INT_VALUE -> value.getIntValue();
            case DOUBLE_VALUE -> value.getDoubleValue();
            case STRING_VALUE -> value.getStringValue();
            case BOOL_VALUE -> value.getBoolValue();
            case DECIMAL_VALUE -> new BigDecimal(value.getDecimalValue());
            case BYTES_VALUE -> value.getBytesValue().toByteArray();
            case IS_NULL, V_NOT_SET -> null;
        };
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
