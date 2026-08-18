package com.bitbi.dfm.testsupport;

import org.springframework.util.PropertyPlaceholderHelper;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One definition of "a directory this test run owns" (issue #187).
 *
 * <p>Under the {@code test} profile the file-backed Parquet scratch must live inside the tree this
 * run owns, because {@code ParquetScratchOrphanSweeper} boots in every cached Spring context and
 * deletes {@code checkpoint-*} / {@code batch-parquet-*} files by age — on the machine-wide
 * {@code java.io.tmpdir} that means another worktree's suite, or anything else on the host using
 * those prefixes.</p>
 *
 * <p>Two guards need the claim: the static one over {@code application-test.yml} and the wired one
 * inside a Spring context, which is the only place an {@code OS} environment override
 * ({@code DELTA_CHECKPOINT_TEMP_DIR}, ranked above every {@code application*.yml}) is visible.
 * They share this class rather than each carrying its own idea of where the run's tree is.</p>
 */
public final class RunOwnedScratch {

    /**
     * System property every Gradle {@code Test} task sets to the absolute
     * {@code <build>/test-scratch/parquet}. Absent only when the suite is launched by something
     * that is not the Gradle build — an IDE run configuration, typically.
     */
    public static final String SCRATCH_ROOT_PROPERTY = "dfm.test.parquet-scratch-root";

    /** Resolves {@code ${key:default}} the way the Spring {@code Environment} would. */
    private static final PropertyPlaceholderHelper PLACEHOLDERS =
            new PropertyPlaceholderHelper("${", "}", ":", false);

    private RunOwnedScratch() {
    }

    /**
     * Expands a value declared in {@code application-test.yml} against system properties and
     * normalizes it. A placeholder with neither a value nor a default fails here rather than in a
     * context.
     *
     * @param declared raw YAML value
     * @return absolute, normalized path
     */
    public static Path resolveDeclared(String declared) {
        return normalize(PLACEHOLDERS.replacePlaceholders(declared, System::getProperty));
    }

    /**
     * Absolute, normalized form of a path as the sweeper resolves it.
     *
     * @param value configured directory
     * @return absolute, normalized path
     */
    public static Path normalize(String value) {
        return Path.of(value).toAbsolutePath().normalize();
    }

    /**
     * Fails unless {@code resolved} is inside this run's tree and outside the machine-wide temp
     * directory.
     *
     * @param key      configuration key, for the failure message
     * @param resolved absolute directory the key resolves to
     */
    public static void assertOwnedByThisRun(String key, Path resolved) {
        Path hostTemp = normalize(System.getProperty("java.io.tmpdir"));
        assertFalse(resolved.startsWith(hostTemp),
                key + " resolves to " + resolved + ", inside the machine-wide temp directory ("
                        + hostTemp + "). Every cached Spring context boots a scratch sweeper over "
                        + "that directory and deletes checkpoint-* / batch-parquet-* files it does "
                        + "not own — another worktree's suite among them (#187)");
        Path root = runRoot();
        assertTrue(resolved.startsWith(root),
                key + " resolves to " + resolved + ", outside this run's tree (" + root + "). "
                        + "Scratch written and swept under the test profile has to live somewhere "
                        + "the run owns, so two runs cannot delete each other's files and `clean` "
                        + "removes whatever a run leaves behind (#187)");
    }

    /**
     * The tree this run owns: the scratch root Gradle supplied, or — when the suite was not
     * launched by Gradle — the project checkout.
     *
     * <p>Deliberately not "an ancestor directory named {@code build}": an IntelliJ run configured
     * to build with the IDE compiles to {@code out/}, and a guard that goes red on a developer's
     * build layout rather than on a regression is worse than no guard.</p>
     *
     * @return absolute, normalized root
     */
    public static Path runRoot() {
        String gradleRoot = System.getProperty(SCRATCH_ROOT_PROPERTY);
        if (gradleRoot != null && !gradleRoot.isBlank()) {
            return normalize(gradleRoot);
        }
        return projectRoot();
    }

    /**
     * The checkout, found by walking up from the compiled test classes to the directory holding
     * {@code settings.gradle.kts}. The working directory is the fallback, and only the fallback:
     * it is the one thing a run configuration is free to move.
     */
    private static Path projectRoot() {
        Path start;
        try {
            start = normalize(Path.of(RunOwnedScratch.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toString());
        } catch (URISyntaxException | NullPointerException e) {
            return normalize("");
        }
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
                return candidate;
            }
        }
        return normalize("");
    }
}
