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
 *
 * <p>{@code health} reads {@code ps} and each process's working directory through its two substitution
 * points ({@code DFM_HEALTH_PS}, {@code DFM_HEALTH_CWD}), so the report runs on a fixture. {@code --kill}
 * is the one branch that acts on the machine, and it is tested on real throwaway {@code sleep}
 * processes whose PIDs the fixture names under the command lines of each kind: a PID taken on trust
 * would let the assertion pass while the script killed nothing, or killed something it must not.
 */
@DisplayName("Wave script")
class WaveScriptTest {

    private static final Path SCRIPT = Path.of("scripts/wave.sh");
    private static final Path FAKE_GH = Path.of("src/test/resources/process/fake-gh");

    // Protected commands that would otherwise read as servers: each names gradle-wrapper.jar or vite.
    private static final String GRADLE_DAEMON =
            "java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.8.0";
    private static final String KOTLIN_DAEMON =
            "java -cp gradle-wrapper.jar org.jetbrains.kotlin.daemon.KotlinCompileDaemon";
    private static final String DOCKER = "docker run --rm node node_modules/.bin/vite";

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
    @DisplayName("two tickets taking a specs/NNN number or a proto field never share a wave")
    void shouldKeepSpecsAndProtoCollisionsApart() throws Exception {
        write("open.json", "[" + String.join(",",
                issue(110, "specs one", "priority: high", touches("`a/One.java`", false, true, false)),
                issue(111, "specs two", "priority: high", touches("`a/Two.java`", false, true, false)),
                issue(112, "proto one", "priority: medium", touches("`a/Three.java`", false, false, true)),
                issue(113, "proto two", "priority: medium", touches("`a/Four.java`", false, false, true)),
                issue(114, "proto by path", "priority: medium",
                        touches("`src/main/proto/delta-ingestion.proto`, `a/Five.java`", false, false, false)),
                issue(115, "free", "priority: low", touches("`a/Six.java`", false, false, false))) + "]");
        board(card(110, "Backlog"), card(111, "Backlog"), card(112, "Backlog"), card(113, "Backlog"),
                card(114, "Backlog"), card(115, "Backlog"));

        Result result = run("plan");

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isZero();
        assertThat(result.picks()).containsExactly("#110", "#112", "#115");
        assertThat(result.line("PICK\t#110")).contains("specs").doesNotContain("flyway").doesNotContain("proto");
        assertThat(result.line("PICK\t#112")).contains("proto").doesNotContain("flyway").doesNotContain("specs");
        assertThat(result.stdout())
                .contains("SKIP\t#111\tпересечение с задачей волны: specs")
                .contains("SKIP\t#113\tпересечение с задачей волны: proto")
                .contains("SKIP\t#114\tпересечение с задачей волны: proto");
    }

