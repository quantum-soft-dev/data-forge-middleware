package com.bitbi.dfm.delta.domain;

import java.util.UUID;

/**
 * Per-batch aggregate over a session's changelog segments (029: a batch owns N segments, so the
 * batch history list shows totals, not one segment's numbers). Computed SQL-side per page — a
 * million-record session has thousands of segment rows that must never be loaded into memory
 * just to render a list row.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public interface SegmentBatchAggregate {

    UUID getBatchId();

    /** Sum of the batch's segment record counts. */
    Long getTotalRecords();

    /** Count of distinct tables across the batch's segment stats; 0 when no segment has stats. */
    Long getTableCount();
}
