package com.bitbi.dfm.site.domain;

/**
 * Reason for forcing a full upload (rebaseline) on a CDC site.
 *
 * <p>Used as a directive sent via heartbeat to instruct the client
 * to upload full CSV files instead of JSONL deltas.</p>
 *
 * Feature: 021-unified-upload-api
 */
public enum ForceFullUploadReason {

    /** Admin manually requested rebaseline via API or UI. */
    ADMIN_REQUEST,

    /** Plugin reinitialization requires new baseline data. */
    PLUGIN_REINIT,

    /** Breaking schema change requires full data re-upload. */
    SCHEMA_INCOMPATIBLE,

    /** Detected data corruption requires clean baseline. */
    DATA_CORRUPTION
}
