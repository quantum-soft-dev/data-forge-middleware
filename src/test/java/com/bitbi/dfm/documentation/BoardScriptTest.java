package com.bitbi.dfm.documentation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code scripts/board.sh} moves a card and its {@code status:*} label, and it runs from every command
 * of the delivery process — several times per ticket, from up to three sessions at once. The GraphQL
 * budget it draws on is one per account (issue #311): the version this replaces spent ~500 points on a
 * single transition ({@code gh project item-list --limit 500} twice, ~200 each, and
 * {@code field-list}, ~100), so a run of three tickets could exhaust the hour on its own.
 *
 * <p>The script runs against a stand-in {@code gh} placed first on {@code PATH}. It records every call
 * and answers from fixtures, and it refuses anything it does not recognise — {@code gh project ...}
 * included — so a costly call coming back turns this class red rather than passing against a
 * permissive fake. What is asserted is both halves: the calls stay cheap, and the observable result
 * (column, labels, which tickets are unblocked) is the one the old script produced.
 */
@DisplayName("Board script")
class BoardScriptTest {

    private static final Path SCRIPT = Path.of("scripts/board.sh");
    private static final Path FAKE_GH = Path.of("src/test/resources/process/fake-gh");

    private static final String OPTIONS = """
            [{"id":"opt-backlog","name":"Backlog"},{"id":"opt-ready","name":"Ready"},
             {"id":"opt-progress","name":"In Progress"},{"id":"opt-blocked","name":"Blocked"},
             {"id":"opt-review","name":"In Review"},{"id":"opt-done","name":"Done"}]""";

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
    @DisplayName("moves a card with three GraphQL calls and no gh project command")
    void shouldMoveACardWithThreeGraphqlCallsAndNoProjectCommand() throws Exception {
        lookup(42, "item-42", OPTIONS);
        write("labels-42.json", "[{\"name\":\"enhancement\"},{\"name\":\"status: ready\"}]");
        write("verify.json", verify("In Progress"));

        Result result = run("status", "42", "In Progress");

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isZero();
        assertThat(result.stdout()).isEqualTo("#42 → In Progress  [status: in progress]\n");
        assertThat(result.graphqlCalls()).as("GraphQL calls: %s", result.calls()).hasSize(3);
        assertThat(result.calls()).noneMatch(call -> call.startsWith("project"));
        assertThat(result.callsContaining("updateProjectV2ItemFieldValue"))
                .singleElement().satisfies(call -> assertThat(call)
                        .contains("item=item-42").contains("option=opt-progress")
                        .contains("field=field-status").contains("project=project-16"));
        assertThat(result.restCalls("DELETE")).singleElement()
                .satisfies(call -> assertThat(call).contains("/issues/42/labels/status%3A%20ready"));
        assertThat(result.restCalls("POST")).singleElement()
                .satisfies(call -> assertThat(call).contains("labels[]=status: in progress"));
    }

    @Test
    @DisplayName("adds an issue that is not on the board, then moves the new card")
    void shouldAddAnIssueThatIsNotOnTheBoard() throws Exception {
        lookup(42, null, OPTIONS);
        write("add.json", "{\"data\":{\"addProjectV2ItemById\":{\"item\":{\"id\":\"item-new\"}}}}");
        write("verify.json", verify("Ready"));

        Result result = run("status", "42", "Ready");

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isZero();
        assertThat(result.callsContaining("addProjectV2ItemById")).singleElement()
                .satisfies(call -> assertThat(call).contains("content=issue-node-42").contains("project=project-16"));
        assertThat(result.callsContaining("updateProjectV2ItemFieldValue")).singleElement()
                .satisfies(call -> assertThat(call).contains("item=item-new").contains("option=opt-ready"));
        assertThat(result.graphqlCalls()).hasSize(4);
        assertThat(result.stdout()).isEqualTo("#42 → Ready  [status: ready]\n");
    }

    @Test
    @DisplayName("refuses a column the board does not have, before any mutation or label change")
    void shouldRefuseAColumnTheBoardDoesNotHave() throws Exception {
        lookup(42, "item-42", "[{\"id\":\"opt-ready\",\"name\":\"Ready\"}]");

        Result result = run("status", "42", "Blocked");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).contains("Blocked");
        assertThat(result.callsContaining("mutation")).isEmpty();
        assertThat(result.calls()).noneMatch(call -> call.contains("/labels"));
    }

    @Test
    @DisplayName("Done strips every status label actually present and adds none")
    void shouldStripEveryStatusLabelAndAddNoneOnDone() throws Exception {
        lookup(42, "item-42", OPTIONS);
        write("labels-42.json", """
                [{"name":"priority: high"},{"name":"status: in review"},{"name":"status: ready to merge"}]""");
        write("verify.json", verify("Done"));

        Result result = run("status", "42", "Done");

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isZero();
        assertThat(result.restCalls("DELETE")).hasSize(2)
                .anySatisfy(call -> assertThat(call).contains("status%3A%20in%20review"))
                .anySatisfy(call -> assertThat(call).contains("status%3A%20ready%20to%20merge"));
        assertThat(result.restCalls("POST")).isEmpty();
        assertThat(result.stdout()).isEqualTo("#42 → Done\n");
    }

    @Test
    @DisplayName("unblocks only the tickets whose blockers are all closed")
    void shouldUnblockOnlyTicketsWhoseBlockersAreAllClosed() throws Exception {
        write("blocked.json", """
                [{"number":50,"body":"Blocked by #42, #43\\r\\n\\n## Что происходит"},
                 {"number":51,"body":"Blocked by #42, #44"},
                 {"number":52,"body":"Blocked by #7"},
                 {"number":53,"body":"Blocked by #42","pull_request":{"url":"x"}}]""");
        write("issue-42.json", "{\"number\":42,\"state\":\"closed\"}");
        write("issue-43.json", "{\"number\":43,\"state\":\"closed\"}");
        write("issue-44.json", "{\"number\":44,\"state\":\"open\"}");
        lookup(50, "item-50", OPTIONS);
        write("labels-50.json", "[{\"name\":\"status: blocked\"}]");
        write("verify.json", verify("Ready"));

        Result result = run("unblock", "42");

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isZero();
        assertThat(result.stdout()).isEqualTo("#50 → Ready  [status: ready]\n");
        assertThat(result.callsContaining("updateProjectV2ItemFieldValue")).singleElement()
                .satisfies(call -> assertThat(call).contains("item=item-50"));
        assertThat(result.calls()).noneMatch(call -> call.contains("issues/7\t"));
    }

    @Test
    @DisplayName("lists open issues in the named columns across pages, one GraphQL call per page")
    void shouldListOpenIssuesInTheNamedColumnsAcrossPages() throws Exception {
        write("list-first.json", listPage(true, "c1", """
                {"fieldValueByName":{"name":"Backlog"},"content":{"number":10,"title":"ten","state":"OPEN"}},
                {"fieldValueByName":{"name":"Done"},"content":{"number":11,"title":"eleven","state":"OPEN"}},
                {"fieldValueByName":{"name":"Ready"},"content":{"number":12,"title":"twelve","state":"CLOSED"}},
                {"fieldValueByName":{"name":"Ready"},"content":{}},
                {"fieldValueByName":null,"content":{"number":14,"title":"no column","state":"OPEN"}}"""));
        write("list-c1.json", listPage(false, null, """
                {"fieldValueByName":{"name":"Ready"},"content":{"number":13,"title":"thirteen","state":"OPEN"}}"""));

        Result result = run("list", "Backlog", "Ready");

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isZero();
        assertThat(result.stdout()).isEqualTo("#10\tBacklog\tten\n#13\tReady\tthirteen\n");
        assertThat(result.graphqlCalls()).hasSize(2);
    }

    @Test
    @DisplayName("shows an issue through REST, spending no GraphQL")
    void shouldShowAnIssueThroughRest() throws Exception {
        write("issue-42.json", """
                {"number":42,"title":"A title","state":"open","labels":[{"name":"bug"},{"name":"status: ready"}]}""");

        Result result = run("show", "42");

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isZero();
        assertThat(result.stdout()).isEqualTo("#42 A title [OPEN] bug,status: ready\n");
        assertThat(result.graphqlCalls()).isEmpty();
    }

    private void lookup(int issue, String item, String options) throws Exception {
        String nodes = item == null ? "" : "{\"id\":\"" + item + "\",\"project\":{\"number\":16}}";
        write("lookup-" + issue + ".json", """
                {"data":{"repository":{"issue":{"id":"issue-node-%d","projectItems":{"nodes":[
                  {"id":"item-elsewhere","project":{"number":3}}%s%s]}}},
                 "organization":{"projectV2":{"id":"project-16","field":{"id":"field-status","options":%s}}}}}"""
                .formatted(issue, nodes.isEmpty() ? "" : ",", nodes, options));
    }

    private static String verify(String column) {
        return "{\"data\":{\"node\":{\"fieldValueByName\":{\"name\":\"" + column + "\"}}}}";
    }

    private static String listPage(boolean hasNextPage, String cursor, String nodes) {
        return """
                {"data":{"organization":{"projectV2":{"items":{
                  "pageInfo":{"hasNextPage":%s,"endCursor":%s},"nodes":[%s]}}}}}"""
                .formatted(hasNextPage, cursor == null ? "null" : "\"" + cursor + "\"", nodes);
    }

    private void write(String name, String content) throws Exception {
        Files.writeString(fixtures.resolve(name), content, StandardCharsets.UTF_8);
    }

    private Result run(String... args) throws Exception {
        Path stdout = work.resolve("stdout-" + System.nanoTime());
        Path stderr = work.resolve("stderr-" + System.nanoTime());
        ProcessBuilder builder = new ProcessBuilder(concat("bash", SCRIPT.toString(), args))
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
            throw new AssertionError("scripts/board.sh did not finish within 30 s");
        }
        List<String> calls = Files.exists(log) ? Files.readAllLines(log, StandardCharsets.UTF_8) : List.of();
        // Every scenario, not one: `gh issue …` and `gh project …` spend GraphQL behind a friendly
        // name, and the script must reach GitHub only through `gh api`, where the cost is visible.
        assertThat(calls).as("gh calls other than 'gh api': %s", calls).allMatch(call -> call.startsWith("api\t"));
        return new Result(process.exitValue(), Files.readString(stdout, StandardCharsets.UTF_8),
                Files.readString(stderr, StandardCharsets.UTF_8), calls);
    }

    private static List<String> concat(String first, String second, String... rest) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(first, second), java.util.Arrays.stream(rest))
                .toList();
    }

    private record Result(int exitCode, String stdout, String stderr, List<String> calls) {

        List<String> graphqlCalls() {
            return calls.stream().filter(call -> call.startsWith("api\tgraphql\t")).toList();
        }

        List<String> callsContaining(String fragment) {
            return calls.stream().filter(call -> call.contains(fragment)).toList();
        }

        List<String> restCalls(String method) {
            return calls.stream()
                    .filter(call -> call.startsWith("api\t") && !call.startsWith("api\tgraphql\t"))
                    .filter(call -> call.contains("-X\t" + method + "\t"))
                    .toList();
        }
    }
}
