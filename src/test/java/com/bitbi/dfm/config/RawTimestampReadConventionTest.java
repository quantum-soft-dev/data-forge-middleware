package com.bitbi.dfm.config;

import com.bitbi.dfm.testsupport.RunOwnedScratch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One convention for reading a zone-independent {@code TIMESTAMP} column through raw JDBC, held by
 * the build rather than by review habit (issue #280).
 *
 * <p><strong>What the column holds.</strong> Every {@code TIMESTAMP} column in this schema stores a
 * <em>UTC wall clock</em>. Raw JDBC reads exactly that; the Hibernate path
 * ({@code spring.jpa.properties.hibernate.jdbc.time_zone: UTC}) converts a bound
 * {@code LocalDateTime} from the JVM's zone into UTC on write and back on read, so the two agree on
 * one condition only — the JVM runs in UTC, which the deployed container now declares rather than
 * inherits ({@code ContainerTimeZoneContractTest}).</p>
 *
 * <p><strong>Why these three shapes are banned.</strong> They are the ones that reach the JVM's
 * default zone silently. {@code java.sql.Timestamp} is an instant, so both directions across it
 * carry a zone: {@code Timestamp.valueOf(ldt)} and {@code new Timestamp(millis)} read the JVM zone
 * on the way in, and {@code rs.getTimestamp(col)} on the way out. Read then written back the pair
 * cancels — which is why {@code rs.getTimestamp(col).toLocalDateTime()} looks harmless and mostly
 * is — but it is not the identity: a stored wall clock that falls in the JVM zone's DST gap does
 * not exist locally, {@code Calendar} resolves it forward, and the value comes back shifted by the
 * transition. {@code rs.getObject(col, LocalDateTime.class)} asks pgjdbc for the column's own type
 * and never involves a zone at all.</p>
 *
 * <p>The scan is static rather than a test that reads a row both ways and requires the two to
 * agree: such a test is deterministically red on any JVM outside UTC, which is #279 — the failure
 * #278 had to fix — reintroduced by the guard meant to prevent it. This one gives the same answer
 * in every zone.</p>
 */
@DisplayName("Raw JDBC reads a TIMESTAMP column as the column's own type")
class RawTimestampReadConventionTest {

    /**
     * One earned use of a banned shape: which file, which shape, how many occurrences, and why.
     *
     * @param path       repository-relative path, forward slashes
     * @param shape      the one banned shape this file may name — any other still fails
     * @param occurrences how many times it may name it — a second one is a new decision, not a
     *                   consequence of this exemption
     * @param reason     why the shape is right here, read by whoever finds the failure
     */
    private record Exemption(String path, String shape, int occurrences, String reason) {
    }

    /**
     * The banned shapes this repository has decided are right where they stand.
     *
     * <p>Scoped to a shape and a count rather than to a file, because the reason an exemption
     * carries is about one line: exempting the whole file would let the next
     * {@code rs.getTimestamp} into the one class whose subject is this very conversion, with the
     * guard green. An entry whose file no longer matches it exactly — shape gone, or named more
     * often than it was earned — is itself a failure, since a stale exemption is how a ban quietly
     * stops being one.</p>
     */
    private static final List<Exemption> ALLOWED = List.of(new Exemption(
            "src/test/java/com/bitbi/dfm/delta/infrastructure/ChangelogSegmentQueueMarkerClobberTest.java",
            "Timestamp.valueOf(...)", 1,
            "asStored() models the Hibernate binding's own JVM-zone conversion on purpose, so that "
                    + "a row written through the repository can be compared with the raw column in "
                    + "any zone (#278, part B)."));

    /** The shapes that reach the JVM default zone without saying so. */
    private static final Map<String, Pattern> BANNED = new LinkedHashMap<>(Map.of(
            "rs.getTimestamp(...)", Pattern.compile("\\.\\s*getTimestamp\\s*\\("),
            "Timestamp.valueOf(...)", Pattern.compile("\\bTimestamp\\s*\\.\\s*valueOf\\s*\\("),
            "new Timestamp(...)", Pattern.compile("\\bnew\\s+(java\\s*\\.\\s*sql\\s*\\.\\s*)?Timestamp\\s*\\(")));

    private static final String REMEDY =
            "read the column as its own type — rs.getObject(column, LocalDateTime.class) — and bind "
                    + "a LocalDateTime directly (JDBC 4.2). See #280.";

    /** One occurrence of a banned shape in real code. */
    record Violation(String path, int line, String shape) {
        @Override
        public String toString() {
            return path + ":" + line + " uses " + shape;
        }
    }

    @Test
    @DisplayName("no production or test source reads a timestamp through java.sql.Timestamp")
    void noSourceReachesTheJvmZoneThroughJavaSqlTimestamp() {
        List<Violation> violations = new ArrayList<>();
        for (Path file : javaSources()) {
            String relative = relative(file);
            violations.addAll(unexempted(relative, scan(relative, read(file))));
        }
        assertThat(violations)
                .withFailMessage(() -> "These sources read or build a timestamp through the JVM's "
                        + "default zone:\n  " + String.join("\n  ", violations.stream().map(Object::toString).toList())
                        + "\nTo fix: " + REMEDY)
                .isEmpty();
    }

    @Test
    @DisplayName("the Parquet Export catalog reads produced_at as a LocalDateTime")
    void theCatalogDaoReadsTheColumnAsItsOwnType() {
        Path dao = RunOwnedScratch.projectRoot()
                .resolve("src/main/java/com/bitbi/dfm/plugin/infrastructure/ParquetExportCatalogDao.java");
        String code = AsyncExecutorQualifierTest.stripComments(read(dao));
        long reads = Pattern.compile("getObject\\s*\\(\\s*\"produced_at\"\\s*,\\s*LocalDateTime\\.class\\s*\\)")
                .matcher(code).results().count();
        assertThat(reads)
                .withFailMessage("Each of the three catalog branches (delta, checkpoint, batch) must read "
                        + "produced_at as LocalDateTime; found %d such reads", reads)
                .isEqualTo(3);
    }

    @Test
    @DisplayName("an exemption whose file no longer needs it fails")
    void everyAllowlistEntryIsStillEarned() {
        for (Exemption exemption : ALLOWED) {
            Path file = RunOwnedScratch.projectRoot().resolve(exemption.path());
            assertThat(Files.exists(file))
                    .withFailMessage("Exempted file %s does not exist — drop the entry", exemption.path())
                    .isTrue();
            long found = scan(exemption.path(), read(file)).stream()
                    .filter(v -> v.shape().equals(exemption.shape()))
                    .count();
            assertThat(found)
                    .withFailMessage("%s names %s %d time(s), and %d were earned: %s. Fewer means the "
                                    + "exemption is stale and must be removed; more means a new use "
                                    + "nobody decided on.",
                            exemption.path(), exemption.shape(), found, exemption.occurrences(),
                            exemption.reason())
                    .isEqualTo(exemption.occurrences());
        }
    }

    @Test
    @DisplayName("an exemption covers its own shape and nothing else in the same file")
    void anExemptionDoesNotCoverTheRestOfItsFile() {
        Exemption exemption = ALLOWED.get(0);
        List<Violation> found = List.of(
                new Violation(exemption.path(), 1, exemption.shape()),
                new Violation(exemption.path(), 2, exemption.shape()),
                new Violation(exemption.path(), 3, "rs.getTimestamp(...)"));
        assertThat(unexempted(exemption.path(), found))
                .extracting(Violation::line)
                .containsExactly(2, 3);
    }

    @Test
    @DisplayName("the scan reads code and not prose")
    void theScanIgnoresCommentsAndStringLiterals() {
        assertThat(scan("X.java", """
                class X {
                    // rs.getTimestamp("a")
                    /* Timestamp.valueOf(x) */
                    /** {@code new Timestamp(1)} */
                    String s = "rs.getTimestamp(\\"a\\")";
                    void ok(java.sql.ResultSet rs) throws Exception {
                        rs.getObject("a", java.time.LocalDateTime.class);
                    }
                }
                """)).isEmpty();
    }

    @Test
    @DisplayName("the scan finds each banned shape in real code")
    void theScanFindsEveryBannedShape() {
        assertThat(scan("X.java", """
                class X {
                    void bad(java.sql.ResultSet rs) throws Exception {
                        rs.getTimestamp("a").toLocalDateTime();
                        Timestamp.valueOf(java.time.LocalDateTime.now());
                        Object t = new java.sql.Timestamp(0L);
                    }
                }
                """))
                .extracting(Violation::shape)
                .containsExactlyInAnyOrder("rs.getTimestamp(...)", "Timestamp.valueOf(...)", "new Timestamp(...)");
    }

    /** The violations of {@code path} that no exemption accounts for, earliest first. */
    private static List<Violation> unexempted(String path, List<Violation> found) {
        Map<String, Integer> budget = new LinkedHashMap<>();
        for (Exemption exemption : ALLOWED) {
            if (exemption.path().equals(path)) {
                budget.merge(exemption.shape(), exemption.occurrences(), Integer::sum);
            }
        }
        List<Violation> remaining = new ArrayList<>();
        for (Violation violation : found.stream().sorted(Comparator.comparingInt(Violation::line)).toList()) {
            Integer left = budget.get(violation.shape());
            if (left != null && left > 0) {
                budget.put(violation.shape(), left - 1);
                continue;
            }
            remaining.add(violation);
        }
        return remaining;
    }

    /** Every banned shape in {@code source}, comments and string literals excluded. */
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
            Path dir = RunOwnedScratch.projectRoot().resolve(root);
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot walk " + dir, e);
            }
        }
        assertThat(files).hasSizeGreaterThan(100);
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
