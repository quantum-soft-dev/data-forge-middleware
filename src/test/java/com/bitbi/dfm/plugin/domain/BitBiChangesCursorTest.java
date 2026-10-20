package com.bitbi.dfm.plugin.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BitBiChangesCursor — the position of a changes-feed page (#388)")
class BitBiChangesCursorTest {

    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    private static final UUID BATCH = UUID.fromString("0199bb10-4c2e-7a1d-9f00-3b5e1a2c7d41");
    private static final LocalDateTime STARTED = LocalDateTime.of(2026, 10, 20, 6, 0);

    /** {@code fc1:<SITE>:1792476000000000:<BATCH>}, written out: the example in the guide. */
    private static final String WIRE =
            "ZmMxOjAxOTliYWFjLWY4NTItNzUzZi02ZmMzLTdjOTk0ZmMzODY1NDoxNzkyNDc2MDAwMDAwMDAwOjAxOTliYjEwLTRjMmUtN2ExZC05ZjAwLTNiNWUxYTJjN2Q0MQ";

    private static String encodeRaw(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("The wire form is pinned")
    void wireFormIsPinned() {
        assertThat(new BitBiChangesCursor(SITE, STARTED, BATCH).encode()).isEqualTo(WIRE);
        assertThat(BitBiChangesCursor.decode(WIRE)).contains(new BitBiChangesCursor(SITE, STARTED, BATCH));
    }

    @Test
    @DisplayName("Microseconds survive the round trip, as PostgreSQL stores them; nanoseconds are cut")
    void microsecondsRoundTrip() {
        LocalDateTime precise = LocalDateTime.of(2026, 10, 20, 6, 0, 1, 123_456_000);
        BitBiChangesCursor cursor = new BitBiChangesCursor(SITE, precise.plusNanos(789), BATCH);

        assertThat(cursor.startedAt()).isEqualTo(precise);
        assertThat(BitBiChangesCursor.decode(cursor.encode())).contains(cursor);
    }

    @Test
    @DisplayName("A time before 1970 round-trips too")
    void beforeTheEpoch() {
        BitBiChangesCursor cursor = new BitBiChangesCursor(SITE, LocalDateTime.of(1969, 12, 31, 23, 59, 59, 500_000_000),
                BATCH);

        assertThat(BitBiChangesCursor.decode(cursor.encode())).contains(cursor);
    }

    @ParameterizedTest(name = "\"{0}\" is not a cursor")
    @ValueSource(strings = {"", "garbage!", "Zm9v"})
    void garbageIsNotACursor(String wire) {
        assertThat(BitBiChangesCursor.decode(wire)).isEmpty();
    }

    @ParameterizedTest(name = "raw \"{0}\" is not a cursor")
    @ValueSource(strings = {
            "fc2:0199baac-f852-753f-6fc3-7c994fc38654:1792476000000000:0199bb10-4c2e-7a1d-9f00-3b5e1a2c7d41",
            "fc1:0199baac-f852-753f-6fc3-7c994fc38654:x:0199bb10-4c2e-7a1d-9f00-3b5e1a2c7d41",
            "fc1:0199baac-f852-753f-6fc3-7c994fc38654:1792476000000000",
            "fc1:0199baac-f852-753f-6fc3-7c994fc38654:1792476000000000:0199bb10-4c2e-7a1d-9f00-3b5e1a2c7d41:x",
            "fc1:0199baac-f852-753f-6fc3-7c994fc38654:+1792476000000000:0199bb10-4c2e-7a1d-9f00-3b5e1a2c7d41",
            "fc1:0199BAAC-F852-753F-6FC3-7C994FC38654:1792476000000000:0199bb10-4c2e-7a1d-9f00-3b5e1a2c7d41"
    })
    void malformedOrAnotherSpelling(String raw) {
        assertThat(BitBiChangesCursor.decode(encodeRaw(raw))).isEmpty();
    }

    @Test
    @DisplayName("A null cursor decodes to nothing")
    void nullIsNothing() {
        assertThat(BitBiChangesCursor.decode(null)).isEmpty();
    }
}
