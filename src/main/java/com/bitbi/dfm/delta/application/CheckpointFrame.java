package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.application.ChangelogFold.FoldedRow;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Re-expresses a folded checkpoint state as an all-INSERT changelog frame (Delta Client v2 — 022,
 * CR §8.D / §3).
 *
 * <p>A full snapshot is just a changelog frame whose records are all {@code INSERT}; persisting the
 * current state in this form makes a checkpoint a self-contained seed. Re-folding the frame from
 * empty reproduces the exact state, so the segments it summarizes can be pruned (T3.5b).</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public final class CheckpointFrame {

    private CheckpointFrame() {
    }

    /**
     * Emit one {@code INSERT} {@link ChangeRecord} per surviving row (table/row order preserved).
     *
     * @param state folded state: table → identity → folded row
     * @return all-INSERT frame; {@code seq} is a frame-local 1-based index (ignored on re-fold)
     */
    public static List<ChangeRecord> toRecords(Map<String, Map<String, FoldedRow>> state) {
        List<ChangeRecord> frame = new ArrayList<>();
        long seq = 0;
        for (Map.Entry<String, Map<String, FoldedRow>> tableEntry : state.entrySet()) {
            for (FoldedRow row : tableEntry.getValue().values()) {
                frame.add(ChangeRecord.newBuilder()
                        .setTable(tableEntry.getKey())
                        .setOp(Op.INSERT)
                        .setSeq(++seq)
                        .putAllKey(row.key())
                        .putAllData(row.data())
                        .build());
            }
        }
        return frame;
    }
}
