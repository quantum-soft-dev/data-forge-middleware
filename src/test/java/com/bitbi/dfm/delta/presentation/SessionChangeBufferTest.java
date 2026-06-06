package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.presentation.SessionChangeBuffer.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T2.2 — idempotency + contiguity: a session change buffer accepts only the next contiguous sequence,
 * ignoring duplicates/replays and flagging a gap when a sequence is skipped.
 */
class SessionChangeBufferTest {

    @Test
    void acceptsContiguousIncreasingSeq() {
        SessionChangeBuffer buffer = new SessionChangeBuffer(0L);

        assertEquals(Result.ACCEPTED, buffer.accept(change(1L)));
        assertEquals(Result.ACCEPTED, buffer.accept(change(2L)));
        assertEquals(Result.ACCEPTED, buffer.accept(change(3L)));

        assertEquals(3, buffer.acceptedCount());
        assertEquals(3L, buffer.lastSeq());
    }

    @Test
    void ignoresDuplicateSeq() {
        SessionChangeBuffer buffer = new SessionChangeBuffer(0L);
        buffer.accept(change(1L));
        buffer.accept(change(2L));

        assertEquals(Result.DUPLICATE, buffer.accept(change(2L)), "duplicate seq must be ignored");

        assertEquals(2, buffer.acceptedCount());
        assertEquals(2L, buffer.lastSeq());
    }

    @Test
    void ignoresOutOfOrderOrAlreadyAppliedSeq() {
        SessionChangeBuffer buffer = new SessionChangeBuffer(5L); // server watermark = 5

        assertEquals(Result.DUPLICATE, buffer.accept(change(3L)), "seq below watermark is a replay");
        assertEquals(Result.DUPLICATE, buffer.accept(change(5L)), "seq equal to watermark is a replay");
        assertEquals(Result.ACCEPTED, buffer.accept(change(6L)));

        assertEquals(1, buffer.acceptedCount());
        assertEquals(6L, buffer.lastSeq());
        assertEquals(6L, buffer.accepted().get(0).getSeq());
    }

    @Test
    void flagsSequenceGapWithoutAdvancing() {
        SessionChangeBuffer buffer = new SessionChangeBuffer(0L);
        assertEquals(Result.ACCEPTED, buffer.accept(change(1L)));

        assertEquals(Result.GAP, buffer.accept(change(3L)), "seq 2 was skipped");

        assertEquals(1, buffer.acceptedCount(), "a gap record is not retained");
        assertEquals(1L, buffer.lastSeq(), "a gap does not advance the watermark");
    }

    private static ChangeRecord change(long seq) {
        return ChangeRecord.newBuilder().setSeq(seq).setTable("t").setOp(Op.INSERT).build();
    }
}
