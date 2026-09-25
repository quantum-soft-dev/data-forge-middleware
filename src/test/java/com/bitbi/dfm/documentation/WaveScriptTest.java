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
 * {@code scripts/wave.sh} is the arithmetic of {@code /wave}: which tickets may run side by side, how
 * many slots a wave has, and whether the board still says what the coordinator recorded. It decides
 * what three sessions do at once, so a wrong answer is two branches both taking {@code V60} or both
 * editing {@code CheckpointService.java} — collisions git merges cleanly and CI does not see.
 *
 * <p>The script runs against the same stand-in {@code gh} as {@link BoardScriptTest}; the journal lives
 * in a temporary directory. What is pinned: the pool and its order (priority label, then size, then
 * number), the ceiling of three, the overlap keys read from the «Что тронет» section (files, and the
 * {@code flyway}/{@code specs}/{@code proto}/{@code stand} collisions), the slot arithmetic of the
 * refill, and the verify that accepts {@code status: ready to merge} as the {@code In Review} column.
 */
@DisplayName("Wave script")
class WaveScriptTest {

    private static final Path SCRIPT = Path.of("scripts/wave.sh");
    private static final Path FAKE_GH = Path.of("src/test/resources/process/fake-gh");

    @TempDir
    Path work;

    private Path fixtures;
    private Path log;
    private Path bin;
    private Path waveDir;

    @BeforeEach
    void installFakeGh() throws Exception {
        fixtures = Files.createDirectories(work.resolve("fixtures"));
        bin = Files.createDirectories(work.resolve("bin"));
        waveDir = work.resolve("wave");
        log = work.resolve("gh-calls.log");
        Path gh = bin.resolve("gh");
        Files.copy(FAKE_GH, gh);
        assertThat(gh.toFile().setExecutable(true)).as("fake gh must be executable").isTrue();
    }

    @Test
    @DisplayName("picks by priority, never two tickets sharing a file or a Flyway number, and says why each was skipped")
    void shouldPickByPriorityWithoutOverlaps() throws Exception {
        write("open.json", "[" + String.join(",",
                issue(70, "high, checkpoint", "priority: high", touches("`src/main/java/x/CheckpointService.java`", false)),
                issue(71, "medium, migration", "priority: medium", touches("`src/main/resources/db/migration/V60__a.sql`", true)),
                issue(72, "high, same file", "priority: high", touches("`src/main/java/y/CheckpointService.java`", false)),
                issue(73, "low, second migration", "priority: low", touches("`src/main/java/Other.java`", true)),
                issue(74, "blocked", "priority: high", "Blocked by #99\\n" + touches("`a/B.java`", false)),
                issue(75, "inbox", "findings-inbox", touches("`a/C.java`", false)),
                issue(76, "low, docs only", "priority: low", touches("`docs/guide.md`, `CLAUDE.md`, `deploy/gke/README.md`, `a/D.java`", false)),
                "{\"number\":99,\"title\":\"blocker\",\"body\":\"\",\"labels\":[]}") + "]");
        board(card(70, "Backlog"), card(71, "Backlog"), card(72, "Backlog"), card(73, "Backlog"),
                card(74, "Backlog"), card(75, "Backlog"), card(76, "Backlog"), card(99, "Blocked"));

        Result result = run("plan");

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isZero();
        assertThat(result.picks()).containsExactly("#70", "#71", "#76");
        assertThat(result.stdout())
                .contains("SKIP\t#72\tпересечение с задачей волны: CheckpointService.java")
                .contains("SKIP\t#73\tпересечение с задачей волны: flyway")
                .contains("SKIP\t#74\tоткрыт Blocked by #99")
                .contains("SKIP\t#75\tвходящие находки");
        assertThat(result.line("PICK\t#71")).contains("flyway").contains("base=develop");
        assertThat(result.line("PICK\t#76")).contains("D.java").doesNotContain("guide.md").doesNotContain("CLAUDE.md").doesNotContain("README.md");
        assertThat(result.graphqlCalls()).as("rateLimit + one page of cards").hasSize(2);
    }

