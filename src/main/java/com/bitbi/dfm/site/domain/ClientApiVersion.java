package com.bitbi.dfm.site.domain;

/**
 * Supported client ingestion API version.
 *
 * <p>Feature 032 retired the legacy HTTP client API. The enum remains in the persisted/API
 * model for compatibility, with Delta gRPC as its sole valid value.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public enum ClientApiVersion {

    /** Delta gRPC client API (changelog ingest + checkpoints). */
    V2
}
