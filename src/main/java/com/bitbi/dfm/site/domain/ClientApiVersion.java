package com.bitbi.dfm.site.domain;

/**
 * Which client ingestion API a site uses (Delta Client v2 — 022, OQ-3).
 *
 * <ul>
 *   <li>{@link #V1} — legacy HTTP client API ({@code /api/dfc}); full CSV snapshots per batch.</li>
 *   <li>{@link #V2} — Delta gRPC client API; changelog ingest + reconstructed checkpoints.</li>
 * </ul>
 *
 * <p>New sites default to {@code V2}; sites that existed before V29 were backfilled to {@code V1}.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public enum ClientApiVersion {

    /** Legacy HTTP client API ({@code /api/dfc}). */
    V1,

    /** Delta gRPC client API (changelog ingest + checkpoints). */
    V2
}
