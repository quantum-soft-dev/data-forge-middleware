package com.bitbi.dfm.shared.storage;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
     * @return true when the object might have been written at or after {@code cutoff}.
     *         S3 {@code LastModified} is second-resolution, so anything in the same
     *         second as {@code cutoff} is treated as newer. A missing timestamp is
     *         treated as newer — the safe direction for a concurrent PutObject.
     */
    public boolean lastModifiedAfter(Instant cutoff) {
        if (lastModified == null || cutoff == null) {
            return true;
        }
        Instant cutoffSecond = cutoff.truncatedTo(ChronoUnit.SECONDS);
        return !lastModified.isBefore(cutoffSecond);
    }
}
