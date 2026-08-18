package com.bitbi.dfm.shared.storage;

import java.util.List;
import java.util.Objects;

/**
 * Result of asking one prefix which prefixes sit directly below it (issue #158).
 *
 * <p>The delimiter twin of {@link S3PrefixListing}, and truncation means the same thing here: a
 * mid-pagination failure keeps the prefixes already read rather than discarding the walk. A caller
 * that acts on this list must treat a truncated result as "fewer, never wrong" — it may miss a
 * site, it may never invent one.</p>
 *
 * @param prefixes  child prefixes from the pages that were read, each ending in the delimiter
 * @param truncated {@code true} when the walk stopped early
 */
public record S3ChildPrefixListing(List<String> prefixes, boolean truncated) {

    public S3ChildPrefixListing {
        prefixes = List.copyOf(Objects.requireNonNull(prefixes, "prefixes"));
    }

    public static S3ChildPrefixListing complete(List<String> prefixes) {
        return new S3ChildPrefixListing(prefixes, false);
    }

    public static S3ChildPrefixListing truncated(List<String> prefixes) {
        return new S3ChildPrefixListing(prefixes, true);
    }
}
