package com.bitbi.dfm.delta.application;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #138 — the application must refuse before the pod's scratch volume fills.
 *
 * <p>Two halves, and they live in different files on purpose. The <b>defaults</b> in
 * {@code application.yml} stay where they were: a process whose scratch is a large node disk must
 * behave exactly as it did before this change, and nothing in the application can know how big the
 * directory is. The <b>deployed</b> ceilings belong beside the volume that bounds them, in
 * {@code k8s/base/configmap.yaml} next to the {@code *_TEMP_DIR} keys that put the writers on it —
 * the same split #141 used for {@code DELTA_PARQUET_SCRATCH_PRIVATE_TO_POD}.</p>
 *
 * <p>What is asserted about the deployed values is the worst case the "Sizing note" of
 * {@code docs/delta-client-v2-guide.md} already writes down, recomputed from the manifests:</p>
 *
 * <pre>
 * 2 x max(checkpoint table, checkpoint frame) + max-concurrent x batch artifact &lt;= sizeLimit
 * </pre>
 *
 * <p>It is a <b>floor on the guarantee, not the budget</b>: the batch term is really
 * {@code x tables claimed per batch}, a multiplier no per-file key can bound — only a
 * directory-wide reservation can, which is issue #150. Orphan residue (#127, #141) is outside it
 * too. What the guard does buy is that the three numbers, the concurrency they assume and the
 * volume behind them can no longer drift apart silently.</p>
 */
class ParquetScratchCeilingBudgetTest {

    /**
     * What both checkpoint ceilings and the batch ceiling defaulted to before this change. The
     * frame key is new, so its default has to be this number for an upgrade to be a no-op.
     */
    private static final long HISTORIC_DEFAULT_BYTES = 10737418240L;

    private static final String SCRATCH_MOUNT_PATH = "/scratch/parquet";

    /** Both checkpoint build paths can hold one scratch file each at the same time. */
    private static final int CONCURRENT_CHECKPOINT_BUILDS = 2;

    private static final String SNAPSHOT_CEILING_KEY = "DELTA_CHECKPOINT_MAX_TEMP_BYTES";
    private static final String FRAME_CEILING_KEY = "DELTA_CHECKPOINT_MAX_FRAME_TEMP_BYTES";
    private static final String BATCH_CEILING_KEY = "DELTA_BATCH_PARQUET_MAX_TEMP_BYTES";
    private static final String BATCH_CONCURRENCY_KEY = "DELTA_BATCH_PARQUET_MAX_CONCURRENT";

    @Test
    void theFrameCeilingIsItsOwnKeyAndUpgradesToTheHistoricValue() throws IOException {
        String yaml = applicationYaml();

        assertTrue(yaml.contains("max-temp-bytes: ${" + SNAPSHOT_CEILING_KEY + ":"
                        + HISTORIC_DEFAULT_BYTES + "}"),
                "delta.checkpoint.max-temp-bytes stays the per-table snapshot ceiling at its "
                        + "historic default");
        assertTrue(yaml.contains("max-frame-temp-bytes: ${" + FRAME_CEILING_KEY + ":"
                        + HISTORIC_DEFAULT_BYTES + "}"),
                "delta.checkpoint.max-frame-temp-bytes must default to the value the frame was "
                        + "governed by before the split, so an unset key behaves as today");
        assertTrue(yaml.contains("max-temp-bytes: ${" + BATCH_CEILING_KEY + ":"
                        + HISTORIC_DEFAULT_BYTES + "}"),
                "the completed-batch default is untouched by this split");
    }

