package com.bitbi.dfm.config;

import com.bitbi.dfm.testsupport.RunOwnedScratch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One convention for <em>producing</em> the value that lands in a zone-independent
 * {@code TIMESTAMP} column, held by the build rather than by review habit (issue #282).
 *
 * <p><strong>The convention.</strong> Every such column holds a <em>UTC wall clock</em>
 * ({@code README.md}, "Time zones"), and nothing on the way in converts it any more, so the only
 * correct producer is one that already yields UTC — {@code now(ZoneOffset.UTC)}. The
 * zero-argument form reads the JVM's default zone without saying so and is therefore banned, for
 * {@code LocalDate} as well as {@code LocalDateTime}: the date decides which monthly partition of
 * {@code error_logs} a UTC {@code occurred_at} belongs to.</p>
 *
 * <p><strong>Why the ban is on the producer and not on the conversion.</strong> Until #282 the
 * repository had both producers at once — the zero-argument form in the legacy aggregates and the
 * UTC form in the delta subsystem — and
 * {@code spring.jpa.properties.hibernate.jdbc.time_zone: UTC} made exactly the first of them
 * correct while shifting the second by the JVM's offset. Removing that setting is what makes one
 * producer correct on every path at once (JPA, raw JDBC, the native
 * {@code clock_timestamp() AT TIME ZONE 'UTC'} catalog watermark, and the DTOs that serialize a
 * {@code LocalDateTime} with {@code toInstant(ZoneOffset.UTC)}), so the setting's absence is
 * asserted here beside the ban: with it back, the ban would enforce the wrong producer.</p>
 *
 * <p>The scan is static, and deliberately so. The end-to-end statement — write through JPA, read
 * the same column through raw JDBC, require equality — is
 * {@code TimestampRoundTripIntegrationTest}; it is only meaningful once the conversion is gone,
 * which is why #280 could not have it (with the conversion in place such a test is red on every
 * JVM outside UTC, which is #279 reintroduced by its own guard). This one gives the same answer in
 * every zone.</p>
 */
@DisplayName("One producer for a UTC TIMESTAMP column")
class TimestampProducerConventionTest {

    /**
     * The producers that read the JVM's default zone without saying so.
     *
     * <p>The keys name the shape with an ellipsis rather than spelling the replacement, so that a
     * blanket search-and-replace over {@code src/} cannot rewrite this test's own vocabulary into
     * agreement with the code it is checking.</p>
     */
    private static final Map<String, Pattern> BANNED = new LinkedHashMap<>(Map.of(
            "LocalDateTime.now(<no zone>)", Pattern.compile("\\bLocalDateTime\\s*\\.\\s*now\\s*\\(\\s*\\)"),
            "LocalDate.now(<no zone>)", Pattern.compile("\\bLocalDate\\s*\\.\\s*now\\s*\\(\\s*\\)")));

    /**
     * The conversion whose absence makes the UTC producer the right one, in both spellings a
     * Spring configuration file can carry it: the nested yaml key under {@code hibernate.jdbc},
     * and the flat relaxed form.
     *
     * <p>Applied to {@link #withoutComments(String)} rather than to the raw file, because the
     * setting's absence is worth a comment saying so and a scan that read that comment as the
     * setting would fail on the very file it is protecting.</p>
     */
    private static final Pattern JDBC_TIME_ZONE = Pattern.compile(
            "(?m)^\\s*[\"']?time_zone[\"']?\\s*:|hibernate\\.jdbc\\.time_zone\\s*[:=]");

    private static final String REMEDY =
            "produce the UTC wall clock the column holds — pass ZoneOffset.UTC to now(). "
                    + "See README.md \"Time zones\" and #282.";

    /** One occurrence of a banned producer in real code. */
    record Violation(String path, int line, String shape) {
        @Override
        public String toString() {
            return path + ":" + line + " uses " + shape;
        }
    }

    @Test
    @DisplayName("no production or test source stamps a timestamp from the JVM's default zone")
    void noSourceProducesATimestampInTheJvmZone() {
        List<Violation> violations = new ArrayList<>();
        for (Path file : javaSources()) {
            violations.addAll(scan(relative(file), read(file)));
        }
        assertThat(violations)
                .withFailMessage(() -> "These sources build a date or timestamp from the JVM's "
                        + "default zone:\n  "
                        + String.join("\n  ", violations.stream().map(Object::toString).toList())
                        + "\nTo fix: " + REMEDY)
                .isEmpty();
    }

    @Test
    @DisplayName("no configuration re-introduces the Hibernate timestamp conversion")
    void noConfigurationBindsTimestampsThroughAConfiguredZone() {
        List<String> offenders = new ArrayList<>();
        for (Path file : configurationFiles()) {
            Matcher matcher = JDBC_TIME_ZONE.matcher(withoutComments(read(file)));
            while (matcher.find()) {
                offenders.add(relative(file));
            }
        }
        assertThat(offenders)
                .withFailMessage(() -> "hibernate.jdbc.time_zone is set in " + offenders + ". It makes "
                        + "Hibernate read a bound LocalDateTime as wall clock in the JVM's zone and store "
                        + "a different instant, so the column stops agreeing with every raw-JDBC and "
                        + "native-SQL path — and it makes the producer this test bans the correct one "
                        + "again. Remove it, or change the convention deliberately (#282).")
                .isEmpty();
    }

    @Test
    @DisplayName("the scan reads code and not prose")
    void theScanIgnoresCommentsAndStringLiterals() {
        assertThat(scan("X.java", """
                class X {
                    // LocalDateTime.now()
                    /* LocalDate.now() */
                    /** {@code LocalDateTime.now()} */
                    String s = "LocalDateTime.now()";
                    void ok() {
                        java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
                        java.time.LocalDate.now(java.time.ZoneOffset.UTC);
                    }
                }
                """)).isEmpty();
    }

    @Test
    @DisplayName("the scan finds each banned producer in real code, whitespace and all")
    void theScanFindsEveryBannedProducer() {
        String bad = "class X {\n"
                + "    void bad() {\n"
                + "        LocalDateTime.now();\n"
                + "        LocalDate . now (  ) ;\n"
                + "    }\n"
                + "}\n";
        assertThat(scan("X.java", bad))
                .extracting(Violation::shape)
                .containsExactlyInAnyOrder("LocalDateTime.now(<no zone>)", "LocalDate.now(<no zone>)");
    }

    @Test
    @DisplayName("the configuration scan finds the setting in a real yaml shape")
    void theConfigurationScanFindsTheSetting() {
        assertThat(finds("""
                spring:
                  jpa:
                    properties:
                      hibernate:
                        jdbc:
                          time_zone: UTC
                """)).isTrue();
        assertThat(finds("spring.jpa.properties.hibernate.jdbc.time_zone=UTC")).isTrue();
        assertThat(finds("""
                spring:
                  jackson:
                    time-zone: UTC
                """))
                .withFailMessage("time-zone (Jackson's serialization zone) is a different setting and "
                        + "must not be caught by this scan")
                .isFalse();
    }

    @Test
    @DisplayName("a comment saying the setting is absent is not read as the setting")
    void theConfigurationScanReadsSettingsAndNotComments() {
        assertThat(finds("""
                spring:
                  jpa:
                    properties:
                      hibernate:
                        # No hibernate.jdbc.time_zone here, deliberately (#282).
                        format_sql: false
                """))
                .withFailMessage("The comment recording why the setting is absent must not itself "
                        + "count as the setting — that would fail the file it documents")
                .isFalse();
        assertThat(finds("""
                spring:
                  jpa:
                    properties:
                      hibernate:
                        jdbc:
                          time_zone: UTC   # restored by accident
                """))
                .withFailMessage("A real setting with a trailing comment is still the setting")
                .isTrue();
    }

    private static boolean finds(String configuration) {
        return JDBC_TIME_ZONE.matcher(withoutComments(configuration)).find();
    }

    /**
     * {@code configuration} with whole-line {@code #} comments blanked, line count preserved.
     * Inline comments are left alone: a trailing {@code #} cannot turn a real setting into prose.
     */
    private static String withoutComments(String configuration) {
        StringBuilder kept = new StringBuilder();
        for (String line : configuration.split("\n", -1)) {
            kept.append(line.stripLeading().startsWith("#") ? "" : line).append('\n');
        }
        return kept.toString();
    }

    /** Every banned producer in {@code source}, comments and string literals excluded. */
    static List<Violation> scan(String path, String source) {
        AsyncExecutorQualifierTest.Stripped stripped = AsyncExecutorQualifierTest.strip(source);
        String code = stripped.code();
        List<Violation> found = new ArrayList<>();
        for (Map.Entry<String, Pattern> banned : BANNED.entrySet()) {
            Matcher matcher = banned.getValue().matcher(code);
            while (matcher.find()) {
                if (stripped.insideLiteral()[matcher.start()]) {
                    continue;
                }
                found.add(new Violation(path, lineOf(code, matcher.start()), banned.getKey()));
            }
        }
        return found;
    }

    private static int lineOf(String code, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static List<Path> javaSources() {
        List<Path> files = new ArrayList<>();
        for (String root : List.of("src/main/java", "src/test/java")) {
            files.addAll(walk(RunOwnedScratch.projectRoot().resolve(root), ".java"));
        }
        assertThat(files).hasSizeGreaterThan(100);
        return files;
    }

    private static List<Path> configurationFiles() {
        List<Path> files = new ArrayList<>();
        for (String root : List.of("src/main/resources", "src/test/resources")) {
            for (String suffix : List.of(".yml", ".yaml", ".properties")) {
                files.addAll(walk(RunOwnedScratch.projectRoot().resolve(root), suffix));
            }
        }
        assertThat(files)
                .withFailMessage("No configuration files found — the scan has gone blind")
                .isNotEmpty();
        return files;
    }

    private static List<Path> walk(Path dir, String suffix) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(p -> p.toString().endsWith(suffix)).forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot walk " + dir, e);
        }
        return files;
    }

    private static String relative(Path file) {
        return RunOwnedScratch.projectRoot().relativize(file).toString().replace('\\', '/');
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }
}
