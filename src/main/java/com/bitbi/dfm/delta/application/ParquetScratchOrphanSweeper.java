package com.bitbi.dfm.delta.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Deletes orphaned file-backed Parquet scratch files left behind when a process dies between
 * {@code createTempFile} and the matching {@code finally} (issue #127).
 *
 * <p>Both writers already delete their own files on the happy path
 * ({@link CheckpointService}, {@link BatchParquetFinalizationService}). This sweeper is the
 * recovery path for a shared persistent {@code temp-dir}: it lists only the configured
 * directories, matches the prefixes those writers use, and refuses to delete anything whose
 * last-modified time is still inside {@code delta.parquet.scratch-orphan-age-seconds}. Age is
 * the only safe filter — a sibling replica may be writing into the same volume, and the
 * batch-parquet lease is renewed for the life of a live build, so it is not a bound on file
 * age.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class ParquetScratchOrphanSweeper {

    private static final Logger log = LoggerFactory.getLogger(ParquetScratchOrphanSweeper.class);

    /**
     * Default minimum age (4 hours). Larger than the default batch-parquet lease (30 min) by
     * enough to cover a long create-to-delete interval: completed-batch files are created before
     * replay starts, and a live writer renews its lease so the file can legitimately outlive
     * {@code lease-seconds}.
     */
    public static final String DEFAULT_AGE_SECONDS = "14400";

    /** Default interval between sweeps (1 hour). The first tick runs as soon as the scheduler starts. */
    public static final String DEFAULT_SWEEP_MS = "3600000";

    static final String CHECKPOINT_PREFIX = "checkpoint-";
    static final String BATCH_PARQUET_PREFIX = "batch-parquet-";

    private final Set<Path> directories;
    private final long ageSeconds;
    private final Clock clock;

    @Autowired
    public ParquetScratchOrphanSweeper(
            @Value("${delta.checkpoint.temp-dir:${java.io.tmpdir}}") String checkpointTempDir,
            @Value("${delta.batch-parquet.temp-dir:${java.io.tmpdir}}") String batchParquetTempDir,
            @Value("${delta.parquet.scratch-orphan-age-seconds:" + DEFAULT_AGE_SECONDS + "}")
            long ageSeconds) {
        this(checkpointTempDir, batchParquetTempDir, ageSeconds, Clock.systemUTC());
    }

    ParquetScratchOrphanSweeper(String checkpointTempDir, String batchParquetTempDir,
                                long ageSeconds, Clock clock) {
        if (ageSeconds <= 0) {
            throw new IllegalArgumentException(
                    "delta.parquet.scratch-orphan-age-seconds must be positive, got " + ageSeconds);
        }
        this.directories = uniqueDirectories(checkpointTempDir, batchParquetTempDir);
        this.ageSeconds = ageSeconds;
        this.clock = clock;
    }

    /**
     * Sweep at startup (initial delay 0) and then every
     * {@code delta.parquet.scratch-orphan-sweep-ms}. Startup-only would leave files dropped by a
     * peer that dies after this instance has already booted.
     */
    @Scheduled(
            initialDelayString = "0",
            fixedDelayString = "${delta.parquet.scratch-orphan-sweep-ms:" + DEFAULT_SWEEP_MS + "}")
    public void sweep() {
        Instant cutoff = clock.instant().minusSeconds(ageSeconds);
        for (Path directory : directories) {
            sweepDirectory(directory, cutoff);
        }
    }

    private void sweepDirectory(Path directory, Instant cutoff) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                deleteIfOrphan(path, cutoff);
            }
        } catch (IOException e) {
            log.warn("Could not list parquet scratch directory {}", directory, e);
        }
    }

    private void deleteIfOrphan(Path path, Instant cutoff) {
        String name = path.getFileName().toString();
        if (!isScratchName(name)) {
            return;
        }
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            FileTime modified = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS);
            if (!modified.toInstant().isBefore(cutoff)) {
                return;
            }
            Files.deleteIfExists(path);
            log.info("Deleted orphaned parquet scratch file {}", path);
        } catch (IOException e) {
            log.warn("Could not inspect or delete parquet scratch file {}", path, e);
        }
    }

    static boolean isScratchName(String name) {
        return name.startsWith(CHECKPOINT_PREFIX) || name.startsWith(BATCH_PARQUET_PREFIX);
    }

    private static Set<Path> uniqueDirectories(String... configured) {
        Set<Path> unique = new LinkedHashSet<>();
        for (String value : configured) {
            unique.add(Path.of(value).toAbsolutePath().normalize());
        }
        return Set.copyOf(unique);
    }
}
