package com.bitbi.dfm.integration;

import com.bitbi.dfm.auth.domain.RefreshToken;
import com.bitbi.dfm.auth.domain.RefreshTokenRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * or reads it (issue #282).
 *
 * <p><strong>Why this test could not exist before.</strong> #280 wanted exactly this statement and
 * refused it: with {@code spring.jpa.properties.hibernate.jdbc.time_zone: UTC} in place, a value
 * written through JPA and read back through {@link JdbcTemplate} differs by the JVM's offset, so
 * the test would have been red for every developer outside UTC — #279 recreated by the guard meant
 * to prevent it. Removing that setting is what makes the statement true in every zone.</p>
 *
 * <p><strong>Where the teeth are, stated plainly.</strong> This test runs in whatever zone the JVM
 * is in. On a developer's machine outside UTC that makes it mutation-sensitive — restore the
 * setting and both methods fail. In CI, which runs in UTC, the removed conversion is the identity
 * and this test would stay green, so it is <em>not</em> what stops the setting coming back there:
 * {@code TimestampProducerConventionTest} asserts the setting's absence directly and gives the same
 * answer in every environment. The two are complementary — one pins the configuration, this one
 * pins that the paths actually agree end to end, which would also catch a conversion arriving by
 * some other route (a dialect change, a Hibernate upgrade).</p>
 *
 * <p><strong>Why it does not set the JVM's default zone to get teeth in CI.</strong> An earlier cut
 * did, running each case in UTC, {@code Asia/Jerusalem} and {@code America/Los_Angeles}. It was
 * withdrawn: pgjdbc takes a connection's PostgreSQL session zone from the JVM's default <em>at
 * connect time</em>, and this suite shares one database and one pool across cached contexts with
 * {@code minimum-idle: 0} and a ten-second idle timeout, so a connection opened during such a
 * window would carry a non-UTC session zone back into the pool. An unrelated test's fixture — some
 * forty of which still seed rows with a bare {@code CURRENT_TIMESTAMP} — would then write a local
 * wall clock into a column everything else fills with UTC: a silent, order-dependent failure in a
 * shared database, which is the #226/#245 class of defect and far worse than the blind spot it was
 * buying. #286 took the six production statements that used to be in that population out of it, so
 * the hazard is now the fixtures alone; the safe way to move the zone is
 * {@code DatabaseClockUtcIntegrationTest}'s, a {@code SET LOCAL} the transaction undoes.</p>
 */
@DisplayName("A TIMESTAMP column round-trips as UTC")
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

    @Test
    @DisplayName("a LocalDateTime stamped by an entity reaches the column as the UTC wall clock")
    void aLocalDateTimeFieldIsStoredAndReadAsTheColumnsOwnValue() {
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
        ChangelogSegment segment = segmentRepository.save(ChangelogSegment.create(
                SITE, BATCH, 1L, 5L, 5L, "hash-282",
                "delta/" + SITE + "/segments/282-" + UUID.randomUUID() + ".pb.gz", "DELTA", Map.of()));
        try {
            LocalDateTime raw = jdbc.queryForObject(
                    "SELECT created_at FROM changelog_segments WHERE id = ?",
                    LocalDateTime.class, segment.getId());

            assertSameWallClock(raw, segment.getCreatedAt(),
                    "the raw column must be what the entity stamped — a conversion on the JPA path "
                            + "would shift it by this JVM's offset (" + TimeZone.getDefault().getID() + ")");
            assertThat(raw)
                    .as("and that value must be a UTC wall clock, not this JVM's local one")
                    .isBetween(before.minus(TOLERANCE), LocalDateTime.now(ZoneOffset.UTC).plus(TOLERANCE));
        } finally {
            jdbc.update("DELETE FROM changelog_segments WHERE id = ?", segment.getId());
        }
    }

    @Test
    @DisplayName("an Instant field stores the UTC wall clock")
    void anInstantFieldIsStoredAsUtc() {
        // The measurement this ticket's decision rests on: Instant binding never consulted
        // hibernate.jdbc.time_zone, so removing that setting could not shift the TIMESTAMP columns
        // held as Instant (refresh_tokens, account_plugins, plugin_configs, admin_action_logs,
        // comparison_results, file_comparisons) — which is why no data migration was needed.
        // Asserted so a future Hibernate upgrade cannot change it quietly.
        RefreshToken token = refreshTokenRepository.save(
                RefreshToken.createNewFamily(SITE, "tz-282-" + UUID.randomUUID()));
        try {
            LocalDateTime raw = jdbc.queryForObject(
                    "SELECT created_at FROM refresh_tokens WHERE id = ?",
                    LocalDateTime.class, token.getId());

            assertSameWallClock(raw, LocalDateTime.ofInstant(token.getCreatedAt(), ZoneOffset.UTC),
                    "an Instant must reach the column as its UTC wall clock");
        } finally {
            jdbc.update("DELETE FROM refresh_tokens WHERE id = ?", token.getId());
        }
    }

    /**
     * The two values name the same wall clock, up to what the column can store.
     *
     * <p>Not {@code isEqualTo}: PostgreSQL {@code TIMESTAMP} keeps microseconds, and the JVM clock
     * has finer resolution on Linux than on macOS — so an exact comparison passes on a developer's
     * machine and fails in CI on the digits the column rounded away, which is what the first cut of
     * this test did. Not {@code isEqualToIgnoringNanos} either: storage <em>rounds</em>, so a stamp
     * at {@code …:17.9999996} lands in the next second, a field that comparison still checks. The
     * shift this test exists to detect is a zone offset — whole hours, or 30 minutes at the finest
     * — so nothing near a millisecond is a defect it could see.</p>
     */
    private static void assertSameWallClock(LocalDateTime raw, LocalDateTime expected, String reason) {
        assertThat(raw).as(reason).isNotNull();
        assertThat(Duration.between(raw, expected).abs())
                .as("%s (column %s, stamped %s)", reason, raw, expected)
                .isLessThan(Duration.ofMillis(1));
    }
}
