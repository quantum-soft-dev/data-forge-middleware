package com.bitbi.dfm.shared.storage;

import java.util.List;
import java.util.Objects;

/**
 * Result of walking one S3 prefix, page by page.
 *
 * <p>A mid-pagination failure does not discard the pages already read: {@link #truncated()} is
 * {@code true} and {@link #objects()} holds whatever arrived before the throw.</p>
 *
 * @param objects    objects from the pages that were read
 * @param truncated  {@code true} when the walk stopped early
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

    public List<String> keys() {
        return objects.stream().map(S3ListedObject::key).toList();
    }
}
