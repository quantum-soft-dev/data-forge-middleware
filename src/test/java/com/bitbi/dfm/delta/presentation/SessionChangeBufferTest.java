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

    @Test
    void flagsOverflowWhenSessionExceedsTheRecordCap() {
        // A single session must not buffer unboundedly (OOM): once the cap is reached, further
        // records overflow so the caller can reject rather than accumulate the whole dataset (r4).
        SessionChangeBuffer buffer = new SessionChangeBuffer(0L, 2, Long.MAX_VALUE);
        assertEquals(Result.ACCEPTED, buffer.accept(change(1L)));
        assertEquals(Result.ACCEPTED, buffer.accept(change(2L)));

        assertEquals(Result.OVERFLOW, buffer.accept(change(3L)), "past the cap must overflow");
        assertEquals(2, buffer.acceptedCount(), "an overflow record is not retained");
        assertEquals(2L, buffer.lastSeq(), "an overflow does not advance the watermark");
    }

    @Test
    void tracksAcceptedSerializedBytes() {
        SessionChangeBuffer buffer = new SessionChangeBuffer(0L);
        ChangeRecord first = change(1L);
        ChangeRecord second = change(2L);

        assertEquals(0L, buffer.acceptedBytes(), "empty buffer holds no bytes");
        buffer.accept(first);
        buffer.accept(second);
        buffer.accept(second); // duplicate — not retained
        buffer.accept(change(9L)); // gap — not retained

        assertEquals((long) first.getSerializedSize() + second.getSerializedSize(), buffer.acceptedBytes(),
                "only accepted records count toward the byte total");
    }

    @Test
    void flagsByteOverflowWhenSessionExceedsTheByteBudget() {
        // The record cap alone lets a fat-but-few-rows snapshot buffer gigabytes on-heap (a 439k-row
        // snapshot OOMed a 1536Mi pod). Past the byte budget, further records overflow so the caller
        // can reject the session instead of the JVM dying.
        ChangeRecord first = change(1L);
        ChangeRecord second = change(2L);
        long budget = (long) first.getSerializedSize() + second.getSerializedSize();
        SessionChangeBuffer buffer = new SessionChangeBuffer(0L, Integer.MAX_VALUE, budget);
        assertEquals(Result.ACCEPTED, buffer.accept(first));
        assertEquals(Result.ACCEPTED, buffer.accept(second));

        assertEquals(Result.OVERFLOW_BYTES, buffer.accept(change(3L)), "past the byte budget must overflow");
        assertEquals(2, buffer.acceptedCount(), "an overflowing record is not retained");
        assertEquals(2L, buffer.lastSeq(), "a byte overflow does not advance the watermark");
        assertEquals(budget, buffer.acceptedBytes(), "an overflowing record does not count toward the total");
    }

    @Test
    void flagsByteOverflowWhenASingleRecordExceedsTheBudget() {
        SessionChangeBuffer buffer = new SessionChangeBuffer(0L, Integer.MAX_VALUE, 1L);

        assertEquals(Result.OVERFLOW_BYTES, buffer.accept(change(1L)),
                "even the first record overflows when it alone exceeds the budget");
        assertEquals(0, buffer.acceptedCount());
        assertEquals(0L, buffer.lastSeq());
    }

    @Test
    void duplicateAndGapAreReportedBeforeByteOverflow() {
        // A replayed seq must stay a harmless DUPLICATE (and a skipped seq a GAP) even once the
        // budget is exhausted — otherwise a resume replay would spuriously kill the session.
        ChangeRecord first = change(1L);
        SessionChangeBuffer buffer = new SessionChangeBuffer(0L, Integer.MAX_VALUE, first.getSerializedSize());
        assertEquals(Result.ACCEPTED, buffer.accept(first));

        assertEquals(Result.DUPLICATE, buffer.accept(change(1L)));
        assertEquals(Result.GAP, buffer.accept(change(5L)));
    }

    private static ChangeRecord change(long seq) {
        return ChangeRecord.newBuilder().setSeq(seq).setTable("t").setOp(Op.INSERT).build();
    }
}
