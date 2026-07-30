package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #12 — SessionEnd.content_hash integrity: the server recomputes a canonical, language-neutral hash
 * over accepted records and rejects a session whose declared hash does not match. A blank declared
 * hash (client did not provide one) is treated as a match.
 */
class ChangelogContentHashTest {

    /** Group separator used inside the canonical form (0x1D). */
    private static final String GS = String.valueOf((char) 0x1D);

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

    @Test
    void doubleEncodingIsLanguageNeutralBitPattern() {
        // The canonical form must not depend on JVM Double.toString (E-notation "1.0E7"), which no
        // other language reproduces. Encoding the IEEE-754 bit pattern is reproducible anywhere; pin
        // it so a cross-language client hashing 1e7 agrees (review r4). A double that is bitwise
        // identical hashes the same; the encoding does not embed the JVM decimal string.
        ChangeRecord r = rec("t", Op.INSERT, 1,
                Map.of("v", Value.newBuilder().setDoubleValue(1e7).build()), Map.of());
        long bits = Double.doubleToLongBits(1e7);
        ChangeRecord viaBits = rec("t", Op.INSERT, 1,
                Map.of("v", Value.newBuilder().setDoubleValue(Double.longBitsToDouble(bits)).build()), Map.of());
        assertTrue(ChangelogContentHash.matches(List.of(viaBits), ChangelogContentHash.compute(List.of(r))));
    }

    @Test
    void separatorBytesInValueCannotForgeAnotherRecordSet() {
        // A value carrying the group separator (0x1D) is crafted to reproduce the UNPREFIXED byte
        // stream of two columns a="x" (GS) b="y". Length-prefixed encoding must defeat the forgery.
        ChangeRecord oneCol = rec("t", Op.INSERT, 1,
                Map.of("a", Value.newBuilder().setStringValue("x" + GS + "b=Sy").build()), Map.of());
        ChangeRecord twoCol = rec("t", Op.INSERT, 1,
                Map.of("a", Value.newBuilder().setStringValue("x").build(),
                       "b", Value.newBuilder().setStringValue("y").build()), Map.of());
        assertNotEquals(ChangelogContentHash.compute(List.of(oneCol)),
                ChangelogContentHash.compute(List.of(twoCol)),
                "separator bytes in a value must not collide with a structurally different record");
    }

    @Test
    void incrementalHasherMatchesWholeListDigest() {
        // 033: a segmented re-baseline seals mid-session, so the whole record list is never in
        // memory at SessionEnd. Feeding the hasher chunk by chunk must reproduce the canonical
        // digest byte-for-byte, or every client's declared content_hash breaks.
        List<ChangeRecord> all = List.of(
                rec("t", Op.INSERT, 1, intKey(1), strData("city", "NY")),
                rec("t", Op.UPDATE, 2, intKey(1), strData("city", "LA")),
                rec("u", Op.DELETE, 3, intKey(2), Map.of()));

        ChangelogContentHash.Hasher hasher = new ChangelogContentHash.Hasher();
        hasher.update(all.subList(0, 1));
        hasher.update(all.subList(1, 3));

        assertEquals(ChangelogContentHash.compute(all), hasher.hex());
    }

    @Test
    void incrementalHasherIsChunkBoundaryIndependent() {
        List<ChangeRecord> all = List.of(
                rec("t", Op.INSERT, 1, intKey(1), strData("c", "a")),
                rec("t", Op.INSERT, 2, intKey(2), strData("c", "b")),
                rec("t", Op.INSERT, 3, intKey(3), strData("c", "c")),
                rec("t", Op.INSERT, 4, intKey(4), strData("c", "d")));

        ChangelogContentHash.Hasher oneAtATime = new ChangelogContentHash.Hasher();
        all.forEach(r -> oneAtATime.update(List.of(r)));

        ChangelogContentHash.Hasher lopsided = new ChangelogContentHash.Hasher();
        lopsided.update(all.subList(0, 3));
        lopsided.update(List.of());
        lopsided.update(all.subList(3, 4));

        assertEquals(ChangelogContentHash.compute(all), oneAtATime.hex());
        assertEquals(ChangelogContentHash.compute(all), lopsided.hex());
    }

    @Test
    void incrementalHasherOfNothingEqualsEmptyDigest() {
        assertEquals(ChangelogContentHash.compute(List.of()), new ChangelogContentHash.Hasher().hex());
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