    @Test
    void theDeployedCeilingsRefuseBeforeTheScratchVolumeFills() throws IOException {
        Map<String, Object> data = child(loadManifest("k8s/base/configmap.yaml"), "data");
        if (!SCRATCH_MOUNT_PATH.equals(data.get("DELTA_CHECKPOINT_TEMP_DIR"))
                && !SCRATCH_MOUNT_PATH.equals(data.get("DELTA_BATCH_PARQUET_TEMP_DIR"))) {
            // Nothing is on the bounded volume, so there is no budget to keep. Any other
            // arrangement has to bring its own guard rather than silently retiring this one.
            return;
        }

        long snapshotCeiling = deployedBytes(data, SNAPSHOT_CEILING_KEY);
        long frameCeiling = deployedBytes(data, FRAME_CEILING_KEY);
        long batchCeiling = deployedBytes(data, BATCH_CEILING_KEY);
        long batchConcurrency = data.containsKey(BATCH_CONCURRENCY_KEY)
                ? Long.parseLong(String.valueOf(data.get(BATCH_CONCURRENCY_KEY)).trim())
                : applicationDefault(BATCH_CONCURRENCY_KEY);

        assertTrue(frameCeiling >= snapshotCeiling,
                "the frame ceiling must be the wider of the two: crossing it aborts the build and "
                        + "freezes the pointer, while a table skip is visible and rematerialized by "
                        + "a later build — " + FRAME_CEILING_KEY + "=" + frameCeiling + " B is below "
                        + SNAPSHOT_CEILING_KEY + "=" + snapshotCeiling + " B");

        long sizeLimit = scratchVolumeSizeLimitBytes();
        long peak = CONCURRENT_CHECKPOINT_BUILDS * Math.max(snapshotCeiling, frameCeiling)
                + batchConcurrency * batchCeiling;
        assertTrue(peak <= sizeLimit,
                "the worst case allowed by these ceilings is " + peak + " B on a " + sizeLimit
                        + " B volume, so kubelet evicts the pod before the application refuses: "
                        + CONCURRENT_CHECKPOINT_BUILDS + " x max(" + snapshotCeiling + ", "
                        + frameCeiling + ") + " + batchConcurrency + " x " + batchCeiling
                        + ". Move the ceilings and the volume together — see the sizing note in "
                        + "docs/delta-client-v2-guide.md");
    }

    @Test
    void noOverlayRedefinesEitherSideOfThatBudget() throws IOException {
        // Same reasoning as ParquetScratchOrphanSweeperTest#noOverlayRedirectsTheScratchBehindThatGuard:
        // the guard above reads the base manifests, so an overlay that redefines either side must
        // widen it rather than slip past it. Both ConfigMap forms count — a `KEY: value` entry and
        // a `- KEY=value` configMapGenerator literal.
        Pattern ceilingOverride = Pattern.compile(
                "(^|\\s|-\\s)(" + SNAPSHOT_CEILING_KEY + "|" + FRAME_CEILING_KEY + "|"
                        + BATCH_CEILING_KEY + "|" + BATCH_CONCURRENCY_KEY + ")\\s*[:=]",
                Pattern.MULTILINE);
        // A sizeLimit belonging to some other volume (dev's Redis, say) is none of this test's
        // business; one in a file that also names the scratch volume or its mount is.
        Pattern sizeLimitOverride = Pattern.compile("^\\s*sizeLimit\\s*:", Pattern.MULTILINE);
        try (Stream<Path> overlays = Files.walk(Path.of("k8s/overlays"))) {
            for (Path file : overlays.filter(ParquetScratchCeilingBudgetTest::isYaml).toList()) {
                String body = Files.readString(file);
                boolean touchesScratch = body.contains("parquet-scratch")
                        || body.contains(SCRATCH_MOUNT_PATH);
                assertFalse(ceilingOverride.matcher(body).find()
                                || (touchesScratch && sizeLimitOverride.matcher(body).find()),
                        file + " redefines a scratch ceiling, the batch concurrency it assumes, or "
                                + "the volume sizeLimit; theDeployedCeilingsRefuseBeforeTheScratchVolumeFills "
                                + "no longer covers the rendered configuration and must be widened to it");
            }
        }
    }

