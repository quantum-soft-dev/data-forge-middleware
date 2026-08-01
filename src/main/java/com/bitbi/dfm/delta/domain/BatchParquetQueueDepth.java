package com.bitbi.dfm.delta.domain;

/** One grouped durable queue count returned to the metrics sampler. */
public record BatchParquetQueueDepth(BatchParquetArtifactStatus status, long count) {
}
