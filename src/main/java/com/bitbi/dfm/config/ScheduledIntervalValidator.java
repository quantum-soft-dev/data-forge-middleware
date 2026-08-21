package com.bitbi.dfm.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.env.Environment;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.security.CodeSource;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fails startup when a {@code @Scheduled} interval placeholder resolves to {@code 0} or a
 * negative value, naming the configuration key and the offending value (issue #251).
 *
 * <p>{@code @Scheduled(fixedDelayString)} accepts {@code 0} and runs the tick back-to-back
 * for ever — a busy-loop on a green rollout. A negative value fails inside Spring's schedule
 * parsing without naming the key. #185 closed that class for
 * {@code plugin.sql-generation.delta-sweep-ms} with a constructor {@code @Value} copy; every
 * other interval key was bound only at the annotation and had no such check. This validator
 * walks the same {@code @Scheduled} sites {@code ScheduledTaskInventoryTest} already inventories,
 * so a newly added interval key is validated without a per-bean copy.</p>
 *
 * <p>Only {@code fixedDelayString} and {@code fixedRateString} are intervals.
 * {@code initialDelayString} of {@code 0} means fire immediately, which several ticks use
 * on purpose (the crash-recovery pass, the scratch sweep). Cron expressions are not
 * intervals.</p>
 *
 * <p>The bean is constructed during singleton instantiation, before
 * {@code ScheduledAnnotationBeanPostProcessor} starts the tasks, so a {@code 0} never
 * reaches the scheduler.</p>
 *
 * @author Data Forge Team
 */
@Component
public class ScheduledIntervalValidator {

    private static final Logger log = LoggerFactory.getLogger(ScheduledIntervalValidator.class);

    private static final String BASE_PACKAGE = "com.bitbi.dfm";

    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([^:}]+)(?::(.*))?}$");

    private static final String CONSEQUENCE = "a non-positive interval busy-loops the scheduled tick";

    /**
     * @param environment the environment the {@code @Scheduled} placeholders will resolve against
     */
    public ScheduledIntervalValidator(Environment environment) {
        NavigableMap<String, String> placeholders = discoverIntervalPlaceholders();
        validate(environment, placeholders);
        log.info("Validated {} @Scheduled interval key(s) (issue #251)", placeholders.size());
    }

    /**
     * Placeholder keys on {@code fixedDelayString} / {@code fixedRateString} of production
     * {@code @Scheduled} methods, mapped to the annotation expression so a missing key still
     * resolves to the annotation default.
     *
     * <p>Package-private so {@code ScheduledTaskInventoryTest} can assert this walk sees the
     * same keys as its independent inventory scan — a scan gone blind is otherwise
     * indistinguishable from a clean application.</p>
     */
    static NavigableMap<String, String> discoverIntervalPlaceholders() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));

        NavigableMap<String, String> keys = new TreeMap<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            String className = definition.getBeanClassName();
            if (className == null) {
                continue;
            }
            Class<?> type;
            try {
                type = Class.forName(className, false, ScheduledIntervalValidator.class.getClassLoader());
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                continue;
            }
            if (!isOwnCodeSource(type)) {
                continue;
            }
            for (Method method : org.springframework.util.ReflectionUtils.getAllDeclaredMethods(type)) {
                if (method.isSynthetic() || method.isBridge()) {
                    continue;
                }
                MergedAnnotations.from(method, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
                        .stream(Scheduled.class)
                        .forEach(scheduled -> {
                            collect(keys, scheduled.getString("fixedDelayString"));
                            collect(keys, scheduled.getString("fixedRateString"));
                        });
            }
        }
        return keys;
    }

    static void validate(Environment environment, Map<String, String> placeholders) {
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String key = entry.getKey();
            String resolved = environment.resolvePlaceholders(entry.getValue());
            long millis = parseIntervalMillis(key, resolved);
            if (millis < 1L) {
                throw new IllegalArgumentException(key + " must be at least 1, but was " + millis
                        + ". Refusing to start: " + CONSEQUENCE + " (issue #251).");
            }
        }
    }

    /**
     * Matches {@code @Scheduled(fixedDelayString)}: a decimal millisecond count, or an ISO-8601
     * duration ({@code PT5M}). A duration-string of {@code PT0S} is the same busy-loop as
     * {@code 0} and is refused as {@code 0}.
     */
    static long parseIntervalMillis(String key, String raw) {
        if (raw == null || raw.isBlank()) {
            throw unparseable(key, raw);
        }
        String trimmed = raw.trim();
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ignored) {
            // The other form ScheduledAnnotationBeanPostProcessor accepts.
        }
        try {
            return Duration.parse(trimmed).toMillis();
        } catch (DateTimeParseException e) {
            throw unparseable(key, trimmed);
        }
    }

    private static IllegalArgumentException unparseable(String key, String raw) {
        return new IllegalArgumentException(key
                + " must be a positive interval in milliseconds or ISO-8601, but was '"
                + raw + "'. Refusing to start: a value Spring cannot parse as an interval "
                + "leaves the tick unconfigured (issue #251).");
    }

    private static void collect(NavigableMap<String, String> keys, String expression) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        String trimmed = expression.trim();
        Matcher matcher = PLACEHOLDER.matcher(trimmed);
        if (!matcher.matches()) {
            return;
        }
        String key = matcher.group(1);
        String previous = keys.put(key, trimmed);
        if (previous != null && !previous.equals(trimmed)) {
            throw new IllegalStateException("interval key " + key
                    + " is bound with two different @Scheduled expressions: "
                    + previous + " and " + trimmed);
        }
    }

    /**
     * Restricts the walk to classes this validator was compiled with, so a test-classpath
     * {@code @Scheduled} fixture cannot fail startup of an integration context.
     */
    private static boolean isOwnCodeSource(Class<?> type) {
        CodeSource mine = ScheduledIntervalValidator.class.getProtectionDomain().getCodeSource();
        CodeSource theirs = type.getProtectionDomain().getCodeSource();
        return mine != null && theirs != null
                && mine.getLocation() != null && theirs.getLocation() != null
                && mine.getLocation().equals(theirs.getLocation());
    }
}
