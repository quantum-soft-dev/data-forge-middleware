package com.bitbi.dfm.batch.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for Batch aggregate (029: session activity tracking).
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@DisplayName("Batch Unit Tests")
class BatchTest {

    @Test
    @DisplayName("Should have null lastActivityAt on creation (v1 batches never set it)")
    void shouldHaveNullLastActivityAtOnCreation() {
        Batch batch = Batch.start(UUID.randomUUID(), UUID.randomUUID());

        assertThat(batch.getLastActivityAt()).isNull();
    }

    @Test
    @DisplayName("Should set lastActivityAt to now on touchActivity")
    void shouldTouchActivityUpdateLastActivityAt() {
        Batch batch = Batch.start(UUID.randomUUID(), UUID.randomUUID());
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);

        batch.touchActivity();

        assertThat(batch.getLastActivityAt())
                .isNotNull()
                .isAfter(before)
                .isBeforeOrEqualTo(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1));
    }

    @Test
    @DisplayName("Should advance lastActivityAt on repeated touches")
    void shouldAdvanceLastActivityAtOnRepeatedTouch() {
        Batch batch = Batch.start(UUID.randomUUID(), UUID.randomUUID());

        batch.touchActivity();
        LocalDateTime first = batch.getLastActivityAt();
        batch.touchActivity();

        assertThat(batch.getLastActivityAt()).isAfterOrEqualTo(first);
    }

    @Test
    @DisplayName("Should expire by startedAt when there was never any session activity")
    void shouldExpireByStartedAtWithoutActivity() {
        Batch batch = startedMinutesAgo(90);

        assertThat(batch.isExpired(60)).isTrue();
    }

    @Test
    @DisplayName("Should stay alive past the timeout while session activity is fresh (029)")
    void shouldNotExpireWhenActivityIsFresh() {
        Batch batch = startedMinutesAgo(90);

        batch.touchActivity();

        assertThat(batch.isExpired(60)).isFalse();
    }

    /** Old-started IN_PROGRESS batch via the protected all-args constructor (same package). */
    private static Batch startedMinutesAgo(int minutes) {
        LocalDateTime startedAt = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(minutes);
        return new Batch(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                BatchStatus.IN_PROGRESS, "path/", 0, 0L, false, startedAt, null, startedAt);
    }
}
