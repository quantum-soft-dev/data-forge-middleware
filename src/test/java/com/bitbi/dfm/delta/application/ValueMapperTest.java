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
