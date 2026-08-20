package com.bitbi.dfm.config;

import com.bitbi.dfm.testsupport.TestJvmHeap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #207 — what an allocation failure in the test JVM does, proven against a real one.
 *
 * <p>{@link TestJvmHeapCeilingTest} reads what {@code build.gradle.kts} declares; a build file can
 * only ever show that. The reason the flag is there is a property of the JVM: an
 * {@code OutOfMemoryError} is a {@code Throwable} like any other, so without
 * {@value TestJvmHeap#EXIT_ON_OOM_FLAG} it unwinds into whatever caller happens to be on the stack
 * and can be caught, wrapped and re-reported as something else entirely. That is precisely what
 * the run this ticket came from did: Spring's {@code ConstructorResolver} caught it and the build
 * failed as {@code BeanCreationException} in a contract test that allocates nothing.</p>
 *
 * <p>Three child JVMs, one per branch of that claim. The suite's own heap is untouched — the
 * allocation happens in a 32 MB child, and the two "no flag" branches are what makes the third
 * assertion mean anything, since a test that only ever sees the flag set cannot tell a working
 * flag from a JVM that would have died anyway.</p>
 */
@DisplayName("An out-of-memory in the test JVM names itself (#207)")
class TestJvmOutOfMemoryExitTest {

    /** Printed by the child when it caught the error and carried on — the swallow being removed. */
    private static final String SWALLOWED = "SWALLOWED-THE-ERROR";

    /** {@code -XX:+ExitOnOutOfMemoryError} terminates with this status. */
    private static final int EXIT_ON_OOM_STATUS = 3;

    /**
     * How long a child may take. Generous against a 32 MB heap that fills in milliseconds, because
     * the number this bounds is a hang, not the work.
     */
    private static final long CHILD_DEADLINE_SECONDS = 120;

    @Test
    @DisplayName("with the flag, a real allocation failure ends the JVM and says so")
    void shouldEndTheJvmNamingTheOutOfMemory(@TempDir Path dumpDir) throws Exception {
        Outcome outcome = runChild(Allocate.class, dumpDir,
                TestJvmHeap.EXIT_ON_OOM_FLAG,
                TestJvmHeap.HEAP_DUMP_ON_OOM_FLAG,
                "-XX:HeapDumpPath=" + dumpDir);

        assertEquals(EXIT_ON_OOM_STATUS, outcome.status(),
                "the child was expected to terminate on the allocation failure, not to run to "
                        + "completion. Output was:\n" + outcome.output());
        assertTrue(outcome.output().contains("java.lang.OutOfMemoryError"),
                "the child died without naming the cause, so a CI log would still not say what "
                        + "happened. Output was:\n" + outcome.output());
        assertFalse(outcome.output().contains(SWALLOWED),
                "the child caught the error and carried on, which is the failure mode this flag "
                        + "exists to remove. Output was:\n" + outcome.output());
        assertTrue(heapDumps(dumpDir).length > 0,
                "no heap dump was written, so the evidence needed to re-size the ceiling is gone "
                        + "with the JVM. " + TestJvmHeap.HEAP_DUMP_ON_OOM_FLAG + " has to fire before "
                        + TestJvmHeap.EXIT_ON_OOM_FLAG + " terminates the process (#207)");
    }

    @Test
    @DisplayName("without the flag, the same failure is caught and reported as something else")
    void shouldBeSwallowedWithoutTheFlag(@TempDir Path dumpDir) throws Exception {
        Outcome outcome = runChild(Allocate.class, dumpDir);

        assertEquals(0, outcome.status(),
                "the control run is meant to show the JVM surviving its own allocation failure. "
                        + "Output was:\n" + outcome.output());
        assertTrue(outcome.output().contains(SWALLOWED),
                "the control run did not reach the catch, so it proves nothing about what the flag "
                        + "changes. Output was:\n" + outcome.output());
    }

    @Test
    @DisplayName("the flag does not fire on an OutOfMemoryError thrown by ordinary code")
    void shouldIgnoreASyntheticOutOfMemoryError(@TempDir Path dumpDir) throws Exception {
        Outcome outcome = runChild(Throw.class, dumpDir, TestJvmHeap.EXIT_ON_OOM_FLAG);

        assertEquals(0, outcome.status(),
                "a `throw new OutOfMemoryError(...)` from ordinary Java code never reaches the VM's "
                        + "allocation-failure path, so the flag must leave it catchable. "
                        + "BatchParquetFinalizationIntegrationTest stubs exactly that to assert the "
                        + "writer propagates it. Output was:\n" + outcome.output());
        assertTrue(outcome.output().contains(SWALLOWED),
                "the synthetic error was not catchable in the child. Output was:\n" + outcome.output());
    }

    /**
     * Runs one child JVM to completion, or kills it and fails when it outstays the deadline.
     *
     * <p>The output goes to a file rather than through a pipe, so the deadline is the only thing
     * bounding this method. Reading a pipe to EOF first would make the timeout unreachable — a
     * child that hangs (a stalled heap dump, a loaded runner) would park the JUnit thread for ever
     * and stop the run without naming a test, which is the failure mode #197 exists to keep out
     * and would be a poor way for the class about naming failures to fail.</p>
     *
     * @param main    class whose {@code main} the child runs
     * @param workDir working directory, so nothing the child writes lands in the checkout
     * @param jvmArgs flags under test
     * @return exit status and combined output
     */
    private static Outcome runChild(Class<?> main, Path workDir, String... jvmArgs) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xms32m");
        command.add("-Xmx32m");
        command.addAll(List.of(jvmArgs));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(main.getName());

        Path log = workDir.resolve("child-" + main.getSimpleName() + ".out");
        Process child = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!child.waitFor(CHILD_DEADLINE_SECONDS, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            child.waitFor(CHILD_DEADLINE_SECONDS, TimeUnit.SECONDS);
            throw new AssertionError("the child JVM did not finish within " + CHILD_DEADLINE_SECONDS
                    + "s and was killed; command was " + command + ", output so far was:\n"
                    + read(log));
        }
        return new Outcome(child.exitValue(), read(log));
    }

    /** The child's output, or a note in its place — a missing file must not mask the real failure. */
    private static String read(Path log) {
        try {
            return Files.exists(log) ? Files.readString(log) : "(the child wrote no output file)";
        } catch (IOException e) {
            return "(the child's output could not be read: " + e + ")";
        }
    }

    private static Path[] heapDumps(Path dumpDir) throws IOException {
        try (Stream<Path> entries = Files.list(dumpDir)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".hprof")).toArray(Path[]::new);
        }
    }

    private record Outcome(int status, String output) {
    }

    /** Child: allocates until the VM refuses, then catches the error the way real code does. */
    public static final class Allocate {
        public static void main(String[] args) {
            List<byte[]> held = new ArrayList<>();
            try {
                while (true) {
                    held.add(new byte[1024 * 1024]);
                }
            } catch (OutOfMemoryError e) {
                held.clear();
                System.out.println(SWALLOWED + " " + e.getClass().getName());
            }
        }
    }

    /** Child: raises the error from ordinary code, the way a Mockito stub does. */
    public static final class Throw {
        public static void main(String[] args) {
            try {
                throw new OutOfMemoryError("row group buffer");
            } catch (OutOfMemoryError e) {
                System.out.println(SWALLOWED + " " + e.getMessage());
            }
        }
    }
}
