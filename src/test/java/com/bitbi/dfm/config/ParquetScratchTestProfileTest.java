package com.bitbi.dfm.config;

import com.bitbi.dfm.testsupport.RunOwnedScratch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Issue #187 — under the {@code test} profile both Parquet scratch directories must name a
 * directory this run owns, not the machine-wide {@code java.io.tmpdir}.
 *
 * <p>Undeclared, {@code delta.checkpoint.temp-dir} and {@code delta.batch-parquet.temp-dir} fall
 * back to {@code ${java.io.tmpdir}}, so every cached Spring context boots a
 * {@code ParquetScratchOrphanSweeper} aimed at the host's temp directory — and that sweeper
 * deletes any regular file named {@code checkpoint-*} or {@code batch-parquet-*} older than four
 * hours, whoever wrote it. The suite was both the victim (#168) and the perpetrator.</p>
 *
 * <p>This is the static half of the guard, on the fast per-task gate: it fails the day either key
 * is dropped from {@code application-test.yml} or is pointed back outside the build tree. The
 * wired half is {@code ParquetScratchSweepIsolationIntegrationTest}, which is also the only one
 * that can see an {@code OS} environment override of these same keys.</p>
 *
 * <p>It lives beside {@link ScheduledTaskTestProfileCadenceTest} rather than with the delta tests
 * because its subject is the {@code test} profile itself, and it reads the profile through that
 * class's package-private YAML helper instead of growing a second parser that could disagree
 * with it.</p>
 */
@DisplayName("Parquet scratch directories under the test profile (#187)")
class ParquetScratchTestProfileTest {

    private static final String CHECKPOINT_KEY = "delta.checkpoint.temp-dir";

    private static final String BATCH_PARQUET_KEY = "delta.batch-parquet.temp-dir";

    @Test
    @DisplayName("both scratch keys are declared, so no context inherits java.io.tmpdir")
    void shouldDeclareBothScratchDirectories() {
        Map<String, Object> testYaml = testYaml();

        assertNotNull(testYaml.get(CHECKPOINT_KEY),
                CHECKPOINT_KEY + " is not declared in application-test.yml, so every cached context "
                        + "falls back to ${java.io.tmpdir} and sweeps the host's temp directory (#187)");
        assertNotNull(testYaml.get(BATCH_PARQUET_KEY),
                BATCH_PARQUET_KEY + " is not declared in application-test.yml, so every cached context "
                        + "falls back to ${java.io.tmpdir} and sweeps the host's temp directory (#187)");
    }

    @Test
    @DisplayName("both scratch keys resolve inside this run's own tree")
    void shouldResolveBothScratchDirectoriesInsideThisRunsTree() {
        Map<String, Object> testYaml = testYaml();

        RunOwnedScratch.assertOwnedByThisRun(CHECKPOINT_KEY, declared(testYaml, CHECKPOINT_KEY));
        RunOwnedScratch.assertOwnedByThisRun(BATCH_PARQUET_KEY, declared(testYaml, BATCH_PARQUET_KEY));
    }

    @Test
    @DisplayName("the two writers get directories of their own")
    void shouldKeepTheTwoWritersApart() {
        Map<String, Object> testYaml = testYaml();

        assertNotEquals(declared(testYaml, CHECKPOINT_KEY), declared(testYaml, BATCH_PARQUET_KEY),
                "the checkpoint and completed-batch writers must not share a scratch directory: a "
                        + "queue drain would then be free to leave a file where a checkpoint "
                        + "assertion requires an empty directory, which is the split "
                        + "CheckpointParquetIntegrationTest already makes for its own context (#168)");
    }

    private static Path declared(Map<String, Object> testYaml, String key) {
        Object declared = testYaml.get(key);
        assertNotNull(declared, key + " is not declared in application-test.yml (#187)");
        return RunOwnedScratch.resolveDeclared(declared.toString());
    }

    private static Map<String, Object> testYaml() {
        Map<String, Object> yaml = ScheduledTaskInventoryTest.optionalYaml("application-test.yml");
        assertNotNull(yaml, "application-test.yml must be on the classpath");
        return yaml;
    }
}
