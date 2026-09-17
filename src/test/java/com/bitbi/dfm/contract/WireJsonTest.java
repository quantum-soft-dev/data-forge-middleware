package com.bitbi.dfm.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The matcher behind the #300 wire characterization must fail on exactly the differences Jackson 3
 * can introduce — otherwise every pinned body passes vacuously.
 */
class WireJsonTest {

    private static final String TEMPLATE =
            "{\"id\":\"<uuid>\",\"status\":\"IN_PROGRESS\",\"count\":1,\"at\":\"<instant>\",\"done\":null}";

    @Test
    @DisplayName("matches the literal body with placeholders filled")
    void matchesFilledTemplate() {
        assertThat(WireJson.matches(TEMPLATE,
                "{\"id\":\"b1c2d3e4-f5a6-7890-bcde-f12345678903\",\"status\":\"IN_PROGRESS\",\"count\":1,"
                        + "\"at\":\"2026-09-17T14:06:40.929989Z\",\"done\":null}")).isTrue();
    }

    @Test
    @DisplayName("an Instant is accepted with 0, 3, 6 or 9 fraction digits and only with Z")
    void instantForms() {
        String t = "\"<instant>\"";
        assertThat(WireJson.matches(t, "\"2026-09-17T14:06:40Z\"")).isTrue();
        assertThat(WireJson.matches(t, "\"2026-09-17T14:06:40.123Z\"")).isTrue();
        assertThat(WireJson.matches(t, "\"2026-09-17T14:06:40.123456789Z\"")).isTrue();
        assertThat(WireJson.matches(t, "\"2026-09-17T14:06:40.1234Z\"")).isFalse();
        assertThat(WireJson.matches(t, "\"2026-09-17T14:06:40+00:00\"")).isFalse();
        assertThat(WireJson.matches(t, "1789654000.929989000")).isFalse();
    }

    @Test
    @DisplayName("a LocalDateTime carries no offset")
    void localDateTimeForms() {
        String t = "\"<local-date-time>\"";
        assertThat(WireJson.matches(t, "\"2026-09-17T14:06:40.5\"")).isFalse();
        assertThat(WireJson.matches(t, "\"2026-09-17T14:06:40.500\"")).isTrue();
        assertThat(WireJson.matches(t, "\"2026-09-17T14:06\"")).isTrue();
        assertThat(WireJson.matches(t, "\"2026-09-17T14:06:40Z\"")).isFalse();
        assertThat(WireJson.matches(t, "[2026,9,17,14,6,40]")).isFalse();
    }

    @Test
    @DisplayName("reordered keys, an omitted null and a widened number all fail")
    void rejectsJackson3StyleDifferences() {
        String reordered = "{\"at\":\"2026-09-17T14:06:40Z\",\"count\":1,\"done\":null,"
                + "\"id\":\"b1c2d3e4-f5a6-7890-bcde-f12345678903\",\"status\":\"IN_PROGRESS\"}";
        String nullOmitted = "{\"id\":\"b1c2d3e4-f5a6-7890-bcde-f12345678903\",\"status\":\"IN_PROGRESS\","
                + "\"count\":1,\"at\":\"2026-09-17T14:06:40Z\"}";
        String widened = "{\"id\":\"b1c2d3e4-f5a6-7890-bcde-f12345678903\",\"status\":\"IN_PROGRESS\","
                + "\"count\":1.0,\"at\":\"2026-09-17T14:06:40Z\",\"done\":null}";
        assertThat(WireJson.matches(TEMPLATE, reordered)).isFalse();
        assertThat(WireJson.matches(TEMPLATE, nullOmitted)).isFalse();
        assertThat(WireJson.matches(TEMPLATE, widened)).isFalse();
    }

    @Test
    @DisplayName("template metacharacters are literal, and a failure shows the actual body")
    void literalAndDiagnostic() {
        assertThat(WireJson.matches("{\"a\":[1.5]}", "{\"a\":[105]}")).isFalse();
        assertThatThrownBy(() -> WireJson.assertMatches("{}", "{\"x\":1}"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("{\"x\":1}");
    }
}
