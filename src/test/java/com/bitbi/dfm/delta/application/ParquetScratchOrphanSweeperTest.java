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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #127 — scratch files left behind when a pod dies between {@code createTempFile} and
 * {@code finally} must be swept by age and prefix, never by name. A sibling pod may be writing
 * into the same configured directory.
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
        sweeper = new ParquetScratchOrphanSweeper(
                checkpointDir.toString(), batchDir.toString(), AGE_SECONDS);
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
        ParquetScratchOrphanSweeper shared = new ParquetScratchOrphanSweeper(
                checkpointDir.toString(), checkpointDir.toString(), AGE_SECONDS);
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
        ParquetScratchOrphanSweeper missingDir = new ParquetScratchOrphanSweeper(
                missing.toString(), batchDir.toString(), AGE_SECONDS);

        missingDir.sweep();

        assertFalse(Files.exists(missing));
    }

    @Test
    void rejectsANonPositiveAgeAtStartup() {
        assertThrows(IllegalArgumentException.class, () ->
                new ParquetScratchOrphanSweeper(checkpointDir.toString(), batchDir.toString(), 0L));
        assertThrows(IllegalArgumentException.class, () ->
                new ParquetScratchOrphanSweeper(checkpointDir.toString(), batchDir.toString(), -1L));
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

    private static Path scratch(Path directory, String prefix, String suffix) throws IOException {
        return Files.createTempFile(directory, prefix, suffix);
    }

    private static void age(Path path, long ageSeconds) throws IOException {
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().minusSeconds(ageSeconds)));
    }
}
