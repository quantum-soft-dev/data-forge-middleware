package com.bitbi.dfm.delta.application;

/**
 * How a checkpoint build writes Parquet. Incremental work always advances {@code seq}. An idle
 * pass (no new segments) never does: it either retries missing keys or rewrites every table.
 *
 * <p>Chosen by {@link CheckpointService}, which decides the build's path, and acted on by
 * {@link CheckpointSnapshotMaterializer}, which owns the {@code checkpoints} rows (issue #297).</p>
 */
enum SnapshotPass {
    INCREMENTAL,
    RETRY_MISSING,
    FORCE
}
