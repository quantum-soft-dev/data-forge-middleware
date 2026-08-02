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
        return batchPrefix(siteId, batchId) + encodedTable(tableName) + ".parquet";
    }

    /** Namespace containing legacy and claim-attempt objects for one logical batch. */
    public static String batchPrefix(UUID siteId, UUID batchId) {
        return String.format("egress/%s/batches/%s/", siteId, batchId);
    }

    /**
     * Derive an immutable object key for one claim attempt.
     *
     * <p>The claim token, rather than the resettable attempt count, prevents a late upload from an
     * earlier operator-authorized lifecycle from colliding with the current one.</p>
     */
    public static String attempt(UUID siteId, UUID batchId, String tableName, UUID claimToken) {
        return batchPrefix(siteId, batchId) + "attempts/" + claimToken + "/"
                + encodedTable(tableName) + ".parquet";
    }

    private static String encodedTable(String tableName) {
        return URLEncoder.encode(tableName, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
