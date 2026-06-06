package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #12 — SessionEnd.content_hash integrity: the server recomputes a canonical, language-neutral hash
 * over accepted records and rejects a session whose declared hash does not match. A blank declared
 * hash (client did not provide one) is treated as a match.
 */
class ChangelogContentHashTest {

    @Test
    void blankDeclaredHashAlwaysMatches() {
        List<ChangeRecord> records = List.of(rec("t", Op.INSERT, 1, intKey(1), strData("city", "NY")));
        assertTrue(ChangelogContentHash.matches(records, ""));
        assertTrue(ChangelogContentHash.matches(records, "   "));
        assertTrue(ChangelogContentHash.matches(records, null));
    }

    @Test
    void matchesComputedHashCaseInsensitively() {
        List<ChangeRecord> records = List.of(
                rec("t", Op.INSERT, 1, intKey(1), strData("city", "NY")),
                rec("t", Op.UPDATE, 2, intKey(1), strData("city", "LA")));

        String hash = ChangelogContentHash.compute(records);
        assertTrue(ChangelogContentHash.matches(records, hash));
        assertTrue(ChangelogContentHash.matches(records, hash.toUpperCase()));
    }

    @Test
    void detectsTamperedValue() {
        List<ChangeRecord> sent = List.of(rec("t", Op.INSERT, 1, intKey(1), strData("city", "NY")));
        String declared = ChangelogContentHash.compute(sent);

        List<ChangeRecord> tampered = List.of(rec("t", Op.INSERT, 1, intKey(1), strData("city", "LA")));
        assertFalse(ChangelogContentHash.matches(tampered, declared), "a changed value must fail the hash");
    }

    @Test
    void isOrderAndCountSensitive() {
        ChangeRecord a = rec("t", Op.INSERT, 1, intKey(1), strData("c", "x"));
        ChangeRecord b = rec("t", Op.INSERT, 2, intKey(2), strData("c", "y"));
        assertNotEquals(ChangelogContentHash.compute(List.of(a, b)),
                ChangelogContentHash.compute(List.of(b, a)));
        assertNotEquals(ChangelogContentHash.compute(List.of(a)),
                ChangelogContentHash.compute(List.of(a, a)));
    }

    @Test
    void typeTagsPreventValueCollision() {
        // int 1 and the string "1" must not hash the same.
        ChangeRecord asInt = rec("t", Op.INSERT, 1, Map.of("v", Value.newBuilder().setIntValue(1).build()), Map.of());
        ChangeRecord asStr = rec("t", Op.INSERT, 1, Map.of("v", Value.newBuilder().setStringValue("1").build()), Map.of());
        assertNotEquals(ChangelogContentHash.compute(List.of(asInt)),
                ChangelogContentHash.compute(List.of(asStr)));
    }

    private static ChangeRecord rec(String table, Op op, long seq, Map<String, Value> key, Map<String, Value> data) {
        return ChangeRecord.newBuilder().setTable(table).setOp(op).setSeq(seq)
                .putAllKey(key).putAllData(data).build();
    }

    private static Map<String, Value> intKey(long v) {
        return Map.of("id", Value.newBuilder().setIntValue(v).build());
    }

    private static Map<String, Value> strData(String col, String v) {
        return Map.of(col, Value.newBuilder().setStringValue(v).build());
    }
}
