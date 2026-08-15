package com.bitbi.dfm.shared.storage;

import java.time.Instant;
import java.util.Objects;

/**
 * One object from a prefix listing.
 *
 * @param key           object key
 * @param lastModified  S3 last-modified timestamp, or {@code null} when the listing omitted it
 */
public record S3ListedObject(String key, Instant lastModified) {

    public S3ListedObject {
        Objects.requireNonNull(key, "key");
    }
}
