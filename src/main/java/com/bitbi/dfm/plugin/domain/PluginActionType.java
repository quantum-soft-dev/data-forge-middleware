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

    /** Admin cleared all plugin history for an account */
    PLUGIN_HISTORY_CLEARED,

    /** SQL regeneration started for a batch */
    SQL_REGENERATION_STARTED,

    /** SQL regeneration completed successfully */
    SQL_REGENERATION_COMPLETED,

    /** SQL regeneration failed with error */
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
    PASSWORD_ROTATED
}
