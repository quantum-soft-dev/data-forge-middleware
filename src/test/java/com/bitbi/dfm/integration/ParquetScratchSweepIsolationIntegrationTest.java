package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ParquetScratchOrphanSweeper;
import com.bitbi.dfm.testsupport.RunOwnedScratch;
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

/**
 * Issue #187 — the sweeper wired into an integration context must sweep the directories this run
 * owns and nothing else.
 *
 * <p>Until the {@code test} profile declared them, both scratch keys fell back to
 * {@code ${java.io.tmpdir}}: every cached context booted a {@link ParquetScratchOrphanSweeper} over
 * the host's temp directory and, at refresh, deleted any regular file named {@code checkpoint-*} or
 * {@code batch-parquet-*} older than four hours — another worktree's suite, or anything else on the
 * machine using those prefixes.</p>
 *
 * <p>The two assertions are deliberately opposite, and neither is a planted file in the host's temp
 * directory: that probe would have been decided by any <em>other</em> process sweeping the same
 * directory — a locally running backend under {@code dev}, or a concurrent worktree on a branch
 * that predates this fix, which is the ordinary shape of {@code /github-issue-runner} — and would
 * have failed accusing this suite of the defect it guards. The wired configuration answers the
 * same question deterministically, and {@code ParquetScratchOrphanSweeperTest} already pins that
 * the sweeper visits its configured directories and no others.</p>
 *
 * <p>The first assertion is the defect: the directories this context actually resolved are the
 * run's own. Being wired rather than static, it is also the only guard that sees an {@code OS}
 * environment override of these keys ({@code DELTA_CHECKPOINT_TEMP_DIR} outranks every
 * {@code application*.yml}). The second is what stops the fix from being "the sweeper was switched
 * off under test" — an aged file inside the configured directory is still deleted, so the profile
 * redirected the sweep rather than disabling it, and the literal prefix this test shares with the
 * package-private production {@code ParquetScratch} cannot drift away unnoticed.</p>
 *
 * <p>{@code BaseIntegrationTest} rather than a context of its own: nothing here needs a property
 * override, and an extra cached context costs another connection pool and another round of
 * refresh-time sweeps (#167).</p>
 *
 * <p>One residual, named rather than left implied: the configured directory is now shared by every
 * cached context, so a <em>peer</em> context's sweeper could in principle delete the probe of
 * {@link #shouldStillSweepTheConfiguredDirectory} and satisfy that assertion while this context's
 * bean was misconfigured. It needs a peer tick inside the method — contexts refresh between
 * classes, this suite runs single-threaded, and the recurring tick is hourly under the test
 * profile (#167) — so it is accepted rather than designed around; the assertion that would
 * actually catch a misconfiguration is the first one, which reads this context's own values.</p>
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

    @Value("${delta.batch-parquet.temp-dir:${java.io.tmpdir}}")
    private String batchParquetTempDirectory;

    @Test
    @DisplayName("the directories this context sweeps belong to this run")
    void shouldSweepOnlyDirectoriesThisRunOwns() {
        RunOwnedScratch.assertOwnedByThisRun(
                "delta.checkpoint.temp-dir", RunOwnedScratch.normalize(checkpointTempDirectory));
        RunOwnedScratch.assertOwnedByThisRun(
                "delta.batch-parquet.temp-dir", RunOwnedScratch.normalize(batchParquetTempDirectory));
    }

    @Test
    @DisplayName("a sweep still deletes aged scratch in the directory this run owns")
    void shouldStillSweepTheConfiguredDirectory() throws IOException {
        Path configured = RunOwnedScratch.normalize(checkpointTempDirectory);
        Files.createDirectories(configured);
        Path orphan = Files.createTempFile(configured, "checkpoint-187-orphan-", ".parquet");
        try {
            Files.setLastModifiedTime(orphan, FileTime.from(LONG_DEAD));

            sweeper.sweep();

            assertFalse(Files.exists(orphan),
                    "the sweeper left " + orphan + " behind. The test profile is supposed to redirect "
                            + "the sweep into the run's own tree, not disable it — recovery from a "
                            + "process that died between createTempFile and its finally is the whole "
                            + "point of the sweeper (#127), and this is the only place it runs against "
                            + "a real directory (#187)");
        } finally {
            Files.deleteIfExists(orphan);
        }
    }
}
