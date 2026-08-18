package com.bitbi.dfm.testsupport;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One definition of "how long a statement in this suite may wait for a lock" (issue #197).
 *
 * <p>The integration classes share one PostgreSQL database across every cached Spring context, and
 * each context keeps its background workers alive for the whole run. A statement of the class under
 * test — {@code @Sql("/test-data.sql")}, a {@code clearPluginSqlGenerations} delete, an ordinary
 * repository write — can therefore wait on a row lock held by a sibling context. Without a
 * database-side bound that wait has no end: the JUnit thread never returns, the run stops rather
 * than fails, and CI kills the job with no indication of which test was stuck.</p>
 *
 * <p>The bound is {@code lock_timeout}, applied to every pooled connection by
 * {@code spring.datasource.hikari.connection-init-sql} in {@code application-test.yml}. Two guards
 * need to agree on what a sane value is — the static one over that file and the wired one that
 * reads the setting a real pooled connection carries — so the range and the parser live here
 * instead of once in each ({@code RunOwnedScratch} sets the precedent).</p>
 *
 * <p><b>Why a range rather than the number.</b> The value is a judgement about the suite's slowest
 * <em>legitimate</em> lock wait, and it is expected to be tuned. What must not change is that it
 * exists, is comfortably above that wait, and is far below "the run hangs".</p>
 */
public final class LockWaitBound {

    /**
     * The longest lock wait the suite legitimately produces is
     * {@code SiteHistoryWipeIntegrationTest}, which holds the {@code site_sync_state} row for 1.5 s
     * while a checkpoint build blocks on it at the epoch check. Anything at or below that turns a
     * deliberate, asserted wait into a failure.
     */
    public static final Duration MIN = Duration.ofSeconds(5);

    /**
     * Above this the bound stops doing its job: a blocked statement is supposed to fail while there
     * is still something to read, not to approximate the hang it replaces.
     */
    public static final Duration MAX = Duration.ofSeconds(60);

    /**
     * {@code SET lock_timeout = '10s'} and its spelling variants. PostgreSQL accepts a bare number
     * (milliseconds) or a number with a unit, quoted or not.
     */
    private static final Pattern SET_LOCK_TIMEOUT = Pattern.compile(
            "(?i)\\block_timeout\\b\\s*(?:=|\\bto\\b)\\s*'?\\s*(\\d+)\\s*(ms|s|min|h)?\\s*'?");

    private LockWaitBound() {
    }

    /**
     * Reads the {@code lock_timeout} a connection-init statement sets.
     *
     * @param connectionInitSql the declared {@code spring.datasource.hikari.connection-init-sql}
     * @return the bound it sets, {@link Duration#ZERO} meaning "wait for ever" as PostgreSQL reads it
     * @throws AssertionError when the statement sets no {@code lock_timeout} at all
     */
    public static Duration parseDeclared(String connectionInitSql) {
        assertNotNull(connectionInitSql, "no connection-init-sql to read a lock_timeout from (#197)");
        Matcher matcher = SET_LOCK_TIMEOUT.matcher(connectionInitSql);
        assertTrue(matcher.find(),
                "connection-init-sql sets no lock_timeout, so a statement blocked on a lock held by "
                        + "another cached context waits for ever and the run stops instead of "
                        + "failing (#197): " + connectionInitSql);
        long amount = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2) == null ? "ms" : matcher.group(2).toLowerCase(Locale.ROOT);
        return switch (unit) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "min" -> Duration.ofMinutes(amount);
            default -> Duration.ofHours(amount);
        };
    }

    /**
     * Fails unless the bound exists and sits between {@link #MIN} and {@link #MAX} inclusive.
     *
     * @param what  where the value was read from, for the failure message
     * @param bound the configured lock wait bound
     */
    public static void assertBoundsALockWait(String what, Duration bound) {
        assertNotNull(bound, what + " has no lock_timeout (#197)");
        assertFalse(bound.isZero(),
                what + " sets lock_timeout to 0, which is PostgreSQL for \"wait for ever\" — the "
                        + "default this guard exists to replace (#197)");
        assertTrue(bound.compareTo(MIN) >= 0,
                what + " bounds a lock wait at " + bound + ", at or below the " + MIN
                        + " the suite legitimately spends waiting on a lock — "
                        + "SiteHistoryWipeIntegrationTest holds the site_sync_state row for 1.5 s "
                        + "while a checkpoint build blocks on it on purpose (#197)");
        assertTrue(bound.compareTo(MAX) <= 0,
                what + " bounds a lock wait at " + bound + ", above the " + MAX
                        + " ceiling: a bound that long no longer fails the blocked test while there "
                        + "is still something to read (#197)");
    }
}
