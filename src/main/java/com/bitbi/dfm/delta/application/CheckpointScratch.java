package com.bitbi.dfm.delta.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * The checkpoint build's scratch directory and the files it puts there — shared by the frame
 * producer and the snapshot materializer (issue #297).
 */
final class CheckpointScratch {

    /** Logged under {@code CheckpointService}, where every checkpoint build line has always been. */
    private static final Logger log = LoggerFactory.getLogger(CheckpointService.class);

    private final Path tempDirectory;
    /**
     * The bound on the scratch <em>directory</em> this build shares with the completed-batch
     * workers (issue #150) — the per-file ceilings cannot bound a count of files.
     */
    private final ParquetScratchBudget scratchBudget;

    CheckpointScratch(Path tempDirectory, ParquetScratchBudget scratchBudget) {
        this.tempDirectory = tempDirectory;
        this.scratchBudget = scratchBudget;
    }

    ParquetScratchBudget budget() {
        return scratchBudget;
    }

    /**
     * Every scratch file of this build goes through the same directory, one at a time. Creating it
     * is systemic, not per-artifact: if it fails, nothing can be materialized this build, so let it
     * fail the build loudly instead of counting every table as its own skip.
     */
    void prepareDirectory() {
        try {
            Files.createDirectories(tempDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot prepare the checkpoint scratch directory " + tempDirectory, e);
        }
    }

    /**
     * Create this artifact's scratch file. A failure here says the scratch directory itself is
     * unusable (gone, read-only, out of inodes) — it is not a fact about this table and it would
     * hit every table of every site alike. Skipping per table would detach every last-good
     * snapshot while the pointer still advanced. A later rematerialize (issue #128) can restore
     * a per-table hole, but a systemic scratch failure must not throw away the last downloadable
     * snapshots first. Fail the build instead, leaving the pointer and keys where they were so the
     * next run redoes everything; {@code CheckpointScheduler} catches per site, so one site's
     * failure does not stop the sweep.
     *
     * <p>A failure <em>during</em> the write stays a per-table skip, so a single oversized or
     * unrenderable table cannot freeze the pointer and stop retention.</p>
     */
    Path createFile(UUID siteId) {
        return createFile(siteId, ".parquet");
    }

    Path createFile(UUID siteId, String suffix) {
        try {
            return Files.createTempFile(tempDirectory,
                    ParquetScratch.CHECKPOINT_PREFIX + siteId + "-", suffix);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot create a checkpoint scratch file in " + tempDirectory, e);
        }
    }

    static void deleteQuietly(Path snapshot, String tableName, UUID siteId) {
        if (snapshot == null) {
            return;
        }
        try {
            Files.deleteIfExists(snapshot);
        } catch (IOException e) {
            log.warn("Could not delete the temporary checkpoint snapshot {} of table {} for site {}",
                    snapshot, tableName, siteId, e);
        }
    }
}
