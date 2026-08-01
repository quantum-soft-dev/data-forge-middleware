package com.bitbi.dfm.delta.domain;

/** Durable lifecycle of one unified batch/table Parquet artifact (036, issue #93). */
public enum BatchParquetArtifactStatus {
    PENDING,
    BUILDING,
    READY,
    FAILED
}
