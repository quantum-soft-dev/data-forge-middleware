package com.bitbi.dfm.delta.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #127 — scratch files left behind when a pod dies between {@code createTempFile} and
 * {@code finally} must be swept by age and prefix, never by name. A sibling pod may be writing
 * into the same configured directory.
 *
 * <p>Issue #141 — on a directory declared pod-private the sweeper additionally deletes anything
 * older than this JVM, so scratch left by a previous container of the same pod does not survive
 * the whole age window on a volume with a {@code sizeLimit}.</p>
 */
class ParquetScratchOrphanSweeperTest {

    private static final long AGE_SECONDS = 3_600L;

    @TempDir
    Path checkpointDir;

    @TempDir
    Path batchDir;

    private ParquetScratchOrphanSweeper sweeper;

    @BeforeEach
    void setUp() {
        sweeper = sharedVolumeSweeper(checkpointDir, batchDir);
    }

    @Test
    void deletesAnOldCheckpointScratchFile() throws IOException {
        Path orphan = scratch(checkpointDir, ParquetScratch.CHECKPOINT_PREFIX + UUID.randomUUID() + "-",
                ".parquet");
        age(orphan, AGE_SECONDS + 1);

        sweeper.sweep();

        assertFalse(Files.exists(orphan));
    }

    @Test
    void deletesAnOldBatchParquetScratchFile() throws IOException {
        Path orphan = scratch(batchDir, ParquetScratch.BATCH_PARQUET_PREFIX + UUID.randomUUID() + "-",
                ".parquet");
        age(orphan, AGE_SECONDS + 1);

        sweeper.sweep();

        assertFalse(Files.exists(orphan));
    }

    @Test
    void keepsAFreshScratchFile() throws IOException {
        // Cutoff is exclusive (mtime.isBefore): a file one second inside the window must stay.
        Path live = scratch(checkpointDir, ParquetScratch.CHECKPOINT_PREFIX + UUID.randomUUID() + "-",
                ".parquet");
        age(live, AGE_SECONDS - 1);

        sweeper.sweep();

        assertTrue(Files.exists(live));
    }

    @Test
    void doesNotTouchForeignNamesEvenWhenTheyAreOld() throws IOException {
        Path foreign = scratch(checkpointDir, "hsperfdata-", ".tmp");
        Path alsoForeign = Files.writeString(checkpointDir.resolve("snapshot.parquet"), "nope");
        age(foreign, AGE_SECONDS + 60);
        age(alsoForeign, AGE_SECONDS + 60);

        sweeper.sweep();

        assertTrue(Files.exists(foreign));
        assertTrue(Files.exists(alsoForeign));
    }

    @Test
    void doesNotDeleteADirectoryThatMatchesAScratchPrefix() throws IOException {
        Path dir = Files.createDirectory(checkpointDir.resolve(ParquetScratch.CHECKPOINT_PREFIX + "not-a-file"));
        age(dir, AGE_SECONDS + 60);

        sweeper.sweep();

        assertTrue(Files.isDirectory(dir));
    }

    @Test
    void sweepsBothConfiguredDirectoriesWhenTheyDiffer() throws IOException {
        Path checkpointOrphan = scratch(checkpointDir, ParquetScratch.CHECKPOINT_PREFIX, ".parquet");
        Path batchOrphan = scratch(batchDir, ParquetScratch.BATCH_PARQUET_PREFIX, ".parquet");
        age(checkpointOrphan, AGE_SECONDS + 1);
        age(batchOrphan, AGE_SECONDS + 1);

        sweeper.sweep();

        assertFalse(Files.exists(checkpointOrphan));
        assertFalse(Files.exists(batchOrphan));
    }

    @Test
    void sweepsBothPrefixesWhenTheyShareOneDirectory() throws IOException {
        ParquetScratchOrphanSweeper shared = sharedVolumeSweeper(checkpointDir, checkpointDir);
        Path checkpointOrphan = scratch(checkpointDir, ParquetScratch.CHECKPOINT_PREFIX, ".parquet");
        Path batchOrphan = scratch(checkpointDir, ParquetScratch.BATCH_PARQUET_PREFIX, ".parquet");
        Path live = scratch(checkpointDir, ParquetScratch.CHECKPOINT_PREFIX + "live-", ".parquet");
        age(checkpointOrphan, AGE_SECONDS + 1);
        age(batchOrphan, AGE_SECONDS + 1);
        age(live, AGE_SECONDS - 1);

        shared.sweep();

        assertFalse(Files.exists(checkpointOrphan));
        assertFalse(Files.exists(batchOrphan));
        assertTrue(Files.exists(live));
    }

