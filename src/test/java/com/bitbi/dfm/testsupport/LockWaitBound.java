package com.bitbi.dfm.testsupport;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;
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
     * The floor is the longest wait the suite <em>declares</em> for a statement it blocks on
     * purpose, not the longest one usually observed.
     * {@code BatchParquetQueueServiceIntegrationTest.staleWorkerCannotRenewTheClaimAfterAConcurrentOperatorRequeue}
     * leaves an {@code UPDATE} waiting on an operator's row lock and releases it only after an
     * awaitility poll budgeted at <b>10 s</b>; it resolves in milliseconds on an idle machine, but
     * a bound at or under that budget turns a green test red on a loaded one.
     * {@code SiteHistoryWipeIntegrationTest} and {@code DeltaRebaselineIntegrationTest} hold their
     * rows for a fixed 1.5 s and are the easy ones.
     */
    public static final Duration MIN = Duration.ofSeconds(15);

    /**
     * Above this the bound stops doing its job: a blocked statement is supposed to fail while there
     * is still something to read, not to approximate the hang it replaces.
     */
    public static final Duration MAX = Duration.ofSeconds(60);

    /**
     * Every way an init statement can leave a session's {@code lock_timeout} somewhere other than
     * where the statement before it put it. Three families, and the second two are the ones easy
     * to miss: an assignment (to a number — PostgreSQL accepts a bare one as milliseconds, or one
     * with a unit, quoted or not — or to {@code DEFAULT}, in either the {@code SET} or the
     * {@code set_config} spelling); an undoing that never names the GUC ({@code RESET ALL},
     * {@code DISCARD ALL}) as well as the one that does; and a **transaction-scoped** assignment
     * ({@code SET LOCAL}, {@code set_config(..., true)}), which is undone by the commit Hikari
     * makes after running the init SQL, so it leaves a pooled session exactly as unbounded as no
     * statement at all.
     */
    private static final Pattern LOCK_TIMEOUT = Pattern.compile(
            "(?i)(?:(?<reset>\\breset\\s+(?:lock_timeout|all)\\b|\\bdiscard\\s+all\\b)"
                    + "|\\bset_config\\s*\\(\\s*'lock_timeout'\\s*,\\s*'(?<config>[^']*)'"
                    + "\\s*,\\s*(?<islocal>[a-z]+)"
                    + "|(?:\\bset\\s+(?<local>local\\s+))?\\block_timeout\\b\\s*(?:=|\\bto\\b)\\s*'?\\s*"
                    + "(?:(?<default>default)|(?<amount>\\d+)\\s*(?<unit>[a-z]*))\\s*'?)");

    /** The value {@code set_config} was handed, once the quotes are off. */
    private static final Pattern CONFIG_VALUE = Pattern.compile("(?i)^\\s*(\\d+)\\s*([a-z]*)\\s*$");

    /** Time units PostgreSQL accepts for a GUC; an empty unit means milliseconds. */
    private static final Map<String, Duration> UNITS = Map.of(
            "", Duration.ofMillis(1),
            "us", Duration.ofNanos(1_000),
            "ms", Duration.ofMillis(1),
            "s", Duration.ofSeconds(1),
            "min", Duration.ofMinutes(1),
            "h", Duration.ofHours(1),
            "d", Duration.ofDays(1));

    private LockWaitBound() {
    }

    /**
     * Reads the {@code lock_timeout} a connection-init statement leaves a session with.
     *
     * <p>The <em>last</em> mention wins, not the first: pgjdbc sends the init SQL over the simple
     * query protocol, which accepts several statements in one string, and PostgreSQL then applies
     * them in order. Reading the first match would green-light
     * {@code SET lock_timeout = '10s'; RESET lock_timeout}, which is exactly the false green this
     * guard exists to prevent.</p>
     *
     * @param connectionInitSql the declared {@code spring.datasource.hikari.connection-init-sql}
     * @return the bound it leaves behind, {@link Duration#ZERO} meaning "wait for ever" as
     *         PostgreSQL reads it — which is also how {@code RESET} and {@code DEFAULT} are read,
     *         since 0 is this GUC's server default
     * @throws AssertionError when the statement never mentions {@code lock_timeout}, or names a
     *         unit PostgreSQL does not use
     */
    public static Duration parseDeclared(String connectionInitSql) {
        assertNotNull(connectionInitSql, "no connection-init-sql to read a lock_timeout from (#197)");
        Matcher matcher = LOCK_TIMEOUT.matcher(connectionInitSql);
        Duration bound = null;
        while (matcher.find()) {
            bound = matchedBound(matcher, connectionInitSql);
        }
        assertNotNull(bound,
                "connection-init-sql sets no lock_timeout, so a statement blocked on a lock held by "
                        + "another cached context waits for ever and the run stops instead of "
                        + "failing (#197): " + connectionInitSql);
        return bound;
    }

    private static Duration matchedBound(Matcher matcher, String connectionInitSql) {
        if (matcher.group("reset") != null || matcher.group("default") != null) {
            return Duration.ZERO;
        }
        if (matcher.group("local") != null || "true".equalsIgnoreCase(matcher.group("islocal"))) {
            // Transaction-scoped, and Hikari commits the init SQL — the pooled session keeps
            // nothing, so this reads as the unbounded session it really leaves behind.
            return Duration.ZERO;
        }
        String config = matcher.group("config");
        if (config != null) {
            return configuredBound(config, connectionInitSql);
        }
        return scaled(matcher.group("amount"), matcher.group("unit"), connectionInitSql);
    }

    /**
     * {@code set_config('lock_timeout', ..., false)} is the function spelling of {@code SET}, and
     * an empty string is its spelling of {@code RESET}.
     */
    private static Duration configuredBound(String value, String connectionInitSql) {
        if (value.isBlank() || "default".equalsIgnoreCase(value.trim())) {
            return Duration.ZERO;
        }
        Matcher amount = CONFIG_VALUE.matcher(value);
        assertTrue(amount.matches(),
                "connection-init-sql hands set_config the lock_timeout value '" + value + "', which "
                        + "this reader cannot turn into a duration — the bound it really sets is not "
                        + "the one the range check below would judge (#197): " + connectionInitSql);
        return scaled(amount.group(1), amount.group(2), connectionInitSql);
    }

    private static Duration scaled(String amount, String rawUnit, String connectionInitSql) {
        String unit = rawUnit.toLowerCase(Locale.ROOT);
        Duration scale = UNITS.get(unit);
        assertNotNull(scale,
                "connection-init-sql gives lock_timeout the unit '" + unit + "', which PostgreSQL "
                        + "does not use — the bound it really sets is not the one this reads, and "
                        + "the range check below would pass on a number that means something else "
                        + "(#197): " + connectionInitSql);
        try {
            return scale.multipliedBy(Long.parseLong(amount));
        } catch (NumberFormatException | ArithmeticException e) {
            return fail("connection-init-sql gives lock_timeout the amount '" + amount + unit
                    + "', which is not a duration this reader can hold — the range check below "
                    + "would never judge it, and the guard has to say so rather than die on a raw "
                    + e.getClass().getSimpleName() + " (#197): " + connectionInitSql);
        }
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
                what + " bounds a lock wait at " + bound + ", below the " + MIN
                        + " floor this suite's own deliberate lock waits need — "
                        + "BatchParquetQueueServiceIntegrationTest budgets 10 s for an UPDATE it "
                        + "blocks on purpose, and a bound under that budget fails a healthy test "
                        + "on a loaded machine (#197)");
        assertTrue(bound.compareTo(MAX) <= 0,
                what + " bounds a lock wait at " + bound + ", above the " + MAX
                        + " ceiling: a bound that long no longer fails the blocked test while there "
                        + "is still something to read (#197)");
    }
}
