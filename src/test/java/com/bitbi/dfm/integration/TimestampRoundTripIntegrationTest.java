package com.bitbi.dfm.integration;

import com.bitbi.dfm.auth.domain.RefreshToken;
import com.bitbi.dfm.auth.domain.RefreshTokenRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A zone-independent {@code TIMESTAMP} column holds the same UTC wall clock whichever path writes
 * or reads it, in whichever zone the JVM happens to run (issue #282).
 *
 * <p><strong>Why this test could not exist before.</strong> #280 wanted exactly this statement and
 * refused it: with {@code spring.jpa.properties.hibernate.jdbc.time_zone: UTC} in place, a value
 * written through JPA and read back through {@link JdbcTemplate} differs by the JVM's offset, so
 * the test would have been green in CI and deterministically red for every developer outside UTC —
 * #279 recreated by the guard meant to prevent it. Removing that setting is what makes the
 * statement true, and asserting it is what stops the setting coming back unnoticed.</p>
 *
 * <p><strong>Why it sets the JVM's default zone instead of inheriting it.</strong> Inherited, the
 * property is observable only off UTC, so in CI the test would be unable to fail — it would pass
 * against the very conversion it exists to keep out. Choosing the zone makes it red under mutation
 * everywhere. The mutation is restoring the setting: both methods then fail on a shifted column.</p>
 *
 * <p>Changing the default zone is process-global, and safe here for a reason this ticket supplies:
 * the suite runs in one JVM with no parallel forks (#207), and since #282 every producer names
 * {@code ZoneOffset.UTC} explicitly, so a background sweep running in a cached context during the
 * window cannot read the zone this test installed. It is restored in a {@code finally}.</p>
 */
@DisplayName("A TIMESTAMP column round-trips as UTC in any JVM zone")
class TimestampRoundTripIntegrationTest extends BaseIntegrationTest {

    /** store-01.example.com, seeded by test-data.sql. */
    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    /** A COMPLETED batch of that site, seeded by test-data.sql. */
    private static final UUID BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    /** Generous enough for a slow container, far below any zone offset it has to tell apart. */
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    @Autowired
    private ChangelogSegmentRepository segmentRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @ParameterizedTest(name = "JVM in {0}")
    @ValueSource(strings = {"UTC", "Asia/Jerusalem", "America/Los_Angeles"})
    @DisplayName("a LocalDateTime stamped by an entity reaches the column as the UTC wall clock")
    void aLocalDateTimeFieldIsStoredAndReadAsTheColumnsOwnValue(String zone) {
        inZone(zone, () -> {
            LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
            ChangelogSegment segment = segmentRepository.save(ChangelogSegment.create(
                    SITE, BATCH, 1L, 5L, 5L, "hash-282",
                    "delta/" + SITE + "/segments/282-" + UUID.randomUUID() + ".pb.gz", "DELTA", Map.of()));

            LocalDateTime raw = jdbc.queryForObject(
                    "SELECT created_at FROM changelog_segments WHERE id = ?",
                    LocalDateTime.class, segment.getId());

            assertThat(raw)
                    .as("the raw column must equal what the entity stamped — a conversion on the "
                            + "JPA path would shift it by the JVM's offset from %s", zone)
                    .isEqualTo(segment.getCreatedAt());
            assertThat(raw)
                    .as("and that value must be a UTC wall clock, not this JVM's local one")
                    .isBetween(before.minus(TOLERANCE), LocalDateTime.now(ZoneOffset.UTC).plus(TOLERANCE));

            jdbc.update("DELETE FROM changelog_segments WHERE id = ?", segment.getId());
        });
    }

    @Test
    @DisplayName("an Instant field stores the UTC wall clock whatever the JVM zone")
    void anInstantFieldIsStoredAsUtcInEveryZone() {
        // The measurement this ticket's decision rests on: Instant binding never consulted
        // hibernate.jdbc.time_zone, so removing that setting could not shift the ~15 TIMESTAMP
        // columns held as Instant (refresh_tokens, account_plugins, plugin_configs,
        // admin_action_logs, comparison_results, file_comparisons) — which is why no data
        // migration was needed. Asserted so that a future Hibernate upgrade cannot change it
        // quietly.
        for (String zone : new String[]{"UTC", "Asia/Jerusalem", "America/Los_Angeles"}) {
            inZone(zone, () -> {
                RefreshToken token = refreshTokenRepository.save(
                        RefreshToken.createNewFamily(SITE, "tz-282-" + UUID.randomUUID()));

                LocalDateTime raw = jdbc.queryForObject(
                        "SELECT created_at FROM refresh_tokens WHERE id = ?",
                        LocalDateTime.class, token.getId());

                assertThat(raw)
                        .as("an Instant must reach the column as its UTC wall clock, in %s as in UTC", zone)
                        .isEqualTo(LocalDateTime.ofInstant(token.getCreatedAt(), ZoneOffset.UTC));

                jdbc.update("DELETE FROM refresh_tokens WHERE id = ?", token.getId());
            });
        }
    }

    /** Runs {@code body} with the JVM's default zone set to {@code zone}, restoring it after. */
    private static void inZone(String zone, Runnable body) {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            body.run();
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
