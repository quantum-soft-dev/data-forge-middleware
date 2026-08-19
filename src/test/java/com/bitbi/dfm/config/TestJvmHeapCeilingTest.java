package com.bitbi.dfm.config;

import com.bitbi.dfm.testsupport.RunOwnedScratch;
import com.bitbi.dfm.testsupport.TestJvmHeap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #207 — every Gradle {@code Test} task must declare the heap it runs on.
 *
 * <p>Gradle's default test-JVM heap is 512 MB. CI runs the whole suite in one such JVM
 * ({@code ./gradlew test --no-daemon}, ~2400 tests, one cached Spring context per class variant
 * held for the length of the run), so the margin was whatever the runner happened to leave — which
 * is why it failed intermittently and named a different, innocent test each time.</p>
 *
 * <p>This is the static half of the guard, on the fast per-task gate: it fails the day the ceiling
 * is dropped, moved to a narrower task that leaves its siblings on the 512 MB default, or set
 * outside the agreed range. The other half is {@code TestJvmOutOfMemoryExitTest}, which proves
 * what the JVM does when the ceiling is reached anyway — the two things a build file cannot
 * show.</p>
 *
 * <p>It lives beside {@link ParquetScratchTestProfileTest} and {@code LockWaitBoundTestProfileTest}
 * because its subject is how this repository's test run is configured rather than any production
 * behaviour, and it reads the agreed bounds from the shared
 * {@link TestJvmHeap} rather than carrying a second idea of them.</p>
 */
@DisplayName("Test JVM heap ceiling (#207)")
class TestJvmHeapCeilingTest {

    @Test
    @DisplayName("exactly one heap ceiling is declared, and it binds every Test task")
    void shouldDeclareOneHeapCeilingForEveryTestTask() {
        String script = TestJvmHeap.buildScript();

        assertEquals(1, TestJvmHeap.declaredHeapSizes(script).size(),
                "build.gradle.kts declares maxHeapSize "
                        + TestJvmHeap.declaredHeapSizes(script).size() + " times "
                        + TestJvmHeap.declaredHeapSizes(script)
                        + ". Exactly one is required: none leaves every test JVM on Gradle's 512 MB "
                        + "default, and a second one on a narrower task ("
                        + "tasks.named<Test>(\"test\"), integrationTest) wins for that task while "
                        + "leaving its sibling on the default (#207)");

        String block = TestJvmHeap.allTestTasksBlock(script);
        assertNotNull(block, "build.gradle.kts has no " + TestJvmHeap.ALL_TEST_TASKS + " block");
        assertTrue(block.contains("maxHeapSize"),
                "maxHeapSize is declared outside " + TestJvmHeap.ALL_TEST_TASKS + ", so it does not "
                        + "bind every Test task — `test`, `integrationTest` and anything added later "
                        + "must all get the ceiling (#207)");
    }

    @Test
    @DisplayName("the declared ceiling is inside the agreed range")
    void shouldDeclareACeilingInsideTheAgreedRange() {
        long declared = declaredCeiling();

        assertTrue(declared >= TestJvmHeap.MIN_HEAP_BYTES,
                "maxHeapSize is " + TestJvmHeap.describe(declared) + ", below the agreed floor of "
                        + TestJvmHeap.describe(TestJvmHeap.MIN_HEAP_BYTES) + ". A full `./gradlew test` "
                        + "run holds a peak live set around " + TestJvmHeap.MEASURED_PEAK_LIVE_SET_MB
                        + " MB, and the rest is the allocation headroom that keeps the collector from "
                        + "thrashing right below the ceiling. Re-measure before lowering it: "
                        + "`./gradlew test -PtestHeapLog` (#207)");
        assertTrue(declared <= TestJvmHeap.MAX_HEAP_BYTES,
                "maxHeapSize is " + TestJvmHeap.describe(declared) + ", above the agreed ceiling of "
                        + TestJvmHeap.describe(TestJvmHeap.MAX_HEAP_BYTES) + ". The CI runner has 16 GB "
                        + "shared with the PostgreSQL, Redis and LocalStack service containers, the "
                        + "Gradle build JVM and every Testcontainers image the suite starts; past this "
                        + "the kernel's OOM killer answers before the JVM does, and that failure names "
                        + "nothing at all (#207)");
    }

