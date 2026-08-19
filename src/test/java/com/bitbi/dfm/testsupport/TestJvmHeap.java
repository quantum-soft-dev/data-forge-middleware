package com.bitbi.dfm.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One definition of "what the test JVM is allowed to do with heap" (issue #207).
 *
 * <p>Gradle's default test-JVM heap is 512 MB and {@code build.gradle.kts} used to declare nothing,
 * so {@code ./gradlew test} in CI ran 2400 tests and every cached Spring context in that one
 * default JVM. When it ran out, the {@code OutOfMemoryError} surfaced wherever the allocation
 * happened to be — inside Spring's {@code ConstructorResolver}, reported as a
 * {@code BeanCreationException} of whichever test was unlucky. A red build that names an innocent
 * test costs a full investigation every time it fires.</p>
 *
 * <p>Two guards share this class rather than each carrying its own idea of the agreed bounds:
 * {@code TestJvmHeapCeilingTest} reads what the build declares and what this JVM was given, and
 * {@code TestJvmOutOfMemoryExitTest} drives a child JVM to a real allocation failure and requires
 * it to die saying so. The {@code RunOwnedScratch} / {@code LockWaitBound} precedent.</p>
 */
public final class TestJvmHeap {

    /**
     * Floor of the agreed range for {@code maxHeapSize}.
     *
     * <p>Measured rather than guessed, as the ticket asks. A {@code -Xlog:gc} pass over the whole
     * {@code ./gradlew test} suite ({@code ./gradlew test -PtestHeapLog}) at a deliberately
     * generous 3 GiB — 2470 tests, 444 classes, 24 cached Spring contexts, all green — never let
     * G1 expand past 1014 MB and put the highest occupancy <em>after</em> a collection at
     * {@value #MEASURED_PEAK_LIVE_SET_MB} MB, with 965 MB the highest reached before one. So a
     * 1 GiB heap sits exactly on the cliff and Gradle's 512 MB default was under it, which is why
     * CI failed intermittently rather than always. The floor is that measurement with the
     * allocation headroom G1 needs on top of it.</p>
     */
    public static final long MIN_HEAP_BYTES = 1536L * 1024 * 1024;

    /**
     * Ceiling of the agreed range.
     *
     * <p>The bound that matters is the CI runner: {@code ubuntu-latest} has 16 GB shared with a
     * PostgreSQL, a Redis and a LocalStack service container, the Gradle build JVM, and the
     * Testcontainers images the suite starts on top of those. A test-JVM ceiling above this stops
     * being a ceiling — the kernel's OOM killer answers first, and that failure names nothing at
     * all.</p>
     */
    public static final long MAX_HEAP_BYTES = 4096L * 1024 * 1024;

    /** Peak live set observed over a full {@code ./gradlew test} run; see {@link #MIN_HEAP_BYTES}. */
    public static final int MEASURED_PEAK_LIVE_SET_MB = 800;

    /**
     * Terminates the JVM on the first allocation failure instead of letting the
     * {@code OutOfMemoryError} unwind into whatever caller happened to be on the stack.
     */
    public static final String EXIT_ON_OOM_FLAG = "-XX:+ExitOnOutOfMemoryError";

    /** Writes the evidence needed to re-size the ceiling, since the JVM is about to disappear. */
    public static final String HEAP_DUMP_ON_OOM_FLAG = "-XX:+HeapDumpOnOutOfMemoryError";

    /** {@code maxHeapSize = "..."} anywhere in the build script. */
    private static final Pattern MAX_HEAP_SIZE =
            Pattern.compile("maxHeapSize\\s*=\\s*\"([^\"]*)\"");

    /**
     * A JVM size literal: digits with an optional {@code k}/{@code m}/{@code g} suffix, and
     * nothing else — exactly what {@code -Xmx} takes. Deliberately not tolerant of a trailing
     * {@code b}: {@code "2gb"} is a value the JVM refuses to start on, and a guard that reads it as
     * 2 GiB would report a ceiling for a build that cannot run.
     */
    private static final Pattern SIZE = Pattern.compile("(\\d+)\\s*([kmg]?)", Pattern.CASE_INSENSITIVE);

    /** The block every {@code Test} task is configured by, the only place these settings bind all of them. */
    public static final String ALL_TEST_TASKS = "tasks.withType<Test>";

    private TestJvmHeap() {
    }

    /**
     * The build script, read from the checkout rather than from the classpath — it is not a
     * resource, and the working directory is the one thing a run configuration is free to move.
     *
     * @return contents of {@code build.gradle.kts} with comments and string literals intact
     */
    public static String buildScript() {
        Path script = RunOwnedScratch.projectRoot().resolve("build.gradle.kts");
        try {
            return Files.readString(script);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + script, e);
        }
    }

    /**
     * The body of the block that configures every {@code Test} task, with comments removed and
     * string literals intact.
     *
     * <p>Both halves matter. Comments have to go, because this build file documents these settings
     * at length right beside them, and a text search over the raw source would read a sentence
     * about a flag as a declaration of it — the mistake {@code AsyncExecutorQualifierTest} had to
     * correct. Literals have to stay, because the flags being looked for <em>are</em> string
     * literals; only the brace matching that finds the block runs over a copy with the literals
     * masked, so a brace inside a path or a Kotlin template cannot unbalance it.</p>
     *
     * @param script build script source
     * @return block body, or {@code null} when the block is not there at all
     */
    public static String allTestTasksBlock(String script) {
        String stripped = stripComments(script);
        String masked = maskLiterals(stripped);
        int header = masked.indexOf(ALL_TEST_TASKS);
        if (header < 0) {
            return null;
        }
        int open = masked.indexOf('{', header);
        if (open < 0) {
            return null;
        }
        int depth = 0;
        for (int i = open; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return stripped.substring(open + 1, i);
                }
            }
        }
        return null;
    }

    /**
     * Every {@code maxHeapSize} assignment in the build script, in source order.
     *
     * <p>Read as a list rather than as a first match on purpose: a second assignment on a narrower
     * task ({@code tasks.named<Test>("test")}, {@code integrationTest}) wins for that task and
     * would leave the other one running on Gradle's 512 MB default while the guard reported a
     * ceiling. Callers require exactly one.</p>
     *
     * @param script build script source
     * @return declared values, e.g. {@code ["2g"]}
     */
    public static List<String> declaredHeapSizes(String script) {
        List<String> declared = new ArrayList<>();
        Matcher matcher = MAX_HEAP_SIZE.matcher(stripComments(script));
        while (matcher.find()) {
            declared.add(matcher.group(1));
        }
        return declared;
    }

    /**
     * Parses a JVM size literal the way {@code -Xmx} does.
     *
     * @param literal e.g. {@code 2g}, {@code 2048m}, {@code 2147483648}
     * @return size in bytes
     * @throws IllegalArgumentException when the literal is not a size, rather than silently
     *                                  reading an unrecognised suffix as bytes
     */
    public static long parseSize(String literal) {
        Matcher matcher = SIZE.matcher(literal.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "'" + literal + "' is not a JVM size literal (digits with an optional k/m/g suffix)");
        }
        long value;
        try {
            value = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + literal + "' is larger than any heap the JVM can take", e);
        }
        long multiplier = switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "k" -> 1024L;
            case "m" -> 1024L * 1024;
            case "g" -> 1024L * 1024 * 1024;
            default -> 1L;
        };
        return value * multiplier;
    }

    /** Human-readable MiB, for failure messages. */
    public static String describe(long bytes) {
        return (bytes / (1024 * 1024)) + " MiB";
    }

    /**
     * Blanks {@code //} and block comments, keeping every other character at its own offset so the
     * result can be indexed against {@link #maskLiterals(String)} of itself.
     */
    static String stripComments(String source) {
        char[] out = source.toCharArray();
        int i = 0;
        while (i < out.length) {
            if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = blank(out, i, end < 0 ? out.length : end);
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = blank(out, i, end < 0 ? out.length : end + 2);
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                i = end < 0 ? out.length : end + 3;
            } else if (out[i] == '"') {
                i = endOfStringLiteral(source, i);
            } else if (out[i] == '\'') {
                i = endOfCharLiteral(source, i);
            } else {
                i++;
            }
        }
        return new String(out);
    }

    /**
     * Blanks the contents of every string literal, keeping the length, so brace matching cannot be
     * thrown by a brace inside a path, a regular expression or a Kotlin template.
     */
    static String maskLiterals(String source) {
        char[] out = source.toCharArray();
        int i = 0;
        while (i < out.length) {
            if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                int stop = end < 0 ? out.length : end + 3;
                blank(out, i + 3, Math.max(i + 3, stop - 3));
                i = stop;
            } else if (out[i] == '"') {
                int stop = endOfStringLiteral(source, i);
                blank(out, i + 1, Math.max(i + 1, stop - 1));
                i = stop;
            } else if (out[i] == '\'') {
                int stop = endOfCharLiteral(source, i);
                blank(out, i + 1, Math.max(i + 1, stop - 1));
                i = stop;
            } else {
                i++;
            }
        }
        return new String(out);
    }

    /** Offset just past the closing quote of the literal starting at {@code start}. */
    private static int endOfStringLiteral(String source, int start) {
        int i = start + 1;
        while (i < source.length() && source.charAt(i) != '"') {
            i += source.charAt(i) == '\\' ? 2 : 1;
        }
        return Math.min(i + 1, source.length());
    }

    /**
     * Offset just past the closing quote of the Kotlin character literal starting at
     * {@code start}. Read at all because {@code '"'} would otherwise open a string literal that
     * swallows the code up to the next quote — the shape that cost
     * {@code AsyncExecutorQualifierTest} forty lines of a file it claimed to scan in full.
     */
    private static int endOfCharLiteral(String source, int start) {
        int i = start + 1;
        while (i < source.length() && source.charAt(i) != '\'') {
            i += source.charAt(i) == '\\' ? 2 : 1;
        }
        return Math.min(i + 1, source.length());
    }

    /** Replaces {@code [from, to)} with spaces, keeping newlines so line numbers survive. */
    private static int blank(char[] out, int from, int to) {
        for (int i = from; i < Math.min(to, out.length); i++) {
            if (out[i] != '\n') {
                out[i] = ' ';
            }
        }
        return Math.min(to, out.length);
    }
}
