package com.bitbi.dfm.documentation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
 * {@code scripts/pr-merge.sh} is the one merge step of {@code /task}, {@code /merge} and
 * {@code /github-issue-runner} (issue #332). {@code gh pr merge --delete-branch} run from a worktree
 * merged the PR on GitHub and then failed its <em>local</em> cleanup ({@code fatal: 'develop' is
 * already used by worktree}): it exited non-zero, so the output read as a failed merge, and the
 * branch was deleted neither locally nor on the remote — with {@code deleteBranchOnMerge} off, nothing
 * else removes it.
 *
 * <p>The script therefore merges without {@code --delete-branch}, takes the verdict from the pull
 * request re-read over REST rather than from the exit code, and deletes the head branch over REST,
 * which does not depend on any local tree. It runs against a stand-in {@code gh} that plays GitHub
 * from fixtures and refuses {@code --delete-branch}, {@code gh pr view} and GraphQL, so each of those
 * coming back turns this class red.
 */
@DisplayName("PR merge script")
class PrMergeScriptTest {

    private static final Path SCRIPT = Path.of("scripts/pr-merge.sh");
    private static final Path FAKE_GH = Path.of("src/test/resources/process/fake-gh-pr-merge");
    private static final String BRANCH_REF = "repos/quantum-soft-dev/data-forge-middleware/git/refs/heads/";

    @TempDir
    Path work;

    private Path fixtures;
    private Path log;
    private Path bin;

    @BeforeEach
    void installFakeGh() throws Exception {
        fixtures = Files.createDirectories(work.resolve("fixtures"));
        bin = Files.createDirectories(work.resolve("bin"));
        log = work.resolve("gh-calls.log");
        Path gh = bin.resolve("gh");
        Files.copy(FAKE_GH, gh);
        assertThat(gh.toFile().setExecutable(true)).as("fake gh must be executable").isTrue();
    }

    @Test
    @DisplayName("squash-merges without --delete-branch, then deletes the head branch over REST")
    void shouldMergeThenDeleteTheBranchOverRest() throws Exception {
        pr("open", false, "feature/332-x", "develop", null);

        Result result = run("42");

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isZero();
        assertThat(result.mergeCalls()).singleElement().satisfies(call -> assertThat(call)
                .startsWith("pr\tmerge\t42\t").contains("\t--squash\t")
                .contains("\t--repo\tquantum-soft-dev/data-forge-middleware\t"));
        assertThat(result.deleteCalls()).singleElement()
                .satisfies(call -> assertThat(call).contains(BRANCH_REF + "feature/332-x"));
        assertThat(result.stdout()).isEqualTo("#42 merged into develop as 01234567; branch feature/332-x deleted\n");
    }

    @Test
    @DisplayName("a merge that landed is a success even when gh pr merge exits non-zero on its local cleanup")
    void shouldTreatALandedMergeAsSuccessWhateverGhExitedWith() throws Exception {
        pr("open", false, "feature/332-x", "develop", null);
        write("merge-mode", "fatal-after-merge");

        Result result = run("42");

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isZero();
        assertThat(result.deleteCalls()).singleElement()
                .satisfies(call -> assertThat(call).contains(BRANCH_REF + "feature/332-x"));
        assertThat(result.stderr()).contains("already used by worktree").contains("not the verdict");
        assertThat(result.stdout()).isEqualTo("#42 merged into develop as 01234567; branch feature/332-x deleted\n");
    }

    @Test
    @DisplayName("a refused merge exits 1, quotes gh and leaves the branch alone")
    void shouldExitOneAndKeepTheBranchWhenTheMergeIsRefused() throws Exception {
        pr("open", false, "feature/332-x", "develop", null);
        write("merge-mode", "refused");

        Result result = run("42");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("not mergeable").contains("#42 was not merged");
        assertThat(result.deleteCalls()).isEmpty();
        assertThat(result.stdout()).isEmpty();
    }

    @ParameterizedTest(name = "head {0}")
    @ValueSource(strings = {"develop", "main", "stage", "release/2.1", "migration/spring-boot-4.1"})
    @DisplayName("refuses a PR whose head is a long-lived branch, before merging or deleting anything")
    void shouldRefuseALongLivedHeadBranch(String head) throws Exception {
        pr("open", false, head, "develop", null);

        Result result = run("42");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains(head);
        assertThat(result.mergeCalls()).isEmpty();
        assertThat(result.deleteCalls()).isEmpty();
    }

    @Test
    @DisplayName("a PR already merged by an earlier run is not merged again; its branch is still removed")
    void shouldFinishTheCleanupOfAnAlreadyMergedPr() throws Exception {
        pr("open", false, "chore/310-form-base-branch", "develop", null);
        write("merged", "");

        Result result = run("42");

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isZero();
        assertThat(result.mergeCalls()).isEmpty();
        assertThat(result.deleteCalls()).singleElement()
                .satisfies(call -> assertThat(call).contains(BRANCH_REF + "chore/310-form-base-branch"));
        assertThat(result.stdout()).contains("branch chore/310-form-base-branch deleted");
    }

    @Test
    @DisplayName("a PR closed without a merge is refused before anything is touched")
    void shouldRefuseAPrClosedWithoutAMerge() throws Exception {
        pr("closed", false, "feature/332-x", "develop", null);

        Result result = run("42");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains("closed without");
        assertThat(result.mergeCalls()).isEmpty();
        assertThat(result.deleteCalls()).isEmpty();
    }

    @Test
    @DisplayName("a branch that is already gone counts as deleted")
    void shouldAcceptABranchThatIsAlreadyGone() throws Exception {
        pr("open", false, "feature/332-x", "develop", null);
        write("delete-mode", "gone");

        Result result = run("42");

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isZero();
        assertThat(result.stdout()).contains("branch feature/332-x deleted");
    }

    @Test
    @DisplayName("a branch that survives the delete exits 3 and says the merge itself happened")
    void shouldExitThreeWhenTheMergeLandedButTheBranchSurvived() throws Exception {
        pr("open", false, "feature/332-x", "develop", null);
        write("delete-mode", "fail");

        Result result = run("42");

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stderr()).contains("#42 IS merged").contains("feature/332-x")
                .contains("do not merge again");
        assertThat(result.mergeCalls()).hasSize(1);
    }

    @Test
    @DisplayName("--rebase merges with --rebase and still deletes over REST")
    void shouldRebaseWhenAsked() throws Exception {
        pr("open", false, "feature/332-x", "migration/spring-boot-4.1", null);

        Result result = run("42", "--rebase");

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isZero();
        assertThat(result.mergeCalls()).singleElement()
                .satisfies(call -> assertThat(call).contains("\t--rebase\t").doesNotContain("--squash"));
        assertThat(result.deleteCalls()).hasSize(1);
        assertThat(result.stdout()).startsWith("#42 merged into migration/spring-boot-4.1 as ");
    }

    @Test
    @DisplayName("a head branch in a fork is merged and left alone — it is not this repository's to delete")
    void shouldNotDeleteABranchThatLivesInAFork() throws Exception {
        pr("open", false, "feature/x", "develop", "someone/data-forge-middleware");

        Result result = run("42");

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isZero();
        assertThat(result.mergeCalls()).hasSize(1);
        assertThat(result.deleteCalls()).isEmpty();
        assertThat(result.stdout()).contains("someone/data-forge-middleware").contains("not deleted");
    }

    @ParameterizedTest(name = "args {0}")
    @ValueSource(strings = {"", "abc", "42 --merge", "42 --squash --rebase"})
    @DisplayName("refuses malformed arguments without calling gh")
    void shouldRefuseMalformedArguments(String args) throws Exception {
        Result result = run(args.isEmpty() ? new String[0] : args.split(" "));

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.calls()).isEmpty();
    }

    private void pr(String state, boolean merged, String head, String base, String headRepo) throws Exception {
        String repo = headRepo == null ? "quantum-soft-dev/data-forge-middleware" : headRepo;
        write("pr.json", """
                {"number":42,"state":"%s","merged":%s,"merge_commit_sha":null,
                 "head":{"ref":"%s","repo":{"full_name":"%s"}},"base":{"ref":"%s"}}"""
                .formatted(state, merged, head, repo, base));
    }

    private void write(String name, String content) throws Exception {
        Files.writeString(fixtures.resolve(name), content, StandardCharsets.UTF_8);
    }

    private Result run(String... args) throws Exception {
        Path stdout = work.resolve("stdout-" + System.nanoTime());
        Path stderr = work.resolve("stderr-" + System.nanoTime());
        List<String> command = new ArrayList<>(List.of("bash", SCRIPT.toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command)
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
            throw new AssertionError("scripts/pr-merge.sh did not finish within 30 s");
        }
        List<String> calls = Files.exists(log) ? Files.readAllLines(log, StandardCharsets.UTF_8) : List.of();
        String err = Files.readString(stderr, StandardCharsets.UTF_8);
        // Every scenario: the fake exits 97 on a call it does not expect (`--delete-branch`,
        // `gh pr view`, GraphQL), and a script that swallowed that exit would otherwise pass.
        assertThat(err).as("the script made a call the fake refuses").doesNotContain("fake-gh:");
        return new Result(process.exitValue(), Files.readString(stdout, StandardCharsets.UTF_8), err, calls);
    }

    private record Result(int exitCode, String stdout, String stderr, List<String> calls) {

        List<String> mergeCalls() {
            return calls.stream().filter(call -> call.startsWith("pr\tmerge\t")).toList();
        }

        List<String> deleteCalls() {
            return calls.stream().filter(call -> call.startsWith("api\t") && call.contains("\t-X\tDELETE\t")).toList();
        }
    }
}
