package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;

/**
 * Validates incoming change records against the target table's key model (Delta Client v2 — 022).
 *
 * <p>Keyless tables (no declared primary/unique key) identify a row by the full set of fields,
 * so an UPDATE would re-key the row; such tables may only INSERT/DELETE. Keyed tables support the
 * full INSERT/UPDATE/DELETE set. See CR §6 (resolved OQ-1).</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public final class ChangeRecordValidator {

    private ChangeRecordValidator() {
    }

    /**
     * Validate a change record for a table.
     *
     * @param record       the change record
     * @param tableHasKey  whether the record's table has a declared primary/unique key
     * @throws InvalidChangeException if the record violates the keyless-table rule
     */
    public static void validate(ChangeRecord record, boolean tableHasKey) {
        if (!tableHasKey && record.getOp() == Op.UPDATE) {
            throw new InvalidChangeException(
                    "UPDATE is not allowed for keyless table '" + record.getTable()
                            + "' (use DELETE + INSERT)");
        }
    }

    /**
     * Thrown when a change record is invalid for its table.
     */
    public static class InvalidChangeException extends RuntimeException {
        public InvalidChangeException(String message) {
            super(message);
        }
    }
}
