package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.TableChangeStats;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T6.1 — per-table insert/update/delete counting shared by session reconciliation and the
 * persisted segment stats surfaced in batch history (Delta Client v2 — 022).
 */
class ChangeRecordStatsTest {

    @Test
    void countsInsertsUpdatesDeletesPerTable() {
        List<ChangeRecord> records = List.of(
                rec("a", Op.INSERT), rec("a", Op.INSERT), rec("a", Op.UPDATE),
                rec("b", Op.DELETE));

        Map<String, TableChangeStats> stats = ChangeRecordStats.computeByTable(records);

        assertEquals(new TableChangeStats(2, 1, 0), stats.get("a"));
        assertEquals(new TableChangeStats(0, 0, 1), stats.get("b"));
        assertEquals(2, stats.size());
    }

    @Test
    void emptyRecordsYieldEmptyMap() {
        assertTrue(ChangeRecordStats.computeByTable(List.of()).isEmpty());
    }

    private static ChangeRecord rec(String table, Op op) {
        return ChangeRecord.newBuilder().setTable(table).setOp(op).setSeq(1).build();
    }
}
