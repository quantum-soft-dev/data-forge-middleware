package com.bitbi.dfm.delta.application;

/**
 * Raised when a file-backed artifact would exceed its configured local-file policy.
 *
 * <p>Shared by the completed-batch writer, the checkpoint snapshot writer (issue #112) and
 * {@link CappedOutputStream} on the checkpoint frame (issue #126): each streams to local disk,
 * so each needs the same guard against a single artifact filling the node.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public final class ArtifactSizeLimitExceededException extends RuntimeException {

    ArtifactSizeLimitExceededException(long maxBytes) {
        super("Artifact exceeds temp-file limit of " + maxBytes + " bytes");
    }
}
