package com.bitbi.dfm.documentation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code scripts/issue-base.sh} is the one reading of the {@code Base branch:} line that {@code /task},
 * {@code /github-issue} and {@code /github-issue-runner} branch from, sync with, open their PR against
 * and merge into (issue #298). The rule is subtle enough to be read differently four times — only a
 * line before the first heading and outside code counts — so it lives in one script, the way the board
 * identifiers live in one section of {@code CLAUDE.md}, and this class is what holds it.
 *
 * <p>The failure that matters is silence in either direction: a migration ticket whose declaration is
 * not read falls back to {@code develop} and opens its PR there, which is the defect #298 exists for,
 * while an example quoted in a ticket's prose that <em>is</em> read retargets a ticket that belongs on
 * {@code develop}. So a line that looks like a declaration but cannot be read as one fails loudly
 * rather than meaning {@code develop}.
 */
@DisplayName("Issue base branch resolver")
class IssueBaseBranchScriptTest {

    private static final Path SCRIPT = Path.of("scripts/issue-base.sh");
    private static final Path ISSUE_298_BODY = Path.of("src/test/resources/process/issue-298-body.md");
    private static final String MIGRATION = "migration/spring-boot-4.1";

    @TempDir
    Path output;

    @Test
    @DisplayName("an issue without a declaration is based on develop")
    void shouldBeBasedOnDevelopWhenTheBodyDeclaresNothing() throws Exception {
        assertResolves("""
                Blocked by #12

                ## Что происходит

                Nothing about branches here.
                """, "develop");
    }

    @Test
    @DisplayName("an empty body is based on develop")
    void shouldBeBasedOnDevelopWhenTheBodyIsEmpty() throws Exception {
        assertResolves("", "develop");
    }

    @Test
    @DisplayName("reads the declaration a migration ticket carries above its first heading")
    void shouldReadTheDeclarationAboveTheFirstHeading() throws Exception {
        assertResolves("""
                Base branch: `migration/spring-boot-4.1`
                Blocked by #298

                ## Что происходит

                Text.
                """, MIGRATION);
    }

    @Test
    @DisplayName("an explicit develop declaration is develop")
    void shouldAcceptAnExplicitDevelopDeclaration() throws Exception {
        assertResolves("Base branch: `develop`\n\n## Heading\n", "develop");
    }

    @Test
    @DisplayName("ignores a declaration below the first heading")
    void shouldIgnoreADeclarationBelowTheFirstHeading() throws Exception {
        assertResolves("""
                Blocked by #12

                ## Что решить

                Base branch: `migration/spring-boot-4.1`
                """, "develop");
    }

    @Test
    @DisplayName("ignores a declaration inside a backtick fence above the first heading")
    void shouldIgnoreADeclarationInsideABacktickFence() throws Exception {
        assertResolves("""
                ```text
                Base branch: `migration/spring-boot-4.1`
                ```

                ## Heading
                """, "develop");
    }

    @Test
    @DisplayName("ignores a declaration inside a tilde fence above the first heading")
    void shouldIgnoreADeclarationInsideATildeFence() throws Exception {
        assertResolves("""
                ~~~
                Base branch: `migration/spring-boot-4.1`
                ~~~
                """, "develop");
    }

    @Test
    @DisplayName("a fence closes only on its own marker, not on the other kind")
    void shouldKeepAFenceOpenUntilItsOwnMarker() throws Exception {
        assertResolves("""
                ````
                ```
                Base branch: `migration/spring-boot-4.1`
                ~~~
                ````
                """, "develop");
    }

    @Test
    @DisplayName("a heading-shaped line inside a fence does not end the header")
    void shouldNotTreatAHashLineInsideAFenceAsTheFirstHeading() throws Exception {
        assertResolves("""
                ```bash
                # a shell comment, not a heading
                ```
                Base branch: `migration/spring-boot-4.1`

                ## Heading
                """, MIGRATION);
    }

    @Test
    @DisplayName("ignores a declaration in an indented code block")
    void shouldIgnoreADeclarationInAnIndentedCodeBlock() throws Exception {
        assertResolves("Example:\n\n    Base branch: `migration/spring-boot-4.1`\n\n## Heading\n", "develop");
    }

    @Test
    @DisplayName("reads a declaration from a body with CRLF line endings")
    void shouldReadADeclarationFromACrlfBody() throws Exception {
        assertResolves("Base branch: `migration/spring-boot-4.1`\r\nBlocked by #298\r\n\r\n## Heading\r\n", MIGRATION);
    }

    @Test
    @DisplayName("this very ticket, whose prose quotes the line after its first heading, stays on develop")
    void shouldKeepIssue298OnDevelop() throws Exception {
        String body = Files.readString(ISSUE_298_BODY);
        assertThat(body)
                .as("the fixture must still quote the declaration, or it proves nothing")
                .contains("Base branch:");

        assertResolves(body, "develop");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "Base branch: migration/spring-boot-4.1",
            "Base branch: `migration/spring-boot-4.1",
            "base branch: `migration/spring-boot-4.1`",
            "Base Branch: `migration/spring-boot-4.1`",
            "Base branch:`migration/spring-boot-4.1`",
            "  Base branch: `migration/spring-boot-4.1`",
            "Base branch: `migration/spring-boot-4.1` (see #305)",
    })
    @DisplayName("refuses a line that looks like a declaration but is not the exact form")
    void shouldRefuseAMalformedDeclaration(String line) throws Exception {
        assertRefused(line + "\n\n## Heading\n", "Base branch");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"main", "release", "feature/298-x", "migration/", "migration/a/b",
            "migration/../develop", "migration/-x", "migration/a b"})
    @DisplayName("refuses a base that is neither develop nor a migration branch")
    void shouldRefuseABaseThatIsNotDevelopOrAMigrationBranch(String branch) throws Exception {
        assertRefused("Base branch: `" + branch + "`\n", branch);
    }

    @Test
    @DisplayName("refuses a body that declares a base twice")
    void shouldRefuseTwoDeclarations() throws Exception {
        assertRefused("""
                Base branch: `migration/spring-boot-4.1`
                Base branch: `migration/spring-boot-4.1`
                """, "больше одного раза");
    }

    @Test
    @DisplayName("refuses an issue argument that is not a number, before touching the network")
    void shouldRefuseANonNumericIssueArgument() throws Exception {
        Result result = run(List.of("bash", SCRIPT.toString(), "abc"), "");

        assertThat(result.exitCode()).as("exit code, stderr: %s", result.stderr()).isNotZero();
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("номер issue");
    }

    /**
     * The forms of {@code .github/ISSUE_TEMPLATE/} cannot carry the declaration in a field of their own
     * (issue #310): GitHub renders every field as {@code ### <label>} with its value beneath, and a form
     * body therefore <em>begins</em> with a heading — the region this resolver reads is empty for every
     * ticket filed from the web UI. So a migration ticket filed that way declares its base by hand, and
     * the forms carry a hint saying so.
     *
     * <p>A hint is prose, and prose rots in one direction that costs: teaching a line the script refuses
     * sends every migration ticket into a refusal, and teaching one it silently ignores restores the gap
     * #298 exists for. These cases take the line <em>out of the form</em> and require the script to read
     * it, so the hint and the resolver are one fact rather than two. What they cannot hold is where the
     * hint says to put the line; that is held from the other side, by the two cases below showing that
     * every position a form can produce resolves to {@code develop}.
     */
    @Nested
    @DisplayName("Issue forms")
    class IssueForms {

        private static final String TASK_FORM = ".github/ISSUE_TEMPLATE/task.yml";
        private static final String BUG_FORM = ".github/ISSUE_TEMPLATE/bug.yml";

        /** The declaration as the repository writes it, inside the double-backtick span of a hint. */
        private static final Pattern DECLARATION = Pattern.compile("Base branch: `([^`\n]+)`");

        @Test
        @DisplayName("a base arriving as a form field of its own is not read: its section is below the first heading")
        void shouldStayOnDevelopWhenTheBaseArrivesAsAFormSection() throws Exception {
            assertResolves(formBody("""
                    ### Base branch

                    migration/spring-boot-4.1
                    """), "develop");
        }

        @Test
        @DisplayName("a declaration written inside a form section is not read either")
        void shouldStayOnDevelopWhenTheDeclarationSitsInsideAFormSection() throws Exception {
            assertResolves(formBody("""
                    ### Смежное, сюда не входит

                    Base branch: `migration/spring-boot-4.1`
                    """), "develop");
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {TASK_FORM, BUG_FORM})
        @DisplayName("the line the form teaches is the line the resolver accepts, above a form body")
        void shouldAcceptTheDeclarationTheFormTeaches(String form) throws Exception {
            assertResolves(declarationTaughtBy(form) + "\n" + formBody(""), MIGRATION);
        }

        /**
         * The one declaration a form quotes, with the {@code <name>} placeholder resolved. Exactly one,
         * because a second copy is a second chance for the two to disagree.
         */
        private String declarationTaughtBy(String form) throws IOException {
            Matcher matcher = DECLARATION.matcher(Files.readString(Path.of(form), StandardCharsets.UTF_8));

            assertThat(matcher.find())
                    .as("%s must teach a migration ticket the exact declaration, or nothing tells the person "
                            + "filing one that the base has to be written by hand", form)
                    .isTrue();
            String branch = matcher.group(1).replace("<name>", "spring-boot-4.1");
            assertThat(matcher.find())
                    .as("%s quotes the declaration more than once; one of the copies will go stale", form)
                    .isFalse();

            return "Base branch: `" + branch + "`";
        }

        /** A body as GitHub renders an issue form: every field under its own heading, nothing above the first. */
        private String formBody(String extraSection) {
            return """
                    ### Предлагаемый майлстоун

                    Sprint Migration — Spring Boot 4.1

                    ### Что происходит

                    Prose.

                    """ + extraSection;
        }
    }

    private void assertResolves(String body, String expectedBase) throws Exception {
        Result result = resolve(body);

        assertThat(result.exitCode()).as("exit code, stderr: %s", result.stderr()).isZero();
        assertThat(result.stdout()).isEqualTo(expectedBase + "\n");
    }

    private void assertRefused(String body, String messageFragment) throws Exception {
        Result result = resolve(body);

        assertThat(result.exitCode()).as("exit code, stdout: %s", result.stdout()).isNotZero();
        assertThat(result.stdout())
                .as("a refused body must print no branch a caller could read as an answer")
                .isEmpty();
        assertThat(result.stderr()).contains(messageFragment);
    }

    private Result resolve(String body) throws Exception {
        return run(List.of("bash", SCRIPT.toString(), "-"), body);
    }

    private Result run(List<String> command, String stdin) throws Exception {
        Path stdout = output.resolve("stdout-" + System.nanoTime());
        Path stderr = output.resolve("stderr-" + System.nanoTime());
        Process process = new ProcessBuilder(command)
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start();
        try (OutputStream in = process.getOutputStream()) {
            in.write(stdin.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // The script may exit before reading its input (a refused argument); that is its answer.
        }
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("scripts/issue-base.sh did not finish within 20 s: " + command);
        }
        return new Result(process.exitValue(),
                Files.readString(stdout, StandardCharsets.UTF_8),
                Files.readString(stderr, StandardCharsets.UTF_8));
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}
