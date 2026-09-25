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

    @Test
    @DisplayName("lists every card, closed and column-less included, one GraphQL call per page")
    void shouldListEveryCardAcrossPages() throws Exception {
        write("list-first.json", listPage(true, "c1", """
                {"fieldValueByName":{"name":"Done"},"content":{"number":11,"title":"eleven","state":"CLOSED"}},
                {"fieldValueByName":null,"content":{"number":14,"title":"no column","state":"OPEN"}},
                {"fieldValueByName":{"name":"Ready"},"content":{}}"""));
        write("list-c1.json", listPage(false, null, """
                {"fieldValueByName":{"name":"Ready"},"content":{"number":13,"title":"thirteen","state":"OPEN"}}"""));

        Result result = run("items");

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isZero();
        assertThat(result.stdout()).isEqualTo("#11\tDone\tCLOSED\televen\n#14\t-\tOPEN\tno column\n#13\tReady\tOPEN\tthirteen\n");
        assertThat(result.graphqlCalls()).hasSize(2);
    }

    @Test
    @DisplayName("sets Size with three GraphQL calls and touches no label")
    void shouldSetSizeWithoutTouchingLabels() throws Exception {
        lookup(42, "item-42", """
                [{"id":"opt-xs","name":"XS"},{"id":"opt-s","name":"S"},{"id":"opt-m","name":"M"}]""");
        write("verify.json", verify("S"));

        Result result = run("field", "42", "Size", "S");

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isZero();
        assertThat(result.stdout()).isEqualTo("#42 Size → S\n");
        assertThat(result.graphqlCalls()).hasSize(3);
        assertThat(result.callsContaining("fieldName=Size")).isNotEmpty();
        assertThat(result.callsContaining("updateProjectV2ItemFieldValue")).singleElement()
                .satisfies(call -> assertThat(call).contains("option=opt-s"));
        assertThat(result.calls()).noneMatch(call -> call.contains("/labels"));
    }

    @Test
    @DisplayName("field refuses Status and Priority before any call — both have their own path")
    void shouldRefuseStatusAndPriorityAsFields() throws Exception {
        Result status = run("field", "42", "Status", "Done");
        Result priority = run("field", "42", "Priority", "P1");

        assertThat(status.exitCode()).isNotZero();
        assertThat(status.stderr()).contains("board.sh status");
        assertThat(priority.exitCode()).isNotZero();
        assertThat(priority.stderr()).contains("priority:");
        assertThat(priority.calls()).isEmpty();
    }

    @Test
    @DisplayName("sweep reports every kind of drift, reads ready-to-merge as In Review, and changes nothing")
    void shouldReportDriftWithoutChangingAnything() throws Exception {
        write("open.json", """
                [{"number":60,"title":"stale","body":"Blocked by #40","assignees":[],"labels":[{"name":"status: blocked"}]},
                 {"number":61,"title":"manual","body":"no deps","assignees":[],"labels":[{"name":"status: blocked"}]},
                 {"number":62,"title":"drifted","body":"","assignees":[],"labels":[{"name":"status: ready"}]},
                 {"number":63,"title":"awaiting merge","body":"","assignees":[{"login":"a"}],"labels":[{"name":"status: ready to merge"}]},
                 {"number":64,"title":"abandoned","body":"","assignees":[],"labels":[{"name":"status: in progress"}]},
                 {"number":65,"title":"backlog","body":"","assignees":[],"labels":[]},
                 {"number":66,"title":"a PR","pull_request":{"url":"x"},"labels":[{"name":"status: ready"}]}]""");
        write("closed.json", """
                [{"number":40,"labels":[]},
                 {"number":41,"labels":[{"name":"status: in review"}]},
                 {"number":42,"labels":[{"name":"status: ready"}]}]""");
        write("list-first.json", listPage(false, null, """
                {"fieldValueByName":{"name":"Blocked"},"content":{"number":60,"title":"stale","state":"OPEN"}},
                {"fieldValueByName":{"name":"Blocked"},"content":{"number":61,"title":"manual","state":"OPEN"}},
                {"fieldValueByName":{"name":"Backlog"},"content":{"number":62,"title":"drifted","state":"OPEN"}},
                {"fieldValueByName":{"name":"In Review"},"content":{"number":63,"title":"awaiting merge","state":"OPEN"}},
                {"fieldValueByName":{"name":"In Progress"},"content":{"number":64,"title":"abandoned","state":"OPEN"}},
                {"fieldValueByName":{"name":"Backlog"},"content":{"number":65,"title":"backlog","state":"OPEN"}},
                {"fieldValueByName":{"name":"Done"},"content":{"number":40,"title":"done","state":"CLOSED"}},
                {"fieldValueByName":{"name":"In Review"},"content":{"number":41,"title":"closed late","state":"CLOSED"}}"""));

        Result result = run("sweep");

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isZero();
        assertThat(result.stdout())
                .contains("STALE_BLOCK\t#60\t")
                .contains("MANUAL_BLOCK\t#61\t")
                .contains("COLUMN\t#62\tколонка Backlog ≠ метка [status: ready]\tReady")
                .contains("ABANDONED\t#64\t")
                .contains("CLOSED\t#41\t").contains("\tDone\n")
                .contains("CLOSED\t#42\tзакрыта, колонка нет на доске, метки [status: ready]\tснять метки")
                .doesNotContain("#63").doesNotContain("#65").doesNotContain("#66").doesNotContain("#40\t");
        assertThat(result.callsContaining("mutation")).isEmpty();
        assertThat(result.restCalls("DELETE")).isEmpty();
        assertThat(result.restCalls("POST")).isEmpty();
        assertThat(result.graphqlCalls()).hasSize(1);
    }

    @Test
    @DisplayName("sweep --fix repairs a column by its label and skips a ticket that moved since the snapshot")
    void shouldFixByLabelAndSkipWhatChanged() throws Exception {
        write("open.json", """
                [{"number":62,"title":"drifted","body":"","assignees":[],"labels":[{"name":"status: ready"}]},
                 {"number":67,"title":"moved meanwhile","body":"","assignees":[],"labels":[{"name":"status: ready"}]}]""");
        write("list-first.json", listPage(false, null, """
                {"fieldValueByName":{"name":"Backlog"},"content":{"number":62,"title":"drifted","state":"OPEN"}},
                {"fieldValueByName":{"name":"Backlog"},"content":{"number":67,"title":"moved meanwhile","state":"OPEN"}}"""));
        write("issue-62.json", "{\"number\":62,\"state\":\"open\",\"labels\":[{\"name\":\"status: ready\"}]}");
        write("issue-67.json", "{\"number\":67,\"state\":\"open\",\"labels\":[{\"name\":\"status: in progress\"}]}");
        lookup(62, "item-62", OPTIONS);
        write("labels-62.json", "[{\"name\":\"status: ready\"}]");
        write("verify.json", verify("Ready"));

        Result result = run("sweep", "--fix");

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isZero();
        assertThat(result.stdout()).contains("#62 → Ready  [status: ready]")
                .contains("#67 пропущена: изменилась после сверки")
                .contains("исправлено 1, пропущено как изменившиеся 1");
        assertThat(result.callsContaining("updateProjectV2ItemFieldValue")).singleElement()
                .satisfies(call -> assertThat(call).contains("item=item-62").contains("option=opt-ready"));
    }

    @Test
    @DisplayName("sweep --fix puts a ready-to-merge ticket in In Review and keeps its label")
    void shouldFixAReadyToMergeColumnWithoutDroppingItsLabel() throws Exception {
        write("open.json", """
                [{"number":68,"title":"awaiting merge","body":"","assignees":[{"login":"a"}],"labels":[{"name":"status: ready to merge"}]}]""");
        write("list-first.json", listPage(false, null, """
                {"fieldValueByName":{"name":"In Progress"},"content":{"number":68,"title":"awaiting merge","state":"OPEN"}}"""));
        write("issue-68.json", "{\"number\":68,\"state\":\"open\",\"labels\":[{\"name\":\"status: ready to merge\"}]}");
        lookup(68, "item-68", OPTIONS);
        write("labels-68.json", "[{\"name\":\"enhancement\"},{\"name\":\"status: ready to merge\"}]");
        write("verify.json", verify("In Review"));

        Result result = run("sweep", "--fix");

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isZero();
        assertThat(result.stdout())
                .contains("COLUMN\t#68\tколонка In Progress ≠ метка [status: ready to merge]\tIn Review")
                .contains("#68 → In Review  [status: ready to merge]")
                .contains("исправлено 1, пропущено как изменившиеся 0");
        assertThat(result.callsContaining("updateProjectV2ItemFieldValue")).singleElement()
                .satisfies(call -> assertThat(call).contains("item=item-68").contains("option=opt-review"));
        assertThat(result.restCalls("DELETE")).as("the ready-to-merge label must stay").isEmpty();
        assertThat(result.restCalls("POST")).singleElement()
                .satisfies(call -> assertThat(call).contains("labels[]=status: ready to merge"));
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
        // The pre-commit hook exports GIT_DIR/GIT_INDEX_FILE; `sweep` would then read the real
        // repository's branches and worktrees (the #297 hazard IssueFindScriptTest records).
        env.keySet().removeIf(key -> key.startsWith("GIT_"));
        env.put("BOARD_GIT_ROOT", work.resolve("not-a-repository").toString());
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
