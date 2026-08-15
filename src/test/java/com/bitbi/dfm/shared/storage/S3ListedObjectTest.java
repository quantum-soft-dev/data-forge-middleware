package com.bitbi.dfm.shared.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wipe cut-off is compared to S3 {@code LastModified}, which is second-resolution.
 */
@DisplayName("S3ListedObject.lastModifiedAfter")
class S3ListedObjectTest {

    @Test
    @DisplayName("an object timestamped in the wipe's own second is treated as newer")
    void sameSecondAsWipeStartIsNewer() {
        // S3 floors LastModified to the whole second. A PutObject 200ms after a wipe that
        // started at .200 of the same second is stored as T+0s and must not be deleted.
        Instant startedAt = Instant.parse("2026-08-15T12:00:00.200Z");
        Instant s3Second = Instant.parse("2026-08-15T12:00:00Z");

        assertThat(new S3ListedObject("k", s3Second).lastModifiedAfter(startedAt)).isTrue();
    }

    @Test
    @DisplayName("an object from the previous second is old enough to sweep")
    void previousSecondIsNotNewer() {
        Instant startedAt = Instant.parse("2026-08-15T12:00:00.200Z");
        Instant previous = Instant.parse("2026-08-15T11:59:59Z");

        assertThat(new S3ListedObject("k", previous).lastModifiedAfter(startedAt)).isFalse();
    }

    @Test
    @DisplayName("a missing lastModified is treated as newer — the safe direction for the race")
    void nullLastModifiedIsNewer() {
        assertThat(new S3ListedObject("k", null).lastModifiedAfter(Instant.now())).isTrue();
    }
}
