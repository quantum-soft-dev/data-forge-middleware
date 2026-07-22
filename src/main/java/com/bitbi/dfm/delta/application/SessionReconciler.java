package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.TableChangeStats;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.TableStats;

import java.util.List;
import java.util.Map;

/**
 * Reconciles a session's accepted records against the client's declared per-table counts
 * (Delta Client v2 — 022, CR §10). A mismatch is a hard failure: the session must not commit.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public final class SessionReconciler {

    private SessionReconciler() {
    }

    /**
     * @param accepted accepted change records of the session
     * @param declared client-declared per-table insert/update/delete counts ({@code SessionEnd})
     * @return {@code true} if the actual records exactly match the declared counts
     */
    public static boolean reconcile(List<ChangeRecord> accepted, Map<String, TableStats> declared) {
        Map<String, TableChangeStats> actual = ChangeRecordStats.computeByTable(accepted);

        if (!actual.keySet().equals(declared.keySet())) {
            return false;
        }
        for (Map.Entry<String, TableStats> entry : declared.entrySet()) {
            TableChangeStats a = actual.get(entry.getKey());
            TableStats d = entry.getValue();
            if (a.inserts() != d.getInserts() || a.updates() != d.getUpdates() || a.deletes() != d.getDeletes()) {
                return false;
            }
        }
        return true;
    }
}
