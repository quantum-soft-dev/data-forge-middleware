package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-session accumulator of accepted change records (Delta Client v2 — 022).
 *
 * <p>Enforces both idempotency and contiguity within a session. Starting from the server watermark,
 * a record is {@link Result#ACCEPTED} only if its {@code seq} is exactly {@code lastSeq + 1}; a
 * {@code seq <= lastSeq} is a {@link Result#DUPLICATE} (replay, ignored), and a {@code seq > lastSeq + 1}
 * is a {@link Result#GAP} (one or more sequences were skipped — the caller must reject the session
 * rather than silently lose the missing records). The accepted records are retained for persistence
 * as a changelog segment.</p>
 *
 * <p>Not thread-safe — a session is driven by a single gRPC stream.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
final class SessionChangeBuffer {

    /** Outcome of offering a record to the buffer. */
    enum Result {
        /** The record was the next contiguous sequence and was retained. */
        ACCEPTED,
        /** {@code seq <= lastSeq}: a replay / already-applied record, safely ignored. */
        DUPLICATE,
        /** {@code seq > lastSeq + 1}: a sequence gap — the session must be rejected. */
        GAP,
        /** The record cap for one session was reached — the session must be rejected (OOM guard). */
        OVERFLOW,
        /** The byte budget for one session was reached — the session must be rejected (OOM guard). */
        OVERFLOW_BYTES
    }

    private long lastSeq;
    private final List<ChangeRecord> accepted = new ArrayList<>();
    private final int maxRecords;
    private final long maxBytes;
    private long acceptedBytes;

    SessionChangeBuffer(long startSeq) {
        this(startSeq, Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    /**
     * @param startSeq   the server watermark the session continues from
     * @param maxRecords the maximum records one session may buffer before {@link Result#OVERFLOW}
     * @param maxBytes   the maximum cumulative serialized size (bytes) one session may buffer before
     *                   {@link Result#OVERFLOW_BYTES} — the record cap alone lets a fat-but-few-rows
     *                   snapshot buffer gigabytes on-heap
     */
    SessionChangeBuffer(long startSeq, int maxRecords, long maxBytes) {
        this.lastSeq = startSeq;
        this.maxRecords = maxRecords;
        this.maxBytes = maxBytes;
    }

    /**
     * Offer a change record to the buffer.
     *
     * @param record incoming change record
     * @return {@link Result#ACCEPTED} if it advanced the buffer, {@link Result#DUPLICATE} if ignored
     *         as a replay, or {@link Result#GAP} if a sequence was skipped
     */
    Result accept(ChangeRecord record) {
        long seq = record.getSeq();
        if (seq <= lastSeq) {
            return Result.DUPLICATE;
        }
        if (seq > lastSeq + 1) {
            return Result.GAP;
        }
        if (accepted.size() >= maxRecords) {
            return Result.OVERFLOW; // do not advance or retain — the caller rejects the session
        }
        long size = record.getSerializedSize();
        if (acceptedBytes + size > maxBytes) {
            return Result.OVERFLOW_BYTES; // do not advance or retain — the caller rejects the session
        }
        lastSeq = seq;
        accepted.add(record);
        acceptedBytes += size;
        return Result.ACCEPTED;
    }

    long lastSeq() {
        return lastSeq;
    }

    int acceptedCount() {
        return accepted.size();
    }

    /** Cumulative serialized size of the accepted records (approximates the buffer's heap weight). */
    long acceptedBytes() {
        return acceptedBytes;
    }

    List<ChangeRecord> accepted() {
        return Collections.unmodifiableList(accepted);
    }
}
