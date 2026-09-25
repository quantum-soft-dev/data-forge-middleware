package com.bitbi.dfm.documentation;

import com.bitbi.dfm.testsupport.RunOwnedScratch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No tracked file carries a line a merge conflict left behind (issue #342).
 *
 * <p>A diff3 marker ({@code |||||||} and the ancestor's sha) reached {@code develop} twice inside the
 * "Recent Changes" journals of {@code CLAUDE.md} and {@code AGENTS.md} — found once by review of
 * #212 and once by the migration sync of #305. Nothing could have caught it: the compiler does not
 * read markdown, and {@link AgentJournalConsistencyTest} reads entry slugs, which a marker line is
 * not. The journals are merged by hand on every conflict between two PRs, so twice in a month is a
 * mechanism rather than a slip.</p>
 *
 * <h2>What is read</h2>
 *
 * <p>Every path {@code git ls-files} lists — the index, so a file staged a moment ago is read by the
 * pre-commit hook — except binary ones, recognised the way git itself does it: a NUL byte in the
 * first 8000. Not a directory walk: in the main checkout {@code .claude/worktrees/} holds other
 * worktrees, possibly in the middle of the very conflict this guard is about, and
 * {@code frontend/node_modules} holds files nobody here owns. {@code build.gradle.kts} declares the
 * same list as inputs of {@code test}, so a docs-only commit does not leave the task UP-TO-DATE —
 * the trap #298 and #311 fell into.</p>
 *
 * <h2>What a marker is</h2>
 *
 * <p>A line beginning in column 0 with seven {@code <}, {@code |} or {@code >} followed by a space or
 * the end of the line — git writes a label after each ({@code HEAD}, the ancestor's sha, the branch),
 * and some tools write none — and a line that is exactly {@code =======}. Eight or more of the same
 * character is not a marker. The last one is legal Markdown, a setext heading underline, which is
 * why the ticket asked to look first: no tracked file carries it (the setext underlines in
 * {@code docs/device-flow-client-guide.md} and {@code specs/} are longer), so it is caught on its
 * own. A lone leftover {@code =======} is exactly as real as the lone {@code |||||||} of #305, and a
 * seven-character heading that trips it is fixed by lengthening its underline.</p>
 *
 * <p>The marker characters are never written in column 0 of this file: the fixtures build them with
 * {@link String#repeat}, so this class passes its own scan for a reason rather than by accident.</p>
 */
@DisplayName("Conflict marker lines (issue #342)")
class ConflictMarkerLineTest {

    private static final int BINARY_PROBE_BYTES = 8000;
    private static final String SEPARATOR = "=".repeat(7);
    private static final List<String> LABELLED = List.of("<".repeat(7), "|".repeat(7), ">".repeat(7));

    @Test
    @DisplayName("no tracked text file has a conflict marker line")
    void noTrackedTextFileHasAConflictMarkerLine() throws IOException, InterruptedException {
        Path root = RunOwnedScratch.projectRoot();
        List<String> findings = new ArrayList<>();
        for (String file : trackedFiles(root)) {
            Path path = root.resolve(file);
            if (!Files.isRegularFile(path)) {
                continue; // staged for deletion, or a symlink — nothing of ours to read
            }
            for (int lineNumber : markerLines(Files.readAllBytes(path))) {
                findings.add(file + ":" + lineNumber);
            }
        }

        assertThat(findings)
                .as("lines a merge conflict left behind — resolve them; a seven-character setext "
                        + "heading underline is read as one too and is fixed by lengthening it")
                .isEmpty();
    }

    @Test
    @DisplayName("the scan is not blind — both agent journals are among the files read")
    void theScanReadsBothAgentJournals() throws IOException, InterruptedException {
        // If git answered nothing the scan above is green for the wrong reason; the two files the
        // marker actually reached twice must be in what it reads.
        assertThat(trackedFiles(RunOwnedScratch.projectRoot()))
                .contains("CLAUDE.md", "AGENTS.md", "build.gradle.kts");
    }

    @Nested
    @DisplayName("the recogniser")
    class Recogniser {

        @Test
        @DisplayName("finds the diff3 ancestor line of #305 in a journal")
        void findsTheDiff3AncestorLine() {
            String journal = "## Recent Changes\n- form-base-branch-hint: text\n"
                    + "|".repeat(7) + " 91b14924\n- gauge-weak-target-held: text\n";

            assertThat(markerLines(bytes(journal))).containsExactly(3);
        }

        @Test
        @DisplayName("finds every marker git writes, labelled or bare")
        void findsEveryMarker() {
            String conflict = "<".repeat(7) + " HEAD\nours\n"
                    + "|".repeat(7) + "\nbase\n"
                    + SEPARATOR + "\ntheirs\n"
                    + ">".repeat(7) + " feature/x\n";

            assertThat(markerLines(bytes(conflict))).containsExactly(1, 3, 5, 7);
        }

        @Test
        @DisplayName("reads a CRLF file the same way")
        void readsCrlf() {
            String conflict = "a\r\n" + LABELLED.get(0) + " HEAD\r\nb\r\n" + SEPARATOR + "\r\nc\r\n";

            assertThat(markerLines(bytes(conflict))).containsExactly(2, 4);
        }

        @Test
        @DisplayName("ignores a longer run, an indented one and a marker glued to text")
        void ignoresLookalikes() {
            String text = "Title\n" + "=".repeat(8) + "\n"
                    + "<".repeat(8) + " not a marker\n"
                    + "  " + "|".repeat(7) + " indented\n"
                    + ">".repeat(7) + "glued\n"
                    + SEPARATOR + " trailing\n"
                    + "text " + "<".repeat(7) + " mid-line\n";

            assertThat(markerLines(bytes(text))).isEmpty();
        }

        @Test
        @DisplayName("skips a binary file, as git does")
        void skipsBinary() {
            byte[] binary = bytes("\0" + "\n" + LABELLED.get(1) + " abc\n");

            assertThat(markerLines(binary)).isEmpty();
        }
    }

    /**
     * One-based numbers of the lines that are conflict markers; none for a binary file.
     *
     * @param content the file's bytes
     * @return the marker lines, in order
     */
    static List<Integer> markerLines(byte[] content) {
        List<Integer> lines = new ArrayList<>();
        int probe = Math.min(content.length, BINARY_PROBE_BYTES);
        for (int i = 0; i < probe; i++) {
            if (content[i] == 0) {
                return lines;
            }
        }
        // ISO-8859-1 decodes any byte sequence, and every marker is ASCII.
        String[] split = new String(content, StandardCharsets.ISO_8859_1).split("\n", -1);
        for (int i = 0; i < split.length; i++) {
            String line = split[i].endsWith("\r") ? split[i].substring(0, split[i].length() - 1) : split[i];
            if (isMarker(line)) {
                lines.add(i + 1);
            }
        }
        return lines;
    }

    private static boolean isMarker(String line) {
        if (line.equals(SEPARATOR)) {
            return true;
        }
        for (String marker : LABELLED) {
            if (line.startsWith(marker) && (line.length() == marker.length() || line.charAt(marker.length()) == ' ')) {
                return true;
            }
        }
        return false;
    }

    private static List<String> trackedFiles(Path root) throws IOException, InterruptedException {
        Process git = new ProcessBuilder("git", "ls-files", "-z")
                .directory(root.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        byte[] out;
        try (InputStream stdout = git.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            stdout.transferTo(buffer);
            out = buffer.toByteArray();
        }
        if (!git.waitFor(60, TimeUnit.SECONDS)) {
            git.destroyForcibly();
            throw new AssertionError("git ls-files did not finish in " + root);
        }
        assertThat(git.exitValue()).as("git ls-files in %s", root).isZero();
        List<String> files = new ArrayList<>();
        for (String name : new String(out, StandardCharsets.UTF_8).split("\0")) {
            if (!name.isEmpty()) {
                files.add(name);
            }
        }
        return files;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
