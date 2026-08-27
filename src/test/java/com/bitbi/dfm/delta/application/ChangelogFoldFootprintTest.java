package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.application.ChangelogFold.FoldedRow;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #290 — what one folded row costs, measured rather than argued.
 *
 * <p>The fold is the checkpoint build's ceiling (issue #152) and its estimate is what the ceiling is
 * enforced against, so a representation that quietly grows back moves the ceiling with it and
 * nothing says so. These tests pin the per-row cost of a synthetic wide site against a constant, and
 * pin that the shared part — a table's column names — is paid once for the table rather than once
 * per row, which is the whole of the saving.</p>
 */
class ChangelogFoldFootprintTest {

    private static final int COLUMNS = 20;
    private static final int ROWS = 10_000;

    /**
     * A twenty-column row with eight-character string values, measured against what the pre-#290
     * representation charged for the very same row — a {@code LinkedHashMap} entry and a fresh
     * {@code String} for every column name of every row, plus a second copy of the key columns.
     * The names now live once per table and the values sit in an array.
     *
     * <p>The comparison is arithmetic rather than a remembered number, so the saving is stated by
     * the test rather than by a comment that can go stale: {@link #legacyEstimatedBytes} is the old
     * formula, verbatim.</p>
     */
    @Test
    void aWideRowCostsFarLessThanItsColumnNamesUsedTo() {
        Map<String, Map<String, FoldedRow>> state = new LinkedHashMap<>();

        long total = 0L;
        long legacyTotal = 0L;
        for (int row = 0; row < ROWS; row++) {
            ChangeRecord insert = wideInsert(row);
            total += ChangelogFold.apply(state, insert);
            legacyTotal += legacyEstimatedBytes(insert);
        }

        long perRow = total / ROWS;
        long legacyPerRow = legacyTotal / ROWS;
        assertEquals(ROWS, state.get("wide").size(), "every row survived");
        assertTrue(perRow * 2 <= legacyPerRow,
                "a " + COLUMNS + "-column row must cost at most half of what it did: "
                        + legacyPerRow + " → " + perRow);
        assertTrue(perRow > 1000,
                "an estimate that collapsed below the values it is counting would make the ceiling a "
                        + "lie in the other direction: " + perRow);
    }

    /**
     * The pre-#290 per-row estimate: row header, identity string, and both maps entry by entry,
     * each entry paying for its column name. Kept here so the saving this ticket claims is measured
     * against the shape it replaced instead of against a constant nobody can check.
     */
    private static long legacyEstimatedBytes(ChangeRecord record) {
        long identity = 48 + identityLength(record);
        return 160L + identity + legacyMapBytes(record.getKeyMap()) + legacyMapBytes(record.getDataMap());
    }

    private static long legacyMapBytes(Map<String, Value> columns) {
        long bytes = 0L;
        for (Map.Entry<String, Value> column : columns.entrySet()) {
            bytes += 64L + 48L + column.getKey().length() + 40L;
            if (column.getValue().hasStringValue()) {
                bytes += 48L + column.getValue().getStringValue().length();
            }
        }
        return bytes;
    }

    private static int identityLength(ChangeRecord record) {
        int length = 0;
        for (Map.Entry<String, Value> column : record.getKeyMap().entrySet()) {
            String encoded = "I" + column.getValue().getIntValue();
            length += String.valueOf(column.getKey().length()).length() + 1 + column.getKey().length()
                    + 1 + String.valueOf(encoded.length()).length() + 1 + encoded.length();
        }
        return length;
    }

    /**
     * The marginal cost of a row must not carry the table's column names, or a million-row site pays
     * for them a million times — which is what it did.
     */
    @Test
    void aTablesColumnNamesArePaidOncePerTableNotOncePerRow() {
        Map<String, Map<String, FoldedRow>> state = new LinkedHashMap<>();

        long first = ChangelogFold.apply(state, wideInsert(0));
        long second = ChangelogFold.apply(state, wideInsert(1));
        long third = ChangelogFold.apply(state, wideInsert(2));

        assertEquals(second, third, "rows of one shape must cost the same once the table is known");
        assertTrue(first - second > 1000,
                "the first row of a table must be the one that pays for its column names: "
                        + first + " vs " + second);
    }

    /**
     * The estimate is a running total kept by {@link ChangelogFold#apply}; if it drifts from what a
     * walk over the fold says, the ceiling stops meaning what the WARN and the abort claim.
     */
    @Test
    void theRunningTotalAgreesWithAWalkOverTheFold() {
        Map<String, Map<String, FoldedRow>> state = new LinkedHashMap<>();
        long total = 0L;
        for (int row = 0; row < 200; row++) {
            total += ChangelogFold.apply(state, wideInsert(row));
        }
        total += ChangelogFold.apply(state, ChangeRecord.newBuilder()
                .setTable("wide").setOp(Op.UPDATE)
                .putKey("id", intValue(7))
                .putData("c03", stringValue("updated-and-then-some"))
                .build());
        total += ChangelogFold.apply(state, ChangeRecord.newBuilder()
                .setTable("wide").setOp(Op.DELETE).putKey("id", intValue(11)).build());

        long walked = 0L;
        for (Map<String, FoldedRow> table : state.values()) {
            walked += ChangelogFold.sharedEstimatedRetainedBytes(table);
            for (Map.Entry<String, FoldedRow> row : table.entrySet()) {
                walked += ChangelogFold.estimatedRetainedBytes(row.getKey(), row.getValue());
            }
        }

        assertEquals(walked, total, "the running total must equal what the fold actually holds");
    }

    private static ChangeRecord wideInsert(int row) {
        ChangeRecord.Builder builder = ChangeRecord.newBuilder()
                .setTable("wide").setOp(Op.INSERT).setSeq(row + 1)
                .putKey("id", intValue(row))
                .putData("id", intValue(row));
        for (int column = 0; column < COLUMNS; column++) {
            builder.putData(String.format("c%02d", column), stringValue("v" + String.format("%07d", row)));
        }
        return builder.build();
    }

    private static Value intValue(long v) {
        return Value.newBuilder().setIntValue(v).build();
    }

    private static Value stringValue(String v) {
        return Value.newBuilder().setStringValue(v).build();
    }
}
