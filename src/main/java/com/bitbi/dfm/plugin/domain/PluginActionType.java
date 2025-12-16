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
    EVENT_TIMEOUT
}
