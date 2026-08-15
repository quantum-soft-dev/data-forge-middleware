package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.shared.storage.S3ListedObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wipe cut-off is compared to S3 {@code LastModified}, which is second-resolution.
 */
@DisplayName("DeltaSiteWipeService.writtenAtOrAfterWipeSecond")
class DeltaSiteWipeCutoffTest {

    @Test
    @DisplayName("an object timestamped in the wipe's own second is treated as newer")
    void sameSecondAsWipeStartIsNewer() {
        Instant startedAt = Instant.parse("2026-08-15T12:00:00.200Z");
        Instant s3Second = Instant.parse("2026-08-15T12:00:00Z");

        assertThat(DeltaSiteWipeService.writtenAtOrAfterWipeSecond(
                new S3ListedObject("k", s3Second), startedAt)).isTrue();
    }

    @Test
    @DisplayName("an object from the previous second is old enough to sweep")
    void previousSecondIsNotNewer() {
        Instant startedAt = Instant.parse("2026-08-15T12:00:00.200Z");
        Instant previous = Instant.parse("2026-08-15T11:59:59Z");

        assertThat(DeltaSiteWipeService.writtenAtOrAfterWipeSecond(
                new S3ListedObject("k", previous), startedAt)).isFalse();
    }

    @Test
    @DisplayName("a missing lastModified is treated as newer")
    void nullLastModifiedIsNewer() {
        assertThat(DeltaSiteWipeService.writtenAtOrAfterWipeSecond(
                new S3ListedObject("k", null), Instant.now())).isTrue();
    }
}
