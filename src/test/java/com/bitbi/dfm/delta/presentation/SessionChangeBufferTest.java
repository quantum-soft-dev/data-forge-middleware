package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T2.2 — idempotency: a session change buffer accepts only strictly-increasing sequence numbers,
 * ignoring duplicates and out-of-order (replayed) records.
 */
class SessionChangeBufferTest {

    @Test
    void acceptsStrictlyIncreasingSeq() {
        SessionChangeBuffer buffer = new SessionChangeBuffer(0L);

        assertTrue(buffer.accept(change(1L)));
        assertTrue(buffer.accept(change(2L)));
        assertTrue(buffer.accept(change(3L)));

        assertEquals(3, buffer.acceptedCount());
        assertEquals(3L, buffer.lastSeq());
    }

    @Test
    void ignoresDuplicateSeq() {
        SessionChangeBuffer buffer = new SessionChangeBuffer(0L);
        buffer.accept(change(1L));
        buffer.accept(change(2L));

        assertFalse(buffer.accept(change(2L)), "duplicate seq must be ignored");

        assertEquals(2, buffer.acceptedCount());
        assertEquals(2L, buffer.lastSeq());
    }

    @Test
    void ignoresOutOfOrderOrAlreadyAppliedSeq() {
        SessionChangeBuffer buffer = new SessionChangeBuffer(5L); // server watermark = 5

        assertFalse(buffer.accept(change(3L)), "seq below watermark is a replay");
        assertFalse(buffer.accept(change(5L)), "seq equal to watermark is a replay");
        assertTrue(buffer.accept(change(6L)));

        assertEquals(1, buffer.acceptedCount());
        assertEquals(6L, buffer.lastSeq());
        assertEquals(6L, buffer.accepted().get(0).getSeq());
    }

    private static ChangeRecord change(long seq) {
        return ChangeRecord.newBuilder().setSeq(seq).setTable("t").setOp(Op.INSERT).build();
    }
}
