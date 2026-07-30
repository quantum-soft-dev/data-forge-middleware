package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.TableStats;
import com.bitbi.dfm.delta.grpc.v2.Value;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 033 — whole-session reconciliation totals that survive mid-session segment seals.
 *
 * <p>Before 033 a session's {@code SessionEnd} reconciled against {@code SessionChangeBuffer}, which
 * held every accepted record. A segmented re-baseline drains that buffer on each seal, so the totals
 * must be accumulated as records are accepted rather than recomputed from what is still in memory.</p>
 */
class SessionTotalsTest {

    @Test
    void accumulatesPerTableCountsAcrossChunks() {
        SessionTotals totals = new SessionTotals();
        totals.add(List.of(
                rec("orders", Op.INSERT, 1),
                rec("orders", Op.INSERT, 2),
                rec("items", Op.INSERT, 3)));
        totals.add(List.of(
                rec("orders", Op.UPDATE, 4),
                rec("items", Op.DELETE, 5)));

        // Counts are observable only through reconcile — the production contract.
        assertTrue(totals.reconcile(Map.of(
                "orders", stats(2, 1, 0),
                "items", stats(1, 0, 1))));
    }

    @Test
    void reconcilesDeclaredCountsOverTheWholeSession() {
        SessionTotals totals = new SessionTotals();
        totals.add(List.of(rec("orders", Op.INSERT, 1), rec("orders", Op.INSERT, 2)));
        totals.add(List.of(rec("orders", Op.DELETE, 3)));

        assertTrue(totals.reconcile(Map.of("orders", stats(2, 0, 1))));
        // The tail chunk alone would have reconciled against 0/0/1 — the whole session must not.
        assertFalse(totals.reconcile(Map.of("orders", stats(0, 0, 1))));
    }

    @Test
    void reconcileRejectsUnknownAndMissingTables() {
        SessionTotals totals = new SessionTotals();
        totals.add(List.of(rec("orders", Op.INSERT, 1)));

        assertFalse(totals.reconcile(Map.of()), "a declared-empty session must not match one record");
        assertFalse(totals.reconcile(Map.of(
                "orders", stats(1, 0, 0),
                "ghost", stats(1, 0, 0))), "a table the session never sent must fail");
    }

    @Test
    void emptySessionReconcilesAgainstEmptyDeclaration() {
        SessionTotals totals = new SessionTotals();
        assertTrue(totals.reconcile(Map.of()));
    }

    @Test
    void contentHashCoversTheWholeSessionNotJustTheTail() {
        List<ChangeRecord> head = List.of(rec("orders", Op.INSERT, 1), rec("orders", Op.INSERT, 2));
        List<ChangeRecord> tail = List.of(rec("orders", Op.DELETE, 3));

        SessionTotals totals = new SessionTotals();
        totals.add(head);
        totals.add(tail);

        List<ChangeRecord> all = List.of(head.get(0), head.get(1), tail.get(0));
        assertTrue(totals.hashMatches(ChangelogContentHash.compute(all)));
        assertFalse(totals.hashMatches(ChangelogContentHash.compute(tail)),
                "a hash over only the last segment must not pass");
    }

    @Test
    void blankContentHashIsTreatedAsOptedOut() {
        SessionTotals totals = new SessionTotals();
        totals.add(List.of(rec("orders", Op.INSERT, 1)));

        assertTrue(totals.hashMatches(""));
        assertTrue(totals.hashMatches("   "));
        assertTrue(totals.hashMatches(null));
    }

    private static ChangeRecord rec(String table, Op op, long seq) {
        return ChangeRecord.newBuilder()
                .setTable(table).setOp(op).setSeq(seq)
                .putKey("id", Value.newBuilder().setIntValue(seq).build())
                .build();
    }

    private static TableStats stats(long inserts, long updates, long deletes) {
        return TableStats.newBuilder().setInserts(inserts).setUpdates(updates).setDeletes(deletes).build();
    }
}
