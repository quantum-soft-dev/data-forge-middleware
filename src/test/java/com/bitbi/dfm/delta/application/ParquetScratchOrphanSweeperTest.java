package com.bitbi.dfm.delta.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    /** The deployed scratch mount and the volume behind it (k8s/base, issue #131). */
    private static final String SCRATCH_MOUNT_PATH = "/scratch/parquet";
    private static final String SCRATCH_VOLUME = "parquet-scratch";

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
    void theProductionConstructorKeysOffTheProcessStartNotItsOwnConstruction() {
        Instant processStart = ProcessHandle.current().info().startInstant().orElse(null);
        assumeTrue(processStart != null, "this platform does not report the process start instant");
        // A day, so the age rule cannot be the later one for a test JVM.
        ParquetScratchOrphanSweeper production = new ParquetScratchOrphanSweeper(
                checkpointDir.toString(), batchDir.toString(), 86_400L, true);

        // The worker JVM has been up a while, so a reference taken at construction would sit at
        // "now" and put scratch written during context refresh in scope.
        assertEquals(processStart.truncatedTo(ChronoUnit.SECONDS), production.cutoff(Instant.now()),
                "the pod-private rule must key off the process start, not this bean's construction");
    }

    @Test
    void fallsBackToTheAgeFilterWhenTheProcessStartIsUnknown() throws IOException {
        ParquetScratchOrphanSweeper unknownStart = new ParquetScratchOrphanSweeper(
                checkpointDir.toString(), batchDir.toString(), AGE_SECONDS, true, null);
        Path recent = scratch(checkpointDir, ParquetScratch.CHECKPOINT_PREFIX, ".parquet");
        age(recent, 60);

        unknownStart.sweep();

        assertTrue(Files.exists(recent),
                "with no process start there is no second rule; substituting a later instant would "
                        + "put scratch written before this bean existed in scope");
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
    void theDeployedScratchIsDeclaredPodPrivateOnlyWhenItIsOne() throws IOException {
        // One direction only. `flag => the mount really is pod-private` is the safety property;
        // the converse would merely enforce an optimisation, and turning the flag off must always
        // stay available as a rollback. Read as YAML rather than grepped: the claim is about the
        // volume behind the mount path, and key order in a manifest is not part of the contract.
        Map<String, Object> configMap = loadManifest("k8s/base/configmap.yaml");
        Map<String, Object> data = child(configMap, "data");
        Object declared = data.get("DELTA_PARQUET_SCRATCH_PRIVATE_TO_POD");
        // A value this guard cannot read is a failure, not a skip: unquoted `true` binds to a
        // Boolean here and to `true` in Spring, and skipping would retire the guard in silence.
        assertTrue(declared == null || isBoolean(declared),
                "DELTA_PARQUET_SCRATCH_PRIVATE_TO_POD must be a boolean, was " + declared);
        if (!isTrue(declared)) {
            return;
        }

        // BOTH directories, because the flag is per process and the sweeper walks both: one key
        // left on a shared volume is enough to start deleting a sibling replica's live scratch.
        assertEquals(SCRATCH_MOUNT_PATH, data.get("DELTA_CHECKPOINT_TEMP_DIR"),
                "the scratch is declared pod-private, so the checkpoint temp dir must be the mount");
        assertEquals(SCRATCH_MOUNT_PATH, data.get("DELTA_BATCH_PARQUET_TEMP_DIR"),
                "the scratch is declared pod-private, so the batch temp dir must be the mount");

        Map<String, Object> deployment = loadManifest("k8s/base/deployment-backend.yaml");
        Map<String, Object> podSpec = child(child(child(deployment, "spec"), "template"), "spec");
        Map<String, Object> volumesByName = new java.util.HashMap<>();
        for (Map<String, Object> volume : children(podSpec, "volumes")) {
            volumesByName.put(String.valueOf(volume.get("name")), volume);
        }
        int mounts = 0;
        List<Map<String, Object>> allContainers = new java.util.ArrayList<>(children(podSpec, "containers"));
        // initContainers share the pod's volumes and can mount the same path.
        allContainers.addAll(children(podSpec, "initContainers"));
        for (Map<String, Object> container : allContainers) {
            for (Map<String, Object> mount : children(container, "volumeMounts")) {
                if (!SCRATCH_MOUNT_PATH.equals(mount.get("mountPath"))) {
                    continue;
                }
                mounts++;
                Object volume = volumesByName.get(String.valueOf(mount.get("name")));
                assertTrue(volume instanceof Map<?, ?> v && v.containsKey("emptyDir"),
                        "the scratch is declared pod-private, so the volume behind "
                                + SCRATCH_MOUNT_PATH + " must be an emptyDir, was " + volume);
            }
        }
        assertTrue(mounts >= 1,
                "the scratch is declared pod-private, but nothing mounts " + SCRATCH_MOUNT_PATH
                        + " — the temp dirs would land on the container's writable layer");
    }

    @Test
    void noOverlayRedirectsTheScratchBehindThatGuard() throws IOException {
        // The guard above reads the base manifests, so it is only the whole truth while no overlay
        // redefines them. An overlay that needs to must widen the guard, not slip past it. Only
        // YAML, and only an actual key assignment or volume entry — a prose mention in a comment
        // is not an override, and a binary blob under an overlay is not this test's business.
        // Setting the flag to "false" in an overlay is allowed: that is the conservative direction.
        Pattern envOverride = Pattern.compile(
                "^\\s*(DELTA_CHECKPOINT_TEMP_DIR\\s*:|DELTA_BATCH_PARQUET_TEMP_DIR\\s*:"
                        // any spelling Spring binds as true, quoted however YAML allows
                        + "|DELTA_PARQUET_SCRATCH_PRIVATE_TO_POD\\s*:\\s*[\"\']?true[\"\']?\\s*$)",
                Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
        // The volume by name, and the mount path whatever volume is behind it — a differently
        // named hostPath at the same path is the same hole.
        Pattern volumeOverride = Pattern.compile(
                "^\\s*(-\\s+name:\\s+" + SCRATCH_VOLUME + "|.*mountPath:\\s+" + SCRATCH_MOUNT_PATH + ")\\s*$",
                Pattern.MULTILINE);
        try (Stream<Path> overlays = Files.walk(Path.of("k8s/overlays"))) {
            for (Path file : overlays.filter(ParquetScratchOrphanSweeperTest::isYaml).toList()) {
                String body = Files.readString(file);
                assertFalse(envOverride.matcher(body).find() || volumeOverride.matcher(body).find(),
                        file + " overrides the scratch mount or its pod-private claim; "
                                + "theDeployedScratchIsDeclaredPodPrivateOnlyWhenItIsOne no longer "
                                + "covers the rendered configuration and must be widened to this overlay");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadManifest(String path) throws IOException {
        return new Yaml().loadAs(Files.readString(Path.of(path)), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        assertTrue(value instanceof Map<?, ?>, key + " must be a mapping, was " + value);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> children(Map<String, Object> parent, String key) {
        Object value = parent.getOrDefault(key, List.of());
        assertTrue(value instanceof List<?>, key + " must be a sequence, was " + value);
        return (List<Map<String, Object>>) value;
    }

    private static boolean isBoolean(Object value) {
        return value instanceof Boolean
                || "true".equalsIgnoreCase(String.valueOf(value))
                || "false".equalsIgnoreCase(String.valueOf(value));
    }

    private static boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static boolean isYaml(Path path) {
        String name = path.getFileName().toString();
        return Files.isRegularFile(path) && (name.endsWith(".yaml") || name.endsWith(".yml"));
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
                checkpointDir.toString(), batchDir.toString(), AGE_SECONDS, false, Instant.now());
    }

    /** Pod-private, with the process start pinned to now so "older than this JVM" is testable. */
    private static ParquetScratchOrphanSweeper podPrivateSweeper(Path checkpointDir, Path batchDir) {
        return new ParquetScratchOrphanSweeper(
                checkpointDir.toString(), batchDir.toString(), AGE_SECONDS, true, Instant.now());
    }

    private static Path scratch(Path directory, String prefix, String suffix) throws IOException {
        return Files.createTempFile(directory, prefix, suffix);
    }

    private static void age(Path path, long ageSeconds) throws IOException {
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().minusSeconds(ageSeconds)));
    }
}
