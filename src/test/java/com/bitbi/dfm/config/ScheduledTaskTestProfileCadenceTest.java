package com.bitbi.dfm.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.lang.reflect.Method;
import java.security.CodeSource;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #167 — a newly added queue-drain sweep must not keep production cadence under {@code test}.
 *
 * <p>When {@code DeltaSqlSweepWorker} was added, nothing required {@code application-test.yml} to
 * override {@code plugin.sql-generation.delta-sweep-ms}. The fallback 60 s tick then ran in every
 * cached Spring context and held row locks across S3, which surfaced as a
 * {@code ScriptStatementFailedException} in the next class's {@code @Sql} (#159 / #163). This
 * inventory is the thing that would have failed the day the annotation landed.</p>
 *
 * <p>The two remaining ticks that still fire inside a suite longer than five minutes are
 * allowlisted below with the reason the audit accepted; a newcomer that is equally fast is not
 * on that list and fails here until it is slowed or justified. {@code *sweep-ms} tasks that fire
 * at context refresh ({@code initialDelay} 0 / unset) are a separate list — delaying the first
 * production tick is a crash-recovery change and is not made here.</p>
 */
@DisplayName("Scheduled task test-profile cadence (#167)")
class ScheduledTaskTestProfileCadenceTest {

    /**
     * Recurring ticks slower than this do not race a suite. Matches the existing
     * {@code application-test.yml} overrides for the three queue-drain sweeps.
     */
    static final long MIN_TEST_SWEEP_MS = 3_600_000L;

    private static final String BASE_PACKAGE = "com.bitbi.dfm";

