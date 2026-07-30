package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.TableChangeStats;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes per-table insert/update/delete counts from a session's accepted change records
 * (Delta Client v2 — 022), for the per-segment stats {@link ChangelogSegmentService} persists and
 * surfaces in batch history. Whole-session reconciliation totals accumulate in
 * {@link SessionTotals} instead, which must survive mid-session segment seals (033).
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public final class ChangeRecordStats {

    private ChangeRecordStats() {
    }

    /**
     * @param records accepted change records of a session
     * @return per-table insert/update/delete counts, keyed by table name (insertion order)
     */
    public static Map<String, TableChangeStats> computeByTable(List<ChangeRecord> records) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        for (ChangeRecord record : records) {
            long[] c = counts.computeIfAbsent(record.getTable(), k -> new long[3]);
            switch (record.getOp()) {
                case INSERT -> c[0]++;
                case UPDATE -> c[1]++;
                case DELETE -> c[2]++;
                default -> {
                }
            }
        }
        Map<String, TableChangeStats> result = new LinkedHashMap<>();
        counts.forEach((table, c) -> result.put(table, new TableChangeStats(c[0], c[1], c[2])));
        return result;
    }
}
