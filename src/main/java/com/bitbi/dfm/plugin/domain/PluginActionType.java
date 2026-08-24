package com.bitbi.dfm.plugin.domain;

/**
 * Types of actions recorded in the plugin audit log.
 * Used for tracking plugin operations and troubleshooting.
 */
public enum PluginActionType {
    /** Plugin activated for an account (new activation) */
    ACTIVATE,

    /** Plugin deactivated for an account */
    DEACTIVATE,

    /** Previously deactivated plugin reactivated */
    REACTIVATE,

    /** Event successfully dispatched to plugin */
    EVENT_DISPATCHED,

    /** Event dispatch failed due to plugin error */
    EVENT_FAILED,

    /** Event dispatch timed out (30 second limit per FR-008) */
    EVENT_TIMEOUT,

    /** SQL generation started for a batch */
    SQL_GENERATION_STARTED,

    /** SQL generation completed successfully */
    SQL_GENERATION_COMPLETED,

    /** SQL generation failed with error */
    SQL_GENERATION_FAILED,

    /**
     * This attempt lost the unique claim ({@code uk_sql_gen_source_batch}) and adopted the
     * winner's generation (issue #260). Terminal companion of {@link #SQL_GENERATION_STARTED} on
     * that path — not a second {@link #SQL_GENERATION_COMPLETED} (that would name the object this
     * attempt just deleted, issue #246) and not {@link #SQL_GENERATION_FAILED} (nothing failed;
     * the batch has its SQL).
     */
    SQL_GENERATION_ADOPTED,

    /** Admin cleared all plugin history for an account */
    PLUGIN_HISTORY_CLEARED,

    /**
     * SQL regeneration started for a batch.
     *
     * <p>Historical: the regeneration path was retired by issue #190 and nothing writes this
     * value any more. It is kept so audit rows that may carry it stay readable — the same
     * reasoning that keeps the write-only {@code superseded}/{@code superseded_by} columns.</p>
     */
    SQL_REGENERATION_STARTED,

    /** SQL regeneration completed successfully. Historical since #190 — see {@link #SQL_REGENERATION_STARTED}. */
    SQL_REGENERATION_COMPLETED,

    /** SQL regeneration failed with error. Historical since #190 — see {@link #SQL_REGENERATION_STARTED}. */
    SQL_REGENERATION_FAILED,

    /** Plugin SQL state reinitialized (history cleared + regenerated from latest batch) */
    REINIT,

    /** Single SQL generation deleted by admin */
    SQL_GENERATION_DELETED,

    /** Parquet Export: file listing served, one-time links registered (028) */
    FILES_LISTED,

    /** Parquet Export: one-time download link consumed, presigned redirect issued (028) */
    LINK_CONSUMED,

    /** Parquet Export: download attempt rejected (consumed/expired/unknown/inactive) (028) */
    LINK_REJECTED,

    /** Parquet Export: Basic Auth password rotated by the account owner (028) */
    PASSWORD_ROTATED,

    /**
     * Bit BI: API key rotated by the account owner.
     * <p>
     * Adding a value here requires a migration extending
     * {@code chk_plugin_audit_logs_action_type} — see V44 and
     * {@code PluginAuditLogActionTypeIntegrationTest}.
     * </p>
     */
    API_KEY_ROTATED,

    /**
     * Bit BI: delta baselines re-captured automatically after a site history wipe (issue #89),
     * on the first checkpoint built post-wipe. Replaces the "reinit required" warning for that
     * path; an ordinary re-baseline still requires a manual reinit.
     */
    DELTA_AUTO_REINIT
}