    @Test
    void skipsAMissingDirectoryWithoutThrowing() {
        Path missing = checkpointDir.resolve("does-not-exist");
        ParquetScratchOrphanSweeper missingDir = sharedVolumeSweeper(missing, batchDir);

        missingDir.sweep();

        assertFalse(Files.exists(missing));
    }

    @Test
    void rejectsANonPositiveAgeAtStartup() {
        assertThrows(IllegalArgumentException.class, () ->
                new ParquetScratchOrphanSweeper(checkpointDir.toString(), batchDir.toString(), 0L, false));
        assertThrows(IllegalArgumentException.class, () ->
                new ParquetScratchOrphanSweeper(checkpointDir.toString(), batchDir.toString(), -1L, false));
    }

    // --- issue #141: a pod-private directory also sweeps by this JVM's start ------------------

    @Test
    void deletesScratchLeftByAPreviousContainerWhenTheDirectoryIsPodPrivate() throws IOException {
        ParquetScratchOrphanSweeper podPrivate = podPrivateSweeper(checkpointDir, batchDir);
        // Well inside the four-hour age window, so only the start-of-JVM rule can delete it.
        Path previousContainer = scratch(checkpointDir, ParquetScratch.CHECKPOINT_PREFIX, ".parquet");
        Path previousBatch = scratch(batchDir, ParquetScratch.BATCH_PARQUET_PREFIX, ".parquet");
        age(previousContainer, 60);
        age(previousBatch, 60);

        podPrivate.sweep();

        assertFalse(Files.exists(previousContainer));
        assertFalse(Files.exists(previousBatch));
    }

    @Test
    void keepsScratchThisJvmCreatedWhenTheDirectoryIsPodPrivate() throws IOException {
        ParquetScratchOrphanSweeper podPrivate = podPrivateSweeper(checkpointDir, batchDir);
        // Created after the sweeper was built — a rebuild resumed at startup races the first
        // tick, so "ignore the age at startup" must not mean "delete everything present".
        Path live = scratch(checkpointDir, ParquetScratch.CHECKPOINT_PREFIX + "live-", ".parquet");

        podPrivate.sweep();
        podPrivate.sweep();

        assertTrue(Files.exists(live));
    }

    @Test
    void keepsAPreviousContainersScratchWhenTheDirectoryIsNotDeclaredPodPrivate() throws IOException {
        // The #127 reasoning: on a shared volume a file older than this JVM may belong to a live
        // sibling replica, so only the age filter may delete it.
        Path siblingLive = scratch(checkpointDir, ParquetScratch.CHECKPOINT_PREFIX, ".parquet");
        age(siblingLive, 60);

        sweeper.sweep();

        assertTrue(Files.exists(siblingLive));
    }

    @Test
    void doesNotTouchForeignNamesOlderThanThisJvmWhenTheDirectoryIsPodPrivate() throws IOException {
        ParquetScratchOrphanSweeper podPrivate = podPrivateSweeper(checkpointDir, batchDir);
        Path foreign = scratch(checkpointDir, "hsperfdata-", ".tmp");
        age(foreign, 60);

        podPrivate.sweep();

        assertTrue(Files.exists(foreign));
    }

    @Test
    void aPodPrivateCutoffStartsAtThisJvmAndFallsBackToTheAgeOnceItIsOlder() {
        ParquetScratchOrphanSweeper podPrivate = podPrivateSweeper(checkpointDir, batchDir);
        Instant now = Instant.now();

        Instant atStartup = podPrivate.cutoff(now);
        // Whole seconds: file mtime can be second-resolution, so a file written in the same
        // second as startup must round to "not before" the cutoff and survive.
        assertEquals(0, atStartup.getNano());
        assertTrue(atStartup.isAfter(now.minusSeconds(AGE_SECONDS)),
                "a freshly started JVM must sweep by its own start, not by the age window");

        Instant muchLater = now.plusSeconds(AGE_SECONDS * 2);
        assertEquals(muchLater.minusSeconds(AGE_SECONDS), podPrivate.cutoff(muchLater),
                "once the process is older than the age window the age filter is the harsher one");
    }

    @Test
    void aSharedVolumeCutoffIsAlwaysTheAgeWindow() {
        Instant now = Instant.now();

        Instant muchLater = now.plusSeconds(AGE_SECONDS * 2);

        assertEquals(now.minusSeconds(AGE_SECONDS), sweeper.cutoff(now));
        assertEquals(muchLater.minusSeconds(AGE_SECONDS), sweeper.cutoff(muchLater));
    }

