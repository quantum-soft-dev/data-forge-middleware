package com.bitbi.dfm.delta.application;

/**
 * Raised when a file-backed Parquet artifact would exceed its configured local-file policy.
 *
 * <p>Shared by the completed-batch writer and the checkpoint snapshot writer (issue #112): both
 * stream to local disk, so both need the same guard against a single table filling the node.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public final class ArtifactSizeLimitExceededException extends RuntimeException {

    ArtifactSizeLimitExceededException(long maxBytes) {
        super("Artifact exceeds temp-file limit of " + maxBytes + " bytes");
    }
}