    private static final java.net.URL TEST_OUTPUT_ROOT =
            ScheduledTaskTestProfileCadenceTest.class.getProtectionDomain().getCodeSource().getLocation();

    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([^:}]+)(?::(.*))?}$");

    /**
     * Ticks whose effective test-profile period is shorter than {@link #MIN_TEST_SWEEP_MS} and
     * that are allowed to stay that way. Same shape as {@link ScheduledTaskInventoryTest}'s
     * known-list: a newcomer is a failure until it is slowed or justified here.
     *
     * <p>Neither of these can be slowed from {@code application-test.yml} — the interval is a
     * literal on the annotation, and replacing it with a property is a production change this
     * ticket does not make.</p>
     */
    private static final Map<String, String> FAST_UNDER_TEST_ALLOWLIST = fastUnderTestAllowlist();

    private static Map<String, String> fastUnderTestAllowlist() {
        Map<String, String> allow = new LinkedHashMap<>();
        allow.put("com.bitbi.dfm.batch.application.BatchTimeoutScheduler#checkExpiredBatches",
                "cron 0 */5 * * * * is the production SLA for abandoned IN_PROGRESS batches, not a "
                        + "queue drain. First fire is the next :00/:05 wall-clock, not context refresh. "
                        + "It only UPDATEs batches already past batch.timeout.minutes (60), and "
                        + "test-data.sql seeds IN_PROGRESS rows at CURRENT_TIMESTAMP, so a live test's "
                        + "batch is not in the SELECT. No S3.");
        allow.put("com.bitbi.dfm.deviceauth.application.DeviceAuthorizationService#cleanupExpired",
                "fixedRate 300000 marks device_authorizations with expires_at < now. test-data.sql "
                        + "does not seed that table, and an in-test authorization that is still valid is "
                        + "not in the UPDATE. No changelog_segments, no S3. First fire is at context "
                        + "refresh but the work is an empty UPDATE on a typical class.");
        return allow;
    }

    /**
     * {@code *sweep-ms} tasks whose first fire is at context refresh and that are allowed to stay
     * that way. Adding {@code initialDelayString} on the annotation would move the first
     * production sweep from t=0 to t=interval; that is the crash-recovery pass a 60 s wait would
     * delay, and it is deliberately not changed here. The residual under {@code test} is one drain
     * per cached context rather than one per context per minute.
     */
    private static final Map<String, String> IMMEDIATE_FIRST_SWEEP_ALLOWLIST = immediateFirstSweepAllowlist();

    private static Map<String, String> immediateFirstSweepAllowlist() {
        Map<String, String> allow = new LinkedHashMap<>();
        String queueDrain = "first tick is the crash-recovery pass for a wake missed at commit; "
                + "a production initialDelay equal to the interval would delay that by 60 s. Under "
                + "test the interval is one hour, so only this first drain runs per context.";
        allow.put("com.bitbi.dfm.delta.application.DeltaEgressWorker#sweep", queueDrain);
        allow.put("com.bitbi.dfm.delta.application.BatchParquetFinalizationWorker#sweep", queueDrain);
        allow.put("com.bitbi.dfm.plugin.application.DeltaSqlSweepWorker#sweep", queueDrain);
        allow.put("com.bitbi.dfm.delta.application.ParquetScratchOrphanSweeper#sweep",
                "initialDelayString is explicitly 0 so a restart sweeps predecessor scratch before "
                        + "the next hourly tick; the directories are local files, not row locks. "
                        + "Production interval is already one hour.");
        return allow;
    }

    @Test
    @DisplayName("every *sweep-ms interval is declared under test and is at least one hour")
    void shouldDeclareAndSlowEverySweepMsKeyUnderTheTestProfile() {
        Map<String, Object> testYaml = testYaml();
        List<Discovered> tasks = scanScheduledTasks();
        assertFalse(tasks.isEmpty(), "no @Scheduled method was found — the scan is broken");
        assertEquals(ScheduledTaskInventoryTest.scanScheduledMethods(), namesOf(tasks),
                "this scan and ScheduledTaskInventoryTest must see the same @Scheduled methods");

        Map<String, Long> found = new TreeMap<>();
        List<String> missingFromTestYaml = new ArrayList<>();
        List<String> stillFast = new ArrayList<>();

        for (Discovered task : tasks) {
            for (String key : sweepMsKeys(task.scheduled())) {
                Long testMs = readDeclaredMillis(testYaml, key);
                if (testMs == null) {
                    missingFromTestYaml.add(task.name() + " -> " + key);
                    continue;
                }
                found.put(key, testMs);
                if (testMs < MIN_TEST_SWEEP_MS) {
                    stillFast.add(task.name() + " -> " + key + "=" + testMs);
                }
            }
        }

        assertTrue(missingFromTestYaml.isEmpty(),
                "a *sweep-ms key is not declared in application-test.yml, so the test profile "
                        + "inherits production cadence — the hole that left "
                        + "plugin.sql-generation.delta-sweep-ms at 60 s for the life of 026 (#159). "
                        + "Declare it (value >= " + MIN_TEST_SWEEP_MS + ") or the next queue repeats "
                        + "it: " + missingFromTestYaml);
        assertTrue(stillFast.isEmpty(),
                "a *sweep-ms key is declared under test but still at production cadence: " + stillFast);
        assertFalse(found.isEmpty(),
                "no *sweep-ms placeholder was found on any @Scheduled method — the matcher is broken");
    }

    @Test
    @DisplayName("every tick faster than one hour under test is on the justified allowlist")
    void shouldAllowlistOrSlowEveryFastTickUnderTheTestProfile() {
        Map<String, Object> testYaml = testYaml();
        Map<String, Object> prodYaml = prodYaml();
        List<Discovered> tasks = scanScheduledTasks();

        Set<String> fast = new TreeSet<>();
        for (Discovered task : tasks) {
            long periodMs = effectivePeriodMs(task.scheduled(), testYaml, prodYaml);
            if (periodMs < MIN_TEST_SWEEP_MS) {
                fast.add(task.name());
            }
        }

        assertEquals(new TreeSet<>(FAST_UNDER_TEST_ALLOWLIST.keySet()), fast,
                "the set of sub-hour ticks under the test profile changed. Slow the newcomer in "
                        + "application-test.yml (property-backed interval) or add it to "
                        + "FAST_UNDER_TEST_ALLOWLIST with a reason — the same audit #159 missed for "
                        + "plugin.sql-generation.delta-sweep-ms");

        for (Map.Entry<String, String> entry : FAST_UNDER_TEST_ALLOWLIST.entrySet()) {
            assertFalse(entry.getValue() == null || entry.getValue().isBlank(),
                    entry.getKey() + " is on FAST_UNDER_TEST_ALLOWLIST without a reason");
        }
    }

    @Test
    @DisplayName("every *sweep-ms task that fires at context refresh is on the justified allowlist")
    void shouldAllowlistImmediateFirstTickOfSweepMsTasks() {
        Map<String, Object> testYaml = testYaml();
        Map<String, Object> prodYaml = prodYaml();
        List<Discovered> tasks = scanScheduledTasks();

        Set<String> immediate = new TreeSet<>();
        for (Discovered task : tasks) {
            if (sweepMsKeys(task.scheduled()).isEmpty()) {
                continue;
            }
            if (isCron(task.scheduled())) {
                continue;
            }
            if (effectiveInitialDelayMs(task.scheduled(), testYaml, prodYaml) == 0L) {
                immediate.add(task.name());
            }
        }

        assertEquals(new TreeSet<>(IMMEDIATE_FIRST_SWEEP_ALLOWLIST.keySet()), immediate,
                "a *sweep-ms task changed whether it fires at context refresh. Give it an "
                        + "initialDelayString the test profile can raise, or add it to "
                        + "IMMEDIATE_FIRST_SWEEP_ALLOWLIST with a reason. Moving the production "
                        + "first tick is a crash-recovery decision, not a silent default");

        for (Map.Entry<String, String> entry : IMMEDIATE_FIRST_SWEEP_ALLOWLIST.entrySet()) {
            assertFalse(entry.getValue() == null || entry.getValue().isBlank(),
                    entry.getKey() + " is on IMMEDIATE_FIRST_SWEEP_ALLOWLIST without a reason");
        }
    }

    private record Discovered(String name, MergedAnnotation<Scheduled> scheduled) {
    }

    private static Set<String> namesOf(List<Discovered> tasks) {
        Set<String> names = new TreeSet<>();
        for (Discovered task : tasks) {
            names.add(task.name());
        }
        return names;
    }

    private static List<Discovered> scanScheduledTasks() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));

        List<Discovered> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            String className = definition.getBeanClassName();
            if (className == null) {
                continue;
            }
            Class<?> type;
            try {
                type = Class.forName(className, false, ScheduledTaskTestProfileCadenceTest.class.getClassLoader());
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                continue;
            }
            if (!isProductionClass(type)) {
                continue;
            }
            for (Method method : org.springframework.util.ReflectionUtils.getAllDeclaredMethods(type)) {
                MergedAnnotation<Scheduled> scheduled = MergedAnnotations
                        .from(method, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
                        .get(Scheduled.class);
                if (scheduled.isPresent()) {
                    found.add(new Discovered(type.getName() + "#" + method.getName(), scheduled));
                }
            }
        }
        return found;
    }

    private static boolean isProductionClass(Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        return source != null && source.getLocation() != null
                && !source.getLocation().equals(TEST_OUTPUT_ROOT);
    }

    private static Set<String> sweepMsKeys(MergedAnnotation<Scheduled> scheduled) {
        Set<String> keys = new TreeSet<>();
        collectSweepMsKey(keys, scheduled.getString("fixedDelayString"));
        collectSweepMsKey(keys, scheduled.getString("fixedRateString"));
        collectSweepMsKey(keys, scheduled.getString("cron"));
        collectSweepMsKey(keys, scheduled.getString("initialDelayString"));
        return keys;
    }

    private static void collectSweepMsKey(Set<String> keys, String expression) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        Matcher matcher = PLACEHOLDER.matcher(expression.trim());
        if (matcher.matches() && matcher.group(1).endsWith("sweep-ms")) {
            keys.add(matcher.group(1));
        }
    }

    private static boolean isCron(MergedAnnotation<Scheduled> scheduled) {
        String cron = scheduled.getString("cron");
        return cron != null && !cron.isBlank() && !"-".equals(cron);
    }

    private static long effectivePeriodMs(
            MergedAnnotation<Scheduled> scheduled,
            Map<String, Object> testYaml,
            Map<String, Object> prodYaml) {
        String cron = scheduled.getString("cron");
        if (cron != null && !cron.isBlank() && !"-".equals(cron)) {
            return cronPeriodMs(resolveExpression(cron, testYaml, prodYaml));
        }
        String delayString = scheduled.getString("fixedDelayString");
        if (delayString != null && !delayString.isBlank()) {
            return parseMillis("fixedDelayString", resolveExpression(delayString, testYaml, prodYaml));
        }
        long delay = scheduled.getLong("fixedDelay");
        if (delay >= 0) {
            return delay;
        }
        String rateString = scheduled.getString("fixedRateString");
        if (rateString != null && !rateString.isBlank()) {
            return parseMillis("fixedRateString", resolveExpression(rateString, testYaml, prodYaml));
        }
        long rate = scheduled.getLong("fixedRate");
        if (rate >= 0) {
            return rate;
        }
        throw new AssertionError("Scheduled method has neither cron, fixedDelay nor fixedRate");
    }

    private static long effectiveInitialDelayMs(
            MergedAnnotation<Scheduled> scheduled,
            Map<String, Object> testYaml,
            Map<String, Object> prodYaml) {
        String initialDelayString = scheduled.getString("initialDelayString");
        if (initialDelayString != null && !initialDelayString.isBlank()) {
            return parseMillis("initialDelayString", resolveExpression(initialDelayString, testYaml, prodYaml));
        }
        long initialDelay = scheduled.getLong("initialDelay");
        return initialDelay < 0 ? 0L : initialDelay;
    }

    /**
     * Test-profile value when the key is declared there; otherwise the production default the
     * test profile would inherit.
     */
    private static String resolveExpression(
            String expression,
            Map<String, Object> testYaml,
            Map<String, Object> prodYaml) {
        String trimmed = expression.trim();
        Matcher matcher = PLACEHOLDER.matcher(trimmed);
        if (!matcher.matches()) {
            return trimmed;
        }
        String key = matcher.group(1);
        Object testValue = testYaml.get(key);
        if (testValue != null) {
            return testValue.toString().trim();
        }
        Object prodValue = prodYaml.get(key);
        if (prodValue != null) {
            Matcher prodPlaceholder = PLACEHOLDER.matcher(prodValue.toString().trim());
            if (prodPlaceholder.matches() && prodPlaceholder.group(2) != null) {
                return prodPlaceholder.group(2);
            }
            return prodValue.toString().trim();
        }
        String annotationDefault = matcher.group(2);
        assertNotNull(annotationDefault, "cannot resolve " + key + " from test yaml, production yaml or the annotation");
        return annotationDefault;
    }

    private static long cronPeriodMs(String cron) {
        CronExpression parsed = CronExpression.parse(cron);
        // Mid-month so a day-of-month "1" cron still has a next-next fire in the same year.
        ZonedDateTime start = ZonedDateTime.of(2026, 6, 15, 0, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime first = parsed.next(start);
        assertNotNull(first, "cron did not produce a next fire: " + cron);
        ZonedDateTime second = parsed.next(first);
        assertNotNull(second, "cron did not produce a second fire: " + cron);
        return Duration.between(first, second).toMillis();
    }

    private static Long readDeclaredMillis(Map<String, Object> yaml, String key) {
        Object value = yaml.get(key);
        return value == null ? null : parseMillis(key, value.toString());
    }

    private static long parseMillis(String key, String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            throw new AssertionError(key + " must be an integer millisecond value, found: " + text, e);
        }
    }

    private static Map<String, Object> testYaml() {
        Map<String, Object> yaml = ScheduledTaskInventoryTest.optionalYaml("application-test.yml");
        assertNotNull(yaml, "application-test.yml must be on the classpath");
        return yaml;
    }

    private static Map<String, Object> prodYaml() {
        Map<String, Object> yaml = ScheduledTaskInventoryTest.optionalYaml("application.yml");
        assertNotNull(yaml, "application.yml must be on the classpath");
        return yaml;
    }
}
