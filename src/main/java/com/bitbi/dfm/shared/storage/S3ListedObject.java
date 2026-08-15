package com.bitbi.dfm.shared.storage;

import java.time.Instant;
import java.util.Objects;

/**
 * One object from a prefix listing: the key plus the bucket's {@code lastModified}, which the
 * site-history wipe uses as a cut-off so a concurrent producer cannot have its fresh object
 * swept (issue #122).
 *
 * @param key           object key
 * @param lastModified  S3 last-modified timestamp; {@code null} is treated as not newer than any cut-off
 */
public record S3ListedObject(String key, Instant lastModified) {

    public S3ListedObject {
        Objects.requireNonNull(key, "key");
    }

    /**
     * @param cutoff the instant the wipe (or other caller) started
     * @return true when the object is known to have been written after {@code cutoff}
     */
    public boolean lastModifiedAfter(Instant cutoff) {
        return lastModified != null && lastModified.isAfter(cutoff);
    }
}
