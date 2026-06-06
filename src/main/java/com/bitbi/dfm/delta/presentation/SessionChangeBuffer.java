package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-session accumulator of accepted change records (Delta Client v2 — 022).
 *
 * <p>Enforces idempotency within a session: a record is accepted only if its {@code seq} is
 * strictly greater than the last accepted sequence (which starts at the server watermark).
 * Duplicates and out-of-order/already-applied records are ignored. The accepted records are
 * retained for persistence as a changelog segment (Task 2.5).</p>
 *
 * <p>Not thread-safe — a session is driven by a single gRPC stream.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
final class SessionChangeBuffer {

    private long lastSeq;
    private final List<ChangeRecord> accepted = new ArrayList<>();

    SessionChangeBuffer(long startSeq) {
        this.lastSeq = startSeq;
    }

    /**
     * Accept a change record if its sequence advances the buffer.
     *
     * @param record incoming change record
     * @return {@code true} if accepted; {@code false} if ignored (duplicate / replay)
     */
    boolean accept(ChangeRecord record) {
        if (record.getSeq() <= lastSeq) {
            return false;
        }
        lastSeq = record.getSeq();
        accepted.add(record);
        return true;
    }

    long lastSeq() {
        return lastSeq;
    }

    int acceptedCount() {
        return accepted.size();
    }

    List<ChangeRecord> accepted() {
        return Collections.unmodifiableList(accepted);
    }
}