    @Test
    @DisplayName("the OOM diagnostics are declared beside the ceiling")
    void shouldDeclareTheOutOfMemoryDiagnostics() {
        String block = TestJvmHeap.allTestTasksBlock(TestJvmHeap.buildScript());
        assertNotNull(block, "build.gradle.kts has no " + TestJvmHeap.ALL_TEST_TASKS + " block");

        assertTrue(block.contains(TestJvmHeap.EXIT_ON_OOM_FLAG),
                TestJvmHeap.EXIT_ON_OOM_FLAG + " is not declared on " + TestJvmHeap.ALL_TEST_TASKS
                        + ". Without it an allocation failure unwinds into whichever caller was on "
                        + "the stack — Spring's ConstructorResolver, reported as a "
                        + "BeanCreationException of an innocent test — instead of ending the JVM that "
                        + "actually ran out (#207)");
        assertTrue(block.contains(TestJvmHeap.HEAP_DUMP_ON_OOM_FLAG),
                TestJvmHeap.HEAP_DUMP_ON_OOM_FLAG + " is not declared on " + TestJvmHeap.ALL_TEST_TASKS
                        + ". The JVM is about to disappear, so the dump is the only evidence left for "
                        + "re-sizing the ceiling (#207)");
    }

    @Test
    @DisplayName("this JVM was actually given the declared ceiling")
    void shouldRunWithTheDeclaredCeiling() {
        long declared = declaredCeiling();
        long actual = Runtime.getRuntime().maxMemory();

        if (System.getProperty(RunOwnedScratch.SCRATCH_ROOT_PROPERTY) == null) {
            // Not a Gradle-launched JVM (an IDE run configuration, typically), so the build file
            // decided nothing here. The floor still has to hold: the suite needs that much heap
            // whoever started it, and an IDE default on any machine that can run Testcontainers
            // clears it. The RunOwnedScratch fallback, for the same reason — a guard that goes red
            // on a developer's run configuration rather than on a regression is worse than none.
            assertTrue(actual >= TestJvmHeap.MIN_HEAP_BYTES,
                    "this JVM was launched outside Gradle with a maximum heap of "
                            + TestJvmHeap.describe(actual) + ", below the "
                            + TestJvmHeap.describe(TestJvmHeap.MIN_HEAP_BYTES) + " the suite needs. "
                            + "Add -Xmx" + TestJvmHeap.describe(declared) + " to the run configuration (#207)");
            return;
        }
        // The JVM keeps one region back from the reported maximum, so this is never an equality.
        assertTrue(actual >= declared - declared / 16,
                "Gradle launched this test JVM with a maximum heap of " + TestJvmHeap.describe(actual)
                        + " where build.gradle.kts declares " + TestJvmHeap.describe(declared)
                        + ". Something outranks the declaration — a -Xmx in the task's own jvmArgs, "
                        + "GRADLE_OPTS or org.gradle.jvmargs reaching the worker — and the ceiling in "
                        + "the build file is then documentation rather than configuration (#207)");
        assertTrue(actual <= declared,
                "Gradle launched this test JVM with a maximum heap of " + TestJvmHeap.describe(actual)
                        + ", above the " + TestJvmHeap.describe(declared) + " build.gradle.kts declares. "
                        + "A later -Xmx is overriding it (#207)");
    }

    /**
     * The one declared ceiling, in bytes, failing by name when there is not exactly one — a raw
     * {@code NoSuchElementException} out of an empty list says nothing about what is wrong.
     */
    private static long declaredCeiling() {
        List<String> declared = TestJvmHeap.declaredHeapSizes(TestJvmHeap.buildScript());
        assertEquals(1, declared.size(),
                "build.gradle.kts must declare maxHeapSize exactly once, on "
                        + TestJvmHeap.ALL_TEST_TASKS + "; found " + declared + " (#207)");
        return TestJvmHeap.parseSize(declared.getFirst());
    }
}
