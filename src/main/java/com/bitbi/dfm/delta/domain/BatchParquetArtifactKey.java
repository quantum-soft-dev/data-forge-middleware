package com.bitbi.dfm.delta.domain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Object-key policy for one logical completed-batch/table Parquet artifact. */
public final class BatchParquetArtifactKey {

    private BatchParquetArtifactKey() {
    }

    /**
     * Derive the stable S3 key shared by finalization, download metadata, and lifecycle cleanup.
     *
     * @param siteId site owning the batch
     * @param batchId completed batch identifier
     * @param tableName source table name
     * @return URL-safe object key
     */
    public static String of(UUID siteId, UUID batchId, String tableName) {
        String encodedTable = URLEncoder.encode(tableName, StandardCharsets.UTF_8).replace("+", "%20");
        return String.format("egress/%s/batches/%s/%s.parquet", siteId, batchId, encodedTable);
    }
}
