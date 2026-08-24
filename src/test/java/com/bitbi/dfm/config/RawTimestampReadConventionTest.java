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
     * Relative path → why that file may still name a banned shape.
     *
     * <p>An entry whose file no longer contains one is itself a failure: a stale exemption is how a
     * ban quietly stops being one.</p>
     */
    private static final Map<String, String> ALLOWED = Map.of(
            "src/test/java/com/bitbi/dfm/delta/infrastructure/ChangelogSegmentQueueMarkerClobberTest.java",
            "asStored() models the Hibernate binding's own JVM-zone conversion on purpose, so that "
                    + "a row written through the repository can be compared with the raw column in "
                    + "any zone (#278, part B).");

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
            if (ALLOWED.containsKey(relative)) {
                continue;
            }
            violations.addAll(scan(relative, read(file)));
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
        for (Map.Entry<String, String> entry : ALLOWED.entrySet()) {
            Path file = RunOwnedScratch.projectRoot().resolve(entry.getKey());
            assertThat(Files.exists(file))
                    .withFailMessage("Allowlisted file %s does not exist — drop the entry", entry.getKey())
                    .isTrue();
            assertThat(scan(entry.getKey(), read(file)))
                    .withFailMessage("%s no longer names a banned shape, so its exemption is stale "
                            + "and must be removed: %s", entry.getKey(), entry.getValue())
                    .isNotEmpty();
        }
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