    @Test
    @DisplayName("a ticket in progress holds its keys, and a second Flyway ticket waits even when the first is not in the wave")
    void shouldHoldTheKeysOfWorkAlreadyInProgress() throws Exception {
        write("open.json", "[" + String.join(",",
                issue(80, "in progress", "status: in progress", touches("`frontend/src/App.tsx`", true)),
                issue(81, "migration", "priority: high", touches("`x/Y.java`", true)),
                issue(82, "ui", "priority: high", touches("`frontend/src/pages/Other.tsx`", false)),
                issue(83, "free", "priority: low", touches("`x/Z.java`", false))) + "]");
        board(card(80, "In Progress"), card(81, "Backlog"), card(82, "Backlog"), card(83, "Backlog"));

        Result result = run("plan");

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isZero();
        assertThat(result.picks()).containsExactly("#83");
        assertThat(result.stdout())
                .contains("SKIP\t#81\tпересечение с задачей в работе: flyway")
                .contains("SKIP\t#82\tпересечение с задачей в работе: stand");
    }

    @Test
    @DisplayName("tickets without «Что тронет» never share a wave, and a refused base keeps a ticket out")
    void shouldSerializeTicketsWithoutAFileListAndRefuseAnUnreadableBase() throws Exception {
        write("open.json", "[" + String.join(",",
                issue(90, "old one", "priority: high", "no section here"),
                issue(91, "old two", "priority: high", "nor here"),
                issue(92, "bad base", "priority: high", "base branch: `migration/x`\\n" + touches("`a/Q.java`", false))) + "]");
        board(card(90, "Backlog"), card(91, "Backlog"), card(92, "Backlog"));

        Result result = run("plan");

        assertThat(result.picks()).containsExactly("#90");
        assertThat(result.line("PICK\t#90")).contains("files?");
        assertThat(result.stdout()).contains("SKIP\t#91\t").contains("SKIP\t#92\tбаза не читается");
    }

    @Test
    @DisplayName("a ceiling of three whatever is asked, and no plan below the budget threshold")
    void shouldCapTheWaveAtThreeAndStopBelowTheBudget() throws Exception {
        write("open.json", "[]");
        board();

        Result four = run("plan", "4");
        write("budget.txt", "999");
        Result poor = run("plan");

        assertThat(four.stdout()).contains("шире 3").contains("ВЫБОР (0 из 3)");
        assertThat(poor.exitCode()).isEqualTo(4);
        assertThat(poor.stdout()).contains("мало GraphQL-очков");
    }

    @Test
    @DisplayName("the journal: one open wave at a time, refill counts slots, add refuses overflow and repeats")
    void shouldKeepTheJournalHonest() throws Exception {
        assertThat(run("begin", "--size", "2", "70", "71").exitCode()).isZero();
        assertThat(run("begin", "72").stderr()).contains("незакрытая волна");
        assertThat(run("plan").exitCode()).isEqualTo(3);

        run("mark", "70", "In Progress", "-", "agent=a1");
        run("mark", "71", "Done", "301");
        Result refill = run("refill");
        assertThat(refill.stdout()).contains("в работе 1, свободных слотов 1");

        assertThat(run("add", "71").stderr()).contains("уже в журнале");
        assertThat(run("add", "72", "73").stderr()).contains("больше свободных слотов");
        assertThat(run("add", "72").exitCode()).isZero();
        assertThat(run("refill").stdout()).contains("Добора нет: свободных слотов нет.");

        Result status = run("status");
        assertThat(status.stdout()).contains("#70\tIn Progress\tPR -\tagent=a1")
                .contains("#71\tDone\tPR 301").contains("#72\tReady\tPR -\tдобор");
        assertThat(run("end").stdout()).contains("волна закрыта (done)");
        assertThat(run("status").stdout()).contains("открытой волны нет");
    }

