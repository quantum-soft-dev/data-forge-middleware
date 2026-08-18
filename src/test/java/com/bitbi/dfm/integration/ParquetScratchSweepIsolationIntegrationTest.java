package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ParquetScratchOrphanSweeper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #187 — the sweeper wired into an integration context must sweep the directories this run
 * owns and nothing else.
 *
 * <p>Until the {@code test} profile declared them, both scratch keys fell back to
 * {@code ${java.io.tmpdir}}: every cached context booted a
 * {@link ParquetScratchOrphanSweeper} over the host's temp directory and, at refresh, deleted any
 * regular file named {@code checkpoint-*} or {@code batch-parquet-*} older than four hours —
 * another worktree's suite, or anything else on the machine using those prefixes.</p>
 *
 * <p>The two assertions are deliberately opposite. The first is the defect: an aged file planted in
 * {@code java.io.tmpdir} survives a sweep. The second is what stops the fix from being "the sweeper
 * was switched off under test" — an equally aged file inside the configured directory is still
 * deleted, so the profile redirected the sweep rather than disabling it, and the shared literal
 * prefix cannot drift away from the production one without failing here.</p>
 *
 * <p>{@code BaseIntegrationTest} rather than a context of its own: nothing here needs a property
 * override, and an extra cached context costs another connection pool and another round of
 * refresh-time sweeps (#167).</p>
 */
@DisplayName("Parquet scratch sweep isolation (#187)")
class ParquetScratchSweepIsolationIntegrationTest extends BaseIntegrationTest {

    /** Old enough to clear the four-hour age window whatever the run's own clock skew. */
    private static final Instant LONG_DEAD = Instant.now().minus(30, ChronoUnit.DAYS);

    @Autowired
    private ParquetScratchOrphanSweeper sweeper;

    /** Resolved exactly as the sweeper resolves it, so the assertions cannot go vacuous. */
    @Value("${delta.checkpoint.temp-dir:${java.io.tmpdir}}")
    private String checkpointTempDirectory;

    @Test
    @DisplayName("a sweep leaves aged scratch in the machine-wide temp directory alone")
    void shouldNotSweepTheMachineWideTempDirectory() throws IOException {
        Path hostTempDirectory = Path.of(System.getProperty("java.io.tmpdir"));
        Path plantedByAnotherProcess =
                Files.createTempFile(hostTempDirectory, "checkpoint-187-guard-", ".parquet");
        // The finally below is the removal; this covers the JVM dying mid-method, since after this
        // change nothing sweeps that directory any more — a class asserting the suite leaves the
        // host temp directory alone must not be the one thing left in it.
        plantedByAnotherProcess.toFile().deleteOnExit();
        try {
            Files.setLastModifiedTime(plantedByAnotherProcess, FileTime.from(LONG_DEAD));

            sweeper.sweep();

            assertTrue(Files.exists(plantedByAnotherProcess),
                    "the sweeper deleted " + plantedByAnotherProcess + ", a file this run does not own. "
                            + "Under the test profile both scratch keys must name a directory inside the "
                            + "build tree; undeclared they fall back to ${java.io.tmpdir} and the suite "
                            + "deletes whatever else on the host uses these prefixes (#187)");
        } finally {
            Files.deleteIfExists(plantedByAnotherProcess);
        }
    }

    @Test
    @DisplayName("a sweep still deletes aged scratch in the directory this run owns")
    void shouldStillSweepTheConfiguredDirectory() throws IOException {
        Path configured = Path.of(checkpointTempDirectory);
        Files.createDirectories(configured);
        Path orphan = Files.createTempFile(configured, "checkpoint-187-orphan-", ".parquet");
        try {
            Files.setLastModifiedTime(orphan, FileTime.from(LONG_DEAD));

            sweeper.sweep();

            assertFalse(Files.exists(orphan),
                    "the sweeper left " + orphan + " behind. The test profile is supposed to redirect "
                            + "the sweep into the build tree, not disable it — recovery from a process "
                            + "that died between createTempFile and its finally is the whole point of "
                            + "the sweeper (#127), and this is the only place it runs against a real "
                            + "directory (#187)");
        } finally {
            Files.deleteIfExists(orphan);
        }
    }
}
