package com.bitbi.dfm.delta.domain;

/** Durable lifecycle of one unified batch/table Parquet artifact (036, issue #93). */
public enum BatchParquetArtifactStatus {
    /** Queued, never claimed. */
    PENDING,
    /** Claimed by a worker. The claim is committed before the build starts, so it survives a crash
     *  and is only reclaimed once the build lease expires. */
    BUILDING,
    /** The S3 object is complete and its metadata published. */
    READY,
    /** Attempt failed, attempts remain — the worker will rebuild it. */
    FAILED,
    /** Attempts exhausted. Terminal: no worker claims it again and the download answers 404. */
    ABANDONED
}