    @Test
    @DisplayName("the three flags written on one line raise only the one that says yes")
    void shouldReadFlagsWrittenOnOneLineOneByOne() throws Exception {
        String oneLine = "**Файлы:** `a/Inline.java`\\n**Flyway-миграция:** нет. **Каталог `specs/NNN-*`:** нет."
                + " **`delta-ingestion.proto`:** да";
        write("open.json", "[" + String.join(",",
                issue(120, "migration", "priority: high", touches("`a/Migration.java`", true)),
                issue(121, "one-line flags", "priority: medium", section(oneLine)),
                issue(122, "proto", "priority: low", touches("`a/Proto.java`", false, false, true))) + "]");
        board(card(120, "Backlog"), card(121, "Backlog"), card(122, "Backlog"));

        Result result = run("plan");

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isZero();
        assertThat(result.picks()).as("a false flyway on #121 would put it behind #120").containsExactly("#120", "#121");
        assertThat(result.line("PICK\t#121")).contains("Inline.java,proto")
                .doesNotContain("flyway").doesNotContain("specs").doesNotContain("NNN");
        assertThat(result.stdout()).contains("SKIP\t#122\tпересечение с задачей волны: proto");
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

    @Test
    @DisplayName("health reports a stale wait loop, a stale CI watch and an orphaned server, and nothing younger, foreign or protected")
    void shouldReportStaleProcessesAndStalledTasks() throws Exception {
        Path main = mainCopy();
        Path open = worktree(main, "50-open-task", true);
        worktree(main, "51-closed-task", true);
        worktree(main, "55-old-task", false);
        Path gone = main.resolve(".claude/worktrees/52-gone");
        write("issue-50.json", "{\"state\":\"open\"}");
        write("issue-51.json", "{\"state\":\"closed\"}");
        write("issue-55.json", "{\"state\":\"open\"}");
        // PIDs above every pid_max (99 998 on macOS, 4 194 304 on Linux): a kill fired by mistake hits nothing.
        Processes ps = new Processes()
                .add("5000001", "01:00:00", loop("until ! pgrep -f gradlew; do sleep 30; done"), open)
                .add("5000002", "1-02:00:00", loop("cd " + open + " && while true; do sleep 5; done"), null)
                .add("5000003", "59:59", loop("while true; do sleep 5; done"), open)
                .add("5000004", "03:00:00", loop("while true; do sleep 5; done"), work.resolve("other-project"))
                .add("5000005", "01:30:00", "gh pr checks 361 --watch --interval 60", main)
                .add("5000006", "10:00", "gh pr checks 362 --watch --interval 60", main)
                .add("5000007", "02:00:00", "node node_modules/.bin/vite --port 3000", gone)
                .add("5000008", "02:00:00", "java -jar gradle/wrapper/gradle-wrapper.jar bootRun", main.resolve(".claude/worktrees/51-closed-task/sub"))
                .add("5000009", "02:00:00", "node node_modules/.bin/vite --port 3000", open)
                .add("5000010", "02:00:00", "java -jar build/libs/app.jar spring-boot", main)
                .add("5000011", "02:00:00", GRADLE_DAEMON, gone)
                .add("5000012", "02:00:00", DOCKER, gone);
        Path tasks = Files.createDirectories(work.resolve("tasks"));
        Files.writeString(tasks.resolve("a53.output"), "fresh transcript");
        age(Files.writeString(tasks.resolve("a55.output"), "old transcript"));
        Files.createDirectories(waveDir);
        Files.writeString(waveDir.resolve("current.tsv"), """
                # wave 2026-09-25T00:00:00Z size=3
                50\tIn Progress\t-\tagent=a50
                53\tIn Progress\t-\tagent=a53
                54\tIn Progress\t-\t-
                55\tIn Progress\t-\tagent=a55
                """);

        Result result = run(ps.env(main), "health", "--tasks-dir", tasks.toString());

        assertThat(result.exitCode()).as("stdout: %s%nstderr: %s", result.stdout(), result.stderr()).isEqualTo(1);
        assertThat(reported(result)).as(result.stdout()).containsExactly(
                "STALE_LOOP 5000001", "STALE_LOOP 5000002", "STALE_WATCH 5000005",
                "ORPHAN_SERVER 5000007", "ORPHAN_SERVER 5000008",
                "LEFTOVER_WORKTREE #51", "STALLED #54", "STALLED #55");
        assertThat(result.line("STALE_LOOP\t5000001")).contains("60 мин").contains("pgrep -f");
        assertThat(result.line("STALE_LOOP\t5000002")).contains("1560 мин").doesNotContain("pgrep -f");
        assertThat(result.line("STALE_WATCH\t5000005")).contains("90 мин");
        assertThat(result.line("ORPHAN_SERVER\t5000007")).contains("удалённом worktree #52");
        assertThat(result.line("ORPHAN_SERVER\t5000008")).contains("закрытой issue #51");
    }

    @Test
    @DisplayName("health --kill ends only stale loops, stale watches and orphaned servers — never the main copy, docker or a Gradle daemon")
    void shouldKillOnlyTheThreeKillableKinds() throws Exception {
        Path main = mainCopy();
        Path open = worktree(main, "50-open-task", true);
        Path gone = main.resolve(".claude/worktrees/52-gone");
        worktree(main, "51-closed-task", true);
        write("issue-50.json", "{\"state\":\"open\"}");
        write("issue-51.json", "{\"state\":\"closed\"}");
        Files.createDirectories(waveDir);
        Files.writeString(waveDir.resolve("current.tsv"), "# wave 2026-09-25T00:00:00Z size=3\n54\tIn Progress\t-\t-\n");
        List<Process> doomed = new java.util.ArrayList<>();
        List<Process> spared = new java.util.ArrayList<>();
        try {
            Processes ps = new Processes()
                    .add(spawn(doomed), "02:00:00", loop("while true; do sleep 5; done"), open)
                    .add(spawn(doomed), "02:00:00", "gh pr checks 361 --watch --interval 60", main)
                    .add(spawn(doomed), "02:00:00", "node node_modules/.bin/vite --port 3000", gone)
                    .add(spawn(spared), "10:00", loop("while true; do sleep 5; done"), open)
                    .add(spawn(spared), "02:00:00", "node node_modules/.bin/vite --port 3000", main)
                    .add(spawn(spared), "02:00:00", "java -jar gradle/wrapper/gradle-wrapper.jar bootRun", main)
                    .add(spawn(spared), "02:00:00", "node node_modules/.bin/vite --port 3000", open)
                    .add(spawn(spared), "02:00:00", GRADLE_DAEMON, gone)
                    .add(spawn(spared), "02:00:00", KOTLIN_DAEMON, gone)
                    .add(spawn(spared), "02:00:00", DOCKER, gone);

            Result result = run(ps.env(main), "health", "--kill");

            assertThat(result.exitCode()).as("the leftover worktree is still a finding: %s", result.stdout()).isEqualTo(1);
            assertThat(reported(result)).as("findings with no process are reported, not killed: %s", result.stdout())
                    .containsExactly("KILLED " + doomed.get(0).pid(), "KILLED " + doomed.get(1).pid(),
                            "KILLED " + doomed.get(2).pid(), "LEFTOVER_WORKTREE #51", "STALLED #54");
            assertThat(result.line("KILLED\t" + doomed.get(0).pid())).contains("STALE_LOOP");
            assertThat(result.line("KILLED\t" + doomed.get(1).pid())).contains("STALE_WATCH");
            assertThat(result.line("KILLED\t" + doomed.get(2).pid())).contains("ORPHAN_SERVER");
            for (Process process : doomed) {
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("pid %d was to be killed", process.pid()).isTrue();
            }
            for (Process process : spared) {
                assertThat(process.isAlive()).as("pid %d must survive --kill", process.pid()).isTrue();
            }
        } finally {
            doomed.forEach(Process::destroyForcibly);
            spared.forEach(Process::destroyForcibly);
        }
    }

    /** A Claude background shell: the snapshot it sources is what marks it as one of ours to watch. */
    private static String loop(String script) {
        return "/bin/zsh -c source /home/u/.claude/shell-snapshots/snapshot-zsh-1.sh && eval '" + script + "'";
    }

    private Path mainCopy() throws Exception {
        return Files.createDirectories(work.resolve("main"));
    }

    private static Path worktree(Path main, String name, boolean fresh) throws Exception {
        Path dir = Files.createDirectories(main.resolve(".claude/worktrees").resolve(name));
        Path file = Files.writeString(dir.resolve("Edited.java"), "class Edited {}");
        if (!fresh) {
            age(file);
        }
        return dir;
    }

    private static Path age(Path file) throws Exception {
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minus(java.time.Duration.ofHours(3))));
        return file;
    }

    /** A throwaway process the fixture can name: the kill test must see real PIDs die, not trust a log line. */
    private static String spawn(List<Process> into) throws Exception {
        Process process = new ProcessBuilder("sleep", "300").start();
        into.add(process);
        return Long.toString(process.pid());
    }

    /** Report lines as "KIND id", in output order, without the trailing OK line. */
    private static List<String> reported(Result result) {
        return result.stdout().lines().filter(line -> !line.startsWith("OK\t"))
                .map(line -> line.split("\t"))
                .filter(fields -> fields.length >= 2)
                .map(fields -> fields[0] + " " + fields[1])
                .toList();
    }

    /** The {@code ps} and {@code lsof} answers health reads, as files behind its two substitution points. */
    private final class Processes {

        private final StringBuilder ps = new StringBuilder();
        private final StringBuilder cwd = new StringBuilder();

        Processes add(String pid, String etime, String command, Path dir) {
            ps.append(pid).append(" 1 ").append(etime).append(' ').append(command).append('\n');
            if (dir != null) {
                cwd.append(pid).append('\t').append(dir).append('\n');
            }
            return this;
        }

        Map<String, String> env(Path main) throws Exception {
            Path psFile = Files.writeString(work.resolve("ps.txt"), ps);
            Path cwdFile = Files.writeString(work.resolve("cwd.tsv"), cwd);
            return Map.of("DFM_MAIN_ROOT", main.toString(),
                    "DFM_HEALTH_PS", "cat '" + psFile + "'",
                    "DFM_HEALTH_CWD", cwdFile.toString());
        }
    }

    private static String issue(int number, String title, String label, String body) {
        String labels = label.isEmpty() ? "" : "{\"name\":\"" + label + "\"}";
        return """
                {"number":%d,"title":"%s","body":"%s","labels":[%s],"assignees":[]}"""
                .formatted(number, title, body.replace("\"", "\\\""), labels);
    }

    private static String touches(String files, boolean flyway) {
        return touches(files, flyway, false, false);
    }

    private static String touches(String files, boolean flyway, boolean specs, boolean proto) {
        return section("**Файлы:** " + files + "\\n\\n**Flyway-миграция:** " + yesNo(flyway)
                + "\\n**Каталог `specs/NNN-*`:** " + yesNo(specs) + "\\n**`delta-ingestion.proto`:** " + yesNo(proto));
    }

    private static String section(String content) {
        return "### Что тронет\\n\\n" + content + "\\n\\n### Открытые тикеты в тех же файлах\\n\\nnone found";
    }

    private static String yesNo(boolean yes) {
        return yes ? "да" : "нет";
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
        return run(Map.of(), args);
    }

    private Result run(Map<String, String> extraEnv, String... args) throws Exception {
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
        env.putAll(extraEnv);
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