    @Test
    @DisplayName("verify accepts ready-to-merge as In Review and names every mismatch")
    void shouldVerifyTheJournalAgainstTheBoard() throws Exception {
        run("begin", "70", "71", "72");
        run("mark", "70", "In Review", "301");
        run("mark", "71", "Done", "302");
        run("mark", "72", "In Progress");
        board(card(70, "In Review"), card(71, "In Review"), card(72, "In Progress"));
        write("issue-70.json", "{\"state\":\"open\",\"assignees\":[{}],\"labels\":[{\"name\":\"status: ready to merge\"}]}");
        write("issue-71.json", "{\"state\":\"closed\",\"assignees\":[{}],\"labels\":[]}");
        write("issue-72.json", "{\"state\":\"open\",\"assignees\":[],\"labels\":[{\"name\":\"status: in progress\"}]}");
        write("pull-301.json", "{\"state\":\"open\",\"merged\":false}");
        write("pull-302.json", "{\"state\":\"closed\",\"merged\":true}");

        Result result = run("verify");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).contains("OK\t#70\tIn Review")
                .contains("MISMATCH\t#71\tколонка In Review, ожидалась Done")
                .contains("MISMATCH\t#72\tнет исполнителя");
    }

    private static String issue(int number, String title, String label, String body) {
        String labels = label.isEmpty() ? "" : "{\"name\":\"" + label + "\"}";
        return """
                {"number":%d,"title":"%s","body":"%s","labels":[%s],"assignees":[]}"""
                .formatted(number, title, body.replace("\"", "\\\""), labels);
    }

    private static String touches(String files, boolean flyway) {
        return "### Что тронет\\n\\n**Файлы:** " + files + "\\n\\n**Flyway-миграция:** " + (flyway ? "да" : "нет")
                + "\\n**Каталог `specs/NNN-*`:** нет\\n**`delta-ingestion.proto`:** нет\\n\\n### Открытые тикеты в тех же файлах\\n\\nnone found";
    }

    private static String card(int number, String column) {
        return """
                {"fieldValueByName":{"name":"%s"},"content":{"number":%d,"title":"t%d","state":"%s"}}"""
                .formatted(column, number, number, "Done".equals(column) ? "CLOSED" : "OPEN");
    }

    private void board(String... cards) throws Exception {
        write("list-first.json", """
                {"data":{"organization":{"projectV2":{"items":{
                  "pageInfo":{"hasNextPage":false,"endCursor":null},"nodes":[%s]}}}}}"""
                .formatted(String.join(",", cards)));
    }

    private void write(String name, String content) throws Exception {
        Files.writeString(fixtures.resolve(name), content, StandardCharsets.UTF_8);
    }

    private Result run(String... args) throws Exception {
        Files.deleteIfExists(log);
        Path stdout = work.resolve("stdout-" + System.nanoTime());
        Path stderr = work.resolve("stderr-" + System.nanoTime());
        List<String> command = new java.util.ArrayList<>(List.of("bash", SCRIPT.toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile());
        Map<String, String> env = builder.environment();
        // The pre-commit hook exports GIT_DIR/GIT_INDEX_FILE; the script would read the real
        // repository's worktrees instead of none (the #297 hazard IssueFindScriptTest records).
        env.keySet().removeIf(key -> key.startsWith("GIT_"));
        env.put("DFM_MAIN_ROOT", work.resolve("not-a-repository").toString());
        env.put("DFM_WAVE_DIR", waveDir.toString());
        env.put("BOARD_GIT_ROOT", work.resolve("not-a-repository").toString());
        env.put("PATH", bin + File.pathSeparator + env.getOrDefault("PATH", "/usr/bin:/bin"));
        env.put("FAKE_GH_DIR", fixtures.toString());
        env.put("FAKE_GH_LOG", log.toString());
        Process process = builder.start();
        process.getOutputStream().close();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("scripts/wave.sh did not finish within 60 s");
        }
        List<String> calls = Files.exists(log) ? Files.readAllLines(log, StandardCharsets.UTF_8) : List.of();
        assertThat(calls).as("gh calls other than 'gh api': %s", calls).allMatch(call -> call.startsWith("api\t"));
        return new Result(process.exitValue(), Files.readString(stdout, StandardCharsets.UTF_8),
                Files.readString(stderr, StandardCharsets.UTF_8), calls);
    }

    private record Result(int exitCode, String stdout, String stderr, List<String> calls) {

        List<String> picks() {
            return stdout.lines().filter(line -> line.startsWith("PICK\t")).map(line -> line.split("\t")[1]).toList();
        }

        String line(String prefix) {
            return stdout.lines().filter(line -> line.startsWith(prefix)).findFirst().orElse("");
        }

        List<String> graphqlCalls() {
            return calls.stream().filter(call -> call.startsWith("api\tgraphql\t")).toList();
        }
    }
}
