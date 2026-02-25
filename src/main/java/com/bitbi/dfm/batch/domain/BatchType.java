package com.bitbi.dfm.batch.domain;

/**
 * Type of batch upload, determining file validation and SQL generation behavior.
 *
 * <p>Required for all new batches. Nullable in the database for backward
 * compatibility with legacy batch records created before this feature.</p>
 *
 * Feature: 021-unified-upload-api
 */
public enum BatchType {

    /** Full CSV upload. No SQL generation — serves as baseline for future deltas. */
    BASELINE,

    /** Change data upload. JSONL for CDC sites, CSV for DBF snapshot sites. */
    DELTA
}
