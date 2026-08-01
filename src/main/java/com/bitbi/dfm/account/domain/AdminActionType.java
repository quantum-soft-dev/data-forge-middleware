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
    BATCH_CLEANUP("Batch Cleanup"),

    /**
     * All server-side history of one site destroyed — batches, files, changelog, checkpoints,
     * schema, plugin SQL, error logs (issue #89). Adding a value here requires a migration
     * extending {@code chk_action_type} on {@code admin_action_logs} — see V23/V24/V48/V50.
     */
    SITE_HISTORY_WIPE("Wipe Site History"),

    /** An operator reset an abandoned or expired completed-batch Parquet build. */
    BATCH_PARQUET_REQUEUE("Requeue Batch Parquet Artifact");

    private final String displayName;

    AdminActionType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
