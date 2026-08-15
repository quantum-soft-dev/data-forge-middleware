package com.bitbi.dfm.delta.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #127 — scratch files left behind when a pod dies between {@code createTempFile} and
 * {@code finally} must be swept by age and prefix, never by name. A sibling pod may be writing
 * into the same configured directory.
 */
class ParquetScratchOrphanSweeperTest {

    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long AGE_SECONDS = 3_600L;

    @TempDir
    Path checkpointDir;

    @TempDir
    Path batchDir;

    private ParquetScratchOrphanSweeper sweeper;

    @BeforeEach
    void setUp() {
        sweeper = new ParquetScratchOrphanSweeper(
                checkpointDir.toString(), batchDir.toString(), AGE_SECONDS, CLOCK);
    }

    @Test
    void deletesAnOldCheckpointScratchFile() throws IOException {
        Path orphan = scratch(checkpointDir, "checkpoint-" + UUID.randomUUID() + "-", ".parquet");
        age(orphan, AGE_SECONDS + 1);

        sweeper.sweep();

        assertFalse(Files.exists(orphan));
    }

    @Test
    void deletesAnOldBatchParquetScratchFile() throws IOException {
        Path orphan = scratch(batchDir, "batch-parquet-" + UUID.randomUUID() + "-", ".parquet");
        age(orphan, AGE_SECONDS + 1);

        sweeper.sweep();

        assertFalse(Files.exists(orphan));
    }

    @Test
    void keepsAFreshScratchFile() throws IOException {
        Path live = scratch(checkpointDir, "checkpoint-" + UUID.randomUUID() + "-", ".parquet");
        age(live, AGE_SECONDS - 1);

        sweeper.sweep();

        assertTrue(Files.exists(live));
    }

    @Test
    void keepsAScratchFileExactlyAtTheAgeThreshold() throws IOException {
        // Strictly older than the threshold is the only safe delete: a file whose mtime equals
        // the cutoff may still belong to a writer that created it just as the lease-length window
        // elapsed, and last-modified resolution on some filesystems is a whole second.
        Path boundary = scratch(batchDir, "batch-parquet-", ".parquet");
        age(boundary, AGE_SECONDS);

        sweeper.sweep();

        assertTrue(Files.exists(boundary));
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
        Path dir = Files.createDirectory(checkpointDir.resolve("checkpoint-not-a-file"));
        age(dir, AGE_SECONDS + 60);

        sweeper.sweep();

        assertTrue(Files.isDirectory(dir));
    }

    @Test
    void sweepsBothConfiguredDirectoriesWhenTheyDiffer() throws IOException {
        Path checkpointOrphan = scratch(checkpointDir, "checkpoint-", ".parquet");
        Path batchOrphan = scratch(batchDir, "batch-parquet-", ".parquet");
        age(checkpointOrphan, AGE_SECONDS + 1);
        age(batchOrphan, AGE_SECONDS + 1);

        sweeper.sweep();

        assertFalse(Files.exists(checkpointOrphan));
        assertFalse(Files.exists(batchOrphan));
    }

    @Test
    void sweepsBothPrefixesWhenTheyShareOneDirectory() throws IOException {
        ParquetScratchOrphanSweeper shared = new ParquetScratchOrphanSweeper(
                checkpointDir.toString(), checkpointDir.toString(), AGE_SECONDS, CLOCK);
        Path checkpointOrphan = scratch(checkpointDir, "checkpoint-", ".parquet");
        Path batchOrphan = scratch(checkpointDir, "batch-parquet-", ".parquet");
        Path live = scratch(checkpointDir, "checkpoint-live-", ".parquet");
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
                missing.toString(), batchDir.toString(), AGE_SECONDS, CLOCK);

        missingDir.sweep();

        assertFalse(Files.exists(missing));
    }

    @Test
    void rejectsANonPositiveAgeAtStartup() {
        assertThrows(IllegalArgumentException.class, () ->
                new ParquetScratchOrphanSweeper(checkpointDir.toString(), batchDir.toString(), 0L, CLOCK));
        assertThrows(IllegalArgumentException.class, () ->
                new ParquetScratchOrphanSweeper(checkpointDir.toString(), batchDir.toString(), -1L, CLOCK));
    }

    @Test
    void marksTheProductionConstructorForSpring() throws NoSuchMethodException {
        // A second Clock-taking constructor exists so unit tests can freeze time. Without
        // @Autowired on the three-argument one, Spring cannot choose and looks for a no-arg
        // constructor — every @SpringBootTest then fails to start.
        Constructor<ParquetScratchOrphanSweeper> production =
                ParquetScratchOrphanSweeper.class.getConstructor(String.class, String.class, long.class);
        assertNotNull(production.getAnnotation(Autowired.class));
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

    private static Path scratch(Path directory, String prefix, String suffix) throws IOException {
        return Files.createTempFile(directory, prefix, suffix);
    }

    private static void age(Path path, long ageSeconds) throws IOException {
        Files.setLastModifiedTime(path, FileTime.from(NOW.minusSeconds(ageSeconds)));
    }
}