    @Test
    void declaresTheDocumentedDefaultsInApplicationYaml() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertTrue(yaml.contains("scratch-orphan-age-seconds: ${DELTA_PARQUET_SCRATCH_ORPHAN_AGE_SECONDS:"
                        + ParquetScratchOrphanSweeper.DEFAULT_AGE_SECONDS + "}"),
                "application.yml must declare delta.parquet.scratch-orphan-age-seconds");
        assertTrue(yaml.contains("scratch-orphan-sweep-ms: ${DELTA_PARQUET_SCRATCH_ORPHAN_SWEEP_MS:"
                        + ParquetScratchOrphanSweeper.DEFAULT_SWEEP_MS + "}"),
                "application.yml must declare delta.parquet.scratch-orphan-sweep-ms");
        assertTrue(yaml.contains("scratch-private-to-pod: ${DELTA_PARQUET_SCRATCH_PRIVATE_TO_POD:"
                        + ParquetScratchOrphanSweeper.DEFAULT_PRIVATE_TO_POD + "}"),
                "application.yml must declare delta.parquet.scratch-private-to-pod, defaulting to the "
                        + "safe shared-volume behaviour");
    }

    @Test
    void theDeployedEmptyDirIsDeclaredPodPrivate() throws IOException {
        // The flag is a claim about the mount, and the mount is two files away. Whoever moves the
        // scratch off the pod-private emptyDir has to move the claim in the same commit.
        String manifest = Files.readString(Path.of("k8s/base/configmap.yaml"));
        // BOTH directories, because the flag is per process and the sweeper walks both: one key
        // left on a shared volume is enough to start deleting a sibling replica's live scratch.
        boolean everyTempDirIsTheScratchVolume =
                manifest.contains("DELTA_CHECKPOINT_TEMP_DIR: \"/scratch/parquet\"")
                        && manifest.contains("DELTA_BATCH_PARQUET_TEMP_DIR: \"/scratch/parquet\"");

        assertEquals(everyTempDirIsTheScratchVolume,
                manifest.contains("DELTA_PARQUET_SCRATCH_PRIVATE_TO_POD: \"true\""),
                "k8s/base/configmap.yaml must declare the scratch pod-private exactly while both "
                        + "temp-dir keys point at the parquet-scratch emptyDir");
    }

    @Test
    void noOverlayRedirectsTheScratchBehindThatGuard() throws IOException {
        // The guard above reads the base manifest, so it is only the whole truth while no overlay
        // redefines these keys. An overlay that needs to must extend the guard, not slip past it.
        try (Stream<Path> overlays = Files.walk(Path.of("k8s/overlays"))) {
            for (Path file : overlays.filter(Files::isRegularFile).toList()) {
                String body = Files.readString(file);
                assertFalse(body.contains("DELTA_CHECKPOINT_TEMP_DIR")
                                || body.contains("DELTA_BATCH_PARQUET_TEMP_DIR")
                                || body.contains("DELTA_PARQUET_SCRATCH_PRIVATE_TO_POD"),
                        file + " overrides the scratch mount or its pod-private claim; "
                                + "theDeployedEmptyDirIsDeclaredPodPrivate no longer covers the "
                                + "rendered configuration and must be widened to this overlay");
            }
        }
    }

    @Test
    void writersCreateFilesWithTheSharedPrefixes() throws IOException {
        // The on-disk contract is the prefix symbol. A writer that goes back to a string
        // literal can drift from the sweeper with no compile failure.
        String checkpoint = Files.readString(
                Path.of("src/main/java/com/bitbi/dfm/delta/application/CheckpointService.java"));
        String batch = Files.readString(
                Path.of("src/main/java/com/bitbi/dfm/delta/application/BatchParquetFinalizationService.java"));
        assertTrue(checkpoint.contains("ParquetScratch.CHECKPOINT_PREFIX"));
        assertTrue(batch.contains("ParquetScratch.BATCH_PARQUET_PREFIX"));
        assertFalse(checkpoint.contains("\"checkpoint-\""));
        assertFalse(batch.contains("\"batch-parquet-\""));
    }

    private static ParquetScratchOrphanSweeper sharedVolumeSweeper(Path checkpointDir, Path batchDir) {
        return new ParquetScratchOrphanSweeper(
                checkpointDir.toString(), batchDir.toString(), AGE_SECONDS, false);
    }

    private static ParquetScratchOrphanSweeper podPrivateSweeper(Path checkpointDir, Path batchDir) {
        return new ParquetScratchOrphanSweeper(
                checkpointDir.toString(), batchDir.toString(), AGE_SECONDS, true);
    }

    private static Path scratch(Path directory, String prefix, String suffix) throws IOException {
        return Files.createTempFile(directory, prefix, suffix);
    }

    private static void age(Path path, long ageSeconds) throws IOException {
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().minusSeconds(ageSeconds)));
    }
}
