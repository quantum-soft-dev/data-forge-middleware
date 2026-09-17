package com.bitbi.dfm.documentation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code scripts/issue-find.sh} is the mandatory search before a ticket is taken and before a new one
 * is filed, so an empty section has to mean "nothing found" and never "could not search" (issue #308).
 * Its path argument plays three roles that want different spellings: a regular expression over issue
 * bodies (where {@code .} is escaped), a literal for {@code gh --search} and for the output line, and
 * a git pathspec. The version this guards against interpolated the escaped path into a jq string
 * literal — {@code "\."} is an invalid escape, so jq refused the expression and the PR section was
 * silently empty — and handed it to git as a pathspec that matched no file.
 *
 * <p>The script runs inside a throwaway git repository whose {@code origin/develop} is a local ref,
 * against a stand-in {@code gh} first on {@code PATH} that applies {@code --jq} with a real jq and
 * returns its failure, as gh does. No network.
 */
@DisplayName("Issue find script")
class IssueFindScriptTest {

    private static final Path SCRIPT = Path.of("scripts/issue-find.sh").toAbsolutePath();
    private static final Path FAKE_GH = Path.of("src/test/resources/process/fake-gh-issue-find");

    @TempDir
    Path work;

    private Path repo;
    private Path fixtures;
    private Path log;
    private Path bin;

    @BeforeEach
    void installFakeGhAndRepository() throws Exception {
        fixtures = Files.createDirectories(work.resolve("fixtures"));
        bin = Files.createDirectories(work.resolve("bin"));
        log = work.resolve("gh-calls.log");
        Path gh = bin.resolve("gh");
        Files.copy(FAKE_GH, gh);
        assertThat(gh.toFile().setExecutable(true)).as("fake gh must be executable").isTrue();

        repo = Files.createDirectories(work.resolve("repo"));
        git("init", "-q");
        commit(".github/workflows/ci-cd.yml", "ci: touch the pipeline");
        commit("scripts/board.sh", "chore: touch the board script");
        commit("docs/ci-cdXyml.md", "docs: a name the escaped dot must not match");
        git("update-ref", "refs/remotes/origin/develop", "HEAD");

        Files.writeString(fixtures.resolve("issues.json"), """
                [{"number":1,"title":"Pipeline","state":"OPEN","labels":[],"body":"Touches .github/workflows/ci-cd.yml"},
                 {"number":2,"title":"Lookalike","state":"OPEN","labels":[],"body":"Touches ci-cdXyml only"}]""",
                StandardCharsets.UTF_8);
        Files.writeString(fixtures.resolve("prs.json"), """
                [{"number":7,"title":"ci: pipeline change","mergedAt":"2026-09-01T10:00:00Z"}]""",
                StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("an escaped path breaks no jq expression and every path section finds its hits")
    void shouldSearchEverySectionWithAnEscapedPath() throws Exception {
        Result result = run("pipeline", "ci-cd\\.yml");

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isZero();
        assertThat(result.stderr()).as("stderr").doesNotContainIgnoringCase("jq").doesNotContain("fake-gh");
        assertThat(section(result.stdout(), "## PR, смерженные"))
                .contains("PR #7 ci: pipeline change  ← ci-cd.yml");
        assertThat(section(result.stdout(), "## Последние коммиты develop по путям"))
                .contains("ci: touch the pipeline")
                .doesNotContain("touch the board script")
                .doesNotContain("the escaped dot must not match");
    }

    @Test
    @DisplayName("issue bodies are matched by the path as a regular expression")
    void shouldMatchIssueBodiesByThePathAsARegularExpression() throws Exception {
        Result result = run("pipeline", "ci-cd\\.yml");

        String bodies = section(result.stdout(), "## Открытые issues, чьё тело упоминает");
        assertThat(bodies).contains("#1 Pipeline").doesNotContain("#2 Lookalike");
    }

    @Test
    @DisplayName("gh --search receives the literal path, not its regular-expression spelling")
    void shouldSearchPullRequestsByTheLiteralPath() throws Exception {
        Result result = run("pipeline", "ci-cd\\.yml");

        List<String> searches = result.calls().stream()
                .filter(call -> call.startsWith("pr\tlist\t"))
                .map(IssueFindScriptTest::searchArgument)
                .toList();
        assertThat(searches).contains("\"ci-cd.yml\" merged:>=" + searches.get(0).replaceAll(".*>=", ""))
                .noneMatch(search -> search.contains("\\"));
    }

    private static String searchArgument(String call) {
        String[] args = call.split("\t");
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--search")) {
                return args[i + 1];
            }
        }
        throw new AssertionError("no --search in " + call);
    }

    @Test
    @DisplayName("a class name finds the commits of the files it names, in any directory")
    void shouldFindCommitsByAFileNameInASubdirectory() throws Exception {
        Result result = run("board", "board");

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isZero();
        assertThat(section(result.stdout(), "## Последние коммиты develop по путям"))
                .contains("touch the board script");
    }

    @Test
    @DisplayName("without paths only the keyword sections run, and nothing is unbound")
    void shouldRunWithoutPaths() throws Exception {
        Result result = run("pipeline");

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isZero();
        assertThat(result.stdout()).contains("## Issues по словам: pipeline")
                .doesNotContain("## Последние коммиты develop по путям");
    }

    @Test
    @DisplayName("a git log that cannot run says so instead of printing an empty section")
    void shouldReportAGitLogThatCannotRun() throws Exception {
        git("update-ref", "-d", "refs/remotes/origin/develop");

        Result result = run("pipeline", "ci-cd\\.yml");

        assertThat(section(result.stdout(), "## Последние коммиты develop по путям"))
                .contains("не выполнился");
    }

    private static String section(String stdout, String heading) {
        int start = stdout.indexOf(heading);
        assertThat(start).as("section '%s' in:%n%s", heading, stdout).isNotNegative();
        int next = stdout.indexOf("\n## ", start + heading.length());
        return next < 0 ? stdout.substring(start) : stdout.substring(start, next);
    }

    private void commit(String file, String message) throws Exception {
        Path path = repo.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, message + "\n", StandardCharsets.UTF_8);
        git("add", file);
        git("-c", "user.name=Test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false",
                "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", message);
    }

    private void git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as("git %s: %s", String.join(" ", args), output).isZero();
    }

    private Result run(String... args) throws Exception {
        Path stdout = work.resolve("stdout-" + System.nanoTime());
        Path stderr = work.resolve("stderr-" + System.nanoTime());
        List<String> command = new ArrayList<>(List.of("bash", SCRIPT.toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(repo.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile());
        Map<String, String> env = builder.environment();
        env.put("PATH", bin + File.pathSeparator + env.getOrDefault("PATH", "/usr/bin:/bin"));
        env.put("FAKE_GH_DIR", fixtures.toString());
        env.put("FAKE_GH_LOG", log.toString());
        Process process = builder.start();
        process.getOutputStream().close();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("scripts/issue-find.sh did not finish within 30 s");
        }
        List<String> calls = Files.exists(log) ? Files.readAllLines(log, StandardCharsets.UTF_8) : List.of();
        return new Result(process.exitValue(), Files.readString(stdout, StandardCharsets.UTF_8),
                Files.readString(stderr, StandardCharsets.UTF_8), calls);
    }

    private record Result(int exitCode, String stdout, String stderr, List<String> calls) {
    }
}
