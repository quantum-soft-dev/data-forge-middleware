package com.bitbi.dfm.account.domain;

/**
 * ActionStatus enum represents the outcome of an administrative action.
 * <p>
 * Used for audit logging to track whether operations succeeded or failed.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public enum ActionStatus {
    SUCCESS("Success"),
    FAILED("Failed");

    private final String displayName;

    ActionStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
