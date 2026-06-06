package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.application.ChangeRecordValidator.InvalidChangeException;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T2.4 — keyless-table rule: tables without a declared key (identified by the full row) may only
 * INSERT/DELETE; UPDATE is rejected. Keyed tables allow the full INSERT/UPDATE/DELETE set.
 */
class ChangeRecordValidatorTest {

    @Test
    void rejectsUpdateForKeylessTable() {
        assertThrows(InvalidChangeException.class,
                () -> ChangeRecordValidator.validate(record(Op.UPDATE), false));
    }

    @Test
    void allowsUpdateForKeyedTable() {
        assertDoesNotThrow(() -> ChangeRecordValidator.validate(record(Op.UPDATE), true));
    }

    @Test
    void allowsInsertAndDeleteForKeylessTable() {
        assertDoesNotThrow(() -> ChangeRecordValidator.validate(record(Op.INSERT), false));
        assertDoesNotThrow(() -> ChangeRecordValidator.validate(record(Op.DELETE), false));
    }

    @Test
    void allowsAllOpsForKeyedTable() {
        assertDoesNotThrow(() -> ChangeRecordValidator.validate(record(Op.INSERT), true));
        assertDoesNotThrow(() -> ChangeRecordValidator.validate(record(Op.DELETE), true));
    }

    private static ChangeRecord record(Op op) {
        return ChangeRecord.newBuilder().setTable("t").setOp(op).setSeq(1L).build();
    }
}
