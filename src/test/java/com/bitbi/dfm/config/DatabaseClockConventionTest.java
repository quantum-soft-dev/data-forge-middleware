package com.bitbi.dfm.config;

import com.bitbi.dfm.testsupport.RunOwnedScratch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The database clock is the third producer of a zone-independent {@code TIMESTAMP} column, and it
 * says UTC out loud rather than inheriting it from the session (issue #286).
 *
 * <p><strong>The convention.</strong> Every such column holds a <em>UTC wall clock</em>
 * ({@code README.md}, "Time zones"). #282 brought every Java producer to it and removed the
 * conversion that used to hide the disagreement. What it deliberately left alone is the producer
 * that is not Java at all: SQL evaluated by PostgreSQL. {@code CURRENT_TIMESTAMP} and its
 * relatives are resolved in the <em>session's</em> zone, and pgjdbc takes that zone from the JVM's
 * default at connect time — so off UTC those statements write local wall clock into a column
 * everything else fills with UTC. The remedy is one expression, in this repository since the
 * Parquet Export catalog watermark: {@code CAST(current_timestamp AT TIME ZONE 'UTC' AS
 * timestamp)}.</p>
 *
 * <p><strong>Why this is a guard and not only a fix.</strong> The six statements #286 names were
 * added one at a time, by six different pieces of work, each correct-looking on a UTC machine.
 * Nothing said no, which is the mechanism #205/#278/#280/#282 each ran into. This scan says no, in
 * every environment, without a database.</p>
 *
 * <p><strong>What it does not cover, stated rather than implied.</strong> Two populations are out
 * of scope on purpose. Test fixtures seed rows with bare {@code CURRENT_TIMESTAMP} in some forty
 * places; they are wrong in the same way and are a sweep of their own rather than a consequence of
 * this one. And eleven <em>applied</em> migrations declare columns {@code DEFAULT
 * CURRENT_TIMESTAMP}: an applied migration is never edited (forward-only), and those defaults
 * cannot fire in production anyway, since every INSERT on those tables goes through JPA with the
 * column mapped and the one native INSERT ({@code insertPendingIfAbsent}) binds its own
 * {@code :now}. New migrations are held to the convention through
 * {@link #GRANDFATHERED_THROUGH_VERSION}, a constant set once — migrations are forward-only and
 * numbered, so a file can only ever appear above it, the shape
 * {@code MigrationDocumentationConsistencyTest} already has over these files.</p>
 *
 * <p>The wired half — that the statements really do write UTC whatever the session zone is — is
 * {@code DatabaseClockUtcIntegrationTest}. Only a database can show that; only this scan gives the
 * same answer without one.</p>
 */
@DisplayName("The database clock produces UTC")
class DatabaseClockConventionTest {

    /**
     * The highest migration version that predates this convention.
     *
     * <p>V57 was the last applied migration when #286 landed.</p>
     */
    private static final int GRANDFATHERED_THROUGH_VERSION = 57;

    /**
     * Clock functions that return a {@code timestamptz} and are therefore correct once they are
     * read in UTC. Each is a violation unless {@code AT TIME ZONE 'UTC'} follows it.
     */
    private static final Map<String, Pattern> WRAPPABLE = new LinkedHashMap<>(Map.of(
            "CURRENT_TIMESTAMP", Pattern.compile("(?i)(?<![.\\w])current_timestamp\\b"),
            "now()", Pattern.compile("(?i)(?<![.\\w])now\\s*\\(\\s*\\)"),
            "clock_timestamp()", Pattern.compile("(?i)(?<![.\\w])clock_timestamp\\s*\\(\\s*\\)"),
            "statement_timestamp()", Pattern.compile("(?i)(?<![.\\w])statement_timestamp\\s*\\(\\s*\\)"),
            "transaction_timestamp()", Pattern.compile("(?i)(?<![.\\w])transaction_timestamp\\s*\\(\\s*\\)")));

    /**
     * Clock functions no wrapping can repair: they have already resolved the session's zone and
     * returned a value with no zone left in it, so {@code AT TIME ZONE 'UTC'} would reinterpret
     * rather than convert. The remedy is to derive them from the wrapped expression instead.
     */
    private static final Map<String, Pattern> UNWRAPPABLE = new LinkedHashMap<>(Map.of(
            "LOCALTIMESTAMP", Pattern.compile("(?i)(?<![.\\w])localtimestamp\\b"),
            "CURRENT_DATE", Pattern.compile("(?i)(?<![.\\w])current_date\\b")));

    /** What may legally follow a wrappable function. */
    private static final Pattern AT_UTC = Pattern.compile("(?is)\\A\\s*at\\s+time\\s+zone\\s+'utc'");

    private static final String REMEDY =
            "let the database say UTC rather than inherit it from the session zone: "
                    + "CAST(current_timestamp AT TIME ZONE 'UTC' AS timestamp), as "
                    + "JpaBatchParquetArtifactRepository.nextCatalogWatermark already does. "
                    + "See README.md \"Time zones\" and #286.";

    /** One occurrence of a session-zone clock in real SQL. */
    record Violation(String path, int line, String shape) {
        @Override
        public String toString() {
            return path + ":" + line + " uses " + shape;
        }
    }

    @Test
    @DisplayName("no production SQL reads the clock in the database session's zone")
    void noProductionSqlStampsATimestampInTheSessionZone() {
        List<Violation> violations = new ArrayList<>();
        for (Path file : productionJavaSources()) {
            violations.addAll(scanJava(relative(file), read(file)));
        }
        assertThat(violations)
                .withFailMessage(() -> "This SQL stamps a timestamp from the database session's "
                        + "zone, which pgjdbc takes from the JVM:\n  "
                        + String.join("\n  ", violations.stream().map(Object::toString).toList())
                        + "\nTo fix: " + REMEDY)
                .isEmpty();
    }

    @Test
    @DisplayName("no migration added since the convention reads the clock in the session's zone")
    void noNewMigrationStampsATimestampInTheSessionZone() {
        List<Violation> violations = new ArrayList<>();
        for (Path file : migrations()) {
            if (versionOf(file) <= GRANDFATHERED_THROUGH_VERSION) {
                continue;
            }
            violations.addAll(scanSql(relative(file), read(file)));
        }
        assertThat(violations)
                .withFailMessage(() -> "These migrations resolve the clock in the database session's "
                        + "zone:\n  "
                        + String.join("\n  ", violations.stream().map(Object::toString).toList())
                        + "\nTo fix: " + REMEDY)
                .isEmpty();
    }

    @Test
    @DisplayName("the grandfather anchor still names a migration that exists")
    void theAnchorNamesARealMigration() {
        assertThat(migrations().stream()
                .filter(DatabaseClockConventionTest::isForwardMigration)
                .map(DatabaseClockConventionTest::versionOf))
                .withFailMessage("V%d is the version the convention starts above and must exist; an "
                                + "anchor pointing at nothing means the migration scan is silently "
                                + "covering files nobody meant to grandfather",
                        GRANDFATHERED_THROUGH_VERSION)
                .contains(GRANDFATHERED_THROUGH_VERSION);
    }

    @Test
    @DisplayName("the catalog watermark is the shape the remedy names")
    void theWatermarkStillCarriesTheWrappedExpression() {
        Path repository = RunOwnedScratch.projectRoot().resolve(
                "src/main/java/com/bitbi/dfm/delta/infrastructure/JpaBatchParquetArtifactRepository.java");
        assertThat(read(repository))
                .as("the remedy points at this statement as the worked example; if it moves or is "
                        + "rewritten, the message sends the next reader somewhere it is not")
                .contains("clock_timestamp() AT TIME ZONE 'UTC'");
    }

    @Test
    @DisplayName("the scan reads SQL and not Java or prose")
    void theScanIgnoresJavaCallsAndComments() {
        assertThat(scanJava("X.java", """
                class X {
                    // UPDATE t SET a = CURRENT_TIMESTAMP
                    /** {@code SET a = now()} */
                    void ok() {
                        java.time.Instant.now();
                        java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
                    }
                    String wrapped = "UPDATE t SET a = CAST(current_timestamp AT TIME ZONE 'UTC' AS timestamp)";
                }
                """))
                .as("a Java now() is not the database's clock, a comment is not code, and a wrapped "
                        + "expression is the convention itself")
                .isEmpty();
    }

    @Test
    @DisplayName("the scan finds each session-zone clock in real SQL")
    void theScanFindsEverySessionZoneClock() {
        assertThat(scanJava("X.java", """
                class X {
                    String a = "UPDATE t SET x = CURRENT_TIMESTAMP WHERE id = :id";
                    String b = "UPDATE t SET x = now()";
                    String c = "UPDATE t SET x = clock_timestamp()";
                    String d = "UPDATE t SET x = statement_timestamp()";
                    String e = "UPDATE t SET x = transaction_timestamp()";
                    String f = "UPDATE t SET x = LOCALTIMESTAMP";
                    String g = "UPDATE t SET x = CURRENT_DATE";
                }
                """))
                .extracting(Violation::shape)
                .containsExactlyInAnyOrder("CURRENT_TIMESTAMP", "now()", "clock_timestamp()",
                        "statement_timestamp()", "transaction_timestamp()", "LOCALTIMESTAMP",
                        "CURRENT_DATE");
    }

    @Test
    @DisplayName("a wrapping that is not UTC does not satisfy the convention")
    void onlyUtcCounts() {
        assertThat(scanJava("X.java", """
                class X {
                    String a = "SET x = current_timestamp AT TIME ZONE 'Europe/Berlin'";
                    String b = "SET x = current_timestamp "
                             + "AT TIME ZONE 'UTC'";
                    String c = "SET x = localtimestamp AT TIME ZONE 'UTC'";
                }
                """))
                .as("another zone is not UTC; a statement split across concatenated literals is "
                        + "still one expression; and a value that has already lost its zone cannot "
                        + "be repaired by reinterpreting it")
                .extracting(Violation::shape)
                .containsExactlyInAnyOrder("CURRENT_TIMESTAMP", "LOCALTIMESTAMP");
    }

    @Test
    @DisplayName("the migration scan reads statements and not comments")
    void theMigrationScanReadsStatementsAndNotComments() {
        assertThat(scanSql("V99__x.sql", """
                -- created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP was the old shape
                /* now() too */
                ALTER TABLE t ADD COLUMN a TIMESTAMP NOT NULL
                    DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC');
                """))
                .as("a comment recording the old shape must not fail the file that replaces it")
                .isEmpty();
        assertThat(scanSql("V99__x.sql", """
                ALTER TABLE t ADD COLUMN a TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
                """))
                .extracting(Violation::shape)
                .containsExactly("CURRENT_TIMESTAMP");
    }

    /**
     * Every session-zone clock inside a string literal of {@code source}.
     *
     * <p>Inside a literal, and only there: SQL reaches PostgreSQL from this repository as a string
     * — a JPQL or native {@code @Query}, or a {@code JdbcTemplate} statement — while {@code now()}
     * written as Java is a different function entirely. That is the opposite of what
     * {@code TimestampProducerConventionTest} and {@code RawTimestampReadConventionTest} want from
     * the same mask, which is why all three share one stripper rather than each guessing.</p>
     */
    static List<Violation> scanJava(String path, String source) {
        AsyncExecutorQualifierTest.Stripped stripped = AsyncExecutorQualifierTest.strip(source);
        return findAll(path, stripped.code(), stripped.insideLiteral());
    }

    /** Every session-zone clock in a {@code .sql} file, its comments removed. */
    static List<Violation> scanSql(String path, String source) {
        String code = withoutSqlComments(source);
        boolean[] everywhere = new boolean[code.length()];
        Arrays.fill(everywhere, true);
        return findAll(path, code, everywhere);
    }

    private static List<Violation> findAll(String path, String code, boolean[] eligible) {
        List<Violation> found = new ArrayList<>();
        for (Map.Entry<String, Pattern> banned : WRAPPABLE.entrySet()) {
            Matcher matcher = banned.getValue().matcher(code);
            while (matcher.find()) {
                if (!eligible[matcher.start()] || wrappedInUtc(code, matcher.end())) {
                    continue;
                }
                found.add(new Violation(path, lineOf(code, matcher.start()), banned.getKey()));
            }
        }
        for (Map.Entry<String, Pattern> banned : UNWRAPPABLE.entrySet()) {
            Matcher matcher = banned.getValue().matcher(code);
            while (matcher.find()) {
                if (!eligible[matcher.start()]) {
                    continue;
                }
                found.add(new Violation(path, lineOf(code, matcher.start()), banned.getKey()));
            }
        }
        return found;
    }

    /**
     * Whether {@code AT TIME ZONE 'UTC'} follows the function that ended at {@code from}.
     *
     * <p>The quotes and {@code +} of a concatenated literal are blanked first, so a statement split
     * across Java source lines reads as one expression — which is how most multi-line
     * {@code @Query} values in this repository are written.</p>
     */
    private static boolean wrappedInUtc(String code, int from) {
        String tail = code.substring(from, Math.min(code.length(), from + 80))
                .replace("\"", " ")
                .replace("+", " ");
        return AT_UTC.matcher(tail).find();
    }

    /** {@code sql} with {@code --} and block comments removed, line count preserved. */
    private static String withoutSqlComments(String sql) {
        StringBuilder kept = new StringBuilder(sql.length());
        boolean inBlock = false;
        for (String line : sql.split("\n", -1)) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < line.length(); i++) {
                if (inBlock) {
                    if (line.startsWith("*/", i)) {
                        inBlock = false;
                        i++;
                    }
                    continue;
                }
                if (line.startsWith("/*", i)) {
                    inBlock = true;
                    i++;
                    continue;
                }
                if (line.startsWith("--", i)) {
                    break;
                }
                out.append(line.charAt(i));
            }
            kept.append(out).append('\n');
        }
        return kept.toString();
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

    private static List<Path> productionJavaSources() {
        List<Path> files = walk(RunOwnedScratch.projectRoot().resolve("src/main/java"), ".java");
        assertThat(files)
                .withFailMessage("No production sources found — the scan has gone blind")
                .hasSizeGreaterThan(100);
        return files;
    }

    private static List<Path> migrations() {
        List<Path> files = walk(
                RunOwnedScratch.projectRoot().resolve("src/main/resources/db/migration"), ".sql");
        assertThat(files)
                .withFailMessage("No migrations found — the scan has gone blind")
                .hasSizeGreaterThan(50);
        return files;
    }

    /** {@code V{N}__} for a migration, {@code U{N}__} for the undo script of one. */
    private static final Pattern VERSION = Pattern.compile("^[VU](\\d+)__");

    private static int versionOf(Path migration) {
        Matcher matcher = VERSION.matcher(migration.getFileName().toString());
        assertThat(matcher.find())
                .withFailMessage("Migration %s does not follow V{N}__description.sql", migration)
                .isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private static boolean isForwardMigration(Path file) {
        return file.getFileName().toString().startsWith("V");
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
