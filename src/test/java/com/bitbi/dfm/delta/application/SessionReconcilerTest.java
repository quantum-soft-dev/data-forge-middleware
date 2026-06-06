package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.TableStats;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.6 — session reconciliation: accepted records must match the client's declared per-table
 * insert/update/delete counts, otherwise the session is rejected (hard-fail).
 */
class SessionReconcilerTest {

    @Test
    void matchesWhenCountsAgree() {
        List<ChangeRecord> accepted = List.of(
                rec("a", Op.INSERT, 1), rec("a", Op.INSERT, 2), rec("a", Op.UPDATE, 3),
                rec("b", Op.DELETE, 4));
        Map<String, TableStats> declared = Map.of(
                "a", stats(2, 1, 0),
                "b", stats(0, 0, 1));

        assertTrue(SessionReconciler.reconcile(accepted, declared));
    }

    @Test
    void emptyMatchesEmpty() {
        assertTrue(SessionReconciler.reconcile(List.of(), Map.of()));
    }

    @Test
    void failsOnCountMismatch() {
        List<ChangeRecord> accepted = List.of(rec("a", Op.INSERT, 1));
        Map<String, TableStats> declared = Map.of("a", stats(2, 0, 0));

        assertFalse(SessionReconciler.reconcile(accepted, declared));
    }

    @Test
    void failsWhenTableMissingFromDeclared() {
        List<ChangeRecord> accepted = List.of(rec("a", Op.INSERT, 1), rec("b", Op.INSERT, 2));
        Map<String, TableStats> declared = Map.of("a", stats(1, 0, 0));

        assertFalse(SessionReconciler.reconcile(accepted, declared));
    }

    private static ChangeRecord rec(String table, Op op, long seq) {
        return ChangeRecord.newBuilder().setTable(table).setOp(op).setSeq(seq).build();
    }

    private static TableStats stats(long inserts, long updates, long deletes) {
        return TableStats.newBuilder().setInserts(inserts).setUpdates(updates).setDeletes(deletes).build();
    }
}
