package com.bitbi.dfm.delta.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Deletes orphaned file-backed Parquet scratch files left behind when a process dies between
 * {@code createTempFile} and the matching {@code finally} (issue #127).
 *
 * <p>Both writers already delete their own files on the happy path
 * ({@link CheckpointService}, {@link BatchParquetFinalizationService}). This sweeper is the
 * recovery path for a persistent {@code temp-dir}: it lists only the configured
 * directories, matches {@link ParquetScratch} prefixes, and refuses to delete anything whose
 * last-modified time is still inside {@code delta.parquet.scratch-orphan-age-seconds}. Age is
 * the only safe filter on a volume this process may share — a sibling replica may be writing
 * into it, and the batch-parquet lease is renewed for the life of a live build, so it is not a
 * bound on file age.</p>
 *
 * <p><b>Pod-private volumes (issue #141).</b> When {@code delta.parquet.scratch-private-to-pod}
 * says no other process can write into these directories — the deployed
 * {@code parquet-scratch} {@code emptyDir}, which survives a container restart but not the pod —
 * a second rule applies: every scratch file older than this JVM belongs to a dead predecessor and
 * is deleted regardless of age. The cutoff is the later of the two rules, so it is at least as
 * aggressive as the age filter at every moment (see {@link #cutoff(Instant)}). Without that flag
 * nothing changes: the age filter alone decides, because on a shared volume a file older than this
 * JVM may belong to a live sibling.</p>
 *
 * <p>The start of this JVM, not "the first run", is the bound: the scheduler's first tick can
 * overlap a rebuild resumed at startup, so an unconditional startup sweep would race a live
 * writer.</p>
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

    /**
     * Default answer to "is this directory written by this pod alone?" — {@code false}, the
     * assumption that keeps the age filter as the only rule. A deployment that mounts a
     * pod-private volume says so in its own manifest.
     */
    public static final String DEFAULT_PRIVATE_TO_POD = "false";

    private final Set<Path> directories;
    private final long ageSeconds;
    private final boolean privateToPod;
    private final Instant startedAt;

    public ParquetScratchOrphanSweeper(
            @Value("${delta.checkpoint.temp-dir:${java.io.tmpdir}}") String checkpointTempDir,
            @Value("${delta.batch-parquet.temp-dir:${java.io.tmpdir}}") String batchParquetTempDir,
            @Value("${delta.parquet.scratch-orphan-age-seconds:" + DEFAULT_AGE_SECONDS + "}")
            long ageSeconds,
            @Value("${delta.parquet.scratch-private-to-pod:" + DEFAULT_PRIVATE_TO_POD + "}")
            boolean privateToPod) {
        if (ageSeconds <= 0) {
            throw new IllegalArgumentException(
                    "delta.parquet.scratch-orphan-age-seconds must be positive, got " + ageSeconds);
        }
        this.directories = uniqueDirectories(checkpointTempDir, batchParquetTempDir);
        this.ageSeconds = ageSeconds;
        this.privateToPod = privateToPod;
        // Truncated to whole seconds because file mtime can be second-resolution: a file written
        // in the same second this bean was built must round to "not older than this JVM" and
        // survive. Bean construction is before any writer can run, so nothing this JVM creates
        // is ever older than this instant.
        this.startedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
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
        Instant cutoff = cutoff(Instant.now());
        for (Path directory : directories) {
            sweepDirectory(directory, cutoff);
        }
    }

    /**
     * The instant a scratch file must predate to be considered dead.
     *
     * <p>The age window always counts. On a pod-private directory the start of this JVM counts
     * too, and whichever of the two is later wins — so a freshly started process sweeps its
     * predecessor's leftovers immediately, and once the process has been up longer than the age
     * window the ordinary age filter is the harsher rule again.</p>
     *
     * @param now current instant
     * @return exclusive cutoff; a file is deleted only when its mtime is strictly before it
     */
    Instant cutoff(Instant now) {
        Instant aged = now.minusSeconds(ageSeconds);
        return privateToPod && startedAt.isAfter(aged) ? startedAt : aged;
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
        if (!ParquetScratch.matches(name)) {
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

    private static Set<Path> uniqueDirectories(String... configured) {
        Set<Path> unique = new LinkedHashSet<>();
        for (String value : configured) {
            unique.add(Path.of(value).toAbsolutePath().normalize());
        }
        return Set.copyOf(unique);
    }
}
