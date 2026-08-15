package com.bitbi.dfm.shared.storage;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Result of walking one S3 prefix, page by page (issue #122).
 *
 * <p>A mid-pagination failure does not discard the pages already read: {@link #truncated()} is
 * {@code true} and {@link #objects()} holds whatever arrived before the throw. Callers that used
 * to infer "complete" from a normal return must now inspect the flag.</p>
 *
 * @param objects    objects from the pages that were read
 * @param truncated  {@code true} when the walk stopped early (failed listing or partial page set)
 */
public record S3PrefixListing(List<S3ListedObject> objects, boolean truncated) {

    public S3PrefixListing {
        objects = List.copyOf(Objects.requireNonNull(objects, "objects"));
    }

    public static S3PrefixListing complete(List<S3ListedObject> objects) {
        return new S3PrefixListing(objects, false);
    }

    public static S3PrefixListing truncated(List<S3ListedObject> objects) {
        return new S3PrefixListing(objects, true);
    }

    public static S3PrefixListing empty() {
        return complete(List.of());
    }

    /**
     * A complete listing of keys whose timestamps are irrelevant to the caller. Used by tests
     * and by callers that only want the key set of a finished walk.
     *
     * @param keys object keys
     * @return a complete listing at the epoch, which every wipe cut-off treats as old
     */
    public static S3PrefixListing completeKeys(List<String> keys) {
        return complete(keys.stream()
                .map(key -> new S3ListedObject(key, Instant.EPOCH))
                .toList());
    }

    public List<String> keys() {
        return objects.stream().map(S3ListedObject::key).toList();
    }
}
