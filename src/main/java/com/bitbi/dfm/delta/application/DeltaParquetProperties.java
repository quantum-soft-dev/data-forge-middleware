package com.bitbi.dfm.delta.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The Parquet row-group budget shared by every Delta v2 Parquet path (issue #112).
 *
 * <p>A Parquet writer buffers one row group in heap before flushing it to the output, so with
 * file-backed writers the row-group size is the last multiplier of a build's peak memory:
 * {@code open writers × row-group budget}. parquet-mr's implicit default is ~128 MB, which a
 * grouped completed-batch build (one open writer per claimed table) multiplies straight past a
 * 2–3 Gi pod. One key covers the checkpoint snapshot, the per-segment egress and the
 * completed-batch artifacts, because they share the same heap on the same pod.</p>
 *
 * <p>This is a memory ceiling, not a compression knob: shrinking it costs a little compression
 * ratio and adds row-group metadata, it does not change the data written.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class DeltaParquetProperties {

    /**
     * Default row-group budget in bytes (8 MiB). Small enough that a few dozen concurrently open
     * writers stay in the low hundreds of MB, large enough that readers still get row groups worth
     * seeking to (sub-MB groups multiply footer metadata and hurt scan throughput).
     */
    public static final String DEFAULT_ROW_GROUP_BYTES = "8388608";

    private final long rowGroupBytes;

    public DeltaParquetProperties(
            @Value("${delta.parquet.row-group-bytes:" + DEFAULT_ROW_GROUP_BYTES + "}") long rowGroupBytes) {
        if (rowGroupBytes <= 0) {
            throw new IllegalArgumentException(
                    "delta.parquet.row-group-bytes must be positive, got " + rowGroupBytes);
        }
        this.rowGroupBytes = rowGroupBytes;
    }

    /**
     * @return the configured row-group budget in bytes
     */
    public long rowGroupBytes() {
        return rowGroupBytes;
    }
}
