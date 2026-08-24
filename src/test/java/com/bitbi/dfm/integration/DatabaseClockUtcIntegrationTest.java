package com.bitbi.dfm.integration;

import com.bitbi.dfm.auth.domain.RefreshToken;
import com.bitbi.dfm.auth.domain.RefreshTokenRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The statements that let PostgreSQL stamp the time write a UTC wall clock whatever zone the
 * database session is in (issue #286).
 *
 * <p><strong>What this pins that a static scan cannot.</strong> Six repository statements produce
 * a {@code TIMESTAMP} value with the database's own clock rather than the application's — #245's
 * deliberate choice, so that a fleet whose pods disagree about the time still stamps one order.
 * The clock they read is resolved in the <em>session's</em> zone, and pgjdbc sets that zone from
 * the JVM's default at connect time, so before this ticket they wrote local wall clock into
 * columns every other producer fills with UTC. Since JPQL has no {@code AT TIME ZONE} the six
 * became native SQL — and SQL inside {@code @Query} is a contract neither the compiler nor CI
 * checks, so it has to be driven by the real statements against a real PostgreSQL.</p>
 *
 * <p><strong>Why the zone is moved with {@code SET LOCAL} and not {@code SET}.</strong> A plain
 * {@code SET TIME ZONE} lives for the connection's session and would ride the pooled connection
 * back to whoever borrows it next — and this suite shares one database and one pool across cached
 * contexts. A neighbouring test would then be writing local wall clock into UTC columns because
 * of this file, which is the order-dependent, silent contamination of #226/#245. {@code SET LOCAL}
 * is undone by the end of the transaction, and each method here runs in one that rolls back.</p>
 *
 * <p><strong>Where the teeth are.</strong> The zone is moved by the test rather than inherited
 * from the JVM, so this is red against a bare {@code CURRENT_TIMESTAMP} in CI (which runs in UTC)
 * exactly as it is on a developer's machine — unlike {@code TimestampRoundTripIntegrationTest},
 * whose teeth depend on the ambient zone. Each case asserts the offset it is measuring is really
 * there before it asserts anything about the column, so a session zone that silently failed to
 * move cannot read as a pass.</p>
 */
@DisplayName("The database clock writes UTC whatever the session zone")
@Transactional
class DatabaseClockUtcIntegrationTest extends BaseIntegrationTest {

    /** store-01.example.com, seeded by test-data.sql. */
    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    /** A COMPLETED batch of that site, seeded by test-data.sql. */
    private static final UUID BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    /** The account that owns that site, seeded by test-data.sql. */
    private static final UUID ACCOUNT = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    /**
     * A zone whose offset from UTC no clock skew, container slowness or DST rule can shrink to
     * anything close to the tolerance below: {@code America/Los_Angeles} is 7 or 8 hours behind.
     */
    private static final String OFF_UTC = "America/Los_Angeles";

    /** Generous enough for a slow container, far below the offset it has to tell apart. */
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    @Autowired
    private ChangelogSegmentRepository segmentRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private AccountPluginRepository accountPluginRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void moveTheSessionZoneOffUtc() {
        jdbc.execute("SET LOCAL TIME ZONE '" + OFF_UTC + "'");
        assertThat(offsetOfTheSessionClock())
                .as("this test measures a zone offset, so the session must really be off UTC — "
                        + "SET LOCAL that did not take would make every case below vacuous")
                .isGreaterThan(Duration.ofHours(1));
    }

    @Test
    @DisplayName("markPluginSqlProcessed stamps the delta-SQL marker in UTC")
    void theDeltaSqlMarkerIsUtc() {
        UUID segment = seedSegment("DELTA");

        segmentRepository.markPluginSqlProcessed(segment);

        assertUtc(read("plugin_sql_at", segment), "changelog_segments.plugin_sql_at");
    }

    @Test
    @DisplayName("markEgressed stamps the egress marker in UTC")
    void theEgressMarkerIsUtc() {
        UUID segment = seedSegment("DELTA");

        segmentRepository.markEgressed(segment);

        assertUtc(read("egress_at", segment), "changelog_segments.egress_at");
    }

    @Test
    @DisplayName("markFullSnapshotPluginSqlProcessed stamps the parked marker in UTC")
    void theParkedSnapshotMarkerIsUtc() {
        UUID segment = seedSegment("FULL_SNAPSHOT");

        int updated = segmentRepository.markFullSnapshotPluginSqlProcessed(SITE);

        assertThat(updated)
                .as("the site-wide statement must have reached the seeded snapshot segment")
                .isGreaterThanOrEqualTo(1);
        assertUtc(read("plugin_sql_at", segment), "changelog_segments.plugin_sql_at (snapshot)");
    }

    @Test
    @DisplayName("revokeAllBySiteId stamps revoked_at in UTC")
    void theSiteWideRevocationIsUtc() {
        RefreshToken token = seedRefreshToken();

        refreshTokenRepository.revokeAllBySiteId(SITE);

        assertUtc(refreshTokenRevokedAt(token.getId()), "refresh_tokens.revoked_at (by site)");
    }

    @Test
    @DisplayName("revokeAllByFamilyId stamps revoked_at in UTC")
    void theFamilyWideRevocationIsUtc() {
        RefreshToken token = seedRefreshToken();

        refreshTokenRepository.revokeAllByFamilyId(token.getFamilyId());

        assertUtc(refreshTokenRevokedAt(token.getId()), "refresh_tokens.revoked_at (by family)");
    }

    @Test
    @DisplayName("updateLastUsedAtById stamps both activation timestamps in UTC")
    void theActivationTimestampsAreUtc() {
        AccountPlugin activation = accountPluginRepository.save(
                AccountPlugin.activate(ACCOUNT, "parquet-export", Map.of()));

        accountPluginRepository.updateLastUsedAtById(activation.getId());

        assertUtc(jdbc.queryForObject("SELECT last_used_at FROM account_plugins WHERE id = ?",
                LocalDateTime.class, activation.getId()), "account_plugins.last_used_at");
        assertUtc(jdbc.queryForObject("SELECT updated_at FROM account_plugins WHERE id = ?",
                LocalDateTime.class, activation.getId()), "account_plugins.updated_at");
    }

    /** How far the session's own clock reading is from UTC right now. */
    private Duration offsetOfTheSessionClock() {
        LocalDateTime sessionWallClock = jdbc.queryForObject(
                "SELECT CAST(current_timestamp AS timestamp)", LocalDateTime.class);
        return Duration.between(sessionWallClock, LocalDateTime.now(ZoneOffset.UTC)).abs();
    }

    private void assertUtc(LocalDateTime stored, String column) {
        LocalDateTime utcNow = LocalDateTime.now(ZoneOffset.UTC);
        assertThat(stored).as("%s was not written at all", column).isNotNull();
        assertThat(Duration.between(stored, utcNow).abs())
                .as("%s must hold the UTC wall clock, not the session zone's (%s; stored %s, "
                        + "UTC now %s)", column, OFF_UTC, stored, utcNow)
                .isLessThan(TOLERANCE);
    }

    private LocalDateTime read(String column, UUID segmentId) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM changelog_segments WHERE id = ?",
                LocalDateTime.class, segmentId);
    }

    private LocalDateTime refreshTokenRevokedAt(UUID id) {
        return jdbc.queryForObject("SELECT revoked_at FROM refresh_tokens WHERE id = ?",
                LocalDateTime.class, id);
    }

    private UUID seedSegment(String mode) {
        ChangelogSegment segment = segmentRepository.save(ChangelogSegment.create(
                SITE, BATCH, 1L, 5L, 5L, "hash-286",
                "delta/" + SITE + "/segments/286-" + UUID.randomUUID() + ".pb.gz", mode, Map.of()));
        return segment.getId();
    }

    private RefreshToken seedRefreshToken() {
        return refreshTokenRepository.save(
                RefreshToken.createNewFamily(SITE, "tz-286-" + UUID.randomUUID()));
    }
}