    /**
     * A deployed ceiling is read by Spring into a {@code long}, so it must be a plain byte count:
     * {@code "2Gi"} would fail the context, not merely this parse.
     */
    private static long deployedBytes(Map<String, Object> data, String key) {
        Object declared = data.get(key);
        assertNotNull(declared,
                key + " must be set beside the temp dirs: the application default (10 GiB) is "
                        + "above the volume, so without it the pod is evicted before the app refuses");
        String raw = String.valueOf(declared).trim();
        assertTrue(raw.matches("\\d+"),
                key + " must be a plain byte count — Spring binds it to a long, so a Kubernetes "
                        + "quantity like \"2Gi\" crashes the context on startup. Was: " + raw);
        long bytes = Long.parseLong(raw);
        assertTrue(bytes > 0, key + " must be positive, was " + bytes);
        return bytes;
    }

    /** The default in {@code application.yml}, for a key the deployment does not override. */
    private static long applicationDefault(String environmentVariable) throws IOException {
        Matcher matcher = Pattern.compile(Pattern.quote("${" + environmentVariable + ":") + "(\\d+)}")
                .matcher(applicationYaml());
        assertTrue(matcher.find(),
                "application.yml must declare a numeric default for " + environmentVariable);
        return Long.parseLong(matcher.group(1));
    }

    /**
     * The volume actually mounted at the scratch path, not merely the first bounded {@code
     * emptyDir} in the pod: a second one would otherwise be measured instead.
     */
    private static long scratchVolumeSizeLimitBytes() throws IOException {
        Map<String, Object> deployment = loadManifest("k8s/base/deployment-backend.yaml");
        Map<String, Object> podSpec = child(child(child(deployment, "spec"), "template"), "spec");

        Map<String, Map<String, Object>> volumesByName = new HashMap<>();
        for (Map<String, Object> volume : children(podSpec, "volumes")) {
            volumesByName.put(String.valueOf(volume.get("name")), volume);
        }
        for (Map<String, Object> container : children(podSpec, "containers")) {
            for (Map<String, Object> mount : children(container, "volumeMounts")) {
                if (!SCRATCH_MOUNT_PATH.equals(mount.get("mountPath"))) {
                    continue;
                }
                Map<String, Object> volume = volumesByName.get(String.valueOf(mount.get("name")));
                assertNotNull(volume, "no volume named " + mount.get("name") + " backs "
                        + SCRATCH_MOUNT_PATH);
                Object emptyDir = volume.get("emptyDir");
                assertTrue(emptyDir instanceof Map<?, ?>,
                        SCRATCH_MOUNT_PATH + " must be backed by an emptyDir, was " + volume);
                Object sizeLimit = ((Map<?, ?>) emptyDir).get("sizeLimit");
                assertNotNull(sizeLimit, "the scratch volume declares no sizeLimit — the deployed "
                        + "ceilings have nothing to sit below");
                return quantityToBytes(String.valueOf(sizeLimit));
            }
        }
        throw new AssertionError("nothing mounts " + SCRATCH_MOUNT_PATH + ", yet the ConfigMap "
                + "points the writers at it — they would land on the unbounded writable layer");
    }

    /** Kubernetes binary/decimal suffixes, enough of them for a volume budget. */
    private static long quantityToBytes(String quantity) {
        Map<String, Long> units = new LinkedHashMap<>();
        units.put("Ki", 1024L);
        units.put("Mi", 1024L * 1024);
        units.put("Gi", 1024L * 1024 * 1024);
        units.put("Ti", 1024L * 1024 * 1024 * 1024);
        units.put("k", 1000L);
        units.put("M", 1000_000L);
        units.put("G", 1000_000_000L);
        for (Map.Entry<String, Long> unit : units.entrySet()) {
            if (quantity.endsWith(unit.getKey())) {
                String amount = quantity.substring(0, quantity.length() - unit.getKey().length());
                return (long) (Double.parseDouble(amount.trim()) * unit.getValue());
            }
        }
        return Long.parseLong(quantity.trim());
    }

    private static String applicationYaml() throws IOException {
        return Files.readString(Path.of("src/main/resources/application.yml"));
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

    private static boolean isYaml(Path path) {
        String name = path.getFileName().toString();
        return Files.isRegularFile(path) && (name.endsWith(".yaml") || name.endsWith(".yml"));
    }
}
