package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #369 — the one reading of {@code ChangeRecord.row_hash} every consumer shares: present means
 * exactly 32 bytes, anything else is absent, and the Parquet form is lowercase hex.
 */
class RowHashTest {

    private static final ByteString HASH = hashOf(0xAB);

    @Test
    void aThirtyTwoByteValueIsTheRowsHash() {
        assertEquals(HASH, RowHash.of(record(HASH)));
        assertFalse(RowHash.isMalformed(record(HASH)));
    }

    @Test
    void anEmptyValueIsAnOlderClientAndNotMalformed() {
        assertNull(RowHash.of(record(ByteString.EMPTY)));
        assertFalse(RowHash.isMalformed(record(ByteString.EMPTY)),
                "an older client sends nothing, which is the absent hash and not a defect");
    }

    @Test
    void anyOtherLengthIsMalformedAndReadsAsAbsent() {
        for (int length : new int[] {1, 31, 33, 64}) {
            ChangeRecord record = record(ByteString.copyFrom(new byte[length]));
            assertTrue(RowHash.isMalformed(record), "length " + length);
            assertNull(RowHash.of(record), "length " + length + " must never reach a consumer");
        }
    }

    @Test
    void withoutAMalformedHashTheRecordIsLeftAlone() {
        ChangeRecord valid = record(HASH);
        assertSame(valid, RowHash.dropMalformed(valid));
        ChangeRecord absent = record(ByteString.EMPTY);
        assertSame(absent, RowHash.dropMalformed(absent));
    }

    @Test
    void aMalformedHashIsDroppedAndNothingElseIsTouched() {
        ChangeRecord malformed = record(ByteString.copyFrom(new byte[31]));
        ChangeRecord dropped = RowHash.dropMalformed(malformed);
        assertTrue(dropped.getRowHash().isEmpty());
        assertEquals(malformed.toBuilder().clearRowHash().build(), dropped);
    }

    @Test
    void theParquetFormIsSixtyFourLowercaseHexCharacters() {
        ByteString hash = ByteString.copyFrom(new byte[] {
                (byte) 0x00, (byte) 0x0F, (byte) 0xA0, (byte) 0xFF, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
                13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, (byte) 0xBE});
        String hex = RowHash.hex(hash);
        assertEquals("000fa0ff0102030405060708090a0b0c0d0e0f101112131415161718191a1bbe", hex);
        assertEquals(64, hex.length());
        assertNull(RowHash.hex(null), "no hash is a null cell, never an empty string");
    }

    static ByteString hashOf(int fill) {
        byte[] bytes = new byte[RowHash.LENGTH];
        for (int at = 0; at < bytes.length; at++) {
            bytes[at] = (byte) (fill + at);
        }
        return ByteString.copyFrom(bytes);
    }

    private static ChangeRecord record(ByteString rowHash) {
        return ChangeRecord.newBuilder().setTable("t").setOp(Op.INSERT).setSeq(1).setRowHash(rowHash).build();
    }
}
