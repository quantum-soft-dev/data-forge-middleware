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
     * nothing else — exactly what {@code -Xmx} takes.
     *
     * <p>Deliberately intolerant of a trailing {@code b} and of a space before the suffix.
     * {@code "2gb"} and {@code "2 g"} are both values the JVM refuses to start on, and Gradle
     * passes this string through verbatim, so a guard that read either as 2 GiB would report a
     * ceiling for a build that cannot run.</p>
     */
    private static final Pattern SIZE = Pattern.compile("(\\d+)([kmg]?)", Pattern.CASE_INSENSITIVE);

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
     * <p>Read as a list rather than as a first match on purpose. A second assignment on a narrower
     * task ({@code tasks.named<Test>("test")}, {@code integrationTest}) overrides the shared
     * ceiling for that task alone, so the value the guard checked is not the value that task runs
     * on — the hazard being the narrowed one dropping below the floor, since the shared assignment
     * still covers its siblings. Callers require exactly one.</p>
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
     * @throws IllegalArgumentException when the literal is not a size or does not fit a
     *                                  {@code long}, rather than silently reading an unrecognised
     *                                  suffix as bytes or a wrapped product as a plausible ceiling
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
        try {
            // multiplyExact, not *: a digit run that fits a long and then wraps is the one overflow
            // the parse above cannot see, and it lands inside the agreed range rather than outside
            // it — "17179869186g" wraps to exactly 2 GiB and would pass every assertion, while
            // "9999999999g" goes negative and is reported as a negative ceiling instead of failing.
            return Math.multiplyExact(value, multiplier);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "'" + literal + "' is larger than any heap the JVM can take", e);
        }
    }

    /**
     * Whether the build script passes a {@code -Xmx} of its own anywhere.
     *
     * <p>{@code maxHeapSize} is the only way this build may state the ceiling, because it is the
     * only one the guard can read. A {@code -Xmx} smuggled through {@code jvmArgs} — on
     * {@code integrationTest}, say — overrides it for that task while leaving every assertion here
     * green, which is exactly the shape #207 exists to remove.</p>
     *
     * @param script build script source
     * @return true when a {@code -Xmx} appears outside a comment
     */
    public static boolean declaresRawXmx(String script) {
        return stripComments(script).contains("-Xmx");
    }

    /** Human-readable MiB, for failure messages. */
    public static String describe(long bytes) {
        return (bytes / (1024 * 1024)) + " MiB";
    }

    /**
     * Blanks {@code //} and block comments, keeping every other character at its own offset so the
     * result can be indexed against {@link #maskLiterals(String)} of itself.
     */
    private static String stripComments(String source) {
        char[] out = source.toCharArray();
        for (Span span : lex(source)) {
            if (span.comment()) {
                blank(out, span.from(), span.to());
            }
        }
        return new String(out);
    }

    /**
     * Blanks the contents of every string and character literal, keeping the length, so a brace
     * inside a path, a regular expression or a Kotlin template cannot unbalance the block match.
     */
    private static String maskLiterals(String source) {
        char[] out = source.toCharArray();
        for (Span span : lex(source)) {
            if (!span.comment()) {
                blank(out, span.from(), span.to());
            }
        }
        return new String(out);
    }

    /** A comment or a literal, as a half-open range of the source. */
    private record Span(int from, int to, boolean comment) {
    }

    /**
     * Every comment and every literal in a Kotlin source, in one pass.
     *
     * <p>The hard part is the template: {@code "…${expr}…"} puts <em>code</em> inside a literal, and
     * that code may hold literals of its own — {@code "-XX:HeapDumpPath=${dir("reports").path}"} is
     * the shape this very build file has. A scanner that pairs the first inner quote with the outer
     * one desynchronises from there on, and the two ways that shows up are both silent: a brace in
     * a later template ends the block early (the guard reports no {@code tasks.withType<Test>} block
     * at all) and a {@code //} inside a literal — a URL — blanks the rest of a line that may carry
     * the declaration. The same class of hole as the char literal below and as the regex
     * {@code AsyncExecutorQualifierTest} had to replace; found in review of #207.</p>
     *
     * <p>A template's expression is reported as part of the literal. For the two callers that is
     * exactly right: brace matching must not see its braces, and no flag is ever written inside
     * one.</p>
     */
    private static List<Span> lex(String source) {
        List<Span> spans = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                int stop = end < 0 ? source.length() : end;
                spans.add(new Span(i, stop, true));
                i = stop;
            } else if (source.startsWith("/*", i)) {
                int stop = endOfBlockComment(source, i);
                spans.add(new Span(i, stop, true));
                i = stop;
            } else if (source.startsWith("\"\"\"", i)) {
                int stop = endOfRawString(source, i);
                spans.add(new Span(i, stop, false));
                i = stop;
            } else if (source.charAt(i) == '"') {
                int stop = endOfString(source, i);
                spans.add(new Span(i, stop, false));
                i = stop;
            } else if (source.charAt(i) == '\'') {
                int stop = endOfCharLiteral(source, i);
                spans.add(new Span(i, stop, false));
                i = stop;
            } else {
                i++;
            }
        }
        return spans;
    }

    /**
     * Offset just past the end of the block comment starting at {@code start}; Kotlin nests them.
     *
     * <p>The closing delimiter is deliberately not written here in {@code @code} form: Java lexes
     * the sequence before any Javadoc tool sees it, so it would close this comment mid-sentence —
     * the mistake {@code AsyncExecutorQualifierTest} made and had to correct.</p>
     */
    private static int endOfBlockComment(String source, int start) {
        int depth = 0;
        int i = start;
        while (i < source.length()) {
            if (source.startsWith("/*", i)) {
                depth++;
                i += 2;
            } else if (source.startsWith("*/", i)) {
                depth--;
                i += 2;
                if (depth == 0) {
                    return i;
                }
            } else {
                i++;
            }
        }
        return source.length();
    }

    /**
     * Offset just past the quote closing the ordinary string literal at {@code start}.
     *
     * <p>A newline ends it as well: an ordinary Kotlin literal cannot span lines, so meeting one
     * means the source is malformed, and stopping there costs a line rather than the rest of the
     * file.</p>
     */
    private static int endOfString(String source, int start) {
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == '"') {
                return i + 1;
            } else if (c == '\n') {
                return i;
            } else if (c == '$' && i + 1 < source.length() && source.charAt(i + 1) == '{') {
                i = endOfTemplate(source, i + 1);
            } else {
                i++;
            }
        }
        return source.length();
    }

    /** Offset just past the {@code """} closing the raw string at {@code start}; no escapes there. */
    private static int endOfRawString(String source, int start) {
        int i = start + 3;
        while (i < source.length()) {
            if (source.startsWith("\"\"\"", i)) {
                return i + 3;
            }
            if (source.charAt(i) == '$' && i + 1 < source.length() && source.charAt(i + 1) == '{') {
                i = endOfTemplate(source, i + 1);
            } else {
                i++;
            }
        }
        return source.length();
    }

    /**
     * Offset just past the brace closing the template expression whose {@code &#123;} is at
     * {@code open}. The expression is ordinary code, so its own literals are skipped whole and its
     * braces are counted.
     */
    private static int endOfTemplate(String source, int open) {
        int depth = 0;
        int i = open;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
                i++;
            } else if (c == '}') {
                depth--;
                i++;
                if (depth == 0) {
                    return i;
                }
            } else if (source.startsWith("\"\"\"", i)) {
                i = endOfRawString(source, i);
            } else if (c == '"') {
                i = endOfString(source, i);
            } else if (c == '\'') {
                i = endOfCharLiteral(source, i);
            } else {
                i++;
            }
        }
        return source.length();
    }

    /**
     * Offset just past the closing quote of the Kotlin character literal at {@code start}, or one
     * character on when what follows is not one.
     *
     * <p>Read at all because {@code '"'} would otherwise open a string literal that swallows the
     * code up to the next quote. Bounded to the two shapes a character literal can have, so a stray
     * apostrophe in code costs one character rather than running to the next one.</p>
     */
    private static int endOfCharLiteral(String source, int start) {
        int i = start + 1;
        if (i < source.length() && source.charAt(i) == '\\') {
            i += 2;
        } else {
            i++;
        }
        return i < source.length() && source.charAt(i) == '\'' ? i + 1 : start + 1;
    }

    /** Replaces {@code [from, to)} with spaces, keeping newlines so offsets and lines both survive. */
    private static void blank(char[] out, int from, int to) {
        for (int i = Math.max(from, 0); i < Math.min(to, out.length); i++) {
            if (out[i] != '\n') {
                out[i] = ' ';
            }
        }
    }
}
