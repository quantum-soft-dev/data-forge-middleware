package com.bitbi.dfm.account.domain;

/**
 * AdminActionType enum represents types of administrative actions on accounts.
 * <p>
 * Used for audit logging and tracking admin operations in the system.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public enum AdminActionType {
    CREATE_ACCOUNT("Create Account"),
    LOCK_ACCOUNT("Lock Account"),
    UNLOCK_ACCOUNT("Unlock Account"),
    RESET_PASSWORD("Reset Password"),
    CREATE_SITE("Create Site"),
    DEACTIVATE_SITE("Deactivate Site"),
    ACTIVATE_SITE("Activate Site"),
    DELETE_SITE("Delete Site"),
    UPDATE_SITE_RETENTION("Update Site Retention Policy"),
    BATCH_CLEANUP("Batch Cleanup");

    private final String displayName;

    AdminActionType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
