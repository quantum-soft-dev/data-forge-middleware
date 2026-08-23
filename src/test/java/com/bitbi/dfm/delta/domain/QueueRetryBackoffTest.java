package com.bitbi.dfm.delta.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The retry policy both segment work queues defer a failing segment with (issue #243).
 */
@DisplayName("QueueRetryBackoff")
class QueueRetryBackoffTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 20, 12, 0).toInstant(ZoneOffset.UTC)
            .atZone(ZoneOffset.UTC).toLocalDateTime();

    private static final String DELAY_KEY = "delta.egress.retry-delay-seconds";
    private static final String ATTEMPTS_KEY = "delta.egress.poison-after-attempts";

    private final QueueRetryBackoff backoff = new QueueRetryBackoff(DELAY_KEY, 60, ATTEMPTS_KEY, 7);

    @Test
    @DisplayName("doubles per attempt so a transient outage gets a wide window")
    void shouldDoublePerAttempt() {
        assertThat(backoff.nextRetryAt(NOW, 1)).isEqualTo(NOW.plusSeconds(60));
        assertThat(backoff.nextRetryAt(NOW, 2)).isEqualTo(NOW.plusSeconds(120));
        assertThat(backoff.nextRetryAt(NOW, 3)).isEqualTo(NOW.plusSeconds(240));
        assertThat(backoff.nextRetryAt(NOW, 7)).isEqualTo(NOW.plusSeconds(3840));
    }

    @Test
    @DisplayName("caps the doubling at 64x, so a permanently poisoned segment is retried hourly for ever")
    void shouldCapTheDoubling() {
        assertThat(backoff.nextRetryAt(NOW, 8)).isEqualTo(NOW.plusSeconds(60L * 64));
        assertThat(backoff.nextRetryAt(NOW, 400)).isEqualTo(NOW.plusSeconds(60L * 64));
        assertThat(backoff.nextRetryAt(NOW, Integer.MAX_VALUE)).isEqualTo(NOW.plusSeconds(60L * 64));
    }

    @Test
    @DisplayName("an attempt count below one is still the first attempt's delay")
    void shouldTreatNonPositiveAttemptsAsTheFirst() {
        assertThat(backoff.nextRetryAt(NOW, 0)).isEqualTo(NOW.plusSeconds(60));
        assertThat(backoff.nextRetryAt(NOW, -3)).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    @DisplayName("a segment is poisoned once it reaches the configured attempt count")
    void shouldReportPoisonedAtTheThreshold() {
        assertThat(backoff.isPoisoned(6)).isFalse();
        assertThat(backoff.isPoisoned(7)).isTrue();
        assertThat(backoff.isPoisoned(8)).isTrue();
    }

    @Test
    @DisplayName("an out-of-range value fails startup naming its own key (#185)")
    void shouldRefuseAnOutOfRangeValueNamingTheKey() {
        assertThatThrownBy(() -> new QueueRetryBackoff(DELAY_KEY, 0, ATTEMPTS_KEY, 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(DELAY_KEY)
                .hasMessageContaining("0");
        assertThatThrownBy(() -> new QueueRetryBackoff("plugin.sql-generation.delta-retry-delay-seconds", 60,
                "plugin.sql-generation.delta-poison-after-attempts", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plugin.sql-generation.delta-poison-after-attempts");
    }
}
