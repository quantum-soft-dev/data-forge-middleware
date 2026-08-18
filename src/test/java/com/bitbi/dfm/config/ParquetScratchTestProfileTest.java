package com.bitbi.dfm.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.PropertyPlaceholderHelper;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
 * <p>This is the static half of the guard: it fails the day either key is dropped from
 * {@code application-test.yml} or is pointed back outside the build tree. The behavioural half is
 * {@code ParquetScratchSweepIsolationIntegrationTest}, which drives the wired sweeper.</p>
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

    /** Resolves {@code ${key:default}} the way the Spring {@code Environment} would. */
    private static final PropertyPlaceholderHelper PLACEHOLDERS =
            new PropertyPlaceholderHelper("${", "}", ":", false);

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
    @DisplayName("both scratch keys resolve inside this run's build tree")
    void shouldResolveBothScratchDirectoriesInsideTheBuildTree() {
        Map<String, Object> testYaml = testYaml();
        Path buildDirectory = buildDirectory();

        for (String key : new String[]{CHECKPOINT_KEY, BATCH_PARQUET_KEY}) {
            Object declared = testYaml.get(key);
            assertNotNull(declared, key + " is not declared in application-test.yml (#187)");
            Path resolved = resolve(declared.toString());
            assertTrue(resolved.startsWith(buildDirectory),
                    key + " resolves to " + resolved + ", which is outside this run's build tree ("
                            + buildDirectory + "). Scratch written and swept under the test profile has "
                            + "to live somewhere the run owns: a shared directory means one worktree's "
                            + "suite deleting another's files, and `./gradlew clean` no longer removes "
                            + "what a run left behind (#187)");
        }
    }

    /**
     * Resolves the declared value against system properties, which is what the Gradle test tasks
     * feed it with. An unresolvable placeholder with no default fails here rather than reaching a
     * context.
     */
    private static Path resolve(String declared) {
        String expanded = PLACEHOLDERS.replacePlaceholders(declared, System::getProperty);
        return Path.of(expanded).toAbsolutePath().normalize();
    }

    /**
     * This run's build directory, derived from where the test classes were compiled to
     * ({@code <build>/classes/java/test}) rather than from {@code user.dir} — the working
     * directory is what an IDE run is free to change, and this assertion must not become vacuous
     * when it does.
     */
    private static Path buildDirectory() {
        Path classes;
        try {
            classes = Path.of(ParquetScratchTestProfileTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
        } catch (URISyntaxException e) {
            throw new AssertionError("cannot locate the compiled test classes", e);
        }
        for (Path candidate = classes; candidate != null; candidate = candidate.getParent()) {
            Path name = candidate.getFileName();
            if (name != null && "build".equals(name.toString())) {
                return candidate;
            }
        }
        return fail("no 'build' directory above the compiled test classes at " + classes
                + " — this guard cannot tell whether the scratch directories are inside it");
    }

    private static Map<String, Object> testYaml() {
        Map<String, Object> yaml = ScheduledTaskInventoryTest.optionalYaml("application-test.yml");
        assertNotNull(yaml, "application-test.yml must be on the classpath");
        return yaml;
    }
}
